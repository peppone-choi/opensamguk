from contextlib import nullcontext
import json
import os
from pathlib import Path
import runpy
import subprocess
import tempfile
import unittest
from unittest.mock import patch

HERE = Path(__file__).resolve().parent
STACK = runpy.run_path(str(HERE / "local-stack.py"))
ROLE = runpy.run_path(str(HERE / "role-fixtures.py"))


class QaIsolationTest(unittest.TestCase):
    def stack_at(self, root):
        script = root / "tools/qa/local-stack.py"
        script.parent.mkdir(parents=True)
        script.write_text((HERE / "local-stack.py").read_text())
        return runpy.run_path(str(script))

    def test_checkout_and_worktree_use_the_same_foreign_heavy_lock(self):
        with tempfile.TemporaryDirectory() as tmp:
            meta = Path(tmp) / "meta"
            (meta / "bin").mkdir(parents=True)
            for name in ("start-task", "finish-task"):
                (meta / "bin" / name).touch()
            for name in ("projects", "worktrees"):
                (meta / name).mkdir()
            lock = meta / ".locks/heavy-run"
            lock.mkdir(parents=True)
            (lock / "owner").write_text("C10 existing-shared-lock\n")
            for root in (meta / "projects/opensamguk", meta / "worktrees/opensamguk/qa160"):
                with self.subTest(layout=root.relative_to(meta)):
                    stack = self.stack_at(root)
                    with patch.dict(stack["heavy"].__wrapped__.__globals__, resource_gate=lambda: {}):
                        with self.assertRaises(FileExistsError):
                            with stack["heavy"]():
                                self.fail("Both layouts must respect the existing shared lock")
                    self.assertEqual(stack["META"], meta.resolve())
                    self.assertEqual((lock / "owner").read_text(), "C10 existing-shared-lock\n")

    def test_unmanaged_or_unmarked_checkout_cannot_create_a_heavy_lock(self):
        with tempfile.TemporaryDirectory() as tmp:
            for root in (Path(tmp) / "ci/checkout", Path(tmp) / "unmarked/projects/opensamguk"):
                with self.subTest(layout=root.relative_to(tmp)):
                    stack = self.stack_at(root)
                    with patch.dict(stack["heavy"].__wrapped__.__globals__, resource_gate=lambda: {}):
                        with self.assertRaises(RuntimeError):
                            with stack["heavy"]():
                                self.fail("An unmanaged checkout must not start QA operations")
            self.assertEqual(list(Path(tmp).rglob(".locks")), [])

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

    def test_registry_metadata_matches_the_gateway_canonical_coordinates(self):
        registry = STACK["registry"]()
        self.assertEqual(len(registry), 1)
        self.assertEqual(registry[0]["id"], "qa160")
        self.assertEqual(registry[0]["gameApiUrl"], "http://sqa160-game-api:8081")
        self.assertEqual(registry[0]["gameEngineUrl"], "http://sqa160-game-engine:8082")
        self.assertEqual(registry[0]["deployProject"], "opensamguk-sqa160")
        self.assertEqual(STACK["PROJECT"], "opensamguk-qa160")

    def test_nginx_health_uses_the_listening_ipv4_address(self):
        model = json.loads((STACK["ROOT"] / "docker-compose.qa.yml").read_text())
        probe = model["services"]["nginx"]["healthcheck"]["test"]
        self.assertEqual(probe, ["CMD", "wget", "-q", "-O", "/dev/null", "http://127.0.0.1/health"])
        self.assertIn("listen 80;", STACK["nginx"]())
        self.assertIn("location /health { return 200 'QA160'; }", STACK["nginx"]())

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

    def test_start_refreshes_routes_after_compose_health_wait(self):
        calls = []
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp)
            (directory / "stack.json").write_text(json.dumps({"images": {}}))
            with patch.dict(STACK["start"].__globals__,
                            validate=lambda _: None, heavy=nullcontext,
                            resource_gate=lambda: {}, custody=lambda _: directory,
                            compose=lambda _, *args: calls.append(args),
                            refresh_routes=lambda _: calls.append(("refresh",))):
                STACK["start"](directory)
        self.assertEqual(calls[-2:], [
            ("up", "-d", "--no-build", "--wait", "--wait-timeout", "300"),
            ("refresh",),
        ])

    def test_refresh_routes_checks_custody_and_owner_before_test_and_reload(self):
        calls = []
        directory = Path("/unit-test-private-custody")
        def check_custody(value):
            self.assertEqual(value, directory)
            calls.append("custody")
            return directory
        with patch.dict(STACK["refresh_routes"].__globals__, custody=check_custody,
                        container_guard=lambda name: calls.append(("guard", name)),
                        compose=lambda value, *args: calls.append((value, *args))):
            STACK["refresh_routes"](directory)
        self.assertEqual(calls, ["custody", ("guard", "nginx"),
            (directory, "exec", "-T", "nginx", "nginx", "-t"),
            (directory, "exec", "-T", "nginx", "nginx", "-s", "reload")])

    def test_refresh_routes_propagates_test_and_reload_failures(self):
        for fail_at in ("test", "reload"):
            with self.subTest(fail_at=fail_at):
                calls = []
                def command(directory, *args):
                    calls.append(args)
                    if args[-1] == ("-t" if fail_at == "test" else "reload"):
                        raise subprocess.CalledProcessError(1, args)
                with patch.dict(STACK["refresh_routes"].__globals__, custody=lambda d: d,
                                container_guard=lambda _: None, compose=command):
                    with self.assertRaises(subprocess.CalledProcessError):
                        STACK["refresh_routes"](Path("/unit-test"))
                self.assertEqual(len(calls), 1 if fail_at == "test" else 2)

    def test_refresh_routes_rejects_foreign_owner_before_any_exec(self):
        def foreign(_):
            raise ValueError("foreign project")
        with patch.dict(STACK["refresh_routes"].__globals__, custody=lambda d: d,
                        container_guard=foreign) as scope:
            with patch.dict(scope, compose=lambda *_: self.fail("Must not exec a foreign container")):
                with self.assertRaises(ValueError):
                    STACK["refresh_routes"](Path("/unit-test"))

    def test_start_does_not_refresh_after_failed_health_wait(self):
        def command(directory, *args):
            if args[0] == "up":
                raise subprocess.CalledProcessError(1, args)
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp)
            (directory / "stack.json").write_text(json.dumps({"images": {}}))
            with patch.dict(STACK["start"].__globals__, validate=lambda _: None,
                            heavy=nullcontext, resource_gate=lambda: {}, custody=lambda _: directory,
                            compose=command, refresh_routes=lambda _: self.fail("Health wait failed")):
                with self.assertRaises(subprocess.CalledProcessError):
                    STACK["start"](directory)

    def test_character_creation_uses_the_canonical_game_proxy_path(self):
        row = {"nation": "A", "role": "lord", "name": "QA-A-lord", "userId": 1}
        calls = []
        observations = iter([[], [{"id": 100, "name": row["name"], "npcState": 0}]])
        def accept(path, body, token):
            calls.append((path, body, token))
            return 202, {"status": "ACCEPTED"}
        with tempfile.TemporaryDirectory() as tmp:
            with patch.dict(ROLE["create_accounts"].__globals__, credentials=lambda _: [row],
                            login=lambda _: "unit-test-token", general_rows=lambda *_: next(observations),
                            api=accept), patch.object(ROLE["time"], "sleep"):
                ROLE["create_accounts"](Path(tmp))
        self.assertEqual(len(calls), 1)
        self.assertEqual(calls[0][0], "/api/game/api/join?server=qa160")
        self.assertEqual(calls[0][1]["name"], row["name"])
        self.assertEqual(calls[0][1]["character"], "Random")
        self.assertEqual(row["generalId"], 100)

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
