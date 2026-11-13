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
  /** Absent (null) pour un compte qui ne voit pas les coûts d'achat. */
  avgCostMilli: number | null;
  alertThreshold: number;
  lowStock: boolean;
  /** Faux pour un produit désactivé : absent de la caisse et des listes. */
  active: boolean;
}

export interface ProductDetail {
  product: ProductSummary;
  packagings: Packaging[];
}

/** Une ligne du tableau d'import. Seul le nom est obligatoire ; montants en francs CFA entiers. */
export interface ImportRow {
  name: string | null;
  category?: string | null;
  baseUnit?: string | null;
  retailPrice?: number | null;
  retailFloor?: number | null;
  barcode?: string | null;
  alertThreshold?: number | null;
  initialStock?: number | null;
  unitCost?: number | null;
  packagingName?: string | null;
  packagingFactor?: number | null;
  packagingPrice?: number | null;
  packagingFloor?: number | null;
}

export interface ImportRowResult {
  /** Rang de la ligne dans la demande, à partir de 1. */
  line: number;
  name: string;
  status: 'CREE' | 'EXISTE' | 'ERREUR';
  message: string;
}

export interface ImportResult {
  dryRun: boolean;
  created: number;
  skipped: number;
  errors: number;
  rows: ImportRowResult[];
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
  /** Absent (null) pour un compte qui ne voit pas les coûts d'achat. */
  avgCostMilli: number | null;
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

export interface PackagingUpdateRequest extends PackagingRequest {
  /** Identifiant du conditionnement existant ; absent pour en ajouter un. */
  id?: string;
}

export interface ProductUpdateRequest {
  name: string;
  categoryId: string | null;
  baseUnit: string;
  alertThreshold: number;
  packagings: PackagingUpdateRequest[];
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

/** Coordonnées imprimées en tête du reçu, et format du papier. */
export interface ShopSettings {
  name: string;
  address: string;
  phone: string;
  receiptFooter: string;
  receiptWidth: '58' | '80' | 'A4';
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
  /** Vrai si les sauvegardes sont protégées par mot de passe. */
  encryptionEnabled: boolean;
  status: BackupStatus;
  destinations: BackupDestination[];
  history: Backup[];
}

export interface BackupFile {
  path: string;
  name: string;
  sizeBytes: number;
  modifiedAt: string;
  /** Protégée par mot de passe. */
  encrypted: boolean;
  /** Ce PC n'en a pas la clé : il faut le mot de passe ou la clé de secours. */
  needsSecret: boolean;
}

export interface RestoreResult {
  restoredFrom: string;
  backupCreatedAt: string;
  safetyCopyPath: string;
}

export type Role = 'PROPRIETAIRE' | 'SECRETAIRE' | 'VENDEUR';

export interface User {
  id: string;
  username: string;
  displayName: string;
  role: Role;
}

export interface Me {
  /** Vrai tant qu'aucun compte n'existe : il faut créer celui du propriétaire. */
  setupRequired: boolean;
  user: User | null;
}

export interface UserAccount extends User {
  active: boolean;
}

export interface AuditEntry {
  username: string | null;
  action: string;
  detail: string | null;
  createdAt: string;
}

export interface LicenseStatus {
  /** DESACTIVEE : version sans licences (développement). */
  state: 'DESACTIVEE' | 'ESSAI' | 'ACTIVE' | 'ESSAI_TERMINE' | 'EXPIREE';
  /** Vrai si le logiciel n'enregistre plus rien. */
  readOnly: boolean;
  /** Code à communiquer à l'éditeur pour obtenir une licence. */
  installId: string;
  shop: string | null;
  expiresOn: string | null;
  daysLeft: number | null;
  warning: boolean;
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

  products(lowStock = false, includeInactive = false): Observable<ProductSummary[]> {
    return this.http.get<ProductSummary[]>('/api/products', { params: { lowStock, includeInactive } });
  }

  updateProduct(id: string, request: ProductUpdateRequest): Observable<ProductDetail> {
    return this.http.put<ProductDetail>(`/api/products/${id}`, request);
  }

  setProductActive(id: string, active: boolean): Observable<ProductDetail> {
    return this.http.post<ProductDetail>(`/api/products/${id}/active`, { active });
  }

  product(id: string): Observable<ProductDetail> {
    return this.http.get<ProductDetail>(`/api/products/${id}`);
  }

  createProduct(request: ProductRequest): Observable<ProductDetail> {
    return this.http.post<ProductDetail>('/api/products', request);
  }

  /** Importe un tableau de produits ; avec dryRun, vérifie sans rien enregistrer. */
  importCatalog(rows: ImportRow[], dryRun: boolean): Observable<ImportResult> {
    return this.http.post<ImportResult>('/api/products/import', { dryRun, rows });
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

  me(): Observable<Me> {
    return this.http.get<Me>('/api/auth/me');
  }

  setup(username: string, displayName: string, password: string): Observable<Me> {
    return this.http.post<Me>('/api/auth/setup', { username, displayName, password });
  }

  login(username: string, password: string): Observable<Me> {
    return this.http.post<Me>('/api/auth/login', { username, password });
  }

  logout(): Observable<void> {
    return this.http.post<void>('/api/auth/logout', {});
  }

  changeOwnPassword(currentPassword: string, newPassword: string): Observable<void> {
    return this.http.post<void>('/api/auth/password', { currentPassword, newPassword });
  }

  users(): Observable<UserAccount[]> {
    return this.http.get<UserAccount[]>('/api/users');
  }

  createUser(username: string, displayName: string, role: Role, password: string): Observable<UserAccount> {
    return this.http.post<UserAccount>('/api/users', { username, displayName, role, password });
  }

  resetUserPassword(userId: string, password: string): Observable<void> {
    return this.http.post<void>(`/api/users/${userId}/password`, { password });
  }

  changeUserRole(userId: string, role: Role): Observable<UserAccount> {
    return this.http.post<UserAccount>(`/api/users/${userId}/role`, { role });
  }

  setUserActive(userId: string, active: boolean): Observable<void> {
    return this.http.post<void>(`/api/users/${userId}/active`, { active });
  }

  audit(): Observable<AuditEntry[]> {
    return this.http.get<AuditEntry[]>('/api/audit');
  }

  shop(): Observable<ShopSettings> {
    return this.http.get<ShopSettings>('/api/shop');
  }

  updateShop(settings: ShopSettings): Observable<ShopSettings> {
    return this.http.put<ShopSettings>('/api/shop', settings);
  }

  licenseStatus(): Observable<LicenseStatus> {
    return this.http.get<LicenseStatus>('/api/license');
  }

  activateLicense(license: string): Observable<LicenseStatus> {
    return this.http.post<LicenseStatus>('/api/license', { license });
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

  restoreBackup(path: string, secret: string): Observable<RestoreResult> {
    return this.http.post<RestoreResult>('/api/backups/restore', { path, ...(secret ? { secret } : {}) });
  }

  /** Active la protection des sauvegardes ou change le mot de passe. Renvoie la clé de secours. */
  protectBackups(password: string): Observable<{ recoveryKey: string }> {
    return this.http.post<{ recoveryKey: string }>('/api/backups/encryption', { password });
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
