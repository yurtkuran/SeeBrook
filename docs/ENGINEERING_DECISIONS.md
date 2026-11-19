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
| ED-06 | Background jobs: DB outbox + in-process coroutine worker | Accepted, amended by ED-35 | 2026-11-13 |
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
| ED-27 | Protected branches enforced by local git hooks (no GitHub branch protection) | Accepted | 2026-11-14 |
| ED-28 | Authentication: Firebase Auth (identity only); authorization in our database; Root MFA deferred | Accepted, amended by ED-29 | 2026-11-14 |
| ED-29 | Sign-in and invitation rules; development tokens instead of the Firebase emulator | Accepted, amended by ED-34 and ED-35 | 2026-11-14 |
| ED-30 | Automated schema-ownership check | Accepted | 2026-11-14 |
| ED-31 | Pilot operators: owner is sole Root and operator; admin commands name their operator | Accepted | 2026-11-14 |
| ED-32 | Roles and permissions | Accepted | 2026-11-14 |
| ED-33 | Append-only audit trail | Accepted | 2026-11-14 |
| ED-34 | Owner CSV import: additive, all-or-nothing, never transfers ownership | Accepted | 2026-11-14 |
| ED-35 | Invitation delivery through the job outbox | Accepted | 2026-11-15 |
| ED-36 | Provider-neutral email gateway; Mailpit locally, Brevo HTTP API for the beta, Postmark or Resend later | Accepted | 2026-11-15 |
| ED-37 | Ownership transfer: set a unit's owners atomically; access ends on the next request | Accepted | 2026-11-15 |
| ED-38 | Document storage, upload checks and ClamAV malware scanning | Accepted | 2026-11-15 |
| ED-39 | Owner document library API | Accepted | 2026-11-15 |
| ED-40 | Document administration: replace, archive, restore, version rollback, categories | Accepted | 2026-11-15 |
| ED-41 | Finance ledger, account mapping and transaction import | Accepted | 2026-11-16 |
| ED-42 | Owner finance view, voids and the register workbook converter | Accepted | 2026-11-18 |
| ED-43 | Payments: provider-neutral interface, Stripe Connect adapter, per-HOA fees, one-time payments | Accepted | 2026-11-19 |
| ED-44 | Online payments in the HOA register: export rows and `Seebrook Ref` matching on import | Accepted | 2026-11-19 |
| ED-45 | Payment receipts and notices; refunds, failed refunds and disputes as linked compensating entries | Accepted | 2026-11-19 |

## Pending decisions

| Topic | Needed by | Current default / notes |
|---|---|---|
| Hosting provider | M1 (before first deploy) | Low-cost managed PostgreSQL + single service; escalate only material cost/security differences |
| Root MFA (Identity Platform upgrade) | Before a second HOA joins the beta | Deferred by owner (GA-04 amendment, ED-28). Upgrade the Firebase project to Identity Platform and enforce TOTP for Root |
| Brevo account and sending domain | Before the first real invitation is sent | Provider chosen by owner (ED-36). Owner creates the Brevo account (under the pending legal entity), authenticates `seebrook.cc` (SPF, DKIM, DMARC) and turns off open/click tracking; then set `SEEBROOK_EMAIL_MODE=brevo`, `SEEBROOK_BREVO_API_KEY` and `SEEBROOK_EMAIL_FROM`, and send a test email. Until then production keeps invitation emails queued |
| Account deletion vs financial record retention | Before App Store submission | App Store requires in-app account deletion; financial history must be retained. Proposed: delete/anonymize identity and contact data, retain ledger and audit records. Owner approval + legal check — user-facing/legal |
| Privacy policy and terms of service | Before App Store submission | Required by the App Store; decide author and publisher (Seebrook vs HOA) — legal |
| Legal entity / account ownership | Before developer accounts, the production Firebase project and processor setup | Who owns developer accounts, domain, Firebase/Google Cloud project, platform and processor relationship (individual, company or HOA) — owner to decide |
| Future web client (`webapp/`) | After pilot | Folder reserved; not in pilot scope. Likely the financial administration portal required before a second HOA |
| Private object storage provider | Before the first real document upload | Any S3-compatible provider works (ED-38); usually follows the hosting choice. Needs a private bucket, keys limited to it, and its own backups (REQUIREMENTS: PDFs are backed up) |
| Stripe platform account and the HOA's connected account | Before the first real payment | Processor chosen by owner (ED-43). Owner creates the Seebrook Stripe platform account (under the pending legal entity) and enables Connect; Channel Harbor connects its own Standard account; create a Connect webhook endpoint at `/v1/webhooks/payments/stripe` on API version `2026-09-30.endive` for `payment_intent.processing`, `.succeeded`, `.payment_failed`, `.canceled`, `refund.created`, `refund.failed`, `charge.dispute.funds_withdrawn` and `charge.dispute.funds_reinstated` (ED-45); set `SEEBROOK_PAYMENTS_MODE=stripe` and the three `SEEBROOK_STRIPE_*` keys; then `configure-payments` |
| Card fee for Channel Harbor | Before card payments are enabled for real | Policy set by owner (ED-43): owner pays the card fee, HOA absorbs ACH, checks carry an HOA convenience fee. Owner confirms the card surcharge is allowed (state law, card network rules, Stripe's requirements) and sets its amount; until then the card fee stays $0 or cards stay off |
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

## ED-27 — Protected branches via local git hooks
- **Status:** Accepted · **Date:** 2026-11-14 · **Decided by:** project owner · **Related:** ED-18
- **Context:** ED-18 recommended GitHub branch protection for `develop` and `master`. The current GitHub plan does not offer branch protection or rulesets for this private repository.
- **Decision:**
  - Versioned hooks in `.githooks/`: `pre-commit` rejects commits on `develop` or `master`; `pre-push` rejects pushes to them. Merges performed on GitHub through pull requests are unaffected.
  - Each clone enables them once with `git config core.hooksPath .githooks` (documented in the README quick start).
  - `AGENTS.md` forbids agents from committing to protected branches or bypassing the hooks with `--no-verify`. CI still runs on every PR and on pushes to `develop`/`master`, so a direct push would at least be tested.
- **Limitations:** Opt-in per clone; does not prevent edits through the GitHub web UI; can be bypassed with `--no-verify` (reserved for genuine emergencies by the owner).
- **Revisit:** Enable GitHub branch protection (require PR and passing "Build and test", block force-push and deletion) if the plan is upgraded (e.g. GitHub Pro) or the repository becomes public; keep the hooks as a local early warning.

## ED-28 — Authentication: Firebase Auth for identity only
- **Status:** Accepted, amended by ED-29 (item 7) · **Date:** 2026-11-14 · **Decided by:** project owner · **Related:** GA-04, GA-05, GA-07, ED-17, ED-26
- **Context:** M1 needs owner sign-in with verified email, email/password, and Apple/Google sign-in. Building password storage, resets, verification and abuse protection in-house is high-risk work for a 32-unit pilot.
- **Decision:**
  1. **Firebase Authentication (basic tier) proves identity only.** Enabled sign-in methods: email/password with email verification, Sign in with Apple, Google. No other Firebase products are enabled. The basic tier needs no billing account; its daily email quotas (verification, password reset) are far above pilot needs.
  2. **Authorization stays in our database.** Invitations (single-use, expiring, bound to email and property — GA-05), roles, HOA membership and property access live in our schema and are checked on every request, so ownership changes revoke access on the next request (GA-07) regardless of token lifetime.
  3. **Our user record is authoritative.** Users have our own UUIDv7 ID (ED-26); the Firebase UID is stored as an external identity (provider + subject), never used as our primary key or foreign key.
  4. **The backend only verifies tokens.** Ktor verifies Firebase ID tokens locally (signature against Google's published keys, issuer, audience = project ID, expiry, verified email). No Firebase Admin SDK is needed for verification. Issuer and audience are configuration, so a different OIDC provider can be added later.
  5. **Sensitive-action reauthentication (GA-04) without MFA:** sensitive actions require a recent sign-in, checked server-side from the token's `auth_time`. Session revocation is server-side: our database disables the user, and Firebase refresh tokens are revoked.
  6. **Root MFA deferred (owner amendment to GA-04):** until a second HOA joins the beta, Root does not require MFA. Interim controls: Root holders kept to the minimum; initial Root operations use secure backend tooling, not the app; Root sign-in uses Apple/Google (with that account's own 2-step verification) or a strong unique password; recent-sign-in check and full audit on every Root action.
  7. **Local development and tests:** the Firebase Auth Emulator for manual runs; automated tests sign tokens with a test key. Emulator/test token acceptance is only possible when `SEEBROOK_ENV` is `development` or `test`.
- **Rationale:** Covers every pilot requirement at no cost, with native iOS and Android SDKs (ED-15, ED-16), standard tokens, and nothing extra to host. Identity Platform is the same service with MFA and enterprise features and can be enabled in place later without user migration.
- **Alternatives considered:** Google Identity Platform from day one (only needed for MFA, now deferred). Keycloak self-hosted in Docker (best standards fit and no vendor, but another service to host and patch, and a browser-sheet login on iOS). Appwrite (a full backend platform; tokens validated by calling its API). Auth0, Clerk, Supabase Auth, AWS Cognito. Building in-house (rejected: highest security risk).
- **Exit path (if leaving Firebase):** export users with `firebase auth:export` (includes password hashes in Firebase's modified scrypt format and the hash parameters); import into a provider that accepts that format, or migrate lazily at next sign-in, or as a last resort require password resets. Apple and Google identities (stable `sub` per app/team) re-link by subject. The backend accepts both issuers during the transition; the iOS app ships an update with the new sign-in SDK behind its small authentication interface. Our user IDs, roles and data do not change.
- **Consequences:** Dependency on Google for sign-in availability. The production Firebase project should be owned by the pending legal entity. A transactional email provider is still needed for invitations.
- **Revisit:** Before a second HOA joins the beta: upgrade to Identity Platform, enforce TOTP MFA for Root, and require a second factor in the token for Root actions.

## ED-29 — Sign-in and invitation rules
- **Status:** Accepted · **Date:** 2026-11-14 · **Amends:** ED-28 item 7 · **Related:** GA-05, GA-06, GA-07
- **Decision:**
  1. **Invitation acceptance requires a verified email equal to the invited email** (case-insensitive). A forwarded or leaked invitation is useless without control of that mailbox. Consequence: an owner using Sign in with Apple's "Hide My Email" must share their real email, or sign in with Google or email/password, to accept.
  2. **Invitation tokens:** 256 random bits, URL-safe, stored only as a SHA-256 hash; valid for 14 days; single use. Reissuing revokes the earlier pending invitation for the same property and email (at most one pending per property and email, enforced by the database). Acceptance is idempotent for the account that accepted it, so a lost response can be retried safely; any other account gets `invitation_no_longer_valid`.
  3. **Acceptance is atomic:** find or create the user, link the sign-in identity, start the ownership (through PropertyService) and mark the invitation used — in one transaction with the invitation row locked. A user who already exists is matched by their linked identity, otherwise by verified email (so the same person signing in with a second provider stays one user).
  4. **Access is checked on every request** from our database: the token's identity is resolved to an active user, and property access requires an active ownership. Ending an ownership or disabling a user takes effect on the next request, even with a still-valid token (GA-07).
  5. **Unknown and unauthorized properties both return 404 `not_found`**, so responses never reveal another owner's or HOA's property (GA-06).
  6. **API paths are versioned (`/v1/...`)**, because installed app versions linger after API changes. Errors use `{"error": "<code>"}` with stable codes.
  7. **Development tokens replace the Firebase Auth Emulator** (amends ED-28 item 7). With `SEEBROOK_AUTH_MODE=development` (the default outside production, refused in production), the server verifies Firebase-shaped HS256 tokens minted by `seebrook dev-token <email>`; `seebrook dev-invite <unit> <email>` issues an invitation for a synthetic unit. Same claim checks as production, no Node.js tooling or Firebase project needed. Automated tests use RS256 tokens from a test key through the production verifier.
- **Configuration added (ED-23):** `SEEBROOK_AUTH_MODE` (`firebase` | `development`), `SEEBROOK_FIREBASE_PROJECT_ID` (required for `firebase`), `SEEBROOK_DEV_AUTH_SECRET` (optional, development only; never logged).
- **Libraries:** Nimbus JOSE + JWT 10.10 for token verification (Google's keys are fetched and cached); Ktor `ktor-server-auth` for bearer authentication.
- **Alternatives considered:** Treating token possession as proof of email (would let Apple relay emails accept, but a forwarded invitation would grant access) — rejected for security. Firebase Auth Emulator — needs Node.js tooling and issues unsigned tokens, so production verification code would not run locally.

## ED-30 — Automated schema-ownership check
- **Status:** Accepted · **Date:** 2026-11-14 · **Fulfils:** ED-21
- **Decision:** A root Gradle task, `checkSchemaOwnership`, runs with every `check`/`build` (and so in CI). It fails if a service module's main code references another schema's generated jOOQ classes: `server:property` may use only `property`, `server:identity` only `identity`. `server:app` is the composition root and may use none; its `devtools/` (development data and tokens) are exempt. New service modules are added to the task's map when created.
- **Cross-service calls:** a service needing another's data or change calls that service's public Kotlin API, passing the current transaction where atomicity is needed (e.g. invitation acceptance calls `Ownerships.grant` in its transaction).
- **Alternatives considered:** Database roles per service — real enforcement, but needs separate connection pools and credentials; revisit if the check proves insufficient. An architecture-test library (Konsist, ArchUnit) — more machinery than a text check needs today.

## ED-31 — Pilot operators and admin commands
- **Status:** Accepted · **Date:** 2026-11-14 · **Decided by:** project owner · **Related:** GA-03, GA-04, ED-14, ED-28
- **Decision:**
  - **The project owner is the sole Root holder and the only operator** of admin commands for the pilot. Board members may receive HOA-scoped roles; they use them through the app or the future admin portal, not the command line.
  - **The first Root is created with `seebrook bootstrap-root --email <email>`**, which works only while no active Root exists. Any further Root grant is an audited `grant-role` by an existing Root.
  - **Every other admin command names its operator** (`--operator <email>`). The operator must be an active user; what they may do is decided by their roles in our database, through the same actions and checks as the API (ED-14). Every change is audited with the operator and channel `cli`.
  - **Shell access to the environment is the outer control:** `--operator` identifies who acts but is not authentication. Until Root MFA arrives (GA-04 amendment, ED-28), production shell access must stay limited to the owner.
- **Revisit:** Before a second HOA joins: authenticate operators (e.g. a short-lived token from a Root sign-in with MFA) instead of trusting `--operator`, or move administration to the admin portal.

## ED-32 — Roles and permissions
- **Status:** Accepted · **Date:** 2026-11-14 · **Related:** GA-03, ED-14
- **Decision:** Roles map to permissions; actions check permissions, not role names.

  | Role | Scope | Permissions |
  |---|---|---|
  | Root | Platform (every HOA) | All |
  | HOA Admin | One HOA | Manage roles, manage membership |
  | Membership Admin | One HOA | Manage membership |
  | Document Admin | One HOA | Manage documents |
  | Financial Admin | One HOA | Manage finances |

  - Roles are combinable; a board member who needs documents and finances holds both roles.
  - Only Root grants or revokes Root. Managing HOA roles needs "manage roles" in that HOA.
  - An HOA Admin cannot remove the HOA's last HOA Admin; Root can, with a reason. The last Root can never be revoked.
  - Every grant and revocation requires a reason (GA-04 "controlled grants") and is audited. Assignments are never deleted; revocation is recorded.
  - Roles are read fresh from the database for each request or command, so a revocation applies immediately.
  - Roles are granted only to existing users (users are created by accepting an invitation, or by `bootstrap-root`).
- **Interpretation to confirm with the HOA:** the requirements list HOA Admin alongside the other roles without saying whether it includes document or financial administration. HOA Admin is kept to roles and membership; grant the other roles separately when needed.

## ED-33 — Append-only audit trail
- **Status:** Accepted · **Date:** 2026-11-14 · **Related:** GA-09, GA-15, GA-21
- **Decision:** One `audit.event` table owned by the platform module: time, HOA (null for platform-wide), actor (null only for bootstrap), channel (`api`/`cli`/`system`), action name (`<service>.<event>`), target, reason and JSON details with before/after values. Written by `Audit.record` in the same transaction as the change, so a change and its record commit or roll back together. Database triggers reject UPDATE, DELETE and TRUNCATE.
- **Never recorded:** secrets, tokens, passwords or payment credentials. Emails and names may be recorded where needed to identify the people affected.
- **Alternatives considered:** Per-service audit tables — harder to review one HOA's history; database-trigger auditing of every table — captures rows but not the actor or reason.

## ED-34 — Owner CSV import
- **Status:** Accepted · **Date:** 2026-11-14 · **Related:** GA-05, GA-09, GA-14, ED-13, ED-29
- **Decision:**
  - **Format:** UTF-8 CSV (RFC 4180, optional byte-order mark), header with exactly these columns in any order: `unit_label, address_line1, address_line2, city, state_code, postal_code, owner_email, owner_name`. One row per owner of a unit; a row with no owner describes only the property. Limits: 1 MiB, 5,000 rows.
  - **Validation first, all-or-nothing:** every row is checked (required fields, lengths, two-letter state, ZIP or ZIP+4, email format; rows of one unit must agree; an owner may not repeat within a unit) and every problem is reported with its line and column. Any error rejects the file.
  - **Additive and conservative:** missing units are created and each listed owner gets a pending, unsent invitation. A unit that already has owners or pending invitations must list exactly those owners, or the file is rejected as a conflict — changing ownership always goes through `TransferOwnership` with its reason and review (GA-09), never an import. An existing unit's details must match. Units not in the file are left alone.
  - **Dry run by default:** `import-owners` shows what would change; `--commit --reason <text>` applies it in one transaction, re-planned under a per-HOA lock.
  - **Idempotent:** each committed file is recorded (`identity.owner_import`, unique per HOA and SHA-256). Re-submitting it returns the earlier batch; any file whose changes are already applied creates nothing.
  - **Authorization and audit:** requires "manage membership" in the HOA (ED-32); one audit event lists the file checksum, the units created and the invitations created.
  - **Invitations start unsent** (amends ED-29 item 2): the token is created when the invitation is sent, so no usable token is stored before delivery, and the 14-day expiry starts at sending.
  - **Ownership of the action:** `ImportOwners` lives in IdentityService (it creates invitations) and creates properties through PropertyService's public API in the same transaction (ED-13).
- **Alternatives considered:** Full synchronization (removing owners missing from the file) — rejected: a typo or partial file would silently revoke access. Storing tokens at import — rejected: usable tokens would sit in the database before sending.

## ED-35 — Invitation delivery through the job outbox
- **Status:** Accepted · **Date:** 2026-11-15 · **Amends:** ED-06, ED-29 item 2 · **Related:** GA-05, ED-33, ED-34
- **Decision:**
  - **Job outbox (implements ED-06):** `jobs.outbox_job`, owned by the platform module. A job is written in the same transaction as the change that needs it; one queued or running job per dedupe key. The worker claims jobs with `FOR UPDATE SKIP LOCKED`, retries failures after 1, 4 and 16 minutes, then hourly, and marks a job failed after 6 attempts, or at once when the failure is permanent (e.g. the provider rejected the address). A job left running by a dead worker is claimed again after a 5-minute lease. Only kinds with a registered handler are claimed. Payloads hold IDs only; a failure records the exception type only, never its message, since messages can contain personal data.
  - **Worker (amends ED-06):** one background thread inside `serve`, polling every 10 seconds, instead of a coroutine worker — the job code is blocking JDBC, and a thread is the simplest correct choice. `seebrook run-jobs` runs due jobs once.
  - **Sending:** `send-invitations` queues emails for invitations never sent (whole HOA or one unit); dry run by default, `--commit` to queue, because email is outward-facing. `resend-invitation` re-queues a pending invitation (sent, unsent or expired). `revoke-invitation` revokes it with a required reason and cancels any queued email. `list-invitations` shows each invitation's state. All require "manage membership" in the HOA and are audited (ED-32, ED-33).
  - **Tokens are created at delivery (amends ED-29 item 2):** the job, in one transaction, checks the invitation is still pending and gives it a new token, `sent_at` and 14-day expiry, then sends. Each send replaces the previous token, so a resend makes the earlier link stop working, and an invitation accepted or revoked while its email was queued is not sent. A failed send is retried with another new token. If the provider accepted a message but the reply was lost, the retry sends a second email and only the newest link works. Resending updates the same invitation instead of creating a new one.
  - **Email content:** a transactional email (ED-36) in plain text with the HOA, unit, a link `<SEEBROOK_INVITE_LINK_BASE>#token=<token>` and the same code to type into the app; the expiry is shown in the HOA's time zone. The token goes in the URL fragment, which browsers do not send to servers, so it stays out of web server logs.
  - **While email is disabled** (the production default until the provider is set up), no email handler is registered, so invitation jobs wait in the queue and are sent once email is configured.
- **Configuration added (ED-23):** `SEEBROOK_INVITE_LINK_BASE` (default `https://seebrook.cc/invite`; must be https, or localhost outside production). Email settings: ED-36.
- **Alternatives considered:** Storing the rendered email (with its token) in the outbox — rejected: usable tokens would sit in the database until sent (ED-34). A separate email table per service — the generic outbox serves later jobs too (recurring-charge cancellation, reconciliation).

## ED-36 — Provider-neutral email gateway
- **Status:** Accepted · **Date:** 2026-11-15 · **Decided by:** project owner (abstraction, providers, Brevo's HTTP API for the beta) · **Related:** GA-05, ED-35
- **Decision:**
  - **One interface, two operations:** business code sends email only through `EmailGateway`: `sendTransactional(to, email)` for one message about a person's own account or action (invitations, receipts), and `sendBulk(hoaId, recipients, template)` for the same template to members of one HOA (notices). Bulk sends one message per recipient, so no one sees another's address; a bad address fails only that recipient and is reported back. Provider names never appear in business code; configuration and the composition root (`BackgroundJobs`) are the only places that name a provider.
  - **Our templates and data stay ours:** templates are Kotlin code in this repository and are rendered by us; the provider receives only finished messages. No provider-side templates, contact lists, audiences or campaigns. Switching provider changes no content.
  - **Implementations (adapters):**

    | Stage | Provider | Adapter |
    |---|---|---|
    | Development and gateway tests | Mailpit (container in `infra/local`, web UI at http://localhost:8025) | `SmtpEmailGateway`. Nothing leaves the machine |
    | Beta | Brevo | `BrevoEmailGateway`: Brevo's transactional HTTP API (`POST /v3/smtp/email`). Domain-authenticated `seebrook.cc` sender; open and click tracking turned off in the account (click tracking would route invitation links through Brevo's redirect domain) |
    | Later | Postmark or Resend | A new adapter for its HTTP API (or SMTP settings), behind the same interface |

    The HTTP API is used for the beta because it works on hosts that block outbound SMTP ports (hosting is not yet chosen), tells a permanent rejection from a temporary failure, and supports one-request bulk sends. `SmtpEmailGateway` stays for Mailpit and as a fallback for any provider.
  - **Failures:** `EmailRejectedException` means the provider refused the message for good (malformed address; Brevo HTTP 400 other than account errors; SMTP 5xx about the address or message). The job queue marks such a job failed at once instead of retrying (ED-35). Everything else — network errors, timeouts, rate limits (429), server errors, credentials or credit problems — is `EmailDeliveryException` and is retried. Exception messages carry the status and error code only, never addresses, response bodies or keys.
  - **Brevo specifics:** Brevo requires HTML, so plain-text emails also get an escaped HTML copy (paragraphs and line breaks kept, links left as text for the mail client to link). Bulk is one request with one `messageVersions` entry per recipient; if Brevo refuses the batch as a whole, the adapter falls back to one request per recipient so only the bad ones fail. Brevo documents no idempotency, so a retry after a lost response can send a second email; for invitations only the newest link works (ED-35).
  - **Development and beta use different adapters:** Mailpit speaks SMTP only, so local runs exercise the SMTP adapter, while the Brevo adapter is tested against a local fake of Brevo's API (request shape, error mapping, bulk fallback). The first real check of the Brevo adapter is a test send from the owner's Brevo account before the first invitation.
  - **Sensitive content at the provider:** any provider keeps sent messages in its logs for a while, including invitation links. Accepted because a link works once, expires in 14 days and only for the invited verified email (ED-29); revoking an invitation disables its link.
  - **Message details:** UTF-8; plain text, or text plus HTML; over SMTP, `Auto-Submitted: auto-generated` on every message and `Precedence: bulk` on bulk; 10-second connect and 30-second request timeouts; at most 1,000 recipients per bulk call.
- **Configuration (ED-23):** `SEEBROOK_EMAIL_MODE` (`smtp` | `brevo` | `disabled`; default `smtp` in development, `disabled` elsewhere). Common: `SEEBROOK_EMAIL_FROM` (e.g. `Seebrook <no-reply@seebrook.cc>`), optional `SEEBROOK_EMAIL_REPLY_TO`. Brevo: `SEEBROOK_BREVO_API_KEY` (never logged), optional `SEEBROOK_BREVO_API_URL` (https; default `https://api.brevo.com/v3`). SMTP: `SEEBROOK_SMTP_HOST`, `SEEBROOK_SMTP_PORT`, `SEEBROOK_SMTP_SECURITY` (`starttls` | `tls` | `none`; `none` refused in production), `SEEBROOK_SMTP_USERNAME` and `SEEBROOK_SMTP_PASSWORD` (together; never logged). Development defaults to Mailpit at `localhost:1025`.
- **Libraries:** Eclipse Angus Mail 2.0.5 (the Jakarta Mail reference implementation) for SMTP; the JDK HTTP client and kotlinx.serialization for Brevo, so no provider SDK.
- **Alternatives considered:** SMTP for every provider — one adapter and the same path in development, but fails on hosts that block SMTP ports and cannot tell permanent from temporary failures as reliably; kept as the fallback. Provider SDKs — more dependencies for a single API call. Provider-hosted templates — rejected by owner: content would be locked into the provider. Writing local email to files — Mailpit shows real rendered messages and exercises the SMTP path.

## ED-37 — Ownership transfer
- **Status:** Accepted · **Date:** 2026-11-15 · **Related:** GA-07, GA-09, ED-29, ED-32, ED-34, ED-35
- **Decision:**
  - **One action sets a unit's owners:** `transfer-ownership --unit <label> --to <emails>` makes the listed people the unit's owners in one transaction. Owners not listed lose the unit (their ownership gets an end time; rows are never deleted). Pending invitations for people not listed are revoked and any queued email is cancelled. Listed people who are neither owners nor invited get a new, unsent invitation, sent later with `send-invitations`. Listed people who already own the unit (or are invited) are untouched, so adding or removing a co-owner uses the same action.
  - **Effective on the next request:** access is checked against the database on every request (ED-29), so a removed owner's still-valid sign-in token no longer reaches the unit. Their other units are unaffected.
  - **Safeguards:** dry run by default; `--commit` needs a reason; requires "manage membership" in the HOA (ED-32); one audit event records the owners and invitations before, who was removed, what was revoked and who was invited. At least one new owner is required: leaving a unit with no owner is not supported yet.
  - **Concurrency:** transfers and owner imports in one HOA run one at a time (a per-HOA lock, shared with ED-34). Within the transaction the unit's pending invitations are locked before its owners are read, so an acceptance in progress finishes first and its new owner is then seen and handled, never missed.
  - **Imports cannot undo a transfer:** an import listing the previous owner for the unit is a conflict (ED-34).
- **Not yet covered:** the date of sale (recorded on the roadmap with owner profile details; the ownership end time is when the transfer was recorded); cancelling the previous owner's recurring payments, which M6 adds to this same transaction (GA-08).
- **Alternatives considered:** Separate add-owner and remove-owner commands — two steps for a sale, with a window where the unit has both or neither owner. Ending ownership via the import — rejected in ED-34.

## ED-38 — Document storage, upload checks and malware scanning
- **Status:** Accepted · **Date:** 2026-11-15 · **Decided by:** project owner (ClamAV); engineering (the rest) · **Related:** GA-10–GA-13, ED-13, ED-35
- **Decision:**
  - **Private object storage behind an interface:** `ObjectStorage` (put, get, copy, delete, signed download link) with one adapter for any S3-compatible service (AWS S3, Cloudflare R2, Backblaze B2, DigitalOcean Spaces, …). Nothing is public; owners will download through short-lived signed links (M3 part 2). The provider follows the hosting decision. Locally and in tests, Adobe's S3Mock stands in. (MinIO no longer publishes free images.)
  - **Malware scanning with ClamAV (owner's choice):** `MalwareScanner` with a `ClamdScanner` adapter that streams the file to ClamAV's daemon running next to the server (`INSTREAM`), so documents never leave our infrastructure. ClamAV needs about 1.5–2 GB of memory and loads its signatures in roughly a minute; signatures update automatically. No verdict (unreachable, size limit, error) is never treated as clean.
  - **Upload pipeline (GA-12):**
    1. `upload-document` checks the details (title, description, category required; tags; document date) and the file at once: not empty, within the size limit (25 MB, `SEEBROOK_DOCUMENT_MAX_MB`), a PDF signature, opens as a PDF (Apache PDFBox), not encrypted or restricted, at least one page. A bad file is refused before anything is stored.
    2. The file is stored under a quarantine prefix, and the document (status `processing`) and version (state `quarantined`) are recorded with the file's SHA-256, together with a job (ED-35) and an audit event.
    3. The job re-reads the file, checks it is unchanged (SHA-256) and still valid, and scans it. Clean: it is copied to its permanent private location, the version becomes `clean`, the document is published, and the quarantined copy is removed. Infected or invalid: the version is `rejected` with the reason, the document too if it was new, and the file is deleted; the record and audit event are kept. Scanner or storage unavailable: the job retries and the file stays quarantined.
    4. Nothing is visible to owners until published. In an environment without storage or a scanner, uploads are not processed (the job waits), so nothing is ever published unscanned.
  - **Data model:** `document.category` (stable IDs, names unique per HOA ignoring case), `document.document` (metadata, status, current version), `document.version` (storage key, size, SHA-256, page count, check results, uploader). References are HOA-scoped (ED-26). Documents and versions are never deleted (GA-11).
  - **Permissions:** "manage documents" in the HOA (Document Admin, or Root) for categories and uploads (ED-32); every change is audited.
- **Configuration (ED-23):** `SEEBROOK_STORAGE_MODE` (`s3` | `disabled`; default `s3` with the local S3Mock in development, `disabled` elsewhere), `SEEBROOK_S3_ENDPOINT` (optional for AWS; https in production), `SEEBROOK_S3_REGION`, `SEEBROOK_S3_BUCKET`, `SEEBROOK_S3_ACCESS_KEY_ID`, `SEEBROOK_S3_SECRET_ACCESS_KEY` (never logged), `SEEBROOK_S3_PATH_STYLE`; `SEEBROOK_MALWARE_SCAN_MODE` (`clamd` | `disabled`; default `clamd` on localhost in development), `SEEBROOK_CLAMD_HOST`, `SEEBROOK_CLAMD_PORT` (3310); `SEEBROOK_DOCUMENT_MAX_MB` (default 25, at most 100).
- **Libraries and images:** AWS SDK for Java 2.57.8 (S3 only, over the JDK HTTP client), Apache PDFBox 3.0.8, `clamav/clamav:1.5.4-debian` (multi-architecture), `adobe/s3mock:5.2.3`.
- **Alternatives considered:** A hosted scanning API — documents would go to another vendor. The host's built-in scanning (e.g. AWS GuardDuty) — ties scanning to a hosting choice not yet made. Scanning synchronously in the upload command — an unavailable scanner would fail the upload instead of retrying. Storing files in PostgreSQL — larger backups and database load for no benefit over object storage.

## ED-39 — Owner document library API
- **Status:** Accepted · **Date:** 2026-11-15 · **Decided by:** engineering · **Related:** GA-06, GA-10, GA-11, ED-14, ED-38
- **Decision:**
  - **Access (GA-10):** an owner reaches an HOA's library only while they actively own at least one property in it; ending their last ownership in the HOA ends access at once. Roles grant nothing here: administrators use the admin commands, and Root sees the library as an owner only if they own a property. Unknown HOAs, HOAs without access, and unknown or unpublished documents all answer `404 not_found`, so nothing about another HOA can be inferred (GA-06).
  - **Visibility (GA-11):** only published documents whose current version is clean. Processing, rejected and (from M3 part 3) archived documents, and previous versions, are never listed.
  - **Endpoints (`/v1/hoas/{hoaId}`):**
    - `GET /documents` — `q` (search), `category` (ID), `sort` = `uploaded` (default, newest first) | `title` | `documentDate` (undated last), `order` = `asc` | `desc`, `page` (from 1), `pageSize` (default 20, at most 50). Returns the page and the total. A malformed value answers `400 invalid_query` rather than a guess.
    - `GET /documents/{documentId}` — one document.
    - `GET /document-categories` — categories holding at least one visible document, with counts, for the filter.
    - `POST /documents/{documentId}/download-link` — a signed link to the current version, valid 5 minutes, with the expiry and a file name taken from the title (not the administrator's original file name). POST because every call issues a link and writes an audit event.
  - **Search:** case-insensitive substring match on title, description, category name and tags; every word must match somewhere; `%` and `_` are literal. Not PDF text (requirements). A plain `ILIKE` scan suits a pilot library of tens to hundreds of documents; revisit with PostgreSQL full-text search if libraries grow to thousands.
  - **Upload date** is when the current version was uploaded, so a replaced document (M3 part 3) shows its new date.
  - **Audit:** each link issued is recorded (`document.download_link_issued`: owner, document, version, validity), never the link itself. Viewing and downloading both need a link, so this is the observable record of PDF access; fetches from storage are not observable by the server and would need the provider's access logs. Browsing the list is not audited.
  - **Storage disabled:** listing still works; links answer `503 documents_unavailable`.
- **Alternatives considered:** Streaming PDFs through the API — keeps files off the public internet entirely, but the requirements call for signed URLs and it would load the single server. Cursor pagination — more robust under concurrent changes, but page numbers suit a small, rarely-changing library and the requirement for server pagination. Longer-lived links — fewer refreshes, but a leaked link would stay valid longer; clients request a fresh link per open.

## ED-40 — Document administration: replace, archive, restore, version rollback, categories
- **Status:** Accepted · **Date:** 2026-11-15 · **Decided by:** engineering · **Related:** GA-11, GA-13, ED-32, ED-38, ED-39
- **Decision:**
  - **Replace (GA-11):** a new PDF for an existing document goes through the same checks, quarantine and scan as an upload. Owners keep seeing the current version until the new one is clean, which then becomes current; a rejected replacement leaves the current version in place. One replacement at a time per document, so versions cannot overtake each other. An archived document must be restored first. A document whose first upload was rejected can be uploaded again this way.
  - **Archive and restore:** archiving hides a published document from owners at once (list, details and new download links); restoring shows it again with its current version. Archived documents are kept indefinitely (requirements). Both need a reason.
  - **Version rollback (GA-11):** any earlier clean version of the same document can be made current again, with a reason. Newer versions are kept, so a rollback can itself be undone. Refused while a replacement is being scanned, as finishing it would silently override the rollback.
  - **Never deleted:** documents, versions and published files are kept; only rejected files are removed (ED-38). The upload date owners see is the current version's.
  - **Details:** title, description, tags, document date and category can be edited without a new version; the audit event holds the values before and after.
  - **Categories (GA-13):** renaming keeps the category's ID, so its documents are unaffected; names stay unique per HOA ignoring case (a case-only rename is allowed). Reassigning moves every document of a category, in every status, to another. A category can be deleted only when it holds no document of any status, archived, rejected and processing included; the check locks the category so a concurrent upload or move into it cannot slip past. The category row is removed; the audit event keeps its name and ID.
  - **Access and audit:** all need "manage documents" in the HOA (ED-32), look documents and categories up within that HOA only, lock the document while changing it, and are audited (`document.replacement_uploaded`, `document.archived`, `document.restored`, `document.version_restored`, `document.details_updated`, `document.category_renamed`, `document.category_reassigned`, `document.category_deleted`).
  - **Commands:** `replace-document`, `update-document`, `archive-document`, `restore-document`, `list-versions`, `restore-version`, `rename-category`, `reassign-documents`, `delete-category`. Documents are named by ID, which `list-documents` now shows.
- **Alternatives considered:** Deleting a category with documents still in it by moving them automatically — hides a decision the administrator should make. Allowing several pending replacements — whichever scan finished last would win, which is confusing. Soft-deleting categories — nothing references a deleted category, and the audit trail already keeps the record.

## ED-41 — Finance ledger, account mapping and transaction import
- **Status:** Accepted · **Date:** 2026-11-16 · **Decided by:** engineering · **Related:** GA-14, GA-15, GA-20, GA-21, ED-13, ED-26, ED-34
- **Decision:**
  - **FinanceService** (`server/finance`, schema `finance`) owns accounts, imported transactions, corrections and the ledger.
  - **Money:** integer cents, USD only; never floating point. One sign convention throughout: positive increases what the owner owes (assessments, late fees), negative reduces it (payments, waivers); adjustments may be either. A balance above zero is owed, below zero is a credit.
  - **Accounts (GA-20):** one persistent account per property, owned by the property, not by an owner, so it survives transfers. Each carries the account's identifier in the HOA's spreadsheet, unique per HOA. `import-accounts` maps units to identifiers from a `unit_label,account` CSV: dry run by default, commit with a reason, every unit must exist, an identifier cannot belong to two units, and an identifier may be corrected only while its account has no transactions.
  - **Ledger (GA-21):** `finance.ledger_entry` is append-only (database triggers refuse update, delete and truncate), as are corrections and import batches; transactions cannot be deleted. An account's balance is the sum of its entries. The invariant "ledger sum = sum of transactions at current values" is checked by reconciliation and by tests.
  - **Transaction import (GA-14):** a canonical UTF-8 CSV with `transaction_id, account, date, type, description, amount` and optional `due_date`; types `assessment`, `late_fee`, `adjustment`, `waiver`, `payment` (payments the HOA received outside the app, such as checks). Dates as `YYYY-MM-DD` or `M/D/YYYY`; amounts exactly, with or without `$`, thousands separators, a minus sign or parentheses, at most two decimals, never rounded. Every problem is reported with its line and column, and any problem rejects the whole file. Then the whole file is planned: unmapped accounts are conflicts; a known transaction on a different account is a conflict (money never moves between properties by inference); new IDs are created; changed rows become corrections; identical rows are unchanged; transactions missing from the file are left alone (files may be partial; a removal is entered in the spreadsheet as a correcting row). Dry run by default; a commit with a reason applies everything in one transaction under a per-HOA lock, so a failure leaves nothing behind.
  - **Idempotent retry:** each committed file is recorded by SHA-256 and never applied twice; any file whose rows are already applied changes nothing.
  - **Corrections (GA-15):** an immutable before/after record per changed transaction. A changed amount posts a reversal of the old amount and an entry for the new one; history is never rewritten. Processor payments (M5) will live in their own records that imports cannot touch.
  - **Reconciliation (M4 exit criterion):** `reconcile-balances` compares every ledger balance with the spreadsheet's (`account,balance`), lists mismatches, unknown and missing accounts and any ledger inconsistency, exits non-zero unless everything matches, and is audited.
  - **Access and audit:** all finance commands need "manage finances" in the HOA (Financial Admin or Root, ED-32); lookups are HOA-scoped, so the same spreadsheet ID in two HOAs names two different accounts. Audit events hold counts and checksums, never amounts or personal financial data; amounts live in the ledger and corrections tables.
  - **Fixtures:** synthetic `accounts.csv`, `transactions.csv` (261 rows: a year of quarterly dues, check payments, late fees, a waiver, adjustments, a partial and an advance payment) and `expected-balances.csv` for the 32 sample units, all reconciling.
- **Alternatives considered:** Storing balances directly — fast, but nothing would prove them. Rewriting ledger entries on correction — simpler, but loses the history GA-15 requires. Deleting transactions missing from a file — a partial file would silently erase charges. Reading `.xlsx` directly — more parsing surface and Excel-version quirks for no gain over "Save as CSV UTF-8"; revisit with the real spreadsheet.

## ED-42 — Owner finance view, voids and the register workbook converter
- **Status:** Accepted · **Date:** 2026-11-18 · **Decided by:** owner (what a new owner sees, transaction numbers) and engineering · **Related:** GA-06, GA-14, GA-15, GA-20, GA-21, ED-17, ED-41
- **Decision:**
  - **What a new owner sees (owner decision):** the account's full balance, with everything before their ownership shown as one "balance carried forward" amount and no details. Owners' history starts when the previous owner left: the latest end of an earlier ownership by someone else that ended no later than this owner's started. Transactions dated on or before that day (HOA time zone) are carried forward; later ones are listed. A unit with no earlier owner, and co-owners who overlapped with the previous owner, show the full history. The carried-forward amount is the ledger balance minus the listed transactions, so the lines always add up to the balance. Purchase dates (planned owner profile extension) can refine this later.
  - **Owner API:** `GET /v1/properties/{propertyId}/finance/summary` (balance, amount due, overdue, not yet due, credit, next due date, the HOA-local date it is measured on, last import time; integer cents, USD) and `GET /v1/properties/{propertyId}/finance/transactions` (newest first, each with its balance after; `page`, `pageSize` up to 50; plus the carried-forward amount). Active owners of the property only; every other case is 404 `not_found` (GA-06); an owned unit with no account mapped is 404 `finance_unavailable`. One property at a time: no cross-property total. Owners see current values only, never correction history; voided transactions are left out. All figures are computed in the backend (ED-17).
  - **Due and overdue:** credits pay the oldest charges first, so overdue is what charges already past their due date leave unpaid after all credits, capped at the balance. A charge without a due date is due on its transaction date; one due today is not overdue. No grace period.
  - **Transaction numbers (owner decision):** the register's new `transactionId` column (`2026-00001` style, typed values, never reused or renumbered) is the stable ID that matches corrections. A year prefix keeps years apart.
  - **Voids (amends ED-41):** an amount of 0 on a known transaction voids it: a correction changes only the amount to 0 and the ledger gets a reversal of the old amount; the row keeps its history and can be restored with a later non-zero amount. A new transaction ID with 0 has nothing to post and is skipped and counted. The database allows 0 only after a correction (`version > 1`).
  - **Register workbook converter:** `convert-register --file <xlsx> --year <yyyy> --out <dir>` reads the HOA's workbook (cached values only, never evaluating or changing it; Apache POI) and writes the canonical `transactions.csv`, `expected-balances.csv` and `accounts.csv`. Units come from the `owners` defined name; dues, due dates and balances from `<year> income`; payments, late fees and adjustments from the `<year> …` register sheet with a `transactionId` column. Each unit gets a carried-forward adjustment from `<year-1> Balance` (`OPEN-<year>-<unit>`) and one assessment per month due so far (`DUES-<year>-<MM>-<unit>`). Register rows are a unit's when their Payee ID is a unit and their date is in the year, matching the income sheet's own formulas; money received is a payment (a `Late Fee`-type credit is a waiver), money charged is a late fee in the `Late Fee` category and otherwise an adjustment; descriptions come from the category only, never names or comments. The spreadsheet's sign (positive = credit) is flipped. Excel floating-point noise below a millionth of a dollar is removed; any real fraction of a cent rejects the file. Before writing anything, every unit's converted total must equal the sheet's `<year> Balance`, or nothing is written. Output is sorted and deterministic, so converting an unchanged workbook gives a file the import recognizes as already imported. It needs no database, and its output directory should be outside the repository.
- **Alternatives considered:** Showing a new owner nothing from before their purchase, not even the balance — hides money the account really owes. Starting history at invitation acceptance — every original owner would see only a carried-forward line at launch. UUIDs in the register — Excel cannot generate them without macros or volatile formulas that change on every edit. IDs derived from date, unit and amount — an edited row would look like a deleted one plus a new one. Importing the `.xlsx` directly into the ledger — the canonical CSV keeps one reviewed import path; the converter only prepares it.

---

## ED-43 — Payments: provider-neutral interface, Stripe Connect adapter, per-HOA fees, one-time payments
- **Status:** Accepted · **Date:** 2026-11-19 · **Decided by:** owner (Stripe, Connect, a provider-neutral layer, the fee policy) and engineering · **Related:** GA-06, GA-17, GA-21, GA-22, GA-23, GA-24, ED-13, ED-17 (X-02), ED-36, ED-41, ED-42
- **Context:** M5 needs one-time card and ACH payments. The owner chose Stripe and asked that business logic not depend on any one processor, so processors can be added, replaced or removed. Each HOA has its own merchant account (REQUIREMENTS) and decides who pays the processing fee.
- **Decision:**
  - **Provider-neutral layer (adapter pattern):** business code depends only on `PaymentProvider` (`server/platform/payments`): create a payment for an exact amount (idempotent), read its status, and verify and normalize a webhook. Each processor is an adapter behind it; `PaymentSetup` in the app is the only code that names concrete adapters, as with email (ED-36). Every payment and HOA setting stores the provider's name, so an HOA can move to another provider without affecting earlier payments. Adapters now: `stripe` and `simulated` (moves no money; development and tests only, refused in production).
  - **Stripe adapter:** Stripe Connect with direct charges. Seebrook holds one platform key; each HOA connects its own Stripe account, so the money goes to the HOA and Seebrook stores only the connected account ID (`acct_…`), no per-HOA secrets. A payment is a PaymentIntent on the HOA's account (`Stripe-Account`), limited to the method the owner chose (`allowed_payment_method_types`: `card`, which includes Apple Pay and Google Pay, or `us_bank_account`), so the approved fee is exact. Plain HTTPS calls with the API version pinned to `2026-09-30.endive`; Stripe's error messages are never logged (only codes). Requests are checked in tests against stripe-mock, Stripe's mock of its API.
  - **Card and bank details:** collected by the provider's SDK in the app (GA-24, X-02). Seebrook stores the provider's payment ID and the amounts only. The client parameters the SDK needs (for Stripe: client secret, publishable key and connected account) go to the app in the response and are never stored or logged.
  - **Per-HOA settings and fees (GA-22):** `finance.payment_settings` (provider, merchant account, suspended) and `finance.payment_method_fee`: each accepted method has a fee the owner pays on top of the dues, a percentage (basis points, half-up to the cent) plus a fixed amount, up to 10% and $100. A fee of 0 means the HOA absorbs the processor's fee. Set with the audited `configure-payments` command. Channel Harbor's policy (owner): ACH absorbed by the HOA; card fee paid by the owner, its amount set once its legality is confirmed (pending); paper checks carry an HOA convenience fee, which is an HOA charge on the unit (entered in the register and imported like any charge), not a processor fee.
  - **Quote and approval (GA-23):** `POST /v1/properties/{id}/payments/quote` returns dues, fee and total per accepted method. `POST /v1/properties/{id}/payments` takes the approved method, dues, fee, total and an app-generated idempotency key. The server recomputes the fee; if anything differs, nothing is charged and the response is 409 `quote_changed` with the new quote to reconfirm, so a charge is never raised silently. Any amount from $1 to $50,000: partial and advance payments are allowed.
  - **Idempotency:** the owner's key returns the same payment on a repeat (a different body with the same key is 409 `idempotency_conflict`). The provider payment is created outside the database transaction with the key `seebrook-payment-<id>`, so a retry after a provider outage resumes the same payment rather than creating another.
  - **Webhooks (GA-17):** `POST /v1/webhooks/payments/{provider}`, authenticated by the provider's signature (Stripe's scheme: HMAC-SHA256 over timestamp and body, 5-minute tolerance, several signatures accepted while a secret rotates) and Stripe's live/test mode. Each event ID is applied once (`finance.payment_event`, unique per provider; concurrent deliveries are serialized with an advisory lock), and a replay answers 200 and changes nothing. The event must match the payment's provider, merchant account, amount and currency; otherwise it is recorded as `mismatch` or `unmatched`, logged for investigation and changes nothing. Valid state changes only: created → processing/succeeded/failed/canceled; processing → succeeded/failed/canceled; failed → processing/succeeded/canceled (the owner may retry at the provider); succeeded and canceled are final. Events store the normalized outcome, never the raw payload.
  - **Ledger (GA-21, GA-22):** only a confirmed success credits the ledger, with the dues only (`kind = payment`, linked to the payment); the fee never touches the owner's balance ($350 dues + $5 fee credits $350). A unique index allows one credit per payment whatever the events do. A ledger entry now has exactly one source: an imported transaction with its batch, or a payment. The ledger invariant becomes "ledger = imported transactions + settled payments". Processing payments are shown apart as `pendingPaymentsCents` in the summary; settled payments appear in the owner's history and in `account-statement` with their `PAY-…` reference.
  - **Access (GA-06, GA-07):** paying uses the same access as viewing finances: active owners of the property only; other properties look like 404; access ends when ownership ends. A suspended HOA, one without settings, or one whose provider is not configured answers 503 `payments_unavailable`, while history stays readable.
  - **Configuration:** `SEEBROOK_PAYMENTS_MODE` = `stripe` (needs `SEEBROOK_STRIPE_SECRET_KEY`, `SEEBROOK_STRIPE_PUBLISHABLE_KEY`, `SEEBROOK_STRIPE_WEBHOOK_SECRET`; optional `SEEBROOK_STRIPE_API_URL`, https in production), `simulated` (default in development; not allowed in production) or `disabled` (default elsewhere).
  - **Not yet (M5 part 2):** emailed receipts and refunds, ACH returns and disputes (done in ED-45); reconciliation of payments stuck in processing and of payouts and Stripe's actual fees; how online payments appear in the HOA's register so the monthly import does not count them twice (done in ED-44); automatic suspension during provider outages; the mobile checkout screens.
- **Alternatives considered:** Each HOA's own Stripe API keys — no platform account, but a secret per HOA and manual onboarding (owner chose Connect). Stripe's Java SDK — a large dependency with global configuration; the few calls needed are simple over HTTPS, as with Brevo. Crediting the ledger when the app reports success — the app is not trusted; only the provider's signed event is. A fee as a ledger charge — would mix processor fees into the HOA's books (GA-22).

## ED-44 — Online payments in the HOA register: export rows and `Seebrook Ref` matching on import
- **Status:** Accepted · **Date:** 2026-11-19 · **Decided by:** owner (an export in the register's columns, a separate reference column, the bookkeeper keeps their own transaction numbers) and engineering · **Related:** GA-14, GA-15, GA-21, ED-41, ED-42, ED-43
- **Context:** During the beta the bookkeeper keeps the register workbook and imports it monthly (ED-42). Online payments reach Seebrook's ledger directly (ED-43), and the bookkeeper also records them in the register. Without a link, the next import would count each one twice, and until it is recorded the sheet and Seebrook disagree.
- **Decision:**
  - **Export:** `export-payments --from --to --out` writes received (succeeded) payments, dated the day received in the HOA's time zone, oldest first, as rows in the register's columns and order plus a new last column `Seebrook Ref` (`PAY-…`). Only columns the bookkeeper types are filled: `ID` = the register's code for HOA dues (`101` by default, `--category-id`), `Transaction Date`, `Transaction Type` = `Online Payment`, `Amount` = the dues only in the register's sign (positive), `Payee ID` = the unit's account, and `Comment` = method, plus fee and total when the owner paid a fee. The register's formula columns (Posted Balance, Balance, Payee, Category, Topic, Year, Month) and bank-side columns (Posted Date, Account, Check/Serial #, Invoice Number) are left blank, so pasting with Paste Special → Skip blanks keeps the formulas. `transactionId` stays the bookkeeper's own numbering: blank, or numbered from `--first-id`. The file is never overwritten, belongs outside the repository, and each export is audited (range and count).
  - **Register:** the bookkeeper adds a `Seebrook Ref` column to the register sheet once. `convert-register` reads it when present: a unit row with a `PAY-…` there must be money received and appear once in the workbook, and is passed on in a new optional `payment_ref` column of the canonical transaction file, counted apart in the summary. It still counts toward the unit's total, so the check against the sheet's `<year> Balance` is unchanged.
  - **Import:** a row with a `payment_ref` is never posted, because the payment already credited the ledger. It must match a received payment of the same HOA, the same unit and the same dues amount, and its `transaction_id` must not already be an imported transaction; anything else is a conflict that rejects the whole file. Matched rows are reported as "online payments matched" and counted as unchanged in the batch.
  - **Result:** once the rows are pasted, `reconcile-balances` matches again; until then, a unit's mismatch equals the payments not yet recorded.
- **Amends:** ED-41 (canonical file gains the optional `payment_ref` column) and ED-42 (the converter reads `Seebrook Ref`).
- **Alternatives considered:** Using `PAY-…` as the `transactionId` — simpler, but mixes two numbering schemes in the bookkeeper's key column (owner preferred a separate column). Filling every register column — pasting would overwrite the register's formulas. Leaving online payments out of the register — the sheet's `<year> Balance` would no longer be the unit's balance.

## ED-45 — Payment receipts and notices; refunds, failed refunds and disputes as linked compensating entries
- **Status:** Accepted · **Date:** 2026-11-19 · **Decided by:** engineering · **Related:** GA-17, GA-19, GA-21, GA-22, ED-35, ED-36, ED-43, ED-44
- **Context:** REQUIREMENTS: preserve original payments and record linked compensating entries for full and partial refunds, ACH returns and chargebacks; notify owners of payment success and failure. Refunds are rare in the pilot and there is no dispute UI.
- **Decision:**
  - **Events:** providers report money moving after a payment was received as a normalized `Adjusted` event: refund, refund failed, dispute funds withdrawn, dispute funds reinstated. Stripe: `refund.created`, `refund.failed`, `charge.dispute.funds_withdrawn`, `charge.dispute.funds_reinstated`. ACH returns of received payments arrive from Stripe as disputes; bank failures before settlement are failed payments (ED-43). Refunds themselves are issued in the provider's dashboard, by the HOA; Seebrook records what the provider reports.
  - **Records:** each refund or dispute movement is one `finance.payment_adjustment` (append-only), unique per payment, kind and provider refund or dispute ID, so a replay or a second event about the same refund changes nothing. The payment row is never changed (its status stays `succeeded`). Each adjustment has a reference for owners and the bookkeeper: `PAY-…-R1`, `-R2` (refunds), `-R1F` (that refund failed), `-D1` (funds withdrawn), `-D1W` (reinstated).
  - **Ledger (GA-19, GA-21):** money leaving the HOA (refund, withdrawal) reverses dues first, up to what is left of the payment's dues; anything beyond was the owner's fee and does not touch the balance. Money coming back (failed refund, reinstatement) restores exactly what the matching refund or withdrawal reversed. Each has its own linked ledger entry (`payment_reversal`, positive; `payment_restored`, negative), at most one per adjustment. An adjustment for a payment not received, a reinstatement or failed refund without its earlier counterpart, an amount above the payment, or a different currency is refused as `mismatch` and logged; an unknown payment is `unmatched`. The ledger invariant counts adjustments.
  - **Owners:** adjustments appear in the history and `account-statement` with their reference and a plain description. Emails (plain text, in the HOA's name, amounts only, no card or bank details or provider IDs): a receipt when a payment is received (dues, fee, total, method, date), a notice when it fails, and notices for a refund, a failed refund, a returned or disputed payment and a reinstatement. They are queued in the same transaction as the change (`finance.payment_email`, ED-35) and sent to the paying owner's current address (looked up through IdentityService; nothing is sent if the account is disabled). Push notifications and preferences come with the mobile app.
  - **Register (ED-44):** `export-payments` also exports adjustments that changed a balance, in the register's sign (a refund is negative), with their reference in `Seebrook Ref` and a comment such as "Refund of online payment PAY-…; $50.00 moved". The import matches them like payments.
- **Alternatives considered:** Changing the payment's status to refunded — loses the original record and cannot express partial refunds. Posting the full refunded amount — would charge the owner's balance for the fee they paid. Reacting to `charge.dispute.created` and `.closed` — the ledger would move before or after the money does; the funds events follow the money itself.

## Change log
- 2026-11-13 — Created. Recorded ED-01–ED-12 and pending decisions.
- 2026-11-13 — Added ED-13 (logical services in one deployable, service map, boundary rules) and ED-14 (action-style use cases). The owner's earlier service map was an illustrative example, not a decision.
- 2026-11-14 — Added ED-15 (KMP shared logic, native SwiftUI/Compose UI) and ED-16 (iOS first). Resolved "Mobile framework" pending item; added pending items for Android pilot access and developer accounts.
- 2026-11-14 — Added ED-17 (backend-owned business logic, thin clients) with client-side exceptions register X-01–X-05. Marked ED-08 and ED-15 as amended by ED-17.
- 2026-11-14 — Added ED-18 (git flow, supersedes ED-12) and ED-19 (repository layout; docs moved from `documentation/docs/` to `docs/`). Added pending items: auth build-vs-buy, email provider, account deletion vs retention, privacy policy/terms, legal entity, admin operators, future web client.
- 2026-11-14 — Added ED-20 (Temurin 21 JDK).
- 2026-11-14 — M0: added ED-21 (central migrations and jOOQ codegen; amends ED-13), ED-22 (PostgreSQL 18, local environment), ED-23 (configuration and environments), ED-24 (JVM-only shared module for now), ED-25 (dependency version policy).
- 2026-11-14 — Added ED-26 (UUIDv7 primary keys, separate business/human identifiers, HOA-scoped composite references).
- 2026-11-14 — Added ED-27 (local git hooks in place of unavailable GitHub branch protection).
- 2026-11-14 — Added ED-28 (Firebase Auth for identity only; authorization in our database; Root MFA deferred by owner until a second HOA joins the beta). Resolved the auth provider pending item; added a pending item for the Identity Platform/MFA upgrade; amended GA-04 in `DECISIONS.md` and `REQUIREMENTS.md`.
- 2026-11-14 — M1: added ED-29 (sign-in and invitation rules; development tokens instead of the Firebase emulator; amends ED-28 item 7) and ED-30 (automated schema-ownership check, fulfilling ED-21).
- 2026-11-14 — M2: added ED-31 (owner is sole Root and operator; admin commands), ED-32 (roles and permissions) and ED-33 (append-only audit trail). Resolved the admin operators pending item.
- 2026-11-14 — M2: added ED-34 (owner CSV import; invitations start unsent, amending ED-29).
- 2026-11-15 — M2: added ED-35 (invitation delivery through the job outbox; worker thread amends ED-06; tokens created at each send, amending ED-29) and ED-36 (provider-neutral email gateway; owner chose Mailpit for development, Brevo's HTTP API for the beta, Postmark or Resend later). Replaced the email provider pending item with the Brevo account and domain setup.
- 2026-11-15 — M2: added ED-37 (ownership transfer).
- 2026-11-15 — M3: added ED-38 (S3-compatible private storage, upload checks, ClamAV scanning chosen by owner). Resolved the malware scanning pending item; updated the storage provider item.
- 2026-11-15 — M3: added ED-39 (owner document library API: access from active property ownership, search, sort, pagination, audited 5-minute download links).
- 2026-11-15 — M3: added ED-40 (document administration: replace, archive, restore, version rollback, detail edits, category rename, reassign and delete when empty). Completes M3.
- 2026-11-16 — M4: added ED-41 (finance ledger, account mapping, atomic and idempotent transaction import with corrections, reconciliation). Added pending items for the HOA spreadsheet format and the owner view of balances across an ownership change.
- 2026-11-18 — M4: added ED-42 (owner finance API with due/overdue and carried-forward history, voids, `convert-register` for the HOA register workbook). Resolved the "HOA spreadsheet format" and "Owner view of balances across an ownership change" pending items with the owner's decisions. Amends ED-41 (amount 0 now voids).
- 2026-11-19 — M5 part 1: added ED-43 (provider-neutral payment layer, Stripe Connect adapter, per-HOA payment methods and fees, quote and idempotent one-time payments, verified idempotent webhooks, ledger credit on confirmed settlement). Replaced the "Payment processor" pending item with the Stripe account setup and the Channel Harbor card fee items. Amends ED-41: ledger entries may come from payments.
- 2026-11-19 — M5 part 2: added ED-44 (export of received payments in the register's layout, `Seebrook Ref` column read by `convert-register`, `payment_ref` matched on import so online payments are not counted twice). Amends ED-41 and ED-42.
- 2026-11-19 — M5 part 2: added ED-45 (payment receipts and notices by email; refunds, failed refunds and disputes as append-only adjustments with linked compensating ledger entries; exported and matched like payments). Updated the Stripe setup pending item with the webhook events to enable.
