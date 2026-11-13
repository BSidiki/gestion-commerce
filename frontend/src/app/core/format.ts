import { Packaging, PriceLevel } from './api';

const numberFormat = new Intl.NumberFormat('fr-FR');
const costFormat = new Intl.NumberFormat('fr-FR', { maximumFractionDigits: 2 });
const dateFormat = new Intl.DateTimeFormat('fr-FR', { dateStyle: 'short', timeStyle: 'short' });

export function fcfa(amount: number): string {
  return `${numberFormat.format(amount)} F`;
}

/** Coût en millièmes de franc, affiché en francs avec au plus deux décimales. */
export function costFromMilli(milli: number): string {
  return `${costFormat.format(milli / 1000)} F`;
}

export function quantity(value: number): string {
  return numberFormat.format(value);
}

const dayFormat = new Intl.DateTimeFormat('fr-FR', { dateStyle: 'long' });

/** Date seule (AAAA-MM-JJ) en toutes lettres : « 12 février 2027 ». */
export function day(isoDate: string): string {
  const [year, month, dayOfMonth] = isoDate.split('-').map(Number);
  return dayFormat.format(new Date(year, month - 1, dayOfMonth));
}

export function dateTime(iso: string): string {
  return dateFormat.format(new Date(iso));
}

export const PRICE_LEVEL_LABELS: Record<PriceLevel, string> = {
  DETAIL: 'Détail',
  DEMI_GROS: 'Demi-gros',
  GROS: 'Gros',
};

export const REASON_LABELS: Record<string, string> = {
  PERTE: 'Perte',
  CASSE: 'Casse',
  VOL: 'Vol',
  ERREUR_SAISIE: 'Erreur de saisie',
  INVENTAIRE: 'Inventaire',
};

export const MOVEMENT_LABELS: Record<string, string> = {
  RECEPTION: 'Réception',
  VENTE: 'Vente',
  AJUSTEMENT: 'Ajustement',
  ANNULATION: 'Annulation',
};

/**
 * Stock exprimé dans le plus grand conditionnement, par exemple
 * 67 pièces avec un carton de 24 : « 2 Carton + 19 pièce ».
 */
export function stockBreakdown(stockQty: number, baseUnit: string, packagings: Packaging[]): string | null {
  const largest = packagings.reduce<Packaging | null>(
    (best, p) => (p.factor > 1 && (!best || p.factor > best.factor) ? p : best),
    null,
  );
  if (!largest || stockQty < largest.factor) {
    return null;
  }
  const whole = Math.floor(stockQty / largest.factor);
  const rest = stockQty % largest.factor;
  const head = `${quantity(whole)} ${largest.name}`;
  return rest === 0 ? head : `${head} + ${quantity(rest)} ${baseUnit}`;
}

export const ROLE_LABELS: Record<string, string> = {
  PROPRIETAIRE: 'Propriétaire',
  SECRETAIRE: 'Secrétaire',
  VENDEUR: 'Vendeur',
};

export const AUDIT_LABELS: Record<string, string> = {
  VENTE_ANNULEE: 'Vente annulée',
  VENTE_SOUS_PLANCHER: 'Vente sous le prix plancher',
  STOCK_AJUSTE: 'Stock corrigé',
  COMPTE_CREE: 'Compte créé',
  COMPTE_DESACTIVE: 'Compte désactivé',
  COMPTE_REACTIVE: 'Compte réactivé',
  MOT_DE_PASSE_CHANGE: 'Mot de passe changé',
  PRODUIT_MODIFIE: 'Produit modifié',
  PRIX_MODIFIE: 'Prix modifié',
  PRODUIT_DESACTIVE: 'Produit désactivé',
  PRODUIT_REACTIVE: 'Produit réactivé',
  CATALOGUE_IMPORTE: 'Catalogue importé',
  RESTAURATION: 'Sauvegarde restaurée',
};

export const BACKUP_KIND_LABELS: Record<string, string> = {
  MANUELLE: 'Sauvegarde',
  AUTOMATIQUE: 'Sauvegarde automatique',
  AVANT_RESTAURATION: 'Copie avant restauration',
  RESTAURATION: 'Restauration',
};

const sizeFormat = new Intl.NumberFormat('fr-FR', { maximumFractionDigits: 1 });

/** Taille de fichier lisible : octets, Ko, Mo ou Go. */
export function fileSize(bytes: number): string {
  if (bytes < 1024) {
    return `${bytes} octets`;
  }
  if (bytes < 1024 * 1024) {
    return `${sizeFormat.format(bytes / 1024)} Ko`;
  }
  if (bytes < 1024 * 1024 * 1024) {
    return `${sizeFormat.format(bytes / (1024 * 1024))} Mo`;
  }
  return `${sizeFormat.format(bytes / (1024 * 1024 * 1024))} Go`;
}

export const LEDGER_LABELS: Record<string, string> = {
  CREDIT: 'Vente à crédit',
  REMBOURSEMENT: 'Remboursement',
  ANNULATION: 'Annulation de vente',
};

export const PAYMENT_LABELS: Record<string, string> = {
  ESPECES: 'Espèces',
  MOBILE_MONEY: 'Mobile Money',
};

/**
 * Identifiant unique d'une validation de vente. crypto.randomUUID n'existe que
 * sur une page sécurisée (localhost ou HTTPS) : repli pour les postes du réseau local.
 */
export function newIdempotencyKey(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID();
  }
  const random = () => Math.floor(Math.random() * 0x100000000).toString(16).padStart(8, '0');
  return `${Date.now().toString(16)}-${random()}-${random()}-${random()}`;
}
