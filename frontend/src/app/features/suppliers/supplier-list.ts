import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';

import { Api, Supplier, errorMessage } from '../../core/api';
import { fcfa } from '../../core/format';

/** Les fournisseurs et ce que la boutique doit à chacun. */
@Component({
  selector: 'app-supplier-list',
  imports: [FormsModule, RouterLink],
  template: `
    <div class="page-head">
      <h1>Fournisseurs</h1>
    </div>

    @if (error(); as message) {
      <p class="alert error" role="alert">{{ message }}</p>
    }

    <div class="stats">
      <div class="stat">
        <span class="label">Reste à payer aux fournisseurs</span>
        <span class="value">{{ money(totalOwed()) }}</span>
        <span class="muted">{{ owedCount() }} fournisseur(s) à payer</span>
      </div>
    </div>

    <form class="card form" (ngSubmit)="add()">
      <h2>Nouveau fournisseur</h2>
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
        <button type="submit" class="button primary" [disabled]="!name.trim() || saving()">
          Ajouter le fournisseur
        </button>
      </div>
    </form>

    @if (loading()) {
      <p class="muted">Chargement…</p>
    } @else if (suppliers().length === 0) {
      <div class="empty">
        <p>Aucun fournisseur pour l'instant. Ajoutez le premier pour enregistrer ses livraisons.</p>
      </div>
    } @else {
      <table class="table">
        <thead>
          <tr>
            <th>Fournisseur</th>
            <th>Téléphone</th>
            <th class="num">Reste à payer</th>
            <th></th>
          </tr>
        </thead>
        <tbody>
          @for (s of suppliers(); track s.id) {
            <tr>
              <td>
                <a [routerLink]="['/fournisseurs', s.id]">{{ s.name }}</a>
              </td>
              <td>{{ s.phone || '—' }}</td>
              <td class="num">
                @if (s.balance > 0) {
                  <strong>{{ money(s.balance) }}</strong>
                } @else if (s.balance < 0) {
                  <span class="muted">avoir de {{ money(-s.balance) }}</span>
                } @else {
                  <span class="muted">0 F</span>
                }
              </td>
              <td class="row-actions">
                <a class="button" [routerLink]="['/fournisseurs', s.id, 'achat']">Nouvel achat</a>
              </td>
            </tr>
          }
        </tbody>
      </table>
    }
  `,
})
export class SupplierList {
  private readonly api = inject(Api);

  protected readonly suppliers = signal<Supplier[]>([]);
  protected readonly loading = signal(true);
  protected readonly saving = signal(false);
  protected readonly error = signal<string | null>(null);

  protected name = '';
  protected phone = '';

  protected readonly totalOwed = computed(() => this.suppliers().reduce((sum, s) => sum + Math.max(s.balance, 0), 0));
  protected readonly owedCount = computed(() => this.suppliers().filter(s => s.balance > 0).length);

  protected readonly money = fcfa;

  constructor() {
    this.reload();
  }

  protected add(): void {
    this.saving.set(true);
    this.error.set(null);
    this.api.createSupplier({ name: this.name.trim(), phone: this.phone.trim() || null, note: null }).subscribe({
      next: () => {
        this.name = '';
        this.phone = '';
        this.saving.set(false);
        this.reload();
      },
      error: e => {
        this.error.set(errorMessage(e));
        this.saving.set(false);
      },
    });
  }

  private reload(): void {
    this.api.suppliers().subscribe({
      next: suppliers => {
        this.suppliers.set(suppliers);
        this.loading.set(false);
      },
      error: e => {
        this.error.set(errorMessage(e));
        this.loading.set(false);
      },
    });
  }
}
