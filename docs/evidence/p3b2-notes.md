# P3b2 evidence notes: the P-WORM store as built, the gates, the limits and the hand-offs

Written in task T12 of the [P3b2 plan](../superpowers/plans/2026-10-23-p3b2-worm-s3.md) on branch `p3b2/worm-s3` at `a7bc619`. The code is that of `6b21151`, the head that CP-2 (run 40141048211) ran green; `git diff --stat 6b21151 a7bc619` shows one changed file, `docs/evidence/p3b2-decisions.md`. The file is written before the final review (T13): a code change made there makes the statements about that code stale, and the pass that makes it corrects this file. It changes no code and no specification text. The approvals, owner inputs, pins, deviations and checkpoint records are in [p3b2-decisions.md](p3b2-decisions.md); the probe read-out is [p3b2-probe.md](p3b2-probe.md); the P-SEALED half of the evidence tier is described in [p3b-notes.md](p3b-notes.md).

**How the statements are marked.** *Probe result Pn* is an observation of the probe item Pn in CP-1 (run 40123391011, head `ab7dab2`, job `evidence-worm`, `S3ServiceProbeIT` run 1, failed 0). The service of that run is Scality CloudServer 9.5.0 (`ghcr.io/scality/cloudserver:9.5.0@sha256:652d50d0...a0727`, `S3BACKEND=file`), not AWS S3; one run is an observation and not a proof of a rule, and the probe was not run again at CP-2 (job version 2 selects the `Worm*IT` and `PgWorm*IT` classes). *Recollection* is a statement about S3 or a server product that no probe item or run has checked; the `[R]` of the plan means the same and is not repeated as a fact anywhere below. *Measured* is a count taken on the fake service (`FakeS3Server`), which is test code written from the probe results and is not evidence of the service. A sentence without one of these marks is read from the code at `6b21151` and pinned by the test it names. Messages and exception texts of the module carry the operation, the status and the S3 code and nothing else (`S3ClientTest#errorMessagesCarryOnlyStatusAndCode`, `WormEvidenceStoreTest#noMessageContainsTheSecretOrTheHost`).

## 1. The P-WORM store as built

### 1.1 Module and dependencies

- `regpack-evidence-s3` (package `dev.regpack.evidence.s3`; the client in `dev.regpack.evidence.s3.client`) has two compile dependencies, `regpack-model` and `regpack-ports`, and no third-party dependency (AP-5; `S3ModuleBoundaryTest`). `regpack-testkit`, `regpack-inmemory`, `regpack-tools` and `regpack-evidence` are test scope. `regpack-app` and `regpack-evidence` do not name the module (`S3ModuleBoundaryTest`), so the distribution does not reach the store; `regpack-postgres` has it as a test-scope dependency (the `PgWorm*IT` classes).
- The client is hand-written: `SigV4` (five vectors pinned in `SigV4Test`), `S3Client` (path-style requests; 2xx returned, anything else an `S3Exception`), `S3Transport` and `JdkTransport` (a fresh JDK `HttpClient` per request, HTTP/1.1, redirects not followed, one deadline for the whole exchange, no retries), `S3Xml` (a DOCTYPE is refused, `S3XmlTest#aDoctypeIsRefused`). `NoBypassTest` holds that the main sources neither name the governance-bypass header nor issue a DELETE request.
- `EvidenceStoreContract` has 31 methods (the 28 of P3b1 and the three added in T5) and `JournalContract` 11; `WormEvidenceStoreContractIT` ran 42 tests (31 + 11) at CP-2.

### 1.2 The mappings (D-W2 to D-W9)

| Concept | How P-WORM stores or reads it |
|---|---|
| Key | the S3 object `/{bucket}/{key}` (path-style); each byte of the key outside `A-Za-z0-9-._~` and `/` is written `%XX` once (`SigV4.encodePath`, `S3ClientTest#theCanonicalPathEncodesPlusEqualsAndComma`). Probe result P9: keys with `+ = , $` and a 512-character key were stored, read and listed back unchanged. |
| Bytes and content type | the object body; content type `application/octet-stream` (`WormEvidenceStoreTest#aNewObjectCarriesTheLockHeadersAndTheMetadataOfTheModel`). |
| Metadata (user metadata, lower-case names, prefix `x-amz-meta-`) | `regpack-sha256` (hex of the bytes), `regpack-written-at` (ISO instant of the data clock), `regpack-retain-until` (ISO instant, the exact value of the put), `regpack-class` and `regpack-media-type` (UTF-8, base64url without padding). Probe result P3: 1,676 bytes of user metadata in five names (the worst case of A10) were accepted and came back on HEAD and GET. The 2 KB limit that A10 computes against is recollection. |
| Which version is the object | invariant V: the oldest data version of the exact key. The version list of the key (`?versions&prefix=<key>`, entries of other keys ignored) is read to its end and the last data entry in list order is taken; Last-Modified is not consulted. Probe result P7: the list is newest first. |
| Lock | `x-amz-object-lock-mode` from the configuration (`GOVERNANCE`) and `x-amz-object-lock-retain-until-date`: the retain-until rounded up to whole seconds, at most `maxLockDate` (default `9999-12-31T23:59:59Z`). Probe result P2: the headers were accepted and HEAD returned the date text as sent. Probe result P12: whole seconds, six fractional digits and year 9999 were accepted and a past date was refused (400 `InvalidArgument`); this service kept the sub-second digits it was given, so the rounding up is harmless on it and is kept for a store that keeps whole seconds (recollection). |
| Retention extension | the lock first (`PutObjectRetention`, to a later date than the version has, and a shortening is not written: `WormRetentionTest#theLockIsNeverShortenedByAnExtend`), then the tag `regpack-retain-until` with the exact value, because metadata cannot change and tags can. Probe result P4: tags were written on a locked version and came back verbatim with a tagging count. Probe result P5: extending worked with and without Content-MD5; shortening a governance lock was refused (403 `AccessDenied`) without the bypass header and accepted with it. The main sources do not name the header (`NoBypassTest#noMainSourceNamesTheBypassHeader`). |
| Retention as reported | the exact value (tag, else metadata) when its rounded form equals the lock date; the lock date when the lock is longer (an extension that lost its tag); `EvidenceCorruptionException` when the lock is shorter than the exact value rounded up, absent, or the tag is no instant or lies below the metadata. |
| Legal hold | `PutObjectLegalHold` on the oldest data version, under the stripe lock; the header on GET is read as off when absent. Probe result P6: the hold was set and cleared, the header was absent before any hold and HEAD and GET carried it afterwards. A key that has not been put is `IllegalArgumentException("no such evidence key: ...")` after the outage check (M28, M33). |
| Delete marker | a marker as the newest entry over a data version makes `get`, `put` and the retention methods throw `EvidenceCorruptionException`; `list` still lists the key. Probe result P7: a DELETE without a version id added a marker (204) even under lock, GET and HEAD then answered 404 with the delete-marker header, and a DELETE of a locked version by id was refused (403). |
| Durability and grade | `durability()` reads the versioning status and the object-lock configuration when it is called (the code has no cache), throws `EvidenceUnavailableException` unless both are on, and returns the `Durability` that `WormConfig` declares. `grade()` makes the versioning request and returns `("P-WORM", "governance (unverified)")` for `UNVERIFIED_NON_AWS`. Probe result P1: a bucket created with the lock header showed versioning and object lock on. Probe result P15: the API showed no replication and no storage class, so the declaration is the operator's. |
| Failures | a transport failure, a timeout, an interrupt (flag restored), any status the call does not expect (3xx included, not followed), an unparsable answer, a missing version id, a listing that does not end or does not advance: `EvidenceUnavailableException` with operation, status and Code. The one absence is a version list of the exact key, read to its end, that holds no data version. Probe result P14: an error body is `<Error><Code>...</Code><Message>...`; the message is not copied. |
| Clocks | `open(config, dataClock, signingClock[, transport])`: the data clock gives `regpack-written-at` and the retain-until check; the signing clock gives `X-Amz-Date` and has to be real time (recollection: a service refuses a request that is far off its own clock). |

`WormConfig` refuses before any request: an endpoint that is not `scheme://host[:port]`; plain `http` for a host other than a literal 127.0.0.0/8 address, `[::1]` or `localhost` (read from the URI, no name lookup); `AWS_S3` with a host outside `amazonaws.com` and `amazonaws.com.cn`; `COMPLIANCE` with `UNVERIFIED_NON_AWS`; a declaration that is not synchronous or has no failure domain; more than one declared domain for `UNVERIFIED_NON_AWS`; a bucket name that breaks the S3 rules (`WormConfigTest`, 19 methods).

### 1.3 The put algorithm and the race that remains (D-W3)

Inside the stripe lock of the key (64 locks held by the store object; a second store object on the same bucket models a second process):

1. Validate with no request: the retain-until has at most microsecond precision and a year from 0000 to 9999, the media type has at most 255 characters, the class name at most 100, an unpaired surrogate is `IllegalArgumentException`. List the versions of the key.
2. A delete marker as the newest entry over a data version: `EvidenceCorruptionException`.
3. An oldest data version exists: read it with GET, hash its bytes against the recorded `regpack-sha256` (a mismatch or missing metadata is `EvidenceCorruptionException`, no PUT), and then compare the recorded hash with the request's: equal is `ALREADY_PRESENT_IDENTICAL` with no PUT, different is `EvidenceIntegrityException` (`WormEvidenceStoreTest#aPutOfAnExistingKeyWithTheSameBytesSendsNoPut`, `#aPutOfAnExistingKeyWithOtherBytesIsAnIntegrityAlarmAndSendsNoPut`, `#aPutOfAnExistingKeyWhoseStoredBytesAreDamagedIsACorruptionAlarmAndSendsNoPut`).
4. Absent: refuse a retain-until not after the data clock, then PUT with `x-amz-content-sha256`, `Content-MD5`, the lock headers and the metadata; the answer must carry a version id. Then list the versions again: an own version missing from the second listing is `EvidenceUnavailableException` and not `CREATED` (`WormEvidenceStoreTest#anOwnVersionMissingFromTheSecondListingIsUnavailable`); the own version oldest is `CREATED`; another, older version decides as in step 3 and the own version stays as an extra version.

Probe result P10: a wrong `x-amz-content-sha256` was refused (400 `XAmzContentSHA256Mismatch`), a wrong `Content-MD5` was refused (400 `BadDigest`) and the run recorded `stored=0`, so the plan's fallback (read the own version back and hash it) was not built; section 3, item 7 states what rests on that. Probe result P17: `If-None-Match: *` on a PUT was accepted and ignored, a second PUT to the same key made a second version, so the algorithm does not rely on conditional writes. Probe result P13: eight concurrent PUTs of different bytes to one key answered 200 with eight version ids and eight versions; the newest version was not the last to finish and the oldest was the first to finish in that one run. The rank of a version is the server's.

The race that remains, two processes putting different bytes under one key at once, is stated in the words of the plan in section 3, statement (i). `WormStoreVersionsTest#twoStoresRacingWithDifferentBytesAreCountedAndReadAsOne` states and counts it on the fake (20 rounds, two store objects, two threads; both read the same bytes afterwards, a round with two `CREATED` is counted and is not a failure). Threads of one store object share the stripe locks, and the shared contract method `concurrentPutsOfTheSameKeyWithDifferentBytesHaveExactlyOneWinner` shows one winner for them on the fake and, at CP-2, on the service. `aForeignOlderVersionAppearingDuringThePutWithOtherBytesIsAnIntegrityAlarmAndGetReturnsTheForeignBytes` and `aForeignOlderVersionAppearingDuringThePutWithTheSameBytesIsAlreadyPresentIdentical` cover the foreign-older-version case with the fake's `addOlderVersionOnNextPut`.

### 1.4 Get, list, paging

- `get`: the version list, GET of the oldest data version, bytes hashed against `regpack-sha256` (`EvidenceCorruptionException` on a difference, on missing or unreadable metadata, on a listed version that answers 404, and when the newest entry is a delete marker); one more request for the tags when GET carries a tagging count. `aNewerVersionAddedBehindTheBackNeverChangesWhatGetReturns` and `theOldestVersionIsFoundWhenTheVersionListSpansPages` pin invariant V.
- `list`: ListObjectVersions with the prefix and, for `after`, a key marker without a version-id marker; it returns the keys that have a data version in key order, skips keys that hold delete markers and no data version, and keys that are not valid `EvidenceKey`s, and follows the returned markers until `limit` distinct keys are collected or the listing ends. Probe result P8: with 1,100 keys and one marker the pages held 1,000 and 101 entries, keys came in ASCII order, a key marker without a version-id marker started strictly after that key, pages of 7 were distinct, a key hidden by a marker was listed, and the run saw no short page. The adapter does not rely on that: `listFollowsTheMarkersUntilThePageIsFull` (fake with pages of 2) and `aMarkerBehindTheAdaptersPagingIsFound` (`WormSealScenariosTest`) show full answers over short service pages. A page that repeats its markers, a truncated page without both next markers and more than 10,000 pages are `EvidenceUnavailableException`; the fake's endless truncation shows the first case (`aListingThatNeverEndsIsUnavailable`).
- The paging hand-off of p3b-notes section 3 item 32: the `EvidenceStore.list` Javadoc promises full pages, the contract method `listReturnsFullPagesWhileKeysRemain` has the mutant `Flaw.SHORT_PAGES`, and `EpochMarker.current` and `JournalWriter.nextOrdinal` read to an empty page and fail instead of spinning on a listing that does not advance (`EpochMarkerTest#aStoreThatAnswersInShortPagesIsReadToTheEnd`, `JournalWriterTest#nextOrdinalReadsUntilAnEmptyPage`, and the two `aListingThatDoesNotAdvanceFailsInsteadOfSpinning` methods).

### 1.5 Requests per operation

Measured on 2026-10-24 on the code of `6b21151` with the fake service, from `FakeS3Server.requestLines()`, by a throwaway program (a test class in the module, run once and deleted, not committed; the plan names no test for the counts). These are counts of requests against the fake and not durations or costs on any service. Each count has one page of versions per version listing; a key with more versions than a page, or a listing of more keys than a page, adds one request per further page.

| Operation | Requests | Order | Plan figure |
|---|---|---|---|
| `put`, new key | 3 | versions, PUT, versions | 3 |
| `put`, existing key, same bytes | 2 | versions, GET (no PUT) | 2 |
| `put`, existing key, other bytes | 2 | versions, GET, then `EvidenceIntegrityException` (no PUT) | not stated |
| `get`, key present, no retention tag | 2 | versions, GET | 2 |
| `get`, key present, retention tag present | 3 | versions, GET, tagging GET | 3 |
| `get`, key absent | 1 | versions | not stated |
| `extendRetention`, first extension, the lock moves | 5 | versions, GET, tagging GET, PutObjectRetention, PutObjectTagging | 4 (5 when a tag exists) |
| `extendRetention`, first extension, rounded date equals the lock | 4 | versions, GET, tagging GET, PutObjectTagging | not stated |
| `extendRetention`, a further extension (tag exists), the lock moves | 5 | as the first | 5 |
| `extendRetention` to the current value | 3 | versions, GET, tagging GET (no write) | not stated |
| `setLegalHold` | 2 | versions, PutObjectLegalHold | not stated |
| `list`, two keys, one page | 1 | versions (one request per page of up to 1,000 entries) | not stated |
| `durability()` | 2 | versioning GET, object-lock configuration GET | not stated |
| `grade()` | 1 | versioning GET | not stated |

The write path of `extendRetention` reads the tags whatever the count header of the GET says (a comment in `extendLocked` gives the reason: the count header is not trusted for a write), so the first extension costs 5 requests when the lock moves and 4 when just the tag is written, and the plan figure of 4 (5 when a tag exists) does not match the code (section 7). Rows with a pinning assertion: `put` of a new key (`WormEvidenceStoreTest#aNewKeyIsPutOnceAndCheckedTwice`), `put` of an existing key with the same bytes (`#aPutOfAnExistingKeyWithTheSameBytesSendsNoPut`) and with other bytes (`#aPutOfAnExistingKeyWithOtherBytesIsAnIntegrityAlarmAndSendsNoPut`), and `get` without and with a tag (the kinds of request in `WormRetentionTest#anExtendedRetentionIsLockedFirstAndTaggedSecond`). The same test pins the order of the two writes of an extension and not the total count. The other rows (`get` of an absent key, the extension rows, `setLegalHold`, `list`, `durability()`, `grade()`) are measurements without a pinning test.

### 1.6 What each probe item recorded

Source: [p3b2-probe.md](p3b2-probe.md), which holds the printed text of each item and the consequence the controller drew; the probe reduces and cuts values (the tail of P7 and the page sizes of P8 are shortened).

| Item | Recorded (probe result) | Effect on the adapter |
|---|---|---|
| P1 | create with the lock header 200; versioning and lock on | D-W9 stands |
| P2 | GOVERNANCE and a retain-until accepted; HEAD returned mode, the date as sent and a version id | D-W5 header names stand |
| P3 | 1,676 bytes of metadata in five names accepted and returned | D-W2 stands |
| P4 | tags on a locked version written and returned verbatim, tagging count 1 | the tag design stands; OI-8 and the task T7b were not needed |
| P5 | extend 200 with and without Content-MD5; shorten 403 without the bypass header and 200 with it (governance); a COMPLIANCE lock 403 even with it | the adapter does not shorten a lock and does not send the header; the client sends Content-MD5 anyway |
| P6 | hold set and cleared; header absent before a hold | an absent header is off |
| P7 | newest first; marker on DELETE without a version id (204); 404 with the marker header; delete by version id 403 | D-W2 to D-W4 stand |
| P8 | pages of 1,000 and 101; ASCII order; key marker strictly after; pages of 7 distinct; hidden key listed; no short page | the adapter still handles short pages |
| P9 | keys with `+ = , $` and 512 characters round trip | D-W2 stands |
| P10 | wrong payload hash 400 `XAmzContentSHA256Mismatch`; wrong MD5 400 `BadDigest`; `stored=0` | D-W3 step 4 has no read-back |
| P11 | XML bodies for retention, hold and tagging accepted (sent with Content-MD5 as the client does) | the client keeps sending it |
| P12 | whole seconds, six digits and year 9999 accepted; past date 400; HEAD returned the six-digit text | `maxLockDate` stays year 9999; the rounding stays |
| P13 | eight concurrent PUTs: eight 200s, ids and versions; newest not last to finish; oldest first to finish | the race text of D-W3 stays |
| P14 | error body `<Error><Code>` and `<Message>` | `S3Xml` stands; the message is not copied |
| P15 | replication read 404 `ReplicationConfigurationNotFoundError`; storage class and replication status absent | the declaration is the operator's |
| P16 | first signed GET with the port in `Host` 200; the other signed request types were accepted too | the signer and the host rule hold against this service |
| P17 | `If-None-Match: *` accepted and ignored; second PUT made a second version | information for SP-O |

The read-out records no contradiction of A1, A3 or A5 or of a decision of the plan (p3b2-probe.md, section "Deviations from the plan that the read-out causes").

### 1.7 The fake and the behaviour contract

`FakeS3Server` (test code, JDK `HttpServer` on the loopback address) recomputes the SigV4 signature of each request from the raw request and answers 403 `SignatureDoesNotMatch` otherwise (`FakeS3ServerTest#aRequestSignedWithAnotherSecretIsRefusedWithSignatureDoesNotMatch`); it implements the behaviours of the probe results with the switches `SHORT_VERSION_PAGES`, `ENDLESS_TRUNCATION`, `LOSE_RESPONSE_AFTER_STORE`, `SERVER_ERROR`, `DROP_CONNECTION`, `CORRUPT_BYTES`, `NO_VERSION_ID` and `FAIL_TAGGING`. `S3BehaviourContract` (13 methods) states the behaviours the adapter relies on once and is bound to the fake (`S3BehaviourFakeTest`) and to the service (`WormServiceBehaviourIT`, 13 tests at CP-2): a fake that drifts from the service fails there. `S3BehaviourContract#theRetainUntilResolutionIsWholeSeconds` asserts the whole-second round trip of the adapter's own writes, not that the service truncates (probe result P12).

## 2. The gate commands and the label `REALWORM`

Local, from the repository root (Git Bash; `export JAVA_HOME=/c/dev/jdk/jdk-25.0.4.1+1; export PATH="$JAVA_HOME/bin:$PATH"` first):

- `./mvnw -B -ntp verify` (the whole reactor). The `*Test` classes of `regpack-evidence-s3` run against the fake; the `Worm*IT`, `PgWorm*IT` and `S3ServiceProbeIT` classes carry the tags `worm` and `probe`, which the module property `excludedGroups` (`mutant,worm,probe`) and that of `regpack-postgres` (`mutant,capacity,durability,worm`) keep out of `verify`. `bash scripts/t.sh regpack-evidence-s3 '*Test'` runs the unit classes; `bash scripts/it.sh regpack-evidence-s3 'Worm*IT' -Dgroups=worm -DexcludedGroups=none` runs the service classes (and `bash scripts/it.sh regpack-postgres 'PgWorm*IT' -Dgroups=worm -DexcludedGroups=none` those of the PostgreSQL module); they skip when `REGPACK_S3_ENDPOINT` is unset and fail instead of skipping when `REGPACK_S3_REQUIRED=true` (`S3TestServiceTest#anUnsetServiceSkipsUnlessRequired`, `#requiredWithoutAServiceFails`). Without the two flags the command runs 0 tests and ends green, because `excludedGroups` removes the `worm` tag. With them and no service, run on 2026-10-24 at `a7bc619`: `regpack-evidence-s3` 88 tests, all skipped (42 + 26 + 7 + 13), and `regpack-postgres` 28, all skipped; the no-flag run printed `Tests run: 0` and BUILD SUCCESS. A skipped run says nothing about the service; the passes of these classes are those of CP-2. `S3TestService` is the one class of the module that reads the environment (`NoAmbientEnvironmentTest`); `PgWormSupport` is a further environment reader in `regpack-postgres` tests.
- `bash scripts/check-gates.sh` ends `check-gates: all gates passed`; its traceability call stays `--realstore skip`, where `REALWORM` lines are not looked up.
- `java -cp regpack-tools/target/classes dev.regpack.tools.Gates entry --target P-WORM-non-aws --root .` printed `entry: P-WORM-non-aws: SP-O REPORT_EXISTS MISSING` when run for this file on 2026-10-24: the SP-O report is missing and the target stays `MISSING`.

CI: the job `evidence-worm` (name "evidence P-WORM (S3-compatible object-lock service, PostgreSQL 18)", `runs-on: ubuntu-24.04`) runs the Scality CloudServer image above and the PostgreSQL image of `ledger-pg` as service containers on `127.0.0.1`, waits for the S3 port, runs `./mvnw verify -pl regpack-evidence-s3 ... -Dgroups=worm -DexcludedGroups=none -Dit.test='Worm*IT'`, the same for `regpack-postgres` with `'PgWorm*IT'`, then `Gates traceability ... --realstore worm`, then `Gates annotate`. The approved text of version 2 has the SHA-256 `bd419fd96d7b9448144e2246e0e9b083ca893a8e2c32fcf84789de4caeb80821` and `PackagingFilesTest` holds it by that hash. Observed at CP-2 (run 40141048211, head `6b21151`): `evidence-worm` ran 6 classes and 116 tests (S3 step 88, PostgreSQL step 28), failed 0, errors 0, skipped 0; the `ledger (real PostgreSQL 18)` job ran 603 tests; `ledger durability` ran `PgDurabilityUpIT` 4 and `PgDurabilityDownIT` 5, unchanged. The worm-mode traceability step passed; I1 and I10 are not due while `exited_plans` is P0, so it checked no row yet.

The label `REALWORM` (A14 of P3b1 extended; owner approval AP-1): a line names an integration test whose class name ends in `IT` and carries `@Tag("worm")`; such a line counts as non-interim evidence for I1 and I10 and not for another row (`TraceabilityCheckTest#realWormCountsOnlyForI1AndI10`); mode `worm` evaluates `REALWORM` lines and the modes `skip`, `only` and `standby` ignore them (`#realWormLinesAreIgnoredInSkipOnlyAndStandbyModes`, `#realWormLinesAreEvaluatedOnlyInAllAndWormModes`), and a due I1 or I10 row without a passed `REALWORM` line is a problem in mode `worm` (`#aDueI1OrI10RowWithoutAPassedRealWormLineIsAProblemInWormMode`); a `REALWORM` line that cites a `*Test` class is a problem (`REALWORM_FAKE_BACKED`; `#realWormLineOfAFakeBackedClassIsAProblem`), and the cited method has to exist in the sources (`#everyRealWormLineNamesATestMethodThatExistsInTheSources`). `docs/traceability.md` holds 5 `REALWORM` lines for I1 and 3 for I10 (`WormEvidenceStoreContractIT`, `WormSealScenariosIT`, `WormVerifyIT`, `PgWormSealScenarioIT`, `PgWormVerifyIT`). A due I1 or I10 row still needs the `REALSTORE` line of `ledger-pg` (spec 14.1, D-W12). The gate line stays `rows=24 closed=1 open=23 deferred=0` while the rows are not due.

## 3. Limits

Each limit says who owns it.

1. **Path-style addressing** (A1). The client sends `/{bucket}/{key}`; the first signed GET with the port in `Host` was accepted (probe result P16). A store that needs virtual-hosted-style addressing is not served (recollection about such stores). Owner: SP-O per store, and the wiring plan.
2. **Whole object in memory; response bodies read whole and not size-capped by the client.** `put` takes a `byte[]`, which is held in memory and hashed twice (SHA-256 and MD5), `JdkTransport` reads a response with `BodyHandlers.ofByteArray()`, and `get` returns the bytes. The size constants in the main code are the page size (1,000), the page cap (10,000) and the lengths of the media type and the class name; no limit on an object or on a response is set in `JdkTransport` or `WormEvidenceStore` (read at `6b21151`; no test pins it). The bound on an object is the journal bound where a journal object is stored (p3b-notes section 1.4: 1 MiB for most types and 64 MiB for two), and this module sets no bound on a sealed file. The peak heap per put or get is unmeasured. The plan's list of unrun cases names an object over 3 MiB and the 64 MiB journal bound through a real PUT. Owner: plan P8 and the wiring plan (a size cap in the transport is a later change that no task owns).
3. **A TLS handshake per request.** `JdkTransport` builds a fresh `HttpClient` for each request and closes it, so no connection is reused; a new key costs 3 requests (section 1.5). The CI service ran over plain `http` on loopback; a non-loopback TLS endpoint was not run. Owner: the wiring plan (connection pooling is a performance hand-off).
4. **Failure domains are declared and not shown.** The S3 API shows no replication (probe result P15). The CI service declares one domain, so the production gate (`Requirement.production()` asks for two) stays closed for it; a test that declares three does so with `Verification.SP_O_VERIFIED` and says so (`AbstractWormSealTest#theProductionGateIsClosedForOneDeclaredDomainAndOpensForThreeDeclaredByTheTest`). A copy counts when it exists at the acknowledgement of the PUT; asynchronous replication counts zero (`WormConfigTest#aStoreMustDeclareSynchronousAcknowledgementAndOneFailureDomain`). Owner: the operator's declaration, attested in the G8 record (spec 5.4).
5. **Governance mode, graded unverified, one mode per store.** The grade is `P-WORM/governance (unverified)`. Compliance mode is accepted by `WormConfig` for `AWS_S3` or `SP_O_VERIFIED` and was not run through `WormEvidenceStore` on any store; the one observation of a COMPLIANCE lock is probe result P5, on the raw client in a throwaway bucket (a COMPLIANCE lock was not shortened, even with the bypass header). A per-class mode is not built (statement (ii) below). Owner: the wiring plan, SP-O and G8.
6. **A retain-until above `maxLockDate` is recorded and not store-enforced.** The lock date is clamped to `maxLockDate` and the exact value stays in the metadata and the tag (`WormRetentionTest#theLastSecondOfYear9999IsClampedAndStillRoundTrips`, `WormEvidenceStoreTest#aRetainUntilBeyondTheMaximumLockDateIsLockedUntilTheMaximumAndRecordedInFull`). Probe result P12 accepted year 9999, so at the default the clamp reaches a retain-until after `9999-12-31T23:59:59Z`; a lower configured `maxLockDate` widens it. Owner: the wiring plan (the value of `maxLockDate` for a store).
7. **In-transit integrity rests on the service verifying the payload hash.** The put sends `x-amz-content-sha256` and `Content-MD5`; the service refused a wrong value of either and the run recorded `stored=0` (probe result P10), and step 4 of the algorithm does not read the own version back. A service that did not verify could store altered bytes while the put answers `CREATED`; a later `get` then throws `EvidenceCorruptionException` and `verify` reports the key as `HASH_MISMATCH` `store-integrity`, because the recorded hash is that of the request (`getRefusesBytesThatDoNotMatchTheRecordedSha256`, `aDamagedObjectIsStoreIntegrityAndNotMissing`). Owner: SP-O per store.
8. **Adapter limits that are not port rules** (A10, Port change 4). A media type over 255 characters, a class name over 100, an unpaired surrogate in either, and a retain-until not after the data clock for a new key are refused before any request (`WormEvidenceStoreTest#aMediaTypeOver255CharactersAndAClassNameOver100AreRefusedBeforeAnyRequest`, `#aMediaTypeOrClassNameWithAnUnpairedSurrogateIsRefusedBeforeAnyRequest`, `#aRetainUntilNotAfterTheClockIsRefusedBeforeThePutOfANewKeyButNotForAnExistingOne`); the other adapters accept them, and the contract method `aMediaTypeOf255CharactersAndARetentionClassNameOf100CharactersRoundTrip` pins the boundary in the three bindings (`InMemoryEvidenceStoreContractTest`, `SealedEvidenceStoreContractTest`, `WormEvidenceStoreContractTest`). The reason is the user-metadata limit (recollection: about 2 KB) and a lock date that has to lie in the server's future (probe result P12: a past date was refused). Owner: the owner may ask for port rules.
9. **Extra versions after a lost race stay locked** (D-W3). A version that is not the oldest was put with its own lock headers and keeps that lock; a DELETE of a locked version by id was refused (probe result P7), and the port does not return such a version. Retention expiry (plan P4) has to deal with it. Owner: plan P4.
10. **Foreign keys and delete markers** (D-W4). A key that is not a valid `EvidenceKey` is skipped by `list` (`aForeignKeyWithInvalidCharactersIsSkippedByListAndStillFillsThePage`); a valid-looking foreign key without regpack metadata is listed and its `get` is `EvidenceCorruptionException` (`getRefusesAnObjectWithoutRegpackMetadata`); a delete marker added by another principal hides a key: it stays listed, `get` and `put` raise `EvidenceCorruptionException` and `verify` reports `HASH_MISMATCH` `store-integrity` (`WormStoreVersionsTest#aDeleteMarkerOverAnObjectIsACorruptionAlarmForGetAndPutAndTheKeyStaysListed`, `AbstractWormVerifyTest#aHiddenObjectIsReportedAsStoreIntegrityHashMismatchAndTheRunContinues`). The main sources issue no DELETE request (`NoBypassTest`).
11. **Tags are not protected by the lock.** Probe result P4 shows tags written on a locked version. The lock date is therefore the floor of what `get` reports: a tag raised above the lock makes the rounded exact value later than the lock and `get` throws `EvidenceCorruptionException` (read from `retained()`; the test `WormRetentionTest#aLockShorterThanTheRecordedRetentionIsACorruptionAlarm` takes the same branch for a shortened lock). The adapter needs the permission to tag, so the identity cannot be denied it. Owner: deployment.
12. **No retries, and one deadline per request.** A lost response is `EvidenceUnavailableException` and the retry finds the object (`aPutWhoseResponseIsLostIsUnavailableAndTheRetryFindsTheObject`, on the fake); the deadline of each request is `WormConfig.requestTimeout` (default 30 seconds) and the adapter bounds no call as a whole (a put is 3 requests). Owner: plan P8 (the watchdog of p3b-notes section 3 item 18 is the ledger side of the same question).
13. **Test data and clocks** (D-W13). The bindings run the data clock at `2040-03-01T00:00:00Z` so that retain-until dates in 2045 lie in the server's future; the signing clock is real time on the service and manual on the fake. One bucket per test (`rp-` plus 12 hex digits), not emptied afterwards; the tests that send the bypass header shorten a lock and do not delete a version; the service container is discarded with the job (recollection). Clock skew between the runner and a service was not run.
14. **One probe run, one image digest.** The probe results above are one observation of CloudServer 9.5.0 at that digest on one CI runner. These notes make no statement about AWS S3, MinIO, SeaweedFS or another version, and SP-O has not been run.
15. **What runs where.** The `*Test` classes of the module, among them the contract bindings on the fake, `SigV4Test` and the teeth tests, run in `verify` locally and in the build jobs on Linux and Windows; at CP-2 those jobs were green, and that the tests executed is not observed there (the build jobs print no tally annotation); the local `./mvnw -B -ntp verify` of 2026-10-24 at `a7bc619` ended BUILD SUCCESS with 290 tests in `regpack-evidence-s3` (failed 0, errors 0, skipped 0) and 3,758 in the reactor (299 skipped, 288 of them integration tests of `regpack-postgres` that skip without a server). `Worm*IT` (88 tests) and `PgWorm*IT` (28 tests) run in `evidence-worm` on `ubuntu-24.04`. The probe ran at CP-1 (job version 1); version 2 does not select it. Windows against the service and SP-W are not run.
16. **The PostgreSQL half.** On the PostgreSQL ledger with P-WORM the seeded faults are ORPHAN, MISSING, HASH_MISMATCH (`manifest-hash`) and INTENT_MISMATCH (`PgWormVerifyIT#aSeededFaultOnThePostgreSqlLedgerIsReportedWithItsOwnClass`); SEQUENCE_GAP needs the class (b) counter of P3d. `WormVerifyIT` (7 tests) runs `verify` on P-WORM with the in-memory ledger; the number n of faults it seeds is stated in the exit record.

Three statements, each copied from its source in the plan.

(i) The residual hole of the cross-process race (D-W3 of the plan):

> `get` returns the older-ranked version (invariant V), and if the server ranks by request start the answer of `get` can change once while the slower upload is in flight. The ledger keeps the hash of whichever writer reaches `recordObjects` first and fails the other seal closed, so when the writer whose bytes `get` does not return is first, its seal succeeds and gates an effect on bytes the port cannot return (they stay as a non-oldest locked version that the port does not return; reading it needs the raw S3 API with its version id [R]); `verify` reports the key afterwards (`HASH_MISMATCH`, `manifest-hash`). This is a stated residual hole of I10 for that attempt, not closed here: conditional writes (spec 5.2, 11.5 B.1; deferred) would close it, and no other closure was examined; `docs/exit/P3b2.md` says so in these words.

(ii) The per-class compliance mode (Departures (3) of the plan):

> Spec 16 P3 row: compliance mode is enabled per retention class in production. `WormConfig` carries one `LockMode` for the whole store; a per-class mode is not built in P3b2 and is a hand-off to the plan that wires the adapter and to G8. Nothing is wired. AP-1 approves it or asks for it.

The answer to AP-1, which named this departure, is recorded in [p3b2-decisions.md](p3b2-decisions.md).

(iii) The governance grade in production, from the Scope check of the plan:

> Nothing in the adapter or in `SendGate` refuses governance mode in production (`SendGate` reads durability only); refusing a governance grade in production is a hand-off to the wiring plan and to G8.

## 4. Open items

| Item | Who decides or owns it |
|---|---|
| The residual hole of the cross-process race (section 1.3, statement (i)); conditional writes are deferred (spec 5.2, 11.5 B.1) and probe result P17 shows that this service ignores `If-None-Match` | the owner, with SP-O and the AWS conditional-PUT point |
| A per-class lock mode (statement (ii)) | the wiring plan and G8 |
| Neither the adapter nor `SendGate` refuses a governance grade in production (statement (iii)) | the wiring plan and G8 |
| The entry target `P-WORM-non-aws` is `MISSING`; no non-AWS store is called P-WORM or compliance mode in production before SP-O | the owner (the SP-O run for the store the firm will use) |
| `closes_in` and `exited_plans` for I1 and I10 (AP-8 of the P3b plan: `P3b` means P3b2); the agent edits neither | the owner, at the exit of P3b2 |
| The answers still open in [p3b2-decisions.md](p3b2-decisions.md): OI-5 (default no, not asked), OI-6 (the OI-6 defaults for retention class names and periods stand for tests and tools; Legal replaces them at G8), OI-7 (asked at T14), and group V of `docs/exit/P3b.md` (not asked; the plan does not take it) | the owner |
| The open items of `docs/exit/P3b.md` that no task of this plan took (I.1, I.3 to I.8, II.2 to II.12, III, IV, and I.10 and I.12 unchanged) keep the owners written there; the S3 items below are added to its group III | as written in that record |
| Group III additions for the S3 store: the bucket, versioning and object lock, the identity and its permissions, TLS, credentials, the declaration and `maxLockDate` (section 5, Deployment) | deployment, the first runtime consumer |
| Item II.1 of `docs/exit/P3b.md` (the `list` promise): the contract method, the mutant, the two callers and the bindings are listed in section 1.4; "closed" is written in the exit record with the tests and the runs | the exit record of P3b2 (T14) |
| The 2 KB metadata limit, the 15-minute clock skew, the permission names and the lifecycle of locked versions after expiry are recollection about AWS S3 and unchecked for other stores | SP-O per store |

## 5. Hand-offs

### P3b1 hand-offs to P3b2 (p3b-notes section 5), disposition

| Hand-off | Disposition |
|---|---|
| Bind `EvidenceStoreContract`, `JournalContract`, `SealScenarioContract` and `verify` to P-WORM | bound: `WormEvidenceStoreContractTest`, `WormSealScenariosTest` and `WormVerifyTest` on the fake, `WormEvidenceStoreContractIT` (42), `WormSealScenariosIT` (26), `WormVerifyIT` (7) on the service at CP-2, and `PgWormSealScenarioIT` and `PgWormVerifyIT` (28 together) with PostgreSQL at CP-2 |
| A put of an existing key without conditional writes, the race stated | section 1.3 |
| The durability-configuration check for S3 | `durability()` and `grade()` (section 1.2); `Requirement.production()` is unchanged |
| Paging (section 3 item 32) | section 1.4 |
| `P-WORM-non-aws` stays `MISSING` | section 2 |
| The S3 pointer (CloudServer, hand-written client, no copyleft image) | built; facts about the candidates are the probe results above, for CloudServer 9.5.0 (one run) |

### P3c

- Keys, the signer of manifests, the manifest function and the manifest conditions of the send gate; the signed-manifest findings of `verify` (on P-WORM the identity is reconstructed from `DeliveryIntent` objects, `AbstractWormVerifyTest#theIdentityOfEverySentDeliveryIsReconstructableFromTheEvidenceTierAlone`). Objects are stored as given: P-WORM holds plaintext until the envelope encryption of P3c, so the CI service and the fake receive no production data.
- SEAL-1: a content-addressed sealed object keeps the retention of its first put; the retention rows or the engine extend it to the maximum over the deliveries that name it, treating `EvidenceRetentionException` as satisfied. On P-WORM an earlier date than the exact retain-until throws it, an equal date writes nothing (`WormRetentionTest#extendingToTheCurrentRetainUntilChangesNothing`), and a later one costs 4 or 5 requests (section 1.5).

### P3d

- The class (b) shipper reads `list` to an empty page and puts through `JournalWriter`; on P-WORM a put of an existing key costs 2 requests and sends no PUT. `JournalWriter` still returns neither the stored hash nor the stored size for `ALREADY_PRESENT_SAME_PAYLOAD` (p3b-notes section 5, P3d).
- Whether the shipper runs in the process of the store object that the sender uses decides if the race of statement (i) reaches its puts; P3d states it.

### Plan P4

- Retention expiry deletes versions: the adapter has no delete and no bypass, and plan P4 has to remove the data versions, the extra versions that a lost race left and the delete markers of a key (recollection about the delete permissions; a DELETE of a locked version by id was refused in probe result P7). Epoch markers (`control/epoch/`) stay exempt: `EpochMarker.current()` reads the highest marker and a deleted one reads as a lower epoch.
- `verify --deep` with `LedgerIntegrity`, the restore steps, `attempt_id` resume and the scheduled checks of p3b-notes section 5, plan P4.

### Plan P8

- A bound on a whole call is not built (section 3, item 12) and the retry rule after an `EvidenceUnavailableException` is the sender's: the retry of a put whose response was lost finds the object and answers `ALREADY_PRESENT_IDENTICAL` on the fake.

### The wiring plan (the first runtime consumer)

- A pooled or reused connection (section 3, item 3: a TLS handshake per request), the value of `maxLockDate` and `requestTimeout`, the retention class names and periods of Legal (G8), the choice of lock mode per class (statement (ii)) and the refusal of a governance grade in production (statement (iii)).

### Deployment

- A bucket created with object lock and versioning on (probe result P1 shows that a bucket created with the lock header has both on). An identity for the adapter without the permissions to delete an object or a version, to bypass governance retention, to change versioning or the object-lock configuration, or to delete the bucket (the names of these permissions are recollection about AWS S3). The adapter makes these calls: PutObject, GetObject, ListObjectVersions, PutObjectRetention, PutObjectLegalHold, GetObjectTagging, PutObjectTagging, GetBucketVersioning and GetObjectLockConfiguration; that they map to permissions of those names is recollection. TLS to a non-loopback endpoint, credentials from the secrets port, and the declaration of failure domains attested in the G8 record.

### SP-O

- Per store: governance against compliance bypass, extend-only retention, versioning and delete markers, the put of an existing key, in the terms of the probe table above; for AWS S3 the conditional PUT, which would close the race of statement (i). Probe results P1 to P17 are the first observations of one store, not an SP-O report.

## 6. Records elsewhere

- Approvals, owner inputs, pins, deviations and checkpoints: [p3b2-decisions.md](p3b2-decisions.md). The probe: [p3b2-probe.md](p3b2-probe.md). The exit record `docs/exit/P3b2.md` is T14.
- The P-SEALED half and the journal formats: [p3b-notes.md](p3b-notes.md) and [decisions.md](decisions.md).

## 7. Where the plan text is stale

- The text of T12 gives the first extension as 4 requests (5 when a tag exists). The code reads the tags on the write path whatever the count header says, so the first extension takes 5 requests when the lock moves and 4 when just the tag is written (section 1.5, measured against the fake).
