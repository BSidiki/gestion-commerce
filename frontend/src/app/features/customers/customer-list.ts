import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';

import { Api, Customer, errorMessage } from '../../core/api';
import { fcfa } from '../../core/format';

@Component({
  selector: 'app-customer-list',
  imports: [FormsModule, RouterLink],
  template: `
    <div class="page-head">
      <h1>Clients</h1>
    </div>

    @if (error(); as message) {
      <p class="alert error" role="alert">{{ message }}</p>
    }

    <div class="stats">
      <div class="stat">
        <span class="label">Total dû par les clients</span>
        <span class="value">{{ money(totalDebt()) }}</span>
        <span class="muted">{{ debtors() }} client(s) doivent de l'argent</span>
      </div>
    </div>

    <form class="card form" (ngSubmit)="add()">
      <h2>Nouveau client</h2>
      <div class="grid">
        <label>
          Nom
          <input name="name" [(ngModel)]="name" required />
        </label>
        <label>
          Téléphone (facultatif)
          <input name="phone" [(ngModel)]="phone" />
        </label>
      </div>
      <div class="actions">
        <button type="submit" class="button primary" [disabled]="!name.trim() || saving()">Ajouter le client</button>
      </div>
    </form>

    <div class="toolbar">
      <input
        type="search"
        placeholder="Rechercher un client"
        aria-label="Rechercher un client"
        [ngModel]="search()"
        (ngModelChange)="search.set($event)"
      />
      <label class="check">
        <input type="checkbox" [ngModel]="debtOnly()" (ngModelChange)="debtOnly.set($event)" />
        Seulement ceux qui doivent
      </label>
    </div>

    @if (loading()) {
      <p class="muted">Chargement…</p>
    } @else {
      <table class="table">
        <thead>
          <tr>
            <th>Client</th>
            <th>Téléphone</th>
            <th class="num">Doit</th>
          </tr>
        </thead>
        <tbody>
          @for (c of visible(); track c.id) {
            <tr>
              <td>
                <a [routerLink]="['/clients', c.id]">{{ c.name }}</a>
              </td>
              <td>{{ c.phone || '—' }}</td>
              <td class="num">
                @if (c.balance > 0) {
                  <strong>{{ money(c.balance) }}</strong>
                } @else if (c.balance < 0) {
                  <span class="muted">avoir de {{ money(-c.balance) }}</span>
                } @else {
                  <span class="muted">0 F</span>
                }
              </td>
            </tr>
          } @empty {
            <tr>
              <td colspan="3" class="muted">Aucun client à afficher.</td>
            </tr>
          }
        </tbody>
      </table>
    }
  `,
})
export class CustomerList {
  private readonly api = inject(Api);

  protected readonly customers = signal<Customer[]>([]);
  protected readonly loading = signal(true);
  protected readonly saving = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly search = signal('');
  protected readonly debtOnly = signal(false);

  protected name = '';
  protected phone = '';

  protected readonly visible = computed(() => {
    const term = this.search().trim().toLowerCase();
    return this.customers().filter(
      c =>
        (!this.debtOnly() || c.balance > 0) &&
        (term === '' || c.name.toLowerCase().includes(term) || (c.phone ?? '').includes(term)),
    );
  });

  protected readonly totalDebt = computed(() =>
    this.customers().reduce((sum, c) => sum + Math.max(c.balance, 0), 0),
  );
  protected readonly debtors = computed(() => this.customers().filter(c => c.balance > 0).length);

  protected readonly money = fcfa;

  constructor() {
    this.reload();
  }

  protected add(): void {
    const name = this.name.trim();
    if (!name) {
      return;
    }
    this.saving.set(true);
    this.api.createCustomer(name, this.phone.trim()).subscribe({
      next: () => {
        this.name = '';
        this.phone = '';
        this.saving.set(false);
        this.error.set(null);
        this.reload();
      },
      error: e => {
        this.saving.set(false);
        this.error.set(errorMessage(e));
      },
    });
  }

  private reload(): void {
    this.api.customers().subscribe({
      next: customers => {
        this.customers.set(customers);
        this.loading.set(false);
      },
      error: e => {
        this.error.set(errorMessage(e));
        this.loading.set(false);
      },
    });
  }
}
