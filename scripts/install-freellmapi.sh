#!/usr/bin/env bash
# Installs and starts FreeLLMAPI (https://github.com/tashfeenahmed/freellmapi) on a PC / Raspberry Pi / VPS.
# Needs: git, Node.js >= 20.18 and < 25, npm >= 10, openssl.   Usage: install-freellmapi.sh [dir] [port]
# LAN access for your phone: HOST=0.0.0.0 ./install-freellmapi.sh   (default listens on 127.0.0.1 only)
set -euo pipefail
DIR="${1:-$HOME/freellmapi}"; PORT="${2:-3001}"; BIND="${HOST:-127.0.0.1}"
command -v git >/dev/null && command -v node >/dev/null && command -v npm >/dev/null || { echo "need git, node, npm"; exit 1; }
node -e 'const [a,b]=process.versions.node.split(".").map(Number); process.exit(a>=20&&a<25&&(a>20||b>=18)?0:1)' \
  || { echo "Node $(node -v) unsupported (need >=20.18 <25)"; exit 1; }
[ -d "$DIR/.git" ] || git clone --depth 1 https://github.com/tashfeenahmed/freellmapi.git "$DIR"
cd "$DIR"
npm install
[ -f .env ] || cp .env.example .env
grep -q '^ENCRYPTION_KEY=[0-9a-f]\{64\}$' .env || \
  sed -i.bak "s|^ENCRYPTION_KEY=.*|ENCRYPTION_KEY=$(openssl rand -hex 32)|" .env
sed -i.bak "s|^PORT=.*|PORT=$PORT|" .env
npm run build -w server
npm run build -w client            # REQUIRED: the server crashes on a dashboard request without client/dist
if curl -fs -m3 "http://127.0.0.1:$PORT/api/ping" >/dev/null 2>&1; then echo "Already running on port $PORT - not starting a second copy."; exit 0; fi
( cd "$DIR/server" && HOST="$BIND" PORT="$PORT" setsid nohup node dist/index.js >"$DIR/freellmapi.log" 2>&1 </dev/null & )
for i in $(seq 1 30); do curl -fs -m2 "http://127.0.0.1:$PORT/api/ping" >/dev/null 2>&1 && break; sleep 1; done
curl -fs "http://127.0.0.1:$PORT/api/ping" && echo || { echo "did not start; see $DIR/freellmapi.log"; exit 1; }
cat <<MSG

FreeLLMAPI is running on port $PORT.
Next (cannot be automated - these are your accounts):
  1. Open http://127.0.0.1:$PORT in a browser ON THIS MACHINE and create the dashboard account
     (from another device it needs the one-time setup code printed in $DIR/freellmapi.log).
  2. Keys page: add at least one provider key (Groq, Google AI Studio, Cerebras, ... all have free tiers).
  3. Keys page: copy the unified key (freellmapi-...) into JARVIS > Settings > API key.
  4. Verify:  scripts/verify-freellmapi.sh http://127.0.0.1:$PORT <unified-key>
MSG
