#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# prod 재시딩 — 게임 월드를 시나리오 시작점으로 리셋한다.
#
# 무엇을 하나:
#   1) 엔진 정지(쓰기 차단)
#   2) 게임 테이블 전부 TRUNCATE — 단, `users`(로그인 계정) + `flyway_schema_history`는 보존
#      (전 앱이 단일 postgres DB `sammo`를 공유하므로 볼륨 통째 wipe는 유저 계정까지 날린다.
#       외과적 truncate로 "게임만 리셋, 로그인 유지").
#   3) redis FLUSHALL(이전 턴 데몬 스트림/캐시 잔여 제거 — durable 게임 진실 아님)
#   4) 엔진 재시작 → world_state 비어있음 → ScenarioSeedRunner가 시나리오 재시드(capital_city_id 포함)
#   5) game-api 재시작(MapPreview 10분 캐시 비움 → 수도 별 즉시 반영)
#   6) 검증 출력(world_state / 수도 보유 국가 수)
#
# 대상: 본섭(opensamguk-postgres / sammo / scenario_1010) + 빼섭(opensamguk-bbae-db / samguk / scenario_1030)
#
# 선행조건: parity-final이 main에 머지·배포 완료(엔진/게임-api 이미지에 수도 렌더+isCapital 로직 반영)된 뒤 실행.
#
# 사용법 (EC2 박스에서):
#   bash reseed-prod.sh             # 양 서버 모두
#   bash reseed-prod.sh main        # 본섭만
#   bash reseed-prod.sh bbae        # 빼섭만
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail

TARGET="${1:-all}"

# 보존 테이블 — 로그인 계정 + Flyway 이력. 그 외 public 스키마 전 테이블이 truncate 대상.
PRESERVE="'users','flyway_schema_history'"

confirm() {
    echo "⚠️  파괴적 작업: '$1' 게임 월드를 시나리오 시작점으로 리셋한다(장수/도시/턴/연월 소실, 로그인 계정은 보존)."
    read -r -p "    진행하려면 RESEED 입력: " ans
    [ "$ans" = "RESEED" ] || { echo "    취소됨."; return 1; }
}

reseed_stack() {
    local pg="$1" db="$2" dbuser="$3" engine="$4" api="$5" redis="$6" label="$7"
    echo "=== [$label] 재시딩 ($pg / db=$db) ==="

    # 1) 엔진 정지(쓰기 차단).
    docker stop "$engine" >/dev/null && echo "    엔진 정지: $engine"

    # 2) 게임 테이블 전부 TRUNCATE (users + flyway 제외). RESTART IDENTITY로 시퀀스 리셋, CASCADE로 FK 처리.
    docker exec -i "$pg" psql -U "$dbuser" -d "$db" -v ON_ERROR_STOP=1 >/dev/null <<SQL
DO \$\$
DECLARE r RECORD;
BEGIN
  FOR r IN
    SELECT tablename FROM pg_tables
    WHERE schemaname = 'public' AND tablename NOT IN ($PRESERVE)
  LOOP
    EXECUTE 'TRUNCATE TABLE public.' || quote_ident(r.tablename) || ' RESTART IDENTITY CASCADE';
  END LOOP;
END \$\$;
SQL
    echo "    게임 테이블 TRUNCATE 완료 (users 보존)"

    # 3) redis 비우기(턴 데몬 스트림/캐시 잔여).
    docker exec "$redis" redis-cli FLUSHALL >/dev/null && echo "    redis FLUSHALL: $redis"

    # 4) 엔진 재시작 → 빈 world_state → 재시드.
    docker start "$engine" >/dev/null && echo "    엔진 재시작: $engine (재시드 진행)"
    sleep 25
    echo "    --- 엔진 시드 로그(tail) ---"
    docker logs --tail 8 "$engine" 2>&1 | grep -iE "seed|world|nation|general|scenario|complete|error|exception" | tail -8 | sed 's/^/      /' || true

    # 5) game-api 재시작 → MapPreview 10분 캐시 비움.
    docker restart "$api" >/dev/null && echo "    game-api 재시작(맵 캐시 비움): $api"

    # 6) 검증.
    echo "    --- 검증 ---"
    docker exec -i "$pg" psql -U "$dbuser" -d "$db" -tAc \
        "SELECT 'world_state='||count(*) FROM world_state" 2>/dev/null | sed 's/^/      /' || true
    docker exec -i "$pg" psql -U "$dbuser" -d "$db" -tAc \
        "SELECT 'nation_total='||count(*)||' nation_with_capital='||count(capital_city_id) FROM nation" 2>/dev/null | sed 's/^/      /' || true
    echo "=== [$label] 완료 ==="
    echo
}

cd ~/opensamguk 2>/dev/null || true

if [ "$TARGET" = "all" ] || [ "$TARGET" = "main" ]; then
    confirm "본섭/1010" && reseed_stack \
        opensamguk-postgres sammo sammo \
        opensamguk-game-engine opensamguk-game-api opensamguk-redis "본섭/1010"
fi

if [ "$TARGET" = "all" ] || [ "$TARGET" = "bbae" ]; then
    confirm "빼섭/1030" && reseed_stack \
        opensamguk-bbae-db samguk samguk \
        opensamguk-bbae-game-engine opensamguk-bbae-game-api opensamguk-bbae-redis "빼섭/1030"
fi

echo "전체 재시딩 종료. 지도에서 국가별 수도 별(event51) + 축소된 도시 아이콘을 확인하라."
