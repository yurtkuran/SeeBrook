#!/usr/bin/env bash
# One-command local startup: database, mail catcher, migrations, synthetic data, then the API on http://localhost:8088.
#   scripts/dev.sh          start everything
#   scripts/dev.sh down     stop the local database (data is kept)
#   scripts/dev.sh reset    stop the local database and delete its data
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
COMPOSE=(docker compose -f "$ROOT/infra/local/docker-compose.yml")

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
  *)
    echo "Usage: scripts/dev.sh [up|down|reset]" >&2
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

echo "==> Starting API on http://localhost:${SEEBROOK_HTTP_PORT} (Ctrl+C to stop)"
exec "$APP" serve
