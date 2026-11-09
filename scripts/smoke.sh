#!/usr/bin/env bash
# Boundary check usable locally and on the server.
# Usage: scripts/smoke.sh [BASE_URL]
# Optional env: SMOKE_WEBHOOK_SECRET (signed ping), SMOKE_WAIT_SECONDS (default 120).
set -u

BASE="${1:-http://localhost}"
BASE="${BASE%/}"
WAIT="${SMOKE_WAIT_SECONDS:-120}"
FAILS=0

code() { curl -s --max-time 10 -o /dev/null -w '%{http_code}' "$@"; }

check() { # check <name> <command...>; the command succeeds when the check passes
  local name="$1"; shift
  if "$@"; then echo "PASS $name"; else echo "FAIL $name"; FAILS=$((FAILS + 1)); fi
}

sign() { # sign <secret> <body> -> sha256=<hex>
  printf 'sha256=%s' "$(printf '%s' "$2" | openssl dgst -sha256 -hmac "$1" | awk '{print $NF}')"
}

ping() { # ping <secret>; prints "<status> <body>"
  local body='{"zen":"smoke"}'
  curl -s --max-time 10 -w ' %{http_code}' -X POST "$BASE/webhooks/github" \
    -H 'Content-Type: application/json' -H 'X-GitHub-Event: ping' \
    -H "X-GitHub-Delivery: smoke-$(date +%s)" \
    -H "X-Hub-Signature-256: $(sign "$1" "$body")" -d "$body"
}

# Wait until the app answers (000 means the connection is not up yet).
ready=0
for _ in $(seq 1 $((WAIT / 2))); do
  s=$(code -X POST "$BASE/webhooks/github")
  case "$s" in 000) sleep 2 ;; *) ready=1; break ;; esac
done
[ "$ready" = 1 ] || { echo "FAIL not ready within ${WAIT}s"; echo "smoke: FAIL (1)"; exit 1; }

hist=$(curl -s --max-time 10 -w ' %{http_code}' "$BASE/api/history")
check "history answers" bash -c '[ "${1##* }" = 200 ] && [[ "$1" == \{\"items\"* ]]' _ "$hist"
page=$(curl -s --max-time 10 -w ' %{http_code}' "$BASE/")
check "screen answers" bash -c '[ "${1##* }" = 200 ] && grep -q "id=\"root\"" <<<"$1"' _ "$page"
asset=$(grep -o '/assets/[^"]*\.js' <<<"$page" | head -n1)
check "screen asset answers" test -n "$asset" -a "$(code "$BASE$asset")" = 200

if [ -n "${SMOKE_WEBHOOK_SECRET:-}" ]; then
  signed=$(ping "$SMOKE_WEBHOOK_SECRET")
  check "signed ping accepted" bash -c '[ "${1##* }" = 200 ] && grep -q ignored <<<"$1"' _ "$signed"
fi

if [ "$FAILS" -gt 0 ]; then echo "smoke: FAIL ($FAILS)"; exit 1; fi
echo "smoke: PASS"
