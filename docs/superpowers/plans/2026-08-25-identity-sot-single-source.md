# Identity SoT Single Source Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `gateway-postgres.users` the only runtime source of account display identity for join, possession, and select-pool.

**Architecture:** `gateway-api` owns the projection and exposes an authenticated internal profile endpoint. `game-api` consumes it through a focused client with a 120-second Redis cache and maps unavailable cache misses to 503. Docker wiring is delivered as a separate repository change because the shared and per-server stacks are separate compose projects.

**Tech Stack:** Kotlin, Spring Boot 3, Spring Security, Spring MVC `RestClient`, Spring Data Redis, JUnit 5, MockMvc, Gradle 8.12, Java 21, Docker Compose.

**Spec:** `docs/superpowers/specs/2026-08-25-identity-sot-single-source-design.md`

## Global Constraints

- Run production-code changes only after a behavior test has failed for the expected assertion.
- Do not read or output `.env*`, keys, or token values.
- Do not delete `UserEntity`, `UserRepository`, the game `users` table, or any Flyway migration.
- Do not alter `owner_name` semantics or stored historical values.
- Verify test output tails and fresh XML files; process exit status alone is insufficient.
- Keep the application repository to one logical commit with the required co-author trailer.
- Keep Docker wiring in its own repository commit and report its dependency; do not push, merge, or deploy.

---

### Task 1: Gateway internal profile contract

**Files:**
- Create: `app/gateway-api/src/main/kotlin/opensamguk/gateway/profile/InternalMemberProfileController.kt`
- Create: `app/gateway-api/src/main/kotlin/opensamguk/gateway/security/InternalServiceTokenFilter.kt`
- Create: `app/gateway-api/src/test/kotlin/opensamguk/gateway/profile/InternalMemberProfileControllerTest.kt`
- Modify: `app/gateway-api/src/main/kotlin/opensamguk/gateway/security/SecurityConfig.kt`
- Modify: `app/gateway-api/src/main/resources/application.yml`

**Interfaces:**
- Produces: `data class MemberProfileResponse(val name: String, val grade: Int, val picture: String?, val imageServer: Int)`.
- Produces: `GET /internal/users/{id}/profile`, bearer-authenticated, returning 200 or 404.

- [x] Write MockMvc tests using a real `UserRepository` test double boundary that prove the exact four-field JSON shape, explicit absence of `password`, `email`, `role`, and `blockUntil`, 404 for a missing ID, and 401 for missing/wrong service credentials.
- [x] Run the focused gateway test and record an assertion failure caused by the missing route, not a compilation error.
- [x] Implement the response projection in gateway-api and a constant-time shared-token filter scoped to `/internal/**`; configure Spring Security so user tokens cannot substitute for the service token.
- [x] Run the focused test and inspect its newly written XML for zero failures and errors.

### Task 2: Game profile client, cache, and failure contract

**Files:**
- Create: `app/game-api/src/main/kotlin/opensamguk/gameapi/member/MemberProfileClient.kt`
- Create: `app/game-api/src/main/kotlin/opensamguk/gameapi/member/GatewayMemberProfileClient.kt`
- Create: `app/game-api/src/main/kotlin/opensamguk/gameapi/member/CachingMemberProfileClient.kt`
- Create: `app/game-api/src/main/kotlin/opensamguk/gameapi/member/MemberProfileUnavailableException.kt`
- Create: `app/game-api/src/main/kotlin/opensamguk/gameapi/member/MemberProfileExceptionHandler.kt`
- Create: `app/game-api/src/test/kotlin/opensamguk/gameapi/member/CachingMemberProfileClientTest.kt`
- Create: `app/game-api/src/test/kotlin/opensamguk/gameapi/member/GatewayMemberProfileClientTest.kt`
- Create: `app/game-api/src/test/kotlin/opensamguk/gameapi/member/RedisMemberProfileCacheTest.kt`
- Modify: `app/game-api/src/main/resources/application.yml`
- Modify: `app/game-api/src/main/kotlin/opensamguk/gameapi/member/MemberProfile.kt` (retain the DTO/interfaces, remove the local-row mapper)

**Interfaces:**
- Produces: `fun interface MemberProfileClient { fun get(userId: Long): MemberProfile? }`.
- Produces: successful-only Redis JSON cache entries with a 120-second TTL.
- Produces: `MemberProfileUnavailableException` for upstream transport/5xx/invalid-response failures.

- [x] Write focused client tests that exercise a real HTTP test server for request path/header/JSON mapping and the cache boundaries for JSON round-trip, 120-second expiry, successful caching, cache-hit fallback, and cache-miss unavailability.
- [x] Run the focused tests and record behavior assertion failures caused by the missing implementation.
- [x] Implement the HTTP client, successful-result Redis cache, TTL comment, and 503 exception mapping with bounded connect/read timeouts.
- [x] Run the focused tests and inspect fresh XML for zero failures and errors.

### Task 3: Migrate the three game controllers

**Files:**
- Modify: `app/game-api/src/main/kotlin/opensamguk/gameapi/web/JoinController.kt`
- Modify: `app/game-api/src/main/kotlin/opensamguk/gameapi/controller/SelectPoolController.kt`
- Modify: `app/game-api/src/main/kotlin/opensamguk/gameapi/controller/PossessionController.kt`
- Modify: `app/game-api/src/test/kotlin/opensamguk/gameapi/web/JoinControllerTest.kt`
- Modify: `app/game-api/src/test/kotlin/opensamguk/gameapi/controller/SelectPoolControllerTest.kt`
- Modify: `app/game-api/src/test/kotlin/opensamguk/gameapi/controller/PossessionControllerTest.kt`

**Interfaces:**
- Consumes: `MemberProfileClient.get(userId)`.
- Preserves: `owner_name` receives the profile `name` captured when possession succeeds.

- [x] Change controller tests first to supply the client and assert join, select-pool, and possession behavior plus 503 on client unavailability.
- [x] Run each focused suite and record assertion failures from the old repository-dependent behavior.
- [x] Replace `UserRepository` constructor dependencies and calls with `MemberProfileClient`; keep missing-profile handling distinct from upstream unavailability.
- [x] Run all three focused suites and inspect fresh XML results.
- [x] Prove with `rg` that the three production controller files contain zero `UserRepository` references.

### Task 4: Separate Docker deployment wiring

**Files:**
- Modify in `opensamguk-docker`: `docker-compose.server.yml`
- Modify in `opensamguk-docker`: `servers/s1.env.example`
- Test in `opensamguk-docker`: the existing compose/deployer configuration tests or a focused new configuration test.

**Interfaces:**
- Supplies to game-api: `GATEWAY_API_URL=http://gateway-api:8080` by default.
- Supplies to game-api: required `INTERNAL_SERVICE_TOKEN` without printing its value.

- [x] Create or reuse the dedicated Docker worktree through `bin/start-task`.
- [x] Add a configuration behavior test that fails because game-api lacks the two environment keys.
- [x] Add gateway/game environment wiring plus deployer allowlists and update the example variable descriptions.
- [x] Validate compose configuration without rendering secret values and run the focused Docker tests.
- [x] Write the Docker task report and create its single logical commit.

### Task 5: Final gates, reports, and application commit

**Files:**
- Create in metarepo: `reports/opensamguk/tasks/2026-08-25-identity-sot-single-source.md`
- Update: the plan checkboxes in this document.

**Interfaces:**
- Produces: reviewable RED logs, final Gradle tail, fresh XML audit, grep proof, Docker dependency, remaining-risk statement, and commit IDs.

- [x] Run `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :app:gateway-api:test :app:game-api:test --rerun-tasks 2>&1 | tail -40` from the application repository root and save the visible tail.
- [x] List every `build/test-results/test/*.xml` with mtime after the run, parse all suites, and require aggregate failures/errors to be zero.
- [x] Review the diff against every spec requirement, inspect for sensitive fields/config leaks, and run the repository-sensitive-file guard if available.
- [x] Write the metarepo report with outcome, RED evidence, verification evidence, Docker dependency, commit, and remaining risks.
- [x] Create the one application commit with the required co-author trailer; do not push, merge, or deploy.
