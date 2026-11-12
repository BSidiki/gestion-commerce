import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { forkJoin } from 'rxjs';

import { Api, CashSession, errorMessage } from '../../core/api';
import { dateTime, fcfa } from '../../core/format';

@Component({
  selector: 'app-cash-closing',
  imports: [FormsModule],
  template: `
    <div class="page-head">
      <h1>Clôture de caisse</h1>
    </div>

    @if (error(); as message) {
      <p class="alert error" role="alert">{{ message }}</p>
    }
    @if (notice(); as message) {
      <p class="alert success" role="status">{{ message }}</p>
    }

    @if (loading()) {
      <p class="muted">Chargement…</p>
    } @else {
      @if (current(); as s) {
        <p class="muted">Caisse ouverte le {{ date(s.openedAt) }}.</p>
        <div class="stats">
          <div class="stat">
            <span class="label">Fond de caisse</span>
            <span class="value">{{ money(s.openingFloat) }}</span>
          </div>
          <div class="stat">
            <span class="label">Espèces encaissées</span>
            <span class="value">{{ money(s.cashIn) }}</span>
            <span class="muted">ventes et remboursements</span>
          </div>
          <div class="stat">
            <span class="label">Dépenses en espèces</span>
            <span class="value">{{ money(s.cashExpenses) }}</span>
          </div>
          <div class="stat">
            <span class="label">Espèces attendues</span>
            <span class="value">{{ money(s.expectedCash) }}</span>
          </div>
        </div>

        <form class="card form" (ngSubmit)="close(s)">
          <h2>Clôturer la caisse</h2>
          <div class="grid">
            <label>
              Espèces comptées dans le tiroir (F)
              <input
                name="counted"
                type="number"
                min="0"
                [ngModel]="counted()"
                (ngModelChange)="counted.set($event)"
                required
              />
            </label>
            <label>
              Note (facultatif)
              <input name="note" [(ngModel)]="note" placeholder="Explication d'un écart" />
            </label>
          </div>
          @if (difference(); as d) {
            @if (d.value === 0) {
              <p class="alert success">La caisse est juste.</p>
            } @else if (d.value < 0) {
              <p class="alert error">Il manque <strong>{{ money(-d.value) }}</strong> dans la caisse.</p>
            } @else {
              <p class="alert warning">
                Il y a <strong>{{ money(d.value) }}</strong> de trop dans la caisse.
              </p>
            }
          }
          <div class="actions">
            <button type="submit" class="button primary" [disabled]="saving()">Clôturer la caisse</button>
          </div>
        </form>
      } @else {
        <form class="card form" (ngSubmit)="open()">
          <h2>Ouvrir la caisse</h2>
          <p class="muted">
            Aucune caisse n'est ouverte. Les ventes restent possibles, mais elles ne seront comptées dans aucune
            clôture.
          </p>
          <div class="grid">
            <label>
              Fond de caisse : espèces dans le tiroir à l'ouverture (F)
              <input name="float" type="number" min="0" [(ngModel)]="openingFloat" required />
            </label>
          </div>
          <div class="actions">
            <button type="submit" class="button primary" [disabled]="saving()">Ouvrir la caisse</button>
          </div>
        </form>
      }

      <section class="card">
        <h2>Clôtures précédentes</h2>
        <table class="table">
          <thead>
            <tr>
              <th>Ouverture</th>
              <th>Clôture</th>
              <th class="num">Attendu</th>
              <th class="num">Compté</th>
              <th class="num">Écart</th>
              <th>Note</th>
            </tr>
          </thead>
          <tbody>
            @for (s of closed(); track s.id) {
              <tr>
                <td>{{ date(s.openedAt) }}</td>
                <td>{{ s.closedAt ? date(s.closedAt) : '—' }}</td>
                <td class="num">{{ money(s.expectedCash) }}</td>
                <td class="num">{{ s.countedCash !== null ? money(s.countedCash) : '—' }}</td>
                <td class="num" [class.negative]="(s.difference ?? 0) < 0">
                  {{ s.difference !== null ? signed(s.difference) : '—' }}
                </td>
                <td>{{ s.note || '—' }}</td>
              </tr>
            } @empty {
              <tr>
                <td colspan="6" class="muted">Aucune clôture pour l'instant.</td>
              </tr>
            }
          </tbody>
        </table>
      </section>
    }
  `,
})
export class CashClosing {
  private readonly api = inject(Api);

  protected readonly current = signal<CashSession | null>(null);
  protected readonly history = signal<CashSession[]>([]);
  protected readonly loading = signal(true);
  protected readonly saving = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly notice = signal<string | null>(null);
  protected readonly counted = signal<number | null>(null);

  protected openingFloat: number | null = 0;
  protected note = '';

  protected readonly closed = computed(() => this.history().filter(s => s.status === 'CLOTUREE'));

  /** Écart en direct pendant la saisie du montant compté. Enveloppé pour qu'un écart de 0 s'affiche. */
  protected readonly difference = computed(() => {
    const session = this.current();
    const counted = this.counted();
    return session && counted !== null ? { value: counted - session.expectedCash } : null;
  });

  protected readonly money = fcfa;
  protected readonly date = dateTime;

  constructor() {
    this.reload();
  }

  protected signed(amount: number): string {
    return amount > 0 ? `+${fcfa(amount)}` : fcfa(amount);
  }

  protected open(): void {
    if (this.openingFloat === null || this.openingFloat < 0 || !Number.isInteger(this.openingFloat)) {
      this.error.set('Indiquez le fond de caisse en francs entiers (0 si le tiroir est vide).');
      return;
    }
    this.saving.set(true);
    this.api.openCash(this.openingFloat).subscribe({
      next: () => this.done('Caisse ouverte.'),
      error: e => this.failed(e),
    });
  }

  protected close(session: CashSession): void {
    const counted = this.counted();
    if (counted === null || counted < 0 || !Number.isInteger(counted)) {
      this.error.set('Indiquez les espèces comptées, en francs entiers.');
      return;
    }
    this.saving.set(true);
    this.api.closeCash(counted, this.note.trim()).subscribe({
      next: closed => {
        const gap = closed.difference ?? 0;
        const verdict = gap === 0 ? 'la caisse est juste' : gap < 0 ? `il manque ${fcfa(-gap)}` : `${fcfa(gap)} de trop`;
        this.counted.set(null);
        this.note = '';
        this.done(`Caisse clôturée : ${verdict}.`);
      },
      error: e => this.failed(e),
    });
  }

  private done(message: string): void {
    this.saving.set(false);
    this.error.set(null);
    this.notice.set(message);
    this.reload();
  }

  private failed(error: unknown): void {
    this.saving.set(false);
    this.error.set(errorMessage(error));
  }

  private reload(): void {
    forkJoin({ status: this.api.cashStatus(), history: this.api.cashSessions() }).subscribe({
      next: ({ status, history }) => {
        this.current.set(status.open ? status.session : null);
        this.history.set(history);
        this.loading.set(false);
      },
      error: e => {
        this.error.set(errorMessage(e));
        this.loading.set(false);
      },
    });
  }
}
