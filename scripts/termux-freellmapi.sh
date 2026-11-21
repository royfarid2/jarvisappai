#!/data/data/com.termux/files/usr/bin/bash
# Installs and runs FreeLLMAPI (github.com/tashfeenahmed/freellmapi) inside Termux on your phone.
# Usage:  bash termux-freellmapi.sh install   (once)
#         bash termux-freellmapi.sh start | stop | status | key
set -e
DIR="$HOME/freellmapi"
case "${1:-}" in
  install)
    pkg update -y
    pkg install -y nodejs-lts git python make clang binutils openssl
    [ -d "$DIR" ] || git clone --depth 1 https://github.com/tashfeenahmed/freellmapi.git "$DIR"
    cd "$DIR"
    npm install --no-audit --no-fund
    npm run build
    if [ ! -f .env ]; then
      cp .env.example .env
      KEY=$(node -e "console.log(require('crypto').randomBytes(32).toString('hex'))")
      sed -i "s/^ENCRYPTION_KEY=.*/ENCRYPTION_KEY=$KEY/" .env
      sed -i "s/^PORT=.*/PORT=3001\nHOST=127.0.0.1/" .env
    fi
    echo "Installed. Now run:  bash $0 start"
    echo "Then open http://127.0.0.1:3001 in your phone browser, create the account, add your Groq (or other) provider keys,"
    echo "copy the unified key (freellmapi-...) from the Keys page, and paste it in JARVIS > Settings > AI provider > FreeLLMAPI."
    ;;
  start)
    cd "$DIR/server"
    if curl -s -m 2 http://127.0.0.1:3001/api/auth/status >/dev/null; then echo "Already running."; exit 0; fi
    nohup node dist/index.js > "$HOME/freellmapi.log" 2>&1 &
    echo $! > "$HOME/freellmapi.pid"
    sleep 4; curl -s -m 3 http://127.0.0.1:3001/api/auth/status >/dev/null && echo "Running on http://127.0.0.1:3001" || { echo "Did not start, see ~/freellmapi.log"; exit 1; }
    ;;
  stop) [ -f "$HOME/freellmapi.pid" ] && kill "$(cat "$HOME/freellmapi.pid")" && rm -f "$HOME/freellmapi.pid" && echo stopped || echo "not running" ;;
  status) curl -s -m 3 http://127.0.0.1:3001/api/auth/status >/dev/null && echo running || echo stopped ;;
  *) echo "Usage: bash $0 install|start|stop|status"; exit 1 ;;
esac
