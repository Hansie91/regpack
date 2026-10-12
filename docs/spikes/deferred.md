# Deferred spikes

None of these ran in P1a. The P1b boxes are the owner's answer of 2026-10-09 and are re-confirmed when P1b is written. The entry wiring is `docs/spikes/entry-wiring.tsv`, read by `Gates entry --target <target>`.

| Spike | Box | Question | Entry wiring or trigger |
|---|---|---|---|
| SP-M (P1b) | 1 day | 10M-record soak, bounded memory per role, worst GC pause (lease TTL, 7.8), 100M-row JDBC extraction; the memory number AT-1 uses | `Gates entry --target P7a-microbatch` (SP-L, SP-M, SP-X); AT-1 |
| SP-S (P1b) | 4 h | Java SFTP library: no-clobber rename, resumable upload, host-key pinning, 1 GB file | `P8` (SP-S, SP-E, SP-X, G3); G6 per destination stays |
| SP-W (P1b) | 1 day | Sealed-file evidence profile (tamper-evidence, Windows semantics, external signing, second copy); closes the evidence-PUT interval | SP-W PASS before a sealed adapter is used outside tests (wired by that plan); feeds G8 |
| SP-N (P1b) | 4 h on the provider simulator | Enrichment latency, rate limits, breaker, storage mode (closes the enrichment interval) | SP-N PASS and G5 before a real provider (wired by that plan) |
| SP-E | 1 h (spec) | Open the ESMA EMIR zips: XSD 1.0 only? Rechecks SP-X's synthetic shape | **Gated by G3**: `Gates entry --target SP-E` needs `docs/gates/G3.md` `CLEARED`; also entry of `P8` and `P5-xsd-or-derived-content` |
| SP-O | 1 day per store | Object-lock semantics of the S3-compatible store | Trigger: the firm names an object-lock store; cited in G8 |
| SP-V | n/a | Annex II country formats against EUR-Lex | Trigger: natural persons enter scope (17.3 item 8) |
| SP-D | n/a | DRR report functions and unimplemented stubs | Trigger: DRR execution pursued; G4 first |
