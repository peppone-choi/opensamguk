# 부대 편성 액션 라벨 패러티 Review

page-parity 루프 바퀴 77. FE-only(`web/game`), 백엔드/DTO 무변경. fresh 적대 패러티
리뷰어(별도 컨텍스트, agentId a4cf8887)가 byte-identity·collateral·잔여발산을 codepoint
단위로 검증해 **PASS**로 채점했다.

## Verdict: cleared

Accepted locally. 부대 편성 페이지의 두 핵심 액션 버튼 라벨이 legacy verbatim과 byte-break
나 있었다 — join 버튼이 legacy `부대 탑승` 대신 `가입`(모달 `부대 가입`), create 버튼이
legacy `부대 창설` 대신 `결성`(헤더/모달 `부대 결성`). 둘 다 legacy `PageTroop.vue`와
정확히 일치하도록 복원했다. "최소한의 변경" 지시에 맞춘 단일 파일 라벨 교정.

## Evidence

- PHP/Vue UI grand truth: `legacy/devsam-core/hwe/ts/PageTroop.vue`
  - `:58` join BButton(`v-if="!me.troop"`) → `부대 탑승`.
  - `:113`(헤더 div) + `:118`(BButton) → `부대 창설`.
  - `:63` `부대 해산`/`부대 탈퇴`, `:69`/`:73` `부대명 변경`, `:3` title `부대 편성` — 전부 이미 일치.
  - `:388` 토스트 `...부대에 가입했습니다.` 는 문장이지 버튼 라벨이 아님(혼동 금지).
- 포트 발산: `web/game/app/game/troop/page.tsx`
  - `:168-169` join: `label: '부대 가입'` + 버튼 텍스트 `가입`.
  - `:335`(헤더 strong) + `:344-345`(모달 label + 버튼 텍스트) create: `부대 결성`/`결성`.
- codepoint 검증(리뷰어): 신규 `부대 탑승`=`[bd80 b300 0020 d0d1 c2b9]`, `부대 창설`=
  `[bd80 b300 0020 cc3d c124]` — legacy와 byte-identical. half-width space(`0020`),
  zero-width/hidden char·ellipsis·trailing whitespace 없음.

## Root Cause

포트가 부대 join/create 액션 버튼에 legacy 용어(`탑승`/`창설`) 대신 일반 용어(`가입`/`결성`)를
임의로 사용했다. devsam 모의전투에서 부대 합류는 `탑승`, 부대 신설은 `창설`이 정본 — 플레이어
가시 1차 액션 버튼이라 byte-break이 곧바로 화면에 노출됐다.

## Change

`web/game/app/game/troop/page.tsx` 8라인:
- join 버튼 텍스트 `가입` → `부대 탑승`, 모달 label `부대 가입` → `부대 탑승`(`:168-169`).
- create 헤더 strong `부대 결성` → `부대 창설`(`:335`), 모달 label `부대 결성` → `부대 창설`,
  버튼 텍스트 `결성` → `부대 창설`(`:344-345`).
- 코드 주석 2곳(`:12-13`, `:331`)을 legacy 용어로 정정(가시 영향 없음, 일관성).
- 명령코드(`troopJoin`/`troopNew`/`troopExit`/`troopSetName`)·extraArgs·게이팅·josa 무변경.
- 해산/탈퇴/부대명 변경/title 은 이미 일치라 미변경. 추방 패널 **구조** 차이(legacy
  `부대원 추방...` 서브패널 vs 포트 인라인 per-member 추방)는 KNOWN·비-최소 → 이번 바퀴 out-of-scope.

## Verification

게이트(web/game 동결 골든):
- `pnpm typecheck` — 통과(0 에러).
- `pnpm test` — **23 files / 107 tests green**(회귀 0).
- `pnpm build` — 통과(`next-env.d.ts` 빌드 산출물 복원).
- `git diff --check` — clean. 변경 파일 = `troop/page.tsx` 단일.

Fresh 적대 패러티 리뷰어(a4cf8887, 별도 컨텍스트):
- byte-identity CONFIRMED(codepoint), 잔여 `가입`/`결성` 0건, collateral 0(8라인 정확,
  명령코드·extraArgs byte-preserved), 타 라벨 전수 대조 발산 0. **VERDICT: PASS**.

LEDGER 정본: `docs/loops/page-parity/LEDGER.md` 바퀴 77.

## Remaining Risk

머지 후 `web-game` 자동 pull+bounce(프론트 자동배포)로 라이브 반영. 백엔드 무변경이라 핀/리시드
불요. 추방 패널 구조 패러티(서브패널 전개)는 백로그(별도 바퀴, 비-최소).
