# SP-G: pack-format paper exercise and spec-freeze gate

Spec 16 asks SP-G to encode the EMIR action types NEWT, MODI, TERM, EROR, REVI and VALU in the pack format, sketch the canonical-model extension, and deliver a written list of pack-format breakages, each fixed before the grammar freezes; the coexistence check of the EMIR and MiFIR chains for one execution follows in task G2. This directory holds the first half: the encoding and the register.

Nothing here is EMIR content that anybody has reviewed. It is a paper exercise whose output is the register.

## Sources and gates

- **Sources, and only these:** the fact-checked research brief `docs/research/03-trade-repository-lifecycle.md`, sections 3.3, 3.4 and 3.10, and the approved specification (6.1 pack format, 6.2 rule language, 7.4 canonical model, 9.2 endpoint profile, section 10 keys, decisions D1 to D17).
- **Gate G3:** no ESMA workbook text, no regulator validation rule ids, no XSD. The six rule ids are synthetic (`SYN-EMIR-SEQ-01` to `-06`) and the sequencing conditions restate the status machine of research 3.4, not any catalogue. The report schema of the pack is not encoded, because its facets would have to equal the XSD.
- **Gate G4:** no artefact, sample or test pack of the ISDA reporting-rules library is used or consulted, not even as an oracle.
- **Gate G1:** there is no human-reviewed golden case, so every example in the pack files carries `provenance: consistency-only`, and so does every pack file as a whole. `PackFilesTest` checks both.
- **Honesty rule:** a construct that the format of the spec cannot express is never invented in silence. The line carries `# BRK-nn` and `register.md` has the row. A marker on a line that opens a block covers the block. `PackFilesTest` checks that the markers and the register ids are the same set.

## Files

| File | Holds |
|---|---|
| `register.md` | the breakage register (19 rows: H1 to H10, the second half of H1 (BRK-17) and eight gaps the hypotheses missed) |
| `emir-pack/pack.yaml` | the layout of spec 6.1: id, semver, regime, `valid{legal}`, `as_at_field`, `engine_min`, report definition |
| `emir-pack/lifecycle.yaml` | `business_key` and `record_key`, identifier policy, status model, corrections, `successor_gate` per action pair, `due_at` and the UTI cut-off clock |
| `emir-pack/derive/action-type.yaml` | one decision table from (event kind, derivative status) to the six action types, in the row shape of spec 6.1, with the mandatory catch-all |
| `emir-pack/validate/sequencing.yaml` | six synthetic L5 rules, predictability `partial` |
| `emir-pack/endpoints/tr-sftp.yaml` | a trade-repository profile in the schema of spec 9.2, illustrative values |
| `emir-pack/model/canonical-extension.yaml` | the canonical-model extension sketch: derivative side, position, valuation event, collateral event, reused MiFIR entities, event identity of a valuation |

## What was not encoded

CORR, POSC and MARU (not among the six action types), the collateral and margin report, the report schema and its facets (gate G3), the scope tables and routing, the enrichment plan, reasons, sources and rationales files, golden cases (gate G1), and the reconciliation inbox. The CollateralEvent and Position entities are sketches only. Citations, rationale references, validity dates, digests, file names, limits and message versions are placeholders in angle brackets.

## Assumptions

These are facts about EMIR or choices that the three research sections do not establish. They are marked in the files and are not established facts.

| Id | Assumption |
|---|---|
| A-1 | The deadline of a lifecycle report is T+1 working day on the TARGET calendar. The plan text states it; the three research sections used do not. |
| A-2 | `as_at_field` is `event_date`. It is a Legal decision (spec 6.4); the value only shows the shape. |
| A-3 | A MODI is reportable only while the derivative is OUTSTANDING (research 3.4 says only "when effective"). |
| A-4 | An EROR is allowed from OUTSTANDING and from TERMINATED (research 3.4 draws the EROR transition from the outstanding state only). |
| A-5 | A derivative that is not outstanding needs no valuation report: a VALUATION event then ends in DO_NOT_REPORT with a reason. |
| A-6 | An ERRORED derivative admits only REVI. The six rules together say this; research 3.4 lists only the REVI transition out of ERRORED. |
| A-7 | A firm that reports straight to a repository uses submission route SELF; spec 9.2 has no route value for a repository. |
| A-8 | The canonical event kinds TERMINATE, REVIVE and VALUATION exist beside NEW, AMEND, CANCEL and CORRECTION, and CANCEL maps to EROR. The source mapping (P7a) does the mapping from a source's own events. |
| A-9 | The destination rejects a successor that is sent before its predecessor is accepted, so the gate never proceeds on expiry (`on_expiry: ESCALATE`). Research 3.10 keeps dependants BLOCKED until the predecessor is ACCEPTED_PROVISIONAL; it does not say what the destination does otherwise. |
| A-10 | Only the listed (predecessor, successor) pairs of `successor_gate.pairs` need to wait; other pairs may be sent together. This is the plan's hypothesis H7, not a research fact: research 3.10 blocks every later event of a UTI behind its predecessor, which makes the restriction unnecessary (BRK-07). |

## How to read the result

The register rows are `OPEN` or `PROPOSED`; only the owner dispositions them (`ACCEPTED`, `REJECTED_NOT_NEEDED`, `UNFIXABLE`). The specification file is not edited. Criteria G-1 to G-4 are evaluated by task G2 (the SP-G report); this directory is the evidence for G-1 to G-3. The coexistence of the EMIR and MiFIR chains (G-4) is checked in G2.

`MiniYaml` and `Register` in `spikes/spike-g` parse these files; `PackFilesTest`, `MiniYamlTest` and `RegisterTest` are the tests (`spikes/t.sh spike-g 'MiniYamlTest,PackFilesTest,RegisterTest'`).
