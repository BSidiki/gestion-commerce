import { Injectable, computed, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { Api, Me } from './api';

/** Qui est connecté. Partagé par le menu, les gardes de navigation et les écrans. */
@Injectable({ providedIn: 'root' })
export class AuthState {
  private readonly api = inject(Api);
  private loading: Promise<Me> | null = null;

  readonly me = signal<Me | null>(null);
  readonly user = computed(() => this.me()?.user ?? null);
  readonly isOwner = computed(() => this.user()?.role === 'PROPRIETAIRE');

  /** État de connexion, demandé au serveur une seule fois puis gardé en mémoire. */
  ensureLoaded(): Promise<Me> {
    const known = this.me();
    if (known) {
      return Promise.resolve(known);
    }
    this.loading ??= firstValueFrom(this.api.me()).then(
      me => {
        this.me.set(me);
        this.loading = null;
        return me;
      },
      error => {
        this.loading = null;
        throw error;
      },
    );
    return this.loading;
  }

  set(me: Me): void {
    this.me.set(me);
  }

  /** La session n'est plus valable : on oublie la personne, pas le fait que des comptes existent. */
  signedOut(): void {
    this.me.set({ setupRequired: false, user: null });
  }
}
