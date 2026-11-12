import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';

import {
  Api,
  Category,
  Packaging,
  PackagingUpdateRequest,
  Price,
  PriceLevel,
  ProductDetail,
  errorMessage,
} from '../../core/api';
import { PRICE_LEVEL_LABELS } from '../../core/format';

interface PriceDraft {
  level: PriceLevel;
  price: number | null;
  floorPrice: number | null;
}

interface PackagingDraft {
  /** Identifiant du conditionnement existant, ou null s'il est ajouté dans ce formulaire. */
  id: string | null;
  name: string;
  factor: number | null;
  barcode: string;
  prices: PriceDraft[];
}

const LEVELS: PriceLevel[] = ['DETAIL', 'DEMI_GROS', 'GROS'];

function emptyPrices(): PriceDraft[] {
  return LEVELS.map(level => ({ level, price: null, floorPrice: null }));
}

/** Les trois niveaux de prix, préremplis avec ceux du conditionnement existant. */
function pricesOf(packaging: Packaging): PriceDraft[] {
  return LEVELS.map(level => {
    const current = packaging.prices.find(p => p.level === level);
    return { level, price: current?.price ?? null, floorPrice: current?.floorPrice ?? null };
  });
}

function draftOf(packaging: Packaging): PackagingDraft {
  return {
    id: packaging.id,
    name: packaging.name,
    factor: packaging.factor,
    barcode: packaging.barcodes[0] ?? '',
    prices: pricesOf(packaging),
  };
}

@Component({
  selector: 'app-product-form',
  imports: [FormsModule, RouterLink],
  template: `
    <div class="page-head">
      <h1>{{ editingId ? 'Modifier le produit' : 'Nouveau produit' }}</h1>
      <a class="button" [routerLink]="editingId ? ['/produits', editingId] : ['/produits']">Annuler</a>
    </div>

    <form (ngSubmit)="save()" class="form">
      <section class="card">
        <h2>Le produit</h2>
        <div class="grid">
          <label>
            Nom du produit
            <input name="name" [(ngModel)]="name" required placeholder="Savon de Marseille" />
          </label>
          <label>
            Unité de base
            <input name="baseUnit" [(ngModel)]="baseUnit" required placeholder="pièce, bouteille, gramme…" />
            <small>La plus petite quantité vendue. Le stock est compté dans cette unité.</small>
          </label>
          <label>
            Seuil d'alerte (en unité de base)
            <input name="alertThreshold" type="number" min="0" [(ngModel)]="alertThreshold" />
          </label>
          <label>
            Catégorie
            <select name="categoryId" [(ngModel)]="categoryId">
              <option [ngValue]="null">Sans catégorie</option>
              @for (c of categories(); track c.id) {
                <option [ngValue]="c.id">{{ c.name }}</option>
              }
            </select>
          </label>
          <label>
            Ou créer une catégorie
            <span class="inline">
              <input name="newCategory" [(ngModel)]="newCategory" placeholder="Hygiène" />
              <button type="button" class="button" (click)="addCategory()" [disabled]="!newCategory.trim()">
                Ajouter
              </button>
            </span>
          </label>
        </div>
      </section>

      <section class="card">
        <h2>Vente à l'unité de base</h2>
        <div class="grid">
          <label>
            Code-barres (facultatif)
            <input name="baseBarcode" [(ngModel)]="base.barcode" />
          </label>
        </div>
        <table class="table compact">
          <thead>
            <tr>
              <th>Niveau</th>
              <th>Prix de vente (F)</th>
              <th>Prix plancher (F)</th>
            </tr>
          </thead>
          <tbody>
            @for (price of base.prices; track price.level) {
              <tr>
                <td>{{ levelLabel(price.level) }}</td>
                <td>
                  <input type="number" min="0" [name]="'base-price-' + price.level" [(ngModel)]="price.price" />
                </td>
                <td>
                  <input type="number" min="0" [name]="'base-floor-' + price.level" [(ngModel)]="price.floorPrice" />
                </td>
              </tr>
            }
          </tbody>
        </table>
        <small>Laissez vide les niveaux non utilisés. Le prix plancher est la limite de négociation du vendeur.</small>
      </section>

      @for (packaging of extras(); track $index; let i = $index) {
        <section class="card">
          <div class="page-head">
            <h2>{{ packaging.id ? 'Conditionnement' : 'Nouveau conditionnement' }}</h2>
            @if (!packaging.id) {
              <button type="button" class="button danger" (click)="removePackaging(i)">Retirer</button>
            }
          </div>
          <div class="grid">
            <label>
              Nom
              <input [name]="'pk-name-' + i" [(ngModel)]="packaging.name" required placeholder="Carton" />
            </label>
            <label>
              Contient combien d'unités de base ?
              <input
                [name]="'pk-factor-' + i"
                type="number"
                min="2"
                [(ngModel)]="packaging.factor"
                [disabled]="!!packaging.id"
                required
              />
              @if (packaging.id) {
                <small>Non modifiable : le stock déjà compté deviendrait faux.</small>
              }
            </label>
            <label>
              Code-barres (facultatif)
              <input [name]="'pk-barcode-' + i" [(ngModel)]="packaging.barcode" />
            </label>
          </div>
          <table class="table compact">
            <thead>
              <tr>
                <th>Niveau</th>
                <th>Prix de vente (F)</th>
                <th>Prix plancher (F)</th>
              </tr>
            </thead>
            <tbody>
              @for (price of packaging.prices; track price.level) {
                <tr>
                  <td>{{ levelLabel(price.level) }}</td>
                  <td>
                    <input
                      type="number"
                      min="0"
                      [name]="'pk-price-' + i + '-' + price.level"
                      [(ngModel)]="price.price"
                    />
                  </td>
                  <td>
                    <input
                      type="number"
                      min="0"
                      [name]="'pk-floor-' + i + '-' + price.level"
                      [(ngModel)]="price.floorPrice"
                    />
                  </td>
                </tr>
              }
            </tbody>
          </table>
        </section>
      }

      <button type="button" class="button" (click)="addPackaging()">+ Ajouter un conditionnement (paquet, carton…)</button>

      @if (error(); as message) {
        <p class="alert error" role="alert">{{ message }}</p>
      }

      <div class="actions">
        <button type="submit" class="button primary" [disabled]="saving()">
          {{ saving() ? 'Enregistrement…' : editingId ? 'Enregistrer les modifications' : 'Enregistrer le produit' }}
        </button>
      </div>
    </form>
  `,
})
export class ProductForm {
  private readonly api = inject(Api);
  private readonly router = inject(Router);

  /** Identifiant du produit en cours de modification, ou null pour une création. */
  protected readonly editingId = inject(ActivatedRoute).snapshot.paramMap.get('id');

  protected name = '';
  protected baseUnit = '';
  protected alertThreshold: number | null = 0;
  protected categoryId: string | null = null;
  protected newCategory = '';

  /** Le conditionnement de facteur 1 : il porte le nom de l'unité de base. */
  protected base: PackagingDraft = { id: null, name: '', factor: 1, barcode: '', prices: emptyPrices() };
  protected readonly extras = signal<PackagingDraft[]>([]);

  protected readonly categories = signal<Category[]>([]);
  protected readonly saving = signal(false);
  protected readonly error = signal<string | null>(null);

  constructor() {
    this.api.categories().subscribe({
      next: categories => this.categories.set(categories),
      error: e => this.error.set(errorMessage(e)),
    });
    if (this.editingId) {
      this.saving.set(true);
      this.api.product(this.editingId).subscribe({
        next: detail => {
          this.fill(detail);
          this.saving.set(false);
        },
        error: e => this.error.set(errorMessage(e)),
      });
    }
  }

  private fill(detail: ProductDetail): void {
    this.name = detail.product.name;
    this.baseUnit = detail.product.baseUnit;
    this.alertThreshold = detail.product.alertThreshold;
    this.categoryId = detail.product.categoryId;
    const basePackaging = detail.packagings.find(p => p.factor === 1);
    if (basePackaging) {
      this.base = draftOf(basePackaging);
    }
    this.extras.set(detail.packagings.filter(p => p.factor !== 1).map(draftOf));
  }

  protected levelLabel(level: PriceLevel): string {
    return PRICE_LEVEL_LABELS[level];
  }

  protected addPackaging(): void {
    this.extras.update(list => [...list, { id: null, name: '', factor: null, barcode: '', prices: emptyPrices() }]);
  }

  protected removePackaging(index: number): void {
    this.extras.update(list => list.filter((_, i) => i !== index));
  }

  protected addCategory(): void {
    const name = this.newCategory.trim();
    if (!name) {
      return;
    }
    this.api.createCategory(name).subscribe({
      next: category => {
        this.categories.update(list => [...list, category].sort((a, b) => a.name.localeCompare(b.name)));
        this.categoryId = category.id;
        this.newCategory = '';
        this.error.set(null);
      },
      error: e => this.error.set(errorMessage(e)),
    });
  }

  protected save(): void {
    const problem = this.validate();
    if (problem) {
      this.error.set(problem);
      return;
    }
    this.error.set(null);
    this.saving.set(true);

    const baseUnit = this.baseUnit.trim();
    const packagings: PackagingUpdateRequest[] = [
      this.toRequest({ ...this.base, name: baseUnit, factor: 1 }),
      ...this.extras().map(p => this.toRequest(p)),
    ];
    const product = {
      name: this.name.trim(),
      categoryId: this.categoryId,
      baseUnit,
      alertThreshold: this.alertThreshold ?? 0,
      packagings,
    };
    const request = this.editingId
      ? this.api.updateProduct(this.editingId, product)
      : this.api.createProduct(product);

    request.subscribe({
      next: saved => this.router.navigate(['/produits', saved.product.id]),
      error: e => {
        this.error.set(errorMessage(e));
        this.saving.set(false);
      },
    });
  }

  private validate(): string | null {
    if (!this.name.trim()) {
      return 'Donnez un nom au produit.';
    }
    if (!this.baseUnit.trim()) {
      return "Indiquez l'unité de base (pièce, bouteille, gramme…).";
    }
    for (const packaging of this.extras()) {
      if (!packaging.name.trim()) {
        return 'Chaque conditionnement supplémentaire doit avoir un nom.';
      }
      if (!packaging.factor || packaging.factor < 2 || !Number.isInteger(packaging.factor)) {
        return `Le conditionnement « ${packaging.name.trim()} » doit contenir au moins 2 unités de base.`;
      }
    }
    for (const packaging of [this.base, ...this.extras()]) {
      for (const price of packaging.prices) {
        if (price.price != null && price.floorPrice != null && price.floorPrice > price.price) {
          return 'Un prix plancher ne peut pas dépasser le prix de vente.';
        }
      }
    }
    return null;
  }

  private toRequest(draft: PackagingDraft): PackagingUpdateRequest {
    const prices: Price[] = draft.prices
      .filter(p => p.price != null)
      .map(p => ({ level: p.level, price: p.price!, floorPrice: p.floorPrice ?? p.price! }));
    const barcode = draft.barcode.trim();
    return {
      ...(draft.id ? { id: draft.id } : {}),
      name: draft.name.trim(),
      factor: draft.factor ?? 1,
      ...(barcode ? { barcode } : {}),
      prices,
    };
  }
}
