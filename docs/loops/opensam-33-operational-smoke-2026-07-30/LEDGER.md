# OPENSAM-33 B2 운영 스모크 루프 원장

| 바퀴 | 가설 | 점수 전→후 | 채점자 | 판정 | 원인 한 줄 |
|---|---|---|---|---|---|
| 0 | 현재 repository가 D4-14~17을 이미 만족한다 | 미측정→backend 22/22 + shell contract PASS, live correlated smoke 부재 | frozen backend + live stack graders | 기각 | D4-17과 개별 D4-15 seam은 fresh green이지만 60초 browser-facing correlated smoke는 없다 |
| 1a | QA-only turnterm을 seed 전에 주입하면 1분 cadence와 모든 장수 jitter가 일치한다 | compile RED→ScenarioMapSeedIT 8/8 green | QA cadence seed IT | 채택 | `SCENARIO_QA_TURNTERM=1`이 importer 생성 전에 적용되고 local compose에만 노출된다 |

## 0바퀴 계약

- production/default cadence는 변경하지 않는다.
- 기존 isolated backend tests와 live E2E contract를 먼저 재측정한다.
- 실제 gap이 확인되기 전 production code를 추측 수정하지 않는다.
- live harness가 secret을 요구하면 local ephemeral 값을 생성해 isolated
  Compose에만 주입하고 값 자체는 artifact/log에 남기지 않는다.

## 0바퀴 source 관측

- D4-14: 60초 reduced-cadence live harness 없음.
- D4-15: `RedisCommandStream`, `TurnRunService`, `RealtimePublisher`,
  `RealtimeRelayController`, `useSSE` seam과 개별 tests는 존재한다.
- D4-16: `CommandModal` terminal polling과 일부 no-op closure unit tests는
  있으나 browser runtime false-deny/state-delta/stale-refresh 기준은 없다.
- D4-17: `ScenarioSeedCoordinator` transaction과
  `ScenarioImporterIT` mid-import rollback→retry 회귀가 존재한다.
- 반복 Fablize generic failure notice는 exit 0 read 명령에도 발생하는
  `.ai/known-issues.md` baseline이며 제품 판정과 분리한다.
- compose mapping discovery의 focused `rg`가 committed `.env.example`을
  포함한 절차 위반은 `.ai/known-issues.md`에 격리했다. 실제 `.env`/user
  secret/production credential은 읽거나 변경하지 않았고 이후 `.env*`는
  모든 discovery에서 명시 제외한다.

## 0바퀴 fresh 관측

- `ScenarioImporterIT`: 14 tests / failures·errors·skipped 0, fresh XML
  timestamp `2026-07-30T15:25:35Z`.
- `RedisCommandStreamIT`: 3 tests / failures·errors·skipped 0, timestamp
  `2026-07-30T15:27:49Z`.
- `IntakeResultChannelTest`: 4 tests / failures·errors·skipped 0, timestamp
  `2026-07-30T15:27:33Z`.
- `RealtimeRelayIT`: 1 test / failures·errors·skipped 0, timestamp
  `2026-07-30T15:27:40Z`.
- `bash tools/e2e/local_v1_gate_timeout_contract_test.sh`: PASS.
- 현재 host의 Gradle context wrapper는 fresh XML을 남긴 뒤 최종
  `BUILD SUCCESSFUL` tail/신뢰 가능한 exit를 호출자에게 돌려주지 않았다.
  XML freshness와 counts로 기능 결과를 판정하되, completion gate에서는
  direct command tail을 다시 확보한다.
- 결론: D4-17은 fresh green. D4-15 seam은 개별 green이지만 D4-14/16과
  한 live artifact chain이 없어 가설을 기각한다.

## 1바퀴 계약

- 단일 가설: isolated local Compose가 seed 전에 QA turnterm=1을 적용하고,
  같은 browser run이 `che_요양` request를 reservation→execution까지 추적하면
  60초 cadence와 no-op/false-deny/stale UI를 객관적으로 탐지할 수 있다.
- 새 장수 personality는 `che_유지`로 고정하고 expected delta는
  experience `+10`, dedication `+7`이다. 첫 tick race와 strict due 비교를
  고려해 execution은 최대 두 boundary까지 기다린다.
- backend seed override와 operational smoke harness를 disjoint lane으로
  구현한다.
- 채택 기준은 `GOLDENSET.md`의 D4-14~17 전 항목이며, arbitrary
  catalog/휴식 fallback과 admin post-seed cadence patch는 금지한다.

## 1a 관측 — QA pre-seed cadence

- RED: `qaTurnTerm` named parameter가 아직 없어
  `:app:game-engine:compileTestKotlin`이 실패했다.
- GREEN: `SeedBootstrap`이 optional `SCENARIO_QA_TURNTERM`을 받으며 absent/
  empty는 기존 60, present는 정확히 `1`만 허용한다.
- override는 `ScenarioImporter(turnTerm=...)` 생성 전에 전달돼
  `tick_seconds`, config/game_env/ng_games와 모든 general `turn_time` jitter
  range에 함께 적용된다.
- `docker-compose.yml` local service만 변수를 전달하며
  `docker-compose.production.yml`은 변경하지 않았다.
- Fresh `ScenarioMapSeedIT`: `BUILD SUCCESSFUL in 1m 23s`,
  8 tests / failures·errors·skipped 0.
- production data shape와 default 60분, RNG draw 순서는 변경하지 않았다.

## Tooling notes during live run

- Optional `docker buildx history ls --format` progress probe used unsupported
  template fields and exited with a parse error. It did not mutate the isolated
  stack or files, was discarded, and is not product evidence. The live gate
  process itself remained active.
- Isolated gateway preflight reproduced the initial startup failure using only
  an intentionally malformed synthetic test value: Spring
  `UnsatisfiedDependencyException` for `jwtTokenProvider`, caused by JJWT
  `DecodingException: Illegal base64 character: '-'` at
  `JwtTokenProvider.kt:30`. A separate isolated run with fixed non-secret
  32-byte Base64 test material returned `/actuator/health` `UP`. This was
  invalid test input, not a product regression; both diagnostic stacks were
  brought down and no real environment or credential was read.
- Full corrected-key operational run reached the browser smoke but failed its
  intentional cadence guard with daemon `tickSeconds=3600` instead of `60`
  before reservation submission. Cause: the gate contract used a variable name
  not consumed by the local Compose/seed implementation; the canonical
  `SCENARIO_QA_TURNTERM=1` contract is now required without aliasing. This is
  an integration failure, not a product cadence result; the isolated stack was
  cleaned down before the correction.

## 1b~1d 관측 — harness hardening과 final live GREEN

- `E2E_SKIP_BUILD=true` lane은 now opt-in
  `E2E_PREBUILT_IMAGE_PREFIX=<prefix>`의 five-service image source를 새
  Compose project tag로 alias한다. default `opensamguk` source, custom source,
  invalid prefix, missing source, existing target은 shell contract로 각각
  PASS/fail-closed를 확인했다.
- First prebuilt browser attempt는 generated `op${Date.now()}${random}` name이
  max 19 ASCII가 될 수 있어 JoinController width-18 guard에서 200 BLOCKED를
  반환했다. Generator를 exactly 16 ASCII로 제한하고 ASCII/length assertion 및
  status/reason/requestId-only intake artifact를 추가했다.
- Second attempt는 reservation/execution/delta/three ticks까지 통과했지만
  browser navigation이 in-memory test EventSource evidence를 지웠다. Probe를
  test-only same-origin sessionStorage key로 hydrate/update하고 navigation
  context-destroyed error만 bounded retry했다.
- Third attempt는 persisted evidence가 pre-submit event와 post-turn
  front-info fetch를 보였다. Separate manual EventSource가 navigation에서
  닫힌 것이 원인이므로 제거하고 native EventSource construct-only Proxy에
  `/api/game/sse/turn` listener만 부착했다. Native static/prototype,
  app listener와 close semantics는 보존한다.

### Final command, exit, and artifact

```bash
E2E_SKIP_BUILD=true \
E2E_PREBUILT_IMAGE_PREFIX=v1-e2e-20260730164653-65541 \
OPENSAMGUK_WORLD_ID=1 \
JWT_SECRET='<fixed local 32-byte Base64 test material>' \
E2E_ENABLE_AUTH=true \
E2E_OPERATIONAL_SMOKE=true \
SCENARIO_QA_TURNTERM=1 \
E2E_ARTIFACT_DIR=/tmp/opensamguk-op33-e2e-prebuilt-native-sse.VeG0KU \
tools/e2e/local_v1_gate.sh
```

- Exit `0`: `local v1 gate passed`.
- `docker-image-aliases.txt`: all five source images under the supplied prefix
  tagged into isolated project `v1-e2e-20260731015518-90291`.
- `operational-smoke-correlation.json`: `che_요양` reservationAccepted at
  world version `0`, executionApplied at version `3`, authoritative
  injury/experience/dedication `0/10/7`.
- Three tick snapshots: `successfulTicks` `2 → 3 → 4`, each `tickSeconds=60`
  and exact 60-second last/next boundary; `failedTicks=0`,
  `consecutiveFailures=0` throughout.
- Browser evidence: 10 SSE opens, 8 `turnCompleted` events, 14 browser
  `front-info` fetches, refresh-after-event true, rendered extra values
  `명성=전무 (10)` and `계급=30품관 (7)`.
- Cleanup evidence: the isolated Compose project had no remaining containers.

### Final focused checks

- `pnpm --dir web/game typecheck`: PASS.
- `E2E_COMPOSE_PROJECT_NAME=op33-list pnpm --dir web/game exec playwright test e2e/v1-core-live.spec.ts --list`: PASS, 2 tests discovered.
- `bash -n tools/e2e/local_v1_gate.sh tools/e2e/local_v1_gate_timeout_contract_test.sh`: PASS.
- `bash tools/e2e/local_v1_gate_timeout_contract_test.sh`: PASS.
- Scoped `git diff --check`: PASS.

## 2바퀴 — reviewer `fix-required` 보정과 marker RED→GREEN

### 이전 reviewer 판정 (아직 final cleared 아님)

- 초기 판정은 `fix-required`였다.
- MAJOR-1: 기존 live chain은 DB poll fallback으로도 terminal까지 갈 수 있어,
  Redis wake가 실제로 publish되었다는 증거가 없었다.
- MAJOR-2: cleanup은 이름이 알려진 project volume 세 개와 project image
  alias 다섯 개를 남겼다.
- MINOR: reservation/execution phase 각각의 HTTP `200` 및 original intake
  request ID 동등성을 명시적으로 단언하지 않았고, operational worst-case
  timeout도 너무 짧았다.
- 아래는 그 finding의 보정 evidence다. final reviewer가 반환되기 전에는
  verdict를 `cleared`로 쓰지 않는다.

### RED — production Redis marker bind 결함과 관측기 실패

- Hydration 보정 뒤의 prior isolated run은 intake `202`, matching stream entry,
  command inbox row를 만들었지만 `redis_wake_published_at`가 null이었다.
  Observer의 실패 메시지는 `commandInbox rows=1 published=false streamEntries=1`였다.
- 원인은 production `CommandInboxRepository.markRedisWakePublished`가 raw
  `Instant`를 JDBC parameter로 bind한 것이었다. Spring JDBC/pgjdbc 조합에서
  그 update가 실패했고 `publishBestEffort`가 그 예외를 삼켜, XADD는 성공했지만
  durable marker는 남지 않았다. 이는 observer parser 문제가 아니다.
- 별도 marker-fix lane은 raw `Instant` 대신 `Timestamp.from(publishedAt)`를
  bind하도록 수정했다. 이 원장은 source 변경의 소유자가 아니며, 그 lane의
  focused unit `4/4`와 Testcontainers IT `1/1`, skip `0` 보고를 소비한다.
- Auth RED도 별도였다. 초기 browser attempt는 React hydration 전 input fill이
  reset되어 `/api/auth/register` request가 생기지 않고 client validation으로
  끝났다. 이는 backend auth 거절이 아니며, form-ready/form-fill artifact와
  bounded response wait를 추가한 이유다.
- Observer는 실패 시 request ID, stream key, marker row, matching entry ID와
  payload SHA만 보존하고 raw payload/credential은 보존하지 않는다.

### GREEN — marker-fix source를 사용한 final isolated live gate

- game-api만 direct single-service Docker build로 교체했다.
  Source prefix `op33-remediation-20260731025011-25997`의 game-api tag는
  `sha256:802d40589a93f93550b2707af02ba68debfdb17dfce7aa8a531537e5255292f2`
  에서 `sha256:2f825b993f4324e3de3b53c24fe7f7f1e90959af63df88493bc76253a7da63dc`
  로 바뀌었다. Gradle build tail은 `BUILD SUCCESSFUL in 7m 23s`였고 Docker
  export/name/unpack도 완료했다. 다른 four source IDs는 build/tag 직후와 final
  cleanup 뒤 모두 불변으로 단언했다.
- Final artifact exact path:
  `/var/folders/34/jlnbkc0j6fj0nkcp7fj0f9h00000gn/T/opensamguk-op33-remediation.A4KNsK/live-gate-marker-fixed`.
  Build evidence는 같은 root의 `game-api-build-evidence.txt`, full build tail은
  `game-api-build.log`에 있다. Approved fixed local synthetic JWT material만
  사용했고 값은 기록하지 않았다.
- Gate exit는 `0` (`local v1 gate passed`)였다. Operational Playwright는
  `1 passed`, `1 skipped`, duration `221617ms`, timeout `600000ms`였다.
- Hydration GREEN: register/login form artifact 모두
  `networkIdleSettled=true`; controlled field value matches는 전부 true,
  `refilledAfterHydration=false`; register `200`, login `200`, join intake `202`.
- Same request ID `53dfaac0-a9f2-43ea-85ee-a19d6dc87f69`의 `che_요양` intake는
  `202`였다. Reservation phase는 `200` + same request ID +
  `reservationAccepted`, execution phase는 `200` + same request ID +
  `executionApplied`; committed world version은 `3`이었다.

### Redis ingress / ACK exact evidence (MAJOR-1 closure candidate)

- Final ingress observation at `2026-07-31T06:35:24.452Z`:
  stream `sammo:che:scenario_2:w1:turn-daemon:commands`, entry
  `1785479722611-0`, payload SHA-256
  `325c881d62dad24292d85004fabfd214b1b461929e4c735def364baa574caa11`.
- DB marker query returned `1|t|2026-07-31 15:35:22.612963+09`: exactly one
  matching inbox row and a non-null `redis_wake_published_at`.
- XRANGE matched exactly that entry and payload SHA. XINFO showed group
  `game-engine` and consumer `world-1`; ingress-time group/consumer pending
  counts were both `0` and `lastDeliveredId` equalled the entry ID.
- ACK observation at `2026-07-31T06:36:12.470Z` retained the same entry and
  SHA; XPENDING exact matching entry count was `0`, `entryStillPresent=true`,
  and group `lastDeliveredId` still equalled `1785479722611-0`.
- This proves the submitted request had a durable marker, matching Redis wake,
  group/consumer delivery state, and post-processing ACK; it is not merely DB
  polling fallback evidence.

### Cadence, authoritative state, SSE, timeout, and cleanup

- Initial successful tick count was `1`; three final snapshots were `2`, `3`,
  and `4`, all `tickSeconds=60`, with `failedTicks=0` and
  `consecutiveFailures=0`. Their last/next turn boundaries advanced by 60 seconds.
- Authoritative read moved injury/experience/dedication from `0/0/0` to
  `0/10/7`. Browser evidence observed `turnCompleted`, followed by front-info
  fetch/refresh (`refreshedAfterTurnCompleted=true`) and rendered
  `명성=전무 (10)`, `계급=30품관 (7)`.
- Operational default is now `E2E_PLAYWRIGHT_TIMEOUT_MS=600000`; a caller
  override remains honored. `bash tools/e2e/local_v1_gate_timeout_contract_test.sh`
  passed default, override, operational-default, operational-override,
  source-alias preservation, and failure-cleanup contracts.
- Final cleanup project `v1-e2e-20260731063246-68779` ran compose down with
  volumes successfully. It proved absent:
  `v1-e2e-20260731063246-68779_pgdata`,
  `v1-e2e-20260731063246-68779_redisdata`, and
  `v1-e2e-20260731063246-68779_profile-icons`.
- It removed and then proved absent each exact run alias:
  `v1-e2e-20260731063246-68779-{gateway-api,game-api,game-engine,web-gateway,web-game}:latest`.
  The five prebuilt source tags remained present in their `RepoTags` at their
  expected immutable IDs after cleanup.

### QUESTION — duplicate SSE observation follow-up

- The final artifact recorded nine EventSource-open timestamps and eight
  `turnCompleted` events, more than the isolated cadence ticks. A prior run
  similarly had ten opens/eight events. Whether this is sequential reconnect,
  navigation/dev remount, or concurrent duplicate subscription remains UNKNOWN.
- Current D4-16 stale-UI criterion is still evidenced: an event was followed by
  front-info refresh and the expected rendered EXP/DED values. This evidence
  does not establish that exactly one live SSE subscription exists.
- Follow-up needs connection identity plus active-listener/concurrency telemetry
  to distinguish reconnect from duplicate subscription before making an
  operational-load claim. It is not silently closed by this smoke result.

### Tooling boundary

- Fablize emitted generic tool-failure notices again for successful exit-0
  read-only artifact/document commands. This is the existing external tooling
  baseline, not an opensamguk runtime failure; direct command exits, Playwright
  result JSON, and gate artifacts above are the product evidence. No broad
  retry was used to convert the notice into a product verdict.
