# HWIHA 豫州 slice (synthetic operating candidate)

`scenario_990002.json` is a synthetic HWIHA scenario for the S3 core loop: six NPC lords, one per 豫州 commandery (潁川·汝南·梁國·沛國·陳國·魯國), each owning the administrative counties of its commandery on the active `han-world-v3` map, all at war with each other. A human signs up, creates a character and enlists with any lord (every lord accepts enlistment).

The scenario is registered in the operating catalog as an S3 test and pep transition candidate. Registration does not change the live world. The people, stats and quantities below are game-design placeholders, not historical data. All of them remain `PROVISIONAL` until the W4 measurements are reviewed:

| Item | Value | Note |
|---|---|---|
| Lords | `<郡> 주공`, stats 70/65/65/60/70, `synthetic-qa:yuzhou-slice` | synthetic; no historical person is claimed |
| Nations | level 1, gold/rice 0 | treasury lives only in county warehouses |
| Units (`hwihaUnits`) | 2 per lord, infantry 1100, 4,000 troops, training 50, morale 60, provisions 6 months (24,000) | the generator test checks that every lord can besiege an enemy county on the active map |
| Warehouses | grain = seed garrison × 100 × 18 phases in every slice county; money 100,000 in each capital; zero elsewhere | 18 phases is the approved siege reference (`march-tempo-targets-v1.json` `siegeResolution.referenceInitialRationTurns`); 100/soldier/phase is the provisional ration |
| Start | year 190 | |

Counties outside 豫州 are unowned (neutral). NPC lords may also march on nearby neutral counties when they can field twice the garrison.

The file is generated, not hand-edited: `infra/src/test/kotlin/opensamguk/infra/seed/HwihaYuzhouSliceScenarioTest.kt` rebuilds it from the map and the boot artifact pin and fails when the committed file drifts. To regenerate after a map change, delete the file and run that test, then review the diff. Do not touch the han-tiles chain for this fixture.

Local run follows `tools/e2e/fixtures/hwiha-court/README.md` with:

```bash
SCENARIO_CODE=scenario_990002
TURN_PROFILE_NAME=che:scenario_990002
OPENSAMGUK_WORLD_ID=990002
SCENARIO_HOST_DIR="$(pwd)/tools/e2e/fixtures/hwiha-yuzhou"
SCENARIO_QA_TURNTERM=1
E2E_ENABLE_AUTH=true
E2E_HWIHA_YUZHOU=true
E2E_TEST_SPEC=e2e/hwiha-yuzhou-live.spec.ts
E2E_TEST_TIMEOUT_MS=3000000
E2E_BUILD_MODE=sequential
```

Run from a fresh isolated Compose stack with caller-supplied ephemeral JWT keys, `INTERNAL_SERVICE_TOKEN`, and unused host ports, as in the court fixture instructions. The Playwright case persists nine screen captures, their API responses, a read-only siege/warehouse DB snapshot, and the phase outcome in the runner artifact directory. Its only post-seed SQL write raises the new human general's `killturn` to 96 so the long QA loop does not delete that account. It also asserts that the engine log has no `tick failed` entry.

This fixture establishes a playable loop candidate only. It is not a balance claim, and it does not validate historical ownership in 190.
