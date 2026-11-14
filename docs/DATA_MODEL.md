# Proposed logical data model (not final SQL)
| Entity | Key invariant |
|---|---|
| HOA | Tenant root and merchant config |
| Property | Immutable HOA-scoped ID, unit label/address |
| User | Global identity, verified email |
| PropertyOwnership | User-property association, effective dates/status |
| RoleAssignment | HOA-scoped roles or platform Root |
| Invitation | Email/property binding, expiry, one-time status |
| FinancialAccount | Persistent one-to-one with property |
| ExternalPropertyMapping | Unique HOA/source/external ID mapping to property |
| LedgerEntry | HOA/account, amount, source, immutable origin |
| ImportBatch | HOA, checksum, status, validation results |
| ImportedTransaction | Stable external ID, current values |
| TransactionCorrection | Immutable field-level before/after |
| Payment | HOA, property, owner, processor IDs, dues/fee/total, status |
| PaymentEvent | Unique processor merchant/event ID, processing status |
| PaymentMethodRef | Token and safe display metadata, scoped associations |
| RecurringAgreement | Owner/property/method, fixed amount, schedule, status |
| ReconciliationException | Discrepancy and investigation status |
| DocumentCategory | Stable HOA-scoped ID |
| Document | Metadata, current version, published/archived |
| DocumentVersion | Private object key and validation state |
| OutboxJob | Durable external follow-up with retries |
| AuditEvent | Actor, HOA, action, reason, before/after |
| NotificationDelivery | Channel/status and retention |

Constraints: unique external import transaction ID per HOA/source; unique processor event ID per merchant; unique payment idempotency key; access by active ownership or admin role; financial account survives ownership transfer; never credit pending payment to confirmed balance.
