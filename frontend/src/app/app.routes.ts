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
