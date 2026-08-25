# Identity SoT Single Source Design

## Decision

`gateway-postgres.users` is the sole source of truth for account display identity. `game-api` must not read its local `users` table for join, possession, or select-pool behavior. The local table and its Flyway history remain untouched in this change.

## Gateway profile contract

`gateway-api` exposes `GET /internal/users/{id}/profile` to trusted services only. The response contains exactly:

```json
{"name":"display-name","grade":1,"picture":null,"imageServer":0}
```

The mapping lives only in `gateway-api`:

- `name`: non-blank `nickname`, otherwise `username`.
- `grade`: `grade ?: if (role == "ADMIN") 6 else 1`, then `coerceIn(0, 9)`.
- `picture`: the stored picture value.
- `imageServer`: `1` when `imgsvr` is true, otherwise `0`.

Unknown positive user IDs return 404. The route never serializes password, email, role, block state, or any other `UserEntity` field.

## Service authentication

The internal route uses a dedicated shared bearer token, not an end-user access token. `game-api` receives only that service token and does not receive the gateway JWT private key. `gateway-api` compares the configured token in constant time and rejects missing or wrong credentials with 401. Empty production configuration fails closed.

This is intentionally smaller than adding service-token minting and rotation to the existing user JWT provider. Token distribution remains an environment concern and must be wired separately in `opensamguk-docker`.

## Game profile client and cache

`MemberProfileClient.get(userId)` calls the gateway endpoint with the service bearer token and returns the response DTO. A Redis-backed decorator caches successful profiles for 120 seconds. This is inside the requested 60–300 second range and limits a nickname or avatar update delay to two minutes while absorbing short gateway restarts.

The cache comment explicitly states that identity changes can remain stale for the TTL. Missing users are not cached. On gateway unavailability or invalid upstream responses, a cache hit is served; a cache miss raises a dedicated unavailable exception mapped to HTTP 503. It must not become 401, because that would misdiagnose an upstream dependency failure as user authentication failure.

Join, possession, and select-pool consume the client and no longer inject or reference `UserRepository`. Possession continues writing the fetched `name` into `owner_name`; that field remains the historical game fact recorded at claim time.

## Deployment wiring

The shared and per-server compose projects already join the external `opensamguk-net`, so `gateway-api:8080` is reachable by DNS. The per-server `game-api` service still needs `GATEWAY_API_URL` and the shared internal token injected. That change belongs to a separate `opensamguk-docker` worktree, report, commit, and PR dependency.

## Verification

Every behavior is introduced test-first and its expected failure is recorded before production implementation. Gateway tests pin 200 shape, 404, missing authentication rejection, and explicit absence of sensitive fields. Game tests pin all three controller flows, cached fallback, and cache-miss 503. Final verification runs Java 21 from the repository root with `--rerun-tasks`, checks the output tail, and inspects fresh `build/test-results/test/*.xml` mtimes and failure/error counts.

No push, merge, deploy, database row deletion, table drop, or production data mutation is part of this work.
