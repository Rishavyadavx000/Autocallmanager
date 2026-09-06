#!/bin/bash
set -e
export NO_COLOR=1
export FORCE_COLOR=0
cd "$(dirname "$0")"
rm -rf data
rm -f /tmp/server.log

export ADMIN_BOOTSTRAP_USER=admin
export ADMIN_BOOTSTRAP_PASSWORD='Test-Passw0rd-123'
export PORT=8787

node src/server.js > /tmp/server.log 2>&1 &
SERVER_PID=$!
trap 'kill $SERVER_PID 2>/dev/null || true' EXIT

for i in $(seq 1 30); do
  if curl -s -o /dev/null http://localhost:8787/v1/health; then break; fi
  sleep 0.3
done

pass=0
fail=0
check() {
  # check "label" "actual" "expected"
  if [ "$2" = "$3" ]; then
    pass=$((pass+1))
    echo "PASS: $1"
  else
    fail=$((fail+1))
    echo "FAIL: $1  (got [$2] expected [$3])"
  fi
}

echo "=== health ==="
curl -s http://localhost:8787/v1/health

echo ""
echo "=== public catalog ==="
PUB=$(curl -s http://localhost:8787/v1/prompts)
COUNT=$(echo "$PUB" | node -e "console.log(JSON.parse(require('fs').readFileSync(0,'utf8')).prompts.length)")
check "public catalog has 5 seeded prompts" "$COUNT" "5"

echo "=== unauthenticated admin call ==="
CODE=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:8787/v1/admin/prompts)
check "unauthenticated /v1/admin/prompts -> 401" "$CODE" "401"

echo "=== wrong password ==="
CODE=$(curl -s -o /dev/null -w "%{http_code}" -X POST http://localhost:8787/v1/admin/login -H 'Content-Type: application/json' -d '{"username":"admin","password":"wrong"}')
check "wrong password -> 401" "$CODE" "401"

echo "=== login rate limiting (6 rapid wrong attempts) ==="
for i in 1 2 3 4 5 6; do
  LAST_CODE=$(curl -s -o /dev/null -w "%{http_code}" -X POST http://localhost:8787/v1/admin/login -H 'Content-Type: application/json' -d '{"username":"admin","password":"wrong"}')
done
check "6th rapid wrong attempt gets rate-limited (429)" "$LAST_CODE" "429"

echo "=== correct login after lockout should ALSO be blocked until window clears ==="
CODE=$(curl -s -o /dev/null -w "%{http_code}" -X POST http://localhost:8787/v1/admin/login -H 'Content-Type: application/json' -d '{"username":"admin","password":"Test-Passw0rd-123"}')
check "correct password still locked out (429)" "$CODE" "429"

# Reset for the rest of the suite by restarting the server (clears in-memory lockout).
kill $SERVER_PID 2>/dev/null || true
sleep 0.3
node src/server.js > /tmp/server2.log 2>&1 &
SERVER_PID=$!
trap 'kill $SERVER_PID 2>/dev/null || true' EXIT
for i in $(seq 1 30); do
  if curl -s -o /dev/null http://localhost:8787/v1/health; then break; fi
  sleep 0.3
done

LOGIN_JSON=$(curl -s -X POST http://localhost:8787/v1/admin/login -H 'Content-Type: application/json' -d '{"username":"admin","password":"Test-Passw0rd-123"}')
TOKEN=$(echo "$LOGIN_JSON" | node -e "console.log(JSON.parse(require('fs').readFileSync(0,'utf8')).token)")
check "login succeeded, token looks like hex" "$(echo -n "$TOKEN" | grep -Eq '^[0-9a-f]{64}$' && echo yes || echo no)" "yes"

AUTH="Authorization: Bearer $TOKEN"

echo "=== admin list with valid token ==="
ADMIN_LIST=$(curl -s http://localhost:8787/v1/admin/prompts -H "$AUTH")
ACOUNT=$(echo "$ADMIN_LIST" | node -e "console.log(JSON.parse(require('fs').readFileSync(0,'utf8')).prompts.length)")
check "admin list has 5 prompts with templates" "$ACOUNT" "5"

echo "=== create new prompt ==="
CREATE=$(curl -s -X POST http://localhost:8787/v1/prompts -H "$AUTH" -H 'Content-Type: application/json' \
  -d '{"id":"message.hinglish.v1","type":"MESSAGE_GENERATE","name":"Hinglish Reminder","maxWords":50,"variables":["instruction"],"template":"Banao ek chhota reminder: {{instruction}} (max {{maxWords}} words)"}')
CREATE_OK=$(echo "$CREATE" | node -e "console.log(JSON.parse(require('fs').readFileSync(0,'utf8')).ok)")
check "create prompt ok" "$CREATE_OK" "true"

echo "=== duplicate id rejected ==="
CODE=$(curl -s -o /dev/null -w "%{http_code}" -X POST http://localhost:8787/v1/prompts -H "$AUTH" -H 'Content-Type: application/json' \
  -d '{"id":"message.hinglish.v1","type":"X","name":"dup","template":"x"}')
check "duplicate prompt id -> 400" "$CODE" "400"

echo "=== public catalog now has 6 (new one enabled by default) ==="
COUNT2=$(curl -s http://localhost:8787/v1/prompts | node -e "console.log(JSON.parse(require('fs').readFileSync(0,'utf8')).prompts.length)")
check "public catalog now has 6 prompts" "$COUNT2" "6"

echo "=== disable the new prompt via PUT ==="
curl -s -X PUT http://localhost:8787/v1/prompts/message.hinglish.v1 -H "$AUTH" -H 'Content-Type: application/json' -d '{"enabled":false}' > /dev/null
COUNT3=$(curl -s http://localhost:8787/v1/prompts | node -e "console.log(JSON.parse(require('fs').readFileSync(0,'utf8')).prompts.length)")
check "public catalog back to 5 after disabling" "$COUNT3" "5"

echo "=== re-enable + create version 2 ==="
curl -s -X PUT http://localhost:8787/v1/prompts/message.hinglish.v1 -H "$AUTH" -H 'Content-Type: application/json' -d '{"enabled":true}' > /dev/null
VERSION2=$(curl -s -X POST http://localhost:8787/v1/prompts/message.hinglish.v1/versions -H "$AUTH" -H 'Content-Type: application/json' \
  -d '{"template":"Ek naya reminder banao: {{instruction}}"}')
V2=$(echo "$VERSION2" | node -e "console.log(JSON.parse(require('fs').readFileSync(0,'utf8')).prompt.version)")
check "new version is 2" "$V2" "2"

echo "=== version history has 2 entries ==="
HIST=$(curl -s http://localhost:8787/v1/admin/prompts/message.hinglish.v1/versions -H "$AUTH")
HCOUNT=$(echo "$HIST" | node -e "console.log(JSON.parse(require('fs').readFileSync(0,'utf8')).versions.length)")
check "version history length 2" "$HCOUNT" "2"

echo "=== rollback to version 1 ==="
ROLLBACK=$(curl -s -X POST http://localhost:8787/v1/prompts/message.hinglish.v1/rollback -H "$AUTH" -H 'Content-Type: application/json' -d '{"version":1}')
RV=$(echo "$ROLLBACK" | node -e "console.log(JSON.parse(require('fs').readFileSync(0,'utf8')).prompt.version)")
check "current version back to 1 after rollback" "$RV" "1"

echo "=== rollback to nonexistent version -> 404 ==="
CODE=$(curl -s -o /dev/null -w "%{http_code}" -X POST http://localhost:8787/v1/prompts/message.hinglish.v1/rollback -H "$AUTH" -H 'Content-Type: application/json' -d '{"version":99}')
check "rollback to bad version -> 404" "$CODE" "404"

echo "=== test-render endpoint (no GEMINI_API_KEY set -> render_only) ==="
TEST=$(curl -s -X POST http://localhost:8787/v1/prompts/message.hinglish.v1/test -H "$AUTH" -H 'Content-Type: application/json' -d '{"variables":{"instruction":"Call mummy at 6pm"}}')
echo "$TEST"
MODE=$(echo "$TEST" | node -e "console.log(JSON.parse(require('fs').readFileSync(0,'utf8')).mode)")
check "test mode is render_only without server key" "$MODE" "render_only"
RENDERED_HAS_VAR=$(echo "$TEST" | node -e "const d=JSON.parse(require('fs').readFileSync(0,'utf8')); console.log(d.rendered.includes('Call mummy at 6pm') ? 'yes' : 'no')")
check "rendered template substituted the variable" "$RENDERED_HAS_VAR" "yes"

echo "=== audit log recorded actions ==="
AUDIT=$(curl -s http://localhost:8787/v1/admin/audit -H "$AUTH")
ALEN=$(echo "$AUDIT" | node -e "console.log(JSON.parse(require('fs').readFileSync(0,'utf8')).entries.length > 5 ? 'yes' : 'no')")
check "audit log has multiple entries" "$ALEN" "yes"

echo "=== AI Voice recipient consent safety tests ==="
CONSENT_TEST=$(node test_voice_consent.js 2>&1)
echo "$CONSENT_TEST"
CONSENT_RESULT=$(echo "$CONSENT_TEST" | tail -1)
check "AI Voice consent unit tests" "$CONSENT_RESULT" "11 passed, 0 failed"

echo "=== logout invalidates token ==="
curl -s -X POST http://localhost:8787/v1/admin/logout -H "$AUTH" > /dev/null
CODE=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:8787/v1/admin/prompts -H "$AUTH")
check "token invalid after logout -> 401" "$CODE" "401"

echo ""
echo "============================================"
echo "RESULTS: $pass passed, $fail failed"
echo "============================================"

kill $SERVER_PID 2>/dev/null || true
exit $fail
