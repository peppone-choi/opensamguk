"""Fast, non-Docker safety regressions for the explicit hosted QA backend."""
import argparse
from contextlib import contextmanager, redirect_stdout
import copy
import io
import json
import os
from pathlib import Path
import runpy
import signal
import tempfile
import subprocess
import sys
import unittest
from unittest.mock import patch
import zipfile

HERE = Path(__file__).resolve().parent
S = runpy.run_path(str(HERE / "local-stack.py"))
H = runpy.run_path(str(HERE / "hosted-candidate.py"))


class HostedCandidateTest(unittest.TestCase):
    def snapshot_args(self, **values):
        return argparse.Namespace(custody="/owned/custody", general_id="123", request_id=None, **values)

    def test_snapshot_errors_are_fixed_codes_and_nonzero_without_private_details(self):
        secret = "username=qa-private password=private sam_access=secret Bearer eyJ.fake token=secret SQL person='private'"
        def fail(*_args):
            raise RuntimeError(secret)
        for code, changes, args in [
            ("CUSTODY", {"custody": fail}, self.snapshot_args()),
            ("OWNER", {"hosted_owner": fail}, self.snapshot_args()),
            ("OWNER", {"hosted_owner": lambda: {"directory": "/foreign"}}, self.snapshot_args()),
            ("INVALID_ARG", {}, argparse.Namespace(custody="/owned/custody", general_id=secret, request_id=None)),
            ("INVALID_ARG", {}, argparse.Namespace(custody="/owned/custody", general_id="123", request_id="' OR username=private")),
            ("SQL_FAILED", {"sql": fail}, self.snapshot_args()),
            ("SQL_FAILED", {"sql": lambda *_: secret}, self.snapshot_args()),
        ]:
            with self.subTest(code=code):
                stack = dict(H["STACK"], custody=lambda _: Path("/owned/custody"),
                             hosted_owner=lambda: {"directory": "/owned"}, sql=lambda *_: "{}")
                stack.update(changes)
                output = io.StringIO()
                with patch.dict(H["snapshot_command"].__globals__, STACK=stack), redirect_stdout(output):
                    self.assertEqual(H["snapshot_command"](args), 1)
                self.assertEqual(output.getvalue(), json.dumps({"error": code}) + "\n")
                self.assertNotIn(secret, output.getvalue())

    def test_snapshot_guard_failure_does_not_reach_sql(self):
        stack = dict(H["STACK"], custody=lambda _: Path("/owned/custody"),
                     hosted_owner=lambda: {"directory": "/foreign"},
                     sql=lambda *_: self.fail("Foreign custody must never reach SQL"))
        with patch.dict(H["snapshot_command"].__globals__, STACK=stack), redirect_stdout(io.StringIO()):
            self.assertEqual(H["snapshot_command"](self.snapshot_args()), 1)

    def test_snapshot_success_keeps_the_original_payload(self):
        value = {"generalId": 123, "personal": {"requestId": None}, "experience": 10}
        stack = dict(H["STACK"], custody=lambda _: Path("/owned/custody"),
                     hosted_owner=lambda: {"directory": "/owned"}, sql=lambda *_: json.dumps(value))
        output = io.StringIO()
        with patch.dict(H["snapshot_command"].__globals__, STACK=stack), redirect_stdout(output):
            self.assertEqual(H["snapshot_command"](self.snapshot_args()), 0)
        self.assertEqual(json.loads(output.getvalue()), value)

    def test_snapshot_cli_failure_is_nonzero_and_only_code_on_stdout(self):
        with tempfile.TemporaryDirectory() as tmp:
            result = subprocess.run([sys.executable, str(HERE / "hosted-candidate.py"), "snapshot",
                                     "--custody", str(Path(tmp) / "missing-private"), "--general-id", "123"],
                                    text=True, capture_output=True)
        self.assertEqual(result.returncode, 1)
        self.assertEqual(result.stdout, '{"error": "CUSTODY"}\n')
        self.assertEqual(result.stderr, "")

    def environment(self, tmp):
        return {"GITHUB_ACTIONS": "true", "RUNNER_ENVIRONMENT": "github-hosted",
                "RUNNER_OS": "Linux", "RUNNER_ARCH": "X64", "GITHUB_REF": "refs/heads/main",
                "GITHUB_REPOSITORY": "peppone-choi/opensamguk", "GITHUB_RUN_ID": "123",
                "GITHUB_RUN_ATTEMPT": "1", "RUNNER_TEMP": tmp,
                "QA_OWNER_INDEX": str(Path(tmp) / "qa-owner-index.json"),
                "QA_EXECUTION_BACKEND": "github-hosted"}

    def claim(self):
        with patch.dict(S["hosted_claim"].__globals__, hosted_preflight=lambda: {}):
            return S["hosted_claim"]()[0]

    def run_record(self):
        run = {"id": 42, "event": "workflow_dispatch", "head_branch": "main",
               "path": ".github/workflows/build-pr-candidate-qa-images.yml",
               "status": "completed", "conclusion": "success", "run_attempt": 1,
               "head_sha": "b"*40, "repository": {"id": 1, "full_name": H["REPO"]},
               "head_repository": {"id": 1, "full_name": H["REPO"]}}
        jobs = [{"name": name, "conclusion": "success", "status": "completed",
                 "run_attempt": 1, "head_sha": "b"*40}
                for name in ["images ("+s+")" for s in S["SERVICES"]] + ["receipt"]]
        artifact = {"expired": False, "workflow_run": {"id": 42, "head_sha": "b"*40,
                    "head_branch": "main", "repository_id": 1, "head_repository_id": 1}}
        row = {"baseSha": "b"*40}
        return run, jobs, artifact, row

    def test_hosted_opt_in_rejects_self_hosted_missing_identity_and_non_main(self):
        with tempfile.TemporaryDirectory() as tmp:
            env = self.environment(tmp)
            for key, value in [("GITHUB_ACTIONS", "false"), ("RUNNER_ENVIRONMENT", "self-hosted"),
                               ("GITHUB_REF", "refs/heads/a"), ("GITHUB_REPOSITORY", "other/repo"),
                               ("RUNNER_OS", "Windows"), ("GITHUB_RUN_ATTEMPT", "")]:
                with self.subTest(key=key), patch.dict(os.environ, dict(env, **{key:value}), clear=True):
                    with self.assertRaises((ValueError, RuntimeError)):
                        S["hosted_environment"]()

    def test_unknown_backend_never_uses_hosted_or_managed(self):
        with patch.dict(os.environ, QA_EXECUTION_BACKEND="anything"):
            with self.assertRaises(ValueError):
                with S["heavy"]():
                    self.fail("Unknown backend acquired a lock")

    def test_claim_nonce_permissions_and_duplicate_preservation(self):
        with tempfile.TemporaryDirectory() as tmp, patch.dict(os.environ, self.environment(tmp), clear=True):
            owner = self.claim()
            self.assertEqual(S["hosted_owner"](), owner)
            directory = Path(owner["directory"])
            self.assertEqual(directory.stat().st_mode & 0o077, 0)
            self.assertEqual(Path(os.environ["QA_OWNER_INDEX"]).stat().st_mode & 0o077, 0)
            model = json.loads((directory / "ownership.json").read_text())
            for kind in ("services", "networks", "volumes"):
                self.assertTrue(all(row["labels"][S["OWNER_LABEL"]] == owner["nonce"] for row in model[kind].values()))
            self.assertEqual(model["services"]["web-gateway"]["environment"], {"COOKIE_SECURE": "false"})
            with self.assertRaises(FileExistsError):
                self.claim()
            self.assertEqual(S["hosted_owner"]()["nonce"], owner["nonce"])

    def test_owner_attempt_nonce_and_index_symlink_cannot_be_adopted(self):
        with tempfile.TemporaryDirectory() as tmp, patch.dict(os.environ, self.environment(tmp), clear=True):
            owner = self.claim()
            with patch.dict(os.environ, GITHUB_RUN_ATTEMPT="2"), self.assertRaises(ValueError):
                S["hosted_owner"]()
            (Path(owner["directory"]) / "nonce").write_text("different")
            with self.assertRaises(ValueError):
                S["hosted_owner"]()

    def test_partial_prepare_cleanup_removes_private_files_without_docker_deletion(self):
        with tempfile.TemporaryDirectory() as tmp, patch.dict(os.environ, self.environment(tmp), clear=True):
            owner = self.claim()
            custody = Path(owner["directory"]) / "custody"
            custody.mkdir(mode=0o700)
            (custody / "new-key.pem").write_text("unit-test-private-key")
            with patch.dict(S["hosted_cleanup"].__globals__, hosted_inventory=lambda *_: {"container": [], "network": [], "volume": []},
                            compose=lambda *_a, **_k: self.fail("Partial prepare must not delete Docker resources")):
                result = S["hosted_cleanup"]()
            self.assertEqual(result["status"], "PASS")
            self.assertFalse(Path(owner["directory"]).exists())
            self.assertFalse(Path(os.environ["QA_OWNER_INDEX"]).exists())

    def test_active_owned_operation_prevents_cleanup_pass(self):
        with tempfile.TemporaryDirectory() as tmp, patch.dict(os.environ, self.environment(tmp), clear=True):
            owner = self.claim()
            (Path(owner["directory"]) / "operation").mkdir()
            with patch.dict(S["hosted_cleanup"].__globals__, hosted_inventory=lambda *_: {"container": []}):
                with self.assertRaises(RuntimeError):
                    S["hosted_cleanup"]()
            self.assertTrue(Path(owner["directory"]).exists())

    def test_hosted_lock_releases_on_fixture_error_and_never_touches_meta(self):
        with tempfile.TemporaryDirectory() as tmp, patch.dict(os.environ, self.environment(tmp), clear=True):
            owner = self.claim()
            with patch.dict(S["heavy"].__wrapped__.__globals__, resource_gate=lambda: {}):
                with self.assertRaises(RuntimeError):
                    with S["heavy"]():
                        raise RuntimeError("fixture error")
            self.assertFalse((Path(owner["directory"]) / "operation").exists())
            self.assertFalse((Path(tmp) / ".locks").exists())

    def test_foreign_same_named_volume_never_reaches_cleanup_delete(self):
        names = S["resource_names"]()
        def run(args, **kwargs):
            if args[1:3] == ["volume", "ls"] and "{{.Name}}" in args:
                value = names["volume"][0] + "\n"
            elif args[1:3] == ["volume", "inspect"]:
                value = json.dumps([{"Name": names["volume"][0], "Labels": {
                    "com.docker.compose.project": S["PROJECT"], S["OWNER_LABEL"]: "foreign"}}])
            else:
                value = ""
            return type("Completed", (), {"stdout": value.encode()})()
        with patch.dict(S["hosted_inventory"].__globals__, run=run):
            with self.assertRaises(ValueError):
                S["hosted_inventory"]({"nonce": "ours"})

    def test_preflight_insufficient_memory_reports_metrics_before_docker(self):
        with tempfile.TemporaryDirectory() as tmp, patch.dict(os.environ, self.environment(tmp), clear=True):
            class LowMemory:
                def read_text(self):
                    return "MemAvailable: 1024 kB\n"
            real_path = Path
            with patch.dict(S["hosted_preflight"].__globals__, hosted_environment=lambda: Path(tmp),
                            resource_gate=lambda: {"freeGiB": 24},
                            Path=lambda p: LowMemory() if p == "/proc/meminfo" else real_path(p),
                            run=lambda *_a, **_k: self.fail("Must refuse before Docker")):
                with self.assertRaises(RuntimeError) as caught:
                    S["hosted_preflight"]()
            self.assertLess(caught.exception.metrics["availableGiB"], 8)

    def test_incomplete_process_custody_never_claims_cleanup_pass_or_deletes_stack(self):
        with tempfile.TemporaryDirectory() as tmp, patch.dict(os.environ, self.environment(tmp), clear=True):
            owner = self.claim()
            S["private_json"](Path(owner["directory"])/"process-pending.json", {"nonce": owner["nonce"]})
            with patch.dict(S["hosted_cleanup"].__globals__,
                            hosted_inventory=lambda *_: self.fail("Do not touch Docker with incomplete process custody")):
                with self.assertRaisesRegex(RuntimeError, "incomplete process custody"):
                    S["hosted_cleanup"]()
            self.assertTrue(Path(owner["directory"]).exists())

    def test_owned_process_group_is_terminated_and_absence_verified(self):
        with tempfile.TemporaryDirectory() as tmp, patch.dict(os.environ, self.environment(tmp), clear=True):
            owner = self.claim()
            child = subprocess.Popen([sys.executable, "-c", "import time; time.sleep(30)"],
                                     env=dict(os.environ, QA_PROCESS_NONCE=owner["nonce"]), start_new_session=True)
            try:
                created = Path(f"/proc/{child.pid}/stat").read_text().rsplit(")",1)[1].split()[19]
                S["private_json"](Path(owner["directory"])/"process.json",
                    {"pid": child.pid, "createdTick": created, "nonce": owner["nonce"]})
                S["hosted_process_cleanup"](owner)
                child.wait(timeout=5)
                self.assertIsNotNone(child.returncode)
                self.assertFalse((Path(owner["directory"])/"process.json").exists())
            finally:
                if child.poll() is None:
                    child.kill()
                    child.wait()

    def test_failed_process_receipt_write_still_verifies_owned_group_absence(self):
        with tempfile.TemporaryDirectory() as tmp, patch.dict(os.environ, self.environment(tmp), clear=True):
            owner = self.claim()
            child = subprocess.Popen([sys.executable, "-c", "import time; time.sleep(30)"],
                                     env=dict(os.environ, QA_PROCESS_NONCE=owner["nonce"]), start_new_session=True)
            try:
                created = Path(f"/proc/{child.pid}/stat").read_text().rsplit(")",1)[1].split()[19]
                S["hosted_process_cleanup"](owner, {"pid": child.pid, "createdTick": created, "nonce": owner["nonce"]})
                child.wait(timeout=5)
                self.assertIsNotNone(child.returncode)
            finally:
                if child.poll() is None:
                    child.kill()
                    child.wait()

    def test_reused_pid_receipt_never_kills_another_process(self):
        with tempfile.TemporaryDirectory() as tmp, patch.dict(os.environ, self.environment(tmp), clear=True):
            owner = self.claim()
            child = subprocess.Popen([sys.executable, "-c", "import time; time.sleep(30)"], start_new_session=True)
            try:
                S["private_json"](Path(owner["directory"])/"process.json",
                    {"pid": child.pid, "createdTick": "0", "nonce": owner["nonce"]})
                with self.assertRaisesRegex(ValueError, "reused"):
                    S["hosted_process_cleanup"](owner)
                self.assertIsNone(child.poll())
            finally:
                child.kill()
                child.wait()

    @staticmethod
    def process_alive(pid):
        try:
            return Path(f"/proc/{pid}/stat").read_text().rsplit(")", 1)[1].split()[0] != "Z"
        except FileNotFoundError:
            return False

    @contextmanager
    def detached_browser(self, owner, *, leader_exits=False, browser_nonce=None):
        """Tiny setsid grandchild, not a browser/QA stack; always reap test children."""
        code = (
            "import os,signal,subprocess,sys,time\n"
            "child = subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(60)'], "
            f"start_new_session=True, env=dict(os.environ, QA_PROCESS_NONCE={browser_nonce or owner['nonce']!r}))\n"
            "print(child.pid, flush=True)\n"
            "def stop(*_):\n"
            "    try: child.wait(timeout=5)\n"
            "    except subprocess.TimeoutExpired: pass\n"
            "    sys.exit(0)\n"
            "signal.signal(signal.SIGTERM, stop)\n"
            + ("sys.exit(0)\n" if leader_exits else "time.sleep(60)\n"))
        leader = subprocess.Popen([sys.executable, "-c", code], stdout=subprocess.PIPE, text=True,
                                  env=dict(os.environ, QA_PROCESS_NONCE=owner["nonce"]), start_new_session=True)
        browser, tick = None, None
        try:
            browser = int(leader.stdout.readline())
            tick = Path(f"/proc/{browser}/stat").read_text().rsplit(")", 1)[1].split()[19]
            created = Path(f"/proc/{leader.pid}/stat").read_text().rsplit(")", 1)[1].split()[19]
            S["private_json"](Path(owner["directory"])/"process.json",
                              {"pid": leader.pid, "createdTick": created, "nonce": owner["nonce"]})
            if leader_exits:
                leader.wait(timeout=5)
            yield leader, browser
        finally:
            # Only the exact tiny processes created above may be touched by the test.
            if browser is not None and self.process_alive(browser):
                current = Path(f"/proc/{browser}/stat").read_text().rsplit(")", 1)[1].split()[19]
                if current == tick:
                    os.kill(browser, signal.SIGKILL)
            if leader.poll() is None:
                leader.terminate()
                try:
                    leader.wait(timeout=6)
                except subprocess.TimeoutExpired:
                    leader.kill()
                    leader.wait(timeout=5)
            leader.stdout.close()

    def test_detached_browser_tree_is_terminated_after_runner_group_exit(self):
        with tempfile.TemporaryDirectory() as tmp, patch.dict(os.environ, self.environment(tmp), clear=True):
            owner = self.claim()
            with self.detached_browser(owner) as (leader, browser):
                self.assertNotEqual(os.getpgid(browser), leader.pid)
                S["hosted_process_cleanup"](owner)
                leader.wait(timeout=5)
                self.assertFalse(self.process_alive(browser))
                self.assertFalse((Path(owner["directory"])/"process.json").exists())

    def test_detached_browser_is_found_even_after_leader_already_exited(self):
        with tempfile.TemporaryDirectory() as tmp, patch.dict(os.environ, self.environment(tmp), clear=True):
            owner = self.claim()
            with self.detached_browser(owner, leader_exits=True) as (_, browser):
                self.assertTrue(self.process_alive(browser))
                S["hosted_process_cleanup"](owner)
                self.assertFalse(self.process_alive(browser))

    def test_no_receipt_still_checks_nonce_owned_detached_processes(self):
        with tempfile.TemporaryDirectory() as tmp, patch.dict(os.environ, self.environment(tmp), clear=True):
            owner = self.claim()
            with self.detached_browser(owner, leader_exits=True) as (_, browser):
                (Path(owner["directory"])/"process.json").unlink()
                S["hosted_process_cleanup"](owner)
                self.assertFalse(self.process_alive(browser))

    def test_detached_other_nonce_and_unrelated_same_user_process_survive(self):
        with tempfile.TemporaryDirectory() as tmp, patch.dict(os.environ, self.environment(tmp), clear=True):
            owner = self.claim()
            with self.detached_browser(owner, browser_nonce=owner["nonce"]+"-other") as (_, browser):
                S["hosted_process_cleanup"](owner)
                self.assertTrue(self.process_alive(browser))
                self.assertFalse((Path(owner["directory"])/"process.json").exists())

    def test_foreign_member_of_original_group_refuses_any_signal(self):
        with tempfile.TemporaryDirectory() as tmp, patch.dict(os.environ, self.environment(tmp), clear=True):
            owner = self.claim()
            child = subprocess.Popen([sys.executable, "-c", "import time; time.sleep(30)"], start_new_session=True)
            try:
                created = Path(f"/proc/{child.pid}/stat").read_text().rsplit(")",1)[1].split()[19]
                S["private_json"](Path(owner["directory"])/"process.json",
                    {"pid": child.pid, "createdTick": created, "nonce": owner["nonce"]})
                with patch.object(signal, "pidfd_send_signal") as send:
                    with self.assertRaisesRegex(ValueError, "Foreign process"):
                        S["hosted_process_cleanup"](owner)
                    send.assert_not_called()
                self.assertIsNone(child.poll())
                self.assertTrue((Path(owner["directory"])/"process.json").exists())
            finally:
                child.kill()
                child.wait()

    def test_detached_survivor_never_reports_pass_or_removes_custody(self):
        with tempfile.TemporaryDirectory() as tmp, patch.dict(os.environ, self.environment(tmp), clear=True):
            owner = self.claim()
            with self.detached_browser(owner, leader_exits=True) as (_, browser):
                # Simulate unsuccessful TERM/KILL without a 20-second unit-test wait.
                clock = iter(range(0, 200, 20))
                with patch.object(signal, "pidfd_send_signal") as send, \
                        patch.object(S["time"], "monotonic", side_effect=lambda: next(clock)), \
                        patch.dict(S["hosted_cleanup"].__globals__,
                                   hosted_inventory=lambda *_: self.fail("Survivor must block Docker cleanup")):
                    with self.assertRaisesRegex(RuntimeError, "CLEANUP_FAILED: owned processes remain"):
                        S["hosted_cleanup"]()
                    self.assertEqual([call.args[1] for call in send.call_args_list], [signal.SIGTERM, signal.SIGKILL])
                self.assertTrue(self.process_alive(browser))
                self.assertTrue((Path(owner["directory"])/"process.json").exists())
                self.assertTrue(Path(os.environ["QA_OWNER_INDEX"]).exists())

    def test_reused_detached_pid_between_discovery_and_signal_is_refused(self):
        with tempfile.TemporaryDirectory() as tmp, patch.dict(os.environ, self.environment(tmp), clear=True):
            owner = self.claim()
            with self.detached_browser(owner, leader_exits=True) as (_, browser):
                original_read, original_open = Path.read_text, os.pidfd_open
                pinned = []
                def open_pidfd(pid):
                    fd = original_open(pid)
                    pinned.append(pid)
                    return fd
                def read(path, *args, **kwargs):
                    value = original_read(path, *args, **kwargs)
                    if pinned and path == Path(f"/proc/{browser}/stat"):
                        prefix, fields = value.rsplit(")", 1)
                        fields = fields.split()
                        fields[19] = "0"  # Simulate reuse after discovery, before signalling.
                        return prefix + ") " + " ".join(fields)
                    return value
                with patch.object(os, "pidfd_open", side_effect=open_pidfd), \
                        patch.object(Path, "read_text", read), patch.object(signal, "pidfd_send_signal") as send:
                    with self.assertRaisesRegex(RuntimeError, "PID reused before signal"):
                        S["hosted_process_cleanup"](owner)
                    send.assert_not_called()
                self.assertTrue(self.process_alive(browser))
                self.assertTrue((Path(owner["directory"])/"process.json").exists())

    def test_unreadable_job_process_environment_fails_closed_without_signal(self):
        with tempfile.TemporaryDirectory() as tmp, patch.dict(os.environ, self.environment(tmp), clear=True):
            owner = self.claim()
            with self.detached_browser(owner, leader_exits=True) as (_, browser):
                original_read = Path.read_bytes
                def read(path):
                    if path == Path(f"/proc/{browser}/environ"):
                        raise PermissionError("unit test")
                    return original_read(path)
                with patch.object(Path, "read_bytes", read), patch.object(signal, "pidfd_send_signal") as send, \
                        patch.object(S["time"], "sleep") as wait:
                    with self.assertRaisesRegex(RuntimeError, "ownership cannot be verified"):
                        S["hosted_process_cleanup"](owner)
                    send.assert_not_called()
                    self.assertEqual(wait.call_args_list, [unittest.mock.call(.01)] * 5)
                self.assertTrue(self.process_alive(browser))
                self.assertTrue((Path(owner["directory"])/"process.json").exists())

    def test_foreign_uid_denied_stat_and_environment_does_not_block_cleanup(self):
        with tempfile.TemporaryDirectory() as tmp, patch.dict(os.environ, self.environment(tmp), clear=True):
            owner = self.claim()
            # A real nonce-owned detached child must still be removed; do not mock
            # the entire /proc scan or hide unrelated PIDs from discovery.
            with self.detached_browser(owner, leader_exits=True) as (_, browser):
                real_read, real_bytes = Path.read_text, Path.read_bytes
                def read(path, *args, **kwargs):
                    if path == Path("/proc/1/status"):
                        return "Uid:\t123456\t123456\t123456\t123456\n"
                    if path == Path("/proc/1/stat"):
                        raise PermissionError("foreign system process stat denied")
                    return real_read(path, *args, **kwargs)
                def read_bytes(path):
                    if path == Path("/proc/1/environ"):
                        self.fail("Other UID environment must never be opened")
                    return real_bytes(path)
                with patch.object(Path, "read_text", read), patch.object(Path, "read_bytes", read_bytes):
                    S["hosted_process_cleanup"](owner)
                self.assertFalse(self.process_alive(browser))

    def test_pre_nonce_same_uid_denied_environment_is_not_owned(self):
        with tempfile.TemporaryDirectory() as tmp, patch.dict(os.environ, self.environment(tmp), clear=True):
            # This test process represents a pre-existing runner service. A later
            # minter starts on a later kernel tick, just as separate CLI steps do.
            child = subprocess.Popen([sys.executable, "-c", "import time; time.sleep(30)"], start_new_session=True)
            try:
                tick = int(Path(f"/proc/{child.pid}/stat").read_text().rsplit(")", 1)[1].split()[19])
                import time
                time.sleep(.03)
                minter = subprocess.Popen([sys.executable, "-c", "pass"])
                floor = int(Path(f"/proc/{minter.pid}/stat").read_text().rsplit(")", 1)[1].split()[19])
                minter.wait(timeout=5)
                self.assertGreater(floor, tick)
                owner = self.claim()
                owner["processFloorTick"] = floor
                real_bytes, real_read = Path.read_bytes, Path.read_text
                def read_bytes(path):
                    if path == Path(f"/proc/{child.pid}/environ"):
                        raise PermissionError("pre-existing same-UID runner service")
                    return real_bytes(path)
                # Hosted systems may have pre-existing set-UID helpers with a
                # runner real UID but root effective/saved UID. The kernel birth
                # proof must precede demanding nonce-child credential equality.
                for mixed in (False, True):
                    def read(path, *args, **kwargs):
                        if mixed and path == Path(f"/proc/{child.pid}/status"):
                            return f"Uid:\t{os.geteuid()}\t123456\t123456\t123456\n"
                        return real_read(path, *args, **kwargs)
                    with self.subTest(mixedUid=mixed), patch.object(Path, "read_bytes", read_bytes), \
                            patch.object(Path, "read_text", read):
                        S["hosted_process_cleanup"](owner)
                    self.assertIsNone(child.poll())
            finally:
                child.kill()
                child.wait(timeout=5)

    @unittest.skipIf(os.geteuid() == 0, "requires actual unprivileged proc permission checks")
    def test_actual_nonroot_nondumpable_nonce_child_fails_closed(self):
        with tempfile.TemporaryDirectory() as tmp, patch.dict(os.environ, self.environment(tmp), clear=True):
            owner = self.claim()
            code = ("import ctypes,time; assert ctypes.CDLL(None).prctl(4,0,0,0,0)==0; "
                    "print('ready',flush=True); time.sleep(30)")
            child = subprocess.Popen([sys.executable, "-c", code], stdout=subprocess.PIPE, text=True,
                                     env=dict(os.environ, QA_PROCESS_NONCE=owner["nonce"]), start_new_session=True)
            try:
                self.assertEqual(child.stdout.readline().strip(), "ready")
                with self.assertRaises(PermissionError):
                    Path(f"/proc/{child.pid}/environ").read_bytes()
                # Its proc directory becomes root-owned, but status retains the
                # real UID. A directory-owner skip would silently lose this child.
                with patch.object(signal, "pidfd_send_signal") as send:
                    with self.assertRaisesRegex(RuntimeError, "ownership cannot be verified") as caught:
                        S["hosted_process_cleanup"](owner)
                    self.assertIn(f"pid={child.pid},", str(caught.exception))
                    send.assert_not_called()
                self.assertIsNone(child.poll())
            finally:
                child.kill()
                child.wait(timeout=5)
                child.stdout.close()

    def test_same_uid_different_gid_nonce_child_is_not_ignored(self):
        with tempfile.TemporaryDirectory() as tmp, patch.dict(os.environ, self.environment(tmp), clear=True):
            owner = self.claim()
            with self.detached_browser(owner, leader_exits=True) as (_, browser):
                original_stat = Path.stat
                def stat(path, *args, **kwargs):
                    result = original_stat(path, *args, **kwargs)
                    if path == Path(f"/proc/{browser}"):
                        fields = list(result)
                        fields[5] = os.getegid() + 1
                        return os.stat_result(fields)
                    return result
                with patch.object(Path, "stat", stat):
                    S["hosted_process_cleanup"](owner)
                self.assertFalse(self.process_alive(browser))

    def test_uid_changes_between_nonce_discovery_and_signal_are_refused(self):
        with tempfile.TemporaryDirectory() as tmp, patch.dict(os.environ, self.environment(tmp), clear=True):
            owner = self.claim()
            with self.detached_browser(owner, leader_exits=True) as (_, browser):
                real_read, real_open = Path.read_text, os.pidfd_open
                pinned = []
                def open_pidfd(pid):
                    result = real_open(pid)
                    pinned.append(pid)
                    return result
                def read(path, *args, **kwargs):
                    if pinned and path == Path(f"/proc/{browser}/status"):
                        return "Uid:\t123456\t123456\t123456\t123456\n"
                    return real_read(path, *args, **kwargs)
                with patch.object(os, "pidfd_open", side_effect=open_pidfd), \
                        patch.object(Path, "read_text", read), patch.object(signal, "pidfd_send_signal") as send:
                    with self.assertRaisesRegex(RuntimeError, "ownership changed before signal"):
                        S["hosted_process_cleanup"](owner)
                    send.assert_not_called()
                self.assertTrue(self.process_alive(browser))
                self.assertTrue((Path(owner["directory"])/"process.json").exists())

    def test_unknown_or_mixed_uid_evidence_never_signals_or_removes_custody(self):
        for evidence in (None, "Uid:\t0\n", f"Uid:\t{os.geteuid()}\t123456\t123456\t123456\n"):
            with self.subTest(evidence=evidence), tempfile.TemporaryDirectory() as tmp, \
                    patch.dict(os.environ, self.environment(tmp), clear=True):
                owner = self.claim()
                with self.detached_browser(owner, leader_exits=True) as (_, browser):
                    real_read = Path.read_text
                    def read(path, *args, **kwargs):
                        if path == Path(f"/proc/{browser}/status"):
                            if evidence is None:
                                raise PermissionError("status denied")
                            return evidence
                        return real_read(path, *args, **kwargs)
                    with patch.object(Path, "read_text", read), patch.object(signal, "pidfd_send_signal") as send:
                        with self.assertRaisesRegex(RuntimeError, "CLEANUP_FAILED"):
                            S["hosted_process_cleanup"](owner)
                        send.assert_not_called()
                    self.assertTrue(self.process_alive(browser))
                    self.assertTrue((Path(owner["directory"])/"process.json").exists())

    def test_missing_or_foreign_uid_starttime_custody_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp, patch.dict(os.environ, self.environment(tmp), clear=True):
            owner = self.claim()
            for key, value in (("processUid", None), ("processUid", os.geteuid()+1),
                               ("processFloorTick", None), ("processFloorTick", 0)):
                with self.subTest(key=key, value=value), patch.object(signal, "pidfd_send_signal") as send:
                    with self.assertRaisesRegex(ValueError, "custody required"):
                        S["hosted_process_cleanup"](dict(owner, **{key: value}))
                    send.assert_not_called()

    def test_environment_denial_only_skips_confirmed_same_tick_zombie(self):
        with tempfile.TemporaryDirectory() as tmp, patch.dict(os.environ, self.environment(tmp), clear=True):
            owner = self.claim()
            child = subprocess.Popen([sys.executable, "-c", "import time; time.sleep(30)"],
                                     env=dict(os.environ, QA_PROCESS_NONCE=owner["nonce"]), start_new_session=True)
            try:
                created = Path(f"/proc/{child.pid}/stat").read_text().rsplit(")", 1)[1].split()[19]
                S["private_json"](Path(owner["directory"])/"process.json",
                                  {"pid": child.pid, "createdTick": created, "nonce": owner["nonce"]})
                real_bytes = Path.read_bytes
                denied = []
                def read_bytes(path):
                    if path == Path(f"/proc/{child.pid}/environ"):
                        child.kill()
                        import time
                        deadline = time.monotonic() + 5
                        while self.process_alive(child.pid) and time.monotonic() < deadline:
                            time.sleep(.001)
                        self.assertFalse(self.process_alive(child.pid))
                        # Keep it unreaped: the exact process is now a zombie.
                        self.assertEqual(Path(f"/proc/{child.pid}/stat").read_text().rsplit(")", 1)[1].split()[19], created)
                        denied.append(True)
                        raise PermissionError("exit raced with environ read")
                    return real_bytes(path)
                with patch.object(Path, "read_bytes", read_bytes):
                    S["hosted_process_cleanup"](owner)
                self.assertTrue(denied)
                self.assertFalse((Path(owner["directory"])/"process.json").exists())
            finally:
                if child.poll() is None:
                    child.kill()
                child.wait(timeout=5)

    def test_environment_denial_waits_for_same_tick_exit_before_removing_custody(self):
        with tempfile.TemporaryDirectory() as tmp, patch.dict(os.environ, self.environment(tmp), clear=True):
            owner = self.claim()
            child = subprocess.Popen([sys.executable, "-c", "import time; time.sleep(30)"],
                                     env=dict(os.environ, QA_PROCESS_NONCE=owner["nonce"]), start_new_session=True)
            try:
                created = Path(f"/proc/{child.pid}/stat").read_text().rsplit(")", 1)[1].split()[19]
                receipt = Path(owner["directory"])/"process.json"
                S["private_json"](receipt, {"pid": child.pid, "createdTick": created, "nonce": owner["nonce"]})
                real_bytes, real_sleep = Path.read_bytes, S["time"].sleep
                denied, waits = [], []
                def read_bytes(path):
                    if path == Path(f"/proc/{child.pid}/environ"):
                        self.assertTrue(self.process_alive(child.pid))
                        denied.append(True)
                        raise PermissionError("exit raced with environ read")
                    return real_bytes(path)
                def finish_exit(delay):
                    self.assertTrue(denied)
                    self.assertTrue(receipt.exists())
                    self.assertTrue(self.process_alive(child.pid))
                    waits.append(delay)
                    child.kill()  # Only our exact Popen child; cleanup must not signal it.
                    import time
                    deadline = time.monotonic() + 5
                    while self.process_alive(child.pid) and time.monotonic() < deadline:
                        real_sleep(.001)
                    self.assertFalse(self.process_alive(child.pid))
                    self.assertEqual(Path(f"/proc/{child.pid}/stat").read_text().rsplit(")", 1)[1].split()[19], created)
                with patch.object(Path, "read_bytes", read_bytes), \
                        patch.object(S["time"], "sleep", side_effect=finish_exit), \
                        patch.object(signal, "pidfd_send_signal") as send:
                    S["hosted_process_cleanup"](owner)
                    send.assert_not_called()
                self.assertEqual(waits, [.01])
                self.assertFalse(receipt.exists())
            finally:
                if child.poll() is None:
                    child.kill()
                child.wait(timeout=5)

    def test_environment_denial_reused_zombie_tick_never_removes_custody(self):
        with tempfile.TemporaryDirectory() as tmp, patch.dict(os.environ, self.environment(tmp), clear=True):
            owner = self.claim()
            with self.detached_browser(owner, leader_exits=True) as (_, browser):
                real_bytes, real_read = Path.read_bytes, Path.read_text
                denied = []
                def read_bytes(path):
                    if path == Path(f"/proc/{browser}/environ"):
                        denied.append(True)
                        raise PermissionError("unreadable process")
                    return real_bytes(path)
                def read(path, *args, **kwargs):
                    value = real_read(path, *args, **kwargs)
                    if denied and path == Path(f"/proc/{browser}/stat"):
                        prefix, fields = value.rsplit(")", 1)
                        fields = fields.split()
                        fields[0], fields[19] = "Z", "0"
                        return prefix + ") " + " ".join(fields)
                    return value
                with patch.object(Path, "read_bytes", read_bytes), patch.object(Path, "read_text", read), \
                        patch.object(signal, "pidfd_send_signal") as send:
                    with self.assertRaisesRegex(RuntimeError, "ownership cannot be verified"):
                        S["hosted_process_cleanup"](owner)
                    send.assert_not_called()
                self.assertTrue(self.process_alive(browser))
                self.assertTrue((Path(owner["directory"])/"process.json").exists())

    def test_original_successful_run_requires_all_seven_successes_and_provenance(self):
        record = self.run_record()
        H["verify_run"](*record, "42")
        changes = [(0, "conclusion", "failure"), (0, "head_branch", "other"),
                   (0, "run_attempt", 2), (0, "path", ".github/workflows/other.yml"),
                   (2, "expired", True)]
        for i, key, value in changes:
            changed = copy.deepcopy(record)
            changed[i][key] = value
            with self.subTest(key=key), self.assertRaises(ValueError):
                H["verify_run"](*changed, "42")
        missing = copy.deepcopy(record)
        missing[1].pop()
        with self.assertRaises(ValueError):
            H["verify_run"](*missing, "42")
        wrong_base = copy.deepcopy(record)
        wrong_base[3]["baseSha"] = "a"*40
        with self.assertRaises(ValueError):
            H["verify_run"](*wrong_base, "42")

    def test_tampered_artifact_bytes_rejected_before_image_or_git_operation(self):
        run, jobs, artifact, row = self.run_record()
        artifact.update(id=100, name="candidate-receipt", size_in_bytes=100, digest="sha256:"+"0"*64)
        def gh(path, binary=False):
            if path.endswith("/zip"):
                return b"tampered zip"
            if path.endswith("/jobs?per_page=100"):
                return {"jobs": jobs}
            if path.endswith("/artifacts?per_page=100"):
                return {"artifacts": [artifact]}
            return run
        with tempfile.TemporaryDirectory() as tmp:
            with patch.dict(H["download_receipt"].__globals__, gh=gh):
                with self.assertRaisesRegex(ValueError, "SHA256"):
                    H["download_receipt"]("42", "a"*40, "1622", Path(tmp)/"receipt")

    def test_fixture_plan_uses_only_actual_distinct_database_witnesses(self):
        rows = [{"nation": 13, "city": 28, "sample": 101}, {"nation": 17, "city": 41, "sample": 105},
                {"nation": 20, "city": 60, "sample": 111}]
        with patch.dict(H["fixture_plan"].__globals__["STACK"], sql=lambda *_: json.dumps(rows)):
            self.assertEqual(H["fixture_plan"]("private")["nationIds"], [13,17,20])
        with patch.dict(H["fixture_plan"].__globals__["STACK"], sql=lambda *_: "[]"):
            with self.assertRaises(ValueError):
                H["fixture_plan"]("private")

    def test_graph_requires_applied_marker_not_just_accounts_verify(self):
        with patch.dict(H["role_graph"].__globals__["ROLE"], verify=lambda _: None):
            with patch.dict(H["role_graph"].__globals__["STACK"], sql=lambda *_: ""):
                with self.assertRaisesRegex(ValueError, "not applied"):
                    H["role_graph"]("private")

    def test_bootstrap_separates_admin_and_never_emits_credentials(self):
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp)
            (directory/"qa.env").write_text("QA_ADMIN_PASSWORD='unit-test-private'\n")
            def api(path, body=None, token=None):
                if path.endswith("/login"):
                    return 200, {"accessToken": "unit-test-memory-token", "user": {"id": 17}}
                if path.endswith("/me"):
                    return 200, {"id": 17, "role": "ADMIN"}
                if path.endswith("/users"):
                    return 200, {"allowJoin": True, "allowLogin": True}
                return 200, {}
            output = io.StringIO()
            with patch.dict(H["bootstrap"].__globals__["STACK"], custody=lambda _: directory):
                with patch.dict(H["bootstrap"].__globals__["ROLE"], api=api), redirect_stdout(output):
                    H["bootstrap"](directory)
            self.assertFalse((directory/"accounts.json").exists())
            self.assertEqual((directory/"admin-account.json").stat().st_mode & 0o077, 0)
            self.assertNotIn("private", output.getvalue())
            self.assertNotIn("unit-test-memory-token", (directory/"admin-account.json").read_text())

    def test_prepare_failure_still_runs_finally_cleanup(self):
        with tempfile.TemporaryDirectory() as tmp, patch.dict(os.environ, self.environment(tmp), clear=True):
            called = []
            def fail(*_):
                raise RuntimeError("partial prepare")
            stack = H["execute"].__globals__["STACK"]
            with patch.dict(stack, hosted_environment=lambda: Path(tmp),
                            hosted_claim=lambda: ({"directory": str(Path(tmp)/"owned")}, {}),
                            prepare=fail, hosted_cleanup=lambda: called.append("cleanup") or {"status": "PASS"}):
                with patch.dict(H["execute"].__globals__, download_receipt=lambda *_: {}, verify_runtime=lambda: None):
                    with self.assertRaises(RuntimeError):
                        H["execute"](argparse.Namespace(image_run_id="42", source_sha="a"*40, pr_number="1622"))
            self.assertEqual(called, ["cleanup"])
            self.assertEqual(json.loads((Path(tmp)/"qa-public/runtime.json").read_text())["status"], "FAIL")

    def test_workflow_no_write_secrets_paid_runner_or_raw_upload(self):
        workflow = (S["ROOT"]/".github/workflows/run-pr-candidate-qa.yml").read_text()
        self.assertIn("runs-on: ubuntu-24.04", workflow)
        self.assertIn("timeout-minutes: 50", workflow)
        job_header = workflow.split("    steps:", 1)[0]
        self.assertNotIn("runner.temp", job_header)
        self.assertEqual(workflow.count("QA_OWNER_INDEX: "), 2)
        self.assertIn("if: always()", workflow)
        self.assertNotIn("secrets.", workflow)
        self.assertNotIn(": write", workflow)
        self.assertNotIn("self-hosted", workflow)
        upload = workflow.split("path: |", 1)[1]
        self.assertNotIn("*", upload)
        self.assertNotIn("custody", upload)
        self.assertNotIn("playwright", upload)

    def test_live_spec_never_synthesizes_backend_or_captures_secrets(self):
        spec = (S["ROOT"]/"web/game/e2e/qa160-live.spec.ts").read_text()
        config = (S["ROOT"]/"web/game/playwright.qa160.config.ts").read_text()
        for forbidden in ("page.route", ".fulfill(", ".storageState(", ".screenshot("):
            self.assertNotIn(forbidden, spec)
        for setting in ("trace: 'off'", "video: 'off'", "screenshot: 'off'", "retries: 0", "workers: 1"):
            self.assertIn(setting, config)


if __name__ == "__main__":
    unittest.main()
