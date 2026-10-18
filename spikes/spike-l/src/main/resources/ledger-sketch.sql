-- SP-L: the key DDL of the PostgreSQL ledger sketch (spec 6.3, 7.2, 11.1, 11.2). DISPOSABLE:
-- its findings, not its DDL, feed the owner-reviewed P3 design.
--
-- Rules for this file (SchemaInstaller splits it, LedgerSketchSqlTest checks it):
--   * one statement ends at a semicolon that ends a line; a line that starts with two hyphens is a comment
--   * no schema-qualified names and no CREATE SCHEMA: the installer creates the schema and sets the search path
--   * a primary or unique key of a partitioned table holds the partition key (a PostgreSQL rule)
--   * a referenced table is created before the table that references it
--   * the daily partitions of decision and ingest are created by the installer, not here
--   * variant E1 (compact: small integers, bytea) is this file as it stands; E3 (verbose) adds the
--     column path jsonb to decision (the installer runs that ALTER TABLE)

-- Known gap (L3, L4, findings for the P3 schema design): decision_head has no as_at column, so
-- "head then pruned fetch" (L-9) cannot prune the partitions of decision from the head's data
-- alone. L3 must either add as_at to the head (and report it) or measure the unpruned probe.
CREATE TABLE decision_head (
  regime text NOT NULL,
  event_identity text NOT NULL,
  decision_seq bigint NOT NULL,
  decision_id bigint NOT NULL,
  PRIMARY KEY (regime, event_identity)
);

CREATE TABLE decision (
  decision_id bigint NOT NULL,
  regime text NOT NULL,
  event_identity text NOT NULL,
  decision_seq bigint NOT NULL,
  decision_context_id bigint NOT NULL,
  input_digest bytea NOT NULL,
  as_at date NOT NULL,
  outcome smallint NOT NULL,
  body bytea,
  PRIMARY KEY (decision_id, as_at),
  UNIQUE (event_identity, decision_context_id, input_digest, as_at)
) PARTITION BY RANGE (as_at);

CREATE TABLE report_subject (
  subject_id bigint PRIMARY KEY,
  regime text NOT NULL,
  event_identity text NOT NULL,
  leg smallint NOT NULL,
  UNIQUE (regime, event_identity, leg)
);

CREATE TABLE trn_assignment (
  subject_id bigint PRIMARY KEY REFERENCES report_subject,
  executing_lei text NOT NULL,
  trn text NOT NULL,
  UNIQUE (executing_lei, trn)
);

CREATE TABLE delivery (
  delivery_id bigint PRIMARY KEY,
  endpoint_id int NOT NULL,
  business_key text NOT NULL,
  state text NOT NULL,
  gate_released boolean NOT NULL DEFAULT false
);

CREATE UNIQUE INDEX delivery_in_flight ON delivery (endpoint_id, business_key) WHERE state IN ('SENDING','AWAITING_ACK','IN_DOUBT') AND NOT gate_released;

CREATE TABLE attempt (
  attempt_id bigint PRIMARY KEY,
  delivery_id bigint NOT NULL,
  lease_epoch int NOT NULL,
  lease_counter bigint NOT NULL,
  commit_issued boolean NOT NULL DEFAULT false
);

CREATE INDEX attempt_delivery ON attempt (delivery_id);

CREATE TABLE lease (
  endpoint_id int PRIMARY KEY,
  restore_epoch int NOT NULL,
  counter bigint NOT NULL
);

CREATE TABLE seq_counter (
  endpoint_id int NOT NULL,
  restore_epoch int NOT NULL,
  seq bigint NOT NULL,
  PRIMARY KEY (endpoint_id, restore_epoch)
);

-- ingest is partitioned by trade date: its key holds trade_date, so it cannot catch the same
-- (source, record, version) arriving under two trade dates; ingest_registry (not partitioned) can.
CREATE TABLE ingest (
  source_id int NOT NULL,
  record_id text NOT NULL,
  version int NOT NULL,
  trade_date date NOT NULL,
  content_hash bytea NOT NULL,
  batch_id bigint NOT NULL,
  PRIMARY KEY (source_id, record_id, version, trade_date)
) PARTITION BY RANGE (trade_date);

CREATE TABLE ingest_registry (
  source_id int NOT NULL,
  record_id text NOT NULL,
  version int NOT NULL,
  content_hash bytea NOT NULL,
  PRIMARY KEY (source_id, record_id, version)
);

CREATE TABLE ingest_checkpoint (
  source_id int NOT NULL,
  stream text NOT NULL,
  stream_offset bigint NOT NULL,
  batch_id bigint NOT NULL,
  PRIMARY KEY (source_id, stream)
);

-- state 0 = pending; items are claimed with FOR UPDATE SKIP LOCKED
CREATE TABLE outbox (
  outbox_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  kind smallint NOT NULL,
  batch_id bigint NOT NULL,
  state smallint NOT NULL DEFAULT 0
);

CREATE INDEX outbox_pending ON outbox (outbox_id) WHERE state = 0;

CREATE TABLE report_version (
  subject_id bigint NOT NULL REFERENCES report_subject,
  version_seq int NOT NULL,
  decision_id bigint NOT NULL,
  state smallint NOT NULL,
  PRIMARY KEY (subject_id, version_seq)
);

CREATE TABLE delivery_event (
  event_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  endpoint_id int NOT NULL,
  seq bigint NOT NULL,
  kind smallint NOT NULL
);
