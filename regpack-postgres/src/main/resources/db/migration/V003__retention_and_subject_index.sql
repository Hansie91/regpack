-- V003: the retention rows and the data-subject index of the P3c1 slice, written from the approved
-- docs/ledger/schema-design-p3c.md (the SHA-256 of that document is recorded as
-- approved-schema-p3c-sha256 in docs/evidence/p3c-decisions.md). The machine-read blocks of the
-- document (tables, constraints) are the contract of this file; SchemaDocMatchesMigrationsTest
-- compares them, and compares the six statements below with the proposed text of the migration in
-- that document. An applied script is never edited: a change is a new script (the checksum is
-- stored in schema_migration and pinned in docs/evidence/p3c-decisions.md).
--
-- Two tables, two indexes, one function and one trigger; no existing table, column, constraint,
-- function or sequence changes (V001, V002 and their documents stay as they are). The rules for the
-- text of a script are those of V001 (ScriptCatalogue splits it): a line that starts with two
-- hyphens is a comment, a statement ends at a line whose last character is a semicolon, the pair of
-- dollar signs appears only around the function body, no schema qualification, every constraint,
-- index and trigger has an explicit name, and no SQL default reads a clock.
--
-- retention holds one row per (evidence key, regime, anchor event), with a date that is extend-only
-- (decision D2: the trigger retention_extend_only refuses an update that shortens a date, turns a
-- date into NULL, or changes the key, regime, anchor or class of a row; legal_hold and recorded_at
-- stay updatable; the adapter writes GREATEST, which ignores NULL, so an open anchor takes a value
-- once and never becomes open again). The regime is text (D3) and the key columns are compared byte
-- by byte (D6). The partial index serves the expiry list (D5). There is no foreign key (the evidence
-- tier is ahead of the database after a restore) and no purge in release 1 (D7).
--
-- subject_index holds the keyed hash of an identifier of a natural person (HMAC-SHA-256, 32 bytes)
-- and the reference of the artifact that names the person; nothing is encrypted at rest (D4). There
-- are no key tables (D1): the wrapped data key travels inside each envelope.
--
-- Codes (smallint, stable; the application class Codes pins the same numbers, and the database has
-- no CHECK on the set so that a new code is not a migration):
--   anchor_event: OBJECT_WRITTEN 1, TERMINATION 2.
--   ref_kind: REPORT_VERSION 1, EVIDENCE_OBJECT 2.

CREATE TABLE retention (
  object_key text COLLATE "C" NOT NULL, regime text COLLATE "C" NOT NULL, anchor_event smallint NOT NULL,
  entity_id smallint NOT NULL DEFAULT 1, retention_class text NOT NULL, retain_until timestamptz,
  legal_hold boolean NOT NULL, recorded_at timestamptz NOT NULL,
  CONSTRAINT retention_pk PRIMARY KEY (object_key, regime, anchor_event));
CREATE INDEX retention_expiry_idx ON retention (retain_until, object_key) WHERE retain_until IS NOT NULL AND NOT legal_hold;
CREATE TABLE subject_index (
  subject_hash bytea NOT NULL, ref_kind smallint NOT NULL, ref text COLLATE "C" NOT NULL,
  entity_id smallint NOT NULL DEFAULT 1, recorded_at timestamptz NOT NULL,
  CONSTRAINT subject_index_pk PRIMARY KEY (subject_hash, ref_kind, ref),
  CONSTRAINT subject_index_hash_check CHECK (octet_length(subject_hash) = 32));
CREATE INDEX subject_index_ref_idx ON subject_index (ref_kind, ref);
CREATE FUNCTION retention_extend_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.object_key IS DISTINCT FROM OLD.object_key
     OR NEW.regime IS DISTINCT FROM OLD.regime
     OR NEW.anchor_event IS DISTINCT FROM OLD.anchor_event
     OR NEW.retention_class IS DISTINCT FROM OLD.retention_class
     OR (OLD.retain_until IS NOT NULL AND (NEW.retain_until IS NULL OR NEW.retain_until < OLD.retain_until)) THEN
    RAISE EXCEPTION USING ERRCODE = '55000', MESSAGE = 'retention rows are extend-only';
  END IF;
  RETURN NEW;
END;
$$;
CREATE TRIGGER retention_extend_only BEFORE UPDATE ON retention FOR EACH ROW EXECUTE FUNCTION retention_extend_only();
