import { Component, inject, signal } from '@angular/core';
import { Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';

import { Api } from './core/api';
import { Appearance } from './core/appearance';
import { AuthState } from './core/auth-state';
import { day } from './core/format';
import { Icon } from './core/icon';
import { LicenseState } from './core/license-state';

@Component({
  selector: 'app-root',
  imports: [RouterOutlet, RouterLink, RouterLinkActive, Icon],
  template: `
    @if (auth.user(); as user) {
      <div class="shell">
        <div class="mobilebar no-print">
          <button type="button" (click)="menuOpen.set(true)" aria-label="Ouvrir le menu">
            <app-icon name="menu" />
          </button>
          <span>{{ look.displayName() }}</span>
        </div>
        @if (menuOpen()) {
          <button type="button" class="scrim no-print" (click)="menuOpen.set(false)" aria-label="Fermer le menu"></button>
        }

        <aside class="sidebar no-print" [class.open]="menuOpen()">
          <a class="brand" routerLink="/" (click)="menuOpen.set(false)">
            <span class="brand-mark">
              @if (look.logo(); as logo) {
                <img [src]="logo" alt="" />
              } @else {
                {{ initial() }}
              }
            </span>
            <span class="brand-name">{{ look.displayName() }}</span>
          </a>

          <nav aria-label="Menu principal" (click)="menuOpen.set(false)">
            <p class="group">Vendre</p>
            <a routerLink="/" routerLinkActive="active" [routerLinkActiveOptions]="{ exact: true }">
              <app-icon name="caisse" /> Caisse
            </a>
            <a routerLink="/ventes" routerLinkActive="active"><app-icon name="ventes" /> Ventes du jour</a>
            <a routerLink="/clients" routerLinkActive="active"><app-icon name="clients" /> Clients</a>

            <p class="group">Gérer</p>
            <a routerLink="/produits" routerLinkActive="active"><app-icon name="produits" /> Produits</a>
            <a routerLink="/depenses" routerLinkActive="active"><app-icon name="depenses" /> Dépenses</a>
            <a routerLink="/cloture" routerLinkActive="active"><app-icon name="cloture" /> Clôture</a>

            <p class="group">Réglages</p>
            @if (auth.isOwner()) {
              <a routerLink="/boutique" routerLinkActive="active"><app-icon name="boutique" /> Boutique</a>
              <a routerLink="/utilisateurs" routerLinkActive="active">
                <app-icon name="utilisateurs" /> Utilisateurs
              </a>
              <a routerLink="/sauvegarde" routerLinkActive="active"><app-icon name="sauvegarde" /> Sauvegarde</a>
              <a routerLink="/journal" routerLinkActive="active"><app-icon name="journal" /> Journal</a>
            }
            <a routerLink="/licence" routerLinkActive="active"><app-icon name="licence" /> Licence</a>
          </nav>

          <div class="who" (click)="menuOpen.set(false)">
            <a routerLink="/compte" routerLinkActive="active"><app-icon name="compte" /> {{ user.displayName }}</a>
            <button type="button" (click)="logout()"><app-icon name="quitter" /> Se déconnecter</button>
          </div>
        </aside>

        <div class="content">
          @if (license.status(); as s) {
            @if (s.readOnly) {
              <p class="license-banner blocked no-print" role="alert">
                Lecture seule :
                {{ s.state === 'EXPIREE' ? 'la licence a expiré' : "la période d'essai est terminée" }}. Vos
                données restent consultables et sauvegardables, mais plus rien ne s'enregistre.
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
        </div>
      </div>
    } @else {
      <main class="page">
        <router-outlet />
      </main>
    }
  `,
})
export class App {
  private readonly api = inject(Api);
  private readonly router = inject(Router);
  protected readonly auth = inject(AuthState);
  protected readonly license = inject(LicenseState);
  protected readonly look = inject(Appearance);
  /** Sur petit écran, le menu est un volet qu'on ouvre et referme. */
  protected readonly menuOpen = signal(false);
  protected readonly date = day;

  constructor() {
    // L'état de la licence n'est lisible qu'une fois connecté.
    this.auth.ensureLoaded().then(
      me => {
        if (me.user) {
          this.license.refresh();
        }
      },
      () => undefined,
    );
  }

  /** Sans logo, la pastille du menu porte l'initiale de la boutique. */
  protected initial(): string {
    return this.look.displayName().trim().charAt(0).toUpperCase();
  }

  protected days(count: number | null): string {
    const n = count ?? 0;
    return n <= 1 ? 'dernier jour' : `${n} jours restants`;
  }

  protected logout(): void {
    this.api.logout().subscribe({
      next: () => this.leave(),
      // Même si le serveur ne répond pas, on quitte l'écran : la session expirera d'elle-même.
      error: () => this.leave(),
    });
  }

  private leave(): void {
    this.auth.signedOut();
    this.license.status.set(null);
    this.router.navigateByUrl('/connexion');
  }
}
