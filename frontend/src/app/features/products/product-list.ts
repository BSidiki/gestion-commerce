import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';

import { Api, ProductSummary, errorMessage } from '../../core/api';
import { AuthState } from '../../core/auth-state';
import { costFromMilli, quantity } from '../../core/format';

@Component({
  selector: 'app-product-list',
  imports: [RouterLink, FormsModule],
  template: `
    <div class="page-head">
      <h1>Produits</h1>
      @if (auth.isManager()) {
        <div class="actions">
          <a class="button" routerLink="/catalogue/importer">Importer depuis Excel</a>
          <a class="button primary" routerLink="/produits/nouveau">+ Nouveau produit</a>
        </div>
      }
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
      @if (auth.isManager()) {
        <label class="check">
          <input type="checkbox" [ngModel]="showInactive()" (ngModelChange)="setShowInactive($event)" />
          Afficher les produits désactivés
        </label>
      }
    </div>

    @if (error(); as message) {
      <p class="alert error" role="alert">{{ message }}</p>
    }

    @if (loading()) {
      <p class="muted">Chargement…</p>
    } @else if (products().length === 0 && !error()) {
      <div class="empty">
        <p>Aucun produit pour l'instant.</p>
        @if (auth.isManager()) {
          <a class="button primary" routerLink="/produits/nouveau">Créer le premier produit</a>
          <a class="button" routerLink="/catalogue/importer">Importer depuis Excel</a>
        }
      </div>
    } @else {
      <table class="table">
        <thead>
          <tr>
            <th>Produit</th>
            <th class="num">Stock</th>
            <th class="num">Seuil d'alerte</th>
            @if (auth.isManager()) {
              <th class="num">Coût moyen</th>
              <th class="num">Valeur du stock</th>
            }
          </tr>
        </thead>
        <tbody>
          @for (p of visible(); track p.id) {
            <tr>
              <td>
                <a [routerLink]="['/produits', p.id]">{{ p.name }}</a>
                @if (!p.active) {
                  <span class="badge neutral">Désactivé</span>
                } @else if (p.lowStock) {
                  <span class="badge warning">Stock bas</span>
                }
              </td>
              <td class="num">{{ qty(p.stockQty) }} {{ p.baseUnit }}</td>
              <td class="num">{{ qty(p.alertThreshold) }}</td>
              @if (auth.isManager()) {
                <td class="num">{{ cost(p.avgCostMilli ?? 0) }}</td>
                <td class="num">{{ stockValue(p) }}</td>
              }
            </tr>
          } @empty {
            <tr>
              <td colspan="5" class="muted">Aucun produit ne correspond.</td>
            </tr>
          }
        </tbody>
      </table>
      <p class="muted">
        {{ visible().length }} produit(s)
        @if (auth.isManager()) {
          · valeur totale du stock affiché : {{ totalValue() }}
        }
      </p>
    }
  `,
})
export class ProductList {
  private readonly api = inject(Api);
  protected readonly auth = inject(AuthState);

  protected readonly products = signal<ProductSummary[]>([]);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);
  protected readonly search = signal('');
  protected readonly lowStockOnly = signal(false);
  protected readonly showInactive = signal(false);

  protected readonly visible = computed(() => {
    const term = this.search().trim().toLowerCase();
    return this.products().filter(
      p => (!this.lowStockOnly() || p.lowStock) && (term === '' || p.name.toLowerCase().includes(term)),
    );
  });

  protected readonly totalValue = computed(() =>
    costFromMilli(this.visible().reduce((sum, p) => sum + Math.max(p.stockQty, 0) * (p.avgCostMilli ?? 0), 0)),
  );

  protected readonly qty = quantity;
  protected readonly cost = costFromMilli;

  constructor() {
    this.load();
  }

  protected setShowInactive(show: boolean): void {
    this.showInactive.set(show);
    this.load();
  }

  private load(): void {
    this.api.products(false, this.showInactive()).subscribe({
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
    return costFromMilli(Math.max(p.stockQty, 0) * (p.avgCostMilli ?? 0));
  }
}
