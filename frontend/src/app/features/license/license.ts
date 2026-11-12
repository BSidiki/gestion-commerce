import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';

import { Api, errorMessage } from '../../core/api';
import { day } from '../../core/format';
import { LicenseState } from '../../core/license-state';

@Component({
  selector: 'app-license',
  imports: [FormsModule],
  template: `
    <div class="page-head">
      <h1>Licence</h1>
    </div>

    @if (error(); as message) {
      <p class="alert error" role="alert">{{ message }}</p>
    }
    @if (notice(); as message) {
      <p class="alert success" role="status">{{ message }}</p>
    }

    @if (state.status(); as s) {
      <div class="stats">
        <div class="stat">
          <span class="label">État</span>
          @switch (s.state) {
            @case ('ACTIVE') {
              <span class="value">Licence valide</span>
              <span class="muted">{{ s.shop }} · jusqu'au {{ date(s.expiresOn!) }} inclus</span>
            }
            @case ('ESSAI') {
              <span class="value">Période d'essai</span>
              <span class="muted">jusqu'au {{ date(s.expiresOn!) }} inclus</span>
            }
            @case ('EXPIREE') {
              <span class="value negative">Licence expirée</span>
              <span class="muted">depuis le {{ date(s.expiresOn!) }} · lecture seule</span>
            }
            @case ('ESSAI_TERMINE') {
              <span class="value negative">Essai terminé</span>
              <span class="muted">depuis le {{ date(s.expiresOn!) }} · lecture seule</span>
            }
            @default {
              <span class="value">Sans licence</span>
              <span class="muted">cette version du logiciel ne demande pas de licence</span>
            }
          }
        </div>
        <div class="stat">
          <span class="label">Code d'installation</span>
          <span class="value code">{{ s.installId }}</span>
          <span class="muted">à communiquer pour obtenir ou renouveler la licence</span>
        </div>
      </div>

      @if (s.state !== 'DESACTIVEE') {
        <form class="card form" (ngSubmit)="activate()">
          <h2>{{ s.state === 'ACTIVE' ? 'Renouveler la licence' : 'Enregistrer une licence' }}</h2>
          <p class="muted">
            Envoyez le code d'installation à votre fournisseur. Il vous renvoie un texte de licence : collez-le
            ci-dessous en entier.
          </p>
          <label>
            Texte de la licence
            <textarea name="license" rows="5" [(ngModel)]="token" spellcheck="false"></textarea>
          </label>
          <div class="actions">
            <button type="submit" class="button primary" [disabled]="saving() || !token.trim()">
              Enregistrer la licence
            </button>
          </div>
        </form>
      }
    } @else {
      <p class="muted">Chargement…</p>
    }
  `,
})
export class LicensePage {
  private readonly api = inject(Api);
  protected readonly state = inject(LicenseState);

  protected readonly saving = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly notice = signal<string | null>(null);
  protected token = '';

  protected readonly date = day;

  constructor() {
    this.state.refresh();
  }

  protected activate(): void {
    this.saving.set(true);
    this.error.set(null);
    this.notice.set(null);
    this.api.activateLicense(this.token).subscribe({
      next: status => {
        this.state.status.set(status);
        this.token = '';
        this.saving.set(false);
        this.notice.set(
          status.expiresOn
            ? `Licence enregistrée : valable jusqu'au ${day(status.expiresOn)} inclus.`
            : 'Licence enregistrée.',
        );
      },
      error: e => {
        this.saving.set(false);
        this.error.set(errorMessage(e));
      },
    });
  }
}
