# v1 비운영 미완성 폐쇄 원장

## 평가 계약

- 골든셋: `GOLDENSET.md`
- 베이스라인 채점자: audit §6/§8, backend/frontend gates, PHP Docker capture, local Docker smoke, two-world/restart IT, authenticated Playwright.
- 채택 기준: PHP 근거가 있는 RED→GREEN 또는 미도달 경로의 end-to-end 도달성 확보, 기존 게이트 무약화.
- 원복 기준: 같은 채점에서 동점/하락, PHP 반증, 또는 기존 패러티 회귀.
- 실행 경계: commit/push는 2026-07-30 사용자 승인 범위다. `main` merge는 shared-stack 배포 경고 뒤 별도 재확인 대상이며, production cutover/data delete/secret access/legacy write/golden or test weakening은 승인되지 않았다.

## 바퀴

| 바퀴 | 가설 | 점수 전→후 | 채점자 | 판정 | 원인 한 줄 | 승인 대기 |
|---:|---|---|---|---|---|---|
| 0 | 2026-07-26 감사 이후 로컬 Docker/PHP/browser 기준선을 측정한다 | backend 521 suites/4,585 tests/0 failures/0 errors/138 skips; web-game 42 files/216 tests; PHP 12개월 `reachedMaxTurns=true`; local Compose는 image build 중 Gradle daemon disappearance | audit §6.9 + `tools/parity/gate.sh backend` + frontend gate + `run_longsim.sh` + smoke log | 진행 중 | Testcontainers와 PHP capture는 실제 Docker green. Compose baseline은 동시 빌드 자원 경합으로 service start 전 실패했고 OOM은 미확정; 제품 smoke 판정이 아니므로 final clean run 필요 | production/S6 제외 |
| 1 | CommandModal이 HTTP 202 접수를 성공으로 오인하지 않고 daemon terminal 결과를 기다린다 | 신규 RED 4/4 실패 → GREEN 4/4, typecheck/diff-check green | `CommandModal.terminal-result.test.tsx` | 채택 | general/nation 모두 `submitCommandAndAwaitResult`를 사용하고 applied만 성공·닫기, reject/pending은 화면 유지 | Docker/browser 대기 |
| 2 | select-pool pick/update도 terminal result가 적용된 뒤에만 성공·reload한다 | 신규 RED 6/6 실패 → GREEN 6/6; 기존 live-noop test 8/8 green | select-pool terminal RTL + stale regression | 채택 | applied/rejected/pending을 구분하고 서버 거절 사유와 `처리 지연`을 보존 | Docker/browser 대기 |
| 3 | 개인 명령 admission을 PHP 공개 general+intake catalog로 제한한다 | 임의/internal/chief-only가 Rest→Available→reserve되는 source RED → admission 구현 및 diff-check green; focused XML 대기 | `CommandControllerSecurityTest` | 채택(검증대기) | registry fallback은 daemon 내부에 유지하고 API 경계만 좁혔다 | shared Gradle contention 해소 후 root XML |
| 4 | 같은 daemon drain에서 vote/inheritance 명령이 직전 pending write를 즉시 본다 | DB snapshot-only source RED → pending vote key + effective inheritance/KV overlay 소비 구현; diff-check green | Vote/Inheritance focused tests | 채택(검증대기) | 보상·RNG·포인트 소비 전에 recorder의 최신 pending truth를 읽는다 | root focused XML |
| 5 | 토너먼트의 synthetic score를 실제 PHP-MT 전투·예선/본선으로 교체한다 | 기존 fixture 미소비 source RED → 6 fight fixture, phase55 qualify, all-group schedule, daemon rank/log delta 구현 | tournament golden + RNG vectors | 채택(검증대기) | production fight는 hiddenSeed를 날조하지 않고 ambient PHP-MT state를 사용한다 | root XML + Docker PHP 2회 |
| 6 | 공개 명령의 복합 인자를 ordered multi-field form으로 만든다 | scalar `argType`로 13종 payload 순서 손실 → PHP 93 files/92 unique, key 92/92, 13 order mismatch 0, RTL 6/6 | PHP command matrix + catalog/RTL | 채택(backend 검증대기) | catalog와 modal이 같은 ordered field spec을 사용한다 | root logic/game-api XML |
| 7 | 메시지에 envelope 시각과 PHP 삭제/표식 순서를 적용한다 | turn-time/null public dest/source RED → sentAt, durable lastMsg, invalid-dest throttle, receiver→sender markers, public dest=src 구현 | Message handler/lifecycle tests | 채택(검증대기) | game-api/daemon 사이 server sentAt을 버리지 않고 recorder 단일 write로 저장한다 | root engine XML |
| 8 | 월말 외교·rank·통계 날짜가 같은 tick의 최신 상태를 사용한다 | Q9 original rows/kill-death 0/pre-date statistic RED → ordered Q5→Q7→Q9, rank baseline+overlay, advanced MonthlyEnv 구현 | monthly focused tests | 채택(검증대기) | flush 성공 뒤 clock advance라는 기존 recovery 불변식은 유지했다 | root logic/engine XML |
| 9 | 출병·점령·국가베팅·저장 로그 PHP 캡처를 독립 fresh DB에서 두 번 실행해 결정성을 증명한다 | 초기 nation-betting 캡처는 install 시각/server ID가 달라 byte mismatch → fixture 입력을 고정한 뒤 4 families/8 artifacts 모두 2회 byte-identical, SHA256SUMS 9개 OK | `tools/php-golden/run_v1_evidence.sh`; `build/v1-evidence/evidence-63e09cfa14b3/MANIFEST.json` | 채택 | 변동 필드를 출력에서 삭제하지 않고 capture fixture의 PHP 입력만 고정했으며, raw 스냅샷과 부수효과는 그대로 비교했다 | 없음 |
| 10 | 기존 12개월 장기 재생 픽스처가 production PHP AI 입력을 완전히 담고 있다고 본다 | exact gate 활성화 후 달력·시각 하니스 오류를 차례로 제거했으나 `game_env`가 10키만 포함해 live PHP의 `maxgeneral`·`npc_*_policy`·`autorun_user` 등을 누락함을 확인; gate는 현재 RED | `LongSimReplayGateTest`; `.omo/evidence/v1-ai-production/longsim-nation-divergence-analysis.md`; attempt 1–10 logs/XML | 기각 | 누락 env 기본값으로 Kotlin 건국 AI가 PHP와 다른 후보/분기를 선택하므로 기존 fixture만으로 exact 완료를 주장할 수 없다 | tracked golden은 read-only; 신규 versioned full-env PHP 캡처로 교체 검증 필요 |
| 11 | PHP SQL `ORDER BY RAND()`를 connection seed로 고정하면 12개월 full-env 캡처 두 번이 byte-identical하다 | baseline은 2회 동일(SHA `df0298cd…`), fresh env는 10→32키로 확장됨; `SELECT RAND(424242)` 후에도 month-12가 179 records/10 nations 대 191/11로 달라짐 | `.omo/evidence/v1-ai-production/longsim-full-env-capture.md`; `longsim-capture-attempt2.md` | 기각 | MariaDB `ORDER BY RAND()` 선택은 해당 seed seam으로 장기 실행 전체를 고정하지 못했다. SQL 선택 스트림을 외부 오라클 입력으로 캡처하지 않으면 full-state exact를 주장할 수 없다 | tracked golden 불변; 선택 스트림 비침습 캡처·replay 필요 |
| 12 | v1 달력을 PHP처럼 12턴/년으로 되돌린다 | 구현 전 정적 근거와 사용자 제품 정본이 충돌 → 사용자 직접 확정: “v1도 36순을 써.” | ADR-LITE-024 | 기각 | v1은 3순×12개월=36순을 유지한다. 잔여 결함은 36순 자체가 아니라 다중 경계 catch-up의 live date/로그 시각 전파다 | 없음 |
| 13 | 36순 catch-up의 각 경계에서 live date를 전진시키고 로그 생성 시점을 고정하면 single-tick과 catch-up이 일치한다 | phase 2/3가 final flush까지 옛 날짜를 유지하고 nullable 로그 날짜가 최종 월로 오염되는 RED → nonmonthly boundary callback + `pushLog` null-date stamp; ServerClock 7/7, engine catch-up/recovery 10/10, `BUILD SUCCESSFUL` | `ServerClockTest`; `MonthBoundaryLoopTest`; `V1CalendarCatchupTest`; `V1CalendarFlushRecoveryTest` | 채택 | phase 1의 L5 old→L7 new→Month/post 순서는 보존하고 phase 2/3만 경계 직후 전진한다. FLUSH_RETRY는 retained payload로 동일 날짜를 복구한다 | full backend + longsim replay 대기 |
| 14 | 토너먼트 PHP-MT/대진 캡처는 fresh scenario 두 번에서 byte-identical하다 | 6 fights + qualify + 3 RNG vectors, 각 27,601 bytes, SHA `dc81e9c…`; two-run cmp와 canonical cmp 모두 identical | `.omo/evidence/v1-tournament-resume-20260727/php-two-run-capture.log`; `php-tournament-capture.json` | 채택 | 실제 PHP `fight()`·예선·본선과 RNG 벡터가 Kotlin이 소비한 canonical capture와 일치한다 | full backend 대기 |

## 백로그 가설

- 명령 terminal/form matrix와 build-nation/settings/vacation closure
- monthly Q9 ordering, NPC troop leader, betting open, rank/invader inputs
- battle side pipeline, source-city bonus, outer conquest effects and round-trip
- AI policy/vacation/semiannual/long-sim
- side-system lifecycle and tournament RNG
- raw JPA repository world-scope facade
- emperor/settings/select-pool/mailbox/global history routes
- stored log byte contract

## 2026-07-29 종결 측정

이 절은 위 바퀴의 당시 `진행 중`/`검증대기` 상태를 숨기지 않는다. 아래는
그 상태를 실제 PHP·Docker·gate 증거로 다시 측정한 후의 종결 기록이다.
운영/S6 cutover는 처음부터 이 루프의 제외 범위이며, PASS로 바꾸지 않는다.

| 바퀴 | 가설 | 점수 전→후 | 채점자 | 판정 | 원인 한 줄 | 제외/후속 |
|---:|---|---|---|---|---|---|
| 15 | schema 4 fresh PHP cohort을 v1 36순으로 12개월 재생하면 구 fixture의 first-divergence가 사라진다 | 이전 disabled/RED → Kotlin XML 1/0/0/0, capture A/B SHA 동일, handled drain 7,428 | `LongSimReplayGateTest`, `schema4-12month-exact-final-report.md` | 채택 | raw `prev_income`은 display round 값이 아니라 PHP의 raw income이어야 했다 | production/S6 제외 |
| 16 | 생산 `TurnRunService`가 36 phase, 12 monthly, flush retry와 restart 뒤에도 같은 calendar를 유지한다 | old date/bean/retry proof gap → engine 4 tests/0 failure/error/skip | `EmptyWorldBootIT`, `MessageRepositoryWiringTest`, `V1CalendarFlushRecoveryTest` | 채택 | phase별 live date와 retained payload를 분리해 monthly는 phase 1에서만 실행했다 | production/S6 제외 |
| 17 | runtime daemon의 side-read bean 및 E2E response 동기화를 고치면 실제 join이 terminal·front state까지 닫힌다 | runtime6 timeout/bean 결여 → runtime9 Playwright 1 expected/passed, 0 unexpected | isolated `local_v1_gate.sh`, retained JSON/DOM/restart artifacts | 채택 | `MessageRepository`를 process `WorldId` scoped bean으로 제공하고 POST join의 request id·`RESOLVED`·`hasGeneral`을 모두 기다렸다 | production/S6 제외 |
| 18 | 넓은 회귀와 최종 프런트 재실행이 각 family의 focused 증거를 뒤집지 않는다 | backend 521/4,585 → 550/4,753; affected backend 185/1,172 0 failure/error; game 46/227 + typecheck | backend gate, affected modules, frontend final verification, independent review | 채택 | 마지막 `my-boss` RED는 제품 매핑이 아니라 test event timing이었고 awaited user interaction으로 재현/해소했다 | whole-worktree strict baseline은 사용자 소유 `.codex/config.toml` 1 error와 별도 |
| 19 | checker 수정 뒤 최종 Agent OS rerun이 비운영 종결의 후속 finding을 만들지 않는다 | exact 1회 rerun: Gradle 5 modules `BUILD SUCCESSFUL` 13m27s/29 tasks, web/game 46/227 + typecheck, contract/diff PASS; strict 1 error/0 warnings | `scripts/agent/verify-changes.sh --run`, `verify-changes-final2` artifact | 채택(범위 분리) | cleared/quarantined disjoint Scope union을 인정해 cross-agent finding은 해소됐고, 유일한 strict error는 untouched user-owned config다 | strict green·ship/merge ready 아님; production/S6 제외 |

종결 증거의 상세 경로는
`../../superpowers/research/2026-07-26-v1-legacy-equivalence-audit.md`의 2026-07-30
사후 검토 부록 및 2026-07-29 historical snapshot,
`../../superpowers/reviews/2026-07-27-v1-nonoperational-completion-review.md`를
따른다.

최종 rerun은 checker 수정 뒤 정확히 한 번만 실행했다. 결과의 exit 1은
`.codex/config.toml`의 최상위 personal model pin이라는 사용자 소유 기존
whole-worktree strict 기준선 하나뿐이며, 이 루프에서 수정하지 않았다. 따라서
비운영 기능 종결의 PASS와 strict 전체 녹색은 별개다. 증거는
[verify-changes.log](../../../.omo/evidence/v1-final/verify-changes-final2/verify-changes.log)와
[exit-code.txt](../../../.omo/evidence/v1-final/verify-changes-final2/exit-code.txt)에 있다.

## 2026-07-30 사후 parity 검토와 corrected gate

이 절은 7월 29일 표의 당시 `채택`을 지우지 않는다. 아래는 final reviewer의
scope 재개방과 그 보정 뒤, git action 전 release-candidate 증거를 다시
기록한 것이다. S6/production cutover는 계속 제외다.

| 바퀴 | 가설 | 점수 전→후 | 채점자 | 판정 | 원인 한 줄 | 제외/후속 |
|---:|---|---|---|---|---|---|
| 20 | V32 복합 world key만으로 side read의 cross-world 혼입을 막을 수 있다 | final review가 `SelectPool`·`VotePoll`·`DiplomacyLetter` unscoped read를 발견 → outer/nested query `WorldId` binding, 중앙 scoped beans, same-local-ID 2-world regression | final parity review | 채택 | 복합 key는 row identity일 뿐 local-ID-only read를 world boundary로 만들지 않는다 | `OPENSAM-149 (OP149) Rehydrate`는 origin/main의 superseded·not-wired historical quarantine; 현 v1 blocker/증거로 재연결 금지 |
| 21 | scope 보정 뒤 canonical backend와 frontend가 비운영 종결을 유지한다 | backend 552 suites / 4,758 tests / failure·error 0, known `LongSim` skip 1; fresh 영향 범위 237/1,366 green; frontend typecheck + 46/227 + diff-check green | canonical backend/frontend gates | 채택 | Golden은 current green이나 logic task `UP-TO-DATE`라 fresh logic rerun으로 과장하지 않는다 | S6/production cutover 제외; final parity verdict `CLEARED` |
| 22 | 순차 image build와 격리 E2E가 병렬 OOM/포트 충돌의 환경 잡음을 제거한다 | five images sequential green, 8 health green, Playwright 1 passed `241634ms`; join `RESOLVED`, `ok=true`, general `1230`; exactly 14 DOM; restart general/result/repository `200`; auth `false|false`; project containers 0 | corrected local Docker gate | 채택 | 이전 fresh parallel build OOM과 port 3000 collision/120s timeout은 하니스/환경 실패였고 제품 assertion 실패가 아니다 | root timeout default `420000`, override test green; production 제외 |
| 23 | 무관한 OOM container가 final project cleanup/결과를 오염시키지 않는다 | `eager_cray` OOMKilled, volume retained, not restarted; final project containers 0은 별도 확인 | corrected local Docker gate | 채택(격리) | 무관 container를 gate project로 세거나 삭제하지 않았다 | 운영/외부 container 조작 없음 |

7월 29일의 strict error 1은 historical whole-worktree hygiene 기준선일 뿐, 이
사후 parity 증거의 current blocker가 아니다. 반대로 current strict green을
주장하지도 않는다. 이 원장은 commit/push/merge/deploy를 실행하거나 승인하지
않으며, 연도는 계속 **36순**이다.
