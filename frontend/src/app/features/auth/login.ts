import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';

import { Api, errorMessage } from '../../core/api';
import { AuthState } from '../../core/auth-state';
import { LicenseState } from '../../core/license-state';

@Component({
  selector: 'app-login',
  imports: [FormsModule],
  template: `
    <form class="card form sign-in" (ngSubmit)="login()">
      <h1>Connexion</h1>
      @if (error(); as message) {
        <p class="alert error" role="alert">{{ message }}</p>
      }
      <label>
        Nom d'utilisateur
        <input name="username" [(ngModel)]="username" autocomplete="username" autocapitalize="none" required />
      </label>
      <label>
        Mot de passe
        <input name="password" type="password" [(ngModel)]="password" autocomplete="current-password" required />
      </label>
      <button type="submit" class="button primary big" [disabled]="busy()">
        {{ busy() ? 'Connexion…' : 'Se connecter' }}
      </button>
      <small>Mot de passe oublié ? Le propriétaire peut vous en donner un nouveau depuis la page Utilisateurs.</small>
    </form>
  `,
})
export class LoginPage {
  private readonly api = inject(Api);
  private readonly auth = inject(AuthState);
  private readonly license = inject(LicenseState);
  private readonly router = inject(Router);

  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected username = '';
  protected password = '';

  protected login(): void {
    if (!this.username.trim() || !this.password) {
      this.error.set("Saisissez votre nom d'utilisateur et votre mot de passe.");
      return;
    }
    this.busy.set(true);
    this.error.set(null);
    this.api.login(this.username.trim(), this.password).subscribe({
      next: me => {
        this.auth.set(me);
        this.license.refresh();
        this.router.navigateByUrl('/');
      },
      error: e => {
        this.busy.set(false);
        this.password = '';
        this.error.set(errorMessage(e));
      },
    });
  }
}
