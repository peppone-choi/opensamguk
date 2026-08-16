# 정복 멸망 로그 누락 수정 — 적대적 리뷰

Date: 2026-08-16

Scope: 정복(ConquerCity) 멸망 경로에 누락된 deleteNation 멸망 로그 3종 추가 + 재야 리셋 보강. 브랜치 `fix-conquer-collapse-destroy-log`, base `origin/main` (`d0f9d47f`). 변경 파일 3개 — `logic/src/main/kotlin/opensamguk/logic/war/ConquerCity.kt`, `logic/src/test/kotlin/opensamguk/logic/war/ConquerCityCollapseTest.kt`, `docs/loops/nation-destroy-log-2026-08-16/investigation.md`.

Verdict: quarantined-with-proof

Proof: 추가한 멸망 로그 3종은 PHP 원문(`legacy/devsam-core/hwe/func.php:1729,1772,1773`)과 이미 패리티 검증된 형제 구현 2곳(`logic/src/main/kotlin/opensamguk/logic/actions/founding/CheHaesan.kt:145-147`, `app/game-engine/src/main/kotlin/opensamguk/engine/turn/RulerSuccessionHandler.kt:127,142-143`)에 대해 sibling-code-path byte-match로 대조했다. 정복-멸망 골든(`logic/src/test/resources/golden/p4/conquercity-collapse-01.json`) 부재와 ActionLogger PLAIN 포맷 플래그 미전달은 §4 B1/B2/B3/B4로 격리 등재했고, 골든은 실제 PHP 캡처로만 만든다(날조 금지). 전 모듈 5046 테스트 그린(§5).

리뷰어 P1 2건은 모두 이 변경이 만든 결함이 아니라 선재(pre-existing) 갭이며 아래 §4에 백로그로 등재했다. 라이브 버그(정복 멸망 시 멸망 로그 0줄)는 닫혔고 draw 스트림은 불변이다.

Reviewer: 독립 `parity-reviewer` 서브에이전트(작성자와 별도 컨텍스트). PHP 원문(`func.php:1713-1805`, `process_war.php:606-700`, `ActionLogger.php:23-119`)을 직접 열어 대조했다.

---

## 1. 근본원인과 수정

PHP는 멸망 3경로(정복/해산/승계실패)가 `deleteNation()`(`legacy/devsam-core/hwe/func.php:1713`) 한 함수를 공유해 로그 3종을 찍는다. Kotlin은 경로별 3벌 재구현이고, 정복 경로 `ConquerCity.resolveCollapse`에만 멸망 로그가 통째로 빠져 있었다.

추가한 로그 (PHP 원문 대조, 바이트 일치):

| PHP | 채널 | 문자열 |
|---|---|---|
| `func.php:1729` | global history | `<R><b>【멸망】</b></><D><b>{국가}</b></>{은} <R>멸망</>했습니다.` |
| `func.php:1772` | general action | `<D><b>{국가}</b></>{이} <R>멸망</>했습니다.` |
| `func.php:1773` | general history | `<D><b>{국가}</b></>{이} <R>멸망</>` |

형제 구현 `CheHaesan.kt:145-147`, `RulerSuccessionHandler.kt:127,142-143`과도 문자열이 동일하다(sibling-code-path byte-match).

방출 순서는 PHP 실행 순서를 그대로 따랐다: 정복 nation-history(`process_war.php:621`) → global 【멸망】 → deleteNation 장수 루프 **전체**(장수별 action+history) → 그 다음에야 `process_war.php:627`의 도주/draw 루프. 장수 순서는 기존과 동일하게 타 장수 asc PK + 군주 LAST(`func.php:1732,1735`).

부수 갭(T2): 재야 리셋이 `officerLevel/officerCity/nationId`만 0으로 놓던 것을 `func.php:1745-1760` 전체로 보강했다 — `belong=0`, `troop=0`, `permission='normal'`, NPCType<2면 `aux.max_belong = max(belong, aux.max_belong)`. 신규 `private fun neutralize()`는 `CheHaesan.neutralizeMember`(`CheHaesan.kt:182-200`)와 동일 규약이고 `LinkedHashMap`/`withMeta`로 삽입 순서를 보존한다. 5205 테스트 전 모듈 그린이라 파급 없음을 확인하고 이번 커밋에 포함했다.

## 2. 리뷰어가 침묵으로 통과시킨 검사

- **C1 draw 패리티** — 신규 코드의 RNG draw 0개. 기존 draw 순서·개수·인자 불변.
- **C2 라운딩** — 신규 코드에 반올림 없음.
- **C5 삽입 순서** — `neutralize`가 aux/meta 재정렬을 하지 않음. gold/rice/exp/ded는 `.copy`로 리셋 이후 적용되어 기존 필드를 덮지 않음.
- 로그 3종 문자열·조사(은/이)·태그·`했습니다.` PHP 바이트 일치.
- 신규 general 로그의 `nationId=0`은 형제 `RulerSuccessionHandler.kt:167-168`과 일치하고, PHP가 `setVar('nation',0)` **이후** `getLogger()`를 부르는 것과도 맞다.

## 3. 리뷰에서 수정한 지적

- `ConquerCity.kt` `defenderNationGenerals` KDoc이 "EXCLUDING the city ones"라고 잘못 적혀 있었다(실제 caller `ReservedTurnHandler.kt:692-694`는 도시 장수를 포함해 전달하고 PHP도 전 장수 대상). 문서대로 구현한 신규 caller가 생기면 도시 주둔 장수의 멸망 로그가 통째로 누락되므로 KDoc을 정정했다.

## 4. 남은 지적 — 전부 선재 갭, 별건 백로그

| # | 지적 | 왜 이번 범위 밖인가 |
|---|---|---|
| B1 | 장수 action 로그가 `ActionLogger::PLAIN` 포맷 플래그를 잃는다. `ConquerLog`에 format 필드가 없고 `LogEntryDraft.format`(`TurnWorldModel.kt:155`)은 어디서도 채워지거나 소비되지 않아 PLAIN(`<C>●</>`)과 history 기본 YEAR_MONTH 접두사를 재현할 수 없다 (`func.php:1772`, `ActionLogger.php:25,119,135,237`) | ConquerLog seam 전체의 선재 결함이다 — 기존 도주 로그(`ConquerCity.kt:224`)도 동일하게 앓는다. 고치려면 ConquerLog/LogEntryDraft에 formatType을 실어야 하고 이는 logic↔engine 경계 변경이라 이 버그 수정 범위를 벗어난다 |
| B2 | `logic/src/test/resources/golden/p4/conquercity-collapse-01.json` 부재. 이번 회귀 테스트의 기대 로그는 실제 PHP 캡처가 아니라 PHP 원문 + 패리티 검증된 형제 구현 2곳에서 옮긴 self-authored 기대값 | 골든은 `tools/php-golden` Docker 실캡처로만 만든다(날조 금지). 캡처 자체가 별도 작업 — 정복-멸망 시나리오 캡처 스크립트 신규 필요 |
| B3 | 【멸망】 global-history의 flush 시점 순서. PHP는 이 로그를 **군주 객체의** ActionLogger 버퍼에 push하고 소멸자 flush 시점에 INSERT하므로, 이후 `$attackerLogger`가 push하는 분쟁협상/양도 global-history(`process_war.php:764-774`)와의 global_history row 순서가 캡처 없이는 확정 불가 | B2 골든이 있어야 판정 가능. 현재 방출 위치는 PHP **실행** 순서에 맞춰져 있다 |
| B4 | 장수별 로그를 "전원 destroy → 전원 도주" 두 루프로 분리 방출한다. PHP는 장수별 로거 버퍼가 소멸 시점에 장수 단위로 한 번에 INSERT되므로 채널 병합 스트림 순서(장수3 도주 vs 장수8 destroy)가 다를 수 있다. 장수별 상대 순서는 일치 | 동일하게 B2 골든이 있어야 판정 가능. 실행 순서 재현은 이 커밋이 맞다 |
| B5 | `officerCity`를 컬럼과 `meta["officer_city"]` 양쪽에 쓴다(`ChangeRecorder.diffGeneral` + `diffMeta` 이중 기록) | 형제 `CheHaesan.kt:196`과 동일 규약 — 표현 수렴은 별건 리팩터 |

## 5. 게이트 증거

RED 확인 (수정 전, 테스트만 추가한 상태):

```
ConquerCityCollapseTest > collapse emits the deleteNation destroy logs before the 도주 loop in PHP order() FAILED
    org.opentest4j.AssertionFailedError at ConquerCityCollapseTest.kt:290
9 tests completed, 1 failed
```

수정 후 전 모듈 (`--rerun-tasks`, `**/build/test-results/test/*.xml` 파싱):

| 모듈 | tests | failures | errors | skipped |
|---|---|---|---|---|
| common | 232 | 0 | 0 | 0 |
| logic | 3227 | 0 | 0 | 0 |
| infra | 239 | 0 | 0 | 0 |
| app/game-engine | 838 | 0 | 0 | 1 |
| app/game-api | 510 | 0 | 0 | 0 |
| **합계** | **5046** | **0** | **0** | **1** |

`BUILD SUCCESSFUL in 20m 52s`. skipped 1건은 Docker 미가용 IT(정책상 skip, 실패 아님).
