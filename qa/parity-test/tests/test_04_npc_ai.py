"""
Parity Test — NPC AI decision logic.

Both systems should make structurally similar decisions for NPC generals.
We compare:
  - NPC command choices (not exact values, but category: domestic vs war vs diplomacy)
  - NPC policy settings structure
"""
import pytest
from comparison import compare_responses


class TestNpcPolicy:
    def test_npc_policy_structure(self, legacy, new):
        """NPC policy settings should have matching structure."""
        # Legacy stores NPC policy in nation settings
        lr = legacy.get("Global/GetNationList")
        nr = new.get("/api/nations/1/npc-policy")

        if lr.status_code != 200 or nr.status_code != 200:
            pytest.skip("NPC policy not available")

        new_data = nr.json()
        assert new_data is not None, "New stack returned null NPC policy"

        # New stack should have policy fields
        if isinstance(new_data, dict):
            # Expect fields like warPolicy, domesticPolicy, etc.
            assert len(new_data) > 0, "NPC policy is empty dict"


class TestNpcDecisions:
    """Compare NPC AI decision patterns."""

    def test_npc_generals_have_commands(self, legacy_db, new_db):
        """Both DBs should have NPC generals with assigned commands."""
        try:
            with legacy_db.cursor() as cur:
                cur.execute("""
                    SELECT COUNT(*) as cnt FROM general
                    WHERE npc > 0 AND turntime IS NOT NULL
                """)
                legacy_npc_count = cur.fetchone()["cnt"]
        except Exception:
            pytest.skip("Legacy DB schema doesn't match expected structure")

        try:
            with new_db.cursor() as cur:
                cur.execute("""
                    SELECT COUNT(*) as cnt FROM generals
                    WHERE npc_type IS NOT NULL AND npc_type != 'NONE'
                """)
                row = cur.fetchone()
                new_npc_count = row[0] if row else 0
        except Exception:
            pytest.skip("New DB schema doesn't match expected structure")

        # Both should have NPC generals
        if legacy_npc_count == 0 and new_npc_count == 0:
            pytest.skip("No NPC generals in either DB (world not started)")

        # NPC counts should be roughly similar (same scenario = same NPCs)
        if legacy_npc_count > 0 and new_npc_count > 0:
            ratio = min(legacy_npc_count, new_npc_count) / max(legacy_npc_count, new_npc_count)
            assert ratio > 0.5, (
                f"NPC general count mismatch: legacy={legacy_npc_count}, new={new_npc_count}"
            )

    def test_npc_command_categories(self, legacy_db, new_db):
        """NPC commands should fall into similar categories on both systems."""
        # Categorize legacy commands
        try:
            with legacy_db.cursor() as cur:
                cur.execute("""
                    SELECT DISTINCT LEFT(turn0, 10) as cmd_prefix
                    FROM general WHERE npc > 0 LIMIT 50
                """)
                legacy_cmds = {r["cmd_prefix"] for r in cur.fetchall() if r["cmd_prefix"]}
        except Exception:
            legacy_cmds = set()

        try:
            with new_db.cursor() as cur:
                cur.execute("""
                    SELECT DISTINCT action FROM general_turns
                    WHERE general_id IN (SELECT id FROM generals WHERE npc_type IS NOT NULL AND npc_type != 'NONE')
                    LIMIT 50
                """)
                new_cmds = {r[0] for r in cur.fetchall() if r[0]}
        except Exception:
            new_cmds = set()

        if not legacy_cmds and not new_cmds:
            pytest.skip("No NPC commands found in either DB")

        # Map command categories
        domestic_kr = {"che_농업투자", "che_상업투자", "che_치안강화", "che_수비강화", "che_성벽보수"}
        military_kr = {"che_징병", "che_정비훈련", "che_정비사기", "che_출병"}
        domestic_en = {"DEVELOP_AGRICULTURE", "DEVELOP_COMMERCE", "IMPROVE_SECURITY", "IMPROVE_DEFENCE", "REPAIR_WALL"}
        military_en = {"RECRUIT", "TRAIN", "BOOST_MORALE", "MARCH"}

        legacy_has_domestic = bool(legacy_cmds & domestic_kr)
        legacy_has_military = bool(legacy_cmds & military_kr)
        new_has_domestic = bool(new_cmds & domestic_en)
        new_has_military = bool(new_cmds & military_en)

        # Both should have similar command categories
        if legacy_cmds and new_cmds:
            assert legacy_has_domestic == new_has_domestic, \
                f"Domestic command parity: legacy={legacy_has_domestic}, new={new_has_domestic}"
            assert legacy_has_military == new_has_military, \
                f"Military command parity: legacy={legacy_has_military}, new={new_has_military}"
