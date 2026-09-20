# HWIHA court live fixture

`scenario_990001.json` is synthetic QA data. Its five attributes and initial lord policy are explicit game-design fixtures, not historical-person provenance. It creates one NPC lord in 허창; the human player must sign up and create a character through the normal UI. Do not add this fixture to the operating scenario catalog.

Use `tools/e2e/local_v1_gate.sh` with fresh isolated resources and caller-supplied ephemeral JWT keys, `INTERNAL_SERVICE_TOKEN`, and unused host ports. Set:

```bash
SCENARIO_CODE=scenario_990001
TURN_PROFILE_NAME=che:scenario_990001
OPENSAMGUK_WORLD_ID=990001
SCENARIO_HOST_DIR="$(pwd)/tools/e2e/fixtures/hwiha-court"
SCENARIO_QA_TURNTERM=1
E2E_ENABLE_AUTH=true
E2E_HWIHA_COURT=true
E2E_TEST_SPEC=e2e/hwiha-court-live.spec.ts
E2E_TEST_TIMEOUT_MS=600000
E2E_BUILD_MODE=sequential
```

Export these variables before invoking the runner. Set both public web URLs to the selected host ports **before building**. Use fresh images for changed code. The runner preserves the normal full-suite selection when `E2E_TEST_SPEC` is absent, and gives every Compose container a unique project-scoped name.

The spec exercises actual signup, logout/login, character creation, personal-turn enlistment, NPC dispatch, and UI acceptance. It waits for terminal command results and reads persisted policy, dispatch, assignment, position, and reservation counts. It does not write membership or assignment through SQL. The one-minute QA cadence only affects newly seeded test worlds.

This is court-flow evidence only. It does not establish historical-data validation, restart/re-delivery idempotence, marching, combat, or completion of the one-Zhou slice.
