-- Troisième rôle : la secrétaire, entre le vendeur et le propriétaire.
-- SQLite ne sait pas modifier une contrainte : la table des comptes est reconstruite à l'identique,
-- avec la liste des rôles élargie. Les sessions ouvertes sont fermées (il suffit de se reconnecter).

DELETE FROM user_session;

CREATE TABLE app_user_new (
    id            TEXT PRIMARY KEY,
    username      TEXT NOT NULL UNIQUE COLLATE NOCASE,
    display_name  TEXT NOT NULL,
    role          TEXT NOT NULL CHECK (role IN ('PROPRIETAIRE', 'SECRETAIRE', 'VENDEUR')),
    password_hash TEXT NOT NULL,
    password_salt TEXT NOT NULL,
    iterations    INTEGER NOT NULL,
    active        INTEGER NOT NULL DEFAULT 1,
    created_at    TEXT NOT NULL
);

INSERT INTO app_user_new (id, username, display_name, role, password_hash, password_salt, iterations, active, created_at)
SELECT id, username, display_name, role, password_hash, password_salt, iterations, active, created_at
FROM app_user;

DROP TABLE app_user;

ALTER TABLE app_user_new RENAME TO app_user;
