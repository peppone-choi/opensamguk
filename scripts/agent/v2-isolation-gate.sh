#!/usr/bin/env bash
# OPENSAM-35 V2-0A 격리 게이트 ②③⑤ + C1 — 실행 가능 정본.
#
# 왜 스크립트인가 (OPENSAM-188): 게이트가 문서의 복붙 코드블록으로만 존재하는 동안
# 두 종류의 사람 실수가 실제로 발생했다.
#   ① pathspec 오타 — `'app/*/src/main/kotlin/'`은 git wildmatch에서 항상 빈 출력이라
#      게이트 ③의 app/ 절반이 아무것도 검사하지 않았다(빈 출력 = 공허하게 참).
#   ② 기준선 오지정 — `origin/main`을 그대로 쓰면 분기 후 머지된 타 브랜치 변경이
#      섞여 거짓 위반이 뜬다. 기준선은 merge-base 고정이다.
# 이 스크립트는 둘 다 사람이 틀릴 수 없게 고정한다.
#
# 사용:  scripts/agent/v2-isolation-gate.sh [<ref>]
#   <ref> 생략 시 HEAD(워킹트리 포함). 기준선은 항상 merge-base(<ref>, origin/main).
# 종료코드: 0 = 전 게이트 빈 출력, 1 = 하나라도 위반(fail-closed).
set -uo pipefail

REF="${1:-HEAD}"
BASE_REF="${V2_GATE_BASE_REF:-origin/main}"
MB="$(git merge-base "$REF" "$BASE_REF")" || {
  echo "FATAL: merge-base($REF, $BASE_REF) 계산 실패" >&2
  exit 1
}
# HEAD를 재면 워킹트리까지 포함하도록 <to>를 비운다(더 엄격한 쪽).
# `${TO[@]+"${TO[@]}"}` 형태는 bash 3.2(macOS 기본)에서 빈 배열 + `set -u` 조합이
# "unbound variable"로 죽는 것을 피한다. 그냥 `"${TO[@]}"`로 쓰면 3.2에서 게이트가
# 조용히 전부 PASS로 떨어진다 — 검사기가 침묵하는 것이 이 스크립트가 막으려는 결함이다.
if [ "$REF" = "HEAD" ]; then TO=(); else TO=("$REF"); fi

echo "MB=$MB  ($(git log -1 --format='%h %s' "$MB"))"
echo "REF=$REF ($(git rev-parse --short "$REF"))"
echo

rc=0
gate() { # gate <이름> <pathspec...>
  local name="$1"; shift
  local out
  if ! out="$(git diff --name-only --diff-filter=MD "$MB" ${TO[@]+"${TO[@]}"} -- "$@")"; then
    echo "ERROR     $name — git diff 실패 (fail-closed)"
    rc=1
    return
  fi
  if [ -n "$out" ]; then
    echo "VIOLATION $name"
    echo "$out" | sed 's/^/  /'
    rc=1
  else
    echo "PASS      $name"
  fi
}

# ② T1 — 패러티 코어 + 기존 테스트: 수정·삭제 0건. 신규 파일 추가만 허용(--diff-filter=MD).
#
# 좁히기 (OPENSAM-190) — 테스트 루트의 `**/v2/**` 디렉터리만 제외한다. 게이트 ⑤의 README
# 제외와 동형 결함이었다: v2 소유 테스트가 통째로 동결돼 v2 후속 티켓이 OPENSAM-35가 만든
# 자기 테스트를 고칠 구조적 방법이 없었다. 특히 `V2ProductionContextBeanGateIT`는
# "production에 v2 빈 0개"를 **v2 빈 타입을 하나씩 열거해** 증명하므로, 얼려 두면 v2가
# 자랄수록 격리 증명이 낡는다 — 즉 동결이 격리를 지키는 게 아니라 좀먹는다.
#
# 제외 범위가 좁은 이유:
#   · 디렉터리 세그먼트 `/v2/`만 본다. `V26NpcLifecycleMigrationTest.kt` 같은 **Flyway
#     버전 번호가 앞에 붙은 v1 테스트**는 `infra/.../persistence/`에 있어 걸리지 않는다.
#   · `logic/src/main/kotlin/**`·`common/src/main/kotlin/**`·golden은 제외 대상이 아니다
#     (v2 소유 main 소스도 T1 동결 유지).
#   · v1 테스트를 `/v2/`로 **옮겨서** 고치는 우회는 원경로의 삭제가 `--diff-filter=MD`에
#     걸리므로 여전히 위반이다.
gate "② T1 parity core + existing tests (테스트 루트의 v2 디렉터리 제외)" \
  ':(glob)logic/src/main/kotlin/**' \
  ':(glob)common/src/main/kotlin/**' \
  ':(glob)logic/src/test/resources/golden/**' \
  ':(glob)logic/src/test/kotlin/**' \
  ':(glob)common/src/test/kotlin/**' \
  ':(glob)infra/src/test/kotlin/**' \
  ':(glob)app/*/src/test/kotlin/**' \
  ':(glob,exclude)logic/src/test/kotlin/**/v2/**' \
  ':(glob,exclude)common/src/test/kotlin/**/v2/**' \
  ':(glob,exclude)infra/src/test/kotlin/**/v2/**' \
  ':(glob,exclude)app/*/src/test/kotlin/**/v2/**'

# ②' 제외된 v2 테스트의 수정·삭제 목록 (OPENSAM-191).
#    ②의 제외는 v2 테스트를 **통째로 무검사**로 만든다 — `V2ProductionContextBeanGateIT`를
#    지우거나 비워도 게이트가 침묵한다. 블라스트 반경은 v1 파손이 아니라 증명 부패이므로
#    ③과 동형으로 rc에 반영하지 않고 목록만 낸다(사람이 티켓 선언과 대조).
echo
echo "LIST      ②' excluded v2 tests — 수정·삭제 (삭제/축소는 격리 증명 부패, 티켓과 대조할 것)"
git diff --name-only --diff-filter=MD "$MB" ${TO[@]+"${TO[@]}"} -- \
  ':(glob)logic/src/test/kotlin/**/v2/**' \
  ':(glob)common/src/test/kotlin/**/v2/**' \
  ':(glob)infra/src/test/kotlin/**/v2/**' \
  ':(glob)app/*/src/test/kotlin/**/v2/**' | sed 's/^/  /'

echo
# ③ T2 — 경계 수정 목록. 출력이 티켓 본문 사전선언과 "정확히" 일치해야 한다(초과 = 위반).
#    빈 출력이 아니어도 되는 유일한 게이트이므로 rc에 반영하지 않고 목록만 낸다.
echo "LIST      ③ T2 boundary edits (티켓 사전선언과 대조할 것 — 초과 = 위반)"
git diff --name-only --diff-filter=MD "$MB" ${TO[@]+"${TO[@]}"} -- \
  ':(glob)app/*/src/main/kotlin/**' \
  ':(glob)infra/src/main/kotlin/**' \
  ':(glob)infra/src/main/resources/db/migration/**' | sed 's/^/  /'

# ⑤ 설정 리소스 무수정. README.md만 제외 — 어떤 로더도 읽지 않으므로(Flyway는 V*.sql,
#    V2ContentCatalog는 *.json만 스캔) v1 런타임을 바꿀 수 없고, 제외하지 않으면
#    v2 문서가 영구 갱신 불가가 된다(OPENSAM-188 결함 ②). yml·json·sql·map은 동결 유지.
#
# 좁히기 (OPENSAM-152) — `scenario/scenario_9[0-9][0-9][0-9].json`(9000번대)만 추가로 제외한다.
# 게이트 ⑤의 목적은 "v1 프로덕션 설정이 드리프트하지 않을 것"인데 9000번대는 정의상 **v2 샌드박스
# 전용 시나리오**라 v1 설정이 아니다: `ScenarioCatalogService.V2_SANDBOX_CODE_FLOOR`(=9000)가 v1
# 선택 목록에서 통째로 걸러내므로 v1 운영자에게 보이지도, 시드되지도 않는다.
# 좁히지 않으면 R2가 main에 머지된 순간부터 `scenario_9200.json`이 Addition이 아니라 Modification이
# 되어, R4~R6이 자기 leaf 행을 그 파일에 append할 구조적 방법이 사라진다(② 가 테스트 루트의
# `**/v2/**`를 제외한 OPENSAM-190과 같은 모양의 결함).
#
# 제외 범위가 좁은 이유:
#   · 정확히 4자리 9000번대 파일명만 본다. 기존 대역(0~2 / 900번대 / 1010~1120)은 걸리지 않는다.
#   · `scenario/` 디렉터리 밖의 리소스(application*.yml, db/migration/**, map/**)는 그대로 동결.
#   · v1 시나리오를 9000번대로 **옮겨서** 고치는 우회는 원경로 삭제가 --diff-filter=MD 에 걸린다.
gate "⑤ configuration resources (README.md · v2 9000번대 시나리오 제외)" \
  ':(glob)app/*/src/main/resources/**' \
  ':(glob)infra/src/main/resources/**' \
  ':(glob,exclude)app/*/src/main/resources/**/README.md' \
  ':(glob,exclude)infra/src/main/resources/**/README.md' \
  ':(glob,exclude)infra/src/main/resources/scenario/scenario_9[0-9][0-9][0-9].json'

# C1 — production 불변식 파일: 수정 0건.
gate "C1 production compose + checker" \
  docker-compose.production.yml docker-compose.yml tools/agent-system/check.py

echo
[ "$rc" -eq 0 ] && echo "GATE RESULT: PASS (②'·③ 목록은 사람이 티켓 선언과 대조)" \
                || echo "GATE RESULT: FAIL"
exit "$rc"
