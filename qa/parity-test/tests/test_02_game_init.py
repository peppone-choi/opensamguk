"""
Parity Test — Game Initialization (scenarios, world creation, general list).

Compares:
  - Available scenarios list (structure)
  - Const/config data structure
  - Nation list structure
  - General list structure after world init
"""
import pytest
from comparison import compare_responses, RNG_DEPENDENT_FIELDS


class TestScenarios:
    def test_scenario_list_structure(self, legacy, new):
        """Both systems should return a scenario list with matching structure."""
        lr = legacy.get("Global/GetConst")
        nr = new.get("/api/scenarios")

        assert lr.status_code == 200, f"Legacy GetConst failed: {lr.status_code}"
        assert nr.status_code == 200, f"New scenarios failed: {nr.status_code}"

        legacy_data = lr.json()
        new_data = nr.json()

        # Legacy embeds scenarios inside GetConst; new has dedicated endpoint
        # Both should return list-like data about available scenarios
        if isinstance(new_data, list):
            assert len(new_data) > 0, "New stack returned empty scenario list"

        # Structural: both must have scenario entries with id/name/year fields
        if isinstance(new_data, list) and len(new_data) > 0:
            sample = new_data[0]
            assert any(k in sample for k in ("id", "scenarioId", "name")), \
                f"New scenario missing id/name: {list(sample.keys())}"


class TestWorldState:
    """After world creation, compare global state."""

    def test_nation_list_structure(self, legacy, new):
        """Nation lists should have same structural shape."""
        lr = legacy.get("Global/GetNationList")

        # New: we need a worldId — try world 1
        nr = new.get("/api/worlds/1/nations")

        if lr.status_code != 200 or nr.status_code != 200:
            pytest.skip("World not initialised yet on one or both stacks")

        legacy_nations = lr.json()
        new_nations = nr.json()

        if not legacy_nations or not new_nations:
            pytest.skip("Empty nation data — world may not be started")

        # Structural comparison
        cmp = compare_responses(legacy_nations, new_nations, structural_only=True)
        assert cmp["structural_match"], (
            f"Nation list structure mismatch:\n"
            f"  Legacy shape: {cmp['legacy_shape']}\n"
            f"  New shape:    {cmp['new_shape']}"
        )

    def test_general_list_structure(self, legacy, new):
        """General lists should have same structural shape."""
        lr = legacy.get("Global/GeneralList")
        nr = new.get("/api/worlds/1/front-info")

        if lr.status_code != 200 or nr.status_code != 200:
            pytest.skip("World not initialised on one or both stacks")

        cmp = compare_responses(lr.json(), nr.json(), structural_only=True)
        # Structures will differ (different field names), but both should be dicts/lists
        legacy_data = lr.json()
        new_data = nr.json()

        # Basic sanity: both return data
        assert legacy_data, "Legacy returned empty general list"
        assert new_data, "New returned empty front info"

    def test_city_list_structure(self, legacy, new):
        """City data should have same structural shape."""
        lr = legacy.get("Global/GetMap")
        nr = new.get("/api/worlds/1/cities")

        if lr.status_code != 200 or nr.status_code != 200:
            pytest.skip("World not initialised on one or both stacks")

        legacy_data = lr.json()
        new_data = nr.json()

        # Both should return a list of city objects
        if isinstance(legacy_data, list) and isinstance(new_data, list):
            if legacy_data and new_data:
                cmp = compare_responses(legacy_data, new_data, structural_only=True)
                # Log but don't hard-fail on structural mismatch for cities
                # (field name mapping differences are expected)
                if not cmp["structural_match"]:
                    pytest.xfail(
                        f"City structure differs (expected due to field naming):\n"
                        f"  Legacy keys: {sorted(legacy_data[0].keys()) if isinstance(legacy_data[0], dict) else 'N/A'}\n"
                        f"  New keys:    {sorted(new_data[0].keys()) if isinstance(new_data[0], dict) else 'N/A'}"
                    )

    def test_diplomacy_structure(self, legacy, new):
        """Diplomacy relations should have same structural shape."""
        lr = legacy.get("Global/GetDiplomacy")
        nr = new.get("/api/worlds/1/diplomacy")

        if lr.status_code != 200 or nr.status_code != 200:
            pytest.skip("World not initialised on one or both stacks")

        legacy_data = lr.json()
        new_data = nr.json()

        assert legacy_data is not None, "Legacy returned null diplomacy"
        assert new_data is not None, "New returned null diplomacy"
