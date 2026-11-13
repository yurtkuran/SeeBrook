# Proposed architecture (engineering guidance, not approved stack)
```text
iOS / Android client
        |
     HTTPS API
        |
single modular backend --- private object storage (PDF quarantine/versions)
        |
   PostgreSQL
        |
simple scheduled worker / DB outbox
        |
payment processor (merchant account per HOA)
```
- One modular backend and relational DB, not microservices or broker.
- Kotlin/JVM backend and PostgreSQL are reasonable candidates; final stack not selected.
- Native mobile preferred; evaluate Kotlin Multiplatform pragmatically.
- Mature identity solution for verified email, invitations, Apple/Google, Root MFA.
- Object storage private, signed read URLs after authorization, scan before publish.
- Durable DB-backed outbox for transfer cancellation, notification/reconciliation retries.
- Financial writes transactional; unique IDs and idempotency keys; audit without secrets.
- Tenant ID on relevant records; server checks property access, optionally DB RLS.
- Managed backup and simple deployment; no Kubernetes or HA for pilot.

Illustrative APIs (not final contract):
`GET /me/properties`, `GET /hoas/{id}/documents`, `POST /documents/{id}/read-url`, `GET /properties/{id}/financial-summary`, `GET /properties/{id}/transactions`, `POST /properties/{id}/payments`, `POST /webhooks/{processor}`.

All endpoints verify current server-side access. CLI/backend commands handle pilot admin tasks.
