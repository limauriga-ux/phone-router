#!/system/bin/sh
set -eu

ROUTERD="${ROUTERD:-/data/local/tmp/phone-routerd.sh}"

send_response() {
  code="$1"
  title="$2"
  body="$3"
  payload="$(printf '%s\n' "$body")"
  length="$(printf '%s' "$payload" | wc -c | tr -d ' ')"
  printf 'HTTP/1.1 %s %s\r\n' "$code" "$title"
  printf 'Content-Type: text/plain; charset=utf-8\r\n'
  printf 'Content-Length: %s\r\n' "$length"
  printf 'Cache-Control: no-store\r\n'
  printf 'Connection: close\r\n'
  printf '\r\n'
  printf '%s' "$payload"
}

valid_arg() {
  case "$1" in
    *[!A-Za-z0-9:._-]*|'') return 1 ;;
    *) return 0 ;;
  esac
}

valid_path_arg() {
  case "$1" in
    *[!A-Za-z0-9:._/-]*|'') return 1 ;;
    *) return 0 ;;
  esac
}

run_router() {
  if [ ! -x "$ROUTERD" ]; then
    send_response 500 ERROR "router script not installed: $ROUTERD"
    return
  fi

  output="$("$ROUTERD" "$@" 2>&1 || true)"
  send_response 200 OK "$output"
}

IFS=' ' read -r method path proto || true
CR="$(printf '\r')"
while IFS= read -r header; do
  header="${header%$CR}"
  [ -n "$header" ] || break
done

case "${method:-}" in
  GET|POST) ;;
  *)
    send_response 405 METHOD_NOT_ALLOWED "Only GET and POST are supported"
    exit 0
    ;;
esac

path="${path%%\?*}"

case "$path" in
  /|/status) run_router status ;;
  /summary) run_router summary ;;
  /ap-status) run_router ap-status ;;
  /tether-status) run_router tether-status ;;
  /stop-ap) run_router stop-ap ;;
  /enable-tether) run_router enable-tether ;;
  /disable-tether) run_router disable-tether ;;
  /clients) run_router clients ;;
  /blocks) run_router blocks ;;
  /prepare-clash-ports) run_router prepare-clash-ports ;;
  /singbox-start) run_router singbox-start ;;
  /singbox-stop) run_router singbox-stop ;;
  /singbox-status) run_router singbox-status ;;
  /singbox-log) run_router singbox-log ;;
  /singbox-nodes) run_router singbox-nodes ;;
  /singbox-config-info) run_router singbox-config-info ;;
  /proxy-start) run_router proxy-start ;;
  /proxy-global) run_router proxy-global ;;
  /proxy-rule) run_router proxy-rule ;;
  /proxy-stop) run_router proxy-stop ;;
  /enable-tproxy) run_router enable-tproxy ;;
  /enable-redir) run_router enable-redir ;;
  /disable) run_router disable ;;
  /pause-all) run_router pause-all ;;
  /resume-all) run_router resume-all ;;
  /start-ap/*)
    args="${path#/start-ap/}"
    oldifs="$IFS"
    IFS='/'
    set -- $args
    IFS="$oldifs"
    valid_arg "${1:-}" || { send_response 400 BAD_REQUEST "invalid SSID"; exit 0; }
    valid_arg "${2:-}" || { send_response 400 BAD_REQUEST "invalid passphrase"; exit 0; }
    valid_arg "${3:-2}" || { send_response 400 BAD_REQUEST "invalid band"; exit 0; }
    run_router start-ap "$1" "$2" "${3:-2}"
    ;;
  /singbox-set-node/*)
    value="${path#/singbox-set-node/}"
    valid_arg "$value" || { send_response 400 BAD_REQUEST "invalid node"; exit 0; }
    run_router singbox-set-node "$value"
    ;;
  /singbox-import-config/*)
    value="${path#/singbox-import-config/}"
    value="/$value"
    valid_path_arg "$value" || { send_response 400 BAD_REQUEST "invalid config path"; exit 0; }
    run_router singbox-import-config "$value"
    ;;
  /block-mac/*)
    value="${path#/block-mac/}"
    valid_arg "$value" || { send_response 400 BAD_REQUEST "invalid MAC"; exit 0; }
    run_router block-mac "$value"
    ;;
  /unblock-mac/*)
    value="${path#/unblock-mac/}"
    valid_arg "$value" || { send_response 400 BAD_REQUEST "invalid MAC"; exit 0; }
    run_router unblock-mac "$value"
    ;;
  /block-ip/*)
    value="${path#/block-ip/}"
    valid_arg "$value" || { send_response 400 BAD_REQUEST "invalid IP"; exit 0; }
    run_router block-ip "$value"
    ;;
  /unblock-ip/*)
    value="${path#/unblock-ip/}"
    valid_arg "$value" || { send_response 400 BAD_REQUEST "invalid IP"; exit 0; }
    run_router unblock-ip "$value"
    ;;
  *)
    send_response 404 NOT_FOUND "unknown endpoint: $path"
    ;;
esac
