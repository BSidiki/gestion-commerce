-- Réglages du logiciel, sous forme clé / valeur.
-- Premier usage : les clés de chiffrement des sauvegardes (sels et clés dérivées, en base64).
-- Ni le mot de passe ni la clé de secours ne sont enregistrés.

CREATE TABLE app_setting (
    key   TEXT PRIMARY KEY,
    value TEXT NOT NULL
);
