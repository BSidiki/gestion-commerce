-- Inventaire physique : on compte le stock réel, on compare au stock du logiciel, puis on valide les écarts.
-- La validation crée un ajustement de stock (motif INVENTAIRE) par produit dont le compte diffère.

CREATE TABLE stock_count (
    id         TEXT PRIMARY KEY,
    number     INTEGER NOT NULL UNIQUE,
    -- Ce qui est compté : un rayon, une réserve, ou toute la boutique.
    label      TEXT,
    status     TEXT NOT NULL DEFAULT 'EN_COURS' CHECK (status IN ('EN_COURS', 'VALIDE', 'ABANDONNE')),
    started_at TEXT NOT NULL,
    started_by TEXT,
    closed_at  TEXT,
    closed_by  TEXT
);

-- Un produit compté. Quantités en unités de base.
CREATE TABLE stock_count_line (
    id           TEXT PRIMARY KEY,
    count_id     TEXT NOT NULL REFERENCES stock_count (id),
    product_id   TEXT NOT NULL REFERENCES product (id),
    -- Stock du logiciel au moment du comptage : l'écart se mesure par rapport à lui, si bien que
    -- les ventes faites entre le comptage et la validation ne faussent pas la correction.
    expected_qty INTEGER NOT NULL,
    counted_qty  INTEGER NOT NULL CHECK (counted_qty >= 0),
    -- Coût moyen au moment du comptage, pour chiffrer l'écart.
    cost_milli   INTEGER NOT NULL,
    counted_at   TEXT NOT NULL,
    counted_by   TEXT,
    UNIQUE (count_id, product_id)
);

CREATE INDEX idx_stock_count_line_count ON stock_count_line (count_id);
