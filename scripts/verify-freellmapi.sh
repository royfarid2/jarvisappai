#!/usr/bin/env bash
# Real end-to-end checks against a running FreeLLMAPI. Usage: verify-freellmapi.sh <base-url> <unified-key> [model]
# Prints PASS/FAIL per check and exits non-zero if any fails. Nothing is simulated.
BASE="${1:?base url, e.g. http://127.0.0.1:3001}"; KEY="${2:?unified key}"; MODEL="${3:-auto}"
fail=0; ok(){ echo "PASS  $1"; }; ko(){ echo "FAIL  $1"; fail=1; }
code(){ curl -s -m "${T:-15}" -o /tmp/vf.out -w '%{http_code}' "$@"; }
[ "$(code "$BASE/api/ping")" = 200 ] && grep -q '"ok"' /tmp/vf.out && ok "health  GET /api/ping" || ko "health  GET /api/ping"
[ "$(code "$BASE/v1/models")" = 401 ] && ok "auth    /v1/models rejects missing key" || ko "auth    /v1/models should be 401 without key"
[ "$(code "$BASE/v1/models" -H "Authorization: Bearer freellmapi-wrong")" = 401 ] && ok "auth    wrong key rejected" || ko "auth    wrong key should be 401"
if [ "$(code "$BASE/v1/models" -H "Authorization: Bearer $KEY")" = 200 ]; then
  ok "models  GET /v1/models ($(node -pe 'JSON.parse(require("fs").readFileSync("/tmp/vf.out")).data.length' 2>/dev/null || echo '?') models)"
else ko "models  GET /v1/models with key"; fi
BODY="{\"model\":\"$MODEL\",\"messages\":[{\"role\":\"user\",\"content\":\"Reply with the single word: ready\"}]}"
T=60 c=$(code "$BASE/v1/chat/completions" -H "Authorization: Bearer $KEY" -H 'content-type: application/json' -d "$BODY")
if [ "$c" = 200 ] && grep -q '"choices"' /tmp/vf.out; then ok "chat    authenticated completion"
else ko "chat    HTTP $c: $(head -c 220 /tmp/vf.out)"; fi
SBODY="${BODY%\}},\"stream\":true}"
n=$(curl -sN -m 60 "$BASE/v1/chat/completions" -H "Authorization: Bearer $KEY" -H 'content-type: application/json' -d "$SBODY" | tee /tmp/vf.sse | grep -c '^data: {')
if [ "$n" -ge 2 ] && grep -q '\[DONE\]' /tmp/vf.sse; then ok "stream  $n SSE chunks then [DONE]"
else ko "stream  got $n chunks: $(head -c 220 /tmp/vf.sse)"; fi
exit $fail
