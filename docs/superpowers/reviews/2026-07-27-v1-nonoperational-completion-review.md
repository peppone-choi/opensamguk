# v1 비운영 감사 종결 검토

Scope: app/, common/, infra/, logic/, tools/, web/

- 최초 종결일: 2026-07-29 (historical snapshot)
- 최종 사후 parity review: 2026-07-30
- 범위: 2026-07-26 v1 레거시 동등성 감사 §6.1–§6.8과 §8의 **비운영**
  차단 항목
- 제외: CQRS S6, canary/expand/backfill, capacity/admission, live EC2,
  production deploy/cutover, v2
- 정본: PHP `legacy/devsam-core` commit
  `4de7ebec17a722d516608dbb987467f1a451dada`; 프런트 흐름은 `hwe/ts/`

## 판정

Verdict: CLEARED

이 판정은 v1의 비운영 감사 범위에 한정한다. 7월 26일의 source-map과
RED/`채점대기` 이력은 삭제하지 않았으며, 각 결함을 PHP capture·실제 daemon
경로·local Docker 브라우저 관측으로 다시 채점했다. 프로덕션 활성화는 이
검토의 합격 항목이 아니며, 완료라고 주장하지 않는다.

### 2026-07-30 사후 scope 검토 정정

7월 29일 `cleared` 뒤 final reviewer가 `SelectPool`·`VotePoll`·
`DiplomacyLetter` read의 unscoped escape hatch를 먼저 발견했다. V32의 복합
world key가 row identity를 보호해도, local-ID만 조건으로 읽으면 다른 world의
같은 local ID를 가져올 수 있다.

수정은 outer query와 nested query 모두에 `WorldId`와 `world_id` predicate/
binding을 요구하고, process `WorldId`를 제공하는 중앙 scoped beans만 소비하게
하는 것이다. same-local-ID 두 world regression으로 해당 경계를 재발 방지했다.
`origin/main`에서 유입된 `OPENSAM-149 (OP149) Rehydrate` 표기는
superseded·not-wired historical quarantine으로만 남긴다. 현재 v1 종료/rehydrate
증거 또는 활성 blocker로 연결하지 않았다. 이 보정 뒤 최종 parity review는
**`CLEARED`**다.

이 결과는 git action 전에도 유효한 v1 비운영 release-candidate 증거다. 단,
commit·push·merge·deploy 권한이나 실행을 뜻하지 않으며 S6/production cutover를
포함하지 않는다.

독립 최종 코드 검토 원문은
`.omo/evidence/v1-final-code-review.md`에
있고 `codeQualityStatus: CLEAR`, `recommendation: APPROVE`, blockers none을
기록한다.

## 종결한 핵심 결함

| 영역 | 수정/종결 내용 | 관측 증거 |
| --- | --- | --- |
| 명령과 UI terminal 경계 | public 92-command matrix와 ordered 복합 인자 13종을 PHP와 대조했다. `CommandModal`·select-pool은 HTTP 202 접수를 성공으로 닫지 않고 terminal result를 기다린다. | PHP matrix, RTL, local Docker의 `202 → RESOLVED`/403 거절 |
| 월간·이벤트·AI | 월간 순서, event lifecycle, four-layer AI 입력과 raw income 반올림 경계를 고쳤다. | schema 4 PHP 12개월·36순 A/B capture 및 Kotlin exact replay |
| 전투·점령·저장 로그 | per-side 전투/점령 후속 효과와 flush/read 경계를 연결하고 sortie·점령·국가베팅·저장 로그 PHP capture를 두 번 비교했다. | 4 families / 8 artifacts A/B byte-identical, backend gate |
| 부가 시스템 | betting, select-pool, vote/inheritance same-drain overlay, tournament PHP-MT/대진, auction/diplomacy/mailbox lifecycle을 terminal/부수효과까지 닫았다. | PHP tournament capture, focused regression, local browser surface |
| world scope·재시작 | world-owned side read의 탈출구를 제거하고, cold/restart 경계와 36순 flush-retry를 실제 경로에서 확인했다. | same-local-ID/read contracts, `V1CalendarFlushRecoveryTest`, local Docker restart |
| `MessageRepository` 부팅 결함 | 엔진 daemon이 side-read `MessageRepository` bean을 얻지 못해 intake가 처리되지 않던 결함을 shared scoped bean으로 고쳤다. | real Spring Boot + Testcontainers `EmptyWorldBootIT`, runtime join terminal |
| Docker E2E 동기화/격리 | 이전의 GET/POST 응답 혼동과 오래된 Compose volume catch-up을 제거했다. POST join의 request id, terminal `RESOLVED`, `hasGeneral`, 격리 Compose project, auth flag restore/cleanup을 강제했다. | runtime9 Playwright 1 passed / 0 unexpected, retained JSON·DOM·cleanup artifacts |
| 36순 제품 규칙 | v1은 PHP 월 capture를 3순 cadence로 재생한다. phase 1에서만 monthly, phase 2/3도 live date를 전진하고 restart/flush retry가 같은 결과를 유지한다. | ADR-LITE-024, production `TurnRunService` 36 phase test |

## 확인한 증거

| 검증 표면 | 결과 | 증거 위치 |
| --- | --- | --- |
| PHP schema 4 12개월 exact replay | fresh PHP 8.3/MariaDB 11.4 A/B byte-identical; 7,428 handled command drain; Kotlin replay 1/0/0/0 | `.omo/evidence/v1-ai-production/schema4-12month-exact-final-report.md`, `diplomacy-identity-12month-authoritative-replay4.xml` |
| 표준 backend gate | 최신 canonical: 552 suites / 4,758 tests / failure·error 0; known `LongSim` skip 1 | 2026-07-30 final gate evidence |
| 영향 backend 재검증 | fresh 237 suites / 1,366 tests green. Golden은 current green이지만 logic task는 `UP-TO-DATE`라 fresh logic 재실행으로 과장하지 않음 | 2026-07-30 final gate evidence |
| 엔진 wiring/36순 재시작 | `EmptyWorldBootIT` 1/0/0/0, `MessageRepositoryWiringTest` 1/0/0/0, `V1CalendarFlushRecoveryTest` 2/0/0/0 | `app/game-engine/build/test-results/test/` 및 `.omo/evidence/v1-final/new-engine-tests/focused.log` |
| 프런트 | `web/game` typecheck + 46 files / 227 tests, `git diff --check` green | 2026-07-30 final frontend evidence |
| local Docker 수용 | corrected final: 다섯 image sequential build green, 8 health green, Playwright 1 passed (`241634ms`), join `RESOLVED`/`ok=true`/general `1230`, 정확히 14 DOM, restart 뒤 general/result/repository `200`, auth `false|false` 복원, final project containers 0 | 2026-07-30 corrected local gate evidence |
| 독립 parity review | initial scope finding 보정 후 `CLEARED` | 이 문서의 사후 scope 검토 |

프런트의 마지막 `my-boss` 회귀는 제품의 `destGeneralID` 변환 결함이 아니라
React 19/jsdom에서 동기 `fireEvent` 다음 click이 이전 closure를 호출한 테스트
플래키였다. `userEvent`를 await해 실제 사용자 순서를 관측했고, `43` ID
assertion은 유지했다. 제품 코드를 우회하거나 테스트 기대값을 약화하지 않았다.

## Agent OS 전체-worktree 검증 (2026-07-29 historical snapshot)

cross-agent checker는 `cleared`와 증빙이 있는 `quarantined-with-proof`의
서로 분리된 Scope coverage를 union으로 합치도록 수정됐다. 이 수정 뒤
`scripts/agent/verify-changes.sh --run`을 정확히 한 번 재실행했고, 해당
cross-agent finding은
[`2026-07-29 scope-union 독립 검토`](2026-07-29-cross-agent-scope-union-review.md)의
`cleared` 판정으로 해소됐다.

- Gradle 5개 모듈: `BUILD SUCCESSFUL in 13m 27s`, 29 actionable tasks executed;
  최종 JUnit XML 551 suites / 4,755 tests / failure 0 / error 0 / skip 1
  (기존 long-sim 격리).
- `web/game`: typecheck와 46 files / 227 tests 통과. Agent OS contract 및
  diff/whitespace 검증도 PASS.
- strict checker: error 1 / warning 0. exit 1의 유일한 원인은 수정하지 않은
  사용자 소유 `.codex/config.toml` 최상위 personal model pin이다.
- 증거: [verify-changes.log](../../../.omo/evidence/v1-final/verify-changes-final2/verify-changes.log),
  [exit-code.txt](../../../.omo/evidence/v1-final/verify-changes-final2/exit-code.txt).

따라서 이 7월 29일 strict 결과는 당시 whole-worktree hygiene 기록일 뿐이다.
7월 30일 현재 v1 parity release-candidate 증거의 blocker나 current strict
green 주장으로 읽지 않는다. `.codex/config.toml`은 이 작업에서 건드리지 않았고,
이 review는 git action을 승인하지 않는다.

## 남은 경계와 안전 규칙

- **미수행:** production/S6 rollout, production data 변경, production deploy/cutover.
- **수행하지 않음:** commit, push, merge, external tracker write, data delete,
  secret access, legacy/golden write, golden/test weakening.

## 문서 입력 경계

7월 26일 감사는 동결 manifest의 388개 `docs/` 파일을 모두 읽어 입력으로
삼았다. 이 review와 runtime evidence는 동결 뒤 생성된 산출물이며, 388개라는
과거 입력 수에 소급 포함하지 않는다. 원래의 발견·source map은
[`v1 레거시 동등성 감사`](../research/2026-07-26-v1-legacy-equivalence-audit.md)의
2026-07-30 사후 검토 부록과 2026-07-29 historical snapshot 본문에 보존된다.
