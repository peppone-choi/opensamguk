# 입구(로그인+로비) devsam '제 전황' 멀티서버 재설계 — 2026-06-05

레퍼런스: **https://sam.hided.net/sam/** (devsam 로그인 페이지 = 헤더 + 로그인폼 + "제 전황"(세계지도 아이콘/깃발 + 전황 로그) + 푸터). 우리 입구를 이 형태로 맞춘다. **로그인 + 로비 둘 다** 적용.

## 사용자 결정 (이 세션)
- 맵: 점(circle) → **아이콘/깃발 정적 마커**(성 cast_<lv>.gif + 국가색 깃발 4프레임 + 수도별 event51 + 오오라). hover 시 도시정보 div(레거시 `.city_tooltip` = 도시명 + 【레벨】 + 소속국).
- **상단 서버 전환 탭** — 로그인+로비 둘 다.
- **전황 로그** — 맵 아래 최근 이벤트(탭과 같이 빌드).
- 새 **fresh 서버 "빼섭"**(94도시·공백지 70, 년1월 시작, devsam형 풀맵). 기존 통일(main, year 182)은 별도 탭 유지.

## 핵심 제약 (발견)
- **공백지 누락 원인**: 라이브 prod DB는 70 공백지가 seed 파일(cities_1010.json)에 추가(2026-06-05)되기 **전** 구 시드(24도시)로 굳음. 멱등 시드라 갱신 미적용. → **fresh 시드된 서버에서만 공백지 표출**(렌더/좌표 버그 아님). che.json은 94도시 전부 좌표 보유.
- **멀티서버 = 서버별 독립 백엔드 스택**(`ServerRegistry`: game-engine+game-api+db+redis, 자기 IMAGE_TAG, gateway 공유). fresh 스택은 빈 world_state라 scenario_1010(94도시·공백지70) **자동 시드**.
- **route.ts per-server 버그**: `/api/server-map/[id]`가 `process.env.GAME_API_ORIGIN ?? server.gameApiUrl` — env가 per-server URL을 덮어씀 → 모든 서버가 같은 game-api로 프록시. 멀티서버 전에 `server.gameApiUrl` 우선으로 수정 필요(단일서버 박스는 GAME_API_ORIGIN env로 dev-default localhost 오버라이드 중이라, per-server URL을 박스 내부주소로 baked 해야 함).
- servers.json은 **빌드타임 baked**(박스도 repo 값 사용) — per-server gameApiUrl을 환경별로 분기해야 함.

## 단계

### ✅ 1단계 — 프론트 프레임 (이번 세션, 단일서버로 배포가능)
- `components/MapPreview.tsx` — 점→정적 마커(아이콘/깃발/수도별/오오라) + 커서추종 hover 툴팁 + serverName prop. 공백지(nationId=0)=회색 오오라(데이터 있으면 표출).
- `components/ServerBoard.tsx` — 서버탭 + MapPreview + ServerLog(공용, 로그인+로비).
- `components/ServerLog.tsx` — 전황 로그, `/api/server-log/[id]` 프록시(API 전까지 '전황 보고 준비 중' graceful).
- `lib/flagTint.ts` + `public/icons/` + `public/flags/` — web/game에서 복사.
- CSS: 마커 클래스(.city-*) + .server-board/.server-tabs/.server-tab/.server-log.
- login/lobby 페이지 배선(MapPreview→ServerBoard).
- **빌드 green 확인 → 배포**(기존 통일 서버 1탭으로 깔끔 표출).

### ⬜ 2단계 — "빼섭" 백엔드 스택 (인프라, opensamguk-docker + 박스)
- opensamguk-docker compose에 2번째 스택 추가: `bbae-db` / `bbae-game-api`(18082?) / `bbae-game-engine` — fresh scenario_1010 시드(94도시·공백지70 자동).
- `SERVER_REGISTRY_JSON` env에 빼섭 추가(gameApiUrl/gameEngineUrl 내부주소).
- nginx: 빼섭 game-api/game-frontend 라우팅(server param 또는 경로).
- 리소스: t3.large가 2스택(engine+api+db+redis ×2) 감당하는지 확인.

### ⬜ 3단계 — route 멀티서버화 + servers.json per-server URL
- route.ts: `server.gameApiUrl` 우선(env는 단일서버 폴백). 환경별 servers.json(또는 SERVER_REGISTRY 기반 동적 server list API).
- servers.json: 통일=http://game-api:18080, 빼섭=http://bbae-game-api:18080 (박스 내부) — 빌드 분기 or 런타임 config.

### ⬜ 4단계 — 전황 read API (전황 로그 데이터)
- game-api: 월드 최근 이벤트(정복/멸망/전투 로그) read 엔드포인트.
- 게이트웨이 `/api/server-log/[id]` route handler 프록시(맵 프리뷰 패턴).

## 미해결/결정 필요
- 2단계 리소스(t3.large 2스택) — 부족 시 인스턴스 업 or 단일스택 재시드(통일 폐기)로 대체.
- 전황 로그 데이터 소스(월드 로그 테이블) 확인.
