# Web Game Login Origin Review

Verdict: cleared

## Scope

- `docker/web-game.Dockerfile`
- `web/game/components/AuthGate.tsx`
- `docker-compose.production.yml`

## Problem

Production `web-game` images baked `http://localhost:3000` into the client bundle because the Dockerfile defaulted `NEXT_PUBLIC_GATEWAY_URL` to that value at build time. Runtime `NEXT_PUBLIC_GATEWAY_ORIGIN=https://sam.peppone.dev` could not override the already-inlined client bundle, so unauthenticated `/game/spep` visits could redirect to localhost.

## Review

The Dockerfile now leaves `NEXT_PUBLIC_GATEWAY_URL` empty unless a build caller explicitly provides it. `AuthGate` treats an empty configured value as absent and falls back to `window.location.origin`, which preserves same-origin production login redirects. Local Docker Compose still passes `NEXT_PUBLIC_GATEWAY_URL` explicitly, so local game-on-3001 to gateway-on-3000 redirects remain intact.

No legacy gameplay logic, RNG, logs, seed data, or server lifecycle behavior is changed.

## Evidence

- `env -u NEXT_PUBLIC_GATEWAY_URL -u NEXT_PUBLIC_GATEWAY_ORIGIN ASSET_PREFIX=/game pnpm --dir web/game build`
- `rg "localhost:3000" web/game/.next/static/chunks/app/game web/game/.next/server/app/game` returned no matches after the build.
- Existing build warnings are unrelated `jsx-a11y/role-supports-aria-props` and `react-hooks/exhaustive-deps` warnings in existing pages.
