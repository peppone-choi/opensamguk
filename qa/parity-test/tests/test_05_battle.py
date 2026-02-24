"""
Parity Test — Battle simulation.

Both systems have a battle simulator. We compare:
  - Response structure (attacker/defender results, rounds, winner)
  - Battle phases (not exact damage values — RNG-dependent)
"""
import pytest
from comparison import compare_responses


class TestBattleSimulation:
    def test_battle_simulate_structure(self, legacy, new):
        """Battle simulation should return structurally equivalent results."""
        # Minimal battle request
        battle_params = {
            "attackerId": 1,
            "defenderId": 2,
        }

        lr = legacy.call("Global/BattleSimulate", {
            "attacker": 1,
            "defender": 2,
        })
        nr = new.post("/api/battle/simulate", battle_params)

        if lr.status_code != 200 and nr.status_code != 200:
            pytest.skip("Battle simulation not available on either stack")

        if lr.status_code == 200 and nr.status_code == 200:
            legacy_data = lr.json()
            new_data = nr.json()

            # Both should return a result with battle outcome fields
            assert legacy_data is not None, "Legacy returned null battle result"
            assert new_data is not None, "New returned null battle result"

            # Check both have some notion of winner/result
            if isinstance(new_data, dict):
                battle_fields = {"winner", "result", "rounds", "attackerResult", "defenderResult",
                                 "attacker", "defender", "log", "battleLog"}
                new_keys = set(new_data.keys())
                has_battle_fields = bool(new_keys & battle_fields)
                assert has_battle_fields, (
                    f"New battle response missing expected fields. Got: {sorted(new_keys)}"
                )

    def test_battle_result_has_rounds(self, legacy, new):
        """Battle results should include round-by-round or summary data."""
        lr = legacy.call("Global/BattleSimulate", {
            "attacker": 1,
            "defender": 2,
        })
        nr = new.post("/api/battle/simulate", {
            "attackerId": 1,
            "defenderId": 2,
        })

        if lr.status_code != 200 or nr.status_code != 200:
            pytest.skip("Battle sim not available")

        legacy_data = lr.json()
        new_data = nr.json()

        # Both should have some form of round/log data
        def has_sequence(data):
            if isinstance(data, dict):
                for v in data.values():
                    if isinstance(v, list) and len(v) > 0:
                        return True
            return isinstance(data, list) and len(data) > 0

        legacy_has_seq = has_sequence(legacy_data)
        new_has_seq = has_sequence(new_data)

        # Both should have round data or neither (consistent behavior)
        assert legacy_has_seq == new_has_seq, (
            f"Battle round data parity: legacy_has_rounds={legacy_has_seq}, new_has_rounds={new_has_seq}"
        )

    def test_battle_invalid_generals(self, legacy, new):
        """Both systems should handle invalid general IDs gracefully."""
        lr = legacy.call("Global/BattleSimulate", {
            "attacker": 999999,
            "defender": 999998,
        })
        nr = new.post("/api/battle/simulate", {
            "attackerId": 999999,
            "defenderId": 999998,
        })

        # Both should return error (4xx or result=false)
        legacy_error = lr.status_code >= 400 or (
            isinstance(lr.json(), dict) and lr.json().get("result") in (False, 0, "0")
        ) if lr.status_code == 200 else True
        new_error = nr.status_code >= 400

        assert legacy_error == new_error, (
            f"Invalid battle parity: legacy_error={legacy_error}, new_error={new_error}"
        )
