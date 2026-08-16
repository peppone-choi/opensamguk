# OPENSAM-150 (R1) 구현 기록 · T2 대조 · 미해결 블로커

- 브랜치: `op-150-v2-city-ledger-r1` (base `origin/main` @ `d63f6fec`)
- 실측 노트: `u9-u10-u12-measurement.md` (같은 디렉터리)
- 규율: push/PR/merge/deploy 없음. 로컬 커밋까지.

## 1. 실제 변경 파일 ↔ T2 표 대조

| T2 # | 티켓이 허용한 파일 | 실제 | 비고 |
|---|---|---|---|
| 1 | `engine/turn/DirtyState.kt` | **수정** (+9, 삭제 0) | `CityLedgerV2Upsert` 행 모델 추가만 |
| 2 | `engine/turn/ChangeRecorder.kt` | **수정** (+24, 삭제 0) | 필드·`isDirty`·record·accessor·clear 5지점 |
| 3 | `engine/flush/DatabaseHooks.kt` | **수정** (+3, 삭제 0) | import 1줄 + 매핑 1줄(+주석) |
| 4 | `infra/persistence/JdbcFlushExecutor.kt` | **수정** (+50, 삭제 0) | payload 후행 필드 + step 분기 + `cityLedgerV2UpsertMany` + row 타입 |
| 5 | `engine/config/DaemonLoopConfig.kt` (R2와 공유) | **미수정** | R1은 이 파일이 필요 없다. 초과가 아니라 미사용 — 블로커 B1과 함께 R2로 넘긴다 |
| 11 | 0A-c location 마이그레이션 1개 | **신규** `infra/src/main/resources/db/migration_v2/V901__v2_city_ledger.sql` | `infra/src/main/resources/db/migration/`에 두지 않음 |
| — | `engine.v2` 신규 파일 | **신규** `engine/v2/V2CityLedgerStore.kt` | store + 판정(0 하한) + lazy 적재 |
| — | v2 flush IT | **신규** `app/game-engine/src/test/kotlin/opensamguk/engine/v2/V2CityLedgerFlushIT.kt` | 아래 §3 참조 |

**T2 초과 0건.** 수정된 4개 파일의 diff는 **전부 순수 추가**(86 insertions, 0 deletions)다 —
기존 줄을 한 줄도 바꾸지 않았으므로 v1 경로의 실행 형태가 구조적으로 불변이다.

가드 제약 확인:
- `historyRows` 이름·본문 무편집 — `git diff origin/main -- JdbcFlushExecutor.kt | grep -c historyRows` = **0**.
- v2 타입명이 `Repository`/`Reader`로 끝나지 않음 — `V2CityLedgerStore` / `V2CityLedgerEntry`.
- `DaemonLoopConfig`의 `jdbc` 파라미터에 메서드 호출 추가 없음 (파일 자체를 열지 않았다).
- `ChangeRecorder.kt`에 `*Repository`/`*Reader` 확장 함수 선언 없음.
- `HotColdWorldCatalogGuardTest` 10/0/0 green.

## 2. 설계안·티켓과의 의도적 차이 3건

### D1 — 타입 이름에서 `V2` 접두사를 뺐다 (`CityLedgerV2Upsert(Row)`)

티켓은 `V2CityLedgerUpsert` 류를 암시하나, `V2NamingConventionGuardTest`
(`app/game-engine/src/test/kotlin/opensamguk/engine/v2/V2NamingConventionGuardTest.kt:25`)가
`class|object|interface V2[A-Z]\w*` 선언을 **`opensamguk.*.v2.*` 패키지 밖에서 전부 거부**한다.
행 모델은 `engine.turn`·`infra.persistence`에 있어야 하므로(T2 표가 그 두 파일을 지정) 접두사를 뺐다.
**실측: 접두사를 유지한 첫 시도에서 이 가드가 실제로 FAILED 했다** — 추정이 아니다.
`engine.v2` 안의 타입(`V2CityLedgerStore`, `V2CityLedgerEntry`)은 규약대로 `V2` 접두사를 유지한다.

### D2 — `world_id`를 `integer`로 (설계안 §2.1 스케치는 `bigint`)

근거는 실측 노트 U12 §"의도적 차이"에 path:line으로 적었다(`world_state.id`가 serial, `WorldId.value`가 Int,
0A-c probe가 integer). `gold`/`rice`는 스케치대로 `bigint`.

### D3 — flush IT를 infra가 아니라 engine 테스트에 두었다

DoD는 "infra flush IT"라고 적었으나 증명 대상 체인의 앞 두 마디(`ChangeRecorder`·`V2CityLedgerStore`)가
`:app:game-engine` 소속이고 `:infra`는 엔진에 의존하지 않는다. 엔진 쪽에 두면 `JdbcFlushExecutor`를
그대로 쓰면서 채널 전체를 증명할 수 있고 반대는 불가능하다. 증명 항목(멱등 UPSERT · v1 델타와 같은
트랜잭션 · 빈 컬렉션 미진입)은 DoD와 동일하다.

## 3. `V2CityLedgerFlushIT` 6케이스 (6/0/0 green, Testcontainers postgres:16-alpine)

Flyway location은 v2 스택 운영값(`classpath:db/migration,classpath:db/migration_v2`)과 동일하다.

1. `store.adjust` 델타 → recorder → flush → `v2_city_ledger` 절대값 영속 + 같은 flush에서 v1 `world_state`도 반영
2. 같은 payload 재적용 멱등 — 500이 1000이 되지 않고 행도 1개
3. lazy 적재 — 행 INSERT 후 생성한 store가 첫 접근에 그 값을 읽는다 (U10)
4. 0 하한 판정 — 음수 델타는 0에서 멈춘다
5. **같은 트랜잭션 증명** — v2 step에서 NOT NULL 위반을 강제하면 이미 실행된 v1 `world_state` UPDATE까지
   롤백된다 (`JdbcFlushExecutor.kt:47-48`의 단일 `TransactionTemplate`)
6. 빈 컬렉션 가드 — v2 델타가 없으면 `lastOps`에 `v2_city_ledger` op가 없다 (v1 경로 SQL 0)

## 4. 미해결 블로커

### B1 (BLOCKER) — v2 store의 `@Bean` 등록이 게이트 ②와 충돌한다

티켓 산출물의 `engine.v2` **신규 `@Configuration`**을 넣지 못했다.

- 0A-b 게이트(`@Profile(V2SandboxGate.PROFILE)` + `@ConditionalOnProperty`)를 그대로 쓴 신규
  `@Configuration`을 추가하면 `V2BothConditionsBeanGateIT`
  (`app/game-engine/src/test/kotlin/opensamguk/engine/v2/V2ProductionContextBeanGateIT.kt:186-190`)가
  **모든** `opensamguk.*.v2.*` 빈 이름 집합을
  `setOf("v2SandboxConfiguration","v2SandboxMarker","v2ContentCatalog","v2CityCatalogAdapter")`와
  `assertEquals` 하므로 실패한다. 그 단언은 스스로 "새 v2 빈이 타입 목록에 없다는 이유로 게이트를
  우회하지 못하게" 하려고 만든 것이라, **v2 빈을 추가하는 티켓이 기대 집합을 넓히는 것이 원래 설계**로 보인다.
- 그러나 그 파일은 `app/game-engine/src/test/kotlin/` 소속이고 **R1 게이트 ②가 수정을 금지**한다.
- 우회로는 전부 기각했다: (i) `V2SandboxConfiguration.kt` 수정 → 게이트 ③ T2 초과,
  (ii) 빈 타입을 v2 패키지 밖으로 빼기 → `V2NamingConventionGuardTest`가 막는 규약을 이름 장난으로
  우회하는 것이라 설계 위반.

**필요한 결정(사람):** (a) `V2BothConditionsBeanGateIT:188`의 기대 집합 확장을 R1 범위의 T1 예외로 승인,
또는 (b) OPENSAM-35 후속 티켓으로 그 단언을 allowlist/superset 형태로 바꾼 뒤 R1이 소비.
**어느 쪽이든 T2 파일 수는 늘지 않는다** (신규 `@Configuration` 1개 + 테스트 1줄).

그때까지 `V2CityLedgerStore`는 빈이 아니고 직접 생성해서 쓴다 — IT가 그 경로를 증명한다.
R2의 `DaemonLoopConfig` `ObjectProvider<V2CityLedgerStore>` 배선(T2 5행)은 B1이 닫혀야 성립한다.

### B2 (범위 밖, 기록만) — v2 원장 초기 적재 경로 없음

R1은 빈 원장에서 출발한다. 도시별 시작 금·병량·도시병사를 v2 시나리오 시드에서 넣을지, R2 첫 정산이
채울지는 R2 소관이다. R1의 어떤 판정도 이 값에 의존하지 않는다.

## 5. UNKNOWN (추측하지 않음)

- Kotlin sealed 서브클래스를 **다른 모듈**에 두었을 때의 성립 여부 — 미실측 (U9 §증명하지 못하는 것).
- v2 프로세스를 실제 부팅했을 때 `ScenarioSeedRunner`가 v2 DB에 심는 시점 — 미실측. R1 산출물이 이
  순서에 의존하지 않도록 lazy 적재로 설계했으므로 결론의 전제가 아니다.
- 도시병사 훈련·사기·3개월 병량 유지비 — 설계안 §2.1이 명시적으로 오픈 후로 보낸 항목. 미구현이 정상.

## 6. 독립 적대적 리뷰 결과 (2026-08-16, 별도 레인)

정본: `docs/superpowers/reviews/2026-08-16-opensam-150-v2-city-ledger-review.md` (`Verdict: cleared`).
위 §1~§5 주장은 전부 독립 재현됐고(이탈 ①은 임시 `V2*` 선언 mutation으로 가드 FAILED 실증, 이탈 ②는
`V1__baseline.sql:11` serial + `WorldId.kt:17` Int 확인), B1 분석은 정확하다 — 오히려 막는 단언이
`V2ProductionContextBeanGateIT.kt:186-190` 하나가 아니라 같은 파일 `:64` `assertNoV2Beans()`까지 **둘**이다.

리뷰가 브랜치에서 직접 닫은 결함:
- **D-1** `entries()`가 신규 도시 append 때문에 `city_id ASC`를 잃었다 → `toSortedMap()` + IT 회귀 케이스
  (mutation으로 테스트 유효성 확인). 설계안 §8 R3 공백지화 순회가 이 순서에 의존한다.
- **D-2** `engine/v2`가 `HotColdCatalog.runtimeSourceDirectories`·`runtimeDirectSqlBoundaries` 양쪽 밖이라
  store의 `jdbc.query`가 무-카탈로그 런타임 읽기다(`Store` 접미사 선택이 수신자-이름 탐지를 회피한 결과).
  보완 통제로 `V2CityLedgerReadBoundGuardTest` 신설. **항구적 해법인 카탈로그 등재는 R2 선행 조건** —
  `HotColdCatalog.kt`가 T1 동결 영역이라 R1 범위에서 닫을 수 없다.
- **D-3** B1 참조에 파일 경로 추가.

**M1 정정 (문서 drift, 고칠 수 없음).** `infra/src/main/resources/db/migration_v2/README.md` §5의
"production `db/migration_v2/`에는 아직 SQL이 없다"는 이 브랜치 이후 **거짓**이다 — `V901__v2_city_ledger.sql`이 있다.
게이트 ⑤가 `infra/src/main/resources/` 전체에 `--diff-filter=MD`를 걸어 그 README 수정을 금지하므로
고치지 않았다. 게이트 pathspec을 설정 파일로 좁히는 것은 OPENSAM-35 후속 결정 사항이며, 그때까지의
정정 사실은 이 문단과 리뷰 문서가 보유한다.
