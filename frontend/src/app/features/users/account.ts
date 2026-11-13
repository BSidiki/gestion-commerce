import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';

import { Api, errorMessage } from '../../core/api';
import { Appearance, TextScale, Theme } from '../../core/appearance';
import { AuthState } from '../../core/auth-state';
import { ROLE_LABELS } from '../../core/format';

@Component({
  selector: 'app-account',
  imports: [FormsModule],
  template: `
    <div class="page-head">
      <h1>Mon compte</h1>
    </div>

    @if (auth.user(); as user) {
      <div class="stats">
        <div class="stat">
          <span class="label">Connecté en tant que</span>
          <span class="value">{{ user.displayName }}</span>
          <span class="muted">{{ user.username }} · {{ role(user.role) }}</span>
        </div>
      </div>
    }

    @if (error(); as message) {
      <p class="alert error" role="alert">{{ message }}</p>
    }
    @if (notice(); as message) {
      <p class="alert success" role="status">{{ message }}</p>
    }

    <section class="card form">
      <h2>Affichage sur ce poste</h2>
      <p class="muted">Ces deux réglages ne changent que cet ordinateur, pas celui de vos collègues.</p>
      <div>
        <p class="field-title" id="theme-title">Thème</p>
        <div class="choices" role="radiogroup" aria-labelledby="theme-title">
          @for (option of themes; track option.id) {
            <label class="choice">
              <input
                type="radio"
                name="theme"
                [value]="option.id"
                [checked]="look.theme() === option.id"
                (change)="look.setTheme(option.id)"
              />
              {{ option.label }}
            </label>
          }
        </div>
      </div>
      <div>
        <p class="field-title" id="scale-title">Taille du texte</p>
        <div class="choices" role="radiogroup" aria-labelledby="scale-title">
          @for (option of scales; track option.id) {
            <label class="choice">
              <input
                type="radio"
                name="scale"
                [value]="option.id"
                [checked]="look.scale() === option.id"
                (change)="look.setScale(option.id)"
              />
              {{ option.label }}
            </label>
          }
        </div>
      </div>
    </section>

    <form class="card form" (ngSubmit)="change()">
      <h2>Changer mon mot de passe</h2>
      <div class="grid">
        <label>
          Mot de passe actuel
          <input name="current" type="password" [(ngModel)]="current" autocomplete="current-password" required />
        </label>
        <label>
          Nouveau mot de passe (6 caractères au moins)
          <input name="next" type="password" [(ngModel)]="next" autocomplete="new-password" required />
        </label>
        <label>
          Répétez le nouveau mot de passe
          <input name="nextAgain" type="password" [(ngModel)]="nextAgain" autocomplete="new-password" required />
        </label>
      </div>
      <div class="actions">
        <button type="submit" class="button primary" [disabled]="busy()">Changer le mot de passe</button>
      </div>
    </form>
  `,
})
export class AccountPage {
  private readonly api = inject(Api);
  protected readonly auth = inject(AuthState);
  protected readonly look = inject(Appearance);

  protected readonly themes: { id: Theme; label: string }[] = [
    { id: 'auto', label: 'Comme Windows' },
    { id: 'clair', label: 'Clair' },
    { id: 'sombre', label: 'Sombre' },
  ];
  protected readonly scales: { id: TextScale; label: string }[] = [
    { id: 'normale', label: 'Normale' },
    { id: 'grande', label: 'Grande' },
    { id: 'tres-grande', label: 'Très grande' },
  ];

  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly notice = signal<string | null>(null);
  protected current = '';
  protected next = '';
  protected nextAgain = '';

  protected role(role: string): string {
    return ROLE_LABELS[role] ?? role;
  }

  protected change(): void {
    this.notice.set(null);
    if (this.next.length < 6) {
      this.error.set('Le nouveau mot de passe doit contenir au moins 6 caractères.');
      return;
    }
    if (this.next !== this.nextAgain) {
      this.error.set('Les deux nouveaux mots de passe ne sont pas identiques.');
      return;
    }
    this.busy.set(true);
    this.error.set(null);
    this.api.changeOwnPassword(this.current, this.next).subscribe({
      next: () => {
        this.current = '';
        this.next = '';
        this.nextAgain = '';
        this.busy.set(false);
        this.notice.set('Mot de passe changé.');
      },
      error: e => {
        this.busy.set(false);
        this.error.set(errorMessage(e));
      },
    });
  }
}
