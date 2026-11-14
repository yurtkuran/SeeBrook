# Seebrook

HOA mobile app — membership, document library, dues and payments. Pilot: Channel Harbor HOA (32 units).

Start with [`docs/README.md`](docs/README.md) for the product handoff and [`docs/ENGINEERING_DECISIONS.md`](docs/ENGINEERING_DECISIONS.md) for engineering choices. Contributor and agent rules are in [`AGENTS.md`](AGENTS.md).

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
│   ├── platform/           Audit, outbox, authorization context, DB access
│   └── <service>/          tenant, identity, property, ledger, assessment, payment, document
├── mobile-ios/             SwiftUI app (built first, ED-16)
├── mobile-android/         Jetpack Compose app (after iOS)
├── webapp/                 Reserved: future web client/admin portal (not in pilot scope)
├── infra/                  Local dev (Docker Compose) and deployment configuration
└── .github/workflows/      CI/CD
```

`shared/`, `server/` and `mobile-android/` form one Gradle build rooted at the repository root. `mobile-ios/` is an Xcode project that consumes `shared/` as an XCFramework. Folders are created as the milestones that need them are built.

## Branching

`feature/*` → PR → `develop` → production release PR → `master` → CI/CD → production. Never commit directly to `develop` or `master` (ED-18).
