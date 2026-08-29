# Active World Map Version Recovery Design

**Date:** 2026-08-29  
**Incident:** `spep` turn daemon stalled after the active Han map changed from 780 to 774 cities  
**Goal:** Resume existing worlds without remapping live city identities, and prevent one invalid NPC move candidate from aborting an entire tick.

## Incident evidence

Production diagnosis recorded the repeating failure as:

```text
RandUtil.choice -> GenFoundFamily.pickNationChoiceMove
-> GenFoundFamily.do국가선택Body -> reserved-turn / turn-daemon loop
```

The persisted `spep` world contains the pre-merge 780-city Han world, while the deployed engine image contains the post-merge 774-city `han` constants. NPC general `1023` is persisted at city `775`; the active `han` variant cannot resolve that id, supplies an empty path list, and throws `IllegalArgumentException("Empty items")`. The daemon retries the same deterministic failure every second.

The mismatch is broader than city `775`. Removing and merging places reindexed later numeric city ids, so a bulk `775..780` cleanup would leave earlier persisted references attached to the wrong logical places. Restarting the engine or catching only the observed exception does not repair that identity mismatch.

## Decision

Treat a map's numeric city-id space as immutable once a world is created.

- Keep the current 774-city map under the existing `han` key for new worlds.
- Restore the last 780-city map artifacts under a new immutable compatibility key, `han-780-v1`.
- Migrate only existing Han worlds whose persisted city set proves the 780-city identity space to `mapName=han-780-v1`.
- Do not rewrite city, general, nation, troop, turn, or ledger ids in the live world.
- Defensively skip the NPC nation-choice move branch when its current city is missing or has no paths, instead of calling `RandUtil.choice` with an empty list.

This preserves the running world's actual identities and avoids a many-table remap whose correctness cannot be proven from ordinal ids alone.

## Map compatibility package

The compatibility package is a complete, immutable set restored from commit
`ad75119548050e5954d813753da41aa6a30be3b3`, the exact 780-city parent of the first merge/removal
commit:

- logic constants and adjacency exposed as a `CityConstVariant` named `han-780-v1`;
- classpath map JSON used by scenario/API map consumers;
- terrain/tile assets required by the map endpoints;
- gate-index data used by Han unit and region restrictions.

The package must be generated or restored from one exact historical commit and carry a checked SHA-256 manifest. It must not be reconstructed by guessing the six deleted ids or by padding the 774-city map.

The source hashes are fixed:

| Artifact | SHA-256 |
|---|---|
| `HanCityConst.kt` | `12388359f9a18a0e165621807d2fcdd8a114443081d38218162f3d0f583bde8c` |
| `HanGateIndex.kt` | `c73b711f730d5434fd182c49fe51a6548d9abe6122d27c27fd334978fba7ab4b` |
| `infra/.../map/han.json` | `a61cbd8aa6fd0dd2f7f794df6d0ebdc026c0b6c351568c60efb8d115f54b3670` |
| `data/map/han-tiles.json` | `1979c193de6774af7c3cf5a9ddfd1c81bf94ead5b8c5b46dafd06bed03c6888d` |

`CityConstRegistry` registers both `han` and `han-780-v1`. Existing selection through `ActiveWorldMap.requireName/requireVariant` remains the single runtime route; engine logic and game-api must not introduce separate compatibility switches.

## Existing-world migration

A Flyway data migration classifies each existing world independently.

It changes a world only when all of the following are true:

1. its configured active map is exactly `han`;
2. its persisted `city` rows for that `world_id` form the exact numeric set `1..780`;
3. it is not already pinned to an immutable compatibility key.

For a qualifying world, the migration updates the existing map fields in both `world_state.config` and `world_state.meta` to `han-780-v1`, preserving every unrelated JSON field. `ng_games.map` remains unchanged: the loader exposes that column as `map_theme`, while `ActiveWorldMap` selects only from `world_state.config/meta`.

The migration is idempotent. It refuses ambiguous Han states instead of guessing:

- 774 rows remain `han`;
- an exact 780-row legacy world becomes `han-780-v1`;
- any other count or non-contiguous id set causes a clear migration failure with the world id and observed shape.

The migration never edits gameplay entity ids or deletes rows.

## Defensive NPC behavior

`GenFoundFamily.do국가선택Body` retains the existing RNG order through the 0.3 join gate and 0.2 move gate. After the move gate succeeds:

- resolve the current city in the active `CityConstVariant`;
- if the city is absent or its ordered path list is empty, return `null` without performing a choice draw;
- otherwise call the existing `pickNationChoiceMove` exactly once and preserve path insertion order.

This is a corruption boundary, not a map migration mechanism. It prevents one bad actor from aborting the full tick while health/validation evidence exposes the inconsistent world. Valid worlds retain their existing draw count and selected command behavior.

## Startup validation and observability

After selecting the active map during snapshot construction, the engine validates:

- every persisted city id exists in the selected map variant;
- every general and nation-capital city reference resolves to a persisted city and the selected variant;
- the persisted city count matches the selected variant.

For `han-780-v1`, a valid legacy world passes and starts normally. Ambiguous or partially migrated worlds fail startup/readiness with a bounded error naming the world and incompatible ids, before the daemon enters a one-second retry loop. Detailed row data stays in server logs and is not exposed publicly.

The existing turn-daemon status remains the operational signal. Recovery is proven only when `successfulTicks` increases, `consecutiveFailures` returns to zero, the last error clears or is superseded by success, and public game time advances.

## API and UI consistency

Game-api already resolves the map through the same `world_state` configuration. It must accept `han-780-v1` anywhere it currently accepts `han`:

- constants response;
- map preview and terrain assets;
- distance/precheck consumers;
- server/admin read models that expose the active map key.

No user-facing map selector is added. Existing worlds show their compatibility map transparently; new worlds continue to report `han`.

## Test strategy

All production changes follow red-green TDD.

1. A logic regression test constructs the real nation-choice flow with an unresolved current city and proves it returns no command instead of throwing. A second case covers a resolved city with zero paths. Existing non-empty-path tests continue to prove exactly one choice draw.
2. Registry tests prove both map variants are present, immutable, and have literal counts 774 and 780. SHA tests bind compatibility resources to commit `ad75119548050e5954d813753da41aa6a30be3b3`.
3. Migration integration tests cover exact 780 pinning, 774 no-op, idempotent rerun, multi-world isolation, unrelated JSON preservation, and fail-closed ambiguous/non-contiguous data.
4. Snapshot-loader tests prove a valid 780 world selects `han-780-v1`, while mismatched references fail before daemon startup.
5. Game-api tests prove constants and map assets for `han-780-v1` return the 780-city identity space.
6. The full `logic`, `infra`, `game-engine`, and `game-api` test gates run with JDK 21. Relevant test XML is inspected because the repository's context wrapper can return an inaccurate exit status.

## Rollout and live recovery

1. Back up the `spep` database and record the active image digest, world id, city-id shape, turn timestamp, and daemon diagnostics.
2. Deploy the image containing the compatibility artifacts, migration, guard, and validation.
3. Confirm Flyway pins only the proven 780-city world and does not alter gameplay entity ids.
4. Restart through the normal deployment workflow; do not manually edit individual city ids.
5. Observe at least two successful scheduled ticks or a bounded accelerated verification accepted by the existing operations runbook.
6. Confirm health `UP`, increasing `successfulTicks`, zero consecutive failures, and advancing public game time.
7. If validation rejects the world, stop rollout and restore the backup/image; do not weaken validation in production.

Production mutation is limited to the reviewed Flyway map-key update and the normal application flushes after the daemon resumes. Any additional manual database repair requires separate evidence and approval.

## Documentation impact

- Add an admin runbook note that active-world map ids are immutable and map artifact changes require a new versioned key.
- Record the `spep` incident recovery evidence and exact deployed commit in a review/report.
- Update architecture guidance so future map generators cannot replace an active key's numeric id space.

## Acceptance criteria

- Existing exact 780-city Han worlds resolve every persisted city against `han-780-v1` without gameplay-id rewrites.
- New/fresh Han worlds continue to use the 774-city `han` map.
- Missing current-city or empty-adjacency NPC nation-choice evaluation cannot throw `Empty items` or abort the full tick.
- Ambiguous persisted map shapes fail readiness before the daemon loop starts.
- Engine and API use the same active map key and artifact set.
- Focused and full relevant test suites pass with zero failures/errors.
- After rollout, `spep` produces successful ticks and advances game time.

## Out of scope

- Remapping a 780-city live world into the 774-city identity space.
- Redesigning city ids as UUIDs in this recovery task.
- Changing scenario ownership, balance, RNG behavior for valid maps, or the one-daemon-write architecture.
- Deleting the compatibility map while any persisted world references it.
