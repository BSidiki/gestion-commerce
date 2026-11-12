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

    @if (recoveryKey(); as key) {
      <section class="card recovery">
        <h2>Clé de secours</h2>
        <p>
          Notez cette clé sur papier ou imprimez-la, puis rangez-la hors de la boutique. Si le mot de passe est
          oublié, elle seule permet d'ouvrir les sauvegardes. Elle ne sera plus jamais affichée.
        </p>
        <p class="recovery-key">{{ key }}</p>
        <div class="actions no-print">
          <button type="button" class="button" (click)="print()">Imprimer</button>
          <button type="button" class="button primary" (click)="recoveryKey.set(null)">J'ai noté la clé</button>
        </div>
      </section>
    }

    <form class="card form no-print" (ngSubmit)="protect()">
      <h2>Protection par mot de passe</h2>
      @if (encryptionEnabled()) {
        <p class="alert success">
          Les sauvegardes sont chiffrées. Sans le mot de passe ou la clé de secours, une clé USB perdue est
          illisible.
        </p>
      } @else {
        <p class="alert warning">
          Les sauvegardes ne sont pas protégées : toute personne qui trouve la clé USB peut lire vos ventes et le
          nom de vos clients.
        </p>
      }
      <div class="grid">
        <label>
          {{ encryptionEnabled() ? 'Nouveau mot de passe' : 'Mot de passe' }} (8 caractères au moins)
          <input name="password" type="password" autocomplete="new-password" [(ngModel)]="password" />
        </label>
        <label>
          Répétez le mot de passe
          <input name="passwordAgain" type="password" autocomplete="new-password" [(ngModel)]="passwordAgain" />
        </label>
      </div>
      @if (encryptionEnabled()) {
        <small>
          Changer le mot de passe crée aussi une nouvelle clé de secours. Les sauvegardes déjà faites gardent
          l'ancien mot de passe et l'ancienne clé.
        </small>
      }
      <div class="actions">
        <button type="submit" class="button" [disabled]="busy()">
          {{ encryptionEnabled() ? 'Changer le mot de passe' : 'Protéger les sauvegardes' }}
        </button>
      </div>
    </form>

    <form class="card form no-print" (ngSubmit)="backup()">
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

    <section class="card form no-print">
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
                <td>
                  {{ file.name }}
                  @if (file.encrypted) {
                    <span class="badge neutral">Protégée</span>
                  }
                </td>
                <td>{{ date(file.modifiedAt) }}</td>
                <td class="num">{{ size(file.sizeBytes) }}</td>
                <td>
                  @if (confirming() !== file.path) {
                    <button type="button" class="button" (click)="startRestore(file)" [disabled]="busy()">
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
                    @if (file.needsSecret) {
                      <label class="secret">
                        Cette sauvegarde a été protégée avec un autre mot de passe. Saisissez son mot de passe ou sa
                        clé de secours.
                        <input name="secret" type="password" autocomplete="off" [(ngModel)]="secret" />
                      </label>
                    }
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

    <section class="card no-print">
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

  protected readonly encryptionEnabled = signal(false);
  protected readonly recoveryKey = signal<string | null>(null);

  protected readonly other = OTHER;
  protected password = '';
  protected passwordAgain = '';
  protected secret = '';
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

  protected protect(): void {
    if (this.password.length < 8) {
      this.error.set('Le mot de passe doit contenir au moins 8 caractères.');
      return;
    }
    if (this.password !== this.passwordAgain) {
      this.error.set('Les deux mots de passe ne sont pas identiques.');
      return;
    }
    this.start();
    this.api.protectBackups(this.password).subscribe({
      next: result => {
        this.password = '';
        this.passwordAgain = '';
        this.recoveryKey.set(result.recoveryKey);
        this.done('Les prochaines sauvegardes seront chiffrées. Notez la clé de secours affichée ci-dessous.');
      },
      error: e => this.failed(e),
    });
  }

  protected print(): void {
    window.print();
  }

  protected startRestore(file: BackupFile): void {
    this.secret = '';
    this.error.set(null);
    this.confirming.set(file.path);
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
    if (file.needsSecret && !this.secret) {
      this.error.set('Saisissez le mot de passe ou la clé de secours de cette sauvegarde.');
      return;
    }
    this.start();
    this.api.restoreBackup(file.path, this.secret).subscribe({
      next: result => {
        this.secret = '';
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
        this.encryptionEnabled.set(overview.encryptionEnabled);
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
