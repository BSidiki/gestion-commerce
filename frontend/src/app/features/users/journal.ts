import { Component, inject, signal } from '@angular/core';

import { Api, AuditEntry, errorMessage } from '../../core/api';
import { AUDIT_LABELS, dateTime } from '../../core/format';

@Component({
  selector: 'app-journal',
  template: `
    <div class="page-head">
      <h1>Journal</h1>
    </div>
    <p class="muted">
      Les 200 dernières opérations sensibles : annulations, ventes sous le prix plancher, corrections de stock,
      comptes, restaurations. Ce journal ne peut pas être modifié.
    </p>

    @if (error(); as message) {
      <p class="alert error" role="alert">{{ message }}</p>
    }

    @if (loading()) {
      <p class="muted">Chargement…</p>
    } @else {
      <table class="table">
        <thead>
          <tr>
            <th>Date</th>
            <th>Par</th>
            <th>Opération</th>
            <th>Détail</th>
          </tr>
        </thead>
        <tbody>
          @for (entry of entries(); track $index) {
            <tr>
              <td>{{ date(entry.createdAt) }}</td>
              <td>{{ entry.username || '—' }}</td>
              <td>{{ label(entry.action) }}</td>
              <td>{{ entry.detail || '—' }}</td>
            </tr>
          } @empty {
            <tr>
              <td colspan="4" class="muted">Aucune opération pour l'instant.</td>
            </tr>
          }
        </tbody>
      </table>
    }
  `,
})
export class JournalPage {
  private readonly api = inject(Api);

  protected readonly entries = signal<AuditEntry[]>([]);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);
  protected readonly date = dateTime;

  constructor() {
    this.api.audit().subscribe({
      next: entries => {
        this.entries.set(entries);
        this.loading.set(false);
      },
      error: e => {
        this.error.set(errorMessage(e));
        this.loading.set(false);
      },
    });
  }

  protected label(action: string): string {
    return AUDIT_LABELS[action] ?? action;
  }
}
