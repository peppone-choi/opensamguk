import json
import os
from pathlib import Path
import runpy
import tempfile
import unittest
from unittest.mock import patch

HERE = Path(__file__).resolve().parent
STACK = runpy.run_path(str(HERE / "local-stack.py"))
ROLE = runpy.run_path(str(HERE / "role-fixtures.py"))


class QaIsolationTest(unittest.TestCase):
    def test_generated_compose_has_no_production_mount_network_or_port(self):
        model = json.loads((STACK["ROOT"] / "docker-compose.qa.yml").read_text())
        self.assertEqual(model["name"], "opensamguk-qa160")
        for name, service in model["services"].items():
            self.assertEqual(service["container_name"], "opensamguk-qa160-" + name)
            self.assertNotIn("build", service)
            self.assertNotIn("privileged", service)
            self.assertNotIn("network_mode", service)
            for mount in service.get("volumes", []):
                self.assertNotIn("docker.sock", mount)
                self.assertNotIn(".env", mount)
                self.assertTrue(mount.startswith("${QA_RUNTIME:") or mount.split(":")[0] in model["volumes"])
        self.assertEqual(model["services"]["nginx"]["ports"], ["127.0.0.1:18300:80"])
        self.assertTrue(all(not value.get("external") for value in model["networks"].values()))
        self.assertTrue(all(value["name"].startswith("opensamguk-qa160-") for value in model["volumes"].values()))
        engine = model["services"]["game-engine"]["environment"]
        self.assertEqual(engine["OPENSAMGUK_WORLD_ID"], "160")
        self.assertEqual(engine["RESET_TURNTERM"], "2")
        self.assertEqual(engine["RESET_MAXGENERAL"], "50")

    def test_atomic_heavy_lock_preserves_foreign_owner(self):
        with tempfile.TemporaryDirectory() as tmp:
            lock = Path(tmp) / ".locks/heavy-run"
            lock.mkdir(parents=True)
            owner = lock / "owner"
            owner.write_text("C10 foreign-owner\n")
            with patch.dict(STACK["heavy"].__wrapped__.__globals__, META=Path(tmp)):
                with self.assertRaises(FileExistsError):
                    with STACK["heavy"]():
                        self.fail("Foreign lock must not be acquired")
            self.assertEqual(owner.read_text(), "C10 foreign-owner\n")

    def test_own_heavy_lock_is_released_after_error(self):
        with tempfile.TemporaryDirectory() as tmp:
            with patch.dict(STACK["heavy"].__wrapped__.__globals__, META=Path(tmp), resource_gate=lambda: {}):
                with self.assertRaises(RuntimeError):
                    with STACK["heavy"]():
                        raise RuntimeError("fixture failure")
            self.assertFalse((Path(tmp) / ".locks/heavy-run").exists())

    def test_secret_custody_rejects_public_env_and_symlink(self):
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp)
            STACK["private_json"](directory / "stack.json", {"project": STACK["PROJECT"], "worldId": 160})
            env = directory / "qa.env"
            env.write_text("QA_SYNTHETIC=unused\n")
            env.chmod(0o644)
            with self.assertRaises(ValueError):
                STACK["custody"](directory)
            env.chmod(0o600)
            self.assertEqual(STACK["custody"](directory), directory.resolve())
            link = directory / "alias"
            link.symlink_to(directory)
            with self.assertRaises(ValueError):
                STACK["custody"](link)

    def test_container_guard_rejects_a_foreign_container(self):
        def foreign(*args, **kwargs):
            class Result:
                stdout = b'{"com.docker.compose.project":"opensamguk-spep","com.docker.compose.service":"game-postgres"}'
            return Result()
        with patch.dict(STACK["container_guard"].__globals__, run=foreign):
            with self.assertRaises(ValueError):
                STACK["container_guard"]("game-postgres")

    def test_redirects_cannot_send_qa_credentials_outside_loopback(self):
        with self.assertRaises(ValueError):
            ROLE["LoopbackOnly"]().redirect_request(None, None, 302, "", {}, "https://sam.peppone.dev")


class QaRoleFixtureTest(unittest.TestCase):
    def rows(self):
        return [{"nation": n, "role": r, "name": f"QA-{n}-{r}", "userId": i + 1,
                 "generalId": i + 100} for i, (n, r) in enumerate(ROLE["ROLES"])]

    def test_role_fixture_preserves_auth_and_world_scope(self):
        sql = ROLE["role_sql"](self.rows(), {"nationIds": [1, 2, 3], "cityIds": [4, 5, 6], "positionGeneralIds": [7, 8, 9]})
        self.assertNotIn("UPDATE users", sql)
        self.assertNotIn("DELETE", sql)
        self.assertIn("tick_seconds=120", sql)
        self.assertIn("npc_state=0", sql)
        self.assertIn("Manager needs actual NPC followers and a bugok", sql)
        self.assertIn("state_code=0", sql)
        self.assertIn("state_code=7", sql)
        self.assertNotIn("state_code=2", sql)  # Trade must not masquerade as an alliance.
        self.assertIn("qa160RoleFixture", sql)
        self.assertTrue(sql.startswith("BEGIN;") and sql.endswith("COMMIT;"))

    def test_incomplete_accounts_or_duplicate_positions_are_rejected(self):
        plan = {"nationIds": [1, 2, 3], "cityIds": [4, 5, 6], "positionGeneralIds": [7, 8, 9]}
        with self.assertRaises(ValueError):
            ROLE["role_sql"](self.rows()[:4], plan)
        with self.assertRaises(ValueError):
            ROLE["role_sql"](self.rows(), dict(plan, cityIds=[4, 4, 6]))

    def test_sql_literals_preserve_quotes_without_executing_them(self):
        self.assertEqual(ROLE["quote"]("x'; DROP TABLE users;--"), "'x''; DROP TABLE users;--'")


if __name__ == "__main__":
    unittest.main()
