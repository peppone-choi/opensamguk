# 합성 豫州 월드 W0–W4 증거 수집

이 절차는 `scenario_990002`의 **격리 로컬 세계**를 검증한다. 운영 pep의 reset·배포·복원 절차가 아니다. 지도 城 수와 이미지 SHA가 바뀌면 아래 수집을 최종 SHA에서 다시 한다. 과거 1224 城 증거를 1447/1428 城의 합격으로 옮기지 않는다. 수집 전 [전환 런북](hwiha-pep-transition.md)의 중단 조건과 [복구 런북](game-server-recovery.md)의 냉간 백업 선행 조건을 별도로 확인한다.

## 0. 기준 고정과 실행 슬롯

최종 후보가 main에 병합된 뒤 정확한 SHA를 기록한다. 지도 판을 이 문서에 하드코딩하지 않는다. 같은 SHA에서 실행한 시험·격리 이미지·시나리오 파일의 결과만 묶는다. Gradle, Docker 이미지 빌드, PG/Redis 통합 시험과 브라우저 스택은 공용 로컬 자원을 쓰므로 운영 조율의 슬롯을 배정받아 **순차** 실행한다. 슬롯 전에는 이 문서와 Python 수집기의 오프라인 검증만 가능하다.

```sh
git rev-parse HEAD
git status --short
shasum -a 256 infra/src/main/resources/map/han-world-v3.json \
  infra/src/main/resources/scenario/scenario_990002.json \
  tools/e2e/fixtures/yuzhou/scenario_990002.json
gh run list --workflow CI --commit "$(git rev-parse HEAD)" --json databaseId,headSha,conclusion,url
```

`git status`가 깨끗해야 하고, 두 시나리오 사본의 해시가 같아야 한다. 마지막 명령의 CI가 현재 SHA의 필수 잡을 모두 성공으로 보이는지 확인한다. W0의 선택 DB IT 목록과 건너뜀 0 근거는 [S3 종료 명세](../superpowers/specs/2026-09-23-hwiha-s3-exit-yuzhou-slice.md)의 W0 및 당시 보고서를 기준으로 최신 테스트 이름과 비교한다. CI 한 번의 초록은 W1의 세 번 비교가 아니다.

지도에서 합성 시나리오를 다시 만드는 생성기와 커밋된 fixture의 일치도 W0에 기록한다. 다음 실행은 Gradle 슬롯이 배정된 뒤에만 한다.

```sh
JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./gradlew :infra:test \
  --tests 'opensamguk.infra.seed.YuzhouSliceScenarioTest' --rerun-tasks
```

## 1. W1 관문·적색 짝·모의 시험

다음 세 클래스는 48순 실제 DB/Redis 관문, 초기 부곡 제거로 같은 단언을 일부러 깨는 적색 짝, 36순 결정론 모의다. 적색 짝 클래스의 **시험 자체는 통과**해야 하며, 내부의 `assertChain`이 행군·조우·공성에서 실패했음을 단언한다. 세 회차 모두 **같은 Git SHA**에서 실행하고 각 회차의 XML·출력·첫 성립 순·세계 상태 SHA를 비교한다. 다른 작업의 Gradle 실행과 겹치지 않는다.

```sh
set -euo pipefail
evidence_dir="$(mktemp -d "${TMPDIR:-/tmp}/yuzhou-w1-$(git rev-parse --short=12 HEAD)-XXXXXX")"
for attempt in 1 2 3; do
  mkdir -p "$evidence_dir/attempt-$attempt"
  JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./gradlew :app:game-engine:test \
    --tests 'opensamguk.engine.boot.PassChainInvarianceIT' \
    --tests 'opensamguk.engine.boot.S3PassChainProbeIT' \
    --tests 'opensamguk.engine.invariance.YuzhouCampaignInvarianceTest' \
    --rerun-tasks >"$evidence_dir/attempt-$attempt/gradle.log" 2>&1
  cp app/game-engine/build/test-results/test/TEST-*PassChainInvarianceIT.xml \
    app/game-engine/build/test-results/test/TEST-*S3PassChainProbeIT.xml \
    app/game-engine/build/test-results/test/TEST-*YuzhouCampaignInvarianceTest.xml \
    "$evidence_dir/attempt-$attempt/"
done
```

각 XML의 `failures=0`, `errors=0`, `skipped=0`과 `BUILD SUCCESSFUL`을 확인한다. 결과 본문에서 첫 `enlist/march/siege/income/salary/assessment/rank/encounter/capture/dispatch` 순과 `world-state-sha256`을 표로 비교한다. 기준선 변경은 원인과 설계 근거를 별도로 판정한다. [결정론 기준선 README](../../app/game-engine/src/test/resources/invariance/README.md)가 SHA 투영 범위를 설명한다.

## 2. W2·W3 독립 증거

W2는 함락 수비대, 수도·보급망, 풀 수 없는 조우, 월 경계 외교 만료, map4 도로/보루/보급, 경계 예외 20곳의 회귀 결과를 기록한다. 예를 들어 다음 선택 테스트는 W2의 **일부**만 다루며, 20곳 전체의 도달 불가 또는 건너뛰기+기록 판정을 대신하지 않는다.

```sh
JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./gradlew :app:game-engine:test \
  --tests 'opensamguk.engine.campaign.SiegeServiceTest' \
  --tests 'opensamguk.engine.campaign.CapitalAfterCaptureTest' \
  --tests 'opensamguk.engine.campaign.EncounterResolverTest' \
  --tests 'opensamguk.engine.siege.RoadFortSiegeServiceTest' --rerun-tasks
```

W3은 공성 입력/거절과 조정 입력, 9화면의 현재 API 계약을 확인한다. 웹 시험·타입 검사와 W4 화면 캡처를 함께 근거로 남긴다. 건너뛴 시험이나 단순 버튼 노출은 실행 성공으로 세지 않는다.

```sh
pnpm --dir web/game exec vitest run __tests__/siege-orders.test.tsx __tests__/CourtForm.test.tsx --maxWorkers=1
pnpm --dir web/game typecheck
```

## 3. W4 새 격리 스택

`tools/e2e/local_v1_gate.sh`는 프로젝트별 새 Compose 이름·새 PG/Redis 볼륨을 만들고, 종료 시 자기 자원만 정리한다. 실행자는 실제로 비어 있는 9개 호스트 포트와 테스트 전용 `JWT_PRIVATE_KEY`, `JWT_PUBLIC_KEY`, `INTERNAL_SERVICE_TOKEN`, `POSTGRES_PASSWORD`를 **값을 출력하지 않고** 공급한다. 공개 URL은 이미지 빌드 전에 선택한 포트로 맞춘다. 운영 키·운영 데이터·운영 서버 ID는 사용하지 않는다. 다음 명령은 저장소 루트에서 실행한다.

```sh
set -euo pipefail
: "${JWT_PRIVATE_KEY:?test-only key required}"
: "${JWT_PUBLIC_KEY:?test-only key required}"
: "${INTERNAL_SERVICE_TOKEN:?test-only token required}"
: "${POSTGRES_PASSWORD:?isolated database password required}"
: "${POSTGRES_PORT:?unused host port required}"
: "${REDIS_PORT:?unused host port required}"
: "${GATEWAY_API_PORT:?unused host port required}"
: "${BOARD_API_PORT:?unused host port required}"
: "${GAME_API_PORT:?unused host port required}"
: "${GAME_ENGINE_PORT:?unused host port required}"
: "${WEB_GATEWAY_PORT:?unused host port required}"
: "${WEB_GAME_PORT:?unused host port required}"
: "${NGINX_HTTP_PORT:?unused host port required}"
export SCENARIO_CODE=scenario_990002 TURN_PROFILE_NAME=che:scenario_990002
export OPENSAMGUK_WORLD_ID=990002 SCENARIO_HOST_DIR="$PWD/tools/e2e/fixtures/yuzhou"
export SCENARIO_QA_TURNTERM=1 E2E_ENABLE_AUTH=true E2E_YUZHOU_LIVE=true
export E2E_TEST_SPEC=e2e/yuzhou-live.spec.ts E2E_TEST_TIMEOUT_MS=3000000
export E2E_BUILD_MODE=sequential
export NEXT_PUBLIC_GATEWAY_URL="http://localhost:${WEB_GATEWAY_PORT}"
export NEXT_PUBLIC_GAME_URL="http://localhost:${WEB_GAME_PORT}"
export GATEWAY_WEB_URL=http://web-gateway:3000
export E2E_ARTIFACT_DIR="$(mktemp -d "${TMPDIR:-/tmp}/yuzhou-w4-$(git rev-parse --short=12 HEAD)-XXXXXX")"
tools/e2e/local_v1_gate.sh
python3 tools/e2e/collect_yuzhou_evidence.py "$E2E_ARTIFACT_DIR"
```

위 변수명은 현재 격리 fixture의 계약이다. #917의 식별자 은퇴와 최종 SHA에서 `SCENARIO_CODE`, `TURN_PROFILE_NAME`, 시나리오 실제 적재 경로를 다시 대조한다.

이미지 ID는 위 runner가 살아 있는 동안 **별도 터미널**에서 아래 읽기 전용 명령으로 기록한다. `E2E_ARTIFACT_DIR`는 같은 절대 경로를 입력한다. `compose-project-name.txt`가 만들어지고 서비스가 뜬 뒤 실행하며, 비어 있는 결과는 합격 증거가 아니다.

```sh
project="$(cat "$E2E_ARTIFACT_DIR/compose-project-name.txt")"
override="$E2E_ARTIFACT_DIR/$project-containers.json"
for service in gateway-api board-api game-api game-engine web-gateway web-game; do
  container="$(docker compose --project-name "$project" --env-file /dev/null \
    -f docker-compose.yml -f "$override" ps -q "$service")"
  test -n "$container" || exit 1
  printf '%s\t%s\n' "$service" "$(docker inspect --format '{{.Image}}' "$container")"
done >"$E2E_ARTIFACT_DIR/container-image-ids.tsv"
```

실행 전 `docker-compose.yml`의 공개 URL 변수 이름과 현재 빌드 인자를 다시 대조한다. 스크립트는 합성 QA 세계에서만 가입·장수 생성·출사·발령·행군·NPC 공성/점령·월 정산을 진행한다. 사람 장수의 `killturn`을 96으로 올리기 위한 유일한 사후 DB write는 엔진 정지 중 이루어지고, 재시작 뒤 보존을 확인한다. 이 격리 write를 운영 DB에 적용하지 않는다. 포위 12–24순 목표와 월단평 편향은 사건표를 읽어 별도 판정한다. 실스택 E2E는 조우 >0, 중립 재점령 0, 수비대가 남은 점령 縣의 중립화 0, 첫 월 뒤 적대 30관계, 월단평 READY, 엔진 로그 `tick failed` 0을 단언한다.

## 4. 산출물과 판정표

`E2E_ARTIFACT_DIR` 아래 `playwright-results.json`은 원본이다. 수집기는 건너뛴/재시도한/실패한 Playwright 결과와 화면·API·DB 첨부 누락을 거절하고, 이미 있는 다른 바이트의 파일을 덮어쓰지 않는다. `--check-only`는 출력 없이 형식만 확인한다. 산출물은 개인 계정·장수 데이터와 게임 응답을 포함할 수 있으므로 PR에 원문을 넣지 않고 검토 가능한 보안 저장 위치와 요약·해시만 보고한다.

| 파일 | 내용·판정 |
|---|---|
| `playwright.log`, `playwright-results.json` | 단일 흐름 1/1, skip 0, 191-01 월 경계 도달과 소요 시간 |
| `attachments/screen-{court,hand,orders,posts,retinue,siege,supply,war-room,yuedan}.png` | 실제 데이터 로딩 뒤 9화면을 사람이 열어 확인 |
| `attachments/api-<screen>-<index>.json` | 각 화면의 200 응답. 현 스펙은 13개 경로이며 이전 1224 城 실행은 12개였다. 이름·개수는 결과에서 재측정 |
| `attachments/db-hwiha-slice.json` | 포위 상태/지속 순, 사람 발령·위치, 창고, 월 징세·녹봉·월단평 도장/순위 |
| `attachments/phase-events.json`, `phase-event-counts.tsv` | `year,month,phase,event_kind,count` 순별 사건표. 조우·포위·점령·징세·녹봉 실측 |
| `attachments/phase-evidence.json` | 행군 진척, 포위·NPC 전투, 조우, 중립 재점령, 적대 기간, 월단평 |
| `yuzhou-evidence-manifest.json` | 원본 결과와 추출 첨부의 SHA256, 화면/API/사건 수. 내부 계정 정보가 섞인 `phase_evidence`는 공개 PR 첨부에서 제외 |
| `docker-compose-build-*.log`, `health-*`, `cleanup-resources.txt` | 이미지 순차 빌드, 서비스 상태, 격리 볼륨·컨테이너 정리 |
| `container-image-ids.tsv` | 살아 있는 격리 컨테이너의 정확한 이미지 ID. 별도 터미널에서 수집 |

현 Playwright 화면별 API 수집 경로는 `web/game/e2e/yuzhou-live.spec.ts`의 `paths`가 정본이다. 현재는 court=`map/preview`,`commands/dispatches`; hand=`commands/stratagem-hand`; orders=`retinue`,`warehouses`; posts=`posts`; retinue=`retinue`; siege=`sieges`; supply=`warehouses`; war-room=`visibility`,`corps`,`sieges`; yuedan=`yuedan`이다. 모두 게임 웹의 `/api/game/api/` 경유이며 장수별 경로는 `generalId`를 붙인다. 경로를 바꾸면 수집 결과의 화면별 API 집합을 다시 검토한다.

최종 보고 표에는 Git SHA, 지도·시나리오 해시, 실제 사용 외부 시나리오 경로, 이미지 ID/digest, W0–W3 결과, W4 사건 수와 첫 발생 순, 화면 9종 확인, `tick failed=0`, 미검증 항목을 각각 기입한다. 엔진 컨테이너 이미지 ID는 스택이 살아 있는 동안 `compose-project-name.txt`와 생성된 Compose override를 이용해 `docker inspect`로 기록해야 한다. 종료 뒤 격리 이미지 별칭은 삭제되므로 빌드 로그만으로 digest를 추정하지 않는다. 어떤 관문도 실패하거나 미확인이면 전환 런북에는 `BLOCKED`로 적는다.
