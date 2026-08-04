# opensamguk 버전 1 레거시 동등성 감사

> ## 2026-07-30 사후 검토 부록 — 비운영 v1 release-candidate 증거
>
> 이 부록은 2026-07-29 종결 뒤의 마지막 패리티 검토 결과다. 7월 26일의
> 발견·RED·`채점대기`와 7월 29일의 측정값을 지우거나 현재값처럼 고쳐 쓰지
> 않는다. 아래 7월 30일 값이 현재 상태를 대체하고, 이전 수치에는 날짜를
> 붙여 역사 증거로만 읽는다.
>
> **현재 판정:** 최종 패리티 review는 **`CLEARED`**다. 이는 v1 비운영 범위의
> release-candidate 증거이며, git action 전에도 유효한 관측 묶음이다. 그러나
> commit, push, merge, deploy의 승인·실행을 뜻하지 않으며, CQRS S6·운영
> cutover는 계속 범위 밖이다.

| 사후 검토 항목 | 최종 확인 |
| --- | --- |
| world scope 재개방 | 최종 reviewer가 처음에는 `SelectPool`·`VotePoll`·`DiplomacyLetter`의 unscoped read를 발견했다. V32의 복합 world key가 row identity를 보장해도 local-ID만 읽으면 다른 world의 같은 ID를 잡을 수 있었던 것이 원인이다. |
| 보정 계약 | 모든 outer query와 nested query가 `WorldId`를 받아 `world_id` predicate/binding을 적용하고, process `WorldId`를 주입하는 중앙 scoped beans만 소비하게 했다. 동일 local-ID를 가진 두 world 회귀로 이 escape hatch를 막는다. |
| OP149 격리 | `origin/main`에서 온 `OPENSAM-149 (OP149) Rehydrate` 표기는 superseded·not-wired historical quarantine이다. 현재 v1 종료 경로나 restart 증거로 다시 연결하지 않으며, 활성 blocker/구현 주장으로 사용하지 않는다. |
| backend 최신 gate | canonical backend gate는 **552 suites / 4,758 tests / failure 0 / error 0**이며 known `LongSim` skip 1개만 남는다. 영향 범위 fresh 측정은 **237 suites / 1,366 tests** green이다. Golden은 current green이지만 logic task는 `UP-TO-DATE`였으므로 이를 fresh logic 재실행으로 과장하지 않는다. |
| frontend 최신 gate | typecheck, `web/game` **46 files / 227 tests**, `git diff --check`가 green이다. |
| corrected local Docker gate | 다섯 이미지를 순차 build해 모두 green, 8개 health green, Playwright 1 passed (`241634ms`)를 관측했다. join은 `RESOLVED`, `ok=true`, `general=1230`; 정확히 14개 DOM route, engine restart 뒤 general/result/repository `200`, 인증 `false|false` 복원, 최종 project containers 0을 확인했다. |

### 하니스 실패의 해석

- 더 이른 fresh parallel image build의 OOM은 병렬 빌드 자원 경합으로 인한
  하니스/환경 실패다. 서비스 readiness나 제품 패리티 실패로 재분류하지
  않는다.
- 포트 `3000` 충돌 뒤의 120초 timeout도 같은 환경/하니스 실패였다. root
  timeout 계약은 기본 `420000`으로 고쳤고 override test는 green이다.
- 무관한 `eager_cray` container는 OOMKilled였지만 volume은 보존됐고 재시작하지
  않았다. final project의 container 0 판정에 섞지 않는다.

### strict 표기의 현재 경계

7월 29일의 user-owned `.codex/config.toml` personal model pin strict error는
그 시점 whole-worktree hygiene의 **역사 기준선**이다. 이것은 현재 v1 parity
release-candidate 증거의 blocker도, 현재 strict green이라는 주장도 아니다.
이번 부록은 실행 행위를 승인하지 않고, 최종 동작 증거와 git action 권한을
분리한다. v1 날짜 규칙은 계속 **월 3순·연 36순**이다.

> ## 2026-07-29 종결 부록 — 비운영 v1 감사 범위 종결 (historical snapshot)
>
> 이 부록은 아래 2026-07-26 감사의 당시 판정과 source map을 **지우거나
> 고쳐 쓰지 않는다.** 그 본문은 당시의 RED·`채점대기` 근거를 보존하는
> 스냅샷이다. 다만 아래 표와 실제 산출물로 §6.1–§6.8의 **비운영** 차단
> 항목은 구현·재측정되어 종결되었으므로, 이 부록은 §6 및 §9의 **2026-07-29
> 상태 판정**을 대체한다. 현재 상태는 위 2026-07-30 사후 검토 부록을 따른다.
>
> **당시 판정:** v1의 2026-07-26 감사 범위 중 비운영 동등성 폐쇄는
> **PASS**다. 이는 프로덕션 활성화 선언이 아니다. CQRS S6, canary,
> expand/backfill, capacity/admission, 실제 운영 월드 cutover와 배포는
> 명시적으로 제외되어 여전히 미수행이다.

| 7월 26일 차단 절 | 7월 29일 종결 근거 | 현재 상태 |
| --- | --- | --- |
| §6.1 명령·사용자 입력 | PHP 93파일/92 고유 명령의 form·intake·daemon·flush·terminal matrix, ordered 복합 인자 13종, 실제 202→`RESOLVED`/거절 경로 | 비운영 PASS |
| §6.2 월 틱·이벤트 | fresh PHP schema 4 12개월·36순 capture A/B byte-identical 및 Kotlin authoritative replay | 비운영 PASS |
| §6.3 전투·점령 | sortie/점령·국가베팅·저장 로그를 포함한 PHP 두 번 capture와 daemon/flush 회귀 | 비운영 PASS |
| §6.4 AI | 4층 정책·실제 월 입력을 포함한 schema 4 12개월 exact replay, 7,428 handled command drain | 비운영 PASS |
| §6.5 부가 시스템 | betting/select-pool/vote/inheritance/tournament/auction/diplomacy/mailbox terminal·부수효과 회귀 및 PHP tournament capture | 비운영 PASS |
| §6.6 world-scoped read·재시작 | scoped repository 경계, 실 Spring context `MessageRepository`, 36순 flush-retry/restart, local Docker 재기동 영속성 | 비운영 PASS |
| §6.7 프런트 도달성 | 인증된 local Playwright가 join terminal과 후속 장수 상태를 확인하고 14개 실제 route DOM을 보존 | 비운영 PASS |
| §6.8 저장 로그·운영 | PHP 저장 로그 capture와 local Docker full-stack/restart는 PASS; **S6/프로덕션 cutover는 제외·미수행** | 비운영 PASS / 운영 보류 |

### 2026-07-29 증거 묶음 (historical snapshot)

- PHP 정본은 그대로 `legacy/devsam-core` commit
  `4de7ebec17a722d516608dbb987467f1a451dada`다. schema 4 fresh PHP 8.3 /
  MariaDB 11.4 capture 두 개는 12개월·36순에서 byte-identical이고,
  Kotlin의 authoritative replay XML은 1 test, 0 failure/error/skip이다.
  상세: [`schema4-12month-exact-final-report.md`](../../../.omo/evidence/v1-ai-production/schema4-12month-exact-final-report.md),
  `diplomacy-identity-12month-authoritative-replay4.xml`.
- 표준 backend gate는 550 suites / 4,753 tests / 0 failure / 0 error,
  영향 backend 재검증은 185 suites / 1,172 tests / 0 failure / 0 error
  (long-sim 1 skip, Testcontainers skip 0)다.
- 최종 프런트는 `web/game` 46 files / 227 tests 및 typecheck green이고,
  gateway도 53/53 + build green이다. React/jsdom 이벤트 동기화로 드러난
  `my-boss` 테스트 플래키는 제품 ID 매핑이 아닌 test interaction 경계로
  분리·수정했다.
- local Docker runtime9은 격리 Compose 프로젝트에서 8서비스 health,
  인증 fixture 복원, join의 `202 → RESOLVED(ok)`·후속 예약/거절,
  14 DOM surface, 엔진 재기동 뒤 command result·장수·repository read
  영속성을 관측했다. Playwright는 1 expected/passed, 0 unexpected다.
- 독립 코드 검토는 `codeQualityStatus: CLEAR`, `recommendation: APPROVE`다.
  종결 요약은
  [`2026-07-27-v1-nonoperational-completion-review.md`](../reviews/2026-07-27-v1-nonoperational-completion-review.md)에
  남긴다.

### 문서 입력의 경계

이 감사가 참조한 문서는 동결 당시의
[`DOCS_MANIFEST.md`](../../loops/v1-legacy-equivalence-audit-2026-07-26/DOCS_MANIFEST.md)에
기록된 **388개**이며 모두 읽어 감사 입력으로 사용했다. 이 7월 29일
부록, 최종 review, runtime/loop 증거처럼 동결 뒤 새로 생성된 문서는 그
388개에 소급 산입하지 않는다. 새 문서는 과거 입력의 대체물이 아니라
그 입력에서 발견한 결함을 닫은 실행 기록이다.

### Agent OS 전체-worktree 검증 (2026-07-29, historical snapshot)

`tools/agent-system/check.py`의 cross-agent 범위 판정은 `cleared` 및 증빙이
있는 `quarantined-with-proof`의 서로 분리된 Scope coverage를 union으로 합치도록
수정됐다. 이 수정 뒤 최종 `scripts/agent/verify-changes.sh --run`은 **정확히 한
번** 재실행됐다. 독립 [scope-union 검토](../reviews/2026-07-29-cross-agent-scope-union-review.md)는
`cleared`이며, 이전 cross-agent finding은 제거됐다.

- Gradle 5개 모듈은 `BUILD SUCCESSFUL in 13m 27s`, 29 actionable tasks
  executed로 끝났다. 최종 JUnit XML 집계는 551 suites / 4,755 tests /
  failure 0 / error 0 / skip 1(기존 long-sim 격리)이다.
- `web/game` typecheck와 Vitest는 46 files / 227 tests를 통과했고, Agent OS
  contract와 diff/whitespace 검증도 PASS다.
- strict checker는 error 1 / warning 0이며, 전체 worktree에서 남은 유일한
  error는 **수정하지 않은 사용자 소유** `.codex/config.toml` 최상위 personal
  model pin이다. 따라서 이 결과를 strict green 또는 ship/merge ready로
  해석하지 않는다.
- 원본 증거: [verify-changes.log](../../../.omo/evidence/v1-final/verify-changes-final2/verify-changes.log),
  [exit-code.txt](../../../.omo/evidence/v1-final/verify-changes-final2/exit-code.txt).

이 strict 기준선은 비운영 v1 기능 종결과 별개의 whole-worktree 상태다. 전자의
기능·Docker·회귀 증거는 위 표의 PASS를 유지하며, 후자의 사용자 소유 설정 오류는
이 감사의 기능 종결을 넓혀 strict 완료로 바꾸지 않는다.

### 아직 하지 않은 것

- production/S6 rollout 또는 live EC2 cutover
- commit, push, merge, deploy, 데이터 삭제, secret 접근

- 감사일: 2026-07-26
- 감사 대상: 현재 작업 트리의 v1 호환 표면
- Kotlin/Next 기준 커밋: `0cbcf44626074f7e481d58b6e42defab164b6ea7`
- PHP 정본: `https://storage.hided.net/gitea/devsam/core`
- PHP 정본 커밋: `4de7ebec17a722d516608dbb987467f1a451dada`
- 판정: **미완성(fix-required), v1 동등 릴리스 차단**

## 1. 요약 판정

현재 버전은 “핵심 패러티 커널과 많은 기능이 구현된 상태”이지, PHP
`devsam/core`와 기능·부수효과·로그·RNG·재시작·사용자 도달성까지 동등한
버전 1 완료 상태가 아니다.

감사에서 다음 사실을 동시에 확인했다.

1. 표준 백엔드 게이트와 프런트 단위 게이트는 대규모로 통과한다.
2. 그러나 장기 리플레이가 disabled이고 Docker 의존 시험 204개가 skip된다.
3. 등록된 명령 수와 실제 사용 가능한 폼/실행/결과 표시 사이에 차이가 있다.
4. 전투·월 이벤트·AI·부가 시스템에는 PHP 부수효과가 실데몬 경로까지
   연결되지 않은 부분이 다수 있다.
5. multiworld cold boot, durable command 종결, 천도, AI 요양, 이벤트 적재,
   프런트 deep link에서 즉시 고칠 수 있는 확정 결함이 발견되었다.

따라서 “테스트가 많이 통과한다”를 “v1 완성”으로 해석하면 안 된다. 현재
릴리스 라벨은 **v1 부분 구현 / 패리티 작업 중**이 정확하다.

## 2. 감사 범위와 증거 우선순위

### 2.1 레거시 정본 고정

로컬 `legacy/devsam-core`의 `origin`이 사용자가 지정한 URL과 일치하며,
`git ls-remote`의 원격 HEAD와 로컬 HEAD가 모두
`4de7ebec17a722d516608dbb987467f1a451dada`임을 확인했다.

동작 판정 우선순위는 다음과 같다.

1. `legacy/devsam-core` PHP 실행 결과와 PHP 원문
2. `legacy/devsam-core/hwe/ts/` Vue 사용자 흐름
3. 실제 Kotlin/Next 런타임 관측
4. Kotlin/Next 테스트
5. 과거 문서의 완료 주장

문서와 테스트가 PHP 원문 또는 실경로와 충돌하면 PHP와 실경로가 이긴다.

### 2.2 `docs/` 전수 참조

감사 시작 시점에 `rg --files docs | sort`로 동결한 기존 문서 388개를
전부 읽고 감사 입력으로 분류했다.

- 파일 수: 388
- 총 바이트: 6,264,225
- 정렬된 경로 목록 SHA-256:
  `03cb498e4f848829bea60a3188b5416b89ca5b3b7a8251553af7585ba0c52538`
- 전수 목록과 확인 상태:
  [`docs/loops/v1-legacy-equivalence-audit-2026-07-26/DOCS_MANIFEST.md`](../../loops/v1-legacy-equivalence-audit-2026-07-26/DOCS_MANIFEST.md)
- 측정·가설·채점 원장:
  [`docs/loops/v1-legacy-equivalence-audit-2026-07-26/LEDGER.md`](../../loops/v1-legacy-equivalence-audit-2026-07-26/LEDGER.md)

이 보고서와 위 두 감사 산출물은 동결 뒤 새로 만든 결과물이므로 388개 입력
문서 수에는 포함하지 않았다.

### 2.3 문서에서 이미 확인되는 미완료 상태

문서 전수 검토만으로도 완료 판정을 내릴 수 없었다.

- `LongSimReplayGateTest`는 PHP 12국 대 Kotlin 5국 차이로 disabled 상태다.
- 명령 원장은 과거 시점 기준 `DONE 71 / FE_MISSING 20 / LOGIC_ONLY 2`를
  기록한다.
- F4 라이브 명령 폐쇄, F5 운영, CQRS S6 실제 롤아웃이 남아 있다.
- 토너먼트 수식/RNG와 stored log prefix byte 패리티가 미결이다.
- 여러 review의 `cleared`는 해당 좁은 build-only slice를 뜻하며, 전체 v1
  완료를 뜻하지 않는다.

## 3. 실행 베이스라인

| 표면 | 실행 결과 | 해석 |
|---|---|---|
| `tools/parity/gate.sh backend` | `BUILD SUCCESSFUL`, XML 515 suites / 4,572 tests / 0 failure / 0 error / 204 skipped | 넓은 회귀 게이트는 green, Docker/Testcontainers 공백 존재 |
| `web/gateway` typecheck | exit 0 | 정적 타입 green |
| `web/game` typecheck + Vitest | 41 files / 212 tests green | 현재 단위/route fixture green |
| `LongSimReplayGateTest` | disabled/skip | 장기 수렴 패리티 증거 없음 |
| Docker compose / live services | 실행 불가 | Docker daemon 정지, 로컬 포트 미기동 |
| PHP 골든 재캡처 | 실행 불가 | 호스트 PHP CLI 부재, Docker 정지 |
| 브라우저 실관측 | 미실행 | 서버 미기동으로 `채점대기` |

프런트 테스트의 styled-jsx 경고와 중복 `-` key 경고는 각각 테스트 변환과
fixture 문제로 격리했다. 현재 확인된 production blocker로 계산하지 않았다.

변경 후에는 같은 표준 backend gate가 `BUILD SUCCESSFUL`, XML 521 suites /
4,585 tests / 0 failures / 0 errors / 205 skipped로 통과했다. `web/game`도
typecheck와 42 files / 216 tests가 통과했다.

첫 변경 후 backend gate는 천도 command matrix의 “성공 fixture”가 실제
거리를 제공하지 않아 11건 RED였다. 비용 검사를 되돌리거나 테스트를
약화하지 않고 해당 유효 fixture에 인접 거리 1을 명시했고, 같은 전체
gate를 다시 실행해 green을 확인했다.

일부 도구 호출 뒤 fablize wrapper가 과거 RED·출력 절단을 일반화한
`tool failure` 알림을 재표시했다. 최종 독립 실행의 실제 exit code,
`BUILD SUCCESSFUL`, XML 집계와 `git diff --check`는 모두 green이므로 이
알림은 제품 실패가 아닌 도구 baseline으로 격리한다.

## 4. 영역별 완성도

| 영역 | 판정 | 핵심 이유 |
|---|---|---|
| RNG/반올림/조사 공용 커널 | 부분 완료 | 단위 골든은 강하지만 전체 실경로가 소비한다는 증거가 부족 |
| 일반·국가 명령 | 미완료 | registry 92/92와 달리 폼 13개, 직접 액션 3개, admission/result UI가 불완전 |
| 월 틱·이벤트 | 미완료 | world scope, 날짜, 외교 Q 순서, betting event, 기본 이벤트 divergence |
| 전투·점령 | 미완료 | 외곽 wrapper가 per-unit pipeline/보너스/순위/외교/로그/이벤트를 잃음 |
| AI | 미완료 | 4층 정책, 인간 휴가 autorun, 월 6/12 수입, 캡처 provenance 미완료 |
| 베팅·경매·투표·유산 등 | 미완료 | same-drain 중복, PHP RNG/로그/후속 이벤트 차이 |
| 재시작·영속성 | 미완료 | loader 결함 일부 수정, JPA read world scope와 복합 ID는 잔존 |
| 프런트 사용자 흐름 | 미완료 | WRONG/PARTIAL/DEAD route와 202 조기 성공 표시가 남음 |
| 운영/배포 | 미완료 | S6 cutover 및 실서비스 E2E 미실행 |

## 5. 이번 감사에서 수정한 확정 버그

아래 변경은 legacy 증거가 명확하고, 기존 아키텍처를 넓히지 않고 고칠 수
있는 결함만 선택했다. 테스트나 골든 기대값을 약화하지 않았다.

### 5.1 multiworld cold boot 혼입과 부대 소실

#### 수정 전

`WorldSnapshotLoader`가 `ng_games`, `ng_old_nations`, 경매, non-inheritance
`game_kv`, `nation_env`, `diplomacy`, `general_access_log`를 `world_id`
없이 읽었다. 또한 `WorldSnapshot.troops`를 항상 빈 목록으로 만들었다.

V32의 복합 world key 계약에서는 다른 월드의 같은 local ID가 합법이므로,
재시작 시 cross-world 혼입, `.singleOrNull()` 실패, map overwrite, 부대
소실이 발생할 수 있다.

#### 수정

- 모든 world-owned cold query에 `world_id = ?`와 `worldId.value`를 배선
- 전역 inheritance KV는 `world_id IS NULL` 유지
- `troop`을 world scope로 적재해 snapshot에 복원
- hot/cold catalog에 troop cohort 등록
- 정적 SQL/binding 계약 테스트와 동일 local ID 2-world IT 추가

변경:

- `app/game-engine/src/main/kotlin/opensamguk/engine/boot/WorldSnapshotLoader.kt`
- `logic/src/main/kotlin/opensamguk/logic/memory/HotColdCatalog.kt`
- `app/game-engine/src/test/kotlin/opensamguk/engine/boot/WorldSnapshotLoaderWorldScopeContractTest.kt`
- `app/game-engine/src/test/kotlin/opensamguk/engine/boot/WorldSnapshotLoaderWorldScopeIT.kt`

### 5.2 `ProfileIconSync` durable inbox 무한 재claim

#### 수정 전

`ProfileIconSyncHandler`는 정상·no-op·idempotent 경로 모두 `null`을
반환했다. dispatcher는 null 결과를 버리고, JDBC flush는 terminal result가
있을 때만 inbox를 `APPLIED/REJECTED`로 전환한다. 결과적으로 lease 만료 뒤
같은 명령이 반복 claim될 수 있었다.

#### 수정

- 모든 정상 경로에서 `profileIconSync`, `ok=true`,
  `commandKind=IMMEDIATE` lifecycle result 반환
- wire serializer에 결과 타입 등록
- 실제 `TurnRunService` flush payload가 `terminalizeInbox=true` 결과를
  포함하는 lifecycle 테스트 추가

### 5.3 `che_천도` 거리·준비 턴·부수효과의 live-path 보정

PHP 정본은 실제 도시 거리로 비용·준비 턴·경험/공헌을 계산하고, 매 poll의
`last천도Trial`, 성공 시 활동점수·로그·정적 이벤트까지 순서대로 실행한다
(`legacy/devsam-core/hwe/sammo/Command/Nation/che_천도.php:99-140,212-232`).

이번 수정은 다음을 같은 daemon 경로에 연결했다.

- 소유 도시 그래프의 수도→목적지 거리를 한 번 계산해 FULL constraint,
  `distance * 2` 준비 턴과 `distance * 10 + 5` 보상에 동일하게 전달
- `Int` shift overflow를 `Long` 포화 계산으로 제거하고, 자원 최대치보다 큰
  비용은 항상 부족으로 판정
- FULL 허용 뒤 `addTermStack` 직전마다
  `nation_env.last천도Trial=[officerLevel, turnTime]` 기록
- 성공 시 수도/capset 변경, 국고·병량 비차감, raw `active_action +1`,
  general/nation/global action·history 5건과 `che_천도` 정적 이벤트를 PHP
  순서로 배출
- nation `power`/`tech`를 수도 변경 전후 보존

`CheondoTest` 5/5와 `ProcessNationCommandCheondoTest` 3/3가 포화 분기,
최초·진행·완료 poll 3회, 로그 scope/order, 활동점수, 정적 이벤트와
power/tech 보존을 검증했다.

다만 대상 args가 없는 available-command PRECHECK/catalog는 거리 50 fallback을
쓸 수 있다. 실제 예약의 FULL 평가는 수정됐지만, UI의 `possible` 표시와 FULL
평가의 동치는 별도 matrix와 PHP live capture가 필요하므로 이 읽기 표면은
`채점대기`다.

### 5.4 AI 요양 임계값 30 → PHP 기본 정책값 10

#### 수정 전

PHP와 `AutorunNationPolicy` 기본값은 10이지만 production `AiTurnAdapter`가
30을 하드코딩했다. 부상 11~30인 NPC가 PHP에서는 요양하고 Kotlin에서는
다른 후보를 선택할 수 있었다.

#### 수정

- adapter가 **기본 `AutorunNationPolicy` 객체**의 `cureThreshold`를 AI input으로
  전달
- 부상 10/11/30 경계와 실제 `ReservedTurnHandler` AI hook 회귀 테스트 추가

여기서 “기본”은 의도적으로 정확한 표현이다. production
`AiTurnAdapter.kt:324-327,411-414`는 `aiOptions`/server KV/nation KV 없이
`AutorunNationPolicy(npcType, tech, develcost)`를 새로 만들고,
`:1684-1688`에서 그 객체의 threshold를 input에 전달한다. PHP는 default 뒤
server policy, nation policy를 순서대로 merge한다
(`legacy/devsam-core/hwe/sammo/AutorunNationPolicy.php:182-215`; 생성 호출은
`GeneralAI.php:120-127`). 따라서 4층 live policy 미적재는 §6.4의 별도
blocker이며, 이 수정으로 해소됐다고 주장하지 않는다.

### 5.5 이벤트 cold-load의 cross-world 실행

#### 수정 전

`EngineEventConfig`가 `event` 테이블 전체를 world 구분 없이 읽어 한 월드의
이벤트가 다른 월드에서 실행될 수 있었다.

#### 수정

- `WHERE world_id = ? ORDER BY id ASC`와 `worldId.value` binding 추가
- 동일 local 환경의 두 월드 중 대상 월드 이벤트만 적재하는 테스트 추가

### 5.6 기밀실·유니크 경매 deep link

#### 수정 전

MainControlBar는 `/game/board?secret=1`,
`/game/auction?type=unique`로 이동하지만 각 페이지는 query를 읽지 않고
항상 회의실·자원 경매로 시작했다.

#### 수정

- URL query를 최초 route state에 반영
- query가 없는 기존 기본값 유지
- 두 실제 route component의 deep-link 회귀 테스트 추가

### 5.7 수정 코드 검증

| 변경 | 회귀 결과 |
|---|---|
| profile wire | 4/4 green |
| 천도 logic | 5/5 green |
| loader catalog/contract/2-world IT | 12 green / Docker IT 1 skip |
| event world scope | 1/1 green |
| profile handler/lifecycle | 10/10 green |
| AI cure threshold | 2/2 green |
| 천도 daemon | 3/3 green |
| board/auction route | 4/4 green |
| 변경 후 backend 표준 gate | 521 suites / 4,585 tests / 0 failure / 0 error / 205 skip |
| 변경 후 `web/game` 전체 | typecheck + 42 files / 216 tests green |

Testcontainers 2-world IT는 Docker가 없어 skip됐으므로 정적 scope 계약과
컴파일은 검증됐지만 실제 PostgreSQL round-trip은 `채점대기`다.

## 6. 릴리스를 계속 막는 확인 결함

이번 감사는 발견한 모든 큰 미완성 영역을 위험한 부분 패치로 덮지 않았다.
아래 항목이 남아 있으므로 수정 6건 뒤에도 v1 완료 판정은 바뀌지 않는다.

**근거 표기.** 각 `[E6.x-y]`는 §6.9의 행으로 해석한다. 모든 행은
`PHP 또는 hwe/ts 정본 path:line → Kotlin/Next path:line → 관찰 방식` 순서다.
`정적대조`는 현재 source의 직접 대조, `실행`은 이 보고서 §3 또는 명시한
테스트 결과, `추론/채점대기`는 아직 PHP 재캡처·Docker·browser로 재현하지
못한 위험 또는 증거 공백이다. 후자는 실행된 버그 재현으로 읽으면 안 된다.

### 6.1 명령·사용자 입력

- `[E6.1-A]` registry 수와 실제 form/intake/terminal-result 도달성의 matrix를
  다시 생성해야 한다. 기존 92/92·70·57 수치는 감사 inventory일 뿐 browser
  실행 증거가 아니다.
- `[E6.1-B]` 숙련전환·장비매매·국기/국호변경 및 군량매매·증여·헌납·발령·몰수·
  물자원조·불가침제의·피장파장·포상은 suffix 기반 form map 밖 또는 generic
  numeric fallback으로 갈 수 있다. 각 실제 PHP arg shape와 UI body를 matrix로
  대조하기 전에는 “완전 no-op” 또는 “오입력”을 개별 확정하지 않는다.
- `[E6.1-C]` `BuildNationCandidate`는 현재 명시적 deny-only handler여서
  PHP의 사전 거병 실행과 동등하지 않다.
- `[E6.1-D]` legacy의 vacation/my-setting 흐름은 존재하지만 Kotlin/Next는
  tournament toggle 외 설정과 vacation/사전거병을 현재 page에서 제공하지
  않는다. 이 항목은 handler 존재 여부까지 포함한 live matrix가 필요하다.
- `[E6.1-E]` PHP는 공개 command whitelist를 명시적으로 검사한다. Kotlin도
  `GameConst` 또는 intake mapper에 든 code만 forecast-reserve한다. 따라서
  이전의 “임의 unknown action fallback이 admission을 넓힌다”는 표현은
  철회한다. 남은 blocker는 두 집합과 terminal deny의 동치가 미검증이라는
  점이다.
- `[E6.1-F]` `CommandModal`은 `AVAILABLE`(202 접수)를 성공 toast/close로
  처리하지만 공용 API contract는 이를 terminal success가 아니라고 정의한다.
  최소 25개라는 수는 재계산 전 추정치이며, 실제 UI 수와 결과 polling 여부는
  browser E2E로 채점대기다.

### 6.2 월 틱·이벤트

- `[E6.2-A]` 월간 date/statistic lifecycle은 PHP와 같은 12개월 live capture가
  없으므로 이전 날짜/bean-captured 상태라는 단정 대신 **채점대기**로 둔다.
- `[E6.2-B]` Q9은 pure function에서 원본 `rows`로 만들고 hook이 Q5/Q7 write 뒤
  그것을 다시 적용하므로, 앞 갱신을 덮을 수 있는 정적 경로가 확인된다.
- `[E6.2-C]` `ProvideNPCTroopLeader`의 final mint는 production context에서 빈
  seam이다. 반면 `UpdateNationLevel`은 context/apply path가 있으므로 그것을
  “완전 no-op”로 묶지 않으며, unique grant의 daemon execution만 별도 채점대기다.
- `[E6.2-D]` default event 행은 Kotlin에서 VERBATIM이라고 주석 처리됐지만 PHP
  install/행 순서/날짜를 재캡처해 비교하지 않았다. 날짜/행 divergence라는
  기존 단정은 capture 전에는 **추론/채점대기**다.
- `[E6.2-E]` `OpenNationBetting`은 후보 상세·후속 `FinishNationBetting` event
  등록·bonus·history/message를 PHP처럼 완결하지 않는다. 특히 Kotlin의 event
  insertion은 TODO/commented-out 상태다.
- `[E6.2-F]` 국력 계산의 `killcrew_person`/`deathcrew_person` 입력은 live hook에서
  0으로 고정되고, invader start trigger는 source상 latent다. LostUniqueItem 등
  인접 event family의 도달성은 별도 실행 matrix가 필요하다.

`OpenNationBetting`은 typed event context와 recorder intent를 포함한 별도
세로 슬라이스로 이식해야 한다. env 키 몇 개만 넣는 부분 수정은 오히려
false-green을 만든다.

### 6.3 전투·점령

- `[E6.3-A]` outer `processWar`는 defender `WarUnitGeneral`에도 attacker와 같은
  `pipeline` 인자를 전달한다. PHP의 per-side module source와의 matrix가 필요하다.
- `[E6.3-B]` `ProcessWar`의 `attackerCityLevel=9`/`attackerIsCapital=false` 기본값은
  default caller가 override하지 않는다. 출발 도시 level/capital 보너스는
  goldens로 확인해야 한다.
- `[E6.3-C]` `warnum/killnum/deathnum/occupied` 자체는 war state에 존재하지만
  loader→resolve→flush→rank read round-trip 증거가 없다. 기존의 “반드시 flush되지
  않는다”는 단정 대신 이 경계를 blocker로 둔다.
- `[E6.3-D]` 현재 caller는 attacker/defender tech를 전달한다. 그러므로 “국가 기술이
  빠졌다”는 기존 문장은 철회하고, 양방향 casualty/rank/nation delta의 live capture
  미실행만 남긴다.
- `[E6.3-E]` diplomacy `dead`의 schema/누계 의미는 PHP row와 PostgreSQL row를
  같은 전투 fixture로 비교하지 않아 **추론/채점대기**다.
- `[E6.3-F]` 점령의 `OccupyCity`/`DestroyNation` event slots는 explicit no-op으로
  남아 있고 scout/NPC 합류는 message/reservation seam만 남긴다. 이 외곽 효과와
  log scope/order는 live daemon 결과로 검증해야 한다.
- `[E6.3-G]` 격리된 23개 battle/conquest test green은 커널 일부만 증명하며 위
  outer path를 증명하지 않는다.

### 6.4 AI

- `[E6.4-A]` PHP의 default→server→nation→general policy composition은 production
  adapter가 읽지 않는다. §5.4의 cure threshold 수정은 기본 policy object만
  사용하므로 이 blocker를 닫지 않는다.
- `[E6.4-B]` human vacation/`autorun_limit`은 legacy UI/API에 있으나 현재 read
  surface가 원천 부재를 명시해 null로 둔다. write/AI path까지의 matrix가 없다.
- `[E6.4-C]` 6/12 rates/promotion hook은 실제 income inputs 대신 derived defaults를
  남긴다고 adapter가 명시한다.
- `[E6.4-D]` AI capture fixture의 nation/family provenance는 PHP 재캡처가 불가했던
  실행 환경에서는 증명할 수 없다. 이 줄은 기존 fixture가 틀렸다는 실행 판정이
  아니라 provenance 재검증 요구다.
- `[E6.4-E]` G12 deny log는 generic unit test가 존재해도 해당 PHP branch의 byte
  capture가 없다. “미배선”이라고 확정하지 않고 **채점대기**로 유지한다.
- `[E6.4-F]` 12개월 first-divergence gate는 disabled 상태다.

### 6.5 부가 시스템

- `[E6.5-A]` nation betting의 Kotlin open action은 `FinishNationBetting` event
  insertion을 TODO로 남긴다. finish leaf 자체가 있어도 live lifecycle closure는
  보장되지 않는다.
- `[E6.5-B]` select-pool은 후보 refresh에는 terminal-result poll을 쓰지만
  pick/update submit은 202 접수만 표시한다. `killturn=5`, owner name, logs,
  custom option 등은 PHP scenario와 browser matrix에서 확인해야 한다.
- `[E6.5-C]` 유산·투표의 same-drain 중복 차감/보상은 아직 재현 fixture가 없다.
  따라서 위험으로 격리하되, 이 report는 이중 차감이 이미 관측됐다고 주장하지
  않는다.
- `[E6.5-D]` Kotlin tournament match winner는 score 식으로 결정된다. PHP tournament
  RNG source/capture와의 draw-for-draw proof가 없으므로 v1 blocker다.
- `[E6.5-E]` auction/diplomacy/mailbox는 각각 일부 handler/read surface가 있으나
  log/message/order/cursor의 end-to-end PHP matrix가 없다. 기존의 포괄적인
  “다르다” 문장은 live case별 evidence를 얻을 때까지 **추론/채점대기**다.

### 6.6 world-scoped read와 재시작 경계

`WorldSnapshotLoader`의 cold path는 이번 수정으로 world scope를 갖지만,
다음 JPA read repository는 `JpaRepository<…, Int>`와 world 없는 finder를
공개한다: `AuctionRepository`, `AuctionBidRepository`, `BettingRepository`,
`BoardPostRepository`, `GameKvRepository`, `DiplomacyRepository` `[E6.6-A]`.

이는 PHP single-game 동작의 직접 divergence가 아니라 v1 multiworld 격리 계약의
**아키텍처 추론**이다. raw repository를 private으로 두고 process `WorldId`를
강제하는 facade foundation과 same-local-ID 2-world IT가 필요하다. query 하나만
고치면 상속된 `findAll/findById` escape hatch가 남는다.

### 6.7 프런트 도달성

- `[E6.7-A]` spy/query 및 global menu의 legacy route 의미는 Next route와
  browser session으로 대조하지 못했다. 따라서 “query가 무시된다” 또는
  “global board가 국가 회의실로 간다”는 이전 단정은 matrix 항목으로 낮춘다.
- `[E6.7-B]` 왕조/황제 목록은 live winner 하나를 id=1로 합성하고 detail endpoint는
  명시적으로 404다. link를 렌더하는 Next detail page까지 포함해 실제 DEAD route다.
- `[E6.7-C]` 내 정보 설정은 다섯 개 이상을 literal `-`로 렌더하며 legacy의
  vacation/set-my-setting flow를 제공하지 않는다.
- `[E6.7-D]` select-pool pick/update의 terminal result 확인은 없다. custom
  option 값의 legacy 동치도 browser/PHP case가 필요하다.
- `[E6.7-E]` mailbox contact/read cursor와 연감의 global history/action은
  source-level로 완결성을 확인하지 못했고, 세력일람의 ambassador/auditor는
  backend가 명시적으로 빈 값으로 격리한다. 이 묶음은 **추론/채점대기**다.
- `[E6.7-F]` 정적 페이지 계정의 core-live 후보 21개, PARTIAL/WRONG 29개,
  DEAD 1개는 browser 인증이 아니다. 현 환경에서 browser가 미기동이므로
  page별 terminal-result E2E가 남는다.

### 6.8 로그와 운영

- `[E6.8-A]` PHP `ActionLogger`는 scope별 prefix/date format과 flush 순서를
  정의한다. Kotlin은 log type token을 일부 전달하지만 stored-row prefix/date/
  scope/category/order를 PHP golden으로 비교하지 않았다.
- `[E6.8-B]` CQRS S6 production rollout, 실제 Docker smoke, browser E2E, PHP
  재캡처는 §3의 실행 베이스라인에서 모두 미실행이다. build-only pass를 이
  운영 증거로 대체할 수 없다.

### 6.9 근거 지도 (2026-07-26 source snapshot)

아래는 §6의 각 키에 대한 재현 가능한 source map이다. `정적대조`는 해당 줄의
현재 구현을 직접 읽은 결과이고, `실행`은 §3에 적은 실제 test/환경 결과다.
`추론/채점대기`는 source가 보이는 위험 또는 증거 공백이지, PHP live case에서
이미 발생한 divergence라는 뜻이 아니다. 경로는 모두 repository root 기준이다.

| 키 | PHP 또는 hwe/ts 정본 (`path:line`) | Kotlin/Next (`path:line`) | 관찰·판정 |
| --- | --- | --- | --- |
| E6.1-A | `legacy/devsam-core/hwe/sammo/API/Command/ReserveCommand.php:16-55` | `logic/src/main/kotlin/opensamguk/logic/actions/CommandRegistry.kt:121-224`; `web/game/components/CommandModal.tsx:152-201` | **정적대조/채점대기**: registry·modal은 읽었지만 command별 form→intake→terminal matrix를 실행 생성하지 않았다. |
| E6.1-B | `legacy/devsam-core/hwe/sammo/API/Command/ReserveCommand.php:39-55` | `web/game/lib/command-arg-types.ts:10-62`; `web/game/components/CommandModal.tsx:292-332` | **정적대조/채점대기**: suffix map 밖은 generic numeric fallback이 가능하다. 개별 PHP arg shape의 오입력·no-op은 아직 실행 확정하지 않았다. |
| E6.1-C | `legacy/devsam-core/hwe/sammo/API/General/BuildNationCandidate.php:34-96` | `app/game-engine/src/main/kotlin/opensamguk/engine/intake/BuildNationCandidateHandler.kt:13-32` | **정적대조**: PHP 실행 경로와 달리 Kotlin handler는 foundation stub/deny를 반환한다. |
| E6.1-D | `legacy/devsam-core/hwe/ts/myPage.ts:193-239` | `logic/src/main/kotlin/opensamguk/logic/actions/intake/TournamentEnroll.kt:4-19`; `web/game/app/game/my/page.tsx:111-118` | **정적대조/채점대기**: tournament 외 setting 범위와 literal `-` 렌더는 보이지만, vacation까지 포함한 handler/live matrix는 없다. |
| E6.1-E | `legacy/devsam-core/hwe/sammo/API/Command/ReserveCommand.php:47-49` | `app/game-api/src/main/kotlin/opensamguk/gameapi/web/CommandController.kt:101-121,400-406` | **정적대조/채점대기**: 양쪽 모두 허용 집합을 제한한다. 임의 unknown admission 확대 주장은 철회했고, 두 집합과 terminal deny의 동치만 미검증이다. |
| E6.1-F | `legacy/devsam-core/hwe/sammo/API/Command/ReserveCommand.php:47-55` | `web/game/components/CommandModal.tsx:168-201`; `web/game/lib/api.ts:395-408,790-799` | **정적대조/채점대기**: modal은 `AVAILABLE` 뒤 toast/close, API helper는 202을 terminal success가 아니라고 설명한다. 실제 browser polling 수는 미측정이다. |
| E6.2-A | `legacy/devsam-core/hwe/func_gamerule.php:259-415` | `app/game-engine/src/main/kotlin/opensamguk/engine/run/MonthlyPostUpdateHook.kt:54-110,192-194` | **추론/채점대기**: state update 코드만 대조했으며 12개월 PHP/Kotlin capture는 없다. |
| E6.2-B | `legacy/devsam-core/hwe/func_gamerule.php:336-406` | `logic/src/main/kotlin/opensamguk/logic/world/PostUpdateMonthly.kt:246-307`; `app/game-engine/src/main/kotlin/opensamguk/engine/run/MonthlyPostUpdateHook.kt:155-184` | **정적대조**: Q5/Q7 후 hook이 Q9의 원본 `rows` 결과를 적용하는 순서가 직접 확인된다. |
| E6.2-C | `legacy/devsam-core/hwe/sammo/Event/Action/ProvideNPCTroopLeader.php:29-91` | `logic/src/main/kotlin/opensamguk/logic/world/ProvideNPCTroopLeader.kt:104-143`; `app/game-engine/src/main/kotlin/opensamguk/engine/world/WorldActionContext.kt:596-598`; `logic/src/main/kotlin/opensamguk/logic/world/UpdateNationLevel.kt:398-426` | **정적대조**: NPC mint seam은 빈 구현이다. `UpdateNationLevel`에는 별도 apply path가 있어 동일한 no-op으로 판정하지 않았고 daemon grant 실행은 채점대기다. |
| E6.2-D | `legacy/devsam-core/hwe/sammo/GameConstBase.php:447-531` | `logic/src/main/kotlin/opensamguk/logic/event/EventStore.kt:150-156,230-236` | **추론/채점대기**: Kotlin 주석의 VERBATIM 선언은 보이나 PHP install row/date/order 재캡처가 없다. |
| E6.2-E | `legacy/devsam-core/hwe/sammo/Event/Action/OpenNationBetting.php:22-151`; `legacy/devsam-core/hwe/sammo/Event/Action/FinishNationBetting.php:20-72` | `logic/src/main/kotlin/opensamguk/logic/event/OpenNationBetting.kt:39-109`; `logic/src/main/kotlin/opensamguk/logic/event/FinishNationBetting.kt:37-94` | **정적대조**: Kotlin open의 event insertion은 TODO/commented-out이고, finish leaf가 있어도 lifecycle closure가 되지 않는다. |
| E6.2-F | `legacy/devsam-core/hwe/func_gamerule.php:288-310` | `app/game-engine/src/main/kotlin/opensamguk/engine/run/MonthlyPostUpdateHook.kt:66-110`; `app/game-engine/src/main/kotlin/opensamguk/engine/world/WorldEventContextFactory.kt:89-92` | **정적대조/채점대기**: hook 입력의 zero 값과 invader start trigger 부재는 확인했지만 인접 event family 전체 도달성은 실행하지 않았다. |
| E6.3-A | `legacy/devsam-core/hwe/sammo/Command/General/che_출병.php:225-259` | `logic/src/main/kotlin/opensamguk/logic/actions/war/CheChulbyeong.kt:242-279`; `logic/src/main/kotlin/opensamguk/logic/war/ProcessWar.kt:103-112` | **정적대조/채점대기**: outer caller가 defender에도 같은 `pipeline`을 준다. PHP per-side module matrix 전에는 effect divergence를 확정하지 않는다. |
| E6.3-B | `legacy/devsam-core/hwe/sammo/Command/General/che_출병.php:225-259` | `logic/src/main/kotlin/opensamguk/logic/war/ProcessWar.kt:71-97`; `logic/src/main/kotlin/opensamguk/logic/actions/war/CheChulbyeong.kt:258-279` | **정적대조**: caller가 override하지 않는 `attackerCityLevel=9`/`attackerIsCapital=false` default가 확인된다. |
| E6.3-C | `legacy/devsam-core/hwe/sammo/Command/General/che_출병.php:225-259` | `logic/src/main/kotlin/opensamguk/logic/war/WarUnitGeneral.kt:322-349`; `logic/src/main/kotlin/opensamguk/logic/war/WarUnitGeneralState.kt:156-159` | **추론/채점대기**: state/rank key는 있으나 loader→resolve→flush→rank read를 잇는 live round-trip fixture가 없다. |
| E6.3-D | `legacy/devsam-core/hwe/sammo/Command/General/che_출병.php:225-259` | `logic/src/main/kotlin/opensamguk/logic/actions/war/CheChulbyeong.kt:265-277` | **정적대조**: attacker/defender tech는 caller가 전달한다. 기술 누락 주장은 철회하며 bilateral delta capture만 남긴다. |
| E6.3-E | `legacy/devsam-core/hwe/sammo/Command/General/che_출병.php:225-259` | `app/game-engine/src/main/kotlin/opensamguk/engine/boot/WorldSnapshotLoader.kt:499-514` | **추론/채점대기**: `dead` mapping이 존재해도 PHP/PG row의 누계 의미를 같은 전투 fixture로 비교하지 않았다. |
| E6.3-F | `legacy/devsam-core/hwe/sammo/Command/General/che_출병.php:225-259` | `logic/src/main/kotlin/opensamguk/logic/war/ConquerCity.kt:45-68,171-207,372-378` | **정적대조**: `OccupyCity`/`DestroyNation` slots는 explicit no-op이며 scout/NPC seam도 message/reservation 수준에 남아 있다. |
| E6.3-G | `legacy/devsam-core/hwe/sammo/Command/General/che_출병.php:225-259` | `logic/src/main/kotlin/opensamguk/logic/war/ProcessWar.kt:71-167` | **실행/채점대기**: §3의 battle/conquest 23 test green은 이 outer command/daemon scenario까지 검증한 결과가 아니다. |
| E6.4-A | `legacy/devsam-core/hwe/sammo/AutorunNationPolicy.php:182-215`; `legacy/devsam-core/hwe/sammo/GeneralAI.php:120-127` | `app/game-engine/src/main/kotlin/opensamguk/engine/turn/AiTurnAdapter.kt:324-327,411-414,1684-1688`; `logic/src/main/kotlin/opensamguk/logic/ai/AutorunNationPolicy.kt:34-45,255-281` | **정적대조**: policy class는 layer를 지원하지만 production adapter는 기본 object만 만들며 server/nation/general values를 주입하지 않는다. |
| E6.4-B | `legacy/devsam-core/hwe/ts/myPage.ts:193-239` | `app/game-api/src/main/kotlin/opensamguk/gameapi/controller/ChiefCenterController.kt:47,156-157`; `web/game/lib/types.ts:698-699` | **정적대조/채점대기**: read contract는 `autorunLimit`을 null/미구현으로 남긴다. write→AI path의 live matrix는 없다. |
| E6.4-C | `legacy/devsam-core/hwe/sammo/GeneralAI.php:120-132` | `app/game-engine/src/main/kotlin/opensamguk/engine/turn/AiTurnAdapter.kt:811-815` | **정적대조**: adapter 주석이 6/12 rate/promotion에 derived defaults를 남긴다고 명시한다. |
| E6.4-D | `legacy/devsam-core/hwe/sammo/GeneralAI.php:120-127` | `app/game-engine/src/main/kotlin/opensamguk/engine/turn/AiTurnAdapter.kt:324-327,411-414` | **추론/채점대기**: PHP 재캡처가 불가했던 환경에서는 fixture의 nation/family provenance를 검증할 수 없다. 이것은 fixture divergence의 실행 판정이 아니다. |
| E6.4-E | `legacy/devsam-core/hwe/sammo/GeneralAI.php:3767-3775` | `logic/src/main/kotlin/opensamguk/logic/ai/GeneralAI.kt:238-241`; `logic/src/test/kotlin/opensamguk/logic/ai/ChooseNationTurnTest.kt:213-219` | **실행/채점대기**: generic deny-log test는 있으나 해당 PHP branch의 byte capture가 없다. |
| E6.4-F | `legacy/devsam-core/hwe/sammo/GeneralAI.php:120-127` | `app/game-engine/src/test/kotlin/opensamguk/engine/golden/LongSimReplayGateTest.kt:47-50,86-89` | **실행**: 12개월 first-divergence gate는 disabled로 선언돼 있다. |
| E6.5-A | `legacy/devsam-core/hwe/sammo/Event/Action/OpenNationBetting.php:22-151`; `legacy/devsam-core/hwe/sammo/Event/Action/FinishNationBetting.php:20-72` | `logic/src/main/kotlin/opensamguk/logic/event/OpenNationBetting.kt:39-109`; `logic/src/main/kotlin/opensamguk/logic/event/FinishNationBetting.kt:37-94` | **정적대조**: open path의 TODO event insertion 때문에 finish handler만으로 live lifecycle을 닫지 못한다. |
| E6.5-B | `legacy/devsam-core/hwe/ts/select_general_from_pool.ts:84-162` | `web/game/app/game/select-pool/page.tsx:38-116` | **정적대조**: refresh는 terminal result를 poll하지만 pick/update는 queued message만 표시한다. PHP scenario/browser 결과는 채점대기다. |
| E6.5-C | `legacy/devsam-core/hwe/sammo/InheritancePointManager.php:223-247`; `legacy/devsam-core/hwe/sammo/API/Vote/Vote.php:57-65` | `app/game-engine/src/main/kotlin/opensamguk/engine/intake/VoteHandler.kt:46-52,279-314` | **추론/채점대기**: same-drain joint fixture가 없으며, 이 report는 duplicate charge/reward가 관측됐다고 주장하지 않는다. |
| E6.5-D | `legacy/devsam-core/hwe/sammo/TurnExecutionHelper.php:504-508` | `logic/src/main/kotlin/opensamguk/logic/tournament/ProcessTournament.kt:180-197` | **추론/채점대기**: Kotlin score식은 확인했으나 PHP tournament RNG draw source/capture가 없어 draw-for-draw를 판정할 수 없다. |
| E6.5-E | `legacy/devsam-core/hwe/sammo/Auction.php:282-344`; `legacy/devsam-core/hwe/sammo/Message.php:151-192` | `app/game-api/src/main/kotlin/opensamguk/gameapi/controller/MailboxController.kt:428-453` | **추론/채점대기**: handler/read surface 존재만 확인됐고 auction·diplomacy·mailbox의 message/order/cursor PHP E2E matrix는 없다. |
| E6.6-A | `legacy/devsam-core/hwe/sammo/Auction.php:67-75` | `infra/src/main/kotlin/opensamguk/infra/read/AuctionRepository.kt:17-49`; `infra/src/main/kotlin/opensamguk/infra/read/AuctionBidRepository.kt:16-41`; `infra/src/main/kotlin/opensamguk/infra/read/BettingRepository.kt:19-78`; `infra/src/main/kotlin/opensamguk/infra/read/BoardPostRepository.kt:16-19`; `infra/src/main/kotlin/opensamguk/infra/read/GameKvRepository.kt:16-19`; `infra/src/main/kotlin/opensamguk/infra/read/DiplomacyRepository.kt:19-37` | **아키텍처 추론/채점대기**: PHP는 single-game 정본이라 multiworld 대조 대상이 아니다. Kotlin raw repositories의 world 없는 inherited finder가 v1 격리 계약을 우회할 수 있으며 2-world IT가 필요하다. |
| E6.7-A | `legacy/devsam-core/hwe/ts/PageBoard.vue:98-145`; `legacy/devsam-core/hwe/sammo/API/Global/GeneralList.php:68-70,148-152` | `web/game/lib/global-menu-fixture.ts:24-26`; `web/game/app/game/board/page.tsx:166-186,219-284` | **추론/채점대기**: legacy route/filter 의미와 Next route를 browser session으로 대조하지 않았다. 기존 spy/global-board 단정은 철회했다. |
| E6.7-B | `legacy/devsam-core/hwe/ts/PageHistory.vue:1-30` | `web/game/app/game/rankings/emperor/page.tsx:18-50`; `web/game/app/game/rankings/emperor/[id]/page.tsx:14-52`; `app/game-api/src/main/kotlin/opensamguk/gameapi/controller/RankingController.kt:69-75`; `app/game-api/src/main/kotlin/opensamguk/gameapi/rank/RankReadService.kt:409-434` | **정적대조**: list는 winner id=1을 합성하고 rendered detail link의 endpoint는 404다. |
| E6.7-C | `legacy/devsam-core/hwe/ts/myPage.ts:193-239` | `web/game/app/game/my/page.tsx:111-118` | **정적대조**: five-plus settings가 literal `-`이며 legacy vacation/set-my-setting flow가 화면에 없다. |
| E6.7-D | `legacy/devsam-core/hwe/ts/select_general_from_pool.ts:84-162` | `web/game/app/game/select-pool/page.tsx:38-116` | **정적대조/채점대기**: pick/update submit 뒤 terminal polling이 없고 custom option 동치는 browser/PHP case가 필요하다. |
| E6.7-E | `legacy/devsam-core/hwe/sammo/Message.php:151-222`; `legacy/devsam-core/hwe/sammo/GeneralAI.php:3996-3999` | `web/game/app/game/mailbox/page.tsx:48-64`; `app/game-api/src/main/kotlin/opensamguk/gameapi/rank/RankReadService.kt:270-315` | **추론/채점대기**: mailbox/history complete flow는 source만으로 닫히지 않았고 ambassador/auditor는 backend가 빈 값으로 격리한다. |
| E6.7-F | `legacy/devsam-core/hwe/ts/PageHistory.vue:1-30` | `web/game/app/game/rankings/emperor/page.tsx:18-50`; `web/game/app/game/select-pool/page.tsx:38-116` | **정적 inventory/채점대기**: page 수는 browser 인증이 아니며 현 환경에는 browser runtime evidence가 없다. |
| E6.8-A | `legacy/devsam-core/hwe/sammo/ActionLogger.php:15-39,80-116,119-258` | `logic/src/main/kotlin/opensamguk/logic/event/EventAction.kt:116-132,269-272`; `app/game-engine/src/main/kotlin/opensamguk/engine/run/MonthlyPostUpdateHook.kt:162-176` | **정적대조/채점대기**: token 전달은 보이나 stored prefix/date/scope/category/order의 PHP golden byte compare는 없다. |
| E6.8-B | `legacy/devsam-core/hwe/sammo/ActionLogger.php:80-116` | `app/game-engine/src/test/kotlin/opensamguk/engine/golden/LongSimReplayGateTest.kt:47-50,86-89` | **실행/운영 채점대기**: disabled long-sim과 §3의 Docker/browser/PHP-capture 미실행은 production rollout 증거가 아니다. 이는 PHP source divergence가 아닌 운영 gate다. |

## 7. 문서 정합성 감사

문서 388개는 역사 기록으로 유용하지만, 상태 문서끼리 시점과 범위가
섞여 있다.

- 초기 migration 설계는 Kakao/direct API/구형 구조를 포함해 현재 F0와 다르다.
- 일부 review의 `cleared`는 build-only slice인데 전체 완료처럼 읽힐 수 있다.
- five-stat 문서는 “승인 대기”와 “승인됨”이 한 문서에 공존한다.
- log prefix 명세는 계약 결정과 P0~P3가 미완료다.
- v1 완료 기준 문서는 스스로 `draft-gate`, long-sim blocked,
  web/prod partial을 기록한다.
- v2 문서의 “설계 승인/독립 채점 완료”는 구현 완료가 아니다.

따라서 현행 상태 판정은 이 보고서의 실코드/실게이트 결과를 우선하고,
과거 `cleared` 표기는 해당 문서가 명시한 좁은 범위로만 해석해야 한다.

## 8. v1 완료로 바꾸기 위한 최소 게이트

다음 조건을 모두 만족하기 전에는 v1 동등 완료로 표시하지 않는다.

1. PHP 93파일/92 고유 명령의 **사용자 입력 → 예약/즉시 intake → 데몬 →
   JDBC flush → terminal 결과 → UI** 교차 계층 matrix 통과.
2. 전투·점령의 숫자, RNG draw stream, rank/nation/diplomacy delta,
   ordered typed log/event/message를 PHP 골든으로 검증.
3. 월 이벤트와 AI의 실제 production adapter를 PHP 12개월 이상 재생하고
   disabled long-sim 제거.
4. 모든 world-owned cold/JPA read를 동일 local ID 2-world IT로 검증.
5. 문서에 PARTIAL/DEAD로 분류된 실제 사용자 route를 live browser와
   daemon terminal 결과까지 검증.
6. Docker smoke, restart round-trip, S6 cutover rehearsal, PHP 골든 재캡처를
   실행하고 `채점대기`를 해소.
7. 저장 로그의 prefix/date/scope/category/order byte 계약을 확정하고
   회귀 골든을 활성화.

## 9. 최종 결론

버전 1은 레거시와 동등한 기능으로 완성되지 않았다. 핵심 엔진 기반과
많은 세로 슬라이스는 가치 있게 구현되어 있고 대규모 회귀 테스트도
green이지만, 사용자가 실제로 밟는 경로와 데몬 부수효과, 재시작 격리,
전투/월 이벤트/AI 장기 수렴을 포함하면 명확한 버그와 미구현이 남아 있다.

이번 감사에서는 안전하게 닫을 수 있는 6개 결함을 코드와 회귀 테스트로
수정했다. 나머지는 이 보고서의 release blocker로 유지한다. 큰 미완성
기능을 상수 응답, no-op hook, 좁은 단위 테스트로 “완료” 처리해서는 안 된다.
