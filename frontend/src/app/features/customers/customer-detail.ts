import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';

import { Api, CustomerDetail, PaymentMethod, errorMessage } from '../../core/api';
import { LEDGER_LABELS, dateTime, fcfa } from '../../core/format';

@Component({
  selector: 'app-customer-detail',
  imports: [FormsModule, RouterLink],
  template: `
    <p><a routerLink="/clients">← Tous les clients</a></p>

    @if (error(); as message) {
      <p class="alert error" role="alert">{{ message }}</p>
    }
    @if (notice(); as message) {
      <p class="alert success" role="status">{{ message }}</p>
    }

    @if (detail(); as d) {
      <div class="page-head">
        <h1>{{ d.customer.name }}</h1>
        <span class="muted">{{ d.customer.phone || 'Pas de téléphone' }}</span>
      </div>

      <div class="stats">
        <div class="stat">
          @if (d.customer.balance >= 0) {
            <span class="label">Doit à la boutique</span>
            <span class="value">{{ money(d.customer.balance) }}</span>
          } @else {
            <span class="label">Avoir du client</span>
            <span class="value">{{ money(-d.customer.balance) }}</span>
            <span class="muted">la boutique lui doit cette somme</span>
          }
        </div>
      </div>

      @if (d.customer.balance > 0) {
        <form class="card form" (ngSubmit)="repay(d.customer.balance)">
          <h2>Enregistrer un remboursement</h2>
          <div class="grid">
            <label>
              Montant reçu (F)
              <input name="amount" type="number" min="1" [max]="d.customer.balance" [(ngModel)]="amount" required />
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
              <input name="note" [(ngModel)]="note" />
            </label>
          </div>
          <div class="actions">
            <button type="submit" class="button primary" [disabled]="saving()">Enregistrer le remboursement</button>
            <button type="button" class="button" (click)="amount = d.customer.balance">Tout rembourser</button>
          </div>
        </form>
      }

      <section class="card">
        <h2>Historique du compte</h2>
        <table class="table">
          <thead>
            <tr>
              <th>Date</th>
              <th>Opération</th>
              <th class="num">Montant</th>
              <th>Note</th>
            </tr>
          </thead>
          <tbody>
            @for (entry of d.ledger; track $index) {
              <tr>
                <td>{{ date(entry.createdAt) }}</td>
                <td>{{ label(entry.type) }}</td>
                <td class="num" [class.negative]="entry.amount > 0">{{ signed(entry.amount) }}</td>
                <td>{{ entry.note || '—' }}</td>
              </tr>
            } @empty {
              <tr>
                <td colspan="4" class="muted">Aucune opération pour l'instant.</td>
              </tr>
            }
          </tbody>
        </table>
        <small>Un montant positif augmente ce que le client doit ; un montant négatif le diminue.</small>
      </section>
    } @else if (!error()) {
      <p class="muted">Chargement…</p>
    }
  `,
})
export class CustomerDetailPage {
  private readonly api = inject(Api);
  private readonly customerId = inject(ActivatedRoute).snapshot.paramMap.get('id') ?? '';

  protected readonly detail = signal<CustomerDetail | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly notice = signal<string | null>(null);
  protected readonly saving = signal(false);

  protected amount: number | null = null;
  protected method: PaymentMethod = 'ESPECES';
  protected note = '';

  protected readonly money = fcfa;
  protected readonly date = dateTime;

  constructor() {
    this.api.customer(this.customerId).subscribe({
      next: detail => this.detail.set(detail),
      error: e => this.error.set(errorMessage(e)),
    });
  }

  protected label(type: string): string {
    return LEDGER_LABELS[type] ?? type;
  }

  protected signed(amount: number): string {
    return amount > 0 ? `+${fcfa(amount)}` : fcfa(amount);
  }

  protected repay(balance: number): void {
    this.notice.set(null);
    if (!this.amount || this.amount < 1 || !Number.isInteger(this.amount)) {
      this.error.set('Indiquez un montant en francs entiers, supérieur à zéro.');
      return;
    }
    if (this.amount > balance) {
      this.error.set(`Le remboursement ne peut pas dépasser ce que le client doit (${fcfa(balance)}).`);
      return;
    }
    const amount = this.amount;
    this.saving.set(true);
    this.api.repay(this.customerId, amount, this.method, this.note.trim()).subscribe({
      next: detail => {
        this.detail.set(detail);
        this.amount = null;
        this.note = '';
        this.saving.set(false);
        this.error.set(null);
        this.notice.set(`Remboursement de ${fcfa(amount)} enregistré.`);
      },
      error: e => {
        this.saving.set(false);
        this.error.set(errorMessage(e));
      },
    });
  }
}
