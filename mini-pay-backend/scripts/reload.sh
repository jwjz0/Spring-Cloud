#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUN_DIR="$ROOT_DIR/.run"
LOG_DIR="$ROOT_DIR/logs"
mkdir -p "$RUN_DIR" "$LOG_DIR"

SERVICES=(gateway user product order pay notification)

usage() {
  cat <<'EOF'
Usage:
  ./scripts/reload.sh all
  ./scripts/reload.sh <service> [<service>...]

Available services:
  gateway user product order pay notification

Examples:
  ./scripts/reload.sh order
  ./scripts/reload.sh pay
  ./scripts/reload.sh gateway product
  ./scripts/reload.sh all
EOF
}

is_valid_service() {
  case "$1" in
    gateway|user|product|order|pay|notification) return 0 ;;
    *) return 1 ;;
  esac
}

module_name() {
  case "$1" in
    gateway) printf '%s\n' "mini-pay-gateway" ;;
    user) printf '%s\n' "mini-pay-user" ;;
    product) printf '%s\n' "mini-pay-product" ;;
    order) printf '%s\n' "mini-pay-order" ;;
    pay) printf '%s\n' "mini-pay-pay" ;;
    notification) printf '%s\n' "mini-pay-notification" ;;
    *) return 1 ;;
  esac
}

module_port() {
  case "$1" in
    gateway) printf '%s\n' "8080" ;;
    user) printf '%s\n' "8081" ;;
    product) printf '%s\n' "8082" ;;
    order) printf '%s\n' "8083" ;;
    pay) printf '%s\n' "8084" ;;
    notification) printf '%s\n' "8085" ;;
    *) return 1 ;;
  esac
}

module_service_name() {
  case "$1" in
    gateway) printf '%s\n' "gateway-service" ;;
    user) printf '%s\n' "user-service" ;;
    product) printf '%s\n' "product-service" ;;
    order) printf '%s\n' "order-service" ;;
    pay) printf '%s\n' "pay-service" ;;
    notification) printf '%s\n' "notification-service" ;;
    *) return 1 ;;
  esac
}

is_runnable_java() {
  local cmd="$1"
  [[ -x "$cmd" ]] || return 1
  "$cmd" -version >/dev/null 2>&1
}

java_bin() {
  local candidate

  if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/java" ]]; then
    if is_runnable_java "$JAVA_HOME/bin/java"; then
      printf '%s\n' "$JAVA_HOME/bin/java"
      return
    fi
  fi

  candidate="/opt/homebrew/opt/openjdk/bin/java"
  if is_runnable_java "$candidate"; then
    printf '%s\n' "$candidate"
    return
  fi

  for candidate in /opt/homebrew/Cellar/openjdk/*/libexec/openjdk.jdk/Contents/Home/bin/java; do
    if is_runnable_java "$candidate"; then
      printf '%s\n' "$candidate"
      return
    fi
  done

  if command -v java >/dev/null 2>&1; then
    candidate="$(command -v java)"
    if is_runnable_java "$candidate"; then
      printf '%s\n' "$candidate"
      return
    fi
  fi

  echo "ERROR: java not found. Please install JDK or set JAVA_HOME." >&2
  exit 1
}

stop_service() {
  local key="$1"
  local module
  module="$(module_name "$key")"
  local port
  port="$(module_port "$key")"
  local pid_file="$RUN_DIR/${module}.pid"

  if [[ -f "$pid_file" ]]; then
    local pid
    pid="$(cat "$pid_file" 2>/dev/null || true)"
    if [[ -n "${pid}" ]] && kill -0 "$pid" 2>/dev/null; then
      echo "Stopping $module (pid=$pid)"
      kill "$pid" || true
      sleep 1
    fi
    rm -f "$pid_file"
  fi

  local pids
  pids="$(lsof -tiTCP:"$port" -sTCP:LISTEN 2>/dev/null || true)"
  if [[ -n "$pids" ]]; then
    echo "Stopping process on port $port: $pids"
    kill $pids || true
    sleep 1
  fi
}

wait_for_port() {
  local module="$1"
  local port="$2"
  local pid="$3"
  local log_file="$4"

  local i
  for i in $(seq 1 30); do
    if lsof -nP -iTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1; then
      echo "Started $module on :$port (pid=$pid)"
      return 0
    fi
    if ! kill -0 "$pid" 2>/dev/null; then
      echo "ERROR: $module exited unexpectedly. Last log lines:" >&2
      tail -n 40 "$log_file" 2>/dev/null || true
      return 1
    fi
    sleep 1
  done

  echo "ERROR: $module did not open port $port within 30s. Last log lines:" >&2
  tail -n 40 "$log_file" 2>/dev/null || true
  return 1
}

wait_for_nacos() {
  local service_name="$1"
  local port="$2"
  local expect_ip="127.0.0.1"

  local i
  for i in $(seq 1 30); do
    local resp
    resp="$(curl -sS "http://localhost:8848/nacos/v1/ns/instance/list?serviceName=${service_name}" 2>/dev/null || true)"
    if [[ -n "$resp" ]] && echo "$resp" | grep -q "\"ip\":\"${expect_ip}\"" && echo "$resp" | grep -q "\"port\":${port}"; then
      echo "Nacos registered ${service_name} -> ${expect_ip}:${port}"
      return 0
    fi
    sleep 1
  done

  echo "ERROR: ${service_name} not registered in Nacos as ${expect_ip}:${port} within 30s" >&2
  return 1
}

start_service() {
  local key="$1"
  local jbin="$2"
  local module
  module="$(module_name "$key")"
  local port
  port="$(module_port "$key")"
  local service_name
  service_name="$(module_service_name "$key")"
  local pid_file="$RUN_DIR/${module}.pid"
  local log_file="$LOG_DIR/${module}.log"

  local jar
  jar="$(ls -t "$ROOT_DIR/${module}/target/${module}-"*.jar 2>/dev/null | head -n 1 || true)"
  if [[ -z "$jar" ]]; then
    echo "ERROR: jar not found for $module. Did build succeed?" >&2
    return 1
  fi

  echo "Starting $module ..."
  nohup "$jbin" -jar "$jar" >"$log_file" 2>&1 &
  local pid=$!
  echo "$pid" >"$pid_file"
  wait_for_port "$module" "$port" "$pid" "$log_file"
  if ! wait_for_nacos "$service_name" "$port"; then
    kill "$pid" >/dev/null 2>&1 || true
    return 1
  fi
}

build_all() {
  echo "Building all modules ..."
  (cd "$ROOT_DIR" && mvn -q -DskipTests package)
}

build_module() {
  local key="$1"
  local module
  module="$(module_name "$key")"
  echo "Building $module ..."
  (cd "$ROOT_DIR" && mvn -q -pl "$module" -am -DskipTests package)
}

normalize_targets() {
  local -a raw=("$@")
  if [[ ${#raw[@]} -eq 0 ]]; then
    echo "ERROR: missing target." >&2
    usage
    exit 1
  fi

  if [[ "${raw[0]}" == "all" ]]; then
    printf '%s\n' "${SERVICES[@]}"
    return
  fi

  local t
  for t in "${raw[@]}"; do
    if ! is_valid_service "$t"; then
      echo "ERROR: unknown service '$t'" >&2
      usage
      exit 1
    fi
  done
  printf '%s\n' "${raw[@]}"
}

main() {
  local jbin
  jbin="$(java_bin)"

  local -a targets=()
  local item
  while IFS= read -r item; do
    [[ -n "$item" ]] && targets+=("$item")
  done < <(normalize_targets "$@")

  if [[ "$1" == "all" ]]; then
    build_all
  else
    local t
    for t in "${targets[@]}"; do
      build_module "$t"
    done
  fi

  local t
  for t in "${targets[@]}"; do
    stop_service "$t"
    start_service "$t" "$jbin"
  done

  echo "Done."
}

main "$@"
