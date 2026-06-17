# JWT provider test expiry debug loop

## Evidence

- Failing surface: `Build + Deploy to EC2` run `27673903290`, job `build-jvm`, step `Build + test JVM`.
- Error: `JwtTokenProviderTest > generate and validate access token()` failed with `ExpiredJwtException` at `JwtTokenProviderTest.kt:18`.
- Working comparison: the same SHA passed the main `CI` workflow `jvm` job, so the product implementation was not deterministically broken; the deploy workflow hit a timing-sensitive test path.

## Root cause

- `JwtTokenProviderTest` used `JwtTokenProvider(secret, 1000L, 2000L)` for normal access/refresh token assertions.
- The test validates the token and then reparses it for user id, username, and role. On a slow CI path, that second or later parse can happen after the 1 second access token expiry.
- The explicit expiry behavior remains covered by the separate `expired token returns false` test with a 1ms provider.

## Change

- Increase the normal-test provider TTL to 60s access / 120s refresh.
- Keep the short-lived provider only in the expiry test.

## Docs

- No README/AGENTS/CLAUDE update: this is a test determinism fix, not a user-facing behavior or architecture rule change.

Verdict: cleared
