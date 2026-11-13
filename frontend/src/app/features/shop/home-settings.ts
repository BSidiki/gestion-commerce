import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';

import { Api, errorMessage } from '../../core/api';
import { SHORTCUTS } from '../home/home';

const COVER_WIDTH = 1600;
const COVER_MAX_CHARS = 850_000;

/** Réduit la photo à 1 600 points de large au plus et l'enregistre en JPEG, pour qu'elle reste légère. */
function shrinkPhoto(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const url = URL.createObjectURL(file);
    const image = new Image();
    image.onload = () => {
      URL.revokeObjectURL(url);
      const canvas = document.createElement('canvas');
      const context = canvas.getContext('2d');
      if (!context) {
        reject(new Error('canvas'));
        return;
      }
      // Si la photo reste trop lourde, on la réduit encore, par paliers.
      for (let width = Math.min(COVER_WIDTH, image.width); width >= 400; width = Math.round(width * 0.75)) {
        canvas.width = width;
        canvas.height = Math.max(1, Math.round((image.height * width) / image.width));
        context.fillStyle = '#fff';
        context.fillRect(0, 0, canvas.width, canvas.height);
        context.drawImage(image, 0, 0, canvas.width, canvas.height);
        const dataUrl = canvas.toDataURL('image/jpeg', 0.82);
        if (dataUrl.length <= COVER_MAX_CHARS) {
          resolve(dataUrl);
          return;
        }
      }
      reject(new Error('too-large'));
    };
    image.onerror = () => {
      URL.revokeObjectURL(url);
      reject(new Error('image'));
    };
    image.src = url;
  });
}

/** Réglages de la page d'accueil : message, photo de couverture, raccourcis. Réservé au propriétaire. */
@Component({
  selector: 'app-home-settings',
  imports: [FormsModule, RouterLink],
  styles: `
    :host {
      display: block;
      margin-top: 1.1rem;
    }
    .cover {
      display: grid;
      place-items: center;
      height: 9rem;
      border-radius: 14px;
      border: 1px dashed var(--line-strong);
      background: var(--surface-2) center / cover no-repeat;
      color: var(--muted);
    }
    .cover.has-photo {
      border-style: solid;
    }
    .picks {
      display: grid;
      grid-template-columns: repeat(auto-fill, minmax(15rem, 1fr));
      gap: 0.4rem 1rem;
    }
  `,
  template: `
    <form class="card form no-print" (ngSubmit)="save()">
      <h2>Page d'accueil</h2>
      <p class="muted">
        C'est l'écran sur lequel toute l'équipe arrive en ouvrant l'application.
        <a routerLink="/">Voir la page d'accueil</a>
      </p>

      @if (error(); as message) {
        <p class="alert error" role="alert">{{ message }}</p>
      }
      @if (notice(); as message) {
        <p class="alert success" role="status">{{ message }}</p>
      }

      <label>
        Message d'accueil (facultatif)
        <input
          name="welcomeMessage"
          [(ngModel)]="welcomeMessage"
          maxlength="140"
          placeholder="Bonne journée à toute l'équipe !"
        />
      </label>

      <div>
        <p class="field-title">Photo de couverture</p>
        <div
          class="cover"
          [class.has-photo]="cover()"
          [style.background-image]="cover() ? 'url(' + cover() + ')' : null"
        >
          @if (!cover()) {
            Aucune photo : le bandeau prend la couleur de la boutique.
          }
        </div>
        <div class="actions" style="margin-top: 0.6rem">
          <label class="button">
            Choisir une photo
            <input type="file" accept="image/png,image/jpeg,image/webp" hidden (change)="pickCover($event)" />
          </label>
          @if (cover()) {
            <button type="button" class="button" (click)="cover.set('')">Retirer la photo</button>
          }
        </div>
        <p class="muted small">Une photo de la devanture ou des rayons, plus large que haute, rend le mieux.</p>
      </div>

      <div>
        <p class="field-title">Raccourcis affichés</p>
        <div class="picks">
          @for (s of all; track s.id) {
            <label class="check">
              <input type="checkbox" [name]="'shortcut-' + s.id" [checked]="chosen().includes(s.id)" (change)="toggle(s.id)" />
              {{ s.label }}
              @if (s.manager) {
                <span class="muted small">(propriétaire et secrétaire)</span>
              }
            </label>
          }
        </div>
      </div>

      <div class="actions">
        <button type="submit" class="button primary" [disabled]="busy() || loading()">
          Enregistrer la page d'accueil
        </button>
      </div>
    </form>
  `,
})
export class HomeSettingsForm {
  private readonly api = inject(Api);

  protected readonly all = SHORTCUTS;
  protected readonly chosen = signal<string[]>([]);
  protected readonly loading = signal(true);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly notice = signal<string | null>(null);

  protected welcomeMessage = '';
  /** La photo arrive après une lecture de fichier : un signal, pour que l'écran se mette à jour. */
  protected readonly cover = signal('');

  constructor() {
    this.api.home().subscribe({
      next: settings => {
        this.welcomeMessage = settings.welcomeMessage;
        this.cover.set(settings.cover);
        this.chosen.set(settings.shortcuts);
        this.loading.set(false);
      },
      error: e => {
        this.error.set(errorMessage(e));
        this.loading.set(false);
      },
    });
  }

  /** Coche ou décoche un raccourci ; l'ordre d'affichage reste celui de la liste. */
  protected toggle(id: string): void {
    const now = this.chosen().includes(id) ? this.chosen().filter(x => x !== id) : [...this.chosen(), id];
    this.chosen.set(SHORTCUTS.map(s => s.id).filter(x => now.includes(x)));
    this.notice.set(null);
  }

  protected pickCover(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    input.value = '';
    if (!file) {
      return;
    }
    this.error.set(null);
    this.notice.set(null);
    shrinkPhoto(file).then(
      dataUrl => this.cover.set(dataUrl),
      () => this.error.set("Cette photo n'a pas pu être lue. Choisissez un fichier PNG ou JPEG."),
    );
  }

  protected save(): void {
    this.busy.set(true);
    this.error.set(null);
    this.notice.set(null);
    this.api
      .updateHome({ welcomeMessage: this.welcomeMessage.trim(), cover: this.cover(), shortcuts: this.chosen() })
      .subscribe({
        next: settings => {
          this.welcomeMessage = settings.welcomeMessage;
          this.cover.set(settings.cover);
          this.chosen.set(settings.shortcuts);
          this.notice.set("Page d'accueil enregistrée.");
          this.busy.set(false);
        },
        error: e => {
          this.error.set(errorMessage(e));
          this.busy.set(false);
        },
      });
  }
}
