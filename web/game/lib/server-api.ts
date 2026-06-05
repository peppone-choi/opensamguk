// Server-only base URLs for web/game route handlers.
// Route handlers run on the Next server (same origin as the browser) so CORS never applies, and
// the httpOnly `sam_access` JWT stays server-side — the browser never holds the token.
//
//  - GAME_API_URL    : game-api (:8081) — the read/precheck/intake/SSE backend (proxied via /api/game).
//  - GATEWAY_API_URL : gateway-api (:8080) — auth/profile; web/game only calls /auth/me on it.
//
// GATEWAY_API_URL / GAME_API_URL are SERVER-ONLY (no NEXT_PUBLIC_ prefix). The browser never sees them.
// 배포 compose 관례는 `*_ORIGIN`(박스 game-frontend는 GAME_API_ORIGIN/NEXT_PUBLIC_GATEWAY_ORIGIN을 주입)
// — 이름 불일치로 컨테이너서 localhost로 폴백해 game-api 미도달(인게임 data fetch 깨짐)했던 prod 버그를
// 막기 위해 `*_URL → *_ORIGIN` 순으로 읽는다(web/gateway lib/server-api와 동일 패턴).
export const GAME_API_URL =
    process.env.GAME_API_URL ?? process.env.GAME_API_ORIGIN ?? 'http://localhost:8081';
export const GATEWAY_API_URL =
    process.env.GATEWAY_API_URL ?? process.env.GATEWAY_API_ORIGIN ?? 'http://localhost:8080';

// Public (browser-visible) gateway ORIGIN used only to build the cross-app login redirect.
// web/game has no login page — unauthenticated users are bounced to the gateway's /login.
export const GATEWAY_PUBLIC_URL =
    process.env.NEXT_PUBLIC_GATEWAY_URL ?? process.env.NEXT_PUBLIC_GATEWAY_ORIGIN ?? 'http://localhost:3000';
