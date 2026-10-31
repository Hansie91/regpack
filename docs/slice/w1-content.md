# W1a content sheet: the demo pack, the data set and the scenarios

**NON-PRODUCTION. SYNTHETIC DATA ONLY.** This sheet is for the Compliance owner (the owner of the platform) to read before the demo pack is built (plan T10). It states the content that the W1a slice (a CSV file in, a report out, an answer back, on a destination simulator, in one process) is built and tested on. Source: the section "Content of the demo pack, the data set and the scenarios" of the [W1a plan](../superpowers/plans/2026-10-31-w1-thin-slice.md). Nothing here is regulator-published content and nothing here is a legal reading.

**How to read it.** Every legal or regulatory statement is marked **[R]**: a recollection of the planner, not a fact, and not checked against any text (nothing is fetched; gate G3 is not cleared). The demo pack is a DEMO pack `demo.tr.s1` in the shape of the transaction-reporting path; its golden files stay `CONSISTENCY_ONLY` for good (their source is synthetic) and never count as evidence for traceability rows R9 or I5. The real pack comes after gate G1 is recorded (plan W1b, part iii). Citations in the pack point only to `sample-law`, `regpack-spec` and `fixture-law`, with `SYNTHETIC` quotes.

## 1. Scope table

`scope/scope.yaml`, table `demo.tr.s1.scope`, mode FIRST with an explicit priority; reason-code prefix `DEMO`; nodes `N03 N08 N11 N13 N18 DEFAULT`.

| Rule id | Priority | When | Outcome | Reason code |
|---|---|---|---|---|
| S-N03-novation | 300 | `event.kind in ['NOVATION','ASSIGNMENT']` | DO_NOT_REPORT | `DEMO.N03.EXCLUDED` |
| S-N13-natural-person | 400 | `client.kind == 'NATURAL_PERSON'` | UNDETERMINED | `DEMO.N13.NATURAL_PERSON` |
| S-N13-client-id-missing | 450 | `client.kind == 'LEGAL_ENTITY' && !has(client.lei)` | UNDETERMINED | `DEMO.N13.CLIENT_ID_MISSING` |
| S-N18-amend | 500 | `event.kind in ['AMEND','CORRECTION']` | UNDETERMINED | `DEMO.N18.AMEND_NOT_IN_SLICE` |
| S-N11-capacity | 700 | `!has(trade.capacity) \|\| !(trade.capacity in ['AOTC','MTCH'])` | UNDETERMINED | `DEMO.N11.CAPACITY_ABSENT_OR_NOT_IN_SLICE` |
| S-N08-new | 800 | `instrument.assetClass == 'EQUITY' && instrument.tradedOnVenue && event.kind == 'NEW'` | REPORT, legs `[MARKET]`, `set: {action: NEWT}` | `DEMO.N08.IN_SCOPE` |
| S-N08-cancel | 810 | the same with `'CANCEL'`, `set: {action: CANC}` | REPORT, legs `[MARKET]` | `DEMO.N08.IN_SCOPE` |
| S-DEFAULT-catch-all | 9999 | (always) | UNDETERMINED | `DEMO.DEFAULT.NO_RULE_HIT` (a debt instrument reaches it) |

DEAL is a capacity of the universe of decision D14 that W1a does not build (OI-7), so it gives UNDETERMINED through S-N11. A CANCEL row repeats the fields of its trade.

## 2. Derive table

`derive/report.yaml`; each row has `{id, field, when, value, rule_ref, cite}`; `when` defaults to true.

| Field | When | Value |
|---|---|---|
| `executing_lei` | always | `firm.lei` |
| `submitting_lei` | always | `firm.lei` |
| `buyer` | `event.kind == 'NEW'` | `trade.side == 'BUY' ? (trade.capacity == 'AOTC' ? client.lei : firm.lei) : trade.counterparty` |
| `seller` | `event.kind == 'NEW'` | the mirror for `'SELL'` |
| `trading_datetime` | `event.kind == 'NEW'` | `isoMicros(event.tradedAt)` |
| `capacity` | `event.kind == 'NEW'` | `trade.capacity` |
| `quantity` | `event.kind == 'NEW'` | `string(round(trade.quantity, 4, 'HALF_EVEN'))` |
| `price` | `event.kind == 'NEW'` | `string(round(trade.price, 6, 'HALF_EVEN'))` |
| `price_currency` | `event.kind == 'NEW'` | `trade.currency` |
| `venue` | `event.kind == 'NEW'` | `venue.mic` |
| `instrument` | `event.kind == 'NEW'` | `instrument.isin` |
| `execution_within_firm` | `event.kind == 'NEW' && has(trade.executionWithin)` | `trade.executionWithin` |
| `transmission` | `event.kind == 'NEW'` | `'false'` |

A cancel carries the TRN, the executing entity and the submitting entity only; the TRN is added by the report builder.

## 3. Validation rules

`validate/rules.yaml`: 15 rules, BLOCKER and QUARANTINE unless stated; `regulator_id: null` (the map to regulator codes is gate G3 work, not built).

| Layer | Rule | What it checks |
|---|---|---|
| L1 | V01 | quantity > 0, at most 12 integer and 4 fraction digits (DEMO limit) |
| L1 | V02 | price > 0, at most 12 integer and 6 fraction digits (DEMO limit) |
| L1 | V03 | currency shape **[R]** |
| L2 | V04 | firm LEI shape and the MOD 97-10 check |
| L2 | V05 | client LEI and counterparty LEI (each when present), the same |
| L2 | V06 | ISIN shape and the Luhn check |
| L2 | V07 | MIC shape **[R]** |
| L2 | V08 | currency not one of the DEMO list XAU XDR XXX **[R]: the codes are recalled** |
| L3 | V09 | executing LEI status ACTIVE at the trade date |
| L3 | V10 | client LEI and counterparty LEI (each when present) ACTIVE or LAPSED at the trade date; LAPSED is WARNING `LEI_LAPSED`, and the rule passes (the status words mix two registry vocabularies **[R]**) |
| L3 | V11 | (ISIN, MIC) listed at the trade date; a miss is WARNING `REFDATA_LAG_EXPECTED` when the trade date is not before the snapshot date, else `REFDATA_UNKNOWN`; the rule passes and the record is flagged |
| L3 | V12 | MIC known |
| L4 | V13 | trading time not after reception |
| L4 | V14 | `tradedAt` is not before `receipt.atZone(UTC).minusYears(3)` (DEMO limit) |
| L5 | V15 | chain fit (check `CHAIN_FIT`): a CANCEL needs a subject whose latest live version has action NEWT (code `CANCEL_WITHOUT_LIVE_NEWT`); a NEW needs a subject with no live version or whose latest live version is a CANC (code `NEWT_OVER_LIVE_NEWT`) |

Notes. An unchanged re-export with a higher version is quarantined, not suppressed: a known limit (W1b). "Live" means the delivery is not REJECTED, IN_REPAIR, WITHDRAWN or WRITTEN_OFF. V15 is judged against committed state only: a cancel in the same batch as its NEW is quarantined, never lost. Every limit and list in `validate/rules.yaml` is a DEMO value of the planner, not a figure of any regulator artefact; V09, V10 and V13 are generic plausibility rules, not workbook rules (G3). The closed list of check kinds: `DECIMAL_RANGE`, `LEI`, `ISIN`, `MIC_SHAPE`, `CURRENCY_SHAPE`, `CURRENCY_NOT_IN`, `REF_STATUS`, `REF_LISTED`, `REF_KNOWN`, `TIME_NOT_AFTER_RECEIPT`, `TIME_NOT_OLDER`, `CHAIN_FIT`.

## 4. Enrichment plan and lifecycle

`enrich/plan.yaml`: one field, `client.lei`, needed by SCOPE, one source `static-data` keyed by `account`, `max_staleness: P30D`, `on_miss: UNDETERMINED`; it applies when `client.lei` is absent and the client is a legal entity. `lifecycle.yaml`: `business_key: "executing_lei|trn"`, `trn: {policy: derive, length: 32}`, `successor_gate` as in the destination profile (the profile and the projection are specified in plan T10).

## 5. Sample files

All files are `SYNTHETIC`; each CSV has a `<name>.csv.done` marker (one line of JSON with sorted keys: `data_class`, `file`, `records`, `sha256`, `size`, `sum_quantity`). Columns: `record_id,version,event,exec_id,venue_mic,traded_at,isin,on_venue,account,client_lei,client_type,side,capacity,quantity,price,currency,counterparty,exec_within`. `event` is NEW, CANCEL or NOVATION. The firm LEI is a mapping constant, not a column. Every trade is at `2026-10-30T09:30:00.123456Z` with the central counterparty as `counterparty` and `ALGO-1` as `exec_within`; the reference snapshot date is 2026-10-31. The files are in `regpack-flow/src/main/resources/dev/regpack/flow/samples/`.

| File | Rows | Expected outcome |
|---|---|---|
| `trades-day1.csv` | T-001 NEW EX-001 XPAR ZZ0000000016, client 1, BUY AOTC 100 at 12.25 EUR; T-002 EX-002 XPAR ZZ0000000024, no client LEI, account ACC-2, SELL MTCH 250.5 at 8.1; T-003 EX-003 XAMS ZZ0000000032, client 1, BUY AOTC 40 at 99.5; T-004 EX-004 XPAR ZZ0000000017 (invalid ISIN); T-005 EX-005 XPAR ZZ0000000065 (not in the instrument seed), client 2; T-006 EX-006 NATURAL_PERSON, account ACC-9; T-007 NOVATION EX-007; T-008 EX-008 XPAR ZZ0000000040, lapsed client | T-001, T-002 (client LEI from static data), T-005 (flag `REFDATA_UNKNOWN`) and T-008 (flag `LEI_LAPSED`): NEWT sent, ACPT. T-003: sent, RJCT `DEMO-R001` at `/Doc/Tx/New/Price`, then IN_REPAIR. T-004: V06, a DQ item, no version. T-006: UNDETERMINED `DEMO.N13.NATURAL_PERSON`, a DQ item. T-007: DO_NOT_REPORT |
| `trades-day2.csv` | T-001 version 2 CANCEL EX-001; T-003 version 2 NEW EX-003 at price 99.55; T-009 version 1 with quantity `abc`; T-010 NEW EX-010 quantity 77 (the simulator stays silent for quantity 77) | T-001: CANC sent after the NEWT is accepted, ACPT. T-003: version 2 NEWT (original action) sent, ACPT; the version 1 delivery stays IN_REPAIR, listed as superseded by version 2. T-009: QUARANTINE_RECORD. T-010: sent, no answer, past the horizon IN_DOUBT, never resent |
| `trades-day1.csv` again | same bytes | FILE_REDELIVERED, no new records |
| `trades-day3-badtotals.csv` | the marker says 3 records, the file has 2 | QUARANTINE_BATCH |
| `trades-day4-broken.csv` | the header lacks `price` | REJECT_FILE, landed, moved to `rejected/` |
| `trades-day5-notsynth.csv` | the marker has no `data_class` | REJECT_FILE `NOT_SYNTHETIC`, nothing landed |

**Remaining cells.** Unless a row says otherwise: `currency` EUR, `on_venue` true, `client_type` LEGAL_ENTITY, ISIN ZZ0000000016 on XPAR, BUY, AOTC, and the `client_lei` and `account` of client 1 (`ACC-1`; the rows name client 2 `ACC-2` or the lapsed client `ACC-3` where they differ). Quantity and price: T-004 10 and 5.5; T-005 20 and 6.5; T-006 30 and 7.5 (NATURAL_PERSON, ACC-9, no client LEI); T-007 40 and 8.5; T-008 50 and 9.5. Day 2: the CANCEL repeats T-001 (100 and 12.25); T-003 version 2 has quantity 40; T-009 and T-010 have price 10.5 and 11.5, T-009 quantity `abc`. No other row has price 99.5 or quantity 77, so only T-003 version 1 is rejected and only T-010 stays silent. Days 3 to 5 have two rows each, with the cells of T-001 but quantities 10 and 20 and ids of their own (T-011 and T-012, T-013 and T-014, T-015 and T-016; exec ids EX-011 to EX-016), so that the three files differ in bytes and in batch id; the day 4 header lacks `price`.

**Control totals.** The marker's `records` is the number of data rows and `sum_quantity` the sum of the quantities that parse: day 1 `540.5` over 8 records, day 2 `217` over 4 (the row with `abc` adds nothing). Day 3 states 3 records for 2 rows. `sum_quantity` is compared as a decimal number, so `540.5` and `540.5000` agree.

**Reference seeds** (same directory; the dataset name is the file stem; an empty `valid_to` is open): `lei-status.csv` (header `key,status,valid_from,valid_to`; the firm, the central counterparty and clients 1 and 2 ACTIVE from 2020-01-01; the lapsed client LAPSED from 2025-01-01), `mic.csv` (XPAR and XAMS ACTIVE from 2020-01-01), `isin-mic.csv` (key `<ISIN>|<MIC>`: `ZZ0000000016|XPAR`, `ZZ0000000024|XPAR`, `ZZ0000000032|XAMS`, `ZZ0000000040|XPAR`, status LISTED from 2020-01-01; `ZZ0000000065` is absent on purpose), and `static-data.csv` (header `key,lei`: `ACC-1` client 1, `ACC-2` client 2, `ACC-3` the lapsed client). The identifiers are the synthetic ones of known-answer vector V3; the plan does not claim that they are unassigned **[R]**, and the safety rule is the closed-world seeds together with the sample-file test that names no other identifier.

## 6. Review checklist for the Compliance owner

Tick nothing on behalf of the owner; each line is a question the owner answers, and the answers go to `docs/evidence/w1-decisions.md`.

- [ ] Every legal statement above is **[R]**: a recollection to be replaced or confirmed. The demo pack is a stand-in and is replaced by the owner in W1b, after gate G1.
- [ ] OI-6: lifecycle in the slice is NEWT and CANC only; AMEND and CORRECTION give UNDETERMINED. Is that the wanted default?
- [ ] OI-7: capacity AOTC and MTCH on one leg only; DEAL gives UNDETERMINED. Is that the wanted default?
- [ ] OI-9: `refdata_wait` is NONE (a miss does not wait for reference data). Is that the wanted default?
- [ ] Regulator-published values (code lists, rule texts, schema facets) enter only after gate G3 (W1b, part ii); none is in this sheet or in the pack.
- [ ] Against the specification, section 17.3, the planner's `[R]` statements are: `as_at_field` is the trade date (item 1); V09 and V10 take a lapsed LEI by role (item 9); `successor_gate` and a CANC that waits for its NEWT (items 11 and 16); V11, `refdata_wait` NONE and the horizons PT10M and PT30M are sandbox values, not the T+1 and R+1 norms (item 13); late trades are not handled (item 16).
- [ ] Gate G2 stays open: the owner recording gate G1 is not the subject-matter confirmation that section 17.3 asks for.
- [ ] Gate G3 stays pending: nothing is fetched and no schema- or workbook-derived value is held.
