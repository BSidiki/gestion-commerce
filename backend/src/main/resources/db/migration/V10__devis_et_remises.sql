-- Remise sur le total d'une vente : le total enregistré est déjà net de la remise.
ALTER TABLE sale ADD COLUMN discount INTEGER NOT NULL DEFAULT 0 CHECK (discount >= 0);

-- Devis : une proposition de prix, sans effet sur le stock ni sur la caisse.
-- Transformé en vente, il garde le lien vers la vente ; il ne se transforme qu'une fois.
CREATE TABLE quote (
    id            TEXT PRIMARY KEY,
    number        INTEGER NOT NULL UNIQUE,
    customer_id   TEXT REFERENCES customer (id),
    -- Nom affiché sur le devis : celui du client enregistré, ou un nom saisi librement.
    customer_name TEXT,
    discount      INTEGER NOT NULL DEFAULT 0 CHECK (discount >= 0),
    -- Total net de la remise.
    total         INTEGER NOT NULL CHECK (total >= 0),
    status        TEXT NOT NULL DEFAULT 'OUVERT' CHECK (status IN ('OUVERT', 'TRANSFORME', 'ANNULE')),
    valid_until   TEXT NOT NULL,
    note          TEXT,
    sale_id       TEXT REFERENCES sale (id),
    created_at    TEXT NOT NULL,
    created_by    TEXT
);

CREATE TABLE quote_item (
    id             TEXT PRIMARY KEY,
    quote_id       TEXT NOT NULL REFERENCES quote (id),
    product_id     TEXT NOT NULL REFERENCES product (id),
    packaging_id   TEXT NOT NULL REFERENCES product_packaging (id),
    product_name   TEXT NOT NULL,
    packaging_name TEXT NOT NULL,
    factor         INTEGER NOT NULL CHECK (factor >= 1),
    quantity       INTEGER NOT NULL CHECK (quantity > 0),
    unit_price     INTEGER NOT NULL CHECK (unit_price >= 0),
    line_total     INTEGER NOT NULL
);

CREATE INDEX idx_quote_item_quote ON quote_item (quote_id);
