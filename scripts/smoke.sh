#!/usr/bin/env bash
# End-to-end smoke test against a running payguard (docker compose up): real tokens, real HTTP, real database.
# Prints a GitHub annotation for the first check that fails.
set -euo pipefail
BASE=${BASE:-http://localhost:8080}
RUN="smoke-$(date +%s)-$RANDOM"

token() { java scripts/DevToken.java --sub "$1" --scope "$2" 2>/dev/null; }
field() { python3 -c "import json,sys; d=json.load(sys.stdin); print(d$1)"; }
fail() { echo "::error title=smoke test::$1"; exit 1; }

# request METHOD PATH TOKEN [JSON] [EXTRA_HEADER]: prints the body; the status goes to $STATUS_FILE
STATUS_FILE=$(mktemp)
request() {
  local args=(-sS -o /tmp/smoke_body -w '%{http_code}' -X "$1" "$BASE$2" -H "Content-Type: application/json")
  [ -n "$3" ] && args+=(-H "Authorization: Bearer $3")
  [ -n "${4:-}" ] && args+=(-d "$4")
  [ -n "${5:-}" ] && args+=(-H "$5")
  curl "${args[@]}" > "$STATUS_FILE"
  cat /tmp/smoke_body
}
status() { cat "$STATUS_FILE"; }

CUSTOMER=$(token "$RUN" payments)
REVIEWER=$(token "reviewer-$RUN" reviews)

echo "1. health is up"
request GET /actuator/health "" > /dev/null; [ "$(status)" = 200 ] || fail "health returned $(status)"

echo "2. no token is refused"
request GET /v1/payees "" > /dev/null; [ "$(status)" = 401 ] || fail "expected 401 without a token, got $(status)"

echo "3. a payee whose name matches; a usual payment is released"
JOHN=$(request POST /v1/payees "$CUSTOMER" '{"name":"John Smith","sortCode":"12-34-56","accountNumber":"12345678"}')
[ "$(status)" = 201 ] || fail "register payee returned $(status): $JOHN"
[ "$(echo "$JOHN" | field "['nameCheck']")" = MATCH ] || fail "expected MATCH: $JOHN"
JOHN_ID=$(echo "$JOHN" | field "['id']")
KEY=$(python3 -c "import uuid; print(uuid.uuid4())")
PAID=$(request POST /v1/payments "$CUSTOMER" "{\"payeeId\":\"$JOHN_ID\",\"amountPence\":2500,\"reference\":\"DINNER\"}" "Idempotency-Key: $KEY")
[ "$(status)" = 201 ] || fail "payment returned $(status): $PAID"
[ "$(echo "$PAID" | field "['status']")" = RELEASED ] || fail "expected RELEASED: $PAID"

echo "4. the same request again is a replay, not a second payment"
AGAIN=$(request POST /v1/payments "$CUSTOMER" "{\"payeeId\":\"$JOHN_ID\",\"amountPence\":2500,\"reference\":\"DINNER\"}" "Idempotency-Key: $KEY")
[ "$(status)" = 200 ] || fail "replay returned $(status): $AGAIN"
[ "$(echo "$AGAIN" | field "['id']")" = "$(echo "$PAID" | field "['id']")" ] || fail "replay returned a different payment"

echo "5. a large payment to a name that does not match is held, and a reviewer releases it"
WRONG=$(request POST /v1/payees "$CUSTOMER" '{"name":"Mike Jones","sortCode":"20-30-40","accountNumber":"33334444"}')
[ "$(echo "$WRONG" | field "['nameCheck']")" = NO_MATCH ] || fail "expected NO_MATCH: $WRONG"
HELD=$(request POST /v1/payments "$CUSTOMER" "{\"payeeId\":\"$(echo "$WRONG" | field "['id']")\",\"amountPence\":400000,\"reference\":\"INVOICE 77\"}" "Idempotency-Key: $(python3 -c "import uuid; print(uuid.uuid4())")")
[ "$(echo "$HELD" | field "['status']")" = HELD ] || fail "expected HELD: $HELD"
HELD_ID=$(echo "$HELD" | field "['id']")
DECIDED=$(request POST "/v1/reviews/$HELD_ID" "$REVIEWER" '{"decision":"APPROVE","reason":"confirmed with the supplier on a known number"}')
[ "$(status)" = 200 ] || fail "review returned $(status): $DECIDED"
[ "$(echo "$DECIDED" | field "['status']")" = RELEASED ] || fail "expected RELEASED after approval: $DECIDED"

echo "6. a customer token cannot use the review queue"
request GET /v1/reviews "$CUSTOMER" > /dev/null; [ "$(status)" = 403 ] || fail "expected 403, got $(status)"

echo "smoke test passed"
