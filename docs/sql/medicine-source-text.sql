-- Preserve complete FDA label text. Required on an existing schema where these
-- columns were created as VARCHAR(255); Hibernate update may not widen them.
ALTER TABLE medicine
    MODIFY COLUMN brand_name TEXT,
    MODIFY COLUMN generic_name TEXT,
    MODIFY COLUMN substance_name TEXT,
    MODIFY COLUMN active_ingredient TEXT,
    MODIFY COLUMN purpose TEXT;
