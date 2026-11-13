import { Component, ElementRef, computed, inject, signal, viewChild } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';

import {
  Api,
  Customer,
  Packaging,
  PaymentLine,
  ProductDetail,
  ProductSummary,
  Quote,
  Sale,
  errorMessage,
} from '../../core/api';
import { AuthState } from '../../core/auth-state';
import { PAYMENT_LABELS, dateTime, fcfa, newIdempotencyKey, quantity } from '../../core/format';
import { ReceiptView } from '../sales/receipt-view';

interface CartLine {
  product: ProductSummary;
  packaging: Packaging;
  quantity: number;
  unitPrice: number;
}

interface Receipt {
  sale: Sale;
  change: number;
  customerName: string | null;
}

const MAX_RESULTS = 20;

/** Prix proposé par défaut : le tarif détail s'il existe, sinon le plus élevé. */
function defaultPrice(packaging: Packaging): number {
  const detail = packaging.prices.find(p => p.level === 'DETAIL');
  return detail?.price ?? packaging.prices[0]?.price ?? 0;
}

/** Limite de négociation : le prix plancher le plus bas du conditionnement. */
function floorPrice(packaging: Packaging): number | null {
  return packaging.prices.length ? Math.min(...packaging.prices.map(p => p.floorPrice)) : null;
}

@Component({
  selector: 'app-pos',
  imports: [FormsModule, RouterLink, ReceiptView],
  template: `
    @if (receipt(); as r) {
      <app-receipt [sale]="r.sale" [customerName]="r.customerName" [change]="r.change" />
      <div class="actions receipt-actions no-print">
        <button class="button primary" (click)="newSale()">Nouvelle vente</button>
        <button class="button" (click)="print()">Imprimer le reçu</button>
      </div>
    } @else {
      <div class="pos">
        <section>
          <h1>Caisse</h1>
          <input
            #searchBox
            type="search"
            class="search"
            placeholder="Nom du produit ou code-barres"
            aria-label="Rechercher un produit par nom ou code-barres"
            autocomplete="off"
            [ngModel]="search()"
            (ngModelChange)="search.set($event)"
            (keydown.enter)="addFromSearch()"
          />

          @if (loading()) {
            <p class="muted">Chargement du catalogue…</p>
          } @else if (catalog().length === 0) {
            <div class="empty">
              <p>Aucun produit au catalogue.</p>
              <a class="button primary" routerLink="/produits/nouveau">Créer un produit</a>
            </div>
          } @else {
            <ul class="results">
              @for (entry of results(); track entry.product.id) {
                <li>
                  <div>
                    <strong>{{ entry.product.name }}</strong>
                    <span class="muted"> · stock : {{ qty(entry.product.stockQty) }} {{ entry.product.baseUnit }}</span>
                  </div>
                  <div class="inline">
                    @for (packaging of entry.packagings; track packaging.id) {
                      <button type="button" class="button" (click)="add(entry.product, packaging)">
                        {{ packaging.name }} · {{ money(priceOf(packaging)) }}
                      </button>
                    }
                  </div>
                </li>
              } @empty {
                <li class="muted">Aucun produit ne correspond.</li>
              }
            </ul>
          }
        </section>

        <section class="card ticket">
          <h2>Vente en cours</h2>
          @if (quote(); as q) {
            <p class="alert success">
              Devis n° {{ q.number }}{{ q.customerName ? ' — ' + q.customerName : '' }} : vérifiez les articles, puis
              encaissez. <button type="button" class="link" (click)="dropQuote()">Ne plus suivre ce devis</button>
            </p>
          }

          @if (cart().length === 0) {
            <p class="muted">Ajoutez un article pour commencer.</p>
          } @else {
            <table class="table compact">
              <thead>
                <tr>
                  <th>Article</th>
                  <th>Qté</th>
                  <th>Prix (F)</th>
                  <th class="num">Total</th>
                  <th></th>
                </tr>
              </thead>
              <tbody>
                @for (line of cart(); track line.packaging.id; let i = $index) {
                  <tr>
                    <td>
                      {{ line.product.name }}
                      <div class="muted small">{{ line.packaging.name }}</div>
                      @if (line.quantity * line.packaging.factor > line.product.stockQty) {
                        <div class="warning-text small">
                          Stock affiché insuffisant ({{ qty(line.product.stockQty) }} {{ line.product.baseUnit }})
                        </div>
                      }
                    </td>
                    <td>
                      <input
                        class="narrow"
                        type="number"
                        min="1"
                        [attr.aria-label]="'Quantité de ' + line.product.name"
                        [ngModel]="line.quantity"
                        (ngModelChange)="setQuantity(i, $event)"
                      />
                    </td>
                    <td>
                      <input
                        class="narrow"
                        type="number"
                        min="0"
                        [attr.aria-label]="'Prix de ' + line.product.name"
                        [ngModel]="line.unitPrice"
                        (ngModelChange)="setPrice(i, $event)"
                      />
                      @if (floorOf(line.packaging); as floor) {
                        <div class="small" [class.negative]="line.unitPrice < floor" [class.muted]="line.unitPrice >= floor">
                          plancher {{ money(floor) }}
                        </div>
                      }
                    </td>
                    <td class="num">{{ money(line.quantity * line.unitPrice) }}</td>
                    <td>
                      <button
                        type="button"
                        class="button danger icon"
                        [attr.aria-label]="'Retirer ' + line.product.name"
                        (click)="remove(i)"
                      >
                        ✕
                      </button>
                    </td>
                  </tr>
                }
              </tbody>
            </table>

            <label class="discount">
              Remise sur le total (F)
              <input
                class="narrow"
                type="number"
                min="0"
                [ngModel]="discount()"
                (ngModelChange)="setDiscount($event)"
              />
            </label>

            <p class="grand-total">
              Total
              <span>
                @if (discountAmount() > 0) {
                  <s class="muted">{{ money(subtotal()) }}</s>
                }
                <strong>{{ money(total()) }}</strong>
              </span>
            </p>

            <div class="grid two">
              <label>
                Espèces reçues (F)
                <input type="number" min="0" [ngModel]="cashReceived()" (ngModelChange)="setCash($event)" />
              </label>
              <label>
                Mobile Money (F)
                <input type="number" min="0" [ngModel]="mobileMoney()" (ngModelChange)="setMobileMoney($event)" />
              </label>
            </div>
            <button type="button" class="button" (click)="payAllInCash()">Tout en espèces</button>

            @if (change() > 0) {
              <p class="alert success">
                Monnaie à rendre : <strong>{{ money(change()) }}</strong>
              </p>
            }

            @if (credit() > 0) {
              <div class="credit">
                <p class="alert warning">
                  Reste à payer : <strong>{{ money(credit()) }}</strong>. Choisissez un client pour vendre à crédit.
                </p>
                <label>
                  Client
                  <select [ngModel]="customerId()" (ngModelChange)="setCustomer($event)">
                    <option [ngValue]="null">Choisir un client…</option>
                    @for (c of customers(); track c.id) {
                      <option [ngValue]="c.id">
                        {{ c.name }}{{ c.balance > 0 ? ' (doit déjà ' + money(c.balance) + ')' : '' }}
                      </option>
                    }
                  </select>
                </label>
                <div class="grid two">
                  <label>
                    Nouveau client
                    <input [(ngModel)]="newCustomerName" placeholder="Nom" />
                  </label>
                  <label>
                    Téléphone
                    <input [(ngModel)]="newCustomerPhone" placeholder="Facultatif" />
                  </label>
                </div>
                <button type="button" class="button" (click)="addCustomer()" [disabled]="!newCustomerName.trim()">
                  Ajouter ce client
                </button>
              </div>
            }
          }

          @if (error(); as message) {
            <p class="alert error" role="alert">{{ message }}</p>
          }

          <button
            type="button"
            class="button primary big"
            (click)="validate()"
            [disabled]="cart().length === 0 || saving()"
          >
            {{ saving() ? 'Enregistrement…' : 'Valider la vente' }}
          </button>

          @if (cart().length > 0 && !quote()) {
            @if (quoting()) {
              <div class="quote-box">
                <div class="grid two">
                  <label>
                    Nom du client (facultatif)
                    <input name="quoteCustomer" [(ngModel)]="quoteCustomer" />
                  </label>
                  <label>
                    Valable pendant (jours)
                    <input name="quoteDays" type="number" min="1" max="365" [(ngModel)]="quoteDays" />
                  </label>
                </div>
                <div class="actions">
                  <button type="button" class="button primary" (click)="saveQuote()" [disabled]="saving()">
                    Enregistrer le devis
                  </button>
                  <button type="button" class="button" (click)="quoting.set(false)">Annuler</button>
                </div>
              </div>
            } @else {
              <button type="button" class="button" (click)="quoting.set(true)">Faire un devis plutôt qu'une vente</button>
            }
          }
        </section>
      </div>
    }
  `,
})
export class Pos {
  private readonly api = inject(Api);
  private readonly auth = inject(AuthState);
  private readonly searchBox = viewChild<ElementRef<HTMLInputElement>>('searchBox');

  protected readonly catalog = signal<ProductDetail[]>([]);
  protected readonly customers = signal<Customer[]>([]);
  protected readonly loading = signal(true);
  protected readonly search = signal('');
  protected readonly cart = signal<CartLine[]>([]);
  protected readonly cashReceived = signal<number | null>(null);
  protected readonly mobileMoney = signal<number | null>(null);
  protected readonly customerId = signal<string | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly saving = signal(false);
  protected readonly receipt = signal<Receipt | null>(null);
  /** Remise sur le total, saisie en francs. */
  protected readonly discount = signal<number | null>(null);
  /** Le devis en cours de transformation en vente, s'il y en a un. */
  protected readonly quote = signal<Quote | null>(null);
  protected readonly quoting = signal(false);

  protected quoteCustomer = '';
  protected quoteDays = 15;

  protected newCustomerName = '';
  protected newCustomerPhone = '';

  /** Change à chaque modification de la vente : une même clé ne désigne qu'un seul contenu. */
  private idempotencyKey = newIdempotencyKey();

  protected readonly results = computed(() => {
    const term = this.search().trim().toLowerCase();
    const all = this.catalog();
    if (term === '') {
      return all.slice(0, MAX_RESULTS);
    }
    return all
      .filter(
        entry =>
          entry.product.name.toLowerCase().includes(term) ||
          entry.packagings.some(p => p.barcodes.some(code => code.startsWith(term))),
      )
      .slice(0, MAX_RESULTS);
  });

  protected readonly subtotal = computed(() => this.cart().reduce((sum, l) => sum + l.quantity * l.unitPrice, 0));
  /** La remise retenue : jamais négative, jamais plus que le sous-total. */
  protected readonly discountAmount = computed(() => Math.min(Math.max(this.discount() ?? 0, 0), this.subtotal()));
  protected readonly total = computed(() => this.subtotal() - this.discountAmount());
  protected readonly paid = computed(() => (this.cashReceived() ?? 0) + (this.mobileMoney() ?? 0));
  protected readonly change = computed(() => Math.max(0, this.paid() - this.total()));
  protected readonly credit = computed(() => Math.max(0, this.total() - this.paid()));

  protected readonly money = fcfa;
  protected readonly qty = quantity;
  protected readonly date = dateTime;
  protected readonly priceOf = defaultPrice;
  protected readonly floorOf = floorPrice;

  private readonly router = inject(Router);
  /** Identifiant du devis à transformer, passé par la page du devis. */
  private pendingQuoteId = inject(ActivatedRoute).snapshot.queryParamMap.get('devis');

  constructor() {
    this.loadCatalog();
    this.api.customers().subscribe({
      next: customers => this.customers.set(customers),
      error: e => this.error.set(errorMessage(e)),
    });
  }

  protected paymentLabel(method: string): string {
    return PAYMENT_LABELS[method] ?? method;
  }

  // ---------- Panier ----------

  protected add(product: ProductSummary, packaging: Packaging): void {
    this.changed();
    this.cart.update(lines => {
      const index = lines.findIndex(l => l.packaging.id === packaging.id);
      if (index >= 0) {
        return lines.map((l, i) => (i === index ? { ...l, quantity: l.quantity + 1 } : l));
      }
      return [...lines, { product, packaging, quantity: 1, unitPrice: defaultPrice(packaging) }];
    });
    this.search.set('');
    this.focusSearch();
  }

  /** Touche Entrée : un code-barres exact, ou un seul produit trouvé, part directement au panier. */
  protected addFromSearch(): void {
    const term = this.search().trim();
    if (term === '') {
      return;
    }
    for (const entry of this.catalog()) {
      const packaging = entry.packagings.find(p => p.barcodes.includes(term));
      if (packaging) {
        this.add(entry.product, packaging);
        return;
      }
    }
    const found = this.results();
    if (found.length === 1 && found[0].packagings.length > 0) {
      this.add(found[0].product, found[0].packagings[0]);
    }
  }

  protected setQuantity(index: number, value: number | null): void {
    this.changed();
    this.cart.update(lines => lines.map((l, i) => (i === index ? { ...l, quantity: value ?? 0 } : l)));
  }

  protected setPrice(index: number, value: number | null): void {
    this.changed();
    this.cart.update(lines => lines.map((l, i) => (i === index ? { ...l, unitPrice: value ?? 0 } : l)));
  }

  protected remove(index: number): void {
    this.changed();
    this.cart.update(lines => lines.filter((_, i) => i !== index));
  }

  // ---------- Encaissement ----------

  protected setCash(value: number | null): void {
    this.changed();
    this.cashReceived.set(value);
  }

  protected setMobileMoney(value: number | null): void {
    this.changed();
    this.mobileMoney.set(value);
  }

  protected setDiscount(value: number | null): void {
    this.changed();
    this.discount.set(value);
  }

  /** Enregistre le panier comme devis : ni stock ni caisse ne bougent. */
  protected saveQuote(): void {
    const problem = this.checkLines();
    if (problem) {
      this.error.set(problem);
      return;
    }
    const days = Number(this.quoteDays);
    if (!Number.isInteger(days) || days < 1 || days > 365) {
      this.error.set('La validité du devis va de 1 à 365 jours.');
      return;
    }
    this.error.set(null);
    this.saving.set(true);
    this.api
      .createQuote({
        customerId: this.customerId(),
        customerName: this.quoteCustomer.trim() || null,
        items: this.cart().map(l => ({ packagingId: l.packaging.id, quantity: l.quantity, unitPrice: l.unitPrice })),
        discount: this.discountAmount(),
        validDays: days,
        note: null,
      })
      .subscribe({
        next: quote => {
          this.saving.set(false);
          this.router.navigate(['/devis', quote.id]);
        },
        error: e => {
          this.error.set(errorMessage(e));
          this.saving.set(false);
        },
      });
  }

  /** Garde le panier, mais la vente ne sera plus rattachée au devis. */
  protected dropQuote(): void {
    this.quote.set(null);
    this.router.navigate([], { queryParams: {} });
  }

  protected setCustomer(id: string | null): void {
    this.changed();
    this.customerId.set(id);
  }

  protected payAllInCash(): void {
    this.changed();
    this.mobileMoney.set(null);
    this.cashReceived.set(this.total());
  }

  protected addCustomer(): void {
    const name = this.newCustomerName.trim();
    if (!name) {
      return;
    }
    this.api.createCustomer(name, this.newCustomerPhone.trim()).subscribe({
      next: customer => {
        this.customers.update(list => [...list, customer].sort((a, b) => a.name.localeCompare(b.name)));
        this.setCustomer(customer.id);
        this.newCustomerName = '';
        this.newCustomerPhone = '';
        this.error.set(null);
      },
      error: e => this.error.set(errorMessage(e)),
    });
  }

  protected validate(): void {
    const problem = this.check();
    if (problem) {
      this.error.set(problem);
      return;
    }
    const total = this.total();
    const mobileMoney = this.mobileMoney() ?? 0;
    // Les espèces gardées en caisse : ce qui est reçu, moins la monnaie rendue.
    const cashKept = Math.min(this.cashReceived() ?? 0, total - mobileMoney);
    const payments: PaymentLine[] = [];
    if (cashKept > 0) {
      payments.push({ method: 'ESPECES', amount: cashKept });
    }
    if (mobileMoney > 0) {
      payments.push({ method: 'MOBILE_MONEY', amount: mobileMoney });
    }
    const change = this.change();
    const customerId = this.credit() > 0 ? this.customerId() : null;

    this.error.set(null);
    this.saving.set(true);
    this.api
      .createSale({
        idempotencyKey: this.idempotencyKey,
        customerId,
        items: this.cart().map(l => ({
          packagingId: l.packaging.id,
          quantity: l.quantity,
          unitPrice: l.unitPrice,
        })),
        payments,
        discount: this.discountAmount(),
        quoteId: this.quote()?.id ?? null,
      })
      .subscribe({
        next: sale => {
          const customerName = this.customers().find(c => c.id === sale.customerId)?.name ?? null;
          this.receipt.set({ sale, change, customerName });
          this.saving.set(false);
          if (this.quote()) {
            this.router.navigate([], { queryParams: {} });
          }
          this.reset();
          this.loadCatalog();
        },
        error: e => {
          this.error.set(errorMessage(e));
          this.saving.set(false);
        },
      });
  }

  protected newSale(): void {
    this.receipt.set(null);
    this.focusSearch();
  }

  protected print(): void {
    window.print();
  }

  private check(): string | null {
    const problem = this.checkLines();
    if (problem) {
      return problem;
    }
    const cash = this.cashReceived() ?? 0;
    const mobileMoney = this.mobileMoney() ?? 0;
    if (cash < 0 || mobileMoney < 0 || !Number.isInteger(cash) || !Number.isInteger(mobileMoney)) {
      return 'Les montants encaissés doivent être des francs entiers positifs.';
    }
    if (mobileMoney > this.total()) {
      return 'Le montant Mobile Money dépasse le total de la vente.';
    }
    if (this.credit() > 0 && !this.customerId()) {
      return `Il reste ${fcfa(this.credit())} à payer : choisissez un client pour vendre à crédit.`;
    }
    return null;
  }

  /** Contrôles communs à la vente et au devis : quantités, prix, remise. */
  private checkLines(): string | null {
    for (const line of this.cart()) {
      if (!Number.isInteger(line.quantity) || line.quantity < 1) {
        return `Indiquez une quantité entière d'au moins 1 pour « ${line.product.name} ».`;
      }
      if (!Number.isInteger(line.unitPrice) || line.unitPrice < 0) {
        return `Indiquez un prix en francs entiers pour « ${line.product.name} ».`;
      }
      const floor = floorPrice(line.packaging);
      // Seul le propriétaire peut vendre sous le prix plancher ; l'opération est inscrite au journal.
      if (floor != null && line.unitPrice < floor && !this.auth.isOwner()) {
        return `Le prix de « ${line.product.name} » est sous le prix plancher de ${fcfa(floor)}.`;
      }
    }
    const discount = this.discount() ?? 0;
    if (!Number.isInteger(discount) || discount < 0) {
      return 'La remise doit être un montant en francs entiers, sans signe moins.';
    }
    if (discount > this.subtotal()) {
      return `La remise dépasse le total de la vente (${fcfa(this.subtotal())}).`;
    }
    return null;
  }

  /** Remplit le panier avec les articles d'un devis encore ouvert. */
  private loadQuote(quoteId: string): void {
    this.api.quote(quoteId).subscribe({
      next: quote => {
        if (quote.status !== 'OUVERT') {
          this.error.set(`Le devis n° ${quote.number} n'est plus ouvert : il a déjà été transformé ou annulé.`);
          return;
        }
        const lines: CartLine[] = [];
        const missing: string[] = [];
        for (const item of quote.items) {
          const entry = this.catalog().find(e => e.packagings.some(p => p.id === item.packagingId));
          const packaging = entry?.packagings.find(p => p.id === item.packagingId);
          if (entry && packaging) {
            lines.push({ product: entry.product, packaging, quantity: item.quantity, unitPrice: item.unitPrice });
          } else {
            missing.push(item.productName);
          }
        }
        this.cart.set(lines);
        this.discount.set(quote.discount || null);
        this.customerId.set(quote.customerId);
        this.quote.set(quote);
        this.idempotencyKey = newIdempotencyKey();
        this.error.set(
          missing.length
            ? `Ces articles du devis ne sont plus au catalogue et n'ont pas été repris : ${missing.join(', ')}.`
            : null,
        );
      },
      error: e => this.error.set(errorMessage(e)),
    });
  }

  private changed(): void {
    this.idempotencyKey = newIdempotencyKey();
    this.error.set(null);
  }

  private reset(): void {
    this.cart.set([]);
    this.cashReceived.set(null);
    this.mobileMoney.set(null);
    this.customerId.set(null);
    this.discount.set(null);
    this.quote.set(null);
    this.quoting.set(false);
    this.quoteCustomer = '';
    this.search.set('');
    this.idempotencyKey = newIdempotencyKey();
    // Les soldes des clients ont pu changer avec cette vente.
    this.api.customers().subscribe({ next: customers => this.customers.set(customers) });
  }

  private loadCatalog(): void {
    this.api.sellable().subscribe({
      next: catalog => {
        this.catalog.set(catalog);
        this.loading.set(false);
        if (this.pendingQuoteId) {
          this.loadQuote(this.pendingQuoteId);
          this.pendingQuoteId = null;
        }
      },
      error: e => {
        this.error.set(errorMessage(e));
        this.loading.set(false);
      },
    });
  }

  private focusSearch(): void {
    setTimeout(() => this.searchBox()?.nativeElement.focus());
  }
}
