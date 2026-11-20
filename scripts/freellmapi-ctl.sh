#!/data/data/com.termux/files/usr/bin/bash
# Termux-side controller for FreeLLMAPI. Copy to ~/freellmapi-ctl.sh and `chmod +x` it.
#   freellmapi-ctl.sh setup | start | stop | restart | status
# FreeLLMAPI is Node.js + better-sqlite3 (a native module). It builds on many Termux installs but that is
# not guaranteed; if `setup` fails at npm install, run FreeLLMAPI on a PC/Pi/Docker host instead.
set -u
DIR="$HOME/freellmapi"
PIDF="$HOME/.freellmapi.pid"
LOG="$HOME/freellmapi.log"
PORT="${PORT:-3001}"

running() { [ -f "$PIDF" ] && kill -0 "$(cat "$PIDF")" 2>/dev/null; }

case "${1:-status}" in
  setup)
    pkg update -y && pkg install -y nodejs git python make clang binutils || exit 1
    [ -d "$DIR/.git" ] || git clone --depth 1 https://github.com/tashfeenahmed/freellmapi.git "$DIR" || exit 1
    cd "$DIR" || exit 1
    npm install || exit 1
    [ -f .env ] || cp .env.example .env
    grep -q '^ENCRYPTION_KEY=[0-9a-f]\{64\}$' .env || \
      sed -i "s|^ENCRYPTION_KEY=.*|ENCRYPTION_KEY=$(node -e 'console.log(require("crypto").randomBytes(32).toString("hex"))')|" .env
    npm run build -w server && npm run build -w client
    echo "Setup done. Run: $0 start   then open http://127.0.0.1:$PORT to create the account and add provider keys." ;;
  start)
    running && { echo "already running (pid $(cat "$PIDF"))"; exit 0; }   # never start a second instance
    cd "$DIR/server" || { echo "not installed: run setup"; exit 1; }
    HOST=127.0.0.1 PORT="$PORT" nohup node dist/index.js >>"$LOG" 2>&1 &
    echo $! >"$PIDF"; echo "started pid $(cat "$PIDF")" ;;
  stop)
    running && kill "$(cat "$PIDF")" && rm -f "$PIDF" && echo stopped || echo "not running" ;;
  restart) "$0" stop; sleep 1; "$0" start ;;
  status) running && echo "running pid $(cat "$PIDF")" || echo "stopped" ;;
  *) echo "usage: $0 setup|start|stop|restart|status"; exit 2 ;;
esac
