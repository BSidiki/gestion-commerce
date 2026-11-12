import { Injectable, inject, signal } from '@angular/core';

import { Api, LicenseStatus } from './api';

/** État de la licence, partagé entre le bandeau du haut et la page Licence. */
@Injectable({ providedIn: 'root' })
export class LicenseState {
  private readonly api = inject(Api);

  readonly status = signal<LicenseStatus | null>(null);

  refresh(): void {
    this.api.licenseStatus().subscribe({
      next: status => this.status.set(status),
      // Sans réponse, pas de bandeau : les autres écrans signalent déjà que le service ne répond pas.
      error: () => this.status.set(null),
    });
  }
}
