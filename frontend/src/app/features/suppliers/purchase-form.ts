import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';

import { Api, Packaging, PaymentMethod, ProductDetail, SupplierDetail, errorMessage } from '../../core/api';
import { fcfa } from '../../core/format';

interface Line {
  product: ProductDetail;
  packaging: Packaging;
  quantity: number;
  /** Prix d'achat d'un conditionnement, en francs CFA. */
  unitCost: number;
}

const MAX_RESULTS = 8;

/** Saisie d'une livraison : les articles reçus, leur prix d'achat, et ce qui est payé tout de suite. */
@Component({
  selector: 'app-purchase-form',
  imports: [FormsModule, RouterLink],
  styles: `
    .picker {
      position: relative;
    }
    .found {
      list-style: none;
      margin: 0.5rem 0 0;
      padding: 0;
      display: flex;
      flex-direction: column;
      gap: 0.4rem;
    }
    .found li {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 0.75rem;
      flex-wrap: wrap;
      padding: 0.5rem 0.75rem;
      border: 1px solid var(--line);
      border-radius: 10px;
    }
    .summary {
      display: grid;
      grid-template-columns: 1fr auto;
      gap: 0.35rem 1.5rem;
      margin: 0;
    }
    .summary dd {
      margin: 0;
      text-align: right;
      font-variant-numeric: tabular-nums;
    }
    .summary .strong {
      font-weight: 700;
      font-size: 1.15rem;
    }
  `,
  template: `
    @if (supplier(); as s) {
      <div class="page-head">
        <h1>Achat chez {{ s.supplier.name }}</h1>
        <a class="button" [routerLink]="['/fournisseurs', s.supplier.id]">Annuler</a>
      </div>

      @if (error(); as message) {
        <p class="alert error" role="alert">{{ message }}</p>
      }

      <section class="card">
        <h2>Articles reçus</h2>
        <div class="picker">
          <input
            type="search"
            class="search"
            name="search"
            placeholder="Nom du produit ou code-barres"
            aria-label="Chercher un produit à ajouter"
            [ngModel]="search()"
            (ngModelChange)="search.set($event)"
          />
          @if (search().trim()) {
            <ul class="found">
              @for (p of results(); track p.product.id) {
                <li>
                  <span>{{ p.product.name }}</span>
                  <span class="actions">
                    @for (packaging of p.packagings; track packaging.id) {
                      <button type="button" class="button" (click)="addLine(p, packaging)">
                        {{ packaging.name }}
                      </button>
                    }
                  </span>
                </li>
              } @empty {
                <li class="muted">
                  Aucun produit ne correspond. <a routerLink="/produits/nouveau">Créer le produit</a>
                </li>
              }
            </ul>
          }
        </div>

        @if (lines().length) {
          <table class="table compact">
            <thead>
              <tr>
                <th>Article</th>
                <th>Quantité</th>
                <th>Prix d'achat de chacun (F)</th>
                <th class="num">Total</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              @for (line of lines(); track $index; let i = $index) {
                <tr>
                  <td>
                    {{ line.product.product.name }}
                    <div class="muted small">{{ line.packaging.name }}</div>
                  </td>
                  <td>
                    <input
                      class="narrow"
                      type="number"
                      min="1"
                      [ngModel]="line.quantity"
                      (ngModelChange)="update(i, { quantity: +$event })"
                      [attr.aria-label]="'Quantité de ' + line.product.product.name"
                    />
                  </td>
                  <td>
                    <input
                      class="narrow"
                      type="number"
                      min="0"
                      [ngModel]="line.unitCost"
                      (ngModelChange)="update(i, { unitCost: +$event })"
                      [attr.aria-label]="'Prix d\\'achat de ' + line.product.product.name"
                    />
                  </td>
                  <td class="num">{{ money(lineTotal(line)) }}</td>
                  <td>
                    <button
                      type="button"
                      class="button danger icon"
                      (click)="remove(i)"
                      [attr.aria-label]="'Retirer ' + line.product.product.name"
                    >
                      ✕
                    </button>
                  </td>
                </tr>
              }
            </tbody>
          </table>
        } @else {
          <p class="muted">Cherchez un produit ci-dessus, puis choisissez le conditionnement reçu.</p>
        }
      </section>

      <form class="card form" (ngSubmit)="save(s)">
        <h2>Paiement</h2>
        <div class="grid">
          <label>
            Payé à la livraison (F)
            <input name="paid" type="number" min="0" [max]="total()" [(ngModel)]="paid" />
          </label>
          <label>
            Moyen de paiement
            <select name="method" [(ngModel)]="method">
              <option value="ESPECES">Espèces</option>
              <option value="MOBILE_MONEY">Mobile Money</option>
            </select>
          </label>
          <label>
            Note (facultatif)
            <input name="note" [(ngModel)]="note" placeholder="Numéro du bon, remarque…" />
          </label>
        </div>
        @if (method === 'ESPECES' && paidAmount() > 0) {
          <label class="check">
            <input type="checkbox" name="fromCashDrawer" [(ngModel)]="fromCashDrawer" />
            L'argent sort du tiroir de la caisse (il comptera à la clôture)
          </label>
        }
        <div class="actions">
          <button type="button" class="button" (click)="paid = total()">Tout payer</button>
          <button type="button" class="button" (click)="paid = 0">Tout à crédit</button>
        </div>

        <dl class="summary">
          <dt>Total de l'achat</dt>
          <dd class="strong">{{ money(total()) }}</dd>
          <dt>Payé maintenant</dt>
          <dd>{{ money(paidAmount()) }}</dd>
          <dt>Restera dû à {{ s.supplier.name }}</dt>
          <dd class="strong">{{ money(total() - paidAmount()) }}</dd>
        </dl>

        <button type="submit" class="button primary big" [disabled]="saving() || lines().length === 0">
          {{ saving() ? 'Enregistrement…' : "Enregistrer l'achat et entrer le stock" }}
        </button>
      </form>
    } @else if (error(); as message) {
      <p class="alert error" role="alert">{{ message }}</p>
    } @else {
      <p class="muted">Chargement…</p>
    }
  `,
})
export class PurchaseForm {
  private readonly api = inject(Api);
  private readonly router = inject(Router);
  private readonly supplierId = inject(ActivatedRoute).snapshot.paramMap.get('id') ?? '';

  protected readonly supplier = signal<SupplierDetail | null>(null);
  protected readonly catalog = signal<ProductDetail[]>([]);
  protected readonly lines = signal<Line[]>([]);
  protected readonly search = signal('');
  protected readonly saving = signal(false);
  protected readonly error = signal<string | null>(null);

  protected paid: number | null = 0;
  protected method: PaymentMethod = 'ESPECES';
  protected fromCashDrawer = false;
  protected note = '';

  protected readonly money = fcfa;

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
  protected readonly total = computed(() => this.lines().reduce((sum, line) => sum + this.lineTotal(line), 0));

  constructor() {
    this.api.supplier(this.supplierId).subscribe({
      next: supplier => this.supplier.set(supplier),
      error: e => this.error.set(errorMessage(e)),
    });
    this.api.sellable().subscribe({
      next: catalog => this.catalog.set(catalog),
      error: e => this.error.set(errorMessage(e)),
    });
  }

  /** Le montant payé, borné entre zéro et le total, pour l'affichage du reste dû. */
  protected paidAmount(): number {
    const paid = Number(this.paid) || 0;
    return Math.min(Math.max(paid, 0), this.total());
  }

  protected lineTotal(line: Line): number {
    return (line.quantity || 0) * (line.unitCost || 0);
  }

  protected addLine(product: ProductDetail, packaging: Packaging): void {
    this.lines.update(lines => [...lines, { product, packaging, quantity: 1, unitCost: 0 }]);
    this.search.set('');
  }

  protected update(index: number, change: Partial<Line>): void {
    this.lines.update(lines => lines.map((line, i) => (i === index ? { ...line, ...change } : line)));
  }

  protected remove(index: number): void {
    this.lines.update(lines => lines.filter((_, i) => i !== index));
  }

  protected save(s: SupplierDetail): void {
    const lines = this.lines();
    const invalid = lines.find(
      line =>
        !Number.isInteger(line.quantity) || line.quantity <= 0 || !Number.isInteger(line.unitCost) || line.unitCost < 0,
    );
    if (invalid) {
      this.error.set(
        `Vérifiez la ligne « ${invalid.product.product.name} » : quantité et prix d'achat doivent être des nombres entiers.`,
      );
      return;
    }
    const paid = Number(this.paid) || 0;
    if (!Number.isInteger(paid) || paid < 0 || paid > this.total()) {
      this.error.set("Le montant payé doit être compris entre zéro et le total de l'achat.");
      return;
    }
    this.saving.set(true);
    this.error.set(null);
    this.api
      .createPurchase({
        supplierId: s.supplier.id,
        items: lines.map(line => ({
          packagingId: line.packaging.id,
          quantity: line.quantity,
          totalCost: this.lineTotal(line),
        })),
        paidAmount: paid,
        paidMethod: paid > 0 ? this.method : null,
        fromCashDrawer: paid > 0 && this.method === 'ESPECES' && this.fromCashDrawer,
        note: this.note.trim() || null,
      })
      .subscribe({
        next: () => this.router.navigate(['/fournisseurs', s.supplier.id]),
        error: e => {
          this.error.set(errorMessage(e));
          this.saving.set(false);
        },
      });
  }
}
