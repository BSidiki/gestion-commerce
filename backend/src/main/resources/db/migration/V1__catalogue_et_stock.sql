-- Phase 1 : catalogue et stock.
-- Conventions :
--   * identifiants : UUID en TEXT
--   * montants : francs CFA entiers (INTEGER)
--   * coûts unitaires : millièmes de franc (INTEGER), pour rester exacts
--     quand un carton est divisé en unités ou un sac en grammes
--   * quantités : toujours en unité de base du produit (INTEGER)
--   * dates : ISO-8601 UTC en TEXT

CREATE TABLE category (
    id         TEXT PRIMARY KEY,
    name       TEXT NOT NULL UNIQUE,
    created_at TEXT NOT NULL
);

CREATE TABLE product (
    id              TEXT PRIMARY KEY,
    name            TEXT NOT NULL,
    category_id     TEXT REFERENCES category (id),
    base_unit       TEXT NOT NULL,
    -- Cache du cumul des mouvements, recalculable depuis stock_movement.
    stock_qty       INTEGER NOT NULL DEFAULT 0,
    -- Coût moyen pondéré par unité de base, en millièmes de franc.
    avg_cost_milli  INTEGER NOT NULL DEFAULT 0 CHECK (avg_cost_milli >= 0),
    alert_threshold INTEGER NOT NULL DEFAULT 0 CHECK (alert_threshold >= 0),
    active          INTEGER NOT NULL DEFAULT 1,
    created_at      TEXT NOT NULL,
    updated_at      TEXT NOT NULL
);

CREATE INDEX idx_product_name ON product (name);

-- Conditionnements : unité, paquet, carton... avec conversion vers l'unité de base.
CREATE TABLE product_packaging (
    id         TEXT PRIMARY KEY,
    product_id TEXT NOT NULL REFERENCES product (id),
    name       TEXT NOT NULL,
    factor     INTEGER NOT NULL CHECK (factor >= 1),
    UNIQUE (product_id, name)
);

-- Prix par conditionnement et par niveau, avec prix plancher pour la négociation.
CREATE TABLE product_price (
    id           TEXT PRIMARY KEY,
    packaging_id TEXT NOT NULL REFERENCES product_packaging (id),
    level        TEXT NOT NULL CHECK (level IN ('DETAIL', 'DEMI_GROS', 'GROS')),
    price        INTEGER NOT NULL CHECK (price >= 0),
    floor_price  INTEGER NOT NULL CHECK (floor_price >= 0 AND floor_price <= price),
    UNIQUE (packaging_id, level)
);

CREATE TABLE product_barcode (
    id           TEXT PRIMARY KEY,
    packaging_id TEXT NOT NULL REFERENCES product_packaging (id),
    code         TEXT NOT NULL UNIQUE
);

-- Journal des mouvements : jamais modifié, jamais supprimé.
CREATE TABLE stock_movement (
    id              TEXT PRIMARY KEY,
    product_id      TEXT NOT NULL REFERENCES product (id),
    type            TEXT NOT NULL CHECK (type IN ('RECEPTION', 'VENTE', 'AJUSTEMENT', 'ANNULATION')),
    -- Quantité signée, en unité de base : positive en entrée, négative en sortie.
    quantity        INTEGER NOT NULL CHECK (quantity <> 0),
    unit_cost_milli INTEGER,
    reason          TEXT,
    note            TEXT,
    stock_after     INTEGER NOT NULL,
    created_at      TEXT NOT NULL,
    created_by      TEXT
);

CREATE INDEX idx_stock_movement_product ON stock_movement (product_id, created_at);
