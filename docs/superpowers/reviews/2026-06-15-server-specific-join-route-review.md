# Server-Specific Join Route Review

Verdict: cleared

Scope: web/gateway live lobby routing for server-specific character creation.

Evidence:
- Live pre-measure: `https://sam.peppone.dev/api/servers` returned `s1` with `gameUrl: "/game"`, so the lobby could build generic `/game/join`.
- Live pre-measure: `https://sam.peppone.dev/game/s1/join` returned HTTP 404 before the s1 stateless bounce.
- Local function cases: `resolveServerGameBase` maps `/game` + `s1` to `/game/s1`; `resolveServerGamePath` maps `/game/s1` + `join` to `/game/s1/join` and `/game?server=bbae` + `join` to `/game/join?server=bbae`.
- Local route handler: `SERVER_REGISTRY_JSON=[{"id":"s1","gameUrl":"/game"},{"id":"bbae"}]` made `/api/servers` return `s1=/game/s1` and `bbae=/game?server=bbae`.
- Verification: `web/gateway` `tsc --noEmit`, `git diff --check`, and `pnpm build` passed.

Fresh Review:
- First pass: BLOCK. The reviewer found that a query-form server URL such as `/game?server=bbae` would become `/game?server=bbae/join` if child paths were string-concatenated.
- Fix applied: added `resolveServerGamePath` so child paths are inserted before query/hash suffixes.
- Second pass: PASS. The reviewer found no remaining blocking issues and confirmed no `/game/s1/game` regression.

Risk:
- The fix is intentionally limited to gateway routing URL construction. It does not change game-api, game-engine, auth, or server config semantics.
