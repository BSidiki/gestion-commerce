import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';

import { Api, PaymentMethod, Purchase, SupplierDetail, errorMessage } from '../../core/api';
import { AuthState } from '../../core/auth-state';
import { PAYMENT_LABELS, dateTime, fcfa, quantity } from '../../core/format';

/** Un fournisseur : ce qu'on lui doit, le payer, et l'historique de ses livraisons. */
@Component({
  selector: 'app-supplier-detail',
  imports: [FormsModule, RouterLink],
  template: `
    @if (detail(); as d) {
      <div class="page-head">
        <h1>{{ d.supplier.name }}</h1>
        <div class="actions">
          <a class="button" routerLink="/fournisseurs">Tous les fournisseurs</a>
          <a class="button primary" [routerLink]="['/fournisseurs', d.supplier.id, 'achat']">Nouvel achat</a>
        </div>
      </div>

      @if (error(); as message) {
        <p class="alert error" role="alert">{{ message }}</p>
      }
      @if (notice(); as message) {
        <p class="alert success" role="status">{{ message }}</p>
      }

      <div class="stats">
        <div class="stat">
          <span class="label">{{ d.supplier.balance < 0 ? 'Avoir chez ce fournisseur' : 'Reste à payer' }}</span>
          <span class="value">{{ money(abs(d.supplier.balance)) }}</span>
          <span class="muted">{{ d.supplier.phone || 'Pas de téléphone enregistré' }}</span>
        </div>
      </div>

      @if (d.supplier.balance > 0) {
        <form class="card form" (ngSubmit)="pay(d)">
          <h2>Payer ce fournisseur</h2>
          <div class="grid">
            <label>
              Montant (F)
              <input name="amount" type="number" min="1" [max]="d.supplier.balance" [(ngModel)]="amount" required />
            </label>
            <label>
              Moyen de paiement
              <select name="method" [(ngModel)]="method">
                <option value="ESPECES">Espèces</option>
                <option value="MOBILE_MONEY">Mobile Money</option>
              </select>
            </label>
            <label>
              Note (facultatif)
              <input name="note" [(ngModel)]="note" />
            </label>
          </div>
          @if (method === 'ESPECES') {
            <label class="check">
              <input type="checkbox" name="fromCashDrawer" [(ngModel)]="fromCashDrawer" />
              L'argent sort du tiroir de la caisse (il comptera à la clôture)
            </label>
          }
          <div class="actions">
            <button type="submit" class="button primary" [disabled]="saving() || !amount">Enregistrer le paiement</button>
            <button type="button" class="button" (click)="amount = d.supplier.balance">Tout payer</button>
          </div>
        </form>
      }

      <form class="card form" (ngSubmit)="save(d)">
        <h2>Coordonnées</h2>
        <div class="grid">
          <label>
            Nom
            <input name="name" [(ngModel)]="name" required />
          </label>
          <label>
            Téléphone
            <input name="phone" [(ngModel)]="phone" />
          </label>
          <label>
            Note
            <input name="supplierNote" [(ngModel)]="supplierNote" placeholder="Jour de livraison, conditions…" />
          </label>
        </div>
        <div class="actions">
          <button type="submit" class="button" [disabled]="saving() || !name.trim()">Enregistrer</button>
        </div>
      </form>

      <h2>Historique</h2>
      <table class="table">
        <thead>
          <tr>
            <th>Date</th>
            <th>Opération</th>
            <th class="num">Achat</th>
            <th class="num">Paiement</th>
            <th></th>
          </tr>
        </thead>
        <tbody>
          @for (e of d.entries; track $index) {
            <tr [class.cancelled]="e.cancelled">
              <td>{{ date(e.createdAt) }}</td>
              <td>
                @if (e.type === 'ACHAT') {
                  Achat n° {{ e.purchaseNumber }}
                  @if (e.cancelled) {
                    <span class="badge warning">Annulé</span>
                  }
                } @else {
                  Paiement en {{ methodLabel(e.method) }}
                  @if (e.purchaseNumber) {
                    <span class="muted">à la livraison n° {{ e.purchaseNumber }}</span>
                  }
                  @if (e.fromCashDrawer) {
                    <span class="badge neutral">Caisse</span>
                  }
                }
                @if (e.note) {
                  <div class="muted small">{{ e.note }}</div>
                }
              </td>
              <td class="num">
                @if (e.type === 'ACHAT') {
                  @if (e.cancelled) {
                    <s>{{ money(e.amount) }}</s>
                  } @else {
                    {{ money(e.amount) }}
                  }
                }
              </td>
              <td class="num">{{ e.type === 'PAIEMENT' ? money(e.amount) : '' }}</td>
              <td class="row-actions">
                @if (e.type === 'ACHAT' && e.purchaseId) {
                  <button type="button" class="button" (click)="toggle(e.purchaseId)">
                    {{ opened()?.id === e.purchaseId ? 'Masquer' : 'Détail' }}
                  </button>
                }
              </td>
            </tr>
            @if (e.type === 'ACHAT' && opened()?.id === e.purchaseId) {
              <tr>
                <td colspan="5">
                  <table class="table compact">
                    <tbody>
                      @for (item of opened()!.items; track $index) {
                        <tr>
                          <td>{{ item.productName }}</td>
                          <td class="num">{{ qty(item.quantity) }} {{ item.packagingName }}</td>
                          <td class="num">{{ money(item.totalCost) }}</td>
                        </tr>
                      }
                    </tbody>
                  </table>
                  @if (opened()!.status === 'ANNULE') {
                    <p class="muted">Achat annulé : {{ opened()!.cancelReason }}</p>
                  } @else if (auth.isOwner()) {
                    <div class="inline">
                      <input
                        name="cancelReason"
                        [(ngModel)]="cancelReason"
                        placeholder="Motif de l'annulation"
                        aria-label="Motif de l'annulation"
                      />
                      <button
                        type="button"
                        class="button danger"
                        (click)="cancelPurchase(opened()!)"
                        [disabled]="saving() || !cancelReason.trim()"
                      >
                        Annuler cet achat
                      </button>
                    </div>
                    <p class="muted small">
                      La marchandise ressort du stock et l'achat ne compte plus dans la dette. Impossible si une
                      partie a déjà été vendue.
                    </p>
                  }
                </td>
              </tr>
            }
          } @empty {
            <tr>
              <td colspan="5" class="muted">Aucun achat enregistré chez ce fournisseur.</td>
            </tr>
          }
        </tbody>
      </table>
    } @else if (error(); as message) {
      <p class="alert error" role="alert">{{ message }}</p>
    } @else {
      <p class="muted">Chargement…</p>
    }
  `,
})
export class SupplierDetailPage {
  private readonly api = inject(Api);
  protected readonly auth = inject(AuthState);
  private readonly supplierId = inject(ActivatedRoute).snapshot.paramMap.get('id') ?? '';

  protected readonly detail = signal<SupplierDetail | null>(null);
  protected readonly opened = signal<Purchase | null>(null);
  protected readonly saving = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly notice = signal<string | null>(null);

  protected amount: number | null = null;
  protected method: PaymentMethod = 'ESPECES';
  protected fromCashDrawer = false;
  protected note = '';
  protected name = '';
  protected phone = '';
  protected supplierNote = '';
  protected cancelReason = '';

  protected readonly money = fcfa;
  protected readonly qty = quantity;
  protected readonly date = dateTime;

  constructor() {
    this.reload(true);
  }

  protected methodLabel(method: string | null): string {
    return method ? (PAYMENT_LABELS[method] ?? method) : '';
  }

  protected pay(d: SupplierDetail): void {
    const amount = Number(this.amount);
    if (!Number.isInteger(amount) || amount <= 0) {
      this.error.set('Saisissez un montant entier supérieur à zéro.');
      return;
    }
    this.start();
    this.api
      .paySupplier(d.supplier.id, {
        amount,
        method: this.method,
        fromCashDrawer: this.method === 'ESPECES' && this.fromCashDrawer,
        note: this.note.trim() || null,
      })
      .subscribe({
        next: () => {
          this.amount = null;
          this.note = '';
          this.fromCashDrawer = false;
          this.done(`Paiement de ${fcfa(amount)} enregistré.`);
        },
        error: e => this.failed(e),
      });
  }

  protected save(d: SupplierDetail): void {
    this.start();
    this.api
      .updateSupplier(d.supplier.id, {
        name: this.name.trim(),
        phone: this.phone.trim() || null,
        note: this.supplierNote.trim() || null,
      })
      .subscribe({
        next: () => this.done('Coordonnées enregistrées.'),
        error: e => this.failed(e),
      });
  }

  protected abs(value: number): number {
    return Math.abs(value);
  }

  protected cancelPurchase(purchase: Purchase): void {
    this.start();
    this.api.cancelPurchase(purchase.id, this.cancelReason.trim()).subscribe({
      next: cancelled => {
        this.cancelReason = '';
        this.opened.set(cancelled);
        this.done(`Achat n° ${cancelled.number} annulé : la marchandise est ressortie du stock.`);
      },
      error: e => this.failed(e),
    });
  }

  protected toggle(purchaseId: string): void {
    if (this.opened()?.id === purchaseId) {
      this.opened.set(null);
      return;
    }
    this.api.purchase(purchaseId).subscribe({
      next: purchase => this.opened.set(purchase),
      error: e => this.error.set(errorMessage(e)),
    });
  }

  private start(): void {
    this.saving.set(true);
    this.error.set(null);
    this.notice.set(null);
  }

  private done(message: string): void {
    this.saving.set(false);
    this.notice.set(message);
    this.reload(false);
  }

  private failed(error: unknown): void {
    this.saving.set(false);
    this.error.set(errorMessage(error));
  }

  private reload(fillForm: boolean): void {
    this.api.supplier(this.supplierId).subscribe({
      next: detail => {
        this.detail.set(detail);
        if (fillForm) {
          this.name = detail.supplier.name;
          this.phone = detail.supplier.phone ?? '';
          this.supplierNote = detail.supplier.note ?? '';
        }
      },
      error: e => this.error.set(errorMessage(e)),
    });
  }
}
