# 국가 멸망 로그 누락 조사 (2026-08-16)

조사만 수행. 코드 수정·커밋 없음. 라이브/프로덕션 DB 미접근.

## 1. 증상

국가가 멸망(정복으로 마지막 도시 상실)하는데 `【멸망】` 로그가 안 찍힌다.

## 2. PHP 오라클 증거 (GRAND TRUTH)

멸망 로그는 전부 `deleteNation()` **한 곳**에서 나온다.

`legacy/devsam-core/hwe/func.php:1713` `function deleteNation(General $lord, bool $applyDB): array`

| PHP path:line | 로그 종류 | 문자열 (원문) |
|---|---|---|
| `func.php:1729` | `pushGlobalHistoryLog` (전체 역사로그) | `<R><b>【멸망】</b></><D><b>{$nationName}</b></>{$josaUn} <R>멸망</>했습니다.` |
| `func.php:1750` → `func.php:1772` | `pushGeneralActionLog(..., ActionLogger::PLAIN)` (장수 일반로그) | `<D><b>{$nationName}</b></>{$josaYi} <R>멸망</>했습니다.` |
| `func.php:1751` → `func.php:1773` | `pushGeneralHistoryLog` (장수 역사로그) | `<D><b>{$nationName}</b></>{$josaYi} <R>멸망</>` |

실행 순서 (= 로그 순서):
1. `DeleteConflict` → 2. **global history `【멸망】`** (`:1729`) → 3. 전 장수 루프(`:1752-1778`): `belong/troop/officer_level/officer_city/nation=0`, `permission='normal'`, NPCType<2면 `max_belong` aux 갱신 → 장수 action + 장수 history 멸망 로그 → 4. 도시 공백지화 / troop 삭제 / `ng_old_nations` 아카이브 / nation·nation_turn·diplomacy 삭제.

장수 순서: `func.php:1732` `SELECT no FROM general WHERE nation=%i AND no != %i` (ORDER BY 없음, PK ASC) + 군주(`$lordID`)를 마지막에 append (`:1735`).

### `deleteNation` 호출자 4곳 (PHP)

| 호출자 | 상황 |
|---|---|
| `hwe/process_war.php:623` | **정복 멸망** — 마지막 도시 함락 (`:606-607` `count(city)==1`) |
| `hwe/func.php:1862` | 후계자 없는 군주 사망 (승계 실패) |
| `hwe/sammo/Command/General/che_해산.php:108` | 해산 |

즉 정복 멸망도 해산·승계와 **동일한 `deleteNation` 로그 3종**을 찍는다. `process_war.php:621`의 `<D><b>{국가}</b></>{을} 정복` 은 attacker의 **nation history**로, 멸망 로그와 별개다.

## 3. Kotlin 현재 동작

멸망 경로가 PHP처럼 하나로 공유되지 않고 **3개로 흩어져 있고, 정복 경로에만 로그가 없다.**

| 경로 | 구현 | `【멸망】` global history | 장수 action/history 멸망 로그 |
|---|---|---|---|
| 해산 | `logic/src/main/kotlin/opensamguk/logic/actions/founding/CheHaesan.kt:145-147` | ✅ | ✅ |
| 후계 없는 군주 사망 | `app/game-engine/src/main/kotlin/opensamguk/engine/turn/RulerSuccessionHandler.kt:118-170` (`:127`, `:142-143`, `:167-168`) | ✅ | ✅ |
| **정복 멸망** | `logic/src/main/kotlin/opensamguk/logic/war/ConquerCity.kt:158-267` (`resolveCollapse`) | ❌ **없음** | ❌ **없음** |

`resolveCollapse`가 찍는 로그는 두 종류뿐:
- `ConquerCity.kt:176-179` attacker nation history `... 정복`
- `ConquerCity.kt:209-213` 장수별 `도주하며 금<C>N</> 쌀<C>N</>을 분실했습니다.`

멸망 자체의 tombstone은 `app/game-engine/src/main/kotlin/opensamguk/engine/turn/ReservedTurnHandler.kt:727` → `ChangeRecorder.markNationDeleted` (`app/game-engine/src/main/kotlin/opensamguk/engine/turn/ChangeRecorder.kt:1013-1056`)에서 수행되는데, 이 함수는 **로그를 전혀 push하지 않는다** (스냅샷·도시 중립화·nation patch 제거만). 즉 정복 경로에서는 어떤 계층도 멸망 로그를 만들지 않는다.

부수 갭 (같은 지점): `ConquerCity.kt:199-207`의 재야 리셋이 `officerLevel/officerCity/nationId`만 0으로 놓고, PHP `func.php:1755-1760`의 `belong=0`, `troop=0`, `permission='normal'`과 NPCType<2 `max_belong` aux 갱신은 빠져 있다. `RulerSuccessionHandler.kt:148-164`에는 이 처리가 있다.

## 4. 가설 표

| # | 가설 | 증거 (for) | 증거 (against) | 판정 |
|---|---|---|---|---|
| a | 멸망 처리 자체가 미포팅 | — | `ConquerCity.kt:111-123` collapse 분기 + `markNationDeleted` 존재, 국가는 실제로 삭제됨 | **기각** |
| b | 처리는 되는데 로그 push가 빠짐 | `resolveCollapse`(`ConquerCity.kt:158-267`) 전체에 `【멸망】`/`destroyLog` 문자열 없음; `markNationDeleted`(`ChangeRecorder.kt:1013-1056`) 로그 미발행; 전체 소스 grep `<R>멸망</>` 히트가 `CheHaesan.kt`/`RulerSuccessionHandler.kt` 두 곳뿐 | — | **확정 (근본원인)** |
| c | push는 되는데 flush/조회에서 유실 | — | `app/game-engine/src/test/kotlin/opensamguk/engine/run/MonthlyPostUpdateHookTailWiringTest.kt:110`이 `global`/`history` `【멸망】` 라인이 flush 페이로드까지 도달함을 검증; `FoundingHandlerSeamTest.kt:359`, `KillTombstoneTest.kt:298-304`도 동일 채널 통과 | **기각** (해산/승계 경로 한정으로 검증됨) |
| d | 프론트가 해당 로그 종류를 미렌더 | — | 같은 `global`/`history` 채널을 `web/game/app/game/world-log/page.tsx`, `.../history/page.tsx`가 렌더하고 `【유지】`/`【멸망】`은 승계 경로에서 이미 노출 | **기각** (단, 정복 경로 실물 렌더는 로그가 없어 확인 불가 — UNKNOWN) |

### 왜 게이트에서 안 잡혔나

`logic/src/test/resources/golden/p4/`에는 `conquercity-capital-01.json`, `conquercity-survive-01.json`만 있고 **collapse(멸망) 골든이 없다.** `logic/src/test/kotlin/opensamguk/logic/war/ConquerCityCollapseTest.kt`는 draw 순서·금쌀·경험/공헌·장수 순서만 검증하고 **로그를 한 줄도 assert하지 않는다.** 로그 게이트 공백이 이 누락을 통과시켰다.

## 5. 근본원인 결론

PHP는 멸망 3경로(정복/해산/승계 실패)가 모두 `deleteNation()` 한 함수를 공유해 로그 3종(global history `【멸망】` + 장수 action/history)을 찍는데, Kotlin은 그 함수를 공유 헬퍼로 포팅하지 않고 경로별로 3벌 재구현했고 **정복 경로(`ConquerCity.resolveCollapse`)에만 멸망 로그 3종이 통째로 빠져 있다.** collapse 골든/로그 assert 부재로 게이트가 이를 못 잡았다.

## 6. 제안 수정 범위

| 파일 | 왜 |
|---|---|
| `logic/src/main/kotlin/opensamguk/logic/war/ConquerCity.kt` | `resolveCollapse`에 `func.php:1729` global history `【멸망】`(정복 nation-history 직후, 장수 루프 **이전**)과 장수별 action/history 멸망 로그(`func.php:1772-1773`)를 PHP 순서대로 추가. 같은 자리에서 재야 리셋에 `belong/troop/permission`·`max_belong` aux(`func.php:1755-1760`) 보강. `ConquerLog.globalHistory` 발행 seam은 이미 `resolveSurvive`(긴급천도)에서 쓰이므로 추가 배관 불필요 |
| `logic/src/test/kotlin/opensamguk/logic/war/ConquerCityCollapseTest.kt` | collapse 로그 시퀀스(순서 포함) assert 추가 — 이번 회귀를 실제로 잡는 최소 체크 |
| (권장) `tools/php-golden` 캡처 → `logic/src/test/resources/golden/p4/conquercity-collapse-01.json` | 정복-멸망 골든 부재가 게이트 공백의 원인. 골든은 반드시 실제 PHP 캡처로만 |

리팩터 여부(3벌 중복을 공유 `deleteNation` 헬퍼로 통합)는 별건. logic/engine 모듈 경계를 넘는 이동이라 이번 버그 수정에 포함하지 말 것 — 최소 변경은 `ConquerCity.kt` 한 파일이다.

## 7. 재현

정복-멸망 시나리오의 실행 가능한 재현은 미수행(UNKNOWN). 다음 프로브: `ConquerCityCollapseTest`의 기존 `collapseInput` 픽스처로 `res.conquerLogs`를 덤프해 `【멸망】` 부재를 직접 출력하면 코드 수정 없이 1분 내 증명 가능.
