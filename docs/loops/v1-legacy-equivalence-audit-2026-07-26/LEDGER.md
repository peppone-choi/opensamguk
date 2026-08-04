# Version 1 Legacy Equivalence Audit — Loop Ledger

## 평가 계약

- 베이스라인: PHP `legacy/devsam-core` commit `4de7ebec17a722d516608dbb987467f1a451dada`, opensamguk commit `0cbcf44626074f7e481d58b6e42defab164b6ea7` + 기존 사용자 작업 트리 변경 보존.
- 골든셋/채점자: 기존 `tools/parity/gate.sh backend`, 모듈 테스트 XML, `web/gateway` typecheck, `web/game` typecheck+Vitest, 브라우저/API 관측, PHP/hwe path+line.
- 합치기 기준: 재현된 결함이 추가된 회귀 테스트에서 RED→GREEN이고 기존 게이트가 약화 없이 유지.
- 원복 기준: 동일 시험지에서 동점/하락하거나 PHP 근거가 반증되면 해당 가설 변경만 원복.
- 승인 대기: golden/test 기대값 변경, commit/push/merge/deploy/data delete/secret access는 금지 또는 별도 사람 승인.

## 바퀴

| 바퀴 | 가설 | 점수 전→후 | 채점자 | 판정 | 원인 한 줄 | 승인 대기 |
|---:|---|---|---|---|---|---|
| 0 | 변경 전 v1 완성도와 결함을 문서·PHP·코드·실행 게이트로 측정한다 | backend 515 suites/4,572 tests/204 skip, web 41 files/212 tests, v1 incomplete | docs 388 전수 원장 + backend/frontend 게이트 + PHP/Kotlin/Next 대조 | 채택 | green test 수와 실경로 완성도가 다르며 long-sim/live QA 공백이 존재 | PHP/Docker/browser 채점대기 |
| 1 | cold loader의 모든 world-owned 조회를 scope하고 troop을 복원하면 cross-world/restart 발산을 막는다 | unscoped 9 family + troops=0 → scope contract 2/2, catalog 10/10 green; 2-world IT 1 skip | `WorldSnapshotLoaderWorldScopeContractTest`, `HotColdWorldCatalogGuardTest`, `WorldSnapshotLoaderWorldScopeIT` | 채택 | V32 복합 키 이후에도 loader가 singleton 쿼리와 빈 troop snapshot을 유지 | Docker IT 채점대기 |
| 2 | `ProfileIconSync`가 lifecycle result를 반환하면 durable inbox가 한 flush에서 종결된다 | wire RED `unknown result type=profileIconSync` → wire 4/4, handler 9/9, lifecycle 1/1 green | wire round-trip + handler + `TurnRunService` flush payload | 채택 | null result가 dispatcher에서 사라져 terminal row와 inbox 전환이 없었음 | 없음 |
| 3 | 천도의 실제 BFS 거리 하나를 constraints/stack/resolve에 공유하고 overflow를 제거하면 PHP 수식과 실행이 일치한다 | d25 음수/d31 0/d32 500/d50 131072000 → logic 5/5, daemon 3/3, command matrix green | JDK old-formula 재현 + `CheondoTest` + `ProcessNationCommandCheondoTest` + broad logic gate | 채택 | 거리 미주입과 32비트 shift/multiply가 비용·턴·보상을 동시에 왜곡 | PHP 실행 골든 채점대기; catalog PRECHECK 거리 표시 잔여 |
| 4 | production AI가 기본 policy object의 cureThreshold를 쓰면 부상 11~30 분기가 PHP 기본값과 같아진다 | hardcoded 30 → 경계/실훅 2/2 green | `AiTurnAdapterCureThresholdTest` | 채택 | 정책 기본 10이 adapter 상수 30에 의해 무시됨 | 4층 정책 전체 배선은 별도 blocker |
| 5 | event cold-load에 canonical WorldId를 바인딩하면 다른 월드 이벤트가 실행되지 않는다 | unscoped SELECT → 2-world fake 1/1 green | `EngineEventConfigWorldScopeTest` | 채택 | event row가 world-owned로 확장된 뒤 loader predicate가 갱신되지 않음 | Docker 실DB 채점대기 |
| 6 | board/auction route가 query를 초기 state로 소비하면 MainControlBar deep link가 정답 화면을 연다 | query 무시 → route 4/4, web 전체 42 files/216 tests green | `board-auction-deep-links.test.tsx` + typecheck + full Vitest | 채택 | 두 client page가 상수 기본 state만 사용 | live browser 채점대기 |
| 7 | 독립 리뷰의 천도 부수효과·증거 지적을 PHP 순서로 보정하면 bounded diff를 승인할 수 있다 | 1차 `fix-required` → trial 3회, united no-write, inheritance→로그 5건→static hook, source map 39/39 → `cleared` | `review_v1_audit_fixes` + `second_code_review` + focused XML + 최종 backend gate | 채택 | 최초 패치는 수치 경로만 닫고 KV·유산·로그·이벤트와 감사 줄 근거를 놓쳤음 | v1 전체 blocker와 live QA는 계속 잔여 |

## 변경 후 종합 채점

- `tools/parity/gate.sh backend`: `BUILD SUCCESSFUL`, XML 521 suites / 4,585 tests /
  0 failures / 0 errors / 205 skipped.
- `web/game`: typecheck green, Vitest 42 files / 216 tests green.
- 첫 변경 후 backend gate는 천도 성공 fixture에 실제 거리가 없어서 11건 RED였다.
  기대값을 낮추지 않고 fixture에 인접 거리 1을 명시한 뒤 같은 전체 gate가 green이 됐다.
- PHP CLI, Docker daemon, 로컬 서비스가 없으므로 PHP 재캡처, Testcontainers
  2-world round-trip, 브라우저 실관측은 `채점대기`다.
