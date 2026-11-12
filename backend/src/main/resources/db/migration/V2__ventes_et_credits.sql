-- Phase 2 : ventes, paiements et crédits clients.
-- Mêmes conventions que V1 : montants en francs CFA entiers, dates ISO-8601 UTC.
-- business_date est la date locale de la boutique (AAAA-MM-JJ), pour les rapports du jour.

CREATE TABLE customer (
    id         TEXT PRIMARY KEY,
    name       TEXT NOT NULL,
    phone      TEXT,
    created_at TEXT NOT NULL
);

CREATE INDEX idx_customer_name ON customer (name);

-- Une vente validée n'est jamais supprimée : elle peut seulement passer à ANNULEE.
CREATE TABLE sale (
    id              TEXT PRIMARY KEY,
    number          INTEGER NOT NULL UNIQUE,
    -- Fourni par l'interface : un double clic ne crée pas deux ventes.
    idempotency_key TEXT NOT NULL UNIQUE,
    customer_id     TEXT REFERENCES customer (id),
    total           INTEGER NOT NULL CHECK (total >= 0),
    paid            INTEGER NOT NULL CHECK (paid >= 0 AND paid <= total),
    credit          INTEGER NOT NULL CHECK (credit = total - paid),
    status          TEXT NOT NULL DEFAULT 'VALIDEE' CHECK (status IN ('VALIDEE', 'ANNULEE')),
    business_date   TEXT NOT NULL,
    created_at      TEXT NOT NULL,
    cancelled_at    TEXT,
    cancel_reason   TEXT,
    created_by      TEXT,
    -- Une vente à crédit exige un client identifié.
    CHECK (credit = 0 OR customer_id IS NOT NULL)
);

CREATE INDEX idx_sale_business_date ON sale (business_date);

-- Chaque ligne fige la désignation, le prix et le coût au moment de la vente.
CREATE TABLE sale_item (
    id              TEXT PRIMARY KEY,
    sale_id         TEXT NOT NULL REFERENCES sale (id),
    product_id      TEXT NOT NULL REFERENCES product (id),
    packaging_id    TEXT NOT NULL REFERENCES product_packaging (id),
    product_name    TEXT NOT NULL,
    packaging_name  TEXT NOT NULL,
    factor          INTEGER NOT NULL CHECK (factor >= 1),
    quantity        INTEGER NOT NULL CHECK (quantity > 0),
    unit_price      INTEGER NOT NULL CHECK (unit_price >= 0),
    -- Prix catalogue le plus élevé du conditionnement, pour mesurer la remise accordée.
    list_price      INTEGER,
    -- Coût moyen par unité de base au moment de la vente, en millièmes de franc.
    unit_cost_milli INTEGER NOT NULL,
    line_total      INTEGER NOT NULL
);

CREATE INDEX idx_sale_item_sale ON sale_item (sale_id);

-- Encaissements : paiement d'une vente (sale_id) ou remboursement d'un crédit (customer_id seul).
CREATE TABLE payment (
    id            TEXT PRIMARY KEY,
    sale_id       TEXT REFERENCES sale (id),
    customer_id   TEXT REFERENCES customer (id),
    method        TEXT NOT NULL CHECK (method IN ('ESPECES', 'MOBILE_MONEY')),
    amount        INTEGER NOT NULL CHECK (amount > 0),
    note          TEXT,
    business_date TEXT NOT NULL,
    created_at    TEXT NOT NULL,
    CHECK (sale_id IS NOT NULL OR customer_id IS NOT NULL)
);

CREATE INDEX idx_payment_business_date ON payment (business_date);
CREATE INDEX idx_payment_sale ON payment (sale_id);

-- Compte du client : montant positif = il doit davantage, négatif = il doit moins.
-- Le solde dû est la somme des lignes.
CREATE TABLE customer_ledger (
    id          TEXT PRIMARY KEY,
    customer_id TEXT NOT NULL REFERENCES customer (id),
    type        TEXT NOT NULL CHECK (type IN ('CREDIT', 'REMBOURSEMENT', 'ANNULATION')),
    amount      INTEGER NOT NULL CHECK (amount <> 0),
    sale_id     TEXT REFERENCES sale (id),
    payment_id  TEXT REFERENCES payment (id),
    note        TEXT,
    created_at  TEXT NOT NULL
);

CREATE INDEX idx_customer_ledger_customer ON customer_ledger (customer_id, created_at);
