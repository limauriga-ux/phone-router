#!/system/bin/sh
set -eu

PORT="${PHONE_ROUTER_API_PORT:-19777}"
STATE_DIR="/data/local/phone-router"
PID_FILE="$STATE_DIR/api.pid"
HANDLER="/data/local/tmp/phone-router-api-handler.sh"

mkdir -p "$STATE_DIR"

is_running() {
  [ -f "$PID_FILE" ] || return 1
  pid="$(cat "$PID_FILE" 2>/dev/null || true)"
  [ -n "$pid" ] || return 1
  kill -0 "$pid" 2>/dev/null
}

start() {
  if is_running; then
    echo "phone-router API already running on 127.0.0.1:$PORT"
    exit 0
  fi

  nohup "$0" serve >/dev/null 2>&1 &
  sleep 1

  if is_running; then
    echo "phone-router API started on 127.0.0.1:$PORT"
  else
    echo "failed to start phone-router API" >&2
    exit 1
  fi
}

serve() {
  echo "$$" > "$PID_FILE"
  exec nc -s 127.0.0.1 -p "$PORT" -L "$HANDLER"
}

stop() {
  if is_running; then
    kill "$(cat "$PID_FILE")" 2>/dev/null || true
  fi
  rm -f "$PID_FILE"
  pkill -f "nc -s 127.0.0.1 -p $PORT" 2>/dev/null || true
  echo "phone-router API stopped"
}

status() {
  if is_running; then
    echo "running on 127.0.0.1:$PORT pid=$(cat "$PID_FILE")"
  else
    echo "stopped"
  fi
}

case "${1:-start}" in
  start) start ;;
  serve) serve ;;
  stop) stop ;;
  restart) stop; start ;;
  status) status ;;
  *) echo "usage: $0 start|stop|restart|status" >&2; exit 2 ;;
esac
