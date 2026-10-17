# P2a decisions and records

Kept by the controller (the main session). Plan: [P2a](../superpowers/plans/2026-10-16-p2a-rule-pack-core.md) and its [contracts](../superpowers/plans/2026-10-16-p2a-contracts.md). Style of `docs/spikes/decisions.md`.

## Baseline (T1 step 2, 2026-10-16)

Branch `p2a/rule-pack-core` from `p1a/spikes`; plan committed as `110db05`. `./mvnw -B -ntp verify` ends `BUILD SUCCESS` with the totals the plan names (tools 145 + 1 IT, model 47, ports 14, testkit 86, bench 343 with 1 skipped, inmemory 105, engine 34 + 5 IT, app 43 + 5 IT). `scripts/check-gates.sh` ends `check-gates: all gates passed` (including the P1a `verify-committed`, `results.md` and `latency-budget.md` checks). `Gates entry --target P2a` prints `SP-R REPORT_EXISTS OK` and `SP-G REPORT_EXISTS OK`. `docs/spikes/freeze-record.md` line 3: `status: PENDING` (the spec freeze is not recorded; departures table row 1).

## Approvals

| Id | What | Date | Name | State |
|---|---|---|---|---|
| AP-1 | The plan, its companion contracts file, the departures table, the beyond-6.1 format elements, the split decision, "Contract and layout changes", "Existing tests that change", D-P6 and A1 to A6 | 2026-10-16 | hans | given in chat as "ok approved lets continue", in answer to the controller's message that started P2a on the working assumptions A1 to A5 (it followed "ok let's continue development. make your own decision how to continue"). The plan text was written and reviewed after that message; the controller summarised it to the owner before executing and proceeds on this approval. **The owner has not read the plan text as far as this record knows**; the owner can stop or amend at any time. |
| AP-2 | Every push of `p2a/rule-pack-core` and every CI run waited on | | | asked at each push |
| AP-3 | The two edits of the pinned workflow `ci.yml` (T2 branch filter, T24 second digest) | | | asked at the first push that contains them. The T2 branch filter `p2a/**` is committed locally only (b6bb562); nothing has been pushed. |
| AP-4 | Merge, tag and the sign-offs in `docs/exit/P2a.md` | | | owner only (T27) |

## Owner inputs

All answered as "default accepted" on 2026-10-16 by the owner's delegation ("make your own decision how to continue"), except where the plan makes a task ask again (OI-5 at T14). A default is not an owner ruling on the question; it keeps work moving and is reversible at the cost the plan names.

| Id | Question | Default | Answer |
|---|---|---|---|
| OI-1 | Plan approval and A1 to A6 | proceed on AP-1 | default accepted (AP-1 given) |
| OI-2 | Source of `legs[]` of a REPORT decision | optional `legs:` list on REPORT rows, default `[MARKET]`, vocabulary MARKET, CLIENT, ALLOCATION | default accepted |
| OI-3 | How several scope tables per report combine | the SP-R benchmark rule | default accepted |
| OI-4 | When an open poison circuit closes | only by explicit `close(ruleId)` or a new pack digest | default accepted |
| OI-5 | Rulings on D13, A04, A07, E06 (S07: see A2) | in-house outcomes, provisional, one line each of `rulings.tsv` | default accepted for now; asked again at T14 |
| OI-6 | Keep the P0 `skeleton` package | yes, until the frozen spike code is removed (Q7, D-8) | default accepted |
| OI-7 | Where the reason-code prefix and node list are declared | `reasons.yaml` header | default accepted |
| OI-8 | Calibration read-out before P3 is planned | stop at T27 | default accepted |
| OI-9 | Accept the split and add a row P2a-2 to the spec 16 table | the notes of the Deferral section stand in until the owner edits the spec | default accepted; the spec is not edited |
| OI-10 | Optional CI checkpoint: push after T16 and wait for the run | recommended | asked at the time (AP-2) |

Owner reading and deciding time for the effort ledger (Convention 8): not provided.

## Pins created

All provisional (G-S3b): they are format choices the plan leaves open and the owner can change at the freeze.

| Name | Value | Task | Reason |
|---|---|---|---|
| SemVer component limit | no leading zeros, at most 9 ASCII digits | T6 | `pack.yaml` version parsing |
| `pack.yaml` jurisdiction shape | `[A-Z][A-Z0-9]{1,7}` | T6 | plan names no shape |
| `pack.yaml` as_at_field shape | `[a-z][A-Za-z0-9_]*(\.[a-z][A-Za-z0-9_]*)` | T6 | plan names no shape |
| `pack.yaml` supersedes shape | `[A-Za-z0-9][A-Za-z0-9._+-]{0,127}` | T6 | plan names no shape |
| Maximum pack path length and depth | 256 characters per path, 16 directory levels | T6 | hostile-input bound |
| Report `scope_tables` minimum | at least one entry, else `REPORT_SCOPE_TABLES_*`; the SP-G EMIR spike pack had an empty list, so the owner may want that shape accepted | T6 | see concerns of T6 |
| Problem codes introduced | PATH_INVALID, PATH_EXTENSION, PATH_RESERVED and the related path, CHECKSUMS and manifest codes (full list in the source of `PackSource`, `Checksums`, `PackManifest`) | T6 | plan names no codes for these |
| Table problem codes beyond the plan | CATCH_ALL_MISSING, CATCH_ALL_NOT_LAST, CATCH_ALL_NOT_UNDETERMINED, PRIORITY_DUPLICATE, ROW_NO_CITATION, WHEN_FORMULA_CHARACTER, CELL_BAD_CHARACTER | T7 | plan names seven codes only |
| Registry problem codes beyond the plan | REGISTRY_KEY_MISMATCH, TABLE_ID_DUPLICATE, REASON_VALIDITY_INVERTED, PREFIX_RESERVED, REASON_PREFIX_MISMATCH, REASON_SHAPE_INVALID, REASON_NODE_UNDECLARED, REASON_LABEL_MISMATCH and others | T8 | plan lists fewer |
| Reason `label` | the third dot-segment of the code, must equal it | T8 | plan does not define `label`; the owner may have meant a human-readable label |
| Rationale id | `RAT-` plus 4 to 9 ASCII digits | T8 | plan says `RAT-nnnn` |
| Hash-only provision | a `quote_sha256` without a `quote` is accepted and cannot be verified by CI; a quote without a hash is `QUOTE_HASH_MISSING` | T8 | reading of spec 6.4 |
| Opaque directories depth | `*.yaml` at any depth under `derive/ route/ enrich/ validate/ endpoints/`; `scope/*.yaml` and `tests/golden/*.json` direct children only | T9 | reading of companion 5.x |
| IR choices | reports are a map keyed by report id (report order is not content); a report's leg vocabulary and a table's inputs are sorted sets | T9 | to be stated in the T26 format notes |
| `PackDigestTest#digestOfTheFixtureIsPinned` | `89d9e4331f6942bd41ad90b1c366acfcecbc722e` | T9 | captured on Windows with the hostile JVM settings (tr_TR, Pacific/Kiritimati); the Linux check is T24/T27 |

## Deviations from the plan

Chunk A (T2 to T9, base 6dc9774, head 9f1bc3e; 8 commits, 242 tests added, 0 fix rounds, every task approved by an independent reviewer, full `verify` and `check-gates.sh` re-run by the controller: green). The implementers' wording is shortened here; the commit messages and the task reports hold the detail.

- **T2.** The `p2a/**` branch filter in `ci.yml` is committed locally only (AP-3 open). The plan's red expectation for T2 only occurs before the module exists; afterwards the red is a compile error.
- **T3.** One test beyond the eight named (`parseNeverReturnsAnOutOfRangeBuild`) and a public `MAX_COMPONENT = 9999`.
- **T4.** A 13th test for the six syntax codes. The parser guards nesting depth only (`YAML_SYNTAX` "nesting too deep"); the other `Limits` fields arrive in T5. A lone CR is not normalised by T4 (T5 refuses it). A sequence at the same indent as its parent key (`a:\n- x`) is not supported and gives `YAML_EMPTY_VALUE`; the plan does not say whether to accept it.
- **T5.** `PackTextTest` (6 methods) beyond the four named test classes; additive `PackSyntaxException.excerpt()` and `MAX_EXCERPT_CHARS`. A plain scalar with a colon followed by a space gives `YAML_SYNTAX`, so a `when:` ternary and any scalar starting with `! * & | > %` or `? ` **must be double-quoted in pack files** (T10 and later sample packs, T21 writer already quotes `when`). The plan's 10,000-`[` hostile test cannot give `YAML_TOO_DEEP` because the 8192-character line limit fires first (`YAML_TOO_LARGE`); the test asserts that. Spotless rewrites printable-looking `\uXXXX` escapes in test sources, so hostile strings are built from code points. Locale and time-zone runs were covered only by the build's hostile JVM settings.
- **T6.** `PackSource` file and path errors, `PACK_BOM` and `PACK_BAD_UTF8` are thrown as `PackSyntaxException` (line 0, excerpt = path); the loader (T9) turns them into problems. `fromDirectory` also refuses a symlink or junction directory (`PACK_NOT_REGULAR`). Extra path refusals: segment ending in a dot, over 256 characters, file and directory with the same name. `Checksums.verify` also reports `CHECKSUMS_MISSING`; a CHECKSUMS line listing CHECKSUMS or ATTESTATION is `CHECKSUMS_MALFORMED`. `Seal` has a package-private `run(args, out, err)`. Two symlink tests are skipped on this Windows account (no symlink privilege); the junction test covers the reparse-point code. One test creates a 2 GiB + 1 byte sparse file in the temp directory and skips if it cannot.
- **T7.** `DecisionTable` has no catch-all flag, only `catchAllRowId`; "by its flag" was read as the declared catch_all id. Space padding around `true` is accepted; non-space Unicode whitespace gives `CATCH_ALL_NOT_UNDETERMINED`. `TableChecks` problems carry line 0 (the plan's `TableRow` and `DecisionTable` hold no source lines). Row keys id, node, priority, when, outcome, reason, rationale are required; rule_ref, cite, valid, set, legs are optional.
- **T8.** Registry records copy into sorted unmodifiable collections (types unchanged). A row's `node` is not required to equal the node segment of its reason code (the P0 skeleton rows have such mismatches); adding `ROW_NODE_REASON_MISMATCH` later would be a deliberate change.
- **T9.** `LoadResult` is its own file in `model/`; `CanonicalInputSpec` also has `FILE`, `toIr()`, `digest()`. The loader continues after a failing step and reports all problems; an unlisted scope file is `FILE_UNREFERENCED` and is not parsed. **T10 must fix two sample-pack defects the T8 parser will refuse:** the `reasons.yaml` owner text `agent:p2a-sample (Compliance owner not named, gate G1)` fails `OWNER_INVALID` (needs `[A-Za-z][A-Za-z0-9_.-]{0,63}`), and the `sources.yaml` version `2026-10-08 draft 3` fails `VERSION_INVALID` (use `2026-10-08-draft-3`). The loader does not yet check table `inputs` against the declared canonical variables (T15 compile rejects undeclared paths in expressions). The report-listed files are checked for existence only, not for role. T9 and T7 both detect duplicate row ids (`ROW_ID_DUPLICATE`); avoid double-reporting when T21 lands.

Open questions that the owner may answer at the freeze (none blocks work): the `label` meaning (T8), opaque-directory depth (T9), report map/set IR choices (T9), whether an empty `scope_tables` list should be accepted (T6), hash-only provisions (T8).
