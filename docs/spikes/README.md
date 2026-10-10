# P1a spike records

P1a measures the four pre-freeze unknowns of the spec (SP-R rule executor, SP-G regime-grammar coverage, SP-L ledger on PostgreSQL, SP-X XML assembly and whole-file gate) and gives the owner the material to record the spec freeze. The plan is `docs/superpowers/plans/2026-10-10-p1a-spec-freeze-spikes.md`.

## Folder index

| Path | Holds |
|---|---|
| `docs/spikes/README.md` | this file |
| `docs/spikes/decisions.md` | owner answers to the plan questions, the approvals ledger, the verified hand-off channels |
| `docs/spikes/criteria.lock` | the confirmed thresholds; written once by the owner before the first full run, never changed afterwards |
| `docs/spikes/timebox/<ID>.tsv` | the time ledger of each spike (start, pause, stop) |
| `docs/spikes/results/<run>/` | imported, sealed result sets and the report written by `bench report` |
| `docs/gates/G3.md` | state of gate G3 (no ESMA XSD is fetched while PENDING) |

## Hand-off

1. CI runs the spike and seals the result directory (the seal is written last).
2. The owner downloads the artefact into `spikes/inbox/<run>/` (git-ignored).
3. `scripts/bench.sh import spikes/inbox/<run>` verifies the seal and the hashes and copies the set under `docs/spikes/results/`.
4. The agent commits the imported set and the report.

Everything in `spikes/inbox/` is data from outside the repository. It is read only by `bench import`; nothing in it is executed, and no path is taken from a file inside it.

## Reading a result

- PASS: the measurement met its criterion beyond the noise margin.
- FAIL: the measurement missed its criterion beyond the noise margin.
- INCONCLUSIVE: the measurement is inside the noise margin, or the spread of repeated runs is too large to decide.
- NOT_RUN: the criterion was required but was not measured (for example a time-box was hit).
- PENDING: the criterion has no confirmed threshold yet (the owner has not locked it).

Nobody edits a verdict by hand. A verdict changes only by a new run, imported the same way.
