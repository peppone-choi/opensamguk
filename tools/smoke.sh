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

# Use the same host ports docker-compose.yml binds (defaults match .env.example).
GATEWAY_API_PORT="${GATEWAY_API_PORT:-8080}"
BOARD_API_PORT="${BOARD_API_PORT:-8083}"
GAME_API_PORT="${GAME_API_PORT:-8081}"
GAME_ENGINE_PORT="${GAME_ENGINE_PORT:-8082}"
WEB_GATEWAY_PORT="${WEB_GATEWAY_PORT:-3000}"
WEB_GAME_PORT="${WEB_GAME_PORT:-3001}"
NGINX_HTTP_PORT="${NGINX_HTTP_PORT:-80}"

wait_for "gateway-api" "http://localhost:${GATEWAY_API_PORT}/actuator/health"
wait_for "board-api"   "http://localhost:${BOARD_API_PORT}/actuator/health"
wait_for "game-api"    "http://localhost:${GAME_API_PORT}/actuator/health"
wait_for "game-engine" "http://localhost:${GAME_ENGINE_PORT}/admin/turn-daemon/status"
wait_for "web-gateway" "http://localhost:${WEB_GATEWAY_PORT}/api/health"
wait_for "web-game"    "http://localhost:${WEB_GAME_PORT}/api/health"
wait_for "nginx->gateway" "http://localhost:${NGINX_HTTP_PORT}/api/gateway/actuator/health"

echo "==> ALL SERVICES HEALTHY"
docker compose down
