import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';

import { Api, Sale, ShopSettings, errorMessage } from '../../core/api';
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

/** Coordonnées de la boutique et format du reçu. Réservé au propriétaire. */
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
          <h2>En-tête du reçu</h2>
          <p class="muted">Tout est facultatif : ce qui est vide n'apparaît pas sur le reçu.</p>
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
export class ShopSettingsPage {
  private readonly api = inject(Api);
  private readonly shop = inject(ShopState);

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
        this.notice.set('Réglages enregistrés : les prochains reçus les utiliseront.');
        this.busy.set(false);
      },
      error: e => {
        this.error.set(errorMessage(e));
        this.busy.set(false);
      },
    });
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
    };
  }

  private fill(settings: ShopSettings): void {
    this.name = settings.name;
    this.address = settings.address;
    this.phone = settings.phone;
    this.receiptFooter = settings.receiptFooter;
    this.receiptWidth = settings.receiptWidth;
    this.edits.update(n => n + 1);
  }
}
