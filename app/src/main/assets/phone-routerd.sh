#!/system/bin/sh
set -eu

STATE_DIR="/data/local/phone-router"
MAC_BLOCKS="$STATE_DIR/blocked.macs"
IP_BLOCKS="$STATE_DIR/blocked.ips"
PAUSE_FILE="$STATE_DIR/pause_all"

NAT_CHAIN="PHONE_ROUTER_NAT"
MANGLE_CHAIN="PHONE_ROUTER_PRE"
FILTER_CHAIN="PHONE_ROUTER_FWD"
IP6_FILTER_CHAIN="PHONE_ROUTER_V6_FWD"
TETHER_NAT_CHAIN="PHONE_ROUTER_TETHER_NAT"
TETHER_FILTER_CHAIN="PHONE_ROUTER_TETHER_FWD"
TETHER_MANGLE_CHAIN="PHONE_ROUTER_TETHER_MANGLE"
MARK_HEX="0x1"
ROUTE_TABLE="100"

TPROXY_PORT="${TPROXY_PORT:-7893}"
REDIR_PORT="${REDIR_PORT:-7892}"
DNS_PORT="${DNS_PORT:-1053}"
DOWN_IFACE="${DOWN_IFACE:-}"
UP_IFACE="${UP_IFACE:-}"
AP_SSID="${AP_SSID:-PhoneRouter}"
AP_PASSPHRASE="${AP_PASSPHRASE:-phonebuild888}"
AP_BAND="${AP_BAND:-2}"
IP_FORWARD_PREV="$STATE_DIR/ip_forward.prev"
SING_BOX_BIN="${SING_BOX_BIN:-$STATE_DIR/sing-box}"
SING_BOX_CONFIG="${SING_BOX_CONFIG:-$STATE_DIR/sing-box.json}"
SING_BOX_GLOBAL_CONFIG="$STATE_DIR/sing-box-global.json"
SING_BOX_RULE_CONFIG="$STATE_DIR/sing-box-rule.json"
SING_BOX_PID="$STATE_DIR/sing-box.pid"
SING_BOX_LOG="$STATE_DIR/sing-box.log"
SING_BOX_SELECTED="$STATE_DIR/sing-box-selected"
SING_BOX_SELECTOR="${SING_BOX_SELECTOR:-节点选择}"
SING_BOX_MODE="$STATE_DIR/proxy-mode"
RULE_DIR="$STATE_DIR/rules"
GEOSITE_CN_RULE="$RULE_DIR/geosite-cn.srs"
GEOIP_CN_RULE="$RULE_DIR/geoip-cn.srs"

CLASH_PKG="com.github.metacubex.clash.meta"
CLASH_DATA="/data/data/$CLASH_PKG"

log() {
  printf '%s\n' "$*"
}

die() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

require_root() {
  [ "$(id -u)" = "0" ] || die "root adb shell is required; run adb root or use an app-visible su provider"
}

init_state() {
  mkdir -p "$STATE_DIR"
  touch "$MAC_BLOCKS" "$IP_BLOCKS"
}

ipt() {
  iptables -w 3 "$@"
}

ip6t() {
  ip6tables -w 3 "$@"
}

chain_exists() {
  table="$1"
  chain="$2"
  ipt -t "$table" -S "$chain" >/dev/null 2>&1
}

ip6_chain_exists() {
  chain="$1"
  ip6t -S "$chain" >/dev/null 2>&1
}

ensure_chain() {
  table="$1"
  chain="$2"
  chain_exists "$table" "$chain" || ipt -t "$table" -N "$chain"
  ipt -t "$table" -F "$chain"
}

ensure_jump() {
  table="$1"
  chain="$2"
  target="$3"
  if ! ipt -t "$table" -C "$chain" -j "$target" >/dev/null 2>&1; then
    ipt -t "$table" -I "$chain" 1 -j "$target"
  fi
}

remove_jump() {
  table="$1"
  chain="$2"
  target="$3"
  while ipt -t "$table" -D "$chain" -j "$target" >/dev/null 2>&1; do :; done
}

remove_ip6_jump() {
  chain="$1"
  target="$2"
  while ip6t -D "$chain" -j "$target" >/dev/null 2>&1; do :; done
}

detect_down_iface() {
  if [ -n "$DOWN_IFACE" ]; then
    printf '%s\n' "$DOWN_IFACE"
    return 0
  fi

  iface="$(dumpsys tethering 2>/dev/null | awk '/ - TetheredState - / {print $1; exit}')"
  if [ -n "${iface:-}" ]; then
    printf '%s\n' "$iface"
    return 0
  fi

  for candidate in ap_br_wlan0 softap0 wlan0; do
    if ip link show "$candidate" >/dev/null 2>&1; then
      printf '%s\n' "$candidate"
      return 0
    fi
  done

  die "could not detect hotspot interface; set DOWN_IFACE=wlan0 or similar"
}

android_upstream_iface() {
  dumpsys tethering 2>/dev/null | awk '
    /Current upstream interface\(s\):/ {
      sub(/.*Current upstream interface\(s\):[[:space:]]*/, "")
      gsub(/[\[\],]/, " ")
      if ($1 != "" && $1 != "null") print $1
      exit
    }
  '
}

detect_upstream_iface() {
  down="${1:-$(detect_down_iface 2>/dev/null || printf '')}"
  if [ -n "$UP_IFACE" ]; then
    printf '%s\n' "$UP_IFACE"
    return 0
  fi

  iface="$(android_upstream_iface)"
  if [ -n "${iface:-}" ] && [ "$iface" != "$down" ]; then
    printf '%s\n' "$iface"
    return 0
  fi

  iface="$(ip route get 8.8.8.8 2>/dev/null | awk -v down="$down" '
    {
      for (i = 1; i <= NF; i++) {
        if ($i == "dev" && $(i + 1) != down && $(i + 1) != "lo") {
          print $(i + 1)
          exit
        }
      }
    }
  ')"
  if [ -n "${iface:-}" ]; then
    printf '%s\n' "$iface"
    return 0
  fi

  iface="$(ip route show default 2>/dev/null | awk -v down="$down" '
    {
      for (i = 1; i <= NF; i++) {
        if ($i == "dev" && $(i + 1) != down && $(i + 1) != "lo") {
          print $(i + 1)
          exit
        }
      }
    }
  ')"
  if [ -n "${iface:-}" ]; then
    printf '%s\n' "$iface"
    return 0
  fi

  ip -o -4 addr show scope global 2>/dev/null | awk -v down="$down" '
    $2 != down && $2 != "lo" && $2 ~ /^(rmnet|ccmni|ccemni|wwan|pdp|v4-|clat|usb)/ {
      print $2
      exit
    }
  '
}

add_bypass_rules() {
  chain="$1"
  ipt -t mangle -A "$chain" -d 0.0.0.0/8 -j RETURN
  ipt -t mangle -A "$chain" -d 10.0.0.0/8 -j RETURN
  ipt -t mangle -A "$chain" -d 100.64.0.0/10 -j RETURN
  ipt -t mangle -A "$chain" -d 127.0.0.0/8 -j RETURN
  ipt -t mangle -A "$chain" -d 169.254.0.0/16 -j RETURN
  ipt -t mangle -A "$chain" -d 172.16.0.0/12 -j RETURN
  ipt -t mangle -A "$chain" -d 192.168.0.0/16 -j RETURN
  ipt -t mangle -A "$chain" -d 224.0.0.0/4 -j RETURN
  ipt -t mangle -A "$chain" -d 240.0.0.0/4 -j RETURN
}

apply_acl_rules() {
  table="$1"
  chain="$2"
  while IFS= read -r mac; do
    [ -n "$mac" ] || continue
    ipt -t "$table" -A "$chain" -m mac --mac-source "$mac" -j DROP
  done < "$MAC_BLOCKS"

  while IFS= read -r ipaddr; do
    [ -n "$ipaddr" ] || continue
    ipt -t "$table" -A "$chain" -s "$ipaddr" -j DROP
  done < "$IP_BLOCKS"

  if [ -f "$PAUSE_FILE" ]; then
    ipt -t "$table" -A "$chain" -j DROP
  fi
}

apply_base_filter() {
  down="$(detect_down_iface)"
  ensure_chain filter "$FILTER_CHAIN"
  ipt -t filter -A "$FILTER_CHAIN" ! -i "$down" -j RETURN
  apply_acl_rules filter "$FILTER_CHAIN"
  ensure_jump filter FORWARD "$FILTER_CHAIN"
}

apply_leak_guard() {
  down="$(detect_down_iface)"
  apply_base_filter
  ipt -t filter -A "$FILTER_CHAIN" -i "$down" -p udp ! --dport 53 -j REJECT

  if command -v ip6tables >/dev/null 2>&1; then
    ip6_chain_exists "$IP6_FILTER_CHAIN" || ip6t -N "$IP6_FILTER_CHAIN"
    ip6t -F "$IP6_FILTER_CHAIN"
    ip6t -A "$IP6_FILTER_CHAIN" ! -i "$down" -j RETURN
    ip6t -A "$IP6_FILTER_CHAIN" -i "$down" -j REJECT
    if ! ip6t -C FORWARD -j "$IP6_FILTER_CHAIN" >/dev/null 2>&1; then
      ip6t -I FORWARD 1 -j "$IP6_FILTER_CHAIN"
    fi
  fi
}

save_ip_forward() {
  init_state
  if [ ! -f "$IP_FORWARD_PREV" ]; then
    cat /proc/sys/net/ipv4/ip_forward 2>/dev/null > "$IP_FORWARD_PREV" || printf '0\n' > "$IP_FORWARD_PREV"
  fi
}

restore_ip_forward() {
  [ -f "$IP_FORWARD_PREV" ] || return 0
  if [ -z "$(android_upstream_iface)" ]; then
    prev="$(cat "$IP_FORWARD_PREV" 2>/dev/null || printf '0')"
    echo "$prev" > /proc/sys/net/ipv4/ip_forward 2>/dev/null || sysctl -w "net.ipv4.ip_forward=$prev" >/dev/null 2>&1 || true
  fi
  rm -f "$IP_FORWARD_PREV"
}

set_ip_forward_on() {
  save_ip_forward
  echo 1 > /proc/sys/net/ipv4/ip_forward 2>/dev/null || sysctl -w net.ipv4.ip_forward=1 >/dev/null
}

add_established_forward_rule() {
  chain="$1"
  up="$2"
  down="$3"
  if ! ipt -t filter -A "$chain" -i "$up" -o "$down" -m state --state RELATED,ESTABLISHED -j ACCEPT 2>/dev/null; then
    ipt -t filter -A "$chain" -i "$up" -o "$down" -m conntrack --ctstate RELATED,ESTABLISHED -j ACCEPT
  fi
}

cleanup_tether_rules() {
  restore="${1:-restore}"
  remove_jump nat POSTROUTING "$TETHER_NAT_CHAIN"
  remove_jump filter FORWARD "$TETHER_FILTER_CHAIN"
  remove_jump mangle FORWARD "$TETHER_MANGLE_CHAIN"

  for spec in "nat $TETHER_NAT_CHAIN" "filter $TETHER_FILTER_CHAIN" "mangle $TETHER_MANGLE_CHAIN"; do
    table="${spec% *}"
    chain="${spec#* }"
    if chain_exists "$table" "$chain"; then
      ipt -t "$table" -F "$chain" || true
      ipt -t "$table" -X "$chain" || true
    fi
  done

  [ "$restore" = "restore" ] && restore_ip_forward
}

enable_tether() {
  require_root
  init_state
  down="$(detect_down_iface)"
  up="$(detect_upstream_iface "$down" || true)"
  [ -n "${up:-}" ] || die "no usable upstream network; enable mobile data/SIM data first"
  [ "$up" != "$down" ] || die "upstream and hotspot interface are both $down"

  cleanup_tether_rules keep-forward
  set_ip_forward_on

  ensure_chain nat "$TETHER_NAT_CHAIN"
  ipt -t nat -A "$TETHER_NAT_CHAIN" -o "$up" -j MASQUERADE
  ensure_jump nat POSTROUTING "$TETHER_NAT_CHAIN"

  ensure_chain filter "$TETHER_FILTER_CHAIN"
  apply_acl_rules filter "$TETHER_FILTER_CHAIN"
  ipt -t filter -A "$TETHER_FILTER_CHAIN" -i "$down" -o "$up" -j ACCEPT
  add_established_forward_rule "$TETHER_FILTER_CHAIN" "$up" "$down"
  ipt -t filter -A "$TETHER_FILTER_CHAIN" -i "$down" -j DROP
  ensure_jump filter FORWARD "$TETHER_FILTER_CHAIN"

  ensure_chain mangle "$TETHER_MANGLE_CHAIN"
  ipt -t mangle -A "$TETHER_MANGLE_CHAIN" -i "$down" -o "$up" -p tcp --tcp-flags SYN,RST SYN -j TCPMSS --clamp-mss-to-pmtu
  ensure_jump mangle FORWARD "$TETHER_MANGLE_CHAIN"

  log "enabled manual tether: downstream=$down upstream=$up ip_forward=$(cat /proc/sys/net/ipv4/ip_forward 2>/dev/null || printf '?')"
}

disable_tether() {
  require_root
  cleanup_tether_rules restore
  log "disabled manual tether rules"
}

apply_dns_hijack() {
  down="$(detect_down_iface)"
  ensure_chain nat "$NAT_CHAIN"
  ipt -t nat -A "$NAT_CHAIN" ! -i "$down" -j RETURN
  ipt -t nat -A "$NAT_CHAIN" -p udp --dport 53 -j REDIRECT --to-ports "$DNS_PORT"
  ipt -t nat -A "$NAT_CHAIN" -p tcp --dport 53 -j REDIRECT --to-ports "$DNS_PORT"
  ensure_jump nat PREROUTING "$NAT_CHAIN"
}

is_tcp_listening() {
  port="$1"
  ss -lntup 2>/dev/null | grep -Eq ":$port([[:space:]]|$)"
}

is_udp_listening() {
  port="$1"
  ss -lnup 2>/dev/null | grep -Eq ":$port([[:space:]]|$)"
}

require_port_listening() {
  name="$1"
  port="$2"
  if ! is_tcp_listening "$port" && ! is_udp_listening "$port"; then
    die "$name port $port is not listening; start sing-box first or keep proxy off"
  fi
}

singbox_pid() {
  [ -f "$SING_BOX_PID" ] || return 1
  pid="$(cat "$SING_BOX_PID" 2>/dev/null || true)"
  [ -n "$pid" ] || return 1
  kill -0 "$pid" 2>/dev/null || return 1
  printf '%s\n' "$pid"
}

singbox_state() {
  if singbox_pid >/dev/null 2>&1; then
    printf 'running\n'
  elif [ -x "$SING_BOX_BIN" ]; then
    printf 'stopped\n'
  else
    printf 'missing\n'
  fi
}

singbox_selected() {
  if [ -f "$SING_BOX_SELECTED" ]; then
    cat "$SING_BOX_SELECTED" 2>/dev/null || printf unknown
  else
    printf unknown
  fi
}

singbox_nodes() {
  require_root
  [ -f "$SING_BOX_CONFIG" ] || die "sing-box config not installed: $SING_BOX_CONFIG"
  awk -v selector="$SING_BOX_SELECTOR" '
    /"type"[[:space:]]*:[[:space:]]*"selector"/ {candidate=1}
    candidate && /"tag"[[:space:]]*:/ && $0 ~ "\"" selector "\"" {in_selector=1; candidate=0}
    in_selector && /"outbounds"[[:space:]]*:/ {in_outbounds=1; next}
    in_selector && in_outbounds {
      line=$0
      while (match(line, /"[^"]+"/)) {
        item=substr(line, RSTART + 1, RLENGTH - 2)
        if (item != "") print item
        line=substr(line, RSTART + RLENGTH)
      }
      if ($0 ~ /\]/) {
        in_outbounds=0
        in_selector=0
      }
    }
  ' "$SING_BOX_CONFIG" | awk 'NF && !seen[$0]++ {print}'
}

singbox_has_node() {
  target="$1"
  singbox_nodes | awk -v target="$target" '$0 == target {found=1} END {exit found ? 0 : 1}'
}

singbox_config_info() {
  require_root
  log "active_config=$SING_BOX_CONFIG"
  log "global_config=$SING_BOX_GLOBAL_CONFIG"
  log "rule_config=$SING_BOX_RULE_CONFIG"
  log "route_mode=$(singbox_route_mode)"
  log "selected=$(singbox_selected)"
  log "nodes:"
  singbox_nodes
}

singbox_check_config() {
  config="$1"
  env ENABLE_DEPRECATED_LEGACY_DNS_SERVERS=true ENABLE_DEPRECATED_MISSING_DOMAIN_RESOLVER=true "$SING_BOX_BIN" check -c "$config" >/dev/null
}

ensure_global_config() {
  [ -f "$SING_BOX_GLOBAL_CONFIG" ] && return 0
  [ -f "$SING_BOX_CONFIG" ] || die "sing-box config not installed: $SING_BOX_CONFIG"
  if grep -q '"rule_set"[[:space:]]*:' "$SING_BOX_CONFIG"; then
    die "global config is missing and current config looks like rule mode: $SING_BOX_GLOBAL_CONFIG"
  fi
  cp "$SING_BOX_CONFIG" "$SING_BOX_GLOBAL_CONFIG"
  chmod 600 "$SING_BOX_GLOBAL_CONFIG"
}

build_rule_config() {
  ensure_global_config
  [ -f "$GEOSITE_CN_RULE" ] || die "missing China domain rule: $GEOSITE_CN_RULE"
  [ -f "$GEOIP_CN_RULE" ] || die "missing China IP rule: $GEOIP_CN_RULE"

  tmp="$SING_BOX_RULE_CONFIG.tmp"
  awk '
    /"route"[[:space:]]*:[[:space:]]*\{/ && !route_seen {
      print
      print "        \"rule_set\": ["
      print "            {"
      print "                \"type\": \"local\","
      print "                \"tag\": \"geosite-cn\","
      print "                \"format\": \"binary\","
      print "                \"path\": \"/data/local/phone-router/rules/geosite-cn.srs\""
      print "            },"
      print "            {"
      print "                \"type\": \"local\","
      print "                \"tag\": \"geoip-cn\","
      print "                \"format\": \"binary\","
      print "                \"path\": \"/data/local/phone-router/rules/geoip-cn.srs\""
      print "            }"
      print "        ],"
      route_seen=1
      in_route=1
      next
    }
    in_route && /"rules"[[:space:]]*:[[:space:]]*\[/ {
      in_route_rules=1
    }
    {
      print
    }
    in_route_rules && /"action"[[:space:]]*:[[:space:]]*"sniff"/ {
      seen_sniff=1
    }
    in_route_rules && seen_sniff && !inserted && /^[[:space:]]*\},[[:space:]]*$/ {
      print "            {"
      print "                \"inbound\": ["
      print "                    \"mixed-in\","
      print "                    \"tproxy-in\","
      print "                    \"redir-in\""
      print "                ],"
      print "                \"rule_set\": ["
      print "                    \"geosite-cn\","
      print "                    \"geoip-cn\""
      print "                ],"
      print "                \"outbound\": \"直连\""
      print "            },"
      inserted=1
      seen_sniff=0
    }
    END {
      if (!route_seen || !inserted) exit 2
    }
  ' "$SING_BOX_GLOBAL_CONFIG" > "$tmp" || {
    rm -f "$tmp"
    die "failed to build rule-mode sing-box config"
  }
  singbox_check_config "$tmp" || {
    cat "$tmp" > "$SING_BOX_RULE_CONFIG.failed" 2>/dev/null || true
    rm -f "$tmp"
    die "rule-mode sing-box config check failed"
  }
  mv "$tmp" "$SING_BOX_RULE_CONFIG"
  chmod 600 "$SING_BOX_RULE_CONFIG"
}

select_singbox_mode() {
  mode="$1"
  [ -x "$SING_BOX_BIN" ] || die "sing-box binary not installed: $SING_BOX_BIN"
  case "$mode" in
    global)
      ensure_global_config
      singbox_check_config "$SING_BOX_GLOBAL_CONFIG" || die "global-mode sing-box config check failed"
      cp "$SING_BOX_GLOBAL_CONFIG" "$SING_BOX_CONFIG"
      ;;
    rule)
      build_rule_config
      cp "$SING_BOX_RULE_CONFIG" "$SING_BOX_CONFIG"
      ;;
    *) die "unknown proxy route mode: $mode" ;;
  esac
  chmod 600 "$SING_BOX_CONFIG"
  printf '%s\n' "$mode" > "$SING_BOX_MODE"
  chmod 600 "$SING_BOX_MODE"
}

singbox_route_mode() {
  if [ -f "$SING_BOX_MODE" ]; then
    mode="$(cat "$SING_BOX_MODE" 2>/dev/null || true)"
    case "$mode" in
      global|rule) printf '%s\n' "$mode"; return 0 ;;
    esac
  fi
  if [ -f "$SING_BOX_CONFIG" ] && grep -q '"rule_set"[[:space:]]*:' "$SING_BOX_CONFIG"; then
    printf 'rule\n'
  elif [ -f "$SING_BOX_CONFIG" ]; then
    printf 'global\n'
  else
    printf 'unknown\n'
  fi
}

singbox_start() {
  require_root
  init_state
  [ -x "$SING_BOX_BIN" ] || die "sing-box binary not installed: $SING_BOX_BIN"
  [ -f "$SING_BOX_CONFIG" ] || die "sing-box config not installed: $SING_BOX_CONFIG"
  if singbox_pid >/dev/null 2>&1; then
    log "sing-box already running pid=$(singbox_pid)"
    return 0
  fi
  rm -f "$SING_BOX_LOG"
  nohup env ENABLE_DEPRECATED_LEGACY_DNS_SERVERS=true ENABLE_DEPRECATED_MISSING_DOMAIN_RESOLVER=true "$SING_BOX_BIN" run -c "$SING_BOX_CONFIG" >> "$SING_BOX_LOG" 2>&1 &
  echo "$!" > "$SING_BOX_PID"
  sleep 1
  if singbox_pid >/dev/null 2>&1; then
    log "sing-box started pid=$(singbox_pid) node=$(singbox_selected)"
    return 0
  fi
  tail -80 "$SING_BOX_LOG" 2>/dev/null || true
  die "sing-box failed to start"
}

singbox_stop() {
  require_root
  if singbox_pid >/dev/null 2>&1; then
    kill "$(singbox_pid)" 2>/dev/null || true
    sleep 1
  fi
  rm -f "$SING_BOX_PID"
  log "sing-box stopped"
}

json_escape() {
  printf '%s' "$1" | sed 's/\\/\\\\/g;s/"/\\"/g'
}

set_node_in_config() {
  config="$1"
  node="$2"
  [ -f "$config" ] || return 0
  esc="$(json_escape "$node")"
  tmp="$config.tmp"
  awk -v selector="$SING_BOX_SELECTOR" -v node="$esc" '
    BEGIN {in_selector=0}
    /"type"[[:space:]]*:[[:space:]]*"selector"/ {candidate=1}
    candidate && /"tag"[[:space:]]*:/ && $0 ~ "\"" selector "\"" {in_selector=1; candidate=0}
    in_selector && /"default"[[:space:]]*:/ {
      sub(/"default"[[:space:]]*:[[:space:]]*"[^"]*"/, "\"default\": \"" node "\"")
    }
    in_selector && /^[[:space:]]*}/ {in_selector=0}
    {print}
  ' "$config" > "$tmp"
  mv "$tmp" "$config"
  chmod 600 "$config"
}

singbox_set_node() {
  require_root
  [ $# -eq 1 ] || die "usage: singbox-set-node <node-tag>"
  node="$1"
  [ -f "$SING_BOX_CONFIG" ] || die "sing-box config not installed: $SING_BOX_CONFIG"
  singbox_has_node "$node" || die "node is not in selector $SING_BOX_SELECTOR: $node"
  set_node_in_config "$SING_BOX_CONFIG" "$node"
  set_node_in_config "$SING_BOX_GLOBAL_CONFIG" "$node"
  set_node_in_config "$SING_BOX_RULE_CONFIG" "$node"
  printf '%s\n' "$node" > "$SING_BOX_SELECTED"
  chmod 600 "$SING_BOX_SELECTED"
  if singbox_pid >/dev/null 2>&1; then
    singbox_stop >/dev/null
    singbox_start
  else
    log "sing-box node set to $node"
  fi
}

singbox_import_config() {
  require_root
  [ $# -eq 1 ] || die "usage: singbox-import-config <path>"
  src="$1"
  case "$src" in
    /data/local/tmp/*|/sdcard/*|/storage/emulated/0/*) ;;
    *) die "config path must be under /data/local/tmp or /sdcard" ;;
  esac
  [ -f "$src" ] || die "config file not found: $src"
  [ -x "$SING_BOX_BIN" ] || die "sing-box binary not installed: $SING_BOX_BIN"

  init_state
  tmp="$STATE_DIR/sing-box-import.json"
  cp "$src" "$tmp"
  chmod 600 "$tmp"
  singbox_check_config "$tmp" || {
    rm -f "$tmp"
    die "imported sing-box config check failed"
  }

  stamp="$(date +%Y%m%d-%H%M%S)"
  [ -f "$SING_BOX_CONFIG" ] && cp "$SING_BOX_CONFIG" "$SING_BOX_CONFIG.bak.$stamp"
  [ -f "$SING_BOX_GLOBAL_CONFIG" ] && cp "$SING_BOX_GLOBAL_CONFIG" "$SING_BOX_GLOBAL_CONFIG.bak.$stamp"

  cp "$tmp" "$SING_BOX_GLOBAL_CONFIG"
  cp "$tmp" "$SING_BOX_CONFIG"
  chmod 600 "$SING_BOX_GLOBAL_CONFIG" "$SING_BOX_CONFIG"
  rm -f "$SING_BOX_RULE_CONFIG" "$tmp"
  printf 'global\n' > "$SING_BOX_MODE"
  chmod 600 "$SING_BOX_MODE"

  first_node="$(singbox_nodes | head -1)"
  if [ -n "$first_node" ]; then
    printf '%s\n' "$first_node" > "$SING_BOX_SELECTED"
    chmod 600 "$SING_BOX_SELECTED"
  fi

  if singbox_pid >/dev/null 2>&1; then
    singbox_stop >/dev/null
    singbox_start
  fi
  log "imported sing-box config from $src"
  log "backup_stamp=$stamp"
  singbox_config_info
}

singbox_status() {
  require_root
  log "singbox=$(singbox_state)"
  log "node=$(singbox_selected)"
  log "route_mode=$(singbox_route_mode)"
  log "bin=$SING_BOX_BIN"
  log "config=$SING_BOX_CONFIG"
  if singbox_pid >/dev/null 2>&1; then
    log "pid=$(singbox_pid)"
  else
    log "pid=none"
  fi
  log "listeners:"
  ss -lntup 2>/dev/null | grep -E ":($TPROXY_PORT|$REDIR_PORT|$DNS_PORT|7890|9090)([[:space:]]|$)" || true
  log ""
  log "nodes:"
  singbox_nodes
}

singbox_log() {
  require_root
  tail -120 "$SING_BOX_LOG" 2>/dev/null || log "no sing-box log yet"
}

proxy_start_mode() {
  mode="$1"
  require_root
  disable_rules >/dev/null
  settings put global tether_offload_disabled 1 2>/dev/null || true
  sysctl -w net.ipv6.conf.all.forwarding=0 >/dev/null 2>&1 || true
  select_singbox_mode "$mode"
  if singbox_pid >/dev/null 2>&1; then
    singbox_stop >/dev/null
  fi
  singbox_start
  enable_redir
  log "proxy route mode enabled: $mode"
}

proxy_start() {
  proxy_start_mode global
}

proxy_rule() {
  proxy_start_mode rule
}

proxy_stop() {
  require_root
  disable_rules >/dev/null
  singbox_stop
  rm -f "$SING_BOX_MODE"
  log "proxy stopped"
}

enable_tproxy() {
  require_root
  init_state
  require_port_listening tproxy "$TPROXY_PORT"
  require_port_listening dns "$DNS_PORT"
  down="$(detect_down_iface)"

  apply_base_filter
  apply_dns_hijack

  ensure_chain mangle "$MANGLE_CHAIN"
  ipt -t mangle -A "$MANGLE_CHAIN" ! -i "$down" -j RETURN
  apply_acl_rules mangle "$MANGLE_CHAIN"
  ipt -t mangle -A "$MANGLE_CHAIN" -p udp --dport 53 -j RETURN
  ipt -t mangle -A "$MANGLE_CHAIN" -p tcp --dport 53 -j RETURN
  add_bypass_rules "$MANGLE_CHAIN"
  ipt -t mangle -A "$MANGLE_CHAIN" -p tcp -j TPROXY --on-port "$TPROXY_PORT" --tproxy-mark "$MARK_HEX/$MARK_HEX"
  ipt -t mangle -A "$MANGLE_CHAIN" -p udp -j TPROXY --on-port "$TPROXY_PORT" --tproxy-mark "$MARK_HEX/$MARK_HEX"
  ensure_jump mangle PREROUTING "$MANGLE_CHAIN"

  ip rule add fwmark "$MARK_HEX/$MARK_HEX" table "$ROUTE_TABLE" 2>/dev/null || true
  ip route replace local default dev lo table "$ROUTE_TABLE"

  log "enabled tproxy mode on $down: tproxy=$TPROXY_PORT dns=$DNS_PORT"
}

enable_redir() {
  require_root
  init_state
  require_port_listening redir "$REDIR_PORT"
  require_port_listening dns "$DNS_PORT"
  down="$(detect_down_iface)"

  apply_leak_guard

  ensure_chain mangle "$MANGLE_CHAIN"
  ipt -t mangle -A "$MANGLE_CHAIN" ! -i "$down" -j RETURN
  apply_acl_rules mangle "$MANGLE_CHAIN"
  ensure_jump mangle PREROUTING "$MANGLE_CHAIN"

  apply_dns_hijack
  ipt -t nat -A "$NAT_CHAIN" -p tcp -j REDIRECT --to-ports "$REDIR_PORT"

  log "enabled redir mode on $down: redir=$REDIR_PORT dns=$DNS_PORT"
}

disable_rules() {
  require_root
  remove_jump nat PREROUTING "$NAT_CHAIN"
  remove_jump mangle PREROUTING "$MANGLE_CHAIN"
  remove_jump filter FORWARD "$FILTER_CHAIN"
  if command -v ip6tables >/dev/null 2>&1; then
    remove_ip6_jump FORWARD "$IP6_FILTER_CHAIN"
    if ip6_chain_exists "$IP6_FILTER_CHAIN"; then
      ip6t -F "$IP6_FILTER_CHAIN" || true
      ip6t -X "$IP6_FILTER_CHAIN" || true
    fi
  fi
  cleanup_tether_rules restore

  for spec in "nat $NAT_CHAIN" "mangle $MANGLE_CHAIN" "filter $FILTER_CHAIN"; do
    table="${spec% *}"
    chain="${spec#* }"
    if chain_exists "$table" "$chain"; then
      ipt -t "$table" -F "$chain" || true
      ipt -t "$table" -X "$chain" || true
    fi
  done

  while ip rule del fwmark "$MARK_HEX/$MARK_HEX" table "$ROUTE_TABLE" 2>/dev/null; do :; done
  ip route flush table "$ROUTE_TABLE" 2>/dev/null || true
  log "disabled phone-router iptables/ip rule state"
}

normalize_list() {
  file="$1"
  tmp="$file.tmp"
  awk 'NF && !seen[$0]++ {print}' "$file" > "$tmp"
  mv "$tmp" "$file"
}

block_value() {
  file="$1"
  value="$2"
  init_state
  grep -qi "^$value$" "$file" 2>/dev/null || printf '%s\n' "$value" >> "$file"
  normalize_list "$file"
  log "blocked $value"
}

unblock_value() {
  file="$1"
  value="$2"
  init_state
  tmp="$file.tmp"
  grep -vi "^$value$" "$file" > "$tmp" || true
  mv "$tmp" "$file"
  log "unblocked $value"
}

sync_acl() {
  tether_active=0
  if is_chain_active nat "$TETHER_NAT_CHAIN" || is_chain_active filter "$TETHER_FILTER_CHAIN"; then
    tether_active=1
  fi

  if chain_exists filter "$FILTER_CHAIN" || chain_exists mangle "$MANGLE_CHAIN"; then
    mode="none"
    if chain_exists mangle "$MANGLE_CHAIN"; then
      mode="tproxy"
    elif chain_exists nat "$NAT_CHAIN"; then
      mode="redir"
    fi
    disable_rules >/dev/null
    case "$mode" in
      tproxy) enable_tproxy ;;
      redir) enable_redir ;;
      *) apply_base_filter ;;
    esac
  fi

  if [ "$tether_active" = "1" ]; then
    enable_tether
  fi
}

clients() {
  down="$(detect_down_iface)"
  log "hotspot interface: $down"
  log ""
  log "tethering clients:"
  dumpsys tethering 2>/dev/null | awk '
    /Client Information:/ {show=1; print; next}
    show && /^[[:space:]]*$/ {show=0; next}
    show && /^    IPv[46] Upstream/ {show=0; next}
    show && /^    Forwarding/ {show=0; next}
    show {print}
  ' || true
  log ""
  log "neighbor table:"
  ip neigh show dev "$down" 2>/dev/null || true
  log ""
  log "arp table:"
  cat /proc/net/arp 2>/dev/null | awk -v dev="$down" 'NR==1 || $6==dev {print}' || true
}

is_chain_active() {
  table="$1"
  chain="$2"
  chain_exists "$table" "$chain" || return 1
  ipt -t "$table" -S "$chain" 2>/dev/null | grep -qv "^-N "
}

proxy_mode() {
  if is_chain_active mangle "$MANGLE_CHAIN" && ipt -t mangle -S "$MANGLE_CHAIN" 2>/dev/null | grep -q -- "-j TPROXY"; then
    printf 'tproxy\n'
  elif is_chain_active nat "$NAT_CHAIN" && ipt -t nat -S "$NAT_CHAIN" 2>/dev/null | grep -q -- "--to-ports $REDIR_PORT"; then
    printf 'redir\n'
  else
    printf 'off\n'
  fi
}

ap_state() {
  dumpsys tethering 2>/dev/null | awk '
    / - TetheredState - / {print "tethered"; found=1; exit}
    / - LocalHotspotState - / {print "local-only"; found=1; exit}
    END {if (!found) print "off"}
  '
}

manual_tether_active() {
  is_chain_active nat "$TETHER_NAT_CHAIN" && is_chain_active filter "$TETHER_FILTER_CHAIN"
}

tether_state() {
  down="$(detect_down_iface 2>/dev/null || printf '')"
  upstream="$(detect_upstream_iface "$down" 2>/dev/null || true)"
  android_upstream="$(android_upstream_iface)"

  if manual_tether_active; then
    printf 'manual\n'
  elif [ -n "$android_upstream" ]; then
    printf 'android\n'
  elif [ "$(ap_state)" != "off" ] && [ -z "$upstream" ]; then
    printf 'no-upstream\n'
  else
    printf 'off\n'
  fi
}

ip_forward_status() {
  cat /proc/sys/net/ipv4/ip_forward 2>/dev/null || sysctl net.ipv4.ip_forward 2>/dev/null | awk -F'= ' '{print $2}' || printf '?'
}

client_count() {
  down="$(detect_down_iface 2>/dev/null || printf wlan0)"
  count="$(cat /proc/net/arp 2>/dev/null | awk -v dev="$down" '
    NR > 1 && $6 == dev && $4 != "00:00:00:00:00:00" {
      mac[tolower($4)] = 1
    }
    END {
      for (item in mac) count++
      print count + 0
    }
  ')"
  if [ "$count" = "0" ]; then
    count="$(dumpsys tethering 2>/dev/null | awk '
      /client: \// {
        line = $0
        while (match(line, /client: \\/[^ ]+ \\([0-9A-Fa-f:]+\\)/)) {
          mac = substr(line, RSTART, RLENGTH)
          sub(/.*\\(/, "", mac)
          sub(/\\).*/, "", mac)
          seen[tolower(mac)] = 1
          line = substr(line, RSTART + RLENGTH)
        }
      }
      END {
        for (item in seen) count++
        print count + 0
      }
    ')"
  fi
  if [ "$count" = "0" ]; then
    count="$(ip neigh show dev "$down" 2>/dev/null | awk '
      /lladdr/ {
        for (i = 1; i <= NF; i++) {
          if ($i == "lladdr" && $(i + 1) != "") {
            seen[tolower($(i + 1))] = 1
          }
        }
      }
      END {
        for (item in seen) count++
        print count + 0
      }
    ')"
  fi
  printf '%s\n' "$count"
}

block_count() {
  init_state
  awk 'NF {count++} END {print count+0}' "$1"
}

summary() {
  require_root
  init_state
  down="$(detect_down_iface 2>/dev/null || printf unknown)"
  up="$(detect_upstream_iface "$down" 2>/dev/null || printf none)"
  [ -n "$up" ] || up="none"
  ap="$(ap_state)"
  tether="$(tether_state)"
  mode="$(proxy_mode)"
  paused="disabled"
  [ -f "$PAUSE_FILE" ] && paused="enabled"
  root_state="adb-root"
  id 2>/dev/null | grep -q 'u:r:magisk:s0' && root_state="magisk"
  pidof magiskd >/dev/null 2>&1 && root_state="magisk"
  core_state="stopped"
  ss -lntup 2>/dev/null | grep -Eq ":($TPROXY_PORT|$REDIR_PORT|$DNS_PORT|7890|9090)([[:space:]]|$)" && core_state="listening"

  log "root=$root_state"
  log "ap=$ap"
  log "iface=$down"
  log "tether=$tether"
  log "upstream=$up"
  log "ip_forward=$(ip_forward_status)"
  log "proxy=$mode"
  log "route_mode=$(singbox_route_mode)"
  log "paused=$paused"
  log "clients=$(client_count)"
  log "blocked_macs=$(block_count "$MAC_BLOCKS")"
  log "blocked_ips=$(block_count "$IP_BLOCKS")"
  log "core=$core_state"
  log "singbox=$(singbox_state)"
  log "node=$(singbox_selected)"
  log "clash=$core_state"
  log "tproxy_port=$TPROXY_PORT"
  log "redir_port=$REDIR_PORT"
  log "dns_port=$DNS_PORT"
}

ap_status() {
  require_root
  log "ap: $(ap_state)"
  log "hotspot interface: $(detect_down_iface 2>/dev/null || printf unknown)"
  log "tether: $(tether_state)"
  log "upstream: $(detect_upstream_iface "$(detect_down_iface 2>/dev/null || printf '')" 2>/dev/null || printf none)"
  log "ip_forward: $(ip_forward_status)"
  dumpsys tethering 2>/dev/null | awk '
    /Tether state:/ {show=1; print; next}
    show && /^  Hardware offload:/ {show=0}
    show {print}
  '
}

tether_status() {
  require_root
  down="$(detect_down_iface 2>/dev/null || printf unknown)"
  up="$(detect_upstream_iface "$down" 2>/dev/null || printf none)"
  [ -n "$up" ] || up="none"
  log "tether: $(tether_state)"
  log "downstream: $down"
  log "upstream: $up"
  log "ip_forward: $(ip_forward_status)"
  log ""
  log "manual tether chains:"
  ipt -t nat -S "$TETHER_NAT_CHAIN" 2>/dev/null || true
  ipt -t filter -S "$TETHER_FILTER_CHAIN" 2>/dev/null || true
  ipt -t mangle -S "$TETHER_MANGLE_CHAIN" 2>/dev/null || true
  log ""
  dumpsys tethering 2>/dev/null | awk '
    /Tether state:/ {show=1; print; next}
    show && /^  Hardware offload:/ {show=0}
    show {print}
  '
}

try_enable_tether() {
  output="$(enable_tether 2>&1)" && {
    printf '%s\n' "$output"
    return 0
  }
  log "tether warning: $output"
  return 0
}

start_ap() {
  require_root
  ssid="${1:-$AP_SSID}"
  pass="${2:-$AP_PASSPHRASE}"
  band="${3:-$AP_BAND}"
  [ ${#pass} -ge 8 ] || die "WPA2 passphrase must be at least 8 characters"
  cmd wifi start-softap "$ssid" wpa2 "$pass" -b "$band"
  log "ap start requested: ssid=$ssid band=$band"
  try_enable_tether
  ap_status
}

stop_ap() {
  require_root
  cmd wifi stop-softap
  log "ap stop requested"
  ap_status
}

status() {
  require_root
  init_state
  log "device: $(getprop ro.product.manufacturer) $(getprop ro.product.model) / Android $(getprop ro.build.version.release) / $(getprop ro.product.cpu.abi)"
  log "root: $(id)"
  log "hotspot interface: $(detect_down_iface 2>/dev/null || printf unknown)"
  log ""
  log "active network:"
  dumpsys connectivity 2>/dev/null | awk '
    /Active default network:/ {print; next}
    /Current Networks:/ {print; show=1; next}
    show && /^  NetworkAgentInfo/ {print; count++}
    show && count >= 8 {exit}
  ' || true
  log ""
  log "listeners:"
  ss -lntup 2>/dev/null | grep -E ":($TPROXY_PORT|$REDIR_PORT|$DNS_PORT|7890|9090)([[:space:]]|$)" || true
  log ""
  singbox_status || true
  log ""
  log "phone-router chains:"
  ipt -t nat -S "$NAT_CHAIN" 2>/dev/null || true
  ipt -t mangle -S "$MANGLE_CHAIN" 2>/dev/null || true
  ipt -t filter -S "$FILTER_CHAIN" 2>/dev/null || true
  log ""
  log "manual tether chains:"
  ipt -t nat -S "$TETHER_NAT_CHAIN" 2>/dev/null || true
  ipt -t filter -S "$TETHER_FILTER_CHAIN" 2>/dev/null || true
  ipt -t mangle -S "$TETHER_MANGLE_CHAIN" 2>/dev/null || true
  log ""
  blocks
}

blocks() {
  init_state
  log "blocked MACs:"
  sed 's/^/  /' "$MAC_BLOCKS"
  log "blocked IPs:"
  sed 's/^/  /' "$IP_BLOCKS"
  if [ -f "$PAUSE_FILE" ]; then
    log "pause-all: enabled"
  else
    log "pause-all: disabled"
  fi
}

prepare_clash_ports() {
  require_root
  [ -d "$CLASH_DATA" ] || die "Clash Meta data dir not found: $CLASH_DATA"
  stamp="$(date +%Y%m%d-%H%M%S)"
  patched=0

  for file in "$CLASH_DATA"/files/processing/config.yaml "$CLASH_DATA"/files/imported/*/config.yaml; do
    [ -f "$file" ] || continue
    cp "$file" "$file.phone-router.$stamp.bak"
    grep -q '^allow-lan:' "$file" && sed -i 's/^allow-lan:.*/allow-lan: true/' "$file" || printf '\nallow-lan: true\n' >> "$file"
    grep -q '^bind-address:' "$file" && sed -i "s/^bind-address:.*/bind-address: '*'/" "$file" || printf "bind-address: '*'\n" >> "$file"
    grep -q '^redir-port:' "$file" || printf 'redir-port: %s\n' "$REDIR_PORT" >> "$file"
    grep -q '^tproxy-port:' "$file" || printf 'tproxy-port: %s\n' "$TPROXY_PORT" >> "$file"
    patched=$((patched + 1))
    log "patched $file"
  done

  log "patched $patched config file(s); restart Clash Meta or reload the active profile"
}

case "${1:-help}" in
  status) status ;;
  summary) summary ;;
  ap-status) ap_status ;;
  tether-status) tether_status ;;
  start-ap) start_ap "${2:-}" "${3:-}" "${4:-}" ;;
  stop-ap) stop_ap ;;
  enable-tether) enable_tether ;;
  disable-tether) disable_tether ;;
  clients) clients ;;
  prepare-clash-ports) prepare_clash_ports ;;
  singbox-start) singbox_start ;;
  singbox-stop) singbox_stop ;;
  singbox-status) singbox_status ;;
  singbox-log) singbox_log ;;
  singbox-nodes) singbox_nodes ;;
  singbox-config-info) singbox_config_info ;;
  singbox-import-config)
    [ $# -eq 2 ] || die "usage: singbox-import-config <path>"
    singbox_import_config "$2"
    ;;
  singbox-set-node)
    [ $# -eq 2 ] || die "usage: singbox-set-node <node-tag>"
    singbox_set_node "$2"
    ;;
  proxy-start) proxy_start ;;
  proxy-global) proxy_start_mode global ;;
  proxy-rule) proxy_rule ;;
  proxy-stop) proxy_stop ;;
  enable-tproxy) enable_tproxy ;;
  enable-redir) enable_redir ;;
  disable) disable_rules ;;
  blocks) blocks ;;
  block-mac)
    [ $# -eq 2 ] || die "usage: block-mac <mac>"
    block_value "$MAC_BLOCKS" "$2"
    sync_acl
    ;;
  unblock-mac)
    [ $# -eq 2 ] || die "usage: unblock-mac <mac>"
    unblock_value "$MAC_BLOCKS" "$2"
    sync_acl
    ;;
  block-ip)
    [ $# -eq 2 ] || die "usage: block-ip <ip>"
    block_value "$IP_BLOCKS" "$2"
    sync_acl
    ;;
  unblock-ip)
    [ $# -eq 2 ] || die "usage: unblock-ip <ip>"
    unblock_value "$IP_BLOCKS" "$2"
    sync_acl
    ;;
  pause-all)
    init_state
    touch "$PAUSE_FILE"
    sync_acl
    log "pause-all enabled"
    ;;
  resume-all)
    init_state
    rm -f "$PAUSE_FILE"
    sync_acl
    log "pause-all disabled"
    ;;
  help|-h|--help)
    sed -n '1,80p' "$0" | sed -n '/^case /q;p' >/dev/null
    cat <<'EOF'
phone-routerd commands:
  status
  clients
  prepare-clash-ports
  singbox-start
  singbox-stop
  singbox-status
  singbox-log
  singbox-nodes
  singbox-config-info
  singbox-import-config <path>
  singbox-set-node <node-tag>
  proxy-start
  proxy-global
  proxy-rule
  proxy-stop
  enable-tproxy
  enable-redir
  disable
  blocks
  block-mac <mac>
  unblock-mac <mac>
  block-ip <ip>
  unblock-ip <ip>
  pause-all
  resume-all
  summary
  ap-status
  tether-status
  start-ap [ssid] [passphrase] [2|5|6|any]
  stop-ap
  enable-tether
  disable-tether
EOF
    ;;
  *)
    die "unknown command: $1"
    ;;
esac
