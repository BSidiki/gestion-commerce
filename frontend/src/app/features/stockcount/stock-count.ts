import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';

import { Api, ProductDetail, StockCountDetail, StockCountSummary, errorMessage } from '../../core/api';
import { AuthState } from '../../core/auth-state';
import { dateTime, fcfa, quantity } from '../../core/format';

const MAX_RESULTS = 8;
const STATUS_LABELS: Record<string, string> = {
  EN_COURS: 'En cours',
  VALIDE: 'Validé',
  ABANDONNE: 'Abandonné',
};

/** Inventaire physique : compter le stock réel, voir les écarts, puis les valider en une fois. */
@Component({
  selector: 'app-stock-count',
  imports: [FormsModule],
  styles: `
    .found {
      list-style: none;
      margin: 0.5rem 0 0;
      padding: 0;
      display: flex;
      flex-direction: column;
      gap: 0.4rem;
    }
    .found button {
      width: 100%;
      justify-content: space-between;
      font-weight: 500;
    }
    .entry {
      margin-top: 1rem;
      padding: 1rem;
      border-radius: 12px;
      background: var(--accent-soft);
    }
    .entry h3 {
      margin: 0 0 0.75rem;
      font-size: 1.05rem;
    }
    .entry .grid {
      grid-template-columns: repeat(auto-fit, minmax(9rem, 1fr));
    }
    .entry .total {
      margin: 0 0 0.75rem;
    }
    .gap.minus {
      color: var(--error-ink);
      font-weight: 600;
    }
    .gap.plus {
      color: var(--success-ink);
      font-weight: 600;
    }
  `,
  template: `
    <div class="page-head">
      <h1>Inventaire</h1>
    </div>

    @if (error(); as message) {
      <p class="alert error" role="alert">{{ message }}</p>
    }
    @if (notice(); as message) {
      <p class="alert success" role="status">{{ message }}</p>
    }

    @if (current(); as c) {
      <p class="muted">
        Inventaire n° {{ c.count.number }}{{ c.count.label ? ' — ' + c.count.label : '' }}, commencé le
        {{ date(c.count.startedAt) }}. Les ventes peuvent continuer pendant le comptage.
      </p>

      <div class="stats">
        <div class="stat">
          <span class="label">Produits comptés</span>
          <span class="value">{{ c.count.lineCount }}</span>
          <span class="muted">sur {{ catalog().length }} au catalogue</span>
        </div>
        <div class="stat">
          <span class="label">Écarts</span>
          <span class="value">{{ c.count.differenceCount }}</span>
          <span class="muted">produit(s) dont le compte diffère</span>
        </div>
        <div class="stat">
          <span class="label">Marchandise manquante</span>
          <span class="value" [class.negative]="c.count.missingValue > 0">{{ money(c.count.missingValue) }}</span>
          <span class="muted">en trop : {{ money(c.count.surplusValue) }}</span>
        </div>
      </div>

      <section class="card">
        <h2>Compter un produit</h2>
        <input
          type="search"
          class="search"
          name="search"
          placeholder="Nom du produit ou code-barres"
          aria-label="Chercher le produit à compter"
          [ngModel]="search()"
          (ngModelChange)="search.set($event)"
        />
        @if (search().trim() && !picked()) {
          <ul class="found">
            @for (p of results(); track p.product.id) {
              <li>
                <button type="button" class="button" (click)="pick(p)">
                  <span>{{ p.product.name }}</span>
                  <span class="muted small">{{ isCounted(c, p) ? 'déjà compté' : 'à compter' }}</span>
                </button>
              </li>
            } @empty {
              <li class="muted">Aucun produit ne correspond.</li>
            }
          </ul>
        }

        @if (picked(); as p) {
          <form class="entry" (ngSubmit)="save(c)">
            <h3>{{ p.product.name }}</h3>
            <div class="grid">
              @for (packaging of p.packagings; track packaging.id; let i = $index) {
                <label>
                  {{ packaging.name }}{{ packaging.factor > 1 ? ' (' + packaging.factor + ' ' + p.product.baseUnit + ')' : '' }}
                  <input
                    type="number"
                    min="0"
                    [name]="'qty' + i"
                    [ngModel]="counts()[i]"
                    (ngModelChange)="setCount(i, $event)"
                  />
                </label>
              }
            </div>
            <p class="total">
              Total compté : <strong>{{ qty(countedTotal()) }} {{ p.product.baseUnit }}</strong>
            </p>
            <div class="actions">
              <button type="submit" class="button primary" [disabled]="busy()">Enregistrer le comptage</button>
              <button type="button" class="button" (click)="unpick()">Annuler</button>
            </div>
          </form>
        }
      </section>

      <div class="toolbar">
        <label class="check">
          <input type="checkbox" [ngModel]="gapsOnly()" (ngModelChange)="gapsOnly.set($event)" />
          Afficher seulement les écarts
        </label>
      </div>
      <table class="table">
        <thead>
          <tr>
            <th>Produit compté</th>
            <th class="num">Logiciel</th>
            <th class="num">Compté</th>
            <th class="num">Écart</th>
            <th class="num">Valeur de l'écart</th>
            <th></th>
          </tr>
        </thead>
        <tbody>
          @for (line of shownLines(); track line.productId) {
            <tr>
              <td>{{ line.productName }}</td>
              <td class="num">{{ qty(line.expectedQty) }} {{ line.baseUnit }}</td>
              <td class="num">{{ qty(line.countedQty) }} {{ line.baseUnit }}</td>
              <td class="num">
                <span class="gap" [class.minus]="line.difference < 0" [class.plus]="line.difference > 0">
                  {{ signed(line.difference) }}
                </span>
              </td>
              <td class="num">{{ line.difference === 0 ? '—' : money(line.valueDifference) }}</td>
              <td class="row-actions">
                <button type="button" class="button" (click)="recount(line.productId)">Recompter</button>
                <button type="button" class="button danger" (click)="remove(c, line.productId)" [disabled]="busy()">
                  Retirer
                </button>
              </td>
            </tr>
          } @empty {
            <tr>
              <td colspan="6" class="muted">
                {{ c.lines.length ? 'Aucun écart pour le moment.' : 'Aucun produit compté pour le moment.' }}
              </td>
            </tr>
          }
        </tbody>
      </table>

      <section class="card" style="margin-top: 1.1rem">
        <h2>Terminer l'inventaire</h2>
        <p class="muted">
          Valider corrige le stock des {{ c.count.differenceCount }} produit(s) en écart. Les produits non comptés
          ne sont pas touchés. Une fois validé, l'inventaire ne se modifie plus.
        </p>
        @if (!auth.isOwner()) {
          <p class="alert warning">Seul le propriétaire peut valider un inventaire, car cela corrige le stock.</p>
        }
        <div class="actions">
          @if (auth.isOwner()) {
            @if (confirming()) {
              <button type="button" class="button primary" (click)="validate(c)" [disabled]="busy()">
                Oui, corriger le stock de {{ c.count.differenceCount }} produit(s)
              </button>
              <button type="button" class="button" (click)="confirming.set(false)">Pas encore</button>
            } @else {
              <button
                type="button"
                class="button primary"
                (click)="confirming.set(true)"
                [disabled]="busy() || c.lines.length === 0"
              >
                Valider l'inventaire
              </button>
            }
          }
          <button type="button" class="button danger" (click)="abandon(c)" [disabled]="busy()">
            Abandonner sans rien corriger
          </button>
        </div>
      </section>
    } @else if (!loading()) {
      <form class="card form" (ngSubmit)="start()">
        <h2>Commencer un inventaire</h2>
        <p class="muted">
          Comptez ce qui est réellement en rayon ; le logiciel montre les écarts avec son stock, puis les corrige
          quand vous validez. Vous pouvez compter toute la boutique ou seulement une partie.
        </p>
        <label>
          Ce que vous comptez (facultatif)
          <input name="label" [(ngModel)]="label" placeholder="Rayon boissons, réserve, toute la boutique…" />
        </label>
        <div class="actions">
          <button type="submit" class="button primary" [disabled]="busy()">Commencer l'inventaire</button>
        </div>
      </form>
    }

    @if (history().length) {
      <h2 style="margin-top: 2rem">Inventaires précédents</h2>
      <table class="table">
        <thead>
          <tr>
            <th>N°</th>
            <th>Commencé le</th>
            <th>Quoi</th>
            <th>État</th>
            <th class="num">Comptés</th>
            <th class="num">Écarts</th>
            <th class="num">Manquant</th>
            <th class="num">En trop</th>
          </tr>
        </thead>
        <tbody>
          @for (h of history(); track h.id) {
            <tr [class.cancelled]="h.status === 'ABANDONNE'">
              <td>{{ h.number }}</td>
              <td>{{ date(h.startedAt) }}</td>
              <td>{{ h.label || '—' }}</td>
              <td>{{ statusLabel(h.status) }}</td>
              <td class="num">{{ h.lineCount }}</td>
              <td class="num">{{ h.differenceCount }}</td>
              <td class="num">{{ money(h.missingValue) }}</td>
              <td class="num">{{ money(h.surplusValue) }}</td>
            </tr>
          }
        </tbody>
      </table>
    }
  `,
})
export class StockCountPage {
  private readonly api = inject(Api);
  protected readonly auth = inject(AuthState);

  protected readonly current = signal<StockCountDetail | null>(null);
  protected readonly history = signal<StockCountSummary[]>([]);
  protected readonly catalog = signal<ProductDetail[]>([]);
  protected readonly loading = signal(true);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly notice = signal<string | null>(null);
  protected readonly search = signal('');
  protected readonly picked = signal<ProductDetail | null>(null);
  /** Quantité saisie pour chaque conditionnement du produit choisi. */
  protected readonly counts = signal<(number | null)[]>([]);
  protected readonly gapsOnly = signal(false);
  protected readonly confirming = signal(false);

  protected label = '';

  protected readonly money = fcfa;
  protected readonly qty = quantity;
  protected readonly date = dateTime;

  protected readonly results = computed(() => {
    const term = this.search().trim().toLowerCase();
    return this.catalog()
      .filter(
        p =>
          p.product.name.toLowerCase().includes(term) ||
          p.packagings.some(packaging => packaging.barcodes.includes(term)),
      )
      .slice(0, MAX_RESULTS);
  });
  /** Le total compté, en unités de base : chaque conditionnement multiplié par son contenu. */
  protected readonly countedTotal = computed(() => {
    const product = this.picked();
    return product
      ? product.packagings.reduce((sum, packaging, i) => sum + (Number(this.counts()[i]) || 0) * packaging.factor, 0)
      : 0;
  });
  protected readonly shownLines = computed(() => {
    const lines = this.current()?.lines ?? [];
    return this.gapsOnly() ? lines.filter(line => line.difference !== 0) : lines;
  });

  constructor() {
    this.api.sellable().subscribe({
      next: catalog => this.catalog.set(catalog),
      error: e => this.error.set(errorMessage(e)),
    });
    this.reload();
  }

  protected statusLabel(status: string): string {
    return STATUS_LABELS[status] ?? status;
  }

  protected signed(value: number): string {
    return value > 0 ? `+${quantity(value)}` : value < 0 ? `−${quantity(-value)}` : '0';
  }

  protected isCounted(c: StockCountDetail, p: ProductDetail): boolean {
    return c.lines.some(line => line.productId === p.product.id);
  }

  protected pick(product: ProductDetail): void {
    this.picked.set(product);
    this.counts.set(product.packagings.map(() => null));
    this.notice.set(null);
  }

  protected recount(productId: string): void {
    const product = this.catalog().find(p => p.product.id === productId);
    if (product) {
      this.pick(product);
      window.scrollTo({ top: 0 });
    } else {
      this.error.set('Ce produit est désactivé : réactivez-le pour le recompter.');
    }
  }

  protected unpick(): void {
    this.picked.set(null);
    this.search.set('');
  }

  protected setCount(index: number, value: number | null): void {
    this.counts.update(counts => counts.map((count, i) => (i === index ? value : count)));
  }

  protected start(): void {
    this.run(this.api.startStockCount(this.label.trim() || null), () => {
      this.label = '';
      return null;
    });
  }

  protected save(c: StockCountDetail): void {
    const product = this.picked();
    if (!product) {
      return;
    }
    if (this.counts().some(count => count !== null && (!Number.isInteger(Number(count)) || Number(count) < 0))) {
      this.error.set('Les quantités doivent être des nombres entiers, sans signe moins.');
      return;
    }
    const name = product.product.name;
    this.run(this.api.countProduct(c.count.id, product.product.id, this.countedTotal()), () => {
      this.unpick();
      return `${name} : comptage enregistré.`;
    });
  }

  protected remove(c: StockCountDetail, productId: string): void {
    this.run(this.api.removeCountLine(c.count.id, productId), () => null);
  }

  protected validate(c: StockCountDetail): void {
    this.run(this.api.validateStockCount(c.count.id), detail => {
      this.confirming.set(false);
      return `Inventaire n° ${detail.count.number} validé : le stock de ${detail.count.differenceCount} produit(s) a été corrigé.`;
    });
  }

  protected abandon(c: StockCountDetail): void {
    this.run(this.api.abandonStockCount(c.count.id), detail => {
      this.confirming.set(false);
      return `Inventaire n° ${detail.count.number} abandonné : le stock n'a pas été modifié.`;
    });
  }

  /** Envoie une opération, puis affiche l'inventaire tel que le service le renvoie. */
  private run(
    request: ReturnType<Api['startStockCount']>,
    after: (detail: StockCountDetail) => string | null,
  ): void {
    this.busy.set(true);
    this.error.set(null);
    this.notice.set(null);
    request.subscribe({
      next: detail => {
        this.busy.set(false);
        this.notice.set(after(detail));
        if (detail.count.status === 'EN_COURS') {
          this.current.set(detail);
        } else {
          this.current.set(null);
          this.reload();
        }
      },
      error: e => {
        this.busy.set(false);
        this.error.set(errorMessage(e));
      },
    });
  }

  private reload(): void {
    this.api.stockCounts().subscribe({
      next: counts => {
        this.history.set(counts.filter(count => count.status !== 'EN_COURS'));
        const running = counts.find(count => count.status === 'EN_COURS');
        if (!running) {
          this.loading.set(false);
          return;
        }
        this.api.stockCount(running.id).subscribe({
          next: detail => {
            this.current.set(detail);
            this.loading.set(false);
          },
          error: e => {
            this.error.set(errorMessage(e));
            this.loading.set(false);
          },
        });
      },
      error: e => {
        this.error.set(errorMessage(e));
        this.loading.set(false);
      },
    });
  }
}
