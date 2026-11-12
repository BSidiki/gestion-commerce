import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';

import { Api, ProductSummary, errorMessage } from '../../core/api';
import { costFromMilli, quantity } from '../../core/format';

@Component({
  selector: 'app-product-list',
  imports: [RouterLink, FormsModule],
  template: `
    <div class="page-head">
      <h1>Produits</h1>
      <a class="button primary" routerLink="/produits/nouveau">+ Nouveau produit</a>
    </div>

    <div class="toolbar">
      <input
        type="search"
        placeholder="Rechercher un produit"
        aria-label="Rechercher un produit"
        [ngModel]="search()"
        (ngModelChange)="search.set($event)"
      />
      <label class="check">
        <input type="checkbox" [ngModel]="lowStockOnly()" (ngModelChange)="lowStockOnly.set($event)" />
        Stock bas seulement
      </label>
    </div>

    @if (error(); as message) {
      <p class="alert error" role="alert">{{ message }}</p>
    }

    @if (loading()) {
      <p class="muted">Chargement…</p>
    } @else if (products().length === 0 && !error()) {
      <div class="empty">
        <p>Aucun produit pour l'instant.</p>
        <a class="button primary" routerLink="/produits/nouveau">Créer le premier produit</a>
      </div>
    } @else {
      <table class="table">
        <thead>
          <tr>
            <th>Produit</th>
            <th class="num">Stock</th>
            <th class="num">Seuil d'alerte</th>
            <th class="num">Coût moyen</th>
            <th class="num">Valeur du stock</th>
          </tr>
        </thead>
        <tbody>
          @for (p of visible(); track p.id) {
            <tr>
              <td>
                <a [routerLink]="['/produits', p.id]">{{ p.name }}</a>
                @if (p.lowStock) {
                  <span class="badge warning">Stock bas</span>
                }
              </td>
              <td class="num">{{ qty(p.stockQty) }} {{ p.baseUnit }}</td>
              <td class="num">{{ qty(p.alertThreshold) }}</td>
              <td class="num">{{ cost(p.avgCostMilli) }}</td>
              <td class="num">{{ stockValue(p) }}</td>
            </tr>
          } @empty {
            <tr>
              <td colspan="5" class="muted">Aucun produit ne correspond.</td>
            </tr>
          }
        </tbody>
      </table>
      <p class="muted">
        {{ visible().length }} produit(s) · valeur totale du stock affiché : {{ totalValue() }}
      </p>
    }
  `,
})
export class ProductList {
  private readonly api = inject(Api);

  protected readonly products = signal<ProductSummary[]>([]);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);
  protected readonly search = signal('');
  protected readonly lowStockOnly = signal(false);

  protected readonly visible = computed(() => {
    const term = this.search().trim().toLowerCase();
    return this.products().filter(
      p => (!this.lowStockOnly() || p.lowStock) && (term === '' || p.name.toLowerCase().includes(term)),
    );
  });

  protected readonly totalValue = computed(() =>
    costFromMilli(this.visible().reduce((sum, p) => sum + Math.max(p.stockQty, 0) * p.avgCostMilli, 0)),
  );

  protected readonly qty = quantity;
  protected readonly cost = costFromMilli;

  constructor() {
    this.api.products().subscribe({
      next: products => {
        this.products.set(products);
        this.loading.set(false);
      },
      error: e => {
        this.error.set(errorMessage(e));
        this.loading.set(false);
      },
    });
  }

  protected stockValue(p: ProductSummary): string {
    return costFromMilli(Math.max(p.stockQty, 0) * p.avgCostMilli);
  }
}
