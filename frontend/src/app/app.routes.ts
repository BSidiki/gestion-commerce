import { Routes } from '@angular/router';

export const routes: Routes = [
  {
    path: '',
    title: 'Caisse',
    loadComponent: () => import('./features/pos/pos').then(m => m.Pos),
  },
  {
    path: 'ventes',
    title: 'Ventes du jour',
    loadComponent: () => import('./features/sales/sales-day').then(m => m.SalesDay),
  },
  {
    path: 'clients',
    title: 'Clients',
    loadComponent: () => import('./features/customers/customer-list').then(m => m.CustomerList),
  },
  {
    path: 'clients/:id',
    title: 'Fiche client',
    loadComponent: () => import('./features/customers/customer-detail').then(m => m.CustomerDetailPage),
  },
  {
    path: 'depenses',
    title: 'Dépenses',
    loadComponent: () => import('./features/cash/expenses').then(m => m.Expenses),
  },
  {
    path: 'cloture',
    title: 'Clôture de caisse',
    loadComponent: () => import('./features/cash/cash-closing').then(m => m.CashClosing),
  },
  {
    path: 'sauvegarde',
    title: 'Sauvegarde',
    loadComponent: () => import('./features/backup/backup').then(m => m.BackupPage),
  },
  {
    path: 'licence',
    title: 'Licence',
    loadComponent: () => import('./features/license/license').then(m => m.LicensePage),
  },
  {
    path: 'produits',
    title: 'Produits',
    loadComponent: () => import('./features/products/product-list').then(m => m.ProductList),
  },
  {
    path: 'produits/nouveau',
    title: 'Nouveau produit',
    loadComponent: () => import('./features/products/product-form').then(m => m.ProductForm),
  },
  {
    path: 'produits/:id',
    title: 'Fiche produit',
    loadComponent: () => import('./features/products/product-detail').then(m => m.ProductDetailPage),
  },
  { path: '**', redirectTo: '' },
];
