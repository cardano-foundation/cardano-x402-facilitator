-- V1 statuses never prove who submitted, provider acceptance, or the selected depth.
-- Nullable historical policy and LEGACY provenance deliberately preserve that uncertainty.
ALTER TABLE facilitator.settlement ADD COLUMN selected_confirmations integer;
ALTER TABLE facilitator.settlement ADD COLUMN submission_provenance varchar(16) NOT NULL DEFAULT 'LEGACY';
ALTER TABLE facilitator.settlement ADD COLUMN submission_accepted boolean NOT NULL DEFAULT false;
ALTER TABLE facilitator.settlement ADD COLUMN terms_digest varchar(64);
ALTER TABLE facilitator.settlement ADD CONSTRAINT settlement_confirmation_range
    CHECK (selected_confirmations IS NULL OR selected_confirmations BETWEEN -1 AND 20);
ALTER TABLE facilitator.settlement ADD CONSTRAINT settlement_submission_provenance
    CHECK (submission_provenance IN ('LEGACY', 'LOCAL'));
-- A single row owns both claims; conflicting terms roll back the whole insert/update.
CREATE UNIQUE INDEX settlement_terms_digest_unique ON facilitator.settlement (terms_digest);
