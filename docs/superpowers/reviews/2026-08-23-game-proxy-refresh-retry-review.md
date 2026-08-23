# game proxy 401 refresh-retry critique

Scope: `web/game` game proxy route handler(`app/api/game/[...path]/route.ts`), 공유 `sam_refresh` 헬퍼(`web/game/lib/authRefresh.ts`), `app/api/auth/me/route.ts` 리팩터, 회귀 테스트(`__tests__/game-api-proxy-route.test.ts`).

Verdict: cleared

- Root cause: `sam_access`는 15분 수명인데 장수 생성처럼 입력에 시간이 걸리는 POST 폼이 그 경계를 넘겨 upstream 401을 그대로 클라이언트에 전달했다. `common/.../auth/GatewayJwtSecurity.kt:120-129`는 token_type/expiration/subject/role만 검증하므로 nickname staleness 등 다른 원인은 배제된다(검증 경로 자체에 없음).
- Fix: `/api/auth/me`가 이미 쓰던 401 → `sam_refresh` → gateway `/auth/refresh` → 갱신 쿠키 세팅 패턴을 `web/game/lib/authRefresh.ts::refreshAccessToken()` 공유 헬퍼로 뽑아 `app/api/auth/me/route.ts`와 game proxy `forward()` 양쪽이 재사용한다. game proxy는 401 시 정확히 1회만 재시도한다(무한루프 없음).
- Non-idempotent body: `forward()`는 `req.text()`로 요청 본문을 fetch 이전에 이미 문자열로 버퍼링해 두므로(스트림 아님), 재시도 시 동일 `init.body`를 그대로 재사용해도 빈 본문으로 나가지 않는다.
- Evidence: `npx tsc --noEmit` clean. `npx next lint` — 변경 파일에 대한 신규 warning/error 없음(사전 존재 warning만). `npx vitest run` — 76 files / 433 tests 전부 통과(기존 431 + 신규 2: 401→refresh→재시도+body 재전송+쿠키 갱신 케이스, refresh 실패 시 재시도 없이 원 401 반환 케이스).
- Scope 제외: `web/gateway`, board 프록시는 이번 범위 밖 — 변경 없음. SSE(`sse/turn`) 스트림 도중 만료 재시도는 이번 버그(장수 생성 폼 제출) 범위 밖.
