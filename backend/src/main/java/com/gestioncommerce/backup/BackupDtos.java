package com.gestioncommerce.backup;

import java.util.List;

import jakarta.validation.constraints.NotBlank;

public final class BackupDtos {

    private BackupDtos() {
    }

    /** @param directory dossier de destination ; vide ou absent : dossier local du logiciel */
    public record BackupRequest(String directory) {
    }

    /** @param path chemin complet de l'archive à restaurer */
    public record RestoreRequest(@NotBlank String path) {
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
            BackupStatus status,
            List<Destination> destinations,
            List<BackupView> history) {
    }

    /** Archive trouvée dans un dossier, candidate à la restauration. */
    public record BackupFile(String path, String name, long sizeBytes, String modifiedAt) {
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
