# Preserve missing portrait status through servlet error dispatch

## Approved scope and cause

Authenticated requests for `/auth/account/profile-icon/crops` and `/source` must return 404 when the account has no personal portrait bundle. The service already raises `ResponseStatusException(NOT_FOUND)`. With stateless authentication, the servlet ERROR redispatch does not rerun the JWT `OncePerRequestFilter`; the catch-all authenticated authorization rule then replaces the original status with 401.

An embedded-server regression reproduced this with a real test JWT: `/auth/me` returned 200, while both missing portrait endpoints returned 401. MockMvc alone does not execute the container error dispatch and would miss the failure.

## Implementation and acceptance

1. Add real HTTP regressions for both missing portrait resources using the existing repository and JWT provider; require 404 status and JSON status.
2. Permit `DispatcherType.ERROR` in the gateway security chain so Spring can render the already-determined error status. External requests to `/error` remain subject to authentication.
3. Verify anonymous and invalid-token portrait reads still return 401, and run existing portrait/admin/token security tests.

No new portrait data, credential handling, authorization roles, or production changes are involved. The dispatcher rule also preserves other already-determined error statuses; it does not authorize the original protected request.

## Evidence

- RED: `ProfileIconMissingStatusIT`, 5 tests, both authenticated missing-resource cases failed with actual401/expected404; unauthenticated and direct-error guards passed.
- GREEN: missing-status embedded HTTP5 + existing portrait HTTP/security7 + admin reconciliation security3 =15 tests, zero failures/errors/skips. Build successful in1m29s. `git diff --check` passed. Independent parent review CLEAN.
