# P3b2 probe read-out (T7 step 1)

What the S3-compatible CI service really does, read from the first CI run that executed the probe. Written by the controller; every statement about the service below is an observation of that one run (a probe result), not a claim about AWS S3 or about any other version of the image.

## Source

- CI checkpoint CP-1: [run 40123391011](https://github.com/Hansie91/regpack/actions/runs/40123391011), head `ab7dab2` on `p3b2/worm-s3`, 2026-10-24 19:01:31 to 19:08:25 UTC. All ten jobs that run succeeded; `ledger capacity` was skipped by design.
- Job `evidence-worm` (check run 120959571592): `S3ServiceProbeIT` run 1, failed 0, errors 0, skipped 0 (`tally-classes` and `tally-total` notices).
- Service: `ghcr.io/scality/cloudserver:9.5.0@sha256:652d50d0d92f5ef948b8c3aae92251b5707a70892d886835f904a069f60a0727` with `S3BACKEND=file`, path-style requests to `http://127.0.0.1:8000`.
- The text below is the `probe-results` warning annotation of that job, read without sign-in from the check-run annotations. The probe reduces every observed value to the characters `A-Za-z0-9 ._:=,;/+-` (anything else is printed `?`) and cuts it at 120 characters, so some items are shortened: P7 ends in `mkFirst` without its value and the P8 page sizes print as `?1000, 101?`.

## Results and consequences

| Id | Printed result | Consequence for the plan |
|---|---|---|
| P1 | `200/none/create=200 versioning=true lock=true` | As expected: a bucket created with object lock is versioned and locked. A1, A2, D-W9 stand. |
| P2 | `200/none/put=200 mode=GOVERNANCE until=2045-03-01T00:00:00Z vid=true` | As expected: the lock headers are accepted, HEAD returns mode, date and a version id. The retain-until text comes back as `2045-03-01T00:00:00Z` (the text that was sent). D-W5 header names stand. |
| P3 | `200/none/bytes=1676 put=200 head=5/5 get=5/5` | As expected: the worst case of A10 (1,676 bytes of user metadata, five names) is accepted and all five come back on HEAD and GET. D-W2 stands. |
| P4 | `200/none/tagged=ok verbatim=true tags=1 count=1` | As expected: tags can be written on a locked version and come back verbatim, with `x-amz-tagging-count`. A5 stands; OI-8 and T7b are not needed. |
| P5 | `403/AccessDenied/ext=200 extNoMd5=200 short=403/AccessDenied shortBypass=200 compBypass=403/AccessDenied` | Extending works, with and without Content-MD5 (this service does not require it; the client sends it anyway). Shortening a governance lock is refused without the bypass header and accepted with it; a COMPLIANCE lock cannot be shortened even with the bypass header. D-W5 stands: the adapter never shortens a lock and never sends the bypass header. |
| P6 | `200/none/before=absent headOn=ON getOn=ON headOff=OFF` | As expected: legal hold can be set and cleared; before any hold the header is absent (read as OFF, D-W6); HEAD and GET carry it. D-W6 stands. |
| P7 | `204/none/newestFirst=true del=204 mk=true get=404/NoSuchKey/true head=404/true byId=403/AccessDenied listed=3 mkFirst` | As expected: the version list is newest first; a DELETE without a version id adds a delete marker (204) even under lock; GET and HEAD after it answer 404 with the delete-marker header; a DELETE of a locked version by id is refused (403). The tail of the item (`mkFirst`) was cut at 120 characters, so the position of the marker in the list is read only from `newestFirst=true` (a marker is the newest entry of its key). D-W2, D-W3, D-W4 stand; the oldest data version is the last data entry of the key's run. |
| P8 | `200/none/mk=204 sizes=?1000, 101? keys=1100 entries=1101 sorted=true strictAfter=true m7=7+7 distinct=14 hidden=1+1 short=0` | Version listing over 1,100 keys with one marker: pages of 1,000 and 101 entries, ASCII key order, a `key-marker` without a `version-id-marker` starts strictly after that key, pages of 7 are distinct, a key hidden by a marker is listed (its marker and its version), and no page was short. This service answers full pages; the adapter and the fake still handle short pages (D-W4, D-W10). |
| P9 | `200/none/put=200 getOdd=200/true listedOdd=true get512=200/true listed512=true` | As expected: keys with `+ = , $` and a 512-character key round-trip through the canonical path and are listed back unchanged. D-W2 stands. |
| P10 | `400/XAmzContentSHA256Mismatch/badSha=400/XAmzContentSHA256Mismatch badMd5=400/BadDigest stored=0` | The service verifies the payload hash and the MD5 and stores nothing on a mismatch. D-W3 step (4) needs no read-back of the own version; T12 states that in-transit integrity rests on the service verifying the payload hash. |
| P11 | `200/none/retention=200 hold=200 tagging=200` | XML bodies for retention, legal hold and tagging are accepted (this run sent them as the client does). Nothing changes: the client always sends Content-MD5. |
| P12 | `200/InvalidArgument/s=200 f6=200 y9999=200 max=9999 past=400/InvalidArgument hf=2045-03-01T00:00:00.123456Z` | Whole seconds, six fractional digits and the year 9999 are accepted; the largest accepted year is 9999; a past date is refused (400 InvalidArgument). HEAD returned the six-digit text it was given, so this service keeps sub-second digits. The adapter still locks until the retain-until rounded up to whole seconds and keeps the exact value in the metadata and the tag (D-W5): that is correct for a store that keeps whole seconds and harmless here. `maxLockDate` stays `9999-12-31T23:59:59Z`. The behaviour contract states the whole-second round trip that the adapter relies on, not that the service truncates. |
| P13 | `200/none/ok=8 ids=8 versions=8 newestIsLastDone=false oldestIsFirstDone=true` | Eight concurrent PUTs of different bytes to one key: all 200, eight distinct version ids, eight versions. The newest version was not the last one to finish; in this one run the oldest was the first to finish. The rank of a version is the server's, so the race of D-W3 stays stated and is not closed by the adapter. One run is an observation, not a proof of an ordering rule. |
| P14 | `404/NoSuchKey/status=404 errorElement=true code=NoSuchKey message=true` | As expected: an error body is `<Error><Code>...</Code><Message>...`. `S3Xml` stands; the adapter never copies the message (D-W7). |
| P15 | `200/none/location=200 replication=404/ReplicationConfigurationNotFoundError storageClass=absent replicationStatus=absent` | The API shows nothing about replication or storage class. D-W9 stands: `durability()` reports what `WormConfig` declares, after the API has shown versioning and object lock to be on. |
| P16 | `200/none/get=200 hostHasPort=true` | The signer and the host rule (the port is kept in the Host header) are right: the first signed GET is accepted. The other items sent signed PUT, HEAD, DELETE, list, retention, legal-hold and tagging requests and all were accepted by the signature check, so T2 and T3 work against this service for those request types. |
| P17 | `200/none/first=200 second=200 versions=2` | `If-None-Match: *` on a PUT is accepted and ignored: a second PUT to the same key creates a second version. There are no conditional writes on this service, as D-W3 assumes (information for SP-O). |

## What this does not show

- Whether any of this holds for AWS S3 or another store, or for another version of the image (SP-O is not run).
- The tail of P7 and the page sizes of P8 as exact text (cut and reduced by the probe).
- Any behaviour outside the one run: the probe ran once, with one writer set per item, in one CI job.

## Deviations from the plan that the read-out causes

None so far. P12 is a difference between this service and the AWS behaviour the plan recollected, not a contradiction of a decision. The names of the behaviour-contract methods stand; `theRetainUntilResolutionIsWholeSeconds` is implemented as the plan's T7 text says and asserts the whole-second round trip of the adapter's own writes.
