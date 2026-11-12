import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

export type PriceLevel = 'DETAIL' | 'DEMI_GROS' | 'GROS';
export type AdjustmentReason = 'PERTE' | 'CASSE' | 'VOL' | 'ERREUR_SAISIE' | 'INVENTAIRE';

export interface Category {
  id: string;
  name: string;
}

export interface Price {
  level: PriceLevel;
  price: number;
  floorPrice: number;
}

export interface Packaging {
  id: string;
  name: string;
  factor: number;
  barcodes: string[];
  prices: Price[];
}

export interface ProductSummary {
  id: string;
  name: string;
  categoryId: string | null;
  baseUnit: string;
  stockQty: number;
  /** Coût moyen pondéré par unité de base, en millièmes de franc. */
  avgCostMilli: number;
  alertThreshold: number;
  lowStock: boolean;
}

export interface ProductDetail {
  product: ProductSummary;
  packagings: Packaging[];
}

export interface Movement {
  id: string;
  type: 'RECEPTION' | 'VENTE' | 'AJUSTEMENT' | 'ANNULATION';
  quantity: number;
  unitCostMilli: number | null;
  reason: string | null;
  note: string | null;
  stockAfter: number;
  createdAt: string;
}

export interface StockLevel {
  productId: string;
  stockQty: number;
  avgCostMilli: number;
}

export interface PackagingRequest {
  name: string;
  factor: number;
  barcode?: string;
  prices: Price[];
}

export interface ProductRequest {
  name: string;
  categoryId: string | null;
  baseUnit: string;
  alertThreshold: number;
  packagings: PackagingRequest[];
}

export interface ReceptionRequest {
  productId: string;
  packagingId: string;
  quantity: number;
  totalCost: number;
  note?: string;
}

export interface AdjustmentRequest {
  productId: string;
  quantityDelta: number;
  reason: AdjustmentReason;
  note?: string;
}

@Injectable({ providedIn: 'root' })
export class Api {
  private readonly http = inject(HttpClient);

  categories(): Observable<Category[]> {
    return this.http.get<Category[]>('/api/categories');
  }

  createCategory(name: string): Observable<Category> {
    return this.http.post<Category>('/api/categories', { name });
  }

  products(lowStock = false): Observable<ProductSummary[]> {
    return this.http.get<ProductSummary[]>('/api/products', { params: { lowStock } });
  }

  product(id: string): Observable<ProductDetail> {
    return this.http.get<ProductDetail>(`/api/products/${id}`);
  }

  createProduct(request: ProductRequest): Observable<ProductDetail> {
    return this.http.post<ProductDetail>('/api/products', request);
  }

  movements(productId: string): Observable<Movement[]> {
    return this.http.get<Movement[]>(`/api/products/${productId}/movements`);
  }

  receive(request: ReceptionRequest): Observable<StockLevel> {
    return this.http.post<StockLevel>('/api/stock/receptions', request);
  }

  adjust(request: AdjustmentRequest): Observable<StockLevel> {
    return this.http.post<StockLevel>('/api/stock/adjustments', request);
  }
}

/** Message lisible par le commerçant à partir d'une erreur de l'API. */
export function errorMessage(error: unknown): string {
  if (error instanceof HttpErrorResponse) {
    if (error.status === 0) {
      return 'Le service local ne répond pas. Vérifiez que le logiciel est bien démarré sur ce PC.';
    }
    const detail = error.error?.detail;
    if (typeof detail === 'string' && detail.length > 0 && error.status !== 400) {
      return detail;
    }
    if (error.status === 400) {
      return 'Certains champs sont vides ou incorrects. Vérifiez la saisie.';
    }
  }
  return 'Une erreur inattendue est survenue. Réessayez.';
}
