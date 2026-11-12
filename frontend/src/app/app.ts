import { Component } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';

@Component({
  selector: 'app-root',
  imports: [RouterOutlet, RouterLink, RouterLinkActive],
  template: `
    <header class="topbar">
      <a class="brand" routerLink="/">Gestion commerciale</a>
      <nav>
        <a routerLink="/" routerLinkActive="active" [routerLinkActiveOptions]="{ exact: true }">Produits</a>
        <a routerLink="/produits/nouveau" routerLinkActive="active">Nouveau produit</a>
      </nav>
    </header>
    <main class="page">
      <router-outlet />
    </main>
  `,
})
export class App {}
