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
