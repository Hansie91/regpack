# regpack

Working title of a self-hosted regulatory reporting platform for one firm: it decides which transactions are
reportable and why, validates and enriches them, builds the regulator's file, delivers it, tracks every response,
queues what was not accepted, and can prove afterwards what was sent and when. Release 1 covers EU MiFIR
transaction reporting (RTS 22) to a simulated ARM; EMIR follows.

- Design specification (approved 2026-10-09): [docs/superpowers/specs/2026-10-08-regulatory-reporting-platform-design.md](docs/superpowers/specs/2026-10-08-regulatory-reporting-platform-design.md)
- Plan P0, foundation and walking skeleton: [docs/superpowers/plans/2026-10-09-p0-foundation-and-walking-skeleton.md](docs/superpowers/plans/2026-10-09-p0-foundation-and-walking-skeleton.md)
- Research: [docs/research/](docs/research/)

## Commands

- Set up the toolchain (Java 25 and Maven wrapper): see [toolchain/README.md](toolchain/README.md)
- Build and test: `./mvnw -B -ntp verify` (PowerShell: `.\mvnw.cmd -B -ntp verify`)
- Run the repository gates: `scripts/check-gates.sh` (added in plan P0, Task 3 onward)
