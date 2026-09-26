"""Keep the active API and daemon on one neutral default world identity."""

import json
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PROFILE = "pep:scenario_990002"
SCENARIO = "scenario_990002"

DEFAULTS = {
    ".env.example": ("TURN_PROFILE_NAME=pep:scenario_990002", "SCENARIO_CODE=scenario_990002"),
    "docker-compose.yml": (
        "${TURN_PROFILE_NAME:-pep:scenario_990002}",
        "${SCENARIO_CODE:-scenario_990002}",
        "${SERVER_ID:-pep}",
    ),
    "docker-compose.production.yml": (
        "${TURN_PROFILE_NAME:-pep:scenario_990002}",
        "${SCENARIO_CODE:-scenario_990002}",
        "${SERVER_ID:-pep}",
    ),
    "app/game-engine/src/main/resources/application.yml": (
        "${TURN_PROFILE_NAME:pep:scenario_990002}",
    ),
    "app/game-engine/src/test/kotlin/opensamguk/engine/GameEngineApplicationTests.kt": (
        'body.contains("pep:scenario_990002")',
    ),
    "app/game-api/src/main/resources/application.yml": (
        "${OPENSAMGUK_PROFILE:pep:scenario_990002}",
    ),
    "app/game-api/src/main/kotlin/opensamguk/gameapi/reserve/CommandReserveService.kt": (
        "${opensamguk.profile:pep:scenario_990002}",
    ),
    "app/game-api/src/main/kotlin/opensamguk/gameapi/web/CommandController.kt": (
        "${opensamguk.profile:pep:scenario_990002}",
    ),
    "app/game-api/src/main/kotlin/opensamguk/gameapi/sse/RealtimeSubscriber.kt": (
        "${opensamguk.profile:pep:scenario_990002}",
    ),
    "tools/e2e/local_v1_gate.sh": (
        "${TURN_PROFILE_NAME:-pep:scenario_990002}",
    ),
}


def contract_errors(files):
    errors = []
    for path, snippets in DEFAULTS.items():
        source = files[path]
        for snippet in snippets:
            if snippet not in source:
                errors.append(f"{path}: missing {snippet}")
        if "che:scenario_2" in source:
            errors.append(f"{path}: retired profile default")
    scenario = json.loads(files[f"infra/src/main/resources/scenario/{SCENARIO}.json"])
    if scenario.get("worldFormat") != "GENERAL_RETAINER_CAMPAIGN":
        errors.append(f"{SCENARIO}: not the active world format")
    return errors


class DefaultTurnProfileTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        paths = set(DEFAULTS) | {f"infra/src/main/resources/scenario/{SCENARIO}.json"}
        cls.files = {path: (ROOT / path).read_text(encoding="utf-8") for path in paths}

    def test_active_defaults_share_the_seeded_world_identity(self):
        self.assertEqual([], contract_errors(self.files))

    def test_retired_profile_probe_is_rejected(self):
        files = dict(self.files)
        path = "docker-compose.yml"
        files[path] = files[path].replace(PROFILE, "che:scenario_2", 1)
        self.assertTrue(contract_errors(files))

    def test_seed_world_format_probe_is_rejected(self):
        files = dict(self.files)
        path = f"infra/src/main/resources/scenario/{SCENARIO}.json"
        scenario = json.loads(files[path])
        scenario["worldFormat"] = "RETIRED"
        files[path] = json.dumps(scenario)
        self.assertTrue(contract_errors(files))


if __name__ == "__main__":
    unittest.main()
