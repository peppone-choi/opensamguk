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

### 정규화 행 차이의 별도 진단

세계 상태 SHA가 다르면 지도 구조와 캠페인 결과를 나눠 확인한다. `compare_campaign_rows.py`는 `WorldStateBaseline`이 출력한 `behavior-row ` 행이 들어 있는 `PassChainInvarianceIT.xml` 또는 개행으로 끝나는 행 파일 두 개를 받아, 원본/정규화 행 SHA와 출처 Git·지도·시나리오 핀을 가진 `row-diff.json`을 만든다. 구조 항목은 城 ID의 제거·추가, 공통 城의 값 변경은 별도 혼합 항목, 공성·부곡·장수·국가·위치·달력은 캠페인 결과 항목에 둔다. 城 값 변화의 원인을 지도 입력이나 전투로 자동 귀속하지 않는다. 진단 계측을 임시 commit에 붙였다면 그 계측 SHA와 artifact ID를 기록하고, 최종 SHA의 W1 세 번을 대체하지 않는다.

L7의 1447 기준/1428 후보 판정용 통제 비교는 **동일한 최신 엔진 Git SHA와 동일한 시나리오 SHA**에서 phase 0과 phase 48을 각각 수집한다. 이 경우 아래 명령에 `--require-same-git`을 붙이고 두 Git 핀을 같은 값으로 설정한다. 과거의 서로 다른 커밋을 비교한 진단은 참고 자료로만 둔다. phase 0과 phase 48의 `row-diff.json`·원본 SHA·artifact ID를 서로 다른 파일로 보존하고 두 단계 중 하나라도 차이가 나면 원인을 분리해 심사한다.

```sh
python3 tools/e2e/compare_campaign_rows.py \
  "$BASELINE_JUNIT_XML" "$CANDIDATE_JUNIT_XML" \
  --baseline-git "$BASELINE_GIT_SHA" --candidate-git "$CANDIDATE_GIT_SHA" \
  --baseline-map-sha "$BASELINE_MAP_SHA256" --candidate-map-sha "$CANDIDATE_MAP_SHA256" \
  --baseline-artifact-id "$BASELINE_ARTIFACT_ID" --candidate-artifact-id "$CANDIDATE_ARTIFACT_ID" \
  --scenario-sha "$SCENARIO_SHA256" --output "$EVIDENCE_DIR/row-diff.json"
```

2026-09-29의 1447 Map4 기준 대 1428 Map4 후보 진단은 1,540→1,523행, 동일 1,409행이었다. 城은 기준 전용 23·후보 전용 4·공통 ID 값 변경 79개여서 완전히 같은 행 기준 대칭 차이가 **102/83 = 23+79 / 4+79**다. 후보 전용 4개는 1621–1624, 기준 전용 23개는 승인된 은퇴 원장과 일치한다. 그 밖에 공성 30→31, 부곡 11→12, 장수·국가·위치·달력 값도 달랐다. 따라서 지도 城 수 또는 기준 SHA만 바꿔 합격 처리하지 않는다. 공통 城 79개와 전쟁·부곡 결과의 원인은 최종 지도 SHA의 W1 3회·W4 사건표/조우별 전투 결과/월단평 실측과 대조해 판정한다.

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
: "${ROW_DIFF_JSON:?pinned W1 diagnostic row diff required}"
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
python3 tools/e2e/collect_yuzhou_evidence.py "$E2E_ARTIFACT_DIR" --row-diff "$ROW_DIFF_JSON"
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

실행 전 `docker-compose.yml`의 공개 URL 변수 이름과 현재 빌드 인자를 다시 대조한다. 스크립트는 합성 QA 세계에서만 가입·장수 생성·출사·발령·행군·NPC 공성/점령·월 정산을 진행한다. 사람 장수의 `killturn`을 96으로 올리기 위한 유일한 사후 DB write는 엔진 정지 중 이루어지고, 재시작 뒤 보존을 확인한다. 이 격리 write를 운영 DB에 적용하지 않는다. 포위 12–24순 목표와 월단평 편향은 사건표를 읽어 별도 판정한다. 실스택 E2E는 조우 >0, 중립 재점령 0, 수비대가 남은 점령 縣의 중립화 0, 첫 월 뒤 적대 30관계, 월단평 READY, 엔진 로그 `tick failed` 0을 단언한다. **이 단언들만으로 전투 결과 관문은 통과하지 않는다.**

### W4 전투 결과 관문

최종 map4 전장과 이미지 SHA에서 실제 봉인·해결된 조우를 `encounterId`별로 한 줄씩 수집한다. 조우 진입 횟수, 해결·준비 불가/해산 횟수, `outcome`별 분포(`ATTACKER_VICTORY`/`DEFENDER_VICTORY`), `winners`가 빈 결과와 있는 결과, 지휘관 `HOLDING`/`RETREATED`/`DESTROYED` 분포, `barrier=ROUND_LIMIT`, `rounds=24`, `WarOutcomeListener.onEncounterResolved` 호출 여부를 **별도** 표에 낸다. 지도 전장 타일 해시·가로/세로 칸 수, 양측 시작 좌표/거리, 이동량을 함께 기록해 전장 크기·속도·라운드 제한의 영향을 볼 수 있게 한다. 함락된 각 포위의 `turns`와 12–24순 목표 대비 분포도 같은 최종 SHA에서 보고한다.

`EncounterResolution`은 24라운드 상한 뒤 양측이 `RETREATED`해 `winners=[]`여도 `outcome=DEFENDER_VICTORY`로 분류할 수 있다. 이 경우 `EncounterResolver`는 승자 callback을 호출하지 않는다. 따라서 `outcome` 이름만으로 승자가 있었다고 세거나, `WarOutcomeListener` 계수 0을 조우 0으로 해석하지 않는다. L7 통제 실험에서는 1447 world/fixture에서도 Map4 격자만 사용하면 24라운드 양측 후퇴·승자 없음·callback 0이 관측됐다. 이는 #995 城 은퇴 효과와 분리된 전장 크기/속도/라운드 계약의 검토 대상이다.

현재 `phase-evidence.json`의 `liveEncounterCount`는 행군 사건의 조우 ID 수이고 `npcBattles`는 최종 장수 meta에 `lastBattle`이 남은 사람 수다. `lastBattle`은 마지막 결과로 덮이므로 **전체 전투 결과 분포를 복원할 수 없다**. 현 수집기는 전투 결과 자료가 없음을 `NOT_COLLECTED`로 표시한다. L1의 계측·슬롯 합의 뒤 격리 시험이나 읽기 전용 관측에서 조우별 원본 결과를 확보해 출처 SHA·전장 핀과 함께 붙이기 전에는 W4 전투 관문을 `BLOCKED`로 둔다. 승자 없는 24라운드 종료가 재현되면 조우 수가 양수여도 밸런스·전장 계약 판정 전에는 통과로 적지 않는다.

### QA 결과 export 소비 계약 초안

`tools/e2e/collect_yuzhou_evidence.py --battle-export <경로>`는 제품 기록 종류가 정해지기 전의 **QA 전용 정규화 JSON**을 검증한다. 예제 `tools/e2e/testdata/encounter-outcomes-draft.json`의 값은 합성 fixture이며 실측이 아니다. `origin`에는 정확한 Git·지도·시나리오 해시와 world ID를 적는다. 출처가 `MEMORY_ONLY`면 발행 경계는 `IN_MEMORY_TEST`, `DB_BACKED`면 `AFTER_SUCCESSFUL_FLUSH`로 구분한다. `sealedEncounterIds`는 DB `log_entry`의 `march.corps` 중 `refs.stop=ENCOUNTER`인 행에 기록된 조우 ID 집합과 일치해야 한다. 일반 행군 기록에는 조우 ID가 없으므로 그 행은 집합에 넣지 않는다. 조우 행에 ID가 없거나 이 집합의 크기가 `phase-evidence.liveEncounterCount`와 다르면 거절한다. 해결 `rows`와 `disbandedEncounterIds`·`unresolvedEncounterIds`는 이 집합을 빠짐없이 분할해야 하며 `(worldId, encounterId)` 중복을 거절한다.

L1과 맞춘 한 행의 DTO 필드는 `worldId`, `encounterId`, `resolvedYear/Month/Phase`, `provinceId`, `approachProvinceId`, `worldMapVariant`, `topologyRevision/Hash`, `tilesContentHash`, `deploymentRuleVersion/layoutRuleVersion/geometryRuleVersion/resolutionRuleVersion`, `initialSeparationSteps`, `outcome`, 정렬된 `winners`/`statuses`, `barrier`, `rounds`, `replayHash`, `callbackInvoked`다. 전장 가로·세로 칸 수와 이동 속도는 DTO 값으로 가장하지 않고, `tilesContentHash`·규칙 버전이 가리키는 별도 고정 산출물과 대조한다.

현재 #1029의 `callbackInvoked`는 실제 listener 호출을 직접 관측한 값이 아니라 `winners` 유무로 산출한다. 수집기는 둘의 일관성만 검사하고 `callback_count`를 **보고된 값의 합계**로 둔다. manifest의 `callback_evidence_status=UNVERIFIED_PRODUCER`와 `CONTRACT_DRAFT`는 이 수치가 실제 호출 증거가 아님을 나타낸다. L1이 실제 호출 뒤 값을 채우는 변경을 병합하고 그 정확한 제품 SHA에서 DB 경계 export를 재수집하기 전에는 W4 callback 관문을 통과시키지 않는다.

`provinceId`와 `approachProvinceId`는 `land:`를 붙인 경로 키가 아니라 `LandProvince.id` 원문이다. `worldMapVariant`는 `WorldMapVariant.name` 또는 `null`이며, 합성 fixture의 `V3_1447_MAP4`는 예시일 뿐 최종 지도 판정값을 고정하지 않는다.

`initialSeparationSteps`는 봉인된 `EncounterDeployment.tokens`의 실제 좌표를 가진 공격 측과 수비 측 토큰 집합 사이, 선택된 `BattlefieldLayout`의 육상 통과 가능 칸에서 4방향 BFS로 구한 최소 이동 수다. 점유·충돌·사거리·속도는 제외하고, 양측 배치가 없으면 `null`이다. 수집기는 이 값을 재계산하지 않고 L1 관측값과 전장 핀을 그대로 묶는다.

전투 결과 observer는 성공한 `flushWithGeneration` 또는 `retryRetainedFlush` 뒤에만 export를 공개하고, 실패 flush에서는 행을 내지 않는다. DB commit 뒤 export 전 크래시로 행이 사라져도 위 독립 DB 조우 ID 대조가 실패해야 한다. 현재 이 producer 연결은 없고, consumer가 유효한 초안 JSON을 읽더라도 상태는 `CONTRACT_DRAFT`다. `MEMORY_ONLY`는 최종 W4 DB 영속 근거가 아니다. L1의 immutable DTO/게임 로그 `kind+refs+facts`와 공개 범위가 확정되면 매핑과 합격 조건을 함께 갱신한다. 그 전에는 제품 호출 파일을 수정하지 않는다.

프로그램으로 구성한 DB harness는 `runTick` 정상 반환 또는 retained retry true 뒤 pending batch를 게시할 수 있다. `runTick`은 flush 뒤 후속 단계에서도 예외가 날 수 있으므로 그 batch를 다음 tick과 섞어 게시하지 않고 격리한다. 기존 Docker 브라우저 W4는 `DaemonLoopConfig`가 `AssignmentMarchTurn`을 직접 구성하므로 observer 주입 경로가 아직 없다. B2가 소유한 해당 파일은 main 착지 전 편집하지 않으며, 격리 브라우저 스택의 producer 연결은 별도 작업으로 남는다.

producer가 격리 스택 산출물에 `battle-outcomes.json`을 내기 시작한 뒤에는 다음과 같이 원본 Playwright 첨부와 함께 읽는다. 현재는 해당 파일이 생성되지 않으므로 이 명령을 W4 완료 기록으로 실행하지 않는다.

```sh
python3 tools/e2e/collect_yuzhou_evidence.py "$E2E_ARTIFACT_DIR" \
  --battle-export "$E2E_ARTIFACT_DIR/battle-outcomes.json"
```

## 4. 산출물과 판정표

`E2E_ARTIFACT_DIR` 아래 `playwright-results.json`은 원본이다. 수집기는 건너뛴/재시도한/실패한 Playwright 결과와 화면·API·DB 첨부 누락을 거절하고, 이미 있는 다른 바이트의 파일을 덮어쓰지 않는다. `--check-only`는 출력 없이 형식만 확인한다. 산출물은 개인 계정·장수 데이터와 게임 응답을 포함할 수 있으므로 PR에 원문을 넣지 않고 검토 가능한 보안 저장 위치와 요약·해시만 보고한다.

행 비교를 함께 보관할 때는 위의 `row-diff.json`을 `--row-diff "$EVIDENCE_DIR/row-diff.json"`으로 수집기에 넘긴다. 수집기는 핀·행 수·城 대칭 차이의 일관성을 확인해 manifest에 구조/값/캠페인 결과를 나눠 싣는다. 행이 같아도 `NO_ROW_DIFF`는 W1·W4 합격 마커가 아니며, 차이가 있으면 `REVIEW_REQUIRED`, 미수집이면 `NOT_COLLECTED`다.

| 파일 | 내용·판정 |
|---|---|
| `playwright.log`, `playwright-results.json` | 단일 흐름 1/1, skip 0, 191-01 월 경계 도달과 소요 시간 |
| `attachments/screen-{court,hand,orders,posts,retinue,siege,supply,war-room,yuedan}.png` | 실제 데이터 로딩 뒤 9화면을 사람이 열어 확인 |
| `attachments/api-<screen>-<index>.json` | 각 화면의 200 응답. 현 스펙은 13개 경로이며 이전 1224 城 실행은 12개였다. 이름·개수는 결과에서 재측정 |
| `attachments/db-hwiha-slice.json` | 포위 상태/지속 순, 사람 발령·위치, 창고, 월 징세·녹봉·월단평 도장/순위 |
| `attachments/phase-events.json`, `phase-event-counts.tsv` | `year,month,phase,event_kind,count` 순별 사건표. 조우·포위·점령·징세·녹봉 실측 |
| `attachments/phase-evidence.json` | 행군 진척, 포위·NPC 전투, 조우, 중립 재점령, 적대 기간, 월단평 |
| `yuzhou-evidence-manifest.json` | 원본 결과와 추출 첨부의 SHA256, 화면/API/사건 수. 내부 계정 정보가 섞인 `phase_evidence`는 공개 PR 첨부에서 제외 |
| `row-diff.json` / manifest `row_diff_gate` | 기준·후보 Git/지도/시나리오 핀, 정규화 행 SHA/종류별 수, 城 구조 23/4와 공통 값 79의 구분, 공성·부곡 등 결과 변화. 진단이며 단독 합격 불가 |
| 별도 조우별 전투 결과표 | `encounterId`, 봉인·해결 순, 전장 해시/크기·시작 거리, 결과·승자/무승자·지휘관 상태·라운드/장벽·callback. 현재 수집 불가이면 `BLOCKED` |
| `--battle-export` QA JSON | 위 초안 계약 검증과 결과 분포 요약. 현재는 `CONTRACT_DRAFT`; 제품/DB 결과의 최종 합격 판정 아님 |
| `docker-compose-build-*.log`, `health-*`, `cleanup-resources.txt` | 이미지 순차 빌드, 서비스 상태, 격리 볼륨·컨테이너 정리 |
| `container-image-ids.tsv` | 살아 있는 격리 컨테이너의 정확한 이미지 ID. 별도 터미널에서 수집 |

현 Playwright 화면별 API 수집 경로는 `web/game/e2e/yuzhou-live.spec.ts`의 `paths`가 정본이다. 현재는 court=`map/preview`,`commands/dispatches`; hand=`commands/stratagem-hand`; orders=`retinue`,`warehouses`; posts=`posts`; retinue=`retinue`; siege=`sieges`; supply=`warehouses`; war-room=`visibility`,`corps`,`sieges`; yuedan=`yuedan`이다. 모두 게임 웹의 `/api/game/api/` 경유이며 장수별 경로는 `generalId`를 붙인다. 경로를 바꾸면 수집 결과의 화면별 API 집합을 다시 검토한다.

최종 보고 표에는 Git SHA, 지도·시나리오 해시, 실제 사용 외부 시나리오 경로, 이미지 ID/digest, W0–W3 결과, W4 사건 수와 첫 발생 순, 화면 9종 확인, `tick failed=0`, 미검증 항목을 각각 기입한다. 엔진 컨테이너 이미지 ID는 스택이 살아 있는 동안 `compose-project-name.txt`와 생성된 Compose override를 이용해 `docker inspect`로 기록해야 한다. 종료 뒤 격리 이미지 별칭은 삭제되므로 빌드 로그만으로 digest를 추정하지 않는다. 어떤 관문도 실패하거나 미확인이면 전환 런북에는 `BLOCKED`로 적는다.
