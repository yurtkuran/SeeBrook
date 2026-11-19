# Genuine blockers only — no new questionnaire
| Topic | Needed when | Default action |
|---|---|---|
| Payment processor selection, fees, merchant model | Before M5 | Short targeted comparison; verify ACH/cards/recurring/webhooks, settlement and compliance |
| Actual sanitized Channel Harbor Excel/CSV format | M4 | Resolved 2026-11-18: layout reviewed from structure only (no values read); `transactionId` column added by the owner; `convert-register` reads the workbook (ED-42). The first real run is a dry run started by the owner |
| Pilot owner/property roster and PDFs | M2/M3 | Use synthetic data until securely provided |
| Backend framework, hosting, auth provider | M0/M1 | Engineering decision with simple reversible default; escalate only meaningful cost/security differences |
| Mobile framework and app distribution | Client build/M7 | Native preferred, Kotlin Multiplatform candidate; decide with prototype |
| Processor fee legality/HOA policy | M5 | Verify before charging real users |

Non-blockers: full admin portal, elaborate refunds/disputes, HA, queues, advanced document tools. Raise new questions only if user-facing behavior, legal/financial liability, security or material costs are at stake.
