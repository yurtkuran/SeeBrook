# Roadmap — build vertical slices, not all at once
| Phase | Deliverable | Exit criterion |
|---|---|---|
| M0 Foundation | Repo, CI, backend skeleton, local DB, migrations, synthetic 32-unit fixtures | One-command local startup and passing CI |
| M1 Auth + property | Invitation acceptance, login, tenant-safe property API | Test owner sees only assigned unit |
| M2 Membership ops | CSV owner import, invitations, roles, transfer commands/audit | Operator can onboard and transfer owners safely |
| M3 Documents | PDF validation/storage, categories, read/search/download, archive/version restore | Owner sees authorized current PDFs only |
| M4 Read-only finance | Account mapping, atomic CSV import, ledger, balance/history UI | Reimport idempotent; all 32 fixture balances reconcile |
| M5 One-time payments | Processor chosen, tokenized checkout, fee quote, webhooks, receipts | Sandbox success/failure/replay tests pass |
| M6 Recurring + exceptions | Fixed recurring plans, cancellation, reconciliation, reversals, alerts | Simulated lifecycle passes without double charges |
| M7 Pilot | Production setup, backup restore drill, security review, owner onboarding | Pilot homeowners complete core workflows |

## Planned extensions (not yet scheduled)
| Item | Scope | Notes |
|---|---|---|
| Owner profile details (added 2026-11-15 by owner) | Per owner: mailing address (may differ from the unit, e.g. off-site or second-home owners); multiple labelled phone numbers (e.g. mobile, home, business; one per co-owner); additional contact emails; date of purchase; further owner details as the HOA needs | Contact emails are separate from the verified sign-in email (ED-29), which stays the only login identity. Date of purchase is the legal acquisition date, distinct from when the account was linked in the app. Extends the owner CSV import (ED-34). Who may view or edit each field, and retention, follow the pending privacy-policy and account-deletion decisions. |

**First three Codex tasks:** (1) bootstrap runnable repository/CI; (2) implement HOA/property/user/ownership schema and tenant-safe read endpoint; (3) invitation acceptance and end-to-end login/property test.

Before onboarding second HOA: implement backend financial administration portal and streamline multi-HOA operations based on beta feedback. Do not block M1–M4 on payment processor choice.
