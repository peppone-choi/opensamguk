# City Id→Name And Nation 성향 Korean-Name Review

page-parity 루프 바퀴 74·75. 둘 다 FE-only(`web/game`), 백엔드 무변경. 각 바퀴는
독립 fresh 적대 패러티 리뷰어가 채점했다(아래 Verification).

## Verdict: cleared

Accepted locally (both wheels). 두 결함 모두 표시-계층(display-layer) 패러티 버그다 —
백엔드 데이터/와이어는 이미 올바른 해석값을 제공하는데 프론트가 raw id/code를 그대로
렌더했다. 해석값(`cityConst[id].name`, nationType `typeName`)이 이미 `/api/const`로
클라이언트에 내려오므로 API DTO 변경 없이 FE에서 해석한다.

## 바퀴 74 — 중원정보 분쟁 현황 도시 id→이름

### Evidence
- PHP/hwe UI grand truth: `legacy/devsam-core/hwe/ts/PageGlobalDiplomacy.vue:65-68`
  — `<div class="conflictCityName">{{ gameConstStore?.cityConst[cityID].name }}</div>`
  (분쟁 현황은 도시 **이름**을 렌더).
- PHP source: `legacy/devsam-core/hwe/sammo/API/Global/GetConst.php` — `cityConst`는
  `CityConst::all()` (id→{name,...}).
- opensamguk 데이터 가용성: `web/game/lib/api.ts:265` `gameConst()` → `/api/const`가
  `GameConstResponse.cityConst: GameCityConstItem[]`(id,name 포함)을 이미 내려준다
  (`web/game/lib/types.ts:390-440`). 헬퍼 `web/game/lib/utilGame/formatCityName.ts`도
  이미 존재하나 어느 페이지도 소비하지 않고 있었다.

### Root Cause
`web/game/app/game/global-diplomacy/page.tsx:250`이 분쟁 현황 행에서 raw `도시 {cityId}`
숫자 라벨을 렌더했다(코드 주석 자체가 갭을 명시). F4 conflict tuple은 cityId 숫자만
키로 내려오지만, 도시명 해석에 필요한 cityConst는 별도 `/api/const`로 이미 가용했다.

### Change
- `web/game/app/game/global-diplomacy/page.tsx`: `api.gameConst()` 1회 로드(불변
  상수) → 기존 `formatCityName(cityId, cityConst)` 소비. miss/미로드 시에만 `도시 {id}`
  폴백(날조 아님, legacy는 miss에서 throw하지만 FE는 graceful). API tuple에 cityName을
  추가하지 않음 — 그건 `GetDiplomacy.php` divergence라 금지(바퀴35 백로그 제약).
- troop 주둔도시 절반은 BE가 이미 `leaderCityName`/`cityName`을 직렬화해 해소됨(뷰어 무변경).

## 바퀴 75 — 세력 일람 성향 한글명 + `che_` 누출 차단

### Evidence
- 표시명 충실 소스: `app/game-api/.../controller/GetConstController.kt:175-181`
  `nationTypeItem(code)` → `NationTypeRegistry.resolve(code).typeName` → `iAction.nationType[].name`.
  `FrontInfoController.kt:448-452`도 동일 `NationTypeRegistry.resolve(typeCode).typeName`으로
  성향을 해석한다(일관 소스).
- legacy: `legacy/devsam-core/hwe/sammo/ActionNationType/che_법가.php` `$name='법가'`,
  `GetFrontInfo.php` `buildNationTypeClass()->getName()` — 표시명은 클래스 `$name`,
  파일/클래스명은 `che_<name>` 관례.
- 의도된 미해석 기록: `app/game-api/.../rank/RankReadService.kt:227-229` — kingdomRoster가
  성향을 raw type_code로 내려준다(한글명 헬퍼 미이식, "이 번들 disjoint 범위 밖").
- 기존 strip 관례: `web/game/components/game/GeneralBasicCard.tsx:39` `nameOrCode`가 이미
  `che_` 접두사를 strip한다 — 본 폴백과 동일 관례.

### Root Cause
`web/game/app/game/rankings/kingdoms/page.tsx`의 성향 필드가 raw `{n.typeCode}`(=`che_병가`)를
렌더했다. **유저 지적**("che_ 접두사가 아예 프론트로 나오면 어떡하냐")을 반영해, web/game
전수 스캔으로 raw `che_` 화면 누출 지점을 전수 확인했고 이 1곳뿐이었다(diplomacy=내부
명령코드+한글 label, join=`PERSONALITY_INFO[p].name`, GeneralBasicCard=이미 strip).

### Change
- `web/game/app/game/rankings/kingdoms/page.tsx`: `api.gameConst()`의 `iAction.nationType`에서
  value→name 맵을 1회 빌드 → `typeNameMap.get(n.typeCode) || n.typeCode.replace(/^che_/, '')`.
  1차 경로는 권위 typeName, 폴백(map 미스/미로드/API 실패)도 `che_` strip이라 **어떤
  경로로도 `che_` 접두사가 화면에 나오지 않는다**. 표준 타입은 클래스명=che_+name 관례라
  strip 결과가 충실 `$name`과 일치(날조 아님). BE 무변경.

## Verification

각 바퀴 동일 게이트(web/game 동결 골든):
- `cd web/game && pnpm typecheck` — 통과(0 에러).
- `cd web/game && pnpm test` — 22 files / 102 tests green.
- `cd web/game && pnpm build` — 통과.
- `git diff --check` — clean(각 바퀴 단일 소스 파일).

Fresh 적대 패러티 리뷰어(별도 컨텍스트, 제안 컨텍스트 비공개):
- 바퀴 74 리뷰어 — VERDICT PASS 5/5(faithful·no-API-divergence·correctness·no-regression·no-premature-abstraction).
- 바퀴 75 리뷰어 — VERDICT PASS 5/5(faithful·**전 경로 che_ 누출 0**·no-API-divergence·correctness·no-regression).

LEDGER 정본: `docs/loops/page-parity/LEDGER.md` 바퀴 74·75.

## Remaining Risk

머지 후 `web-game`이 자동 pull+bounce되므로(프론트 자동배포) 라이브에서
`/game/s1/global-diplomacy` 분쟁 현황의 도시명과 `/game/s1/rankings/kingdoms` 성향 한글명을
실측 재확인한다. 백엔드(game-api/game-engine)는 무변경이라 핀/리시드 불요. 현재 prod
월드에 분쟁 도시가 없을 수 있어(conflict[] 빈 경우 섹션 숨김) 도시명은 직접 관측이 불가할
수 있다 — 그 경우 성향 한글명만 실측한다.
