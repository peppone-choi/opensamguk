// web/game READS the gateway-issued auth cookie; it never issues or clears tokens.
// The gateway (web/gateway + gateway-api) owns login/refresh/logout and sets this httpOnly cookie.
// On localhost the cookie is host-only (shared across ports → :3001 server side can read it); in prod
// it is shared across a common domain via nginx. JS can't read it (httpOnly) — only server route handlers
// via next/headers cookies().
export const ACCESS_COOKIE = 'sam_access';
