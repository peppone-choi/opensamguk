# OPENSAM-187 — ConquerCity 로그 접두사 누락: 교차 비평 기록

- 대상: `logic/.../war/ConquerCity.kt`, `app/game-engine/.../turn/ReservedTurnHandler.kt`
- 브랜치: `fix-opensam-191-192` (PR #423)
- 일자: 2026-08-17

Scope: OPENSAM-187 로그 접두사 배선(logic/, app/) + 같은 브랜치의 OPENSAM-191 게이트 목록(scripts/agent/)·OPENSAM-192 DENSITY_FLOOR 주석(tools/)
Verdict: cleared

비평이 낸 fix-required 2건을 전부 수용·수정했고, consider 1건은 테스트 추가로 닫았다.
증거: `:common:test :logic:test :infra:test :app:game-engine:test :app:game-api:test --rerun-tasks`
BUILD SUCCESSFUL 9m 2s — common 232 / logic 3231 / infra 239 / game-engine 848(skip 1) / game-api 510,
failures 0 errors 0 (test-results XML 확인).

## 결함 (원 티켓)

`ConquerCity`가 만든 로그가 `ReservedTurnHandler.drainConquerCity()`에서 `LogEntryDraft`로 옮겨질 때
ActionLogger 접두사(`<C>●</>`, `Y년 M월:`, `M월:`)가 **전혀 붙지 않았다**. PHP는
`ActionLogger::formatText()`(`hwe/sammo/ActionLogger.php:231-268`)에서 flush 시 붙인다.

골든 게이트가 이걸 놓친 이유: `ConquerCityCollapseTest.kt:337 stripLogPrefix()`가 비교 **전에** 골든 쪽
접두사를 떼어낸다. 즉 접두사가 통째로 없어도 통과한다.

티켓이 제시한 처방(`LogEntryDraft.format` 필드 배선)은 채택하지 않았다 — 그 필드는 아무 소비처가 없는
죽은 필드다(`TurnWorldModel.kt:155`). 엔진의 기존 관례대로 drain 지점에서 문자열로 접두사를 붙인다.

## 교차 비평에서 나온 fix-required

### ① 포맷을 헬퍼 이름에 고정한 것이 오답 (수용)

내 1차 구현은 `ConquerLog.generalAction()` = 항상 PLAIN 식으로 헬퍼마다 포맷을 하나씩 못박았다.
**PHP는 포맷을 call-site 인자로 넘긴다.** push 메서드의 기본값(`ActionLogger.php`)은
`pushGeneralActionLog`=MONTH(:135), `pushGlobalActionLog`=MONTH(:199), 나머지 history 3종=YEAR_MONTH이고,
호출부가 필요하면 `ActionLogger::PLAIN`을 명시한다.

실증 divergence — `process_war.php`의 붙어 있는 두 줄:

```php
:726  $defenderLogger->pushGeneralActionLog($moveLog, ActionLogger::PLAIN);
:728  $defenderLogger->pushGeneralActionLog("수뇌는 <G><b>{$minCityName}</b></>{$josaRo} 집합되었습니다.");
```

골든 `conquercity-capital-01.json`이 이를 그대로 못박는다:

```
19× action PLAIN       '<C>●</>수도가 함락되어 <G><b>복양</b></>으로 <M>긴급천도</>합니다.'
 1× action MONTH       '<C>●</>1월:수뇌는 <G><b>복양</b></>으로 집합되었습니다.'
```

수정: 헬퍼가 `format` 파라미터를 받고 기본값은 PHP push 메서드 기본값 그대로.
PHP가 명시 인자를 주는 5곳에만 `ConquerLogFormat.PLAIN`을 명시한다 —
`ConquerCity.kt` 공략성공(`process_war.php:575`) · 멸망(`func.php:1772`) · 도주(`:631`) ·
정복보상(`:691,694`) · 긴급천도 moveLog(`:726`). 수뇌 집합(`:728`)은 기본값 MONTH.

### ② 내 검증 절차의 결함 (수용, 기록용)

1차에 "17개 호출부 매핑이 균일하다"고 단언했다. 틀렸다. 골든 스캔에 쓴 파이썬 필터가
`천도|양도|분쟁|지배|함락|점령|정복|멸망|공략` 정규식이었고, `수뇌는 …집합되었습니다.`는
**어디에도 안 걸려 스캔에서 조용히 빠졌다.** 표본을 키워드로 좁히면 "예외가 없다"는 결론은
표본이 만든 것이지 데이터가 만든 것이 아니다. 이번 건에서 골든 스캔은 **전량**을 포맷별로
분류하는 방식으로 다시 돌렸다(위 표).

## consider (수용)

접두사 assert 8건이 전부 collapse(멸망) 경로만 덮었고, 위 버그는 정확히 그 사각지대인
긴급천도 경로에 있었다. `ConquerCityCollapseTest`에
`capital-move log formats match the PHP capital golden prefixes`를 추가했다 —
기대 포맷을 코드에 박지 않고 `conquercity-capital-01.json` 텍스트의 접두사에서 파싱해 대조하며,
붙어 있는 두 줄의 포맷이 **서로 달라야 한다**는 것까지 계약으로 고정한다.

## 같은 브랜치의 선행 커밋 (363a3f9b)

- **OPENSAM-192** `tools/assets/build_brand_assets.py` — `DENSITY_FLOOR` 주석이 존재하지 않는 여유를
  주장했다. 실측 안전 구간은 19~32이고 자기검증이 쓰는 `+8`은 그 상한 정확히 = 여유 0이다. 주석을
  실측대로 고쳤고 `--check`가 `7 files byte-match`로 그린.
- **OPENSAM-191** `scripts/agent/v2-isolation-gate.sh` — 게이트 ②의 `**/v2/**` 제외가 v2 테스트를
  통째로 무검사로 만들었다. ②'(목록 전용, rc 미반영)를 추가. 스모크: `V2ProductionContextBeanGateIT`를
  지우면 ②'에 뜨고 ②는 PASS, rc=0. 이후 `git checkout --`로 복구.

## 남은 위험

- `LogEntryDraft.format`은 여전히 죽은 필드다. 이번 티켓 범위 밖이라 건드리지 않았다.
- 골든 대조 헬퍼 `stripLogPrefix()`는 그대로다. 접두사를 떼고 비교하는 다른 테스트들은
  여전히 포맷 회귀에 눈이 멀어 있다 — 새 테스트가 ConquerCity 경로에 한해 그 구멍을 막는다.
