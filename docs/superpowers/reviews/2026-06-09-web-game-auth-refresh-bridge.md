# Web Game Auth Refresh Bridge Review

## Scope

`web/game/app/api/auth/me/route.ts` now mirrors the gateway-owned session recovery flow for the game frontend:

- validate `sam_access` with gateway-api `/auth/me`;
- on `401` or `403`, refresh with same-origin `sam_refresh` via gateway-api `/auth/refresh`;
- rotate httpOnly `sam_access` and `sam_refresh` cookies on refresh success;
- clear both cookies only after true auth failure;
- preserve cookies on gateway/network transient failures.

No backend auth policy, game command logic, PHP parity logic, daemon write path, or UI design surface changed.

## Source Of Truth

- Gateway auth/cookie contract: `web/gateway/app/api/auth/me/route.ts` and `web/gateway/lib/cookies.ts`.
- Game frontend auth bridge: `web/game/app/api/auth/me/route.ts` and `web/game/lib/cookies.ts`.
- Security regression: renewed token values must not appear in browser-readable `x-middleware-set-cookie`.

## Evidence

- RED route behavior: `.omx/evidence/authentication/red-auth-me-route.txt`.
- RED security regression: `.omx/evidence/authentication/red-x-middleware-cookie.txt`.
- GREEN targeted route test: `.omx/evidence/authentication/final-auth-me-route-test.txt`.
- Full `web/game` tests: `.omx/evidence/authentication/final-web-game-test-suite.txt`.
- Typecheck: `.omx/evidence/authentication/final-typecheck-web-game-2.txt`.
- HTTP happy path: `.omx/evidence/authentication/http-refresh-success-no-mirror.txt`.
- HTTP invalid refresh: `.omx/evidence/authentication/http-refresh-invalid-no-mirror.txt`.
- HTTP transient failure: `.omx/evidence/authentication/http-transient-failure-no-mirror.txt`.
- Cleanup receipt: `.omx/evidence/authentication/http-qa-cleanup.txt`.

## Residual Risk

Production must continue setting `COOKIE_SECURE=true` for HTTPS deployments, matching the existing gateway cookie contract.
