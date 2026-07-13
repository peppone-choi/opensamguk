# LEDGER — live-gap-closure-2026-07-10

| 바퀴 | 가설 | 점수 전→후 | 채점자 | 판정 | 원인 한 줄 |
|---|---|---|---|---|---|
| 0 | 기준선 | backend 442 suites / 3469 tests / fail 0 | `tools/parity/gate.sh backend` | 기준 | main `e2e55f3`, prod health와 `/game/s1` 200 |
| 1 | live gap 전수 목록과 RTK14 매칭 시험지를 먼저 고정 | 목록 없음 → 5개 구현 웨이브 | PHP path, UI click audit, XLSX 1000행, scenario 678명 | 채택 | 무동작·불일치만 범위로 삼고 운영 선택은 제외 |
| 2 | 월간 빈 callback 6종을 PHP 순서로 복구 | 상수/빈 callback → Q11~Q17 + 실제 PRE adapter | 집중 테스트 + backend gate | 채택 | 2026-07-11 전체 backend gate green으로 재채점 완료 |
| 3 | 수락되지만 거부/무동작인 명령 6종을 실제 처리 | null/상수 deny → personnel/diplomacy/select-pool handler | dispatch/intake 테스트 + PHP oracle | 채택 | 운영 repository와 ONE daemon write 경로 연결, 전체 게이트 green |
| 4 | 대회 상태기계를 매턴 처리와 관리자 API에 연결 | toast/누락 → daemon/start/reset/read 계약 | 상태 전이 테스트 + UI QA | 채택 | API/프론트 계약 통일, web/game 테스트 143/143 + 백엔드 게이트 green |
| 5 | 활성 UI 무동작을 실제 route/request로 교체 | 31 files / 128 tests green | typecheck/test + Playwright | 채택 | 감찰부, 대회 관리, 내정보 로그, 선발장 request 연결 |
| 6 | NPC·유저 5능력치 경로와 총량 상한을 완결 | 30 scenarios / 10,176 tuples + Python 8 tests + join tests | importer/creation/API/UI 테스트 | 채택 | tuple 14/15 원수치, 유저 total 275, reset 정치·매력 보존 |
| 7 | 런타임 P0 감사에서 발견한 저장·제약·이벤트 유실을 닫기 | 전투/PRE/AI/이벤트 결과 유실 → backend 3,599 tests fail 0 | `tools/parity/gate.sh backend` + web tsc/test | 채택 | 2026-07-11 전체 게이트: infra `BettingUpsertFlushIT` init 1건은 Testcontainers 접속 flake(단독 재실행 2/2 green), web/game 143/143 + tsc 0, web/gateway tsc 0 |
| 9 | 인재탐색 NPC 이름 선택을 PHP 이름 풀의 중복 회피 규칙으로 복원 | prod 256년 괴포 3개 ID·비포 2개 ID가 동일 표시명 재사용 → focused Kotlin 회귀 1/1 green, 전체 게이트·prod 재측정 중 | PHP `RandomNameGeneral.php:30-62`, `AbsGeneralPool.php:79-85`, `GeneralBuilder.php:42-52` + 운영 DB ID별 로그 | 채점중 | 사망 중복이 아니라 `che_인재탐색`이 현재 장수명 중복 검사와 숫자 접미사를 우회한 이름 풀 divergence |
| 10 | 선전포고 실행 게이트에도 실제 보급 인접국 판정을 공급 | prod 57년간 신규 전쟁 0·`인접 국가가 아닙니다.` 반복 → 실제 인접 허용 RED→green, 무보급 인접 거부 RED→green, 전체 게이트·prod 재측정 중 | PHP `che_선전포고.php:76-95`, `Constraint/NearNation.php:23`, `GeneralAI.php:1848-1970` + `NationCommandDispatchTest` | 채점중 | AI 후보 선택에는 `__isNeighbor`가 있었으나 최종 full constraint 재평가에는 없었고, 복구 시 PHP의 `isNeighbor(..., false)` 보급 필터도 함께 명시해야 함 |

## RTK14 매칭 기준

- 원본: `/Users/apple/Desktop/삼국지14 무장정보.xlsx`, `무장` 시트 1000명.
- 대상: 저장소의 30개 `scenario_*.json`, 장수 tuple 10,176개(`scenario_1010` 678명 포함).
- 1차: 이름 exact match. 동명이인은 기존 통솔·무력·지력 fingerprint의 최소 거리로 1:1 결정.
- 2차: 한자 독음·표기 변형·자가가 확인되는 이름만 명시적 alias로 연결.
- 불확실하거나 원본에 없는 인물은 자동 추측하지 않고 50/50 fallback을 유지.
- 생성물은 원본 파일명을 유지한 완성 `scenario_*.json`이며 tuple 인덱스 14/15를 덮어쓴다. source JSON과 생성 디렉터리는 gitignored sidecar로만 사용.

## 초기 감사 목록

- 월간: `checkWander`, `updateGeneralNumber`, nation static refresh 의미 검증, tournament trigger, auction register, `SetNationFront`.
- 명령: `selectPoolPick`, `selectPoolUpdate`, `appoint`, `kick`, `changePermission`, `diploRespondLetter`.
- 대회: per-turn `processTournament`, 관리자 start/reset, read 상태.
- 프론트: 감찰부 coming-soon, tournament toast-only, 내정보 로그 fake load-more, 선발장 mutation.
- 5능력치: alias/fingerprint 매칭, MakeGeneral 입력·검증·영속화·read/UI, 5능력치 총량.

## 바퀴 8 — 서브에이전트 버그 헌팅 (2026-07-11)

5축 병렬 리뷰(intake/월간훅·전투영속/대회/game-api보안/web프론트)로 15건 접수, 오케스트레이터 검증 결과:

- **기각 14건**: `listGenerals()`는 `.toList()` 복사(반복 중 변이 불가), intake 핸들러는 데몬 단일스레드(race 불가), world_state는 flush step 1 always-UPDATE로 status/config/tick_seconds 영속, auctionRepository는 `DaemonLoopConfig.kt:297` prod 주입, `TournamentAdminService` 무상태, `refresh_score_total`은 V25 마이그레이션 존재, select-pool base path 명시 매칭.
- **실질 파리티 갭 1건 (백로그)**: `logic/tournament/ProcessTournament.kt` `resolveMatch()`가 결정론 점수비교(`score = total + level`, goal `/10`)인 반면 PHP 정본 `hwe/func_tournament.php` `fight()`(1004행~)는 에너지 기반 RNG 전투 시뮬(아이템 로그, `rand()%4` 문구, `Util::round($gen[$tp]*getLog(...)*10)`, win/draw/lose 3상태 + rank_data gl `Util::round(($gd2-$gd1)/50)`). 대회 승패·로그 byte-parity 미달 — `fight()` 풀 포트 + PHP 골든 캡처 필요. 상태기계 전이(조·토너먼트 단계)는 계약 수준 일치.

## 승인 대기

없음. 사용자가 수정·푸시·머지·prod 재기동과 DB 유실 허용을 명시했다.
