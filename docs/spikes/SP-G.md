# SP-G report
spike: SP-G
status: PASS
scope: DOCUMENT
evidence_level: RAW
criteria_digest: d85eca736beb3ba4826bdbd6b160bba9e40cd838f7227b41c97140730d5b9447
source_git_sha: 6f369718c04b479927bb358b29352ee20895e294
runs: 1 (vmCount 1)
time_box: used 35 of 240 minutes, hit: no

## Criteria

| Id | Verdict | Basis | Lower | Point | Upper | Threshold | Margin | Reason |
|---|---|---|---|---|---|---|---|---|
| G-1 | PASS | DOCUMENT | - | - | - | at least 6 | 0 | observed 6, at least 6 required |
| G-2 | PASS | DOCUMENT | - | - | - | at most 0 | 0 | observed 0, at most 0 required |
| G-3 | PASS | DOCUMENT | - | - | - | at most 0 | 0 | observed 0, at most 0 required |
| G-4 | PASS | DERIVED | - | - | - | at most 0 | 0 | observed 0, at most 0 required |

## Warnings

- forks share one VM, bounds are optimistic

## Decisions

- none

## Open

- none

## Environment

- CPU model: unknown
- vCPUs: 12
- Physical cores: unknown
- Memory (bytes): 8405794816
- Image version: local

P-5 steal discard observable: no

## Runs

| Run | Attempt | Part | Git SHA | CPU model | Image |
|---|---|---|---|---|---|
| local | 1 | all | 6f369718c04b479927bb358b29352ee20895e294 | unknown | local |

Git SHAs of the pooled runs: 6f369718c04b479927bb358b29352ee20895e294

---

## Interpretation (hand-written; the part above the line is generated and recomputed)

SP-G is a paper exercise with a local run: its criteria are counts and documents with no hardware sensitivity, so there is no CI run and the environment block above describes the controller's PC. The warning about forks does not apply (SP-G has no forks and no timing criterion). Source commit `6f36971`. The time-box line above was generated at import (35 of 240 minutes, counted up to the pause for the owner); the final figure after the ledger stop is in `docs/spikes/timebox/SP-G.tsv`. Gates G3 and G4 (not the criterion G-4) are respected, not cleared: the six rule ids are synthetic (`SYN-EMIR-SEQ-01` to `-06`), no ESMA workbook text, XSD, regulator validation-rule id or ISDA reporting-rules artefact was used (`PackFilesTest#noEsmaOrDrrIdentifiers` and `scripts/check-gates.sh` pass), and `docs/gates/G3.md` stays PENDING.

**Result.** G-1: the six EMIR action types (NEWT, MODI, TERM, EROR, REVI, VALU) are encoded, 6 of 6 (each is a `value` of the derive table and the `action` of a sequencing rule). G-2: no register row is OPEN or PROPOSED. G-3: no row is UNFIXABLE (a redesign rather than a format fix). G-4: the coexistence check has 0 failures (see its limits below). The status is PASS because the owner dispositioned every row on 2026-10-10; while a row was OPEN or PROPOSED, G-2 would have been INCONCLUSIVE by design (`SpGMainTest#openRowsGiveInconclusiveNotFail`). PASS here means "every gap that the encoding found has a disposition and none was rated unfixable", not "the pack format suffices for EMIR".

**What the register says.** The encoding found 19 rows: the ten hypotheses H1 to H10 of the plan, the second half of H1 (BRK-17, the in-flight guard) and eight gaps the hypotheses missed (BRK-11 to BRK-16, BRK-18, BRK-19). Owner dispositions (hans, 2026-10-10): 16 ACCEPTED and 3 REJECTED_NOT_NEEDED (BRK-03, BRK-05, BRK-17). Of the 16 accepted rows, 15 are wording changes on the amendment list below; the 16th, BRK-18, is accepted as a P12 item and carries no wording.

- **Which rows block the freeze, as rated.** BLOCKS_FREEZE: BRK-04 and BRK-10, because they change ledger contracts: BRK-04 adds a status part to the decision input (`input_digest`, `decision_context`), and BRK-10 adds `attributes{}` to the decision record. P3 sizes the decision schema and should see both before it does. FIX_BEFORE_FREEZE (changes to the pack-file structure or the profile schema that must be in the spec before the grammar freezes): BRK-01, 02, 06, 07, 08, 09, 11, 12, 13, 14, 15, 16 and 19. P12_ONLY: BRK-03, 05, 17 and 18. BRK-17 and BRK-18 were written as BLOCKS_FREEZE by the agent and re-rated P12_ONLY by the owner. Both touch a ledger contract that the freeze fixes (BRK-17: the column list of the partial unique in-flight index and the in-flight rule of spec 9.7 and 10, if the guard is later relaxed to `record_key`; BRK-18: spec 10 rules 1, 6 and 7 and the supersession of 6.3 for a late past-dated event), so the freeze fixes those contracts without a decided answer; the owner acknowledges this in the freeze record.
- **Amendment list for the freeze (15 accepted wording changes).** The specification is not edited by the agent and no spec text changes until the owner approves the amendments separately. By spec section: 6.1 (BRK-13 pack `imports` slot, BRK-16 keys of `lifecycle.yaml`, BRK-19 row shapes of `validate/` and `derive/` and the keys of a report definition), 6.2 (BRK-04 per-key `status_model` as a read-only input), 6.3 (BRK-10 `attributes{}` in the decision record, BRK-11 reason-code prefix per regime), 7.4 (BRK-13 event kinds that a pack may add), 9.2 (BRK-09 feedback correlation including the per-attempt reporting timestamp, BRK-15 profile confirmation rule, BRK-06 `restamp` names the volatile field), 9.7 (BRK-07 successor gate per action pair), 10 (BRK-01 `record_key`, BRK-02 TRN policy `none`, BRK-06 volatile fields of `logical_hash`, BRK-12 leg vocabulary per pack, BRK-14 regime as the report definition), 11.1 (BRK-08 named regime clocks). Dependencies: BRK-16 is applied together with BRK-01, 02, 04, 07 and 08 (a rejected one drops its key from BRK-16); BRK-09 needs BRK-01. Edits that no proposal contains and that the owner decides when applying: the `legs[]` comment of the 6.3 decision record (`MARKET | CLIENT | ALLOCATION`) must admit the pack-declared leg vocabulary of BRK-12, and the `scope/` and `derive/` lines of the 6.1 file tree must reflect that BRK-10 moves the action-type table to `scope/` (both are noted in the register).
- **Amendments that rest on an assumption, not on a research fact:** BRK-07 (assumptions A-9 and A-10: the per-pair restriction is the plan's hypothesis H7; the research would also be met by the single gate of spec 10 rule 1, and the pair list is not closed under the status machine) and BRK-08 and BRK-16 (A-1: the T+1 working-day deadline on the TARGET calendar). The owner accepted these rows on design grounds while gate G1 (a named Compliance owner) is open; the acceptance is not a Compliance or Legal view of EMIR.
- **Settled by the owner as P12 items:** BRK-17 (the in-flight guard stays per `business_key`, which is stricter than the research needs for EMIR; relaxing it to `record_key` is decided in P12 with measured need; MiFIR is unaffected) and BRK-18 (the reaction to a late past-dated event that re-folds a key's status after a later version was decided or sent: keep, cancel, re-derive or park for review, settled with the first EMIR onboarding). BRK-03 (late past-dated reports: no format change; the onboarding check that an EMIR source without a version declares `BATCH_SEQUENCE`, never `SOURCE_EVENT_TIME`, stays a P12 item) and BRK-05 (the identity of a derivative side and of a valuation needs no format change; positions and POSC were not encoded, so nothing is claimed for them and they stay a P12 item) were set REJECTED_NOT_NEEDED.
- **No conflict with the approved decisions D1 to D17 was found in the 15 accepted wordings.** The agent compared each `proposed_fix` with D1 to D17 and the owner accepted them on that reading; a proposal that would have changed a decision was to be left OPEN, and none was. The nearest are D1 (BRK-04, BRK-07 and BRK-08 add generic engine mechanisms, a status fold, gate pairs and named clocks, that a pack parameterises; P12 must still show that no regime logic is added to engine code) and D5 (BRK-04 and BRK-10 add an EMIR-driven part to two release-1 ledger contracts; EMIR pack content stays in P12).

**Limits of this report**

- **One encoding by one author, from one source.** Every EMIR fact comes from the research brief (3.3, 3.4, 3.10); anything else is an explicit assumption (A-1 to A-10 in `docs/spikes/sp-g/README.md`). The register lists the gaps found by this encoding of six action types. It does not show that the amended format is sufficient for EMIR, and a second regime may expose more gaps. Not encoded: CORR, POSC and MARU, the collateral and margin report, the report schema and its facets (gate G3), scope tables and routing, the enrichment plan, reasons, sources and rationales files, golden cases (gate G1) and the reconciliation inbox. The CollateralEvent and Position entities are sketches only, and citations, validity dates, digests, file names, limits and message versions are placeholders.
- **Gate G1 is not closed by this report.** No human-reviewed golden case exists: every example carries `provenance: consistency-only`.
- **The proposed wording is the agent's draft.** Each of the 20 quoted old wordings (in 15 rows) occurs verbatim, exactly once, in the section the row names of the specification file (checked on 2026-10-10); `PackFilesTest` checked this while rows were OPEN or PROPOSED and an independent read-only text search repeated it after the dispositions. That proves only that the old wording is quoted correctly. The new wording is the agent's draft, accepted by the owner as an amendment-list item; it has not been reviewed by Legal or Compliance.
- **G-4 evidences less than the ledger.** The check has four parts. Two run on the P0 `InMemoryLedgerStore`: the MiFIR and EMIR decision chains of one execution both start at seq 1 and advance independently by compare-and-set, and superseding the MiFIR decision leaves the EMIR chain at seq 1. The other two (the `(regime, event_identity, leg)` keys of `report_subject` are regime-scoped; an EMIR row carries no TRN and a TRN is unique per executing LEI) run against `CoexistenceCheck.ReportSubjects`, a key model written in the spike because P0 has no `report_subject` or `trn_assignment` store. They show that the rules of spec 10 are consistent as stated and that the check fails when the model is deliberately broken; they exercise no ledger code. The SQL-level form (the UNIQUE constraints of `report_subject` and `trn_assignment` and the partial unique index of the in-flight guard) is criterion L-12 of the SP-L report, which now exists (committed after this report): L-12 is PASS, observed 0 (the ten constraint checks, including the regime-scoped `report_subject` key, the `trn_assignment` key unique per executing LEI and the in-flight partial unique index, passed against PostgreSQL 18.6 on the disposable sketch). L-12 tests spec 10 as written today; none of its checks covers an amendment of this list (TRN policy `none`, a pack-declared leg, `attributes{}`, the status part of the decision input), so those remain unevidenced at SQL level.
- **The grammar and IR are not frozen by this report** (spec 6.8 freezes them only at a G-S3b pass, together with the SP-R and SP-G reports). P2a's entry condition (SP-R and SP-G reports before the grammar freezes) is met on the SP-G side. P2a should start on the pack format, the rule language and the decision record only after the owner has applied the accepted amendments to 6.1, 6.2 and 6.3, or only on the parts that those amendments do not touch.
