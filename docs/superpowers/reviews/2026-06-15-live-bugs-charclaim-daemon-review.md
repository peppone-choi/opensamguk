# Cross-agent critique — 라이브 버그 2종 fix (장수 등록 폴백 · 데몬 상태 미로드)

- **날짜**: 2026-06-15
- **브랜치**: `loop-parity-2026-06-14-c`
- **범위**: 백엔드 wiring — `app/game-api`(FrontInfoController), `app/gateway-api`(AdminController·DeployService). fresh 스택(ba626ed1)서도 재현 = 코드 갭(stale 아님).

## 근본원인 + fix (독립 조사 에이전트 bug-investigator, 라이브 증거)

**BUG1 장수 등록 폴백** — `FrontInfoController.kt:426` `npcMode = intOrNull(config["npcmode"])`가 미기재시 raw null 반환 → FE `CharacterClaim.tsx:20` `npcMode ?? 1` = 1 → 빙의(possession) 모드 오판 → 678장 빙의 그리드 렌더(장수생성 폼 아님). 형제 `ServerBasicInfoController.kt:74`는 `?: 0`(생성모드) — **두 엔드포인트 불일치가 결함**. fix: FrontInfoController도 `?: 0` 정렬 → canSelectNpc(===1) false → 장수생성 표면. 백엔드 1줄.

**BUG2 데몬/서버 상태 미로드** — gateway admin "서버 제어"가 `/api/proxy/admin/turn-daemon/{status,pause,resume}` 호출 → gateway-api `AdminController`에 매핑 無(실 `StatusController @RequestMapping("/admin/turn-daemon")`는 game-engine 서비스에 있고 gateway 미프록시) → 404 → "데몬 상태를 불러오지 못했습니다." fix: gateway-api `AdminController` +3 매핑 + `DeployService.proxyEngine()`가 `ServerDef.gameEngineUrl`로 RestClient 직결(VersionService `/actuator/info` 패턴 동형). game-engine은 무인증 internal-only → 토큰 없이 포워드, gateway `/admin/**` ADMIN 게이트 상속. 레지스트리 변경 無.

## 검증

- **컴파일**: BUG1 `:app:game-api`(`?: 0`는 Int? 안전), BUG2 `:app:gateway-api:compileKotlin` **BUILD SUCCESSFUL**.
- **CI**: 이 PR의 jvm = 전모듈 컴파일+테스트 게이트.
- 두 fix 모두 기존 동형 패턴(sibling 엔드포인트 `?: 0` / VersionService 엔진 직결) 정렬 — 신규 추상화 0, 날조 0.
- 조사=bug-investigator(독립, 라이브 HTTPS 프로브 + 코드 추적), 빌드=bug2-builder(컴파일 검증).

## 배포 경로

- BUG2(gateway-api)=**shared 스택** → main 머지시 CI 자동배포(gateway-api recreate).
- BUG1(game-api)=**s1 고정태그** → s1 bump 필요(별도).

## Verdict: cleared

블로커 0. 두 wiring 갭을 sibling/VersionService 동형 패턴으로 최소 수정. 컴파일 green. CI jvm green 후 머지(gateway 자동배포)+s1 bump(game-api).

## 스코프 확장 — loop-parity 배치 12바퀴 (FE 패러티 + 엔진/플러시/재난)

이 PR(#85)은 위 백엔드 wiring 2종에 더해 bug-parity-2026-06-15 루프 바퀴 1–15를 합본한다. 변경 행위영역(behavior areas)과 검증 증거를 명시한다(provider-agnostic guard parity-evidence 경로).

### FE 행위영역 — `web/game` · `web/gateway`
- **`web/game`**: city·diplomacy·generals·history·inherit·join·rankings/npcs·troop 페이지 + MapViewer·NationBasicCard 컴포넌트 + types — 표시/계약/가드/포맷 패러티(legacy `hwe/ts` Vue grand truth 대조). 바퀴 1–10·12.
- **`web/gateway`**: lobby(입장행 초상 렌더)·login(next open-redirect 차단)·MapPreview(재난 state 회귀 복원) — 바퀴 11·12 + 로비맵 회귀.
- **두 맵뷰어 불변식**: MapViewer(`web/game`)↔MapPreview(`web/gateway`)는 데이터만 다르고 기능·툴팁 동일 — 동시 정정(바퀴 1·5).

### 검증 증거 (결정적)
- FE: `web/game` + `web/gateway` `tsc --noEmit` 양쪽 EXIT 0/0에러, `web/game` vitest **65/65** green. CI `web (game)`·`web (gateway)` 빌드 둘다 PASS.
- 바퀴별 가설→점수→채점자→판정 원장: `docs/loops/bug-parity-2026-06-15/LEDGER.md`(바퀴 1–15 전부 "채택", fresh 게이트 결정적 채점).
- 백엔드(엔진 turn-loop·flush·disaster): `:app:game-engine:test` 372/0, `:infra:test` JdbcFlushExecutorIT 6/0(Docker IT), `:logic:test` RaiseDisasterTest 10/0 — CI `jvm` PASS.

### 패러티 규율
모든 FE 변경은 표시/계약/가드 레이어(RNG draw·로그·골든 불변). 엔진 turn-loop 수정은 8 fixture 재정합(약화 0)으로 닫았고 AiSelectionGate 불변. 골든·테스트 약화 0, 날조 0.
