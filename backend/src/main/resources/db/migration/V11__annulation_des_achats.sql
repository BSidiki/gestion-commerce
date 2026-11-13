-- Un achat fournisseur peut être annulé : la marchandise ressort du stock et l'achat ne compte plus
-- dans ce qui est dû au fournisseur. Rien n'est supprimé ; les paiements déjà faits restent enregistrés.
ALTER TABLE purchase ADD COLUMN status TEXT NOT NULL DEFAULT 'VALIDE' CHECK (status IN ('VALIDE', 'ANNULE'));
ALTER TABLE purchase ADD COLUMN cancelled_at TEXT;
ALTER TABLE purchase ADD COLUMN cancel_reason TEXT;
