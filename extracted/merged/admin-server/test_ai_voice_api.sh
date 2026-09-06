#!/bin/bash
set -euo pipefail
export NO_COLOR=1
export FORCE_COLOR=0
export ADMIN_BOOTSTRAP_USER=admin
export ADMIN_BOOTSTRAP_PASSWORD='Test-Passw0rd-123'
export PUBLIC_APP_KEY='test-public-key'
export PORT=8790
cd "$(dirname "$0")"
rm -rf data-ai-test
export DATA_DIR="$PWD/data-ai-test"
node src/server.js >/tmp/acm-ai-voice-test.log 2>&1 &
PID=$!
trap 'kill $PID 2>/dev/null || true; rm -rf "$DATA_DIR"' EXIT
ready=0
for i in $(seq 1 50); do
  if curl -fsS "http://localhost:${PORT}/v1/health" >/dev/null 2>&1; then
    ready=1
    break
  fi
  sleep .2
done
if [ "$ready" -ne 1 ]; then
  echo "ERROR: Admin server did not become ready on port ${PORT}" >&2
  cat /tmp/acm-ai-voice-test.log >&2 || true
  exit 1
fi

pass=0; fail=0
check(){ if [ "$2" = "$3" ]; then pass=$((pass+1)); echo "PASS: $1"; else fail=$((fail+1)); echo "FAIL: $1 (got [$2] expected [$3])"; fi; }

LOGIN=$(curl -s -X POST http://localhost:${PORT}/v1/admin/login -H 'Content-Type: application/json' -d '{"username":"admin","password":"Test-Passw0rd-123"}')
TOKEN=$(echo "$LOGIN" | node -e "console.log(JSON.parse(require('fs').readFileSync(0,'utf8')).token)")
AUTH="Authorization: Bearer $TOKEN"

R=$(curl -s http://localhost:${PORT}/v1/admin/voice/recipients/+919876543210 -H "$AUTH")
check "unknown recipient is NOT_CONSENTED" "$(echo "$R" | node -e "console.log(JSON.parse(require('fs').readFileSync(0,'utf8')).recipient.consentStatus)")" "NOT_CONSENTED"

C=$(curl -s -X POST http://localhost:${PORT}/v1/admin/voice/consent -H "$AUTH" -H 'Content-Type: application/json' -d '{"phoneNumber":"+919876543210","status":"CONSENTED","source":"test","purpose":"testing"}')
check "consent can be recorded" "$(echo "$C" | node -e "console.log(JSON.parse(require('fs').readFileSync(0,'utf8')).ok)")" "true"

BLOCK=$(curl -s -X POST http://localhost:${PORT}/v1/voice/calls -H 'Content-Type: application/json' -H 'X-App-Key: test-public-key' -d '{"phoneNumber":"+919876543210","instruction":"Hello","scheduleEnabled":true}')
check "consented call reaches provider configuration gate" "$(echo "$BLOCK" | node -e "console.log(JSON.parse(require('fs').readFileSync(0,'utf8')).reason)")" "PROVIDER_NOT_CONFIGURED"

O=$(curl -s -X POST http://localhost:${PORT}/v1/admin/voice/opt-out -H "$AUTH" -H 'Content-Type: application/json' -d '{"phoneNumber":"+919876543210","source":"test_opt_out"}')
check "opt-out recorded" "$(echo "$O" | node -e "console.log(JSON.parse(require('fs').readFileSync(0,'utf8')).recipient.doNotCall)")" "true"

BLOCK2=$(curl -s -X POST http://localhost:${PORT}/v1/voice/calls -H 'Content-Type: application/json' -H 'X-App-Key: test-public-key' -d '{"phoneNumber":"+919876543210","instruction":"Hello","scheduleEnabled":true}')
check "opt-out blocks voice call" "$(echo "$BLOCK2" | node -e "console.log(JSON.parse(require('fs').readFileSync(0,'utf8')).reason)")" "DO_NOT_CALL"

BAD=$(curl -s -X POST http://localhost:${PORT}/v1/voice/calls -H 'Content-Type: application/json' -d '{"phoneNumber":"+919876543210","instruction":"Hello","scheduleEnabled":true}')
check "public voice call requires app key" "$(echo "$BAD" | node -e "console.log(JSON.parse(require('fs').readFileSync(0,'utf8')).ok)")" "false"

echo "RESULTS: $pass passed, $fail failed"
exit $fail
