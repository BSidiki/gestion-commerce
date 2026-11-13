import { Component, computed, input } from '@angular/core';

/** Tracés des icônes du menu, sur une grille de 24 : traits simples, sans remplissage. */
const PATHS: Record<string, string> = {
  caisse: 'M4 9h16l-1.2 10.2a1 1 0 0 1-1 .8H6.2a1 1 0 0 1-1-.8L4 9Zm4 0V7a4 4 0 0 1 8 0v2',
  ventes: 'M6 3h12v18l-3-2-3 2-3-2-3 2V3Zm3 5h6M9 12h6',
  clients:
    'M9 11a3.5 3.5 0 1 0 0-7 3.5 3.5 0 0 0 0 7Zm-6 9a6 6 0 0 1 12 0M16 4.5a3.5 3.5 0 0 1 0 6.5M18 14.5a6 6 0 0 1 3 5.5',
  depenses: 'M3 7h15a3 3 0 0 1 3 3v7a3 3 0 0 1-3 3H3V7Zm0 0 12-3v3m1.5 6.5h.01',
  cloture: 'M5 4h14v16H5V4Zm3 4h8M8.5 12h.01M12 12h.01M15.5 12h.01M8.5 16h.01M12 16h.01M15.5 16h.01',
  produits: 'm12 3 8 4.5v9L12 21l-8-4.5v-9L12 3Zm0 9 8-4.5M12 12v9m0-9L4 7.5',
  boutique: 'M4 10v10h16V10M3 10l1.5-6h15L21 10a3 3 0 0 1-6 0 3 3 0 0 1-6 0 3 3 0 0 1-6 0Zm7 10v-5h4v5',
  sauvegarde: 'M12 3 5 6v5c0 4.5 3 8.3 7 10 4-1.7 7-5.5 7-10V6l-7-3Zm-3 9 2 2 4-4',
  utilisateurs: 'M12 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8Zm-7 10a7 7 0 0 1 14 0',
  journal: 'M12 21a9 9 0 1 0-9-9m0-5v5h5m4-4v5l3 2',
  licence: 'M8 16a4 4 0 1 1 3.9-5H21v3h-2v2h-3v-2h-4.1A4 4 0 0 1 8 16Zm-.5-4h.01',
  compte: 'M12 12a4 4 0 1 0 0-8 4 4 0 0 0 0 8Zm-8 9a8 8 0 0 1 16 0',
  quitter: 'M10 4H5v16h5m4-12 4 4-4 4m4-4H9',
  menu: 'M4 7h16M4 12h16M4 17h16',
  accueil: 'm3 11 9-7 9 7M5.5 9.5V20h13V9.5M10 20v-5.5h4V20',
  devis: 'M7 3h7l4 4v14H7V3Zm7 0v4h4M10 12h5M10 16h5',
  inventaire: 'M9 4h6v3H9V4Zm0 1.5H6V21h12V5.5h-3M9 12l2 2 4-4M9 17.5h6',
  fournisseurs: 'M3 6h11v10H3V6Zm11 3h4l3 3.5V16h-7V9ZM7.5 19a2 2 0 1 0 0-4 2 2 0 0 0 0 4Zm10 0a2 2 0 1 0 0-4 2 2 0 0 0 0 4Z',
  rapports: 'M4 20V10m5.5 10V4M15 20v-7m5.5 7V8M3 20h18',
};

@Component({
  selector: 'app-icon',
  template: `
    <svg
      class="icon"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      stroke-width="1.7"
      stroke-linecap="round"
      stroke-linejoin="round"
      aria-hidden="true"
    >
      <path [attr.d]="path()" />
    </svg>
  `,
  styles: `
    :host {
      display: inline-flex;
    }
  `,
})
export class Icon {
  readonly name = input.required<string>();
  protected readonly path = computed(() => PATHS[this.name()] ?? '');
}
