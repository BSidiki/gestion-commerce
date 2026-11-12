import { Component } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';

@Component({
  selector: 'app-root',
  imports: [RouterOutlet, RouterLink, RouterLinkActive],
  template: `
    <header class="topbar no-print">
      <a class="brand" routerLink="/">Gestion commerciale</a>
      <nav>
        <a routerLink="/" routerLinkActive="active" [routerLinkActiveOptions]="{ exact: true }">Caisse</a>
        <a routerLink="/ventes" routerLinkActive="active">Ventes du jour</a>
        <a routerLink="/clients" routerLinkActive="active">Clients</a>
        <a routerLink="/depenses" routerLinkActive="active">Dépenses</a>
        <a routerLink="/cloture" routerLinkActive="active">Clôture</a>
        <a routerLink="/produits" routerLinkActive="active">Produits</a>
        <a routerLink="/sauvegarde" routerLinkActive="active">Sauvegarde</a>
      </nav>
    </header>
    <main class="page">
      <router-outlet />
    </main>
  `,
})
export class App {}
