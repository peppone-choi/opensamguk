#!/usr/bin/env bash
# verify-changes.sh — 현재 diff를 분류해 최소 검증을 안내/실행
#
# 사용:
#   scripts/agent/verify-changes.sh          # 분류 + 필요한 검증 명령 출력 (기본, 부작용 없음)
#   scripts/agent/verify-changes.sh --run    # 해당 모듈 검증을 실제 실행 (gradle은 느릴 수 있음)
#
# 판정 원칙 (docs/agent/verification.md):
#  - gradle exit code로 성공 주장 금지 — 출력 tail의 BUILD SUCCESSFUL + 테스트 XML을 봐야 한다.
#  - 이 스크립트는 무한 자동수정 루프를 만들지 않는다: 실행은 1회, 실패 시 보고 후 종료.
set -u
cd "$(git rev-parse --show-toplevel 2>/dev/null || echo .)"

RUN=0; [ "${1:-}" = "--run" ] && RUN=1
CHANGED="$( { git diff --name-only HEAD 2>/dev/null; git diff --name-only --cached 2>/dev/null; \
              git ls-files --others --exclude-standard 2>/dev/null; } | sort -u | grep -v '^$' || true)"

if [ -z "$CHANGED" ]; then echo "변경 없음 — 검증할 diff가 없다."; exit 0; fi
echo "== 변경 파일 =="; printf '%s\n' "$CHANGED"; echo

has() { printf '%s\n' "$CHANGED" | grep -qE "$1"; }
NEED=""

has '^common/'           && NEED="$NEED :common:test :logic:test"
has '^logic/'            && NEED="$NEED :logic:test"
has '^infra/'            && NEED="$NEED :infra:test"
has '^app/game-engine/'  && NEED="$NEED :app:game-engine:test"
has '^app/game-api/'     && NEED="$NEED :app:game-api:test"
has '^app/gateway-api/'  && NEED="$NEED :app:gateway-api:test"
NEED="$(printf '%s' "$NEED" | tr ' ' '\n' | sort -u | grep -v '^$' | tr '\n' ' ')"

WEB_GW=0;   has '^web/gateway/' && WEB_GW=1
WEB_GAME=0; has '^web/game/'    && WEB_GAME=1
CODE=0;     has '\.(kt|kts|ts|tsx|sql|sh|py)$' && CODE=1

echo "== 필요한 최소 검증 (docs/agent/verification.md 행렬) =="
if [ -n "$NEED" ]; then
  echo "JAVA_HOME=\$(/usr/libexec/java_home -v 21) ./gradlew $NEED--rerun-tasks 2>&1 | tail -40"
  echo "  → BUILD SUCCESSFUL + build/test-results/**/*.xml 의 failures=\"0\" errors=\"0\" 확인"
fi
[ $WEB_GW -eq 1 ]   && echo "cd web/gateway && pnpm typecheck"
[ $WEB_GAME -eq 1 ] && echo "cd web/game && pnpm typecheck && pnpm test"
has '^(docker/|docker-compose|infra/nginx/)' && echo "./tools/smoke.sh   # compose/nginx 변경"
[ $CODE -eq 0 ] && echo "git diff --check   # 문서/설정만 변경 — 코드 게이트 불필요"
echo "python3 tools/agent-system/check.py   # 공통 가드"
echo

if [ $RUN -eq 1 ]; then
  FAIL=0
  if [ -n "$NEED" ]; then
    echo "== gradle 실행 =="
    JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew $NEED --rerun-tasks 2>&1 | tail -40
    XML_FAIL="$(find . -path '*/build/test-results/*' -name '*.xml' \
      -exec grep -lE '(failures|errors)="[1-9]' {} + 2>/dev/null || true)"
    if [ -n "$XML_FAIL" ]; then echo "XML 실패 감지:"; echo "$XML_FAIL"; FAIL=1; fi
  fi
  if [ $WEB_GW -eq 1 ];   then (cd web/gateway && pnpm typecheck) || FAIL=1; fi
  if [ $WEB_GAME -eq 1 ]; then (cd web/game && pnpm typecheck && pnpm test) || FAIL=1; fi
  python3 tools/agent-system/check.py || FAIL=1
  echo
  if [ $FAIL -eq 1 ]; then
    echo "RESULT: FAIL — 결과를 보고하라. 자동 재수정 루프 금지(1회 실행 원칙)." >&2
    exit 1
  fi
  echo "RESULT: 실행분 green. (단, gradle은 XML을 직접 확인했는지 재점검할 것)"
fi
