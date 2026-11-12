import { Routes } from '@angular/router';

export const routes: Routes = [
  {
    path: '',
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
