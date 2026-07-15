# v1 안정화 평가와 v2 오픈 전 실행 계획

> 작성일: 2026-07-13
> 상태: active-plan
> 목표: 이번 달 v2 서버 오픈 전에 현재 v1을 운영 가능한 기준선으로 고정하고, v2 구현이 v1 안정성을 깨지 않게 한다.

## 현재 버전 평가

| 축 | 판정 | 근거 | v2 전 차단 기준 |
|---|---|---|---|
| 시나리오 부팅 | 중상 | `scenario_1010` 부팅·tick IT와 `scenario_0` 공백지 seed가 존재한다 | fresh DB에서 seed/load/restart가 반복 녹색이어야 한다 |
| 명령 intake | 중 | 장수 생성, 거병, 임관, 건국, 내정이 데몬 경로로 연결되어 있다 | command result가 flush 이후에만 성공으로 관측되어야 한다 |
| 외교 자동화 | 중 | B1 player-bot의 실제 `che_선전포고`와 24개월 diplomacy 전이는 녹색이다. 종전·불가침·파기 정책은 후속이다 | 봇이 선전포고·종전·불가침·파기를 실제 명령으로 예약하고 상태 변화를 기록해야 한다 |
| 장기 시뮬 | 중상 | Kotlin 실제 런타임 공백지→천통 불변식은 녹색이다. PHP turn-for-turn replay는 별도 blocked gate다 | 공백지 player-bot 천통을 유지하고 PHP replay divergence를 별도로 닫아야 한다 |
| 프론트 운영 | 중 | 주요 메뉴와 read/mutation 표면은 있으나 dead control·stale refresh 가능성이 남아 있다 | 버튼이 보이면 실제 API/daemon 결과가 있어야 한다 |
| 배포·운영 | 중 | Docker/production compose와 s1 운영 경로는 있다 | seed, disk, image tag, runner health를 배포 전 체크리스트로 고정해야 한다 |
| 패러티 회귀 | 중상 | PHP golden과 backend gate가 기준선이다 | v2 작업 전후 `tools/parity/gate.sh backend` 또는 대상 gate를 유지해야 한다 |

## 2026-07-13 실행 결과

| 게이트 | 결과 | 증거 |
|---|---|---|
| B0 공백지 다국가 커맨드 | 녹색 | `ScenarioBlankPlayerCommandIT`: 6개국·60명, 역할별 항목 증분, flush/restart. XML 1 test, failures/errors 0 |
| B0.5 전체 공개 커맨드 계약 | 녹색 | `CommandContractMatrixTest`: 현행 가입 제약을 만족하는 10개 역할별 5능력치 커버리지 프로필과 성공/실패 계약, fresh 886 tests, failures/errors/skips 0. 자기충족형 source-name 검사는 제거 |
| B1 공백지 player-bot 천통 | 녹색 | `ScenarioBlankUnificationIT`: 실제 선전포고·군량매매·징병·훈련·사기진작·이동·출병으로 94개 도시, `isunited=2`, restart 영속 |
| 표적 lifecycle/command 묶음 | 녹색 | 16 suites, 112 tests + `MilitaryConstraintsTest` 15 tests, failures/errors/skips 0; `BUILD SUCCESSFUL in 42m 31s` |
| logic 전체 gate | 녹색 | `CommandContractMatrixTest` 886/886. 새 징병 FULL 제약이 드러낸 모순 성공 픽스처 22건을 수정 후 전체 gate 녹색 |
| engine 전체 gate | 녹색 | `ProductionPipelineIntegrationTest` 3/3 및 최종 backend 전체 XML 481 suites, 4,406 tests, failures/errors 0, skip 1; forced `BUILD SUCCESSFUL in 30m 44s`, canonical gate `BUILD SUCCESSFUL in 15m 52s` |
| agent-system | 재검증 중 | 독립 리뷰 후 strict gate 재실행 예정 |

B1은 PHP long-sim byte parity 완료 선언이 아니다. `scenario_0` Kotlin 런타임의 시작→통일 행동 불변식을 닫은 게이트이고, 기존 `LongSimReplayGateTest`의 PHP 12개월 구조 divergence는 독립 과제로 유지한다.

## 평가 계획

평가는 기능 목록 점검이 아니라 “게임이 시작되고, 유저가 여러 명 들어오고, 여러 국가가 생기고, 커맨드가 누적되어, 끝 상태까지 가는가”를 보는 방식으로 고정한다.

1. **B0 빠른 공백지 커맨드 게이트**
   - Testcontainers Postgres + Flyway fresh DB.
   - `scenario_0` 선택.
   - 플레이어 장수 60명 생성: 국가당 군주, 농지, 상업, 치안, 성벽, 수비, 기술, 전쟁, 징병, 외교 역할.
   - 6개 그룹이 실제 `che_거병`으로 방랑국을 만들고, 나머지는 `che_임관`으로 합류.
   - 다음 달에 `che_건국`으로 6개 정식 국가 생성.
   - 역할별로 `che_농지개간`, `che_상업투자`, `che_치안강화`, `che_성벽보수`, `che_수비강화`, `che_기술연구`, `che_징병`, `che_훈련`을 실행하고 각 항목의 직전/직후 증분을 단언한다.
   - `ChangeRecorder → DatabaseHooks.toFlushPayload → JdbcFlushExecutor`로 flush 후 restart snapshot에서 검증.
   - 구현: `app/game-engine/src/test/kotlin/opensamguk/engine/boot/ScenarioBlankPlayerCommandIT.kt`.

2. **B0.5 전체 커맨드 선택/계약 게이트**
   - 공개 장수 메뉴 `GameConst.availableGeneralCommand`와 수뇌 메뉴 `GameConst.availableChiefCommand`를 전수 평탄화한다.
   - `휴식`은 반드시 `RestAction`으로 해석되어야 한다.
   - 나머지 공개 커맨드는 모두 `CommandRegistry`에서 실제 정의로 해석되어야 하며 `RestAction` fallback으로 떨어지면 실패한다.
   - 공개 커맨드는 모두 `COMMAND_CONTRACTS` 성공/실패 계약 행을 가져야 한다.
   - 군주, 농지, 상업, 치안, 성벽, 수비, 기술, 전쟁, 징병, 외교 역할군이 각각 모든 공개 커맨드를 하나씩 선택하고, 각 커맨드의 valid args가 parse + FULL constraint allow를 통과해야 한다.
   - 이 계층은 역할별 메뉴·파서·제약 계약의 전수 게이트다. 10개 역할이 모든 명령의 부수효과를 실행한다고 주장하지 않으며, 부수효과는 명령별 logic 테스트와 B0/B1 대표 델타로 검증한다.
   - 구현: `logic/src/test/kotlin/opensamguk/logic/actions/CommandContractMatrixTest.kt`.

3. **B1 공백지 player-bot 천하통일 게이트**
   - B0의 세계를 시작점으로 사용한다.
   - 터미널 상태를 강제로 만들지 않는다.
   - 봇은 예약 커맨드와 같은 payload를 실제 `ReservedTurnHandler`/국가 명령 처리기에 직접 넣는 deterministic direct-handler stress gate다.
   - Redis queue, due-turn ring, `TurnRunService` lifecycle은 통과하지 않으며 그 운영 경계는 B2가 담당한다.
   - 기본 루프는 내정 → 징병 → 훈련 → 자동외교 → 인접 공백지 출병 → 점령지 회복 → 타국 전쟁 순서다.
   - B1a 자동외교는 외교 역할 플레이어를 실제 관직에 임명한 뒤 `che_선전포고`를 발령해 양방향 `state=1, term=24`를 만들고, 결정적 월간 하네스 24회 뒤 `state=0`을 확인한다.
   - 월간 하네스는 production `EventDispatcher`, `WorldEventContextFactory`, `MonthlyPostUpdateHook`를 사용하지만 clock/pre-update/statistic은 테스트 대역이며 Redis intake, `TurnRunService`, SSE를 우회한다. 이 영역은 B2가 담당한다.
   - B1b 자동외교는 `che_종전제의`, `che_불가침제의`, `che_불가침파기제의`와 수락 3종의 정책 상태전이를 후속으로 닫는다.
   - 종료 조건은 `level > 0` 국가가 정확히 1개이고 그 국가가 전 도시를 소유해 `checkEmperior`가 `isunited=2`를 만든 상태다.
   - 실패 시 마지막 성공 월, 첫 fallback 명령, 첫 전투·외교 불일치, 도시/국가/장수/diplomacy count를 기록한다.
   - 구현: `app/game-engine/src/test/kotlin/opensamguk/engine/boot/ScenarioBlankUnificationIT.kt`.

4. **B2 운영형 장기 스모크**
   - s1/QA profile처럼 60초 cadence 설정으로 같은 루프를 축소 실행한다.
   - Redis intake, game-engine daemon, game-api read, SSE 관측을 포함한다.
   - 목적은 패러티가 아니라 운영 중 no-op, false-deny, stale frontend를 잡는 것이다.
   - fresh DB seed 중 중간 실패가 발생해도 `world_state`만 남아 재시도가 skip되지 않는지 검증한다. 현재 `ScenarioImporter.importAll` 전체 원자성은 오픈 리스크다.

## 구현 원칙

- 억지 종착상태를 만들지 않는다. 테스트가 직접 `isunited`나 도시 소유권을 조작하면 장기 결함을 숨긴다.
- 플레이어 국가를 2개가 아니라 수개 만든다. 최소 5개, 기본 6개로 둔다.
- 플레이어 장수는 수십 명을 만든다. 기본 60명, 국가당 10명으로 두고 군주·내정·전쟁·징병·외교 역할을 분화한다.
- 내정 캐릭터는 농업/상업만 하지 않는다. 치안, 성벽, 수비, 기술까지 각기 다른 담당 장수로 실행한다.
- 자동외교는 전쟁 회피용 편의 기능이 아니라 장기 시뮬의 전략 입력이다. 선전포고, 종전, 불가침, 불가침 파기를 모두 상태 전이로 검증한다.
- 모든 역할군은 자기 전문 커맨드만이 아니라 공개된 전체 커맨드를 선택 대상으로 가져야 한다. 특정 역할이 전문성 때문에 다른 우선순위를 갖는 것은 허용하지만, 메뉴/계약/파서 단계에서 역할별로 빠지는 커맨드는 v1 안정화 실패로 본다.
- 모든 행동은 실제 명령 핸들러를 통과한다. 테스트 전용 helper가 직접 world row를 완성하지 않는다.
- 빠른 게이트와 장기 게이트를 분리한다. B0는 CI 친화, B1은 천통 품질 게이트, B2는 운영 스모크다.
- v2 구현은 B0 또는 B0.5를 깨면 중단한다. B1은 v2 오픈 전 hardening gate로 승격한다.

## 자동외교 정책

자동외교는 B1 player-bot의 필수 서브시스템으로 둔다.

| 상황 | 봇 행동 | 명령 | 검증 |
|---|---|---|---|
| 인접 비동맹 국가를 공격 목표로 선정 | 전쟁 의사를 먼저 확정 | `che_선전포고` | 양방향 diplomacy state/term 변경, 이후 `che_출병` allow |
| 양국 모두 병력·식량이 부족하거나 제3국이 위협 | 휴전 요청 | `che_종전제의` → `che_종전수락` | war 상태가 trade로 전환 |
| 초반 확장 중 후방 국가와 충돌 회피 | 불가침 요청 | `che_불가침제의` → `che_불가침수락` | non-aggression 상태와 term 기록 |
| 천통 직전 또는 공격 목표 전환 | 불가침 파기 | `che_불가침파기제의` → `che_불가침파기수락` | trade 상태 복귀 후 선전포고 가능 |
| 외교 명령이 fallback | 원인 기록 후 다음 월 재평가 | 동일 명령 재시도 또는 전쟁/내정 대체 | 첫 fallback command, denyReason, 양방향 diplomacy row 기록 |

외교 봇은 완전한 AI가 아니라 deterministic policy다. 같은 seed와 같은 world snapshot이면 같은 상대국, 같은 제의, 같은 수락 여부가 나와야 한다. 수락 정책은 최소한 전력비, 인접 전선 수, 도시 수, 식량, 현재 diplomacy state를 입력으로 삼고, 결과와 score를 로그에 남긴다.

## 이번 달 실행 순서

| 순서 | 산출물 | 완료 조건 |
|---|---|---|
| 1 | B0 공백지 다국가 커맨드 IT (완료) | 대상 테스트 녹색, restart snapshot 검증 |
| 2 | B0.5 전체 커맨드 선택/계약 게이트 (완료) | 모든 역할군 x 전체 공개 커맨드가 parse + FULL allow 계약 통과 |
| 3 | v1 안정화 체크리스트 | seed/load/intake/flush/read/SSE/deploy 항목별 명령과 판정 기준 문서화 |
| 4 | B1 player-bot 설계 (B1a 완료) | 내정·징병·전투·선전포고 정책, 실패 로그, deterministic seed 고정 |
| 5 | B1 12개월 윈도 (완료) | 억지 상태 변경 없이 공백지 점령과 국가 성장 관측 |
| 6 | B1 천통 확장 (완료) | `checkEmperior`가 `isunited=2`를 만들 때까지 윈도 확장 |
| 7 | v2 V2-0 경계 고정 | v1 gate 녹색, v2 sandbox world/profile/schema가 production과 분리 |
| 8 | v2 V2-1 첫 수직 slice | command result lifecycle과 조작 대상 갱신이 실제 화면에서 관측 |

## 오픈 전 Go/No-Go

Go:
- B0 녹색.
- B0.5 녹색.
- v1 backend gate 녹색 또는 실패 항목이 문서화된 비차단 항목.
- 공백지 player-bot이 억지 상태 변경 없이 94개 도시를 점령하고 `isunited=2`로 종료.
- 자동외교가 최소 한 번 선전포고 또는 불가침/종전 상태 전이를 실제 diplomacy row로 남긴다.
- production-like sandbox에서 장수 생성 후 화면 갱신이 턴 완료와 무관하게 관측.
- 배포 체크리스트가 image tag, seed code, runner health, disk headroom, DB migration 상태를 확인한다.

No-Go:
- 명령 성공 응답이 flush 이전에 노출된다.
- 공백지에서 건국 또는 임관이 false-deny 된다.
- 외교 명령이 성공처럼 보이지만 diplomacy row가 변하지 않는다.
- 버튼이 실제 daemon 결과 없이 완료처럼 보인다.
- B1이 같은 월/명령에서 반복 중단되는데 원인 기록이 없다.
- 전체 engine JUnit은 녹색이지만 XML `system-out`에 의도적 `TurnDaemonRunnerTest` 회복 fixture 외의 예상하지 않은 `turn-daemon-loop tick failed`, JDBC 연결 오류, 종료 순서 역전이 남아 있다.
- seed 중간 실패 후 부분 `world_state`로 인해 재시도가 멱등적 skip된다.
- v2 migration/schema가 s1 production world를 오염시킨다.
