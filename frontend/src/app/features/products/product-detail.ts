import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { forkJoin } from 'rxjs';

import { AdjustmentReason, Api, Movement, ProductDetail, errorMessage } from '../../core/api';
import {
  MOVEMENT_LABELS,
  PRICE_LEVEL_LABELS,
  REASON_LABELS,
  costFromMilli,
  dateTime,
  fcfa,
  quantity,
  stockBreakdown,
} from '../../core/format';

type Panel = 'none' | 'reception' | 'adjustment';

@Component({
  selector: 'app-product-detail',
  imports: [FormsModule, RouterLink],
  template: `
    <p><a routerLink="/produits">← Tous les produits</a></p>

    @if (error(); as message) {
      <p class="alert error" role="alert">{{ message }}</p>
    }
    @if (notice(); as message) {
      <p class="alert success" role="status">{{ message }}</p>
    }

    @if (detail(); as d) {
      <div class="page-head">
        <h1>
          {{ d.product.name }}
          @if (d.product.lowStock) {
            <span class="badge warning">Stock bas</span>
          }
        </h1>
        <div class="inline">
          <button class="button primary" (click)="open('reception')">Recevoir de la marchandise</button>
          <button class="button" (click)="open('adjustment')">Corriger le stock</button>
        </div>
      </div>

      <div class="stats">
        <div class="stat">
          <span class="label">Stock</span>
          <span class="value">{{ qty(d.product.stockQty) }} {{ d.product.baseUnit }}</span>
          @if (breakdown(); as text) {
            <span class="muted">soit {{ text }}</span>
          }
        </div>
        <div class="stat">
          <span class="label">Coût moyen par {{ d.product.baseUnit }}</span>
          <span class="value">{{ cost(d.product.avgCostMilli) }}</span>
        </div>
        <div class="stat">
          <span class="label">Valeur du stock</span>
          <span class="value">{{ stockValue() }}</span>
        </div>
        <div class="stat">
          <span class="label">Seuil d'alerte</span>
          <span class="value">{{ qty(d.product.alertThreshold) }}</span>
        </div>
      </div>

      @if (panel() === 'reception') {
        <form class="card form" (ngSubmit)="submitReception()">
          <h2>Recevoir de la marchandise</h2>
          <div class="grid">
            <label>
              Conditionnement reçu
              <select name="packagingId" [(ngModel)]="receptionPackagingId" required>
                @for (p of d.packagings; track p.id) {
                  <option [ngValue]="p.id">{{ p.name }} ({{ qty(p.factor) }} {{ d.product.baseUnit }})</option>
                }
              </select>
            </label>
            <label>
              Quantité reçue
              <input name="quantity" type="number" min="1" [(ngModel)]="receptionQuantity" required />
            </label>
            <label>
              Coût total payé (F)
              <input name="totalCost" type="number" min="0" [(ngModel)]="receptionTotalCost" required />
            </label>
            <label>
              Note (facultatif)
              <input name="note" [(ngModel)]="receptionNote" placeholder="Fournisseur, bon de livraison…" />
            </label>
          </div>
          <div class="actions">
            <button type="submit" class="button primary" [disabled]="saving()">Enregistrer la réception</button>
            <button type="button" class="button" (click)="open('none')">Annuler</button>
          </div>
        </form>
      }

      @if (panel() === 'adjustment') {
        <form class="card form" (ngSubmit)="submitAdjustment()">
          <h2>Corriger le stock</h2>
          <div class="grid">
            <label>
              Sens
              <select name="direction" [(ngModel)]="adjustmentDirection">
                <option [ngValue]="-1">Retirer du stock</option>
                <option [ngValue]="1">Ajouter au stock</option>
              </select>
            </label>
            <label>
              Quantité (en {{ d.product.baseUnit }})
              <input name="adjQuantity" type="number" min="1" [(ngModel)]="adjustmentQuantity" required />
            </label>
            <label>
              Motif
              <select name="reason" [(ngModel)]="adjustmentReason" required>
                @for (r of reasons; track r) {
                  <option [ngValue]="r">{{ reasonLabel(r) }}</option>
                }
              </select>
            </label>
            <label>
              Note (facultatif)
              <input name="adjNote" [(ngModel)]="adjustmentNote" />
            </label>
          </div>
          <div class="actions">
            <button type="submit" class="button primary" [disabled]="saving()">Enregistrer la correction</button>
            <button type="button" class="button" (click)="open('none')">Annuler</button>
          </div>
        </form>
      }

      <section class="card">
        <h2>Conditionnements et prix</h2>
        <table class="table">
          <thead>
            <tr>
              <th>Conditionnement</th>
              <th class="num">Contenu</th>
              <th>Prix de vente (plancher)</th>
              <th>Code-barres</th>
            </tr>
          </thead>
          <tbody>
            @for (p of d.packagings; track p.id) {
              <tr>
                <td>{{ p.name }}</td>
                <td class="num">{{ qty(p.factor) }} {{ d.product.baseUnit }}</td>
                <td>
                  @for (price of p.prices; track price.level) {
                    <div>{{ levelLabel(price.level) }} : {{ money(price.price) }} ({{ money(price.floorPrice) }})</div>
                  } @empty {
                    <span class="muted">Aucun prix</span>
                  }
                </td>
                <td>{{ p.barcodes.join(', ') || '—' }}</td>
              </tr>
            }
          </tbody>
        </table>
      </section>

      <section class="card">
        <h2>Historique des mouvements</h2>
        <table class="table">
          <thead>
            <tr>
              <th>Date</th>
              <th>Mouvement</th>
              <th class="num">Quantité</th>
              <th class="num">Stock après</th>
              <th>Note</th>
            </tr>
          </thead>
          <tbody>
            @for (m of movements(); track m.id) {
              <tr>
                <td>{{ date(m.createdAt) }}</td>
                <td>{{ movementLabel(m) }}</td>
                <td class="num" [class.negative]="m.quantity < 0">{{ signed(m.quantity) }}</td>
                <td class="num">{{ qty(m.stockAfter) }}</td>
                <td>{{ m.note || '—' }}</td>
              </tr>
            } @empty {
              <tr>
                <td colspan="5" class="muted">Aucun mouvement pour l'instant.</td>
              </tr>
            }
          </tbody>
        </table>
      </section>
    } @else if (!error()) {
      <p class="muted">Chargement…</p>
    }
  `,
})
export class ProductDetailPage {
  private readonly api = inject(Api);
  private readonly productId = inject(ActivatedRoute).snapshot.paramMap.get('id') ?? '';

  protected readonly detail = signal<ProductDetail | null>(null);
  protected readonly movements = signal<Movement[]>([]);
  protected readonly error = signal<string | null>(null);
  protected readonly notice = signal<string | null>(null);
  protected readonly panel = signal<Panel>('none');
  protected readonly saving = signal(false);

  protected readonly reasons: AdjustmentReason[] = ['CASSE', 'PERTE', 'VOL', 'ERREUR_SAISIE', 'INVENTAIRE'];

  protected receptionPackagingId: string | null = null;
  protected receptionQuantity: number | null = null;
  protected receptionTotalCost: number | null = null;
  protected receptionNote = '';

  protected adjustmentDirection = -1;
  protected adjustmentQuantity: number | null = null;
  protected adjustmentReason: AdjustmentReason = 'CASSE';
  protected adjustmentNote = '';

  protected readonly breakdown = computed(() => {
    const d = this.detail();
    return d ? stockBreakdown(d.product.stockQty, d.product.baseUnit, d.packagings) : null;
  });

  protected readonly stockValue = computed(() => {
    const d = this.detail();
    return d ? costFromMilli(Math.max(d.product.stockQty, 0) * d.product.avgCostMilli) : '';
  });

  protected readonly qty = quantity;
  protected readonly cost = costFromMilli;
  protected readonly money = fcfa;
  protected readonly date = dateTime;

  constructor() {
    this.reload();
  }

  protected levelLabel(level: keyof typeof PRICE_LEVEL_LABELS): string {
    return PRICE_LEVEL_LABELS[level];
  }

  protected reasonLabel(reason: string): string {
    return REASON_LABELS[reason] ?? reason;
  }

  protected movementLabel(m: Movement): string {
    const label = MOVEMENT_LABELS[m.type] ?? m.type;
    return m.reason ? `${label} · ${this.reasonLabel(m.reason)}` : label;
  }

  protected signed(value: number): string {
    return value > 0 ? `+${quantity(value)}` : quantity(value);
  }

  protected open(panel: Panel): void {
    this.panel.set(panel);
    this.error.set(null);
    this.notice.set(null);
    if (panel === 'reception' && !this.receptionPackagingId) {
      // Par défaut, le plus grand conditionnement : c'est le plus souvent celui qu'on reçoit.
      const packagings = this.detail()?.packagings ?? [];
      this.receptionPackagingId = packagings.at(-1)?.id ?? null;
    }
  }

  protected submitReception(): void {
    if (!this.receptionPackagingId || !this.receptionQuantity || this.receptionQuantity < 1) {
      this.error.set('Indiquez le conditionnement et une quantité reçue supérieure à zéro.');
      return;
    }
    if (this.receptionTotalCost == null || this.receptionTotalCost < 0) {
      this.error.set('Indiquez le coût total payé pour cette réception.');
      return;
    }
    this.saving.set(true);
    this.api
      .receive({
        productId: this.productId,
        packagingId: this.receptionPackagingId,
        quantity: this.receptionQuantity,
        totalCost: this.receptionTotalCost,
        ...(this.receptionNote.trim() ? { note: this.receptionNote.trim() } : {}),
      })
      .subscribe({
        next: () => {
          this.receptionQuantity = null;
          this.receptionTotalCost = null;
          this.receptionNote = '';
          this.done('Réception enregistrée.');
        },
        error: e => this.failed(e),
      });
  }

  protected submitAdjustment(): void {
    if (!this.adjustmentQuantity || this.adjustmentQuantity < 1) {
      this.error.set('Indiquez une quantité supérieure à zéro.');
      return;
    }
    this.saving.set(true);
    this.api
      .adjust({
        productId: this.productId,
        quantityDelta: this.adjustmentDirection * this.adjustmentQuantity,
        reason: this.adjustmentReason,
        ...(this.adjustmentNote.trim() ? { note: this.adjustmentNote.trim() } : {}),
      })
      .subscribe({
        next: () => {
          this.adjustmentQuantity = null;
          this.adjustmentNote = '';
          this.done('Correction de stock enregistrée.');
        },
        error: e => this.failed(e),
      });
  }

  private done(message: string): void {
    this.saving.set(false);
    this.panel.set('none');
    this.error.set(null);
    this.notice.set(message);
    this.reload();
  }

  private failed(error: unknown): void {
    this.saving.set(false);
    this.error.set(errorMessage(error));
  }

  private reload(): void {
    forkJoin({
      detail: this.api.product(this.productId),
      movements: this.api.movements(this.productId),
    }).subscribe({
      next: ({ detail, movements }) => {
        this.detail.set(detail);
        this.movements.set(movements);
      },
      error: e => this.error.set(errorMessage(e)),
    });
  }
}
