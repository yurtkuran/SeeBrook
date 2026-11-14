# Engineering decisions (living record)

Engineering choices made during the build, with rationale and alternatives considered.
This complements — and never overrides — the approved product constraints in
`REQUIREMENTS.md` and `DECISIONS.md` (GA-01–GA-24).

**Rules for this file**
- Add an entry whenever an engineering decision is made or changed; update the change log.
- Never delete or rewrite a past decision. To change one, mark it `Superseded by ED-xx` and add a new entry.
- Statuses: `Proposed` (not yet confirmed), `Accepted`, `Superseded`, `Deprecated`.
- IDs are `ED-xx`, sequential. Do not reuse IDs or invent product decision IDs.

## Summary

| ID | Decision | Status | Date |
|---|---|---|---|
| ED-01 | Backend language: Kotlin on JDK 21 | Accepted | 2026-11-13 |
| ED-02 | Web framework: Ktor | Accepted | 2026-11-13 |
| ED-03 | Database access: jOOQ on PostgreSQL | Accepted | 2026-11-13 |
| ED-04 | Schema migrations: Flyway (plain SQL) | Accepted | 2026-11-13 |
| ED-05 | Money representation: `BigDecimal` value class, `NUMERIC` columns | Accepted | 2026-11-13 |
| ED-06 | Background jobs: DB outbox + in-process coroutine worker | Accepted | 2026-11-13 |
| ED-07 | Testing: JUnit 5, Kotest assertions, Testcontainers PostgreSQL | Accepted | 2026-11-13 |
| ED-08 | Shared module of pure Kotlin types (KMP-ready) | Accepted | 2026-11-13 |
| ED-09 | Build: Gradle with Kotlin DSL | Accepted | 2026-11-13 |
| ED-10 | CI: GitHub Actions | Accepted | 2026-11-13 |
| ED-11 | Development data: synthetic 32-unit Channel Harbor fixtures | Accepted | 2026-11-13 |
| ED-12 | Branching: feature branches off `master`; no direct commits | Superseded by ED-18 | 2026-11-13 |
| ED-13 | Logical services (modular monolith) in one deployable backend | Accepted | 2026-11-13 |
| ED-14 | Action-style use cases as the single entry point for API and CLI | Accepted | 2026-11-13 |
| ED-15 | Mobile: KMP shared logic; native UI (SwiftUI on iOS, Jetpack Compose on Android) | Accepted | 2026-11-14 |
| ED-16 | Mobile sequencing: iOS app first, Android after iOS is complete | Accepted | 2026-11-14 |
| ED-17 | Business logic lives in the backend; clients are thin; exceptions documented | Accepted | 2026-11-14 |
| ED-18 | Git flow: feature → `develop` → `master` → production | Accepted | 2026-11-14 |
| ED-19 | Repository layout: single Gradle build at root; `docs/` at root | Accepted | 2026-11-14 |
| ED-20 | JDK distribution: Eclipse Temurin 21 everywhere | Accepted | 2026-11-14 |
| ED-21 | Database module: central migrations and jOOQ codegen from SQL scripts | Accepted | 2026-11-14 |
| ED-22 | PostgreSQL 18; local environment via Docker Compose and `scripts/dev.sh` | Accepted | 2026-11-14 |
| ED-23 | Configuration via environment variables; explicit `SEEBROOK_ENV` | Accepted | 2026-11-14 |
| ED-24 | Shared module starts JVM-only; iOS targets added at iOS project setup | Accepted | 2026-11-14 |
| ED-25 | Dependency versions: version catalog, stable releases at least two weeks old | Accepted | 2026-11-14 |
| ED-26 | Record identifiers: UUIDv7 primary keys; HOA-scoped composite references | Accepted | 2026-11-14 |

## Pending decisions

| Topic | Needed by | Current default / notes |
|---|---|---|
| Hosting provider | M1 (before first deploy) | Low-cost managed PostgreSQL + single service; escalate only material cost/security differences |
| Identity / auth provider — build vs buy | M1 | Must support verified email, invitations, Apple/Google sign-in, Root MFA (GA-04). Options: managed (Firebase Auth, Auth0, Clerk, Supabase Auth), self-hosted Keycloak, or in-house. Short comparison to be presented at M1 start |
| Transactional email provider | M1 / M2 (invitations) | e.g. Postmark, SES, Resend; send from `seebrook.cc` with SPF/DKIM/DMARC |
| Account deletion vs financial record retention | Before App Store submission | App Store requires in-app account deletion; financial history must be retained. Proposed: delete/anonymize identity and contact data, retain ledger and audit records. Owner approval + legal check — user-facing/legal |
| Privacy policy and terms of service | Before App Store submission | Required by the App Store; decide author and publisher (Seebrook vs HOA) — legal |
| Legal entity / account ownership | Before developer accounts and processor setup | Who owns developer accounts, domain, platform and processor relationship (individual, company or HOA) — owner to decide |
| Admin operators and Root holders | M2 | Who runs backend admin commands for the pilot (owner vs board member); determines CLI authentication and Root vs HOA Admin assignment — owner to decide |
| Future web client (`webapp/`) | After pilot | Folder reserved; not in pilot scope. Likely the financial administration portal required before a second HOA |
| Private object storage provider | M3 | S3-compatible, private bucket, signed URLs; usually follows hosting choice |
| Malware scanning approach | M3 | e.g. ClamAV in-process or provider service (GA-12) |
| Payment processor | M5 | Short comparison: ACH/cards/recurring/webhooks/per-HOA merchant (see `OPEN_QUESTIONS.md`) |
| Pilot access for Android owners | Before M7 | iOS ships first (ED-16). Decide whether the pilot starts with iOS owners only or waits for Android — user-facing, owner to decide |
| Apple / Google developer accounts | Before first TestFlight build | Apple Developer Program ($99/yr) and Google Play ($25 one-time), ideally under an organization |

---

## ED-01 — Backend language: Kotlin on JDK 21
- **Status:** Accepted · **Date:** 2026-11-13 · **Decided by:** project owner
- **Context:** `ARCHITECTURE.md` listed Kotlin/JVM as a candidate but no stack was selected. Owner is experienced in TypeScript and Kotlin.
- **Decision:** Kotlin on JDK 21 (LTS).
- **Rationale:** (1) strong static typing for financial and authorization code; (2) coroutines for concurrency and background processing; (3) potential code reuse with Kotlin Multiplatform mobile clients.
- **Alternatives considered:**
  - TypeScript (NestJS/Fastify) — owner's second choice; fast and widely used, but weaker numeric safety for money.
  - Python/Django — free admin UI and mature migrations; rejected on owner preference and dynamic typing.
  - C#/.NET, Go, Ruby on Rails — viable but no owner familiarity advantage; Go lacks native decimal and admin tooling.
- **Consequences:** JVM tooling (Gradle, slower builds); good library maturity for payments and databases.

## ED-02 — Web framework: Ktor
- **Status:** Accepted · **Date:** 2026-11-13
- **Decision:** Ktor for the HTTP API.
- **Rationale:** Coroutine-native; uses `kotlinx.serialization`, which is KMP-compatible; lighter than Spring for a single pilot service; explicit wiring makes authorization and transaction boundaries visible.
- **Alternatives considered:** Spring Boot — stronger built-in security and transaction management, but heavier, more conventions, and less coroutine-first. Revisit if Ktor's security/auth integration becomes a burden.
- **Consequences:** Authorization, request validation and transaction handling are implemented explicitly by us; must be covered by tests (cross-tenant denial).

## ED-03 — Database access: jOOQ on PostgreSQL
- **Status:** Accepted · **Date:** 2026-11-13
- **Decision:** PostgreSQL as the single relational database; jOOQ with code generated from the migrated schema.
- **Rationale:** Type-safe SQL; explicit control of transactions, `SELECT … FOR UPDATE` locking and constraints for ledger, import and webhook idempotency (GA-14, GA-17, GA-21).
- **Alternatives considered:** Exposed — more idiomatic Kotlin but historically more API churn and less explicit SQL control. Spring Data/JPA — not used with Ktor; ORM magic is undesirable for financial writes.
- **Consequences:** Code generation step in the build (run against migrated schema, e.g. via Testcontainers).

## ED-04 — Schema migrations: Flyway
- **Status:** Accepted · **Date:** 2026-11-13
- **Decision:** Versioned plain-SQL Flyway migrations, applied on startup locally and as a deploy step in production.
- **Rationale:** Reviewable SQL; supports constraints, RLS policies and indexes directly.
- **Alternatives considered:** Liquibase — more features than needed.
- **Consequences:** Migrations are forward-only; corrections are new migrations.

## ED-05 — Money representation
- **Status:** Accepted · **Date:** 2026-11-13
- **Decision:** A `Money` value class wrapping `BigDecimal` (scale 2, USD for the pilot) with explicit currency; stored as `NUMERIC(12,2)` plus currency code.
- **Rationale:** No floating-point error; supports dues/fee separation (GA-22) and balance invariants (GA-21).
- **Alternatives considered:** Integer cents (`Long`) — also safe; `BigDecimal` chosen for clearer domain code and direct `NUMERIC` mapping.
- **Consequences:** Rounding mode must be explicit wherever division or fee calculation occurs.

## ED-06 — Background jobs: DB outbox + coroutine worker
- **Status:** Accepted · **Date:** 2026-11-13
- **Decision:** Durable `outbox_job` table written in the same transaction as the triggering change; an in-process coroutine worker claims jobs with `SELECT … FOR UPDATE SKIP LOCKED`, with retries and backoff.
- **Rationale:** Durable, idempotent follow-ups (recurring-charge cancellation, notification and reconciliation retries — GA-08, GA-17, GA-18) without a message broker, per `AGENTS.md`.
- **Alternatives considered:** Message broker (RabbitMQ/Kafka/SQS) — unnecessary for the pilot. Separate worker process — possible later using the same table.
- **Consequences:** Job handlers must be idempotent; worker runs in the API service for the pilot.

## ED-07 — Testing
- **Status:** Accepted · **Date:** 2026-11-13
- **Decision:** JUnit 5 runner, Kotest assertions, Testcontainers PostgreSQL for integration tests.
- **Rationale:** Tenant isolation, RLS, atomic imports and idempotency must be tested against real PostgreSQL, not an in-memory substitute.
- **Consequences:** Docker required locally and in CI.

## ED-08 — Shared module (KMP-ready)
- **Status:** Accepted, amended by ED-17 · **Date:** 2026-11-13
- **Decision:** A `shared` Gradle module containing only pure Kotlin types (DTOs, enums, validation) using `kotlinx.serialization`; no JVM-only or framework dependencies.
- **Rationale:** Keeps the option of Kotlin Multiplatform mobile reuse open at negligible cost.
- **Consequences:** Converting to a KMP module later is a build change, not a rewrite. Not a commitment to KMP mobile.

## ED-09 — Build tooling
- **Status:** Accepted · **Date:** 2026-11-13
- **Decision:** Gradle with Kotlin DSL and a version catalog; Gradle wrapper committed.
- **Consequences:** One-command local build/test.

## ED-10 — CI: GitHub Actions
- **Status:** Accepted · **Date:** 2026-11-13
- **Decision:** GitHub Actions running build, migrations and the full test suite on pushes and pull requests.
- **Rationale:** Repository is hosted on GitHub; no extra service needed.

## ED-11 — Development data
- **Status:** Accepted · **Date:** 2026-11-13
- **Decision:** Synthetic fixtures for 32 Channel Harbor units, owners, documents and transactions. Real owner data, PDFs and spreadsheets are used only when securely provided, and never committed.
- **Rationale:** `OPEN_QUESTIONS.md`; protects personal and financial data.

## ED-12 — Branching
- **Status:** Superseded by ED-18 · **Date:** 2026-11-13
- **Decision:** One feature branch per milestone/task off `master` (e.g. `feature/m0-foundation`); no direct commits to `master` or `develop`; commits and pushes only with owner authorization.
- **Rationale:** `AGENTS.md` git workflow.

## ED-13 — Logical services in one deployable backend
- **Status:** Accepted, amended by ED-21 · **Date:** 2026-11-13 · **Decided by:** project owner
- **Context:** Owner prefers an API organized as logical services (identity, property, payment, …). `AGENTS.md` prefers one service and no speculative scaling; several requirements need atomic changes across domains (ownership transfer, GA-08; ledger invariants, GA-21).
- **Decision:** One deployable Ktor backend made of logical services (a modular monolith). Each service is a Gradle module that owns its own PostgreSQL schema and exposes a public Kotlin interface. Other modules use only that interface — never another module's tables — enforced through Gradle module dependencies. Cross-service workflows that must be atomic run in a single database transaction.
- **Service map (initial):**
  ```
  TenantService      ProvisionHoa, GetHoa, ConfigureMerchant
  IdentityService    InviteHomeowner, AcceptInvitation, ResendInvitation, RevokeInvitation,
                     GrantRole, RevokeRole, UpdateProfile, GetMe
  PropertyService    CreateProperty, ImportOwners, TransferOwnership,
                     GetMyProperties, GetProperty
  LedgerService      GetBalance, GetTransactions, PostEntry (internal only),
                     ImportTransactions (dry run + commit), CorrectTransaction
  AssessmentService  CreateAssessment, VoidAssessment, GetAssessments   (posts to Ledger)
  PaymentService     QuotePayment, InitiatePayment, HandleProcessorEvent, ReconcilePayment,
                     RecordReversal, SavePaymentMethod,
                     Create/Pause/Resume/CancelRecurringPlan
  DocumentService    UploadDocument, ReplaceDocument, ArchiveDocument, RestoreDocument,
                     SearchDocuments, IssueDocumentReadUrl, ManageCategories
  Platform (shared)  Audit, Outbox jobs, Notifications, Authorization context
  ```
  The map grows as milestones are built; new actions do not need a new ED entry unless they change a boundary.
- **Boundary rules (derived from approved requirements):**
  - **LedgerService** is the only writer of ledger entries and the sole owner of balance invariants; assessments, imports, payments and reversals post through it.
  - **Identity** owns users, authentication, roles and invitations. **Property** owns properties and ownership. Membership access is *derived* from active ownership (GA-10); there is no approval workflow (ownership is preverified, GA-05).
  - Ownership changes go through a single atomic `TransferOwnership` action (GA-07, GA-08, GA-09), not separate remove/assign calls.
  - Documents are never deleted — archive/restore/replace with retained versions (GA-11).
  - `VoidAssessment` posts a linked compensating entry; financial history is never deleted (GA-15, GA-19).
- **Alternatives considered:**
  - Separately deployed microservices — rejected: distributed transactions/sagas for ownership transfer and ledger consistency, plus extra ops, for a 32-unit pilot; conflicts with `AGENTS.md`.
  - Unstructured single module — rejected: boundaries erode and later extraction becomes hard.
  - Merging AssessmentService into LedgerService — viable since assessments mostly arrive via CSV import; kept separate for clarity. Revisit if it stays thin.
- **Consequences:** Modules can be extracted into separate services later if ever needed, but that would require replacing in-transaction workflows with outbox-driven ones.

## ED-14 — Action-style use cases
- **Status:** Accepted · **Date:** 2026-11-13 · **Decided by:** project owner
- **Decision:** Each operation is a named use-case (action) class — e.g. `InitiatePayment`, `TransferOwnership` — that receives an explicit actor/authorization context. Every action performs HOA/property authorization, idempotency (where applicable) and audit before or as part of its work. The HTTP API and the admin CLI are thin adapters that call the same actions.
- **Rationale:** A single, hard-to-bypass place for authorization (GA-06); admin CLI tasks get the same checks and audit as the API; idempotency and audit attach per action.
- **HTTP mapping:** Resource-style routes (e.g. `POST /properties/{id}/payments`) mapped to actions. This is an adapter detail and may change without a new ED entry.
- **Alternatives considered:** Controller-centric code with logic in route handlers — rejected; authorization and audit would be duplicated between API and CLI.

## ED-15 — Mobile: KMP shared logic with native UI
- **Status:** Accepted, amended by ED-17 · **Date:** 2026-11-14 · **Decided by:** project owner
- **Context:** `PRODUCT.md` prefers native iOS/Android with Kotlin Multiplatform as a candidate. Options compared: fully native, KMP shared logic + native UI, KMP + Compose Multiplatform (shared UI), React Native/Expo, Flutter.
- **Decision:**
  - **Shared (KMP, Kotlin):** API client (Ktor client), DTOs and validation (building on the backend `shared` module, ED-08), repositories/data layer, auth session and token handling, money and date formatting. Platform-specific pieces (Keychain/Keystore secure storage, etc.) via `expect`/`actual`.
  - **iOS UI:** Swift and SwiftUI, consuming the shared module as an XCFramework.
  - **Android UI:** Kotlin and Jetpack Compose.
  - **Not shared (initial):** view models / screen state stay native on each platform (SwiftUI observable models, Android `ViewModel`) to keep Swift interop simple. Revisit if duplication becomes significant.
  - **Platform SDKs used natively in each UI layer:** payment processor SDK (processor-hosted tokenization, GA-24), Sign in with Apple / Google, biometrics, PDF viewing (PDFKit / Android renderer) and the system share sheet, push notifications.
  - **Swift interop:** use a Kotlin→Swift interop tool (e.g. SKIE) so suspend functions and Flows appear as Swift `async`/`await` and `AsyncSequence`. Exact tool chosen at mobile project setup and recorded then.
- **Rationale:** Platform-standard UI and accessibility on each OS; direct use of native payment, identity and document SDKs removes the main interop risk of shared UI; business and API logic written once in Kotlin and kept consistent with the backend. This makes ED-08's KMP-ready shared module an active dependency of the mobile apps.
- **Alternatives considered:**
  - KMP + Compose Multiplatform — more code sharing, but non-native iOS UI and payment SDK interop risk.
  - React Native/Expo — mature and TypeScript-based, but no reuse with the Kotlin backend.
  - Fully native without KMP — duplicates networking, models and validation.
  - Flutter — new language, no reuse.
- **Consequences:** Swift skills needed for iOS UI; Xcode/macOS required to build iOS; iOS CI needs macOS runners (higher GitHub Actions cost). UI is written twice by design.

## ED-16 — Mobile sequencing: iOS first
- **Status:** Accepted · **Date:** 2026-11-14 · **Decided by:** project owner
- **Decision:** Build and complete the iOS app first; build the Android app after iOS is complete. The KMP shared module is built with Android-compatible targets from the start so Android can be added without restructuring.
- **Rationale:** Focus a small team on one client; shared logic is built and validated once before the second UI is written.
- **Consequences:** Pilot owners with Android devices cannot use the app until the Android app ships — see Pending decisions. Android-specific integrations (Google Sign-In on Android, Firebase push, Android payment SDK) are deferred and must be verified when the Android build starts.

## ED-17 — Business logic in the backend; thin clients
- **Status:** Accepted · **Date:** 2026-11-14 · **Decided by:** project owner
- **Decision:** All business rules live in backend actions (ED-14) and are authoritative there. Mobile clients — including the KMP shared module — only present server data, collect input and call actions. Any client-side logic beyond presentation is an exception that must be justified and listed in the exceptions register below.
- **What stays in the backend (non-exhaustive):** authorization and visibility (which properties, documents and transactions a user may see); balances, due/overdue status, pending estimates and payment allocation; fee calculation and payment quotes (QuotePayment); payment eligibility and limits; recurring schedule dates, including business-day and holiday handling; document search, sort and pagination; ownership, invitation and role rules; outage/suspension state.
- **API design consequences:**
  - Responses carry computed values ready to display (e.g. balance, status, due date, fee and total) rather than raw inputs for the client to calculate.
  - Responses include server-computed permissions/available actions (e.g. `canPay`, `canEditRecurringPlan`, `paymentsSuspended`) so clients enable or disable controls without re-deriving rules.
  - Amounts are sent as exact decimal strings with a currency code; clients format them for display only and never do arithmetic on them.
- **Effect on ED-08 / ED-15:** The KMP shared module contains the API client, DTOs, session/token handling, platform storage wrappers and presentation formatting. "Validation" in the shared module is limited to exception X-01. No balance, fee, allocation, eligibility or authorization logic in shared or client code.
- **Rationale:** One authoritative implementation of financial and access rules (GA-06, GA-21–GA-23); rule changes ship without app-store releases; iOS and Android (built later, ED-16) cannot diverge; clients cannot be modified to bypass rules.
- **Alternatives considered:** Shared business rules in KMP running on both client and server — rejected: clients are untrusted, rules would still have to be enforced server-side, and app-store release lag would cause mismatches.
- **Consequences:** More API round-trips (e.g. fetching a fee quote instead of calculating locally); clients require connectivity for most actions, consistent with the no-offline-cache requirement.

### Client-side logic exceptions register
Each exception must state what it is, why it cannot live solely in the backend, and the safeguard. The backend always re-validates and remains authoritative.

| ID | Exception | Justification | Safeguard |
|---|---|---|---|
| X-01 | Input format checks before submission (required fields, email format, amount is a positive decimal, field lengths) | Immediate feedback; avoids round-trips for obvious typos | Format checks only, defined once in the shared module and reused by the backend so they cannot drift; backend re-validates every request; business limits (e.g. maximum payment) come from the server |
| X-02 | Payment credential entry and tokenization in the processor's native SDK | Required by GA-24 / PCI scope: card and bank details must never reach the backend | Client receives only a processor token; the backend creates and confirms the payment, binds amount/fee/HOA/property, and treats only verified webhooks as settlement (GA-17, GA-23) |
| X-03 | Biometric app unlock | Device convenience feature; runs only on device | Gates only local access to an existing session; the server session and authorization remain authoritative; sensitive actions still require server-side reauthentication (GA-04) |
| X-04 | Session token storage and refresh | Technical requirement of authenticated clients | Tokens in Keychain/Keystore; server controls expiry and revocation (e.g. on ownership transfer, GA-07) |
| X-05 | Presentation formatting (currency, dates, locale) | Display concern, not a business rule | Operates only on server-provided values; no arithmetic on amounts |

## ED-18 — Git flow
- **Status:** Accepted · **Date:** 2026-11-14 · **Decided by:** project owner · **Supersedes:** ED-12
- **Decision:** `feature/*` branches are created from `develop` and merged into `develop` by pull request. Production releases are merged from `develop` into `master` by pull request. Merges to `master` trigger CI/CD deployment to production. No direct commits to `develop` or `master`; commits and pushes only with owner authorization.
- **CI expectations:** Build and full test suite on every PR to `develop` and `master`; deployment runs only from `master`. iOS jobs run only when `mobile-ios/` or `shared/` change (macOS runner cost, ED-15).
- **Recommended repository settings (owner to apply on GitHub):** protect `develop` and `master` (require PR, passing CI, no force-push, no deletion).
- **Alternatives considered:** Trunk-based (feature → `master`) — simpler but gives no staging point between integration and production release.

## ED-19 — Repository layout
- **Status:** Accepted · **Date:** 2026-11-14
- **Context:** Initial owner-created layout had double-nested docs (`documentation/docs/`) while `AGENTS.md` and the handoff README referenced `docs/`; `.DS_Store` files were tracked; no `.gitignore`.
- **Decision:**
  - Product and engineering docs live in root `docs/`; the original handoff zip is preserved, unchanged, in `docs/archive/`.
  - One Gradle build rooted at the repository root containing `shared/` (KMP), `server/` (backend modules per ED-13: `app`, `platform`, and one module per service) and, later, `mobile-android/`.
  - `mobile-ios/` is an Xcode project consuming `shared/` as an XCFramework.
  - HOA-specific source assets under `assets/hoas/<hoa-slug>/` (e.g. Channel Harbor logo). In production, per-HOA branding is served from HOA configuration/object storage, not bundled per HOA.
  - `infra/` for local Docker Compose and deployment configuration; `.github/workflows/` for CI/CD.
  - `webapp/` reserved for a future web client (not in pilot scope).
  - Root `.gitignore` excludes OS/IDE/build files, secrets and real pilot data.
  - Folders are created when the milestone that needs them is built; the layout is documented in the root `README.md`.
- **Rationale:** A single Gradle build lets the backend and Android consume `shared/` directly as a project dependency; root `docs/` matches existing references and convention.
- **Alternatives considered:** Separate Gradle builds per folder — requires publishing `shared/` or composite builds; unnecessary for one repo.

## ED-20 — JDK distribution: Eclipse Temurin 21
- **Status:** Accepted · **Date:** 2026-11-14 · **Decided by:** project owner
- **Decision:** Eclipse Temurin 21 (LTS) for local development, CI (`actions/setup-java` with `distribution: temurin`) and production container images (`eclipse-temurin:21`). Pinned via the Gradle toolchain so builds use the same vendor and version.
- **Rationale:** Vendor-neutral, TCK-certified OpenJDK build; common default for GitHub Actions; hosting provider not yet chosen. Consistency across environments matters more than the vendor.
- **Alternatives considered:** Amazon Corretto 21 — equivalent; preferable if hosting lands on AWS. Switching is a small, reversible build/CI change.

## ED-21 — Database module: central migrations and jOOQ codegen
- **Status:** Accepted · **Date:** 2026-11-14 · **Amends:** ED-13
- **Context:** ED-13 gives each service its own PostgreSQL schema. Foreign keys cross schemas (e.g. `property.property` → `tenant.hoa`), so migrations need one global order, and jOOQ code generation needs the whole schema.
- **Decision:**
  - All Flyway migrations live in one module, `server:db` (`src/main/resources/db/migration`), with one sequential version series (`V1__tenant_hoa.sql`, `V2__property_property.sql`, …). Each file is prefixed with the owning service's schema name.
  - Schema ownership from ED-13 is unchanged: each service reads and writes only its own schema's generated classes (`cc.seebrook.db.generated.<schema>`). An automated check enforcing this is added when the first service modules are created (M1).
  - jOOQ classes are generated at build time with `DDLDatabase`, which parses the Flyway scripts — no database is needed to build. Generated code is not committed.
  - PostgreSQL-specific statements jOOQ cannot simulate (regex checks, triggers, functions, later RLS policies) are wrapped in `-- [jooq ignore start]` / `-- [jooq ignore stop]`. They still run in PostgreSQL and are covered by integration tests.
  - Strings that need unique indexes use `varchar(n)` rather than `text` (jOOQ simulates DDL in H2, which cannot index `text`). This also bounds input length at the database.
- **Known gotchas:** a table with the same name as its schema generates as `PROPERTY_`; import it with an alias (`import …Tables.PROPERTY_ as PROPERTY`). Generated column defaults reflect H2 (e.g. `RANDOM_UUID()`); always let PostgreSQL apply defaults by omitting those columns in inserts.
- **Alternatives considered:** Codegen against a live PostgreSQL via Testcontainers during the build — exact, but slower and needs Docker for every build; revisit if DDL simulation becomes limiting. Per-module migration folders — rejected because version numbers would collide and cross-schema ordering becomes fragile.

## ED-22 — PostgreSQL 18 and local environment
- **Status:** Accepted · **Date:** 2026-11-14
- **Decision:**
  - PostgreSQL 18 (`postgres:18-alpine`) for local development and Testcontainers tests; production hosting must provide 18.
  - Local database via `infra/local/docker-compose.yml`, bound to `127.0.0.1:55432` (avoids clashing with any local PostgreSQL). Local-only credentials.
  - `scripts/dev.sh` is the one-command startup: start the database, build, migrate, load synthetic data, serve on `http://localhost:8088` (8080 is commonly taken). `scripts/dev.sh down` stops the database; `reset` also deletes its data.
- **Alternatives considered:** PostgreSQL 17 — also fine; 18 chosen for the longer support window. Running the app in Docker Compose too — slower edit/run loop; unnecessary for now.

## ED-23 — Configuration and environments
- **Status:** Accepted · **Date:** 2026-11-14
- **Decision:**
  - Configuration comes from environment variables: `SEEBROOK_ENV` (`development` | `test` | `production`, **required, no default**), `SEEBROOK_DB_URL`, `SEEBROOK_DB_USER`, `SEEBROOK_DB_PASSWORD`, `SEEBROOK_HTTP_PORT` (default 8080), `SEEBROOK_MIGRATE_ON_START`.
  - Local database defaults apply only in `development`. Outside development, missing database settings stop startup.
  - Migrations run on start by default only in `development`; in production they run as an explicit deploy step (`seebrook migrate`).
  - The database password is never included in logs or `toString()`; HTTP request logs record method, path and status only.
  - One executable with commands: `serve` (default), `migrate`, `seed-dev`. `seed-dev` loads synthetic Channel Harbor data (ED-11), is idempotent, and refuses to run in production. It is a development tool, not an administrative action — real provisioning and imports are audited actions (ED-14).
- **Rationale:** An explicit environment prevents a production deployment from silently using development defaults or loading sample data.

## ED-24 — Shared module starts JVM-only
- **Status:** Accepted · **Date:** 2026-11-14
- **Decision:** `shared/` uses the Kotlin Multiplatform plugin with a JVM target only for now; it is consumed by the backend. iOS targets (`iosArm64`, `iosSimulatorArm64`) and the XCFramework build are added when the iOS project is set up. Android can consume the JVM target until an Android target is added (ED-16).
- **Rationale:** Avoids downloading the Kotlin/Native toolchain and building iOS binaries on every backend build before any iOS code exists; adding targets later is a build change, not a restructuring.

## ED-25 — Dependency versions
- **Status:** Accepted · **Date:** 2026-11-14
- **Decision:** All library and plugin versions are declared in `gradle/libs.versions.toml`. Use stable releases (no alpha/beta/RC/milestone) published at least two weeks before adoption. GitHub Actions are pinned to exact release tags.
- **Initial versions (M0):** Kotlin 2.4.21, Ktor 3.6.1, jOOQ 3.21.9, Flyway 13.12.0, PostgreSQL JDBC 42.7.14, HikariCP 7.1.0, kotlinx.serialization 1.12.0, Logback 1.6.6, JUnit 6.1.3, Kotest 6.2.6, Testcontainers 2.0.5, Gradle 9.8.2.
- **Rationale:** Reproducible builds; a short delay avoids adopting releases with undiscovered regressions.

## ED-26 — Record identifiers
- **Status:** Accepted · **Date:** 2026-11-14 · **Decided by:** project owner
- **Decision:**
  1. **Primary keys are UUIDv7** (`uuid PRIMARY KEY DEFAULT uuidv7()`, native in PostgreSQL 18). The application may generate the ID itself when it must know it before insert (e.g. linking an outbox job or idempotency record in the same transaction).
  2. **IDs are not a security boundary.** Unguessable IDs are defense in depth only; every request is authorized server-side (GA-06).
  3. **Business identifiers are separate columns** with their own scoped unique constraints, never reused as primary keys: unit label per HOA, external import property/transaction IDs (GA-14, GA-20), processor payment/event IDs per merchant (GA-17).
  4. **Human-facing references are separate** (e.g. receipt `CH-2026-000123`), designed with the feature that needs them; users never see raw UUIDs.
  5. **Secrets are never IDs.** Invitation and similar tokens are separate high-entropy random values, stored hashed; a UUIDv7 (which embeds a timestamp) is never used as a token.
  6. **HOA-scoped references.** Tenant-owned tables carry `hoa_id`. Parent tables expose `UNIQUE (hoa_id, id)`, and children reference them with a composite foreign key — e.g. `FOREIGN KEY (hoa_id, property_id) REFERENCES property.property (hoa_id, id)` — so the database rejects any row linking to another HOA's record (GA-06 defense in depth). `tenant.hoa` itself is the root and is referenced by `id`.
  7. **API representation:** IDs travel as canonical UUID strings.
- **Rationale:** Unguessable and generatable anywhere like UUIDv4, but time-ordered for compact, insert-friendly indexes on large tables (ledger, audit, events); composite references make cross-tenant data corruption impossible at the database level.
- **Alternatives considered:** `bigint` identity — guessable, reveals counts, database-only generation. UUIDv4 — random index writes, not sortable. ULID/Snowflake — non-standard types and custom generation.
- **Consequences:** A UUIDv7 reveals its creation time to anyone who sees it (acceptable; records already carry dates). Ordering is guaranteed only within one database session. One extra unique index per HOA-scoped parent table.

---

## Change log
- 2026-11-13 — Created. Recorded ED-01–ED-12 and pending decisions.
- 2026-11-13 — Added ED-13 (logical services in one deployable, service map, boundary rules) and ED-14 (action-style use cases). The owner's earlier service map was an illustrative example, not a decision.
- 2026-11-14 — Added ED-15 (KMP shared logic, native SwiftUI/Compose UI) and ED-16 (iOS first). Resolved "Mobile framework" pending item; added pending items for Android pilot access and developer accounts.
- 2026-11-14 — Added ED-17 (backend-owned business logic, thin clients) with client-side exceptions register X-01–X-05. Marked ED-08 and ED-15 as amended by ED-17.
- 2026-11-14 — Added ED-18 (git flow, supersedes ED-12) and ED-19 (repository layout; docs moved from `documentation/docs/` to `docs/`). Added pending items: auth build-vs-buy, email provider, account deletion vs retention, privacy policy/terms, legal entity, admin operators, future web client.
- 2026-11-14 — Added ED-20 (Temurin 21 JDK).
- 2026-11-14 — M0: added ED-21 (central migrations and jOOQ codegen; amends ED-13), ED-22 (PostgreSQL 18, local environment), ED-23 (configuration and environments), ED-24 (JVM-only shared module for now), ED-25 (dependency version policy).
- 2026-11-14 — Added ED-26 (UUIDv7 primary keys, separate business/human identifiers, HOA-scoped composite references).
