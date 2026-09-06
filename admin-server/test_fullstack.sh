#!/bin/bash
set -e
export NO_COLOR=1
export FORCE_COLOR=0
cd "$(dirname "$0")"
rm -rf data
rm -f /tmp/server3.log

export ADMIN_BOOTSTRAP_USER=admin
export ADMIN_BOOTSTRAP_PASSWORD='Test-Passw0rd-123'
export PORT=8787

node src/server.js > /tmp/server3.log 2>&1 &
SERVER_PID=$!
trap 'kill $SERVER_PID 2>/dev/null || true' EXIT

for i in $(seq 1 30); do
  if curl -s -o /dev/null http://localhost:8787/v1/health; then break; fi
  sleep 0.3
done

pass=0; fail=0
check(){ if [ "$2" = "$3" ]; then pass=$((pass+1)); echo "PASS: $1"; else fail=$((fail+1)); echo "FAIL: $1 (got [$2] expected [$3])"; fi; }

echo "=== static admin UI serving ==="
CODE=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:8787/admin)
check "GET /admin -> 200" "$CODE" "200"
CT=$(curl -s -D - -o /dev/null http://localhost:8787/admin | grep -i "content-type" | tr -d '\r')
echo "content-type: $CT"

CODE2=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:8787/admin/)
check "GET /admin/ (trailing slash) -> 200" "$CODE2" "200"

echo "=== path traversal attempt should NOT escape ADMIN_DIR ==="
CODE3=$(curl -s -o /dev/null -w "%{http_code}" "http://localhost:8787/admin/../../../etc/passwd")
echo "traversal attempt status: $CODE3 (curl/http normalizes .. before reaching us in most cases)"
BODY=$(curl -s "http://localhost:8787/admin/%2e%2e/%2e%2e/%2e%2e/etc/passwd")
HAS_ROOT=$(echo "$BODY" | grep -c "root:" || true)
check "encoded traversal does not leak /etc/passwd" "$HAS_ROOT" "0"

echo "=== unknown static path -> 404 ==="
CODE4=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:8787/admin/does-not-exist.js)
check "unknown admin asset -> 404" "$CODE4" "404"

echo "=== full browser-style session via fetch-equivalent curl calls ==="
LOGIN=$(curl -s -X POST http://localhost:8787/v1/admin/login -H 'Content-Type: application/json' -d '{"username":"admin","password":"Test-Passw0rd-123"}')
TOKEN=$(echo "$LOGIN" | node -e "console.log(JSON.parse(require('fs').readFileSync(0,'utf8')).token)")
AUTH="Authorization: Bearer $TOKEN"
check "login ok" "$(echo "$LOGIN" | node -e "console.log(JSON.parse(require('fs').readFileSync(0,'utf8')).ok)")" "true"

CREATE=$(curl -s -X POST http://localhost:8787/v1/prompts -H "$AUTH" -H 'Content-Type: application/json' \
  -d '{"id":"message.exam.v1","type":"MESSAGE_GENERATE","name":"Exam Reminder","maxWords":45,"variables":["instruction"],"template":"Exam reminder: {{instruction}} (under {{maxWords}} words)"}')
check "create exam prompt ok" "$(echo "$CREATE" | node -e "console.log(JSON.parse(require('fs').readFileSync(0,'utf8')).ok)")" "true"

V2=$(curl -s -X POST http://localhost:8787/v1/prompts/message.exam.v1/versions -H "$AUTH" -H 'Content-Type: application/json' -d '{"template":"Naya version: {{instruction}}"}')
check "version bump to 2" "$(echo "$V2" | node -e "console.log(JSON.parse(require('fs').readFileSync(0,'utf8')).prompt.version)")" "2"

TEST=$(curl -s -X POST http://localhost:8787/v1/prompts/message.exam.v1/test -H "$AUTH" -H 'Content-Type: application/json' -d '{"variables":{"instruction":"UPSC prelims tomorrow"}}')
RENDERED_OK=$(echo "$TEST" | node -e "const d=JSON.parse(require('fs').readFileSync(0,'utf8')); console.log(d.rendered.includes('UPSC prelims tomorrow')?'yes':'no')")
check "test render includes sample instruction" "$RENDERED_OK" "yes"

ROLLBACK=$(curl -s -X POST http://localhost:8787/v1/prompts/message.exam.v1/rollback -H "$AUTH" -H 'Content-Type: application/json' -d '{"version":1}')
check "rollback to v1" "$(echo "$ROLLBACK" | node -e "console.log(JSON.parse(require('fs').readFileSync(0,'utf8')).prompt.version)")" "1"

FINAL_PUBLIC=$(curl -s http://localhost:8787/v1/prompts)
FCOUNT=$(echo "$FINAL_PUBLIC" | node -e "console.log(JSON.parse(require('fs').readFileSync(0,'utf8')).prompts.length)")
check "public catalog now includes the new prompt (6 total)" "$FCOUNT" "6"

# Simulate what the Android client will actually do: fetch, then verify it still
# matches the PromptStore.parse() expectations (schemaVersion + prompts[] with required fields).
SCHEMA_OK=$(echo "$FINAL_PUBLIC" | node -e "
const d=JSON.parse(require('fs').readFileSync(0,'utf8'));
const ok = d.schemaVersion===1 && Array.isArray(d.prompts) && d.prompts.every(p=>p.id&&p.template===undefined?false:true || true);
// PromptStore.parse requires id + template on each entry; public catalog omits template by design (client only needs id/type/name/etc to populate dropdown,
// template rendering happens server-side isn't true here -- rendering happens ON DEVICE, so template MUST be present. Check that explicitly:
const hasTemplate = d.prompts.every(p => typeof p.template === 'string' && p.template.length>0);
console.log(hasTemplate ? 'yes' : 'no');
")
check "every public prompt includes a non-empty template (required for on-device rendering)" "$SCHEMA_OK" "yes"

echo ""
echo "============================================"
echo "RESULTS: $pass passed, $fail failed"
echo "============================================"
kill $SERVER_PID 2>/dev/null || true
exit $fail
