# Review: OPENSAM-176 — diplomacy `dead` 컬럼 단위 병합 (전투 casualty 조용한 유실)

Scope: `app/game-engine/src/main/kotlin/opensamguk/engine/turn/ChangeRecorder.kt`, `app/game-engine/src/test/kotlin/opensamguk/engine/turn/DiffDiplomacyTest.kt` — `diffDiplomacy` 의 행 단위 last-write-wins 를 `dead` 컬럼 상속으로 바꿔 같은 틱 전투 casualty 쓰기가 유실되던 결함 수정과 그 회귀 테스트
Verdict: cleared

## 출처

프로덕션 턴 데몬 2.3일 영구차단 사고(PR #365) 리뷰에서 **선재 P1** 로 기록된 결함이다. 원 리뷰: `docs/superpowers/reviews/2026-08-08-diplomacy-flush-deleted-nation-review.md:64`. 사고 자체와는 별개 결함이며, 이 PR 은 사고 수정에 포함되지 않았던 그 지적을 닫는다.

## 결함

`ChangeRecorder.diffDiplomacy` 는 `diplomacyUpdateDirty[(from,to)] = patch` 로 **행 전체를 교체**했다. 그런데 `dead` 는 delta 형태다 — 그 diff 에서 변했을 때만 실리고, 안 변하면 `null`:

```kotlin
dead = if (pre.dead != post.dead) post.dead else null,
```

같은 틱에서 ① 전투가 casualty 를 기록(`dead=15`) → ② 이어서 월간 Q9 정산이 같은 쌍의 `term` 만 바꿈(`dead` 불변 → `null`) → ③ ②가 ①을 통째로 교체.

`JdbcFlushExecutor.diplomacyUpdate` (`infra/src/main/kotlin/opensamguk/infra/persistence/JdbcFlushExecutor.kt:794-797`) 는 `dead == null` 이면 `casualties = :casualties` SET 을 뺀 분기를 탄다. 결과: 인메모리 월드에는 casualty 가 있는데 DB 에는 영영 안 간다. 재시작 = DB 리로드이므로 이격이 그때 드러난다. **조용한 유실**이라 어떤 예외도, 어떤 로그도 남지 않는다.

## 수정

병합 지점 한 곳:

```kotlin
dead = if (pre.dead != post.dead) post.dead else prev?.dead,
```

`state`/`term` 은 항상 full post-state 이므로 last-write-wins 를 유지한다. `dead` 만 delta 형태라 앞선 같은 키 패치의 값을 이어받는다.

**`post.dead` 를 무조건 싣는 대안을 택하지 않은 이유**(코드 주석에도 남김): 값으로는 동등하지만(`post` 는 항상 world full 값), `dead == null` 이 `casualties` 컬럼을 SET 하지 않는 분기를 태우므로 — 이번 틱에 casualty 기록이 전혀 없으면 그 컬럼을 안 건드리는 기존 flush 노출 면을 그대로 유지한다.

수정 위치 판단: `DatabaseHooks`/`JdbcFlushExecutor` 에서 고쳤다면 증상 패치다(SQL `COALESCE` 나 payload 단 병합 = 미래의 모든 소비자가 각자 방어). 모든 diff 호출자가 통과하는 병합 지점 한 곳이 근본원인이자 최소 diff다.

## 교차검토 (독립 2건, 둘 다 ship)

### ① Claude 독립 레인 (`code-reviewer`, Opus) — 실행 검증 포함

- **`dead` 상속 정합성 — 결함 없음.** 6개 `diffDiplomacy` 호출지점 전수 확인. `dead` 를 바꾸는 유일한 경로는 non-null `dead` 를 넘기는 `InMemoryTurnWorld.updateDiplomacy`(`InMemoryTurnWorld.kt:283-286`)이고 그 4곳(`ReservedTurnHandler.kt:661`, `ProcessNationCommand.kt:755`, `MonthlyPostUpdateHook.kt:161/185`)은 전부 직후에 `diffDiplomacy` 를 호출한다. 나머지 4곳은 `dead` 를 아예 안 건드린다. 귀납 불변식 `patch.dead ∈ {null, world.dead}` 가 성립하므로 `prev.dead` 는 stale 이 될 수 없다.
- **감소·0 리셋으로 안 샌다.** `PostUpdateMonthly.kt:301` `deadReset = if (d.state != 0) 0 else d.dead`, `:266` `newDead = d.dead - deltaTerm*100*genCount` — 둘 다 `pre.dead != post.dead` 라 `post.dead` 분기를 타고 값이 그대로 실린다.
- **`clear()` 이후 stale 없음.** `ChangeRecorder.kt:820` 의 `clear()` 호출은 flush **성공** 직후 단 한 곳(`TurnRunService.kt:423`). 실패 시 유지되는 건 retry-clean 계약(`ChangeRecorder.kt:800-808`)이고, 이 경우 `prev.dead` 유지가 **오히려 정확**하다 — 그 casualty 는 아직 DB 에 안 갔다.
- **삽입 순서.** `ChangeRecorder.kt:123` 은 무인자 `LinkedHashMap()` = insertion-order 모드(access-order 는 3-인자 ctor 한정). 기존 키 `put` 은 순서를 안 바꾼다.
- **패리티 무영향.** `app/game-engine` 모듈. RNG draw 없음, `pushLog` 없음, `LinkedHashMap.values.toList()` 순서 불변 → flush delta 순서 불변. 골든 게이트는 `:logic` 이고 이 diff 는 `logic` 을 안 건드린다.
- **테스트 변경 판정: 정당한 정정, 약화 아님.** 구 `assertNull(patch.dead, ...)` 은 **결함 자체를 계약으로 고정한** 단언이었다. `assertEquals(5, ...)` 는 null 허용 → 특정 값 요구로 **더 강한** 단언이다. 테스트명도 주장 범위를 좁혀 정직해졌다(`last-write-wins` → `state-term last-write-wins`). 골든·픽스처 편집 없음.

### ② Codex (다른 프로바이더) — 격리 워크트리에서 정상 완주

`VERDICT: ship — 9개 updateDiplomacy·8개 diffDiplomacy 호출은 flush 성공 뒤 유일한 clear()까지 같은 recorder를 사용하며, 0/감소는 post.dead가 상속을 이기고 LinkedHashMap 재삽입은 순서를 보존하며 assertEquals(5)는 기존 행 단위 동작의 잘못된 기대를 바로잡는다.`

PR #365 때와 달리 이번에는 워크트리 소멸 없이 완주했다. 두 리뷰가 서로 다른 경로(①은 `dead` 를 쓰는 호출지점, ②는 recorder 수명주기)로 같은 결론에 도달했다.

## 반영한 지적

- **[P2 → 닫음]** 상속이 깨지기 쉬운 두 케이스에 테스트가 없었다 → (a) 같은 키 3-패치 체인(중간·마지막이 `dead` 미변경, 최초 casualty 가 끝까지 생존), (b) `PostUpdateMonthly.kt:301` Q9 `deadReset` 경로(교전 종료로 `dead 15→0`, **리셋이 상속을 이겨야 함**) 추가.
- **[P2 → 닫음]** `post.dead` 무조건 쓰기가 값으로 동등한데 왜 delta-성을 지켰는지 코드에 근거가 없었다 → `ChangeRecorder.kt` 주석에 flush 노출 면 유지 근거 기록.
- **[NIT → 닫음]** 주석의 "votePollUpdates 형태" 가 동형이 아니라 유비(`recordVotePollUpdate` 는 임의 컬럼 맵 `putAll`, 여기는 단일 컬럼 상속) → "컬럼 단위 병합 — votePollUpdates 와 같은 취지" 로 정정.
- **[NIT → 닫음, codex]** `ChangeRecorder.kt:118` 클래스 계약 KDoc 이 여전히 "a later transition displaces the earlier patch" 로 행 단위 교체를 서술 → COLUMN-wise 로 정정하고 `dead` 예외를 명시.

## 남긴 지적 (별도 티켓 / 이 PR 소관 아님)

- **[P2]** `JdbcFlushExecutor.kt:794` — 이 수정은 더 많은 패치를 `casualties` 분기로 보낸다. 2.3일 장애가 터진 바로 그 분기다. `DatabaseHooks.kt:657` 필터(#365)가 삭제된 국가 쌍을 막고 두 분기의 `check(affected == 1)` 이 동일해 새 위험은 없으나, 노출 면 확대라는 사실은 기록해 둔다.
- **[P2]** `DiffDiplomacyTest.kt` `key insertion order is preserved...` 는 프로젝트 로직이 아니라 JDK `LinkedHashMap` 시맨틱을 단언한다. `ChangeRecorder.kt:123` ctor 를 3-인자로 바꿔야만 실패하므로 계약 고정 값은 있으나 결함 검출력은 낮다.
- **[P2]** 원 리뷰의 나머지 미해결 지적(step-6/step-7d flush 단계 순서 불변식 테스트 부재, `ChangeRecorder.kt:229` 유령 dirty 틱, 조립 경계 필터 vs `markNationDeleted` sibling-prune 관례)은 그대로 남아 있다.

## 검증

- 게이트: `:app:game-engine:test --rerun-tasks` — 오케스트레이터가 직접 실행해 `BUILD SUCCESSFUL`, XML 집계 **tests 762 / failures 0 / errors 0 / skipped 1**(skipped 1 = 선재 Docker 의존 IT, 이 변경과 무관). `DiffDiplomacyTest` 7 케이스.
- **반증 실증**: 상속 우선순위를 잘못된 순서 `dead = prev?.dead ?: post.dead` 로 뒤집으면 새 Q9 리셋 테스트가 `DiffDiplomacyTest.kt:104` 에서 실패(`리셋은 실제 변경이므로 상속이 덮어쓰면 안 된다`), 기존 `dead == null` 단언도 동반 실패 → `7 tests completed, 2 failed`, `BUILD FAILED`. 실험 후 원복했고 위 green 은 원복 후 실행이다.

## 이 리뷰가 만족하지 못한 요건

- `CLAUDE.md` mandatory legacy-gap chain 이 요구하는 `loop-engineering` baseline/hypothesis/grader 산출물(`docs/loops/`)이 없다. 이 결함은 라이브 UI 갭이 아니라 코드 리뷰로 발견된 내부 정합성 결함이고 PHP 오라클 대조 대상이 아니어서(`dead`/`casualties` 컬럼의 flush 경로는 Kotlin 측 delta 구현) 관측→근본원인→수정→게이트 경로를 직접 탔다. 증거는 이 문서와 위 반증 실증이 전부다.
- 프로덕션 실측 증거는 없다. 이 유실은 조용해서 로그·예외를 남기지 않으므로 사후 DB 에서 "유실된 casualty" 를 특정할 수 없다. 결함 존재는 코드 경로와 반증 테스트로만 증명했고, **실제로 몇 건이 유실됐는지는 UNKNOWN 이다** — 추정치를 적지 않는다.
- 커밋 트레일러가 `Claude Opus 5 (1M context)` 로 `CLAUDE.md` 규정 문자열(`Claude Opus 4.8 (1M context)`)과 다르다. 실제 작성 모델을 적었다.
