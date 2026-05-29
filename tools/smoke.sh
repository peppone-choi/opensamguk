#!/usr/bin/env bash
set -euo pipefail

echo "==> building + starting stack"
docker compose up -d --build

cleanup() { docker compose logs --no-color > tools/smoke.log 2>&1 || true; }
trap cleanup EXIT

wait_for() {
    local name="$1" url="$2" tries=60
    echo "==> waiting for $name ($url)"
    until curl -fsS "$url" >/dev/null 2>&1; do
        tries=$((tries - 1))
        if [ "$tries" -le 0 ]; then echo "FAIL: $name not healthy"; exit 1; fi
        sleep 3
    done
    echo "OK: $name"
}

wait_for "gateway-api" "http://localhost:8080/actuator/health"
wait_for "game-api"    "http://localhost:8081/actuator/health"
wait_for "game-engine" "http://localhost:8082/admin/turn-daemon/status"
wait_for "web-gateway" "http://localhost:3000/api/health"
wait_for "web-game"    "http://localhost:3001/api/health"
wait_for "nginx->gateway" "http://localhost:80/api/gateway/actuator/health"

echo "==> ALL SERVICES HEALTHY"
docker compose down
