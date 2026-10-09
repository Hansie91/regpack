# P0 Implementation Plan: Foundation and Walking Skeleton

> **Announcement.** This is the **P0 implementation plan** for regpack (plan id `P0`, the repository, build, CI, ports and walking-skeleton part of increment S0). It was written on 2026-10-09 against the approved design specification and is saved as `docs/superpowers/plans/2026-10-09-p0-foundation-and-walking-skeleton.md`.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal.** Build the foundation every later plan stands on: a Maven multi-module Java 25 repository with CI on Linux and Windows Server runners, a supply-chain gate (SBOM, licence allow-list) and a traceability gate; every port of spec 5.2 (including the connector, transport, `EnrichmentSource` lookup, secrets and KMS ports) as owner-reviewed Java signatures with in-memory adapters and port-contract suites; canonical and decision types; a golden-test harness with provenance; a deterministic fault simulator; a destination simulator skeleton; a walking skeleton in which three scope rules run in memory to a decision record and `explain`; and a packaging skeleton whose smoke run (start, health probe, toy pipeline, clean stop) passes on Linux (systemd), Windows Server (Windows service) and Kubernetes (Helm on kind).

**Architecture summary.**

- Seven Maven modules under groupId `dev.regpack`, Java package root `dev.regpack`, JDK-only code (no third-party runtime dependency; enforced at build time). Dependency direction: `regpack-tools` (no deps) ; `regpack-model` (no deps) <- `regpack-ports` <- `regpack-testkit`, `regpack-inmemory`, `regpack-engine` <- `regpack-app`. `testkit` and `tools` are test-scope dependencies of the other modules; `inmemory` is a test dependency of `engine` and a runtime dependency of `app` (the walking skeleton wires in-memory adapters); `testkit` never depends on `inmemory` (no cycle).
- **Ports are the deliverable.** Each port is an interface in `regpack-ports`; each has an in-memory adapter (or, for `Transport` and `EnrichmentSource`, a deterministic simulator in `regpack-testkit`) and an abstract contract suite in `regpack-testkit` written as JUnit interfaces with default test methods. Concrete contract tests in the adapter's module bind a suite to an adapter; P3 binds the same suites to PostgreSQL and the evidence adapters.
- **Contract suites must have teeth for the stores that P3 rebinds to real systems.** The `EvidenceStore` and `LedgerStore` in-memory adapters accept a deliberate `Flaw` (test double only) and a meta-test proves that each of those two suites fails against each flawed adapter. The `SourceConnector`, `Transport`, lookup, `Kms` and `ReferenceData` suites have no mutants in P0; P7a, P8, P5 and P3 add one when they bind a real adapter.
- **Walking skeleton (marked, replaced by P2a).** `regpack-engine` holds a regime-agnostic `MicroBatchPipeline` (land, ingest, acknowledge, decide, persist; spec 7.2 order), a cost-unit `MeteredExecutor`, a tier-1 `explain` renderer, and a clearly marked `skeleton` package with a stand-in `Expression`, three placeholder scope rules and the mandatory catch-all row. Nothing in `skeleton` is regime logic that survives P2a.
- **Determinism is checked structurally.** A bytecode scan (JDK `java.lang.classfile`) bans wall clock, randomness and default-locale calls in rule-reachable modules (I3); tests run under a hostile locale and time zone (`tr_TR`, `Pacific/Kiritimati`) so locale or zone dependence fails on every machine; the toy crash test publishes a state digest that CI compares between the Linux and the Windows runner.
- **One build, three targets.** `regpack-app` produces one distribution (zip and tar.gz, unversioned jar names, launcher scripts, systemd unit, Windows scripts, SBOM). The same distribution is installed by the Linux smoke (systemd), the Windows smoke (Apache Commons Daemon procrun service) and the container image used by the Kubernetes smoke (Helm chart, single replica, probes, kind).

**Tech stack** (every version below was checked on 2026-10-09 against the primary source named in Appendix A).

| Concern | Choice | Pin |
|---|---|---|
| Language | Java 25 LTS (Temurin), `--release 25`, no preview features, no native image | Temurin 25.0.4.1+1 for local use; CI uses `actions/setup-java` `temurin` `25` |
| Build | Apache Maven multi-module with the Maven Wrapper (`only-script` type) | Maven 3.9.16; wrapper plugin 3.3.4 |
| Compile and test | maven-compiler-plugin, maven-surefire-plugin, maven-failsafe-plugin | 3.16.0, 3.6.0, 3.6.0 |
| Test libraries | JUnit Jupiter (BOM) and AssertJ | 6.1.3 and 3.27.7 (4.0.0-M1 is a milestone and is not used) |
| Build rules | maven-enforcer-plugin | 3.6.3 |
| Packaging and lifecycle | maven-jar-plugin, maven-assembly-plugin, maven-resources-plugin, maven-clean-plugin; maven-install-plugin, maven-deploy-plugin, maven-site-plugin (pinned only because the enforcer rule `requirePluginVersions` checks the clean, deploy and site lifecycles) | 3.5.1, 3.8.0, 3.5.0, 3.5.0; 3.2.0, 3.2.0, 3.22.0 |
| Formatting | spotless-maven-plugin with google-java-format | 3.10.4 and 1.35.0 |
| Supply chain | cyclonedx-maven-plugin (SBOM); license-maven-plugin (licence allow-list) | 2.9.3 and 2.7.1 |
| Health endpoint | JDK `com.sun.net.httpserver` (module `jdk.httpserver`), virtual-thread executor; no web framework | JDK built-in |
| Windows service | Apache Commons Daemon procrun (`prunsrv.exe`), jvm mode | 1.6.1, SHA-512 pinned |
| Container | `eclipse-temurin:25-jre-noble` pinned by digest | `sha256:d9a39a23634650173f1e2bbc176227af9728587ecf0f4b62d53e9355cd7a19ab` (2026-10-04) |
| Kubernetes smoke | kind, kindest/node, Helm | kind v0.33.0; `kindest/node:v1.36.4@sha256:099e049362a1526b2db71494e1947aae99bd16290d7c895f2b7ea312e3cbfaed`; Helm v4.2.4 |
| CI | GitHub Actions, workflows in `.github/workflows`, third-party and first-party actions pinned by full commit SHA | checkout v7.0.1, setup-java v6.0.1, upload-artifact v7.0.1, download-artifact v8.0.1; runners `ubuntu-24.04`, `windows-2025` |

**Approved specification and plan scope.** Spec: [`docs/superpowers/specs/2026-10-08-regulatory-reporting-platform-design.md`](../specs/2026-10-08-regulatory-reporting-platform-design.md) (approved by the owner 2026-10-09). This plan implements **P0 (section 16 row P0)** and the P0 share of **increment S0 (section 15)**. Project facts decided by the owner: working title and name `regpack`; Maven groupId and Java base package `dev.regpack`; module names `regpack-*`; repository root `C:\Users\hans_\OneDrive\Documenten\ProjectRere` (git initialised on `main`); CI is GitHub Actions with a Linux and a Windows Server matrix and a kind-based Kubernetes smoke test.

**P0 exit criterion (spec section 16, verbatim).** Three scope rules run in memory to a decision record and `explain`; a toy crash test is deterministic; the contract suites pass on the in-memory adapters; the smoke test passes on Linux, Windows Server and Kubernetes.

## Global constraints (copied verbatim from the approved specification)

The statements below are quoted without change; a line `> [...]` marks text of the same section that is left out, and where a block skips bullets a line under it says which. Where a task seems to conflict with one of them, the specification wins and the task is a defect to report.

**Spec 0 (How to read this document): release terms and normative words**

> - **Release terms.** *Release 1* is the first production release and delivers everything in section 15. *MVP* and *v1* (in the Brief and in earlier drafts) mean release 1; *v1.1* means the release after release 1. Items deferred past release 1 are named as such and listed in section 18; those with a plan appear in P11 to P13 (section 16), the others are not scheduled. "R1" always means requirement R1, never a release.
> - **MUST / SHOULD** are normative. Everything else is explanation.

**Spec 2 (Decision record): D4 and D10**

> | # | Decision | Choice |
> |---|---|---|
> | D4 | Runtime targets | Linux, Windows Server and Kubernetes must all be supported ("supported" is defined in §5.2) |
> | D10 | Maintainers | Mostly the owner with AI assistance: favour simplicity, strict typing, strong tests, few moving parts |

**Spec 4 (Design invariants)**

> Each invariant is testable and maps to named evidence (§14.4).
> 
> | # | Invariant |
> |---|---|
> | I1 | **Durable before effect.** No first byte to a destination, no acknowledgement to a source and no move or delete of a source file happens until the record that justifies it is durable (ledger committed with synchronous-standby acknowledgement, and bytes or journal objects acknowledged by the evidence store as durable, §5.4). The system fails closed. |
> | I2 | **Correctness comes from ledger constraints** (unique keys, partial unique in-flight index, fenced leases), not from messaging semantics or exactly-once claims. |
> | I3 | **Reproducible.** Every stage is a deterministic function of pinned inputs: pack digest, overlay, interpretation pins, reference-data snapshots, enrichment responses, mapping version, Firm Profile version and engine build. Rules use no wall clock, randomness or locale; UTC and decimal arithmetic only. Enrichment snapshots are durable before any decision cites them (§7.5). **Replay compares** decisions, derived fields, validation results and `logical_hash`. Sealed bytes are reproduced only by re-projecting with the artefact's stored volatile inputs (file sequence, header id, creation instant, file name, projection version), which are stored with the artefact. |
> | I4 | **Regulation is data.** Rules, reason codes, citations, validation catalogues, destination profiles and schema pins are signed, versioned data. Regime logic found in engine code is a design alarm. |
> | I5 | **Every candidate gets a decision with a reason and citation, negatives included.** UNDETERMINED never silently becomes DO_NOT_REPORT. |
> | I6 | **Silence is neither success nor failure.** No blind resend. Automatic resend is limited to content the destination provably never processed (not received, or rejected whole at file level). |
> | I7 | **Completeness is proven daily in two directions.** Downstream: every REPORT decision maps to an accepted version or an open queue item, and every REPORT_BY_OTHER decision to CONFIRMED_BY_OTHER or an open NOT_CONFIRMED item (the exact classes are in §9.5). Upstream: every expected source record maps to an ingested record and a decision, proven against an independent population where one exists and labelled UNPROVEN where not. |
> | I8 | **Production and non-production never mix.** A non-production credential, destination or acknowledgement can never satisfy a production completeness, acceptance or retention rule. |
> | I9 | **One sender per endpoint.** A lease with a fencing token, expiry and pre-commit re-check narrows the window for a second sender; it cannot close it. Harm from a late sender is bounded by the in-flight index, `logical_hash` suppression, destination duplicate rejection and the IN_DOUBT rules (I6). |
> | I10 | **The evidence tier is never rebuilt; the database is restored from backup and made current from the evidence tier.** Everything that gated an external effect is reconstructable from the evidence tier. The database is restored from a base backup with PITR and brought current by journal gap replay and re-derivation of the unjournaled tail (§11.5.B). A full rebuild from the evidence tier alone is a degraded secondary path that recovers only what journal objects and landing batches cover; its time is measured by SP-L, and state that is neither journalled nor re-derivable (for example queue assignments and comments) is bounded by the backup RPO and listed in the restore report. |

**Spec 5.2 (Runtime and packaging): role flag, ports and adapters, port contracts, targets**

> ### 5.2 Runtime and packaging
> 
> - **One codebase, one version, one process by default.** A role flag (`ingest`, `engine`, `assembler`, `sender`, `feedback`, `timekeeper`, `control-plane`, `console`, `signer`) selects roles; all run in one JVM except `signer`. The web console and API are served by this process. The `timekeeper` role also fires source schedules and manual runs (§7.8); a due run is a `timer` row. The `signer` role is the one deliberate exception to "one process": the same image and release run as a separate process under its own workload identity, the only identity with use permission on the pack-signing key and on the manifest-signing key (two separate KMS keys, each under its own policy), so that the application and CI never hold either key and the application identity can invoke neither. The role has two functions. *Manifest signing* (minimal version in plan P3; used by both evidence profiles, §5.4) signs only a manifest that the signer builds itself from the ledger's write-time hashes and the store listing, never an arbitrary payload. *Pack signing* (plan P9b, §6.5 step 5) performs the attestation check and then the signature; packs may be activated unsigned before increment S8 only as §6.5 step 6 states. The `signer` counts as a role of the application image, not as a fourth versioned component.
> - **Ports and adapters.** Every external dependency sits behind an interface with an in-memory test adapter: `LedgerStore`, `EvidenceStore`, `Clock`, `Transport`, `Executor`, `Expression`, `ReferenceData`, `EnrichmentSource`, `RegimeProvider`. This is what makes TDD and the deterministic destination simulator possible.
> - **Port contracts.** `LedgerStore` is a unit-of-work API: every "same transaction" rule in this document is one call on the port, so the in-memory and PostgreSQL adapters share one invariant suite. `Transport` is the destination side of the connector contract (`send`, `probe`, `poll_acks`) and `EnrichmentSource` is its `lookup` kind: there is one SPI, defined in P0. A `Batch` payload is bytes plus media type plus manifest; a JDBC extract is serialised to canonical NDJSON before landing. The connector, transport and `EvidenceStore` conformance suites assert checkpoint resume, backpressure, idempotent redelivery and fault injection. Method signatures are P0 deliverables reviewed by the owner.
> [...]
> - **Targets:** Linux (systemd service or container), Windows Server (Windows service), Kubernetes (Helm chart, health probes; one active replica plus cold standby, not horizontal scale-out). "Supported" means that the same signed build installs and passes the smoke run, a restore and `verify --deep` on each target at the release-candidate tier (§14.5). Platform-specific behaviour (file-completion detection, move-to-rejected, SFTP rename, KMS and secrets options) is covered by the connector and transport conformance suites, which run on every target at the tiers of §14.5.

Omitted from 5.2: the image-flavours, one-active-sender, upgrade and moving-parts bullets (the one-active-sender rule is why the Helm chart is single-replica, T31; the sender-lease row it rests on is quoted under spec 10).

**Spec 5.3 (Stack): language, expressions, supply chain, not used**

> | Layer | Choice | Notes |
> |---|---|---|
> | Language/runtime | Java 25 LTS (Temurin or another OpenJDK build) | Virtual threads for I/O, bounded pools for CPU; no preview features; no native-image baseline |
> | Expressions | `cel-java` behind the `Expression` port, pending spike SP-R; in-house CEL-shaped subset over `BigDecimal` as fallback | |
> | Supply chain | CycloneDX SBOM, dependency scanning, licence allow-list in CI | |
> | Not used in release 1 | libxml2, DuckDB/DataFusion/Arrow in the core, Kafka as data plane, Camel/NiFi/Airbyte as connector layer, immudb, Litestream as primary store | |

**Spec 6.2 (Rule language)**

> ### 6.2 Rule language
> 
> YAML decision tables plus a CEL-shaped, non-Turing-complete expression subset: bool, int, scaled decimal, string, UTC timestamp, date, enum, list; explicit absence (`has(x)`); evaluation errors yield UNDETERMINED; no loops, I/O, clock or user-defined functions; a per-rule cost ceiling; a vetted built-in library (LEI mod 97-10, ISIN Luhn, CFI, MIC, dates, decimals) versioned and pinned through `engine_min`. CI always enforces a mandatory catch-all row per table (default outcome UNDETERMINED, I5), unique priorities and the coverage gates; full static analysis for gaps, overlaps, unreachable and shadowed rows (plan P2b) is built if gate G-S3b passes (§6.8). There is no Rune-like textual language in release 1.

**Spec 6.3 (Decision record)**

> ### 6.3 Decision record and reason codes
> 
> Every candidate gets exactly one *current* decision, negatives included:
> 
> ```
> { decision_id, decision_seq, supersedes_decision_id, event_identity, regime,
>   decision_context_id,   // pack digest, overlay digest, interpretation pins, reference and enrichment snapshot ids,
>                          // firm_profile_version, mapping_version, engine_build, executor
>   as_at, evaluated_at,
>   outcome: REPORT | DO_NOT_REPORT | REPORT_BY_OTHER | UNDETERMINED,
>   flags[], reason_code, node, rule_ids_hit[], path[], citations[],
>   legs[],                // N11 output, REPORT only: MARKET | CLIENT | ALLOCATION
>   input_digest }
> ```

**Spec 6.6 (Execution)**

> ### 6.6 Execution
> 
> One typed IR (canonical JSON). Release 1 has **one executor behind an `Executor` port: the reference interpreter**, a tree-walking evaluator with an optional full trace; it is the executable specification, used by production, by `explain` and by golden tests. Before the S3b gate it evaluates the YAML tables directly. The expression layer is `cel-java` if SP-R shows its numeric, date, null and error behaviour is adequate for scaled decimals and timestamps, otherwise a minimal in-house subset; both sit behind the `Expression` port. A faster executor is added **only if the benchmark gate fails**. Proposed gates (placeholders): at least 10,000 records/s/core on a 200-rule pack, bounded memory at 50M records, p99 `explain` under one second. Each record runs under a per-record executor budget counted in deterministic cost units (interpreter steps plus list-length and string-size limits) enforced inside the interpreter, so the same input always gives the same outcome. A wall-clock watchdog may raise an alarm but never changes a decision.

**Spec 7.1 (Connector model): the contract bullet**

> ### 7.1 Connector model (R3)
> 
> - **Contract** (versioned, language-neutral in specification, in-process Java interfaces in release 1): source `spec`, `check`, `discover` (schema and stable keys), `read(state, limits)` emitting `Batch{stream, seq, payload, source_coordinates, checkpoint}`; credit-based backpressure; opaque state; explicit acknowledgement. Destination: `spec`, `check`, `send(file)`, `probe(file_id)`, `poll_acks()` declaring its `ack_model`. Enrichment sources use a `lookup` kind (§7.5).
> [...]

**Spec 7.2 (Commit rule)**

> ### 7.2 Commit rule
> 
> Order, per micro-batch (§7.8): (1) write the batch to the landing zone (content-addressed, idempotent) and PUT its `BatchAdmitted` journal object (§11.2); (2) one PostgreSQL transaction records the ingest row, checkpoint and outbox row; (3) acknowledge the source. A crash between steps leaves a harmless orphan object and a redelivered batch that is a no-op through `ingest_key`. Delivery is at-least-once; the acknowledgement follows durability (I1) and idempotent landing through `ingest_key` makes redelivery a no-op (I2).
> [...]

**Spec 7.3 (Ingest faults and poison data): the EXEC row**

> | Fault | Disposition |
> |---|---|
> [...]
> | Record throws or overruns the executor | Decision is UNDETERMINED (`EXEC_ERROR` / `EXEC_BUDGET`) carrying (rule_id, error class, cost units, §6.6); a poison counter per rule; after N `EXEC_*` outcomes in M records (defaults set by SP-R) the rule's circuit opens, and affected records get reason `EXEC_CIRCUIT_OPEN` and a data-quality item (dashboard and notification hook), and are re-evaluated by a superseding decision when the circuit closes |
> [...]

**Spec 7.5 (Enrichment): the Contract, Efficiency, Provenance and Replay rows**

> ### 7.5 Enrichment (decision D11)
> 
> [...]
> 
> | Aspect | Design |
> |---|---|
> | Contract | `lookup(keys[], context)` returns per key HIT, MISS or ERROR with an as-of and a canonical serialisation whose SHA-256 is the response hash; partial results are allowed; timeout, batch size and rate limit come from the source configuration. The SPI is the `lookup` kind defined in P0 |
> [...]
> | Efficiency | Lookups are batched per micro-batch on distinct keys, with caching, rate limits, timeouts and a circuit breaker. Credentials are references into the secrets manager |
> | Provenance | Every enriched value records source, source version or as-of, request key and response hash |
> [...]
> | Replay | The sealed snapshot maps each request key to its response hash and is pinned to the batch. A crash, retry or redelivery reuses a sealed snapshot whose `enrichment_response` rows have committed and never calls the provider again for it; a snapshot object without committed rows is an orphan (§5.4) and is ignored, and the batch re-fetches, because nothing external depends on it (see Commit order). A re-enrichment after a miss (an UNDETERMINED decision or a HOLD) seals a new snapshot that the superseding decision's context references. A replay reads the stored answers and never calls the live provider; restore step 3 treats a tail with no stored snapshot as a first fetch |

**Spec 8.4 (Reference-data service): the first bullet**

> ### 8.4 Reference-data service, calendars, clock
> 
> - **Sealed snapshots with ids stamped on every decision.** GLEIF Golden Copy files are current-state snapshots and ESMA's FIRDS instructions tell NCAs to build their own LEI status history (**V**, rev 3, 2022), so the Golden Copy is ingested into our own **LEI status-interval table**, which reproducibility needs in any case; ESMA FIRDS full plus delta (valid-from/valid-to plus latest flag); ISO 10383 MIC list; ISO 4217, 3166, CFI. PostgreSQL `WITHOUT OVERLAPS` enforces non-overlapping validity. A snapshot id is a monotonic load sequence of a dataset's interval tables, not a file copy. Every lookup takes explicit as-of dates (trade date and submission date). `revalidate --as-of D --snapshot S` reproduces a past decision.

**Spec 9.3 (State machine): the single edge list and the state vocabulary**

> ### 9.3 State machine
> 
> ```mermaid
> stateDiagram-v2
>   [*] --> PREPARED
>   PREPARED --> BLOCKED: predecessor open or lease frozen
>   BLOCKED --> PREPARED: cleared
>   PREPARED --> QUEUED: released by sequencer
>   QUEUED --> SENDING: sealed, intent durable
>   SENDING --> QUEUED: failed before the commit_issued marker
>   SENDING --> AWAITING_ACK: transport confirmed
>   SENDING --> IN_DOUBT: crash, timeout, lease takeover or unclean lease loss after the SENDING transition
>   AWAITING_ACK --> ACCEPTED_PROVISIONAL: ACPT
>   AWAITING_ACK --> PENDING_AT_ENDPOINT: PDNG or RCVD
>   AWAITING_ACK --> REJECTED: record RJCT with codes
>   AWAITING_ACK --> FILE_REJECTED: file level failure
>   AWAITING_ACK --> IN_DOUBT: feedback horizon elapsed
>   AWAITING_ACK --> IN_DOUBT: duplicate-NEWT rejection (earlier attempt may have been accepted)
>   PENDING_AT_ENDPOINT --> ACCEPTED_PROVISIONAL
>   PENDING_AT_ENDPOINT --> REJECTED
>   PENDING_AT_ENDPOINT --> IN_DOUBT: pdng_horizon elapsed
>   IN_DOUBT --> ACCEPTED_PROVISIONAL: endpoint evidence
>   IN_DOUBT --> REJECTED: endpoint evidence
>   IN_DOUBT --> PENDING_AT_ENDPOINT: endpoint evidence (PDNG)
>   IN_DOUBT --> RESEND_PENDING: non-ingestion proven
>   IN_DOUBT --> QUEUED: non-delivery proven by probe before any commit (sealed file reused, §11.5 A.3)
>   IN_DOUBT --> SUPERSEDED: later version of the key accepted (restore resolution)
>   IN_DOUBT --> WRITTEN_OFF: two-approver closure with incident
>   FILE_REJECTED --> RESEND_PENDING: content unchanged
>   FILE_REJECTED --> IN_REPAIR: this record caused it
>   FILE_REJECTED --> IN_DOUBT: duplicate file name or message id (earlier attempt received)
>   REJECTED --> IN_REPAIR
>   IN_REPAIR --> PREPARED: corrected, new report_version
>   IN_REPAIR --> WRITTEN_OFF: approved closure
>   RESEND_PENDING --> QUEUED: new attempt artifact (after possible receipt or whole-file reject)
>   ACCEPTED_PROVISIONAL --> ACCEPTED_CONFIRMED: profile confirmation rule
>   ACCEPTED_PROVISIONAL --> REJECTED: end-of-day rejection
>   ACCEPTED_PROVISIONAL --> SUPERSEDED: later version accepted (successor released before confirmation)
>   ACCEPTED_CONFIRMED --> SUPERSEDED: later version accepted
>   PREPARED --> WITHDRAWN: superseding decision not REPORT or leg dropped
>   BLOCKED --> WITHDRAWN: same
>   QUEUED --> WITHDRAWN: same
>   RESEND_PENDING --> WITHDRAWN: same
>   IN_REPAIR --> WITHDRAWN: same
> ```
> 
> HOLD is a pre-version validation disposition only. BLOCKED is a delivery state. In PENDING_AT_ENDPOINT silence is expected (the NCA sends no repeat status, §9.2); only `pdng_horizon` moves the delivery on. The diagram is the single edge list: the §14.4 consistency test reads it, and every resolution of the §11.5 B.7 table is an IN_DOUBT edge of it.

**Spec 10 (Identity): in-flight guard and sender lease rows**

> | Key | Definition | Enforced by |
> |---|---|---|
> | In-flight guard | At most one delivery per (endpoint, business_key) in SENDING, AWAITING_ACK or IN_DOUBT, except a predecessor released by a journalled `successor_gate` PROCEED while it is AWAITING_ACK (§9.7) | **partial UNIQUE index** `WHERE state IN (...) AND NOT gate_released` |
> | Sender lease | One `lease` row per (entity partition, endpoint) with a fencing token and an expiry on the database clock (`lease_ttl`, placeholder 60 s) renewed by heartbeat; another node may acquire only after expiry or a clean release. The token is the pair (`restore_epoch`, counter), compared as a whole and incremented at each acquisition; the first acquisition in a new epoch starts a new epoch pair, so no token issued before a restore can equal one issued after it. The SENDING transition and the `commit_issued` marker (§11.2 step 4) are compare-and-set on `lease_token = :t`; the sender re-checks the token before the first byte and again immediately before the marker; a sender that cannot renew within `lease_ttl / 2` (monotonic clock) aborts the upload and never commits (this is the one statement of that rule). A takeover moves the endpoint's SENDING deliveries to IN_DOUBT in the transaction that increments the token (§11.5 A.2). A restore flag is checked at start-up. "Lease frozen" (§9.3) means the lease has no live holder (takeover wait or restore freeze): no delivery of the endpoint may enter SENDING | narrows the stale-sender window; cannot stop a process stalled inside a transport call |

**Spec 10 (Identity): version-ordering and out-of-order rules (steps 6 and 7 of the numbered list)**

> 6. **Version ordering:** the version space is per (`event_identity`, `source_id`). A lower `source_version` arriving after a higher one is ignored and journalled as STALE; the same `source_version` with the same content hash is a journalled no-op (insert `ON CONFLICT DO NOTHING`, then compare hashes); with a different content hash it is UNDETERMINED with a data-quality item (never a silent merge). Such an item is cleared only by a new `source_version` or ordering value from the source (a re-extract that carries it); there is no in-platform override in release 1 (§9.5). A higher version creates a new `report_version`. A mapping MUST declare `source_version` where the source has one. A source that carries no version MUST declare `ordering: SOURCE_EVENT_TIME | BATCH_SEQUENCE` in its source mapping (§7.4); CI rejects a mapping that declares neither. `SOURCE_EVENT_TIME` may be declared only for a source whose event time changes on every correction, and `BATCH_SEQUENCE` is a monotonic value supplied by the source (a sequence in the file name or control record, or a column of the extract), never assigned by the platform; both are confirmed at onboarding and covered by a golden fixture. A redelivered or re-dropped older file therefore carries its old value, hits the existing `ingest_key` and is a no-op, or is STALE if a higher value was already ingested. A record whose content equals an earlier state but arrives with a later ordering value (A, B, A) is a new version; no-op suppression compares only with the latest non-rejected version. Each event class has one source of record, declared in the source mapping; the same `event_identity` arriving from another source is journalled as a `SOURCE_CONFLICT` item in the data-quality queue and is never ingested as a version or compared for STALE. The shared connector conformance suite includes an A, B, A fixture and a stale-redelivery fixture (F1 with A, F2 with B, F1 re-dropped: no change) for the file connector and the JDBC as-of extract.
> 7. **Out-of-order lifecycle events:** an amend or cancel arriving before its NEW is parked (`PARKED_ORPHAN`) with a deadline, released in `source_version` order when the NEW arrives, and on expiry becomes UNDETERMINED with a data-quality item, never a silent drop.

**Spec 11.2 (Seal-and-send procedure), steps 1 to 5**

> ### 11.2 Seal-and-send procedure (I1)
> 
> 1. The assembler seals the file: bytes, SHA-256, name, header id, sequence.
> 2. PUT the sealed bytes and a `DeliveryIntent` journal object to the evidence tier. The journal key is (endpoint, `restore_epoch`, `type`, `subject_id`, `ordinal`) as defined below, with the `DeliveryIntent` subject being the `attempt_id`, allocated from a ledger sequence before the PUT, so an unmatched intent object is an orphan handled by §11.5 B.5, never a silent gap.
> 3. One synchronous PostgreSQL transaction: delivery to SENDING (carrying `WHERE lease_token = :t`), attempt intent row, manifest entry.
> 4. Sending. Before the first byte the sender re-checks the lease fencing token and reads the current restore-epoch marker from the evidence tier, and refuses to send if the marker differs from its own epoch (an unreadable marker fails closed). Immediately before the first destination-visible byte of the transport (SFTP temp-name upload: the rename; single-request HTTPS, MQ put, file or object drop and SFTP `upload: direct`: the first byte) the sender commits the `commit_issued` marker synchronously as a compare-and-set: `UPDATE attempt SET commit_issued = true WHERE attempt_id = :a AND lease_token = :t`, joined to `delivery.state = SENDING`; zero rows means abort and send nothing. The rename or first byte follows only a successful CAS. A crash after the marker is IN_DOUBT unless §11.5 A.3(b) applies. Lease rules: §10.
> 5. After the transport result: a `TransportResult` journal entry and the PostgreSQL update.

**Spec 13 (Security), supply-chain bullet**

> - **Supply chain:** SBOM per release, dependency scanning, mirrored repository, licence allow-list (BSL, ELv2 and similar licences need explicit legal approval).

**Spec 14.1 (TDD units)**

> ### 14.1 TDD units
> 
> Pure-logic modules (decision tables, validators, identifier checks, key derivation, state machine, projections, recovery decisions) are unit-tested against in-memory ports. Integration tests run the whole pipeline against the deterministic destination simulator.
> 
> The invariant tests available at each plan (§14.4, §16) and, from P4, the full invariant suite (I1-I10), together with the port-contract suites for `LedgerStore` and `EvidenceStore`, run unchanged against the in-memory adapters and against real PostgreSQL 18 and both evidence profiles (P-SEALED and an S3-compatible store with object lock). Constraint-dependent behaviour (a duplicate in-flight insert is rejected, a stale lease token updates 0 rows, overlapping validity ranges are rejected, row-level security, if built, cannot be bypassed) is proven only by the real-store run. Integration tests and AT-1 use the real stores. A traceability row (§14.4) for I1, I2, I9 or I10 that cites only an in-memory test does not count.
> 
> Executor budget tests count cost units (§6.6), never wall-clock time: the same input hits the same budget on every run, and a seeded over-budget rule gives `EXEC_BUDGET` and opens its circuit deterministically. The console has a component-test layer and a small end-to-end suite, run against the in-memory ports, for role-based access, PII masking, read-audit rows and step-up authentication flows.

**Spec 14.2 (Oracle independence)**

> ### 14.2 Oracle independence
> 
> TDD proves consistency with golden cases, not with the law. When agents implement the product, the same agent could author both a rule and the expected outcome that "proves" it. Therefore:
> 
> - **O1 Provenance.** Every golden case records its source (a regulator workbook rule row, an ESMA Q&A id, a Guidelines section or example, an ARM/NCA sandbox response, or an anonymised real trade with the real rejection) and a named human reviewer. A case without provenance is labelled `consistency-only`.
> - **O2 Separation.** Implementation agents may write rule content but never both write a rule and approve its oracle. Golden cases are authored from the source evidence by a different author than the rule's, and the reviewer of a scope rule is a human.
> - **O3** Consistency-only cases (regenerated expected outputs) cannot satisfy a traceability row.
> - **O4 Differential oracles** where available: the validation workbook rule texts, an ARM sandbox, and DRR's EMIR-family report pipelines (and its MiFIR functions once ISDA publishes them, §6.7), only after Legal clears the licence (G4).
> - **O5 Real data.** Whether anonymised real trades and past ARM/NCA rejections exist is an open item (§17); without them oracle strength is limited and the risk register says so.

**Spec 14.3 (Destination simulator)**

> ### 14.3 Destination simulator
> 
> A deterministic simulator built from public ESMA artefacts (technical reporting instructions v1.6 **V**, validation workbook v1.7 **V**, XSDs) emits auth.031 scenarios: ACPT, PDNG then ACPT, PDNG turning RJCT on day 8, RJCT with CON codes, RCVD, file RJCT/CRPT/INCF, duplicate TRN, silence (both cases of §9.3), the §9.4 feedback anomalies, a skewed clock, and fault injection. A real ARM/NCA is a profile swap after onboarding. The simulator encodes *our assumptions*; real onboarding documents may differ.
> 
> Enrichment has its own deterministic fakes behind the `EnrichmentSource` port: a provider-API simulator and a database-lookup fake with scripted latency, rate-limit responses, timeouts, conflicting answers and stale values, driven by the same fault harness.

**Spec 14.4 (Traceability)**

> ### 14.4 Traceability
> 
> A file in the repository maps each requirement R1-R14 and each invariant I1-I10 to named evidence: a test, a benchmark or spike report, a gate record, or a review checklist with a named owner (for example R2 and R14). **CI fails if a row has none**, or if the referenced artefact is missing or has no recorded pass status. A consistency-only case (O3) never counts. Rules that keep the file honest:
> 
> - Each row names the plan whose exit closes it: I1, I2, I9 and the ledger half of I6: P3; the resend and in-doubt half of I6: P4 and P8; the storage half of I10: P3 and the restore half: P4; I7: P4; I3: P5 (replay of decisions and validation) and P8 (re-projection of sealed bytes); I8: P8; I4: P9b (signed activation) and P12 (no regime logic added to engine code); I5: P2a and P6a. Until a plan exits, the invariant is shown as open.
> - For I1, I2, I9 and I10 the evidence includes a real-store run (§14.1).
> - The I9 row names a test with two concurrent senders and a lease takeover during an in-progress upload, a stall-after-token-check that resumes after a takeover (the marker compare-and-set must fail, §11.2 step 4) and a restore-then-zombie case (a stale token against a restored lease row, §10).
> - The I6 row covers both cases: sealed-file reuse only when the content was never received, and a new attempt artifact for a whole-file reject or for proven non-ingestion after possible receipt.
> - The I3 row compares decisions, derived fields, validation results and `logical_hash`, and re-projects sealed bytes from the stored volatile inputs (I3).
> - A test that checks the §9.3 diagram edges (including the WITHDRAWN and duplicate-NEWT edges) and the §11.5 B.7 resolution table describe the same relation; the diagram source is the one edge list.
> - Fault tests cover more than crash-stop: pause-and-resume, partition, zombie node, duplicate delivery and clock steps (§11.5 Drills).

**Spec 14.5 (Acceptance tests): CI tiers paragraph**

> **CI tiers.** *Every build:* unit tests, the invariant and port-contract suites on the in-memory adapters and on real PostgreSQL with both evidence profiles, and the P0 smoke run on all three targets. *Nightly:* the S7 fault matrix and a reduced AT-1 (about 10k records, all five crash points) on Linux, alternating P-SEALED and P-WORM. *Release candidate and again before go-live:* full AT-1 and AT-4 on both profiles and AT-12 on all three targets; D4 "supported" (§5.2) is satisfied at this tier. A traceability row (§14.4) names the tier of its evidence. Capacity is proven by spikes SP-L and SP-X at a 50M-per-day-equivalent rate, not by the 1M-record test, and is re-confirmed on the release candidate before go-live.

**Spec 15 (Release 1): increment S0**

> 
> | Increment | Content | Plans |
> |---|---|---|
> | S0 | Walking skeleton on in-memory ports: ledger, store, clock, transport; destination simulator skeleton; CI on Linux and Windows Server; packaging skeleton for the three targets; three scope rules run to a decision record and `explain` | P0, P2a |

**Spec 16 (Build plan decomposition): plan P0 row and exit criterion**

> 
> | Plan | Scope | Exit criterion | Depends on |
> |---|---|---|---|
> | P0 | Foundation and walking skeleton: repository, build, CI with SBOM and licence gate and a matrix on Linux and Windows Server runners (build, unit and in-memory integration suites); ports with in-memory adapters, including the connector, transport and `EnrichmentSource` `lookup` contracts (§5.2) and the secrets and KMS ports; canonical types; golden-test harness with provenance fields; deterministic fault simulator; destination simulator skeleton; the `LedgerStore` and `EvidenceStore` port-contract suites (run against the in-memory adapters here and against real stores in P3); packaging skeleton for the three targets (systemd unit, Windows service wrapper, Helm chart with probes) | Three scope rules run in memory to a decision record and `explain`; a toy crash test is deterministic; the contract suites pass on the in-memory adapters; the smoke test passes on Linux, Windows Server and Kubernetes | none |

## Review focus: failure modes the spec implies but no existing test covers

The spec's own tests (section 14) exercise behaviour. The list below is what a careful reviewer should attack first: each item is a way P0 could look green and still be wrong, with the test in this plan that owns it. A reviewer who finds a failure mode that is not on this list should add a row and a test.

| # | Failure mode | Owning test (task) |
|---|---|---|
| RF1 | The `EvidenceStore` or `LedgerStore` contract suite cannot fail (passes against a broken adapter), so P3 inherits false confidence | `ContractSuiteTeethTest#everyFlawIsCaughtByItsNamedContractTests` (T12) |
| RF2 | In-memory adapter is not linearizable, so fencing and in-flight bugs hide until the real store | `concurrentAcquireGrantsExactlyOneHolder`, `concurrentBeginSendingForSameBusinessKeyAppliesExactlyOnce` (T11) |
| RF3 | Sender stalls after its token check, resumes after a takeover, and its marker compare-and-set still succeeds (I9, spec 14.4) | `takeoverMovesSendingDeliveriesToInDoubtAndFencesTheOldHolder` (T11) |
| RF4 | A token issued before a restore equals one issued after it, or a pre-restore token still passes the fence after the restore (zombie after restore, I9) | `restoreEpochRaisesTokenPairAndNoOldTokenEqualsNewOne`, `restoreThenZombieStaleTokenIsFencedEvenBeforeExpiry` (T11) |
| RF5 | Locale or time-zone dependence is invisible on CI (en-US, UTC) and breaks on the owner machine (nl-NL, Europe/Amsterdam) | `HostileEnvironmentTest#testsRunUnderTurkishLocaleAndKiritimatiZone` (T2) plus every test in the build |
| RF6 | Rule-reachable code reads the wall clock, randomness or the default locale (I3), including through method references | `ForbiddenCallScanTest#*` (T3), `NoWallClockRandomLocaleTest` in model and engine (T6, T21), `NoWallClockTest` in ports (all three rule sets, because the rule-reachable `CostMeter`, `Value` and `Expression` types live there) and in inmemory (wall clock only) (T8) |
| RF7 | An executor budget test that is secretly time-based, so it flakes or hides a real cost bug (spec 14.1) | `MeteredExecutorTest#sameInputHitsSameBudgetOnEveryRun`, `ExecutorBudgetTestsAreWallClockFreeTest#budgetTestsUseNoWallClock` (T18) |
| RF8 | Fault schedule depends on hash order, platform or locale, so the deterministic crash test differs between Linux and Windows | `FaultPlanTest#seededPlanIsStableAcrossRuns` (T19), `ToyCrashIT#sameFaultPlanGivesIdenticalTraceAndDigest` (T22), CI job `determinism-compare` (T32) |
| RF9 | A crash at one port boundary leaves a duplicate or a missing decision | `ToyCrashIT#everyCrashPointConvergesToTheUncrashedBaseline` (T22) |
| RF10 | A redelivered batch re-ingests or re-decides (I2, spec 7.2) | `redeliveredBatchIsNoopAndReturnsOriginalResult` (T9), `ToyCrashIT#redeliveryAfterAckLossIsNoop` (T22) |
| RF11 | The source is acknowledged before the landing PUT or the ledger commit (I1) | `ToyCrashIT#acknowledgementFollowsIngestCommit` (T22) |
| RF12 | Golden cases written by the same agent as the rule, or with an unregistered reviewer, satisfy a traceability row (O1 to O3) | `GoldenAuditTest#*` (T20), `TraceabilityCheckTest#consistencyOnlyGoldenNeverCounts` (T25) |
| RF13 | The traceability check is vacuous: it accepts a missing row, a skipped test, or in-memory-only evidence for I1, I2, I9, I10 (spec 14.1, 14.4) | `TraceabilityCheckTest#missingRowFails`, `#skippedTestDoesNotCount`, `#inMemoryOnlyEvidenceDoesNotCloseI1`, `#rowWithoutEvidenceFailsWhenItsPlanHasExited` (T25) |
| RF14 | Supply-chain drift: an unpinned action, a floating image tag, or a latest-labelled runner | `PinCheckTest#*` (T26), `scripts/check-gates.sh` in CI (T32) |
| RF15 | Windows or Git line-ending and BOM damage to scripts, goldens or the Maven wrapper | `EolCheckTest#*` (T26), `.gitattributes` (T1) |
| RF16 | The health endpoint listens on all interfaces by default, or stays ready while stopping | `HealthServerTest#bindsLoopbackByDefault`, `#readyReturns503WhileStopping` (T24) |
| RF17 | The signer role is accepted together with other roles (spec 5.2: the one deliberate exception to one process) | `RoleSetTest#signerMustBeAlone` (T23) |
| RF18 | A third-party library enters a runtime classpath (the walking skeleton is JDK-only) | enforcer `bannedDependencies` rule evaluated in the validate phase of every build (T2 step 7 proves once that it fails on a compile dependency) |
| RF19 | The smoke test passes while the pipeline is wrong (it only checks for exit code 0) | `SmokeCommandTest#smokeFailsWhenAnOutcomeDiffers` (T23) |
| RF20 | Clean stop is claimed without evidence (hang, exit 143 mistaken for failure, no shutdown marker) | `ServiceLifecycleTest#stopCompletesAndPrintsStoppedCleanly` (T24); the marker is asserted by every target smoke (T29, T30, T31) |
| RF21 | The Helm chart permits two replicas or a rolling update with two live senders (I9) | `packaging/helm/verify-chart.sh` (T31) |
| RF22 | `EvidenceStore.put` silently overwrites an existing key, or retention can be shortened | `putSameKeyDifferentBytesIsIntegrityAlarmAndKeepsOriginal`, `retentionCanOnlyBeExtended` (T8) |
| RF23 | An evidence store outage is treated as success or as a partial write (I1 fail-closed) | `outageFailsClosedAndStoresNothing` (T8) |
| RF24 | The transport makes a byte visible before the sender can commit the `commit_issued` marker (spec 11.2 step 4) | `gateIsCalledExactlyOnceBeforeAnythingIsVisible`, `refusedGateLeavesNothingVisible` (T14) |
| RF25 | Version-ordering rule 6 applied wrongly: A, B, A treated as a no-op, or a stale version stored | `abaProducesThreeVersions`, `lowerVersionAfterHigherIsStale` (T9) |

## Scope check: out of scope for P0

P0 stays P0. Each item below is deliberately not built here; the owning plan is named. Minimal stand-ins that the P0 exit needs are in scope but live in a package named `skeleton` or are named `InMemory*`, `Scripted*` or `Destination*Simulator`, and are documented as replaced or extended later.

| Out of scope | Owning plan |
|---|---|
| Pack format and parser, YAML decision tables, bought expression layer, reference interpreter, pack CI checks, Legal Source Registry, reason-code registry, interpretation pins, Firm Profile register, overlay registry, CSV round trip of tables, `pack test`, `pack explain`, `engine_min`, the `engine_build` replay-compatibility rule. (P0 has three placeholder scope rules in code and a stand-in `Expression`.) | P2a (P2b only if gate G-S3b passes) |
| PostgreSQL `LedgerStore`, schema design, TRN assignment, sequencer, the delivery state machine and its edge-list test (P0 stores states and enforces only the ledger-level invariants), timekeeper, queues, completeness job, journal-object format, the real `EvidenceStore` adapters (P-SEALED, P-WORM), `verify`, key hierarchy, envelope encryption, crypto-shredding, retention rows, the `signer` role, signed manifests | P3 |
| Restart and restore algorithms, the five crash points and the rest of the fault matrix (pause, partition, zombie node, clock steps), offline verify, evidence bundle, reconciliation runs, incident record, `LATE_T1`, the full I1-I10 suite | P4 |
| Reference-data service content, calendars, clock-offset gate, REST and JDBC lookup clients, validation layers L1-L6, section 8.3 classification, CON import | P5 |
| Real connectors (file CSV, XML, JSON; JDBC), the ingestion subsystem proper, ingest fault taxonomy, scheduler, mapping packs, connector conformance beyond the baseline in T13 | P7a |
| Projections, XSD handling, assembler, sealing, SFTP transport, feedback parsers, simulator scenarios other than the two `Scenario` implementations in T14 (including the scenario content behind the skewed-clock mechanism), the `UNKNOWN_OUTCOME` and `UNKNOWABLE` scenarios, the synchronous response payload, transport conformance beyond the baseline in T14 | P8 |
| OIDC, RBAC, API skeleton, CLI beyond `serve` and `smoke` | P9a |
| Pack-signing function, approvals, activation, key ceremony, alerts and runbooks, completion of packaging (image flavours, supported KMS and secrets options, upgrade, backup and security-release playbooks), archiving of production engine builds, the choice of a dependency vulnerability scanner (P0 configures Dependabot version updates and asks the owner to enable Dependabot alerts; the scanner choice and image or runtime SBOMs are P9b) | P9b |
| Console | P10a, P10b |
| Analyst editor; EMIR; everything in spec section 18 | P11, P12, P13 |
| Per-rule poison counter, circuit open and close, the `EXEC_CIRCUIT_OPEN` reason and its data-quality item, and the N-in-M defaults (spec 7.3 EXEC row, 6.5 step 8, 14.1 second paragraph: the circuit half of the seeded over-budget test) | P2a (interpreter), defaults from SP-R in P1; the data-quality item rides on the P3 and P7a queues. The owner confirms this when P1 is written |
| Out-of-order lifecycle parking (`PARKED_ORPHAN`, spec 10 rule 7) | P3 |
| The spec 7.4 entity set beyond one flattened execution (Party, Instrument, Venue, Order, Transmission, Allocation or package, natural-person encryption of party data) and the source-mapping layer | mapping: P7a; MiFIR entity fields that scope and derive rules read: P6a and P6b; EMIR extension: P12 after SP-G (P1) |
| Performance or capacity claims, executor throughput, micro-batch N and T, lease TTL tuning | P1 (spikes SP-R, SP-L, SP-X, SP-M) |
| Gates: G1 (no golden case in P0 counts as human-reviewed), G3 (no XSD, workbook text or ESMA-derived content enters the repository), G4, G5, G8 (no production evidence is written) | G1: P6a; G3: P5 and P8; the others: their own plans |

## File structure

Every file P0 creates or modifies, with its single responsibility and the task that owns it. Paths are relative to the repository root. The module layout follows the owner's suggestion with two justified changes. (1) A seventh module, `regpack-tools`, holds the build and CI gates (traceability, pins, line endings, bytecode determinism scan) as JDK-only code with its own tests, so gate logic is not mixed into product modules and `regpack-model`, `regpack-ports`, `regpack-inmemory` and `regpack-engine` can use the scanner in their own tests. (2) The destination simulator and the enrichment fakes live in `regpack-testkit` (spec 5.5 lists the simulator as test-only) rather than in `regpack-inmemory`; this keeps `testkit` free of any dependency on `inmemory` and so avoids a module cycle, because the in-memory adapters' own tests depend on `testkit`.

### Repository root and tooling

| Path | Responsibility | Task |
|---|---|---|
| `pom.xml` | Parent and aggregator: module list, dependency management (JUnit BOM, AssertJ, internal modules), pinned plugins, enforcer rules, compiler, surefire and failsafe settings, spotless, supply-chain plugin configuration, `offsync` profile | T2, T27 |
| `mvnw`, `mvnw.cmd`, `.mvn/wrapper/maven-wrapper.properties` | Maven Wrapper (`only-script`), pinned to Maven 3.9.16 with `distributionSha256Sum` | T1 |
| `.mvn/jvm.config` | JVM flags for Maven (`--add-exports` lines that google-java-format needs on JDK 16 and newer) | T2 |
| `.gitattributes`, `.gitignore` | LF endings everywhere except `*.cmd`; ignore build output and local files | T1 |
| `README.md` (modify) | Build and toolchain pointers | T1 |
| `toolchain/README.md` | Owner procedure: Temurin 25 download with checksum verification, Maven bootstrap for the wrapper, Docker fallback, OneDrive note | T1 |
| `toolchain/docker-mvn.sh`, `toolchain/docker-mvn.ps1` | Fallback build wrapper: runs Maven 3.9.16 on Temurin 25 inside a digest-pinned `maven:3.9.16-eclipse-temurin-25` container (also able to generate the wrapper) | T1 |
| `toolchain/docker-java.sh` | Fallback JVM for the gate commands (path B): runs `java` from the same digest-pinned image, stdin forwarded; selected by `REGPACK_JAVA` | T1 |
| `scripts/t.sh`, `scripts/t.ps1`, `scripts/it.sh`, `scripts/it.ps1` | Run selected unit tests or integration tests together with upstream modules | T2 |
| `scripts/check-gates.sh` | Runs the traceability, pin and line-ending gates after `verify` | T32 |
| `governance/licence-policy.md` | Licence allow-list rationale, the owner's approval line and the interim vulnerability handling until P9b | T27 |
| `governance/THIRD-PARTY.missing.properties` | Licence overrides for dependencies whose POM lacks a licence (initially empty) | T27 |
| `governance/golden-reviewers.txt` | Registry of named human golden-case reviewers (empty until gate G1) | T20 |
| `docs/traceability.md` | Spec 14.4 file: R1-R14 and I1-I10 mapped to named evidence | T25 |
| `docs/reviews/R14-process-checklist.md` | Review checklist with a named owner, evidence for R14 | T25 |
| `docs/exit/P0.md` | P0 exit record: CI run links, digests, effort data for the spec 15 calibration | T33 |
| `.github/workflows/ci.yml` | Build and test matrix, gates, SBOM and licence gate, three smoke jobs, determinism comparison | T32 |
| `.github/dependabot.yml` | Weekly updates for actions, Maven and the Dockerfile | T32 |

### regpack-tools (`dev.regpack.tools`, no dependencies)

| Path | Responsibility | Task |
|---|---|---|
| `Gates.java` | `main` dispatcher for `traceability`, `pins` and `eol` | T3, T25, T26 |
| `ForbiddenCallScan.java` | Bytecode scan (JDK `java.lang.classfile`) for wall-clock, randomness and default-locale references | T3 |
| `TraceabilityCheck.java` | Parses `docs/traceability.md`, reads surefire and failsafe XML and the golden audit file, applies the spec 14.4 rules | T25 |
| `PinCheck.java` | Dockerfile digest pins; workflow SHA pins, version comments, permissions, runner labels | T26 |
| `EolCheck.java` | Line-ending, BOM and ASCII rules per file type | T26 |
| `src/test/java/.../HostileEnvironmentTest.java` and one test class per gate | Gate behaviour on seeded fixtures | T2, T3, T25, T26 |

### regpack-model (`dev.regpack.model`, no dependencies)

| Path | Responsibility | Task |
|---|---|---|
| `Sha256`, `Outcome`, `Leg`, `CitationRole`, `Regime`, `EventIdentity`, `ReasonCode`, `Citation`, `IngestKey`, `BatchId`, `Checkpoint` | Validated value types shared by ports | T4 |
| `json/Json.java`, `json/JsonException.java` | Strict JSON parser and canonical writer (sorted keys, plain stripped decimals, microsecond UTC instants, no null) | T5 |
| `canonical/CanonicalEvent`, `CanonicalExecution`, `LifecycleKind`, `AssetClass`, `PartyKind`, `CanonicalExecutionCodec` | Canonical execution type and its map codec | T6 |
| `decision/DecisionId`, `PathStep`, `DecisionContext`, `DecisionBody`, `DecisionRecord`, `InputDigest`, `DecisionCodec` | Decision record of spec 6.3, context id, replay digest, input digest | T6 |
| tests | `Sha256Test`, `ValueTypesTest`, `JsonTest`, `CanonicalExecutionCodecTest`, `DecisionTypesTest`, `NoWallClockRandomLocaleTest` | T4-T6 |

### regpack-ports (`dev.regpack.ports`, depends on model)

| Path | Responsibility | Task |
|---|---|---|
| `Clock.java` | Injectable wall and monotonic time | T7 |
| `evidence/EvidenceKey`, `EvidenceStore`, `EvidenceUnavailableException`, `EvidenceIntegrityException`, `EvidenceRetentionException` | Evidence store port | T8 |
| `ledger/LedgerStore`, `IngestLedger`, `OutboxLedger`, `DecisionLedger`, `LeaseLedger`, `DeliveryLedger`, `DuplicateKeyException`, `DecisionHeadConflictException` | Ledger unit-of-work port, split into sub-ports so later plans add sub-ports without changing existing ones | T9-T11 |
| `connector/Connector` (sealed), `ConnectorSpec`, `ConnectorKind`, `CheckResult`, `SourceConnector`, `Transport`, `EnrichmentSource`, `LookupUnavailableException` | The one connector SPI: source, destination (`Transport`) and lookup kinds | T13-T15 |
| `security/SecretRef`, `SecretValue`, `SecretsProvider`, `SecretNotFoundException`, `KeyPurpose`, `KeyRef`, `KmsSignature`, `WrappedKey`, `Kms`, `KmsAccessDeniedException` | Secrets and KMS ports | T16 |
| `refdata/ReferenceData` | Sealed as-of reference snapshots | T17 |
| `exec/Executor`, `RuleProgram`, `CostBudget`, `CostMeter`, `Trace`, `ExecutionResult`, `BudgetExceededException`, `RuleEvaluationException` | Cost-unit executor port | T18 |
| `expr/Expression`, `CompiledExpression`, `Value`, `ValueType`, `Bindings`, `ExpressionException` | Expression port | T18 |
| `regime/RegimeProvider` | Regime evaluation port | T18 |
| tests | `EvidenceKeyTest`, `SecretRefTest`, `SecretValueTest`, `NoWallClockTest` | T8, T16 |

### regpack-testkit (`dev.regpack.testkit`, depends on model and ports; JUnit and AssertJ are `provided`)

| Path | Responsibility | Task |
|---|---|---|
| `clock/ManualClock.java` | Settable `Clock`: `advance`, `stepWall` (may go backwards), monotonic never backwards | T7 |
| `contract/EvidenceStoreContract`, `IngestLedgerContract`, `DecisionLedgerContract`, `LeaseDeliveryLedgerContract`, `SourceConnectorContract`, `TransportContract`, `LookupContract`, `KmsContract` | Port-contract suites as JUnit interfaces with default `@Test` methods | T8-T16 |
| `contract/LedgerFixtures` | Shared builders for the three ledger contracts | T9-T11 |
| `fault/FaultPlan`, `FaultInjector`, `SimulatedCrash` | Deterministic fault simulator: seeded plans, proxy-based crash and failure injection at port method boundaries | T19 |
| `golden/GoldenCase`, `GoldenCaseLoader`, `ReviewerRegistry`, `GoldenAudit` | Golden-test harness: provenance, named reviewer, consistency-only label, separation check, audit file | T20 |
| `sim/DestinationSimulator`, `Scenario`, `ScenarioContext`, `ScenarioCatalogue`, `Scenarios` | Destination simulator skeleton and the spec 14.3 scenario catalogue | T14 |
| `enrichment/ScriptedEnrichmentSource`, `ProviderApiSimulator`, `DatabaseLookupFake` | Deterministic enrichment fakes behind `EnrichmentSource` | T15 |
| tests | `ManualClockTest`, `FaultPlanTest`, `FaultInjectorTest`, `GoldenAuditTest`, `ScenarioCatalogueTest`, `DestinationSimulatorTransportContractTest`, `ScriptedEnrichmentSourceLookupContractTest`, `EnrichmentFakesTest` | T7, T14, T15, T19, T20 |

### regpack-inmemory (`dev.regpack.inmemory`, depends on model and ports)

| Path | Responsibility | Task |
|---|---|---|
| `InMemoryEvidenceStore` | Evidence adapter with an outage switch and a test-only `Flaw` | T8, T12 |
| `InMemoryLedgerStore` | Linearizable ledger adapter (one lock) with a test-only `Flaw` | T9-T12 |
| `InMemorySourceConnector` | Source connector over a fixed list of payloads | T13 |
| `InMemoryKms`, `InMemorySecrets` | KMS (Ed25519, HmacSHA256, AES-GCM) and secrets adapters | T16 |
| `InMemoryReferenceData` | Sealed snapshots with non-overlapping validity | T17 |
| tests | One concrete `*ContractTest` per suite, `ContractSuiteTeethTest`, `mutants/*` (tag `mutant`, excluded from the normal run), `NoWallClockTest` | T8-T17 |

### regpack-engine (`dev.regpack.engine`, depends on model and ports)

| Path | Responsibility | Task |
|---|---|---|
| `exec/MeteredExecutor` | `Executor` that charges cost units and maps errors and budget exhaustion | T18 |
| `explain/Tier1Explainer` | Renders `explain` tier 1 from a stored decision | T21 |
| `pipeline/MicroBatchPipeline` | Regime-agnostic ingest and decide orchestration in the spec 7.2 order | T22 |
| `skeleton/WalkingSkeletonRules`, `SkeletonExpression`, `ScopeProgram`, `WalkingSkeletonRegime` | **Walking-skeleton stand-ins replaced by P2a**: three placeholder scope rules and the catch-all row | T21 |
| tests and resources | `MeteredExecutorTest`, `ExecutorBudgetTestsAreWallClockFreeTest`, `ScopeEngineTest`, `SkeletonExpressionTest`, `Tier1ExplainerTest`, `RepositoryGoldenAuditTest`, `NoWallClockRandomLocaleTest`, `MicroBatchPipelineTest`, `ToyCrashIT` (with `ToyBatches`), `src/test/resources/golden/*.json` | T18, T21, T22 |

### regpack-app (`dev.regpack.app`, depends on model, ports, inmemory, engine)

| Path | Responsibility | Task |
|---|---|---|
| `Main`, `Role`, `RoleSet`, `AppConfig` | Commands `serve`, `smoke`, `help`; role flag parsing (signer alone); flags and environment | T23 |
| `SmokeCommand`, `src/main/resources/smoke/toy-executions.ndjson` | Toy pipeline run to decision records and explain; optional health probe | T23 |
| `SystemClock` | The only wall-clock `Clock` (instants truncated to microseconds) | T23 |
| `HealthServer`, `Service`, `WindowsServiceEntry` | JDK `HttpServer` probes, start and stop lifecycle with the shutdown marker, procrun entry points | T24 |
| `src/assembly/dist.xml`, `src/dist/bin/regpack`, `src/dist/bin/regpack.cmd` | Distribution layout and launchers | T28 |
| tests | `RoleSetTest`, `AppConfigTest`, `SystemClockTest`, `SmokeCommandTest`, `MainTest`, `HealthServerTest`, `ServiceLifecycleTest`, `DistributionLayoutIT`, `PackagingFilesTest` | T23, T24, T28-T31 |

### packaging

| Path | Responsibility | Task |
|---|---|---|
| `packaging/systemd/regpack.service`, `packaging/systemd/smoke.sh`, `packaging/systemd/README.md` | Hardened unit with `DynamicUser`; install, verify, start, probe, smoke, stop, assert; install notes | T28, T29 |
| `packaging/windows/install-procrun.ps1`, `install-service.ps1`, `smoke.ps1`, `packaging/windows/README.md` | SHA-512-verified procrun download, service registration, smoke; notes | T28, T30 |
| `packaging/container/Dockerfile` | Digest-pinned `eclipse-temurin:25-jre-noble`, non-root, exec form | T31 |
| `packaging/helm/regpack/*`, `packaging/helm/verify-chart.sh`, `packaging/helm/smoke.sh` | Chart with probes, one replica (schema-enforced), `Recreate` strategy; chart assertions; kind smoke | T31 |

## Port contracts for owner review

Spec 5.2: "Method signatures are P0 deliverables reviewed by the owner." This section is the complete set of signatures P0 delivers and is normative for the tasks. Each port block is followed by a short statement of its semantics and of how later plans extend it additively. Please review these before Task 7 starts; the decisions most worth your attention are listed in "Owner decisions" at the end of the plan.

**Rules that hold for every port.** No `null` anywhere (absence is `Optional.empty()` or an empty collection). Every port is thread-safe. Ports that need time take a `Clock` at construction and never read the system clock. Ports throw unchecked exceptions; a named exception below is part of the contract. **Additive extension:** a later plan may add new interfaces and new sub-ports, new enum constants and new permitted subtypes of a sealed type, and may add trailing record components only together with a constructor that keeps the old signature. A later plan never changes or removes an existing method, constant or component. Additive means that existing implementers and callers keep compiling, with two deliberate exceptions: a new enum constant or permitted subtype breaks every exhaustive switch over that type at compile time, and the plan that adds it updates those switches in the same commit; and adding an abstract method to `LedgerStore` or one of its sub-ports is allowed only together with both in-repository adapters (in-memory and PostgreSQL) and the contract suite. Do not add a catch-all `default` branch to a switch over a port enum or sealed type unless the owning task states what it must do (fail closed): an unknown `SendStatus`, for example, is never treated as success.

**Conformance baseline (spec 5.2: checkpoint resume, backpressure, idempotent redelivery and fault injection).** Each behaviour is owned by one named contract test, so P3, P5, P7a and P8 inherit it by binding the suite.

| Suite | Checkpoint resume | Backpressure | Idempotent redelivery | Fault injection |
|---|---|---|---|---|
| Source (T13) | `resumeFromCheckpointSkipsEarlierBatches` | `nextNeverReturnsMoreThanCredits` | `unacknowledgedBatchesAreRedeliveredOnNewRead` | `failureMidStreamResumesFromCheckpointWithoutLossOrDuplicate` |
| Transport (T14) | `pollAcksHonoursCursorAndAckDelay` | `pollAcksNeverReturnsMoreThanMaxAndRepollYieldsNothingSeen` | `duplicateFileIdIsRejectedNoClobber` | `failureBeforeFirstByteDoesNotInvokeGate` |
| EvidenceStore (T8) | `listReturnsKeysInCodeUnitOrderWithPrefixAfterAndLimit` (resume through the `list` cursor) | not applicable: stateless store | `putSameKeySameBytesIsIdempotent` | `outageFailsClosedAndStoresNothing` |

The lookup suite (T15, spec 7.1 and 7.5) owns: distinct-key batching `duplicateKeysInRequestAreRejected` and `batchLargerThanLimitIsRejected`; timeout `timeoutThrowsLookupUnavailableTimeout`; rate limit `rateLimitCarriesRetryAfter`; provenance `hitCarriesCanonicalBytesAndMatchingResponseHash`; the circuit breaker is client-side behaviour owned by P5.

### Shared model types (package `dev.regpack.model`)

```java
public record Sha256(String hex) {                        // 64 lowercase hex characters
  public static Sha256 of(byte[] data);
  public static Sha256 ofUtf8(String text);
}
public enum Outcome { REPORT, DO_NOT_REPORT, REPORT_BY_OTHER, UNDETERMINED }
public enum Leg { MARKET, CLIENT, ALLOCATION }
public enum CitationRole { BASIS, EXCEPTION, INTERPRETATION, GUIDANCE }
public record Regime(String id) {}                        // lower-case dotted, e.g. "mifir.eu.rts22"
public record EventIdentity(String value) {               // parts joined with '|'
  public static EventIdentity of(String... parts);
}
public record ReasonCode(String value) {}                 // "MIFIR.N03.EXCLUDED", "EXEC_BUDGET"
public record Citation(String sourceId, String provisionPath, CitationRole role,
                       Optional<Sha256> quoteSha256) {}
public record IngestKey(String sourceId, String sourceRecordId, long sourceVersion) {}
public record BatchId(String value) { public static BatchId of(Sha256 landingDigest, int slice); }
public record Checkpoint(String stream, byte[] state) {}  // opaque; content equality

// dev.regpack.model.canonical
public sealed interface CanonicalEvent permits CanonicalExecution {
  EventIdentity eventIdentity();
  Instant occurredAt();
}
public enum LifecycleKind { NEW, AMEND, CANCEL, CORRECTION, NOVATION, ASSIGNMENT }
public enum AssetClass { EQUITY, DEBT, DERIVATIVE, OTHER }
public enum PartyKind { LEGAL_ENTITY, NATURAL_PERSON }
public record CanonicalExecution(String firmLei, String venueMic, String venueExecutionId,
    LifecycleKind eventKind, Instant tradedAt, AssetClass assetClass, String isin,
    boolean tradedOnVenue, PartyKind clientKind, Optional<String> clientLei,
    BigDecimal quantity, BigDecimal price, String currency) implements CanonicalEvent {}

// dev.regpack.model.decision  (spec 6.3)
public record DecisionId(long value) {}
public record PathStep(String ruleId, boolean matched, long costUnits) {}
public record DecisionContext(Sha256 packDigest, Optional<Sha256> overlayDigest,
    List<String> interpretationPins, List<String> referenceSnapshotIds,
    List<String> enrichmentSnapshotIds, String firmProfileVersion, String mappingVersion,
    String engineBuild, String executor) {
  public Sha256 id();                                     // SHA-256 of the canonical JSON
}
public record DecisionBody(EventIdentity eventIdentity, Regime regime, Sha256 decisionContextId,
    Instant asAt, Instant evaluatedAt, Outcome outcome, List<String> flags, ReasonCode reasonCode,
    String node, List<String> ruleIdsHit, List<PathStep> path, List<Citation> citations,
    List<Leg> legs, Sha256 inputDigest) {
  public Sha256 replayDigest();                           // canonical JSON without evaluatedAt (I3)
}
public record DecisionRecord(DecisionId decisionId, long decisionSeq,
    Optional<DecisionId> supersedesDecisionId, DecisionBody body) {}
public final class InputDigest {                          // spec 6.3 input_digest
  public static Sha256 of(CanonicalExecution execution, IngestKey ingestKey);
}
```

`InputDigest.of(e, k)` is the digest with an empty enrichment-hash list. Spec 6.3 includes the stored enrichment response hashes in the input; P5 adds the overload `of(CanonicalExecution, IngestKey, List<Sha256> enrichmentResponseHashes)` whose result for an empty list equals the two-argument form, and P2a generalises the first parameter to `CanonicalEvent`. No digest produced in P0 is persisted outside tests.

### `Clock` (package `dev.regpack.ports`)

```java
public interface Clock {
  Instant now();            // UTC wall time, always whole microseconds (getNano() % 1000 == 0), the canonical and PostgreSQL precision; may step forwards or backwards (clock drills)
  long monotonicNanos();    // durations only; never decreases; origin unspecified
}
```

Every `Clock` implementation returns instants truncated to microseconds; a `Clock` that returns finer instants breaks `Json.canonical` (T5) and the PostgreSQL round trip. Rules never receive a `Clock` (I3); orchestration and adapters do, and stamp `evaluatedAt`, lease expiry and retention from it. The real adapter, `SystemClock`, exists only in `regpack-app`. Extension: P4 adds clock-step scenarios through the `ManualClock` test double, not through this interface.

### `EvidenceStore` (package `dev.regpack.ports.evidence`)

```java
public record EvidenceKey(String path) {                  // segments [A-Za-z0-9._=,+-]+ joined by '/'
  public static EvidenceKey of(String... segments);       // rejects empty, "." and ".." segments, > 512 chars
  public String namespace();                              // first segment
}
public interface EvidenceStore {
  record RetentionClass(String name) {}
  record Retention(RetentionClass retentionClass, Instant retainUntil) {}
  record Grade(String profile, String mode) {}            // ("P-WORM","compliance"), ("P-SEALED","")
  record Durability(boolean synchronousAcknowledgement, int independentFailureDomains) {}  // Durability(false, 0) for test doubles
  record PutRequest(EvidenceKey key, byte[] bytes, String mediaType, Retention retention) {}
  enum PutOutcome { CREATED, ALREADY_PRESENT_IDENTICAL }
  record StoredObject(EvidenceKey key, byte[] bytes, String mediaType, Sha256 sha256,
                      Instant writtenAt, Retention retention, boolean legalHold) {}

  Grade grade();
  Durability durability();
  PutOutcome put(PutRequest request);
  Optional<StoredObject> get(EvidenceKey key);
  List<EvidenceKey> list(String prefix, Optional<EvidenceKey> after, int limit);
  void extendRetention(EvidenceKey key, Instant newRetainUntil);
  void setLegalHold(EvidenceKey key, boolean hold);
}
// EvidenceUnavailableException, EvidenceIntegrityException, EvidenceRetentionException extend RuntimeException
```

`put` returns only after the bytes are durable at the grade's `Durability` (I1); `durability()` is a claim that the P3 send gate compares with the required durability (spec 5.4), so an adapter may claim only what it provides; a put of an existing key with identical bytes returns `ALREADY_PRESENT_IDENTICAL` and keeps the first put's `mediaType`, `writtenAt` and `retention`; with different bytes it throws `EvidenceIntegrityException` and the stored object is unchanged (spec 11.2); an outage makes every method throw `EvidenceUnavailableException` and stores nothing (fail closed). `list` returns keys that start with `prefix` and sort after `after`, in UTF-16 code-unit order, at most `limit`. Retention is fixed and extend-only (`extendRetention` with an earlier instant throws `EvidenceRetentionException`); legal hold is a separate flag. There is deliberately no delete: expiry and crypto-shredding are P3 retention work. Extension: P3 adds journal-key helpers and a `Manifest` sub-port; P-SEALED and P-WORM adapters bind the same contract suite.

### `LedgerStore` (package `dev.regpack.ports.ledger`)

`LedgerStore` is a unit-of-work API: every "same transaction" rule of the spec is one call. It is the aggregate of five sub-ports so that later plans add sub-ports without touching these.

```java
public interface LedgerStore extends IngestLedger, OutboxLedger, DecisionLedger,
                                     LeaseLedger, DeliveryLedger {}

public interface IngestLedger {
  enum Disposition { NEW_VERSION, NOOP_SAME_VERSION_SAME_HASH, STALE,
                     CONFLICT_SAME_VERSION_DIFFERENT_HASH, SOURCE_CONFLICT }
  record Entry(IngestKey key, EventIdentity eventIdentity, Sha256 contentHash) {}
  record Commit(BatchId batchId, String sourceId, List<Entry> entries,
                Optional<Checkpoint> checkpoint) {}
  record EntryResult(IngestKey key, Disposition disposition) {}
  record Result(BatchId batchId, boolean redelivery, List<EntryResult> entries,
                Optional<OutboxLedger.OutboxId> outboxId) {}
  record StoredIngest(IngestKey key, EventIdentity eventIdentity, Sha256 contentHash,
                      BatchId batchId) {}

  Result commitIngest(Commit commit);                     // spec 7.2 step 2, one transaction
  Optional<Result> ingestResult(BatchId batchId);
  Optional<StoredIngest> findIngest(IngestKey key);
  Optional<Checkpoint> latestCheckpoint(String sourceId, String stream);
}

public interface OutboxLedger {
  record OutboxId(long value) {}
  enum OutboxKind { DECIDE_BATCH }
  enum OutboxState { PENDING, DONE }
  record OutboxItem(OutboxId id, OutboxKind kind, String subjectId, OutboxState state) {}
  List<OutboxItem> pendingOutbox(OutboxKind kind, int max);   // oldest first
  Optional<OutboxItem> outboxItem(OutboxId id);
}

public interface DecisionLedger {
  record NewDecision(DecisionBody body, long expectedHeadSeq) {}
  record BatchCommit(List<NewDecision> decisions, Optional<OutboxLedger.OutboxId> completesOutbox) {}
  enum CommitStatus { INSERTED, NOOP_REDELIVERY }
  record Committed(CommitStatus status, DecisionRecord record) {}
  List<Committed> commitDecisions(BatchCommit commit);    // throws DecisionHeadConflictException
  Optional<DecisionRecord> currentDecision(Regime regime, EventIdentity eventIdentity);
  List<DecisionRecord> decisionChain(Regime regime, EventIdentity eventIdentity);   // ascending seq
}

public interface LeaseLedger {
  record EndpointKey(String environment, String submissionRoute,
                     String submittingEntityLei, String destinationAuthority) {}
  record LeaseToken(long restoreEpoch, long counter) implements Comparable<LeaseToken> {}
  record LeaseGrant(LeaseToken token, String holder, Instant expiresAt) {}
  sealed interface LeaseResult {
    record Granted(LeaseGrant grant) implements LeaseResult {}
    record Held(String holder, Instant until) implements LeaseResult {}
  }
  LeaseResult acquire(EndpointKey endpoint, String holder, Duration ttl);
  boolean renew(EndpointKey endpoint, LeaseToken token, Duration ttl);
  void release(EndpointKey endpoint, LeaseToken token);
  Optional<LeaseGrant> currentLease(EndpointKey endpoint);
  long restoreEpoch();
  void declareRestoreEpoch(long epoch);                   // must exceed the current epoch; voids all leases
}

public interface DeliveryLedger {
  enum DeliveryState { PREPARED, BLOCKED, QUEUED, SENDING, AWAITING_ACK, ACCEPTED_PROVISIONAL,
      ACCEPTED_CONFIRMED, PENDING_AT_ENDPOINT, REJECTED, FILE_REJECTED, IN_DOUBT,
      RESEND_PENDING, IN_REPAIR, SUPERSEDED, WITHDRAWN, WRITTEN_OFF }   // the 16 states of spec 9.3
  record DeliveryId(long value) {}
  record AttemptId(long value) {}
  record NewDelivery(LeaseLedger.EndpointKey endpoint, long endpointEpoch, String businessKey,
                     long versionSeq) {}
  record Delivery(DeliveryId id, NewDelivery key, DeliveryState state) {}
  record AttemptIntent(Sha256 artifactSha256, String fileName) {}
  record Attempt(AttemptId id, DeliveryId delivery, LeaseLedger.LeaseToken token,
                 Sha256 artifactSha256, String fileName, boolean commitIssued) {}
  enum TransitionStatus { APPLIED, STATE_MISMATCH, IN_FLIGHT_CONFLICT }
  enum SendingStatus { APPLIED, STALE_LEASE, STATE_MISMATCH, IN_FLIGHT_CONFLICT }
  record SendingResult(SendingStatus status, Optional<AttemptId> attempt) {}
      // compact constructor: attempt is present if and only if status == APPLIED, else IllegalArgumentException

  DeliveryId createDelivery(NewDelivery delivery);        // PREPARED; throws DuplicateKeyException
  Optional<Delivery> delivery(DeliveryId id);
  TransitionStatus transition(DeliveryId id, DeliveryState expectedFrom, DeliveryState to);
  AttemptId allocateAttemptId();   // spec 11.2 step 2: ledger sequence, strictly increasing, never reused, consumed only by an APPLIED beginSending
  SendingResult beginSending(DeliveryId id, AttemptId attemptId, LeaseLedger.LeaseToken token,
                             AttemptIntent intent);
  boolean markCommitIssued(AttemptId attempt, LeaseLedger.LeaseToken token);
  Optional<Attempt> attempt(AttemptId id);
}
// LedgerException extends RuntimeException; DuplicateKeyException and
// DecisionHeadConflictException(EventIdentity eventIdentity, long currentHeadSeq) extend it.
```

**Ingest semantics (spec 7.2, 10 rule 6).** `commitIngest` is atomic and idempotent by `batchId`: a second commit of the same batch id writes nothing and returns the stored `Result` with `redelivery = true`. Per entry: the same `IngestKey` with the same hash is `NOOP_SAME_VERSION_SAME_HASH`; with a different hash it is `CONFLICT_SAME_VERSION_DIFFERENT_HASH` and the original row is kept (never a silent merge); a version lower than the highest stored for the same (`eventIdentity`, `sourceId`) is `STALE` and is not stored; an event identity already ingested from a different `sourceId` is `SOURCE_CONFLICT` and is not stored; anything else is `NEW_VERSION`, including a content that equals an earlier state at a higher version (A, B, A gives three versions). Duplicate keys inside one `Commit` throw `IllegalArgumentException` before anything is written. A new batch creates one `DECIDE_BATCH` outbox item whose `subjectId` is the batch id and stores the checkpoint, all in the same unit.

**Decision semantics (spec 6.3).** `commitDecisions` is atomic for the whole list. A decision whose (`eventIdentity`, `decisionContextId`, `inputDigest`) already exists returns `NOOP_REDELIVERY` with the stored record. Otherwise the decision is inserted with `decisionSeq = expectedHeadSeq + 1` and `supersedesDecisionId` = the current head, provided the head's seq equals `expectedHeadSeq`; if it does not, nothing in the list is written and `DecisionHeadConflictException` carries the current head seq (the loser re-reads and re-evaluates). If `completesOutbox` is present the outbox item becomes `DONE` in the same unit. Chains are per (`regime`, `eventIdentity`). The regime is deliberately not part of the idempotency key (spec 6.3); two regimes cannot collide because the decision context id contains the pack digest, which differs per regime.

**Lease and delivery semantics (spec 10, 11.2, 11.5 A.2).** `acquire` succeeds when there is no lease, the lease was released, or `now >= expiresAt` on the ledger's clock; the token is (`restoreEpoch`, counter) with the counter incremented per acquisition, and a new restore epoch restarts the counter at 1, so no token issued before `declareRestoreEpoch` equals one issued after. `declareRestoreEpoch(e)` requires `e` greater than `restoreEpoch()` (else `IllegalArgumentException`), voids every current lease (`currentLease` is empty for every endpoint: the restore freeze of spec 10, no live holder, so no token issued before the declaration is current, `renew` returns false, `release` is ignored, `beginSending` gives `STALE_LEASE` and `markCommitIssued` returns false) and restarts every endpoint counter, so the next acquisition, which is allowed immediately without waiting for expiry, gets the token (`e`, 1). Every acquisition moves that endpoint's `SENDING` deliveries to `IN_DOUBT` in the same unit (the takeover rule). `beginSending` is one unit: it requires the delivery to be `QUEUED` and the token to be the current lease token (else `STALE_LEASE` / `STATE_MISMATCH`), applies the in-flight guard, moves the delivery to `SENDING` and inserts the attempt intent row under the given attempt id. The caller allocates the attempt id first (`allocateAttemptId`), PUTs the `DeliveryIntent` journal object keyed by it (spec 11.2 steps 2 and 3), then calls `beginSending` with that id. An id that was not allocated throws `IllegalArgumentException`; an id already stored on an attempt throws `DuplicateKeyException`; both are checked before the fence. An id passed to a `beginSending` that does not return `APPLIED` (`STALE_LEASE`, `STATE_MISMATCH`, `IN_FLIGHT_CONFLICT`) stays unused and creates no attempt row, which is the orphan-intent case of spec 11.5 B.5. `AttemptIntent` gets the evidence keys of the sealed bytes and of the intent journal object, and the manifest entry, as trailing components in P3. `markCommitIssued` is the fenced compare-and-set of spec 11.2 step 4: true only if the attempt's delivery is `SENDING` and the token is the current lease token. **In-flight guard:** at most one delivery per (`endpoint`, `businessKey`) may be in `SENDING`, `AWAITING_ACK` or `IN_DOUBT`; a change that would add a second returns `IN_FLIGHT_CONFLICT`. `transition` is a compare-and-set on the state, rejects `SENDING` as a target (use `beginSending`; `IllegalArgumentException`), and does not check edge legality: edge legality is the P3 state machine, tested against the spec 9.3 edge list.

**Result shapes.** Losing outcomes of a compare-and-set on one delivery or lease (`STALE_LEASE`, `STATE_MISMATCH`, `IN_FLIGHT_CONFLICT`, `Held`) are values, because callers handle them as normal flow. A loss that must undo a whole multi-row unit and tell the caller the current head (`DecisionHeadConflictException`) is an exception.

**Atomicity across sub-ports.** A same-transaction rule that spans sub-ports (spec 7.8: decision, `report_version`, delivery and outbox row; spec 11.2 step 3: delivery to `SENDING`, attempt intent row and manifest entry) is added by a later plan in one of two ways, both additive: (a) a trailing record component on the existing commit record (`BatchCommit`, `AttemptIntent`) typed by a new interface of that plan, with the old constructor kept; or (b) a new unit-of-work method declared on `LedgerStore` itself. `LedgerStore` and its sub-ports have exactly two implementers, both in this repository (the in-memory adapter and the PostgreSQL adapter), so adding an abstract method to them is allowed when the same commit updates both adapters and the contract suite. Separate calls on separate sub-ports are never one transaction.

Extension: P3 adds `ReportVersionLedger`, `QueueLedger`, `TimerLedger`, `JournalLedger`, `CompletenessLedger` and `gate_released` handling as new sub-ports and enum constants (`OutboxKind.SEQUENCE`, `OutboxKind.ASSEMBLE`); P4 adds `RecoveryLedger`; P7a adds ingest-run and schedule sub-ports.

### The one connector SPI (package `dev.regpack.ports.connector`)

Spec 5.2: "there is one SPI, defined in P0." `Connector` is sealed; its three permitted kinds are the source kind, the destination kind (`Transport`) and the lookup kind (`EnrichmentSource`).

```java
public sealed interface Connector permits SourceConnector, Transport, EnrichmentSource {
  ConnectorSpec spec();
  CheckResult check();
}
public enum ConnectorKind { SOURCE, DESTINATION, LOOKUP }
public record ConnectorSpec(String name, String version, ConnectorKind kind,
                            Map<String, String> properties) {}
public record CheckResult(boolean ok, String detail) {}

public non-sealed interface SourceConnector extends Connector {
  record StreamInfo(String name, List<String> stableKeyFields) {}
  record Discovery(List<StreamInfo> streams) {}
  record ReadLimits(int maxRecordsPerBatch, long maxBytesPerBatch) {}
  record ReadRequest(Optional<Checkpoint> resumeFrom, ReadLimits limits) {}
  record Manifest(int recordCount, Sha256 sha256, Map<String, String> controlTotals) {}
  record BatchPayload(byte[] bytes, String mediaType, Manifest manifest) {}   // spec 5.2
  record Batch(String stream, long seq, BatchPayload payload, String sourceCoordinates,
               Checkpoint checkpoint) {}
  interface BatchStream extends AutoCloseable {
    List<Batch> next(int credits);               // credit-based backpressure
    void acknowledge(Checkpoint checkpoint);     // explicit acknowledgement
    boolean exhausted();
    @Override void close();
  }
  Discovery discover();
  BatchStream read(ReadRequest request);
}

public non-sealed interface Transport extends Connector {
  enum AckModel { SYNC_RESPONSE, ASYNC_FILE, ASYNC_MESSAGE, PORTAL_ONLY }
  record OutboundFile(String fileId, String fileName, byte[] bytes, Sha256 sha256,
                      String mediaType) {}
  @FunctionalInterface interface CommitGate { boolean permitFirstVisibleByte(); }
  enum SendStatus { DELIVERED, REJECTED_DUPLICATE, ABORTED_BY_GATE, FAILED_BEFORE_FIRST_BYTE,
                    UNKNOWN_OUTCOME }
  record SendResult(SendStatus status, String detail) {}
  enum ProbeStatus { PRESENT, ABSENT, UNKNOWABLE }
  record ProbeResult(ProbeStatus status, Optional<Sha256> sha256) {}
  record AckCursor(long position) { public static AckCursor start(); }
  record RawAck(long position, String fileId, byte[] bytes, String mediaType,
                Instant endpointAsOf) {}
  record AckPage(List<RawAck> acks, AckCursor next) {}

  AckModel ackModel();
  SendResult send(OutboundFile file, CommitGate gate);
  ProbeResult probe(String fileId);
  AckPage pollAcks(AckCursor cursor, int max);
}

public non-sealed interface EnrichmentSource extends Connector {      // the `lookup` kind
  record Limits(int maxKeysPerRequest, Duration timeout, int maxRequestsPerSecond) {}
  record Context(String field, Instant asOf, BatchId batchId) {}
  record Request(List<String> keys, Context context) {}
  sealed interface KeyOutcome {
    String key();
    Instant asOf();
    byte[] canonical();
    Sha256 responseHash();
    record Hit(String key, Instant asOf, byte[] canonical, Sha256 responseHash)
        implements KeyOutcome {}
    record Miss(String key, Instant asOf, byte[] canonical, Sha256 responseHash)
        implements KeyOutcome {}
    record Failure(String key, Instant asOf, String errorCode, boolean retryable,
                   byte[] canonical, Sha256 responseHash) implements KeyOutcome {}
  }
  record Result(List<KeyOutcome> outcomes) {}
  Limits limits();
  Result lookup(Request request);
}
// LookupUnavailableException(Kind kind /* TIMEOUT, RATE_LIMITED, UNAVAILABLE */, String errorCode,
//                            Optional<Duration> retryAfter) extends RuntimeException
```

**Source semantics.** `read` starts after the greater of the source's own acknowledged position and `resumeFrom`; batches carry strictly increasing `seq`; `next(credits)` never returns more than `credits` batches (zero credits returns an empty list); `acknowledge(cp)` tells the source that every batch up to and including `cp` is durable in the ledger (I1) and may be moved, deleted or committed at the source; unacknowledged batches are redelivered by a later `read` (at-least-once), and the ledger's idempotency makes the redelivery a no-op. A manifest's `sha256` is the SHA-256 of the payload bytes.

**Transport semantics (spec 9.1, 11.2 step 4, 11.5 A.3).** `send` calls `gate.permitFirstVisibleByte()` exactly once, immediately before the first destination-visible effect (for SFTP temp-name upload the rename, for single-request HTTPS and file drop the first byte). A `false` answer means nothing becomes visible and the result is `ABORTED_BY_GATE`. A failure before that point returns `FAILED_BEFORE_FIRST_BYTE` and never calls the gate. The gate is called before any destination-side check that can fail after the first visible effect, so a destination that rejects a duplicate file id reports `REJECTED_DUPLICATE` after the gate was called once (the marker is then committed and the caller treats the delivery as possibly received, spec 9.3 duplicate-file edges). An exception thrown by the gate propagates unchanged and nothing becomes visible (fail closed). A second send of an existing `fileId` is a no-clobber `REJECTED_DUPLICATE` and leaves the first copy in place. `UNKNOWN_OUTCOME` and `UNKNOWABLE` are produced by the P8 transports and scenarios. `probe` reports `PRESENT` (with the sha256 the destination holds), `ABSENT`, or `UNKNOWABLE`; per spec 11.5 A.3 `ABSENT` proves non-delivery only where the endpoint profile says so. `pollAcks` returns acknowledgements after `cursor`, each with the endpoint's own as-of (used by the non-ingestion proof of spec 9.3); re-polling with the returned `next` cursor yields nothing already seen.

**Lookup semantics (spec 7.5).** One outcome per key, in request order; keys must be distinct and no more than `limits().maxKeysPerRequest()` (else `IllegalArgumentException`); partial results are allowed; every outcome (hit, miss, failure) carries an as-of and a canonical serialisation whose SHA-256 is its `responseHash`, so a sealed snapshot can map every request key to a response hash (spec 7.5 Contract and Replay). For a `Hit` the canonical bytes are the value (`Json.canonical`, UTF-8); for a `Miss` they are `Json.canonical({"key":k,"outcome":"MISS"})`; for a `Failure` `Json.canonical({"errorCode":c,"key":k,"outcome":"ERROR","retryable":r})`, all UTF-8. The same key and as-of give the same hash. A `Hit` or `Miss` as-of is the source's own as-of for the answer (a stale value keeps its older as-of); a `Failure` as-of is the lookup time on the source's clock. A whole-request timeout or rate limit throws `LookupUnavailableException`.

Extension: P7a adds file and JDBC `SourceConnector` implementations and the connector conformance suite; P8 adds SFTP and HTTPS transports and the transport conformance suite; P5 adds REST and JDBC lookup clients. New connector kinds extend the sealed `permits` list.

### Secrets and KMS (package `dev.regpack.ports.security`)

```java
public record SecretRef(String scheme, String path) {      // vault, openbao, awssm, azkv, gcpsm, file, env
  public static SecretRef parse(String uri);               // "env://NAME" -> ("env","NAME")
}
public final class SecretValue implements AutoCloseable {  // toString() is always "SecretValue[REDACTED]"
  public static SecretValue of(byte[] bytes);
  public byte[] copyBytes();
  public String utf8();
  @Override public void close();                           // zeroes the held bytes
}
public interface SecretsProvider {
  boolean supports(String scheme);
  SecretValue resolve(SecretRef ref);                      // throws SecretNotFoundException
}

public enum KeyPurpose { PACK_SIGNING, MANIFEST_SIGNING, TRN_HMAC, ENVELOPE_KEK }
public record KeyRef(String keyId, KeyPurpose purpose) {}
public record KmsSignature(String keyId, String algorithm, byte[] value) {}
public record WrappedKey(String keyId, byte[] ciphertext) {}
public interface Kms {
  KmsSignature sign(KeyRef key, byte[] message);           // PACK_SIGNING or MANIFEST_SIGNING only
  boolean verify(KeyRef key, byte[] message, KmsSignature signature);
  byte[] hmac(KeyRef key, byte[] message);                 // TRN_HMAC only
  WrappedKey wrap(KeyRef kek, byte[] dataKey, byte[] aad); // ENVELOPE_KEK only
  byte[] unwrap(KeyRef kek, WrappedKey wrapped, byte[] aad);
}
// KmsAccessDeniedException, SecretNotFoundException extend RuntimeException
```

Credentials are references that are resolved at run time and never written to the evidence log (spec 7.1); `SecretValue` cannot leak through `toString`. `unwrap` requires `wrapped.keyId` to equal `kek.keyId` (else `IllegalArgumentException`) and authorises only the `kek`. A key id that does not exist throws `IllegalArgumentException` (checked first); an existing key that the bound identity may not use, or that is used for another purpose, throws `KmsAccessDeniedException`; a wrong `aad` or a tampered `WrappedKey` makes `unwrap` throw `IllegalArgumentException`. A `Kms` instance is bound to one workload identity (the in-memory adapter's `as(WorkloadIdentity)`). This is the contract P3's `signer` role is tested against: the application identity cannot invoke the pack-signing or manifest-signing key (spec 5.2). Extension: P3 and P9b bind real KMS and secrets-manager adapters to the same contract suite.

### `ReferenceData` (package `dev.regpack.ports.refdata`)

```java
public interface ReferenceData {
  record DatasetId(String name) {}
  record SnapshotId(long sequence) {}                      // monotonic load sequence of a dataset
  enum Anchor { TRADE_DATE, SUBMISSION_DATE }
  record AsOf(LocalDate tradeDate, LocalDate submissionDate) {}
  record Entry(String key, Map<String, String> attributes, LocalDate validFrom,
               Optional<LocalDate> validTo) {}             // valid on [validFrom, validTo)
  record SnapshotInfo(DatasetId dataset, SnapshotId id, Instant loadedAt, Duration maxAge) {}
  SnapshotInfo currentSnapshot(DatasetId dataset);
  Optional<Entry> lookup(DatasetId dataset, SnapshotId snapshot, String key, AsOf asOf, Anchor anchor);
}
```

Snapshots are sealed: once loaded a snapshot never changes, so `revalidate --as-of D --snapshot S` (spec 8.4) can reproduce a decision. Every lookup names a snapshot and explicit as-of dates; the `Anchor` selects which of the two dates is compared with the validity interval. Loading is adapter-specific in P0 (`InMemoryReferenceData.load`, which rejects overlapping validity intervals for one key, the in-memory analogue of `WITHOUT OVERLAPS`). Extension: P5 adds a `ReferenceDataLoader` port, staleness policy and calendars.

### `Executor`, `Expression`, `RegimeProvider`

```java
// dev.regpack.ports.exec
public record CostBudget(long maxUnits) {}
public final class CostMeter {                              // deterministic cost units, never time
  public CostMeter(CostBudget budget);
  public void enter(String ruleId);                         // sets the rule that later charges belong to
  public void charge(long units);                           // throws BudgetExceededException if used + units > max
  public long used();
  public String currentRule();
}
public interface Trace { void step(String ruleId, boolean matched, long costUnits); List<PathStep> steps(); }
public interface RuleProgram<I, O> { String id(); O run(I input, CostMeter meter, Trace trace); }
public sealed interface ExecutionResult<O> {
  record Completed<O>(O value, long costUnits, List<PathStep> path) implements ExecutionResult<O> {}
  record Failed<O>(String ruleId, String errorClass, long costUnits, List<PathStep> path)
      implements ExecutionResult<O> {}
  record BudgetExhausted<O>(String ruleId, long costUnits, long limit, List<PathStep> path)
      implements ExecutionResult<O> {}
}
public interface Executor {
  String id();
  <I, O> ExecutionResult<O> execute(RuleProgram<I, O> program, I input, CostBudget budget);
}
// BudgetExceededException(String ruleId, long used, long limit), RuleEvaluationException(String ruleId, Throwable cause)
//   both extend RuntimeException

// dev.regpack.ports.expr
public enum ValueType { BOOL, INT, DECIMAL, STRING, TIMESTAMP, DATE, ENUM, LIST }
public sealed interface Value {
  record BoolValue(boolean value) implements Value {}
  record IntValue(long value) implements Value {}
  record DecimalValue(BigDecimal value) implements Value {}
  record StringValue(String value) implements Value {}
  record TimestampValue(Instant value) implements Value {}
  record DateValue(LocalDate value) implements Value {}
  record EnumValue(String type, String name) implements Value {}
  record ListValue(List<Value> items) implements Value {}
}
public interface Bindings { Optional<Value> get(String path); }   // empty = explicit absence
public interface CompiledExpression { String source(); Value evaluate(Bindings bindings, CostMeter meter); }
public interface Expression { CompiledExpression compile(String source, Map<String, ValueType> variables); }
// ExpressionException extends RuntimeException

// dev.regpack.ports.regime
public interface RegimeProvider {
  record Descriptor(Regime regime, String packVersion, String engineMin) {}
  record EvaluationContext(DecisionContext decisionContext, IngestKey ingestKey,
                           Instant evaluatedAt, CostBudget budget) {}
  record DerivedField(String name, String value, String ruleRef, List<Citation> citations) {}
  record Projection(String mediaType, byte[] bytes) {}
  record Result(DecisionBody decision, List<DerivedField> fields, Optional<Projection> projection) {}
  Descriptor describe();
  Result evaluate(CanonicalEvent event, EvaluationContext context);
}
```

**Executor semantics (spec 6.6, 14.1).** The executor owns the `CostMeter` and the `Trace`. `charge` throws when the running total would exceed the budget, so a budget of 10 units admits exactly 10 and rejects the 11th; the same input therefore hits the same budget on every run. A `BudgetExceededException` becomes `BudgetExhausted` (carrying the rule being evaluated and the units used before the failing charge); a `RuleEvaluationException` or any other `RuntimeException` becomes `Failed`. The caller maps these to `UNDETERMINED` decisions with reason codes `EXEC_BUDGET` and `EXEC_ERROR` and the (rule id, error class, cost units) triple (spec 7.3). A wall-clock watchdog may raise an alarm elsewhere but never changes a result. **Expression semantics (spec 6.2):** no loops, I/O, clock or user functions; absence is explicit (`Bindings.get` returns empty and an expression that reads an absent path without a `has` guard throws `ExpressionException`); evaluation charges the meter. **Regime semantics:** `evaluate` is a pure function of the event, the pinned context and the budget; it never reads a clock (`evaluatedAt` is supplied) and never performs I/O. Extension: P2a replaces the stand-in `Expression` and the skeleton program by the real interpreter behind the same ports; the `DerivedField` and `Projection` parts of `Result` are filled from P6b and P8.

## Conventions (read before Task 1)

1. **Branch.** Work on `p0/foundation`, created from `main` in Task 1. One commit per task (more only if a task says so). The owner merges after the final whole-branch review.
2. **Commands.** Bash means Git Bash on the owner machine and on the CI runners. PowerShell forms are given where they differ. After Task 1, `MVN` means `./mvnw` (Bash) or `.\mvnw.cmd` (PowerShell). In PowerShell, quote any argument that contains `#`, `,` or `=` and that Maven must see whole, for example `"-Dtest=Foo#bar"`. PowerShell 5.1 has no `&&`: run chained commands one per line. Each new shell must have `JAVA_HOME` set as in T1 Step 6C (path A).
3. **Helper scripts (created in Task 2).** `scripts/t.sh <module> <selector>` and `scripts/t.ps1 -Module <m> -Test <selector>` run unit tests with their upstream modules built (`-pl <module> -am`, `-Dsurefire.failIfNoSpecifiedTests=false`). `scripts/it.sh <module> <selector>` and `scripts/it.ps1` do the same for failsafe integration tests (`*IT`). A selector is a Surefire `-Dtest` value such as `Sha256Test` or `Sha256Test#knownVectors`. "Expected: green" means the output contains `Tests run: N, Failures: 0, Errors: 0, Skipped: 0` for the selected tests and ends with `BUILD SUCCESS`. Any step that runs a plain `verify` (not `scripts/it.*`, which skip the format check) first runs `MVN -B -ntp -q spotless:apply`, because `spotless:check` is bound to `verify` and the code was just hand-written; CI is the only place where the check runs on committed code without a prior apply.
4. **Test-first discipline.** Every behaviour task writes the test, runs it and sees the stated failure (usually a compilation error `cannot find symbol` for a new type, or an assertion failure), implements the minimum, runs it green, then commits. Do not write implementation before the failing run is observed.
5. **Commit step.** Every commit step is `MVN -B -ntp -q spotless:apply`, then `git add -A`, then the quoted `git commit`. Commit messages end with the trailer line `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>`, given as a second `-m`. Never skip hooks.
6. **Signature authority.** The section "Port contracts for owner review" is normative for every port signature. A task's Interfaces list names ports by that section and adds only adapter, helper and test-support signatures. If an implementation needs a different port signature, stop and ask the owner; do not edit a port silently.
7. **Style.** google-java-format (GOOGLE style, 2 spaces); `-Xlint:all -Werror` (fix warnings, do not suppress whole categories); records, sealed interfaces and enums; no `null` in any API (use `Optional` or absence); defensive copies with `List.copyOf` and `Map.copyOf`; ports throw unchecked exceptions only; no third-party library in any module's compile or runtime scope. Every exception class declares `private static final long serialVersionUID = 1L;`. Payload fields of a non-serializable type (records such as `EventIdentity`, `Optional`, `Duration`, lists) are either declared `transient` and exposed through an accessor, or stored as `String` or `long`. This satisfies `-Xlint:serial` without suppressing it.
8. **Determinism rules.** `regpack-model` and `regpack-engine` main code uses no wall clock, randomness or default-locale API (I3). The `regpack-ports` main code is held to the same three rule sets because it contains rule-reachable types (`CostMeter`, `Value`, `Expression`); in-memory adapters use no wall clock and take a `Clock`. Only `regpack-app` (`SystemClock`) and tests that assert nothing about time may read real time. `regpack-testkit` may use `SplittableRandom` with an explicit seed.
9. **Test names are evidence references.** The traceability file (Task 25) cites tests as `fully.qualified.ConcreteClass#methodName`. For contract suites the class is the concrete test in the adapter's module. Renaming a cited test without updating `docs/traceability.md` fails the gate.
10. **Stop conditions.** The toolchain install and any download (Task 1, the first run of Tasks 29 to 31, Task 33), creating or pushing to a GitHub remote, changing repository settings, and anything outside the repository directory. Stop and ask the owner; never work around.
11. **Path shorthand.** In a task's Files list, a directory such as `dev/regpack/ports/ledger/` is relative to the `src/main/java` of the module named for that group (`src/test/java` for test files, `src/main/resources` or `src/test/resources` where the path says so).

## Tasks

Each task is the smallest unit with its own test cycle; it ends with an independently testable deliverable and one commit. Task order is a dependency order: do not start a task before the previous one is committed and green.

### Task 1 (T1): Toolchain, Maven Wrapper and repository hygiene [OWNER ACTION, STOP CONDITION]

The machine has git 2.52, Docker 29.1.2, Node 22 and kubectl 1.34, and no JDK, Maven or Helm. This task installs a verified JDK (primary path A) or sets up the Docker-based build wrapper (fallback path B), then generates and pins the Maven Wrapper. Installing software and downloading files are owner actions: the agent prepares the commands and stops at each OWNER step until the owner confirms the checksum line.

**Files:**
- Create: `.gitattributes`, `.gitignore`, `pom.xml` (stub, expanded in T2), `toolchain/README.md`, `toolchain/docker-mvn.sh`, `toolchain/docker-mvn.ps1`, `toolchain/docker-java.sh`, `mvnw`, `mvnw.cmd`, `.mvn/wrapper/maven-wrapper.properties`
- Modify: `README.md`

**Interfaces:** none. Deliverable: `./mvnw -v` prints `Apache Maven 3.9.16` and a Java 25 runtime.

- [ ] **Step 1: Create the branch and observe the red state.**
  Run `git switch -c p0/foundation`, then `./mvnw -v`. Expected: failure (`./mvnw: No such file or directory`; PowerShell: `.\mvnw.cmd` is not recognised).
- [ ] **Step 2: Write `.gitattributes`.**
  ```
  * text=auto eol=lf
  *.cmd text eol=crlf
  *.bat text eol=crlf
  *.jar binary
  *.zip binary
  *.gz binary
  ```
  (`*.sh`, `*.ps1`, `mvnw` and all sources get LF from the first rule. Committed blobs of `*.cmd` are LF in the index and CRLF in every working tree.)
- [ ] **Step 3: Write `.gitignore`.**
  ```
  target/
  .idea/
  *.iml
  .vscode/
  .claude/
  *.log
  dependency-reduced-pom.xml
  .mvn/wrapper/maven-wrapper.jar
  .env
  .env.*
  *.pem
  *.p12
  *.pfx
  *.jks
  *.key
  settings-security.xml
  ```
- [ ] **Step 4: Write the stub `pom.xml`** (T2 replaces it; the wrapper plugin needs a project):
  ```xml
  <?xml version="1.0" encoding="UTF-8"?>
  <project xmlns="http://maven.apache.org/POM/4.0.0"
           xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
           xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <groupId>dev.regpack</groupId>
    <artifactId>regpack</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <packaging>pom</packaging>
  </project>
  ```
- [ ] **Step 5 (OWNER, path A, STOP): install Temurin 25.0.4.1+1.** Source: the Eclipse Adoptium release `jdk-25.0.4.1+1` on GitHub. Expected SHA-256 `00c847d804f4a78e9f04f2683faf14fed898535b177b7fc704486cb0284e9283` (the same value is published by the Adoptium API and in the release asset `OpenJDK25U-jdk_x64_windows_hotspot_25.0.4.1_1.zip.sha256.txt`; size 141,167,264 bytes). Install outside OneDrive. PowerShell:
  ```powershell
  New-Item -ItemType Directory -Force C:\dev\dl, C:\dev\jdk | Out-Null
  $url = 'https://github.com/adoptium/temurin25-binaries/releases/download/jdk-25.0.4.1%2B1/OpenJDK25U-jdk_x64_windows_hotspot_25.0.4.1_1.zip'
  Invoke-WebRequest $url -OutFile C:\dev\dl\temurin25.zip
  $h = (Get-FileHash C:\dev\dl\temurin25.zip -Algorithm SHA256).Hash.ToLower()
  if ($h -ne '00c847d804f4a78e9f04f2683faf14fed898535b177b7fc704486cb0284e9283') { Remove-Item C:\dev\dl\temurin25.zip; throw "SHA-256 mismatch: $h" }
  Expand-Archive C:\dev\dl\temurin25.zip C:\dev\jdk
  [Environment]::SetEnvironmentVariable('JAVA_HOME', 'C:\dev\jdk\jdk-25.0.4.1+1', 'User')
  $p = [Environment]::GetEnvironmentVariable('Path', 'User')
  [Environment]::SetEnvironmentVariable('Path', "C:\dev\jdk\jdk-25.0.4.1+1\bin;$p", 'User')
  ```
  Bash equivalent for the check: `echo "00c847d804f4a78e9f04f2683faf14fed898535b177b7fc704486cb0284e9283  /c/dev/dl/temurin25.zip" | sha256sum -c -` must print `OK`. Optionally also verify the detached `.sig` published next to the zip with `gpg --verify`, importing Adoptium's key from the page https://adoptium.net/installation/ and comparing the fingerprint printed there. Open a new shell and run `java -version`. Expected: the output contains `Temurin-25.0.4.1+1`. If the checksum differs, delete the file and stop.
- [ ] **Step 6 (OWNER, path A, STOP): bootstrap Maven once, to generate the wrapper.** Source: https://downloads.apache.org/maven/maven-3/3.9.16/binaries/apache-maven-3.9.16-bin.zip (if the release has been superseded use the same path under https://archive.apache.org/dist/maven/maven-3/3.9.16/binaries/). Expected SHA-512 (Apache dist and Maven Central agree) `ed41650d42485cfc243fad22158caf9cbb5dc408ce7a09ddb94dd42a019de929ca43065bfa450612cf12bf78b5cafa3884b96c090de326ff590448c933454af3`.
  ```powershell
  New-Item -ItemType Directory -Force C:\dev\tools | Out-Null
  Invoke-WebRequest https://downloads.apache.org/maven/maven-3/3.9.16/binaries/apache-maven-3.9.16-bin.zip -OutFile C:\dev\dl\maven.zip
  $h = (Get-FileHash C:\dev\dl\maven.zip -Algorithm SHA512).Hash.ToLower()
  if ($h -ne 'ed41650d42485cfc243fad22158caf9cbb5dc408ce7a09ddb94dd42a019de929ca43065bfa450612cf12bf78b5cafa3884b96c090de326ff590448c933454af3') { Remove-Item C:\dev\dl\maven.zip; throw "SHA-512 mismatch: $h" }
  Expand-Archive C:\dev\dl\maven.zip C:\dev\tools
  (Get-FileHash C:\dev\dl\maven.zip -Algorithm SHA256).Hash.ToLower()
  ```
  Record the printed SHA-256: it is the `distributionSha256Sum` for Step 7 (the wrapper downloads the identical zip from Maven Central). In the repository root run:
  ```
  C:\dev\tools\apache-maven-3.9.16\bin\mvn.cmd -B -N org.apache.maven.plugins:maven-wrapper-plugin:3.3.4:wrapper "-Dmaven=3.9.16" "-Dtype=only-script"
  ```
  Expected: `BUILD SUCCESS` and new files `mvnw`, `mvnw.cmd`, `.mvn/wrapper/maven-wrapper.properties`.
- [ ] **Step 6B (OWNER, path B instead of 5 and 6, STOP): Docker fallback.** Perform Step 9 first (path B only: it writes `toolchain/docker-mvn.sh`, `toolchain/docker-mvn.ps1` and `toolchain/docker-java.sh`), then run `toolchain/docker-mvn.sh -B -N org.apache.maven.plugins:maven-wrapper-plugin:3.3.4:wrapper -Dmaven=3.9.16 -Dtype=only-script`. The first run pulls `maven:3.9.16-eclipse-temurin-25@sha256:93b8a14ea2f412782e4e842651273b4d903e35cc496284f178fbbe2d67d00976` (owner approval needed for the pull). From then on set `REGPACK_MVN=toolchain/docker-mvn.sh` and `REGPACK_JAVA=toolchain/docker-java.sh` so every `scripts/*.sh` helper, including `check-gates.sh`, uses the containers. Read `./mvnw` below as `toolchain/docker-mvn.sh`, read every `java -cp ...` in T25, T26 and T32 as `"$REGPACK_JAVA" -cp ...`, and run the gate commands from Git Bash. `REGPACK_BUILD_ROOT` is not supported on path B (the container does not receive it): with path B the working copy must be outside OneDrive. Compute the SHA-256 for Step 7 from a verified file, never by piping a download into `sha256sum`: `curl -fsSL -o /tmp/maven.zip https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.16/apache-maven-3.9.16-bin.zip && echo "ed41650d42485cfc243fad22158caf9cbb5dc408ce7a09ddb94dd42a019de929ca43065bfa450612cf12bf78b5cafa3884b96c090de326ff590448c933454af3  /tmp/maven.zip" | sha512sum -c - && sha256sum /tmp/maven.zip`. Only the output of the SHA-256 command after a printed `OK` may be used; if the check fails, delete the file and stop. Compare the SHA-256 with the value path A prints if both have ever been run.
- [ ] **Step 6C (OWNER, path A, STOP): make the toolchain visible to the agent.** The environment variables written in Step 5 apply only to processes started afterwards. Restart the Claude Code session (or app) so the agent's shells inherit them, then confirm with `java -version` and `echo $JAVA_HOME`. If a restart is not possible, every agent Bash shell begins with `export JAVA_HOME=/c/dev/jdk/jdk-25.0.4.1+1; export PATH="$JAVA_HOME/bin:$PATH"` and every PowerShell shell with `$env:JAVA_HOME='C:\dev\jdk\jdk-25.0.4.1+1'; $env:Path="$env:JAVA_HOME\bin;$env:Path"`. Step 8 must not be attempted before this check passes. (This is neither a download nor a build.)
- [ ] **Step 7: Pin the wrapper.** Edit `.mvn/wrapper/maven-wrapper.properties` so it contains exactly these keys (the generator writes the first three):
  ```
  wrapperVersion=3.3.4
  distributionType=only-script
  distributionUrl=https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.16/apache-maven-3.9.16-bin.zip
  distributionSha256Sum=<the SHA-256 recorded in Step 6>
  ```
  Then `git update-index --add --chmod=+x mvnw` (Git for Windows does not keep the executable bit; Linux CI needs it).
- [ ] **Step 8: Verify green** (path A only after Step 6C has passed). Run `./mvnw -v` (PowerShell `.\mvnw.cmd -v`). Expected: the first run downloads Maven 3.9.16 and verifies the SHA-256; the output contains `Apache Maven 3.9.16` and `Java version: 25.0.4.1, vendor: Eclipse Adoptium`. Tamper check: change one hex digit of `distributionSha256Sum`, delete `%USERPROFILE%\.m2\wrapper\dists\apache-maven-3.9.16` (the wrapper names the cache folder without the `-bin` suffix; with the wrong name nothing is re-downloaded and no checksum is checked), run `./mvnw -v` again; expected failure mentioning the checksum; restore the correct value.
- [ ] **Step 9: Write the fallback scripts and the toolchain note.**
  `toolchain/docker-mvn.sh`:
  ```bash
  #!/usr/bin/env bash
  # Fallback toolchain: Maven 3.9.16 on Temurin 25 in a digest-pinned container.
  # Usage: toolchain/docker-mvn.sh <maven arguments>
  set -euo pipefail
  IMAGE='maven:3.9.16-eclipse-temurin-25@sha256:93b8a14ea2f412782e4e842651273b4d903e35cc496284f178fbbe2d67d00976'
  cd "$(dirname "$0")/.."
  HOST_DIR="$(pwd -W 2>/dev/null || pwd)"
  MSYS_NO_PATHCONV=1 exec docker run --rm -v "${HOST_DIR}:/work" -w /work -v regpack-m2:/root/.m2 "$IMAGE" mvn "$@"
  ```
  `toolchain/docker-mvn.ps1`:
  ```powershell
  param([Parameter(ValueFromRemainingArguments = $true)][string[]]$MavenArgs)
  $ErrorActionPreference = 'Stop'
  $image = 'maven:3.9.16-eclipse-temurin-25@sha256:93b8a14ea2f412782e4e842651273b4d903e35cc496284f178fbbe2d67d00976'
  $repo = Split-Path $PSScriptRoot -Parent
  & docker run --rm -v "${repo}:/work" -w /work -v regpack-m2:/root/.m2 $image mvn @MavenArgs
  exit $LASTEXITCODE
  ```
  `toolchain/docker-java.sh` (path B fallback JVM for the gates; `-i` forwards the piped `git ls-files`):
  ```bash
  #!/usr/bin/env bash
  # Fallback JVM for the gates: runs `java` from the same digest-pinned image. Usage: toolchain/docker-java.sh <java arguments>
  set -euo pipefail
  IMAGE='maven:3.9.16-eclipse-temurin-25@sha256:93b8a14ea2f412782e4e842651273b4d903e35cc496284f178fbbe2d67d00976'
  cd "$(dirname "$0")/.."
  HOST_DIR="$(pwd -W 2>/dev/null || pwd)"
  MSYS_NO_PATHCONV=1 exec docker run --rm -i -v "${HOST_DIR}:/work" -w /work "$IMAGE" java "$@"
  ```
  Mark both `.sh` fallback scripts executable (`git update-index --add --chmod=+x toolchain/docker-mvn.sh toolchain/docker-java.sh`). `toolchain/README.md` must contain: the exact URLs and checksums of Steps 5 and 6; the Docker fallback including its limits (path B has no `REGPACK_BUILD_ROOT` support and needs Git Bash with `REGPACK_JAVA` set for the gates); the two `JAVA_HOME` lines of Step 6C; and this note. **OneDrive warning.** The repository sits in a OneDrive-synced folder. Maven writes thousands of small files to `target/` and `clean` may fail on files that the sync client holds open. Recommended (owner decision): keep the working copy outside OneDrive, for example `C:\dev\regpack` cloned from this repository, and treat the OneDrive folder as a backup or push target. Alternative: keep the working copy here and set the environment variable `REGPACK_BUILD_ROOT` (for example `C:\dev\regpack-build`) before running Maven; the `offsync` profile added in T2 then moves every module's build output below that directory and `scripts/check-gates.sh` honours it. Warning: `REGPACK_BUILD_ROOT` does not protect `.git`, `.mvn/wrapper` or the sources. If the working copy stays in OneDrive, set the folder to Always keep on this device, pause OneDrive sync while running `git commit`, `rebase` or `checkout`, and expect `index.lock` errors if you do not. The safer layout is a working copy at `C:\dev\regpack` with the OneDrive folder used only for docs or as a backup remote. Do not rely on junctions or symlinks: Microsoft documents that OneDrive does not support syncing through them.
- [ ] **Step 10: Edit `README.md`** so it names the working title `regpack`, links the spec and this plan, and states the three commands `./mvnw -B -ntp verify`, `scripts/check-gates.sh` and `toolchain/README.md` for setup.
- [ ] **Step 11: Check line endings, then commit.**
  Run `git add -A` then `git ls-files --eol mvnw mvnw.cmd .gitattributes toolchain/docker-mvn.sh`. Expected: every file reports `i/lf`; `mvnw.cmd` reports `w/crlf` (or `w/lf` until re-checkout, which is acceptable); `mvnw` shows mode `100755` in `git ls-files -s mvnw`. Commit: `git commit -m "build: add toolchain procedure, Maven Wrapper 3.9.16 and repository hygiene" -m "Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"`.

### Task 2 (T2): Parent POM, module skeletons, build rules and hostile test environment

**Files:**
- Create: `.mvn/jvm.config`, `scripts/t.sh`, `scripts/t.ps1`, `scripts/it.sh`, `scripts/it.ps1`, `regpack-tools/pom.xml`, `regpack-model/pom.xml`, `regpack-ports/pom.xml`, `regpack-testkit/pom.xml`, `regpack-inmemory/pom.xml`, `regpack-engine/pom.xml`, `regpack-app/pom.xml`, `regpack-tools/src/test/java/dev/regpack/tools/HostileEnvironmentTest.java`
- Modify: `pom.xml` (replace the stub)

**Interfaces:** none. Deliverable: `./mvnw -B -ntp verify` is green on an essentially empty build with the enforcer, compiler, surefire, failsafe and spotless rules in force; every test JVM runs under `tr_TR` and `Pacific/Kiritimati` (review focus RF5).

- [ ] **Step 1: Write the failing test** `regpack-tools/src/test/java/dev/regpack/tools/HostileEnvironmentTest.java`:
  ```java
  class HostileEnvironmentTest {
    @Test
    void testsRunUnderTurkishLocaleAndKiritimatiZone() {
      assertThat(Locale.getDefault().getLanguage()).isEqualTo("tr");
      assertThat(Locale.getDefault().getCountry()).isEqualTo("TR");
      assertThat(TimeZone.getDefault().getID()).isEqualTo("Pacific/Kiritimati");
      assertThat("TITLE".toLowerCase()).isEqualTo("t\u0131tle"); // dotless i: default-locale casing is hostile
    }
  }
  ```
- [ ] **Step 2: Write the root `pom.xml`** (complete):
  ```xml
  <?xml version="1.0" encoding="UTF-8"?>
  <project xmlns="http://maven.apache.org/POM/4.0.0"
           xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
           xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <groupId>dev.regpack</groupId>
    <artifactId>regpack</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <packaging>pom</packaging>
    <name>regpack</name>
    <description>Self-hosted regulatory reporting engine (plan P0: foundation and walking skeleton)</description>

    <modules>
      <module>regpack-tools</module>
      <module>regpack-model</module>
      <module>regpack-ports</module>
      <module>regpack-testkit</module>
      <module>regpack-inmemory</module>
      <module>regpack-engine</module>
      <module>regpack-app</module>
    </modules>

    <properties>
      <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
      <project.reporting.outputEncoding>UTF-8</project.reporting.outputEncoding>
      <project.build.outputTimestamp>2026-10-09T00:00:00Z</project.build.outputTimestamp>
      <maven.compiler.release>25</maven.compiler.release>

      <junit.version>6.1.3</junit.version>
      <assertj.version>3.27.7</assertj.version>

      <maven-clean-plugin.version>3.5.0</maven-clean-plugin.version>
      <maven-resources-plugin.version>3.5.0</maven-resources-plugin.version>
      <maven-compiler-plugin.version>3.16.0</maven-compiler-plugin.version>
      <maven-surefire-plugin.version>3.6.0</maven-surefire-plugin.version>
      <maven-failsafe-plugin.version>3.6.0</maven-failsafe-plugin.version>
      <maven-jar-plugin.version>3.5.1</maven-jar-plugin.version>
      <maven-install-plugin.version>3.2.0</maven-install-plugin.version>
      <maven-deploy-plugin.version>3.2.0</maven-deploy-plugin.version>
      <maven-site-plugin.version>3.22.0</maven-site-plugin.version>
      <maven-assembly-plugin.version>3.8.0</maven-assembly-plugin.version>
      <maven-enforcer-plugin.version>3.6.3</maven-enforcer-plugin.version>
      <maven-wrapper-plugin.version>3.3.4</maven-wrapper-plugin.version>
      <cyclonedx-maven-plugin.version>2.9.3</cyclonedx-maven-plugin.version>
      <license-maven-plugin.version>2.7.1</license-maven-plugin.version>
      <spotless.version>3.10.4</spotless.version>
      <google-java-format.version>1.35.0</google-java-format.version>

      <!-- Hostile default environment for every test JVM (review focus RF5). Override with -Dtest.argLine= to see a test fail. -->
      <test.argLine>-Duser.language=tr -Duser.country=TR -Duser.timezone=Pacific/Kiritimati</test.argLine>
    </properties>

    <dependencyManagement>
      <dependencies>
        <dependency>
          <groupId>org.junit</groupId>
          <artifactId>junit-bom</artifactId>
          <version>${junit.version}</version>
          <type>pom</type>
          <scope>import</scope>
        </dependency>
        <dependency>
          <groupId>org.assertj</groupId>
          <artifactId>assertj-core</artifactId>
          <version>${assertj.version}</version>
        </dependency>
        <dependency><groupId>dev.regpack</groupId><artifactId>regpack-tools</artifactId><version>${project.version}</version></dependency>
        <dependency><groupId>dev.regpack</groupId><artifactId>regpack-model</artifactId><version>${project.version}</version></dependency>
        <dependency><groupId>dev.regpack</groupId><artifactId>regpack-ports</artifactId><version>${project.version}</version></dependency>
        <dependency><groupId>dev.regpack</groupId><artifactId>regpack-testkit</artifactId><version>${project.version}</version></dependency>
        <dependency><groupId>dev.regpack</groupId><artifactId>regpack-inmemory</artifactId><version>${project.version}</version></dependency>
        <dependency><groupId>dev.regpack</groupId><artifactId>regpack-engine</artifactId><version>${project.version}</version></dependency>
      </dependencies>
    </dependencyManagement>

    <!-- Inherited by every module. testkit overrides junit-jupiter and assertj-core to provided. -->
    <dependencies>
      <dependency><groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter</artifactId><scope>test</scope></dependency>
      <dependency><groupId>org.junit.platform</groupId><artifactId>junit-platform-launcher</artifactId><scope>test</scope></dependency>
      <dependency><groupId>org.assertj</groupId><artifactId>assertj-core</artifactId><scope>test</scope></dependency>
    </dependencies>

    <build>
      <pluginManagement>
        <plugins>
          <plugin><artifactId>maven-clean-plugin</artifactId><version>${maven-clean-plugin.version}</version></plugin>
          <plugin><artifactId>maven-resources-plugin</artifactId><version>${maven-resources-plugin.version}</version></plugin>
          <plugin>
            <artifactId>maven-compiler-plugin</artifactId>
            <version>${maven-compiler-plugin.version}</version>
            <configuration>
              <release>${maven.compiler.release}</release>
              <showWarnings>true</showWarnings>
              <compilerArgs>
                <arg>-Xlint:all</arg>
                <arg>-Werror</arg>
                <arg>-parameters</arg>
              </compilerArgs>
            </configuration>
          </plugin>
          <plugin>
            <artifactId>maven-surefire-plugin</artifactId>
            <version>${maven-surefire-plugin.version}</version>
            <configuration>
              <argLine>${test.argLine}</argLine>
              <runOrder>random</runOrder>
              <trimStackTrace>false</trimStackTrace>
              <systemPropertyVariables>
                <regpack.classes.dir>${project.build.outputDirectory}</regpack.classes.dir>
                <regpack.test.classes.dir>${project.build.testOutputDirectory}</regpack.test.classes.dir>
                <regpack.build.dir>${project.build.directory}</regpack.build.dir>
                <regpack.repo.root>${maven.multiModuleProjectDirectory}</regpack.repo.root>
              </systemPropertyVariables>
            </configuration>
          </plugin>
          <plugin>
            <artifactId>maven-failsafe-plugin</artifactId>
            <version>${maven-failsafe-plugin.version}</version>
            <configuration>
              <argLine>${test.argLine}</argLine>
              <runOrder>random</runOrder>
              <trimStackTrace>false</trimStackTrace>
              <systemPropertyVariables>
                <regpack.classes.dir>${project.build.outputDirectory}</regpack.classes.dir>
                <regpack.test.classes.dir>${project.build.testOutputDirectory}</regpack.test.classes.dir>
                <regpack.build.dir>${project.build.directory}</regpack.build.dir>
                <regpack.repo.root>${maven.multiModuleProjectDirectory}</regpack.repo.root>
              </systemPropertyVariables>
            </configuration>
            <executions>
              <execution>
                <goals>
                  <goal>integration-test</goal>
                  <goal>verify</goal>
                </goals>
              </execution>
            </executions>
          </plugin>
          <plugin><artifactId>maven-jar-plugin</artifactId><version>${maven-jar-plugin.version}</version></plugin>
          <!-- Pinned only because requirePluginVersions checks the clean, deploy and site lifecycles (banMavenDefaults) -->
          <plugin><artifactId>maven-install-plugin</artifactId><version>${maven-install-plugin.version}</version></plugin>
          <plugin><artifactId>maven-deploy-plugin</artifactId><version>${maven-deploy-plugin.version}</version></plugin>
          <plugin><artifactId>maven-site-plugin</artifactId><version>${maven-site-plugin.version}</version></plugin>
          <plugin><artifactId>maven-assembly-plugin</artifactId><version>${maven-assembly-plugin.version}</version></plugin>
          <plugin>
            <groupId>org.apache.maven.plugins</groupId>
            <artifactId>maven-wrapper-plugin</artifactId>
            <version>${maven-wrapper-plugin.version}</version>
          </plugin>
          <plugin>
            <groupId>org.apache.maven.plugins</groupId>
            <artifactId>maven-enforcer-plugin</artifactId>
            <version>${maven-enforcer-plugin.version}</version>
          </plugin>
          <plugin>
            <groupId>com.diffplug.spotless</groupId>
            <artifactId>spotless-maven-plugin</artifactId>
            <version>${spotless.version}</version>
            <configuration>
              <java>
                <googleJavaFormat>
                  <version>${google-java-format.version}</version>
                  <style>GOOGLE</style>
                </googleJavaFormat>
              </java>
            </configuration>
          </plugin>
        </plugins>
      </pluginManagement>

      <plugins>
        <plugin>
          <groupId>org.apache.maven.plugins</groupId>
          <artifactId>maven-enforcer-plugin</artifactId>
          <executions>
            <execution>
              <id>enforce-build-rules</id>
              <phase>validate</phase>
              <goals><goal>enforce</goal></goals>
              <configuration>
                <fail>true</fail>
                <rules>
                  <requireJavaVersion>
                    <version>[25,26)</version>
                    <message>regpack builds on Java 25 LTS (spec 5.3)</message>
                  </requireJavaVersion>
                  <requireMavenVersion>
                    <version>[3.9.16,)</version>
                  </requireMavenVersion>
                  <requirePluginVersions>
                    <banSnapshots>true</banSnapshots>
                  </requirePluginVersions>
                  <banDuplicatePomDependencyVersions/>
                  <dependencyConvergence/>
                  <bannedDependencies>
                    <excludes>
                      <exclude>*:*:*:*:compile</exclude>
                      <exclude>*:*:*:*:runtime</exclude>
                    </excludes>
                    <includes>
                      <include>dev.regpack</include>
                    </includes>
                    <message>The walking skeleton is JDK-only: no third-party compile or runtime dependency (spec 5.3, D10).</message>
                  </bannedDependencies>
                </rules>
              </configuration>
            </execution>
          </executions>
        </plugin>
        <plugin><artifactId>maven-compiler-plugin</artifactId></plugin>
        <plugin><artifactId>maven-surefire-plugin</artifactId></plugin>
        <plugin><artifactId>maven-failsafe-plugin</artifactId></plugin>
        <plugin>
          <groupId>com.diffplug.spotless</groupId>
          <artifactId>spotless-maven-plugin</artifactId>
          <executions>
            <execution>
              <id>spotless-check</id>
              <phase>verify</phase>
              <goals><goal>check</goal></goals>
            </execution>
          </executions>
        </plugin>
      </plugins>
    </build>

    <profiles>
      <!-- Moves all build output out of a synced folder: set REGPACK_BUILD_ROOT, for example C:\dev\regpack-build -->
      <profile>
        <id>offsync</id>
        <activation>
          <property><name>env.REGPACK_BUILD_ROOT</name></property>
        </activation>
        <build>
          <directory>${env.REGPACK_BUILD_ROOT}/${project.artifactId}</directory>
        </build>
      </profile>
    </profiles>
  </project>
  ```
- [ ] **Step 3: Write the module POMs and Maven JVM config.** Every module POM has this shape (shown for `regpack-tools`; only `artifactId`, `name`, `description`, `dependencies` and, where stated, `properties` differ):
  ```xml
  <?xml version="1.0" encoding="UTF-8"?>
  <project xmlns="http://maven.apache.org/POM/4.0.0"
           xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
           xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
      <groupId>dev.regpack</groupId>
      <artifactId>regpack</artifactId>
      <version>0.1.0-SNAPSHOT</version>
    </parent>
    <artifactId>regpack-tools</artifactId>
    <name>regpack-tools</name>
    <description>Build and CI gates (JDK-only)</description>
  </project>
  ```
  Dependencies to add inside `<dependencies>` (versions come from the parent): `regpack-model`: `regpack-tools` (test). `regpack-ports`: `regpack-model` (compile), `regpack-tools` (test). `regpack-testkit`: `regpack-model`, `regpack-ports` (compile) and the scope overrides `junit-jupiter` and `assertj-core` with `<scope>provided</scope>`. `regpack-inmemory`: `regpack-model`, `regpack-ports` (compile), `regpack-testkit`, `regpack-tools` (test), plus `<properties><excludedGroups>mutant</excludedGroups></properties>`. `regpack-engine`: `regpack-model`, `regpack-ports` (compile), `regpack-testkit`, `regpack-inmemory`, `regpack-tools` (test). `regpack-app`: `regpack-model`, `regpack-ports`, `regpack-inmemory`, `regpack-engine` (compile), `regpack-testkit` (test). `regpack-tools` has no dependency on any other module. `.mvn/jvm.config` (one option per line):
  ```
  --add-exports=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED
  --add-exports=jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED
  --add-exports=jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED
  --add-exports=jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED
  --add-exports=jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED
  --add-exports=jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED
  ```
  (google-java-format needs these on JDK 16 and newer; its README lists exactly this set.)
- [ ] **Step 4: Write the helper scripts.**
  `scripts/t.sh`:
  ```bash
  #!/usr/bin/env bash
  # Usage: scripts/t.sh <module> <surefire -Dtest selector> [extra maven args]
  set -euo pipefail
  cd "$(dirname "$0")/.."
  MVN="${REGPACK_MVN:-./mvnw}"
  exec "$MVN" -B -ntp -pl "$1" -am test "-Dtest=$2" -Dsurefire.failIfNoSpecifiedTests=false "${@:3}"
  ```
  `scripts/it.sh` (integration tests only; unit tests are skipped by a selector that matches nothing):
  ```bash
  #!/usr/bin/env bash
  # Usage: scripts/it.sh <module> <failsafe -Dit.test selector> [extra maven args]
  set -euo pipefail
  cd "$(dirname "$0")/.."
  MVN="${REGPACK_MVN:-./mvnw}"
  # -Dspotless.check.skip=true: freshly hand-written code is not formatted yet; the full verify and CI still enforce the check
  exec "$MVN" -B -ntp -pl "$1" -am verify -Dspotless.check.skip=true "-Dit.test=$2" -Dfailsafe.failIfNoSpecifiedTests=false \
    -Dtest=NoUnitTestsInThisRun -Dsurefire.failIfNoSpecifiedTests=false "${@:3}"
  ```
  `scripts/t.ps1`:
  ```powershell
  param([Parameter(Mandatory = $true)][string]$Module, [Parameter(Mandatory = $true)][string]$Test,
        [Parameter(ValueFromRemainingArguments = $true)][string[]]$Extra)
  $ErrorActionPreference = 'Stop'
  Set-Location (Split-Path $PSScriptRoot -Parent)
  $mvn = if ($env:REGPACK_MVN) { $env:REGPACK_MVN } else { '.\mvnw.cmd' }
  & $mvn -B -ntp -pl $Module -am test "-Dtest=$Test" "-Dsurefire.failIfNoSpecifiedTests=false" @Extra
  exit $LASTEXITCODE
  ```
  `scripts/it.ps1` is the same with `verify`, `"-Dit.test=$Test"`, `"-Dfailsafe.failIfNoSpecifiedTests=false"`, `"-Dtest=NoUnitTestsInThisRun"`, `"-Dspotless.check.skip=true"`. Mark the `.sh` files executable: `git update-index --add --chmod=+x scripts/t.sh scripts/it.sh`.
- [ ] **Step 5: Run the test red.** `scripts/t.sh regpack-tools HostileEnvironmentTest -Dtest.argLine=` (PowerShell: `scripts/t.ps1 -Module regpack-tools -Test HostileEnvironmentTest "-Dtest.argLine="`). Expected: `Tests run: 1, Failures: 1` with an assertion that the default language is not `tr` (it is `en` or `nl`), then `BUILD FAILURE`.
- [ ] **Step 6: Run it green and run the whole build.** `scripts/t.sh regpack-tools HostileEnvironmentTest` expects `Tests run: 1, Failures: 0, Errors: 0, Skipped: 0`. Then `./mvnw -B -ntp -q spotless:apply` and `./mvnw -B -ntp verify`. Expected: `BUILD SUCCESS`, the enforcer, spotless-check and both test plugins listed for each module, and the line `Tests will run in random order` with a seed.
- [ ] **Step 7: Negative check of the dependency rule (do not commit).** Add to `regpack-model/pom.xml` a compile dependency `org.apache.commons:commons-lang3:3.17.0`, run `./mvnw -B -ntp validate`. Expected: `BUILD FAILURE` and an enforcer line containing `BannedDependencies failed with message` followed by `The walking skeleton is JDK-only`. Revert by deleting the added dependency line from `regpack-model/pom.xml` (the file is not yet tracked at this point, so `git checkout` cannot restore it) and run `./mvnw -B -ntp validate` again, expecting `BUILD SUCCESS`. (This is review focus RF18.)
- [ ] **Step 8: Commit.** `./mvnw -B -ntp -q spotless:apply`, `git add -A`, `git commit -m "build: add parent POM, seven module skeletons, enforcer rules and hostile test environment" -m "Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"`.

### Task 3 (T3): Determinism bytecode scan and the gates entry point (regpack-tools)

Spec I3 says rules use no wall clock, randomness or locale. A source grep misses method references and formatting tricks; this task scans compiled classes through the constant pool using the JDK `java.lang.classfile` API (final in JDK 24, so available on Java 25 without preview flags).

**Files:**
- Create: `regpack-tools/src/main/java/dev/regpack/tools/ForbiddenCallScan.java`, `regpack-tools/src/main/java/dev/regpack/tools/Gates.java`, `regpack-tools/src/test/java/dev/regpack/tools/ForbiddenCallScanTest.java`, `regpack-tools/src/test/java/dev/regpack/tools/GatesTest.java`

**Interfaces:**
```java
public final class ForbiddenCallScan {
  public enum RuleSet { WALL_CLOCK, RANDOMNESS, LOCALE }
  public record Violation(String className, String reference, RuleSet ruleSet) {}
  public static List<Violation> scan(Path classesDir, Set<RuleSet> ruleSets) throws IOException;
}
public final class Gates {
  public static void main(String[] args);                       // System.exit(run(...))
  static int run(String[] args, PrintStream out, PrintStream err);   // 0 ok, 1 violations, 2 usage
}
```

Forbidden references (`owner.name` with an optional exact descriptor; an owner ending in `/` is a package prefix):
- `WALL_CLOCK`: any `now` method of a class under `java/time/`; `java/time/Clock.systemUTC`, `systemDefaultZone`, `system`; `java/lang/System.currentTimeMillis`, `nanoTime`; `java/lang/Thread.sleep`; `java/util/Date.<init>()V`; `java/util/Calendar.getInstance`.
- `RANDOMNESS`: `java/util/Random.<init>`; `java/util/SplittableRandom.<init>`; `java/security/SecureRandom.<init>`; `java/util/concurrent/ThreadLocalRandom.current`; `java/lang/Math.random`; `java/util/UUID.randomUUID`; `java/util/random/RandomGenerator.of`, `getDefault`.
- `LOCALE` (these descriptors are the overloads without a `Locale`): `java/lang/String.toLowerCase()Ljava/lang/String;`, `toUpperCase()Ljava/lang/String;`, `format(Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/String;`, `formatted([Ljava/lang/Object;)Ljava/lang/String;`; `java/util/Locale.getDefault` (any descriptor); `java/text/NumberFormat.getInstance()Ljava/text/NumberFormat;` and the other no-argument `get*Instance()` factories; `java/text/DecimalFormat.<init>`; `java/text/SimpleDateFormat.<init>`; `java/text/Collator.getInstance()Ljava/text/Collator;`; `java/time/format/DateTimeFormatter.ofPattern(Ljava/lang/String;)Ljava/time/format/DateTimeFormatter;`; `java/io/PrintStream.printf(Ljava/lang/String;[Ljava/lang/Object;)Ljava/io/PrintStream;` and `format` with the same descriptor; `java/util/Formatter.<init>()V`.

- [ ] **Step 1: Write the failing tests** in `ForbiddenCallScanTest`. A helper `compile(Path dir, String className, String source)` writes the source under a `@TempDir` and compiles it with `ToolProvider.getSystemJavaCompiler()` (`-d <dir>`), failing the test if compilation fails. Tests (each asserts the exact violation list):
  - `flagsInstantNow`: `class A { Object f() { return java.time.Instant.now(); } }` with `{WALL_CLOCK}` gives one `Violation("A", "java/time/Instant.now", WALL_CLOCK)`.
  - `flagsMethodReferenceToWallClock`: `class B { java.util.function.Supplier<Object> s = java.time.Instant::now; }` gives one `WALL_CLOCK` violation for `java/time/Instant.now` (found through the constant pool, not through code walking).
  - `flagsDefaultLocaleToLowerCase`: `class C { String f(String s) { return s.toLowerCase(); } }` with `{LOCALE}` gives one violation; `class D { String f(String s) { return s.toLowerCase(java.util.Locale.ROOT); } }` gives none.
  - `acceptsExplicitLocaleFormat`: `class E { String f() { return String.format(java.util.Locale.ROOT, "%d", 1); } }` gives none; `String.format("%d", 1)` gives one.
  - `flagsRandomness`: `class F { Object a = new java.util.Random(); Object b = java.util.UUID.randomUUID(); }` with `{RANDOMNESS}` gives two violations, sorted by reference.
  - `ruleSetsAreIndependent`: class A scanned with `{LOCALE, RANDOMNESS}` gives none.
  - `cleanClassHasNoViolationsAndNestedDirectoriesAreScanned`: a class in package `p.q` that is clean gives none; the same package with `Instant.now()` gives one violation whose `className` is `p/q/G`.
  `GatesTest#noArgumentsPrintsUsageAndExitsWithTwo`: `Gates.run(new String[0], out, err)` returns 2 and `err` contains `usage: Gates <traceability|pins|eol>`.
- [ ] **Step 2: Run red.** `scripts/t.sh regpack-tools 'ForbiddenCallScanTest,GatesTest'`. Expected: compilation failure `cannot find symbol ... class ForbiddenCallScan` (and `Gates`).
- [ ] **Step 3: Implement.** `ForbiddenCallScan.scan` walks `classesDir` for `*.class`, parses each with `ClassFile.of().parse(bytes)`, iterates `classModel.constantPool()`, and for every `java.lang.classfile.constantpool.MethodRefEntry` or `InterfaceMethodRefEntry` builds `owner = entry.owner().asInternalName()`, `name = entry.name().stringValue()`, `descriptor = entry.type().stringValue()` and matches the rule tables above for the requested sets. `className` is `classModel.thisClass().asInternalName()`; the result is sorted by (`className`, `reference`) and has no duplicates. `reference` is `owner + "." + name` (descriptor omitted). Check the exact accessor names against the JDK 25 javadoc of `java.lang.classfile` while implementing; the API is stable since JDK 24. `Gates.run` prints the usage line for no arguments or an unknown command and returns 2; the three real commands are added in T25 and T26.
- [ ] **Step 4: Run green.** `scripts/t.sh regpack-tools 'ForbiddenCallScanTest,GatesTest'`. Expected: `Tests run: 8, Failures: 0, Errors: 0, Skipped: 0`.
- [ ] **Step 5: Commit.** `./mvnw -B -ntp -q spotless:apply`, `git add -A`, `git commit -m "feat(tools): add bytecode determinism scan and gates entry point" -m "Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"`.

### Task 4 (T4): Model value types

**Files:**
- Create under `regpack-model/src/main/java/dev/regpack/model/`: `Sha256.java`, `Outcome.java`, `Leg.java`, `CitationRole.java`, `Regime.java`, `EventIdentity.java`, `ReasonCode.java`, `Citation.java`, `IngestKey.java`, `BatchId.java`, `Checkpoint.java`
- Test: `regpack-model/src/test/java/dev/regpack/model/Sha256Test.java`, `ValueTypesTest.java`

**Interfaces:** exactly the "Shared model types" block of the port contracts (package `dev.regpack.model`). Validation rules the tests pin: `Sha256.hex` matches `[0-9a-f]{64}`; `Regime.id` matches `[a-z][a-z0-9]*(\.[a-z0-9]+)*`; `ReasonCode.value` matches `[A-Z][A-Z0-9_]*(\.[A-Za-z0-9_]+){0,3}`; `EventIdentity.of` needs at least one part, every part non-blank and free of `|`, and joins with `|`; `IngestKey` strings are non-blank and `sourceVersion >= 0`; `BatchId.of(digest, slice)` is `<hex>:<slice>` with `slice >= 0`; `Checkpoint` requires a non-blank `stream`, copies `state` on construction and on access, and has content-based `equals`, `hashCode` and a `toString` that shows only the stream and the state length; `Citation` requires non-blank `sourceId` and `provisionPath`. Compact constructors throw `IllegalArgumentException`.

- [ ] **Step 1: Write the failing tests.**
  `Sha256Test#knownVectors`: `Sha256.ofUtf8("")` equals hex `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855`; `Sha256.ofUtf8("abc")` equals `ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad`; `Sha256.of("hello".getBytes(UTF_8))` equals `2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824`. `Sha256Test#rejectsUppercaseAndWrongLength`: `new Sha256("E3B0...")` (uppercase, 64 chars) and `new Sha256("abc")` throw `IllegalArgumentException`.
  `ValueTypesTest`: `eventIdentityJoinsPartsWithPipe` (`EventIdentity.of("LEI1","XPAR","EX-1").value()` is `LEI1|XPAR|EX-1`; `of("a|b")`, `of("")` and `of()` throw); `reasonCodeAcceptsSpecShapes` (accepts `MIFIR.N03.EXCLUDED`, `MIFIR.N03.EXCLUDED.2_5_e`, `EXEC_BUDGET`, `ENRICH_MISS`; rejects `mifir.n03`, `MIFIR..X`, `""`, `"A B"`); `regimeIdIsLowerCaseDotted` (accepts `mifir.eu.rts22`; rejects `MiFIR`, `a..b`); `batchIdCombinesDigestAndSlice` (`BatchId.of(Sha256.ofUtf8("abc"), 3).value()` is `ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad:3`; slice `-1` throws); `checkpointHasContentEquality` (two `Checkpoint("s", {1,2})` are equal and have equal hash codes; mutating the constructor argument array afterwards or the array returned by `state()` does not change the checkpoint; `toString()` does not contain the byte values); `ingestKeyRejectsBlankAndNegativeVersion`; `citationRequiresSourceAndPath`.
- [ ] **Step 2: Run red.** `scripts/t.sh regpack-model 'Sha256Test,ValueTypesTest'`. Expected: compilation errors `cannot find symbol` for `Sha256`, `EventIdentity` and the others.
- [ ] **Step 3: Implement** the eleven types. `Sha256.of` uses `MessageDigest.getInstance("SHA-256")` and a lowercase hex table (no `String.format`, which is locale-sensitive).
- [ ] **Step 4: Run green.** Same command. Expected: `Tests run: 9, Failures: 0, Errors: 0, Skipped: 0` (2 in `Sha256Test`, 7 in `ValueTypesTest`).
- [ ] **Step 5: Commit.** `./mvnw -B -ntp -q spotless:apply`, `git add -A`, `git commit -m "feat(model): add validated value types" -m "Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"`.

### Task 5 (T5): Strict canonical JSON

Reproducibility (I3) needs one byte-exact textual form for hashing and for goldens. JDK has no JSON API, so P0 owns a small strict one. It is also the format of the NDJSON toy input and of golden cases.

**Files:**
- Create: `regpack-model/src/main/java/dev/regpack/model/json/Json.java`, `JsonException.java`
- Test: `regpack-model/src/test/java/dev/regpack/model/json/JsonTest.java`

**Interfaces:**
```java
public final class Json {
  public static Object parse(String text);            // Map<String,Object> | List<Object> | String | Boolean | Long | BigDecimal
  public static List<Object> parseNdjson(String text); // one value per non-blank line; errors name "line N"
  public static String canonical(Object value);        // sorted keys, no whitespace
}
public final class JsonException extends RuntimeException { public JsonException(String message); }
```
Rules. Parse: RFC 8259 grammar; an integer literal without fraction or exponent becomes `Long` (out of range is a `JsonException`), any other number becomes `BigDecimal`; the literal `null` is a `JsonException` (explicit absence means the key is omitted; spec 7.4); duplicate keys, leading zeros, `NaN` and trailing characters are `JsonException`; objects parse to a `LinkedHashMap`. Canonical write accepts `Map<String,?>` (keys sorted by `String.compareTo`), `List`, `String`, `Boolean`, `Long`/`Integer`/`Short`/`Byte`, `BigInteger`, `BigDecimal` (written as `stripTrailingZeros().toPlainString()`, zero as `0`), `Instant` (UTC, always six fractional digits: `uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'` with `Locale.ROOT`; sub-microsecond nanos are an `IllegalArgumentException`), `LocalDate` (ISO), and any `Enum` (its `name()`); `null`, `Optional`, `Double` and `Float` throw `IllegalArgumentException` (scaled decimals only). Strings escape `"`, `\`, and control characters (`\b \f \n \r \t`, others as `\u00xx` with lowercase hex built from a table); non-ASCII is written as is.

- [ ] **Step 1: Write the failing tests** in `JsonTest`:
  - `canonicalOrdersKeysAndStripsDecimals`: `Json.canonical(Json.parse("{\"b\": 1.10, \"a\": \"x\", \"c\": [true, 0.00, 100E-2]}"))` is `{"a":"x","b":1.1,"c":[true,0,1]}`.
  - `canonicalOfJavaValues`: a `Map` of `n -> new BigDecimal("1E+3")`, `t -> Instant.parse("2026-10-09T10:15:30.123456Z")`, `d -> LocalDate.of(2026,10,9)`, `e -> Outcome.REPORT` canonicalises to `{"d":"2026-10-09","e":"REPORT","n":1000,"t":"2026-10-09T10:15:30.123456Z"}`.
  - `instantsHaveFixedMicrosecondForm`: `Instant.parse("2026-10-09T10:15:30Z")` becomes `"2026-10-09T10:15:30.000000Z"`; `Instant.parse("2026-10-09T10:15:30.123456789Z")` throws `IllegalArgumentException`.
  - `rejectsNullLiteral` (`{"a":null}` throws `JsonException` whose message contains `null`), `rejectsDuplicateKeys`, `rejectsFloatingPoint` (`Json.canonical(1.5d)` throws `IllegalArgumentException`), `rejectsTrailingGarbageAndLeadingZeros`.
  - `integersParseAsLongAndOthersAsBigDecimal`: `Json.parse("[5, 5.0]")` is `[5L, new BigDecimal("5.0")]`.
  - `parsesStringEscapes`: `Json.parse("\"a\\u00e9\\n\\\"\"")` is `"aé\n\""`.
  - `unicodeIsEmittedRawAndControlCharsEscaped`: `Json.canonical("é\u001f")` (the Java string e-acute followed by control character 0x1f) is the text `"` + `é` + backslash + `u001f` + `"`: the accented letter is written as is and the control character as a six-character escape with lowercase hex, which proves that no locale-dependent formatting is used under `tr_TR`.
  - `ndjsonSkipsBlankLinesAndReportsLineNumber`: `parseNdjson("{\"a\":1}\n\n{\"a\":2}\n")` has size 2; `parseNdjson("{\"a\":1}\n{bad}\n")` throws `JsonException` whose message contains `line 2`.
- [ ] **Step 2: Run red.** `scripts/t.sh regpack-model JsonTest`. Expected: `cannot find symbol ... class Json`.
- [ ] **Step 3: Implement** `Json` as a recursive-descent parser over the string plus a recursive canonical writer (no regex, no `String.format`). `JsonException` declares `serialVersionUID` as Convention 7 requires; `-Xlint:all -Werror` would otherwise fail the first compile (the `serial` category), and every later exception class follows the same rule.
- [ ] **Step 4: Run green.** Same command. Expected: `Tests run: 11, Failures: 0, Errors: 0, Skipped: 0`.
- [ ] **Step 5: Commit.** `./mvnw -B -ntp -q spotless:apply`, `git add -A`, `git commit -m "feat(model): add strict canonical JSON" -m "Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"`.

### Task 6 (T6): Canonical execution and decision types

**Files:**
- Create under `regpack-model/src/main/java/dev/regpack/model/canonical/`: `CanonicalEvent.java`, `CanonicalExecution.java`, `LifecycleKind.java`, `AssetClass.java`, `PartyKind.java`, `CanonicalExecutionCodec.java`
- Create under `regpack-model/src/main/java/dev/regpack/model/decision/`: `DecisionId.java`, `PathStep.java`, `DecisionContext.java`, `DecisionBody.java`, `DecisionRecord.java`, `InputDigest.java`, `DecisionCodec.java`
- Test: `regpack-model/src/test/java/dev/regpack/model/canonical/CanonicalExecutionCodecTest.java`, `.../decision/DecisionTypesTest.java`, `.../NoWallClockRandomLocaleTest.java`

**Interfaces:** the canonical and decision types of the port contracts, plus
```java
public final class CanonicalExecutionCodec {
  public static Map<String, Object> toMap(CanonicalExecution execution);
  public static CanonicalExecution fromMap(Map<String, Object> map);   // IllegalArgumentException names the field
}
public final class DecisionCodec {
  public static Map<String, Object> contextToMap(DecisionContext context);
  public static Map<String, Object> bodyToMap(DecisionBody body, boolean includeEvaluatedAt);
  public static Map<String, Object> recordToMap(DecisionRecord record);
}
```
`CanonicalExecution.eventIdentity()` is `EventIdentity.of(firmLei, venueMic, venueExecutionId)` and `occurredAt()` is `tradedAt`. Map keys are the record component names; enums are their names; `clientLei` is omitted when absent (explicit absence, no null). `DecisionContext.id()` is `Sha256.ofUtf8(Json.canonical(DecisionCodec.contextToMap(this)))`. `DecisionBody.replayDigest()` is `Sha256.ofUtf8(Json.canonical(DecisionCodec.bodyToMap(this, false)))`, so it excludes `evaluatedAt` and nothing else (I3: replay compares decisions, not their evaluation instant). `InputDigest.of(e, k)` is `Sha256.ofUtf8(Json.canonical(CanonicalExecutionCodec.toMap(e)) + "\n" + k.sourceId() + "|" + k.sourceRecordId() + "|" + k.sourceVersion())` (spec 6.3: the canonical input concatenated with the `ingest_key`, so a source state that recurs at a later version gets a new digest). This is the form with an empty enrichment-hash list; the overload that adds the stored enrichment response hashes (spec 6.3) is added by P5, see the port contracts. All list components are copied with `List.copyOf`.

`CanonicalExecution` is a deliberately flattened walking-skeleton type for the toy pipeline. Further `CanonicalEvent` permits and richer entities are additive (sealed interface, trailing components with compatibility constructors) and owned as in the Scope check.

- [ ] **Step 1: Write the failing tests.**
  `CanonicalExecutionCodecTest`: `roundTripsThroughCanonicalJson` (for an execution with `quantity = 100.5`, `price = 12.25`, `tradedAt = 2026-10-09T09:30:00.123456Z`, `clientLei` present: `Json.canonical(toMap(fromMap((Map) Json.parse(Json.canonical(toMap(e))))))` equals `Json.canonical(toMap(e))`); `eventIdentityIsFirmVenueExecution`; `clientLeiIsOmittedWhenAbsent` (`toMap(e).containsKey("clientLei")` is false for `Optional.empty()`); `fromMapRejectsMissingFieldAndUnknownEnum` (message contains `eventKind` for a missing field, and contains `BOGUS` for `"eventKind":"BOGUS"`).
  `DecisionTypesTest`: `contextIdIsSha256OfCanonicalJson` (equal contexts have equal ids; changing `engineBuild` changes the id); `replayDigestIgnoresEvaluatedAtOnly` (two bodies differing only in `evaluatedAt` have equal `replayDigest`; changing `outcome`, or one `PathStep.costUnits`, changes it); `inputDigestChangesWithSourceVersion` (versions 1 and 3 of equal content give different digests; the same key twice gives equal digests); `bodyListsAreImmutable` (`body.flags().add("x")` throws `UnsupportedOperationException`).
  `NoWallClockRandomLocaleTest#modelMainClassesAreClean`: `ForbiddenCallScan.scan(Path.of(System.getProperty("regpack.classes.dir")), EnumSet.allOf(RuleSet.class))` is empty (this test is the model module's instance of review focus RF6).
- [ ] **Step 2: Run red.** `scripts/t.sh regpack-model 'CanonicalExecutionCodecTest,DecisionTypesTest,NoWallClockRandomLocaleTest'`. Expected: `cannot find symbol ... class CanonicalExecution`.
- [ ] **Step 3: Implement** the types and codecs. `fromMap` accepts `Long` or `BigDecimal` for numbers and `String` for instants (`Instant.parse`) and rejects anything else with a message that names the field.
- [ ] **Step 4: Run green.** Same command. Expected: `Tests run: 9, Failures: 0, Errors: 0, Skipped: 0`. Then run `./mvnw -B -ntp -q spotless:apply` followed by `./mvnw -B -ntp -pl regpack-model -am verify` for the model module chain and expect `BUILD SUCCESS`.
- [ ] **Step 5: Commit.** `./mvnw -B -ntp -q spotless:apply`, `git add -A`, `git commit -m "feat(model): add canonical execution and decision types" -m "Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"`.

### Task 7 (T7): `Clock` port and `ManualClock` (testkit bootstrap)

**Files:**
- Create: `regpack-ports/src/main/java/dev/regpack/ports/Clock.java`, `regpack-testkit/src/main/java/dev/regpack/testkit/clock/ManualClock.java`
- Test: `regpack-testkit/src/test/java/dev/regpack/testkit/clock/ManualClockTest.java`

**Interfaces:** `Clock` exactly as in the port contracts.
```java
public final class ManualClock implements Clock {
  public ManualClock(Instant start);
  public Instant now();
  public long monotonicNanos();                 // starts at 0
  public void advance(Duration d);              // d >= 0 (else IllegalArgumentException): wall and monotonic move together
  public void stepWall(Duration d);             // any sign: wall only, monotonic unchanged (clock drills, spec 11.5)
  public void set(Instant instant);             // wall only
}
```
Thread-safe. Like every `Clock`, it works in whole microseconds: the constructor, `set(Instant)`, `advance(Duration)` and `stepWall(Duration)` throw `IllegalArgumentException` for an instant whose nanos part, or a duration whose `toNanos()`, is not a multiple of 1000.

- [ ] **Step 1: Write the failing tests** `ManualClockTest`: `startsAtGivenInstantAndMonotonicAtZero`; `advanceMovesWallAndMonotonicTogether` (`advance(5s)`: `now()` is start + 5 s, `monotonicNanos()` is 5_000_000_000); `stepWallCanGoBackwardsWithoutMovingMonotonic` (`stepWall(-1h)`: `now()` is start - 1 h, monotonic unchanged); `advanceRejectsNegative`; `rejectsSubMicrosecondInstants` (the constructor and `set` with 1 ns of sub-microsecond precision); `rejectsSubMicrosecondDurations` (`advance` and `stepWall` with `Duration.ofNanos(1)`); `isSafeFromManyThreads` (8 threads each call `advance(1ms)` 1000 times; `now()` is start + 8 s and monotonic is 8_000_000_000).
- [ ] **Step 2: Run red.** `scripts/t.sh regpack-testkit ManualClockTest`. Expected: `cannot find symbol ... class ManualClock` and `Clock`.
- [ ] **Step 3: Implement** `Clock` and `ManualClock` (`synchronized` methods are enough).
- [ ] **Step 4: Run green.** Same command. Expected: `Tests run: 7, Failures: 0, Errors: 0, Skipped: 0`.
- [ ] **Step 5: Commit.** `./mvnw -B -ntp -q spotless:apply`, `git add -A`, `git commit -m "feat(ports): add Clock port and ManualClock test double" -m "Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"`.

### Task 8 (T8): `EvidenceStore` port, contract suite and in-memory adapter

**Files:**
- Create (ports): `regpack-ports/src/main/java/dev/regpack/ports/evidence/EvidenceKey.java`, `EvidenceStore.java`, `EvidenceUnavailableException.java`, `EvidenceIntegrityException.java`, `EvidenceRetentionException.java`
- Create (testkit): `regpack-testkit/src/main/java/dev/regpack/testkit/contract/EvidenceStoreContract.java`
- Create (inmemory): `regpack-inmemory/src/main/java/dev/regpack/inmemory/InMemoryEvidenceStore.java`
- Test: `regpack-ports/src/test/java/dev/regpack/ports/evidence/EvidenceKeyTest.java`, `regpack-ports/src/test/java/dev/regpack/ports/NoWallClockTest.java`, `regpack-inmemory/src/test/java/dev/regpack/inmemory/AbstractEvidenceContractTest.java`, `InMemoryEvidenceStoreContractTest.java`, `NoWallClockTest.java`

**Interfaces:** `EvidenceKey` and `EvidenceStore` exactly as in the port contracts.
```java
public interface EvidenceStoreContract {           // JUnit interface; every test is a default method
  EvidenceStore store();                           // a fresh store for each test
  ManualClock clock();
  void simulateOutage(boolean down);               // adapter-specific way to make every call fail
  EvidenceStore reopen();                          // a store over the same persisted state, as after a restart or reconnect; the in-memory adapter returns itself
}
public final class InMemoryEvidenceStore implements EvidenceStore {
  public InMemoryEvidenceStore(Clock clock);
  public void simulateOutage(boolean down);
}
// test: abstract class AbstractEvidenceContractTest implements EvidenceStoreContract (fields, @BeforeEach, abstract EvidenceStore create(ManualClock))
```

- [ ] **Step 1: Write the failing tests.** `EvidenceKeyTest`: `acceptsSegmentsAndJoinsWithSlash` (`EvidenceKey.of("landing","ab","1").path()` is `landing/ab/1`), `rejectsDotDotEmptyAndOverlong` (segments `".."`, `"."`, `""`, a segment with a space, and a path over 512 characters throw `IllegalArgumentException`), `namespaceIsFirstSegment`. `regpack-inmemory`'s `NoWallClockTest` asserts `ForbiddenCallScan.scan(classesDir, EnumSet.of(WALL_CLOCK))` is empty. `regpack-ports`' `NoWallClockTest` (Javadoc: the ports module holds the rule-reachable `CostMeter`, `Value` and `Expression` types) asserts `ForbiddenCallScan.scan(classesDir, EnumSet.allOf(RuleSet.class))` is empty; the class name stays so that RF6 and the file-structure rows remain valid. `EvidenceStoreContract` default tests (each uses a 5-year `Retention` of class `landing` computed from `clock()`; `K` is `EvidenceKey.of("landing","k1")`):
  - `putThenGetReturnsIdenticalBytesAndSha256`: put `"hello"`; `get(K)` has the same bytes, `sha256` equal to `2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824`, `writtenAt` equal to `clock().now()` at put time, `legalHold` false, and the returned `mediaType` and `retention` equal to those put.
  - `putSameKeySameBytesIsIdempotent`: second put returns `ALREADY_PRESENT_IDENTICAL` and `writtenAt` is unchanged after `clock().advance(1h)`; a second put with a different `mediaType` and a later retention leaves the stored `mediaType` and `retention` those of the first put.
  - `putSameKeyDifferentBytesIsIntegrityAlarmAndKeepsOriginal`: second put with other bytes throws `EvidenceIntegrityException`; `get(K)` still returns the original bytes.
  - `getMissingReturnsEmpty`.
  - `listReturnsKeysInCodeUnitOrderWithPrefixAfterAndLimit`: put keys `a/B`, `a/a`, `a/_`, `b/x`; `list("a/", empty, 10)` is `[a/B, a/_, a/a]`; `list("a/", Optional.of(a/B), 1)` is `[a/_]`; `list("zzz", empty, 10)` is empty.
  - `retentionCanOnlyBeExtended`: `extendRetention(K, later)` makes `get(K).retention().retainUntil()` equal `later`; `extendRetention(K, earlier)` throws `EvidenceRetentionException` and the stored value is unchanged.
  - `legalHoldIsSeparateFromRetention`: `setLegalHold(K, true)` sets `legalHold` and leaves `retention` unchanged.
  - `outageFailsClosedAndStoresNothing`: after `simulateOutage(true)` put, get and list each throw `EvidenceUnavailableException`; after `simulateOutage(false)` the key that was put during the outage is absent.
  - `gradeAndDurabilityAreDeclared`: `grade().profile()` is non-blank, `durability()` is non-null and `independentFailureDomains()` is at least 0.
  - `returnedBytesAreCopies`: changing the array passed to `put`, or the array returned by `get`, does not change a later `get`.
  - `acknowledgedPutIsReadableAfterReopen`: put, then `reopen().get(K)` returns identical bytes, `sha256`, `mediaType`, `writtenAt` and `retention`.
  - `concurrentPutsOfTheSameKeyWithDifferentBytesHaveExactlyOneWinner`: 8 threads released by one `CountDownLatch` put different bytes under `K`; exactly one result is `CREATED`, seven throw `EvidenceIntegrityException`, and `get(K)` equals the winner's bytes. Javadoc: meaningful only against a real store; the single-lock in-memory adapter passes trivially (spec 14.1).
  `InMemoryEvidenceStoreContractTest extends AbstractEvidenceContractTest` returns `new InMemoryEvidenceStore(clock)`, implements `simulateOutage` by delegating to the adapter and `reopen()` by returning the same instance, and adds `declaresTestOnlyGradeAndNoDurabilityClaim` (profile `P-MEMORY`, durability equal to `Durability(false, 0)`).
- [ ] **Step 2: Run red.** `scripts/t.sh regpack-inmemory 'InMemoryEvidenceStoreContractTest,NoWallClockTest'` and `scripts/t.sh regpack-ports 'EvidenceKeyTest,NoWallClockTest'`. Expected: `cannot find symbol` for `EvidenceStore`, `EvidenceKey`, `InMemoryEvidenceStore`.
- [ ] **Step 3: Implement** the port types and the adapter: a `TreeMap<String, StoredObject>` guarded by one lock (natural `String` order is UTF-16 code-unit order), `writtenAt` from the injected `Clock`, the grade `("P-MEMORY", "")` and the honest `Durability(false, 0)` (a test double: it survives nothing, so no durability gate may accept it), and one volatile outage flag checked first in every method. The grade string `P-MEMORY` can never be mistaken for P-WORM or P-SEALED.
- [ ] **Step 4: Run green.** Same commands. Expected: `Tests run: 14` in inmemory (12 contract tests, `declaresTestOnlyGradeAndNoDurabilityClaim` and `NoWallClockTest`) and `Tests run: 4` in ports, all with `Failures: 0, Errors: 0, Skipped: 0`.
- [ ] **Step 5: Commit.** `./mvnw -B -ntp -q spotless:apply`, `git add -A`, `git commit -m "feat(ports): add EvidenceStore port, contract suite and in-memory adapter" -m "Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"`.

### Task 9 (T9): `LedgerStore` part 1: ingest and outbox

**Files:**
- Create (ports, `dev/regpack/ports/ledger/`): `LedgerStore.java`, `IngestLedger.java`, `OutboxLedger.java`, `LedgerException.java`
- Create (testkit): `regpack-testkit/src/main/java/dev/regpack/testkit/contract/IngestLedgerContract.java`, `LedgerFixtures.java`
- Create (inmemory): `regpack-inmemory/src/main/java/dev/regpack/inmemory/InMemoryLedgerStore.java`
- Test: `regpack-inmemory/src/test/java/dev/regpack/inmemory/AbstractLedgerContractTest.java`, `InMemoryLedgerStoreContractTest.java`

**Interfaces:** `IngestLedger` and `OutboxLedger` exactly as in the port contracts; in this task `LedgerStore extends IngestLedger, OutboxLedger` (T10 and T11 add the other three sub-ports to the `extends` list).
```java
public interface IngestLedgerContract {
  LedgerStore ledger();
  ManualClock clock();
}
public final class LedgerFixtures {                 // testkit helpers shared by the ledger contracts
  public static IngestLedger.Entry entry(String sourceId, String recordId, long version, String content);
      // key = (sourceId, recordId, version); eventIdentity = EventIdentity.of("LEI1","XPAR",recordId); hash = Sha256.ofUtf8(content)
  public static IngestLedger.Commit commit(String batchTag, String sourceId, Checkpoint cp, IngestLedger.Entry... entries);
      // batchId = BatchId.of(Sha256.ofUtf8(batchTag), 0); checkpoint stream "trades"
  public static Checkpoint checkpoint(int n);       // stream "trades", state {n}
}
public final class InMemoryLedgerStore implements LedgerStore {
  public InMemoryLedgerStore(Clock clock);          // the clock is the "database clock" for leases (T11)
}
// test: abstract class AbstractLedgerContractTest implements IngestLedgerContract (fields clock and ledger created in @BeforeEach, abstract LedgerStore create(ManualClock))
```

- [ ] **Step 1: Write the failing contract tests** (`IngestLedgerContract`, using source `srcA`; "A" and "B" are contents):
  - `newBatchIsCommittedWithDispositionsCheckpointAndOutbox`: commit batch `B1` with entries `r1 v1 "A"`, `r2 v1 "A"` and checkpoint 1: `redelivery()` is false; both dispositions `NEW_VERSION`; `outboxId()` present; `pendingOutbox(DECIDE_BATCH, 10)` is exactly one item with `subjectId` equal to `B1.value()` and state `PENDING`; `latestCheckpoint("srcA","trades")` equals checkpoint 1; `findIngest` for `r1 v1` has hash `Sha256.ofUtf8("A")`.
  - `redeliveredBatchIsNoopAndReturnsOriginalResult`: commit `B1` twice, the second time with checkpoint 2: the second result has `redelivery() == true`, equal entries and equal `outboxId`; `pendingOutbox` still has one item; `latestCheckpoint` is still checkpoint 1.
  - `sameVersionSameHashIsNoop`: a new batch with `r1 v1 "A"` gives `NOOP_SAME_VERSION_SAME_HASH`.
  - `sameVersionDifferentHashIsConflictAndKeepsOriginal`: `r1 v1 "B"` gives `CONFLICT_SAME_VERSION_DIFFERENT_HASH`; `findIngest(r1 v1)` still has the hash of `"A"`.
  - `lowerVersionAfterHigherIsStale`: ingest `r9 v2 "A"` (new), then a batch with `r9 v1 "A"` gives `STALE` and `findIngest(r9 v1)` is empty.
  - `abaProducesThreeVersions`: `r5 v1 "A"`, `r5 v2 "B"`, `r5 v3 "A"` in three batches are all `NEW_VERSION`.
  - `otherSourceForSameEventIsSourceConflict`: `srcA` ingests record `r1`; a commit from `srcB` for record `r1` (same event identity) gives `SOURCE_CONFLICT` and `findIngest` for it is empty.
  - `duplicateKeysInsideOneCommitAreRejectedAtomically`: a commit with two entries of the same key throws `IllegalArgumentException`; afterwards `latestCheckpoint` is empty, `pendingOutbox` is empty and `ingestResult(batchId)` is empty.
  - `ingestResultIsRetrievableByBatchId`.
  - `concurrentCommitsOfTheSameBatchIdApplyExactlyOnce`: 8 threads released by one `CountDownLatch` commit the same batch: exactly one result has `redelivery() == false`, seven have `true`, and `pendingOutbox` has one item.
  - `concurrentBatchesWithTheSameIngestKeyCreateExactlyOneVersion`: 8 threads each commit a different batch id containing the same key and the same hash: exactly one `NEW_VERSION`, seven `NOOP_SAME_VERSION_SAME_HASH`, and `findIngest` has the original `batchId`.
  Both concurrency tests carry the Javadoc line: meaningful only against a real store; the single-lock in-memory adapter passes trivially (spec 14.1).
- [ ] **Step 2: Run red.** `scripts/t.sh regpack-inmemory InMemoryLedgerStoreContractTest`. Expected: `cannot find symbol ... class LedgerStore`.
- [ ] **Step 3: Implement** the port types and `InMemoryLedgerStore`. All public methods are `synchronized` on one lock; a method validates its input first and mutates state only after validation succeeds, so a rejected call leaves no trace. State: a map from `IngestKey` to `StoredIngest`; per (`eventIdentity`, `sourceId`) the highest stored version and the owning source per `eventIdentity`; a map from `BatchId` to the stored `Result`; outbox items with a monotonic `OutboxId` counter whose first id is 1; the latest checkpoint per (`sourceId`, `stream`).
- [ ] **Step 4: Run green.** Same command. Expected: `Tests run: 11, Failures: 0, Errors: 0, Skipped: 0`.
- [ ] **Step 5: Commit.** `./mvnw -B -ntp -q spotless:apply`, `git add -A`, `git commit -m "feat(ports): add LedgerStore ingest and outbox with contract suite and in-memory adapter" -m "Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"`.

### Task 10 (T10): `LedgerStore` part 2: decisions (idempotency, head compare-and-set, atomicity)

**Files:**
- Create (ports): `regpack-ports/src/main/java/dev/regpack/ports/ledger/DecisionLedger.java`, `DecisionHeadConflictException.java`
- Create (testkit): `regpack-testkit/src/main/java/dev/regpack/testkit/contract/DecisionLedgerContract.java`
- Modify: `LedgerStore.java` (add `DecisionLedger` to `extends`), `LedgerFixtures.java`, `InMemoryLedgerStore.java`, `AbstractLedgerContractTest.java` (add `DecisionLedgerContract` to `implements`)

**Interfaces:** `DecisionLedger` and `DecisionHeadConflictException(EventIdentity eventIdentity, long currentHeadSeq)` exactly as in the port contracts. Fixtures added to `LedgerFixtures`:
```java
public static DecisionBody body(String event, String regime, String contextTag, String digestTag,
                                Outcome outcome, Instant evaluatedAt);
    // eventIdentity = EventIdentity.of("LEI1","XPAR",event); decisionContextId = Sha256.ofUtf8(contextTag);
    // inputDigest = Sha256.ofUtf8(digestTag); reason MIFIR.N08.IN_SCOPE_26_2_A, node "N08";
    // legs = [MARKET] for REPORT else []; asAt fixed 2026-10-09T09:30:00Z; other lists empty (calls bodyAt)
public static DecisionBody bodyAt(String event, String regime, String contextTag, String digestTag,
                                  Outcome outcome, Instant evaluatedAt, Instant asAt);
    // same as body(...) with an explicit asAt (the cross-partition case of spec 6.3)
public static DecisionLedger.NewDecision decision(DecisionBody body, long expectedHeadSeq);
```

- [ ] **Step 1: Write the failing contract tests** (`DecisionLedgerContract`; regime `mifir.eu.rts22`, event `E1`):
  - `firstDecisionHasSeq1AndNoSupersedes`: `commitDecisions` of one decision with `expectedHeadSeq = 0` returns `INSERTED` with `decisionSeq` 1 and `supersedesDecisionId` empty; `currentDecision` equals that record.
  - `redeliveryOfSameKeyIsNoopAndReturnsStoredRecord`: committing the same body (same event, context, input digest) again with `expectedHeadSeq = 0` returns `NOOP_REDELIVERY` with the same `decisionId`; `decisionChain` has one record.
  - `newInputDigestSupersedesWithNextSeq`: a second decision with another `digestTag` and `expectedHeadSeq = 1` gets seq 2, `supersedesDecisionId` equal to the first id, a larger `decisionId`; `currentDecision` is seq 2; `decisionChain` is ascending `[1, 2]`.
  - `staleExpectedHeadSeqIsRejectedAndRollsBackWholeBatch`: with `E1` at head 1, commit a batch `[decision(E2, expectedHeadSeq 0), decision(E1 new digest, expectedHeadSeq 0)]`: throws `DecisionHeadConflictException` with `eventIdentity` of `E1` and `currentHeadSeq` 1; `currentDecision(E2)` is empty (the first decision of the batch was rolled back).
  - `abaSameOutcomeThirdDecisionIsNewRow`: three decisions for `E1` with digests `dA`, `dB`, `dA2` (outcomes REPORT, DO_NOT_REPORT, REPORT) get seq 1, 2, 3; the third is `INSERTED` with outcome REPORT; chain length 3.
  - `regimesAreIndependentChains`: the same event under `mifir.eu.rts22` and `emir.eu` both get seq 1; the two bodies use different `contextTag` values (`ctx-mifir`, `ctx-emir`), as the pack digest in a real context differs per regime.
  - `sameEventSameDigestNewContextSupersedes`: `E1` decided with `contextTag` `c1` and `digestTag` `d1` (seq 1); the same event and digest with `contextTag` `c2` and `expectedHeadSeq` 1 is `INSERTED` with seq 2 and `supersedesDecisionId` equal to the first id; the first body again (`c1`, `d1`) with `expectedHeadSeq` 2 is `NOOP_REDELIVERY` returning the seq-1 record.
  - `supersedingDecisionWithDifferentTradeDateKeepsChainLinear`: `E1` seq 1 with `asAt` 2026-10-09; a correction with `asAt` 2026-10-10 and `expectedHeadSeq` 1 gets seq 2 with `supersedesDecisionId` equal to the first id; a further decision with `expectedHeadSeq` 1 and `asAt` 2026-10-10 throws `DecisionHeadConflictException` with `currentHeadSeq` 2; `decisionChain` is `[1, 2]`. Javadoc: the real-store run proves the cross-partition case (spec 6.3).
  - `redeliveryOfTheFirstDecisionAfterATradeDateChangeIsNoop`: after the case above, committing the original seq-1 body again with `expectedHeadSeq` 2 returns `NOOP_REDELIVERY` with the seq-1 record, and the chain stays length 2.
  - `concurrentDecisionCommitsWithTheSameExpectedHeadHaveExactlyOneWinner`: 8 threads each commit a decision for `E1` with a distinct `digestTag` and `expectedHeadSeq` 0: exactly one `INSERTED` with seq 1, seven throw `DecisionHeadConflictException`, and `decisionChain` has one record. Javadoc: meaningful only against a real store; the single-lock in-memory adapter passes trivially (spec 14.1).
  - `completingOutboxIsPartOfTheSameUnit`: after an ingest commit creates a `DECIDE_BATCH` item, `commitDecisions` with `completesOutbox` set makes `outboxItem(id).state()` `DONE` and `pendingOutbox` empty; a batch that throws `DecisionHeadConflictException` leaves the item `PENDING`.
  - `decisionIdsAreUniqueAndIncreasing`: ids of five consecutive inserts across two events are strictly increasing.
  - `expectedHeadSeqZeroOnExistingChainConflicts`: with `E1` at head 1, a new digest with `expectedHeadSeq = 0` throws `DecisionHeadConflictException(currentHeadSeq = 1)`.
- [ ] **Step 2: Run red.** `scripts/t.sh regpack-inmemory InMemoryLedgerStoreContractTest`. Expected: `cannot find symbol ... DecisionLedger`.
- [ ] **Step 3: Implement.** Per (`regime`, `eventIdentity`) keep the ordered chain and the head seq; per (`eventIdentity`, `decisionContextId`, `inputDigest`) keep the stored record (the idempotency key of spec 6.3). `commitDecisions` first computes every outcome against a working copy of the chains, throws `DecisionHeadConflictException` before touching real state if any non-redelivery decision's `expectedHeadSeq` differs from its chain head (taking earlier decisions of the same batch into account), and only then applies all changes, allocating ids from a counter, and completes the outbox item.
- [ ] **Step 4: Run green.** Same command. Expected: `Tests run: 24, Failures: 0, Errors: 0, Skipped: 0` (11 ingest and 13 decision tests).
- [ ] **Step 5: Commit.** `./mvnw -B -ntp -q spotless:apply`, `git add -A`, `git commit -m "feat(ports): add LedgerStore decisions with head compare-and-set" -m "Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"`.

### Task 11 (T11): `LedgerStore` part 3: lease, delivery, in-flight guard and fencing

This task holds the ledger-level building blocks of I1, I2 and I9. They run only against the in-memory adapter in P0 and do not count as evidence for those invariants (spec 14.1); P3 runs the same suite against PostgreSQL.

**Files:**
- Create (ports): `regpack-ports/src/main/java/dev/regpack/ports/ledger/LeaseLedger.java`, `DeliveryLedger.java`, `DuplicateKeyException.java`
- Create (testkit): `regpack-testkit/src/main/java/dev/regpack/testkit/contract/LeaseDeliveryLedgerContract.java`
- Modify: `LedgerStore.java` (add `LeaseLedger`, `DeliveryLedger`), `LedgerFixtures.java`, `InMemoryLedgerStore.java`, `AbstractLedgerContractTest.java`

**Interfaces:** `LeaseLedger`, `DeliveryLedger`, `DuplicateKeyException` exactly as in the port contracts. Fixtures added:
```java
public static LeaseLedger.EndpointKey endpoint(String name);   // ("SIMULATOR","ARM","LEI-"+name,"NCA-"+name)
public static DeliveryLedger.DeliveryId queuedDelivery(DeliveryLedger ledger, LeaseLedger.EndpointKey e,
                                                       String businessKey, long versionSeq);
    // createDelivery(new NewDelivery(e, 1, businessKey, versionSeq)) then transition PREPARED -> QUEUED (asserts APPLIED)
public static DeliveryLedger.SendingResult begin(DeliveryLedger ledger, DeliveryLedger.DeliveryId d,
                                                 LeaseLedger.LeaseToken t);
    // allocateAttemptId(), then beginSending(d, id, t, new AttemptIntent(a fixed sha256, "file.xml")); returns the result
```
Adapter semantics pinned by the tests (the tests are the specification; this is the algorithm summary): ttl is a parameter of `acquire`; `expiresAt = now + ttl`; a lease is held while it is neither released nor expired (`now < expiresAt`); `acquire` on a held lease returns `Held`; a successful `acquire` increments that endpoint's counter, takes the token (`restoreEpoch`, counter), and moves every `SENDING` delivery of the endpoint to `IN_DOUBT` in the same critical section; `declareRestoreEpoch(e)` requires `e > restoreEpoch()` (else `IllegalArgumentException`), voids every current lease (`currentLease` is empty for every endpoint, so no token issued before the declaration is current: `renew` returns false, `release` is ignored, `beginSending` gives `STALE_LEASE`, `markCommitIssued` returns false) and resets every endpoint's counter, so the next acquisition, allowed immediately without waiting for expiry, gets the token (`e`, 1); `renew` is true only for the current token while `now < expiresAt` and then sets `expiresAt = now + ttl`; `release` with a non-current token is ignored; `allocateAttemptId` returns 1, 2, 3, ... from one counter shared by all endpoints, and an allocated id is consumed only when a `beginSending` with it returns `APPLIED`; `beginSending` checks the id (unallocated: `IllegalArgumentException`; already on an attempt: `DuplicateKeyException`) before the fence; `SendingResult` rejects an inconsistent status and attempt combination in its constructor. Every `beginSending` in the tests below allocates an id first, through `LedgerFixtures.begin` or in the test body.

- [ ] **Step 1: Write the failing contract tests** (`LeaseDeliveryLedgerContract`; endpoint `E1 = endpoint("1")`, ttl 60 s, `clock()` starts at 2026-10-09T08:00:00Z):
  - `firstAcquireGrantsTokenOneInEpochZero`: `acquire(E1, "A", 60s)` is `Granted` with token `LeaseToken(0, 1)`, holder `A`, `expiresAt` = start + 60 s; `currentLease(E1)` equals the grant.
  - `secondAcquireWhileHeldIsRefused`: `acquire(E1, "B", 60s)` is `Held("A", start + 60 s)`.
  - `leaseExpiresAtTtlBoundary`: after `advance(59.999999 s)` acquire is `Held`; after a further `advance(1 microsecond)` (exactly 60 s) acquire by `B` is `Granted` with token `(0, 2)`.
  - `renewExtendsOnlyForTheCurrentToken`: renew at +30 s returns true and `expiresAt` becomes +90 s; after expiry and takeover by `B`, renew with the old token returns false; renew of the current token after its expiry but before takeover returns false.
  - `cleanReleaseAllowsImmediateAcquireAndTokenStillIncrements`: after `release(E1, t1)` acquire by `B` is `Granted` with `(0, 2)`; `release(E1, t1)` by the old holder afterwards does not change `currentLease`.
  - `restoreEpochRaisesTokenPairAndNoOldTokenEqualsNewOne`: tokens `(0,1)` then `(0,2)`; `declareRestoreEpoch(1)`; the next acquisition is `(1, 1)`; `(1,1)` is not equal to any earlier token and compares greater than `(0,2)`; `declareRestoreEpoch(1)` again and `declareRestoreEpoch(0)` throw `IllegalArgumentException`; `restoreEpoch()` is 1.
  - `restoreThenZombieStaleTokenIsFencedEvenBeforeExpiry` (the restore-then-zombie case of spec 14.4): holder `A` takes `t1 = (0,1)` on `E1`; a `QUEUED` delivery `D1` goes through `begin` to `SENDING` with attempt `a1`; a second `QUEUED` delivery `D2` (another business key) exists; the lease is unexpired and the clock is not advanced; `declareRestoreEpoch(1)`. Then, with `t1`: `begin(D2)` gives `STALE_LEASE` and leaves `D2` `QUEUED`, `markCommitIssued(a1, t1)` returns false and `attempt(a1).commitIssued()` stays false, `renew(E1, t1, 60s)` returns false, and `currentLease(E1)` is empty. `B` then acquires at once and gets `(1,1)`, `t1` stays fenced, and `D1` is `IN_DOUBT`.
  - `acquireAfterRestoreIsImmediateAndStartsANewEpochPair`: after `declareRestoreEpoch(1)`, `acquire` by `B` at the same instant is `Granted` with `(1,1)`; the earlier token `(0,1)` still fails `renew`; `acquire` by `C` after expiry gives `(1,2)`.
  - `leaseIsPerEndpoint`: acquiring `E2` while `E1` is held is `Granted` with `(0, 1)`.
  - `createDeliveryRejectsDuplicateKey`: creating (`E1`, epoch 1, `bk`, version 1) twice throws `DuplicateKeyException`; version 2 succeeds; the new delivery is `PREPARED`.
  - `beginSendingRequiresCurrentLeaseAndQueuedState`: with the lease held and a `QUEUED` delivery: current token gives `APPLIED` with an attempt id, the delivery is `SENDING` and the attempt has `commitIssued` false; a `PREPARED` delivery gives `STATE_MISMATCH`; a stale token (from before a takeover) on a `QUEUED` delivery gives `STALE_LEASE` and leaves it `QUEUED`; with both wrong the result is `STALE_LEASE` (the fence is checked first). `attempt()` is present exactly when the status is `APPLIED`.
  - `inFlightGuardAllowsOnlyOneDeliveryPerBusinessKey`: versions 1 and 2 of the same (`E1`, `bk`) are `QUEUED`; version 1 `beginSending` is `APPLIED`; version 2 is `IN_FLIGHT_CONFLICT`; after version 1 moves `SENDING -> AWAITING_ACK -> ACCEPTED_PROVISIONAL`, version 2 is `APPLIED`; a different business key on `E1` and the same business key on `E2` are never blocked.
  - `inFlightGuardAlsoCoversAwaitingAckAndInDoubt`: with version 1 `AWAITING_ACK`, version 2 `beginSending` is `IN_FLIGHT_CONFLICT`; after version 1 moves to `IN_DOUBT` it still is; a `transition` of a second delivery from `REJECTED` into `IN_DOUBT` while another delivery of the key is in flight returns `IN_FLIGHT_CONFLICT`.
  - `transitionIsCompareAndSetOnExpectedState`: `transition(id, QUEUED, PREPARED)` on a `PREPARED` delivery returns `STATE_MISMATCH` and leaves it unchanged; a correct expectation returns `APPLIED`.
  - `transitionIntoSendingMustUseBeginSending`: `transition(id, QUEUED, SENDING)` throws `IllegalArgumentException`.
  - `markCommitIssuedIsFencedCompareAndSet`: with the current token on a `SENDING` attempt it returns true and `attempt(id).commitIssued()` is true; calling it again returns true (idempotent); with a stale token it returns false; for an attempt whose delivery is no longer `SENDING` it returns false.
  - `takeoverMovesSendingDeliveriesToInDoubtAndFencesTheOldHolder`: holder `A` (token `t1`) has a `SENDING` delivery with an attempt and has "checked its token" but not yet committed the marker; `advance(61 s)`; `B` acquires `t2`; the delivery is `IN_DOUBT`; `markCommitIssued(attempt, t1)` returns false and `attempt.commitIssued()` stays false; `beginSending` of another `QUEUED` delivery with `t1` is `STALE_LEASE`. This is the stall-after-token-check case of spec 14.4.
  - `concurrentAcquireGrantsExactlyOneHolder`: 16 threads released by one `CountDownLatch` each call `acquire(E1, "h"+i, 60s)`; exactly one result is `Granted`, 15 are `Held`.
  - `concurrentBeginSendingForSameBusinessKeyAppliesExactlyOnce`: eight `QUEUED` deliveries (versions 1 to 8) of one business key, one valid token, eight threads each allocate an id and `beginSending` a different version: exactly one `APPLIED`, seven `IN_FLIGHT_CONFLICT`, and exactly one delivery is `SENDING`.
  - `attemptIdsAreStrictlyIncreasingAndStableUntilUsed`: three `allocateAttemptId` calls give 1, 2, 3 even when the `beginSending` that used the second one returned `STALE_LEASE`; an allocated id is accepted by a later valid `beginSending`.
  - `allocatedButUnusedAttemptIdLeavesNoAttemptRow`: allocate, call `beginSending` with a stale token (`STALE_LEASE`): `attempt(id)` is empty and the delivery is still `QUEUED`; the same id then succeeds with the right token (the orphan-intent case of spec 11.5 B.5).
  - `beginSendingRejectsUnallocatedAndReusedAttemptIds`: an id that was never allocated throws `IllegalArgumentException`; an id already stored on an attempt throws `DuplicateKeyException`; both leave the delivery `QUEUED` (the second delivery is the one used for the reuse).
- [ ] **Step 2: Run red.** `scripts/t.sh regpack-inmemory InMemoryLedgerStoreContractTest`. Expected: `cannot find symbol ... LeaseLedger`.
- [ ] **Step 3a: Implement the lease sub-port.** Write the `LeaseLedger` and `DeliveryLedger` types (the delivery methods may throw `UnsupportedOperationException` for now) and the lease half of `InMemoryLedgerStore` under the same single lock. Run `scripts/t.sh regpack-inmemory 'InMemoryLedgerStoreContractTest#firstAcquireGrantsTokenOneInEpochZero+secondAcquireWhileHeldIsRefused+leaseExpiresAtTtlBoundary+renewExtendsOnlyForTheCurrentToken+cleanReleaseAllowsImmediateAcquireAndTokenStillIncrements+leaseIsPerEndpoint+concurrentAcquireGrantsExactlyOneHolder+restoreEpochRaisesTokenPairAndNoOldTokenEqualsNewOne+acquireAfterRestoreIsImmediateAndStartsANewEpochPair'`. Expected: `Tests run: 9, Failures: 0`.
- [ ] **Step 3b: Implement the delivery sub-port.** Delivery creation, `transition`, `allocateAttemptId`, `beginSending`, `markCommitIssued`, the in-flight guard, the takeover move and the restore voiding, under the same lock. The in-flight set is `{SENDING, AWAITING_ACK, IN_DOUBT}`; the guard is evaluated for `beginSending` and for any `transition` whose target is in the set and whose source is not. Run the whole class (Step 4).
- [ ] **Step 4: Run green.** Same command as Step 2. Expected: `Tests run: 46, Failures: 0, Errors: 0, Skipped: 0` (11 ingest, 13 decision and 22 lease and delivery tests).
- [ ] **Step 5: Commit.** `./mvnw -B -ntp -q spotless:apply`, `git add -A`, `git commit -m "feat(ports): add LedgerStore lease, delivery, in-flight guard and fencing" -m "Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"`.

### Task 12 (T12): Contract suites have teeth (flawed adapters)

A contract suite that passes against a broken adapter proves nothing and would give P3 false confidence (review focus RF1). This task adds deliberate faults to the in-memory adapters (test doubles only) and a meta-test that proves the suites catch each fault by name.

**Files:**
- Modify: `InMemoryEvidenceStore.java`, `InMemoryLedgerStore.java` (add a nested `Flaw` enum and a second constructor)
- Create (inmemory tests, package `dev.regpack.inmemory.mutants`, all annotated `@Tag("mutant")`): `OverwriteOnDuplicateEvidenceContractTest`, `ShortenRetentionEvidenceContractTest`, `IgnoreLeaseTokenLedgerContractTest`, `NoInFlightGuardLedgerContractTest`, `NonAtomicDecisionsLedgerContractTest`, `RedeliveryReingestsLedgerContractTest`, `NoTakeoverInDoubtLedgerContractTest`, `AbaAsNoopLedgerContractTest`, `IgnoreRestoreEpochLedgerContractTest`, `IdempotencyIgnoresContextLedgerContractTest`
- Create: `regpack-inmemory/src/test/java/dev/regpack/inmemory/ContractSuiteTeethTest.java`

**Interfaces:**
```java
// InMemoryEvidenceStore
public enum Flaw { NONE, OVERWRITE_ON_DUPLICATE, ALLOW_RETENTION_SHORTENING }
public InMemoryEvidenceStore(Clock clock, Flaw flaw);
// InMemoryLedgerStore
public enum Flaw { NONE, IGNORE_LEASE_TOKEN, NO_IN_FLIGHT_GUARD, NON_ATOMIC_DECISIONS,
                   REINGEST_ON_REDELIVERY, NO_TAKEOVER_IN_DOUBT, ABA_AS_NOOP,
                   IGNORE_RESTORE_EPOCH, IDEMPOTENCY_KEY_IGNORES_CONTEXT }
public InMemoryLedgerStore(Clock clock, Flaw flaw);
```
Flaw behaviours: `OVERWRITE_ON_DUPLICATE` replaces the stored object on a put with different bytes instead of throwing; `ALLOW_RETENTION_SHORTENING` lets `extendRetention` lower the date; `IGNORE_LEASE_TOKEN` accepts any token in `beginSending` and `markCommitIssued`; `NO_IN_FLIGHT_GUARD` never returns `IN_FLIGHT_CONFLICT`; `NON_ATOMIC_DECISIONS` applies decisions in order and does not roll back when a later one conflicts; `REINGEST_ON_REDELIVERY` treats a redelivered batch as new (second outbox item, checkpoint overwritten); `NO_TAKEOVER_IN_DOUBT` skips the `SENDING -> IN_DOUBT` move on acquire; `ABA_AS_NOOP` answers `NOOP_SAME_VERSION_SAME_HASH` for a new version whose hash equals any earlier version's; `IGNORE_RESTORE_EPOCH` makes `declareRestoreEpoch` change only the epoch number and leave every lease in place; `IDEMPOTENCY_KEY_IGNORES_CONTEXT` looks a decision up by (`eventIdentity`, `inputDigest`) only.

- [ ] **Step 1: Write the failing meta-test** `ContractSuiteTeethTest#everyFlawIsCaughtByItsNamedContractTests`. For each row of this table it runs the mutant class with the JUnit Platform Launcher (`LauncherFactory.create().execute(request, new SummaryGeneratingListener())`, request built from `selectClass(mutant)`), collects the method names of failed tests from `summary.getFailures()` (`MethodSource.getMethodName()`), and asserts that every expected name is among them; it collects all misses and asserts the list of misses is empty:

  | Mutant class | Expected failing tests |
  |---|---|
  | `OverwriteOnDuplicateEvidenceContractTest` | `putSameKeyDifferentBytesIsIntegrityAlarmAndKeepsOriginal` |
  | `ShortenRetentionEvidenceContractTest` | `retentionCanOnlyBeExtended` |
  | `IgnoreLeaseTokenLedgerContractTest` | `beginSendingRequiresCurrentLeaseAndQueuedState`, `markCommitIssuedIsFencedCompareAndSet`, `takeoverMovesSendingDeliveriesToInDoubtAndFencesTheOldHolder` |
  | `NoInFlightGuardLedgerContractTest` | `inFlightGuardAllowsOnlyOneDeliveryPerBusinessKey`, `concurrentBeginSendingForSameBusinessKeyAppliesExactlyOnce` |
  | `NonAtomicDecisionsLedgerContractTest` | `staleExpectedHeadSeqIsRejectedAndRollsBackWholeBatch` |
  | `RedeliveryReingestsLedgerContractTest` | `redeliveredBatchIsNoopAndReturnsOriginalResult` |
  | `NoTakeoverInDoubtLedgerContractTest` | `takeoverMovesSendingDeliveriesToInDoubtAndFencesTheOldHolder` |
  | `AbaAsNoopLedgerContractTest` | `abaProducesThreeVersions` |
  | `IgnoreRestoreEpochLedgerContractTest` | `restoreThenZombieStaleTokenIsFencedEvenBeforeExpiry` |
  | `IdempotencyIgnoresContextLedgerContractTest` | `sameEventSameDigestNewContextSupersedes` |

  Each mutant class is a few lines: it extends `AbstractLedgerContractTest` (or `AbstractEvidenceContractTest`) and overrides `create` to return the adapter with the named `Flaw`. The `@Tag("mutant")` annotation keeps them out of the normal run (the `excludedGroups` property set in T2); the meta-test selects them explicitly.
- [ ] **Step 2: Run red.** `scripts/t.sh regpack-inmemory ContractSuiteTeethTest`. Expected: compilation error `cannot find symbol ... Flaw`.
- [ ] **Step 3: Implement** the `Flaw` constructors and behaviours (each flaw is one conditional in the adapter, commented `// test double only: deliberate flaw for ContractSuiteTeethTest`).
- [ ] **Step 4: Run green and confirm the mutants are excluded from the normal run.** `scripts/t.sh regpack-inmemory ContractSuiteTeethTest` expects `Tests run: 1, Failures: 0`. Then `./mvnw -B -ntp -q spotless:apply` and `./mvnw -B -ntp -pl regpack-inmemory -am verify`; expected `BUILD SUCCESS` and no test named in a `mutants` package in the surefire summary. Sanity: temporarily weaken one contract assertion (for example delete the `IN_FLIGHT_CONFLICT` assertion in `inFlightGuardAllowsOnlyOneDeliveryPerBusinessKey`), rerun the meta-test and see it fail naming `NoInFlightGuardLedgerContractTest`; restore the assertion.
- [ ] **Step 5: Commit.** `./mvnw -B -ntp -q spotless:apply`, `git add -A`, `git commit -m "test(inmemory): prove contract suites fail against deliberately flawed adapters" -m "Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"`.

### Task 13 (T13): Connector SPI, source contract and in-memory source connector

**Files:**
- Create (ports, `dev/regpack/ports/connector/`): `Connector.java`, `ConnectorSpec.java`, `ConnectorKind.java`, `CheckResult.java`, `SourceConnector.java`
- Create (testkit): `regpack-testkit/src/main/java/dev/regpack/testkit/contract/SourceConnectorContract.java`
- Create (inmemory): `regpack-inmemory/src/main/java/dev/regpack/inmemory/InMemorySourceConnector.java`
- Test: `regpack-inmemory/src/test/java/dev/regpack/inmemory/InMemorySourceConnectorContractTest.java`

**Interfaces:** the connector SPI of the port contracts. In this task `Connector` is `sealed ... permits SourceConnector`; T14 and T15 add `Transport` and `EnrichmentSource` to the `permits` clause (a sealed hierarchy may grow).
```java
public interface SourceConnectorContract {
  SourceConnector connector(int batchCount);     // fresh connector with that many one-line NDJSON batches, media type application/x-ndjson
  void armFailureOnNextRead();                   // the next next(n) on the connector under test throws a RuntimeException (fault injection)
}
public final class InMemorySourceConnector implements SourceConnector {
  public InMemorySourceConnector(String name, String stream, String mediaType, List<byte[]> payloads);
  public void armFailureOnNextRead();            // the next BatchStream.next(n) throws a RuntimeException, once
  public long acknowledgedSeq();                 // greatest acknowledged position across all streams; 0 before any acknowledgement; test and diagnostics accessor, not part of the SourceConnector port
  // batch i (1-based): seq i; payload payloads[i-1]; manifest(recordCount = non-blank lines for NDJSON else 1,
  // sha256 of the bytes, no control totals); sourceCoordinates "mem:<stream>:<i>"; checkpoint = (stream, 8-byte big-endian i)
}
```
Semantics to implement: the source keeps `acknowledgedSeq` (the greatest acknowledged position across all its streams); `read` returns a stream that starts after `max(acknowledgedSeq, resumeFrom position)`; `next(credits)` returns at most `credits` batches (a negative value is an `IllegalArgumentException`); `acknowledge(cp)` for a position greater than the greatest position this stream has delivered is an `IllegalArgumentException`, otherwise it raises `acknowledgedSeq`; `exhausted()` is true once the last batch has been delivered; a closed stream throws `IllegalStateException` from `next`.

- [ ] **Step 1: Write the failing contract tests** (`SourceConnectorContract`):
  - `nextNeverReturnsMoreThanCredits`: with 5 batches, `next(0)` is empty, `next(2)` has 2, `next(10)` has 3, and `exhausted()` is then true.
  - `batchSeqIsStrictlyIncreasingFromOne`.
  - `manifestHashMatchesPayloadAndCountsRecords`: `manifest().sha256()` equals `Sha256.of(payload.bytes())` and `recordCount()` is 1.
  - `unacknowledgedBatchesAreRedeliveredOnNewRead`: read all five, acknowledge batch 2's checkpoint, open a new stream with no `resumeFrom`: it delivers seq 3, 4, 5.
  - `resumeFromCheckpointSkipsEarlierBatches`: with no acknowledgement, `read` with `resumeFrom` = batch 3's checkpoint delivers seq 4, 5.
  - `acknowledgeBeyondDeliveredIsRejected`: obtain batch 4's checkpoint from one stream; on a fresh stream that has delivered only two batches, `acknowledge` of that checkpoint throws `IllegalArgumentException`.
  - `discoverListsTheStreamWithStableKeys`: one stream whose `stableKeyFields` is non-empty.
  - `checkReportsOkAndSpecDeclaresSourceKind`.
  - `closedStreamRejectsFurtherReads`.
  - `failureMidStreamResumesFromCheckpointWithoutLossOrDuplicate`: with 5 batches, read 2 and acknowledge batch 2, call `armFailureOnNextRead()`, `next(1)` throws, close the stream; a new read delivers seq 3, 4, 5 exactly once each.
  `InMemorySourceConnectorContractTest` adds, outside the shared contract, `acknowledgedSeqTracksTheGreatestAcknowledgedPosition` (0 initially; 2 after acknowledging batch 2's checkpoint; acknowledging batch 1's checkpoint afterwards leaves it 2).
- [ ] **Step 2: Run red.** `scripts/t.sh regpack-inmemory InMemorySourceConnectorContractTest`. Expected: `cannot find symbol ... class SourceConnector`.
- [ ] **Step 3: Implement** the SPI types and the adapter as described.
- [ ] **Step 4: Run green.** Same command. Expected: `Tests run: 11, Failures: 0, Errors: 0, Skipped: 0` (10 contract tests and 1 adapter test).
- [ ] **Step 5: Commit.** `./mvnw -B -ntp -q spotless:apply`, `git add -A`, `git commit -m "feat(ports): add connector SPI, source contract and in-memory source connector" -m "Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"`.

### Task 14 (T14): `Transport`, the commit gate and the destination simulator skeleton

The simulator must be structured so that the spec 14.3 scenarios can be added later by adding `Scenario` implementations, not by changing the simulator. P0 implements two `Scenario` implementations (`ACPT`, `SILENCE_RECEIVED_UNANSWERED`) and two mechanisms (the endpoint-clock offset and the T19 fault injection), and catalogues all fourteen.

**Files:**
- Modify: `Connector.java` (`permits SourceConnector, Transport`)
- Create (ports): `regpack-ports/src/main/java/dev/regpack/ports/connector/Transport.java`
- Create (testkit): `regpack-testkit/src/main/java/dev/regpack/testkit/sim/DestinationSimulator.java`, `Scenario.java`, `ScenarioContext.java`, `ScenarioCatalogue.java`, `Scenarios.java`, `regpack-testkit/src/main/java/dev/regpack/testkit/contract/TransportContract.java`
- Test: `regpack-testkit/src/test/java/dev/regpack/testkit/sim/DestinationSimulatorTransportContractTest.java`, `ScenarioCatalogueTest.java`

**Interfaces:** `Transport` exactly as in the port contracts.
```java
public interface Scenario {
  enum Disposition { ACCEPT, DROP_SILENTLY, REJECT_DUPLICATE }
  String name();
  Disposition onReceive(ReceivedFile file, ScenarioContext context);  // called once per file whose gate answered true and whose fileId is new
  record ReceivedFile(String fileId, String fileName, Sha256 sha256, byte[] bytes, String mediaType,
                      Instant receivedAt) {}                           // bytes defensively copied
}
public interface ScenarioContext {
  Instant endpointNow();                                               // the endpoint's own clock
  List<Scenario.ReceivedFile> earlierFiles();                          // accepted files in arrival order, excluding the current one
  void emitAckAt(Instant visibleAt, String fileId, String status, Map<String, String> details);
}
public final class Scenarios {
  public static Scenario acpt(Duration delay);        // one acknowledgement {"fileId", "status":"ACPT"} visible after delay; returns ACCEPT
  public static Scenario silence();                   // the file is received and never answered; returns ACCEPT
}
public final class ScenarioCatalogue {
  public enum Status { IMPLEMENTED, PLANNED }
  public enum Kind { SCENARIO, MECHANISM }
  public record Entry(String name, Kind kind, Status status, String owningPlan, String specSource) {}
  public static List<Entry> all();                    // fixed order, 14 entries (below)
  public static Optional<Entry> byName(String name);
}
public final class DestinationSimulator implements Transport {
  public DestinationSimulator(Clock platformClock, Clock endpointClock, Scenario scenario);
  public static DestinationSimulator withScenario(ManualClock clock, Scenario scenario);   // endpoint clock = the same clock
  public void failNextSendBeforeFirstByte();
  public List<Scenario.ReceivedFile> receivedFiles();
  public enum ProbeProof { RETAINS_DELIVERED, NONE }
  public DestinationSimulator withProbeProof(ProbeProof proof);       // default RETAINS_DELIVERED (spec 9.2)
}
public interface TransportContract {
  Transport transport();                 // ACPT scenario with a 1 s delay, endpoint clock equal to the platform clock
  ManualClock clock();
  void armFailureBeforeFirstByte();      // the next send fails before anything becomes visible
}
```
Catalogue entries, in order (name, status, owning plan; kind `SCENARIO` except for the two `MECHANISM` entries `SKEWED_CLOCK` and `FAULT_INJECTION`, whose `specSource` states that P8 adds the scenario content, a skewed-clock scenario factory, and that the fault matrix is P4): `ACPT` IMPLEMENTED P0; `PDNG_THEN_ACPT` PLANNED P8; `PDNG_TURNS_RJCT_DAY8` PLANNED P8; `RJCT_WITH_CON_CODES` PLANNED P8; `RCVD` PLANNED P8; `FILE_RJCT` PLANNED P8; `FILE_CRPT` PLANNED P8; `FILE_INCF` PLANNED P8; `DUPLICATE_TRN` PLANNED P8; `SILENCE_NOT_RECEIVED` PLANNED P8; `SILENCE_RECEIVED_UNANSWERED` IMPLEMENTED P0; `FEEDBACK_ANOMALIES` PLANNED P8 (spec 9.4); `SKEWED_CLOCK` IMPLEMENTED P0 (endpoint clock offset); `FAULT_INJECTION` IMPLEMENTED P0 (mechanism: T19 wraps any port; the fault matrix is P4).
Simulator semantics: `send` assigns the file to the destination only after `gate.permitFirstVisibleByte()` returned true (a throwing gate leaves nothing visible and the exception propagates), then checks for an existing `fileId` (no clobber: `REJECTED_DUPLICATE`, first copy untouched; the gate has been called once by then); a failure armed with `failNextSendBeforeFirstByte` returns `FAILED_BEFORE_FIRST_BYTE` and never calls the gate. After the gate answered true and the simulator's own `fileId` check passed, the scenario is asked. `ACCEPT` stores the file (probe `PRESENT`, listed in `receivedFiles()`, `send` returns `DELIVERED`). `DROP_SILENTLY` returns `DELIVERED` to the sender but stores nothing (not in `receivedFiles`, acks emitted for it are discarded, the probe sees nothing). `REJECT_DUPLICATE` returns `REJECTED_DUPLICATE` and leaves earlier files untouched. With `ProbeProof.NONE`, `probe` of a file that is not stored returns `UNKNOWABLE` instead of `ABSENT`; a stored file is still `PRESENT`. An accepted file's scenario `emitAckAt` calls queue `RawAck`s with increasing positions (from 1), JSON bytes `Json.canonical({"fileId","status", details...})`, media type `application/json`, and `endpointAsOf` equal to `visibleAt` on the endpoint clock; `pollAcks` returns queued acks whose `visibleAt` is not after the platform clock's now and whose position exceeds the cursor, at most `max`, with `next` = the last returned position (or the input cursor if none). `ackModel()` is `ASYNC_FILE`; `spec().kind()` is `DESTINATION`.

- [ ] **Step 1: Write the failing tests.** `TransportContract` default tests (files built with `Sha256.of(bytes)`):
  - `sendDeliversAndProbeShowsPresentWithHash`: `send` is `DELIVERED`; `probe(fileId)` is `PRESENT` with the file's sha256.
  - `gateIsCalledExactlyOnceBeforeAnythingIsVisible`: a gate that increments a counter and, inside the call, asserts `probe(fileId).status() == ABSENT`; after `send` the counter is 1 and the probe is `PRESENT` (review focus RF24).
  - `refusedGateLeavesNothingVisible`: a gate returning false gives `ABORTED_BY_GATE` and `probe` is `ABSENT`.
  - `duplicateFileIdIsRejectedNoClobber`: a second `send` with the same `fileId` and different bytes gives `REJECTED_DUPLICATE`; `probe` still shows the first file's sha256.
  - `failureBeforeFirstByteDoesNotInvokeGate`: after `armFailureBeforeFirstByte()`, `send` gives `FAILED_BEFORE_FIRST_BYTE`, the gate counter is 0 and the probe is `ABSENT`.
  - `pollAcksHonoursCursorAndAckDelay`: right after a delivered send `pollAcks(start, 10)` is empty; after `clock().advance(1s)` it holds one ack for the `fileId`; polling again with `next` is empty.
  - `pollAcksNeverReturnsMoreThanMaxAndRepollYieldsNothingSeen`: three delivered files, `advance(1s)`; `pollAcks(start, 2)` returns 2; polling with its `next` returns the third; a third poll is empty.
  - `duplicateFileIdInvokesTheGateOnceAndIsRejected`: a second `send` with the same `fileId` and a counting gate that returns true gives `REJECTED_DUPLICATE`, the counter is 1, and `probe` still shows the first sha256.
  - `throwingGateLeavesNothingVisibleAndPropagates`: a gate that throws `IllegalStateException`: `send` throws the same exception and `probe` is `ABSENT` (the simulator's concrete test class overrides the test to also assert `receivedFiles()` is empty).
  - `specAndAckModelAreDeclared`.
  `DestinationSimulatorTransportContractTest` adds: `silenceNeverProducesAcksButFileStaysPresent` (silence scenario; after `advance(30 days)` no ack and `probe` is `PRESENT`), `endpointAsOfComesFromTheEndpointClock` (platform clock at T, endpoint clock a `ManualClock` two hours ahead: the ack's `endpointAsOf` is the visible-at instant plus two hours), `receivedFilesRecordsOnlyTheFirstCopy`, and three tests with throw-away inline scenarios: `scenarioCanDropAFileSilentlyAndProbeIsAbsent` (`DROP_SILENTLY`: `send` is `DELIVERED`, `receivedFiles()` is empty, `probe` is `ABSENT`, no ack after `advance(30 days)`); `scenarioCanReadBytesAndEarlierFiles` (a scenario that returns `REJECT_DUPLICATE` when `bytes` equal an earlier file's bytes under another `fileId`: the second send is `REJECTED_DUPLICATE`); `probeProofNoneMakesAbsentUnknowable` (`withProbeProof(NONE)`: `probe` of an unstored file is `UNKNOWABLE`, of a stored file `PRESENT`). `ScenarioCatalogueTest#catalogueNamesEverySection143Scenario`: the 14 names in the order above; the IMPLEMENTED set is exactly `ACPT`, `SILENCE_RECEIVED_UNANSWERED`, `SKEWED_CLOCK`, `FAULT_INJECTION`; the `MECHANISM` set is exactly `SKEWED_CLOCK` and `FAULT_INJECTION`; every PLANNED entry's `owningPlan` is `P8`.
- [ ] **Step 2: Run red.** `scripts/t.sh regpack-testkit 'DestinationSimulatorTransportContractTest,ScenarioCatalogueTest'`. Expected: `cannot find symbol ... class Transport`.
- [ ] **Step 3: Implement** `Transport` (and extend the `permits` clause) and the simulator classes as described.
- [ ] **Step 4: Run green.** Same command. Expected: `Tests run: 17, Failures: 0, Errors: 0, Skipped: 0` (16 in the simulator test: 10 contract plus 3 existing extra plus 3 scenario-hook tests; 1 in the catalogue test).
- [ ] **Step 5: Commit.** `./mvnw -B -ntp -q spotless:apply`, `git add -A`, `git commit -m "feat(testkit): add Transport port, commit gate and destination simulator skeleton" -m "Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"`.

### Task 15 (T15): `EnrichmentSource` lookup kind and deterministic enrichment fakes

**Files:**
- Modify: `Connector.java` (`permits SourceConnector, Transport, EnrichmentSource`)
- Create (ports): `regpack-ports/src/main/java/dev/regpack/ports/connector/EnrichmentSource.java`, `LookupUnavailableException.java`
- Create (testkit): `regpack-testkit/src/main/java/dev/regpack/testkit/enrichment/ScriptedEnrichmentSource.java`, `ProviderApiSimulator.java`, `DatabaseLookupFake.java`, `regpack-testkit/src/main/java/dev/regpack/testkit/contract/LookupContract.java`
- Test: `regpack-testkit/src/test/java/dev/regpack/testkit/enrichment/ScriptedEnrichmentSourceLookupContractTest.java`, `EnrichmentFakesTest.java`

**Interfaces:** `EnrichmentSource` and `LookupUnavailableException(Kind, String errorCode, Optional<Duration> retryAfter)` exactly as in the port contracts (the exception carries `retryAfter` as a `transient` field behind an accessor, Convention 7).
```java
public final class ScriptedEnrichmentSource implements EnrichmentSource {
  public static Builder builder(ManualClock clock, String name);
  public int requestCount();
  public void armTimeout();                                                  // the next lookup advances the clock by limits.timeout() then throws TIMEOUT
  public void armRateLimit(Duration retryAfter);                             // the next lookup throws RATE_LIMITED carrying retryAfter
  public static final class Builder {
    public Builder limits(EnrichmentSource.Limits limits);
    public Builder hit(String key, Map<String, Object> value);                 // asOf = the request context's asOf
    public Builder staleHit(String key, Map<String, Object> value, Instant asOf);
    public Builder miss(String key);                                           // asOf = the request context's asOf
    public Builder failure(String key, String errorCode, boolean retryable);   // asOf = the clock's now at the start of the request
    public Builder latency(Duration perRequest);                               // advances the ManualClock on every lookup; never sleeps
    public Builder timeoutOnKey(String key);                                   // a request containing the key advances the clock by limits.timeout() then throws TIMEOUT
    public Builder rateLimitedOnRequest(int requestNumber, Duration retryAfter);   // the nth request (1-based) throws RATE_LIMITED
    public ScriptedEnrichmentSource build();
  }
}
public final class ProviderApiSimulator { public static ScriptedEnrichmentSource.Builder builder(ManualClock clock); }
    // name "provider-api-simulator"; limits (100 keys, 2 s, 10 requests/s)
public final class DatabaseLookupFake { public static ScriptedEnrichmentSource.Builder builder(ManualClock clock); }
    // name "database-lookup-fake"; limits (1000 keys, 500 ms, 0 = unlimited)
public interface LookupContract {
  EnrichmentSource source();    // scripted: hits K1 {"lei":"LEI-K1"} and K2 {"lei":"LEI-K2"}, miss M1, failure F1 ("E_UPSTREAM", retryable), stale hit S1 (asOf three days before the context), limits (3 keys, 2 s, 10)
  ManualClock clock();                  // starts at 2026-10-09T08:00:00Z, the context's asOf
  EnrichmentSource.Context context();   // field "client.lei", asOf 2026-10-09T08:00:00Z, batch BatchId.of(Sha256.ofUtf8("B1"), 0)
  void armTimeout();                    // the next lookup throws LookupUnavailableException of kind TIMEOUT
  void armRateLimit(Duration retryAfter);   // the next lookup throws RATE_LIMITED with that retry-after (both hooks delegate to the ScriptedEnrichmentSource under test)
}
```
Canonical bytes and response hashes of the three outcome shapes are as in "Lookup semantics" of the port contracts; a `Hit`'s `canonical` is `Json.canonical(value)` as UTF-8 and `responseHash` is `Sha256.of(canonical)`. A request whose keys are not distinct, or longer than `limits().maxKeysPerRequest()`, throws `IllegalArgumentException`.

- [ ] **Step 1: Write the failing tests.** `LookupContract` default tests: `oneOutcomePerDistinctKeyInRequestOrder` (keys `[K2, M1, K1]` give outcomes of type Hit, Miss, Hit with those keys in that order); `hitCarriesCanonicalBytesAndMatchingResponseHash`; `duplicateKeysInRequestAreRejected`; `batchLargerThanLimitIsRejected` (four keys against a limit of three); `partialResultsAreAllowed` (`[K1, F1, M1]` gives a `Hit`, a `Failure` whose `key`, `errorCode` and `retryable` accessors are `F1`, `E_UPSTREAM` and true, and a `Miss`); `sameKeyAndAsOfGiveSameResponseHash`; `staleValueCarriesItsOwnOlderAsOf` (`S1`'s `asOf` is the context as-of minus three days); `missCarriesAsOfAndResponseHash` (`M1`: `asOf` equals the context as-of, `responseHash` equals `Sha256.of(canonical)`, canonical bytes equal `Json.canonical({"key":"M1","outcome":"MISS"})` as UTF-8); `failureCarriesAsOfAndResponseHash` (`F1`: `asOf` equals the clock's now before the call, which is the context as-of; the hash equals `Sha256.of(canonical)`; canonical bytes equal `Json.canonical({"errorCode":"E_UPSTREAM","key":"F1","outcome":"ERROR","retryable":true})` as UTF-8); `timeoutThrowsLookupUnavailableTimeout` (after `armTimeout()`: kind `TIMEOUT`); `rateLimitCarriesRetryAfter` (after `armRateLimit(1 s)`: kind `RATE_LIMITED` and `retryAfter` equal to `Optional.of(1 s)`); `specDeclaresLookupKind`. `ScriptedEnrichmentSourceLookupContractTest` (which keeps its four adapter-specific tests because they assert the exact clock advance) adds: `timeoutIsDeterministicViaClock` (key `T1` with `timeoutOnKey`: throws `LookupUnavailableException` of kind `TIMEOUT` and `clock().monotonicNanos()` has advanced by exactly 2_000_000_000); `rateLimitedRequestSurfacesRetryAfter` (second request limited with retry-after 1 s: the exception carries `Optional.of(1 s)` and the third request succeeds); `conflictingAnswersFromTwoSourcesAreRepresentable` (two sources scripted with different values for `K1` give different `responseHash`); `latencyAdvancesTheClockWithoutSleeping` (three requests at 250 ms advance the clock by exactly 750 ms). `EnrichmentFakesTest` checks the two factory fakes: `providerApiSimulatorHasDocumentedNameAndLimits` (name `provider-api-simulator`, limits 100 keys, 2 s, 10 requests per second) and `databaseLookupFakeHasDocumentedNameAndLimits` (`database-lookup-fake`, 1000 keys, 500 ms, 0).
- [ ] **Step 2: Run red.** `scripts/t.sh regpack-testkit 'ScriptedEnrichmentSourceLookupContractTest,EnrichmentFakesTest'`. Expected: `cannot find symbol ... class EnrichmentSource`.
- [ ] **Step 3: Implement** the port, the exception and the fakes.
- [ ] **Step 4: Run green.** Same command. Expected: `Tests run: 18, Failures: 0, Errors: 0, Skipped: 0` (12 default contract tests plus 4 scripted tests in `ScriptedEnrichmentSourceLookupContractTest`, 2 in `EnrichmentFakesTest`).
- [ ] **Step 5: Commit.** `./mvnw -B -ntp -q spotless:apply`, `git add -A`, `git commit -m "feat(testkit): add EnrichmentSource lookup kind and deterministic enrichment fakes" -m "Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"`.

### Task 16 (T16): Secrets and KMS ports with in-memory adapters

**Files:**
- Create (ports, `dev/regpack/ports/security/`): `SecretRef.java`, `SecretValue.java`, `SecretsProvider.java`, `SecretNotFoundException.java`, `KeyPurpose.java`, `KeyRef.java`, `KmsSignature.java`, `WrappedKey.java`, `Kms.java`, `KmsAccessDeniedException.java`
- Create (testkit): `regpack-testkit/src/main/java/dev/regpack/testkit/contract/KmsContract.java`
- Create (inmemory): `InMemoryKms.java`, `InMemorySecrets.java`
- Test: `regpack-ports/src/test/java/dev/regpack/ports/security/SecretRefTest.java`, `SecretValueTest.java`; `regpack-inmemory/src/test/java/dev/regpack/inmemory/InMemoryKmsContractTest.java`, `InMemorySecretsTest.java`

**Interfaces:** the secrets and KMS blocks of the port contracts; additionally `unwrap` requires `wrapped.keyId` to equal `kek.keyId` (else `IllegalArgumentException`) and authorises only the `kek`; a key id that does not exist throws `IllegalArgumentException` (checked first); an existing key that the bound identity may not use, or that is used for another purpose, throws `KmsAccessDeniedException`; a wrong `aad` or a tampered `WrappedKey` makes `Kms.unwrap` throw `IllegalArgumentException`.
```java
public final class InMemoryKms {
  public record WorkloadIdentity(String name) {}
  public static InMemoryKms create();
  public InMemoryKms createSigningKey(KeyRef key, Set<WorkloadIdentity> allowed);               // Ed25519
  public InMemoryKms createHmacKey(KeyRef key, byte[] material, Set<WorkloadIdentity> allowed);  // HmacSHA256
  public InMemoryKms createKek(KeyRef key, Set<WorkloadIdentity> allowed);                       // AES-256-GCM
  public Kms as(WorkloadIdentity identity);                  // the Kms port bound to one identity; verify() is open to every identity
}
public final class InMemorySecrets implements SecretsProvider {
  public InMemorySecrets put(String uri, byte[] value);      // returns this
}
public interface KmsContract {
  Kms signer();        // identity "signer": allowed on PACK_SIGNING "pack-key", MANIFEST_SIGNING "manifest-key" and ENVELOPE_KEK "kek-2"
  Kms application();   // identity "application": not allowed on the signing keys; allowed on TRN_HMAC "trn-key", "trn-key-2" and ENVELOPE_KEK "kek-1"
  KeyRef packKey(); KeyRef manifestKey(); KeyRef trnKey(); KeyRef trnKey2(); KeyRef kek();
  KeyRef kek2();       // ENVELOPE_KEK "kek-2": allowed to the signer identity only, not to application
}
```
This is the contract the P3 `signer` role is tested against (spec 5.2: the application identity can invoke neither signing key).

- [ ] **Step 1: Write the failing tests.** `SecretRefTest`: `parsesSchemeAndPath` (`SecretRef.parse("env://HOME")` is `("env","HOME")`; `"vault://kv/prod/sftp"` is `("vault","kv/prod/sftp")`), `rejectsUnknownSchemeAndMalformedUris` (`http://x`, `env:/x`, empty string), `acceptsEverySpecScheme` (vault, openbao, awssm, azkv, gcpsm, file, env). `SecretValueTest`: `toStringIsRedacted` (equals `SecretValue[REDACTED]`), `closeZeroesTheHeldBytes` (after `close()` both `copyBytes()` and `utf8()` throw `IllegalStateException`), `copyBytesReturnsACopy`. `InMemorySecretsTest`: `resolvesStoredSecret`, `missingSecretThrowsSecretNotFound`, `supportsOnlyTheSpecSchemes`. `KmsContract` default tests: `signAndVerifyRoundTrip` (signer signs `"manifest-bytes"` with `manifestKey`; `verify` is true for `application()` too; changing one byte of the message makes it false); `signingKeysAreDeniedToTheApplicationIdentity` (`application().sign(packKey, ...)` and `sign(manifestKey, ...)` throw `KmsAccessDeniedException`); `purposeIsEnforcedPerKey` (`signer().sign(trnKey, ...)`, `application().wrap(trnKey, ...)`, `application().hmac(packKey, ...)` and `application().hmac(kek, ...)` throw `KmsAccessDeniedException`); `unwrapWithMismatchedKekIsRejected` (wrap with `kek`, then `application().unwrap(kek, new WrappedKey("kek-2", ciphertext), aad)` throws `IllegalArgumentException`, and the same wrapped key presented under `kek2()` as the signer identity does not decrypt); `kekNotPermittedToTheApplicationIsDenied` (`application().wrap(kek2(), ...)` throws `KmsAccessDeniedException`); `hmacIsDeterministicPerKeyAndDiffersAcrossKeys`; `wrapUnwrapRoundTripWithAad` (right `aad` returns the data key; another `aad` or a changed ciphertext byte throws `IllegalArgumentException`); `wrapIsRandomizedButUnwrapsToTheSameKey` (two wraps differ, both unwrap equal); `signaturesNameKeyAndAlgorithm` (`keyId` equals the key's id, algorithm `Ed25519`); `unknownKeyIsRejected` (`IllegalArgumentException`, not `KmsAccessDeniedException`, for an unknown key id on `sign`, `hmac`, `wrap` and `unwrap`). `InMemoryKmsContractTest` adds `hmacMatchesRfc4231TestCase1`: a key created with 20 bytes of `0x0b` gives `HMAC-SHA256("Hi There")` equal to `b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7` (RFC 4231 test case 1, an external pinned value).
- [ ] **Step 2: Run red.** `scripts/t.sh regpack-inmemory 'InMemoryKmsContractTest,InMemorySecretsTest'` and `scripts/t.sh regpack-ports 'SecretRefTest,SecretValueTest'`. Expected: `cannot find symbol` for the new types.
- [ ] **Step 3: Implement** with JDK crypto only: `KeyPairGenerator.getInstance("Ed25519")` and `Signature.getInstance("Ed25519")`, `Mac.getInstance("HmacSHA256")`, `Cipher.getInstance("AES/GCM/NoPadding")` with a 12-byte random IV prepended to the ciphertext and `aad` bound as additional authenticated data. Randomness is allowed here (adapters are not rule-reachable; the module's `NoWallClockTest` forbids only wall clocks).
- [ ] **Step 4: Run green.** Same commands. Expected: inmemory `Tests run: 14, Failures: 0` (11 KMS tests including the RFC 4231 test, plus 3 secrets tests); ports `Tests run: 6, Failures: 0`.
- [ ] **Step 5: Commit.** `./mvnw -B -ntp -q spotless:apply`, `git add -A`, `git commit -m "feat(ports): add secrets and KMS ports with in-memory adapters" -m "Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"`.

### Task 17 (T17): `ReferenceData` port and in-memory adapter

**Files:**
- Create (ports): `regpack-ports/src/main/java/dev/regpack/ports/refdata/ReferenceData.java`
- Create (inmemory): `regpack-inmemory/src/main/java/dev/regpack/inmemory/InMemoryReferenceData.java`
- Test: `regpack-inmemory/src/test/java/dev/regpack/inmemory/InMemoryReferenceDataTest.java`

**Interfaces:** `ReferenceData` exactly as in the port contracts.
```java
public final class InMemoryReferenceData implements ReferenceData {
  public InMemoryReferenceData(Clock clock);
  public SnapshotId load(DatasetId dataset, List<Entry> entries, Duration maxAge);   // seals snapshot N+1 of the dataset
}
```
`load` rejects (with `IllegalArgumentException`, creating nothing) two entries for one key whose half-open validity intervals overlap, the in-memory analogue of PostgreSQL `WITHOUT OVERLAPS` (spec 8.4); `lookup` on an unknown dataset or snapshot throws `IllegalArgumentException`.

- [ ] **Step 1: Write the failing tests** (`InMemoryReferenceDataTest`): `snapshotIdsAreMonotonicPerDataset` (dataset `lei-status` loads give 1 then 2; dataset `mic` first load gives 1); `lookupHonoursHalfOpenValidityAndAnchor` (entry valid `[2026-01-01, 2026-06-01)`: trade date 2026-01-01 found, 2026-06-01 not found; with `Anchor.SUBMISSION_DATE` the submission date decides); `olderSnapshotKeepsAnsweringAsSealed` (snapshot 1 has `a=1`, snapshot 2 has `a=2` for the same key; lookups against each return their own value; `currentSnapshot` is 2); `overlappingValidityIsRejectedAtLoad` (and `currentSnapshot` for the dataset is unchanged); `adjacentIntervalsAreAccepted`; `unknownDatasetOrSnapshotFails`; `entryRejectsEmptyOrInvertedIntervalAndCopiesAttributes`; `snapshotInfoCarriesLoadTimeAndMaxAge` (`loadedAt` equals the clock's now).
- [ ] **Step 2: Run red.** `scripts/t.sh regpack-inmemory InMemoryReferenceDataTest`. Expected: `cannot find symbol ... class ReferenceData`.
- [ ] **Step 3: Implement** the port and the adapter (per dataset a list of immutable snapshots; per snapshot a map from key to a list of entries).
- [ ] **Step 4: Run green.** Same command. Expected: `Tests run: 8, Failures: 0, Errors: 0, Skipped: 0`.
- [ ] **Step 5: Commit.** `./mvnw -B -ntp -q spotless:apply`, `git add -A`, `git commit -m "feat(ports): add ReferenceData port with sealed in-memory snapshots" -m "Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"`.

### Task 18 (T18): `Executor`, `Expression` and `RegimeProvider` ports; cost-unit `MeteredExecutor`

**Files:**
- Create (ports): `dev/regpack/ports/exec/`: `Executor.java`, `RuleProgram.java`, `CostBudget.java`, `CostMeter.java`, `Trace.java`, `ExecutionResult.java`, `BudgetExceededException.java`, `RuleEvaluationException.java`; `dev/regpack/ports/expr/`: `Expression.java`, `CompiledExpression.java`, `Value.java`, `ValueType.java`, `Bindings.java`, `ExpressionException.java`; `dev/regpack/ports/regime/RegimeProvider.java`
- Create (engine): `regpack-engine/src/main/java/dev/regpack/engine/exec/MeteredExecutor.java`
- Test: `regpack-ports/src/test/java/dev/regpack/ports/exec/CostMeterTest.java`; `regpack-engine/src/test/java/dev/regpack/engine/exec/MeteredExecutorTest.java`, `ExecutorBudgetTestsAreWallClockFreeTest.java`

**Interfaces:** the executor, expression and regime blocks of the port contracts, verbatim.
```java
public final class MeteredExecutor implements Executor {
  public static final String ID = "metered-reference-v0";
  public String id();                                            // ID
  public <I, O> ExecutionResult<O> execute(RuleProgram<I, O> program, I input, CostBudget budget);
}
```
`CostBudget` rejects a negative maximum. `CostMeter.charge(n)` rejects `n < 0`, and throws `BudgetExceededException(currentRule, used, limit)` when `used + n > limit` *without* changing `used`. `MeteredExecutor.execute` creates the meter and an internal list-backed `Trace`, runs the program, and returns: `Completed(value, meter.used(), steps)`; for a `BudgetExceededException` `BudgetExhausted(ruleId, meter.used(), limit, steps)`; for a `RuleEvaluationException` `Failed(ruleId, causeClassSimpleName or its own class name when it has no cause, meter.used(), steps)`; for any other `RuntimeException` `Failed(meter.currentRule(), exceptionClassSimpleName, meter.used(), steps)`. The steps recorded before a failure are kept.

- [ ] **Step 1: Write the failing tests.**
  `CostMeterTest`: `chargesUpToTheLimitInclusive` (budget 10, ten `charge(1)` succeed, `used()` is 10); `exceedingThrowsAndKeepsUsedUnchanged` (the eleventh throws; `used()` still 10; the exception reports used 10, limit 10 and the entered rule id); `negativeChargeIsRejected`; `enterSetsTheCurrentRule`.
  `MeteredExecutorTest` (toy programs; no clock is touched): `completedResultReportsUnitsAndPath` (a program that charges 3 and records two trace steps gives `Completed` with cost 3 and a two-step path); `budgetBoundaryIsInclusive` (budget 10: a program charging 1 ten times completes with cost 10; one charging 1 eleven times gives `BudgetExhausted(ruleId = the rule it entered, costUnits = 10, limit = 10)`); `sameInputHitsSameBudgetOnEveryRun` (the eleven-charge program run 1000 times always yields an equal `BudgetExhausted` record); `ruleEvaluationExceptionBecomesFailedWithCauseClass` (`new RuleEvaluationException("R7", new ArithmeticException())` gives `Failed("R7", "ArithmeticException", cost so far)`); `unexpectedRuntimeExceptionBecomesFailedForTheCurrentRule` (the program calls `meter.enter("R9")` then throws `IllegalStateException`: `Failed("R9", "IllegalStateException", ...)`); `partialPathIsKeptOnFailure`; `negativeBudgetIsRejected`.
  `ExecutorBudgetTestsAreWallClockFreeTest#budgetTestsUseNoWallClock`: copies `MeteredExecutorTest*.class` from `regpack.test.classes.dir` into a temporary directory, asserts at least one class was copied, and asserts `ForbiddenCallScan.scan(tmp, EnumSet.of(WALL_CLOCK))` is empty (spec 14.1: budget tests count cost units, never wall-clock time; review focus RF7). T21 extends the copy to `ScopeEngineTest*.class`, which holds the other executor budget test.
- [ ] **Step 2: Run red.** `scripts/t.sh regpack-ports CostMeterTest` then `scripts/t.sh regpack-engine 'MeteredExecutorTest,ExecutorBudgetTestsAreWallClockFreeTest'`. Expected: `cannot find symbol ... CostMeter`, `MeteredExecutor`.
- [ ] **Step 3: Implement** the ports (interfaces and small classes) and `MeteredExecutor`.
- [ ] **Step 4: Run green.** Same commands. Expected: ports `Tests run: 4`; engine `Tests run: 8` (7 plus 1), all `Failures: 0, Errors: 0, Skipped: 0`.
- [ ] **Step 5: Commit.** `./mvnw -B -ntp -q spotless:apply`, `git add -A`, `git commit -m "feat(ports): add executor, expression and regime ports with cost-unit MeteredExecutor" -m "Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"`.

### Task 19 (T19): Deterministic fault simulator

The simulator wraps any port interface in a `java.lang.reflect.Proxy` and injects a crash, a failure or a clock advance at a named point: the call boundary before or after a method. A crash after a method leaves its effect committed but unseen by the caller, which is the realistic crash. Schedules come from an explicit seed; nothing reads the wall clock.

**Files:**
- Create (testkit, `dev/regpack/testkit/fault/`): `SimulatedCrash.java`, `FaultPlan.java`, `FaultInjector.java`
- Test: `regpack-testkit/src/test/java/dev/regpack/testkit/fault/FaultPlanTest.java`, `FaultInjectorTest.java`

**Interfaces:**
```java
public final class SimulatedCrash extends RuntimeException { public String point(); }   // "<point>#<n>"
public final class FaultPlan {
  public static FaultPlan none();
  public static FaultPlan crashAt(String point, int nthHit);                    // nthHit >= 1
  public static FaultPlan failAt(String point, int nthHit, RuntimeException toThrow);
  public static FaultPlan advanceClockAt(String point, int nthHit, Duration d);
  public static FaultPlan seeded(long seed, List<String> candidatePoints, int maxHit);
      // one crash: rng = new SplittableRandom(seed); point = candidatePoints.get(rng.nextInt(size)); hit = 1 + rng.nextInt(maxHit)
  public FaultPlan then(FaultPlan other);                                       // both apply
  public String describe();                                                     // e.g. "crash IngestLedger.commitIngest:AFTER #2"; entries joined with "; "; "none" when empty
}
public final class FaultInjector {
  public FaultInjector(FaultPlan plan);
  public FaultInjector(FaultPlan plan, ManualClock clock);                      // needed only for advanceClockAt
  public <T> T wrap(Class<T> port, T delegate);                                 // port must be an interface (else IllegalArgumentException)
  public List<String> trace();                                                  // "<Interface>.<method>:BEFORE#1", "...:AFTER#1", ... in call order
  public Sha256 traceDigest();                                                  // SHA-256 of the trace lines joined with "\n"
}
```
Point names are `<SimpleNameOfTheWrappedInterface>.<method>:BEFORE` or `:AFTER`; hits are counted per point string across everything one injector wraps. `Object` methods are not traced. A delegate exception passes through unwrapped and records no AFTER point. `describe()` uses no case conversion and no locale-sensitive formatting.

- [ ] **Step 1: Write the failing tests.**
  `FaultPlanTest`: `seededPlanIsStableAcrossRuns` (`FaultPlan.seeded(42, List.of("A.x:BEFORE","A.y:AFTER","B.z:AFTER"), 3).describe()` is identical across 100 constructions; and an equal-seed rebuild equals it); `differentSeedsCoverMoreThanOnePlan` (seeds 0 to 19 give at least 3 distinct descriptions); `describeIsLocaleIndependent` (`crashAt("IngestLedger.commitIngest:AFTER", 2).describe()` is exactly `crash IngestLedger.commitIngest:AFTER #2`, with an uppercase `I` that a Turkish-locale lower-casing would corrupt); `crashAtRejectsNonPositiveHit`.
  `FaultInjectorTest` (a test-local interface `Counter { int incr(); }` backed by an `AtomicInteger`): `crashBeforeLeavesTheEffectUnapplied` (plan `crashAt("Counter.incr:BEFORE", 2)`: first call returns 1, second throws `SimulatedCrash`, the delegate's count is still 1); `crashAfterLeavesTheEffectCommitted` (`crashAt("Counter.incr:AFTER", 1)`: the call throws and the delegate's count is 1); `failAtThrowsTheGivenExceptionOnlyOnTheNthHit`; `traceRecordsBeforeAndAfterInOrderAndDigestIsStable` (two calls give exactly `[Counter.incr:BEFORE#1, Counter.incr:AFTER#1, Counter.incr:BEFORE#2, Counter.incr:AFTER#2]`; a second injector with the same calls has an equal `traceDigest`); `delegateExceptionPassesThroughUnwrapped` (an `IllegalStateException` from the delegate reaches the caller as such, and no AFTER entry is recorded); `advanceClockFaultMovesTheManualClock`; `wrapRejectsClasses`; `objectMethodsAreNotTraced`; `faultsApplyToEnrichmentSourceAndTransportPorts` (spec 14.3: the enrichment fakes are driven by the same harness: a `failAt("EnrichmentSource.lookup:BEFORE", 1, new LookupUnavailableException(...))` plan makes the first lookup on a wrapped `ScriptedEnrichmentSource` throw and the second succeed; `crashAt("Transport.send:AFTER", 1)` on a wrapped `DestinationSimulator` throws `SimulatedCrash` after the file is `PRESENT` in the destination).
- [ ] **Step 2: Run red.** `scripts/t.sh regpack-testkit 'FaultPlanTest,FaultInjectorTest'`. Expected: `cannot find symbol ... FaultPlan`.
- [ ] **Step 3: Implement.** In the proxy handler: skip `Object` methods; record `BEFORE#n` and apply the plan's faults for (point, n) (a crash throws before the delegate is called); call the delegate via `method.invoke`, unwrapping `InvocationTargetException`; record `AFTER#n` and apply the faults for that point. Keep plan entries in a `List` (never a hash-ordered collection) so the trace cannot depend on hash order (review focus RF8).
- [ ] **Step 4: Run green.** Same command. Expected: `Tests run: 13, Failures: 0, Errors: 0, Skipped: 0` (4 plus 9).
- [ ] **Step 5: Commit.** `./mvnw -B -ntp -q spotless:apply`, `git add -A`, `git commit -m "feat(testkit): add deterministic fault simulator" -m "Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"`.

### Task 20 (T20): Golden-test harness with provenance and a named human reviewer

Spec 14.2: a golden case records its source and a named human reviewer or is labelled `consistency-only`; consistency-only cases never satisfy a traceability row; implementation agents never both write a rule and approve its oracle. The harness enforces this mechanically, and the reviewer must be in a registry the owner maintains, so a name typed into a JSON file proves nothing.

**Files:**
- Create (testkit, `dev/regpack/testkit/golden/`): `GoldenCase.java`, `GoldenCaseLoader.java`, `ReviewerRegistry.java`, `GoldenAudit.java`
- Create: `governance/golden-reviewers.txt`
- Test: `regpack-testkit/src/test/java/dev/regpack/testkit/golden/GoldenAuditTest.java`

**Interfaces:**
```java
public record GoldenCase(String id, String node, String description, Map<String, Object> input,
                         Expected expected, Provenance provenance, String author) {
  public record Expected(Outcome outcome, ReasonCode reasonCode, List<String> ruleIdsHit) {}
  public enum ProvenanceKind { REGULATOR_WORKBOOK, ESMA_QA, GUIDELINES, ARM_NCA_SANDBOX, REAL_TRADE, CONSISTENCY_ONLY }
  public record Provenance(ProvenanceKind kind, Optional<String> source, Optional<String> reviewerId,
                           Optional<LocalDate> reviewedOn) {}
}
public final class GoldenCaseLoader {
  public static GoldenCase parse(String json);                 // strict; a missing "provenance" object means CONSISTENCY_ONLY
  public static List<GoldenCase> loadDirectory(Path dir);      // every *.json, sorted by file name
}
public final class ReviewerRegistry {
  public record Reviewer(String id, String fullName, String role) {}
  public static ReviewerRegistry parse(String text);           // lines "id|Full name|role"; blank lines and '#' comments ignored; duplicate ids rejected
  public static ReviewerRegistry load(Path file);
  public boolean contains(String reviewerId);
}
public final class GoldenAudit {
  public record Row(String caseId, ProvenanceKind kind, String reviewerId, boolean countsForTraceability, List<String> problems) {}
  public static List<Row> audit(List<GoldenCase> cases, ReviewerRegistry reviewers, Map<String, String> ruleAuthorById);
  public static String toTsv(List<Row> rows);                  // caseId TAB kind TAB reviewerId or "-" TAB true|false TAB problems joined by ";" or "-"; one line per row, LF
}
```
JSON case format: `{"id","node","description","author","input":{canonical execution},"expected":{"outcome","reasonCode","ruleIdsHit":[...]},"provenance":{"kind","source","reviewer","reviewedOn":"yyyy-mm-dd"}}`. A case counts iff its kind is not `CONSISTENCY_ONLY`, a non-blank `source` exists, a `reviewer` exists and is in the registry, `reviewedOn` exists, and its `author` differs from the author of every rule in `ruleIdsHit` (looked up in `ruleAuthorById`). A rule id in `ruleIdsHit` that is not a key of `ruleAuthorById` is the problem `UNKNOWN_RULE:<ruleId>` and the case never counts. A `reviewerId` equal to the case author or to the author of any rule in `ruleIdsHit` is the problem `REVIEWER_IS_AUTHOR` and the case never counts. Problems use the tokens `NO_SOURCE`, `NO_REVIEWER`, `REVIEWER_NOT_REGISTERED`, `NO_REVIEW_DATE`, `SEPARATION:<ruleId>`, `UNKNOWN_RULE:<ruleId>`, `REVIEWER_IS_AUTHOR`. A `CONSISTENCY_ONLY` case never counts and has no problems.

- [ ] **Step 1: Write the failing tests** (`GoldenAuditTest`, using inline JSON strings and a registry parsed from `"rev-1|Test Reviewer|Compliance"`): `consistencyOnlyNeverCounts`; `fullyReviewedCaseCounts` (kind `ESMA_QA`, source `Q&A 1699`, reviewer `rev-1`, date set, author `agent:golden` against rule author `agent:rules`); `unregisteredReviewerNeverCounts` (reviewer `ghost` gives `REVIEWER_NOT_REGISTERED`, counts false); `missingSourceNeverCounts`; `sameAuthorAsRuleIsSeparationViolation` (case author equals the author of a hit rule: counts false, problem `SEPARATION:<ruleId>`); `unknownRuleIdNeverCounts` (a hit rule id missing from `ruleAuthorById`: counts false, problem `UNKNOWN_RULE:<ruleId>`); `reviewerEqualToAuthorNeverCounts` (a registered reviewer id equal to the case author: counts false, problem `REVIEWER_IS_AUTHOR`); `registryParsesCommentsAndRejectsDuplicateIds`; `tsvHasOneTabSeparatedLinePerCase`; `loaderRejectsUnknownKindAndMissingFields`.
- [ ] **Step 2: Run red.** `scripts/t.sh regpack-testkit GoldenAuditTest`. Expected: `cannot find symbol ... GoldenCase`.
- [ ] **Step 3: Implement** the four classes with `Json.parse` for the JSON and write `governance/golden-reviewers.txt`:
  ```
  # Registry of named human golden-case reviewers (spec 14.2 O1 and O2; gate G1).
  # Format: id|Full name|role   One line per person. Empty until the named Compliance owner exists (gate G1).
  ```
- [ ] **Step 4: Run green.** Same command. Expected: `Tests run: 10, Failures: 0, Errors: 0, Skipped: 0`.
- [ ] **Step 5: Commit.** `./mvnw -B -ntp -q spotless:apply`, `git add -A`, `git commit -m "feat(testkit): add golden-test harness with provenance and reviewer registry" -m "Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"`.

### Task 21 (T21): Walking-skeleton regime, three scope rules to a decision record, and tier-1 `explain`

Everything in `dev.regpack.engine.skeleton` is a stand-in that P2a replaces with the pack-driven interpreter; the scope rules are placeholders (their citations say so) and their golden cases are consistency-only. Nothing here is regime content for P6a.

**Files:**
- Create (engine): `skeleton/WalkingSkeletonRules.java`, `skeleton/SkeletonExpression.java`, `skeleton/ScopeProgram.java`, `skeleton/WalkingSkeletonRegime.java`, `explain/Tier1Explainer.java`
- Create (test resources): `regpack-engine/src/test/resources/golden/GC-N03-1.json`, `GC-N13-1.json`, `GC-N08-1.json`, `GC-DEFAULT-1.json`
- Test: `regpack-engine/src/test/java/dev/regpack/engine/skeleton/ScopeEngineTest.java`, `SkeletonExpressionTest.java`, `RepositoryGoldenAuditTest.java`, `regpack-engine/src/test/java/dev/regpack/engine/explain/Tier1ExplainerTest.java`, `regpack-engine/src/test/java/dev/regpack/engine/NoWallClockRandomLocaleTest.java`

**Interfaces:**
```java
// WALKING SKELETON: replaced by P2a
public final class WalkingSkeletonRules {
  public record Row(String id, String node, int priority, String when, Outcome outcome,
                    ReasonCode reason, List<Citation> citations, String author, int exprCost) {}
  public static final String RULE_AUTHOR = "agent:p0-implementer";
  public static List<Row> rows();                              // ascending priority; hit policy FIRST; the last row is the catch-all
  public static Map<String, String> authorsById();
}
public final class SkeletonExpression implements Expression { }   // compiles exactly the four sources of the rows; anything else: ExpressionException
public final class ScopeProgram implements RuleProgram<CanonicalExecution, WalkingSkeletonRules.Row> {
  public ScopeProgram(Expression expression);                  // compiles every row's `when` once
}
public final class WalkingSkeletonRegime implements RegimeProvider {
  public static final Regime REGIME = new Regime("mifir.eu.rts22");
  public WalkingSkeletonRegime(Executor executor, Expression expression);
  public static WalkingSkeletonRegime standard();              // MeteredExecutor + SkeletonExpression
  public static DecisionContext skeletonContext();             // pack digest Sha256.ofUtf8("walking-skeleton"), no overlay or pins, firmProfileVersion "none", mappingVersion "toy-1", engineBuild "p0", executor MeteredExecutor.ID
}
public final class Tier1Explainer { public static String explain(DecisionRecord decision); }
```
The four rows (placeholder content; sources are `mifir-placeholder` and `regpack-spec`, never real provisions):

| id | node | priority | `when` | outcome | reason | citation | `exprCost` |
|---|---|---|---|---|---|---|---|
| `S-N03-novation` | N03 | 300 | `event.kind in ['NOVATION','ASSIGNMENT']` | DO_NOT_REPORT | `MIFIR.N03.EXCLUDED` | `mifir-placeholder`, `art/2/par/5/pt/PLACEHOLDER`, BASIS | 2 |
| `S-D14-natural-person` | N13 | 400 | `client.kind == 'NATURAL_PERSON'` | UNDETERMINED | `MIFIR.N13.NATURAL_PERSON` | `regpack-spec`, `D14`, INTERPRETATION | 1 |
| `S-N08-equity-on-venue` | N08 | 800 | `instrument.assetClass == 'EQUITY' && instrument.tradedOnVenue` | REPORT | `MIFIR.N08.IN_SCOPE_26_2_A` | `mifir-placeholder`, `art/26/par/2/pt/a`, BASIS | 2 |
| `S-DEFAULT-catch-all` | DEFAULT | 9999 | `true` | UNDETERMINED | `DEFAULT.NO_RULE_HIT` | `regpack-spec`, `I5`, GUIDANCE | 0 |

Cost accounting (all deterministic): evaluating a row charges 1 for the row, then the expression charges its `exprCost`; the trace step's `costUnits` is the row total. `ScopeProgram.run` evaluates rows in ascending priority, calls `meter.enter(row.id())` before charging, records a `PathStep` per row, and returns the first matching row; an `ExpressionException` becomes `RuleEvaluationException(row.id(), e)`. Bindings: `event.kind` and `client.kind` are `EnumValue`s, `instrument.assetClass` is an `EnumValue`, `instrument.tradedOnVenue` is a `BoolValue`; an absent binding makes `SkeletonExpression` throw `ExpressionException`. `WalkingSkeletonRegime.evaluate` maps `Completed` to a `DecisionBody` (`ruleIdsHit` = the row id; `legs` = `[MARKET]` for REPORT, else empty; `flags` empty; `node`, `reasonCode`, `citations` from the row; `decisionContextId` = `context.decisionContext().id()`; `asAt` = `occurredAt()`; `evaluatedAt` from the context; `inputDigest` = `InputDigest.of(execution, context.ingestKey())`), `BudgetExhausted` to UNDETERMINED with reason `EXEC_BUDGET`, node `EXEC`, flags `["rule_id=<id>", "cost_units=<n>", "limit=<n>"]`, and `Failed` to UNDETERMINED with reason `EXEC_ERROR`, node `EXEC`, flags `["rule_id=<id>", "error_class=<name>", "cost_units=<n>"]` (spec 7.3); in both failure cases `ruleIdsHit` is empty, the path is the recorded path, and the single citation is the catch-all's (I5). P0 tests the `EXEC_BUDGET` half of the spec 14.1 seeded over-budget case only; the circuit is out of scope (see the Scope check).

`Tier1Explainer.explain` renders exactly this text (LF line endings, no trailing newline), with `Flags: none` when empty and `Legs: none` when empty:
```
Decision 7 (seq 1) for LEI1|XPAR|EX-1 under mifir.eu.rts22
Outcome: REPORT
Reason: MIFIR.N08.IN_SCOPE_26_2_A (node N08)
Rule hits: S-N08-equity-on-venue
Path:
  1. S-N03-novation: no match (3 units)
  2. S-D14-natural-person: no match (2 units)
  3. S-N08-equity-on-venue: MATCH (3 units)
Citations:
  - mifir-placeholder art/26/par/2/pt/a [BASIS]
Flags: none
Legs: MARKET
Input digest: <hex>
Context: <hex>
```

- [ ] **Step 1: Write the failing tests.** Test executions use `firmLei LEI-FIRM-0001`, venue `XPAR`, ISIN `ZZ0000000001` (synthetic: P0 validates no identifier shapes, that is P5), `tradedAt 2026-10-09T09:30:00.123456Z`, quantity `100`, price `12.25`, `EUR`, legal-entity client unless stated, and `WalkingSkeletonRegime.standard()` with a budget of 100.
  `ScopeEngineTest`: `novationIsExcludedAndStopsAtTheFirstRow` (NOVATION: DO_NOT_REPORT, `MIFIR.N03.EXCLUDED`, hits `[S-N03-novation]`, path `[(S-N03-novation,true,3)]`, legs empty); `naturalPersonIsUndeterminedNeverDoNotReport` (NEW, NATURAL_PERSON, equity on venue: UNDETERMINED, `MIFIR.N13.NATURAL_PERSON`, path `[(S-N03-novation,false,3),(S-D14-natural-person,true,2)]`; I5); `equityOnVenueIsReportedWithMarketLeg` (REPORT, `MIFIR.N08.IN_SCOPE_26_2_A`, path costs 3, 2, 3, legs `[MARKET]`); `unmatchedInstrumentFallsToCatchAllUndetermined` (DEBT: UNDETERMINED, `DEFAULT.NO_RULE_HIT`, hits `[S-DEFAULT-catch-all]`, path costs 3, 2, 3, 1); `exclusionPrecedesPartyRule` (NOVATION with a natural person: DO_NOT_REPORT); `budgetExhaustionYieldsUndeterminedExecBudget` (equity case with budget 5: UNDETERMINED, `EXEC_BUDGET`, flags exactly `[rule_id=S-N08-equity-on-venue, cost_units=5, limit=5]`, a two-step path); `expressionErrorYieldsUndeterminedExecError` (an `Expression` decorator whose compiled expression for the natural-person row throws `ExpressionException` on evaluation: UNDETERMINED, `EXEC_ERROR`, flags `[rule_id=S-D14-natural-person, error_class=ExpressionException, cost_units=4]`); `sameInputGivesSameReplayDigestOneThousandTimes`; `decisionIsStampedWithContextIdAndIngestKeyDigest`; `descriptorNamesRegimeAndPlaceholderPack`.
  `SkeletonExpressionTest`: `evaluatesTheFourKnownSources`, `chargesDeclaredCostToTheMeter`, `unknownSourceIsRejectedAtCompile`, `absentBindingThrowsExpressionException`.
  `Tier1ExplainerTest`: `rendersPinnedText` (a hand-built `DecisionRecord` with id 7, seq 1, digests `"a"` repeated 64 times for the input digest and `"b"` repeated 64 times for the context id, renders exactly the text above with those digests); `rendersFlagsAndNoLegsForUndetermined` (flags joined by `, `, `Legs: none`).
  Golden resources: four JSON files in the format of T20, `author` `agent:golden-author`, no `provenance` (consistency-only), inputs and expectations matching the first four `ScopeEngineTest` cases (ids `GC-N03-1`, `GC-N13-1`, `GC-N08-1`, `GC-DEFAULT-1`). `RepositoryGoldenAuditTest`: `everyGoldenCaseRunsThroughTheEngineAndMatchesItsExpectation` (outcome, reason code and rule ids per case) and `everyGoldenCaseIsAuditedAndNoneCounts` (audits against `governance/golden-reviewers.txt` under `regpack.repo.root` and `WalkingSkeletonRules.authorsById()`; asserts four rows, none counting, no problems; writes the TSV to `<regpack.build.dir>/golden-audit.tsv`). `NoWallClockRandomLocaleTest#engineMainClassesAreClean`: all three rule sets over `regpack.classes.dir` give no violation (review focus RF6).
- [ ] **Step 2: Run red.** `scripts/t.sh regpack-engine 'ScopeEngineTest,SkeletonExpressionTest,Tier1ExplainerTest,RepositoryGoldenAuditTest,NoWallClockRandomLocaleTest'`. Expected: `cannot find symbol ... WalkingSkeletonRegime`.
- [ ] **Step 3a: Implement the expression and the program.** `SkeletonExpression` and `ScopeProgram` (with `WalkingSkeletonRules`); run `scripts/t.sh regpack-engine SkeletonExpressionTest` (expected `Tests run: 4, Failures: 0`). Mark every class in `skeleton` with the Javadoc line `WALKING SKELETON: replaced by P2a`. Also create `WalkingSkeletonRegime` and `Tier1Explainer` as stubs that throw `UnsupportedOperationException`, so that the test sources of the module compile; the next two steps fill them in.
- [ ] **Step 3b: Implement the regime and the scope rules' mapping.** `WalkingSkeletonRegime`; run `scripts/t.sh regpack-engine 'ScopeEngineTest,RepositoryGoldenAuditTest,NoWallClockRandomLocaleTest'` (expected `Tests run: 13, Failures: 0`).
- [ ] **Step 3c: Implement the explainer** (`Tier1Explainer`); run `scripts/t.sh regpack-engine Tier1ExplainerTest` (expected `Tests run: 2, Failures: 0`).
- [ ] **Step 3d: Extend the wall-clock-free check.** Change `ExecutorBudgetTestsAreWallClockFreeTest#budgetTestsUseNoWallClock` (T18) to copy both `MeteredExecutorTest*.class` and `ScopeEngineTest*.class` from `regpack.test.classes.dir` into the temporary directory, assert that at least one class was copied for each pattern, and scan for `WALL_CLOCK` (spec 14.1: every executor budget test counts cost units, and `ScopeEngineTest#budgetExhaustionYieldsUndeterminedExecBudget` is one). Run `scripts/t.sh regpack-engine ExecutorBudgetTestsAreWallClockFreeTest` (expected `Tests run: 1, Failures: 0`).
- [ ] **Step 4: Run green.** Same command. Expected: `Tests run: 19, Failures: 0, Errors: 0, Skipped: 0` (10, 4, 2, 2 and 1). This satisfies the first exit criterion: three scope rules run in memory to a decision record and `explain`.
- [ ] **Step 5: Commit.** `./mvnw -B -ntp -q spotless:apply`, `git add -A`, `git commit -m "feat(engine): add walking-skeleton scope rules, decision records and tier-1 explain" -m "Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"`.

### Task 22 (T22): `MicroBatchPipeline` and the deterministic toy crash test

**Files:**
- Create (engine): `regpack-engine/src/main/java/dev/regpack/engine/pipeline/MicroBatchPipeline.java`
- Test: `regpack-engine/src/test/java/dev/regpack/engine/pipeline/MicroBatchPipelineTest.java`, `regpack-engine/src/test/java/dev/regpack/engine/pipeline/ToyCrashIT.java`, shared fixture `ToyBatches.java` (test sources)

**Interfaces:**
```java
public final class MicroBatchPipeline {
  public MicroBatchPipeline(Clock clock, EvidenceStore evidence, LedgerStore ledger, RegimeProvider regime,
                            DecisionContext context, EvidenceStore.Retention landingRetention, CostBudget budget);
  /** Takes up to `credits` batches from the stream and processes each in the spec 7.2 order:
   *  land (idempotent PUT), commitIngest, acknowledge the source, then decide and persist. Returns the batches taken. */
  public int pump(SourceConnector.BatchStream stream, String sourceId, int credits);
  /** Crash recovery: decides every DECIDE_BATCH outbox item still PENDING, reading the bytes back from the landing zone. */
  public int recoverPending();
}
```
Behaviour: the batch id is `BatchId.of(manifest.sha256(), 0)`; the landing key is `landing/<hex>/0`; the payload is NDJSON, each line a canonical execution map plus `sourceRecordId` (string) and `sourceVersion` (integer); entries carry `contentHash = Sha256.ofUtf8(Json.canonical(executionMap))`; deciding looks at the stored `ingestResult(batchId)` and decides only `NEW_VERSION` entries, with `expectedHeadSeq` taken from `currentDecision`, evaluated with `EvaluationContext(context, ingestKey, clock.now(), budget)`, and committed with `commitDecisions(BatchCommit(decisions, Optional.of(outboxId)))`; an outbox item already `DONE` is skipped. The pipeline calls only port methods, so a `FaultInjector` can wrap the ledger, the evidence store and the stream.
`ToyBatches`: `batch1` = three lines (`T-1` EX-0001 NEW equity, `T-2` EX-0002 NOVATION, `T-3` EX-0003 NEW natural person), `batch2` = two lines (`T-1` version 2 with kind NOVATION, which supersedes the earlier REPORT decision; `T-2` version 1 again, a no-op); source id `toy`, stream `trades`, media type `application/x-ndjson`.

- [ ] **Step 1: Write the failing tests.** `MicroBatchPipelineTest`: `happyPathProducesDecisionsAndAcknowledgesTheSource` (after `pump(stream, "toy", 10)`: chains for `T-1` are `[REPORT, DO_NOT_REPORT]` with seq 1 and 2, `T-2` is `[DO_NOT_REPORT]`, `T-3` is `[UNDETERMINED]`; `source.acknowledgedSeq()` is 2; two landing objects exist; `pendingOutbox` is empty); `staleVersionIsNotDecided` (a later batch carrying `T-1` version 1 after version 2 adds no decision).
  `ToyCrashIT` (failsafe; every run builds fresh in-memory stores and a `ManualClock` at 2026-10-09T08:00:00Z). Helper `run(FaultPlan plan, boolean resumeFromCheckpoint)` wraps the ledger, evidence store and stream with one `FaultInjector`, calls `pump` until it throws `SimulatedCrash` or the stream is exhausted, then "restarts": a new pipeline over the unwrapped stores, a new stream read from the same `InMemorySourceConnector` with `resumeFrom` = the ledger's `latestCheckpoint` (or empty when `resumeFromCheckpoint` is false, modelling a lost acknowledgement), `recoverPending()`, then `pump` until exhausted. `stateDigest` is `Sha256.ofUtf8` over, for the three event identities in sorted order, each chain's `seq|outcome|reasonCode|replayDigest` lines.
  - `everyCrashPointConvergesToTheUncrashedBaseline`: the baseline (`FaultPlan.none()`) gives digest `D0`; for each of the methods `EvidenceStore.put`, `LedgerStore.commitIngest`, `BatchStream.acknowledge`, `LedgerStore.commitDecisions`, each phase `BEFORE` and `AFTER`, and hit 1 and 2 (16 plans) the final `stateDigest` equals `D0`, `pendingOutbox(DECIDE_BATCH, 10)` is empty and the `T-1` chain has exactly two decisions.
  - `sameFaultPlanGivesIdenticalTraceAndDigest`: the same plan run twice gives equal `traceDigest` and equal `stateDigest`; the baseline `D0` hex is written to `<regpack.build.dir>/toy-digest.txt` (a single line) for the CI comparison between operating systems.
  - `acknowledgementFollowsIngestCommit`: in the baseline trace the index of `EvidenceStore.put:AFTER#1` is smaller than that of `LedgerStore.commitIngest:BEFORE#1`, which is smaller than that of `LedgerStore.commitIngest:AFTER#1`, which is smaller than that of `BatchStream.acknowledge:BEFORE#1` (spec 7.2 order: land, commit, acknowledge); with `crashAt("LedgerStore.commitIngest:BEFORE", 1)` the source's `acknowledgedSeq()` is 0 after the crash; with `crashAt("EvidenceStore.put:BEFORE", 1)` the trace contains no `LedgerStore.commitIngest:BEFORE#1` and `acknowledgedSeq()` is 0 after the crash (I1; review focus RF11).
  - `redeliveryAfterAckLossIsNoop`: with `crashAt("BatchStream.acknowledge:BEFORE", 1)` and `resumeFromCheckpoint = false` the source redelivers batch 1; the final digest equals `D0` and `outboxItem(new OutboxId(3))` is empty (no third outbox item was created).
  - `seededPlansAlsoConverge`: for seeds 1 to 25, `FaultPlan.seeded(seed, <the eight point names above>, 2)` converges to `D0`.
- [ ] **Step 2: Run red.** `scripts/t.sh regpack-engine MicroBatchPipelineTest` and `scripts/it.sh regpack-engine ToyCrashIT`. Expected: `cannot find symbol ... MicroBatchPipeline`.
- [ ] **Step 3: Implement** `MicroBatchPipeline` as described.
- [ ] **Step 4: Run green.** Same commands. Expected: unit `Tests run: 2, Failures: 0`; integration `Tests run: 5, Failures: 0, Errors: 0, Skipped: 0` in the failsafe summary, and a file `regpack-engine/target/toy-digest.txt` of 64 hex characters. This satisfies the second exit criterion: the toy crash test is deterministic.
- [ ] **Step 5: Commit.** `./mvnw -B -ntp -q spotless:apply`, `git add -A`, `git commit -m "feat(engine): add micro-batch pipeline and deterministic toy crash test" -m "Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"`.

### Task 23 (T23): App skeleton: roles, configuration and the `smoke` command

**Files:**
- Create (`regpack-app/src/main/java/dev/regpack/app/`): `Role.java`, `RoleSet.java`, `AppConfig.java`, `SystemClock.java`, `SmokeCommand.java`, `Main.java`
- Create: `regpack-app/src/main/resources/smoke/toy-executions.ndjson`
- Test: `regpack-app/src/test/java/dev/regpack/app/RoleSetTest.java`, `AppConfigTest.java`, `SystemClockTest.java`, `SmokeCommandTest.java`, `MainTest.java`

**Interfaces:**
```java
public enum Role { INGEST, ENGINE, ASSEMBLER, SENDER, FEEDBACK, TIMEKEEPER, CONTROL_PLANE, CONSOLE, SIGNER;
  public String flag();                    // lower case, '_' becomes '-': "control-plane"
  public static Role parse(String flag);   // Locale.ROOT; unknown => IllegalArgumentException listing every allowed flag
}
public record RoleSet(Set<Role> roles) {
  public static RoleSet parse(String csv);     // "all" = every role except SIGNER; SIGNER only alone; empty, unknown or duplicate => IllegalArgumentException
  public static RoleSet defaultRoles();        // all
}
public record AppConfig(RoleSet roles, String bind, int port) {
  public static AppConfig parse(List<String> args, Map<String, String> env);
      // flags --roles --bind --port; environment REGPACK_ROLES, REGPACK_HTTP_BIND, REGPACK_HTTP_PORT; flags win;
      // defaults: roles all, bind 127.0.0.1, port 8080; port must be 0..65535 (0 = ephemeral, used by tests)
}
public final class SystemClock implements Clock { }                  // the only wall-clock adapter: now() = Instant.now().truncatedTo(ChronoUnit.MICROS) (Clock contract: whole microseconds), monotonicNanos() = System.nanoTime()
public final class SmokeCommand {
  public static int run(List<String> args, PrintStream out, PrintStream err, Clock clock);     // 0 ok, 1 failure, 2 usage
  static int run(List<String> args, PrintStream out, PrintStream err, Clock clock, String ndjson);   // input override for tests
}
public final class Main {
  public static void main(String[] args);                                                    // System.exit(run(...))
  static int run(String[] args, Map<String, String> env, PrintStream out, PrintStream err);  // commands: serve (T24), smoke, help
}
```
`smoke [--health-url <base>]` behaviour: with `--health-url`, GET `<base>/health/live` and `<base>/health/ready` through `java.net.http.HttpClient` (5 s timeout), each must return 200 with a body containing `"status":"UP"`, else exit 1; print `regpack smoke: health <path> OK` per probe. Then run the pipeline: an `InMemoryEvidenceStore`, an `InMemoryLedgerStore`, an `InMemorySourceConnector("toy","trades","application/x-ndjson",[the toy input as one batch])`, `WalkingSkeletonRegime.standard()`, `MicroBatchPipeline`, `SystemClock`; then for each execution in file order print `decision <Json.canonical(DecisionCodec.recordToMap(current decision))>` and every line of `Tier1Explainer.explain(...)` prefixed with `explain: `; verify the outcomes are exactly `[REPORT, DO_NOT_REPORT, UNDETERMINED]` (a mismatch prints `regpack smoke: FAILED expected [...] got [...]` to `err`, exit 1); finally print `regpack smoke: OK decisions=3`. `Main` with no arguments, `help` or `-h` prints the command list and returns 0; an unknown command prints usage to `err` and returns 2. The signer role is parsed and validated here; running the signer is P3.
`toy-executions.ndjson` (three lines; `LEI-` values are placeholders because identifier validation is P5):
```
{"sourceRecordId":"T-1","sourceVersion":1,"firmLei":"LEI-FIRM-0001","venueMic":"XPAR","venueExecutionId":"EX-0001","eventKind":"NEW","tradedAt":"2026-10-09T09:30:00.123456Z","assetClass":"EQUITY","isin":"ZZ0000000001","tradedOnVenue":true,"clientKind":"LEGAL_ENTITY","clientLei":"LEI-CLIENT-0001","quantity":100,"price":12.25,"currency":"EUR"}
{"sourceRecordId":"T-2","sourceVersion":1,"firmLei":"LEI-FIRM-0001","venueMic":"XPAR","venueExecutionId":"EX-0002","eventKind":"NOVATION","tradedAt":"2026-10-09T09:31:00.000000Z","assetClass":"EQUITY","isin":"ZZ0000000001","tradedOnVenue":true,"clientKind":"LEGAL_ENTITY","clientLei":"LEI-CLIENT-0001","quantity":50,"price":12.30,"currency":"EUR"}
{"sourceRecordId":"T-3","sourceVersion":1,"firmLei":"LEI-FIRM-0001","venueMic":"XPAR","venueExecutionId":"EX-0003","eventKind":"NEW","tradedAt":"2026-10-09T09:32:00.000000Z","assetClass":"EQUITY","isin":"ZZ0000000001","tradedOnVenue":true,"clientKind":"NATURAL_PERSON","quantity":10,"price":12.35,"currency":"EUR"}
```

- [ ] **Step 1: Write the failing tests.** `RoleSetTest`: `parsesCsvAndAllExcludesSigner` (`parse("engine,ingest")` has those two; `parse("all")` has eight roles and no `SIGNER`); `signerMustBeAlone` (`parse("engine,signer")` throws, `parse("signer")` is exactly `{SIGNER}`; review focus RF17); `rejectsUnknownEmptyAndDuplicateRoles` (the message for `"bogus"` contains `control-plane`); `flagRoundTripsForEveryRole`. `AppConfigTest`: `defaultsAreLoopbackPort8080AllRoles`; `flagsOverrideEnvironment`; `invalidPortIsRejected` (`--port 70000`, `--port abc`). `SystemClockTest`: `nowIsWholeMicroseconds` (1000 calls, each `getNano() % 1000 == 0`, and the first is within one minute of the host's `Instant.now()`), `monotonicNanosNeverDecreases` (1000 successive calls). `SmokeCommandTest` (captured `PrintStream`s and a `ManualClock`): `smokePrintsDecisionsAndExplainAndExitsZero` (exactly three lines start with `decision {`, one contains `"outcome":"REPORT"`, the output contains `explain: Outcome: DO_NOT_REPORT` and `explain: Outcome: UNDETERMINED`, the last line is `regpack smoke: OK decisions=3`, exit 0); `smokeFailsWhenAnOutcomeDiffers` (the NDJSON override changes `T-2` to `"eventKind":"NEW"`: exit 1 and `err` contains `expected [REPORT, DO_NOT_REPORT, UNDETERMINED] got [REPORT, REPORT, UNDETERMINED]`; review focus RF19); `unknownFlagExitsWithUsage` (2); `smokeWithTheRealClockExitsZero` (uses `new SystemClock()`: exit 0 and the last line `regpack smoke: OK decisions=3`, which would fail on sub-microsecond instants). `MainTest`: `helpListsCommandsAndExitsZero`, `unknownCommandExitsTwo`, `smokeThroughMainExitsZero`.
- [ ] **Step 2: Run red.** `scripts/t.sh regpack-app 'RoleSetTest,AppConfigTest,SystemClockTest,SmokeCommandTest,MainTest'`. Expected: `cannot find symbol ... RoleSet`.
- [ ] **Step 3: Implement.** Use `Locale.ROOT` for all case conversion. The `serve` command is added in T24; until then `Main.run` answers `serve` with exit 2 and the message `serve is added by task T24`.
- [ ] **Step 4: Run green.** Same command. Expected: `Tests run: 16, Failures: 0, Errors: 0, Skipped: 0` (4, 3, 2, 4, 3).
- [ ] **Step 5: Commit.** `./mvnw -B -ntp -q spotless:apply`, `git add -A`, `git commit -m "feat(app): add role flags, configuration and the smoke command" -m "Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"`.

### Task 24 (T24): Health endpoint, service lifecycle and the Windows service entry point

**Files:**
- Create (`regpack-app/src/main/java/dev/regpack/app/`): `HealthServer.java`, `Service.java`, `WindowsServiceEntry.java`
- Modify: `Main.java` (`serve`)
- Test: `regpack-app/src/test/java/dev/regpack/app/HealthServerTest.java`, `ServiceLifecycleTest.java`

**Interfaces:**
```java
public final class HealthServer implements AutoCloseable {
  public static HealthServer start(String bind, int port);      // JDK com.sun.net.httpserver, virtual-thread executor; port 0 = ephemeral
  public int port();
  public InetSocketAddress boundAddress();
  public void markReady();
  public void markStopping();
  @Override public void close();                                // stops accepting, waits up to 1 s for running exchanges
}
public final class Service {
  public static Service start(AppConfig config, PrintStream log);   // starts the health server, marks ready, logs the lifecycle lines
  public int port();
  public void awaitStop() throws InterruptedException;
  public void stop();                                               // idempotent
}
public final class WindowsServiceEntry {                            // procrun jvm mode: StartMethod=start, StopMethod=stop
  public static void start(String[] args);                          // parses the flags, starts a Service, blocks until stop(); spec: "the start method should not return until the stop method has been called"
  public static void stop(String[] args);
}
```
Endpoints: `GET /health/live` is 200 `{"status":"UP"}` while the server runs; `GET /health/ready` is 503 `{"status":"STARTING"}` before `markReady`, 200 `{"status":"UP"}` after, and 503 `{"status":"STOPPING"}` after `markStopping`; any other path is 404, any other method 405 with `Allow: GET`; `Content-Type: application/json`. Lifecycle log lines, exactly, each ending in LF: `regpack lifecycle: starting roles=<flags comma-joined in enum order> bind=<bind> port=<port>`, `regpack lifecycle: ready port=<actual port>`, `regpack lifecycle: stopping`, `regpack lifecycle: stopped cleanly`. `Service.stop` calls `markStopping`, logs `stopping`, closes the server, logs `stopped cleanly`, and releases `awaitStop`. `Main` `serve`: `Service.start`, a JVM shutdown hook calling `stop`, then `awaitStop`; it returns 0. (A JVM that stops on SIGTERM exits with status 143; every target treats 143 together with the `stopped cleanly` line as a clean stop, see T29 to T31.)

- [ ] **Step 1: Write the failing tests.** Write `HealthServerTest` now; `ServiceLifecycleTest` is written in Step 3b, so that the Step 3a run compiles. `HealthServerTest` (JDK `HttpClient`, port 0): `bindsLoopbackByDefault` (`AppConfig.parse(List.of("--port","0"), Map.of()).bind()` equals `127.0.0.1`, and a `HealthServer` started with that bind and port 0 has `boundAddress().getAddress().isLoopbackAddress()` true; review focus RF16); `readyIs503WhileStarting`; `liveAndReadyReturnJson200AfterMarkReady` (status 200, `Content-Type` `application/json`, body `{"status":"UP"}`); `readyReturns503WhileStopping` (after `markStopping`: ready is 503 with `{"status":"STOPPING"}`, live is 200); `unknownPathIs404AndPostIs405`; `serverCanBeClosedWithoutHanging` (`close()` returns within the test's own 5 s `assertTimeoutPreemptively`). `ServiceLifecycleTest` (Step 3b): `stopCompletesAndPrintsStoppedCleanly` (captured log, port 0: the four lines in order with the real port number; a second `stop()` adds nothing; `awaitStop()` returns); `awaitStopBlocksUntilStop` (a second thread blocks until `stop()` is called); `windowsEntryStartBlocksUntilStopIsCalled` (`WindowsServiceEntry.start(new String[]{"--port","0"})` runs on a thread; once the lifecycle shows `ready`, `stop(new String[0])` makes the thread end within 10 s).
- [ ] **Step 2: Run red.** `scripts/t.sh regpack-app HealthServerTest`. Expected: `cannot find symbol ... HealthServer`.
- [ ] **Step 3a: Implement the health server.** `HealthServer` (JDK `httpserver`, virtual-thread executor, the endpoint table above). Run `scripts/t.sh regpack-app HealthServerTest`. Expected: `Tests run: 6, Failures: 0`.
- [ ] **Step 3b: Implement the lifecycle.** Add `ServiceLifecycleTest` (its three tests above), then `Service`, `WindowsServiceEntry` and the `Main` `serve` command. Run `scripts/t.sh regpack-app ServiceLifecycleTest`. Expected: `Tests run: 3, Failures: 0`.
- [ ] **Step 4: Run green.** `scripts/t.sh regpack-app 'HealthServerTest,ServiceLifecycleTest'`. Expected: `Tests run: 9, Failures: 0, Errors: 0, Skipped: 0`. (The real launcher and an end-to-end run of the packaged service come with T28 to T31.)
- [ ] **Step 5: Commit.** `./mvnw -B -ntp -q spotless:apply`, `git add -A`, `git commit -m "feat(app): add health endpoint, service lifecycle and Windows service entry point" -m "Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"`.

### Task 25 (T25): Traceability file and its CI check

Spec 14.4: a file maps R1-R14 and I1-I10 to named evidence; CI fails if a row has none or the artefact is missing or has no recorded pass status; consistency-only cases never count; for I1, I2, I9 and I10 only a real-store run counts. A row whose closing plan has not exited is OPEN and may have no evidence (spec 14.4: "Until a plan exits, the invariant is shown as open"). This reading of the rule is an owner decision (Appendix B).

**Files:**
- Create: `regpack-tools/src/main/java/dev/regpack/tools/TraceabilityCheck.java`, `regpack-tools/src/test/java/dev/regpack/tools/TraceabilityCheckTest.java`, `docs/traceability.md`, `docs/reviews/R14-process-checklist.md`
- Modify: `regpack-tools/src/main/java/dev/regpack/tools/Gates.java` (command `traceability`)

**Interfaces:**
```java
public final class TraceabilityCheck {
  public record Evidence(String kind, String ref, String store, String ci, String note) {}
  public record Row(String id, List<String> closesIn, List<Evidence> evidence) {}
  public record Result(List<String> problems, int rows, int closed, int open) { public boolean ok(); }
  public static Result check(String markdown, Path repoRoot, List<Path> scanRoots);
}
// Gates: traceability --file docs/traceability.md [--root .] --scan <dir> [--scan <dir> ...]
//   prints "traceability: OK rows=<n> closed=<c> open=<o>" (exit 0) or one "traceability: PROBLEM <text>" line per problem (exit 1)
```
File grammar: a line `exited_plans: P0,P1` (or `none`), then a Markdown table `| row | closes_in | kind | ref | store | ci | note |`; one evidence per line; several lines may share a row id; `closes_in` is a comma-separated list of plan ids matching `P[0-9]+[ab]?`; `kind` is `TEST` (`ref` = `fully.qualified.Class#method`), `GOLDEN` (`ref` = a golden case id), `BENCHMARK`, `SPIKE`, `GATE`, `REVIEW` (`ref` = repository-relative file path) or `NONE` (`ref` = `-`); `store` is `UNIT`, `INMEMORY`, `REALSTORE` or `NA`; `ci` is `BUILD`, `NIGHTLY`, `RC` or `NA`. Evidence resolution under the scan roots (the scan skips directories named `.git` and `node_modules`): a `TEST` passes when some `TEST-*.xml` (surefire or failsafe) has a `testcase` whose `classname` equals the class and whose `name` equals the method (a trailing `()` ignored) and which has no `failure`, `error` or `skipped` child; a `GOLDEN` passes when a `golden-audit.tsv` row for the id has `true`; file kinds pass when the file exists and holds a line `status: PASS` (REVIEW also needs a non-blank `owner:` line). Rules and the problem tokens: every id R1-R14 and I1-I10 has at least one line (`MISSING_ROW:<id>`); every cited artefact exists (`ARTEFACT_MISSING:<id>:<ref>`) and has a recorded pass status, where a failing or skipped test and a file status other than `PASS` are `NOT_PASSING:<id>:<ref>`, with one tolerance: a file status of `PENDING` is accepted on a row that is still open (it never counts); a row is *due* when every plan in `closes_in` is in `exited_plans`, otherwise it is open; a due row needs at least one counting evidence: passing, not `NONE`, and not interim, where interim means the row is I1, I2, I9 or I10 and the evidence `store` is not `REALSTORE` (`ROW_WITHOUT_EVIDENCE:<id>`); a plan id outside the pattern is `BAD_PLAN:<id>:<plan>`.

- [ ] **Step 1: Write the failing tests** (`TraceabilityCheckTest`; each builds a table with all 24 ids, a `@TempDir` repository and hand-written surefire XML such as `<testsuite><testcase classname="a.B" name="m()"/></testsuite>`): `missingRowFails` (no I7 line gives `MISSING_ROW:I7`); `openRowMayHaveNoEvidence` (I3 closes `P5,P8`, nothing exited: ok); `rowWithoutEvidenceFailsWhenItsPlanHasExited` (I5 closes `P2a,P6a`, both exited, only a `NONE` line: `ROW_WITHOUT_EVIDENCE:I5`); `missingTestArtefactFails`; `skippedTestDoesNotCount` (the testcase has a `<skipped/>` child: `NOT_PASSING`); `testNamesWithParenthesesAreMatched` (`name="m()"` matches ref `a.B#m`); `inMemoryOnlyEvidenceDoesNotCloseI1` (I1 due with one passing `INMEMORY` test gives `ROW_WITHOUT_EVIDENCE:I1`; adding a passing `REALSTORE` test makes it ok; the same `INMEMORY` evidence closes R12); `consistencyOnlyGoldenNeverCounts` (a `golden-audit.tsv` row with `false` leaves a due I5 without evidence); `reviewFileNeedsOwnerAndPassStatus` (on a due row `status: PENDING` does not count and gives `ROW_WITHOUT_EVIDENCE`; on an open row it is tolerated; `status: FAIL` or a missing status line is `NOT_PASSING`; a missing `owner:` line is reported).
- [ ] **Step 2: Run red.** `scripts/t.sh regpack-tools TraceabilityCheckTest`. Expected: `cannot find symbol ... TraceabilityCheck`.
- [ ] **Step 3: Implement** the parser (split on `|`, trim) and the rules; wire `Gates traceability`; parse `TEST-*.xml` with `DocumentBuilderFactory` with DTDs and external entities disabled.
- [ ] **Step 4: Run green.** Same command. Expected: `Tests run: 9, Failures: 0, Errors: 0, Skipped: 0`.
- [ ] **Step 5: Write `docs/traceability.md` and the R14 checklist.** `exited_plans: none`. Header lines and these rows (every row also carries `NA` for `store` and `ci` unless a test is cited, note column short). Closing plans: R1 `P1`; R2 `P9b`; R3 `P7a`; R4 `P3,P4`; R5 `P8`; R6 `P4,P8`; R7 `P2a,P12`; R8 `P9b`; R9 `P6a`; R10 `P5`; R11 `P5`; R12 `P3`; R13 `P4`; R14 `P0`; I1 `P3`; I2 `P3`; I3 `P5,P8`; I4 `P9b,P12`; I5 `P2a,P6a`; I6 `P3,P4,P8`; I7 `P4`; I8 `P8`; I9 `P3`; I10 `P3,P4` (these follow the spec 14.4 list for the invariants and the section 3 and 16 mapping for the requirements; the requirement rows are a proposal for the owner). Interim evidence (store `INMEMORY`, ci `BUILD`, note `interim: does not close the row`), all `TEST` kind: I1 `dev.regpack.inmemory.InMemoryLedgerStoreContractTest#beginSendingRequiresCurrentLeaseAndQueuedState` and `dev.regpack.engine.pipeline.ToyCrashIT#acknowledgementFollowsIngestCommit`; I2 `...InMemoryLedgerStoreContractTest#redeliveredBatchIsNoopAndReturnsOriginalResult`, `#inFlightGuardAllowsOnlyOneDeliveryPerBusinessKey` and `#staleExpectedHeadSeqIsRejectedAndRollsBackWholeBatch`; I3 `dev.regpack.engine.pipeline.ToyCrashIT#sameFaultPlanGivesIdenticalTraceAndDigest`, `dev.regpack.model.NoWallClockRandomLocaleTest#modelMainClassesAreClean` and `dev.regpack.engine.NoWallClockRandomLocaleTest#engineMainClassesAreClean`; I5 `dev.regpack.engine.skeleton.ScopeEngineTest#naturalPersonIsUndeterminedNeverDoNotReport` and `#unmatchedInstrumentFallsToCatchAllUndetermined`; I6 `dev.regpack.engine.pipeline.ToyCrashIT#redeliveryAfterAckLossIsNoop`; I9 `...InMemoryLedgerStoreContractTest#takeoverMovesSendingDeliveriesToInDoubtAndFencesTheOldHolder`, `#restoreEpochRaisesTokenPairAndNoOldTokenEqualsNewOne`, `#restoreThenZombieStaleTokenIsFencedEvenBeforeExpiry` and `#concurrentAcquireGrantsExactlyOneHolder`; I10 `dev.regpack.inmemory.InMemoryEvidenceStoreContractTest#putSameKeyDifferentBytesIsIntegrityAlarmAndKeepsOriginal`; R12 `...InMemoryLedgerStoreContractTest#abaProducesThreeVersions`. R14 has a `REVIEW` line for `docs/reviews/R14-process-checklist.md` (store `NA`). All other rows have one `NONE` line. `docs/reviews/R14-process-checklist.md`:
  ```
  # R14 review checklist: built with superpowers (spec section 3, row R14)
  owner: hans
  status: PENDING
  - [ ] The approved spec and the P0 plan exist in the repository and the plan follows the writing-plans conventions
  - [ ] Every task was implemented test-first and its red run and green run were observed (commit history, one commit per task)
  - [ ] Every task had a spec-compliance review and a code-quality review before its commit was accepted
  - [ ] Oracle independence (spec 14.2): P0 golden cases are consistency-only, no case counts for traceability, reviewer registry is empty until gate G1
  - [ ] The final whole-branch review found no unresolved finding
  - [ ] The owner has read the port contracts and the exit record and signs by changing status to PASS
  ```
- [ ] **Step 6: Run the gate on the real file.** `./mvnw -B -ntp -q spotless:apply` then `./mvnw -B -ntp verify` (so the reports exist), then in Git Bash `"${REGPACK_JAVA:-java}" -cp regpack-tools/target/classes dev.regpack.tools.Gates traceability --file docs/traceability.md --root . --scan .` (in PowerShell on path A, plain `java -cp ...` works; on path B use Git Bash with `REGPACK_JAVA` set). Expected: `traceability: OK rows=24 closed=0 open=24`. If it reports `ARTEFACT_MISSING` for a cited test, the test name in the file is wrong (fix the file) or the test was not run (run `verify`, not `test`, because `ToyCrashIT` is a failsafe test).
- [ ] **Step 7: Commit.** `./mvnw -B -ntp -q spotless:apply`, `git add -A`, `git commit -m "feat(tools): add traceability gate and the spec 14.4 traceability file" -m "Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"`.

### Task 26 (T26): Pin and line-ending gates

**Files:**
- Create: `regpack-tools/src/main/java/dev/regpack/tools/PinCheck.java`, `EolCheck.java`, `regpack-tools/src/test/java/dev/regpack/tools/PinCheckTest.java`, `EolCheckTest.java`
- Modify: `Gates.java` (commands `pins` and `eol`)

**Interfaces:**
```java
public final class PinCheck {
  public static List<String> checkDockerfile(String name, String content);   // problems, empty when clean
  public static List<String> checkWorkflow(String name, String content);
}
public final class EolCheck {
  public static List<String> checkBytes(String path, byte[] content);
  public static List<String> check(Path root, List<String> relativePaths);   // reads files from disk; a path that no longer exists is skipped
}
// Gates: pins --dockerfile <file> --workflows <dir>      eol [--root .]   (relative paths, one per line, on stdin)
```
`PinCheck.checkDockerfile`: every `FROM` instruction (skipping `--platform=` style flags) whose image is not `scratch` and not an earlier stage alias must contain `@sha256:` followed by 64 lowercase hex characters, else `<name>:<line>: FROM not pinned by digest`; an image containing `${` is reported as unverifiable. An empty or missing workflow directory is a problem (`no workflow files in <dir>`). `checkWorkflow`: every `uses:` value not starting with `./` or `docker://` must match `owner/repo[/path]@<40 hex>` (`UNPINNED_ACTION`) and its line must carry a comment starting `# v` and a digit (`MISSING_VERSION_COMMENT`); a column-0 `permissions:` key must exist (`MISSING_PERMISSIONS`); `permissions: write-all`, `pull_request_target` and any `runs-on` value ending in `-latest` are problems (`WRITE_ALL`, `PULL_REQUEST_TARGET`, `LATEST_RUNNER`). `EolCheck.checkBytes` (by file name or extension; unknown types are ignored): no UTF-8 BOM (`BOM`); `.cmd` and `.bat` files must have CRLF on every line (`LF_IN_CMD`); other text types (`.sh .java .xml .md .yml .yaml .json .properties .service .tpl .txt .tsv .ps1 .gitignore .gitattributes`, and the names `mvnw` and `Dockerfile`) must contain no CR (`CR_IN_LF_FILE`); `.sh`, `.ps1` and `.service` files must be ASCII only (`NON_ASCII`; Windows PowerShell 5.1 misreads UTF-8 without a BOM); `.sh` files and `mvnw` must start with `#!` (`NO_SHEBANG`).

- [ ] **Step 1: Write the failing tests.** `PinCheckTest`: `unpinnedActionFails` (`uses: actions/checkout@v7`); `shaPinnedActionWithVersionCommentPasses` (`uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7.0.1` in a file that also has `permissions:` at column 0); `missingVersionCommentFails`; `missingTopLevelPermissionsFails`; `latestRunnerFails`; `pullRequestTargetFails`; `unpinnedFromFails` (`FROM eclipse-temurin:25-jre-noble`); `pinnedFromPasses`; `stageAliasReferencesAndScratchAreAllowed`. `EolCheckTest`: `crInShellScriptFails`; `bomFails`; `cmdWithBareLfFails`; `cmdWithCrlfPasses`; `nonAsciiInPowerShellFails`; `shellScriptWithoutShebangFails`; `lfJavaFilePasses`; `binaryAndUnknownExtensionsAreIgnored`.
- [ ] **Step 2: Run red.** `scripts/t.sh regpack-tools 'PinCheckTest,EolCheckTest'`. Expected: `cannot find symbol ... PinCheck`.
- [ ] **Step 3: Implement** both classes and the two `Gates` commands (`pins` prints `pins: OK` or one `pins: PROBLEM <text>` line per problem; `eol` likewise).
- [ ] **Step 4: Run green.** Same command. Expected: `Tests run: 17, Failures: 0, Errors: 0, Skipped: 0`. Then, from the repository root in Git Bash, `git ls-files | "${REGPACK_JAVA:-java}" -cp regpack-tools/target/classes dev.regpack.tools.Gates eol --root .` (the classes exist after the run above; on path B set `REGPACK_JAVA` first); expected `eol: OK`. `EolCheck` reads working-tree files, and a `.cmd` file written with LF endings (the Maven Wrapper generator may do this) is reported as `LF_IN_CMD`; convert it in the working tree only, the committed blob stays LF: PowerShell `$t = Get-Content -Raw mvnw.cmd; [IO.File]::WriteAllText("$PWD\mvnw.cmd", ($t -replace "(?<!`r)`n", "`r`n"))`. Do the same for every `.cmd` file you create (T28). (Files that later tasks add are checked again in T32.)
- [ ] **Step 5: Commit.** `./mvnw -B -ntp -q spotless:apply`, `git add -A`, `git commit -m "feat(tools): add pin and line-ending gates" -m "Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"`.

### Task 27 (T27): Supply-chain build configuration: SBOM and licence gate

Two aggregator goals run as separate Maven task segments after `verify` (lifecycle-bound executions of a parent POM run before the modules are built, so they cannot see the modules' artifacts): `cyclonedx:makeAggregateBom` writes the SBOM of the whole build, and `license:aggregate-add-third-party` fails the build for any dependency whose licence is not on the allow-list or is missing. The shipped SBOM of the application itself is produced per module in T28.

**Files:**
- Modify: `pom.xml` (plugin management for the two plugins)
- Create: `governance/licence-policy.md`, `governance/THIRD-PARTY.missing.properties`

**Interfaces:** none (build configuration). Deliverable: `./mvnw -B -ntp verify cyclonedx:makeAggregateBom license:aggregate-add-third-party -DincludeTestScope=true` succeeds and produces `target/bom.json` and `target/generated-sources/license/THIRD-PARTY.txt` at the repository root.

- [ ] **Step 1: Make the defect visible (red).** Add to `regpack-testkit/pom.xml` a `test`-scope dependency `javax.activation:javax.activation-api:1.2.0` (CDDL plus GPLv2 with classpath exception, a disallowed licence; it is a leaf artifact with no transitive dependencies, so it passes the enforcer's `dependencyConvergence` rule, and `bannedDependencies` looks only at compile and runtime scope; that is part of the point: only the licence gate can catch it). If the build fails at `validate` with a convergence or banned-dependency message, the chosen artifact is unsuitable: pick another leaf artifact with a non-allow-listed licence; do not edit the enforcer rules. Run `./mvnw -B -ntp verify cyclonedx:makeAggregateBom license:aggregate-add-third-party -DincludeTestScope=true`. Expected: `BUILD SUCCESS`. That success is the defect: a forbidden licence is not caught because no allow-list is configured yet.
- [ ] **Step 2: Add the plugin configuration** to `<pluginManagement>` in the root `pom.xml`:
  ```xml
  <plugin>
    <groupId>org.cyclonedx</groupId>
    <artifactId>cyclonedx-maven-plugin</artifactId>
    <version>${cyclonedx-maven-plugin.version}</version>
    <configuration>
      <projectType>application</projectType>
      <outputFormat>json</outputFormat>
      <outputName>bom</outputName>
    </configuration>
  </plugin>
  <plugin>
    <groupId>org.codehaus.mojo</groupId>
    <artifactId>license-maven-plugin</artifactId>
    <version>${license-maven-plugin.version}</version>
    <configuration>
      <acceptPomPackaging>true</acceptPomPackaging>
      <excludedGroups>^dev\.regpack$</excludedGroups>
      <failOnMissing>true</failOnMissing>
      <failOnBlacklist>true</failOnBlacklist>
      <includedLicenses>Apache-2.0|EPL-2.0|MIT|BSD-2-Clause|BSD-3-Clause</includedLicenses>
      <licenseMerges>
        <licenseMerge>Apache-2.0|The Apache Software License, Version 2.0|Apache License, Version 2.0|The Apache License, Version 2.0|Apache License 2.0|Apache 2.0</licenseMerge>
        <licenseMerge>EPL-2.0|Eclipse Public License v2.0|Eclipse Public License - v 2.0|Eclipse Public License 2.0</licenseMerge>
        <licenseMerge>MIT|The MIT License|MIT License</licenseMerge>
        <licenseMerge>BSD-2-Clause|BSD 2-Clause License|The BSD 2-Clause License</licenseMerge>
        <licenseMerge>BSD-3-Clause|BSD 3-Clause License|The BSD 3-Clause License|BSD-3-Clause License</licenseMerge>
      </licenseMerges>
      <missingFile>${maven.multiModuleProjectDirectory}/governance/THIRD-PARTY.missing.properties</missingFile>
    </configuration>
  </plugin>
  ```
- [ ] **Step 3: Write `governance/licence-policy.md`** (allow-list: Apache-2.0, EPL-2.0 for JUnit at test and build time only, MIT, BSD-2-Clause, BSD-3-Clause; anything else, in particular GPL, AGPL, LGPL, BSL, ELv2, SSPL, Commons Clause, a missing or unknown licence, needs explicit written approval recorded in this file with date and approver before the dependency is added; the rule that the allow-list lives in the root `pom.xml` and this file is its rationale; a line `Owner confirmation: recorded in docs/exit/P0.md (T33)`; and a section "Interim vulnerability handling (until P9b)": Dependabot alerts and security updates are on (T33 step 1); the weekly Dockerfile digest PR is the base-image control; the JDK patch level moves by changing the pinned Temurin version in `toolchain/README.md` and the CI `java-version`; a HIGH or CRITICAL advisory affecting a shipped component is triaged and recorded in `docs/exit/P0.md` before the owner signs P0; the gating scanner, for example OSV-Scanner or Trivy, is chosen in P9b) and an empty `governance/THIRD-PARTY.missing.properties` with a comment header explaining the `groupId--artifactId--version=licence name` format.
- [ ] **Step 4: Run the gate against the forbidden dependency (green for the gate).** With `javax.activation-api` still in `regpack-testkit/pom.xml`, re-run the Step 1 command. Expected: `BUILD FAILURE` from `license:aggregate-add-third-party` whose message names the artifact's group and artifactId (the exact wording is not fixed by this plan; record it in `docs/exit/P0.md`). If the build instead fails on a licence that should be allowed (a message naming a licence on another dependency), read that dependency's POM; if the licence is one of the five allowed, add its exact name to the matching `licenseMerge`; if it is not allowed, stop and ask the owner. Do not widen `includedLicenses` without recording the approval in `governance/licence-policy.md`.
- [ ] **Step 5: Remove the defect and confirm the clean build.** Remove the `javax.activation-api` dependency again with `git checkout -- regpack-testkit/pom.xml` and re-run the Step 1 command. Expected: `BUILD SUCCESS`; `target/bom.json` exists and its JSON has `"bomFormat"` equal to `CycloneDX`; `target/generated-sources/license/THIRD-PARTY.txt` lists JUnit (EPL-2.0), AssertJ, ByteBuddy, opentest4j and apiguardian and no `dev.regpack` entry.
- [ ] **Step 6: Commit.** `./mvnw -B -ntp -q spotless:apply`, `git add -A`, `git commit -m "build: add SBOM and licence allow-list gate" -m "Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"`.

### Task 28 (T28): Distribution assembly (one build, three targets)

**Files:**
- Modify: `regpack-app/pom.xml` (jar manifest, SBOM, assembly)
- Create: `regpack-app/src/assembly/dist.xml`, `regpack-app/src/dist/bin/regpack`, `regpack-app/src/dist/bin/regpack.cmd`, `packaging/systemd/README.md`, `packaging/windows/README.md` (one line each: what the directory will hold; they make the directories exist, T29 and T30 add the real files, and the descriptor's `<includes>` match nothing until then)
- Test: `regpack-app/src/test/java/dev/regpack/app/DistributionLayoutIT.java`

**Interfaces:** none (packaging). Deliverable: `regpack-app/target/regpack-app-0.1.0-SNAPSHOT-dist.zip` and `-dist.tar.gz`, each with the top directory `regpack-0.1.0-SNAPSHOT/` and the layout `bin/regpack`, `bin/regpack.cmd`, `lib/regpack-app.jar`, `lib/regpack-model.jar`, `lib/regpack-ports.jar`, `lib/regpack-inmemory.jar`, `lib/regpack-engine.jar`, `share/systemd/regpack.service`, `share/windows/*.ps1`, `share/sbom/regpack-bom.json`. Jar names carry no version so that the `Class-Path` of `regpack-app.jar` stays fixed and procrun can be given a single jar (T30).

- [ ] **Step 1: Write the failing integration test** `DistributionLayoutIT` (failsafe; finds the single `*-dist.zip` in `regpack.build.dir`): `distributionContainsExactlyTheExpectedFiles` (the five jars listed above and no others, so no third-party jar and no testkit; both launchers; the SBOM file; the entries `share/systemd/regpack.service` and `share/windows/install-procrun.ps1`, `install-service.ps1` are asserted only when present, because T29 and T30 add them, and those tasks change the assertion to required); `manifestClassPathNamesSiblingJarsWithoutVersions` (`lib/regpack-app.jar` manifest has `Main-Class: dev.regpack.app.Main` and a `Class-Path` whose tokens are exactly `regpack-model.jar regpack-ports.jar regpack-inmemory.jar regpack-engine.jar` as a set); `sbomIsCycloneDxJson` (`Json.parse` of `share/sbom/regpack-bom.json` is a map whose `bomFormat` is `CycloneDX`, with a `specVersion`, a `serialNumber` starting with `urn:uuid:`, `metadata.component.name` equal to `regpack-app`, and a non-empty `components` list that contains an entry named `regpack-engine` and one named `regpack-model` and no entry whose name starts with `junit`, `assertj`, `opentest4j`, `apiguardian` or `byte-buddy`; confirm the exact component names against the first real run and fix the assertion, not the intent); `extractedDistributionRunsTheSmokeCommand` (extract to a temp directory, run `<java.home>/bin/java -jar lib/regpack-app.jar smoke` with `ProcessBuilder`: exit code 0 and standard output contains `regpack smoke: OK decisions=3`).
- [ ] **Step 2: Run red.** `scripts/it.sh regpack-app DistributionLayoutIT`. Expected: failure `No dist zip found` (no assembly yet).
- [ ] **Step 3: Configure `regpack-app/pom.xml`** (inside `<project>`; the plugin order matters, the SBOM must be built before the assembly in the same phase):
  ```xml
  <build>
    <plugins>
      <plugin>
        <artifactId>maven-jar-plugin</artifactId>
        <configuration>
          <archive>
            <manifest>
              <mainClass>dev.regpack.app.Main</mainClass>
              <addClasspath>true</addClasspath>
              <classpathLayoutType>custom</classpathLayoutType>
              <customClasspathLayout>$${artifact.artifactId}.$${artifact.extension}</customClasspathLayout>
              <addDefaultImplementationEntries>true</addDefaultImplementationEntries>
            </manifest>
          </archive>
        </configuration>
      </plugin>
      <plugin>
        <groupId>org.cyclonedx</groupId>
        <artifactId>cyclonedx-maven-plugin</artifactId>
        <executions>
          <execution>
            <id>sbom</id>
            <phase>package</phase>
            <goals><goal>makeBom</goal></goals>
            <!-- explicit, so that the -DincludeTestScope=true of the aggregate licence run cannot leak test components into the shipped SBOM -->
            <configuration><includeTestScope>false</includeTestScope></configuration>
          </execution>
        </executions>
      </plugin>
      <plugin>
        <artifactId>maven-assembly-plugin</artifactId>
        <configuration>
          <descriptors><descriptor>src/assembly/dist.xml</descriptor></descriptors>
          <appendAssemblyId>true</appendAssemblyId>
          <tarLongFileMode>posix</tarLongFileMode>
        </configuration>
        <executions>
          <execution><id>dist</id><phase>package</phase><goals><goal>single</goal></goals></execution>
        </executions>
      </plugin>
    </plugins>
  </build>
  ```
  `regpack-app/src/assembly/dist.xml`:
  ```xml
  <assembly xmlns="http://maven.apache.org/ASSEMBLY/2.2.0"
            xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
            xsi:schemaLocation="http://maven.apache.org/ASSEMBLY/2.2.0 https://maven.apache.org/xsd/assembly-2.2.0.xsd">
    <id>dist</id>
    <formats><format>zip</format><format>tar.gz</format></formats>
    <includeBaseDirectory>true</includeBaseDirectory>
    <baseDirectory>regpack-${project.version}</baseDirectory>
    <dependencySets>
      <dependencySet>
        <outputDirectory>lib</outputDirectory>
        <useProjectArtifact>true</useProjectArtifact>
        <scope>runtime</scope>
        <outputFileNameMapping>${artifact.artifactId}.${artifact.extension}</outputFileNameMapping>
      </dependencySet>
    </dependencySets>
    <fileSets>
      <fileSet>
        <directory>src/dist/bin</directory>
        <outputDirectory>bin</outputDirectory>
        <fileMode>0755</fileMode>
        <lineEnding>keep</lineEnding>
      </fileSet>
      <fileSet>
        <directory>${maven.multiModuleProjectDirectory}/packaging/systemd</directory>
        <outputDirectory>share/systemd</outputDirectory>
        <includes><include>regpack.service</include></includes>
        <fileMode>0644</fileMode>
      </fileSet>
      <fileSet>
        <directory>${maven.multiModuleProjectDirectory}/packaging/windows</directory>
        <outputDirectory>share/windows</outputDirectory>
        <includes><include>*.ps1</include></includes>
        <fileMode>0644</fileMode>
      </fileSet>
    </fileSets>
    <files>
      <file>
        <source>${project.build.directory}/bom.json</source>
        <outputDirectory>share/sbom</outputDirectory>
        <destName>regpack-bom.json</destName>
      </file>
    </files>
  </assembly>
  ```
  `bin/regpack`:
  ```sh
  #!/bin/sh
  # regpack launcher: resolves its home, honours JAVA_HOME and JAVA_OPTS, and execs the JVM so signals reach it.
  set -eu
  APP_HOME=$(cd "$(dirname "$(readlink -f "$0")")/.." && pwd)
  if [ -n "${JAVA_HOME:-}" ]; then JAVA="$JAVA_HOME/bin/java"; else JAVA=java; fi
  # shellcheck disable=SC2086
  exec "$JAVA" ${JAVA_OPTS:-} -jar "$APP_HOME/lib/regpack-app.jar" "$@"
  ```
  `bin/regpack.cmd` (write it with CRLF line endings in the working tree, see the T26 note):
  ```bat
  @echo off
  setlocal
  set "APP_HOME=%~dp0.."
  if defined JAVA_HOME ( set "JAVA=%JAVA_HOME%\bin\java.exe" ) else ( set "JAVA=java.exe" )
  "%JAVA%" %JAVA_OPTS% -jar "%APP_HOME%\lib\regpack-app.jar" %*
  exit /b %ERRORLEVEL%
  ```
- [ ] **Step 4: Run green.** `scripts/it.sh regpack-app DistributionLayoutIT`. Expected: `Tests run: 4, Failures: 0, Errors: 0, Skipped: 0` in the failsafe summary. Then list the archive: `unzip -l regpack-app/target/regpack-app-0.1.0-SNAPSHOT-dist.zip` (PowerShell: `tar -tf <zip>`) and confirm the layout.
- [ ] **Step 5: Commit.** `./mvnw -B -ntp -q spotless:apply`, `git add -A`, `git commit -m "build(app): assemble the distribution with SBOM and launchers" -m "Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"`.

### Task 29 (T29): Linux packaging: hardened systemd unit and its smoke run

**Files:**
- Create: `packaging/systemd/regpack.service`, `packaging/systemd/smoke.sh`
- Modify: `packaging/systemd/README.md` (install and uninstall notes), `regpack-app/src/test/java/dev/regpack/app/DistributionLayoutIT.java` (the unit entry becomes required)
- Create (test): `regpack-app/src/test/java/dev/regpack/app/PackagingFilesTest.java` (T30 and T31 add methods)

**Interfaces:** none. Deliverable: a unit that `systemd-analyze verify` accepts without output, and a smoke script that installs the distribution under `/opt/regpack`, starts the unit, waits for readiness, runs `regpack smoke --health-url`, stops the unit and asserts `Result=success` plus the `stopped cleanly` marker in the journal. The machine-level run happens in CI (T32, T33); locally on Windows the static test below is the red-green evidence.

- [ ] **Step 1: Write the failing test** `PackagingFilesTest#systemdUnitHasHardeningAndCleanStopSettings`. It reads `packaging/systemd/regpack.service` under `regpack.repo.root` and asserts these lines exist exactly: `Type=simple`, `DynamicUser=yes`, `StateDirectory=regpack`, `EnvironmentFile=-/etc/regpack/regpack.env`, `ExecStart=/opt/regpack/bin/regpack serve`, `SuccessExitStatus=143`, `NoNewPrivileges=yes`, `ProtectSystem=strict`, `ProtectHome=yes`, `PrivateTmp=yes`, `RestrictAddressFamilies=AF_INET AF_INET6 AF_UNIX`, `CapabilityBoundingSet=`, `Restart=on-failure`; that no line starts with `User=` or `Group=`; and that the file is ASCII with LF endings. The same test also asserts that `packaging/systemd/smoke.sh` contains `--no-same-owner` and `--destructive`.
- [ ] **Step 2: Run red.** `scripts/t.sh regpack-app PackagingFilesTest`. Expected: failure `NoSuchFileException ... regpack.service`.
- [ ] **Step 3: Write `packaging/systemd/regpack.service`:**
  ```ini
  [Unit]
  Description=regpack regulatory reporting engine (walking skeleton)
  After=network-online.target
  Wants=network-online.target

  [Service]
  Type=simple
  DynamicUser=yes
  StateDirectory=regpack
  EnvironmentFile=-/etc/regpack/regpack.env
  ExecStart=/opt/regpack/bin/regpack serve
  Restart=on-failure
  RestartSec=5
  KillSignal=SIGTERM
  TimeoutStopSec=30
  SuccessExitStatus=143
  NoNewPrivileges=yes
  PrivateTmp=yes
  PrivateDevices=yes
  ProtectSystem=strict
  ProtectHome=yes
  ProtectKernelTunables=yes
  ProtectKernelModules=yes
  ProtectKernelLogs=yes
  ProtectControlGroups=yes
  ProtectClock=yes
  ProtectHostname=yes
  RestrictAddressFamilies=AF_INET AF_INET6 AF_UNIX
  RestrictNamespaces=yes
  RestrictRealtime=yes
  RestrictSUIDSGID=yes
  LockPersonality=yes
  SystemCallArchitectures=native
  CapabilityBoundingSet=
  UMask=0077

  [Install]
  WantedBy=multi-user.target
  ```
  `SuccessExitStatus=143` is there because a JVM stopped by SIGTERM exits with status 143 after its shutdown hook has run; `MemoryDenyWriteExecute` is deliberately absent because the JIT needs writable-executable memory. `/etc/regpack/regpack.env` holds `JAVA_HOME`, `REGPACK_HTTP_BIND` and `REGPACK_HTTP_PORT` and is optional (the leading `-`).
- [ ] **Step 4: Write `packaging/systemd/smoke.sh`:**
  ```bash
  #!/usr/bin/env bash
  # Smoke run of the regpack distribution as a systemd service (plan P0; spec 5.2 and 14.5).
  # Usage: sudo --preserve-env=JAVA_HOME bash packaging/systemd/smoke.sh <path to regpack-*-dist.tar.gz> --destructive
  set -euo pipefail

  DIST="${1:?usage: smoke.sh <dist .tar.gz> --destructive}"
  PORT="${REGPACK_SMOKE_PORT:-18080}"
  APP_HOME=/opt/regpack
  UNIT=/etc/systemd/system/regpack.service

  [ "$(id -u)" -eq 0 ] || { echo "smoke: run as root"; exit 1; }
  [ "$(ps -p 1 -o comm=)" = "systemd" ] || { echo "smoke: PID 1 is not systemd"; exit 1; }
  [ "${2:-}" = "--destructive" ] || { echo "smoke: this script deletes ${APP_HOME}, /etc/regpack/regpack.env and ${UNIT}; pass --destructive as the second argument on a disposable host"; exit 1; }
  : "${JAVA_HOME:?JAVA_HOME must point at a JDK 25 (preserve it through sudo)}"

  cleanup() {
    systemctl stop regpack >/dev/null 2>&1 || true
    rm -f "$UNIT" /etc/regpack/regpack.env
    systemctl daemon-reload || true
  }
  trap cleanup EXIT

  rm -rf "$APP_HOME"; mkdir -p "$APP_HOME"
  tar -xzf "$DIST" -C "$APP_HOME" --strip-components=1 --no-same-owner
  chown -R root:root "$APP_HOME"
  install -d /etc/regpack
  install -m 0600 -o root -g root /dev/null /etc/regpack/regpack.env
  printf 'JAVA_HOME=%s\nREGPACK_HTTP_BIND=127.0.0.1\nREGPACK_HTTP_PORT=%s\n' "$JAVA_HOME" "$PORT" >> /etc/regpack/regpack.env
  install -m 0644 "$APP_HOME/share/systemd/regpack.service" "$UNIT"

  # 1. the unit must verify with no message at all
  out=$(systemd-analyze verify "$UNIT" 2>&1) || { echo "$out"; echo "smoke: systemd-analyze verify failed"; exit 1; }
  [ -z "$out" ] || { echo "$out"; echo "smoke: systemd-analyze verify printed warnings"; exit 1; }

  # 2. start and wait for readiness
  systemctl daemon-reload
  systemctl start regpack
  ready=no
  for _ in $(seq 1 60); do
    if curl -fsS "http://127.0.0.1:${PORT}/health/ready" >/dev/null 2>&1; then ready=yes; break; fi
    sleep 1
  done
  [ "$ready" = yes ] || { journalctl -u regpack --no-pager | tail -50; echo "smoke: not ready after 60 s"; exit 1; }

  # 3. the toy pipeline from the installed build, plus the health assertions
  "$APP_HOME/bin/regpack" smoke --health-url "http://127.0.0.1:${PORT}"

  # 4. stop and assert a clean stop
  systemctl stop regpack
  result=$(systemctl show regpack -p Result --value)
  [ "$result" = "success" ] || { journalctl -u regpack --no-pager | tail -50; echo "smoke: unit Result=$result"; exit 1; }
  journalctl -u regpack --no-pager | grep -q 'regpack lifecycle: stopped cleanly' \
    || { journalctl -u regpack --no-pager | tail -50; echo "smoke: no clean-stop marker"; exit 1; }
  echo "smoke: linux systemd OK"
  ```
  Mark it executable: `git update-index --add --chmod=+x packaging/systemd/smoke.sh`. Update `README.md` in that directory with the install steps (`tar -xzf ... --strip-components=1 --no-same-owner` into `/opt/regpack`, `chown -R root:root /opt/regpack`, copy the unit, `daemon-reload`, `enable --now`; the env file is mode 0600 owned by root) and the uninstall steps. In `DistributionLayoutIT.distributionContainsExactlyTheExpectedFiles` require `share/systemd/regpack.service` (remove the "only when present" allowance for it).
- [ ] **Step 5: Run green.** `scripts/t.sh regpack-app PackagingFilesTest` expects `Tests run: 1, Failures: 0`; `scripts/it.sh regpack-app DistributionLayoutIT` expects `Tests run: 4, Failures: 0`. Confirm `unzip -l regpack-app/target/regpack-app-0.1.0-SNAPSHOT-dist.zip | grep regpack.service` shows `share/systemd/regpack.service`.
- [ ] **Step 6: Commit.** `./mvnw -B -ntp -q spotless:apply`, `git add -A`, `git commit -m "build(packaging): add hardened systemd unit and Linux smoke script" -m "Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"`.

### Task 30 (T30): Windows packaging: procrun service and its smoke run

Evidence for the wrapper choice (the owner asked for a maintained wrapper, pinned and checksum-verified): WinSW's last stable release is v2.12.0 (2023-01-28), v3 has been alpha since 2023-01-29, and its release assets carry no published checksum; Apache Commons Daemon procrun 1.6.1 was released 2026-06 as an ASF release with a published SHA-512 and PGP signature, runs the JVM in-process (`jvm` mode) and calls a Java stop method, so a service stop is a real graceful shutdown rather than a process kill. WinSW is therefore not used (Appendix B, owner decision 5).

**Files:**
- Create: `packaging/windows/install-procrun.ps1`, `packaging/windows/install-service.ps1`, `packaging/windows/smoke.ps1`
- Modify: `packaging/windows/README.md`, `DistributionLayoutIT.java` (the `share/windows/*.ps1` entries become required), `PackagingFilesTest.java`

**Interfaces:** none. All scripts are ASCII only and PowerShell 5.1 compatible (the owner machine) as well as PowerShell 7 (CI).

- [ ] **Step 1: Write the failing test** `PackagingFilesTest#windowsScriptsPinProcrunChecksumAndAreAscii`: the three scripts exist and are pure ASCII; `install-procrun.ps1` contains the SHA-512 `a878177edc92e663c1113858f8903451ec02b5b033bca373bea4f8761d9196323771301fcd6a7b8387d8e2300f7a1056b9e08e83fca9703df32ac3ecd5b129f7`, `Get-FileHash -Algorithm SHA512` and `commons-daemon-1.6.1-bin-windows.zip`; `install-service.ps1` contains `--StartMode=jvm`, `--StopMode=jvm`, `dev.regpack.app.WindowsServiceEntry`, `--StartMethod=start`, `--StopMethod=stop`, `bin\server\jvm.dll`, `--ServiceUser` and `icacls`; `smoke.ps1` contains `stopped cleanly`, `ExitCode` and `-Destructive`.
- [ ] **Step 2: Run red.** `scripts/t.sh regpack-app PackagingFilesTest`. Expected: the new test fails with `NoSuchFileException ... install-procrun.ps1`.
- [ ] **Step 3: Write `packaging/windows/install-procrun.ps1`** (downloads from the Apache distribution host with the archive host as fallback, verifies SHA-512, prints the path of the 64-bit `prunsrv.exe`):
  ```powershell
  # Downloads Apache Commons Daemon procrun 1.6.1 and verifies its SHA-512 before using it.
  # Usage: install-procrun.ps1 -Dest <directory>      Prints the full path of amd64\prunsrv.exe.
  param([Parameter(Mandatory = $true)][string]$Dest)
  $ErrorActionPreference = 'Stop'
  $version = '1.6.1'
  $sha512 = 'a878177edc92e663c1113858f8903451ec02b5b033bca373bea4f8761d9196323771301fcd6a7b8387d8e2300f7a1056b9e08e83fca9703df32ac3ecd5b129f7'
  $name = "commons-daemon-$version-bin-windows.zip"
  $urls = @(
    "https://downloads.apache.org/commons/daemon/binaries/windows/$name",
    "https://archive.apache.org/dist/commons/daemon/binaries/windows/$name"
  )
  $zip = Join-Path ([System.IO.Path]::GetTempPath()) $name
  if (Test-Path $zip) { Remove-Item $zip -Force }
  foreach ($u in $urls) {
    try { Invoke-WebRequest -Uri $u -OutFile $zip -UseBasicParsing; break }
    catch { Write-Host "download failed from $u" }
  }
  if (-not (Test-Path $zip)) { throw 'procrun download failed from every host' }
  $actual = (Get-FileHash -Algorithm SHA512 -Path $zip).Hash.ToLowerInvariant()
  if ($actual -ne $sha512) { Remove-Item $zip -Force; throw "SHA-512 mismatch for ${name}: expected $sha512 got $actual" }
  New-Item -ItemType Directory -Force $Dest | Out-Null
  Expand-Archive -Path $zip -DestinationPath $Dest -Force
  $prunsrv = Get-ChildItem -Path $Dest -Recurse -Filter 'prunsrv.exe' |
    Where-Object { $_.Directory.Name -ieq 'amd64' } | Select-Object -First 1
  if (-not $prunsrv) {
    Get-ChildItem -Recurse $Dest | Select-Object -First 40 | ForEach-Object { Write-Host $_.FullName }
    throw 'amd64\prunsrv.exe not found in the archive (the archive layout differs from the documented one; stop and ask the owner)'
  }
  Write-Output $prunsrv.FullName
  ```
- [ ] **Step 4: Write `packaging/windows/install-service.ps1`:**
  ```powershell
  # Registers the regpack Windows service with procrun in jvm mode (the JVM runs inside prunsrv.exe).
  # Usage: install-service.ps1 -Prunsrv <prunsrv.exe> -AppHome <install dir> [-JavaHome <JDK 25 dir>] [-Port 8080] [-Bind 127.0.0.1] [-Startup auto|manual] [-ServiceUser 'NT AUTHORITY\LocalService']
  # The service never runs as LocalSystem. Only Administrators and SYSTEM may write the install directory; only logs is writable by the service.
  param(
    [Parameter(Mandatory = $true)][string]$Prunsrv,
    [Parameter(Mandatory = $true)][string]$AppHome,
    [string]$JavaHome = $env:JAVA_HOME,
    [int]$Port = 8080,
    [string]$Bind = '127.0.0.1',
    [ValidateSet('auto', 'manual')][string]$Startup = 'auto',
    [string]$ServiceName = 'regpack',
    [string]$ServiceUser = 'NT AUTHORITY\LocalService'
  )
  $ErrorActionPreference = 'Stop'
  if (-not $JavaHome) { throw 'JavaHome or JAVA_HOME must name a JDK 25' }
  $jvm = Join-Path $JavaHome 'bin\server\jvm.dll'
  if (-not (Test-Path $jvm)) { throw "jvm.dll not found: $jvm" }
  $jar = Join-Path $AppHome 'lib\regpack-app.jar'
  if (-not (Test-Path $jar)) { throw "regpack-app.jar not found: $jar" }
  $logs = Join-Path $AppHome 'logs'
  New-Item -ItemType Directory -Force $logs | Out-Null
  # SIDs keep the script locale independent: S-1-5-32-544 Administrators, S-1-5-18 SYSTEM, S-1-5-19 LocalService
  icacls $AppHome /inheritance:r /grant:r '*S-1-5-32-544:(OI)(CI)F' '*S-1-5-18:(OI)(CI)F' '*S-1-5-19:(OI)(CI)RX' | Out-Null
  icacls $logs /grant '*S-1-5-19:(OI)(CI)M' | Out-Null
  & $Prunsrv "//IS//$ServiceName" `
    "--DisplayName=regpack" "--Description=regpack regulatory reporting engine (walking skeleton)" `
    "--Install=$Prunsrv" "--Startup=$Startup" "--Jvm=$jvm" "--ServiceUser=$ServiceUser" `
    "--StartMode=jvm" "--StopMode=jvm" "--Classpath=$jar" `
    "--StartClass=dev.regpack.app.WindowsServiceEntry" "--StartMethod=start" "--StartParams=--bind;$Bind;--port;$Port" `
    "--StopClass=dev.regpack.app.WindowsServiceEntry" "--StopMethod=stop" "--StopTimeout=30" `
    "--LogPath=$logs" "--StdOutput=auto" "--StdError=auto" "--LogLevel=Info"
  if ($LASTEXITCODE -ne 0) { throw "prunsrv install failed with exit code $LASTEXITCODE" }
  ```
- [ ] **Step 5: Write `packaging/windows/smoke.ps1`:**
  ```powershell
  # Smoke run of the regpack distribution as a Windows service (plan P0; spec 5.2 and 14.5).
  # Run from an elevated PowerShell with JAVA_HOME pointing at a JDK 25.
  # Usage: smoke.ps1 -DistZip <regpack-*-dist.zip> -Destructive [-AppHome C:\regpack] [-Port 18080]
  # Destructive: removes the service named regpack and the directory AppHome. Use a disposable machine (CI, a Windows VM or Windows Sandbox).
  param(
    [Parameter(Mandatory = $true)][string]$DistZip,
    [string]$AppHome = 'C:\regpack',
    [int]$Port = 18080,
    [switch]$Destructive
  )
  $ErrorActionPreference = 'Stop'
  $name = 'regpack'
  if (-not $env:JAVA_HOME) { throw 'JAVA_HOME must point at a JDK 25' }
  if (-not $Destructive) { throw 'smoke.ps1 deletes the service named regpack and the directory given by -AppHome; rerun with -Destructive on a disposable machine' }

  function Remove-RegpackService([string]$prunsrv) {
    $existing = Get-CimInstance -ClassName Win32_Service -Filter "Name='$name'" -ErrorAction SilentlyContinue
    if ($existing -and ($existing.PathName -notlike "*$AppHome*")) {
      throw "a service named $name exists with path $($existing.PathName), which is not under $AppHome; refusing to remove it"
    }
    if (Get-Service -Name $name -ErrorAction SilentlyContinue) {
      Stop-Service -Name $name -Force -ErrorAction SilentlyContinue
      if ($prunsrv -and (Test-Path $prunsrv)) { & $prunsrv "//DS//$name" | Out-Null }
    }
  }

  $prunsrvInstalled = Join-Path $AppHome 'bin\prunsrv.exe'
  Remove-RegpackService $prunsrvInstalled
  if (Test-Path $AppHome) { Remove-Item $AppHome -Recurse -Force }
  $extract = Join-Path ([System.IO.Path]::GetTempPath()) 'regpack-dist-extract'
  if (Test-Path $extract) { Remove-Item $extract -Recurse -Force }
  Expand-Archive -Path $DistZip -DestinationPath $extract -Force
  $inner = Get-ChildItem -Path $extract -Directory | Select-Object -First 1
  Move-Item -Path $inner.FullName -Destination $AppHome

  $scripts = Join-Path $AppHome 'share\windows'
  $dl = Join-Path $AppHome 'procrun'
  $prunsrv = (& (Join-Path $scripts 'install-procrun.ps1') -Dest $dl | Select-Object -Last 1)
  Copy-Item -Path $prunsrv -Destination $prunsrvInstalled -Force
  & (Join-Path $scripts 'install-service.ps1') -Prunsrv $prunsrvInstalled -AppHome $AppHome -Port $Port -Startup manual

  try {
    Start-Service -Name $name
    $ready = $false
    for ($i = 0; $i -lt 60; $i++) {
      try {
        $r = Invoke-WebRequest -Uri "http://127.0.0.1:$Port/health/ready" -UseBasicParsing -TimeoutSec 2
        if ($r.StatusCode -eq 200) { $ready = $true; break }
      } catch { Start-Sleep -Seconds 1 }
    }
    if (-not $ready) { throw 'service not ready after 60 s' }

    & (Join-Path $AppHome 'bin\regpack.cmd') smoke --health-url "http://127.0.0.1:$Port"
    if ($LASTEXITCODE -ne 0) { throw "regpack smoke failed with exit code $LASTEXITCODE" }

    Stop-Service -Name $name
    $svc = Get-CimInstance -ClassName Win32_Service -Filter "Name='$name'"
    if ($svc.State -ne 'Stopped') { throw "service state is $($svc.State), expected Stopped" }
    if ($svc.ExitCode -ne 0) { throw "service ExitCode is $($svc.ExitCode), expected 0" }
    $marker = Get-ChildItem -Path (Join-Path $AppHome 'logs') -Filter 'service-stdout*.log' |
      Get-Content | Select-String -SimpleMatch 'regpack lifecycle: stopped cleanly'
    if (-not $marker) { throw 'no clean-stop marker in the service log' }
    Write-Host 'smoke: windows service OK'
  }
  finally {
    Remove-RegpackService $prunsrvInstalled
  }
  ```
  Update `README.md` in `packaging/windows` (what each script does; elevation; the Apache download is verified by SHA-512 and the procrun archive layout `amd64\prunsrv.exe` is checked at install time). Add one paragraph: the service runs as LocalService by default, never LocalSystem; only Administrators and SYSTEM may write the install directory; only `logs` is writable by the service; install under `$env:ProgramFiles\regpack` for real use, `C:\regpack` is used only by the disposable CI smoke; `smoke.ps1` needs `-Destructive`. In `DistributionLayoutIT` require the `share/windows/*.ps1` entries.
- [ ] **Step 6: Run green.** `scripts/t.sh regpack-app PackagingFilesTest` expects `Tests run: 2, Failures: 0`; `scripts/it.sh regpack-app DistributionLayoutIT` expects `Tests run: 4, Failures: 0`. The script is destructive, so it is recommended only in a disposable Windows VM or Windows Sandbox (an elevated PowerShell, downloads procrun, needs the owner's approval): `powershell -File packaging/windows/smoke.ps1 -DistZip regpack-app\target\regpack-app-0.1.0-SNAPSHOT-dist.zip -Destructive` ends with `smoke: windows service OK`; otherwise rely on CI, where T33 provides this evidence from the runner.
- [ ] **Step 7: Commit.** `./mvnw -B -ntp -q spotless:apply`, `git add -A`, `git commit -m "build(packaging): add procrun Windows service scripts and Windows smoke script" -m "Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"`.

### Task 31 (T31): Container image, Helm chart and the Kubernetes smoke

Base image evidence: `eclipse-temurin:25-jre-noble` (Ubuntu 24.04, JRE image, about 107 MB for amd64) pinned by the index digest below, fetched from the Docker Hub tag API on 2026-10-09; the Alpine variant is smaller (about 75 MB) but uses musl, which is a risk for a later FIPS or native-library decision (spec 5.3 flip conditions); a jlink-built runtime is possible later and is a P9b packaging choice. The digest policy: the `FROM` line must carry `@sha256:`; Dependabot proposes digest bumps weekly (T32); `PinCheck` fails the build otherwise. Unverified assumption, checked by CI before the smoke: the JRE image contains the module `jdk.httpserver` (the CI step runs `java --list-modules`). If the check fails, switch the base to `eclipse-temurin:25-jdk-noble@sha256:589ff4cc3f71aab462e7048a47a0d10edf57fbccde3fceea2281e610bf5880b4` (re-checked against Appendix A), change the `FROM` assertions in `PackagingFilesTest#dockerfileIsPinnedByDigestAndRunsNonRoot` and the pins example in the same commit, and record it in the exit record.

**Files:**
- Create: `packaging/container/Dockerfile`, `packaging/helm/regpack/Chart.yaml`, `values.yaml`, `values.schema.json`, `templates/deployment.yaml`, `templates/service.yaml`, `packaging/helm/verify-chart.sh`, `packaging/helm/smoke.sh`
- Modify: `PackagingFilesTest.java`

**Interfaces:** none. Deliverable: an image that runs `serve --bind 0.0.0.0 --port 8080` as UID 10001, and a chart whose rendered output has one replica, `Recreate`, three probes against `/health/live` and `/health/ready`, and which refuses any other replica count.

- [ ] **Step 1: Write the failing tests** in `PackagingFilesTest`: `dockerfileIsPinnedByDigestAndRunsNonRoot` (a `FROM eclipse-temurin:25-jre-noble@sha256:` line followed by 64 hex characters; `USER 10001:10001`; an `ENTRYPOINT` in exec form that starts with `["java"`; no occurrence of `:latest`); `helmChartDeclaresProbesSingleReplicaAndRecreate` (the templates contain `startupProbe:`, `livenessProbe:`, `readinessProbe:`, `path: /health/live`, `path: /health/ready`, `type: Recreate` and `replicas: {{ .Values.replicaCount }}`; `values.schema.json` contains `"const": 1`; `values.yaml` has `replicaCount: 1`; the templates contain `automountServiceAccountToken: false`).
- [ ] **Step 2: Run red.** `scripts/t.sh regpack-app PackagingFilesTest`. Expected: the two new tests fail with `NoSuchFileException`.
- [ ] **Step 3: Write `packaging/container/Dockerfile`:**
  ```dockerfile
  FROM eclipse-temurin:25-jre-noble@sha256:d9a39a23634650173f1e2bbc176227af9728587ecf0f4b62d53e9355cd7a19ab
  LABEL org.opencontainers.image.title="regpack" \
        org.opencontainers.image.description="regpack walking skeleton (plan P0)"
  COPY --chown=10001:10001 regpack-dist/ /opt/regpack/
  USER 10001:10001
  WORKDIR /opt/regpack
  EXPOSE 8080
  ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/opt/regpack/lib/regpack-app.jar"]
  CMD ["serve", "--bind", "0.0.0.0", "--port", "8080"]
  ```
  (The build context is a directory holding this file and the extracted distribution as `regpack-dist/`; T32 builds it that way.)
- [ ] **Step 4: Write the chart.** `Chart.yaml`:
  ```yaml
  apiVersion: v2
  name: regpack
  description: regpack walking skeleton (plan P0): one active replica with health probes
  type: application
  version: 0.1.0
  appVersion: "0.1.0-SNAPSHOT"
  ```
  `values.yaml`:
  ```yaml
  # One active replica (spec 5.2, invariant I9): enforced by values.schema.json, not by convention.
  replicaCount: 1
  image:
    repository: regpack
    tag: ci
    digest: ""          # sha256:... for releases; overrides the tag
    pullPolicy: IfNotPresent
  http:
    port: 8080
    bind: 0.0.0.0
  roles: all
  terminationGracePeriodSeconds: 30
  resources:
    requests:
      cpu: 100m
      memory: 256Mi
    limits:
      memory: 512Mi
  probes:
    startup:
      periodSeconds: 2
      failureThreshold: 30
    liveness:
      periodSeconds: 10
      timeoutSeconds: 2
      failureThreshold: 3
    readiness:
      periodSeconds: 5
      timeoutSeconds: 2
      failureThreshold: 3
  ```
  `values.schema.json`:
  ```json
  {
    "$schema": "http://json-schema.org/draft-07/schema#",
    "type": "object",
    "required": ["replicaCount", "image", "http"],
    "properties": {
      "replicaCount": {
        "type": "integer",
        "const": 1,
        "description": "One active replica: a second live sender would breach invariant I9"
      },
      "image": {
        "type": "object",
        "required": ["repository"],
        "properties": {
          "repository": { "type": "string", "minLength": 1 },
          "tag": { "type": "string" },
          "digest": { "type": "string", "pattern": "^(sha256:[0-9a-f]{64})?$" },
          "pullPolicy": { "enum": ["Always", "IfNotPresent", "Never"] }
        }
      },
      "http": {
        "type": "object",
        "properties": {
          "port": { "type": "integer", "minimum": 1, "maximum": 65535 },
          "bind": { "type": "string" }
        }
      },
      "roles": { "type": "string" }
    }
  }
  ```
  `templates/deployment.yaml`:
  ```yaml
  apiVersion: apps/v1
  kind: Deployment
  metadata:
    name: {{ .Release.Name }}
    labels:
      app.kubernetes.io/name: regpack
      app.kubernetes.io/instance: {{ .Release.Name }}
  spec:
    replicas: {{ .Values.replicaCount }}
    strategy:
      type: Recreate
    selector:
      matchLabels:
        app.kubernetes.io/name: regpack
        app.kubernetes.io/instance: {{ .Release.Name }}
    template:
      metadata:
        labels:
          app.kubernetes.io/name: regpack
          app.kubernetes.io/instance: {{ .Release.Name }}
      spec:
        terminationGracePeriodSeconds: {{ .Values.terminationGracePeriodSeconds }}
        automountServiceAccountToken: false
        securityContext:
          runAsNonRoot: true
          runAsUser: 10001
          runAsGroup: 10001
          seccompProfile:
            type: RuntimeDefault
        containers:
          - name: regpack
            image: "{{ .Values.image.repository }}{{ if .Values.image.digest }}@{{ .Values.image.digest }}{{ else }}:{{ .Values.image.tag }}{{ end }}"
            imagePullPolicy: {{ .Values.image.pullPolicy }}
            args: ["serve", "--roles", {{ .Values.roles | quote }}, "--bind", {{ .Values.http.bind | quote }}, "--port", {{ .Values.http.port | quote }}]
            ports:
              - name: http
                containerPort: {{ .Values.http.port }}
            startupProbe:
              httpGet:
                path: /health/live
                port: http
              periodSeconds: {{ .Values.probes.startup.periodSeconds }}
              failureThreshold: {{ .Values.probes.startup.failureThreshold }}
            livenessProbe:
              httpGet:
                path: /health/live
                port: http
              periodSeconds: {{ .Values.probes.liveness.periodSeconds }}
              timeoutSeconds: {{ .Values.probes.liveness.timeoutSeconds }}
              failureThreshold: {{ .Values.probes.liveness.failureThreshold }}
            readinessProbe:
              httpGet:
                path: /health/ready
                port: http
              periodSeconds: {{ .Values.probes.readiness.periodSeconds }}
              timeoutSeconds: {{ .Values.probes.readiness.timeoutSeconds }}
              failureThreshold: {{ .Values.probes.readiness.failureThreshold }}
            resources:
              {{- toYaml .Values.resources | nindent 12 }}
            securityContext:
              allowPrivilegeEscalation: false
              readOnlyRootFilesystem: true
              capabilities:
                drop: ["ALL"]
            volumeMounts:
              - name: tmp
                mountPath: /tmp
        volumes:
          - name: tmp
            emptyDir: {}
  ```
  `templates/service.yaml`:
  ```yaml
  apiVersion: v1
  kind: Service
  metadata:
    name: {{ .Release.Name }}
    labels:
      app.kubernetes.io/name: regpack
      app.kubernetes.io/instance: {{ .Release.Name }}
  spec:
    type: ClusterIP
    selector:
      app.kubernetes.io/name: regpack
      app.kubernetes.io/instance: {{ .Release.Name }}
    ports:
      - name: http
        port: {{ .Values.http.port }}
        targetPort: http
  ```
- [ ] **Step 5: Write `packaging/helm/verify-chart.sh`** (review focus RF21; needs a Helm binary, which CI installs pinned; the owner machine has none, so this runs in CI):
  ```bash
  #!/usr/bin/env bash
  # Static assertions on the rendered chart. Usage: packaging/helm/verify-chart.sh [helm binary]
  set -euo pipefail
  cd "$(dirname "$0")/../.."
  HELM="${1:-helm}"
  CHART=packaging/helm/regpack
  "$HELM" lint "$CHART"
  rendered=$("$HELM" template regpack "$CHART")
  need() { printf '%s\n' "$rendered" | grep -Eq "$1" || { echo "verify-chart: missing: $1"; exit 1; }; }
  need '^[[:space:]]+replicas: 1$'
  need '^[[:space:]]+type: Recreate$'
  need 'path: /health/live'
  need 'path: /health/ready'
  need 'startupProbe:'
  need 'livenessProbe:'
  need 'readinessProbe:'
  need 'readOnlyRootFilesystem: true'
  need 'runAsNonRoot: true'
  need 'allowPrivilegeEscalation: false'
  need 'automountServiceAccountToken: false'
  for bad in 0 2 3; do
    if "$HELM" template regpack "$CHART" --set "replicaCount=$bad" >/dev/null 2>&1; then
      echo "verify-chart: replicaCount=$bad must be rejected by values.schema.json"; exit 1
    fi
  done
  echo "verify-chart: OK"
  ```
- [ ] **Step 6: Write `packaging/helm/smoke.sh`:**
  ```bash
  #!/usr/bin/env bash
  # Kubernetes smoke (plan P0): kind cluster, Helm install, health, toy pipeline, clean stop.
  # Usage: packaging/helm/smoke.sh <locally built image, for example regpack:ci> [helm binary] [kind binary]
  set -euo pipefail
  cd "$(dirname "$0")/../.."
  IMAGE="${1:?usage: smoke.sh <image> [helm] [kind]}"
  HELM="${2:-helm}"
  KIND="${3:-kind}"
  NODE_IMAGE='kindest/node:v1.36.4@sha256:099e049362a1526b2db71494e1947aae99bd16290d7c895f2b7ea312e3cbfaed'
  CLUSTER=regpack-smoke
  NS=regpack
  REPO="${IMAGE%%:*}"
  TAG="${IMAGE##*:}"

  cleanup() {
    "$KIND" delete cluster --name "$CLUSTER" >/dev/null 2>&1 || true
  }
  trap cleanup EXIT

  "$KIND" create cluster --name "$CLUSTER" --image "$NODE_IMAGE" --wait 180s
  "$KIND" load docker-image "$IMAGE" --name "$CLUSTER"
  kubectl create namespace "$NS"
  "$HELM" install regpack packaging/helm/regpack --namespace "$NS" \
    --set image.repository="$REPO" --set image.tag="$TAG" --wait --timeout 180s
  kubectl -n "$NS" rollout status deploy/regpack --timeout=120s

  POD=$(kubectl -n "$NS" get pod -l app.kubernetes.io/name=regpack -o jsonpath='{.items[0].metadata.name}')
  kubectl -n "$NS" exec "$POD" -- java -jar /opt/regpack/lib/regpack-app.jar smoke --health-url http://127.0.0.1:8080

  # Clean stop: SIGTERM to PID 1 is the signal the kubelet sends. The container restarts (restartPolicy Always),
  # and the previous container's exit code and log are the evidence.
  kubectl -n "$NS" exec "$POD" -- sh -c 'kill -TERM 1'
  rc=0
  for _ in $(seq 1 60); do
    rc=$(kubectl -n "$NS" get pod "$POD" -o jsonpath='{.status.containerStatuses[0].restartCount}')
    [ "$rc" = 1 ] && break
    sleep 1
  done
  [ "$rc" = 1 ] || { kubectl -n "$NS" describe pod "$POD"; echo "smoke: container did not restart after SIGTERM"; exit 1; }
  code=$(kubectl -n "$NS" get pod "$POD" -o jsonpath='{.status.containerStatuses[0].lastState.terminated.exitCode}')
  [ "$code" = 143 ] || { echo "smoke: exit code $code, expected 143"; exit 1; }
  kubectl -n "$NS" logs "$POD" --previous | grep -q 'regpack lifecycle: stopped cleanly' \
    || { kubectl -n "$NS" logs "$POD" --previous; echo "smoke: no clean-stop marker"; exit 1; }
  echo "smoke: kubernetes OK"
  ```
  Mark both scripts executable with `git update-index --add --chmod=+x`.
- [ ] **Step 7: Run green.** `scripts/t.sh regpack-app PackagingFilesTest`. Expected: `Tests run: 4, Failures: 0, Errors: 0, Skipped: 0`. The chart itself is rendered and the image built and run in CI (T32, T33); if Docker is available locally (it is, 29.1.2) the owner may also run the build part of the CI step by hand, which pulls the pinned base image and therefore needs approval.
- [ ] **Step 8: Commit.** `./mvnw -B -ntp -q spotless:apply`, `git add -A`, `git commit -m "build(packaging): add container image, Helm chart and Kubernetes smoke script" -m "Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"`.

### Task 32 (T32): GitHub Actions workflows, Dependabot and the gate script

**Files:**
- Create: `.github/workflows/ci.yml`, `.github/dependabot.yml`, `scripts/check-gates.sh`

**Interfaces:** none. Deliverable: a workflow whose jobs are `build` (matrix `ubuntu-24.04`, `windows-2025`), `determinism-compare`, `smoke-linux`, `smoke-windows` and `smoke-kubernetes`; every action pinned by full commit SHA with a version comment; top-level `permissions: contents: read`; no `*-latest` runner label. Action pins verified on 2026-10-09 against the GitHub tags API: checkout v7.0.1 = `3d3c42e5aac5ba805825da76410c181273ba90b1`, setup-java v6.0.1 = `de7274f081f381c8f8158605e0321c36c376e2e6`, upload-artifact v7.0.1 = `043fb46d1a93c77aae656e7c1c64a875d1fc6a0a`, download-artifact v8.0.1 = `3e5f45b2cfb9172054b4087a40e8e0b5a5461e7c`.

- [ ] **Step 1: Observe the gate red.** Build the tools classes (`./mvnw -B -ntp -pl regpack-tools -am package -DskipTests`), then run, in Git Bash, `"${REGPACK_JAVA:-java}" -cp regpack-tools/target/classes dev.regpack.tools.Gates pins --dockerfile packaging/container/Dockerfile --workflows .github/workflows` (on path B `REGPACK_JAVA` is set as in T1 Step 6B). Expected: exit 1 with a problem such as `pins: PROBLEM no workflow files in .github/workflows`.
- [ ] **Step 2: Write `scripts/check-gates.sh`:**
  ```bash
  #!/usr/bin/env bash
  # Repository gates that need build output. Run after: ./mvnw verify
  set -euo pipefail
  cd "$(dirname "$0")/.."
  BUILD_ROOT="${REGPACK_BUILD_ROOT:-}"
  if [ -n "$BUILD_ROOT" ]; then TOOLS="$BUILD_ROOT/regpack-tools/classes"; else TOOLS="regpack-tools/target/classes"; fi
  [ -d "$TOOLS" ] || { echo "check-gates: $TOOLS not found; run ./mvnw verify first"; exit 1; }
  JAVA="${REGPACK_JAVA:-java}"
  gates() { "$JAVA" -cp "$TOOLS" dev.regpack.tools.Gates "$@"; }

  scan=(--scan .)
  if [ -n "$BUILD_ROOT" ]; then scan+=(--scan "$BUILD_ROOT"); fi
  gates traceability --file docs/traceability.md --root . "${scan[@]}"
  gates pins --dockerfile packaging/container/Dockerfile --workflows .github/workflows
  git ls-files | gates eol --root .
  echo "check-gates: all gates passed"
  ```
  The `traceability` scan skips `.git` and `node_modules` directories. Mark it executable with `git update-index --add --chmod=+x scripts/check-gates.sh`.
- [ ] **Step 3: Write `.github/workflows/ci.yml`:**
  ```yaml
  name: ci

  on:
    push:
      branches: [main, 'p0/**']
    pull_request:
      branches: [main]
    workflow_dispatch:

  permissions:
    contents: read

  concurrency:
    group: ci-${{ github.workflow }}-${{ github.ref }}
    cancel-in-progress: ${{ github.ref != 'refs/heads/main' }}

  env:
    MAVEN_ARGS: -B -ntp --strict-checksums

  jobs:
    build:
      name: build (${{ matrix.os }})
      runs-on: ${{ matrix.os }}
      timeout-minutes: 30
      strategy:
        fail-fast: false
        matrix:
          os: [ubuntu-24.04, windows-2025]
      steps:
        - uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7.0.1
          with:
            persist-credentials: false
        - uses: actions/setup-java@de7274f081f381c8f8158605e0321c36c376e2e6 # v6.0.1
          with:
            distribution: temurin
            java-version: '25'
            cache: maven
        - name: Toolchain versions
          shell: bash
          run: |
            java -version
            ./mvnw -v
        - name: Build, unit and in-memory integration suites, SBOM and licence gate
          shell: bash
          run: ./mvnw verify cyclonedx:makeAggregateBom license:aggregate-add-third-party -DincludeTestScope=true
        - name: Gates (traceability, pins, line endings)
          shell: bash
          run: bash scripts/check-gates.sh
        - name: Upload build evidence
          if: always()
          uses: actions/upload-artifact@043fb46d1a93c77aae656e7c1c64a875d1fc6a0a # v7.0.1
          with:
            name: build-evidence-${{ matrix.os }}
            path: |
              target/bom.json
              target/generated-sources/license/THIRD-PARTY.txt
              **/target/surefire-reports/TEST-*.xml
              **/target/failsafe-reports/TEST-*.xml
            if-no-files-found: warn
        - name: Upload toy crash digest
          uses: actions/upload-artifact@043fb46d1a93c77aae656e7c1c64a875d1fc6a0a # v7.0.1
          with:
            name: toy-digest-${{ matrix.os }}
            path: regpack-engine/target/toy-digest.txt
            if-no-files-found: error
        - name: Upload distribution (built once, installed on every target)
          if: matrix.os == 'ubuntu-24.04'
          uses: actions/upload-artifact@043fb46d1a93c77aae656e7c1c64a875d1fc6a0a # v7.0.1
          with:
            name: regpack-dist
            path: |
              regpack-app/target/regpack-app-*-dist.zip
              regpack-app/target/regpack-app-*-dist.tar.gz
            if-no-files-found: error

    determinism-compare:
      name: determinism (linux against windows)
      needs: build
      runs-on: ubuntu-24.04
      timeout-minutes: 5
      steps:
        - uses: actions/download-artifact@3e5f45b2cfb9172054b4087a40e8e0b5a5461e7c # v8.0.1
          with:
            pattern: toy-digest-*
            path: digests
        - name: Compare toy crash digests
          shell: bash
          run: |
            set -euo pipefail
            a=$(cat digests/toy-digest-ubuntu-24.04/toy-digest.txt)
            b=$(cat digests/toy-digest-windows-2025/toy-digest.txt)
            echo "linux:   $a"
            echo "windows: $b"
            [ "$a" = "$b" ] || { echo "toy crash digests differ between operating systems"; exit 1; }

    smoke-linux:
      name: smoke (linux, systemd)
      needs: build
      runs-on: ubuntu-24.04
      timeout-minutes: 15
      steps:
        - uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7.0.1
          with:
            persist-credentials: false
        - uses: actions/setup-java@de7274f081f381c8f8158605e0321c36c376e2e6 # v6.0.1
          with:
            distribution: temurin
            java-version: '25'
        - uses: actions/download-artifact@3e5f45b2cfb9172054b4087a40e8e0b5a5461e7c # v8.0.1
          with:
            name: regpack-dist
            path: dist
        - name: Install the distribution under systemd and run the smoke
          shell: bash
          run: sudo --preserve-env=JAVA_HOME bash packaging/systemd/smoke.sh "$(ls dist/regpack-app-*-dist.tar.gz)" --destructive

    smoke-windows:
      name: smoke (windows server, service)
      needs: build
      runs-on: windows-2025
      timeout-minutes: 20
      steps:
        - uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7.0.1
          with:
            persist-credentials: false
        - uses: actions/setup-java@de7274f081f381c8f8158605e0321c36c376e2e6 # v6.0.1
          with:
            distribution: temurin
            java-version: '25'
        - uses: actions/download-artifact@3e5f45b2cfb9172054b4087a40e8e0b5a5461e7c # v8.0.1
          with:
            name: regpack-dist
            path: dist
        - name: Install the distribution as a Windows service and run the smoke
          shell: pwsh
          run: |
            $zip = (Get-ChildItem dist -Filter 'regpack-app-*-dist.zip' | Select-Object -First 1).FullName
            & packaging/windows/smoke.ps1 -DistZip $zip -Destructive

    smoke-kubernetes:
      name: smoke (kubernetes, kind and Helm)
      needs: build
      runs-on: ubuntu-24.04
      timeout-minutes: 25
      steps:
        - uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7.0.1
          with:
            persist-credentials: false
        - uses: actions/download-artifact@3e5f45b2cfb9172054b4087a40e8e0b5a5461e7c # v8.0.1
          with:
            name: regpack-dist
            path: dist
        - name: Install pinned kind and Helm (checksums verified)
          shell: bash
          run: |
            set -euo pipefail
            mkdir -p "$RUNNER_TEMP/bin"
            curl -fsSL -o "$RUNNER_TEMP/kind" https://github.com/kubernetes-sigs/kind/releases/download/v0.33.0/kind-linux-amd64
            echo "aee6151561422756b764a4ae28e7f44cda5af5a9eead3cc9985112b1de8d8e0d  $RUNNER_TEMP/kind" | sha256sum -c -
            install -m 0755 "$RUNNER_TEMP/kind" "$RUNNER_TEMP/bin/kind"
            curl -fsSL -o "$RUNNER_TEMP/helm.tgz" https://get.helm.sh/helm-v4.2.4-linux-amd64.tar.gz
            echo "c306b46f719b0a4da32d0f78ee21bf90ce8d602f15b22ab753f0674d1670a7f3  $RUNNER_TEMP/helm.tgz" | sha256sum -c -
            tar -xzf "$RUNNER_TEMP/helm.tgz" -C "$RUNNER_TEMP" linux-amd64/helm
            install -m 0755 "$RUNNER_TEMP/linux-amd64/helm" "$RUNNER_TEMP/bin/helm"
            echo "$RUNNER_TEMP/bin" >> "$GITHUB_PATH"
        - name: Chart assertions
          shell: bash
          run: bash packaging/helm/verify-chart.sh helm
        - name: Build the image from the same distribution
          shell: bash
          run: |
            set -euo pipefail
            mkdir image-ctx
            tar -xzf "$(ls dist/regpack-app-*-dist.tar.gz)" -C image-ctx
            mv image-ctx/regpack-* image-ctx/regpack-dist
            cp packaging/container/Dockerfile image-ctx/Dockerfile
            docker build -t regpack:ci image-ctx
            mods="$(docker run --rm --entrypoint java regpack:ci --list-modules)"
            grep -q '^jdk.httpserver@' <<<"$mods" || { echo 'jdk.httpserver missing from the image'; exit 1; }
        - name: Kind and Helm smoke
          shell: bash
          run: |
            kubectl version --client
            bash packaging/helm/smoke.sh regpack:ci helm kind
  ```
- [ ] **Step 4: Write `.github/dependabot.yml`:**
  ```yaml
  version: 2
  updates:
    - package-ecosystem: github-actions
      directory: /
      schedule:
        interval: weekly
    - package-ecosystem: maven
      directory: /
      schedule:
        interval: weekly
    - package-ecosystem: docker
      directory: /packaging/container
      schedule:
        interval: weekly
  ```
- [ ] **Step 5: Run the gates green.** Full local run: `./mvnw -B -ntp verify cyclonedx:makeAggregateBom license:aggregate-add-third-party -DincludeTestScope=true`, then `bash scripts/check-gates.sh` (Git Bash). Expected: `BUILD SUCCESS`, then `traceability: OK rows=24 closed=0 open=24`, `pins: OK`, `eol: OK` and `check-gates: all gates passed`. If `eol` reports `CR_IN_LF_FILE` for a file created on Windows, convert it to LF (`git add --renormalize .`) and rerun.
- [ ] **Step 6: Commit.** `./mvnw -B -ntp -q spotless:apply`, `git add -A`, `git commit -m "ci: add GitHub Actions workflow, Dependabot and the gate script" -m "Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"`.

### Task 33 (T33): First CI run and the P0 exit record [OWNER ACTION, STOP CONDITION]

The exit criterion needs the smoke run on Linux, Windows Server and Kubernetes; only CI has all three, so this task runs the workflow and records the evidence. Creating the GitHub repository, pushing and changing repository settings are owner actions.

**Files:**
- Create: `docs/exit/P0.md`
- Modify: `docs/traceability.md` (`exited_plans: P0`), `docs/reviews/R14-process-checklist.md` (owner sets `status: PASS`), `governance/licence-policy.md` (owner confirmation line)

- [ ] **Step 1 (OWNER, STOP): create the remote.** Create a private GitHub repository (suggested name `regpack`), in Settings enable Dependabot alerts and Dependabot security updates, under Actions permissions allow GitHub-owned actions only (every action in the workflow is `actions/*`) and, if the settings page offers it, turn on "Require actions to be pinned to a full-length commit SHA" (defence in depth next to `PinCheck`) and set the default workflow permissions to read-only; enable secret scanning and push protection if the plan offers them, and optionally a branch-protection rule on `main` requiring the six CI jobs (`build` x2, `determinism-compare`, `smoke-linux`, `smoke-windows`, `smoke-kubernetes`). Then `git remote add origin <url>` and, after the owner's approval, `git push -u origin p0/foundation`.
- [ ] **Step 2: Watch the run** (`gh run watch` if the GitHub CLI is installed, otherwise the Actions tab). Expected: `build (ubuntu-24.04)`, `build (windows-2025)`, `determinism-compare`, `smoke-linux`, `smoke-windows` and `smoke-kubernetes` all green. A red job is fixed in a new commit on the same branch (message `fix(ci): ...`), never by weakening a gate. Likely first-run findings and where to fix them:

  | Symptom | Likely cause | Fix in |
  |---|---|---|
  | `license:aggregate-add-third-party` names an unknown licence | a POM spells the licence differently | `licenseMerges` in `pom.xml` (T27), only for allowed licences |
  | `spotless:check` fails on JDK 25 with `IllegalAccessError ... com.sun.tools.javac` | missing exports | `.mvn/jvm.config` (T2) |
  | `systemd-analyze verify` prints a line | a directive the installed systemd dislikes | `packaging/systemd/regpack.service` (T29); never filter the output |
  | procrun archive layout differs | `amd64\prunsrv.exe` not present | `packaging/windows/install-procrun.ps1` (T30), after the owner confirms the layout |
  | service stop leaves a non-zero `ExitCode` | `stop` returned late or threw | `WindowsServiceEntry` (T24) |
  | `jdk.httpserver` missing from the JRE image | the JRE image lacks the module | switch the Dockerfile base to `eclipse-temurin:25-jdk-noble@sha256:<full digest from Appendix A, re-checked>` and change the `FROM` assertions in `PackagingFilesTest#dockerfileIsPinnedByDigestAndRunsNonRoot` and the pins example in the same commit (T31); note it in the exit record |
  | kind or Helm download checksum mismatch | a pin is wrong | `ci.yml` (T32) after re-reading the release page |
  | service fails to start under LocalService | missing read access to `JAVA_HOME` or `AppHome` | the `icacls` lines in `install-service.ps1` (T30), never by switching to LocalSystem |
  | checksum failure from `--strict-checksums` | a repository artifact lacks a checksum | investigate; never drop the flag without recording why |
  | `determinism-compare` differs | an OS-dependent behaviour in the pipeline or fault plan | the code; this is a real defect (RF8) |

  If the P0 run is cancelled or superseded, re-run it and record the final run only.
- [ ] **Step 3: Write `docs/exit/P0.md`:** the CI run URL and the six job conclusions; the two toy crash digests (equal); the artifact names (`regpack-dist`, `build-evidence-*`, SBOM `bom.json`, `THIRD-PARTY.txt`); the verdict on each exit criterion with the evidence (criterion 1: `ScopeEngineTest` and the `smoke` output; criterion 2: `ToyCrashIT` and `determinism-compare`; criterion 3: `InMemoryEvidenceStoreContractTest`, `InMemoryLedgerStoreContractTest` and `ContractSuiteTeethTest`; criterion 4: the three smoke jobs); the decisions recorded (procrun instead of WinSW; JRE base image check result; licence allow-list confirmed by the owner; traceability reading confirmed; port contracts reviewed by the owner on a stated date; the answer to handoff question 1, the working-copy location and the toolchain path); the runner image names and versions (the Image and Version lines in "Set up job" of each job) and the kubectl client version printed by `smoke-kubernetes`; the exact wording of the licence-gate failure observed in T27 step 4; the interim vulnerability handling of `governance/licence-policy.md` and any HIGH or CRITICAL advisory triaged under it; the sentence "The SBOMs cover Maven components only. The JDK, the base image, procrun and OS packages are not covered; image and runtime SBOMs are a P9b task. The release SBOM is `share/sbom/regpack-bom.json` inside `regpack-dist`."; the sentence "The Kubernetes stop evidence is the SIGTERM to PID 1 with the restart, exit code 143 and the `--previous` log, which is the same signal the kubelet sends."; and the effort table that spec section 15 requires after P0 and S0 (rows: owner hours spent, agent sessions and tasks, rework commits, tasks that needed a second review pass; the owner fills in the numbers and re-estimates P1 to P10b before committing to P3).
- [ ] **Step 4 (OWNER): sign.** Set `status: PASS` in `docs/reviews/R14-process-checklist.md` after ticking the checklist, add the line `Owner confirmation: <date>, hans` to `governance/licence-policy.md`, and set `exited_plans: P0` in `docs/traceability.md`.
- [ ] **Step 5: Verify the exit state locally and in CI.** `./mvnw -B -ntp verify` then `bash scripts/check-gates.sh`. Expected: `traceability: OK rows=24 closed=1 open=23` (R14 closed by the review file, everything else still open with its closing plan named) and `check-gates: all gates passed`. Push and confirm the workflow is green again.
- [ ] **Step 6: Final whole-branch review, then commit.** Run the final reviewer pass described in the execution handoff over `git diff main...p0/foundation`; fix findings in separate commits. Commit the exit record: `git add -A`, `git commit -m "docs: record P0 exit evidence" -m "Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"`. The owner decides the merge to `main`.

## Self-review

Done against the finished plan, not from memory. Each check lists what was compared and what was fixed.

**1. Coverage of the P0 row and the exit criterion.**

| P0 scope item or exit criterion | Task(s) |
|---|---|
| Repository, build | T1, T2 |
| CI with SBOM and licence gate, Linux and Windows Server matrix (build, unit and in-memory integration suites) | T27, T32 (failsafe `*IT` suites: T2, T22, T28) |
| Ports with in-memory adapters: `LedgerStore`, `EvidenceStore`, `Clock`, `Transport`, `Executor`, `Expression`, `ReferenceData`, `EnrichmentSource`, `RegimeProvider`, plus the connector contract | T7, T8, T9-T11, T13, T14, T15, T17, T18 |
| Secrets and KMS ports | T16 |
| Canonical types | T5, T6 |
| Golden-test harness with provenance fields and a named reviewer; consistency-only label (14.2 O1-O3) | T20 (and its use in T21, T25) |
| Deterministic fault simulator | T19 |
| Destination simulator skeleton, structured for the 14.3 scenarios; enrichment fakes | T14, T15 |
| `LedgerStore` and `EvidenceStore` contract suites on the in-memory adapters | T8-T12 |
| Packaging skeleton: systemd unit, Windows service wrapper, Helm chart with probes | T28-T31 |
| Traceability file (14.4) and the CI check | T25, T32 |
| Executor budget tests count cost units, never wall-clock time (14.1) | T18 |
| No wall clock, randomness or locale in rules (I3) | T3, T6, T21 |
| One SPI for connectors, transport and lookup (5.2) | T13-T15 |
| Role flag parsing, health endpoint, smoke command | T23, T24 |
| Exit 1: three scope rules to a decision record and `explain` | T21 (`ScopeEngineTest`, `Tier1ExplainerTest`) and the `smoke` output (T23) |
| Exit 2: toy crash test deterministic | T22 (`ToyCrashIT`) and CI `determinism-compare` (T32) |
| Exit 3: contract suites pass on the in-memory adapters | T8-T12 |
| Exit 4: smoke passes on Linux, Windows Server and Kubernetes | T29-T31 for the scripts, T32 and T33 for the runs |

Not in the table because they are not P0: everything in "Scope check: out of scope for P0".

**2. Findings from the review and what was changed.**
- A sealed `Connector` cannot name types that do not exist yet, so its `permits` clause grows across T13, T14 and T15 (the final form equals the port contract).
- A `testkit` that depended on `inmemory` would create a cycle with the adapters' own tests; therefore `ManualClock`, the simulator and the fakes live in `testkit` and `testkit` depends only on `model` and `ports`.
- Aggregator goals bound to a parent POM run before the modules are built, so the SBOM and licence goals are invoked as separate task segments after `verify` (T27, T32).
- WinSW was rejected for lack of a vendor checksum and releases since 2023; procrun was adopted (T30). Procrun's start method must block until stop, which shaped `WindowsServiceEntry` (T24).
- `*.cmd` blobs are normalised to LF in the index and CRLF in the working tree, so `EolCheck` reads working-tree files, never index blobs (T26); existing documents were checked and are LF.
- A `PENDING` review file must not fail the traceability gate on an open row but must not count on a due row (T25).
- Names that would shadow JDK types were changed: `KeyOutcome.Failure` instead of `Error`, `KmsSignature` instead of `Signature`.
- Test counts in the expected outputs were recounted after the tasks were written; the plan states the count per run.
- Surefire and Failsafe user-property names were checked in the plugin documentation (`surefire.failIfNoSpecifiedTests`, `failsafe.failIfNoSpecifiedTests`, `excludedGroups`).
- The enrichment fakes were first only scripted; T19 now also requires that the fault harness drives them (spec 14.3: "driven by the same fault harness").

**3. Step clarity and consistency.** Every behaviour task has a red run with the expected failure, a green run with an expected count, and one commit command with its message. Config files (POMs, assembly, scripts, Dockerfile, chart, workflow) are given in full. Type names were cross-checked with text searches: every `ContractTest` method named in the Review focus table and in `docs/traceability.md` appears in the task that defines it; nested port types are always written with their owner (`IngestLedger.Entry`, `LeaseLedger.LeaseToken`, `DeliveryLedger.DeliveryState`). Steps that cannot be run locally (systemd, Windows service, kind) say so and name the CI job that produces the evidence.

**4. Review focus.** Every row RF1 to RF25 maps to a named test, script or build rule in a task (checked by search).

**5. Proportion.** The plan is about 357 KB in total, of which about 32 KB are verbatim quotes copied by command, not retyped (checked line by line against the specification), so about 325 KB are its own text against a 216 KB specification (about 1.5 times); most of that is test names, expected counts and configuration files that executing agents need in full. Implementation bodies are given only where tests and signatures do not determine the algorithm (the in-memory adapter semantics, the scan rules, the traceability rules). The plan does not contain product code beyond signatures and configuration.

**6. Known limits of this plan (no build was run while writing it).** Some behaviour can only be observed by running: the google-java-format exports under Spotless on JDK 25, the `java.lang.classfile` accessor names, the `systemd-analyze verify` output on the CI image, the aggregator goals as task segments, the licence name spellings, and the procrun archive layout. Each has a stated expected output and a named place to fix; none may be handled by weakening a gate.

## Execution handoff

**Recommended mode: subagent-driven development.** For each task: a fresh implementer subagent receives the task text, the "Port contracts for owner review" section, "Conventions" and "Global constraints", and works test-first; a spec-compliance reviewer then checks the diff against the constraints the task cites and against the port signatures; a code-quality reviewer checks style, determinism rules and test quality; only an accepted task is committed. After T33's checks, one final whole-branch reviewer reads `git diff main...p0/foundation` with the Review focus table as its checklist. Run tasks strictly in order (the dependency order is the task order).

**Owner checkpoints.**
1. Before T1: decide the OneDrive question (move the working copy out of OneDrive, or use `REGPACK_BUILD_ROOT`) and whether the JDK is installed locally (path A) or through Docker (path B). The answer is recorded in `docs/exit/P0.md`.
2. Before T7: read "Port contracts for owner review" and answer the questions in Appendix B that concern ports. P2a, P3, P4, P5, P7a and P8 extend these signatures; changing them later is expensive.
3. After T12: the contract suites exist and have teeth; confirm before the connector and simulator work.
4. After T22: exit criteria 1 and 2 are met locally; this is the point at which the owner may stop and review (spec 15: after P0 and S0 the owner records effort and re-estimates).
5. After T32: the first push (T33).

**Stop conditions (the agent stops and asks; it never works around).** The JDK or Maven install and every download (T1; the procrun download in T30; the pinned base image pull in T31; kind, Helm and runner downloads in CI); creating a GitHub repository, adding a remote, pushing, or changing repository settings (T33); a licence outside the allow-list; a port signature that does not fit; a gate that fails for a reason the plan does not explain; a spec constraint that seems to conflict with a task; anything outside the repository directory (including `C:\dev`, which is the owner's install location); any attempt to weaken a gate, skip hooks or bypass the traceability rules.

**What the agents must not do.** Do not write regime content (real rule text, real citations, real identifiers): the three scope rules and their citations are placeholders and must stay labelled so. Do not mark a golden case as reviewed. Do not add a runtime dependency. Do not add `Thread.sleep`, wall-clock reads or unseeded randomness to model, ports, in-memory adapters or engine code.

## Appendix A: Verification log (2026-10-09, text fetches only)

Nothing was downloaded, installed or built to produce this plan. Sources are primary where possible.

| Fact | Source |
|---|---|
| Stable versions: junit-jupiter 6.1.3, assertj-core 3.27.7 (latest is the milestone 4.0.0-M1), maven-compiler-plugin 3.16.0, surefire and failsafe 3.6.0, enforcer 3.6.3, jar 3.5.1, assembly 3.8.0, wrapper plugin 3.3.4, resources 3.5.0, clean 3.5.0, install 3.2.0, deploy 3.2.0, site 3.22.0, apache-maven 3.9.16 (3.10.0 also exists), cyclonedx 2.9.3, license-maven-plugin 2.7.1, spotless 3.10.4, google-java-format 1.37.0 (1.35.0 pinned) | `https://repo.maven.apache.org/maven2/<group path>/<artifact>/maven-metadata.xml` for each |
| Surefire 3.6.0 runs every framework through the JUnit Platform provider and documents JUnit 6 support ("we support Junit 6 as well", "distinguish JUnit 6 ParameterizedClass invocations"); released 2026-09-03; built against Maven 3.9.16 | GitHub release `surefire-3.6.0` of apache/maven-surefire; `https://maven.apache.org/surefire/maven-surefire-plugin/whats-new-3-6-0.html` |
| JUnit 6.1.3 requires Java 17 or higher at runtime | `https://docs.junit.org/6.1.3/overview.html` |
| maven-compiler-plugin 3.16.0 depends on Maven 3.9.16, plexus-java 1.6.0 and ASM 9.10.1; 3.15.0 release notes "Fix Java 25 compatibility" and "Enable Java 25 and Maven 4 in CI"; the default source and target are 8, so `--release 25` is set explicitly | GitHub releases of apache/maven-compiler-plugin; the 3.16.0 POM; `https://maven.apache.org/plugins/maven-compiler-plugin/` |
| actions/setup-java v6.0.1 lists Temurin and major version 25, verifies checksums and can verify signatures; sudo does not inherit `JAVA_HOME` on Ubuntu runners | `https://raw.githubusercontent.com/actions/setup-java/v6.0.1/README.md` |
| Runner labels and contents: `ubuntu-24.04` (systemd 255.4, Temurin 25.0.4+1, Docker 28.0.4, kubectl 1.37.1, Kind 0.33.0, Helm 3.22.0); `windows-2025` is Windows Server 2025 (Temurin 25.0.4+101.0, Git Bash, PowerShell 7.6.6) | `https://github.com/actions/runner-images` README and the Ubuntu 24.04 and Windows 2025 readmes |
| Action pins: checkout v7.0.1, setup-java v6.0.1, upload-artifact v7.0.1, download-artifact v8.0.1 and their commit SHAs; upload and download artifact v4 and later share one backend | GitHub tags API `repos/<owner>/<repo>/git/ref/tags/<tag>`; the actions' READMEs |
| Temurin 25.0.4.1+1 Windows x64 JDK zip, SHA-256 `00c847d8...e9283`, 141,167,264 bytes | Adoptium API `api.adoptium.net/v3/assets/latest/25/hotspot`; the release asset `...zip.sha256.txt` (the two agree) |
| Maven 3.9.16 binary zip SHA-512 `ed41650d...54af3` (Apache dist and Maven Central agree); Central publishes no `.sha256` for it | `https://downloads.apache.org/maven/maven-3/3.9.16/binaries/` and the Central `.sha512` |
| Maven Wrapper: default type `only-script`; `distributionSha256Sum` is the documented checksum property | `https://maven.apache.org/tools/wrapper/` |
| Procrun 1.6.1: published 2026-06, SHA-512 `a878177e...129f7`, jvm mode and "the start method should not return until the stop method has been called" | `https://downloads.apache.org/commons/daemon/binaries/windows/` (`.sha512`, `.asc`); `https://commons.apache.org/proper/commons-daemon/procrun.html` |
| WinSW: last stable v2.12.0 (2023-01-28), v3 alpha-only since 2023-01-29, release assets without digests | GitHub releases API of winsw/winsw |
| Docker Hub index digests: `eclipse-temurin:25-jre-noble` `sha256:d9a39a23...19ab`, `25-jdk-noble` `sha256:589ff4cc...80b4`, `maven:3.9.16-eclipse-temurin-25` `sha256:93b8a14e...0976` | `https://hub.docker.com/v2/repositories/library/<image>/tags/<tag>` |
| kind v0.33.0 (2026-08-26): linux-amd64 SHA-256 `aee61515...8e0d`; node image `kindest/node:v1.36.4@sha256:099e0493...faed` | GitHub release of kubernetes-sigs/kind (asset digest and `.sha256sum` agree) |
| Helm v4.2.4 linux-amd64 SHA-256 `c306b46f...a7f3`; Helm 4.2.x supports Kubernetes 1.36.x down to 1.33.x | `https://get.helm.sh/helm-v4.2.4-linux-amd64.tar.gz.sha256sum`; `https://helm.sh/docs/topics/version_skew/` |
| google-java-format 1.34.0 added Java 25 support, 1.35.0 (2026-03-03) needs JDK 21 or newer to run, README lists the six `--add-exports` flags for JDK 16 and newer | GitHub releases and README of google/google-java-format |
| Spotless `googleJavaFormat` configuration (`version`, `style`) | `https://raw.githubusercontent.com/diffplug/spotless/main/plugin-maven/README.md` |
| Enforcer `bannedDependencies`: excludes use `groupId:artifactId:version:type:scope`; includes are exceptions to excludes | `https://maven.apache.org/enforcer/enforcer-rules/bannedDependencies.html` |
| cyclonedx `makeAggregateBom` parameters (`outputFormat`, `outputName`, `includeTestScope` default false, binds to `package`); license plugin `aggregate-add-third-party` (`includedLicenses`, `failOnBlacklist`, `failOnMissing`, `licenseMerges`, `excludedGroups`, `missingFile`, binds to `generate-resources`) | the plugins' goal documentation pages |
| `customClasspathLayout` with `$${artifact.artifactId}.$${artifact.extension}` | `https://maven.apache.org/shared/maven-archiver/index.html` |
| Surefire and Failsafe user properties | the `test` and `integration-test` goal pages |
| OneDrive does not support syncing through symbolic links or junction points; behaviour on encountering them is not stated | Microsoft support article "Restrictions and limitations in OneDrive and SharePoint" |

**Not verified (stated as assumptions, each with a check in the plan):** that the Temurin JRE container image contains `jdk.httpserver` (T32 checks `java --list-modules`); the archive layout `amd64\prunsrv.exe` of the procrun zip (T30 checks at install time); that `makeAggregateBom` and `aggregate-add-third-party` resolve reactor artifacts when invoked as task segments after `verify` (T27); the licence names that dependency POMs use (T27); that Spotless needs the `.mvn/jvm.config` exports (harmless if not); the exact `java.lang.classfile` accessor names (the compiler will tell); kubectl on the runner floats with the runner image version and is not pinned by this plan (T33 records the version that ran). Build plugin supply chain is an accepted P0 risk, mitigated by version pinning, strict checksums in CI (`--strict-checksums`) and Dependabot Maven updates; plugin verification is a P9b topic.

## Appendix B: Owner decisions and open questions

**Decisions this plan made that the owner should confirm** (each is reversible before P1):
1. **Windows service wrapper: Apache Commons Daemon procrun, not WinSW** (evidence in T30). Consequence: the application has a Windows entry class and the service runs the JVM in-process.
2. **Traceability reading** (T25): a row whose closing plan has not exited is OPEN and may have no evidence; "CI fails if a row has none" applies from the plan's exit. For I1, I2, I9 and I10 only `REALSTORE` evidence counts. The closing plans for R1 to R14 are this plan's proposal from spec sections 3 and 16 (the invariant rows follow spec 14.4); R14 closes at P0 through the owner-signed checklist.
3. **Licence allow-list** (T27): Apache-2.0, EPL-2.0 (JUnit, test and build time), MIT, BSD-2-Clause, BSD-3-Clause. Anything else needs written approval. Confirm or amend; Legal may want to review EPL-2.0.
4. **Hostile test environment** (T2): every test JVM runs under `tr_TR` and `Pacific/Kiritimati`, and `-Xlint:all -Werror` is on. Confirm that this strictness is wanted (D10).
5. **Placeholders**: the three scope rules, their reasons (`MIFIR.N03.EXCLUDED`, `MIFIR.N13.NATURAL_PERSON`, `MIFIR.N08.IN_SCOPE_26_2_A`) and citations are walking-skeleton placeholders chosen from the Brief's node table and decision D14; P6a replaces them under the named Compliance owner (gate G1). No golden case counts as reviewed in P0.
6. **Review checklist owner name**: the R14 file names `hans` (the git author); give the full name you want recorded.
7. **Supply-chain hygiene beyond the spec P0 row**: T26 (`PinCheck`, `EolCheck`, 17 tests) and `.github/dependabot.yml` (version-update PRs, not vulnerability scanning, which stays P9b). They are included because spec 13 and 5.3 require supply-chain controls and a two-OS matrix makes line-ending and unpinned-action drift likely (RF14, RF15). Reject to drop T26, RF14 and RF15 and keep only `.gitattributes` and the pinned workflow conventions.

**Port-contract decisions that deserve your explicit sign-off before T7.**
- **P1** `LedgerStore` is five sub-ports (ingest, outbox, decisions, lease, delivery) behind one aggregate; later plans add sub-ports. The alternative is one flat interface. Cross-sub-port atomicity is added through trailing commit-record components or `LedgerStore` methods (see the atomicity paragraph).
- **P2** `Transport.send` takes a `CommitGate` that the transport calls exactly once, just before the first destination-visible effect, so that the sender can commit the fenced `commit_issued` marker (spec 11.2 step 4). The alternative is for the sender to guess the boundary.
- **P3** `EvidenceStore` has no delete; expiry and crypto-shredding are P3 retention work.
- **P4** Decision commit is by `expectedHeadSeq` (optimistic compare-and-set, spec 6.3) and a conflict throws; the idempotency key is (`eventIdentity`, `decisionContextId`, `inputDigest`).
- **P5** `Executor` charges cost units through a `CostMeter` that the rule program calls; P2a's interpreter will charge automatically. The budget boundary is inclusive.
- **P6** Identifiers are `long` wrappers (`DecisionId`, `DeliveryId`, `AttemptId`, `OutboxId`); P3 may map them to database sequences.
- **P7** `Checkpoint` and `BatchId` live in `regpack-model` so that connectors and the ledger share them.
- **P8** The attempt id is allocated by `allocateAttemptId` before the intent PUT and passed to `beginSending` (spec 11.2 steps 2 and 3); the alternative of returning it from `beginSending` conflicts with the sealed-send order.
- **P9** Every lookup outcome (hit, miss, failure) carries an as-of, canonical bytes and a response hash, so a sealed snapshot can record all of them (spec 7.5 Contract and Replay).
- **P10** `Clock.now()` is whole microseconds (the canonical JSON and PostgreSQL precision); every `Clock` implementation truncates.

**Questions only the owner can answer.**
1. Move the working copy out of OneDrive (recommended), or use `REGPACK_BUILD_ROOT`? Install path A (local JDK) or B (Docker)?
2. The GitHub account or organisation, repository name and visibility for T33, and whether Actions may be restricted to GitHub-owned actions.
3. Whether the Temurin JRE image is acceptable as the container base (smaller Alpine and jlink runtimes are alternatives for P9b), and whether Kubernetes 1.36.4 on kind is the version to smoke against.
4. The effort data to record at P0 exit (owner hours) for the spec 15 calibration; the plan leaves the table in `docs/exit/P0.md` for you to fill.
5. Whether `windows-2022` should join the Windows matrix leg in addition to `windows-2025` (the plan uses one Windows Server runner).
6. Whether to run the destructive Windows service smoke (elevated PowerShell, procrun download, `-Destructive`) before pushing. It is recommended only in a disposable Windows VM or Windows Sandbox; otherwise rely on CI.

