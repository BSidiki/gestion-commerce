import { Component, computed, inject, signal } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';

import { Api, Quote, Sale, errorMessage } from '../../core/api';
import { day } from '../../core/format';
import { ReceiptView } from '../sales/receipt-view';
import { quoteStatus, todayIso } from './quote-list';

/** Un devis : à imprimer pour le client, à transformer en vente, ou à annuler. */
@Component({
  selector: 'app-quote-detail',
  imports: [RouterLink, ReceiptView],
  template: `
    @if (quote(); as q) {
      <div class="page-head no-print">
        <h1>Devis n° {{ q.number }}</h1>
        <a class="button" routerLink="/devis">Tous les devis</a>
      </div>

      @if (error(); as message) {
        <p class="alert error no-print" role="alert">{{ message }}</p>
      }
      <p class="alert no-print" [class.success]="q.status === 'TRANSFORME'" [class.warning]="q.status !== 'TRANSFORME'">
        {{ statusLine(q) }}
      </p>

      <app-receipt
        [sale]="asSale()!"
        [customerName]="q.customerName"
        [heading]="'Devis n° ' + q.number"
        [footnote]="footnote(q)"
      />

      <div class="actions receipt-actions no-print">
        <button type="button" class="button" (click)="print()">Imprimer le devis</button>
        @if (q.status === 'OUVERT') {
          <a class="button primary" routerLink="/caisse" [queryParams]="{ devis: q.id }">Transformer en vente</a>
          <button type="button" class="button danger" (click)="cancel(q)" [disabled]="busy()">Annuler le devis</button>
        }
      </div>
    } @else if (error(); as message) {
      <p class="alert error" role="alert">{{ message }}</p>
    } @else {
      <p class="muted">Chargement…</p>
    }
  `,
})
export class QuoteDetail {
  private readonly api = inject(Api);
  private readonly quoteId = inject(ActivatedRoute).snapshot.paramMap.get('id') ?? '';

  protected readonly quote = signal<Quote | null>(null);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);

  /** Le devis présenté comme une vente sans paiement, pour réutiliser la mise en page du reçu. */
  protected readonly asSale = computed<Sale | null>(() => {
    const q = this.quote();
    if (!q) {
      return null;
    }
    return {
      id: q.id,
      number: q.number,
      customerId: q.customerId,
      discount: q.discount,
      total: q.total,
      paid: 0,
      credit: 0,
      status: 'VALIDEE',
      businessDate: q.createdAt.slice(0, 10),
      createdAt: q.createdAt,
      cancelReason: null,
      items: q.items.map(item => ({ ...item, listPrice: null })),
      payments: [],
    };
  });

  constructor() {
    this.api.quote(this.quoteId).subscribe({
      next: quote => this.quote.set(quote),
      error: e => this.error.set(errorMessage(e)),
    });
  }

  protected statusLine(q: Quote): string {
    if (q.status === 'TRANSFORME') {
      return `Ce devis a été vendu : vente n° ${q.saleNumber}.`;
    }
    if (q.status === 'ANNULE') {
      return 'Ce devis a été annulé.';
    }
    return q.validUntil < todayIso()
      ? `Ce devis a expiré le ${day(q.validUntil)}. Il peut encore être transformé en vente : les prix seront revérifiés.`
      : `${quoteStatus(q).label} : valable jusqu'au ${day(q.validUntil)}. Le stock n'est pas réservé.`;
  }

  protected footnote(q: Quote): string {
    return `Devis valable jusqu'au ${day(q.validUntil)}. Ce document n'est ni une facture ni un reçu.`;
  }

  protected print(): void {
    window.print();
  }

  protected cancel(q: Quote): void {
    this.busy.set(true);
    this.error.set(null);
    this.api.cancelQuote(q.id).subscribe({
      next: quote => {
        this.quote.set(quote);
        this.busy.set(false);
      },
      error: e => {
        this.error.set(errorMessage(e));
        this.busy.set(false);
      },
    });
  }
}
