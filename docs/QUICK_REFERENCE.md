---
title: Seebrook quick reference
tags: [seebrook, reference]
updated: 2026-11-19
---

# Seebrook quick reference

Day-to-day commands and URLs. Run everything from the repository root. The full command list is in `README.md` and `seebrook help`.

## Local development

```bash
scripts/dev.sh                  # start everything: containers, build, migrations, synthetic data, API on :8088
curl localhost:8088/health/ready  # is the server up?
scripts/dev.sh down             # stop the containers, keep the data
scripts/dev.sh reset            # stop the containers and delete the data
```

- The API runs on your Mac as a JVM process (Ctrl+C stops it). Only the supporting services run in Docker.
- `scripts/dev.sh` rebuilds the backend each time. To rebuild without restarting the containers: `./gradlew :server:app:installDist`

### Local URLs and ports

| Service | Address | Notes |
|---|---|---|
| Seebrook API | http://localhost:8088 | Started by `scripts/dev.sh` |
| DbGate (database browser) | http://localhost:3001 | Connected to the local database |
| Mailpit (caught emails) | http://localhost:8025 | Every local email lands here; nothing is sent |
| PostgreSQL | localhost:55432 | Database, user: `seebrook`; local-only password is in `infra/local/docker-compose.yml` |
| S3 stand-in | http://localhost:9090 | Bucket `seebrook-dev`; data is lost when the container is removed |
| ClamAV | localhost:3310 | Takes about a minute to load signatures after starting |

## Database

- Schemas: `identity`, `tenant`, `property`, `document`, `finance`, `audit`, `jobs`.
- Browse in DbGate at http://localhost:3001, or use psql:

```bash
docker exec -it seebrook-local-postgres-1 psql -U seebrook -d seebrook
```

## Build and test

```bash
./gradlew build                 # compile and run all tests (Docker must be running for Testcontainers)
./gradlew :server:app:test      # server app tests only
```

## Operator CLI

```bash
export SEEBROOK_ENV=development
APP=server/app/build/install/seebrook/bin/seebrook
$APP help                       # every command
```

- Commands that change data run as a dry run first. Add `--commit --reason "<why>"` to apply.
- Every change is audited under the operator's email (`--operator <email>`).
- The pilot HOA is `--hoa channel-harbor`.

### Sign in as a test owner (development only)

```bash
INVITE=$($APP dev-invite "Unit 07" owner07@example.test | tail -1)
TOKEN=$($APP dev-token owner07@example.test)
curl -s localhost:8088/v1/invitations/accept -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d "{\"token\":\"$INVITE\"}"
curl -s localhost:8088/v1/me/properties -H "Authorization: Bearer $TOKEN"
```

## Monthly finance import

Real financial data. Keep the output folder outside the repository.

```bash
O=(--operator <email> --hoa channel-harbor)
OUT=~/seebrook-import/2026-11

$APP convert-register --file "<register>.xlsx" --year 2026 --out $OUT    # no database needed
$APP import-accounts "${O[@]}" --file $OUT/accounts.csv                  # first time only, then --commit --reason "..."
$APP import-transactions "${O[@]}" --file $OUT/transactions.csv          # dry run: check new rows and corrections
$APP import-transactions "${O[@]}" --file $OUT/transactions.csv --commit --reason "November register"
$APP reconcile-balances "${O[@]}" --file $OUT/expected-balances.csv      # should report that all balances match
```

- `convert-register` writes nothing unless every unit's total matches the sheet's `<year> Balance`.
- Importing the same file again reports "already imported" and changes nothing.
- An amount changed in the register becomes a correction. An amount set to 0 voids the transaction. History is never edited.
- Every register row for a unit needs a `transactionId` (format `2026-00001`), online payments included.
- Check a single unit with `$APP account-statement "${O[@]}" --unit "Unit 05"` and all units with `$APP list-balances "${O[@]}"`.

## Payments

- Locally the provider is **simulated**: no money moves. In production it is Stripe Connect, so each HOA's own Stripe account receives the money.
- Fee per method: `off` (not accepted), `0` (HOA absorbs), `3%`, `1.50` or `2.9%+0.30`. Only the dues ever reach the owner's balance.
- Statuses: `created` → `processing` (bank payments take days) → `succeeded`, or `failed` / `canceled`. Only `succeeded` changes the balance; `processing` shows as pending.

```bash
$APP configure-payments "${O[@]}" --provider simulated --merchant sim_channel_harbor \
  --card-fee 2.9%+0.30 --ach-fee 0                    # dry run; add --commit --reason "..."; --suspended true pauses payments
$APP payment-settings "${O[@]}"
$APP list-payments "${O[@]}" [--unit "Unit 05"]
$APP dev-payment-event --hoa channel-harbor --payment PAY-XXXXXXXX --status succeeded   # local only: play the provider
```

### Online payments into the register (monthly, before converting)

1. Export the month's received payments (outside the repository):

   ```bash
   $APP export-payments "${O[@]}" --from 2026-11-01 --to 2026-11-30 --out $OUT/payments.csv [--first-id 2026-00391]
   ```

2. Open the CSV, copy the data rows (not the header), click the first cell of the next empty register row, and use **Paste Special → Values + Skip blanks**. The formula columns stay intact.
3. Fill in the bank-side columns as usual (Posted Date, Account). Keep `Seebrook Ref`.
4. Run the monthly import as usual. The import reports "Online payments matched" and does not post them again. A wrong unit or amount on a `PAY-…` row rejects the file and says which row.

- The register sheet needs a `Seebrook Ref` column, added once as the last column.
- Why Skip blanks: the register's Posted Balance (both), Balance, Payee, Category, Topic, Year and Month columns are formulas (Category is looked up from the `ID` code; `101` is HOA Dues). The export leaves those columns blank, and pasting with Paste Special → Skip blanks keeps your formulas.

## Git workflow

- Branch from `develop` as `feature/*`, `chore/*`, `docs/*` and so on. Never commit to `develop` or `master`; local hooks block it. Enable them once per clone: `git config core.hooksPath .githooks`
- Commit titles start with a type: `[feature]`, `[update]`, `[bugfix]`, `[quality]`, `[security]`, `[docs]`, `[chore]`.
- Pull requests go into `develop`. Releases are a separate `develop` → `master` pull request.

```bash
git switch develop && git pull --ff-only     # after a merge
git branch --merged develop                  # local branches safe to delete
```

## Where things are documented

| Document | What's in it |
|---|---|
| `docs/ROADMAP.md` | Milestones M0–M7 |
| `docs/ENGINEERING_DECISIONS.md` | Engineering decisions (ED-xx) and pending decisions |
| `docs/DECISIONS.md` | Approved product decisions (GA-xx) |
| `docs/OPEN_QUESTIONS.md` | Questions still open and when they must be answered |
| `docs/REQUIREMENTS.md` | Product requirements |
| `README.md` | Setup and the full command list |
