import type { ImportRow } from '../../core/api';

/** Une colonne reconnue du tableau : son champ, son titre, et les titres acceptés. */
interface Column {
  field: keyof ImportRow;
  title: string;
  numeric: boolean;
  aliases: string[];
}

export const COLUMNS: Column[] = [
  { field: 'name', title: 'Nom', numeric: false, aliases: ['nom', 'produit', 'designation', 'article', 'libelle'] },
  { field: 'category', title: 'Catégorie', numeric: false, aliases: ['categorie', 'famille', 'rayon'] },
  { field: 'baseUnit', title: 'Unité', numeric: false, aliases: ['unite', 'unitedebase', 'unitedevente'] },
  {
    field: 'retailPrice',
    title: 'Prix détail',
    numeric: true,
    aliases: ['prixdetail', 'prix', 'prixdevente', 'prixvente', 'prixunitaire', 'pu'],
  },
  {
    field: 'retailFloor',
    title: 'Prix plancher',
    numeric: true,
    aliases: ['prixplancher', 'plancher', 'prixminimum', 'prixmin', 'prixplancherdetail'],
  },
  { field: 'barcode', title: 'Code-barres', numeric: false, aliases: ['codebarres', 'codebarre', 'code', 'ean'] },
  { field: 'alertThreshold', title: "Seuil d'alerte", numeric: true, aliases: ['seuildalerte', 'seuil', 'stockminimum'] },
  { field: 'initialStock', title: 'Stock initial', numeric: true, aliases: ['stockinitial', 'stock', 'quantite', 'qte'] },
  {
    field: 'unitCost',
    title: "Coût d'achat",
    numeric: true,
    aliases: ['coutdachat', 'cout', 'prixdachat', 'prixachat', 'coutunitaire', 'pa'],
  },
  { field: 'packagingName', title: 'Conditionnement', numeric: false, aliases: ['conditionnement', 'emballage', 'colis'] },
  {
    field: 'packagingFactor',
    title: 'Contenu',
    numeric: true,
    aliases: ['contenu', 'contenance', 'unitesparconditionnement', 'quantiteparconditionnement'],
  },
  { field: 'packagingPrice', title: 'Prix gros', numeric: true, aliases: ['prixgros', 'prixdegros', 'prixconditionnement'] },
  {
    field: 'packagingFloor',
    title: 'Prix plancher gros',
    numeric: true,
    aliases: ['prixplanchergros', 'plancher gros', 'prixminimumgros', 'prixmingros'].map(a => a.replace(' ', '')),
  },
];

/** Le modèle à remplir : séparateur « ; » et marque UTF-8, pour qu'Excel l'ouvre proprement. */
export const TEMPLATE_CSV =
  '﻿' +
  [
    COLUMNS.map(c => c.title).join(';'),
    'Savon de Marseille;Hygiène;pièce;300;250;6001234567890;50;48;200;Carton;24;6500;6000',
    'Riz parfumé;Alimentation;kg;600;;;25;100;450;Sac;25;14000;13500',
    'Stylo bleu;Papeterie;pièce;100;;;;;;;;;',
  ].join('\r\n') +
  '\r\n';

export interface ParsedTable {
  rows: ImportRow[];
  /** Pour chaque ligne envoyée, son numéro dans le tableau d'origine (le titre est la ligne 1). */
  sourceLines: number[];
  /** Ce qui empêche d'envoyer le tableau, à corriger par le commerçant. */
  problems: string[];
  /** Titres de colonnes non reconnus : ignorés, mais signalés. */
  ignoredColumns: string[];
}

/** Minuscules, sans accents ni ponctuation : « Code-barres » et « code barre » se comparent. */
function normalize(text: string): string {
  return text
    .normalize('NFD')
    .replace(/[̀-ͯ]/g, '')
    .toLowerCase()
    .replace(/[^a-z0-9]/g, '');
}

/** Le séparateur se lit sur la ligne de titre : tabulation (copier-coller d'Excel), « ; » ou « , ». */
function detectDelimiter(firstLine: string): string {
  if (firstLine.includes('\t')) {
    return '\t';
  }
  const semicolons = firstLine.split(';').length;
  const commas = firstLine.split(',').length;
  return commas > semicolons ? ',' : ';';
}

/** Découpe un texte tabulaire en cellules, guillemets compris (une cellule peut contenir le séparateur). */
export function splitCells(text: string): string[][] {
  const clean = text.replace(/^﻿/, '');
  const firstLine = clean.split(/\r?\n/, 1)[0] ?? '';
  const delimiter = detectDelimiter(firstLine);

  const table: string[][] = [];
  let row: string[] = [];
  let cell = '';
  let quoted = false;
  for (let i = 0; i < clean.length; i++) {
    const ch = clean[i];
    if (quoted) {
      if (ch === '"' && clean[i + 1] === '"') {
        cell += '"';
        i++;
      } else if (ch === '"') {
        quoted = false;
      } else {
        cell += ch;
      }
    } else if (ch === '"' && cell === '') {
      quoted = true;
    } else if (ch === delimiter) {
      row.push(cell);
      cell = '';
    } else if (ch === '\n' || ch === '\r') {
      if (ch === '\r' && clean[i + 1] === '\n') {
        i++;
      }
      row.push(cell);
      table.push(row);
      row = [];
      cell = '';
    } else {
      cell += ch;
    }
  }
  if (cell !== '' || row.length > 0) {
    row.push(cell);
    table.push(row);
  }
  return table;
}

/**
 * Un nombre entier tel qu'on l'écrit dans un tableur : « 1 500 », « 1500 F », « 1500,00 ».
 * Renvoie null pour une cellule vide, et undefined si ce n'est pas un entier.
 */
export function parseWhole(text: string): number | null | undefined {
  const compact = text
    .replace(/[\s  ]/g, '')
    .replace(/(fcfa|cfa|f)$/i, '');
  if (compact === '') {
    return null;
  }
  const match = /^(\d+)(?:[.,]0+)?$/.exec(compact);
  if (!match) {
    return undefined;
  }
  const value = Number(match[1]);
  return Number.isSafeInteger(value) ? value : undefined;
}

/** Lit le tableau collé ou chargé : la première ligne donne les titres des colonnes. */
export function parseTable(text: string): ParsedTable {
  const result: ParsedTable = { rows: [], sourceLines: [], problems: [], ignoredColumns: [] };
  const table = splitCells(text);
  const headerIndex = table.findIndex(cells => cells.some(c => c.trim() !== ''));
  if (headerIndex < 0) {
    result.problems.push('Le tableau est vide.');
    return result;
  }

  // Chaque colonne du tableau est rattachée à un champ connu, d'après son titre.
  const fields: (Column | null)[] = [];
  const used = new Set<string>();
  for (const title of table[headerIndex]) {
    const key = normalize(title);
    const column = COLUMNS.find(c => normalize(c.title) === key || c.aliases.includes(key)) ?? null;
    if (column && used.has(column.field)) {
      result.problems.push(`La colonne « ${column.title} » apparaît deux fois.`);
    }
    if (column) {
      used.add(column.field);
    } else if (title.trim() !== '') {
      result.ignoredColumns.push(title.trim());
    }
    fields.push(column);
  }
  if (!used.has('name')) {
    result.problems.push(
      'La première ligne doit contenir les titres des colonnes, dont « Nom ». Repartez du modèle.',
    );
    return result;
  }

  for (let i = headerIndex + 1; i < table.length; i++) {
    const cells = table[i];
    if (cells.every(c => c.trim() === '')) {
      continue;
    }
    const line = i + 1;
    const row: Record<string, string | number | null> = {};
    fields.forEach((column, index) => {
      if (!column) {
        return;
      }
      const raw = (cells[index] ?? '').trim();
      if (!column.numeric) {
        row[column.field] = raw === '' ? null : raw;
        return;
      }
      const value = parseWhole(raw);
      if (value === undefined) {
        result.problems.push(
          `Ligne ${line}, colonne « ${column.title} » : « ${raw} » n'est pas un nombre entier.`,
        );
      } else {
        row[column.field] = value;
      }
    });
    result.rows.push(row as unknown as ImportRow);
    result.sourceLines.push(line);
  }
  if (result.rows.length === 0) {
    result.problems.push('Le tableau ne contient que les titres : ajoutez au moins un produit.');
  }
  return result;
}

/** Lit un fichier texte : UTF-8 si possible, sinon l'encodage d'Excel sous Windows. */
export function decodeFile(buffer: ArrayBuffer): string {
  try {
    return new TextDecoder('utf-8', { fatal: true }).decode(buffer);
  } catch {
    return new TextDecoder('windows-1252').decode(buffer);
  }
}
