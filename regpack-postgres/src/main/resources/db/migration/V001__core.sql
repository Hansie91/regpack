-- V001: the 18 tables of the P3a ledger, written from the approved docs/ledger/schema-design.md.
-- The machine-read blocks of that document (tables, constraints) are the contract of this file;
-- SchemaDocMatchesMigrationsTest compares them. An applied script is never edited: a change is
-- a new script (the checksum is stored in schema_migration and pinned in docs/ledger/decisions.md).
--
-- Rules for this file (ScriptCatalogue splits it):
--   * a line that starts with two hyphens is a comment
--   * a statement ends at a line whose last character is a semicolon
--   * the pair of dollar signs appears only around the body of the function
--   * no schema qualification and no CREATE SCHEMA: the migrator sets the search path
--   * every constraint, index and trigger has an explicit name
--   * every instant column is bound by the application from its injected clock; the only SQL
--     default of the whole schema is schema_migration.applied_at
--
-- State and kind codes (smallint, stable; the application class Codes pins the same numbers):
--   delivery.state, 1-based in the order of the DeliveryState enum of the ports: PREPARED 1,
--   BLOCKED 2, QUEUED 3, SENDING 4, AWAITING_ACK 5, ACCEPTED_PROVISIONAL 6, ACCEPTED_CONFIRMED 7,
--   PENDING_AT_ENDPOINT 8, REJECTED 9, FILE_REJECTED 10, IN_DOUBT 11, RESEND_PENDING 12,
--   IN_REPAIR 13, SUPERSEDED 14, WITHDRAWN 15, WRITTEN_OFF 16.
--   outbox.state: PENDING 1, DONE 2.
-- Only these codes appear in this file, in partial-index predicates: SENDING 4, AWAITING_ACK 5,
-- IN_DOUBT 11 and the pending outbox state 1.
--
-- All sequences use CACHE 1. Generated keys of small dictionaries and of the ingest batch, the
-- subject and the outbox are identity columns; decision_id, delivery_id and attempt_id come from
-- explicit sequences.

CREATE TABLE schema_migration (
  version int NOT NULL,
  name text NOT NULL,
  sha256 text NOT NULL,
  applied_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT schema_migration_pk PRIMARY KEY (version)
);

CREATE TABLE regime (
  regime_id smallint GENERATED ALWAYS AS IDENTITY (CACHE 1),
  regime text NOT NULL,
  CONSTRAINT regime_pk PRIMARY KEY (regime_id),
  CONSTRAINT regime_name_key UNIQUE (regime)
);

CREATE TABLE source (
  source_id smallint GENERATED ALWAYS AS IDENTITY (CACHE 1),
  source text NOT NULL,
  CONSTRAINT source_pk PRIMARY KEY (source_id),
  CONSTRAINT source_name_key UNIQUE (source)
);

CREATE TABLE endpoint (
  endpoint_id int GENERATED ALWAYS AS IDENTITY (CACHE 1),
  entity_id smallint NOT NULL DEFAULT 1,
  environment text NOT NULL,
  submission_route text NOT NULL,
  submitting_entity_lei text NOT NULL,
  destination_authority text NOT NULL,
  CONSTRAINT endpoint_pk PRIMARY KEY (endpoint_id),
  CONSTRAINT endpoint_key UNIQUE (entity_id, environment, submission_route, submitting_entity_lei, destination_authority)
);

-- A row with body NULL is a stub: the decision cited its context id before the context was given.
CREATE TABLE decision_context (
  context_id int GENERATED ALWAYS AS IDENTITY (CACHE 1),
  entity_id smallint NOT NULL DEFAULT 1,
  context_sha bytea NOT NULL,
  pack_digest bytea,
  circuit_epoch bigint,
  engine_build text,
  body text,
  CONSTRAINT decision_context_pk PRIMARY KEY (context_id),
  CONSTRAINT decision_context_sha_key UNIQUE (context_sha)
);

CREATE SEQUENCE decision_id_seq AS bigint CACHE 1;

-- Range-partitioned by day on as_at, the UTC date of the decision. The daily partitions are created
-- on demand by the adapter and are not part of this script. Every unique constraint holds as_at.
CREATE TABLE decision (
  decision_id bigint NOT NULL DEFAULT nextval('decision_id_seq'),
  as_at date NOT NULL,
  entity_id smallint NOT NULL DEFAULT 1,
  regime_id smallint NOT NULL,
  event_identity text NOT NULL,
  decision_seq bigint NOT NULL,
  context_id int NOT NULL,
  input_digest bytea NOT NULL,
  prev_decision_id bigint,
  prev_as_at date,
  outcome smallint NOT NULL,
  circuit_open boolean NOT NULL DEFAULT false,
  body_encoding smallint NOT NULL,
  body bytea NOT NULL,
  CONSTRAINT decision_pk PRIMARY KEY (decision_id, as_at),
  CONSTRAINT decision_idem_key UNIQUE (regime_id, event_identity, context_id, input_digest, as_at),
  CONSTRAINT decision_context_fk FOREIGN KEY (context_id) REFERENCES decision_context (context_id)
) PARTITION BY RANGE (as_at);

CREATE INDEX decision_circuit_open_idx ON decision (context_id, as_at) WHERE circuit_open;

-- The one trigger function of the insert-only tables (decision, report_version, trn_assignment).
CREATE FUNCTION reject_modification() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION USING ERRCODE = '55000', MESSAGE = 'this table is insert-only';
  RETURN NULL;
END;
$$;

CREATE TRIGGER decision_immutable BEFORE UPDATE OR DELETE ON decision
  FOR EACH ROW EXECUTE FUNCTION reject_modification();

-- Unpartitioned. Advanced by compare-and-set in the unit that inserts the decision; the only
-- guard of chain linearity across the daily partitions.
CREATE TABLE decision_head (
  regime_id smallint NOT NULL,
  event_identity text NOT NULL,
  decision_seq bigint NOT NULL,
  decision_id bigint NOT NULL,
  as_at date NOT NULL,
  outcome smallint NOT NULL,
  entity_id smallint NOT NULL DEFAULT 1,
  CONSTRAINT decision_head_pk PRIMARY KEY (regime_id, event_identity)
);

CREATE TABLE ingest_batch (
  batch_no bigint GENERATED ALWAYS AS IDENTITY (CACHE 1),
  entity_id smallint NOT NULL DEFAULT 1,
  batch_id text NOT NULL,
  source_id smallint NOT NULL,
  result bytea NOT NULL,
  outbox_id bigint,
  created_at timestamptz NOT NULL,
  CONSTRAINT ingest_batch_pk PRIMARY KEY (batch_no),
  CONSTRAINT ingest_batch_id_key UNIQUE (batch_id)
);

CREATE TABLE ingest (
  source_id smallint NOT NULL,
  source_record_id text NOT NULL,
  source_version bigint NOT NULL,
  entity_id smallint NOT NULL DEFAULT 1,
  event_identity text NOT NULL,
  content_hash bytea NOT NULL,
  batch_no bigint NOT NULL,
  landed_on timestamptz NOT NULL,
  CONSTRAINT ingest_pk PRIMARY KEY (source_id, source_record_id, source_version),
  CONSTRAINT ingest_event_version_key UNIQUE (event_identity, source_id, source_version),
  CONSTRAINT ingest_batch_fk FOREIGN KEY (batch_no) REFERENCES ingest_batch (batch_no)
);

CREATE TABLE ingest_checkpoint (
  source_id smallint NOT NULL,
  stream text NOT NULL,
  entity_id smallint NOT NULL DEFAULT 1,
  state bytea NOT NULL,
  batch_no bigint NOT NULL,
  CONSTRAINT ingest_checkpoint_pk PRIMARY KEY (source_id, stream)
);

CREATE TABLE outbox (
  outbox_id bigint GENERATED ALWAYS AS IDENTITY (CACHE 1),
  entity_id smallint NOT NULL DEFAULT 1,
  kind smallint NOT NULL,
  subject_id text NOT NULL,
  state smallint NOT NULL,
  CONSTRAINT outbox_pk PRIMARY KEY (outbox_id),
  CONSTRAINT outbox_kind_subject_key UNIQUE (kind, subject_id)
);

CREATE INDEX outbox_pending_idx ON outbox (outbox_id) WHERE state = 1;

CREATE TABLE report_subject (
  subject_id bigint GENERATED ALWAYS AS IDENTITY (CACHE 1),
  entity_id smallint NOT NULL DEFAULT 1,
  regime_id smallint NOT NULL,
  event_identity text NOT NULL,
  leg smallint NOT NULL,
  last_version_seq bigint NOT NULL,
  CONSTRAINT report_subject_pk PRIMARY KEY (subject_id),
  CONSTRAINT report_subject_key UNIQUE (regime_id, event_identity, leg)
);

-- Assigned once at the first report version, never re-derived. The trn is 1 to 52 characters of
-- letters and digits; the application checks that, the schema holds no CHECK constraint on it.
CREATE TABLE trn_assignment (
  subject_id bigint NOT NULL,
  entity_id smallint NOT NULL DEFAULT 1,
  executing_lei text NOT NULL,
  trn text NOT NULL,
  key_id text NOT NULL,
  assigned_at timestamptz NOT NULL,
  CONSTRAINT trn_assignment_pk PRIMARY KEY (subject_id),
  CONSTRAINT trn_assignment_subject_fk FOREIGN KEY (subject_id) REFERENCES report_subject (subject_id),
  CONSTRAINT trn_assignment_lei_trn_key UNIQUE (executing_lei, trn)
);

CREATE TRIGGER trn_assignment_immutable BEFORE UPDATE OR DELETE ON trn_assignment
  FOR EACH ROW EXECUTE FUNCTION reject_modification();

-- body is plain canonical JSON until the key hierarchy of P3c; body_encoding 0 is plain and 1 is
-- reserved for the encrypted form. A decision is referenced with its as_at, because decision_id is
-- unique across the partitions only by its sequence.
CREATE TABLE report_version (
  subject_id bigint NOT NULL,
  version_seq bigint NOT NULL,
  entity_id smallint NOT NULL DEFAULT 1,
  decision_id bigint NOT NULL,
  decision_as_at date NOT NULL,
  action text NOT NULL,
  logical_hash bytea NOT NULL,
  body bytea NOT NULL,
  body_encoding smallint NOT NULL,
  origin smallint NOT NULL,
  created_at timestamptz NOT NULL,
  CONSTRAINT report_version_pk PRIMARY KEY (subject_id, version_seq),
  CONSTRAINT report_version_decision_subject_key UNIQUE (decision_id, subject_id),
  CONSTRAINT report_version_subject_fk FOREIGN KEY (subject_id) REFERENCES report_subject (subject_id),
  CONSTRAINT report_version_decision_fk FOREIGN KEY (decision_id, decision_as_at) REFERENCES decision (decision_id, as_at)
);

CREATE TRIGGER report_version_immutable BEFORE UPDATE OR DELETE ON report_version
  FOR EACH ROW EXECUTE FUNCTION reject_modification();

CREATE SEQUENCE delivery_id_seq AS bigint CACHE 1;

-- subject_id is NULL only for a delivery made through the bare createDelivery of the port; the
-- foreign key is not checked while it is NULL. batch_id is the ingest_batch.batch_no of the
-- micro-batch that produced the delivery (no foreign key).
CREATE TABLE delivery (
  delivery_id bigint NOT NULL DEFAULT nextval('delivery_id_seq'),
  entity_id smallint NOT NULL DEFAULT 1,
  endpoint_id int NOT NULL,
  endpoint_epoch bigint NOT NULL,
  business_key text NOT NULL,
  version_seq bigint NOT NULL,
  state smallint NOT NULL,
  gate_released boolean NOT NULL DEFAULT false,
  subject_id bigint,
  batch_id bigint,
  created_at timestamptz NOT NULL,
  CONSTRAINT delivery_pk PRIMARY KEY (delivery_id),
  CONSTRAINT delivery_key UNIQUE (endpoint_id, endpoint_epoch, business_key, version_seq),
  CONSTRAINT delivery_version_fk FOREIGN KEY (subject_id, version_seq) REFERENCES report_version (subject_id, version_seq)
);

-- The in-flight guard (I2): states SENDING 4, AWAITING_ACK 5 and IN_DOUBT 11, not gate-released.
CREATE UNIQUE INDEX delivery_in_flight ON delivery (endpoint_id, business_key) WHERE state IN (4, 5, 11) AND NOT gate_released;

CREATE INDEX delivery_subject_version_idx ON delivery (subject_id, version_seq);

-- Serves the takeover sweep, which selects exactly the SENDING state (code 4).
CREATE INDEX delivery_sending_idx ON delivery (endpoint_id) WHERE state = 4;

CREATE SEQUENCE attempt_id_seq AS bigint CACHE 1;

CREATE TABLE attempt (
  attempt_id bigint NOT NULL DEFAULT nextval('attempt_id_seq'),
  entity_id smallint NOT NULL DEFAULT 1,
  delivery_id bigint NOT NULL,
  lease_restore_epoch bigint NOT NULL,
  lease_counter bigint NOT NULL,
  artifact_sha256 bytea NOT NULL,
  file_name text NOT NULL,
  commit_issued boolean NOT NULL DEFAULT false,
  created_at timestamptz NOT NULL,
  CONSTRAINT attempt_pk PRIMARY KEY (attempt_id),
  CONSTRAINT attempt_delivery_fk FOREIGN KEY (delivery_id) REFERENCES delivery (delivery_id)
);

CREATE INDEX attempt_delivery_idx ON attempt (delivery_id);

-- The fencing token is the pair (restore_epoch, counter), compared as a pair.
CREATE TABLE lease (
  entity_id smallint NOT NULL DEFAULT 1,
  endpoint_id int NOT NULL,
  restore_epoch bigint NOT NULL,
  counter bigint NOT NULL,
  holder text NOT NULL,
  expires_at timestamptz NOT NULL,
  released boolean NOT NULL,
  CONSTRAINT lease_pk PRIMARY KEY (entity_id, endpoint_id),
  CONSTRAINT lease_endpoint_fk FOREIGN KEY (endpoint_id) REFERENCES endpoint (endpoint_id)
);

-- One row, seeded here: declaring a restore raises epoch, the adapter never inserts a second row.
CREATE TABLE restore_epoch (
  singleton boolean NOT NULL DEFAULT true,
  epoch bigint NOT NULL,
  CONSTRAINT restore_epoch_pk PRIMARY KEY (singleton),
  CONSTRAINT restore_epoch_singleton_check CHECK (singleton)
);

INSERT INTO restore_epoch (singleton, epoch) VALUES (true, 0);
