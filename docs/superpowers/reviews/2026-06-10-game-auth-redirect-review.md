# Game Auth Redirect Review

Verdict: cleared

## Scope

- File: `web/game/components/AuthGate.tsx`
- Change: unauthenticated game users no longer fall back to `http://localhost:3000` when no public gateway URL was baked into the client bundle.

## Evidence

- Prod browser proof before the change: `https://sam.peppone.dev/game/spep` redirected to `chrome-error://chromewebdata/` with `ERR_CONNECTION_REFUSED` for `localhost`.
- Prod env proof: server containers had `NEXT_PUBLIC_GATEWAY_ORIGIN=https://sam.peppone.dev`, but `AuthGate` only read `NEXT_PUBLIC_GATEWAY_URL`.
- Same-origin gateway and game deployment should use `window.location.origin` when no build-time public gateway URL exists.

## Parity / Behavior

- Legacy behavior requires unauthenticated users to reach the login flow, not a localhost development origin.
- Auth state, cookie ownership, and `/api/auth/me` behavior are unchanged.
- The redirect still preserves the current game URL in `next`.

## Risk

- Narrow frontend-only change.
- If a future split-domain deployment needs a different login host, it can still set `NEXT_PUBLIC_GATEWAY_URL` or `NEXT_PUBLIC_GATEWAY_ORIGIN`.

## Verification

- `pnpm --dir web/game typecheck`
- Prod lifecycle evidence in this run:
  - `GET https://sam.peppone.dev/api/servers` returned `spep` and `s1`.
  - `GET https://sam.peppone.dev/api/game/api/generals/claimable` for `spep` returned HTTP 200 after game-api refresh.
