#!/usr/bin/env bash
# One-command local startup: database, mail catcher, migrations, synthetic data, then the API on http://localhost:8088.
#   scripts/dev.sh          start everything (sign-in with development tokens, simulated payments)
#   scripts/dev.sh firebase start everything, signing in with the development Firebase project (ED-50)
#   scripts/dev.sh stripe   start everything with Stripe test mode (ED-58): keys from .env.stripe, webhooks
#                           forwarded by the Stripe CLI, and the local Channel Harbor paid into the test account
#   scripts/dev.sh down     stop the local database (data is kept)
#   scripts/dev.sh reset    stop the local database and delete its data
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
COMPOSE=(docker compose -f "$ROOT/infra/local/docker-compose.yml")
STRIPE=false

case "${1:-up}" in
  down)
    "${COMPOSE[@]}" down
    exit 0
    ;;
  reset)
    "${COMPOSE[@]}" down -v
    exit 0
    ;;
  up) ;;
  firebase)
    # The project ID comes from the app's Firebase file (kept out of git), unless set already.
    PLIST="$ROOT/mobile-ios/Config/Firebase/Debug/GoogleService-Info.plist"
    if [ -z "${SEEBROOK_FIREBASE_PROJECT_ID:-}" ]; then
      if [ ! -f "$PLIST" ]; then
        echo "No $PLIST; set SEEBROOK_FIREBASE_PROJECT_ID instead" >&2
        exit 2
      fi
      SEEBROOK_FIREBASE_PROJECT_ID="$(/usr/libexec/PlistBuddy -c 'Print :PROJECT_ID' "$PLIST")"
    fi
    export SEEBROOK_AUTH_MODE=firebase SEEBROOK_FIREBASE_PROJECT_ID
    ;;
  stripe)
    # Test keys and the test connected account live in .env.stripe (kept out of git); never printed.
    ENV_FILE="$ROOT/.env.stripe"
    if [ ! -f "$ENV_FILE" ]; then
      echo "No $ENV_FILE (see docs/QUICK_REFERENCE.md, Stripe test mode)" >&2
      exit 2
    fi
    set -a
    # shellcheck disable=SC1090
    . "$ENV_FILE"
    set +a
    for name in SEEBROOK_STRIPE_SECRET_KEY SEEBROOK_STRIPE_PUBLISHABLE_KEY SEEBROOK_STRIPE_TEST_ACCOUNT; do
      if [ -z "${!name:-}" ]; then echo "$name is missing from $ENV_FILE" >&2; exit 2; fi
    done
    case "$SEEBROOK_STRIPE_SECRET_KEY" in
      sk_test_*|rk_test_*) ;;
      *) echo "SEEBROOK_STRIPE_SECRET_KEY must be a test-mode key (sk_test_ or rk_test_) for local development" >&2; exit 2 ;;
    esac
    case "$SEEBROOK_STRIPE_PUBLISHABLE_KEY" in
      pk_test_*) ;;
      *) echo "SEEBROOK_STRIPE_PUBLISHABLE_KEY must be a test-mode key (pk_test_)" >&2; exit 2 ;;
    esac
    if ! command -v stripe >/dev/null; then
      echo "The Stripe CLI is needed to forward webhooks: brew install stripe/stripe-cli/stripe, then stripe login" >&2
      exit 2
    fi
    STRIPE=true
    ;;
  *)
    echo "Usage: scripts/dev.sh [up|firebase|stripe|down|reset]" >&2
    exit 2
    ;;
esac

echo "==> Starting local PostgreSQL, Mailpit (emails: http://localhost:8025), S3Mock and ClamAV"
"${COMPOSE[@]}" up -d --wait

echo "==> Building the backend"
"$ROOT/gradlew" -q -p "$ROOT" :server:app:installDist

export SEEBROOK_ENV=development
export SEEBROOK_HTTP_PORT="${SEEBROOK_HTTP_PORT:-8088}"
APP="$ROOT/server/app/build/install/seebrook/bin/seebrook"

echo "==> Applying migrations and loading synthetic Channel Harbor data"
"$APP" seed-dev

if ! $STRIPE; then
  echo "==> Starting API on http://localhost:${SEEBROOK_HTTP_PORT} (Ctrl+C to stop)"
  exec "$APP" serve
fi

# --- Stripe test mode -----------------------------------------------------------------------------
# Payments are direct charges on the HOA's connected account (ED-43), so their events arrive as
# Connect events; the CLI forwards exactly the events the server handles. It uses the account
# `stripe login` connected, which must be the platform account the secret key belongs to.
EVENTS="payment_intent.processing,payment_intent.succeeded,payment_intent.payment_failed,payment_intent.canceled,refund.created,refund.failed,charge.dispute.funds_withdrawn,charge.dispute.funds_reinstated"
WEBHOOK="http://localhost:${SEEBROOK_HTTP_PORT}/v1/webhooks/payments/stripe"
export SEEBROOK_PAYMENTS_MODE=stripe
SEEBROOK_STRIPE_WEBHOOK_SECRET="$(stripe listen --print-secret)"
export SEEBROOK_STRIPE_WEBHOOK_SECRET

# Point the local Channel Harbor at the test connected account (audited, like any change), unless it is already.
OPERATOR="${SEEBROOK_DEV_OPERATOR:-root@example.test}"
if ! "$APP" payment-settings --operator "$OPERATOR" --hoa channel-harbor 2>/dev/null | grep -q "^stripe merchant $SEEBROOK_STRIPE_TEST_ACCOUNT;"; then
  echo "==> Setting the local Channel Harbor to take payments into the Stripe test account (operator $OPERATOR)"
  "$APP" configure-payments --operator "$OPERATOR" --hoa channel-harbor --provider stripe \
    --merchant "$SEEBROOK_STRIPE_TEST_ACCOUNT" --card-fee 2.9%+0.30 --ach-fee 0 \
    --commit --reason "Local Stripe test mode" | tail -1 || {
      echo "Could not configure payments; set SEEBROOK_DEV_OPERATOR to a local Root operator's email" >&2
      exit 2
    }
fi

LOG="$ROOT/build/stripe-listen.log"
mkdir -p "$ROOT/build"
echo "==> Forwarding Stripe test webhooks to $WEBHOOK (log: build/stripe-listen.log)"
stripe listen --forward-connect-to "$WEBHOOK" --events "$EVENTS" >"$LOG" 2>&1 &
LISTENER=$!
trap 'kill "$LISTENER" 2>/dev/null || true' EXIT INT TERM

echo "==> Starting API on http://localhost:${SEEBROOK_HTTP_PORT} with Stripe test mode (Ctrl+C to stop)"
echo "    Back to simulated payments: scripts/dev.sh, then configure-payments ... --provider simulated"
"$APP" serve
