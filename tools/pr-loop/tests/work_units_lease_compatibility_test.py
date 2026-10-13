"""Lease admission and ownership tests use isolated synthetic state only."""
import hashlib
import tempfile
import unittest
from pathlib import Path
import work_units_test as contracts
from work_units import claim, schema
from work_units.queue import eligibility


class LeaseCompatibilityTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.state = Path(self.temp.name)

    def acquire(self, task, nonce, *, project="game", repo="owner/game", **lease):
        return claim.acquire(self.state, task, nonce, {"issues": [], "inputs": [], "scopes": [], **lease},
                             project=project, repo=repo)

    def owner(self, project="game", task="old", nonce="old-nonce", repo="owner/game", version=1):
        data = {"version": version, "project": project, "task": task, "unitId": task,
                "nonce": nonce, "repo": repo, "branch": f"work/{project}/{task}", "phase": "active"}
        if version == 2:
            data["lease"] = {}
        schema.durable_write(self.state / "tasks" / (schema.lease_key(project, task) + ".json"), data)
        return data

    def legacy_file(self, nonce="old-nonce"):
        path = self.state / "work-units/leases/old.json"
        schema.durable_write(path, {"unitId": "old", "nonceSha256": hashlib.sha256(nonce.encode()).hexdigest(),
                                   "issues": [], "inputs": [], "scopes": []})
        return path

    def test_same_task_and_input_in_different_repos_are_independent_and_retirement_preserves_other(self):
        a = self.acquire("shared", "a", inputs=["court.reward"])
        b = self.acquire("shared", "b", project="images", repo="owner/images", inputs=["court.reward"])
        path = self.state / "work-units/leases" / (b["leaseKey"] + ".json")
        before = path.read_bytes()
        with self.assertRaisesRegex(ValueError, "LEASE_OWNER"):
            claim.release(self.state, "shared", "b", project="game", repo="owner/game")
        claim.release(self.state, "shared", "a", project="game", repo="owner/game")
        self.assertEqual(path.read_bytes(), before)
        self.assertEqual(claim.active_leases(self.state), [b])
        self.assertNotEqual(a["leaseKey"], b["leaseKey"])

    def test_same_repo_inputs_and_global_issue_references_still_conflict(self):
        self.acquire("first", "a", issues=["owner/game#1"], inputs=["court.reward"])
        for project, repo, fields in [("other", "owner/game", {"inputs": ["court.reward"]}),
                                       ("images", "owner/images", {"issues": ["owner/game#1"]})]:
            with self.subTest(project=project), self.assertRaisesRegex(ValueError, "LEASE_CONFLICT"):
                self.acquire("second", "b", project=project, repo=repo, **fields)

    def test_namespace_does_not_allow_nonce_or_repo_takeover(self):
        first = self.acquire("shared", "a")
        for nonce, repo in [("b", "owner/game"), ("a", "owner/other")]:
            with self.subTest(nonce=nonce, repo=repo), self.assertRaisesRegex(ValueError, "CLAIM_REUSE_DIFFERS"):
                claim.acquire(self.state, "shared", nonce, {"issues": [], "inputs": [], "scopes": []},
                              project="game", repo=repo, replace=True)
        self.assertEqual(claim.active_leases(self.state), [first])

    def test_repo_local_broad_and_unknown_scopes_do_not_block_other_repos(self):
        for scope in ["ALL_INPUTS", "ALL_UI_INPUTS", "court.*"]:
            with self.subTest(scope=scope):
                self.assertFalse(claim.conflicts({"repo": "owner/game", "scopes": [scope], "unknownScope": True},
                                                 {"repo": "owner/images", "inputs": ["court.reward"]}))
        self.assertTrue(claim.conflicts({"repo": "owner/game", "unknownScope": True},
                                        {"repo": "owner/game", "inputs": ["court.reward"]}))
        self.assertFalse(claim.conflicts({"repo": "owner/game", "unknownScope": True},
                                         {"repo": "owner/game", "unknownScope": True}))

    def test_old_file_moves_only_with_one_exact_registry_nonce_owner(self):
        self.owner()
        old = self.legacy_file()
        original = schema.read_record(old)
        self.acquire("next", "new")
        target = self.state / "work-units/leases" / (schema.lease_key("game", "old") + ".json")
        self.assertFalse(old.exists())
        migrated = schema.read_record(target)
        self.assertEqual({k: migrated[k] for k in original}, original)
        self.assertEqual((migrated["project"], migrated["task"], migrated["repo"]), ("game", "old", "owner/game"))

    def test_missing_or_ambiguous_old_owner_holds_without_overwrite_or_delete(self):
        old = self.legacy_file()
        before = old.read_bytes()
        with self.assertRaisesRegex(ValueError, "LEGACY_LEASE_OWNER_ABSENT"):
            self.acquire("next", "new")
        self.owner()
        self.owner(project="images", repo="owner/images")
        with self.assertRaisesRegex(ValueError, "LEGACY_LEASE_OWNER_AMBIGUOUS"):
            self.acquire("next", "new")
        self.assertEqual(old.read_bytes(), before)
        self.assertEqual(list(old.parent.glob("*.json")), [old])

    def test_existing_namespaced_target_is_never_overwritten_by_old_file(self):
        self.owner()
        old = self.legacy_file()
        target = self.state / "work-units/leases" / (schema.lease_key("game", "old") + ".json")
        data = schema.scoped_lease(self.state, old)
        schema.durable_write(target, dict(data, inputs=["different.command"]))
        before = (old.read_bytes(), target.read_bytes())
        with self.assertRaisesRegex(ValueError, "LEGACY_LEASE_TARGET_DIFFERS"):
            self.acquire("next", "new")
        self.assertEqual((old.read_bytes(), target.read_bytes()), before)

    def test_import_requires_an_active_v1_owner_and_keeps_nonce_guard(self):
        owner = self.owner()
        imported = claim.import_legacy(self.state, owner, {"issues": [], "inputs": [], "scopes": []})
        with self.assertRaisesRegex(ValueError, "LEGACY_IMPORT_ACTIVE_OWNER_REQUIRED"):
            claim.import_legacy(self.state, dict(owner, nonce="forged"), {})
        changed = dict(owner, nonce="replacement-nonce")
        schema.durable_write(self.state / "tasks" / (schema.lease_key("game", "old") + ".json"), changed)
        with self.assertRaisesRegex(ValueError, "CLAIM_REUSE_DIFFERS"):
            claim.import_legacy(self.state, changed, {})
        owner["version"], owner["lease"] = 2, imported
        schema.durable_write(self.state / "tasks" / (schema.lease_key("game", "old") + ".json"), owner)
        with self.assertRaisesRegex(ValueError, "LEGACY_IMPORT_ACTIVE_OWNER_REQUIRED"):
            claim.import_legacy(self.state, owner, {})
        self.assertEqual(claim.active_leases(self.state), [imported])

    def test_trusted_registration_rejects_project_repo_and_lease_namespace_mismatch(self):
        owner = self.owner(version=2)
        owner["lease"] = self.acquire("old", "old-nonce")
        path = self.state / "tasks" / (schema.lease_key("game", "old") + ".json")
        schema.durable_write(path, owner)
        self.assertEqual(schema.trusted_registration(self.state, "game/old", repo="owner/game"), owner)
        for key, value in [("project", "images"), ("repo", "owner/images")]:
            with self.subTest(key=key):
                schema.durable_write(path, dict(owner, **{key: value}))
                with self.assertRaisesRegex(ValueError, "REGISTRATION_PROVENANCE_MISMATCH"):
                    schema.trusted_registration(self.state, "game/old", repo="owner/game")
        schema.durable_write(path, owner)
        lease_path = self.state / "work-units/leases" / (owner["lease"]["leaseKey"] + ".json")
        schema.durable_write(lease_path, dict(owner["lease"], repo="owner/images"))
        with self.assertRaisesRegex(ValueError, "REGISTRATION_LEASE_MISMATCH"):
            schema.trusted_registration(self.state, "game/old", repo="owner/game")

    def test_issue_queue_does_not_treat_other_repo_number_as_the_same_issue(self):
        lease = self.acquire("one", "a", issues=["owner/images#1"], issueNumbers=[1], repo="owner/images")
        issue = {"number": 1, "state": "open", "body": contracts.AC}
        self.assertEqual(eligibility(issue, [lease], {}, repo="owner/game")["status"], "ELIGIBLE")
        self.assertEqual(eligibility(issue, [lease], {}, repo="owner/images")["status"], "LEASED")
