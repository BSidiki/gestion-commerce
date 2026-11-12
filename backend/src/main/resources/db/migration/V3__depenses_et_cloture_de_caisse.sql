-- Phase 2 (suite) : dépenses et clôture de caisse.

-- Une session de caisse va de l'ouverture (fond de caisse) à la clôture (montant compté).
CREATE TABLE cash_session (
    id            TEXT PRIMARY KEY,
    status        TEXT NOT NULL CHECK (status IN ('OUVERTE', 'CLOTUREE')),
    opening_float INTEGER NOT NULL CHECK (opening_float >= 0),
    opened_at     TEXT NOT NULL,
    closed_at     TEXT,
    -- Figés à la clôture : espèces attendues, espèces comptées, écart (compté - attendu).
    expected_cash INTEGER,
    counted_cash  INTEGER CHECK (counted_cash IS NULL OR counted_cash >= 0),
    difference    INTEGER,
    note          TEXT
);

-- Une seule caisse ouverte à la fois.
CREATE UNIQUE INDEX idx_cash_session_open ON cash_session (status) WHERE status = 'OUVERTE';

CREATE TABLE expense (
    id              TEXT PRIMARY KEY,
    category        TEXT NOT NULL,
    amount          INTEGER NOT NULL CHECK (amount > 0),
    method          TEXT NOT NULL CHECK (method IN ('ESPECES', 'MOBILE_MONEY')),
    note            TEXT,
    cash_session_id TEXT REFERENCES cash_session (id),
    business_date   TEXT NOT NULL,
    created_at      TEXT NOT NULL
);

CREATE INDEX idx_expense_business_date ON expense (business_date);
CREATE INDEX idx_expense_cash_session ON expense (cash_session_id);

-- Chaque encaissement est rattaché à la caisse ouverte au moment où il est reçu.
ALTER TABLE payment ADD COLUMN cash_session_id TEXT REFERENCES cash_session (id);

CREATE INDEX idx_payment_cash_session ON payment (cash_session_id);
