import { Routes } from '@angular/router';

import { ownerOnly, signedIn, signedOut } from './core/auth-guards';

export const routes: Routes = [
  {
    path: 'connexion',
    title: 'Connexion',
    canActivate: [signedOut],
    loadComponent: () => import('./features/auth/login').then(m => m.LoginPage),
  },
  {
    path: 'installation',
    title: 'Première installation',
    canActivate: [signedOut],
    loadComponent: () => import('./features/auth/setup').then(m => m.SetupPage),
  },
  {
    path: '',
    title: 'Caisse',
    canActivate: [signedIn],
    loadComponent: () => import('./features/pos/pos').then(m => m.Pos),
  },
  {
    path: 'ventes',
    title: 'Ventes du jour',
    canActivate: [signedIn],
    loadComponent: () => import('./features/sales/sales-day').then(m => m.SalesDay),
  },
  {
    path: 'clients',
    title: 'Clients',
    canActivate: [signedIn],
    loadComponent: () => import('./features/customers/customer-list').then(m => m.CustomerList),
  },
  {
    path: 'clients/:id',
    title: 'Fiche client',
    canActivate: [signedIn],
    loadComponent: () => import('./features/customers/customer-detail').then(m => m.CustomerDetailPage),
  },
  {
    path: 'depenses',
    title: 'Dépenses',
    canActivate: [signedIn],
    loadComponent: () => import('./features/cash/expenses').then(m => m.Expenses),
  },
  {
    path: 'cloture',
    title: 'Clôture de caisse',
    canActivate: [signedIn],
    loadComponent: () => import('./features/cash/cash-closing').then(m => m.CashClosing),
  },
  {
    path: 'sauvegarde',
    title: 'Sauvegarde',
    canActivate: [signedIn, ownerOnly],
    loadComponent: () => import('./features/backup/backup').then(m => m.BackupPage),
  },
  {
    path: 'licence',
    title: 'Licence',
    canActivate: [signedIn],
    loadComponent: () => import('./features/license/license').then(m => m.LicensePage),
  },
  {
    path: 'utilisateurs',
    title: 'Utilisateurs',
    canActivate: [signedIn, ownerOnly],
    loadComponent: () => import('./features/users/users').then(m => m.UsersPage),
  },
  {
    path: 'journal',
    title: 'Journal',
    canActivate: [signedIn, ownerOnly],
    loadComponent: () => import('./features/users/journal').then(m => m.JournalPage),
  },
  {
    path: 'compte',
    title: 'Mon compte',
    canActivate: [signedIn],
    loadComponent: () => import('./features/users/account').then(m => m.AccountPage),
  },
  {
    path: 'produits',
    title: 'Produits',
    canActivate: [signedIn],
    loadComponent: () => import('./features/products/product-list').then(m => m.ProductList),
  },
  {
    path: 'produits/nouveau',
    title: 'Nouveau produit',
    canActivate: [signedIn, ownerOnly],
    loadComponent: () => import('./features/products/product-form').then(m => m.ProductForm),
  },
  {
    path: 'produits/:id',
    title: 'Fiche produit',
    canActivate: [signedIn],
    loadComponent: () => import('./features/products/product-detail').then(m => m.ProductDetailPage),
  },
  { path: '**', redirectTo: '' },
];
