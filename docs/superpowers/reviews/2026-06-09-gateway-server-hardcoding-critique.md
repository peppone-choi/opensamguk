# 2026-06-09 Gateway Server Hardcoding Critique

## Scope

- `web/gateway/components/ServerBoard.tsx`
- `web/gateway/lib/serverRegistry.ts`
- `web/gateway/lib/constants.ts`
- `web/gateway/app/globals.css`
- `docs/superpowers/gap/HARDCODE_INVENTORY.md`

## Verdict: cleared

The gateway no longer carries hardcoded server status or turn-term data in the login/lobby board path. `ServerBoard` renders only admin-created server routing entries, returns `null` when the configured list is empty, and no longer synthesizes status badges from baked config. Lobby rows continue to derive live state from `/api/server-basic-info/[id]`, so `game.isUnited`, `game.turnTerm`, and population/status text stay backend-owned.

## Adversarial Checks

- **No-server invariant:** `SERVERS[0]?.id ?? null` plus `if (!selected) return null` means login/lobby maps, logs, and server tabs disappear when `servers.json` is empty.
- **No baked status source:** `ServerEntry.status`, the tab badge branch, and `.server-tab-badge` CSS were removed. There is no remaining gateway UI path that renders a server status from `servers.json`.
- **No stale turn-term source:** `ServerEntry.turnterm` was removed. Turn-term display remains in lobby server rows through live `server-basic-info`.
- **No dead constants:** `SERVER_STATUS`, `competingLabel`, `timelineYear`, and `timelineUsers` were deleted because their only effect was preserving stale definitions outside the live data path.

## Residual Risk

This does not implement admin-side server creation. It only preserves the empty-server production invariant and removes gateway hardcoding that could contradict future admin-created server state.
