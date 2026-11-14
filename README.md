# Seebrook

HOA mobile app — membership, document library, dues and payments. Pilot: Channel Harbor HOA (32 units).

Start with [`docs/README.md`](docs/README.md) for the product handoff and [`docs/ENGINEERING_DECISIONS.md`](docs/ENGINEERING_DECISIONS.md) for engineering choices. Contributor and agent rules are in [`AGENTS.md`](AGENTS.md).

## Quick start

Prerequisites: Eclipse Temurin JDK 21, Docker.

```bash
git config core.hooksPath .githooks   # once per clone: blocks direct commits/pushes to develop and master

scripts/dev.sh            # start PostgreSQL, migrate, load synthetic Channel Harbor data, serve on :8088
curl localhost:8088/health/ready
scripts/dev.sh down       # stop the local database (scripts/dev.sh reset also deletes its data)

./gradlew build           # compile and run all tests (Testcontainers needs Docker)
```

### Try sign-in locally (M1)

Locally the server accepts development tokens instead of Firebase tokens (ED-29). With `scripts/dev.sh` running, in another terminal:

```bash
export SEEBROOK_ENV=development
APP=server/app/build/install/seebrook/bin/seebrook
INVITE=$($APP dev-invite "Unit 07" owner07@example.test | tail -1)   # invitation token
TOKEN=$($APP dev-token owner07@example.test)                         # signed-in owner

curl -s localhost:8088/v1/invitations/accept -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d "{\"token\":\"$INVITE\"}"
curl -s localhost:8088/v1/me/properties -H "Authorization: Bearer $TOKEN"     # only Unit 07
```

### Admin commands

Operators run audited admin commands with the same executable (ED-31). Set the same `SEEBROOK_*` variables as the server, then:

```bash
seebrook bootstrap-root --email <email>    # once: create the first Root
seebrook grant-role --operator <email> --user <email> --role hoa_admin --hoa channel-harbor --reason "<why>"
seebrook revoke-role --operator <email> --user <email> --role hoa_admin --hoa channel-harbor --reason "<why>"
```

Run `seebrook help` for every command.

## Repository layout

```
.
├── AGENTS.md / CLAUDE.md   Contributor and agent instructions
├── docs/                   Product handoff, requirements, decisions
│   └── archive/            Original handoff package (reference only)
├── assets/
│   └── hoas/<hoa>/         HOA-specific source assets (e.g. logos)
├── shared/                 Kotlin Multiplatform: API client, DTOs, input checks (ED-08, ED-15, ED-17)
├── server/                 Kotlin/Ktor backend — one deployable (ED-13)
│   ├── app/                Entry point, HTTP routes, admin CLI
│   ├── platform/           Configuration, DB access, token verification, roles, audit
│   ├── db/                 Flyway migrations and generated jOOQ classes (ED-21)
│   ├── tenant/ identity/ property/   Services built so far (ED-13)
│   └── <service>/          ledger, assessment, payment, document (later milestones)
├── mobile-ios/             SwiftUI app (built first, ED-16)
├── mobile-android/         Jetpack Compose app (after iOS)
├── webapp/                 Reserved: future web client/admin portal (not in pilot scope)
├── infra/                  Local dev (Docker Compose) and deployment configuration
├── scripts/                Developer scripts (dev.sh)
└── .github/workflows/      CI/CD
```

`shared/`, `server/` and `mobile-android/` form one Gradle build rooted at the repository root. `mobile-ios/` is an Xcode project that consumes `shared/` as an XCFramework. Folders are created as the milestones that need them are built.

## Branching

`feature/*` → PR → `develop` → production release PR → `master` → CI/CD → production. Never commit directly to `develop` or `master` (ED-18). GitHub branch protection is not available on the current plan, so local hooks in `.githooks/` enforce this once enabled (ED-27).
