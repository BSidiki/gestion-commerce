import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';

import { Api, errorMessage } from '../../core/api';
import { AuthState } from '../../core/auth-state';
import { LicenseState } from '../../core/license-state';

@Component({
  selector: 'app-setup',
  imports: [FormsModule],
  template: `
    <form class="card form sign-in" (ngSubmit)="create()">
      <h1>Bienvenue</h1>
      <p class="muted">
        Créez le compte du propriétaire de la boutique. Lui seul pourra annuler une vente, corriger le stock, gérer
        les autres comptes et restaurer une sauvegarde.
      </p>
      @if (error(); as message) {
        <p class="alert error" role="alert">{{ message }}</p>
      }
      <label>
        Votre nom
        <input name="displayName" [(ngModel)]="displayName" autocomplete="name" placeholder="Awa Ouédraogo" required />
      </label>
      <label>
        Nom d'utilisateur
        <input name="username" [(ngModel)]="username" autocomplete="username" autocapitalize="none" placeholder="awa" required />
        <small>3 à 30 caractères : lettres sans accent, chiffres, point, tiret.</small>
      </label>
      <label>
        Mot de passe (6 caractères au moins)
        <input name="password" type="password" [(ngModel)]="password" autocomplete="new-password" required />
      </label>
      <label>
        Répétez le mot de passe
        <input name="passwordAgain" type="password" [(ngModel)]="passwordAgain" autocomplete="new-password" required />
      </label>
      <p class="alert warning">
        Notez ce mot de passe en lieu sûr. S'il est perdu et qu'il n'existe aucun autre compte propriétaire, personne
        ne pourra le réinitialiser depuis le logiciel.
      </p>
      <button type="submit" class="button primary big" [disabled]="busy()">
        {{ busy() ? 'Création…' : 'Créer le compte et commencer' }}
      </button>
    </form>
  `,
})
export class SetupPage {
  private readonly api = inject(Api);
  private readonly auth = inject(AuthState);
  private readonly license = inject(LicenseState);
  private readonly router = inject(Router);

  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected displayName = '';
  protected username = '';
  protected password = '';
  protected passwordAgain = '';

  protected create(): void {
    if (!this.displayName.trim() || !this.username.trim()) {
      this.error.set("Indiquez votre nom et un nom d'utilisateur.");
      return;
    }
    if (this.password.length < 6) {
      this.error.set('Le mot de passe doit contenir au moins 6 caractères.');
      return;
    }
    if (this.password !== this.passwordAgain) {
      this.error.set('Les deux mots de passe ne sont pas identiques.');
      return;
    }
    this.busy.set(true);
    this.error.set(null);
    this.api.setup(this.username.trim(), this.displayName.trim(), this.password).subscribe({
      next: me => {
        this.auth.set(me);
        this.license.refresh();
        this.router.navigateByUrl('/');
      },
      error: e => {
        this.busy.set(false);
        this.error.set(errorMessage(e));
      },
    });
  }
}
