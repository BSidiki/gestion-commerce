import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';

import { Api, Backup, BackupDestination, BackupFile, BackupStatus, errorMessage } from '../../core/api';
import { BACKUP_KIND_LABELS, dateTime, fileSize } from '../../core/format';

const OTHER = '__autre__';

@Component({
  selector: 'app-backup',
  imports: [FormsModule],
  template: `
    <div class="page-head">
      <h1>Sauvegarde</h1>
    </div>

    @if (error(); as message) {
      <p class="alert error" role="alert">{{ message }}</p>
    }
    @if (notice(); as message) {
      <p class="alert success" role="status">{{ message }}</p>
    }

    @if (status(); as s) {
      @if (s.reminder) {
        <p class="alert warning">
          @if (s.lastExternalBackupAt) {
            La dernière sauvegarde sur clé USB date du {{ date(s.lastExternalBackupAt) }}. Branchez la clé et
            sauvegardez.
          } @else {
            Aucune sauvegarde sur clé USB pour l'instant. En cas de panne ou de vol du PC, tout serait perdu.
          }
        </p>
      } @else {
        <p class="muted">Dernière sauvegarde sur un support externe : {{ date(s.lastExternalBackupAt!) }}.</p>
      }
    }

    <form class="card form" (ngSubmit)="backup()">
      <h2>Sauvegarder maintenant</h2>
      <div class="grid">
        <label>
          Où enregistrer la sauvegarde ?
          <select name="destination" [ngModel]="destination()" (ngModelChange)="destination.set($event)">
            @for (d of destinations(); track d.path) {
              <option [ngValue]="d.path">
                {{ d.label }} — {{ size(d.freeBytes) }} libres{{ d.sameDiskAsData ? ' (même disque que les données)' : '' }}
              </option>
            }
            <option [ngValue]="other">Autre dossier…</option>
          </select>
        </label>
        @if (destination() === other) {
          <label>
            Chemin du dossier
            <input
              name="customDestination"
              [ngModel]="customDestination()"
              (ngModelChange)="customDestination.set($event)"
              placeholder="E:\\Sauvegardes"
            />
          </label>
        }
      </div>
      <small>Enregistrée dans : {{ targetDirectory() || '—' }}</small>
      <div class="actions">
        <button type="submit" class="button primary" [disabled]="busy()">
          {{ busy() ? 'Travail en cours…' : 'Sauvegarder' }}
        </button>
      </div>
    </form>

    <section class="card form">
      <h2>Restaurer une sauvegarde</h2>
      <p class="muted">
        À utiliser après une panne, un changement de PC ou une erreur grave. Les données actuelles sont remplacées
        par celles de la sauvegarde ; une copie de l'état actuel est gardée sur ce PC avant le remplacement.
      </p>
      <div class="grid">
        <label>
          Dossier où chercher
          <select name="restoreSource" [ngModel]="restoreSource()" (ngModelChange)="pickSource($event)">
            @for (d of destinations(); track d.path) {
              <option [ngValue]="d.path">{{ d.label }}</option>
            }
            <option [ngValue]="other">Autre dossier…</option>
          </select>
        </label>
        @if (restoreSource() === other) {
          <label>
            Chemin du dossier
            <input name="customSource" [(ngModel)]="customSource" placeholder="E:\\Sauvegardes" />
          </label>
        }
      </div>
      <div class="actions">
        <button type="button" class="button" (click)="search()" [disabled]="busy()">Chercher les sauvegardes</button>
      </div>

      @if (files(); as found) {
        <table class="table">
          <thead>
            <tr>
              <th>Fichier</th>
              <th>Date</th>
              <th class="num">Taille</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            @for (file of found; track file.path) {
              <tr>
                <td>{{ file.name }}</td>
                <td>{{ date(file.modifiedAt) }}</td>
                <td class="num">{{ size(file.sizeBytes) }}</td>
                <td>
                  @if (confirming() !== file.path) {
                    <button type="button" class="button" (click)="confirming.set(file.path)" [disabled]="busy()">
                      Restaurer
                    </button>
                  }
                </td>
              </tr>
              @if (confirming() === file.path) {
                <tr>
                  <td colspan="4">
                    <p class="alert warning">
                      Tout ce qui a été saisi après le {{ date(file.modifiedAt) }} sera remplacé par le contenu de
                      cette sauvegarde.
                    </p>
                    <div class="inline">
                      <button type="button" class="button danger" (click)="restore(file)" [disabled]="busy()">
                        Oui, restaurer cette sauvegarde
                      </button>
                      <button type="button" class="button" (click)="confirming.set(null)">Annuler</button>
                    </div>
                  </td>
                </tr>
              }
            } @empty {
              <tr>
                <td colspan="4" class="muted">Aucune sauvegarde trouvée dans ce dossier.</td>
              </tr>
            }
          </tbody>
        </table>
      }
    </section>

    <section class="card">
      <h2>Historique</h2>
      <table class="table">
        <thead>
          <tr>
            <th>Date</th>
            <th>Opération</th>
            <th>Emplacement</th>
            <th class="num">Taille</th>
            <th>Résultat</th>
          </tr>
        </thead>
        <tbody>
          @for (b of history(); track b.id) {
            <tr>
              <td>{{ date(b.createdAt) }}</td>
              <td>{{ kindLabel(b.kind) }}</td>
              <td class="path">{{ b.path }}</td>
              <td class="num">{{ b.sizeBytes !== null ? size(b.sizeBytes) : '—' }}</td>
              <td>
                @if (b.status === 'REUSSIE') {
                  Réussie
                } @else {
                  <span class="badge warning">Échouée</span>
                }
              </td>
            </tr>
          } @empty {
            <tr>
              <td colspan="5" class="muted">Aucune sauvegarde pour l'instant.</td>
            </tr>
          }
        </tbody>
      </table>
    </section>
  `,
})
export class BackupPage {
  private readonly api = inject(Api);

  protected readonly status = signal<BackupStatus | null>(null);
  protected readonly destinations = signal<BackupDestination[]>([]);
  protected readonly history = signal<Backup[]>([]);
  protected readonly files = signal<BackupFile[] | null>(null);
  protected readonly destination = signal<string>(OTHER);
  protected readonly restoreSource = signal<string>(OTHER);
  protected readonly confirming = signal<string | null>(null);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly notice = signal<string | null>(null);

  protected readonly other = OTHER;
  protected readonly customDestination = signal('');
  protected customSource = '';

  protected readonly targetDirectory = computed(() =>
    this.destination() === OTHER ? this.customDestination().trim() : this.destination(),
  );

  protected readonly date = dateTime;
  protected readonly size = fileSize;

  constructor() {
    this.reload(true);
  }

  protected kindLabel(kind: string): string {
    return BACKUP_KIND_LABELS[kind] ?? kind;
  }

  protected backup(): void {
    const directory = this.targetDirectory();
    if (!directory) {
      this.error.set('Indiquez le dossier où enregistrer la sauvegarde.');
      return;
    }
    this.start();
    this.api.createBackup(directory).subscribe({
      next: backup => this.done(`Sauvegarde enregistrée : ${backup.path}`),
      error: e => this.failed(e),
    });
  }

  protected pickSource(path: string): void {
    this.restoreSource.set(path);
    this.files.set(null);
    this.confirming.set(null);
  }

  protected search(): void {
    const directory = this.restoreSource() === OTHER ? this.customSource.trim() : this.restoreSource();
    if (!directory) {
      this.error.set('Indiquez le dossier où chercher les sauvegardes.');
      return;
    }
    this.start();
    this.api.backupFiles(directory).subscribe({
      next: files => {
        this.files.set(files);
        this.confirming.set(null);
        this.busy.set(false);
      },
      error: e => this.failed(e),
    });
  }

  protected restore(file: BackupFile): void {
    this.start();
    this.api.restoreBackup(file.path).subscribe({
      next: result => {
        this.confirming.set(null);
        this.files.set(null);
        this.done(
          `Sauvegarde du ${dateTime(result.backupCreatedAt)} restaurée. ` +
            `L'état précédent est gardé dans : ${result.safetyCopyPath}`,
        );
      },
      error: e => this.failed(e),
    });
  }

  private start(): void {
    this.busy.set(true);
    this.error.set(null);
    this.notice.set(null);
  }

  private done(message: string): void {
    this.busy.set(false);
    this.notice.set(message);
    this.reload(false);
  }

  private failed(error: unknown): void {
    this.busy.set(false);
    this.error.set(errorMessage(error));
  }

  private reload(first: boolean): void {
    this.api.backups().subscribe({
      next: overview => {
        this.status.set(overview.status);
        this.destinations.set(overview.destinations);
        this.history.set(overview.history);
        if (first) {
          // Par défaut : un lecteur autre que celui des données (clé USB) s'il y en a un, sinon le dossier local.
          const preferred = overview.destinations.find(d => !d.sameDiskAsData) ?? overview.destinations[0];
          this.destination.set(preferred?.path ?? OTHER);
          this.restoreSource.set(preferred?.path ?? OTHER);
        }
      },
      error: e => this.error.set(errorMessage(e)),
    });
  }
}
