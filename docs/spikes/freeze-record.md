# Spec freeze record (template)

status: PENDING
frozen_by:
frozen_on:

This file is the owner's. The agent writes the template and the lists below and never changes `status`, the two lines above it, the acknowledgement lines or the checklist boxes, and never writes initials. The spec 16 definition: the **spec freeze** is the owner's recorded decision, taken after the P1 reports for SP-R, SP-G, SP-L and SP-X exist, that the pack-file structure (6.1), the ledger and journal-object contracts and the executor choice are fixed. The owner may decide **not** to freeze, and then writes what is outstanding under section 5.

## 1. Reports that must exist

Checked by `Gates entry --target spec-freeze` (the output is quoted in `docs/exit/P1a.md`).

| Spike | Report | Status | Evidence | One line |
|---|---|---|---|---|
| SP-R | [SP-R.md](SP-R.md) | PASS (the in-house layer; `cel-java` fails the gate) | RAW, one GitHub run | the importer derives the in-house layer; the layer choice and five DECIDE vectors are the owner's |
| SP-G | [SP-G.md](SP-G.md) | PASS (local run) | RAW | register dispositioned by the owner: 16 ACCEPTED, 3 REJECTED_NOT_NEEDED |
| SP-X | [SP-X.md](SP-X.md) | PASS (policy ALL; SAMPLED_10 fails) | RAW, one GitHub run | on the synthetic schema only (G3 stays PENDING) |
| SP-L | [SP-L.md](SP-L.md) | **FAIL** (L-4; seven criteria INCONCLUSIVE) | RAW, one full GitHub run | cause of the L-4 failure not established; consequence is the owner's |

The whole table with every criterion is `results.md`; the partial latency budget is `latency-budget.md`; what is still open is `open-items.md`.

## 2. What is frozen

By this record, once recorded:

1. **The pack-file structure of spec 6.1**, including the accepted SP-G amendments by id: BRK-01, 02, 04, 06, 07, 08, 09, 10, 11, 12, 13, 14, 15, 16 and 19 (the 15 wordings of the amendment list; the specification is edited by the owner, not by the agent). BRK-18 is accepted as a P12 item and changes nothing in the pack format; the ledger contract it touches is frozen without an answer (section 3).
2. **The ledger and journal-object contracts**, including the adopted SP-L findings and the micro-batch N and T (section 4 lists the candidates).
3. **The executor choice from SP-R** (throughput and the Tier-1 render only; the spec 6.6 bounded-memory gate is pending SP-M).

The expression grammar and the IR are not frozen by this record; they freeze only at a G-S3b pass (spec 6.8).

## 3. Acknowledgements

One line per criterion or decision that is not a plain PASS, in the form `ack: <id> <initials> <date>`. The lines below are the list of what needs an acknowledgement, each ending in the placeholder `ack: <initials> <date>`: the owner replaces the placeholder with their initials and the date, and the agent never does. A reading of each is in the report named.

Non-PASS required criteria (SP-L, a FAIL and seven INCONCLUSIVE; the noise margin of the pooled canary is above the allowed 0.50, see SP-L.md):

- L-4 (FAIL: the memory-limited runs did not sustain 3,470 records/s; the cause is not established; the consequence is recorded in section 5) — ack: <initials> <date>
- L-2 (INCONCLUSIVE; median of window maxima 1,077 ms against 1,000 ms; the bounds straddle the threshold) — ack: <initials> <date>
- L-3 (INCONCLUSIVE; closed-loop capacity 12,000 records/s, one run) — ack: <initials> <date>
- L-6 (INCONCLUSIVE and EXTRAPOLATED) — ack: <initials> <date>
- L-7 (INCONCLUSIVE and EXTRAPOLATED) — ack: <initials> <date>
- L-9 (INCONCLUSIVE; 0.14 ms cached) — ack: <initials> <date>
- L-10 (INCONCLUSIVE; 255 ms at 1,826 daily partitions against 50 ms: a failing-level figure that the noise rule refused to call) — ack: <initials> <date>
- L-11 (INCONCLUSIVE; 1,015 events/s) — ack: <initials> <date>
- X-2b (FAIL; modelled capacity of policy SAMPLED_10 at defect rates of 0.1% and 1% is 1,032 and 105 records/s against 3,472; the SAMPLED_10 alternative group fails) — ack: <initials> <date>
- X-3 (FAIL; the capacity model overestimates the integrated stage by 49.2% against a limit of 15%; it was checked for policy ALL at defect rate 0 only, its failure-path terms were never compared with a measurement, and the policy ALL choice rests on the measured X-1a, not on the model) — ack: <initials> <date>
- BRK-17 and BRK-18 (P12 items that touch ledger contracts the freeze fixes: the in-flight guard stays per `business_key` and the reaction to a late past-dated event is undecided, so the freeze fixes those contracts without a decided answer) — ack: <initials> <date>

EXTRAPOLATED criteria:

- L-8 (PASS EXTRAPOLATED: rebuild measured at 0.5M and 1M keys, projected to 10M and 100M) — ack: <initials> <date>
- L-6 and L-7 as above (the restore at 10M keys was measured directly; the 24-hour replay is a model term; 100M is projected) — ack: <initials> <date>
- L-4 (the sustained runs are 20-minute slices at the day's memory ratio, not a full day) — ack: <initials> <date>

Spec 16 asks for measured restore RTO at 10M and 100M keys, both backup-plus-gap-replay and full rebuild from evidence. The 10M restore was measured directly on a runner with 92 GB of free disk; the 100M restore and both rebuild figures are projected.

Not run: L-15 (replay conflicts and lag of a read replica and of the synchronous standby under console load). Standby reads stay disallowed (spec 12.1).

Criteria that rest on one VM (their bounds are optimistic): R-2a and R-4 (SP-R), X-1a and X-1b (SP-X), L-6 and L-7 and the others of SP-L (three parts on three VMs, each internally one VM).

- single-VM criteria (R-2a, R-4, X-1a, X-1b) — ack: <initials> <date>

Alternative groups, where the passing group is a choice of the owner:

- SP-R expression layer (the importer derives the in-house layer; `cel-java` as adapted fails R-2b; whether to adopt the in-house layer, or to keep `cel-java` with a faster executor, is the owner's choice; spec 5.3 and 6.6) — ack: <initials> <date>
- SP-R DECIDE vectors D13, A04, A07, E06 and L01 (S07: both layers agree), see SP-R.md — ack: <initials> <date>
- SP-X per-record policy (the importer derives ALL; sampling at 10% fails the modelled failure path) — ack: <initials> <date>

## 4. Spec amendments the owner will make

- **SP-G accepted rows (15 wordings):** see `docs/spikes/sp-g/register.md`, column `proposed_fix`, and the by-section list in SP-G.md. Two edits that no proposal contains: the `legs[]` comment of the 6.3 decision record (BRK-12) and the `scope/` and `derive/` lines of the 6.1 file tree (BRK-10).
- **SP-L findings for the P3 schema design:** the partition granularity (daily with `as_at` in `decision_head`, or monthly; SP-L.md, Partitions), the growth policy of `decision_head` (24 million rows a day in the sketch) and of the unpartitioned `ingest_registry` (7.9 GB a day), the encoding (E1, 335 bytes per decision row, inside the 300 to 500 band; E3, 983, outside), the ledger size per day (48.8 GB, about 2.9 times the measured decision rows of 16.7 GB a day; the spec 5.6 planning range for decision rows is 15 to 25 GB a day).
- **SP-L and SP-X inputs to the micro-batch rules (spec 7.8):** N (the SP-L knee is 10,000 for four writers; the load model default is 5,000), T (not a measured criterion; final with SP-M in P1b), the SP-X stage latency per file size and its loaded factor.
- **SP-X:** the per-record XSD policy (ALL by derivation) and the stage latency per file size.

## 5. Consequences recorded

The owner writes these; the agent lists the questions.

- **L-4 (SP-L FAIL):** does it trigger the spec 5.3 flip ("PostgreSQL cannot hold the volume (SP-L): offload old partitions to immutable Parquet, then shard by endpoint"), call for more memory, faster storage or another layout on the reference production node, or is it explained by the fixture alone (three PostgreSQL servers and the generator on one 4-vCPU VM)? The sealed data cannot say. Spec 16 and 14.5 already require capacity to be re-confirmed on the release candidate before go-live.
- **P2a scope:** the expression layer is the in-house subset (build, test and maintain it) or `cel-java` with a faster executor; the rulings on the DECIDE vectors are needed before P2a implements absence and error behaviour.
- **P3 schema inputs:** the findings in section 4.
- **P7a N and T:** the SP-L knee and the latency budget (`latency-budget.md`).
- **Outstanding items, if the owner decides not to freeze:** to be written here.

## 6. Owner checklist

- [ ] four reports exist with RAW evidence
- [ ] every non-PASS and EXTRAPOLATED criterion acknowledged (section 3)
- [ ] SP-G register fully dispositioned and the accepted amendments listed
- [ ] the executor choice written down
- [ ] the ledger and journal inputs adopted (N, T, partition granularity, `decision_head` growth policy; T is an owner-adopted input, final with SP-M in P1b)
- [ ] `criteria.lock` unchanged since the first full run
- [ ] G3 still `PENDING` (SP-X used the synthetic schema)
- [ ] `status: RECORDED`, name and date

entry-wiring re-checked against spec 16: <date>
