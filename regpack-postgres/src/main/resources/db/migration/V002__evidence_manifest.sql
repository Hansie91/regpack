-- V002: the object manifest of the P3b1 slice, written from the approved docs/ledger/schema-design-p3b.md.
-- The machine-read blocks of that document (tables, constraints) are the contract of this file;
-- SchemaDocMatchesMigrationsTest compares them, and compares the three statements below with the
-- proposed text of the migration in that document. An applied script is never edited: a change is
-- a new script (the checksum is stored in schema_migration and pinned in docs/evidence/decisions.md).
--
-- One table, one index and one trigger; no existing table, column, constraint, function or
-- sequence changes (V001 and its document stay as they are). The rules for the text of a script
-- are those of V001 (ScriptCatalogue splits it): a line that starts with two hyphens is a comment,
-- a statement ends at a line whose last character is a semicolon, no schema qualification, every
-- constraint, index and trigger has an explicit name, and no SQL default reads a clock.
--
-- object_manifest holds, for every object the ledger has written to the evidence tier, the key and
-- the hash of the bytes as stored at write time. It is unpartitioned with the key as primary key
-- (decision D1: a unique key of a table cut by day cannot refuse one key with another hash on
-- another day), compares the key byte by byte (D4), names an attempt without a foreign key (D3,
-- after a restore the evidence tier is ahead of the database) and is insert-only (D2: the trigger
-- uses reject_modification of V001). No purge in release 1 (D5).
--
-- Codes (smallint, stable; the application class Codes pins the same numbers, and the database has
-- no CHECK on the set so that a new code is not a migration):
--   object_class: SEALED_FILE 1, JOURNAL_A 2, JOURNAL_B 3, LANDING_BATCH 4, ENRICHMENT_RESPONSE 5,
--   SIGNED_MANIFEST 6, OTHER 7.
--   provenance: WRITTEN 1, ADOPTED_AT_RESTORE 2.

CREATE TABLE object_manifest (
  object_key text COLLATE "C" NOT NULL, entity_id smallint NOT NULL DEFAULT 1, object_class smallint NOT NULL,
  sha256 bytea NOT NULL, size_bytes bigint NOT NULL, provenance smallint NOT NULL, attempt_id bigint,
  written_at timestamptz NOT NULL,
  CONSTRAINT object_manifest_pk PRIMARY KEY (object_key),
  CONSTRAINT object_manifest_hash_check CHECK (octet_length(sha256) = 32),
  CONSTRAINT object_manifest_size_check CHECK (size_bytes >= 0));
CREATE INDEX object_manifest_attempt_idx ON object_manifest (attempt_id) WHERE attempt_id IS NOT NULL;
CREATE TRIGGER object_manifest_immutable BEFORE UPDATE OR DELETE ON object_manifest FOR EACH ROW EXECUTE FUNCTION reject_modification();
