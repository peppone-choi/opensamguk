# 사령부 예약 `che_발령` brief postFilter Review

page-parity 루프 바퀴 76. FE-only(`web/game`), 백엔드/DTO 무변경. fresh 적대 패러티
리뷰어(별도 컨텍스트, agentId ab1bf54)가 1차 FAIL → 수정 → 재리뷰 PASS로 채점했다.

## Verdict: cleared

Accepted locally. 표시-계층(display-layer) 패러티 결함 + 그 결함을 드러낸 josa 버그를 함께
닫았다. 사령부 예약 명령 목록이 `che_발령`(부대 발령)을 서버 저장 generic brief
`【장수명】【도시명】로 발령`으로만 렌더했는데, legacy Vue는 client에서 `postFilterNationCommand`로
부대장 대상일 때 `《부대명》【도시명】{조사} 발령`으로 후처리한다. 변환에 필요한 데이터
(troopList·arg·cityConst)는 이미 클라에 내려오므로 FE에서 충실 적용한다.

## Evidence

- PHP/hwe UI grand truth: `legacy/devsam-core/hwe/ts/PageChiefCenter.vue:154-176`
  — `officer.turn.map(postFilterNationCommand)`, `postFilterNationCommandGen(tableObj.troopList, gameConstStore)`.
- 변환 원본: `legacy/devsam-core/hwe/ts/utilGame/postFilterNationCommandGen.ts`
  (action != 'che_발령' → passthrough; destGeneralID not in troopList → passthrough; else
  `《${troopName}》【${destCityName}】${josaRo} 발령`).
- 저장 brief: `legacy/devsam-core/hwe/sammo/Command/Nation/che_발령.php:124 getBrief()` =
  `【${destGeneralName}】【${destCityName}】${josaRo} ${commandName}` (postFilter와 다른 generic 문자열).
- josa 정본: `legacy/devsam-core/hwe/ts/util/JosaUtil.ts:476-487 checkCode(code, isRo=true)`
  — `jongsung===0 → 로`, `isRo && jongsung===8(ㄹ받침) → 로`, else `으로`.
- opensamguk 데이터 가용성: `ChiefReservedResponse`(F4Dto.kt)가 `troopList: Map<String,String>`
  (troopLeaderId→부대명)와 reserved turn별 `arg`(destGeneralID/destCityID)를 이미 내려주고,
  cityConst는 `/api/const`(바퀴74와 동일 소스)로 가용 → API DTO 추가 0.
- 이미 포팅된 헬퍼 `web/game/lib/utilGame/postFilterNationCommandGen.ts`는 존재하나 어느
  페이지도 소비하지 않았고, 그 `josaRo`가 `(code-0xAC00)%28 !== 0 ? '으로':'로'` 근사라
  ㄹ받침(jongsung===8) 예외를 누락하고 있었다.

## Root Cause

`web/game/app/game/chief-center/page.tsx`가 `reservedTurns[].brief`(서버 generic brief)를
그대로 `dangerouslySetInnerHTML` 렌더하고 postFilter를 적용하지 않았다(주석은 오히려
"postFilterNationCommand applied server-side"라고 잘못 기술). 서버는 generic brief를 저장할
뿐 client postFilter를 수행하지 않으므로, 부대 발령이 `《부대명》` 없이 `【장수명】` 형태로 떴다.

## Change

- `web/game/lib/utilGame/postFilterNationCommandGen.ts`: `josaRo`를 legacy `checkCode`로 충실
  교정 — `const jongsung=(code-0xac00)%28; return jongsung===0||jongsung===8 ? '로':'으로';`.
  (scenario_1010 도시명은 전부 한글 음절이라 checkCode 한글 경로로 충분 — Hanja/ASCII 종성 없음.)
- `web/game/app/game/chief-center/page.tsx`: `api.gameConst()`로 cityConst 1회 로드 + `troopList`를
  number-키 맵으로 변환 → `postFilterNationCommandGen(troopMap, cityConst)` 생성 → ChiefPostCard에서
  `reservedTurns`를 한 지점 변환(`{...t, brief: out.brief}`)해 편집/read-only **양 브랜치**가
  동일 후처리 brief를 공유. 미로드/미스/non-발령은 brief 원본 유지(graceful, 날조 아님).
  주석의 "server-side" 오기도 정정. API/DTO/요청 무변경.
- `web/game/__tests__/postFilterNationCommand.test.ts`(신규): 산월(ㄹ받침)→`로`, 성도(무받침)→`로`,
  업(ㅂ받침)→`으로`, non-troop·non-발령 passthrough 회귀 락.

## Verification

게이트(web/game 동결 골든):
- `pnpm typecheck` — 통과(0 에러).
- `pnpm test` — **23 files / 107 tests green**(신규 postFilter 5 포함).
- `pnpm build` — 통과.
- `git diff --check` — clean.

Fresh 적대 패러티 리뷰어(ab1bf54, 별도 컨텍스트):
- 1차: **FAIL** — josa ㄹ받침 결함(`산월` id67 → port `…으로 발령` vs PHP `…로 발령`), 나머지
  7개 dim(troopList 키 coercion·arg null·non-발령 passthrough·양 브랜치·no-API-divergence·
  id-키 cityConst lookup·no-fabrication) 전부 PASS.
- josaRo 수정 후 재리뷰: **josa PASS** — 94개 scenario_1010 도시 전수 재스윕 0 divergence,
  산월 교정 확인. tooltip drop은 P2 latent(opensamguk 사전 무-tooltip, 렌더 brief byte-break 아님).

LEDGER 정본: `docs/loops/page-parity/LEDGER.md` 바퀴 76.

## Remaining Risk

머지 후 `web-game` 자동 pull+bounce(프론트 자동배포)로 라이브 반영. 백엔드 무변경이라 핀/리시드
불요. 현재 prod 월드에 `che_발령`을 부대장 대상으로 예약한 국가가 없으면 변환 적용을 직접
관측 못 할 수 있음 — 그 경우 회귀 테스트(산월 ㄹ받침 락)가 1차 증거. tooltip(P2)은 백로그.
