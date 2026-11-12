-- Comptes utilisateurs, sessions de connexion et journal d'audit.
-- Tant qu'aucun compte n'existe, le logiciel est en accès libre : le premier écran
-- de l'interface fait créer le compte du propriétaire.

CREATE TABLE app_user (
    id            TEXT PRIMARY KEY,
    username      TEXT NOT NULL UNIQUE COLLATE NOCASE,
    display_name  TEXT NOT NULL,
    role          TEXT NOT NULL CHECK (role IN ('PROPRIETAIRE', 'VENDEUR')),
    -- Mot de passe : jamais enregistré, seulement son empreinte PBKDF2 (base64) et son sel.
    password_hash TEXT NOT NULL,
    password_salt TEXT NOT NULL,
    iterations    INTEGER NOT NULL,
    active        INTEGER NOT NULL DEFAULT 1,
    created_at    TEXT NOT NULL
);

-- Le jeton de session n'est pas enregistré : seulement son empreinte SHA-256.
CREATE TABLE user_session (
    token_hash TEXT PRIMARY KEY,
    user_id    TEXT NOT NULL REFERENCES app_user (id),
    created_at TEXT NOT NULL,
    expires_at TEXT NOT NULL
);

CREATE INDEX idx_user_session_user ON user_session (user_id);

-- Journal des opérations sensibles : jamais modifié, jamais supprimé.
CREATE TABLE audit_log (
    id         TEXT PRIMARY KEY,
    username   TEXT,
    action     TEXT NOT NULL,
    detail     TEXT,
    created_at TEXT NOT NULL
);

CREATE INDEX idx_audit_log_created ON audit_log (created_at);
