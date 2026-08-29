# Active-world map version recovery — local approval review

Range reviewed: `5cc0e202..8824893f`.

## Status

**FIX REQUIRED — local merge/rollout gate is blocked.** The completed independent adversarial review
found no Critical or Important implementation defect, but the required fresh all-module rerun did not
complete: it was interrupted after unrelated game-engine rehydration failures. This review does not
claim production deployment, V45 recovery, or a cleared rollout gate.

## Independent adversarial verdict and deferred findings

The independent review found historical provenance, V45 classification/JSON preservation,
city-zero/null-capital validation, RNG containment, canonical hyphen-safe API resolution, and static
Docker artifacts acceptable. Deferred minors remain unchanged: `CityConstRegistry.kt` comments still
say current-Han-only/no-direct-test; `TerrainMapController.kt` conflicts with the approved Docker map
assets and the Docker comment says “one line.” No production-comment edits are included here.

## Fresh local verification evidence

After `git diff --check` passed, this required command was started at HEAD `8824893f`:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew \
  :common:test :logic:test :infra:test :app:game-engine:test :app:game-api:test \
  --rerun-tasks
```

It was interrupted after more than 20 minutes with exit `130`; it is not all-module green evidence.
Before interruption `common`, `logic`, and `infra` completed green. The output recorded ten unrelated
engine failures: `FullRehydrateTurnGateIT` restart; two `RehydrateLosslessGateIT` auction reloads;
three `RehydrateRoundTripIT` troop reloads; two `WorldSnapshotLoaderArchiveIT` scope cases;
`WorldSnapshotLoaderDurableStateIT` start-time reload; and `WorldSnapshotLoaderWorldScopeIT` cohort
isolation. An owning production/test-code task must investigate them before this gate is cleared.

Fresh XML read after interruption was partial only (tests/failures/errors/skipped): common
`256/0/0/0`, logic `3336/0/0/0`, infra `252/0/0/0`, engine `15/0/0/0`, API `17/0/0/0`. It must not be
treated as a complete aggregate. The fresh V45 suite did complete:
`V45LegacyHanWorldMapMigrationTest = 5/0/0/0`; therefore **V45 skipped=0** is explicitly proved.

## Mutation evidence

1. Removing `HAN_780_V1_MAP_NAME` from `isHanMapName`, then running
   `:logic:test --tests 'opensamguk.logic.world.CityConstRegistryTest' --rerun-tasks`, was RED: exit
   1, 14 tests/1 failure (`Han family recognition includes current and compatibility keys only`). The
   exact line was restored; the identical focused run was GREEN: exit 0, `14/0/0/0`.
2. Removing `if (paths.isEmpty()) return null`, then running
   `:logic:test --tests 'opensamguk.logic.ai.families.GenFoundBodiesTest' --rerun-tasks`, was RED:
   exit 1, 31 tests/2 failures. XML records repeated
   `java.lang.IllegalArgumentException: Empty items`. The exact guard was restored; the identical
   focused run was GREEN: exit 0, `31/0/0/0`.

## Historical provenance and Docker evidence

Re-extracted from `ad75119548050e5954d813753da41aa6a30be3b3` with `git show` and SHA-256:

| Source → committed artifact | Source SHA-256 | Committed SHA-256 |
|---|---|---|
| `HanCityConst.kt` → `Han780V1CityConst.kt` | `12388359f9a18a0e165621807d2fcdd8a114443081d38218162f3d0f583bde8c` | `3df1952c65406ddde4f22e4462867b897b8707ad41db645c4e78fb3212a19199` |
| `HanGateIndex.kt` → `Han780V1GateIndex.kt` | `c73b711f730d5434fd182c49fe51a6548d9abe6122d27c27fd334978fba7ab4b` | `7c381e5d7a18948f703c79a92f7edf1b11f0782942783428e3b087da930dc732` |
| `han.json` → `han-780-v1.json` | `a61cbd8aa6fd0dd2f7f794df6d0ebdc026c0b6c351568c60efb8d115f54b3670` | same |
| `han-tiles.json` → `han-780-v1-tiles.json` | `1979c193de6774af7c3cf5a9ddfd1c81bf94ead5b8c5b46dafd06bed03c6888d` | same |

Current-HEAD Docker build and assertion both exited 0:

```bash
docker build -f docker/game-api.Dockerfile --build-arg IMAGE_TAG=map-recovery-task5 \
  -t opensamguk-game-api:map-recovery-task5 .
docker run --rm --entrypoint sh opensamguk-game-api:map-recovery-task5 -c \
  'test -s /app/data/map/han-780-v1-tiles.json && test -s /app/data/map/han-780-v1-provinces.png'
```

Both province sets were generated; manifest-list digest:
`sha256:38efc4dbd0a9d71f50692925083d08b59e15f7f4bc3d09d82153a1a9e43a71ad`.

## Rollout and rollback conditions

Before approved promotion, record backup/restore proof, image digests, active world/map, city shape,
public time, and daemon counters. Promote game-api and game-engine from one immutable SHA. After V45,
an image-only rollback is unsafe: restore its matching pre-V45 DB backup with the prior image. Require
two observations with health `UP`, increasing `successfulTicks`, zero `consecutiveFailures`, advancing
public time, map `han-780-v1`, exact `1..780`, and unchanged general `1023` identity. Never hand-edit
ordinal ids, mutate `ng_games.map`, or weaken V45/validator conditions.

No push, PR, merge, deploy, production inspection, migration, recovery, or metarepo completion report
was performed by this task.
