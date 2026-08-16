# OPENSAM-186 — 정복-멸망 골든 캡처 + 로그 게이트 (독립 크리틱)

이 문서는 구현 레인이 스스로 쓴 리뷰를 대체한다. 자기 승인은 증거가 아니므로 전량 재검증했다.
검증 위치: PR #420 head `80ec6ba2` 의 분리 워크트리. 오라클은 메인 레포의 `legacy/devsam-core` 를 읽기 전용으로 대조했다.

Scope: `logic/src/test/resources/golden/p4/conquercity-collapse-{full,only-random}-01.json` 신규 골든 2종, `logic/src/test/kotlin/opensamguk/logic/war/ConquerCityCollapseTest.kt` 골든 기반 assert 2건, `tools/php-golden/p4-capture-backlog.md` CC-0 항목, 그리고 이 리뷰 문서.

Verdict: fix-required

산출물 자체는 위조가 아니고 게이트도 실재한다(아래 1~5 전부 PASS). 다만 CC-1 격리의 사유가 사실과 다르며,
그 결과 패러티 규칙 1순위인 draw-for-draw 게이트가 **캡처가 이미 있는데도** 만들어지지 않았다. 아래 6번이 차단 사유다.

## 1. 골든이 실제 PHP 캡처인가 — PASS

- 신규 2종의 최상위 키는 기존 커밋 골든(`conquercity-survive-01.json` / `conquercity-capital-01.json`)과 동일하고,
  추가분은 `joinMode` + 스냅샷 테이블 5종(`rank_data`/`general_turn`/`event`/`message`/`world_history`)뿐이다.
- 캡처 스크립트는 이 PR 에서 **변경되지 않았다**(`git diff origin/main -- tools/php-golden/capture_conquercity.php` 공백).
  `tools/php-golden/capture_conquercity.php:462-472` 가 이미 두 `join_mode` 로 collapse 브랜치를 돌리게 되어 있고,
  `:247-262` 의 필드 목록이 커밋된 골든의 키 순서와 정확히 일치한다.
- 교차 검증(위조라면 맞을 수 없는 값): 신규 두 골든의 `warSeed` `094317401656ecf03eac89ddeecea011` 과
  `conquerCitySeeds.seed1` 이, **이전 커밋에서 독립적으로 만들어진** `conquercity-capital-01.json`(같은 목표 도시 1)과
  바이트 일치한다. 도시 80 을 치는 `survive` 골든만 `deea6716…` 로 다르다 — `che_출병.php` 의 시드 유도식과 정합.
- 커밋 메시지의 sha256 두 건은 커밋된 파일의 실제 sha256 과 일치한다(`1b61e42…`, `7e53c19…`).
- UNKNOWN: "fresh DB 2회 캡처 동일" 은 Docker 하니스를 재실행하지 않았으므로 독립 확인 불가.
  또한 스크립트는 `JSON_PRETTY_PRINT`(`capture_conquercity.php:266`)로 쓰는데 커밋본은 1줄 minify 다.
  minify 단계가 어디에도 커밋·문서화돼 있지 않아 "하니스를 돌려 sha 를 재현" 하는 경로가 없다.
  기존 p4 골든 전부가 같은 형태라 이 PR 이 만든 결함은 아니지만, 하니스 재현성 구멍으로 남는다.

## 2. 로그 문자열 하드코딩(순환 게이트) 여부 — PASS

신규 테스트 `ConquerCityCollapseTest.kt:350-411` 은 멸망 문자열을 하드코딩하지 않는다.
`goldenDestroyAction`/`goldenDestroyHistory`/`goldenGlobal`(`:361-368`)로 캡처에서 뽑아 `destroyLog`/`destroyHistoryLog`
를 유도하고, 국가명도 `db_delta.nation.deleted["2"].name`(`:353`)에서 읽는다.
(같은 파일 `:280-294` 의 하드코딩 문자열 테스트는 **이 PR 이전부터 있던** 테스트다. 이번 골든이 그 하드코딩을
실제 캡처로 뒷받침하게 된 것이 OPENSAM-186 의 실질 소득이다.)

## 3. 기존 골든 수정 여부 — PASS

`git diff origin/main -- logic/src/test/resources/golden/` = 신규 2파일 추가뿐(각 1줄). 기존 파일 수정 0.

## 4. 테스트 약화·스킵 — PASS

커밋 `80ec6ba2` 의 `.kt` 추가분에 `@Ignore`/`@Disabled`/`assertTrue(true)`/`TODO` 없음. 순수 추가(+106줄).
다만 신규 assert 2건 중 `:418-424`(`the onlyRandom collapse golden issues no scout message while the full golden does`)
는 **골든 두 파일만 비교**하고 Kotlin 프로덕션 코드를 전혀 호출하지 않는다 — 패러티 게이트가 아니라 캡처 자기정합성
확인이다. 게다가 `full` 은 `.jsonObject`, `onlyRandom` 은 `.jsonArray` 로 읽어 PHP 의 `{}`/`[]` 직렬화 형태에 의존한다.

## 5. 게이트 실재 증명(뮤테이션) — PASS

`ConquerCity.kt:193-196`(장수별 멸망 action/history 발행 루프)을 제거하고 실행:

- RED: `collapse destroy logs byte-match the PHP collapse golden` FAILED at `ConquerCityCollapseTest.kt:404`,
  `collapse emits the deleteNation destroy logs before the 도주 loop in PHP order` FAILED at `:296` — `11 tests completed, 2 failed`.
- `git checkout -- logic/src/main/kotlin/opensamguk/logic/war/ConquerCity.kt` 복원 후 GREEN:
  `BUILD SUCCESSFUL`, `TEST-opensamguk.logic.war.ConquerCityCollapseTest.xml` = `tests="11" skipped="0" failures="0" errors="0"`.

## 6. [차단] CC-1 격리 사유가 사실과 다르다 — collapse draw 는 이미 캡처돼 있다

리뷰·백로그·테스트 주석은 collapse 의 금/쌀 draw 를 "`process_war.php:589` 의 LOCAL rng 라 캡처 불가" 라고 적고
수치 단언을 회피한다(`ConquerCityCollapseTest.kt:346-347`, `:389`). 이는 틀렸다.

- `process_war.php:589-597` 의 `$rng` 는 지역 변수일 뿐 시드는 `UniqueConst::$hiddenSeed|'ConquerCity'|year|month|
  attackerNationID|attackerID|cityID` 로 **완전 결정적**이고, 그 시드 문자열이 골든의 `conquerCitySeeds.seed1` 로 이미 커밋돼 있다.
- 그리고 draw 결과가 골든에 그대로 들어 있다: `conquest_records` 의 도주 로그 19건
  (`도주하며 금<C>3074</> 쌀<C>4826</>을 분실했습니다.` 등) + `db_delta.general.updated` 의 gold/rice `from`/`to`.
- 실측 반증: 커밋된 시드 문자열로 Kotlin `RandUtil(LiteHashDrbg(seed1))` 을 만들어 `nextRange(0.2,0.5)` 를 두 번 뽑으면
  `0.3074862109304774` / `0.48261313604610323` → `phpToInt(10000*r)` = **3074 / 4826**, 즉 골든 첫 장수(no=8)의
  도주 로그 수치와 정확히 일치한다. (검증용 임시 테스트로 확인 후 삭제; 워크트리에 잔재 없음.)
  이는 덤으로 "OccupyCity 이벤트 뒤 스트림이 idx 0 으로 리셋된다"는 캡처 스크립트의 가정(`capture_conquercity.php:186-190`)까지 실증한다.

결론: 이 PR 은 멸망 **로그**를 게이트로 만들었지만, 같은 골든이 이미 담고 있는 **draw-for-draw** 게이트
(CLAUDE.md 패러티 규칙 1순위)를 만들지 않았고, 그 이유로 사실이 아닌 격리 문구를 백로그에 새로 남겼다.
머지 전 요구사항:

1. `tools/php-golden/p4-capture-backlog.md` 의 CC-1 을 "캡처 불가" 에서 실제 잔여 범위로 고칠 것.
   (금/쌀 draw = 캡처됨. 남는 것은 scout `nextBool(0.5)`/NPC 임관 분기의 장수별 `npc` 필드가 스냅샷에 없다는 점뿐이다 —
   `db_delta.general.updated` 키에 `npc` 가 없어 조건부 draw 순서를 재구성할 수 없다. 그것이 진짜 잔여 사유다.)
2. `conquercity-collapse-full-01.json` 의 `conquerCitySeeds.seed1` 로 Kotlin RNG 를 시드해 최소한 **첫 장수의
   loseGold/loseRice 두 draw** 를 골든 도주 로그와 대조하는 assert 를 추가할 것. 시나리오 재구성 없이 성립한다.
3. 같은 CC-1 문구가 `ConquerCityCollapseTest.kt:346-347` 주석에도 복제돼 있으므로 함께 정정할 것.

부수(비차단):
- CC-1 본문의 "both goldens are SURVIVE cases, so no collapse fires" 문장이 이제 사실과 다르다(collapse 골든 2종 존재).
- `:418-424` 테스트는 프로덕션 코드를 호출하지 않으므로 패러티 게이트로 계산하지 말 것.
- 브랜치가 `origin/main` 보다 3커밋 뒤처져 있다(`01c92e47` 등). 머지 전 리베이스 필요.

## PHP 오라클 대조 (전부 일치)

`legacy/devsam-core/hwe/func.php` `deleteNation`:

- `:1729` `pushGlobalHistoryLog("<R><b>【멸망】</b></><D><b>{$nationName}</b></>{$josaUn} <R>멸망</>했습니다.")`
  → 골든 `world_history` created #8 `…<D><b>황건적</b></>은 <R>멸망</>했습니다.` (조사 `은`) ✓
- `:1750` `$destroyLog` / `:1751` `$destroyHistoryLog` (조사 `이`) → 골든 장수 action/history 19+19건과 일치 ✓
- `:1733` `SELECT no FROM general WHERE nation=%i AND no != %i` + `:1741` 군주 append
  → 골든 장수 순서 `[8, 9, 19, 37, 58, 71, 76, 106, 108, 110, 115, 129, 134, 136, 137, 151, 157, 160, 105]`
  (타 장수 + 군주 105 LAST) ✓. 주의: 이 쿼리에는 `ORDER BY` 가 없다 — "asc PK" 는 InnoDB PK 스캔의 부수 효과이지
  PHP 가 보장하는 계약이 아니다. Kotlin 이 asc PK 정렬을 명시 계약으로 단언하는 것은 관측된 캡처와는 맞지만 잠재 리스크로 남긴다.
- `process_war.php:645` / `:656` 의 `join_mode != 'onlyRandom'` 단락 → 골든 쌍의 message 발부 유무 차이와 일치 ✓
