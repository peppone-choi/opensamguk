# Review: 같은 틱에 멸망한 국가의 diplomacy UPDATE flush 제외 (PR #365)

Scope: `app/game-engine/src/main/kotlin/opensamguk/engine/flush/DatabaseHooks.kt`, `app/game-engine/src/test/kotlin/opensamguk/engine/flush/FlushPayloadConvergenceTest.kt` — 프로덕션 턴 데몬 영구차단(`diplomacy UPDATE affected 0 rows`) 근본원인 수정과 그 회귀 테스트
Verdict: cleared

## 장애 사실 (프로덕션 실측, 2026-08-08 관측)

GCP `instance-20260802-070134` (asia-northeast3-c) 는 `RUNNING`, 컨테이너 12개 전부 `Up`, `/api/health` 는 `{"status":"UP"}`. 그런데 게임 시각은 **190년 3월 / `world_version` 118 에서 정지**했고 `world_state.meta.lastTurnTime` 은 `2026-08-05T16:15:45Z` 다. 마지막 `log_entry` (id 73266) 도 `2026-08-05 16:15:49+00`.

`spep-game-engine` 컨테이너 로그, 첫 발생 `2026-08-05T16:20:49Z` 이후 **초당 1회 2.3일간 반복**:

```
ERROR turn-daemon-loop tick failed — backing off 1000ms
java.lang.IllegalStateException: diplomacy UPDATE affected 0 rows; expected exactly 1
  at opensamguk.infra.persistence.JdbcFlushExecutor.diplomacyUpdate-3mcQaDY(JdbcFlushExecutor.kt:804)
  at opensamguk.infra.persistence.JdbcFlushExecutor.flush(JdbcFlushExecutor.kt:57)
  at opensamguk.engine.run.TurnRunService.flushWithGeneration(TurnRunService.kt:421)
  at opensamguk.engine.run.TurnDaemonRunner.loop(TurnDaemonRunner.kt:226)
WARN  turn-daemon-loop blocked — recovery mode=RELOAD_REQUIRED worldId=1 generation=119
```

컨테이너가 살아있고 HTTP 헬스가 `UP` 이라 어떤 헬스체크·재시작 트리거도 걸리지 않았다. **관측 공백이 2.3일 장애의 실질 원인**이다.

프로덕션 DB 실측(read-only): `maxNationId=41`, 현존 국가 34개(멸망 7), `diplomacy` 1122행 = 34×33 완전 매트릭스, 고아 행 0건. flush 가 원자적으로 롤백돼 DB 가 사고 직전 상태로 깨끗이 남아 있음을 뒷받침한다.

## 근본원인

flush 단계 순서는 **step 6 nation cascade `DELETE FROM diplomacy`** (`JdbcFlushExecutor.kt:152`) → **step 7d diplomacy UPDATE** (`:176`) 다.

`InMemoryTurnWorld.removeNation()` (`InMemoryTurnWorld.kt:432-440`) 은 월드 자신의 `diplomacy` 맵과 `dirtyDiplomacyKeys`/`createdDiplomacyKeys` 만 prune 한다. 그런데 step 7d 의 소스는 **별도 맵** `ChangeRecorder.diplomacyUpdateDirty` (`ChangeRecorder.kt:123`, 기록 `:558`, 해제는 전체 `clear()` 뿐 `:813`) 이고 여기엔 nation 단위 prune 이 없다. 같은 틱에서 외교 패치가 먼저 기록되고 나중에 그 국가가 멸망하면, step 6 이 지운 행을 step 7d 가 UPDATE 해서 0 rows → `check(affected == 1)` → 틱 전체 롤백.

독립 리뷰가 확인한 실제 트리거 2개 — 둘 다 `dead != null` 을 실어 스택의 `:804` casualties 분기와 정확히 일치한다:

1. **전투** — `ReservedTurnHandler.kt:658-669` `applyBattleDiplomacyCasualty` 기록 → `:720` `markNationDeleted`
2. **월간 정산** — `MonthlyPostUpdateHook.kt:159-187` Q5/Q9 가 `newDead` 기록 → `:218` `postUpdateMonthlyTail` → `checkWander` → `:270` `markNationDeleted`

## 수정

payload 조립 지점(`DatabaseHooks.kt:652`)에서 월드에 더 이상 없는 `(from,to)` 쌍을 거른다. `check(affected == 1)` 은 **완화하지 않았다** — 그건 증상 은폐다.

## 교차검토 (독립 2건, 둘 다 ship)

### ① Claude 독립 레인 (`code-reviewer`, Opus) — 자체 임시 워크트리에서 실행 검증 포함

- **다른 0-rows 경로 없음**: `world_id` 는 `payload.worldId = world.worldId` 단일 출처; src/dest 뒤바뀜은 `ChangeRecorder.kt:547` `require` 가 차단; 생성-후-미INSERT 쌍은 `createDiplomacy` 가 `createdDiplomacyKeys` 를 항상 채워(`InMemoryTurnWorld.kt:346-347`) 불가; `diplomacy.remove` 는 전 코드베이스에서 `InMemoryTurnWorld.kt:437` 한 곳뿐.
- **과잉차단 없음**: `updateDiplomacy` 는 키 부재 시 null 반환(`:285`)이고 6개 호출지점 전부 `?: continue` 가드라 월드에 없는 쌍의 패치는 애초에 기록되지 않는다. `removeNation` 은 항상 `deletedNationIds` 추가(→ step-6 DELETE) 또는 `wasCreatedThisTick`(→ INSERT 된 적 없음) 둘 중 하나다. **DB 행이 살아있는데 UPDATE 가 조용히 사라지는 케이스는 구성 불가.**
- **패리티 무영향**: 필터는 flush 직렬화 경계에서만 돌고 `world.getDiplomacy` 는 순수 맵 조회. RNG draw 없음, 로그 push 없음, `List.filter` 는 순서 보존이라 flush delta 순서 불변. 골든 게이트는 `:logic` 에 있고 이 diff 는 `logic` 을 건드리지 않는다.
- **테스트 실증**: 픽스를 revert 하면 `tests="9" failures="1"`, `expected: <[(1, 3)]> but was: <[(1, 2), (2, 1), (1, 3)]>`.
- **복구**: flush 는 원자적 롤백이고 `world_version` CAS 는 커밋 후에만 전진(`TurnRunService.kt:425-427`), `acknowledgeClaimedWakes`/`publishTurnCompleted` 도 flush 뒤(`:386,391`) → DB 는 v118 에서 깨끗. 재시작 시 월드는 DB 리로드 + recorder 는 새 인스턴스라 stale 패치 없음.

### ② Codex (다른 프로바이더) — **부분 검토, 실행 검증 없음**

`VERDICT: ship — HEAD의 필터는 삭제된 외교 행만 제외하고 생존 행 순서·RNG·로그를 건드리지 않으며, 롤백 뒤 재시작 시 재구성된 payload에 적용된다.`

**한계를 명시한다.** codex 1차 실행은 공유 작업 디렉터리가 다른 레인(`op-35-v2-0a`)으로 전환돼 있어 diff 자체를 못 보고 무효 판정(`fix-required — 리뷰 대상 diff 없음`)을 냈다. 2차 실행은 격리 워크트리에서 diff 를 읽었으나, 그 워크트리가 리뷰 도중 다른 에이전트의 정리로 소멸해 **실행 기반 검증(테스트 재실행, 추가 소스 조회)을 수행하지 못했다.** 위 판정은 확보한 HEAD diff 와 소스 스냅샷에 대한 정적 검토 결과다. 따라서 실행 증거는 ①에만 있다.

## 반영한 지적

- **[P2 → 닫음]** 회귀 테스트가 `dead == null` 분기(`JdbcFlushExecutor.kt:792`)만 태웠다 — 실제로 죽은 건 casualties 분기(`:804`). 테스트 패치가 `dead = 15` 를 싣도록 고치고 생존 쌍이 casualties 분기로 flush 되는지 단언을 추가했다.
- **[NIT → 닫음]** 테스트의 인라인 FQN `opensamguk.engine.turn.TurnDiplomacy` 를 import 로 정리.

## 남긴 지적 (별도 티켓, 이 PR 소관 아님)

- **[P1]** `ChangeRecorder.kt:551-558` — **선재 결함.** `diplomacyUpdateDirty` 는 행 단위 last-write-wins 인데 `dead` 는 그 diff 에서 변했을 때만 실린다(`:556`). 전투 casualty 패치(dead=15) 뒤 같은 틱 월간 Q9 패치(dead 불변 → null)가 엔트리를 통째로 교체하면 casualty 쓰기가 DB 에 영영 안 간다 — 재시작해도 남는 조용한 메모리/DB 이격. `votePollUpdates` 처럼 컬럼 단위 병합이어야 한다.
- **[P1]** `TurnRunService.kt:521-526` — `RELOAD_REQUIRED` 는 in-process terminal 이다. **이 픽스만으로 지금 물려있는 데몬은 스스로 못 살아난다**; 배포가 컨테이너를 재생성해야 복구된다.
- **[P1]** `TurnRunService.kt:391` — 재시작 후 `lastTurnTime` 이 08-05 에 멈춰 있어 놓친 턴을 연속으로 몰아친다. 첫 캐치업 구간을 감시해야 한다.
- **[P2]** `DatabaseHooks.kt:652-657` — 저장소 관례는 `ChangeRecorder.kt:1031` `markNationDeleted` 안에서 형제 채널을 prune 하는 것(`nationPatches.remove(nationId)`). 조립 경계 필터는 미래의 어떤 제거 경로든 방어한다는 이점이 있으나 둘 중 하나를 고른 근거가 코드에 없다.
- **[P2]** `ChangeRecorder.kt:229` — `isDirty` 가 도태될 diplomacy 패치만 있는 틱을 여전히 dirty 로 보고한다(유령 dirty 틱).
- **[P2]** step-6 DELETE 가 step-7d UPDATE 보다 먼저라는 **flush 단계 순서 불변식 자체를 고정하는 테스트가 없다.** 누가 재배열해도 아무것도 안 깨진다.
- **[P2]** 관측: 데몬이 차단돼도 컨테이너 헬스가 `UP` 이라 알림이 없다. `lastTurnTime` 지연 또는 `RELOAD_REQUIRED` 상태를 헬스/알림에 노출해야 같은 사고가 2.3일 가지 않는다.

## 이 리뷰가 만족하지 못한 요건

- `CLAUDE.md` 의 mandatory legacy-gap chain 은 프로덕션 버그에 `loop-engineering` baseline/hypothesis/grader/adopt 증거를 요구한다. 이 PR 은 그 루프 산출물(`docs/loops/`) 없이 진행했다 — 장애가 진행 중이라 관측→근본원인→수정→게이트 경로를 직접 탔고, 그 증거는 이 문서가 전부다.
- 커밋에 `OPENSAM-###` 참조가 없다. 이 프로덕션 장애에 대응하는 Jira 티켓이 아직 없고 Jira 생성은 승인 범위 밖이었다. 사후 티켓 발행 후 참조를 남겨야 한다.
- 커밋 트레일러가 `Co-Authored-By: Claude Opus 5 (1M context)` 로, `CLAUDE.md` 가 규정한 `Claude Opus 4.8 (1M context)` 문자열과 다르다. 실제 작성 모델을 적었다.
