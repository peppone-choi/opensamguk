"""Synthetic observation contracts; no dispatcher, credentials or live registry."""
import copy
import hashlib
import io
import json
import subprocess
import sys
import tempfile
import unittest
from datetime import datetime, timezone, timedelta
from pathlib import Path
from unittest.mock import patch
from contextlib import redirect_stdout

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "lib"))
from automation_lane.contracts import prepare
from automation_lane.evidence import evaluate
from automation_lane.resources import admission, observe
from automation_lane.planner import report, batches_report
from automation_lane.artifacts import inventory
from automation_lane.preflight import preflight, version_probe, repository_name
from automation_lane.cli import main as observe_main
from work_units.acceptance import parse_ac

NOW = datetime(2026, 10, 10, 3, tzinfo=timezone.utc)
HEAD, BASE = "a" * 40, "b" * 40
FP = "sha256:" + "c" * 64
BODY = "<!-- work-unit-ac v1 -->\npriority: P1\n- AC-1: build foundation\n- AC-2: user journey\n<!-- /work-unit-ac -->"


def unit(task="one", project="opensamguk", kind="product", **kwargs):
    result = {"project": project, "task": task, "repo": "example/" + project, "head": HEAD,
              "kind": kind, "delivery": "FOUNDATION", "createdAt": (NOW - timedelta(hours=1)).isoformat(),
              "issues": [f"example/{project}#{sum(map(ord, task))}"], "body": BODY, "criteria": ["AC-1"],
              "profile": "python", "writePaths": [f"tools/{task}.py"], "inputs": [], "scopes": [],
              "plan": {"status": "READY", "head": HEAD, "criteria": ["AC-1"]},
              "demand": {"cpuPercent": 5, "memoryBytes": 100, "diskBytes": 10, "heavy": False}}
    result.update(kwargs)
    return result


def resources(**kwargs):
    result = {"observedAt": NOW.isoformat(), "cpuPercent": 20,
              "memoryUsed": 200, "memoryTotal": 1000, "diskUsed": 100, "diskTotal": 1000,
              "heavyTokens": {"capacity": 1, "inUse": 0, "observedAt": NOW.isoformat(),
                              "source": {"kind": "external-token-manager", "id": "synthetic-host"}}}
    result.update(kwargs)
    return result


def snapshot(*items, **kwargs):
    result = {"schema": "automation-snapshot/1", "units": list(items), "dependencies": {},
              "lanes": [{"id": f"{kind}-{i}", "kind": kind, "profiles": ["python", "unity"],
                         "isolatedSnapshot": True} for kind in ("product", "planning", "review", "docs") for i in range(2)],
              "activeLeases": [], "resources": resources()}
    result.update(kwargs)
    return result


def proof():
    common = {"head": HEAD, "tool": {"name": "synthetic-validator", "version": "1"},
              "contractFingerprint": FP, "acceptanceFingerprint": FP,
              "scope": {"paths": ["tools/one.py"], "checks": ["unit-contract"], "criteria": ["AC-1"]}}
    record = dict(copy.deepcopy(common), result="PASS", skipped=0, failures=0, recordedAt=NOW.isoformat())
    return record, copy.deepcopy(common)


class QueueContractsTest(unittest.TestCase):
    def test_review_m1_malformed_lease_scope_cannot_admit_a_writer(self):
        for lease in [{"repo": "example/opensamguk", "writePaths": "tools/one.py"},
                      {"repo": "example/opensamguk", "writePaths": [1]},
                      {"repo": "example/opensamguk", "writePaths": ["../outside"]},
                      {"repo": "example/opensamguk", "writePaths": [], "inputs": None},
                      {"repo": "example/opensamguk", "writePaths": [], "scopes": "ALL_INPUTS"},
                      {"writePaths": ["unrelated.py"]}]:
            with self.subTest(lease=lease):
                result = report(snapshot(unit(), unit("review", kind="review"), activeLeases=[lease]), NOW)
                self.assertEqual([a["kind"] for a in result["assignments"]], ["review"])

    def test_review_m1_unknown_batch_scope_is_not_dropped_from_writer_conflicts(self):
        for batch in [{"repo": "example/opensamguk", "stage": "MERGING", "findingsCount": 5, "prs": [1]},
                      {"repo": "example/opensamguk", "stage": "SCANNING", "prs": None},
                      {"repo": "example/opensamguk", "stage": "SCANNING", "prs": [None]},
                      {"repo": "example/opensamguk", "stage": []},
                      {"repo": "example/opensamguk"}, None]:
            with self.subTest(batch=batch):
                result = report(snapshot(unit(), unit("review", kind="review"), batches=[batch]), NOW)
                self.assertEqual([a["kind"] for a in result["assignments"]], ["review"])
                self.assertEqual(result["batches"][0]["observerStatus"], "CONTRACT_HELD")

    def test_review_m1_known_other_repo_uncertainty_does_not_hold_unrelated_writer(self):
        lease = {"repo": "example/bp", "issues": [], "inputs": [], "scopes": [], "writePaths": "unknown"}
        result = report(snapshot(unit(), activeLeases=[lease],
                                 batches=[{"repo": "example/bp", "stage": "MERGING"}]), NOW)
        self.assertEqual([a["unit"] for a in result["assignments"]], ["opensamguk/one"])

    def test_review_m1_unknown_global_issue_scope_holds_writers_across_repos(self):
        for lease in [{"repo": "example/bp", "writePaths": [], "issues": "unknown"},
                      {"repo": "example/bp", "writePaths": []}]:
            with self.subTest(lease=lease):
                self.assertEqual(report(snapshot(unit(), activeLeases=[lease]), NOW)["assignments"], [])

    def test_review_m3_null_lease_retains_valid_read_only_queue_and_unknown_owners(self):
        result = report(snapshot(unit(), unit("review", kind="review"), activeLeases=[None], activeOwnersComplete=True), NOW)
        self.assertEqual([a["kind"] for a in result["assignments"]], ["review"])
        self.assertFalse(result["executionAllowed"])

    def test_malformed_auxiliary_records_hold_only_their_evidence_or_batch(self):
        malformed = [None, {"repo": None}, {"repo": "example/opensamguk", "prs": None}]
        # Unknown batch ownership holds writers; isolated read-only reporting remains available.
        result = report(snapshot(unit(kind="review"), batches=malformed), NOW)
        self.assertEqual(len(result["assignments"]), 1)
        self.assertTrue(all(row["observerStatus"] == "CONTRACT_HELD" for row in result["batches"]))
        record, request = proof()
        record["tool"] = request["tool"] = "invalid"
        self.assertEqual(evaluate(record, request, {}, NOW)["status"], "REVALIDATE")
        self.assertEqual(admission(resources(), None, NOW), "RESOURCE_OBSERVATION_UNKNOWN")

    def test_foundation_unit_completion_does_not_close_an_issue_dependency(self):
        item = unit(body=BODY.replace("priority: P1", "priority: P1\ndepends: #9"))
        data = snapshot(item, dependencies={"example/opensamguk#9": "UNIT_DONE"})
        self.assertEqual(report(data, NOW)["assignments"], [])
        item["body"] = BODY
        item["depends"] = ["unit:opensamguk/foundation"]
        data["dependencies"] = {"unit:opensamguk/foundation": "UNIT_DONE"}
        self.assertEqual(len(report(data, NOW)["assignments"]), 1)
    def test_malformed_item_priority_or_clock_keeps_valid_items_ready(self):
        data = snapshot("malformed", unit("priority", priority="high"), unit("clock", createdAt=123), unit("good"))
        result = report(data, NOW)
        self.assertEqual(len(result["preparation"]), 3)
        self.assertEqual([a["unit"] for a in result["assignments"]], ["opensamguk/good"])

    def test_unknown_existing_file_scope_holds_writer_but_not_isolated_review(self):
        data = snapshot(unit(), unit("review", kind="review"), activeLeases=[{"repo": "example/opensamguk"}])
        self.assertEqual([a["kind"] for a in report(data, NOW)["assignments"]], ["review"])
    def test_ready_partial_unit_is_proposed_without_declaring_feature_completion(self):
        result = report(snapshot(unit()), NOW)
        self.assertEqual(result["assignments"][0]["deliveryLevel"], "INTERNAL_FOUNDATION")
        self.assertFalse(result["assignments"][0]["issueComplete"])
        self.assertFalse(result["executionAllowed"])
        self.assertTrue(result["assignments"][0]["requiresExternalAtomicClaim"])

    def test_user_feature_slice_stays_separate_from_internal_foundation(self):
        result = report(snapshot(unit(delivery="USER_FEATURE")), NOW)
        self.assertEqual(result["assignments"][0]["deliveryLevel"], "FEATURE_SLICE")
        self.assertFalse(result["assignments"][0]["issueComplete"])

    def test_missing_ac_plan_decision_dependency_report_next_actions_and_do_not_poison_other_units(self):
        variants = [unit("no-ac", body="no criteria"), unit("no-plan", plan={}),
                    unit("decision", requiredDecisions=["design"]),
                    unit("depends", body=BODY.replace("priority: P1", "priority: P1\ndepends: #9"))]
        result = report(snapshot(*variants, unit("good")), NOW)
        self.assertEqual(len(result["preparation"]), 4)
        self.assertEqual([a["unit"] for a in result["assignments"]], ["opensamguk/good"])
        self.assertTrue(all(r["reasons"][0]["nextAction"] for r in result["preparation"]))

    def test_known_dependency_and_decision_allow_readiness(self):
        item = unit(body=BODY.replace("priority: P1", "priority: P1\ndepends: #9"),
                    requiredDecisions=["design"], decisions={"design": "APPROVED"})
        self.assertEqual(len(report(snapshot(item, dependencies={"example/opensamguk#9": "closed"}), NOW)["assignments"]), 1)

    def test_approval_or_safety_block_cannot_be_overridden_by_ready_plan_or_reusable_proof(self):
        for code in ("APPROVAL_BLOCKED", "SAFETY_BLOCKED"):
            record, request = proof()
            result = report(snapshot(unit(blockers=[{"code": code, "nextAction": "Resolve through original authority"}]),
                                     evidenceRequests=[{"record": record, "request": request}]), NOW)
            self.assertEqual(result["assignments"], [])
            self.assertEqual(result["blocked"][0]["reasons"][0]["code"], code)

    def test_opensamguk_product_priority_and_separate_planning_review_docs_lanes(self):
        result = report(snapshot(unit("bp", "bp"), unit("game"), unit("design", kind="planning"),
                                 unit("review", kind="review"), unit("doc", kind="docs")), NOW)
        self.assertEqual(result["assignments"][0]["unit"], "opensamguk/game")
        self.assertEqual({a["kind"] for a in result["assignments"]}, {"product", "planning", "review", "docs"})

    def test_missing_doc_lane_does_not_block_product_review(self):
        data = snapshot(unit("doc", kind="docs"), unit("review", kind="review"))
        data["lanes"] = [l for l in data["lanes"] if l["kind"] == "review"]
        self.assertEqual([a["kind"] for a in report(data, NOW)["assignments"]], ["review"])

    def test_aging_prevents_old_low_priority_project_starvation(self):
        old = unit("old", "bp", priority=3, createdAt=(NOW - timedelta(days=8)).isoformat())
        data = snapshot(unit("new", priority=0), old)
        data["lanes"] = data["lanes"][:1]
        self.assertEqual(report(data, NOW)["assignments"][0]["unit"], "bp/old")

    def test_global_issue_same_repo_path_and_command_scope_conflicts(self):
        candidate = unit()
        active = [{"repo": "example/bp", "issues": candidate["issues"]},
                  {"repo": candidate["repo"], "writePaths": ["tools/"]},
                  {"repo": candidate["repo"], "scopes": ["ALL_INPUTS"]}]
        for lease in active:
            with self.subTest(lease=lease):
                item = dict(candidate, inputs=["court.reward"])
                result = report(snapshot(item, activeLeases=[lease]), NOW)
                self.assertEqual(result["assignments"], [])
                self.assertEqual(result["queues"]["product"][0]["reasons"][0]["code"], "ACTIVE_SCOPE_CONFLICT")

    def test_same_file_and_task_in_different_repos_are_independent(self):
        result = report(snapshot(unit(), unit(project="bp")), NOW)
        self.assertEqual(len(result["assignments"]), 2)
        self.assertNotEqual(*[a["leaseKey"] for a in result["assignments"]])

    def test_proposed_writer_reserves_scope_for_later_candidates(self):
        result = report(snapshot(unit("a"), unit("b", writePaths=["tools/a.py"])), NOW)
        self.assertEqual(len(result["assignments"]), 1)

    def test_isolated_read_only_review_can_read_while_product_lease_remains(self):
        item = unit("review", kind="review")
        data = snapshot(item, activeLeases=[{"repo": item["repo"], "issues": item["issues"], "scopes": ["ALL_INPUTS"]}])
        result = report(data, NOW)
        self.assertEqual(len(result["assignments"]), 1)
        self.assertFalse(result["assignments"][0]["requiresExternalAtomicClaim"])
        self.assertTrue(result["assignments"][0]["requiresExternalProviderLease"])
        for lane in data["lanes"]:
            lane["isolatedSnapshot"] = False
        self.assertEqual(report(data, NOW)["assignments"], [])

    def test_project_normalized_adapter_keeps_its_own_runtime_profile(self):
        item = unit("unity", "openhoi4", profile="unity")
        item.pop("body")
        item["acceptance"] = {"adapter": "normalized/1", "head": HEAD, "validatorVersion": "unity-adapter/1",
                              "fingerprint": FP, "criteria": ["MOVE-1", "MOVE-2"], "depends": []}
        item["criteria"] = item["plan"]["criteria"] = ["MOVE-1"]
        self.assertEqual(len(report(snapshot(item), NOW)["assignments"]), 1)
        item["acceptance"]["head"] = BASE
        self.assertEqual(report(snapshot(item), NOW)["assignments"], [])

    def test_duplicate_identity_and_unsafe_write_scope_hold(self):
        self.assertEqual(len(report(snapshot(unit(), unit()), NOW)["blocked"]), 2)
        self.assertEqual(report(snapshot(unit(writePaths=["../shared.py"])), NOW)["assignments"], [])

    def test_report_does_not_mutate_input_or_create_files(self):
        data = snapshot(unit())
        before = copy.deepcopy(data)
        with patch("subprocess.Popen", side_effect=AssertionError("no launch")):
            report(data, NOW)
        self.assertEqual(data, before)


class EvidenceContractsTest(unittest.TestCase):
    def test_exact_head_scope_and_tool_version_reuse(self):
        record, request = proof()
        result = evaluate(record, request, {}, NOW)
        self.assertEqual(result["status"], "REUSE_EXACT")
        self.assertFalse(result["gateSatisfied"])

    def test_tool_contract_acceptance_and_scope_change_each_invalidate(self):
        for field, value in [("tool", {"name": "synthetic-validator", "version": "2"}),
                             ("contractFingerprint", "sha256:" + "d" * 64),
                             ("acceptanceFingerprint", "sha256:" + "e" * 64),
                             ("scope", {"paths": ["other.py"], "checks": ["new-check"], "criteria": ["AC-2"]})]:
            record, request = proof()
            request[field] = value
            self.assertEqual(evaluate(record, request, {}, NOW)["status"], "REVALIDATE")

    def test_stale_future_skipped_failed_or_missing_exact_sha_evidence_cannot_reuse(self):
        for changes in [{"recordedAt": (NOW - timedelta(days=2)).isoformat()},
                        {"recordedAt": (NOW + timedelta(seconds=1)).isoformat()},
                        {"skipped": 1}, {"failures": 1}, {"head": "not-a-sha"}]:
            record, request = proof()
            record.update(changes)
            self.assertEqual(evaluate(record, request, {}, NOW)["status"], "REVALIDATE")

    def test_changed_head_only_offers_delta_context_after_complete_diff_and_risk_classification(self):
        record, request = proof()
        request.update(head=BASE, baseHead=HEAD, changedPaths=["docs/readme.md"], diffComplete=True)
        self.assertEqual(evaluate(record, request, {}, NOW)["status"], "REVALIDATE")
        result = evaluate(record, request, {"classificationComplete": True}, NOW)
        self.assertEqual(result["status"], "DELTA_CONTEXT_ONLY")
        self.assertEqual(result["boundHead"], HEAD)
        self.assertFalse(result["gateSatisfied"])

    def test_contract_security_or_checked_path_changes_force_revalidation(self):
        for path in ["tools/pr-loop/lib/work_units/schema.py", ".github/workflows/ci.yml",
                     "app/security/login.py", "tools/one.py"]:
            record, request = proof()
            request.update(head=BASE, baseHead=HEAD, changedPaths=[path], diffComplete=True)
            policy = {"classificationComplete": True, "securityPaths": ["app/security/"]}
            self.assertEqual(evaluate(record, request, policy, NOW)["status"], "REVALIDATE")


class ResourceContractsTest(unittest.TestCase):
    def test_review_m2_heavy_token_needs_its_own_fresh_timestamp_and_source(self):
        demand = dict(unit()["demand"], heavy=True)
        for changes in [{"observedAt": None}, {"observedAt": (NOW - timedelta(seconds=301)).isoformat()},
                        {"observedAt": (NOW + timedelta(seconds=1)).isoformat()},
                        {"source": None}, {"source": {}}, {"source": {"kind": "unknown", "id": "fixture"}}]:
            with self.subTest(changes=changes):
                data = resources()
                data["heavyTokens"].update(changes)
                self.assertIsNotNone(admission(data, demand, NOW))
        self.assertIsNone(admission(resources(), demand, NOW))

    def test_cpu_memory_and_disk_90_percent_boundary_each_holds(self):
        for changed in [{"cpuPercent": 90}, {"memoryUsed": 900}, {"diskUsed": 900}]:
            result = report(snapshot(unit(), resources=resources(**changed)), NOW)
            self.assertEqual(result["assignments"], [])
            self.assertIn("90_PERCENT_LIMIT", result["queues"]["product"][0]["reasons"][0]["code"])

    def test_proposals_reserve_memory_and_cpu_before_second_assignment(self):
        for field, value in [("memoryBytes", 400), ("cpuPercent", 40)]:
            demand = dict(unit()["demand"], **{field: value})
            result = report(snapshot(unit("a", demand=demand), unit("b", demand=demand)), NOW)
            self.assertEqual(len(result["assignments"]), 1)

    def test_only_one_heavy_test_proposal_uses_available_token(self):
        demand = dict(unit()["demand"], heavy=True)
        result = report(snapshot(unit("a", demand=demand), unit("b", demand=demand)), NOW)
        self.assertEqual(len(result["assignments"]), 1)
        self.assertEqual(result["reservedProposals"]["heavy"], 1)

    def test_unknown_stale_future_nan_or_missing_demand_is_not_capacity(self):
        demand = unit()["demand"]
        for data in [{}, resources(observedAt=(NOW - timedelta(seconds=301)).isoformat()),
                     resources(observedAt=(NOW + timedelta(seconds=1)).isoformat()),
                     resources(cpuPercent=float("nan"))]:
            self.assertIsNotNone(admission(data, demand, NOW))
        self.assertEqual(admission(resources(), {}, NOW), "RESOURCE_ESTIMATE_UNKNOWN")

    def test_unknown_live_sensors_stay_unknown_without_fabricating_capacity(self):
        with tempfile.TemporaryDirectory() as temp:
            data = observe(Path(temp), proc=Path(temp), cgroup=Path(temp))
        self.assertIsNone(data["cpuPercent"])
        self.assertIsNone(data["memoryTotal"])
        self.assertGreater(data["diskTotal"], 0)


class ArtifactContractsTest(unittest.TestCase):
    def test_review_m3_generator_string_or_numeric_owner_is_held_without_crashing(self):
        for owner, generator in [("opensamguk/one", "not-an-object"),
                                 (123, {"name": "synthetic-tests", "version": "1"})]:
            with self.subTest(owner=owner, generator=generator):
                self.item["owner"] = owner
                self.receipt["generator"] = generator
                self.save()
                self.assertEqual(self.row()["status"], "OWNERSHIP_HELD")
        self.assertTrue(self.path.exists())

    def test_review_m3_malformed_artifacts_do_not_hide_valid_following_item(self):
        malformed = dict(self.item, owner=123)
        rows = inventory([None, malformed, self.item], self.root, ["build/"], set(), NOW)
        self.assertEqual([row["cleanupCandidate"] for row in rows], [False, False, True])
        self.assertTrue(self.path.exists())

    def test_unknown_active_owner_prevents_cleanup_proposal(self):
        row = inventory([self.item], self.root, ["build/"], None, NOW)[0]
        self.assertFalse(row["cleanupCandidate"])
        self.assertEqual(row["status"], "RETAIN_ACTIVE_OWNERS_UNKNOWN")
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root / "build").mkdir()
        self.path = self.root / "build/result.xml"
        self.path.write_text("synthetic generated result")
        self.item = {"path": "build/result.xml", "receipt": "build/result.owner.json", "owner": "opensamguk/one", "head": HEAD}
        self.receipt = {"schema": "generated-artifact/1", **{k: self.item[k] for k in ("path", "owner", "head")},
                        "sha256": hashlib.sha256(self.path.read_bytes()).hexdigest(),
                        "createdAt": (NOW - timedelta(days=2)).isoformat(), "expiresAt": (NOW - timedelta(days=1)).isoformat(),
                        "generator": {"name": "synthetic-tests", "version": "1"},
                        "regenerate": ["python3", "synthetic-test.py"]}
        self.save()

    def save(self):
        (self.root / self.item["receipt"]).write_text(json.dumps(self.receipt))

    def row(self, active=()):
        return inventory([self.item], self.root, ["build/"], set(active), NOW)[0]

    def test_matching_owner_head_content_lifetime_and_generator_only_propose_approved_cleanup(self):
        before = self.path.read_bytes()
        row = self.row()
        self.assertTrue(row["cleanupCandidate"])
        self.assertTrue(row["requiresApproval"])
        self.assertEqual(row["ownershipGrade"], "LOCAL_RECEIPT_MATCHED")
        self.assertEqual(self.path.read_bytes(), before)

    def test_active_owner_and_unexpired_output_are_retained(self):
        self.assertFalse(self.row(["opensamguk/one"])["cleanupCandidate"])
        self.receipt["expiresAt"] = (NOW + timedelta(days=1)).isoformat()
        self.save()
        self.assertFalse(self.row()["cleanupCandidate"])

    def test_foreign_owner_changed_head_missing_generator_or_regeneration_hold(self):
        original = copy.deepcopy(self.receipt)
        for changes in [{"owner": "bp/one"}, {"head": BASE}, {"generator": {}}, {"regenerate": []}]:
            self.receipt = dict(original, **changes)
            self.save()
            self.assertEqual(self.row()["status"], "OWNERSHIP_HELD")

    def test_changed_output_invalidates_ownership_without_deleting_either_file(self):
        self.path.write_text("modified by another task")
        self.assertEqual(self.row()["reason"], "ARTIFACT_CONTENT_CHANGED")
        self.assertTrue((self.root / self.item["receipt"]).is_file())

    def test_symlink_traversal_sensitive_and_source_paths_are_held(self):
        self.path.unlink()
        self.path.symlink_to(self.root / "outside")
        self.assertEqual(self.row()["reason"], "ARTIFACT_SYMLINK")
        for path in ["../outside", "build/private.key", "README.md"]:
            self.item["path"] = path
            self.assertFalse(self.row()["cleanupCandidate"])


class HotfixAndCliContractsTest(unittest.TestCase):
    def test_review_m2_live_metrics_do_not_refresh_stale_or_unproven_heavy_tokens(self):
        for changes in [{"observedAt": None}, {"observedAt": (NOW - timedelta(seconds=301)).isoformat()},
                        {"observedAt": (NOW + timedelta(seconds=1)).isoformat()}, {"source": None}]:
            with self.subTest(changes=changes), tempfile.TemporaryDirectory() as temp:
                data = snapshot(unit(demand=dict(unit()["demand"], heavy=True)), unit("light"))
                data["resources"]["observedAt"] = (NOW - timedelta(days=1)).isoformat()
                data["resources"]["heavyTokens"].update(changes)
                token_before = copy.deepcopy(data["resources"]["heavyTokens"])
                path = Path(temp) / "input.json"
                path.write_text(json.dumps(data))
                output = io.StringIO()
                with patch("automation_lane.cli.observe", return_value=resources()), redirect_stdout(output):
                    self.assertEqual(observe_main(["--input", str(path), "--observe-resources", "--now", NOW.isoformat()]), 0)
                result = json.loads(output.getvalue())
                self.assertEqual([a["unit"] for a in result["assignments"]], ["opensamguk/light"])
                self.assertEqual(result["resources"]["heavyTokens"], token_before)

    def test_review_m2_live_metrics_allow_only_fresh_tokens_with_budget_reservation(self):
        demand = dict(unit()["demand"], heavy=True)
        data = snapshot(unit("a", demand=demand), unit("b", demand=demand))
        data["resources"]["observedAt"] = (NOW - timedelta(days=1)).isoformat()
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "input.json"
            path.write_text(json.dumps(data))
            output = io.StringIO()
            with patch("automation_lane.cli.observe", return_value=resources()), redirect_stdout(output):
                self.assertEqual(observe_main(["--input", str(path), "--observe-resources", "--now", NOW.isoformat()]), 0)
        result = json.loads(output.getvalue())
        self.assertEqual(len(result["assignments"]), 1)
        self.assertEqual(result["reservedProposals"]["heavy"], 1)

    def test_review_m3_null_evidence_request_does_not_abort_cli_or_valid_queue(self):
        record, request = proof()
        data = snapshot(unit(), evidenceRequests=[None, {"id": "valid", "record": record, "request": request}])
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "input.json"
            path.write_text(json.dumps(data))
            output = io.StringIO()
            with redirect_stdout(output):
                self.assertEqual(observe_main(["--input", str(path), "--now", NOW.isoformat()]), 0)
        result = json.loads(output.getvalue())
        self.assertEqual(len(result["assignments"]), 1)
        self.assertEqual([row["status"] for row in result["evidence"]], ["REVALIDATE", "REUSE_EXACT"])

    def test_live_observation_uses_clock_after_sampling_and_can_admit_fresh_resources(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "input.json"
            path.write_text(json.dumps(snapshot(unit())))
            output = io.StringIO()
            with patch("automation_lane.cli.datetime") as clock, \
                    patch("automation_lane.cli.observe", return_value=resources(observedAt=(NOW + timedelta(milliseconds=100)).isoformat())), \
                    redirect_stdout(output):
                clock.now.side_effect = [NOW, NOW + timedelta(milliseconds=200)]
                self.assertEqual(observe_main(["--input", str(path), "--observe-resources"]), 0)
            self.assertEqual(len(json.loads(output.getvalue())["assignments"]), 1)
    def test_malformed_batch_count_is_held_without_claiming_a_completed_scan(self):
        row = batches_report([{"repo": "example/opensamguk", "stage": "PLAN_PENDING", "findingsCount": None}])[0]
        self.assertEqual(row["observerStatus"], "CONTRACT_HELD")
        self.assertIn("FINDING_COUNT_UNKNOWN", row["observerReasons"])
    def test_external_hotfix_scan_can_start_without_harness_and_later_batch_needs_five_findings(self):
        batch = {"repo": "example/opensamguk", "batchId": "cycle-1", "stage": "SCANNING", "findingsCount": 0, "prs": []}
        row = batches_report([batch])[0]
        self.assertEqual(row["observerStatus"], "OBSERVED")
        self.assertFalse(row["observerRequiredToStart"])
        batch["stage"] = "PLAN_PENDING"
        self.assertIn("FIVE_CONFIRMED_FINDINGS_REQUIRED", batches_report([batch])[0]["observerReasons"])

    def test_one_active_hotfix_batch_and_one_pr_per_repo_cycle(self):
        batch = {"repo": "example/opensamguk", "stage": "IMPLEMENTING", "findingsCount": 5, "prs": [1]}
        self.assertTrue(all("MULTIPLE_ACTIVE_BATCHES_IN_REPO" in b["observerReasons"] for b in batches_report([batch, batch])))
        batch["prs"] = [1, 2]
        self.assertIn("ONE_PR_PER_CYCLE_REQUIRED", batches_report([batch])[0]["observerReasons"])
        result = report(snapshot(unit("hotfix", batchId="next"), batches=[batch]), NOW)
        self.assertEqual(result["assignments"], [])

    def test_hotfix_ready_for_merge_still_needs_exact_review_ci_and_preserves_blocked_state(self):
        batch = {"repo": "example/opensamguk", "stage": "READY_FOR_MERGE", "findingsCount": 5,
                 "prs": [1], "head": HEAD, "reviewHead": BASE, "ciHead": HEAD,
                 "review": "SOURCE_MERGEABLE", "ci": "PASS", "blockers": ["APPROVAL_BLOCKED"]}
        row = batches_report([batch])[0]
        self.assertEqual(row["observerStatus"], "BLOCKED")
        self.assertFalse(row["executionAllowed"])
        self.assertIn("EXACT_HEAD_REVIEW_CI_REQUIRED", row["observerReasons"])

    def test_cli_observation_has_no_execution_or_deletion_option_and_no_state_writes(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "input.json"
            path.write_text(json.dumps(snapshot(unit())))
            before = path.read_bytes()
            call = [sys.executable, str(ROOT / "bin/work-observe"), "--input", str(path), "--now", NOW.isoformat()]
            result = subprocess.run(call, text=True, capture_output=True, check=True)
            self.assertEqual(json.loads(result.stdout)["mode"], "OBSERVE")
            self.assertEqual(path.read_bytes(), before)
            self.assertEqual(list(Path(temp).iterdir()), [path])
            denied = subprocess.run(call + ["--execute"], text=True, capture_output=True)
            self.assertNotEqual(denied.returncode, 0)


class PreflightContractsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.repo = self.root / "game"
        self.repo.mkdir()
        def git(*args):
            subprocess.run(["git", "-C", str(self.repo), *args], check=True, capture_output=True)
        git("init", "-q")
        git("config", "user.name", "Synthetic")
        git("config", "user.email", "synthetic@example.invalid")
        git("remote", "add", "origin", "https://github.com/example/game.git")
        (self.repo / "AGENTS.md").write_text("public fixture instructions")
        (self.repo / ".env.production").write_text("synthetic-secret-never-export")
        skill = self.repo / ".claude/skills/domain/SKILL.md"
        skill.parent.mkdir(parents=True)
        skill.write_text("public fixture skill")
        git("add", "AGENTS.md", ".env.production", ".claude/skills/domain/SKILL.md")
        git("commit", "-qm", "fixture")
        self.home = self.root / "home"
        (self.home / ".claude").mkdir(parents=True)
        (self.home / ".claude/.credentials.json").write_text("synthetic-auth-never-read")
        self.manifest = {"schema": "development-bootstrap/1", "tools": ["python3", "node"], "projects": [
            {"id": "game", "repository": "example/game", "checkout": "game", "configuration": ["AGENTS.md", ".env.production"],
             "skillDirectories": [".claude/skills"], "runtime": [{"tool": "node", "major": 22}],
             "commands": [{"purpose": "fixture", "argv": ["python3", "fixture.py"]}]},
            {"id": "bp", "repository": None, "checkout": None}]}

    def observed(self):
        with patch("automation_lane.preflight.version_probe", side_effect=lambda name: {
                "tool": name, "status": "AVAILABLE", "version": "24.1.0" if name == "node" else "3.12.0"}):
            return preflight(self.manifest, self.root, home=self.home)

    def test_union_manifest_reports_unknown_bp_runtime_mismatch_and_declarations_not_execution(self):
        data = self.observed()
        self.assertEqual(data["status"], "PARTIAL")
        self.assertEqual(data["projects"][1]["status"], "CONFIGURATION_UNKNOWN")
        self.assertEqual(data["projects"][0]["runtimeChecks"][0]["status"], "UNVERIFIED_OR_MISMATCH")
        self.assertEqual(data["projects"][0]["commandAvailability"][0]["execution"], "NOT_RUN")
        self.assertFalse(data["savedCloudConfigurationChanged"])

    def test_authentication_is_presence_only_and_sensitive_configuration_is_not_exported(self):
        original = Path.open
        def guard(path, *args, **kwargs):
            if path.name == ".credentials.json" or path.name == ".env.production":
                raise AssertionError("auth/private content must not be read")
            return original(path, *args, **kwargs)
        with patch.object(Path, "open", guard):
            data = self.observed()
        serialized = json.dumps(data)
        self.assertNotIn("synthetic-auth-never-read", serialized)
        self.assertNotIn("synthetic-secret-never-export", serialized)
        self.assertEqual(data["authentication"][0]["presence"], "PRESENT_UNVERIFIED")
        self.assertFalse(data["authentication"][0]["valuesRead"])
        self.assertEqual(data["projects"][0]["configuration"][1]["status"], "EXCLUDED_PRIVATE_CONFIGURATION")
        self.assertEqual(data["projects"][0]["skills"], [".claude/skills/domain/SKILL.md"])

    def test_repository_allowlist_mismatch_is_not_a_usable_source(self):
        self.manifest["projects"][0]["repository"] = "example/other"
        self.assertEqual(self.observed()["projects"][0]["status"], "CONFIGURATION_UNKNOWN")
        self.assertIsNone(repository_name("https://user:synthetic-secret@github.com/example/game.git"))

    def test_preflight_cannot_execute_manifest_commands_or_unsupported_probes(self):
        with patch("automation_lane.preflight.subprocess.run") as run:
            self.assertEqual(version_probe("sh")["status"], "UNSUPPORTED_PROBE")
            run.assert_not_called()

    def test_version_probe_disables_corepack_network_and_only_exports_version(self):
        result = subprocess.CompletedProcess([], 0, "11.19.0", "synthetic-private-diagnostic")
        with patch("automation_lane.preflight.shutil.which", return_value="/fixture/pnpm"), \
                patch("automation_lane.preflight.subprocess.run", return_value=result) as run:
            observed = version_probe("pnpm")
        self.assertEqual(run.call_args.kwargs["env"]["COREPACK_ENABLE_NETWORK"], "0")
        self.assertEqual(run.call_args.args[0], ["/fixture/pnpm", "--version"])
        self.assertNotIn("synthetic-private-diagnostic", json.dumps(observed))


if __name__ == "__main__":
    unittest.main()
