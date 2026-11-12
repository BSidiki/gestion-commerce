import { Injectable, inject, signal } from '@angular/core';

import { Api, ShopSettings } from './api';

/** Coordonnées de la boutique et format du reçu, lus une fois puis partagés entre les écrans. */
@Injectable({ providedIn: 'root' })
export class ShopState {
  private readonly api = inject(Api);
  private requested = false;

  readonly settings = signal<ShopSettings | null>(null);

  /** Charge les réglages s'ils ne l'ont pas encore été. Sans réponse, le reçu sort sans en-tête. */
  ensureLoaded(): void {
    if (this.requested) {
      return;
    }
    this.requested = true;
    this.api.shop().subscribe({
      next: settings => this.settings.set(settings),
      error: () => (this.requested = false),
    });
  }

  set(settings: ShopSettings): void {
    this.requested = true;
    this.settings.set(settings);
  }
}
