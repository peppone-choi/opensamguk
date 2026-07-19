# OPENSAM-143 Batch 4 Independent Review

Scope: .ai/, .gitignore, app/game-engine/, docs/superpowers/plans/, docs/superpowers/reviews/, tools/scenario/
Verdict: cleared

## Review result

An implementation-independent static review found no Critical or Important finding and `fix-required=0`. The review includes the tracked generator, manifests, registry/maps, verifier, bootstrap wiring, focused integration test, ignore boundary, and Batch 4 plan. No `ScenarioImporter`, `ScenarioJson`, rank system, migration, legacy source, golden fixture, or environment file was changed.

## Findings resolved before clearance

- The bootstrap accepts only canonical non-negative `scenario_<number>` codes, preserves `scenario_0`, and rejects signs, leading zeroes, suffixes, whitespace, traversal-like values, and overflow before database writes.
- The external scenario is read once, its report SHA-256 is checked against those same bytes, and the verified bytes are written; the review regression covers the prior hash/copy time-of-check/time-of-use gap.
- JSON contract accessors reject quoted numeric/boolean values and non-string nullable/required strings instead of relying on coercion.
- Manifest and builder numeric validation reject Python `bool` and non-integral `float` values. The ideology field is limited to the reviewed vocabulary, rejecting arbitrary, prefixed, and whitespace-variant values.

## Batch 4 v1 projection and v2 boundary

This is a deliberately narrow v1 seed projection for the existing positional importer. It freezes 1,000 stable IDs and flat seven-field fingerprints, then emits the three pilot scenario rows. It does not replace the full v2.1 refined master or introduce a new rank/officer-city system. The v2-only ideology, policy, traits, formations, tactics, portrait fields, and portrait-column registry remain reserved for v2 reconciliation. `君主=12`, `太守=4`, and `都督`/`一般`/`在野=0` are source-status mappings only; no native 都督 semantics or PHP post-build promotion was invented.

The active-row `faction == null` v2 downgrade was not present in the three pilots. Batch 4 fails closed rather than creating a projection rule, so any future scenario that contains such a row remains blocked pending v2 reconciliation.

## Pilot evidence observed before final T6

| Pilot | Scenario SHA-256 | Report SHA-256 | Lifecycle / affiliation result |
|---|---|---|---|
| 3190 | `370d36cdc332d0cc5a4a8b61455e9d3103ce9f7ef24fc4aa79a1665a11bd0902` | `03055d68313832d8d7243f711a7c1ab33a9706e5cf62f8991e2d83293d8acbfa` | roster 280; active 264; deferred 1; dead 15; affiliated 249; neutral 31; ready |
| 3200 | `584f2862646aa78226d1947a40eadd71e22caa38fa331493b0f9028a5c42c633` | `85a8cdef55f42bba2d3344a67c1b72f8173807e9103532ecf429cca7a3d5c1f8` | roster 336; active 316; deferred 2; dead 18; affiliated 304; neutral 32; quarantined |
| 3219 | `c7af5b42045e93cfd61f7abe33bde81bc20a032b3a4505f087315b5309aa809a` | `73bd1ad6f700657c49467fcac4b663b4dd6349bdad76faf5e4a9cbc62f8e901e` | roster 383; active 366; deferred 0; dead 17; affiliated 370; neutral 13; ready |

The 3200 pilot is intentionally not ready for live consumption. Source ruler 손책 is filtered at `200.1` (`death=200`), leaving nation 6 without an eligible ruler. PHP post-build promotion would address that, but Kotlin has no parity implementation for it. The report therefore records `pending v2 PHP postBuild promotion parity`; Batch 4 does not alter lifespan, ranks, or importer behavior to conceal the gap.

## Determinism and static test evidence

- The generation/verifier suites were observed green at **66/66** tests; the RTK14 map suite was observed green at **8/8** tests.
- Each pilot was generated and verified twice byte-identically. The scenario/report bytes above were compared to the ignored canonical outputs, with zero unresolved, ambiguous, or collision counters.
- Pilot manifests cover 21 nations / 249 affiliated officers (3190), 11 / 304 (3200), and 6 / 370 (3219). The only reported relocation is stable ID 10056 공손도, 북평 to 역경.
- Ignore-boundary checks observed no generated `data/scenarios/**` changes in Git, and whitespace/static diff checks were clean at the time of review.

## Final T6 gate evidence

- Forced five-task backend sweep with `--rerun-tasks` completed `BUILD SUCCESSFUL in 7m 20s`. Fresh XML reports cover 486 suites / 4,426 tests, with one known `LongSimReplayGateTest` skip and zero failures or errors.
- The exact `tools/parity/gate.sh backend` gate completed `BUILD SUCCESSFUL in 2m 44s`; its XML gate is green.
- `scripts/agent/verify-changes.sh --run` exited 0. Its game-engine evidence is 84 XML reports / 568 tests / one skip / zero failures or errors, and the strict agent-system result is `Errors=0 Warnings=0`.
- The first final actual-override seed invocation exposed a stale configuration-cache environment and therefore selected the synthetic input. The root cause was isolated as Gradle configuration-cache staleness, not a scenario or test defect. Re-running with `--no-daemon --no-configuration-cache` consumed `input=actual-override` and completed `BUILD SUCCESSFUL in 1m 32s`.
- The final actual override XML is 4 tests / 0 failures / 0 errors / 0 skipped. Identity, idempotency, and integrity assertions passed with database counts: nation 21, city 94, general 264, general_turn 7,920, nation_turn 1,464, diplomacy 420, rank_data 9,768, ng_games 1, and event 13.

The earlier Python **66/66** and RTK14 **8/8** results remain part of this completed T6 evidence. All gates listed in the Batch 4 plan are now complete; the only remaining action is a human-approved disposition of the uncommitted branch.

No Git or external-system mutation is authorized or performed by this review.
