# Battlefield turn integration

Approved scope: independently enter/leave the deployed Changban site, resolve hostile occupants with real field combat, and keep field troops out of city-only effects. Source/catalog rules and persistence are provided by parallel owners.

- [x] Add shared physical city-membership helper based on authoritative battlefield presence.
- [x] Register che_전장이동 schema; intercept after AI replacement and before ordinary city resolver. Validate catalog, topology, actor and expected position revision using pure movement rules.
- [x] Resolve exact-site hostile occupants in stable ID order using existing directional diplomacy. Empty entry moves without combat; defeated defenders retreat to persisted return city; repelled/retreating attacker remains at origin. Apply only recorder-backed exact general snapshots and position deltas.
- [x] Gate all ordinary field actor city actions before effects, with explicit inert field rest; filter city defenders, healing, roaming cascades, capital-followers and other city membership consumers.
- [x] Add integration tests for entry/exit, hostile combat, stale/invalid command rejection, field/city separation and no-op compatibility. Run focused tests after shared Gradle slot release.

No province owner, city creation, traversal or administrative supply mutation. No arbitrary terrain combat multipliers. Field replay presentation requires its own non-city contract; existing city replay fields must not be filled with a fabricated target.

Validation: first engine integration gate passed25 tests. Position deletion and city membership gate passed31 engine and15 infra tests with no skips, including PostgreSQL CAS rollback/world isolation. Review found cross-flush revision reuse after deletion: a new regression failed before the durable general metadata revision floor was added. The subsequent focused gate passed31 engine tests and890 command-contract tests with no skips (42s). The actual reserved dispatcher entry succeeds without fallback, and old exit revisions are rejected after committed deletion and reload. A final flush-payload metadata assertion is added for the final broader gate.

Deletion cancels unflushed inserts or emits scoped CAS deletion for persisted rows. Metadata `spatialPositionRevisionFloor` retains revision history across deletion, without retaining physical position. Forced relocation preserves this metadata; re-entry allocates above its floor. Ordinary movement synchronizes verified city anchors, while external enclaves clear the obsolete explicit position. Field troops cannot execute queued nation commands or participate in ordinary city cascades.

Final broad gate: logic3466 passed; API640 passed; engine1105 completed with one existing reflection fixture broken by the newly internal helper and one skipped test. The reflection fixture now calls the internal helper directly while retaining politics/charm assertions; targeted revalidation is assigned with the parallel v2 exclusion gate. Final floor assertions passed in the broad engine run, including recorder metadata and actual FlushPayload.updatedGenerals. Do not describe the initial broad engine run as all-green. Full log: `/tmp/battlefield-final-full.log`; exact counts: `/tmp/battlefield-final-completed-counts.json`.
