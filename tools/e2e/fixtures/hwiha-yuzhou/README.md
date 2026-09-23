# HWIHA 豫州 slice (synthetic operating candidate)

`scenario_990002.json` is a synthetic HWIHA scenario for the S3 core loop: six NPC lords, one per 豫州 commandery (潁川·汝南·梁國·沛國·陳國·魯國), each owning the administrative counties of its commandery on the active `han-world-v3` map, all at war with each other. A human signs up, creates a character and enlists with any lord (every lord accepts enlistment).

**Not in the operating scenario catalog.** Adding it there needs the user's approval. The people, stats and quantities below are game-design placeholders, not historical data. All of them are `PROVISIONAL — 사용자 결정 대기`:

| Item | Value | Note |
|---|---|---|
| Lords | `<郡> 주공`, stats 70/65/65/60/70, `synthetic-qa:yuzhou-slice` | synthetic; no historical person is claimed |
| Nations | level 1, gold/rice 0 | treasury lives only in county warehouses |
| Units (`hwihaUnits`) | 2 per lord, infantry 1100, 4,000 troops, training 50, morale 60, provisions 6 months (24,000) | enough to besiege non-seat counties (garrison 840–4,000), not seats (5,670–8,190) |
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
```

This fixture establishes a playable loop candidate only. It is not a balance claim, and it does not validate historical ownership in 190.
