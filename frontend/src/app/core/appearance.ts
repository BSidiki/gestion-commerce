import { Injectable, inject, signal } from '@angular/core';

import { Api, Branding } from './api';

export type Theme = 'auto' | 'clair' | 'sombre';
export type TextScale = 'normale' | 'grande' | 'tres-grande';

/** Les couleurs proposées. Les teintes sont définies dans styles.css, sous le même nom. */
export const ACCENTS: { id: string; label: string; color: string }[] = [
  { id: 'lagune', label: 'Lagune', color: '#0e7c7b' },
  { id: 'ocean', label: 'Océan', color: '#1766b8' },
  { id: 'indigo', label: 'Indigo', color: '#4250cf' },
  { id: 'violette', label: 'Violette', color: '#7044c4' },
  { id: 'bissap', label: 'Bissap', color: '#b0234f' },
  { id: 'braise', label: 'Braise', color: '#bf4310' },
  { id: 'karite', label: 'Karité', color: '#946508' },
  { id: 'foret', label: 'Forêt', color: '#1f7a45' },
  { id: 'ardoise', label: 'Ardoise', color: '#3c4a5c' },
];

const THEME_KEY = 'gc.theme';
const SCALE_KEY = 'gc.scale';
const DEFAULT_NAME = 'Gestion commerciale';

/**
 * L'apparence de l'application.
 * - Nom, logo et couleur appartiennent à la boutique : ils viennent du service et valent pour tous les postes.
 * - Thème clair ou sombre et taille du texte sont des réglages du poste : ils restent dans le navigateur.
 */
@Injectable({ providedIn: 'root' })
export class Appearance {
  private readonly api = inject(Api);
  private readonly root = document.documentElement;
  private readonly systemDark = window.matchMedia('(prefers-color-scheme: dark)');

  readonly shopName = signal('');
  readonly logo = signal('');
  readonly accent = signal('lagune');
  readonly theme = signal<Theme>(this.stored(THEME_KEY, ['auto', 'clair', 'sombre'], 'auto'));
  readonly scale = signal<TextScale>(this.stored(SCALE_KEY, ['normale', 'grande', 'tres-grande'], 'normale'));

  constructor() {
    this.applyDevice();
    // En mode automatique, l'application suit le réglage de Windows, y compris s'il change en cours de route.
    this.systemDark.addEventListener('change', () => this.applyDevice());
    this.refresh();
  }

  /** Nom affiché en tête du menu : celui de la boutique, sinon celui du logiciel. */
  displayName(): string {
    return this.shopName() || DEFAULT_NAME;
  }

  /** Relit le nom, le logo et la couleur enregistrés. Sans réponse, l'apparence d'origine reste en place. */
  refresh(): void {
    this.api.branding().subscribe({
      next: branding => this.setBranding(branding),
      error: () => undefined,
    });
  }

  setBranding(branding: Branding): void {
    this.shopName.set(branding.name);
    this.logo.set(branding.logo);
    this.previewAccent(branding.accentColor);
  }

  /** Applique une couleur à l'écran sans l'enregistrer : sert à l'essai dans la page Boutique. */
  previewAccent(accent: string): void {
    this.accent.set(accent);
    this.root.dataset['accent'] = accent;
  }

  setTheme(theme: Theme): void {
    this.theme.set(theme);
    this.store(THEME_KEY, theme);
    this.applyDevice();
  }

  setScale(scale: TextScale): void {
    this.scale.set(scale);
    this.store(SCALE_KEY, scale);
    this.applyDevice();
  }

  private applyDevice(): void {
    const dark = this.theme() === 'sombre' || (this.theme() === 'auto' && this.systemDark.matches);
    this.root.dataset['theme'] = dark ? 'dark' : 'light';
    this.root.dataset['scale'] = this.scale();
  }

  private stored<T extends string>(key: string, allowed: T[], fallback: T): T {
    try {
      const value = localStorage.getItem(key) as T | null;
      return value && allowed.includes(value) ? value : fallback;
    } catch {
      return fallback;
    }
  }

  private store(key: string, value: string): void {
    try {
      localStorage.setItem(key, value);
    } catch {
      // Navigation privée ou stockage refusé : le réglage vaut pour cette session seulement.
    }
  }
}
