# Gap resolutions GA-01–GA-24 (all approved)
| Gap | Decision |
|---|---|
| GA-01 | Audited repeatable backend HOA provisioning. |
| GA-02 | Single-use invitation for initial HOA admin, scoped privileges only upon acceptance. |
| GA-03 | Root platform-wide; HOA Admin tenant-scoped, cannot grant Root or remove last HOA Admin. |
| GA-04 | Root MFA, sensitive reauth, controlled grants, recovery and session revocation. |
| GA-05 | Preverified ownership; email/property-bound invitation; invalidate on ownership change. |
| GA-06 | Defense-in-depth tenant isolation across DB/API/storage/jobs/payments. |
| GA-07 | Immediate property-specific access revocation, retain unaffected property access. |
| GA-08 | Atomic transfer and durable idempotent recurring cancellation; reconcile in-flight payments. |
| GA-09 | Authorized verified ownership change with reason, review and full audit. |
| GA-10 | HOA document access derived from at least one active HOA property association. |
| GA-11 | Privately retain previous PDF versions for authorized restoration and audit. |
| GA-12 | Quarantine, signature/structure/size validation, malware scan, reject encrypted/malformed. |
| GA-13 | Stable category IDs, safe rename/reassign, delete only when empty incl. archived. |
| GA-14 | Atomic validated CSV import, batch tracking, rollback, idempotent retry. |
| GA-15 | Imported transaction corrections with immutable before/after audit; protect processor records. |
| GA-16 | Encrypted backup/PITR; RPO <=1h, RTO <=24h, >=30d, restore tests. |
| GA-17 | Authenticated/replay-protected/idempotent webhook processing with valid state transitions. |
| GA-18 | Automatic unambiguous reconciliation; manual alerted exceptions. |
| GA-19 | Linked compensating ledger entries for refunds/returns/chargebacks; rare refunds. |
| GA-20 | Immutable property ID, persistent financial account, exact external mapping. |
| GA-21 | Transactional ledger, concurrency protection, verified balance and audit. |
| GA-22 | Separate dues from processor fees; reconcile gross/net; disclose fee. |
| GA-23 | Exact authorized amount/fee/total; reconfirm changes; idempotent checkout. |
| GA-24 | Processor-hosted tokenization, safe metadata only, scoped authorization, secure secrets. |

The original 175 decisions were summarized in historical context, not available as a full verbatim numbered transcript here. Do not fabricate original decision IDs or claim a line-by-line 199-decision source matrix.
