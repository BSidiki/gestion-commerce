import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';

import { Api, ImportResult, errorMessage } from '../../core/api';
import { COLUMNS, ParsedTable, TEMPLATE_CSV, decodeFile, parseTable } from './import-parser';

const STATUS_LABELS: Record<string, string> = {
  CREE: 'Créé',
  EXISTE: 'Déjà présent',
  ERREUR: 'Erreur',
};

/** Import du catalogue : coller un tableau Excel, vérifier, puis importer. */
@Component({
  selector: 'app-product-import',
  imports: [RouterLink, FormsModule],
  styles: `
    .steps {
      margin: 0;
      padding-left: 1.2rem;
      line-height: 1.7;
    }
    .columns {
      margin: 0.5rem 0 1rem;
    }
    .columns code {
      display: inline-block;
      margin: 0 0.35rem 0.35rem 0;
      padding: 0.1rem 0.45rem;
      border: 1px solid var(--line);
      border-radius: 5px;
      background: var(--background);
      font-size: 0.85rem;
    }
    .badge {
      margin-left: 0;
    }
    .badge.ok {
      background: var(--success-bg);
      color: var(--success-ink);
    }
    .badge.error {
      background: var(--error-bg);
      color: var(--error-ink);
    }
    .problems {
      margin: 0.5rem 0 0;
      padding-left: 1.2rem;
    }
    .file {
      margin-top: 0.75rem;
    }
  `,
  template: `
    <div class="page-head">
      <h1>Importer des produits</h1>
      <a class="button" routerLink="/produits">Retour aux produits</a>
    </div>

    @if (done(); as report) {
      <p class="alert success" role="status">
        {{ report.created }} produit(s) créé(s).
        @if (report.skipped > 0) {
          {{ report.skipped }} déjà présent(s), laissé(s) tel(s) quel(s).
        }
        @if (report.errors > 0) {
          {{ report.errors }} ligne(s) en erreur, non importée(s) : corrigez-les et relancez l'import,
          les produits déjà créés seront ignorés.
        }
      </p>
    }

    <section class="card">
      <h2>1. Préparer le tableau</h2>
      <ol class="steps">
        <li>Dans Excel, mettez un produit par ligne, avec les titres de colonnes en première ligne.</li>
        <li>Seule la colonne <strong>Nom</strong> est obligatoire. L'ordre des colonnes est libre.</li>
        <li>Les montants sont en francs CFA, sans décimales.</li>
      </ol>
      <p class="columns">
        @for (column of columns; track column) {
          <code>{{ column }}</code>
        }
      </p>
      <p class="muted small">
        « Stock initial » et « Coût d'achat » se comptent dans l'unité de base. « Conditionnement » et
        « Contenu » décrivent la vente en gros : par exemple Carton et 24.
      </p>
      <button type="button" class="button" (click)="downloadTemplate()">Télécharger le modèle</button>
    </section>

    <section class="card">
      <h2>2. Coller le tableau</h2>
      <p class="muted">
        Sélectionnez les cellules dans Excel, titres compris, copiez-les, puis collez-les ici.
      </p>
      <textarea
        name="table"
        rows="10"
        spellcheck="false"
        aria-label="Tableau des produits"
        placeholder="Nom	Catégorie	Unité	Prix détail…"
        [ngModel]="text()"
        (ngModelChange)="setText($event)"
      ></textarea>
      <label class="file">
        Ou choisir un fichier CSV
        <input type="file" accept=".csv,.txt,text/csv,text/plain" (change)="loadFile($event)" />
      </label>

      @if (parsed(); as table) {
        @if (table.problems.length > 0) {
          <div class="alert error" role="alert">
            À corriger avant de continuer :
            <ul class="problems">
              @for (problem of shownProblems(); track $index) {
                <li>{{ problem }}</li>
              }
              @if (table.problems.length > shownProblems().length) {
                <li>… et {{ table.problems.length - shownProblems().length }} autre(s).</li>
              }
            </ul>
          </div>
        } @else {
          <p class="muted">{{ table.rows.length }} produit(s) lu(s) dans le tableau.</p>
        }
        @if (table.ignoredColumns.length > 0) {
          <p class="alert warning">Colonnes non reconnues, ignorées : {{ table.ignoredColumns.join(', ') }}.</p>
        }
      }

      @if (error(); as message) {
        <p class="alert error" role="alert">{{ message }}</p>
      }

      <div class="actions">
        <button type="button" class="button primary" [disabled]="!canCheck()" (click)="check()">
          {{ busy() && !checked() ? 'Vérification…' : 'Vérifier le tableau' }}
        </button>
      </div>
    </section>

    @if (report(); as result) {
      <section class="card">
        <h2>{{ result.dryRun ? '3. Résultat de la vérification' : 'Résultat de l\\'import' }}</h2>
        <div class="stats">
          <div class="stat">
            <span class="label">{{ result.dryRun ? 'Produits à créer' : 'Produits créés' }}</span>
            <span class="value">{{ result.created }}</span>
          </div>
          <div class="stat">
            <span class="label">Déjà présents (ignorés)</span>
            <span class="value">{{ result.skipped }}</span>
          </div>
          <div class="stat">
            <span class="label">Lignes en erreur</span>
            <span class="value" [class.negative]="result.errors > 0">{{ result.errors }}</span>
          </div>
        </div>

        @if (result.dryRun) {
          @if (result.errors > 0) {
            <p class="alert warning">
              Les lignes en erreur ne seront pas importées. Vous pouvez les corriger dans le tableau puis
              vérifier de nouveau, ou importer les autres dès maintenant.
            </p>
          }
          <div class="actions">
            <button
              type="button"
              class="button primary"
              [disabled]="busy() || result.created === 0"
              (click)="run()"
            >
              {{ busy() ? 'Import en cours…' : 'Importer ' + result.created + ' produit(s)' }}
            </button>
          </div>
        } @else {
          <div class="actions">
            <a class="button primary" routerLink="/produits">Voir les produits</a>
          </div>
        }

        <label class="check">
          <input type="checkbox" [ngModel]="problemsOnly()" (ngModelChange)="problemsOnly.set($event)" />
          Afficher seulement les lignes en erreur
        </label>
        <table class="table compact">
          <thead>
            <tr>
              <th class="num">Ligne</th>
              <th>Produit</th>
              <th>Résultat</th>
              <th>Détail</th>
            </tr>
          </thead>
          <tbody>
            @for (row of shownRows(); track row.line) {
              <tr>
                <td class="num">{{ sourceLine(row.line) }}</td>
                <td>{{ row.name || '—' }}</td>
                <td>
                  <span
                    class="badge"
                    [class.ok]="row.status === 'CREE'"
                    [class.warning]="row.status === 'EXISTE'"
                    [class.error]="row.status === 'ERREUR'"
                  >
                    {{ statusLabel(row.status, result.dryRun) }}
                  </span>
                </td>
                <td>{{ detail(row.message, result.dryRun) }}</td>
              </tr>
            } @empty {
              <tr>
                <td colspan="4" class="muted">Aucune ligne en erreur.</td>
              </tr>
            }
          </tbody>
        </table>
      </section>
    }
  `,
})
export class ProductImport {
  private readonly api = inject(Api);

  protected readonly columns = COLUMNS.map(c => c.title);

  protected readonly text = signal('');
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly report = signal<ImportResult | null>(null);
  /** Le dernier import réellement effectué, pour le bandeau de confirmation. */
  protected readonly done = signal<ImportResult | null>(null);
  protected readonly problemsOnly = signal(false);

  /** Numéros de ligne du tableau d'origine, figés au moment de l'envoi. */
  private sentLines: number[] = [];

  protected readonly parsed = computed<ParsedTable | null>(() =>
    this.text().trim() === '' ? null : parseTable(this.text()),
  );
  protected readonly shownProblems = computed(() => this.parsed()?.problems.slice(0, 8) ?? []);
  protected readonly checked = computed(() => this.report() !== null);
  protected readonly canCheck = computed(() => {
    const table = this.parsed();
    return !this.busy() && table !== null && table.problems.length === 0;
  });
  protected readonly shownRows = computed(() => {
    const rows = this.report()?.rows ?? [];
    return this.problemsOnly() ? rows.filter(r => r.status === 'ERREUR') : rows;
  });

  protected setText(value: string): void {
    this.text.set(value);
    // Le tableau a changé : la vérification précédente ne vaut plus.
    this.report.set(null);
    this.error.set(null);
  }

  protected loadFile(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    if (!file) {
      return;
    }
    if (/\.xlsx?$/i.test(file.name)) {
      this.error.set(
        "Un classeur Excel ne se lit pas directement : copiez-collez ses cellules, ou enregistrez-le en CSV.",
      );
      input.value = '';
      return;
    }
    file.arrayBuffer().then(buffer => {
      this.setText(decodeFile(buffer));
      input.value = '';
    });
  }

  protected downloadTemplate(): void {
    const url = URL.createObjectURL(new Blob([TEMPLATE_CSV], { type: 'text/csv;charset=utf-8' }));
    const link = document.createElement('a');
    link.href = url;
    link.download = 'modele-import-produits.csv';
    link.click();
    URL.revokeObjectURL(url);
  }

  protected check(): void {
    this.send(true);
  }

  protected run(): void {
    this.send(false);
  }

  protected sourceLine(line: number): number {
    return this.sentLines[line - 1] ?? line;
  }

  protected statusLabel(status: string, dryRun: boolean): string {
    return status === 'CREE' && dryRun ? 'À créer' : (STATUS_LABELS[status] ?? status);
  }

  /** En vérification, rien n'est encore créé : le détail le dit au futur. */
  protected detail(message: string, dryRun: boolean): string {
    return dryRun ? message.replace(/^Produit créé/, 'Sera créé') : message;
  }

  private send(dryRun: boolean): void {
    const table = this.parsed();
    if (!table || table.problems.length > 0 || this.busy()) {
      return;
    }
    this.busy.set(true);
    this.error.set(null);
    if (dryRun) {
      this.done.set(null);
    }
    const lines = table.sourceLines;
    this.api.importCatalog(table.rows, dryRun).subscribe({
      next: result => {
        this.sentLines = lines;
        this.report.set(result);
        if (!dryRun) {
          this.done.set(result);
        }
        this.busy.set(false);
      },
      error: e => {
        this.error.set(errorMessage(e));
        this.busy.set(false);
      },
    });
  }
}
