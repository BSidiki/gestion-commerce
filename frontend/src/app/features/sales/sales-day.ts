import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { forkJoin, of } from 'rxjs';

import { Api, Customer, DailyReport, Sale, errorMessage } from '../../core/api';
import { AuthState } from '../../core/auth-state';
import { PAYMENT_LABELS, dateTime, fcfa, quantity } from '../../core/format';
import { ReceiptView } from './receipt-view';

@Component({
  selector: 'app-sales-day',
  imports: [FormsModule, RouterLink, ReceiptView],
  template: `
    @if (receiptOf(); as shown) {
      <app-receipt [sale]="shown" [customerName]="customerName(shown)" />
      <div class="actions receipt-actions no-print">
        <button type="button" class="button primary" (click)="print()">Imprimer le reçu</button>
        <button type="button" class="button" (click)="receiptOf.set(null)">
          Retour aux ventes
        </button>
      </div>
    } @else {
      <div class="page-head">
        <h1>Ventes du jour</h1>
        <a class="button primary" routerLink="/">Nouvelle vente</a>
      </div>

      @if (error(); as message) {
        <p class="alert error" role="alert">{{ message }}</p>
      }
      @if (notice(); as message) {
        <p class="alert success" role="status">{{ message }}</p>
      }

      @if (report(); as r) {
        <div class="stats">
          <div class="stat">
            <span class="label">Chiffre d'affaires</span>
            <span class="value">{{ money(r.revenue) }}</span>
            <span class="muted">{{ r.saleCount }} vente(s)</span>
          </div>
          <div class="stat">
            <span class="label">Marge brute</span>
            <span class="value">{{ money(r.grossMargin) }}</span>
            <span class="muted">coût des marchandises : {{ money(r.costOfGoods) }}</span>
          </div>
          <div class="stat">
            <span class="label">Encaissé en espèces</span>
            <span class="value">{{ money(r.cash) }}</span>
          </div>
          <div class="stat">
            <span class="label">Encaissé en Mobile Money</span>
            <span class="value">{{ money(r.mobileMoney) }}</span>
          </div>
          <div class="stat">
            <span class="label">Crédits accordés</span>
            <span class="value">{{ money(r.creditGranted) }}</span>
            <span class="muted">remboursements reçus : {{ money(r.repaymentsReceived) }}</span>
          </div>
          <div class="stat">
            <span class="label">Résultat estimé</span>
            <span class="value" [class.negative]="r.estimatedResult < 0">{{
              money(r.estimatedResult)
            }}</span>
            <span class="muted">après {{ money(r.expenses) }} de dépenses</span>
          </div>
        </div>
      }

      @if (loading()) {
        <p class="muted">Chargement…</p>
      } @else {
        <table class="table">
          <thead>
            <tr>
              <th>N°</th>
              <th>Heure</th>
              <th>Articles</th>
              <th class="num">Total</th>
              <th>Paiement</th>
              <th>État</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            @for (sale of sales(); track sale.id) {
              <tr [class.cancelled]="sale.status === 'ANNULEE'">
                <td>{{ sale.number }}</td>
                <td>{{ date(sale.createdAt) }}</td>
                <td>
                  @for (item of sale.items; track $index) {
                    <div>
                      {{ qty(item.quantity) }} × {{ item.productName }} ({{ item.packagingName }})
                    </div>
                  }
                </td>
                <td class="num">
                {{ money(sale.total) }}
                @if (sale.discount > 0) {
                  <div class="muted small">remise de {{ money(sale.discount) }}</div>
                }
              </td>
                <td>
                  @for (p of sale.payments; track $index) {
                    <div>{{ paymentLabel(p.method) }} : {{ money(p.amount) }}</div>
                  }
                  @if (sale.credit > 0) {
                    <div>Crédit : {{ money(sale.credit) }}</div>
                  }
                </td>
                <td>
                  @if (sale.status === 'ANNULEE') {
                    <span class="badge warning">Annulée</span>
                    <div class="muted small">{{ sale.cancelReason }}</div>
                  } @else {
                    Validée
                  }
                </td>
                <td class="row-actions">
                  <button type="button" class="button" (click)="receiptOf.set(sale)">Reçu</button>
                  @if (auth.isOwner() && sale.status === 'VALIDEE' && cancelling() !== sale.id) {
                    <button type="button" class="button danger" (click)="startCancel(sale.id)">
                      Annuler
                    </button>
                  }
                </td>
              </tr>
              @if (cancelling() === sale.id) {
                <tr>
                  <td colspan="7">
                    <div class="inline">
                      <input
                        [(ngModel)]="cancelReason"
                        placeholder="Motif de l'annulation (obligatoire)"
                        aria-label="Motif de l'annulation"
                      />
                      <button
                        type="button"
                        class="button danger"
                        (click)="confirmCancel(sale)"
                        [disabled]="!cancelReason.trim() || saving()"
                      >
                        Confirmer l'annulation de la vente n° {{ sale.number }}
                      </button>
                      <button type="button" class="button" (click)="cancelling.set(null)">
                        Garder la vente
                      </button>
                    </div>
                  </td>
                </tr>
              }
            } @empty {
              <tr>
                <td colspan="7" class="muted">Aucune vente aujourd'hui.</td>
              </tr>
            }
          </tbody>
        </table>
      }
    }
  `,
})
export class SalesDay {
  private readonly api = inject(Api);
  protected readonly auth = inject(AuthState);

  protected readonly report = signal<DailyReport | null>(null);
  protected readonly sales = signal<Sale[]>([]);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);
  protected readonly notice = signal<string | null>(null);
  protected readonly cancelling = signal<string | null>(null);
  protected readonly saving = signal(false);
  /** La vente dont on affiche le reçu, pour le réimprimer. */
  protected readonly receiptOf = signal<Sale | null>(null);
  private readonly customers = signal<Customer[]>([]);
  protected cancelReason = '';

  protected readonly money = fcfa;
  protected readonly qty = quantity;
  protected readonly date = dateTime;

  constructor() {
    this.reload();
  }

  protected paymentLabel(method: string): string {
    return PAYMENT_LABELS[method] ?? method;
  }

  protected customerName(sale: Sale): string | null {
    return this.customers().find((c) => c.id === sale.customerId)?.name ?? null;
  }

  protected print(): void {
    window.print();
  }

  protected startCancel(saleId: string): void {
    this.cancelReason = '';
    this.notice.set(null);
    this.error.set(null);
    this.cancelling.set(saleId);
  }

  protected confirmCancel(sale: Sale): void {
    const reason = this.cancelReason.trim();
    if (!reason) {
      return;
    }
    this.saving.set(true);
    this.api.cancelSale(sale.id, reason).subscribe({
      next: () => {
        this.saving.set(false);
        this.cancelling.set(null);
        this.notice.set(`Vente n° ${sale.number} annulée : le stock a été remis.`);
        this.reload();
      },
      error: (e) => {
        this.saving.set(false);
        this.error.set(errorMessage(e));
      },
    });
  }

  private reload(): void {
    // Le résumé chiffré (marge, résultat) est réservé au propriétaire et à la secrétaire.
    const report = this.auth.isManager() ? this.api.dailyReport() : of(null);
    forkJoin({ report, sales: this.api.sales(), customers: this.api.customers() }).subscribe({
      next: ({ report, sales, customers }) => {
        this.report.set(report);
        this.sales.set(sales);
        this.customers.set(customers);
        this.loading.set(false);
      },
      error: (e) => {
        this.error.set(errorMessage(e));
        this.loading.set(false);
      },
    });
  }
}
