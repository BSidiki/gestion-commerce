import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';

import { Api, Quote, errorMessage } from '../../core/api';
import { day, fcfa } from '../../core/format';

export function todayIso(): string {
  const now = new Date();
  const month = String(now.getMonth() + 1).padStart(2, '0');
  return `${now.getFullYear()}-${month}-${String(now.getDate()).padStart(2, '0')}`;
}

/** L'état d'un devis tel qu'on le dit au commerçant ; un devis ouvert dont la date est passée est « expiré ». */
export function quoteStatus(quote: Quote): { label: string; tone: 'ok' | 'warning' | 'neutral' } {
  if (quote.status === 'TRANSFORME') {
    return { label: `Vendu (vente n° ${quote.saleNumber})`, tone: 'ok' };
  }
  if (quote.status === 'ANNULE') {
    return { label: 'Annulé', tone: 'neutral' };
  }
  return quote.validUntil < todayIso() ? { label: 'Expiré', tone: 'warning' } : { label: 'Ouvert', tone: 'neutral' };
}

/** Les devis remis aux clients. On en crée un depuis la caisse. */
@Component({
  selector: 'app-quote-list',
  imports: [FormsModule, RouterLink],
  styles: `
    .badge {
      margin-left: 0;
    }
    .badge.ok {
      background: var(--success-bg);
      color: var(--success-ink);
    }
  `,
  template: `
    <div class="page-head">
      <h1>Devis</h1>
      <a class="button primary" routerLink="/">Nouveau devis depuis la caisse</a>
    </div>

    @if (error(); as message) {
      <p class="alert error" role="alert">{{ message }}</p>
    }

    @if (loading()) {
      <p class="muted">Chargement…</p>
    } @else if (quotes().length === 0) {
      <div class="empty">
        <p>
          Aucun devis pour l'instant. À la caisse, composez le panier puis choisissez « Faire un devis plutôt
          qu'une vente ».
        </p>
      </div>
    } @else {
      <div class="toolbar">
        <label class="check">
          <input type="checkbox" [ngModel]="openOnly()" (ngModelChange)="openOnly.set($event)" />
          Seulement les devis ouverts
        </label>
      </div>
      <table class="table">
        <thead>
          <tr>
            <th>N°</th>
            <th>Date</th>
            <th>Client</th>
            <th class="num">Total</th>
            <th>Valable jusqu'au</th>
            <th>État</th>
          </tr>
        </thead>
        <tbody>
          @for (q of visible(); track q.id) {
            <tr [class.cancelled]="q.status === 'ANNULE'">
              <td>
                <a [routerLink]="['/devis', q.id]">Devis n° {{ q.number }}</a>
              </td>
              <td>{{ date(q.createdAt.slice(0, 10)) }}</td>
              <td>{{ q.customerName || '—' }}</td>
              <td class="num">{{ money(q.total) }}</td>
              <td>{{ date(q.validUntil) }}</td>
              <td>
                <span class="badge" [class]="'badge ' + status(q).tone">{{ status(q).label }}</span>
              </td>
            </tr>
          } @empty {
            <tr>
              <td colspan="6" class="muted">Aucun devis ouvert.</td>
            </tr>
          }
        </tbody>
      </table>
    }
  `,
})
export class QuoteList {
  private readonly api = inject(Api);

  protected readonly quotes = signal<Quote[]>([]);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);
  protected readonly openOnly = signal(false);

  protected readonly visible = computed(() =>
    this.openOnly() ? this.quotes().filter(q => q.status === 'OUVERT') : this.quotes(),
  );

  protected readonly money = fcfa;
  protected readonly date = day;
  protected readonly status = quoteStatus;

  constructor() {
    this.api.quotes().subscribe({
      next: quotes => {
        this.quotes.set(quotes);
        this.loading.set(false);
      },
      error: e => {
        this.error.set(errorMessage(e));
        this.loading.set(false);
      },
    });
  }
}
