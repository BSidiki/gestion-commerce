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

export type PaymentMethod = 'ESPECES' | 'MOBILE_MONEY';

export interface Customer {
  id: string;
  name: string;
  phone: string | null;
  /** Solde dû par le client, en francs CFA. */
  balance: number;
}

export interface SaleItemRequest {
  packagingId: string;
  quantity: number;
  unitPrice: number;
}

export interface PaymentLine {
  method: PaymentMethod;
  amount: number;
}

export interface SaleRequest {
  idempotencyKey: string;
  customerId: string | null;
  items: SaleItemRequest[];
  payments: PaymentLine[];
}

export interface SaleItem {
  productId: string;
  productName: string;
  packagingName: string;
  factor: number;
  quantity: number;
  unitPrice: number;
  listPrice: number | null;
  lineTotal: number;
}

export interface Sale {
  id: string;
  number: number;
  customerId: string | null;
  total: number;
  paid: number;
  credit: number;
  status: 'VALIDEE' | 'ANNULEE';
  businessDate: string;
  createdAt: string;
  cancelReason: string | null;
  items: SaleItem[];
  payments: PaymentLine[];
}

export interface DailyReport {
  date: string;
  saleCount: number;
  revenue: number;
  costOfGoods: number;
  grossMargin: number;
  cash: number;
  mobileMoney: number;
  creditGranted: number;
  repaymentsReceived: number;
  expenses: number;
  /** Marge brute moins les dépenses du jour. */
  estimatedResult: number;
}

export interface LedgerEntry {
  type: 'CREDIT' | 'REMBOURSEMENT' | 'ANNULATION';
  /** Positif : le client doit davantage. Négatif : il doit moins. */
  amount: number;
  saleId: string | null;
  note: string | null;
  createdAt: string;
}

export interface CustomerDetail {
  customer: Customer;
  ledger: LedgerEntry[];
}

export interface CashSession {
  id: string;
  status: 'OUVERTE' | 'CLOTUREE';
  openingFloat: number;
  openedAt: string;
  closedAt: string | null;
  cashIn: number;
  cashExpenses: number;
  expectedCash: number;
  countedCash: number | null;
  /** Compté moins attendu : négatif s'il manque de l'argent. */
  difference: number | null;
  note: string | null;
}

export interface CashStatus {
  open: boolean;
  session: CashSession | null;
}

export interface Expense {
  id: string;
  category: string;
  amount: number;
  method: PaymentMethod;
  note: string | null;
  createdAt: string;
}

export interface ExpenseRequest {
  category: string;
  amount: number;
  method: PaymentMethod;
  note?: string;
}

export interface Backup {
  id: string;
  kind: 'MANUELLE' | 'AUTOMATIQUE' | 'AVANT_RESTAURATION' | 'RESTAURATION';
  path: string;
  sizeBytes: number | null;
  /** Vrai si l'archive est hors du PC (clé USB, disque externe). */
  external: boolean;
  status: 'REUSSIE' | 'ECHOUEE';
  message: string | null;
  createdAt: string;
}

export interface BackupStatus {
  lastBackupAt: string | null;
  lastExternalBackupAt: string | null;
  /** Vrai s'il est temps de sauvegarder sur un support externe. */
  reminder: boolean;
}

export interface BackupDestination {
  path: string;
  label: string;
  freeBytes: number;
  local: boolean;
  /** Sur le même disque que les données : ne protège pas d'une panne de ce disque. */
  sameDiskAsData: boolean;
}

export interface BackupOverview {
  status: BackupStatus;
  destinations: BackupDestination[];
  history: Backup[];
}

export interface BackupFile {
  path: string;
  name: string;
  sizeBytes: number;
  modifiedAt: string;
}

export interface RestoreResult {
  restoredFrom: string;
  backupCreatedAt: string;
  safetyCopyPath: string;
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

  /** Catalogue complet pour la caisse : produits, conditionnements, prix, codes-barres. */
  sellable(): Observable<ProductDetail[]> {
    return this.http.get<ProductDetail[]>('/api/catalog/sellable');
  }

  customers(): Observable<Customer[]> {
    return this.http.get<Customer[]>('/api/customers');
  }

  createCustomer(name: string, phone: string): Observable<Customer> {
    return this.http.post<Customer>('/api/customers', { name, phone });
  }

  createSale(request: SaleRequest): Observable<Sale> {
    return this.http.post<Sale>('/api/sales', request);
  }

  /** Ventes du jour, la plus récente en premier. */
  sales(): Observable<Sale[]> {
    return this.http.get<Sale[]>('/api/sales');
  }

  cancelSale(saleId: string, reason: string): Observable<Sale> {
    return this.http.post<Sale>(`/api/sales/${saleId}/cancel`, { reason });
  }

  dailyReport(): Observable<DailyReport> {
    return this.http.get<DailyReport>('/api/reports/daily');
  }

  customer(id: string): Observable<CustomerDetail> {
    return this.http.get<CustomerDetail>(`/api/customers/${id}`);
  }

  repay(customerId: string, amount: number, method: PaymentMethod, note: string): Observable<CustomerDetail> {
    return this.http.post<CustomerDetail>(`/api/customers/${customerId}/repayments`, {
      amount,
      method,
      ...(note ? { note } : {}),
    });
  }

  cashStatus(): Observable<CashStatus> {
    return this.http.get<CashStatus>('/api/cash/current');
  }

  openCash(openingFloat: number): Observable<CashSession> {
    return this.http.post<CashSession>('/api/cash/open', { openingFloat });
  }

  closeCash(countedCash: number, note: string): Observable<CashSession> {
    return this.http.post<CashSession>('/api/cash/close', { countedCash, ...(note ? { note } : {}) });
  }

  cashSessions(): Observable<CashSession[]> {
    return this.http.get<CashSession[]>('/api/cash/sessions');
  }

  /** Dépenses du jour, la plus récente en premier. */
  expenses(): Observable<Expense[]> {
    return this.http.get<Expense[]>('/api/expenses');
  }

  addExpense(request: ExpenseRequest): Observable<Expense> {
    return this.http.post<Expense>('/api/expenses', request);
  }

  backups(): Observable<BackupOverview> {
    return this.http.get<BackupOverview>('/api/backups');
  }

  createBackup(directory: string): Observable<Backup> {
    return this.http.post<Backup>('/api/backups', { directory });
  }

  backupFiles(directory: string): Observable<BackupFile[]> {
    return this.http.get<BackupFile[]>('/api/backups/files', { params: { directory } });
  }

  restoreBackup(path: string): Observable<RestoreResult> {
    return this.http.post<RestoreResult>('/api/backups/restore', { path });
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
