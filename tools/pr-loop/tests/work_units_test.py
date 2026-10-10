"""Independent contracts for work-unit admission, impact and completion."""
import sys
import copy
import json
import hashlib
import multiprocessing
import tempfile
from datetime import datetime, timezone
from unittest.mock import patch
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "lib"))
from work_units.acceptance import parse_ac
from work_units.surface import MemoryTree, derive_impact
from work_units.queue import eligibility
from work_units.queue import rank, next_units
from work_units.schema import binding, manifest, safe_path, durable_write, read_record, digest
from work_units.claim import acquire, active_leases, conflicts, lease_covers
from work_units.qa import static_reference, executed_tests, verify_executed, required_lanes
from work_units.qa import validate_qa, browser_execution
from work_units.gaps import recompute, compare
from work_units.verify import structural_check, check_conclusions, merge_proof
from work_units.adapters import ApiError, GitHubReader, JiraAdapter
from work_units import completion, bundle_hash
from work_units import schema, cli

AC = "<!-- work-unit-ac v1 -->\npriority: P2\ninputs: court.reward\n- AC-1: ship\n- AC-2: accept\n<!-- /work-unit-ac -->"
HEAD, MERGE = "a" * 40, "b" * 40
REPO = "peppone-choi/opensamguk"


def unit_for(impact, criteria=None):
    ac = parse_ac(AC)
    return {"schemaVersion": 1, "unitId": "sample", "branch": "feature",
            "issues": [{"system": "github", "repo": REPO, "number": 1}],
            "acceptance": {"source": REPO + "#1", "mode": "block",
                           "fingerprint": ac["fingerprint"], "criteria": criteria or ["AC-1"]},
            "commandImpact": {k: impact[k] for k in ("result", "inputIds", "scopes", "sammo", "na")},
            "qa": {}, "qaNa": {}, "verification": {"commands": [], "results": "NOT_RUN", "skips": [], "limitations": []},
            "reservation": {"nonceSha256": hashlib.sha256(b"nonce").hexdigest()}}


class FakeReader:
    def __init__(self):
        self.issue = {"number": 1, "state": "open", "body": AC}
        self.comments = []
        self.parent_count = 1
        self.compare_status = "ahead"
        self.blob_sha = "c" * 40

    def get(self, path):
        if path == "user":
            return {"login": "owner"}
        if "/compare/" in path:
            return {"status": self.compare_status}
        if "/commits/" in path:
            return {"parents": [{}] * self.parent_count}
        if "/contents/" in path:
            return {"type": "file", "sha": self.blob_sha}
        if "/issues/" in path:
            return self.issue
        raise AssertionError(path)

    def pages(self, path, **kwargs):
        return self.comments


class FakeWriter:
    def __init__(self, reader, error=None, crash=False):
        self.reader, self.error, self.crash = reader, error, crash
        self.writes = 0

    def write(self, path, payload):
        self.writes += 1
        if self.error:
            raise self.error
        self.reader.comments.append({"id": self.writes, "body": payload["body"], "user": {"login": "owner"}})
        if self.crash:
            raise OSError("crash after remote write")
        return self.reader.comments[-1]


def concurrent_claim(state, identity, output):
    try:
        acquire(Path(state), identity, identity, {"issues": [REPO + "#1"], "inputs": [], "scopes": []}, project="game", repo=REPO)
        output.put("won")
    except ValueError:
        output.put("conflict")


class InitialContractsTest(unittest.TestCase):
    def test_exclusive_publication_failure_never_exposes_partial_final(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / 'audit.json'
            original = schema.os.fdopen
            class FailingWrite:
                def __init__(self, fd, mode):
                    self.handle = original(fd, mode)
                def __enter__(self):
                    return self
                def __exit__(self, *args):
                    self.handle.close()
                def fileno(self):
                    return self.handle.fileno()
                def write(self, payload):
                    self.handle.write(payload[:3])
                    raise OSError('partial write after open')
            with patch.object(schema.os, 'fdopen', side_effect=FailingWrite):
                with self.assertRaises(OSError):
                    durable_write(path, {'complete': True}, exclusive=True)
            self.assertFalse(path.exists())
            self.assertEqual(list(Path(temp).iterdir()), [])
            with patch.object(schema.os, 'fsync', side_effect=OSError('disk full after open')):
                with self.assertRaises(OSError):
                    durable_write(path, {'complete': True}, exclusive=True)
            self.assertFalse(path.exists())
            self.assertEqual(list(Path(temp).iterdir()), [])
            durable_write(path, {'complete': True}, exclusive=True)
            with self.assertRaises(FileExistsError):
                durable_write(path, {'complete': False}, exclusive=True)
            self.assertEqual(read_record(path), {'complete': True})

    def test_record_rejects_external_registration_before_api_or_audit(self):
        with tempfile.TemporaryDirectory() as temp:
            state = Path(temp) / 'state'
            crafted = Path(temp) / 'crafted.json'
            lease = acquire(state, 'sample', 'nonce', {'issues': [REPO + '#1'], 'inputs': [], 'scopes': ['ALL_INPUTS']}, project='game', repo=REPO)
            durable_write(crafted, {'version': 2, 'repo': REPO, 'branch': 'feature',
                                   'unitId': 'sample', 'nonce': 'nonce', 'lease': lease})
            reader = FakeReader()
            raw = {'number': 2, 'merged': True, 'merged_at': '2026-10-09T00:00:00Z',
                   'merge_commit_sha': MERGE, 'head': {'sha': HEAD, 'ref': 'feature'},
                   'base': {'ref': 'main', 'repo': {'full_name': REPO}}}
            tree = MemoryTree.default()
            impact = derive_impact(tree, tree, [{'status': 'M', 'path': 'docs/guide.md'}])
            unit = unit_for(impact)
            receipt = {'headSha': HEAD, 'manifestPath': 'work-units/units/sample.json',
                       'manifestBlob': reader.blob_sha, 'reservation': 'VERIFIED'}
            host = {'result': 'PASS', 'libHash': bundle_hash(), 'receipt': receipt,
                    'review': {'independent': True}, 'checks': []}
            original_get = reader.get
            def get(path):
                return raw if path.endswith('/pulls/2') else original_get(path)
            with patch.object(cli, 'context', return_value=(Path(temp), state)), \
                 patch.object(cli, 'GitHubReader', return_value=reader), \
                 patch.object(reader, 'get', side_effect=get) as api, \
                 patch.object(cli, 'independent_review', return_value={}), \
                 patch.object(cli, 'RemoteTree', return_value=tree), \
                 patch.object(cli.completion, 'host_verify', return_value=(host, unit)):
                self.assertEqual(cli.complete_main(['record', '--repo', REPO, '--pr', '2',
                                                    '--registration', str(crafted)]), 1)
                api.assert_not_called()
            self.assertEqual(completion.records(state, 'audits'), [])
            self.assertEqual(completion.records(state, 'outbox'), [])

    def test_trusted_registration_requires_registry_identity_phase_and_actual_lease(self):
        with tempfile.TemporaryDirectory() as temp:
            state = Path(temp)
            lease = acquire(state, 'sample', 'nonce', {'issues': [REPO + '#1'], 'inputs': [], 'scopes': ['ALL_INPUTS']}, project='game', repo=REPO)
            record = {'version': 2, 'project': 'game', 'task': 'sample', 'unitId': 'sample',
                      'branch': 'work/game/sample', 'repo': REPO, 'phase': 'active', 'nonce': 'nonce', 'lease': lease}
            key = hashlib.sha256(b'game\0sample').hexdigest()
            path = state / 'tasks' / (key + '.json')
            durable_write(path, record)
            self.assertEqual(schema.trusted_registration(state, 'game/sample', repo=REPO), record)
            for field, value in [('repo', 'wrong/repo'), ('branch', 'feature'), ('phase', 'pending'),
                                 ('project', 'other'), ('nonce', 'forged'), ('version', 1)]:
                with self.subTest(field=field):
                    durable_write(path, dict(record, **{field: value}))
                    with self.assertRaises(ValueError):
                        schema.trusted_registration(state, 'game/sample', repo=REPO)
            durable_write(path, record)
            target = state / 'outside.json'
            path.replace(target)
            path.symlink_to(target)
            with self.assertRaises((ValueError, OSError)):
                schema.trusted_registration(state, 'game/sample', repo=REPO)

    def test_missing_ac_is_not_eligible(self):
        self.assertEqual(eligibility({"state": "open", "body": ""}, [], {})["status"],
                         "AC_MIGRATION_PENDING")

    def test_ac_normalization_and_partial_criteria(self):
        body = "<!-- work-unit-ac v1 -->\r\npriority: P1\r\n- AC-1: ship\r\n<!-- /work-unit-ac -->"
        parsed = parse_ac(body)
        self.assertEqual(parsed["criteria"], ["AC-1"])
        self.assertEqual(parsed["fingerprint"], parse_ac(body.replace("\r\n", "\n"))["fingerprint"])

    def test_manifest_and_tests_are_classified(self):
        tree = MemoryTree.default()
        impact = derive_impact(tree, tree, [
            {"status": "A", "path": "work-units/units/task.json"},
            {"status": "A", "path": "tools/pr-loop/tests/work_units_test.py"},
        ])
        self.assertNotEqual(impact["result"], "UNRESOLVED")

    def test_deleted_literal_free_hook_is_broad(self):
        tree = MemoryTree.default()
        impact = derive_impact(tree, tree, [
            {"status": "D", "path": "web/game/lib/use-shared-command.ts"},
        ])
        self.assertEqual(impact["result"], "BROAD")

    def test_unmapped_path_does_not_become_na(self):
        tree = MemoryTree.default()
        impact = derive_impact(tree, tree, [{"status": "A", "path": "mystery/file.xyz"}])
        self.assertEqual(impact["result"], "UNRESOLVED")


class ImpactAndQaTest(unittest.TestCase):
    def setUp(self):
        self.tree = MemoryTree.default()

    def impact(self, path, **kwargs):
        return derive_impact(self.tree, self.tree, [dict(status="M", path=path, **kwargs)])

    def test_rename_checks_old_and_new_surface(self):
        impact = self.impact("docs/moved.md", oldPath="web/game/lib/use-dynamic.ts")
        self.assertEqual(impact["result"], "BROAD")
        self.assertIn("ALL_INPUTS", impact["scopes"])

    def test_central_dispatch_and_generated_catalog_are_broad(self):
        for path in ["app/game-api/src/main/kotlin/opensamguk/gameapi/reserve/Dispatcher.kt",
                     "data/commands/input-catalog.json", "web/game/lib/api/client.ts"]:
            with self.subTest(path=path):
                self.assertEqual(self.impact(path)["result"], "BROAD")

    def test_reward_direct_namespace_and_sammo_separate(self):
        impact = self.impact("app/game-api/src/main/kotlin/opensamguk/gameapi/court/reward/Reward.kt")
        self.assertEqual(impact["inputIds"], ["court.reward"])
        sammo = self.impact("logic/src/main/kotlin/opensamguk/logic/actions/nation/Command.kt")
        self.assertTrue(sammo["sammo"])
        self.assertEqual(sammo["inputIds"], [])

    def test_literals_only_add_and_gate_requires_review(self):
        path = "web/game/lib/use-dynamic.ts"
        self.tree.files[path] = 'const unrelated = "court.reward";'
        impact = self.impact(path)
        self.assertEqual(impact["result"], "BROAD")
        self.assertIn("court.reward", impact["inputIds"])
        self.assertTrue(self.impact("work-units/binding.json")["gateChanged"])

    def test_none_na_is_derived_and_wrong_na_rejected(self):
        changes = [{"status": "M", "path": "docs/guide.md"}]
        impact = derive_impact(self.tree, self.tree, changes)
        unit = unit_for(impact)
        unit["commandImpact"]["na"] = "shared infrastructure"
        self.tree.files["work-units/units/sample.json"] = json.dumps(unit)
        self.assertIn("IMPACT_NA_MISMATCH", structural_check(self.tree, self.tree, changes, branch="feature")["reasons"])

    def test_missing_manifest_docs_and_web_are_reported(self):
        for path in ["docs/guide.md", "web/game/app/page.tsx"]:
            receipt = structural_check(self.tree, self.tree, [{"status": "A", "path": path}], branch="feature")
            self.assertIn("ONE_WORK_UNIT_REQUIRED", receipt["reasons"])
            self.assertEqual(receipt["status"], "REPORT_ONLY")

    def test_comment_only_kotlin_id_is_rejected(self):
        path = "logic/src/test/kotlin/ExampleTest.kt"
        self.tree.files[path] = 'package example\nclass ExampleTest { @Test fun real() { /* "court.reward" */ assert(true) } }'
        with self.assertRaisesRegex(ValueError, "QA_ID_NOT_IN_TEST"):
            static_reference(self.tree, "court.reward", {"role": "handler-test", "path": path, "class": "example.ExampleTest"})

    def test_missing_annotation_and_wrong_class_are_rejected(self):
        path = "logic/src/test/kotlin/ExampleTest.kt"
        self.tree.files[path] = 'package example\nclass ExampleTest { fun fake() { call("court.reward") } }'
        ref = {"role": "handler-test", "path": path, "class": "example.ExampleTest"}
        with self.assertRaisesRegex(ValueError, "QA_ID_NOT_IN_TEST"):
            static_reference(self.tree, "court.reward", ref)
        with self.assertRaisesRegex(ValueError, "QA_CLASS_MISSING"):
            static_reference(self.tree, "court.reward", dict(ref, **{"class": "invented.Class"}))

    def test_actual_annotation_and_id_inside_test_are_accepted(self):
        path = "logic/src/test/kotlin/ExampleTest.kt"
        self.tree.files[path] = 'package example\nclass ExampleTest { @Test fun real() { call("court.reward") } }'
        static_reference(self.tree, "court.reward", {"role": "handler-test", "path": path, "class": "example.ExampleTest"})

    def test_e2e_comment_and_skipped_test_not_static_proof(self):
        path = "web/game/e2e/smoke/example.spec.ts"
        for payload in ['// test("fake", () => { call("court.reward") })',
                        'test.skip("fake", () => { call("court.reward") })',
                        'test("real", () => { /* "court.reward" */ click() })']:
            self.tree.files[path] = payload
            with self.assertRaisesRegex(ValueError, "QA_ID_NOT_IN_TEST"):
                static_reference(self.tree, "court.reward", {"role": "ui-e2e", "path": path})

    def test_actual_e2e_block_is_accepted(self):
        path = "web/game/e2e/smoke/example.spec.ts"
        self.tree.files[path] = 'test("real", async ({page}) => { call("court.reward") })'
        static_reference(self.tree, "court.reward", {"role": "ui-e2e", "path": path})

    def test_quoted_fake_test_is_not_actual_test_code(self):
        path = "web/game/e2e/smoke/example.spec.ts"
        self.tree.files[path] = 'const payload = \x60test("fake", () => { call("court.reward") })\x60;'
        with self.assertRaisesRegex(ValueError, "QA_ID_NOT_IN_TEST"):
            static_reference(self.tree, "court.reward", {"role": "ui-e2e", "path": path})

    def test_planned_exception_rejects_present_handler(self):
        identity = next(r["inputId"] for r in self.tree.json("data/commands/input-catalog.json")["inputs"]
                        if r["deliveryState"] == "PLANNED")
        self.tree.files["app/game-engine/src/main/Registry.kt"] = f'val handlers = mapOf("{identity}" to handler)'
        impact = {"result": "DIRECT", "inputIds": [identity], "scopes": [], "sammo": False,
                  "na": None, "paths": [{"path": "logic/src/main/Input.kt"}]}
        unit = unit_for(impact)
        unit["qaNa"][identity] = "PLANNED_NOT_DELIVERED"
        self.assertIn("PLANNED_HANDLER_PRESENT:" + identity, validate_qa(self.tree, unit, impact)[1])

    def test_planned_exception_rejects_symbol_registered_handler(self):
        identity = next(r["inputId"] for r in self.tree.json("data/commands/input-catalog.json")["inputs"]
                        if r["deliveryState"] == "PLANNED")
        self.tree.files["logic/src/main/PlannedInput.kt"] = f'object PlannedInput {{ const val INPUT_ID = "{identity}" }}'
        self.tree.files["app/game-engine/src/main/Registry.kt"] = 'val handlers = mapOf(PlannedInput.INPUT_ID to handler)'
        impact = {"result": "DIRECT", "inputIds": [identity], "scopes": [], "sammo": False,
                  "na": None, "paths": [{"path": "logic/src/main/Input.kt"}]}
        unit = unit_for(impact)
        unit["qaNa"][identity] = "PLANNED_NOT_DELIVERED"
        self.assertIn("PLANNED_HANDLER_PRESENT:" + identity, validate_qa(self.tree, unit, impact)[1])

    def test_direct_and_legacy_impact_omissions_are_reported(self):
        changes = [{"status": "M", "path": "app/game-api/src/main/kotlin/opensamguk/gameapi/court/reward/Reward.kt"}]
        impact = derive_impact(self.tree, self.tree, changes)
        unit = unit_for(impact)
        unit["commandImpact"]["inputIds"] = []
        self.tree.files["work-units/units/sample.json"] = json.dumps(unit)
        self.assertIn("IMPACT_OMITTED:inputIds", structural_check(self.tree, self.tree, changes)["reasons"])

    def test_gap_metadata_deletion_and_tracking_loss_fail(self):
        head = MemoryTree(copy.deepcopy(self.tree.files))
        doc = head.json("data/commands/command-work-gaps-v1.json")
        doc["entries"].pop()
        head.files["data/commands/command-work-gaps-v1.json"] = json.dumps(doc)
        self.assertTrue(any(r.startswith("GAP_VANISHED") for r in compare(self.tree, head)[1]))
        old = self.tree.json("data/commands/command-work-gaps-v1.json")
        old["entries"][0]["trackedBy"] = [REPO + "#1"]
        self.tree.files["data/commands/command-work-gaps-v1.json"] = json.dumps(old)
        self.assertTrue(any(r.startswith("GAP_UNTRACKED") for r in compare(self.tree, MemoryTree.default())[1]))

    def test_catalog_deletion_and_demotion_are_reported(self):
        head = MemoryTree(copy.deepcopy(self.tree.files))
        catalog = head.json("data/commands/input-catalog.json")
        row = next(r for r in catalog["inputs"] if r["deliveryState"] == "UI_READY")
        identity = row["inputId"]
        row["deliveryState"] = "PLANNED"
        head.files["data/commands/input-catalog.json"] = json.dumps(catalog)
        self.assertIn("REGRESSION:" + identity, compare(self.tree, head)[1])
        catalog["inputs"] = [r for r in catalog["inputs"] if r["inputId"] != identity]
        head.files["data/commands/input-catalog.json"] = json.dumps(catalog)
        self.assertIn("CATALOG_INPUT_DELETED:" + identity, compare(self.tree, head)[1])

    def test_cross_repository_and_unbound_jira_are_not_invented(self):
        impact = self.impact("docs/guide.md")
        unit = unit_for(impact)
        unit["issues"][0]["repo"] = "peppone-choi/opensamguk-images"
        with self.assertRaisesRegex(ValueError, "ISSUE_REPOSITORY"):
            manifest(unit, REPO)
        unit = unit_for(impact)
        unit["issues"].append({"system": "jira", "key": "OPENSAM-1"})
        self.tree.files["work-units/units/sample.json"] = json.dumps(unit)
        receipt = structural_check(self.tree, self.tree, [{"status": "M", "path": "docs/guide.md"}], branch="feature")
        self.assertIn("JIRA_PENDING_BINDING", receipt["reasons"])


class ExecutionTest(unittest.TestCase):
    def setUp(self):
        self.receipt = {"status": "ENFORCED", "headSha": HEAD,
                        "requiredLanes": {"jvm": True}, "qaPlan": [{"role": "handler-test", "class": "example.Real"}]}
        self.execution = {"schema": "wu-executed/1", "headSha": HEAD, "lane": "jvm",
                          "producer": "jvm-core", "runId": "1", "attempt": 1, "tests": 1,
                          "cases": [{"class": "example.Real", "name": "real", "status": "passed"}]}

    def verify(self, execution):
        engine = dict(self.execution, producer="game-engine")
        return verify_executed(self.receipt, [execution, engine], lane="jvm", run_id="1")

    def test_missing_and_zero_tests_and_skip_are_rejected(self):
        self.assertEqual(verify_executed(self.receipt, [], lane="jvm")["status"], "FAIL")
        self.assertEqual(self.verify(dict(self.execution, tests=0))["status"], "FAIL")
        self.assertEqual(self.verify(dict(self.execution, cases=[{"status": "skipped"}]))["status"], "FAIL")

    def test_missing_class_and_stale_head_are_rejected(self):
        engine = dict(self.execution, producer="game-engine", cases=[{"class": "other.Class", "status": "passed"}])
        missing = dict(self.execution, cases=[])
        self.assertEqual(verify_executed(self.receipt, [missing, engine], lane="jvm")["status"], "FAIL")
        self.assertEqual(self.verify(dict(self.execution, headSha=MERGE))["status"], "FAIL")

    def test_valid_execution_and_junit_reader(self):
        self.assertEqual(self.verify(self.execution)["status"], "PASS")
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "TEST-example.xml"
            path.write_text('<testsuite><testcase classname="example.Real" name="real"/></testsuite>')
            parsed = executed_tests([path], head=HEAD, lane="jvm", run_id=1, attempt=1)
            self.assertEqual(parsed["tests"], 1)
            self.assertEqual(parsed["source"], "JUNIT_XML")

    def test_xml_entities_are_not_execution_proof(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "TEST-example.xml"
            path.write_text('<!DOCTYPE test [<!ENTITY x SYSTEM "file:///etc/passwd">]><testsuite/>')
            with self.assertRaisesRegex(ValueError, "UNSAFE_EXECUTION_XML"):
                executed_tests([path], head=HEAD, lane="jvm", run_id=1, attempt=1)

    def test_needed_check_skip_pending_and_red_distinct(self):
        for conclusion, expected in [("skipped", "RED"), ("neutral", "RED"), ("cancelled", "PENDING_CI"),
                                     ("failure", "RED"), ("success", "PASS")]:
            result = check_conclusions([{"id": 1, "name": "jvm", "status": "completed", "conclusion": conclusion}], ["jvm"])
            self.assertEqual(result["result"], expected)

    def test_browser_union_binds_head_and_skips_never_pass(self):
        with tempfile.TemporaryDirectory() as temp:
            for phase in ("smoke", "topdown-screens"):
                folder = Path(temp) / phase
                folder.mkdir()
                (folder / "phase.json").write_text(json.dumps({
                    "schema": "web-e2e-shard-aggregate-v1", "app": "game", "headSha": HEAD,
                    "runId": "1", "runAttempt": "1", "phase": phase}))
                (folder / "results.json").write_text(json.dumps({"suites": [{"file": "smoke/example.spec.ts", "specs": [
                    {"title": "real", "tests": [{"status": "skipped", "results": []}]}]}], "errors": []}))
            execution = browser_execution(temp, head=HEAD, run_id=1, attempt=1)
            receipt = {"status": "ENFORCED", "headSha": HEAD, "requiredLanes": {"web": True}, "qaPlan": []}
            self.assertEqual(verify_executed(receipt, [execution], lane="web")["status"], "FAIL")
            with self.assertRaisesRegex(ValueError, "IDENTITY"):
                browser_execution(temp, head=MERGE, run_id=1, attempt=1)


class ClaimAndQueueTest(unittest.TestCase):
    def test_dry_run_claim_does_not_create_state(self):
        with tempfile.TemporaryDirectory() as temp:
            state = Path(temp) / "not-created"
            acquire(state, "one", "nonce", {"issues": [], "inputs": [], "scopes": []}, project="game", repo=REPO, dry_run=True)
            self.assertFalse(state.exists())
    def test_priority_aging_regression_and_tie_break_are_deterministic(self):
        issue = {"number": 1, "created_at": "2026-09-01T00:00:00Z"}
        ac = parse_ac(AC)
        self.assertEqual(rank(issue, ac, now=datetime(2026, 10, 9, tzinfo=timezone.utc))[1], 0)
        self.assertLess(rank(issue, ac, regression=True), rank(issue, ac))
        self.assertLess(rank(issue, ac), rank(dict(issue, number=2), ac))

    def test_dependency_and_explicit_legacy_policy(self):
        issue = {"number": 1, "state": "open", "body": AC.replace("priority: P2", "depends: #2")}
        self.assertEqual(eligibility(issue, [], {2: {"state": "open"}})["status"], "DEPENDENCY_PENDING")
        self.assertEqual(eligibility(issue, [], {2: {"state": "closed"}})["status"], "ELIGIBLE")
        self.assertEqual(parse_ac("old prose", legacy=True)["criteria"], ["LEGACY-WHOLE"])
        self.assertEqual(eligibility({"number": 1, "state": "open", "body": "old prose"}, [], {})["status"], "AC_MIGRATION_PENDING")

    def test_conflicts_and_exceeded_lease(self):
        self.assertTrue(conflicts({"inputs": ["court.reward"]}, {"inputs": ["court.reward"]}))
        self.assertTrue(conflicts({"scopes": ["ALL_INPUTS"]}, {"inputs": ["court.reward"]}))
        self.assertFalse(conflicts({"unknownScope": True}, {"inputs": [], "scopes": []}))
        self.assertFalse(lease_covers({"inputs": []}, {"result": "DIRECT", "inputIds": ["court.reward"], "scopes": [], "sammo": False}))

    def test_atomic_claim_and_restart_preserve_identity(self):
        with tempfile.TemporaryDirectory() as temp:
            state = Path(temp)
            lease = {"issues": [REPO + "#1"], "inputs": ["court.reward"], "scopes": []}
            first = acquire(state, "one", "nonce", lease, project="game", repo=REPO)
            self.assertEqual(acquire(state, "one", "nonce", lease, project="game", repo=REPO), first)
            with self.assertRaisesRegex(ValueError, "LEASE_CONFLICT"):
                acquire(state, "two", "other", lease, project="game", repo=REPO)
            self.assertEqual(len(active_leases(state)), 1)

    def test_two_processes_only_one_claim_wins(self):
        with tempfile.TemporaryDirectory() as temp:
            ctx = multiprocessing.get_context("fork")
            output = ctx.Queue()
            processes = [ctx.Process(target=concurrent_claim, args=(temp, str(i), output)) for i in ("one", "two")]
            for p in processes: p.start()
            for p in processes: p.join(10); self.assertEqual(p.exitcode, 0)
            self.assertEqual(sorted([output.get(timeout=1), output.get(timeout=1)]), ["conflict", "won"])

    def test_unknown_auth_does_not_produce_eligible_work(self):
        reader = GitHubReader(lambda path: (_ for _ in ()).throw(ApiError(403)))
        self.assertEqual(next_units(reader, REPO, [], {"entries": []})[0]["status"], "UNKNOWN_AUTH")

    def test_unlinked_gap_is_kept_pending(self):
        class Reader:
            def pages(self, path): return []
        result = next_units(Reader(), REPO, [], {"entries": [{"gapId": "court.reward:UI", "trackedBy": []}]})
        self.assertEqual(result[0]["status"], "GAP_PENDING_ISSUE")


class CompletionTest(unittest.TestCase):
    def test_torn_audit_is_quarantined_without_stopping_healthy_recovery(self):
        audit = self.record()
        torn = self.state / 'work-units/audits' / ('0' * 64 + '.json')
        torn.write_bytes(b'')
        for path in (self.state / 'work-units/outbox').glob('*.json'):
            path.unlink()
        result = completion.scan(self.state)
        self.assertEqual(result['audits'], 1)
        self.assertEqual(len(result['quarantined']), 1)
        self.assertFalse(torn.exists())
        self.assertTrue(completion.durable_check(self.state, audit['auditId']))

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.state = Path(self.temp.name)
        self.reader = FakeReader()
        self.tree = MemoryTree.default()
        impact = derive_impact(self.tree, self.tree, [{"status": "M", "path": "docs/guide.md"}])
        self.unit = unit_for(impact)
        self.raw = {"number": 2, "merged": True, "merged_at": "2026-10-09T00:00:00Z", "merge_commit_sha": MERGE,
                    "head": {"sha": HEAD}, "base": {"ref": "main", "repo": {"full_name": REPO}}}
        self.receipt = {"headSha": HEAD, "manifestPath": "work-units/units/sample.json",
                        "manifestBlob": self.reader.blob_sha, "reservation": "VERIFIED"}
        self.host = {"result": "PASS", "libHash": bundle_hash(), "receipt": self.receipt,
                     "review": {"head": HEAD, "independent": True, "verdict": "MERGEABLE"},
                     "checks": [], "binding": {"jira": {"status": "UNBOUND"}}}

    def record(self):
        return completion.record(self.state, self.raw, self.reader, self.host,
                                 unit=self.unit, registration={"version": 2, "legacyAc": False,
                                     "acFingerprint": self.unit["acceptance"]["fingerprint"]})

    def test_open_auto_merge_or_unmerged_has_no_audit(self):
        for raw in [dict(self.raw, merged=False), dict(self.raw, merged_at=None), dict(self.raw, merge_commit_sha=None)]:
            with self.assertRaises(ValueError):
                completion.record(self.state, raw, self.reader, self.host, unit=self.unit)
        self.assertEqual(completion.records(self.state, "audits"), [])

    def test_wrong_branch_nonancestor_and_two_parent_are_manual(self):
        for kind in ("branch", "ancestor", "parents"):
            raw = copy.deepcopy(self.raw)
            self.reader.compare_status, self.reader.parent_count = "ahead", 1
            if kind == "branch": raw["base"]["ref"] = "release"
            if kind == "ancestor": self.reader.compare_status = "diverged"
            if kind == "parents": self.reader.parent_count = 2
            proof = merge_proof(raw, self.reader, final_head=HEAD, manifest_path=self.receipt["manifestPath"],
                                host_verify=self.host, receipt=self.receipt)
            self.assertNotEqual(proof["result"], "MERGE_PROVEN")

    def test_stale_head_and_blob_mismatch_have_no_completion(self):
        for change in ({"headSha": MERGE}, {"manifestBlob": "d" * 40}):
            receipt = dict(self.receipt, **change)
            proof = merge_proof(self.raw, self.reader, final_head=HEAD, manifest_path=receipt["manifestPath"],
                                host_verify=self.host, receipt=receipt)
            self.assertEqual(proof["result"], "MANUAL")

    def test_partial_ac_only_comments_and_idempotent_record(self):
        first = self.record()
        self.assertEqual(first["remainingAtRecord"], ["AC-2"])
        self.assertEqual(self.record()["auditId"], first["auditId"])
        self.assertEqual(len(completion.records(self.state, "audits")), 1)
        self.assertEqual([i["action"] for i in completion.records(self.state, "outbox")], ["comment"])

    def test_two_units_accumulate_but_closure_stays_manual(self):
        first = self.record()
        self.unit["unitId"] = "second"
        self.unit["acceptance"]["criteria"] = ["AC-2"]
        self.raw["number"] = 3
        second = self.record()
        self.assertEqual(second["remainingAtRecord"], [])
        close = [i for i in completion.records(self.state, "outbox") if i["action"] == "close"]
        self.assertEqual(close[0]["state"], "MANUAL")

    def test_ac_drift_prevents_record(self):
        self.reader.issue["body"] = AC.replace("ship", "changed")
        with self.assertRaisesRegex(ValueError, "AC_DRIFT"): self.record()

    def test_audit_only_crash_is_recovered_by_scan(self):
        audit = self.record()
        for path in (self.state / "work-units/outbox").glob("*.json"): path.unlink()
        completion.scan(self.state)
        self.assertTrue(completion.durable_check(self.state, audit["auditId"]))

    def test_intent_creation_failure_does_not_claim_durable_success(self):
        original = completion.durable_write
        def fail_intent(path, data, **kwargs):
            if "outbox" in str(path): raise OSError("disk failed")
            return original(path, data, **kwargs)
        with patch.object(completion, "durable_write", side_effect=fail_intent):
            with self.assertRaises(OSError): self.record()
        self.assertEqual(len(completion.records(self.state, "audits")), 1)
        completion.scan(self.state)
        self.assertEqual(len(completion.records(self.state, "outbox")), 1)

    def test_default_dry_run_and_missing_writer_host_do_zero_writes(self):
        self.record()
        writer = FakeWriter(self.reader)
        completion.drain(self.state, self.reader, writer=writer)
        self.assertEqual(writer.writes, 0)
        self.assertEqual(completion.records(self.state, "outbox")[0]["state"], "DRY_RUN")
        completion.drain(self.state, self.reader, writer=writer, enabled=True)
        self.assertEqual(writer.writes, 0)
        self.assertEqual(completion.records(self.state, "outbox")[0]["state"], "PENDING_AUTH")

    def test_writer_host_mismatch_does_zero_writes(self):
        self.record()
        path = self.state / "work-units/writer-host.json"
        durable_write(path, {"enabled": True, "host": "other-host", "bundleHash": bundle_hash(), "approvedBy": "owner"})
        writer = FakeWriter(self.reader)
        completion.drain(self.state, self.reader, writer=writer, enabled=True)
        self.assertEqual(writer.writes, 0)

    def test_fake_done_without_readback_is_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "intent.json"
            durable_write(path, {"intentId": "a", "action": "comment", "state": "DONE"})
            with self.assertRaisesRegex(ValueError, "READBACK"):
                read_record(path)

    def test_drift_during_remote_write_prevents_done(self):
        self.record()
        reader = self.reader
        class DriftWriter(FakeWriter):
            def write(self, path, payload):
                result = super().write(path, payload)
                reader.issue["body"] = AC.replace("ship", "changed")
                return result
        writer = DriftWriter(reader)
        with patch.object(completion, "writer_authorized", return_value=True):
            completion.drain(self.state, reader, writer=writer, enabled=True, now=1000)
        self.assertEqual(completion.records(self.state, "outbox")[0]["state"], "MANUAL")

    def test_remote_write_crash_then_readback_prevents_duplicate(self):
        self.record()
        writer = FakeWriter(self.reader, crash=True)
        with patch.object(completion, "writer_authorized", return_value=True):
            completion.drain(self.state, self.reader, writer=writer, enabled=True, now=1000)
            completion.drain(self.state, self.reader, writer=writer, enabled=True, now=2000)
        self.assertEqual(writer.writes, 1)
        self.assertEqual(completion.records(self.state, "outbox")[0]["state"], "DONE")

    def test_http_failures_remain_pending_never_done(self):
        for code in (401, 403, 429, 500):
            with self.subTest(code=code):
                self.record()
                writer = FakeWriter(self.reader, ApiError(code, retry_after=90))
                with patch.object(completion, "writer_authorized", return_value=True):
                    completion.drain(self.state, self.reader, writer=writer, enabled=True, now=1000 + code * 10000)
                row = completion.records(self.state, "outbox")[0]
                self.assertNotEqual(row["state"], "DONE")
                if code == 429: self.assertEqual(row["nextAttemptAt"], 1000 + code * 10000 + 90)

    def test_jira_unbound_and_mismatched_receipt_reject_completion(self):
        adapter = JiraAdapter({"jira": {"status": "UNBOUND"}})
        self.assertEqual(adapter.intent({"auditId": "a"}, {"key": "OPENSAM-1"}, "body")["state"], "PENDING_AUTH")
        intent = {"intentId": "a", "issueKey": "OPENSAM-1", "expectedIssueId": "123",
                  "binding": {"instanceHost": "example.atlassian.net"}}
        receipt = {"intentId": "a", "issueKey": "OPENSAM-1", "issueId": "123",
                   "instanceHost": "other.atlassian.net", "performedAt": "now", "remoteCommentId": "1",
                   "readBack": {"markerFound": True, "issueKeyEcho": "OPENSAM-1", "statusName": "Open"}}
        with self.assertRaisesRegex(ValueError, "MISMATCH"): JiraAdapter.validate_receipt(intent, receipt)
        receipt["instanceHost"] = intent["binding"]["instanceHost"]
        receipt["readBack"]["markerFound"] = False
        with self.assertRaisesRegex(ValueError, "READBACK"): JiraAdapter.validate_receipt(intent, receipt)

    def test_pages_include_third_page_marker(self):
        def get(path):
            page = int(path.rsplit("page=", 1)[1])
            return [{"id": page}] * 100 if page < 3 else [{"id": 3}]
        self.assertEqual(GitHubReader(get).pages("repos/example/repo/issues/1/comments?per_page=100")[-1]["id"], 3)


if __name__ == "__main__":
    unittest.main()
