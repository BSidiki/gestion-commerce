import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';

import { Api, Role, UserAccount, errorMessage } from '../../core/api';
import { AuthState } from '../../core/auth-state';
import { ROLE_LABELS } from '../../core/format';

@Component({
  selector: 'app-users',
  imports: [FormsModule],
  template: `
    <div class="page-head">
      <h1>Utilisateurs</h1>
    </div>

    @if (error(); as message) {
      <p class="alert error" role="alert">{{ message }}</p>
    }
    @if (notice(); as message) {
      <p class="alert success" role="status">{{ message }}</p>
    }

    <form class="card form" (ngSubmit)="create()">
      <h2>Nouveau compte</h2>
      <div class="grid">
        <label>
          Nom de la personne
          <input name="displayName" [(ngModel)]="displayName" required />
        </label>
        <label>
          Nom d'utilisateur
          <input name="username" [(ngModel)]="username" autocapitalize="none" autocomplete="off" required />
        </label>
        <label>
          Rôle
          <select name="role" [(ngModel)]="role">
            <option value="VENDEUR">Vendeur : vend, encaisse, reçoit la marchandise</option>
            <option value="PROPRIETAIRE">Propriétaire : tous les droits</option>
          </select>
        </label>
        <label>
          Mot de passe (6 caractères au moins)
          <input name="password" type="password" [(ngModel)]="password" autocomplete="new-password" required />
        </label>
      </div>
      <div class="actions">
        <button type="submit" class="button primary" [disabled]="busy()">Créer le compte</button>
      </div>
    </form>

    <table class="table">
      <thead>
        <tr>
          <th>Personne</th>
          <th>Nom d'utilisateur</th>
          <th>Rôle</th>
          <th>État</th>
          <th></th>
        </tr>
      </thead>
      <tbody>
        @for (u of users(); track u.id) {
          <tr [class.cancelled]="!u.active">
            <td>{{ u.displayName }}{{ u.id === auth.user()?.id ? ' (vous)' : '' }}</td>
            <td>{{ u.username }}</td>
            <td>{{ roleLabel(u.role) }}</td>
            <td>
              @if (u.active) {
                Actif
              } @else {
                <span class="badge warning">Désactivé</span>
              }
            </td>
            <td>
              <div class="inline">
                @if (u.id !== auth.user()?.id) {
                  <button type="button" class="button" (click)="startReset(u)" [disabled]="busy()">
                    Nouveau mot de passe
                  </button>
                  <button type="button" class="button" (click)="toggle(u)" [disabled]="busy()">
                    {{ u.active ? 'Désactiver' : 'Réactiver' }}
                  </button>
                }
              </div>
            </td>
          </tr>
          @if (resetting() === u.id) {
            <tr>
              <td colspan="5">
                <div class="inline">
                  <input
                    type="password"
                    [(ngModel)]="newPassword"
                    autocomplete="new-password"
                    placeholder="Nouveau mot de passe (6 caractères au moins)"
                    [attr.aria-label]="'Nouveau mot de passe de ' + u.displayName"
                  />
                  <button type="button" class="button primary" (click)="confirmReset(u)" [disabled]="busy()">
                    Donner ce mot de passe à {{ u.displayName }}
                  </button>
                  <button type="button" class="button" (click)="resetting.set(null)">Annuler</button>
                </div>
              </td>
            </tr>
          }
        }
      </tbody>
    </table>
    <p class="muted">
      Un compte désactivé ne peut plus se connecter ; son historique est conservé. Pour changer votre propre mot de
      passe, utilisez la page Mon compte.
    </p>
  `,
})
export class UsersPage {
  private readonly api = inject(Api);
  protected readonly auth = inject(AuthState);

  protected readonly users = signal<UserAccount[]>([]);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly notice = signal<string | null>(null);
  protected readonly resetting = signal<string | null>(null);

  protected displayName = '';
  protected username = '';
  protected role: Role = 'VENDEUR';
  protected password = '';
  protected newPassword = '';

  constructor() {
    this.reload();
  }

  protected roleLabel(role: string): string {
    return ROLE_LABELS[role] ?? role;
  }

  protected create(): void {
    if (!this.displayName.trim() || !this.username.trim()) {
      this.error.set("Indiquez le nom de la personne et un nom d'utilisateur.");
      return;
    }
    if (this.password.length < 6) {
      this.error.set('Le mot de passe doit contenir au moins 6 caractères.');
      return;
    }
    this.start();
    const name = this.displayName.trim();
    this.api.createUser(this.username.trim(), name, this.role, this.password).subscribe({
      next: () => {
        this.displayName = '';
        this.username = '';
        this.password = '';
        this.role = 'VENDEUR';
        this.done(`Compte de ${name} créé.`);
      },
      error: e => this.failed(e),
    });
  }

  protected startReset(user: UserAccount): void {
    this.newPassword = '';
    this.error.set(null);
    this.notice.set(null);
    this.resetting.set(user.id);
  }

  protected confirmReset(user: UserAccount): void {
    if (this.newPassword.length < 6) {
      this.error.set('Le mot de passe doit contenir au moins 6 caractères.');
      return;
    }
    this.start();
    this.api.resetUserPassword(user.id, this.newPassword).subscribe({
      next: () => {
        this.newPassword = '';
        this.resetting.set(null);
        this.done(`Nouveau mot de passe enregistré pour ${user.displayName}.`);
      },
      error: e => this.failed(e),
    });
  }

  protected toggle(user: UserAccount): void {
    this.start();
    this.api.setUserActive(user.id, !user.active).subscribe({
      next: () => this.done(`Compte de ${user.displayName} ${user.active ? 'désactivé' : 'réactivé'}.`),
      error: e => this.failed(e),
    });
  }

  private start(): void {
    this.busy.set(true);
    this.error.set(null);
    this.notice.set(null);
  }

  private done(message: string): void {
    this.busy.set(false);
    this.notice.set(message);
    this.reload();
  }

  private failed(error: unknown): void {
    this.busy.set(false);
    this.error.set(errorMessage(error));
  }

  private reload(): void {
    this.api.users().subscribe({
      next: users => this.users.set(users),
      error: e => this.error.set(errorMessage(e)),
    });
  }
}
