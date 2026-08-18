# BATTLE-F0 선행 증거 게이트 (OPENSAM-156)

Scope: 2026-07-30 v2 realtime battle foundation 계획 Task 0의 선행조건 4축을 main 코드베이스에서 실측한 증거 기록. 코드 변경 없음.
Verdict: fix-required

기준 커밋: `e203e31e` (main). 조사일 2026-08-18. 선행 티켓 처분은 2026-08-18 Jira 실조회로 갱신(아래 표 각주). 계획 원문:
`docs/superpowers/plans/2026-07-30-v2-realtime-battle-foundation-implementation-plan.md`.

**결론 먼저 — Task 1 착수 불가.** 계획이 Task 0에서 Verify 하라고 지목한 `campaign/*.kt` 소스와
테스트 3종이 리포에 **하나도 없다**. 축 1~3은 코드가 아니라 스펙 문장으로만 존재하고, 축 4는
절반만 충족된다. 이 티켓의 비범위 규정대로, 누락분을 이 자리에서 즉석 구현하지 않고 선행
티켓으로 되돌린다.

## 축 1 — battle-lockable V2 campaign entity 의 영속 단조 revision

**부재.**

- V2 제품 스키마는 테이블 1개뿐이고 revision 컬럼이 없다 —
  `infra/src/main/resources/db/migration_v2/V901__v2_city_ledger.sql:9-16`
  (`world_id, city_id, gold, rice, garrison`).
- 메모리 보유자에도 개념이 없다 — `app/game-engine/src/main/kotlin/opensamguk/engine/v2/V2CityLedgerStore.kt:9`
  (`V2CityLedgerEntry(gold, rice, garrison)`), 델타는 절대값 UPSERT 4필드(`:89-96`).
- `logic/.../logic/v2`, `app/game-engine/.../engine/v2`, `db/migration_v2` 전체에서 `revision`
  **0건**(재확인: 같은 grep 을 직접 다시 돌렸다).
- 존재하는 것은 v1 **월드 전역** 카운터뿐 — `infra/src/main/resources/db/migration/V33__world_version_writer_fence.sql:2-7`
  (`world_state.world_version` / `writer_epoch`). 스펙은 전역 world version 사용을 명시적으로
  배제한다(`docs/superpowers/specs/2026-07-30-v2-realtime-battle-session-command-replay-design.md:160`).
- `app/game-engine/src/main/kotlin/opensamguk/engine/v2/campaign/` 디렉터리 자체가 없다
  (`ls` → No such file or directory). 리포 전체 `Campaign*.kt` 0건.

## 축 2 — 재시작 전 무손실 rehydrate (V2 campaign battle runtime state)

**부재**(V2 기준). v1 rehydrate 는 별개로 존재하며 아래처럼 정정이 필요하다.

- `lockGeneration` / `lockSetRevision` / deferred sequence 는 **문서에만** 있고 코드 0건 —
  계획 `:264`, 스펙 `:112,128,152,160,173,404,418,451,539,562,566`.
- `CampaignBattleRuntimeState.kt` / `CampaignBattleStateLoader.kt` 부재.
- 실재 로더는 v1 전용 — `app/game-engine/src/main/kotlin/opensamguk/engine/boot/WorldSnapshotLoader.kt`.
  이 파일에 `v2`/`ledger` 문자열 0건이며, V2 원장은 `V2CityLedgerStore.load()` 의 lazy 1회
  SELECT 로만 채워진다(`V2CityLedgerStore.kt:100-115`).

**정정 — `CLAUDE.md`의 OPENSAM-149 ⬜ 표기는 낡았다.** #324 의 bounded restart gate 는 머지됐고
`docs/superpowers/reviews/2026-08-14-opensam-149-closeout-review.md:25` 가 `Verdict: cleared` 다
(`RehydrateLosslessGateIT`·`FullRehydrateTurnGateIT`·`RehydrateRoundTripIT` 실재). 다만 같은 문서
`:18-20` 이 **authoritative closure matrix 의 Q 셀은 여전히 quarantine** 임을 못박는다 — all-channel
lossless 가 아니고, **V2 campaign 은 애초에 범위 밖**이다. 이 커밋에서 CLAUDE.md 문구를 사실대로 고쳤다.

## 축 3 — mutation gate (locked entity → unlocked mutation 또는 durable deferred effect 택일)

**부재.**

- `CampaignMutationGate.kt` 부재. `app/game-engine/src/main/kotlin/opensamguk/engine/v2/` 9개 파일
  전체에서 `lock` **0건**(직접 재확인) — V2 mutation 경로에 잠금 판정이 아예 없다.
- 현재 V2 mutation root(`V2CityTransportHandler`, `V2GarrisonRecruitHandler`, `V2ProcessCityIncome`,
  `V2CityGarrisonAttrition`)는 전부 게이트 없이 `V2CityLedgerStore.adjust()`(`:72-98`)로 수렴한다.
  그 안의 판정은 음수 방지 `coerceAtLeast(0)`(`:83-85`)와 무변경 스킵(`:87`)뿐이다.
- durable deferred-effect 큐/테이블 부재 — `ChangeRecorder` 의 V2 채널은
  `recordCityLedgerV2Upsert` 하나(`V2CityLedgerStore.kt:89`).

## 축 4 — 단일 V2 migration owner + v1 production 부재

**부분 충족.**

충족:
- V2 전용 Flyway location 실재 — `infra/src/main/resources/db/migration_v2/`(README + `V901`).
  README `:6-13` 이 왜 `db/migration/v2/` 하위가 아니라 형제여야 하는지(하위면 v1 이 재귀 스캔해
  적용해버림) 실측으로 고정한다.
- v1 production 에서 배제 — 세 앱 모두 `locations: classpath:db/migration` 단일값
  (`app/game-engine|game-api|gateway-api/src/main/resources/application.yml:12-14`).
- 실측 게이트 존재 — `V2FlywayIsolationIT.kt`, `V2ProductionContextBeanGateIT.kt`,
  `V2MigrationConventionTest.kt`, `infra/src/main/kotlin/opensamguk/infra/v2/V2SandboxGate.kt:20,23`.

미충족:
- **런타임 프로세스가 곧 migration owner다** — 세 앱 전부 `flyway.enabled: true`(위 path:line).
  `spring.flyway.enabled=false` 인 런타임 설정이 리포에 없다.
- 계획이 요구한 one-shot `app:v2-schema-provisioner` 모듈 부재 — `settings.gradle.kts:18-19` 의
  모듈은 `common, logic, infra, app:gateway-api, app:game-api, app:game-engine` 뿐.
- `application-v2-sandbox.yml` 부재(`find app -name 'application-v2*'` 0건).
- v2 compose 스택 부재 — `docker-compose.yml`(서비스 8개)·`docker-compose.production.yml`·
  `.github/workflows/deploy.yml` 에서 `V2_ENABLED`/`SPRING_FLYWAY_LOCATIONS`/v2 서비스 0건.
  OPENSAM-35 DoD ① 미충족(`docs/loops/v2-planning-2026-07-12/TICKETS-issued.md:44`).
- `battle_*` 스키마·역할 분리 부재 — `infra/src/main/resources/db` 전체 `battle_` 0건.

**계획–리포 경로 불일치(기록).** 계획 `:19` 는 기본 location 을 `classpath:db/v2/migration` 으로
적지만 실제 규약은 `classpath:db/migration_v2` 이고, README `:6-13` 은 `db/migration` **하위**
경로가 격리를 정확히 반대로 깬다고 실측으로 못박는다. 계획 문안이 리포 규약과 충돌한다 —
계획을 고치는 것이 맞고, 규약을 계획에 맞추면 안 된다.

## Task 0 이 요구하는 검증 명령과 그 현재 결과

계획 `:270-275` 원문:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :app:game-engine:test \
  --tests '*V2CampaignRevisionCrossCallSiteTest' \
  --tests '*V2CampaignMutationGateArchitectureTest' \
  --tests '*V2CampaignRestartRehydrateIT' --rerun-tasks
```

기대치(`:277`): `BUILD SUCCESSFUL`; mandatory XML 의 `tests>0, failures=0, errors=0, skipped=0`.

**이 3개 테스트 클래스는 리포에 존재하지 않는다**(이름 grep 0건, 직접 재확인). 따라서 이 명령은
`tests>0` 을 만족시킬 수 없다 — **실행하지 않았다.** 없는 테스트를 돌려 "통과"를 만들 방법은 없고,
빈 실행을 증거로 적는 것은 위조다.

## 하드 선행 티켓 대조

| 티켓 | 코드 흔적 | 판정 |
| --- | --- | --- |
| OPENSAM-149 | `RehydrateLosslessGateIT` 외 2종, PR #332/#399 | **v1 한정 충족**, Q 셀 quarantine 잔존, V2 campaign 미포함 |
| OPENSAM-35 | `V2SandboxGate`, `V2FlywayIsolationIT`, `migration_v2/README` | **정당하게 종료됨(완료).** 0A-a~g 충족(PR #370, `e9cc3b31`). production 배포·cutover와 v1↔v2 live smoke는 티켓 코멘트가 명시적으로 범위 밖으로 선언하고 **OPENSAM-177** 로 넘겼다 |
| OPENSAM-43 | 커밋 `90c442cb` 외 | 충족(런타임 계약·Flyway 격리 가드) |
| OPENSAM-44 | 커밋 `b1b94e61` — **docs 전용**, 제품 SQL 0건 | **정당하게 종료됨(완료).** broad T1 일괄 구현은 분해 티켓으로 supersede(티켓 코멘트 2026-08-16). 미구현이 아니라 **소유 이전**이다 |
| OPENSAM-45 | 커밋 `34e42029` | 충족(단 battle 과 무관한 result-push) |
| OPENSAM-46 | 커밋 0건 | 부재 |
| OPENSAM-47 | 커밋 0건, 문서 0건 | 부재 |
| OPENSAM-48 | 커밋 0건 | 부재 |
| OPENSAM-56 | 커밋 0건 | 부재 |

## 스스로 공격해 본 것

- **서브에이전트 보고를 그대로 옮겼나?** 아니다. 부재 주장(campaign 디렉터리, 테스트 3종,
  engine v2 의 `lock`·`revision` grep, 세 앱의 `flyway.enabled: true`, `migration_v2` 내용,
  149 리뷰의 `Verdict: cleared`, 계획 `:270-277` 원문)을 직접 다시 실행해 확인했다.
- **부재를 과장했나?** 축 4 는 절반이 실제로 충족돼 있어 "부분 충족"으로 적었다. 149 도
  "미완"이 아니라 "범위 한정 충족"이다 — CLAUDE.md 쪽 문구를 그 사실에 맞춰 고쳤다.
- **여기서 메우고 싶은 유혹.** 티켓 비범위가 금지한다. revision·gate·rehydrate 를 배틀 브랜치에서
  기회주의적으로 구현하면 선행 티켓의 독립 검토를 건너뛰게 된다.

## 남긴 것 / 다음 행동

1. OPENSAM-46/47/48/56 — 코드 흔적 0이고 상태도 `할 일`이다. 선행으로 먼저 닫아야 한다.
2. v2 compose 스택 / v1↔v2 live smoke — **OPENSAM-177** 소유(35 가 명시적으로 넘긴 범위).
3. 계획 `:19` 의 `classpath:db/v2/migration` 표기 정정(리포 규약은 `db/migration_v2`).
4. 런타임 non-owner(`flyway.enabled=false`) + one-shot provisioner 모듈 — 축 4 잔여. 현재
   **소유 티켓이 지정돼 있지 않다**(35 는 0A-a~g 로 닫혔고 46/47/48/56 범위도 아니다).
   F1 착수 전에 소유자를 정해야 한다.

**44·35 를 다시 열지 마라.** 둘은 각자 supersede·범위 경계를 코멘트로 남기고 정당하게 닫혔다.
F0 이 발견한 것은 "닫힌 티켓이 거짓"이 아니라 **닫힌 범위 밖에 남은 잔여의 소유자가 비어 있다**는
것이다. 잔여를 이유로 완료 티켓을 되돌리면 그 티켓들의 독립 검토 기록까지 흐려진다.

위 항목이 닫히기 전에는 BATTLE-F1(OPENSAM-157) 이후를 시작하지 않는다.
