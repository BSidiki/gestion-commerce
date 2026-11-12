import { Component, inject } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';

import { day } from './core/format';
import { LicenseState } from './core/license-state';

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
        <a routerLink="/licence" routerLinkActive="active">Licence</a>
      </nav>
    </header>

    @if (license.status(); as s) {
      @if (s.readOnly) {
        <p class="license-banner blocked no-print" role="alert">
          Lecture seule :
          {{ s.state === 'EXPIREE' ? 'la licence a expiré' : "la période d'essai est terminée" }}. Vos données
          restent consultables et sauvegardables, mais plus rien ne s'enregistre.
          <a routerLink="/licence">Enregistrer une licence</a>
        </p>
      } @else if (s.state === 'ESSAI') {
        <p class="license-banner no-print">
          Période d'essai : {{ days(s.daysLeft) }}.
          <a routerLink="/licence">Obtenir une licence</a>
        </p>
      } @else if (s.warning && s.expiresOn) {
        <p class="license-banner no-print">
          La licence expire le {{ date(s.expiresOn) }} ({{ days(s.daysLeft) }}).
          <a routerLink="/licence">Renouveler</a>
        </p>
      }
    }

    <main class="page">
      <router-outlet />
    </main>
  `,
})
export class App {
  protected readonly license = inject(LicenseState);
  protected readonly date = day;

  constructor() {
    this.license.refresh();
  }

  protected days(count: number | null): string {
    const n = count ?? 0;
    return n <= 1 ? 'dernier jour' : `${n} jours restants`;
  }
}
