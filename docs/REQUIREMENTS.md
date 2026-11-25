# Consolidated approved requirements

## Membership & Accounts
- Invitation-only onboarding based on HOA-preverified ownership, initial owner/property CSV. Invitation single-use, expiring, bound to verified email and property; states pending/accepted/expired/revoked; manual resend/revoke.
- Multiple separate owners per property and multiple properties/HOAs per user supported in data model, though pilot has no co-owned units.
- Email/password, Apple/Google authentication; biometric convenience where feasible. Homeowner edits own name, verified email, phone, notification preferences; admins control ownership, address, financial identifiers.
- HOA-scoped combinable Membership Admin, Document Admin, Financial Admin, HOA Admin; platform Root has cross-HOA authority. Root requires MFA (deferred until a second HOA joins the beta — GA-04 amendment, ED-28), sensitive-action reauthentication, controlled grants and full audit; HOA Admin cannot grant Root or remove last HOA Admin.
- Authorized Membership Admin/HOA Admin/Root changes ownership only after verifying HOA evidence, entering reason, reviewing affected associations; audit actor, old/new values, time.
- Transfer atomically revokes property access and pending invitations, preserves financial history and unaffected properties, durably requests cancellation of departing owner's recurring charges. Reconcile in-flight payments; do not indefinitely block transfer on processor outage.
- Repeatable audited HOA provisioning script and time-limited initial HOA admin invitation; no shared passwords.

## Document Library
- HOA-scoped access derives from at least one active HOA property association; admin access by role. Losing final property revokes access.
- PDF-only, initial 25MB configurable limit. Validate extension, file signature, structure and malware; reject encrypted/corrupt/unsupported; quarantine until clean and published.
- Single-level HOA categories; stable IDs, create/rename/reassign, delete only when empty including archived docs.
- Required title, description, category; optional tags/document date; automatic upload date. Search title/description/category/tags, not full PDF text. Sort title/upload/document date asc/desc; server pagination.
- Admin backend upload/replace/archive/restore, no draft or upload notification. Homeowners view/download/native share, no automatic offline cache.
- Private storage and short-lived signed URLs. Only current published version visible; previous PDF versions privately retained for admin restore. Archived docs retained indefinitely.
- Audit administrative changes, views/downloads, URL issuance/access where observable.

## Dues & Payment Management
- One persistent financial account per property with immutable HOA-scoped property ID, exact validated mapping of Excel identifiers. Financial account and balances survive ownership transfer; former owners' transaction details not exposed to new owners.
- Excel/CSV initial source for assessments, adjustments and waivers; manual backend import. Stable transaction IDs; whole-file validation/dry run; atomic batch commit/rollback; idempotent retry; audit and reconciliation.
- Imported transaction corrections require immutable before/after history and consistent balance recalculation; cannot overwrite processor payments. Reject ambiguous conflicts.
- Authoritative transactional property ledger, concurrency safeguards and verified balances. Confirmed balance only changes on confirmed settlement; show pending estimates separately.
- Per-selected-property home summary: signed balance, due/overdue, transaction history, processing/failed/received states, pay and recurring controls; no cross-property aggregate balance. Homeowner transactions restricted by ownership period.
- One-time ACH/card and eligible wallets supported by chosen processor. Partial and advance payments allowed; allocate oldest outstanding charges first.
- Before checkout, homeowner explicitly approves exact dues amount, fee and total; reconfirm changes, never auto-increase charge. Bind authorization to HOA/property/method/currency; idempotent submission.
- Track HOA dues and processor fees separately; only dues credits property ledger; reconcile gross, fees, net settlement and reversals.
- Processor-hosted credential collection/tokenization; store only payment references and safe metadata, scoped to user/property/HOA/merchant. Protect secrets.
- Saved payment methods and independent homeowner/property recurring agreements; fixed amount (not automatic balance sweep), monthly/quarterly/annual HOA-defined dates with business-day holiday handling; edit/pause/resume/cancel, no automatic retry after failed recurring charge, cancel upon ownership transfer, reauth sensitive changes.
- One merchant account per HOA; verify webhook signature, replay window, unique event ID, merchant/property binding and valid state transitions. Process transactionally, retry idempotently, reconcile unresolved events.
- Automatically resolve only unambiguous verified reconciliation differences; alert on ambiguous mismatches for backend investigation.
- Preserve original payments; record linked compensating entries for full/partial refunds, ACH returns and chargebacks. Refunds very rare in pilot; no full dispute UI.
- Optional payment success/failure push with preferences and email fallback when push unavailable/definitively rejected; mandatory notices and in-app banners as appropriate. Deduplicate device deliveries, handle invalid tokens, audit delivery (one-year default retention).
- Monitor processor outages, webhook/reconciliation backlog and notification failures; HOA-isolated payment suspension, alerting, safe automatic recovery except explicit admin hold. During outage, show read-only history and disable payment actions.

## Cross-cutting
- Each HOA can add its logo to personalize the owner app; owners see their HOA's logo (owner requirement, 2026-11-25; ED-55).
- Each HOA can add a community photo of its choosing, shown on the owner app's Home screen (owner requirement, 2026-11-25; ED-57).
- Backend authorization plus database tenant isolation (e.g. PostgreSQL RLS where appropriate), scoped jobs/imports/storage/webhooks/audit.
- Encrypted automated backups and PITR where supported; RPO <=1 hour, RTO <=24 hours, >=30 days recoverable retention. Back up PDFs/configuration; test restores and reconcile processor records.
- No unnecessary infrastructure for 32-unit pilot; preserve secure future multi-HOA architecture.

## Representative acceptance tests
- User cannot fetch another property's data by changing an ID.
- Selling final HOA property immediately removes document access.
- Reimporting CSV produces no duplicate ledger effects; failed batch produces none.
- Replayed webhook never double-credits payment.
- $350 dues + $5 fee credits $350 to property ledger.
- Reversal retains original payment and creates linked counter-entry.
- Invalid/malicious PDF is never published.
