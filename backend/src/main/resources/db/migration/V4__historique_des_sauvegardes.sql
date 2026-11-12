-- Phase 3 : sauvegarde et restauration.
-- L'historique vit dans la base : une restauration ramène donc l'historique de la sauvegarde,
-- auquel le logiciel ajoute la copie de sécurité et la restauration elle-même.

CREATE TABLE backup_history (
    id         TEXT PRIMARY KEY,
    kind       TEXT NOT NULL CHECK (kind IN ('MANUELLE', 'AUTOMATIQUE', 'AVANT_RESTAURATION', 'RESTAURATION')),
    path       TEXT NOT NULL,
    size_bytes INTEGER,
    -- 1 si l'archive est hors du dossier de données du logiciel (clé USB, disque externe).
    external   INTEGER NOT NULL DEFAULT 0,
    status     TEXT NOT NULL CHECK (status IN ('REUSSIE', 'ECHOUEE')),
    message    TEXT,
    created_at TEXT NOT NULL
);

CREATE INDEX idx_backup_history_created ON backup_history (created_at);
