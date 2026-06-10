# Hardcode Stage-3 Canonical Constants Review (D3-05/06 app, D3-04 web)

Verdict: cleared

## Scope

- `app/game-api/src/main/kotlin/opensamguk/gameapi/controller/GetConstController.kt`
- `app/game-api/src/main/kotlin/opensamguk/gameapi/read/F4StateText.kt`
- `app/game-api/src/main/kotlin/opensamguk/gameapi/dto/GetConstDto.kt`
- `app/game-api/src/main/kotlin/opensamguk/gameapi/controller/MapPreviewController.kt`
- `infra/src/main/kotlin/opensamguk/infra/seed/MapJson.kt`
- `web/game/app/game/nation/page.tsx` · `web/game/lib/types.ts` · `web/game/__tests__/nation-inherit-costs.test.tsx`
- `docs/superpowers/gap/HARDCODE_INVENTORY.md` (3건 해소 기록)

## Implementer claim

HARDCODE_INVENTORY 단계 3(정본 상수/resolver 단일 소스화) 3건을 닫았다.

1. **D3-06(app)** — GetConstController의 인라인 `OFFICER_LEVEL_TEXT` 고정 맵
   (5→참모, 6→장군, 7→총사령관, 11→군주대리 — 국가레벨 비의존, 정본 모순)을 삭제.
   정본 resolver `F4StateText`의 단일 테이블에서 `officerLevelTextDefault()` +
   `officerLevelTextByNationLevel()`을 파생 직렬화(사본 없음).
2. **D3-05(app)** — `mapWidth=1000`/`mapHeight=714` 컨트롤러 리터럴 삭제.
   `MapJson.loadFromClasspath(GameConst.mapName)` 공유 로더로 커밋된
   `map/<code>.json` 리소스에서 dims를 읽고, MapPreviewController의 사설
   리소스-read도 같은 로더로 위임(파싱 단일화).
3. **D3-04(web)** — nation 페이지 `INHERIT_COSTS = [0,200,600,1200,2000,3000]`
   로컬 사본 삭제. 이미 정본을 직렬화하는 `InheritPointResponse.inheritActionCost.buff`
   (= `GameConst.inheritBuffPoints`, common GameConst.kt:320)를 소비.
   API 미수신 시 비용 표기 생략(사본 폴백 없음 — 날조 금지).

## Self-check against PHP refs

- **getOfficerLevelText** — `legacy/devsam-core/hwe/func_converter.php:522-565`:
  `$code = $nlevel * 100 + $officerLevel`, officer 0..4는 `$nlevel = 0` 강제,
  미정의 코드는 `'-'`. F4StateText의 기존 private 테이블(812 군주 … 0 재야)과
  엔트리 단위 대조 — 일치(이번 변경은 테이블을 만지지 않고 파생 함수만 추가).
- **PHP GetConst.php는 직책 라벨을 보내지 않는다** —
  `legacy/devsam-core/hwe/sammo/API/Global/GetConst.php::genConstData()`는
  `get_class_vars('\sammo\GameConst')` 덤프 + unit/city consts + iActionInfo만
  반환(officer text 없음 — func_converter는 GameConst가 아님). 따라서 와이어
  모양은 레거시 프론트 정본 `hwe/ts/utilGame/formatOfficerLevelText.ts`의
  `OfficerLevelMapDefault`(nlevel=8 기본열 812..805 + 공통 0..4) +
  `OfficerLevelMapByNationLevel`(nlevel 7..0 → 12..5)을 미러링했다 — 날조 모양 아님.
- **PHP-vs-TS divergence 1건 — PHP 승 적용**: 미정의 코드(예: 506)에서 TS는
  기본열로 폴백하지만 PHP는 `'-'`를 돌려준다. 직렬화에서 미정의 코드는 키 자체를
  생략(소비자가 폴백을 날조하지 않도록), 테스트가 `5.6`/`1.10` 부재를 고정.
- **inheritBuffPoints** — `legacy/devsam-core/hwe/sammo/GameConstBase.php:240`
  `[0, 200, 600, 1200, 2000, 3000]` == Kotlin `GameConst.inheritBuffPoints`
  (이미 byte-faithful, 이번 변경에서 값 변경 없음). 프론트 전달 경로 정본 =
  `legacy/devsam-core/hwe/v_inheritPoint.php:95`
  (`'inheritActionCost' => ['buff' => GameConst::$inheritBuffPoints, ...]`) —
  opensamguk 대응인 InheritPointController가 동일 필드를 이미 직렬화하고 있었고,
  web만 사본을 쓰고 있었다. 비용 차액식은 `BuyHiddenBuff.php:68`
  (`$inheritBuffPoints[$level] - $inheritBuffPoints[$prevLevel]`)과 동일.
- **map dims** — 게임플레이 패러티값이 아니라 표시 전용(TS 구조 오라클 native
  700×500을 ×10/7 확대한 1000×714, `infra/src/main/resources/map/che.json` _meta
  문서화). 정본은 커밋된 리소스이고 컨트롤러는 더 이상 값을 모른다. RNG/로그/골든
  무관.

## TDD evidence (red → green)

- D3-06: `GetConstControllerTest.officerLevelText serializes from the canonical
  F4StateText table` — red 확인(`AssertionError at GetConstControllerTest.kt:65`,
  `failures="1"`), 구현 후 green.
- D3-05: `map dims come from the committed map resource` —
  `MapJson.loadFromClasspath` 부재로 `compileTestKotlin FAILED` red, 구현 후 green.
- D3-04(web): `__tests__/nation-inherit-costs.test.tsx` — API 모킹으로 정본과
  다른 배열(`[0,111,333,777,1500,2500]`)을 내려 로컬 사본 사용을 적발
  (`Unable to find … L1 (111P)` red), 페이지 전환 후 green.

## Gate results

- `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :app:game-api:test --rerun-tasks`
  — XML 검증: `GetConstControllerTest tests="4" failures="0" errors="0"`,
  `MapPreviewControllerTest tests="2" failures="0" errors="0"`, 전 스위트
  failures/errors 0 (아래 전체 스위트 합산은 푸시 전 최종 실행 기록 참조).
- `web/game`: `npx pnpm typecheck` clean, `npx pnpm test` — 7 files / 43 tests
  passed (신규 1 포함).

## Risks / non-goals

- `officerLevelTextByNationLevel`는 additive 필드 — 기존 FE 소비자는
  `officerLevelText`(타입만 참조, 실 소비 0건)라 호환 파손 없음.
- 국가레벨 0-9 확장(의도적 divergence)은 이 직렬화의 범위 밖 — F4StateText
  테이블(=PHP 8..0)을 그대로 노출하며 테이블 자체는 변경하지 않았다.
- 데몬 write 경로 무변경(read-only 표면 + 커밋된 정적 리소스만).
