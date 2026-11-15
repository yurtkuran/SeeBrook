# Seebrook

HOA mobile app — membership, document library, dues and payments. Pilot: Channel Harbor HOA (32 units).

Start with [`docs/README.md`](docs/README.md) for the product handoff and [`docs/ENGINEERING_DECISIONS.md`](docs/ENGINEERING_DECISIONS.md) for engineering choices. Contributor and agent rules are in [`AGENTS.md`](AGENTS.md).

## Quick start

Prerequisites: Eclipse Temurin JDK 21, Docker.

```bash
git config core.hooksPath .githooks   # once per clone: blocks direct commits/pushes to develop and master

scripts/dev.sh            # start PostgreSQL, Mailpit, S3Mock and ClamAV; migrate; load synthetic data; serve on :8088
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

Once a document is published (see Admin commands below), the same owner can browse and download it:

```bash
HOA=$(curl -s localhost:8088/v1/me/properties -H "Authorization: Bearer $TOKEN" | sed -E 's/.*"hoaId":"([^"]+)".*/\1/')
curl -s "localhost:8088/v1/hoas/$HOA/documents?q=bylaws&sort=title" -H "Authorization: Bearer $TOKEN"
curl -s -X POST localhost:8088/v1/hoas/$HOA/documents/<documentId>/download-link -H "Authorization: Bearer $TOKEN"
```

ED-39 lists the search, sort and paging options. Download links last 5 minutes and every link issued is audited.

### Admin commands

Operators run audited admin commands with the same executable (ED-31). Set the same `SEEBROOK_*` variables as the server, then:

```bash
seebrook bootstrap-root --email <email>    # once: create the first Root
seebrook grant-role --operator <email> --user <email> --role hoa_admin --hoa channel-harbor --reason "<why>"
seebrook revoke-role --operator <email> --user <email> --role hoa_admin --hoa channel-harbor --reason "<why>"
seebrook import-owners --operator <email> --hoa channel-harbor --file owners.csv               # dry run
seebrook import-owners --operator <email> --hoa channel-harbor --file owners.csv --commit --reason "<why>"
seebrook send-invitations --operator <email> --hoa channel-harbor [--unit "Unit 07"]          # dry run
seebrook send-invitations --operator <email> --hoa channel-harbor [--unit "Unit 07"] --commit
seebrook list-invitations --operator <email> --hoa channel-harbor
seebrook resend-invitation --operator <email> --hoa channel-harbor --unit "Unit 07" --email <owner>
seebrook revoke-invitation --operator <email> --hoa channel-harbor --unit "Unit 07" --email <owner> --reason "<why>"
seebrook transfer-ownership --operator <email> --hoa channel-harbor --unit "Unit 07" --to <buyer>[,<co-buyer>]   # dry run
seebrook transfer-ownership --operator <email> --hoa channel-harbor --unit "Unit 07" --to <buyer> --commit --reason "<why>"
seebrook create-category --operator <email> --hoa channel-harbor --name "Governing documents"
seebrook upload-document --operator <email> --hoa channel-harbor --category "Governing documents" \
  --title "Bylaws" --description "Current bylaws" --file bylaws.pdf [--tags bylaws,rules] [--document-date 2026-03-01]
seebrook list-documents --operator <email> --hoa channel-harbor
```

Uploaded PDFs are checked at once, kept in quarantine, scanned by ClamAV in the background and published only when clean (ED-38). Locally, ClamAV takes about a minute to load its signatures after `scripts/dev.sh` starts it.

The server sends queued invitation emails in the background (`seebrook run-jobs` sends them without a server). Locally, every email is caught by Mailpit — open http://localhost:8025 to read it and copy the invitation code; nothing is sent to real addresses. In production, emails stay queued until email is configured (ED-35, ED-36).

A synthetic example roster is in `server/app/src/main/resources/dev-fixtures/channel-harbor/owners.csv` (ED-34 describes the format).

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
│   ├── platform/           Configuration, DB access, token verification, roles, audit, jobs, email, storage, scanning
│   ├── db/                 Flyway migrations and generated jOOQ classes (ED-21)
│   ├── tenant/ identity/ property/ document/   Services built so far (ED-13)
│   └── <service>/          ledger, assessment, payment (later milestones)
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
