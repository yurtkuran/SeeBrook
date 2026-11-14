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

**First three Codex tasks:** (1) bootstrap runnable repository/CI; (2) implement HOA/property/user/ownership schema and tenant-safe read endpoint; (3) invitation acceptance and end-to-end login/property test.

Before onboarding second HOA: implement backend financial administration portal and streamline multi-HOA operations based on beta feedback. Do not block M1–M4 on payment processor choice.
