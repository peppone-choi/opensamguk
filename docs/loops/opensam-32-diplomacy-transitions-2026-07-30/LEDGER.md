# OPENSAM-32 외교 상태 전이 루프 원장

| 바퀴 | 가설 | 점수 전→후 | 채점자 | 판정 | 원인 한 줄 |
|---|---|---|---|---|---|
| 0 | baseline only — Jira 설명과 현재 코드의 상태 전이·라이브 경로가 일치하는지 측정 | 미측정→backend 106/106 + frontend 5/5 | frozen logic/engine/infra/frontend graders | 채택 | 여섯 명령의 RNG 0·메시지 payload·양방향 상태/term·recorder/flush는 green이지만 기존 UI grader는 종전 제의 한 건만 관측했다 |
| 1 | Jira가 stale하며 OPENSAM-32 production 수정은 불필요하다 | 111/111→신규 D4-10 회귀 1/3 실패 | 동일 frozen graders + independent reviewer + real `CommandModal` behavior test | 기각 | 빠른 실행의 `pinnedArgType="nation"`이 불가침 제의의 `year/month` 구조화 폼을 버린다 |
| 2 | 고정 명령 모달이 backend catalog의 구조화 폼을 소비하면 D4-10이 `destNationID/year/month`를 순서대로 예약한다 | RED 1/3→15/15, edge RED 1/6→최종 16/16 green + typecheck | `CommandModal.form-spec` + terminal-result + `DiplomacyPage.command` | 채택 | authoritative `formSpec`의 세 필드를 pinned launch에도 보존하고 조회 실패·row 누락·form 누락은 fail-closed한다 |
| 3 | 제의 메시지 target은 preload된 상대국 색을 사용해야 PHP JSON과 일치한다 | 20 tests / 3 failures→30/30 green | 세 proposal GoldenTest + proposal fallback regression | 채택 | 세 Kotlin 명령이 사용 가능한 `draft.destNation.color` 대신 `#000000`을 고정했다 |
| 4 | 한 실제 제의→수락→recorder→JDBC 경로를 잇는 통합 회귀가 분리 seam의 false-green을 막는다 | 분리 seam green→실제 IT 2/2, skip 0 | `DiplomaticMessageWorldScopeIT` | 채택 | 실제 lifecycle proposal, DB message read, accept delta, 세 flush와 same-world declaration을 한 테스트가 잇는다 |
| 5 | reserved proposal adapter가 세 제의의 `destNation` 실국가 identity를 resolver에 preload하면 PHP name/color/log payload가 복원된다 | engine 3 tests / 1 failure→3/3 green; logic 30/30 green | reserved runtime regression + 세 zero-RNG golden | 채택 | 제약은 `destNationID`를 알았지만 resolver draft는 대상국을 들고 있지 않아 `상대국/#000000`을 직렬화했다 |

## 0바퀴 계약

- 가설 변경: 없음.
- 합치기/원복 기준: OPENSAM-32 production diff 0을 유지한다.
- 승인 대기: commit/push/merge/deploy/Jira transition, production access,
  legacy/golden write.
- Browser note: `docker compose ps`는 필수 `OPENSAMGUK_WORLD_ID` 미설정으로
  config 단계에서 실패했고 실행 중인 관련 컨테이너도 없었다. 비밀 또는
  로컬 `.env`를 읽지 않았으므로 live browser grader는 현재 `채점대기`다.

## 0바퀴 관측

- Logic: `BUILD SUCCESSFUL in 1m 2s`, 8 suites / 72 tests /
  failure·error·skip 0.
- Engine: `BUILD SUCCESSFUL in 1m 12s`, 3 suites / 32 tests /
  failure·error·skip 0.
- Infra: `BUILD SUCCESSFUL in 45s`, 1 suite / 2 tests /
  failure·error·skip 0.
- Frontend: 1 file / 5 tests PASS.
- 합계: backend 12 suites / 106 tests + frontend 5 tests.
- D4-13의 후속 선전포고 가능성은 같은 engine grader에서
  `불가침파기수락: NON_AGGRESSION→TRADE`와
  `installed declaration resolver accepts ...`의 `TRADE→DECLARATION`
  실제 실행을 각각 관측한다.
- 반복 Fablize generic failure notice는 exit 0 결과에도 붙는
  `.ai/known-issues.md` baseline이며 위 직접 출력/XML 판정과 분리했다.

## 1바퀴 계약

- 단일 가설: Jira 상태가 현재 repository truth보다 뒤처져 있다.
- 변경: production source 변경 없음. loop/review/Agent OS 증거만 갱신.
- 채점자: frozen grader 재사용 없이 0바퀴 fresh 결과와 독립 source review.
- 합치기/원복 기준: production diff 0 유지. reviewer가 실질 누락을 찾으면
  이 결론을 폐기하고 해당 한 동작에 RED test를 먼저 작성한다.
- 승인 대기: live browser 환경, Jira transition, commit/push/merge/deploy.

## 1바퀴 관측과 기각

- 독립 source review가 `DiplomacyPage`의 모든 빠른 명령이
  `pinnedArgType="nation"` 하나로 축약된 것을 찾았다.
- `CommandModal`은 pinned mode에서 catalog fetch를 생략하고 합성 명령에
  구조화 `form`을 넣지 않는다. 따라서 backend의
  `destNationID → year → month` 명세가 UI에 도달하지 않는다.
- 신규 실제 컴포넌트 회귀 테스트는 국가 `위` 선택까지 성공한 뒤
  `spinbutton`을 하나도 찾지 못해 의도한 RED가 됐다:
  `CommandModal.form-spec.test.tsx` 3 tests 중 1 failed / 2 passed.
- 최초 `corepack pnpm ...` 실행은 현재 셸에 `corepack`이 없어 exit 127로
  중단됐다. `/usr/local/bin/pnpm ...`으로 동일 테스트를 재실행해 위 기능
  RED를 분리 관측했다.
- Fablize generic tool-failure 표시는 exit 0 명령에도 반복되는 알려진
  baseline이고, 이번 기능 RED는 Vitest exit 1과 DOM 실패 메시지로 별도
  확인했다.

## 2바퀴 계약

- 단일 가설: pinned command에 선택적 `CommandFormSpec`을 전달하고
  `che_불가침제의` 빠른 실행이 backend catalog의
  `destNationID/year/month` 명세를 소비하면 누락 없이 예약된다.
- 변경 허용: `CommandModal`, diplomacy page, 두 직접 회귀 테스트만.
- 채점:
  - `/usr/local/bin/pnpm vitest run __tests__/CommandModal.form-spec.test.tsx`
  - `/usr/local/bin/pnpm vitest run __tests__/DiplomacyPage.command.test.tsx`
  - `/usr/local/bin/pnpm typecheck`
- 채택 기준: 실제 모달 테스트가 `nationBulk` body의 세 필드와 순서를
  단언하고, 페이지 테스트가 D4-10에만 구조화 폼을 전달함을 단언하며,
  두 focused suite와 typecheck가 모두 green이다.
- 원복 기준: 다른 pinned command 동작을 바꾸거나, 정적 폼이 backend
  catalog와 달라지거나, focused/typecheck가 실패한다. 페이지 하드코딩은
  독립 리뷰가 드리프트 위험으로 기각했으므로 채택하지 않는다.

## 2바퀴 관측

- `CommandModal`은 `resolvePinnedFromCatalog` opt-in에서 server
  `availableCommands`의 matching full row를 사용한다.
- `che_불가침제의`만 이 경로를 활성화하며, 나머지 pinned shortcut은
  기존 scalar picker를 유지한다.
- 조회 중에는 폼/제출을 숨기고, fetch 실패 또는 matching row 누락 시
  오류만 표시해 invalid 축약 제출을 fail-closed한다.
- Fresh implementer grader:
  - terminal-result + form-spec + diplomacy page: 3 files / 15 tests PASS.
  - `/usr/local/bin/pnpm typecheck`: PASS.
  - `git diff --check`: PASS.
- 독립 재검토가 matching catalog row에 `form`이 없는 경우 scalar fallback이
  열리는 edge를 찾았다. 신규 회귀는 수정 전 6개 중 1개 실패했고,
  matching form 자체를 필수로 만든 뒤 최종 3 files / 16 tests PASS,
  typecheck PASS를 재확인했다.
- package에 Prettier binary가 없어 별도 prettier check는 실행하지 못했다.
  프로젝트의 필수 frontend 검증은 Vitest/typecheck이며 root 재검증이
  completion gate에서 다시 수행된다.

## 3바퀴 계약

- 단일 가설: 세 proposal resolver의 destination `MessageTarget.color`를
  preload된 `draft.destNation.color`에서 가져오면 PHP target JSON과 일치한다.
- RED: 각 GoldenTest context에 이름/색이 구별되는 destination nation을
  preload하고 serialized destination color를 단언한다.
- 채택 기준: 세 테스트가 수정 전 `#000000` 불일치로 RED이고, 최소 source
  수정 뒤 동일 테스트가 fresh green이다.
- 원복 기준: destination nation이 없는 격리 unit context가 설명 없는
  crash로 바뀌거나 message key/order/payload가 달라진다.

## 3바퀴 관측

- RED: 세 GoldenTest가 목적국 `#1a2b3c`를 preload한 뒤
  `msg.dest.color`를 단언하자 모두 actual `#000000`으로 실패했다
  (20 tests / 3 failures).
- GREEN: 세 resolver가 `draft.destNation?.color ?: "#000000"`을 사용한다.
  production adapter는 destination nation을 preload하며, destination 없는
  격리 `DiplomacyProposalCommandsTest`는 기존 fallback을 유지한다.
- Fresh grader: `BUILD SUCCESSFUL in 42s`; 세 GoldenTest 20개와 fallback
  regression 10개, 총 30 tests / failures·errors·skipped 0.
- `git diff --check` 통과. legacy/golden fixture/git/external mutation 없음.

## 4바퀴 계약

- 단일 가설: 실제 proposal부터 JDBC persistence까지 잇는 한 회귀가 현재
  분리된 seam 테스트가 놓친 payload/state 오류를 검출한다.
- 현재 상태: 기존 fixture 재사용 가능성과 가장 작은 모듈 경계를 독립
  설계 검토 중이다. 아직 테스트나 production 변경을 허용하지 않았다.
- 채택 기준: 실제 proposal command, 생성 message, accept command,
  양방향 recorder delta, `JdbcFlushExecutor` post-state를 한 관측 사슬에서
  확인한다.

## 4바퀴 관측

- 실제 `TurnDaemonLifecycle` nation pass가 예약된
  `che_불가침파기제의`를 실행했다.
- shared `ChangeRecorder`가 receiver-first message 두 행을 만들고,
  첫 행 mailbox `9002`, 두 번째 발신 사본 mailbox `9001`을 단언했다.
- 첫 `DatabaseHooks.toFlushPayload → JdbcFlushExecutor` 뒤
  `ContactReader`가 DB에서 `action=cancel_na`, `deletable=false`,
  destination color `#0000ff`인 저장 JSON을 다시 읽었다.
- 같은 `ProcessNationCommand`를 사용하는 `DiplomaticMessageHandler` 수락이
  `(2,1)`, `(1,2)` 두 patch를 `TRADE(2)/term=0`으로 만들었고 두 번째
  flush 뒤 DB 양방향 상태가 일치했다.
- 같은 월드에서 `che_선전포고`를 이어 실행해 두 방향
  `DECLARATION(1)/DEFAULT_DECLARE_WAR_TERM`을 세 번째 flush와 DB에서
  확인해 D4-13의 순차성을 닫았다.
- Implementer run: `BUILD SUCCESSFUL in 1m 17s`.
- Root 보강 중 nullable assertion의 컴파일 오류를 1회 관측했고,
  `assertNotNull` 반환값을 사용하도록 바로잡았다.
- Root 최종 engine focused run: `BUILD SUCCESSFUL in 1m 3s`;
  4 suites / 34 tests / failures·errors·skipped 0. 이 중
  `DiplomaticMessageWorldScopeIT`는 2/2, skip 0이다.
- Root 최종 logic: `BUILD SUCCESSFUL in 38s`; 8 suites / 72 tests /
  failures·errors·skipped 0.
- Root 최종 infra: `BUILD SUCCESSFUL in 40s`; 1 suite / 2 tests /
  failures·errors·skipped 0.
- Fablize generic notice는 `.ai/known-issues.md`의 기존 baseline과 동일하다.

## Completion verification

- 실행:
  - `git diff --check`: PASS.
  - `bash scripts/agent/test-codex-agent-os.sh`: PASS.
  - `python3 tools/agent-system/check.py --strict --base origin/main`:
    exit 1, error 2 / warning 0. 두 error는 OPENSAM-32 밖의 기존 baseline인
    user-owned `.codex/config.toml` personal model pin과 historical
    `2026-07-27-v1-nonoperational-completion-review.md`의 uppercase
    `Verdict: CLEARED`/현재 lowercase anchor 규칙 불일치다.
- 실행하지 않음:
  - live browser: `OPENSAMGUK_WORLD_ID`가 없는 compose 환경이며 secret/env를
    읽지 않아 `채점대기`.
  - Prettier: `web/game` package에 binary가 없어 미실행. 필수 frontend
    grader인 focused Vitest 16/16과 typecheck는 PASS.
  - production/EC2, deploy, Jira transition, commit/push/merge, data delete,
    secret access, legacy/golden write.

## 5바퀴 — 2026-08-13 runtime regression

- PHP oracle:
  - `che_종전제의.php:121-165`
  - `che_불가침제의.php:167-221`
  - `che_불가침파기제의.php:119-167`
  - 세 명령 모두 `$this->destNation`의 name/color를 MessageTarget과 action log에 쓰며
    RNG draw와 proposal-time diplomacy UPDATE가 없다.
- 최소 수정:
  - `ReservedTurnHandler.preloadDraftTargets` 대상을 세 proposal code에만 늘렸다.
  - 세 resolver는 preload된 `draft.destNation.name/color`를 사용하고, 격리 unit
    context의 기존 fallback은 유지한다.
  - engine regression은 실제 국명/색, PHP 조사 quirk, action option, 제의 전·후
    양방향 상태 불변, diplomacy dirty 0을 고정한다.
- fresh evidence:
  - engine focused: `BUILD SUCCESSFUL in 28m 55s`; XML 3/0/0/0.
  - logic proposal goldens: `BUILD SUCCESSFUL in 9m 5s`; XML 30/0/0/0.
  - backend parity gate: `BUILD SUCCESSFUL in 42m 22s`; 35 tasks executed,
    XML gate 617 suites / 5,183 tests green.
  - `git diff --check`: PASS.
- 독립 static review: `cleared`; 대상 identity/color, 정확 로그/조사, zero RNG,
  proposal 상태 불변, 기존 accept state/term을 PHP와 재대조했다.
- 초기 engine RED 재실행은 로그의 ActionLogger 월 prefix와 JSON key
  (`nation` vs `name`)를 테스트가 빠뜨린 것이었다. PHP/Kotlin 직렬화 규약으로
  기대값을 보정했고 production behavior는 추가 확장하지 않았다.
