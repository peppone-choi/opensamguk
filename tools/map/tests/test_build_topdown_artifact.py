"""Cheap artifact structure/publication guards. No product bake or image/VM access."""
from copy import deepcopy
import gzip
import json
from pathlib import Path
import re
import struct
import tempfile
from types import SimpleNamespace
import unittest
from unittest import mock

import yaml

from tools.map import build_topdown_artifact as A


def compressed(raw):
    blob = bytearray(gzip.compress(raw, mtime=0))
    blob[9] = 255
    return bytes(blob)


class BundleFixture(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        parent = Path(self.temp.name)
        self.places = dict(schemaVersion=1, provinceCount=1, provinceAdmin=[[0, 0, 0]],
            counties=[dict(id="county:1", name="현", kind="COUNTY", cityId=1)],
            commanderies=[dict(id="commandery:1", name="군", kind="COMMANDERY", seatCityId=1,
                               seatJurisdictionId="county:1", commanderyNo=0, labelAnchorMissing=False)],
            ju=[dict(name="주", anchor=[0, 0])],
            cities=[dict(id=1, name="도시", sourceName="도시(甲)", level=11, cell=[0, 0], provinceIndex=0,
                         countyIndex=0, commanderyIndex=0, isSeat=False, isAdministrativeSeat=True,
                         footprint=dict(originCol=0, originRow=0, span=1, innerSpan=1), roofCell=None,
                         gates="", site="county", households=None)],
            passes=[], passEndpointChecks=[],
            labels=[dict(id="city:1", text="도시", kind="county", anchor=[0, 0], priority=1, priorityHouseholds=None, footprintSpan=1)],
            seatAudit=dict(administrativeCityIds=[1], gameCityIds=[], intersection=[], administrativeOnly=[1], gameOnly=[]),
            sourceDefinitions=dict(administrativeSeat="fixed han-tiles binding", gameSeat="fixed world preset"))
        self.defects = dict(schemaVersion=1, counts={}, defects=[])
        self.manifest = dict(schemaVersion=1, artifactId="topdown-bake", bakeId="", inputFingerprint=dict(region=None),
            mapRelease="fixture-map", kitVersion="1" * 40, formatVersion=1, shape=dict(cols=4, rows=4), chunkSize=256,
            kitId="fixture", kitCatalogSha256="a" * 64, inputs={"repo/hanTiles": "b" * 64},
            tool=dict(file=A.BAKE_TOOL, sha256="c" * 64), partial=False, region=None, sampleChunks=[[0, 0]],
            validation=dict(gamePassControl="unverified", provincePlane="static geographic index"),
            format={key: "fixture description" for key in ("cellOrder", "chunkFile", "tilePlane", "provincePlane", "uniformChunk", "overview", "rawSha256")},
            undrawnTile=65535, chunks=[], overview={}, places={}, defects={}, files=[])
        self.manifest["bakeId"] = A.canonical_identity(self.manifest)
        self.bundle = parent / self.manifest["bakeId"]
        (self.bundle / "grid/L0").mkdir(parents=True)
        self.entries = [self.make_file("grid/L0/0_0.bin.gz", struct.pack("<H", 0) * 65536 + struct.pack("<H", 1) * 65536),
                        self.make_file("grid/L2.bin.gz", struct.pack("<HH", 0, 1)),
                        self.make_file("places.json.gz", json.dumps(self.places, ensure_ascii=False).encode()),
                        self.make_file("defects.json", json.dumps(self.defects).encode())]
        self.manifest["chunks"] = [dict(self.entries[0], cx=0, cy=0)]
        self.manifest["overview"] = dict(self.entries[1], cols=1, rows=1, block=4)
        self.manifest["places"] = self.entries[2]
        self.manifest["defects"] = dict(self.entries[3], counts={})
        self.expected = {key: deepcopy(self.manifest[key]) for key in ("inputFingerprint", "inputs", "mapRelease", "kitVersion", "kitId", "formatVersion", "shape", "tool")}
        self.save()

    def make_file(self, name, raw):
        data = compressed(raw) if name.endswith(".gz") else raw
        (self.bundle / name).write_bytes(data)
        return dict(file=name, bytes=len(data), sha256=A.sha(data), rawSha256=A.sha(raw))

    def save(self):
        self.manifest["files"] = [dict({k: e[k] for k in ("file", "bytes", "sha256", "rawSha256")},
                                      compression="gzip" if e["file"].endswith(".gz") else "none")
                                  for e in [*self.manifest["chunks"], self.manifest["overview"], self.manifest["places"], self.manifest["defects"]] if "file" in e]
        A.write_json(self.bundle / "manifest.json", self.manifest)

    def update_places(self):
        self.manifest["places"] = self.make_file("places.json.gz", json.dumps(self.places, ensure_ascii=False).encode())
        self.save()

    def check(self):
        return A.audit_bundle(self.bundle, self.expected)

    def test_complete_fixture_records_exact_manifest_transport_and_raw_pins(self):
        result = self.check()
        self.assertEqual(result["manifestSha256"], A.sha((self.bundle / "manifest.json").read_bytes()))
        self.assertEqual(len(result["files"]), 5)
        self.assertEqual(result["files"][1]["rawBytes"], 262144)
        self.assertFalse(result["publicScope"]["publicationApproved"])
        self.assertFalse(result["fullBakeRegenerationCheckedHere"])

    def test_valid_hashes_do_not_allow_live_nation_fields(self):
        self.places["nationId"] = 7
        self.update_places()
        with self.assertRaisesRegex(ValueError, "public field"):
            self.check()

    def test_nested_private_object_inside_allowed_name_is_rejected(self):
        self.places["cities"][0]["name"] = {"personalPosition": [2, 3]}
        self.update_places()
        with self.assertRaisesRegex(ValueError, "nested private"):
            self.check()

    def test_private_footprint_field_is_rejected(self):
        self.places["cities"][0]["footprint"]["fog"] = True
        self.update_places()
        with self.assertRaisesRegex(ValueError, "public field"):
            self.check()

    def test_scalar_name_cannot_become_an_array(self):
        self.places["cities"][0]["name"] = [1, 2, 3]
        self.update_places()
        with self.assertRaisesRegex(ValueError, "scalar field is an array"):
            self.check()

    def test_private_defect_field_is_rejected_with_valid_output_hashes(self):
        self.defects["privateNations"] = [7]
        self.manifest["defects"] = dict(self.make_file("defects.json", json.dumps(self.defects).encode()), counts={})
        self.save()
        with self.assertRaisesRegex(ValueError, "public field"):
            self.check()

    def test_mutated_transport_bytes_are_rejected(self):
        (self.bundle / "grid/L2.bin.gz").write_bytes(b"altered")
        with self.assertRaisesRegex(ValueError, "transport SHA/length"):
            self.check()

    def test_wrong_raw_sha_is_rejected_even_when_inventory_matches(self):
        self.manifest["overview"]["rawSha256"] = "0" * 64
        self.save()
        with self.assertRaisesRegex(ValueError, "raw SHA"):
            self.check()

    def test_partial_is_rejected(self):
        self.manifest["partial"] = True
        self.save()
        with self.assertRaisesRegex(ValueError, "partial"):
            self.check()

    def test_source_identity_mismatch_is_rejected(self):
        self.expected["inputs"]["repo/hanTiles"] = "0" * 64
        with self.assertRaisesRegex(ValueError, "source/runtime/input"):
            self.check()

    def test_noncanonical_identity_is_rejected(self):
        self.manifest["mapRelease"] = "other-map"
        self.save()
        with self.assertRaisesRegex(ValueError, "canonical"):
            self.check()

    def test_sixteen_character_prototype_id_is_rejected(self):
        new = self.bundle.with_name(self.bundle.name[:16])
        self.bundle.rename(new)
        self.bundle = new
        with self.assertRaisesRegex(ValueError, "directory identity"):
            self.check()

    def test_incomplete_chunk_coverage_is_rejected(self):
        self.manifest["chunks"] = []
        self.save()
        with self.assertRaisesRegex(ValueError, "chunk coverage"):
            self.check()

    def test_duplicate_chunk_is_rejected(self):
        self.manifest["chunks"].append(deepcopy(self.manifest["chunks"][0]))
        self.save()
        with self.assertRaisesRegex(ValueError, "duplicate/outside chunk"):
            self.check()

    def test_uniform_pair_is_verified_without_a_file(self):
        raw = struct.pack("<H", 65535) * 65536 + struct.pack("<H", 0) * 65536
        self.manifest["chunks"] = [dict(cx=0, cy=0, uniform=dict(tile=65535, province=0), rawSha256=A.sha(raw))]
        (self.bundle / "grid/L0/0_0.bin.gz").unlink()
        self.save()
        self.assertEqual(len(self.check()["files"]), 4)

    def test_extra_file_is_rejected(self):
        (self.bundle / "world.json").write_text('{"private":true}')
        with self.assertRaisesRegex(ValueError, "missing/extra"):
            self.check()

    def test_symlink_is_rejected_even_when_it_resolves_inside_root(self):
        target = self.bundle / "grid/L2.bin.gz"
        target.rename(self.bundle / "hidden.bin")
        target.symlink_to(self.bundle / "hidden.bin")
        with self.assertRaisesRegex(ValueError, "symlink"):
            self.check()

    def test_mtime_is_rejected_even_with_updated_transport_sha(self):
        target = self.bundle / "grid/L2.bin.gz"
        blob = bytearray(target.read_bytes())
        blob[4] = 1
        target.write_bytes(blob)
        self.manifest["overview"]["sha256"] = A.sha(blob)
        self.save()
        with self.assertRaisesRegex(ValueError, "gzip header"):
            self.check()


class SmallGuards(unittest.TestCase):
    def export_fixture(self, directory):
        pin = dict(files={p: dict(sha256=A.sha(p.encode())) for p in [*A.SOURCE_PATHS.values(), A.BUILD_TOOL, A.BAKE_TOOL]},
                   designPaths=[], mapRelease="fixture-map", kitVersion="1" * 40, kitId="fixture",
                   kitInputs={"kit/catalog.json": "2" * 64, "kit/sourceMergeCommit": "1" * 40})
        fingerprint = {key: pin["files"][A.SOURCE_PATHS[name]]["sha256"] for key, name in A.EXPORT_SOURCE_KEYS.items()}
        fingerprint.update(designJsonSha256={}, exportGeneratorSha256=pin["files"][A.BUILD_TOOL]["sha256"])
        layers = {}
        for name in A.LAYERS:
            filename = f"map-design-{name}.png"
            data = ("small transport fixture " + name).encode()
            (directory / filename).write_bytes(data)
            layers[name] = dict(file=filename, bytes=len(data), sha256=A.sha(data), rawSha256="3" * 64)
        export = dict(schemaVersion=2, artifactId="map-design-export-v2", mapRelease=pin["mapRelease"],
                      inputFingerprint=fingerprint, files=layers, shape=[4, 4])
        A.write_json(directory / "map-design-manifest.json", export)
        return pin, export

    def test_export_source_and_all_repo_kit_layer_pins_are_connected(self):
        with tempfile.TemporaryDirectory() as d:
            directory = Path(d)
            pin, export = self.export_fixture(directory)
            identity = A.expected_identity(pin, directory, {"zlibRuntime": "fixture-zlib"})
            self.assertEqual(identity["inputFingerprint"]["bakeInputs"], identity["inputs"])
            self.assertEqual(identity["inputs"]["export/manifest"], A.sha((directory / "map-design-manifest.json").read_bytes()))
            for name, path in A.SOURCE_PATHS.items():
                self.assertEqual(identity["inputs"]["repo/" + name], pin["files"][path]["sha256"])
            for name in A.LAYERS:
                self.assertEqual(identity["inputs"]["export/" + name], export["files"][name]["rawSha256"])
            self.assertEqual(identity["inputFingerprint"]["compressionRuntime"], "fixture-zlib")

    def test_export_source_drift_is_rejected_before_candidate_audit(self):
        with tempfile.TemporaryDirectory() as d:
            directory = Path(d)
            pin, export = self.export_fixture(directory)
            export["inputFingerprint"]["worldJsonSha256"] = "0" * 64
            A.write_json(directory / "map-design-manifest.json", export)
            with self.assertRaisesRegex(ValueError, "input fingerprint"):
                A.expected_identity(pin, directory, {"zlibRuntime": "fixture-zlib"})

    def test_duplicate_json_key_is_not_silently_overwritten(self):
        with self.assertRaisesRegex(ValueError, "duplicate JSON"):
            A.read_json(b'{"partial":true,"partial":false}')

    def test_nonfinite_json_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "nonfinite"):
            A.read_json(b'{"value":NaN}')

    def test_inflate_is_bounded(self):
        with mock.patch.object(A, "MAX_BYTES", 128), self.assertRaisesRegex(ValueError, "inflated artifact"):
            A.inflate(compressed(b"x" * 129))

    def test_exit_77_is_failure_and_never_a_passed_stage(self):
        with tempfile.TemporaryDirectory() as d:
            evidence = Path(d)
            record = {"stages": []}
            with mock.patch.object(A.subprocess, "run", return_value=SimpleNamespace(returncode=77)), \
                    self.assertRaisesRegex(ValueError, "77 is not verified"):
                A.measured_stage("published-check", ["not-executed"], evidence, record)
            saved = A.read_json((evidence / "run.json").read_bytes())
            self.assertEqual(saved["stages"][0]["status"], "FAILED")
            self.assertEqual(saved["stages"][0]["exitCode"], 77)

    def test_success_requires_resource_evidence(self):
        with tempfile.TemporaryDirectory() as d:
            evidence = Path(d)
            A.write_json(evidence / "audit.metrics.json", dict(maxRssKiB=1234, elapsedSeconds=0.1, exitCode=0))
            record = {"stages": []}
            with mock.patch.object(A.subprocess, "run", return_value=SimpleNamespace(returncode=0)):
                A.measured_stage("audit", ["not-executed"], evidence, record)
            self.assertEqual(record["stages"][0]["metrics"]["maxRssKiB"], 1234)

    def test_failed_build_never_creates_candidate(self):
        with tempfile.TemporaryDirectory() as d:
            output = Path(d) / "output"
            commit = "1" * 40
            def git_output(command, **kwargs):
                return commit + "\n" if command[1] == "rev-parse" else ""
            with mock.patch.object(A.subprocess, "check_output", side_effect=git_output), \
                    mock.patch.object(A, "runtime_pin", return_value={}), \
                    mock.patch.object(A, "runner_resources", return_value={}), \
                    mock.patch.object(A, "source_pin", return_value={}), \
                    mock.patch.object(A, "measured_stage", side_effect=ValueError("export failed")), \
                    self.assertRaisesRegex(ValueError, "export failed"):
                A.build(SimpleNamespace(source_sha=commit, workflow_sha=commit, output=output))
            self.assertEqual(A.read_json((output / "evidence/run.json").read_bytes())["status"], "FAILED")
            self.assertFalse((output / "candidate").exists())

    def test_source_selection_rejects_shell_syntax_before_any_git_call(self):
        with mock.patch.object(A.subprocess, "check_output") as git, self.assertRaisesRegex(ValueError, "commit pin"):
            A.build(SimpleNamespace(source_sha="$(touch unexpected)", workflow_sha="1" * 40, output=Path("unused")))
        git.assert_not_called()


class WorkflowContract(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.workflow = yaml.load((A.ROOT / ".github/workflows/map-artifact.yml").read_text(), Loader=yaml.BaseLoader)

    def test_manual_single_job_dedicated_runner_and_read_only_token(self):
        self.assertEqual(set(self.workflow["on"]), {"workflow_dispatch"})
        self.assertEqual(self.workflow["permissions"], {"contents": "read"})
        self.assertEqual(set(self.workflow["jobs"]), {"full-bake"})
        job = self.workflow["jobs"]["full-bake"]
        self.assertEqual(job["runs-on"], ["self-hosted", "Linux", "X64", "map-artifact"])
        self.assertEqual(self.workflow["concurrency"]["cancel-in-progress"], "false")
        self.assertEqual(job["timeout-minutes"], "60")

    def test_actions_python_and_threads_are_pinned(self):
        job = self.workflow["jobs"]["full-bake"]
        for step in job["steps"]:
            if "uses" in step:
                self.assertRegex(step["uses"], r"^actions/(checkout|setup-python|upload-artifact)@[0-9a-f]{40}$")
        self.assertEqual(next(s for s in job["steps"] if s.get("uses", "").startswith("actions/setup-python"))["with"]["python-version"], "3.12.10")
        for name in A.THREADS:
            self.assertEqual(job["env"][name], "1")
        self.assertEqual(job["env"]["PYTHONHASHSEED"], "0")

    def test_source_is_env_passed_and_matches_workflow_before_checkout(self):
        steps = self.workflow["jobs"]["full-bake"]["steps"]
        self.assertIn('[[ "$SOURCE_SHA" == "$WORKFLOW_SHA" ]]', steps[0]["run"])
        checkout = steps[1]
        self.assertEqual(checkout["with"]["persist-credentials"], "false")
        self.assertEqual(checkout["with"]["ref"], "${{ inputs.source_sha }}")
        for step in steps:
            self.assertNotIn("${{ inputs.", step.get("run", ""))

    def test_failure_artifact_is_distinct_and_contains_no_candidate(self):
        uploads = [s for s in self.workflow["jobs"]["full-bake"]["steps"] if s.get("uses", "").startswith("actions/upload-artifact")]
        candidate, diagnostic = uploads
        self.assertEqual(candidate["if"], "success() && steps.build.outcome == 'success'")
        self.assertIn("map-artifact-candidate-", candidate["with"]["name"])
        self.assertIn("/candidate/", candidate["with"]["path"])
        self.assertIn("/evidence/", candidate["with"]["path"])
        self.assertEqual(diagnostic["if"], "always() && steps.build.outcome != 'success'")
        self.assertIn("map-artifact-diagnostic-", diagnostic["with"]["name"])
        self.assertNotIn("candidate", diagnostic["with"]["path"])
        self.assertTrue(diagnostic["with"]["path"].endswith("/evidence/"))
        for step in self.workflow["jobs"]["full-bake"]["steps"]:
            self.assertNotRegex(step.get("run", ""), r"docker|gcloud|kubectl|deploy\.yml|TOPDOWN_BAKE_ID")


if __name__ == "__main__":
    unittest.main()
