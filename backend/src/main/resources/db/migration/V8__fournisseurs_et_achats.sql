-- Fournisseurs, achats (réceptions de marchandise rattachées à un fournisseur) et paiements.
-- Ce que la boutique doit à un fournisseur = total de ses achats - total de ses paiements.
-- Rien n'est supprimé : une erreur se corrige par une écriture inverse.

CREATE TABLE supplier (
    id         TEXT PRIMARY KEY,
    name       TEXT NOT NULL,
    phone      TEXT,
    note       TEXT,
    created_at TEXT NOT NULL
);

CREATE UNIQUE INDEX idx_supplier_name ON supplier (name COLLATE NOCASE);

-- Un achat : une livraison d'un fournisseur, en une ou plusieurs lignes.
CREATE TABLE purchase (
    id            TEXT PRIMARY KEY,
    number        INTEGER NOT NULL UNIQUE,
    supplier_id   TEXT NOT NULL REFERENCES supplier (id),
    -- Coût total de la livraison, en francs CFA.
    total         INTEGER NOT NULL CHECK (total >= 0),
    note          TEXT,
    business_date TEXT NOT NULL,
    created_at    TEXT NOT NULL,
    created_by    TEXT
);

CREATE INDEX idx_purchase_supplier ON purchase (supplier_id, created_at);

-- Chaque ligne a donné lieu à une entrée de stock (mouvement RECEPTION).
-- Les noms et le contenu du conditionnement sont recopiés : la ligne reste lisible si le produit change.
CREATE TABLE purchase_item (
    id             TEXT PRIMARY KEY,
    purchase_id    TEXT NOT NULL REFERENCES purchase (id),
    product_id     TEXT NOT NULL REFERENCES product (id),
    packaging_id   TEXT NOT NULL REFERENCES product_packaging (id),
    product_name   TEXT NOT NULL,
    packaging_name TEXT NOT NULL,
    factor         INTEGER NOT NULL CHECK (factor >= 1),
    quantity       INTEGER NOT NULL CHECK (quantity > 0),
    total_cost     INTEGER NOT NULL CHECK (total_cost >= 0)
);

CREATE INDEX idx_purchase_item_purchase ON purchase_item (purchase_id);

-- Un paiement fait à un fournisseur : à la livraison (purchase_id renseigné) ou plus tard.
-- cash_session_id n'est renseigné que si l'argent est sorti du tiroir de la caisse ouverte :
-- il compte alors dans les espèces attendues à la clôture.
CREATE TABLE supplier_payment (
    id              TEXT PRIMARY KEY,
    supplier_id     TEXT NOT NULL REFERENCES supplier (id),
    purchase_id     TEXT REFERENCES purchase (id),
    amount          INTEGER NOT NULL CHECK (amount > 0),
    method          TEXT NOT NULL CHECK (method IN ('ESPECES', 'MOBILE_MONEY')),
    cash_session_id TEXT REFERENCES cash_session (id),
    note            TEXT,
    business_date   TEXT NOT NULL,
    created_at      TEXT NOT NULL,
    created_by      TEXT
);

CREATE INDEX idx_supplier_payment_supplier ON supplier_payment (supplier_id, created_at);
CREATE INDEX idx_supplier_payment_session ON supplier_payment (cash_session_id);
