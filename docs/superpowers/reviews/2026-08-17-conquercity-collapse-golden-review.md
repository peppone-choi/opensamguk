# OPENSAM-186 — 정복-멸망 골든 캡처 + draw-for-draw 게이트 (독립 크리틱, 2차)

구현 레인의 자기 리뷰를 대체한 독립 크리틱이다. 1차(`adb8d4b3`)에서 `fix-required` 로 차단했고,
레인이 `42c223f6` 으로 3건을 닫았다. 이 문서는 그 수정을 재검증한 2차 판정이다. 판정 줄은 리뷰어만 바꾼다.
검증 위치: `feat-collapse-golden-186` `42c223f6` 분리 워크트리. 오라클은 메인 레포의 `legacy/devsam-core` 읽기 전용 대조.

Scope: logic/src/test (ConquerCityCollapseTest, ConquerCityReplayGateTest) + logic/src/test/resources/golden/p4 신규 골든 2종 + tools/php-golden/p4-capture-backlog.md + scripts/agent 및 docs 의 리베이스 잔차 확인.

Verdict: quarantined-with-proof

Proof: `conquercity-collapse-only-random-01.json` 의 `conquerCitySeeds.seed1` 로 시드한 `RandUtil(LiteHashDrbg)` 이 프로덕션 `ConquerCity.resolve` 를 통해 19장수 38 draw 를 값·순서 모두 골든과 일치시킨다(`ConquerCityCollapseTest.kt:427-472`). 잔여 격리인 `full` fixture 의 조건부 scout/NPC-join draw 는 동일 코드 경로가 `onlyRandom` 에서 바이트 일치로 입증됐고, 미재현 사유는 `db_delta.general.updated` 에 `npc` 컬럼이 없다는 검증된 사실이다 — 커밋된 골든의 updated 키는 `[dedication, dedlevel, experience, explevel, gold, nation, officer_level, rice]` 뿐이며 `npc` 는 collapse 중 변경되지 않아 delta 스냅샷에서 빠진다(backlog CC-1).

## 1차 차단 3건 재검증

### (1) CC-1 격리 사유 정정 — PASS

`tools/php-golden/p4-capture-backlog.md` 의 "LOCAL rng 라 캡처 불가" 문단이 삭제되고, 시드가 결정적이며
`conquerCitySeeds.seed1` 로 이미 커밋돼 있다는 사실 + 진짜 잔여(조건부 draw, `npc` 컬럼 부재)로 재작성됐다.
`npc` 부재는 직접 확인했다 — 위 Proof 의 updated 키 목록. 하단 QUARANTINED 항목의
"both goldens are SURVIVE cases" 문장도 정정됐다.

### (2) draw-for-draw 게이트 — PASS

`ConquerCityCollapseTest.kt:427-472` `collapse loseGold-loseRice draws replay from the golden ConquerCity seed`.

- **수치 하드코딩 0건.** 입력은 `col(id,"gold"|"rice"|"experience"|"dedication","from")`, 기대값은
  `col(id,…,"to")` 와 골든 도주 로그 문자열, 시드는 `conquerCitySeeds.seed1`, 장수 순서는 멸망 action 로그에서
  유도. 3074/4826 같은 상수는 테스트에 없다.
- **"onlyRandom → 장수당 정확히 2 draw" 전제 성립.** `process_war.php:645`
  `if ($admin['join_mode'] != 'onlyRandom' && $rng->nextBool(0.5))`, `:656` 도 동일하게 `!= 'onlyRandom'` 이
  첫 피연산자다. PHP `&&` 는 좌→우 단락이므로 `onlyRandom` 에서는 `nextBool` 이 평가되지 않는다.
  루프 본문(`:628-658`)에 다른 draw 는 없다 → 장수당 `nextRange(0.2,0.5)` ×2 확정. 우연 정렬이 아니다.
  (독립 확인: 커밋된 seed1 로 draw 0/1 을 뽑으면 `0.3074862109…`/`0.4826131360…` → `phpToInt(10000*r)` =
  3074/4826 = 골든 첫 장수 no=8 의 도주 로그. 스트림 오프셋 0 부터 정렬한다는 뜻이며 `:589` 이후
  `onArbitraryAction` 루프가 draw 를 소비하지 않았음도 함께 입증된다.)
- **`lordOverride` 의 `officerLevel = 12`** — 손으로 넣은 상수이지만 골든이 뒷받침한다:
  `db_delta.general.updated["105"].officer_level.from == 12` (일반 장수는 `1`, 예: `["8"].officer_level.from == 1`).
  값은 옳다. 다만 `col(id,"officer_level","from")` 로 읽으면 더 나았다 — 비차단 지적.
- **`collapseInput` 헬퍼의 잔여 합성값**: `defenderNation` gold=5000/rice=6000(골든 실제값은 10000/10000),
  `gen()` 기본치(통무지 50, 도시 200 등). draw 스트림에도 이 테스트의 단언 대상에도 영향이 없다
  (승전국 보상은 별도 ScriptedRng 테스트가 담당). 지어낸 값이 골든 대조 경로로 새어 들어가지는 않는다 — 비차단.

### (3) 주석 정정 — PASS

`ConquerCityCollapseTest.kt:345-352`(CC-1 문구 제거 + CC-3 ORDER BY 리스크 명시), `:391`(주석 교체),
`ConquerCityReplayGateTest.kt:30-34` KDoc, 백로그 QUARANTINED 항목 모두 정정됐다.

## 게이트 재현 (레인 보고와 대조)

- 전체: `BUILD SUCCESSFUL in 38s`, XML 집계 `classes 283 / tests 3230 / failures 0 / errors 0 / skipped 0`
  — 레인 보고와 일치.
- 뮤테이션은 레인이 쓴 축(draw 순서 스왑)을 믿지 않고 **다른 두 축**으로 다시 깼다:
  - `ConquerCity.kt:204` `nextRange(0.2, 0.5)` → `nextRange(0.2, 0.51)`:
    `12 tests completed, 3 failed` (신규 draw 게이트 + 기존 ScriptedRng 인자 검증 2건) → RED.
  - `ConquerCity.kt:205` `phpToInt(...)` → `Math.round(...).toInt()` (반올림 축):
    `12 tests completed, 1 failed` — **신규 draw 게이트만** 잡았다. 순서 스왑만 잡는 게이트가 아니라
    값·반올림 축까지 독립적으로 잡는다는 뜻이고, `Math.round` 금지(패러티 규칙 2) 회귀도 이 게이트가 막는다.
  - 복원 후 `git status --short` 공백, 12/12 GREEN.

## 골든·테스트 무결성 재확인

- `git diff origin/main -- logic/src/test/resources/golden/` = 신규 2파일 각 1줄 추가뿐. **기존 골든 수정 0.**
- `git diff 01c92e47..42c223f6 -- '*.kt'` 추가분에 `@Ignore`/`@Disabled`/`assertTrue(true)`/`TODO`/`Assumptions` 없음.
  테스트 수는 11 → 12 로 증가(약화 아님).
- 등용장 message 비교 테스트(`ConquerCityCollapseTest.kt:475-491`)는 "프로덕션 미호출 = 패러티 게이트 아님" +
  `{}`/`[]` 직렬화 의존이 주석에 명시됐다. 게이트 수에 넣어 계산하지 말 것.

## 남는 격리 (전부 백로그 기록됨, 이번 판정의 quarantine 대상)

- **CC-1 잔여** — `full` fixture 의 조건부 scout(`:645`)/NPC-join(`:656-658`) draw. 사유는 위 Proof.
  닫는 경로도 명시돼 있다(캡처 스크립트에 `npc`/`owner` 프리스테이트 블록 추가 — 골든 편집 아님).
  보강 관측: `full` 골든의 `message.created` 6건이 scout 성공 장수를 드러내므로, `npc` 만 확보되면
  조건부 스트림 재현은 완전히 가능해 보인다. 영구 격리가 아니라 다음 캡처 패스의 작업이다.
- **CC-2** — 전체 수치 db_delta 재현(pre-state 월드 전량 필요). 종전대로.
- **CC-3 신규** — `func.php:1733` 에 `ORDER BY` 부재. 관측된 asc PK 는 InnoDB PK 스캔 부수 효과이며
  순서가 바뀌면 멸망 로그와 draw 짝짓기가 동시에 어긋난다. 리스크 기록으로 적절.
- **CC-4 신규** — 하니스는 `capture_conquercity.php:266` `JSON_PRETTY_PRINT`, 커밋본은 1줄 minify.
  sha 재현 경로 부재(p4 골든 전체 선재 결함). 기록만 하는 처리에 동의.
- **UNKNOWN 유지** — "fresh DB 2회 캡처 sha256 동일"은 Docker 하니스를 재실행하지 않아 독립 확인 불가.
  대신 위조 반증은 확보돼 있다: 신규 두 골든의 `warSeed 094317401656ecf03eac89ddeecea011` 과 `seed1` 이
  **이전 커밋에서 독립 생성된** `conquercity-capital-01.json`(같은 도시 1)과 바이트 일치하고,
  도시 80 인 `survive` 만 `deea6716…` 로 다르다.

## 머지 전 잔여 액션 (비차단)

- 브랜치가 `origin/main ac032be4`(#421) 보다 1커밋 뒤처져 있다. 리베이스 후 머지할 것. 현재 `--base origin/main`
  diff 에는 `scripts/agent/v2-isolation-gate.sh` 등 #421 파일이 역델타로 섞여 나오므로 Scope 에 함께 적었다.

## PHP 오라클 대조 (전부 일치)

- `func.php:1729` global 【멸망】(조사 `은`) / `:1750`·`:1751` 장수 action·history(조사 `이`) → 골든 문자열 일치.
- `func.php:1733` + 군주 append → 골든 순서 `[8, 9, 19, 37, 58, 71, 76, 106, 108, 110, 115, 129, 134, 136, 137, 151, 157, 160, 105]` 일치 (CC-3 단서 첨부).
- `process_war.php:628-629` `Util::toInt(gold|rice * nextRange(0.2,0.5))` → Kotlin `phpToInt(... * rng.nextRange(0.2,0.5))` 가 38 draw 전량 일치.
- `process_war.php:645`·`:656` `join_mode != 'onlyRandom'` 단락 → 골든 쌍의 message 발부 유무 + onlyRandom 의 2-draw 스트림과 일치.
