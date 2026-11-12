import { Component, computed, inject, input } from '@angular/core';

import { Sale, ShopSettings } from '../../core/api';
import { PAYMENT_LABELS, dateTime, fcfa, quantity } from '../../core/format';
import { ShopState } from '../../core/shop-state';

/**
 * Le reçu d'une vente, à l'écran et à l'impression.
 * À l'impression, il prend la largeur du rouleau choisi dans la page Boutique (58 mm, 80 mm) ou la feuille A4.
 */
@Component({
  selector: 'app-receipt',
  styles: `
    .head {
      text-align: center;
      margin-bottom: 0.75rem;
    }
    .head p {
      margin: 0;
    }
    .shop-name {
      font-size: 1.2rem;
      font-weight: 700;
    }
    h1 {
      font-size: 1.1rem;
      margin: 0;
    }
    .meta {
      margin: 0;
    }
    .void {
      margin: 0.5rem 0;
      padding: 0.3rem;
      border: 2px solid currentColor;
      text-align: center;
      font-weight: 700;
    }
    .lines {
      margin: 0.75rem 0;
      padding: 0.5rem 0;
      border-top: 1px dashed currentColor;
      border-bottom: 1px dashed currentColor;
    }
    .line + .line {
      margin-top: 0.4rem;
    }
    .figures {
      display: flex;
      justify-content: space-between;
      gap: 1rem;
      font-variant-numeric: tabular-nums;
    }
    .totals {
      margin: 0.5rem 0;
    }
    .foot {
      text-align: center;
      margin: 0.75rem 0 0;
    }
    .foot p {
      margin: 0.2rem 0 0;
    }
  `,
  template: `
    <section class="receipt card" [class.thermal]="width() !== 'A4'" [class.w58]="width() === '58'">
      @if (shop(); as s) {
        @if (s.name || s.address || s.phone) {
          <div class="head">
            @if (s.name) {
              <p class="shop-name">{{ s.name }}</p>
            }
            @if (s.address) {
              <p>{{ s.address }}</p>
            }
            @if (s.phone) {
              <p>Tél. : {{ s.phone }}</p>
            }
          </div>
        }
      }

      <h1>Reçu n° {{ sale().number }}</h1>
      <p class="meta">{{ date(sale().createdAt) }}</p>
      @if (customerName()) {
        <p class="meta">Client : {{ customerName() }}</p>
      }
      @if (sale().status === 'ANNULEE') {
        <p class="void">VENTE ANNULÉE</p>
      }

      <div class="lines">
        @for (item of sale().items; track $index) {
          <div class="line">
            <div>{{ item.productName }} ({{ item.packagingName }})</div>
            <div class="figures">
              <span>{{ qty(item.quantity) }} × {{ money(item.unitPrice) }}</span>
              <span>{{ money(item.lineTotal) }}</span>
            </div>
          </div>
        }
      </div>

      <dl class="totals">
        <dt>Total</dt>
        <dd class="strong">{{ money(sale().total) }}</dd>
        @for (p of sale().payments; track $index) {
          <dt>Payé en {{ paymentLabel(p.method) }}</dt>
          <dd>{{ money(p.amount) }}</dd>
        }
        @if (change() > 0) {
          <dt>Monnaie rendue</dt>
          <dd>{{ money(change()) }}</dd>
        }
        @if (sale().credit > 0) {
          <dt>Reste dû (crédit)</dt>
          <dd class="strong">{{ money(sale().credit) }}</dd>
        }
      </dl>

      <div class="foot">
        @if (shop()?.receiptFooter; as footer) {
          <p>{{ footer }}</p>
        }
        <p class="small">Reçu interne. Ce document ne tient pas lieu de facture.</p>
      </div>
    </section>
  `,
})
export class ReceiptView {
  private readonly shopState = inject(ShopState);

  readonly sale = input.required<Sale>();
  readonly customerName = input<string | null>(null);
  /** Monnaie rendue au client : connue seulement au moment de l'encaissement. */
  readonly change = input(0);
  /** Réglages à afficher à la place de ceux enregistrés : sert à l'aperçu de la page Boutique. */
  readonly preview = input<ShopSettings | null>(null);

  protected readonly shop = computed(() => this.preview() ?? this.shopState.settings());
  protected readonly width = computed(() => this.shop()?.receiptWidth ?? '80');

  protected readonly money = fcfa;
  protected readonly qty = quantity;
  protected readonly date = dateTime;

  constructor() {
    this.shopState.ensureLoaded();
  }

  protected paymentLabel(method: string): string {
    return PAYMENT_LABELS[method] ?? method;
  }
}
