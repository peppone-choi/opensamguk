"""Completion compatibility uses fake API/readback and temporary state only."""
import socket
import unittest
import work_units_test as contracts
from work_units import completion, schema, bundle_hash
from work_units.acceptance import parse_ac


class LinkedReader(contracts.FakeReader):
    def __init__(self, secondary):
        super().__init__()
        self.secondary = secondary
        self.by_target = {}

    def get(self, path):
        if path.endswith("/issues/4"):
            return {"number": 4, "state": "open", "body": self.secondary}
        return super().get(path)

    def pages(self, path, **kwargs):
        return self.by_target.get(path.split("/comments", 1)[0], [])


class LinkedWriter(contracts.FakeWriter):
    def write(self, path, payload):
        row = super().write(path, payload)
        self.reader.by_target.setdefault(path.split("/comments", 1)[0], []).append(row)
        return row


class CompletionCompatibilityTest(unittest.TestCase):
    def setUp(self):
        self.fixture = contracts.CompletionTest("test_partial_ac_only_comments_and_idempotent_record")
        self.fixture.setUp()
        self.addCleanup(self.fixture.doCleanups)
        self.state, self.reader = self.fixture.state, self.fixture.reader

    def authorize(self):
        schema.durable_write(self.state / "work-units/writer-host.json", {
            "enabled": True, "host": socket.gethostname(), "bundleHash": bundle_hash(),
            "approvedBy": "synthetic-fixture-owner"})

    def attestation(self):
        f = self.fixture
        return {"head": contracts.HEAD, "acFingerprint": f.unit["acceptance"]["fingerprint"],
                "approvedBy": "synthetic-fixture-owner", "reason": "explicit fixture acceptance",
                "bundleHash": bundle_hash(), "manifestBlob": f.receipt["manifestBlob"]}

    def legacy(self):
        f = self.fixture
        self.reader.issue["body"] = "Explicitly approved legacy acceptance prose"
        ac = parse_ac(self.reader.issue["body"], legacy=True)
        f.unit["acceptance"].update(mode="legacy", fingerprint=ac["fingerprint"], criteria=["LEGACY-WHOLE"])
        f.host["reasons"], f.receipt["valid"] = [], True

    def record(self, *, registered=False, attested=True):
        f = self.fixture
        ac = parse_ac(self.reader.issue["body"], legacy=f.unit["acceptance"]["mode"] == "legacy")
        registration = {"version": 2, "legacyAc": ac["legacyAc"], "acFingerprint": ac["fingerprint"]} if registered else None
        return completion.record(self.state, f.raw, self.reader, f.host, unit=f.unit,
                                 registration=registration, attestation=self.attestation() if attested else None)

    def drain(self, writer=None, *, enabled=True):
        writer = writer or contracts.FakeWriter(self.reader)
        completion.drain(self.state, self.reader, writer=writer, enabled=enabled, now=1000)
        return writer, completion.records(self.state, "outbox")

    def test_attested_legacy_drains_to_done_and_closure_stays_manual(self):
        self.legacy()
        audit = self.record()
        self.authorize()
        writer, rows = self.drain()
        self.assertEqual([r["state"] for r in rows if r["action"] == "comment"], ["DONE"])
        self.assertEqual(writer.writes, 1)
        self.assertEqual((audit["acceptanceMode"], audit["acceptanceAttested"]), ("legacy", True))
        self.assertEqual([r["state"] for r in rows if r["action"] == "close"], ["MANUAL"])

    def test_registered_legacy_uses_parsed_mode_and_matching_registry_evidence(self):
        self.legacy()
        audit = self.record(registered=True)
        self.assertEqual((audit["reservation"], audit["acceptanceMode"]), ("VERIFIED", "legacy"))
        self.authorize()
        self.assertEqual(self.drain()[0].writes, 1)
        self.assertEqual([r["state"] for r in completion.records(self.state, "outbox") if r["action"] == "comment"], ["DONE"])

    def test_legacy_requires_attestation_even_with_registration(self):
        self.legacy()
        for registered in (False, True):
            with self.subTest(registered=registered), self.assertRaisesRegex(ValueError, "LEGACY_ATTESTATION_REQUIRED"):
                self.record(registered=registered, attested=False)
        self.assertEqual(completion.records(self.state, "audits"), [])

    def test_registry_legacy_mode_or_fingerprint_mismatch_is_rejected(self):
        self.legacy()
        f = self.fixture
        good = {"version": 2, "legacyAc": True, "acFingerprint": f.unit["acceptance"]["fingerprint"]}
        for bad in [dict(good, legacyAc=False), dict(good, acFingerprint="sha256:" + "0" * 64)]:
            with self.subTest(bad=bad), self.assertRaisesRegex(ValueError, "REGISTRATION_AC_MISMATCH"):
                completion.record(self.state, f.raw, self.reader, f.host, unit=f.unit,
                                  registration=bad, attestation=self.attestation())

    def test_block_mode_attested_does_not_infer_legacy_from_reservation(self):
        f = self.fixture
        f.host["reasons"], f.receipt["valid"] = [], True
        audit = self.record()
        self.assertEqual((audit["reservation"], audit["acceptanceMode"]), ("ATTESTED", "block"))
        self.authorize()
        writer, rows = self.drain()
        self.assertEqual(writer.writes, 1)
        self.assertEqual(rows[0]["state"], "DONE")

    def test_legacy_body_drift_before_write_never_finishes(self):
        self.legacy()
        self.record()
        self.authorize()
        self.reader.issue["body"] += " changed"
        writer, rows = self.drain()
        self.assertEqual(writer.writes, 0)
        self.assertEqual([r["lastError"] for r in rows if r["action"] == "comment"], ["AC_DRIFT"])

    def test_legacy_readback_uses_same_mode_and_detects_drift(self):
        self.legacy()
        self.record()
        self.authorize()
        reader = self.reader
        class DriftWriter(contracts.FakeWriter):
            def write(self, path, payload):
                row = super().write(path, payload)
                reader.issue["body"] += " changed during write"
                return row
        writer, rows = self.drain(DriftWriter(reader))
        self.assertEqual(writer.writes, 1)
        self.assertEqual([r["state"] for r in rows if r["action"] == "comment"], ["MANUAL"])

    def assert_secondary_link(self, body):
        f = self.fixture
        self.reader = LinkedReader(body)
        f.unit["issues"].append({"system": "github", "repo": contracts.REPO, "number": 4})
        audit = self.record(registered=True, attested=False)
        self.assertEqual(audit["issueAcceptance"][contracts.REPO + "#4"]["mode"], "link")
        self.authorize()
        writer, rows = self.drain(LinkedWriter(self.reader))
        self.assertEqual(writer.writes, 2)
        self.assertEqual([r["state"] for r in rows], ["DONE", "DONE"])
        linked = next(r for r in rows if r["target"].endswith("#4"))
        self.assertIn("no acceptance or completion", linked["body"])
        self.reader.secondary += " changed"
        with self.assertRaisesRegex(ValueError, "AUDIT_ID_COLLISION"):
            self.record(registered=True, attested=False)

    def test_secondary_with_different_ac_is_a_bound_link_comment(self):
        self.assert_secondary_link(contracts.AC.replace("ship", "secondary acceptance"))

    def test_secondary_without_ac_is_a_bound_link_comment(self):
        self.assert_secondary_link("secondary has no AC")

    def test_secondary_link_fingerprint_drift_is_still_manual(self):
        self.reader = LinkedReader("linked prose")
        self.fixture.unit["issues"].append({"system": "github", "repo": contracts.REPO, "number": 4})
        self.record(registered=True, attested=False)
        self.authorize()
        self.reader.secondary += " changed"
        writer, rows = self.drain(LinkedWriter(self.reader))
        self.assertEqual(writer.writes, 1)
        linked = next(r for r in rows if r["target"].endswith("#4"))
        self.assertEqual((linked["state"], linked["lastError"]), ("MANUAL", "AC_DRIFT"))

    def test_intent_cannot_override_trusted_audit_mode(self):
        self.fixture.record()
        row = completion.records(self.state, "outbox")[0]
        row["acceptanceMode"] = "legacy"
        schema.durable_write(completion.intent_path(self.state, row["intentId"]), row)
        self.authorize()
        writer, rows = self.drain()
        self.assertEqual(writer.writes, 0)
        self.assertEqual((rows[0]["state"], rows[0]["lastError"]), ("MANUAL", "INTENT_AUDIT_MISMATCH"))

    def test_old_mode_less_audit_is_manual_without_retry_or_external_write(self):
        audit = self.fixture.record()
        for key in ("acceptanceMode", "acceptanceAttested", "issueAcceptance"):
            audit.pop(key)
        schema.durable_write(self.state / "work-units/audits" / (audit["auditId"] + ".json"), audit)
        completion.scan(self.state)
        self.authorize()
        writer, rows = self.drain()
        self.assertEqual(writer.writes, 0)
        self.assertEqual((rows[0]["state"], rows[0]["lastError"]), ("MANUAL", "AUDIT_ACCEPTANCE_MODE_MIGRATION_REQUIRED"))

    def test_old_mode_less_audit_does_not_contribute_to_new_acceptance(self):
        audit = self.fixture.record()
        audit.pop("acceptanceMode")
        schema.durable_write(self.state / "work-units/audits" / (audit["auditId"] + ".json"), audit)
        self.fixture.raw["number"] = 3
        self.fixture.unit["acceptance"]["criteria"] = ["AC-2"]
        self.assertEqual(self.fixture.record()["remainingAtRecord"], ["AC-1"])

    def test_re_record_cannot_silently_change_trusted_acceptance_proof(self):
        f = self.fixture
        f.host["reasons"], f.receipt["valid"] = [], True
        self.record()
        with self.assertRaisesRegex(ValueError, "AUDIT_ID_COLLISION"):
            self.record(registered=True, attested=False)

    def test_legacy_default_dry_run_and_unapproved_writer_make_zero_writes(self):
        self.legacy()
        self.record()
        writer, rows = self.drain(enabled=False)
        self.assertEqual(writer.writes, 0)
        self.assertEqual([r["state"] for r in rows if r["action"] == "comment"], ["DRY_RUN"])
        writer, rows = self.drain()
        self.assertEqual(writer.writes, 0)
        self.assertEqual([r["state"] for r in rows if r["action"] == "comment"], ["PENDING_AUTH"])
