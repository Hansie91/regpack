# P2a format notes: the Regime Pack as built

What plan P2a (rule-pack core, increment S0) implemented, written as built at the head of task T26. It replaces the five sections of the companion contracts file `docs/superpowers/plans/2026-10-16-p2a-contracts.md` where the code differs from them, and it stands in for the specification text that the owner has not edited yet (open item D-4). The specification is not edited by P2a.

**Everything here is provisional.** The grammar, the IR, the `engine_min` semantics, the CSV format and every limit freeze only at a G-S3b pass (spec 6.8); a change to any of them that can alter a decision is a new engine `major` or library version (section 5.1), otherwise a plan amendment. The notes carry no regulatory text: the sample pack and every fixture are synthetic (gates G3 and G4), and every golden case is consistency-only while gate G1 is open.

**Format elements beyond spec 6.1 and the 15 accepted wordings** (offered to the owner as inputs to the pack-format freeze, not as decisions): the `limits` block of `pack.yaml`, the file `tests/authorship.yaml`, the file name `model/canonical-input.yaml`, the CHECKSUMS line format, `input_schema` as the SHA-256 of the typed IR of the input model, row-level `legs`, the quote length cap, the strict-provenance rule for pack goldens (D-P6), the file-size and path-shape limits of `PackFormat`, and the problem codes of section 6.

Sections 1 to 5 follow the numbering of the companion contracts file; section 6 lists every problem code; section 7 the hand-offs; section 8 the pins; section 9 the questions for the owner at the freeze.

## 1. SP-G amendments: what P2a does with each accepted wording

The wording is the NEW text of `docs/spikes/sp-g/register.md`. IMPLEMENTED means behaviour and tests exist in P2a; RESERVED means the slot is parsed strictly, digested and listed by `pack test` as not interpreted by this engine build, and nothing more is promised; LEFT means no P2a artefact exists to carry it. `AmendmentTableTest` keeps this table complete: each accepted wording of the register has exactly one row here, and the notes cite no id that the register lacks.

| id | disposition | where |
|---|---|---|
| BRK-01 | RESERVED | `record_key` is an accepted top-level key of `lifecycle.yaml`; its content is not interpreted. Sequencer P3, correlation P8 |
| BRK-02 | RESERVED | `trn` and `identifiers` are accepted top-level keys of `lifecycle.yaml`; not interpreted. The `trn_assignment` rule is P3, content P6b |
| BRK-04 | RESERVED | A variable with `source: STATE` is accepted by the loader and the compiler and is read through `Bindings`; the production input mapping (`CanonicalBindings`) refuses it with `INPUT_PATH_UNSUPPORTED` until P3 supplies state. Proved with a stubbed `state.status` in the second-regime fixture (`SecondRegimeFixtureTest`). The fold, the `input_digest` part and the ledger contract are P3 and P12 |
| BRK-06 | LEFT | No report schema or projection exists, so `volatile` fields and `restamp` have no carrier. P8 |
| BRK-07 | RESERVED | `successor_gate` is an accepted top-level key of `lifecycle.yaml`; not interpreted. P3, P8 |
| BRK-08 | RESERVED | A named regime clock (a mapping with the keys `anchor` and `offset`, and optionally `on_expiry`) is accepted at the top level of `lifecycle.yaml`; not interpreted. Timekeeper P3 |
| BRK-09 | LEFT | Feedback `correlation` belongs to endpoint profiles. P8 |
| BRK-10 | IMPLEMENTED | `set:` on a scope row (T7); the decision stores it as `attributes{}` (`DecisionBody.attributes`, T15), the one ledger-contract change of P2a |
| BRK-11 | IMPLEMENTED | `reasons.yaml` declares `prefix` and `nodes`; codes are `<PREFIX>.<node>.<label>[.<provision>]` (T8, OI-7) |
| BRK-12 | RESERVED | The leg vocabulary of a report is closed and limited to MARKET, CLIENT, ALLOCATION (`LEG_VOCABULARY_RESERVED`); a row leg outside the report vocabulary is `ROW_LEG_NOT_IN_REPORT`. An open vocabulary (EMIR `SIDE`) needs the ledger leg type: P3, P12 |
| BRK-13 | RESERVED | `imports` in `pack.yaml` is accepted only as an empty list (`IMPORTS_RESERVED`); extra event kinds are P12 |
| BRK-14 | IMPLEMENTED | `reports[].id` is the `Regime` of the decisions; one `PackRegime` per report (T6, T16) |
| BRK-15 | LEFT | The profile `confirmation` rule belongs to endpoint profiles. P8 |
| BRK-16 | RESERVED | The top-level keys of `lifecycle.yaml` are checked against the list in section 5.4 (`LIFECYCLE_KEY_UNKNOWN`); the content is not interpreted |
| BRK-19 | IMPLEMENTED | Row shapes of scope tables and the report-definition keys (T6, T7). The derive row shape is left to P2a-2 and the `validate/` row shape to P5; `derive/` and `validate/` files are strict-parsed, digested and not interpreted |

BRK-18 is an accepted item for P12 and has no wording. BRK-03, BRK-05 and BRK-17 were rejected: nothing in P2a.

**OWNER INPUT (departures table row 4 of the plan).** Applying the 15 wordings to spec 6.1, 6.2, 6.3, 7.4, 9.2, 9.7, 10 and 11.1 (open item D-4) is the owner's edit, plus the two edits that no proposal contains: the `legs[]` comment of the 6.3 record (BRK-12; until then P2a limits legs to MARKET, CLIENT, ALLOCATION) and the `scope/` and `derive/` lines of the 6.1 file tree (BRK-10; the second-regime fixture already puts the action-type table under `scope/`). Until then these notes state what was implemented.

## 2. The pack file contract (as built)

```
packs/<id>/
  pack.yaml            id, semver, regime, jurisdiction, valid.legal.from[, to], as_at_field, engine_min,
                       input_schema, [supersedes], [limits], [imports: []], reports: [...]
  model/canonical-input.yaml   variables: { <path>: {type, [enum], [source], [optional]} }
  scope/*.yaml                   decision tables
  reasons.yaml  sources.yaml  rationales.yaml  [interpretations.yaml: absent or "pins: []"]
  derive/ lifecycle.yaml route/ enrich/ validate/ endpoints/ refdata.yaml   strict-parsed, digested, NOT interpreted
  tests/golden/*.json   tests/authorship.yaml
  CHECKSUMS            "<64 hex>  <path>" per file, sorted, LF-normalised bytes
```

- **Allowed paths** are exactly: the root files `pack.yaml reasons.yaml sources.yaml rationales.yaml interpretations.yaml lifecycle.yaml refdata.yaml CHECKSUMS ATTESTATION`; `model/canonical-input.yaml`; `scope/*.yaml` and `tests/golden/*.json` (direct children only); `tests/authorship.yaml`; `*.yaml` at any depth under `derive/`, `route/`, `enrich/`, `validate/`, `endpoints/`. Any other path, including a `.json` outside `tests/golden/`, is `FILE_UNKNOWN`; anything under `tests/properties/` is `PROPERTY_TESTS_RESERVED` (checked first).
- **Path rules** (`PackSource`): file names end in `.yaml` or `.json` (CHECKSUMS and ATTESTATION excepted at the root); a segment matches `[A-Za-z0-9_.-]+`, does not start or end with a dot and is not a Windows device name; at most 256 characters per path and 16 directory levels; two paths that differ only in case, or a file and a directory of the same name, are refused; files are UTF-8 without BOM, and CRLF is normalised to LF when text is read. A symbolic link or junction is refused.
- **Hard limits** (`PackFormat`): 256 files, 1 MiB per file, 16 MiB in total, 8192 characters per line and per scalar, 128 per key, nesting depth 32, 100,000 YAML nodes, 100 problems per file and 1,000 per load (`MAX_PROBLEMS_PER_FILE`, `MAX_PROBLEMS_PER_LOAD`: further problems are replaced by one `PROBLEMS_TRUNCATED`, section 6.7, and the readers stop reading the file; the loader stops parsing further tables). Engine hard caps for the pack-declared limits: `rule_nodes` 4,096, `list_items` 4,096, `string_code_points` 65,536, `record_budget` 10,000,000.
- **Manifest** (`pack.yaml`): `id` and `regime` are lower-case dotted ids (for example `mifir.eu.rts22`); `semver` is plain major.minor.patch with no leading zeros and at most 9 digits per part; `jurisdiction` matches `[A-Z][A-Z0-9]{1,7}`; `valid` accepts exactly the key `legal` with `from` and optional `to` (a half-open window of UTC dates); `as_at_field` is a dotted path of a declared TIMESTAMP variable; `engine_min` looks like `1.0.0+lib1`; `input_schema` is 64 lower-case hex digits and must equal the digest of the typed IR of `model/canonical-input.yaml`; `supersedes` matches `[A-Za-z0-9][A-Za-z0-9._+-]{0,127}`. A report is `{id, input, scope_tables, [derive_tables], [validate], [lifecycle], [endpoint_profiles], [legs]}`; the key `schema` is reserved (`REPORT_KEY_RESERVED`); `input` must be `model/canonical-input.yaml`; `scope_tables` has at least one entry under `scope/` (an empty list is `REPORT_SCOPE_TABLES_EMPTY`, a question in section 9).
- **Validity windows.** `valid` accepts exactly the key `legal`; `supervisory`, `event` or any other key is `VALIDITY_WINDOW_RESERVED` (RSV-6), in `pack.yaml` and in table rows. The window is `[from, to)` in UTC dates.
- **Limits.** `limits` holds `rule_nodes`, `list_items`, `string_code_points` and `record_budget`; each is an ASCII decimal integer from 1 to the hard cap, else `LIMITS_OUT_OF_RANGE`. Defaults: 512 nodes, 256 list items, 4096 code points, no declared record budget.
- **Legs.** A report's `legs` is its closed leg vocabulary, a subset of MARKET, CLIENT, ALLOCATION (default all three); a row-level `legs` entry outside it is `ROW_LEG_NOT_IN_REPORT`.
- **Cells.** Every string cell of a table row is one line of clean text: LF, CR, TAB and any `PackText` violation give `CELL_BAD_CHARACTER`, `when` included. Ids, `source_id`, `rule_ref` and `rationale` references match `[A-Za-z][A-Za-z0-9_.-]{0,63}`, so none can start with `= + - @` or contain `# ; ,`.
- **Registries.** `reasons.yaml`: `prefix` (`[A-Z][A-Z0-9]{0,15}`), `nodes` (`[A-Z][A-Z0-9]{0,7}`), `reasons: [{code, label, category, default_outcome, owner, valid}]` with category EXCLUSION, INCLUSION, OUT_OF_SCOPE, DATA_QUALITY or DEFAULT. The engine reserves the codes `EXEC_ERROR`, `EXEC_BUDGET`, `EXEC_CIRCUIT_OPEN` and `ENRICH_MISS` (`REASON_RESERVED`, `PREFIX_RESERVED`). `sources.yaml`: `sources: [{source_id, version, retrieved_at, content_sha256, [eli], provisions: [{path, role, status, [quote], [quote_sha256]}]}]`; a quote is at most 240 code points and needs its `quote_sha256` (the SHA-256 of the quote as parsed). `rationales.yaml`: `rationales: [{id, text}]` with ids `RAT-` plus 4 to 9 digits.
- **Goldens.** `tests/golden/<id>.json` is the P0 JSON format with a mandatory `provenance` object (`{"kind":"CONSISTENCY_ONLY"}` while G1 is open; any real kind needs `source`, `reviewer` and `reviewedOn`, and a consistency-only case must carry none of them). `tests/authorship.yaml` is `rules: {<row id>: <author id>}` with one entry per row.
- **CHECKSUMS and sealing.** One line per file, `<64 hex>  <path>`, sorted, over the LF-normalised bytes, listing every file except CHECKSUMS and ATTESTATION. `pack seal <dir>` rewrites it. It is an integrity check against accidental change, not authenticity: P2a has no signature, the loader never claims one (`LoadedPack.signed()` is false) and `pack test` prints `UNSIGNED`. ATTESTATION bytes are opaque, size-bounded, neither parsed nor printed; the loader adds the note `attestation present, not verified (P9b)`.

### Decision table file

Every scalar is a string; the parser types explicitly. Header keys exactly `id hit_policy priority inputs catch_all` (`hit_policy` is `FIRST`, `priority` is `explicit`); row keys `id node priority when outcome reason rule_ref cite rationale valid set legs`; `id node priority when outcome reason rationale` are required, the rest optional. `valid` is required on every row except the catch-all, which has none. The shape and an example are in the companion file, section 2.

## 3. The expression language contract (as built)

The expression layer is the in-house CEL-shaped subset over `BigDecimal` (assumption A1). Code: `dev.regpack.engine.expr`. Design basis was the SP-R spike; nothing was copied.

- **Grammar** (lowest precedence first): ternary `c ? a : b`; `||`; `&&`; relations `== != < <= > >= in`; `+ -`; `* /`; unary `! -`; postfix method call; primary: literal, parentheses, list `[e, ...]`, path, function call, `has(path)`. Literals: 64-bit int; decimal (ASCII digits, a dot, digits; keeps its scale); string in single or double quotes with the escapes `\\ \' \" \n \r \t`; `true`, `false`. Refused at compile (`ExprException`, kind `PARSE`): `null`, exponent and hex literals, assignment, single `&` or `|`, loop macros, user functions, unknown functions and methods, wrong arity, field access on a non-path, syntactic nesting above 100, tree depth above 256, a string literal longer than `string_code_points`, and any path not declared in the input model. Whitespace is space, tab, CR, LF only; no locale anywhere.
- **Decimal literal limit.** Scale at most 38 and precision at most 38 (digits after stripping leading zeros), counted on the source text before any `BigDecimal` is built (review finding R3: a million-digit literal used to cost seconds).
- **Node ceilings.** The parser stops at 16 times `rule_nodes` nodes (`WHEN_DOES_NOT_COMPILE`); a rule between `rule_nodes` and that cap compiles and is then refused by the pack compile with `RULE_OVER_CEILING`. `pack test` prints the SP-R formula `max(64, 4 x largest node count)` beside the enforced `rule_nodes`.
- **Typing** is dynamic (the type checker is P2b); the declared-type map only refuses undeclared paths. A clash is a `TYPE` error at evaluation.
- **Semantics.** int op int gives int and overflow is `OVERFLOW`; with a decimal operand the result is decimal (DECIDE D13); above 38 digits `SCALE`; int `/` int truncates; decimal `/` is exact or `NON_TERMINATING`; division by zero `DIV_ZERO`; `+` also joins strings and lists. `==` and `!=`: same type; int with decimal numerically (1.10 equals 1.1); enum with enum by type and name; enum with string by name (S07); lists elementwise; else `TYPE`. `< <= > >=`: numbers, strings by code point, timestamps, dates, else `TYPE`. `x in list` uses `==`; a non-list right side is `TYPE`. `&&` and `||` go left to right and short-circuit; a non-bool operand is `TYPE`; an error in an evaluated operand propagates (DECIDE A04 and E06). A declared path with no binding is `ABSENT` when read; `has(p)` is true iff bound (DECIDE A07 default: false). A ternary needs a bool condition. A string or list result longer than `string_code_points` or `list_items` is `RANGE`; for `+` the check uses the operand sizes and comes after the charge and before the allocation; for `upper` and `lower` it follows the operation.
- **Library, version 1** (names unfrozen): `dec`, `round(d, places, 'HALF_UP' or 'HALF_EVEN')` (places 0 to 38), `string`, `int`, `timestamp`, `date`, `isoMicros`, `dayOfWeek`, `upper`, `lower` (`Locale.ROOT`), `size` (code points or list items), `contains`, and the methods `startsWith`, `endsWith`, `contains`. Identifier functions (LEI, ISIN, CFI, MIC) are P5. Choices the plan left open, all provisional library-version-1 behaviour: `int(text)` overflow is `OVERFLOW`; `round` takes an int or a decimal and a string mode (else `TYPE`); `timestamp(text)` accepts only upper-case `T` and `Z`, an offset `+HH:MM` or `-HH:MM` with HH up to 23 (a zone beyond 18 hours is `PARSE`) and any fraction length provided the digits beyond the sixth are zero; `string(date)` is `RANGE` outside the years 0000 to 9999; `dayOfWeek` returns a string; `startsWith`, `endsWith` and `contains` compare UTF-16 units while `size` and the cost use code points (lone surrogates are not checked).
- **Errors.** `ErrorKind`: `ABSENT TYPE OVERFLOW DIV_ZERO NON_TERMINATING PARSE RANGE SCALE LOSSY OTHER`. `ExprException` carries no stack trace. A budget overrun is the port's `BudgetExceededException`, never an `ExprException`.
- **Cost model v1** (charged through the meter before the work): 1 per node evaluated, on entry; plus the length of the list operand of `in` and `contains(list, x)`, whether or not an earlier item matches; plus both sizes for list `+`; plus `(code points of both) / 256` for string `+`; plus `code points / 256` per string argument of `upper lower size string int startsWith endsWith`; plus, for string `contains(x, y)`, `(cp(x) / 256 + 1) x (cp(y) / 256 + 1) - 1`; plus `1 + code points / 256` for `dec(text)`, `timestamp`, `date`; plus 1 for `round`, decimal `/` and `dec(non-text)`. Integer division floors.
- **Static worst case.** `InHouseExpression.Compiled.staticWorstCaseUnits()` is the sum over all nodes (taken or not) of 1 plus the surcharge bound, every list bound at `list_items` and every string at `string_code_points`. **Contract clarification (T12, already in the contracts file):** a list literal is bounded at its own length, also as a branch of a ternary, and a ternary is bounded by the larger of its two branches. **Pessimism:** the bound adds every possible surcharge of an operator whatever the operand types, so `1 + 2` has a static bound of 547 against 3 at runtime. This inflates `PackBudget` (the per-record budget is 2 x the sum over all rows of 1 plus the static worst case, unless `limits.record_budget` is declared); a type checker (P2b) can tighten it. A property test shows that runtime units never exceed the bound for inputs within the limits.
- **Evaluator stack.** The evaluator is recursive and does not catch `StackOverflowError`. Every thread that evaluates expressions must have a stack of at least 256 KB (a depth-256 chain needs about 128 to 160 KB; overflow was measured at 128 KB). The throughput check uses 1 MiB.
- **Rulings** (DECIDE vectors D13, A04, E06, A07, S07): see section 5.5.

## 4. Execution: tables, decision record, circuit

- **Interpretation (OI-3, provisional).** The program reads `as_at_field` (a timestamp; an unreadable value is an `EXEC_ERROR` under the constant rule id `as_at_field`) and takes its UTC date. It visits the report's tables in order; in each table it walks the rows ascending by priority, skips a row whose `valid` window does not contain that date (no charge, no path step; the catch-all has no window) and evaluates every other row (one unit per row plus the expression cost). The first match decides the table; if it is not the catch-all it is the decision; otherwise the next table is tried; if every table ended in its catch-all the decision is the last table's catch-all. A table in which no row matches cannot occur in a checked pack; the program fails closed to `EXEC_ERROR`.
- **Decision record.** A completed run gives the row's outcome, reason and node, `ruleIdsHit` = the row id, the recorded path, citations resolved through the Legal Source Registry, `legs` (the row's legs for REPORT, else empty), `attributes` = the row's `set`, `asAt`, and `inputDigest`. `decisionContext()` carries the pack digest, `none` for overlay and Firm Profile, no pins and the engine build text. Every decision is stamped with exactly this context: `evaluate` and `evaluateTraced` refuse (`IllegalArgumentException`) a caller context that differs from it in any component, until P2a-2 lets the caller supply the overlay, pins and Firm Profile.
- **Coverage through a table chain (OI-3, provisional; the owner confirms before P6a).** A golden case's `rule_ids_hit` names the DECIDING row. For the coverage gate a case exercises every row that matched on the way to the decision (the matched steps of the recorded path), so the catch-all of a non-last table, which never decides while a later table follows, is covered by the case that passes through it. A one-table pack behaves as before. A pack that cannot represent an execution (a `tradedAt` finer than a microsecond or outside the years 0000..9999, a `|` or blank in `firmLei`, `venueMic` or `venueExecutionId`) is refused at the codec: `GOLDEN_INPUT_INVALID` in `pack test`, exit 2 in `pack explain --event`.
- **Failures are UNDETERMINED (I5).** `EXEC_BUDGET`, `EXEC_ERROR` and `EXEC_CIRCUIT_OPEN` give UNDETERMINED with node `EXEC`, no rule hit, the recorded path and the citations of the last table's catch-all. Flags: budget `rule_id=<id>`, `cost_units=<n>`, `limit=<n>`; error `rule_id=<id>`, `error_class=<class>`, `error_kind=<KIND>` when known, `cost_units=<n>`; open circuit `rule_id=<id>` and `circuit=open`. Flags never echo record values.
- **Poison circuit (T17, OI-4).** Defaults N = 4 failures in M = 50 evaluations (`RuleCircuit.DEFAULT_FAILURES`, `DEFAULT_WINDOW`; MODELLED, not relied on). Windows are tumbling and count evaluations of one rule, never time: the first M evaluations of a rule are window 1, the next M window 2. A rule opens as soon as its window holds N failures, checked before the window rolls (so the M-th evaluation can open it). `record()` on an open rule is a no-op; `close(ruleId)` resets the window; `openEvents` counts every open including re-opens. An open circuit closes only by `close(ruleId)` or a new pack digest: the caller must create a new circuit per pack digest (`PackRegime` documents it, nothing enforces it). An open rule gets no path step and no charge. The decision carries the flag `circuit=open` so that P3 can enumerate those decisions; nothing in P2a re-evaluates them.
- **Explain.** Tier 1 prints the stored decision with the attributes line only when attributes exist; `PackRegime.evaluateTraced` runs the regime once with the full trace and no circuit, so an explain neither runs a rule twice nor feeds the circuit. The Tier-2 rule is section 5.1. `pack explain --event` re-evaluates the event and stores nothing, so its last line is `Tier 2: no stored decision; evaluated on the current engine <build>`: it compares no stored build and claims no replay compatibility (the replay rule of section 5.1 is exercised by `Tier2Plan` and applies once a stored decision is explained, P3). **Output hygiene:** everything `pack explain` prints (rule and event) passes through one escaping step: each character outside 0x20..0x7E other than the LF that ends a line is written as a `\uXXXX` escape, and a line longer than 1,000 characters after escaping is cut and ends in `[cut]`. This covers the event identity (`firmLei|venueMic|venueExecutionId`) of the Tier-1 line, which comes from the event file and is otherwise unchecked.
- **Reviewer registry of `pack test`.** `--reviewers <file>` names the registry; without it `governance/golden-reviewers.txt` is read relative to the JVM working directory, and an absent file is an empty registry (every real provenance claim then fails with `REVIEWER_NOT_REGISTERED`). `scripts/pack.sh` changes to the repository root first, so there the default is the repository registry; run from any other directory (an IDE, `java -cp ...`, a launcher) the default is whatever that directory holds, or nothing. The audit line therefore names its source: `audit: ... reviewers registered: N from <absolute normalised path>` or `... from none (empty registry)` (escaped, at most 240 characters), so that a result can be reproduced.
- **`pack table export --out` and `import --write` write files.** Both write through a temporary file in the target's directory (`.table-*.tmp`) and an atomic move; if the JVM is killed between the two, the leftover makes `PackSource` refuse the pack (`PATH_INVALID`) until it is removed by hand. Where the file system has POSIX permissions the new file keeps the mode of the file it replaces, else it gets the process default (umask). `export --out` refuses (exit 2, `OUT_INSIDE_PACK`) an output path that, after resolving symbolic links of its existing part, lies inside the pack directory.

## 5. Formats and rules that the tasks apply

### 5.1 Replay compatibility and `engine_min` (T3)

Two builds are replay-compatible iff they have the same `major` and the same `libraryVersion`; `minor` and `patch` never matter. `major` is bumped by any change that can alter a decision for the same pinned inputs (interpreter or table semantics, the cost model, any expression construct, a DECIDE ruling); `libraryVersion` by any change to a built-in function's behaviour or cost. A running engine satisfies an `engine_min` iff it has the same `major` and `libraryVersion` and (`minor`, `patch`) not below the minimum's: a pack never runs on another major or library version unless it is released again with a new `engine_min` (fail closed). The build text is `<major>.<minor>.<patch>+lib<N>` (ASCII digits, no leading zeros, each at most 9999); this build is `1.0.0+lib1`. `SemanticsFingerprintTest` fails when a ruling, a vector or a cost fixture changes without a version bump.

### 5.2 PackYaml/1 (T4, T5)

One document; UTF-8 with LF (CRLF normalised first); block mappings and block sequences by indentation with spaces (a sequence item is a scalar, a one-line flow collection, or a block mapping that may start on the dash line; a sequence directly in a sequence is refused; a sequence at the same indent as its parent key is not supported); one-line flow lists and flow mappings that nest; plain, single-quoted (`''` is a quote) and double-quoted scalars (escapes only `\\`, `\"`, `\n`); comments after `#` at line start or after a space, outside quotes; every scalar is a string; keys are plain (`[A-Za-z_][A-Za-z0-9_.-]*`) or quoted; a key with no value is an error, `''` is the empty string; an empty or comment-only file is an empty mapping. Refused: anchors, aliases, tags, merge keys, directives, document markers, block scalars, complex keys, tabs, duplicate keys, multi-line scalars, lone CR, and any character that `PackText` refuses (control, format, surrogate, private-use, line and paragraph separators, noncharacters, default-ignorable code points). **Quoting rule:** a plain scalar with a colon followed by a space, or one that starts with `! * & | > %` or `? `, must be double-quoted (so a `when:` with a ternary is always quoted; the table writer quotes every `when`).

### 5.3 Decision-table rules (T7)

Row keys and charsets as in section 2. `priority` matches `[1-9][0-9]{0,8}` and is unique in the table, ascending is evaluated first; `outcome` is an exact `Outcome` name (no case folding); `node` matches `[A-Z][A-Z0-9]{0,7}` and is declared in `reasons.yaml`; `provision_path` matches `[A-Za-z0-9_./:-]+`; `set` names match `[a-z][A-Za-z0-9_.]*` and values `[A-Za-z0-9_.:/-]+`; `legs` only on REPORT rows (default `[MARKET]`, OI-2). The mandatory catch-all is the row named by the header `catch_all`: last, `when: true`, outcome UNDETERMINED, no validity window. `TableChecks` problems carry line 0 (the model holds no source lines). Rows are sorted by priority in the model. `TableCsv.importRows` goes through the same parser, so a CSV import cannot bypass a check.

### 5.4 Loader order, reserved slots, canonical IR and digest (T9)

The loader verifies CHECKSUMS, applies the allowed-path rules, parses the manifest and the input model, compares the input-schema digest, checks `as_at_field`, then the reports (listed files exist, tables pass `TableParser` and `TableChecks`, an unlisted scope file is `FILE_UNREFERENCED` and is not parsed), the registries and `RegistryChecks`, pack-wide row-id and `rule_ref` uniqueness, row legs against the report vocabulary, and the reserved and opaque files. **It does not stop at the first failing step: it reports every problem it can find, sorted by (path, line, code), and returns a pack only when there is none.** Reserved, accepted only empty: `interpretations.yaml` absent or `pins: []` (`INTERPRETATIONS_RESERVED`); `imports: []`. Not interpreted (`derive/`, `lifecycle.yaml`, `route/`, `enrich/`, `validate/`, `endpoints/`, `refdata.yaml`): strict-parsed, digested, and one note `not interpreted by this engine build: <path>` each. The top-level keys of `lifecycle.yaml` must be among `business_key record_key trn identifiers status_model corrections successor_gate due_at provenance examples` (`provenance` and `examples` serve the G1 labelling of fixtures and are not part of BRK-16) or be a named clock, else `LIFECYCLE_KEY_UNKNOWN`. The loader does not check table `inputs` against the declared variables (the compile in `CompiledPack` does) and checks that report-listed files exist, not what role they have.

**IR** (numbers are `Long`, as `Json.canonical` requires): `{"formatVersion":1,"packId":...,"files":{<path>: <value>}}` over every file except CHECKSUMS and ATTESTATION. Choices of this implementation: reports are a map keyed by report id (report order is not content); a report's leg vocabulary and a table's `inputs` are sorted sets; tables are typed maps with rows ascending by priority, `set` sorted, absent optionals omitted and REPORT `legs` explicit; registries are typed maps sorted by id; opaque YAML and golden JSON are generic trees. The digest (SHA-256 of the canonical JSON) therefore ignores comments, key order, row order, line endings and the operating system. The digest of a pack is not a signature.

### 5.5 Conformance data (T14)

The 46 vectors are re-authored, not imported: `id class expression bindings expected budget ceiling basis` (tab-separated, UTF-8, LF). Classes: MUST 12, SHOULD 19, DECIDE 5, SPIKE-DESIGN 10; 17 cost fixtures. `ConformanceSuiteTest` runs them; `SemanticsFingerprintTest` pins the fingerprint over `rulings.tsv`, `vectors.tsv` and `cost-fixtures.tsv`. The vectors are a starting suite, not a neutral oracle (H-7).

**`rulings.tsv`** has the columns `id status switch value alt_value expected expected_alt ruled_by ruled_on note`, and its keying is explicit (the companion left it open): `value` is the ruled value; `alt_value` never changes; `expected` is the outcome under the in-house (first listed) value and `expected_alt` the outcome under `alt_value`. Four comment lines in the file say so and `Rulings.parse` enforces it. Five rows, all `PROVISIONAL` with `ruled_by` and `ruled_on` `-`:

| id | switch | value | alt_value |
|---|---|---|---|
| D13 | intDecimalMix | ALLOW | DENY |
| A04 | andLeftError | PROPAGATE | ABSORB |
| E06 | orLeftError | PROPAGATE | ABSORB |
| A07 | hasBelowAbsent | FALSE | ERROR_IF_ANCESTOR_ABSENT |
| S07 | enumStringEquality | BY_NAME | none (validated, not a switch) |

A ruling is one line: the owner changes `value` (and `status`, `ruled_by`, `ruled_on`) of that row; `Rulings.load()` builds the switches from it; the fingerprint test then demands a version bump; a ruled DECIDE row changes class to MUST in `vectors.tsv` in the same commit and the pinned counts of `VectorFileTest` move with it. `InHouseExpression(Limits)` builds its rulings with `Rulings.load()`, so a ruling takes effect. Known gaps: a row with status PROVISIONAL and the alternative value is accepted and takes effect silently, and the suite only runs under the provisional file (the ruled-row path has no test yet).

### 5.6 Table interpretation, OI-3 (T16)

See section 4. In addition: `PackRegime.create` refuses (an `IllegalArgumentException` naming at most 8 problems, sorted, plus a count) a pack that does not compile, whose canonical input `CanonicalBindings.checkSupported` rejects, or whose `engine_min` the running `EngineInfo.BUILD` does not satisfy (`ENGINE_MIN_NOT_SATISFIED`). A rule whose circuit is open is reported as `EXEC_CIRCUIT_OPEN`, never as `EXEC_ERROR`.

### 5.7 CSV format and table YAML (T21)

UTF-8, LF, no BOM, RFC 4180 quoting: a field is quoted iff it contains a comma, a double quote, LF or CR, or begins or ends with a space; a quote inside is doubled. One header row with exactly `id,node,priority,when,outcome,reason,rule_ref,cite,rationale,valid_from,valid_to,set,legs`; rows ascending by priority; an empty cell is an absent value; `cite` is `source_id#provision_path#ROLE` items joined by `;`, `set` is `name=value` items joined by `;` sorted by name, `legs` names joined by `;`. **Import** accepts CRLF, one leading BOM, a missing final newline and any column order, but exactly the expected column set; the table header (`id`, `inputs`, `catch_all`) comes from the existing table; no cell is trimmed. **Staging:** a stage-one problem (`CSV_COLUMNS`, `CSV_QUOTE`, `CSV_CELL_COUNT`, `SET_INVALID`) stops the later stages, so the table parser and the table checks do not run until the CSV itself is well formed. **Blank lines:** a blank line is a record of one empty cell and is refused with `CSV_CELL_COUNT` (also a second newline at the end of the text); only a missing final newline is tolerated. Line breaks inside cells are forbidden (LF, CR and TAB in any cell are `CELL_BAD_CHARACTER`). Export does not escape formula characters: the guarantee that no cell begins with `= + - @` rests on the table having passed `TableChecks` (`WHEN_FORMULA_CHARACTER`) and on the shape limits of every other column. The table YAML is written in one canonical layout (keys in contract order; `when` and every non-plain-safe value double-quoted; `inputs`, `cite`, `set`, `legs` as flow collections); **comments in the original file are not preserved**. `TableCsv.importRows` has no size or row cap (20,000 rows took 371 ms) but reports at most 100 problems (then `PROBLEMS_TRUNCATED`) and stops reading the records once more than 100 of them have the wrong number of cells; `pack table import` reads the file under the `PackFormat` file-size cap first and prints at most 1,001 problem lines.

## 6. Problem codes

Checks return `PackProblem(code, path, line, message)`; tests assert the upper-case ASCII code. The message and path are bounded and escape non-ASCII, and untrusted text is shown through `PackProblem.excerpt` (24 characters). Results are sorted by (path, line, code). A `PackSyntaxException` code is turned into a problem by the loader at the path and line it names.

### 6.1 Source, paths and CHECKSUMS

| code | meaning |
|---|---|
| `PATH_INVALID` | a path segment is empty, starts or ends with a dot or has a character outside `[A-Za-z0-9_.-]`, or the path or directory depth is over the limit |
| `PATH_EXTENSION` | a file name that does not end in `.yaml` or `.json` (CHECKSUMS and ATTESTATION excepted) |
| `PATH_RESERVED_NAME` | a segment whose base name is a Windows device name |
| `PATH_CASE_COLLISION` | two paths that differ only in case |
| `PATH_FILE_AND_DIRECTORY` | a file and a directory with the same name |
| `PACK_NOT_REGULAR` | a symbolic link, junction or other non-regular entry in the pack directory |
| `PACK_TOO_MANY_FILES` | more than 256 files or directories |
| `PACK_FILE_TOO_LARGE` | a file over 1 MiB |
| `PACK_TOO_LARGE` | the pack is over 16 MiB in total |
| `PACK_BOM` | a pack file starts with a byte order mark |
| `PACK_BAD_UTF8` | a pack file is not valid UTF-8 |
| `CHECKSUMS_MISSING` | the pack has no CHECKSUMS file |
| `CHECKSUMS_MALFORMED` | a CHECKSUMS line is not `<64 hex>  <path>`, or lists CHECKSUMS or ATTESTATION |
| `CHECKSUMS_DUPLICATE_ENTRY` | a path is listed twice |
| `CHECKSUM_FILE_MISSING` | a listed file is not in the pack |
| `CHECKSUM_MISMATCH` | a file's content differs from its CHECKSUMS entry |
| `CHECKSUM_UNLISTED_FILE` | a file is not listed in CHECKSUMS |

### 6.2 PackYaml/1

| code | meaning |
|---|---|
| `YAML_TOO_LARGE` | the file, a line or a scalar is over its size limit |
| `YAML_TOO_MANY_NODES` | more than 100,000 nodes |
| `YAML_TOO_DEEP` | nesting deeper than the limit |
| `YAML_TAB` | a tab character |
| `YAML_BAD_CHARACTER` | a character that the `PackText` policy refuses |
| `YAML_DOCUMENT_MARKER` | a document marker (`---` or `...`) |
| `YAML_ANCHOR` | an anchor (`&`) |
| `YAML_ALIAS` | an alias (`*`) |
| `YAML_TAG` | a tag (`!`) |
| `YAML_BLOCK_SCALAR` | a block scalar (the indicators vertical bar and greater-than) |
| `YAML_DIRECTIVE` | a directive (`%`) |
| `YAML_COMPLEX_KEY` | a complex key (`?`) |
| `YAML_MERGE_KEY` | a merge key (`<<`) |
| `YAML_BAD_ESCAPE` | an escape other than the three allowed in double quotes |
| `YAML_UNTERMINATED` | a quoted scalar or flow collection that is not closed on its line |
| `YAML_SYNTAX` | any other syntax error |
| `YAML_BAD_INDENT` | an indentation that does not fit the block structure |
| `YAML_MULTILINE_SCALAR` | a scalar continued on the next line |
| `YAML_EMPTY_VALUE` | a key with no value |
| `YAML_DUPLICATE_KEY` | a key used twice in one mapping |
| `YAML_NESTED_SEQUENCE` | a sequence directly inside a sequence |
| `YAML_BAD_KEY` | a key that is neither plain nor quoted as required |

### 6.3 Manifest

| code | meaning |
|---|---|
| `MANIFEST_MISSING` | the pack has no `pack.yaml` |
| `MANIFEST_SHAPE` | a manifest part is not a mapping or list as required |
| `MANIFEST_KEY_UNKNOWN` | an unknown key in `pack.yaml` or a report |
| `MANIFEST_KEY_MISSING` | a required key is missing |
| `REGIME_ID_INVALID` | `id` or `regime` is not a lower-case dotted id |
| `SEMVER_INVALID` | `semver` is not plain major.minor.patch |
| `JURISDICTION_INVALID` | `jurisdiction` is not 2 to 8 upper-case ASCII characters or digits |
| `DATE_INVALID` | a date that is not `yyyy-mm-dd` (manifest, rows and registries) |
| `VALIDITY_WINDOW_RESERVED` | a validity key other than `legal` (RSV-6) |
| `VALID_RANGE_INVALID` | `valid.legal.to` is not after `from` |
| `AS_AT_FIELD_INVALID` | `as_at_field` is malformed or not a declared TIMESTAMP variable |
| `ENGINE_MIN_INVALID` | `engine_min` is not of the form `1.0.0+lib1` |
| `INPUT_SCHEMA_INVALID` | `input_schema` is not 64 lower-case hex digits |
| `SUPERSEDES_INVALID` | `supersedes` is not a pack reference |
| `LIMITS_OUT_OF_RANGE` | a limit is not an integer from 1 to the engine hard cap |
| `IMPORTS_RESERVED` | `imports` is not an empty list (BRK-13, RSV-6) |
| `REPORTS_EMPTY` | the pack has no report |
| `REPORT_ID_DUPLICATE` | a report id used twice |
| `REPORT_KEY_RESERVED` | the report key `schema` (needs the XSD: P5, P8; gate G3) |
| `REPORT_SCOPE_TABLES_EMPTY` | a report with no scope table (I5) |
| `REPORT_PATH_INVALID` | a listed path is not a valid pack path, or a scope table is outside `scope/` |
| `REPORT_LEGS_EMPTY` | a report with an empty leg vocabulary |
| `LEG_VOCABULARY_RESERVED` | a leg other than MARKET, CLIENT, ALLOCATION (BRK-12) |
| `LEG_DUPLICATE` | a leg listed twice |

### 6.4 Input model

| code | meaning |
|---|---|
| `INPUT_SHAPE` | the input model or a variable is not a mapping as required |
| `INPUT_KEY_UNKNOWN` | an unknown key in the input model |
| `INPUT_KEY_MISSING` | a required key (`variables`, `type`) is missing |
| `INPUT_PATH_INVALID` | a variable or table input is not a dotted path like `client.lei` |
| `INPUT_TYPE_UNKNOWN` | a type other than BOOL, INT, DECIMAL, STRING, TIMESTAMP, DATE, ENUM, LIST |
| `INPUT_ENUM_INVALID` | an ENUM variable without a valid `enum` name, or `enum` on another type |
| `INPUT_SOURCE_INVALID` | `source` is not EVENT or STATE |
| `INPUT_OPTIONAL_INVALID` | `optional` is not true or false |

### 6.5 Registries and registry checks

| code | meaning |
|---|---|
| `REGISTRY_FILE_MISSING` | `reasons.yaml`, `sources.yaml` or `rationales.yaml` is missing |
| `REGISTRY_SHAPE` | a registry part is not a mapping or list as required |
| `REGISTRY_KEY_UNKNOWN` | an unknown key in a registry |
| `REGISTRY_KEY_MISSING` | a required key is missing in a registry |
| `REGISTRY_KEY_MISMATCH` | a registry entry stored under a key that is not its own code or id |
| `CELL_BAD_CHARACTER` | a cell (registry or table) with a character the `PackText` policy refuses, or more than one line |
| `HASH_INVALID` | a SHA-256 value that is not 64 lower-case hex digits |
| `PREFIX_INVALID` | the reason prefix is malformed |
| `PREFIX_RESERVED` | the prefix is an engine-reserved reason code |
| `NODE_INVALID` | a node name is malformed |
| `NODE_DUPLICATE` | a node declared twice |
| `REASON_CODE_INVALID` | a `code` that is not a reason code |
| `REASON_DUPLICATE` | a reason declared twice |
| `REASON_RESERVED` | a reason code the engine reserves (`EXEC_ERROR`, `EXEC_BUDGET`, `EXEC_CIRCUIT_OPEN`, `ENRICH_MISS`) |
| `REASON_SHAPE_INVALID` | a code that is not `<PREFIX>.<node>.<label>[.<provision>]` |
| `REASON_PREFIX_MISMATCH` | a code whose prefix is not the declared prefix |
| `REASON_NODE_UNDECLARED` | a code whose node is not declared |
| `REASON_LABEL_MISMATCH` | a code whose third segment differs from the entry's `label` |
| `REASON_VALIDITY_INVERTED` | a reason validity whose end is not after its start |
| `LABEL_INVALID` | a `label` that is malformed |
| `CATEGORY_INVALID` | a `category` that is not an exact category name |
| `OUTCOME_INVALID` | an outcome (`default_outcome`) that is not an exact `Outcome` name |
| `OWNER_INVALID` | an `owner` that does not match the id shape |
| `SOURCE_DUPLICATE` | a source declared twice |
| `SOURCE_ID_INVALID` | a `source_id` that does not match the id shape |
| `VERSION_INVALID` | a source `version` that is malformed |
| `ELI_INVALID` | an `eli` that is malformed |
| `PROVISION_DUPLICATE` | a provision declared twice in a source |
| `PROVISION_PATH_INVALID` | a provision path outside `[A-Za-z0-9_./:-]` |
| `ROLE_INVALID` | a citation role that is not an exact role name |
| `STATUS_INVALID` | a provision status other than IN_FORCE, REPEALED, PROPOSED |
| `QUOTE_EMPTY` | a quote that is empty |
| `QUOTE_TOO_LONG` | a quote of more than 240 code points |
| `QUOTE_HASH_MISSING` | a quote without `quote_sha256`; the message carries the expected hash |
| `QUOTE_HASH_MISMATCH` | `quote_sha256` is not the SHA-256 of the quote |
| `RATIONALE_ID_INVALID` | a rationale id that is not `RAT-` plus 4 to 9 digits |
| `RATIONALE_TEXT_EMPTY` | a rationale with empty text |
| `RATIONALE_DUPLICATE` | a rationale declared twice |
| `TABLE_ID_DUPLICATE` | two tables with the same id |
| `ROW_REASON_UNKNOWN` | a row uses an undeclared reason |
| `REASON_OUTCOME_MISMATCH` | a row's outcome differs from the default outcome of its reason |
| `ROW_NODE_UNDECLARED` | a row's node is not declared in `reasons.yaml` |
| `ROW_RATIONALE_UNKNOWN` | a row's rationale is not in `rationales.yaml` |
| `CITATION_SOURCE_UNKNOWN` | a citation names a source that `sources.yaml` lacks |
| `CITATION_PROVISION_UNKNOWN` | a citation names a provision the source lacks |
| `PROVISION_NOT_IN_FORCE` | a citation names a provision that is not IN_FORCE |
| `CITATION_ROLE_MISMATCH` | a citation's role differs from the role of the provision |

### 6.6 Decision tables

| code | meaning |
|---|---|
| `TABLE_SHAPE` | a table part is not a mapping or list as required |
| `TABLE_KEY_UNKNOWN` | an unknown key in a table header or row |
| `TABLE_KEY_MISSING` | a required key is missing |
| `TABLE_ID_INVALID` | the table id does not match the id shape |
| `HIT_POLICY_INVALID` | `hit_policy` is not `FIRST` |
| `PRIORITY_MODE_INVALID` | the header `priority` is not `explicit` |
| `CATCH_ALL_INVALID` | the header `catch_all` does not match the id shape |
| `ROW_ID_INVALID` | a row id does not match the id shape |
| `ROW_NODE_INVALID` | a row node does not match `[A-Z][A-Z0-9]{0,7}` |
| `RULE_REF_INVALID` | a `rule_ref` does not match the id shape |
| `RATIONALE_INVALID` | a row `rationale` does not match the id shape |
| `PRIORITY_INVALID` | a priority that is not `[1-9][0-9]{0,8}` |
| `WHEN_EMPTY` | an empty `when` |
| `REASON_INVALID` | a row `reason` that is not a reason code |
| `CITE_ROLE_INVALID` | a citation role that is not an exact role name |
| `SET_INVALID` | a `set` name or value outside its charset |
| `LEGS_ON_NON_REPORT_ROW` | `legs` on a row whose outcome is not REPORT |
| `ROW_LEGS_EMPTY` | an empty `legs` list |
| `TABLE_EMPTY` | a table with no rows |
| `CATCH_ALL_MISSING` | no row has the declared catch-all id |
| `CATCH_ALL_DUPLICATE` | more than one row has the catch-all id |
| `CATCH_ALL_NOT_LAST` | the catch-all is not the last row |
| `CATCH_ALL_NOT_TRUE` | the catch-all's `when` is not `true` |
| `CATCH_ALL_NOT_UNDETERMINED` | the catch-all's outcome is not UNDETERMINED (I5) |
| `CATCH_ALL_HAS_VALIDITY` | the catch-all has a validity window |
| `ROW_NO_VALIDITY` | a row other than the catch-all has no legal validity window |
| `VALIDITY_INVERTED` | a row window whose end is not after its start |
| `ROW_NO_CITATION` | a row without a citation (I5) |
| `ROW_ID_DUPLICATE` | a row id used twice in a table or in the pack |
| `PRIORITY_DUPLICATE` | a priority used by two rows of a table |
| `WHEN_FORMULA_CHARACTER` | a `when` that starts with `= + - @` |
| `RULE_REF_DUPLICATE` | a `rule_ref` used twice in the pack |
| `ROW_LEG_NOT_IN_REPORT` | a row leg outside its report's vocabulary |

### 6.7 Loader

| code | meaning |
|---|---|
| `FILE_UNKNOWN` | a path that is not part of the pack format |
| `FILE_UNREFERENCED` | a scope table that no report lists |
| `PROPERTY_TESTS_RESERVED` | a file under `tests/properties/` |
| `INTERPRETATIONS_RESERVED` | `interpretations.yaml` with content (P2a-2) |
| `INPUT_SCHEMA_DIGEST_MISMATCH` | `input_schema` is not the digest of the input model; the message carries `expected <64 hex>` |
| `REPORT_FILE_MISSING` | a file listed by a report is not in the pack |
| `REPORT_INPUT_UNSUPPORTED` | a report's input is not `model/canonical-input.yaml` |
| `LIFECYCLE_SHAPE` | `lifecycle.yaml` is not a mapping |
| `LIFECYCLE_KEY_UNKNOWN` | a top-level key that is neither a lifecycle key nor a named clock (BRK-16) |
| `GOLDEN_JSON_INVALID` | a golden file that is not valid JSON of the expected shape |
| `IR_NOT_CANONICAL` | the loaded pack has no canonical IR |
| `LOAD_INCOMPLETE` | a required part is missing without a more specific problem |
| `PROBLEMS_TRUNCATED` | a cap on the number of problems was reached; the message is `N further problems not shown` (N counts the problems dropped before the reader stopped, so it is a lower bound). One marker ends the problems of a file (100 kept, `PackFormat.MAX_PROBLEMS_PER_FILE`), one ends a load (1,000 kept, `MAX_PROBLEMS_PER_LOAD`) and one ends what `pack test` or `pack table import` prints; markers sort after every other problem. The first problems kept are the first ones found, so an input below the cap reports exactly what it would without the cap |

### 6.8 Engine: compile, binding and `pack test`

| code | meaning |
|---|---|
| `REPORT_UNKNOWN` | the pack has no report with the requested id |
| `REPORT_TABLE_MISSING` | a report lists a table the pack lacks |
| `WHEN_DOES_NOT_COMPILE` | a `when` that does not compile (syntax, undeclared path, over the parse cap) |
| `RULE_OVER_CEILING` | an expression with more nodes than `rule_nodes` |
| `TABLE_INPUT_UNDECLARED` | the table header lists an input the model does not declare |
| `INPUT_UNDECLARED` | a rule reads a path that the table header omits |
| `INPUT_UNUSED` | the table header lists an input that no rule reads |
| `INPUT_PATH_UNSUPPORTED` | the engine build does not bind the path (or it is `source: STATE`) |
| `INPUT_TYPE_MISMATCH` | the declared type or enumeration differs from the engine's binding |
| `ENGINE_MIN_NOT_SATISFIED` | the running build does not satisfy `engine_min` |
| `MULTI_REPORT_NOT_SUPPORTED_BY_PACK_TEST` | `pack test` runs the golden cases of a pack with exactly one report |
| `GOLDEN_NODE_UNDECLARED` | a golden case names a node `reasons.yaml` does not declare |
| `GOLDEN_INPUT_INVALID` | a golden input is not a canonical execution |
| `GOLDEN_CASE_FAILED` | the decision differs from the expected outcome, reason, node or rule hits |
| `GOLDEN_AUDIT` | a provenance audit failure; the message is one token of section 6.9 |
| `COVERAGE_NO_CASES` | the pack has no golden case |
| `COVERAGE_ROW_NOT_HIT` | a row is matched by no passing case (a catch-all passed through to a later table counts as matched) |
| `COVERAGE_REASON_NOT_REACHED` | a declared reason is reached by no passing case |
| `COVERAGE_PROVISION_NOT_EXERCISED` | a cited provision is exercised by no passing case |
| `TABLE_PATH_INVALID` | a `pack table` path is not a valid pack path or not a report's scope table (exit 2) |
| `OUT_INSIDE_PACK` | `pack table export --out` names a file inside the pack directory (exit 2; nothing is written) |
| `TABLE_NOT_WRITABLE` | an imported table cannot be written faithfully as canonical YAML |

### 6.9 Golden cases and audit

| code | meaning |
|---|---|
| `GOLDEN_NO_PROVENANCE` | a golden case without a `provenance` object (D-P6) |
| `GOLDEN_CONSISTENCY_WITH_FIELDS` | `CONSISTENCY_ONLY` that carries `source`, `reviewer` or `reviewedOn` |
| `GOLDEN_NO_SOURCE` | a real provenance kind without a source |
| `GOLDEN_NO_REVIEWER` | a real provenance kind without a reviewer |
| `GOLDEN_NO_REVIEW_DATE` | a real provenance kind without a review date |
| `GOLDEN_ID_FILE_MISMATCH` | the file is not `tests/golden/<id>.json` |
| `GOLDEN_ID_DUPLICATE` | an id used by an earlier file |
| `TEXT_BAD_CHARACTER` | a decoded golden string with a character `PackText` refuses |
| `AUTHORSHIP_MISSING_FILE` | a pack with scope tables has no `tests/authorship.yaml` |
| `AUTHORSHIP_SHAPE` | `authorship.yaml` is not `rules:` with one non-blank author per rule |
| `AUTHOR_MISSING` | a row id has no author |
| `AUTHOR_UNKNOWN_RULE` | an author is named for an id that is no row |
| `NO_SOURCE` | audit token: the case names no source |
| `NO_REVIEWER` | audit token: the case names no reviewer |
| `REVIEWER_NOT_REGISTERED` | audit token: the reviewer is not in `governance/golden-reviewers.txt` |
| `NO_REVIEW_DATE` | audit token: the case has no review date |
| `REVIEWER_IS_AUTHOR` | audit token: reviewer and case author are the same person |

### 6.10 CSV

| code | meaning |
|---|---|
| `CSV_COLUMNS` | the CSV has no header, or its columns are not exactly the expected set |
| `CSV_QUOTE` | a quote that is not closed, or a stray quote |
| `CSV_CELL_COUNT` | a row whose cell count differs from the header, a blank line included |

### 6.11 Decision reason codes of the engine

These are reasons on UNDETERMINED decisions, not pack problems: `EXEC_ERROR` (an evaluation error), `EXEC_BUDGET` (the per-record budget ran out) and `EXEC_CIRCUIT_OPEN` (the rule's poison circuit is open). A pack may not declare them or `ENRICH_MISS`.

## 7. Hand-offs

- **P3:** the decision schema needs `attributes` (JSON, nullable) and the stored `engine_build` text; the leg type is closed until BRK-12 is decided; `lifecycle.yaml` content is reserved. BRK-04 and BRK-10 are ledger contracts that block the schema freeze: when a pack declares a status model, `state.status` is part of `input_digest` and `decision_context` (P2a reads it only through `Bindings` and adds no field); `attributes{}` is `DecisionBody.attributes`. The `unsigned` stamp of spec 6.5 step 6 is not produced by P2a. Every decision with reason `EXEC_CIRCUIT_OPEN` (flag `circuit=open`) must be enumerable per rule and pack digest, raise a data-quality item (spec 7.3) and be re-evaluated by a superseding decision when the circuit closes; until then they are open UNDETERMINED items for I7. They are to be counted and excluded from the determinism sample (no determinism-sample class exists at P2a).
- **P5:** library version mechanism, identifier functions, `ENRICH_MISS`, the `validate/` row shape, report schemas (G3).
- **P6a:** the sample is not a start for MiFIR content; the chain rule (OI-3) and the `legs` key (OI-2) need the owner's confirmation; non-consistency-only goldens need G1.
- **P8:** BRK-06, BRK-09, BRK-15.
- **P9b:** the digest definition, the ATTESTATION slot, the IR as input to structural semver, archived builds named by `EngineBuild`, the Tier-2 subprocess. `PackRegime.create` does not check signatures; activation must refuse an unsigned pack outside SIMULATOR and CONFORMANCE (spec 6.5 step 6, 9.7). CHECKSUMS is an integrity check, not authenticity.
- **P12:** the second-regime fixture, `Leg.SIDE`, event kinds, BRK-03, BRK-05, BRK-17 and BRK-18.
- **P10b and P11:** the CSV format and the canonical table YAML.
- **P2a-2:** Firm Profile register, overlay registry, interpretation pins and derive tables (plan, section "Deferral").

## 8. Pins and provisional shapes

All provisional (G-S3b). The pin values are kept in `docs/pack-format/decisions.md` (section "Pins created"); the names and the shapes below are what the code enforces.

| Task | Pin or shape |
|---|---|
| T6 | SemVer: no leading zeros, at most 9 ASCII digits per part. `jurisdiction` `[A-Z][A-Z0-9]{1,7}`. `as_at_field` shape `[a-z][A-Za-z0-9_]*` joined by dots. `supersedes` shape `[A-Za-z0-9][A-Za-z0-9._+-]{0,127}`. Paths at most 256 characters and 16 levels. A report needs at least one scope table. |
| T7 | Table codes beyond the plan: see section 6.6. |
| T8 | Reason `label` is the third dot-segment of the code and must equal it (the owner may have meant a human-readable label). Rationale id `RAT-` plus 4 to 9 digits. A hash-only provision (a `quote_sha256` without a `quote`) is accepted and cannot be verified by CI; a quote without a hash is `QUOTE_HASH_MISSING`. A row's `node` need not equal the node segment of its reason code. |
| T9 | Opaque directories accept `*.yaml` at any depth; `scope/*.yaml` and `tests/golden/*.json` direct children only. IR choices: reports a map by id; leg vocabulary and table inputs sorted sets. |
| T11 | Decimal literal limit (section 3); static worst-case pessimism (section 3). |
| T12 | Evaluator stack of at least 256 KB (section 3); list-literal and ternary bound (section 3). |
| T13 | Library version 1 choices (section 3). |
| T14 | `rulings.tsv` keying (section 5.5); build `1.0.0+lib1`; counts MUST 12, SHOULD 19, DECIDE 5, SPIKE-DESIGN 10 and 17 cost fixtures; executor id `metered-reference-v0`. |
| T17 | Circuit defaults, window semantics and the flag `circuit=open` (section 4). |
| T21 | CSV staging, blank-line behaviour and the missing import size cap (section 5.7). |

## 9. Open questions for the owner at the freeze

None blocks work; each has the default stated above.

1. The meaning of reason `label` (T8): the third dot-segment of the code, or a human-readable label.
2. The depth of the opaque directories (T9): any depth under `derive/`, `route/`, `enrich/`, `validate/`, `endpoints/`, direct children only for `scope/` and `tests/golden/`.
3. The report map and sorted-set choices of the IR (T9).
4. Whether an empty `scope_tables` list should be accepted (T6): the SP-G EMIR spike pack had an empty list.
5. Hash-only provisions (T8): accepted, unverifiable by CI.
6. The library version 1 behaviours of T13 (section 3).
7. The five DECIDE rulings (OI-5, T14): D13, A04, E06, A07, S07 stay PROVISIONAL; the owner rules at the freeze by editing one line of `rulings.tsv` each.
8. The CSV import has no size or row cap in `TableCsv.importRows` (T21): the number of rows is still not capped (a row cap is an owner decision); since the final review the number of problems it reports is capped at 100 per file by the new code `PROBLEMS_TRUNCATED`.
9. The sample pack's `regpack-spec` source hash is the hash of an invented label, not of the specification file (T10).
10. The 15 SP-G wordings (open item D-4): applying them to the specification text, or accepting these notes in their place; and whether to add a P2a-2 row to the spec 16 table (OI-9).
11. The rule id `as_at_field` is used for an as-at read failure and is not reserved, so a pack row with that id would collide (T16, T17).
12. Whether the node of a golden case is informational rather than compared with the decision's node (T20).
