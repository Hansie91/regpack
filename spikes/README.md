# spikes

Disposable measurement code of plan P1a (spec 16: SP-R, SP-G, SP-L, SP-X). Nothing here is a release module.

## Rules

- `spikes/pom.xml` (`regpack-spikes`) is **not** a module of the root POM. The release build, the SBOM and the distribution never see it. `gates isolation` (run by `scripts/check-gates.sh`) fails when the root POM lists a `spikes` module, when a release POM names a `spike-` artefact, or when a module other than `regpack-bench` has `regpack-bench` on its compile or runtime class path.
- Each spike is one module (`spike-r`, `spike-g`, `spike-x`, `spike-l`, package `dev.regpack.spikes.<letter>`), added to `<modules>` of `spikes/pom.xml` by its own task.
- A third-party dependency is admitted only by the task that names it, with exact coordinates in the `bannedDependencies` include list of `spikes/pom.xml` (approvals A-1 and A-2 of the plan) and a licence entry.
- Gate G3: no ESMA XSD and no XSD- or workbook-derived content. The only schema allowed is the self-authored synthetic one under `spikes/spike-x/src/{main,test}/resources/synthetic/` (marker line and own namespace required). `gates g3` enforces this.
- The code is disposable and time-boxed (`docs/spikes/timebox/<ID>.tsv`). A result becomes evidence only through `scripts/bench.sh import` (see `docs/spikes/README.md`).

## Helpers

| Helper | Does |
|---|---|
| `spikes/t.sh <module> '<tests>'`, `spikes\t.ps1 -Module <module> -Test '<tests>'` | installs the release modules the spikes use (`regpack-bench`, `regpack-engine`, `regpack-inmemory`, skipping tests), then runs unit tests of one spike module |
| `spikes/it.sh <module> '<tests>'`, `spikes\it.ps1 -Module <module> -Test '<tests>'` | the same, then runs failsafe integration tests |

The install first is deliberate: a stale installed harness is the usual reason for a spike test that disagrees with the harness source. Set `SPIKES_SKIP_INSTALL` to any value to skip it when nothing outside `spikes/` changed. `REGPACK_MVN` overrides the Maven launcher.

`mvnw -B -ntp -f spikes/pom.xml validate` loads the aggregator and its enforcer rules.
