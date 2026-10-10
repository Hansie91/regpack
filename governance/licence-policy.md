# Licence policy

## Allow-list

A dependency may be added to any module only if its declared licence is one of:

- Apache-2.0
- EPL-2.0 (JUnit, at test and build time only; never in a shipped artefact)
- MIT
- BSD-2-Clause
- BSD-3-Clause

Anything else needs explicit written approval, recorded in this file with date and approver, before the dependency is added. In particular: GPL, AGPL, LGPL, BSL, ELv2, SSPL, Commons Clause, and any missing or unknown licence.

## Where the rule lives

The allow-list is enforced by the `license-maven-plugin` configuration in the root `pom.xml` (`includedLicenses`, with `licenseMerges` mapping the spellings used in dependency POMs to the five names above). This file is its rationale. Do not widen `includedLicenses` without recording the approval here first.

The gate runs as separate Maven task segments after `verify`:

```
./mvnw -B -ntp verify cyclonedx:makeAggregateBom license:aggregate-add-third-party -DincludeTestScope=true
```

`cyclonedx:makeAggregateBom` writes the SBOM of the whole build to `target/bom.json`. `license:aggregate-add-third-party` fails the build for any dependency whose licence is not on the allow-list or is missing, and writes `target/generated-sources/license/THIRD-PARTY.txt`. The plugin is configured with `<force>true</force>`, so a repeat local run is never skipped as up to date and always re-checks the licences. A dependency whose POM lacks licence data is declared in `governance/THIRD-PARTY.missing.properties`, and only with an approval recorded below.

Owner confirmation: 2026-10-09, hans. The allow-list above is confirmed; EPL-2.0 applies at test and build time only, and Legal may review it later.

## Approvals

None recorded.

## Interim vulnerability handling (until P9b)

- Dependabot alerts and security updates: to be enabled in the repository settings. They were not enabled when P0 was signed (an open owner action, recorded in docs/exit/P0.md), so this control is not yet in force.
- The weekly Dockerfile digest pull request (`.github/dependabot.yml`) is the base-image control; it takes effect only once that file is on the default branch and Dependabot is enabled.
- The JDK patch level moves by changing the pinned Temurin version in `toolchain/README.md` and the CI `java-version`.
- A HIGH or CRITICAL advisory affecting a shipped component is triaged and recorded in `docs/exit/P0.md` before the owner signs P0.
- The gating scanner, for example OSV-Scanner or Trivy, is chosen in P9b.
