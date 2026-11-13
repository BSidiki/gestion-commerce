import { Component, OnDestroy, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';

import { Api, Sale, ShopSettings, errorMessage } from '../../core/api';
import { ACCENTS, Appearance } from '../../core/appearance';
import { ShopState } from '../../core/shop-state';
import { ReceiptView } from '../sales/receipt-view';

/** Une vente d'exemple, pour voir le reçu sans rien encaisser. */
const SAMPLE: Sale = {
  id: 'exemple',
  number: 128,
  customerId: null,
  total: 7700,
  paid: 5000,
  credit: 2700,
  status: 'VALIDEE',
  businessDate: '2026-01-15',
  createdAt: '2026-01-15T10:42:00.000Z',
  cancelReason: null,
  items: [
    { productName: 'Savon de Marseille', packagingName: 'Carton', quantity: 1, unitPrice: 6500, lineTotal: 6500 },
    { productName: 'Riz parfumé', packagingName: 'kg', quantity: 2, unitPrice: 600, lineTotal: 1200 },
  ],
  payments: [{ method: 'ESPECES', amount: 5000 }],
} as unknown as Sale;

const LOGO_SIDE = 320;
const LOGO_MAX_CHARS = 380_000;

/**
 * Réduit l'image choisie à 320 points de côté au plus, pour qu'elle reste légère :
 * en PNG pour garder la transparence, en JPEG sur fond blanc si le PNG pèse trop.
 */
function shrinkImage(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const url = URL.createObjectURL(file);
    const image = new Image();
    image.onload = () => {
      URL.revokeObjectURL(url);
      const ratio = Math.min(1, LOGO_SIDE / Math.max(image.width, image.height));
      const canvas = document.createElement('canvas');
      canvas.width = Math.max(1, Math.round(image.width * ratio));
      canvas.height = Math.max(1, Math.round(image.height * ratio));
      const context = canvas.getContext('2d');
      if (!context) {
        reject(new Error('canvas'));
        return;
      }
      context.drawImage(image, 0, 0, canvas.width, canvas.height);
      let dataUrl = canvas.toDataURL('image/png');
      if (dataUrl.length > LOGO_MAX_CHARS) {
        context.globalCompositeOperation = 'destination-over';
        context.fillStyle = '#fff';
        context.fillRect(0, 0, canvas.width, canvas.height);
        dataUrl = canvas.toDataURL('image/jpeg', 0.85);
      }
      resolve(dataUrl);
    };
    image.onerror = () => {
      URL.revokeObjectURL(url);
      reject(new Error('image'));
    };
    image.src = url;
  });
}

/** Coordonnées, apparence de l'application et format du reçu. Réservé au propriétaire. */
@Component({
  selector: 'app-shop-settings',
  imports: [FormsModule, ReceiptView],
  styles: `
    .layout {
      display: grid;
      grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
      gap: 1.5rem;
      align-items: start;
    }
    @media (max-width: 900px) {
      .layout {
        grid-template-columns: 1fr;
      }
    }
    .preview h2 {
      margin-top: 0;
    }
  `,
  template: `
    <div class="page-head no-print">
      <h1>Boutique</h1>
    </div>

    @if (error(); as message) {
      <p class="alert error no-print" role="alert">{{ message }}</p>
    }
    @if (notice(); as message) {
      <p class="alert success no-print" role="status">{{ message }}</p>
    }

    @if (loading()) {
      <p class="muted">Chargement…</p>
    } @else {
      <div class="layout">
        <form class="card form no-print" (ngSubmit)="save()">
          <h2>Coordonnées</h2>
          <p class="muted">
            Elles s'impriment en tête du reçu ; le nom s'affiche aussi dans le menu. Tout est facultatif.
          </p>
          <label>
            Nom de la boutique
            <input name="name" [(ngModel)]="name" (ngModelChange)="touch()" maxlength="80" />
          </label>
          <label>
            Adresse ou repère
            <input
              name="address"
              [(ngModel)]="address"
              (ngModelChange)="touch()"
              maxlength="80"
              placeholder="Quartier, marché, ville"
            />
          </label>
          <label>
            Téléphone
            <input name="phone" [(ngModel)]="phone" (ngModelChange)="touch()" maxlength="80" />
          </label>
          <label>
            Phrase de fin de reçu
            <input
              name="receiptFooter"
              [(ngModel)]="receiptFooter"
              (ngModelChange)="touch()"
              maxlength="200"
              placeholder="Merci de votre visite !"
            />
          </label>

          <h2>Apparence</h2>
          <div>
            <p class="field-title" id="accent-title">Couleur de l'application</p>
            <div class="swatches" role="radiogroup" aria-labelledby="accent-title">
              @for (option of accents; track option.id) {
                <label class="swatch" [style.--dot]="option.color">
                  <input
                    type="radio"
                    name="accentColor"
                    [value]="option.id"
                    [checked]="accentColor === option.id"
                    (change)="pickAccent(option.id)"
                  />
                  <span class="dot"></span>
                  {{ option.label }}
                </label>
              }
            </div>
          </div>
          <div>
            <p class="field-title">Logo</p>
            <div class="logo-row">
              <span class="logo-preview">
                @if (logo) {
                  <img [src]="logo" alt="Logo de la boutique" />
                } @else {
                  Aucun logo
                }
              </span>
              <label class="button">
                Choisir une image
                <input type="file" accept="image/png,image/jpeg,image/webp" hidden (change)="pickLogo($event)" />
              </label>
              @if (logo) {
                <button type="button" class="button" (click)="removeLogo()">Retirer le logo</button>
              }
            </div>
            <p class="muted small">
              Une image carrée rend le mieux. Elle apparaît dans le menu, à la connexion et sur le reçu.
            </p>
          </div>

          <h2>Papier</h2>
          <label>
            Format d'impression du reçu
            <select name="receiptWidth" [(ngModel)]="receiptWidth" (ngModelChange)="touch()">
              <option value="80">Imprimante thermique, rouleau de 80 mm</option>
              <option value="58">Imprimante thermique, rouleau de 58 mm</option>
              <option value="A4">Imprimante de bureau, feuille A4</option>
            </select>
          </label>
          <p class="muted small">
            L'imprimante doit être installée dans Windows. À la première impression, choisissez-la dans la
            fenêtre d'impression, mettez les marges sur « Aucune » et décochez « En-têtes et pieds de page ».
          </p>

          <div class="actions">
            <button type="submit" class="button primary" [disabled]="busy()">Enregistrer</button>
            <button type="button" class="button" (click)="print()">Imprimer un essai</button>
          </div>
        </form>

        <div class="preview">
          <h2 class="no-print">Aperçu du reçu</h2>
          <app-receipt [sale]="sample" customerName="Client d'exemple" [preview]="draft()" />
        </div>
      </div>
    }
  `,
})
export class ShopSettingsPage implements OnDestroy {
  private readonly api = inject(Api);
  private readonly shop = inject(ShopState);
  private readonly look = inject(Appearance);

  protected readonly accents = ACCENTS;

  protected readonly sample = SAMPLE;
  protected readonly loading = signal(true);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly notice = signal<string | null>(null);

  protected name = '';
  protected address = '';
  protected phone = '';
  protected receiptFooter = '';
  protected receiptWidth = '80';
  protected accentColor = 'lagune';
  protected logo = '';

  /** Compteur de frappes : l'aperçu se recalcule à chaque modification du formulaire. */
  private readonly edits = signal(0);
  protected readonly draft = computed<ShopSettings>(() => {
    this.edits();
    return this.current();
  });

  constructor() {
    this.api.shop().subscribe({
      next: settings => {
        this.fill(settings);
        this.loading.set(false);
      },
      error: e => {
        this.error.set(errorMessage(e));
        this.loading.set(false);
      },
    });
  }

  protected touch(): void {
    this.notice.set(null);
    this.edits.update(n => n + 1);
  }

  protected save(): void {
    this.busy.set(true);
    this.error.set(null);
    this.notice.set(null);
    this.api.updateShop(this.current()).subscribe({
      next: settings => {
        this.fill(settings);
        this.shop.set(settings);
        this.look.setBranding(settings);
        this.notice.set('Réglages enregistrés.');
        this.busy.set(false);
      },
      error: e => {
        this.error.set(errorMessage(e));
        this.busy.set(false);
      },
    });
  }

  /** Quitter la page sans enregistrer rend à l'application sa couleur enregistrée. */
  ngOnDestroy(): void {
    this.look.refresh();
  }

  /** La couleur s'essaie tout de suite à l'écran ; elle n'est gardée qu'à l'enregistrement. */
  protected pickAccent(accent: string): void {
    this.accentColor = accent;
    this.look.previewAccent(accent);
    this.touch();
  }

  protected pickLogo(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    input.value = '';
    if (!file) {
      return;
    }
    this.error.set(null);
    shrinkImage(file).then(
      dataUrl => {
        this.logo = dataUrl;
        this.touch();
      },
      () => this.error.set("Cette image n'a pas pu être lue. Choisissez un fichier PNG ou JPEG."),
    );
  }

  protected removeLogo(): void {
    this.logo = '';
    this.touch();
  }

  protected print(): void {
    window.print();
  }

  private current(): ShopSettings {
    return {
      name: this.name.trim(),
      address: this.address.trim(),
      phone: this.phone.trim(),
      receiptFooter: this.receiptFooter.trim(),
      receiptWidth: this.receiptWidth as ShopSettings['receiptWidth'],
      accentColor: this.accentColor,
      logo: this.logo,
    };
  }

  private fill(settings: ShopSettings): void {
    this.name = settings.name;
    this.address = settings.address;
    this.phone = settings.phone;
    this.receiptFooter = settings.receiptFooter;
    this.receiptWidth = settings.receiptWidth;
    this.accentColor = settings.accentColor;
    this.logo = settings.logo;
    this.edits.update(n => n + 1);
  }
}
