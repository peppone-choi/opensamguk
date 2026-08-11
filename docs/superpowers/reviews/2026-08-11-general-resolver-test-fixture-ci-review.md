# General resolver standalone fixture CI review

Scope: CI regression fix limited to the standalone controller-test ownership fixture in `app/game-api/src/test/kotlin/opensamguk/gameapi/controller/`.

Verdict: cleared

## Failure and correction

The deploy run for main failed because seven standalone controller test classes still modeled ownership only through `general_owner`. Production now requires the typed `general.user_id` identity through `GeneralOwnershipClassifier`, so those stale fixtures resolved no playable general.

The change keeps the production classifier and resolver in the test path. `GeneralResolverFixture.kt` only fills the typed identity omitted by legacy Mockito entities returned from `findByUserId`, or derives it from an existing legacy owner fixture. It preserves the playable `npcState < 2` boundary. Production ownership tests do not use this helper.

No README, AGENTS, CLAUDE, API, or runtime documentation changes are needed because this patch changes no product behavior, endpoint contract, configuration, deployment topology, or migration. It restores standalone test fixtures to the already-merged typed-ownership contract.

## Independent critique

An independent read-only reviewer reported 0 BLOCKER, 0 MAJOR, 0 MINOR, and 0 QUESTION on base `1ed0213368bd4c6cab16f59d851ba06c8586d4bb`. The reviewed source patch SHA-256 was `69c42e200e0dd5b5f2d81ffc2cc4d460520248b8cf6a38215e07fd567586c3d0`.

The reviewer confirmed that the helper does not mock the resolver result or relax production ownership semantics. It is confined to seven legacy controller fixtures; `owner/GeneralResolverTest.kt` continues to exercise stale, pending, released, and typed ownership directly without the helper.

## Verification

- Focused regression: the two remaining direct-owner cases passed after synchronizing the snapshot and fixture entity identity.
- Seven controller suites: 107 tests, 0 failures, 0 errors, 0 skipped.
- Full `:app:game-api:test --rerun-tasks`: `BUILD SUCCESSFUL in 11m 57s`, 13 tasks executed.
- The first strict run correctly failed only because this critique artifact and docs-no-change rationale had not yet been recorded; strict is rerun after this file is added.
