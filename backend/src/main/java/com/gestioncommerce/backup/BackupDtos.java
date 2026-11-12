package com.gestioncommerce.backup;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class BackupDtos {

    private BackupDtos() {
    }

    /** @param directory dossier de destination ; vide ou absent : dossier local du logiciel */
    public record BackupRequest(String directory) {
    }

    /**
     * @param path   chemin complet de l'archive à restaurer
     * @param secret mot de passe ou clé de secours, si l'archive est protégée et que ce PC n'en a pas la clé
     */
    public record RestoreRequest(@NotBlank String path, String secret) {
    }

    public record EncryptionRequest(@NotBlank @Size(min = 8) String password) {
    }

    /** @param recoveryKey clé de secours, montrée une seule fois : elle n'est enregistrée nulle part */
    public record EncryptionResult(String recoveryKey) {
    }

    public record BackupView(
            String id,
            String kind,
            String path,
            Long sizeBytes,
            boolean external,
            String status,
            String message,
            String createdAt) {
    }

    /**
     * @param lastBackupAt         dernière sauvegarde réussie, où qu'elle soit
     * @param lastExternalBackupAt dernière sauvegarde réussie hors du PC (clé USB, disque externe)
     * @param reminder             vrai s'il est temps de sauvegarder sur un support externe
     */
    public record BackupStatus(String lastBackupAt, String lastExternalBackupAt, boolean reminder) {
    }

    /**
     * Emplacement proposé pour une sauvegarde.
     *
     * @param local          le dossier du logiciel lui-même
     * @param sameDiskAsData sur le même disque que les données : ne protège pas d'une panne de ce disque
     */
    public record Destination(String path, String label, long freeBytes, boolean local, boolean sameDiskAsData) {
    }

    public record BackupOverview(
            boolean encryptionEnabled,
            BackupStatus status,
            List<Destination> destinations,
            List<BackupView> history) {
    }

    /**
     * Archive trouvée dans un dossier, candidate à la restauration.
     *
     * @param encrypted   protégée par mot de passe
     * @param needsSecret ce PC n'en a pas la clé : il faudra le mot de passe ou la clé de secours
     */
    public record BackupFile(String path, String name, long sizeBytes, String modifiedAt, boolean encrypted,
            boolean needsSecret) {
    }

    /**
     * @param restoredFrom    archive restaurée
     * @param backupCreatedAt date de création de cette archive
     * @param safetyCopyPath  copie de l'état précédent, faite juste avant la restauration
     */
    public record RestoreResult(String restoredFrom, String backupCreatedAt, String safetyCopyPath) {
    }

    /** Fiche d'identité rangée dans l'archive à côté de la base. */
    public record Manifest(String app, String createdAt, int schemaRank, String sha256, long dbSize) {
    }
}
