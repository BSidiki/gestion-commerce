import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';

import { Api, Expense, PaymentMethod, errorMessage } from '../../core/api';
import { PAYMENT_LABELS, dateTime, fcfa } from '../../core/format';

const SUGGESTED_CATEGORIES = ['Loyer', 'Électricité', 'Eau', 'Transport', 'Salaire', 'Crédit téléphone', 'Entretien', 'Divers'];

@Component({
  selector: 'app-expenses',
  imports: [FormsModule],
  template: `
    <div class="page-head">
      <h1>Dépenses du jour</h1>
    </div>

    @if (error(); as message) {
      <p class="alert error" role="alert">{{ message }}</p>
    }

    <form class="card form" (ngSubmit)="add()">
      <h2>Nouvelle dépense</h2>
      <div class="grid">
        <label>
          Catégorie
          <input name="category" [(ngModel)]="category" list="expense-categories" required placeholder="Transport" />
          <datalist id="expense-categories">
            @for (c of categories; track c) {
              <option [value]="c"></option>
            }
          </datalist>
        </label>
        <label>
          Montant (F)
          <input name="amount" type="number" min="1" [(ngModel)]="amount" required />
        </label>
        <label>
          Payée en
          <select name="method" [(ngModel)]="method">
            <option value="ESPECES">Espèces (sorties de la caisse)</option>
            <option value="MOBILE_MONEY">Mobile Money</option>
          </select>
        </label>
        <label>
          Note (facultatif)
          <input name="note" [(ngModel)]="note" />
        </label>
      </div>
      <div class="actions">
        <button type="submit" class="button primary" [disabled]="saving()">Enregistrer la dépense</button>
      </div>
    </form>

    @if (loading()) {
      <p class="muted">Chargement…</p>
    } @else {
      <table class="table">
        <thead>
          <tr>
            <th>Heure</th>
            <th>Catégorie</th>
            <th>Payée en</th>
            <th>Note</th>
            <th class="num">Montant</th>
          </tr>
        </thead>
        <tbody>
          @for (e of expenses(); track e.id) {
            <tr>
              <td>{{ date(e.createdAt) }}</td>
              <td>{{ e.category }}</td>
              <td>{{ methodLabel(e.method) }}</td>
              <td>{{ e.note || '—' }}</td>
              <td class="num">{{ money(e.amount) }}</td>
            </tr>
          } @empty {
            <tr>
              <td colspan="5" class="muted">Aucune dépense aujourd'hui.</td>
            </tr>
          }
        </tbody>
      </table>
      <p class="muted">Total des dépenses du jour : {{ money(total()) }}</p>
    }
  `,
})
export class Expenses {
  private readonly api = inject(Api);

  protected readonly expenses = signal<Expense[]>([]);
  protected readonly loading = signal(true);
  protected readonly saving = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly total = computed(() => this.expenses().reduce((sum, e) => sum + e.amount, 0));

  protected readonly categories = SUGGESTED_CATEGORIES;
  protected category = '';
  protected amount: number | null = null;
  protected method: PaymentMethod = 'ESPECES';
  protected note = '';

  protected readonly money = fcfa;
  protected readonly date = dateTime;

  constructor() {
    this.api.expenses().subscribe({
      next: expenses => {
        this.expenses.set(expenses);
        this.loading.set(false);
      },
      error: e => {
        this.error.set(errorMessage(e));
        this.loading.set(false);
      },
    });
  }

  protected methodLabel(method: string): string {
    return PAYMENT_LABELS[method] ?? method;
  }

  protected add(): void {
    if (!this.category.trim()) {
      this.error.set('Indiquez la catégorie de la dépense.');
      return;
    }
    if (!this.amount || this.amount < 1 || !Number.isInteger(this.amount)) {
      this.error.set('Indiquez un montant en francs entiers, supérieur à zéro.');
      return;
    }
    const note = this.note.trim();
    this.saving.set(true);
    this.api
      .addExpense({
        category: this.category.trim(),
        amount: this.amount,
        method: this.method,
        ...(note ? { note } : {}),
      })
      .subscribe({
        next: expense => {
          this.expenses.update(list => [expense, ...list]);
          this.category = '';
          this.amount = null;
          this.note = '';
          this.saving.set(false);
          this.error.set(null);
        },
        error: e => {
          this.saving.set(false);
          this.error.set(errorMessage(e));
        },
      });
  }
}
