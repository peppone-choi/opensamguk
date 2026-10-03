"""Cheap artifact structure/publication guards. No product bake or image/VM access."""
from copy import deepcopy
import gzip
import json
from pathlib import Path
import re
import struct
import subprocess
import os
import tempfile
from types import SimpleNamespace
import unittest
from unittest import mock

import yaml

from tools.map import build_topdown_artifact as A
from tools.map import export_metadata as E


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
            passes=[dict(cityId=1, orientation="EW", gateCells=[[1, 1]], wallCells=[[0, 1], [2, 1]])],
            # Same public row schema as bake_topdown_map.pass_endpoint_checks().
            passEndpointChecks=[
                dict(cityId=1, orientation="EW", wallEnd=[0, 1], next=[0, 0], terrainClass="M",
                     ground=8, relief=2, riverWidth=0, road=False, accepted=True, side="-1,0"),
                dict(cityId=1, orientation="EW", wallEnd=[2, 1], next=[2, 2], terrainClass="W",
                     ground=0, relief=0, riverWidth=3, road=False, accepted=True, side="1,0")],
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

    def test_valid_hash_display_markers_fail_and_provenance_remains_classified(self):
        self.places["cities"][0]["sourceName"] = "원천#1"
        self.update_places()
        audited = A.audit_bundle(self.bundle)
        self.assertEqual(len(audited["placesDisplayAudit"]["provenanceHashHits"]), 1)
        for group, field in [("cities", "name"), ("counties", "name"), ("commanderies", "name"), ("ju", "name"), ("labels", "text")]:
            with self.subTest(group=group):
                previous = self.places[group][0][field]
                self.places[group][0][field] = previous + "#1"
                self.update_places()
                with self.assertRaisesRegex(ValueError, "places display #"):
                    A.audit_bundle(self.bundle)
                self.places[group][0][field] = previous
        self.update_places()

    def test_pass_endpoint_terrain_is_metadata_and_unknown_text_still_fails(self):
        self.assertTrue(self.places["passes"])
        self.assertEqual({row["terrainClass"] for row in self.places["passEndpointChecks"]}, {"M", "W"})
        audit = self.check()["placesDisplayAudit"]
        self.assertEqual(audit["status"], "PASS")
        self.assertEqual(audit["unclassifiedText"], [])
        self.places["passEndpointChecks"][0]["unknownText"] = "must be classified"
        classified = A.audit_places_display(self.places)
        self.assertEqual(classified["status"], "FAILED")
        self.assertEqual([row["path"] for row in classified["unclassifiedText"]],
                         ["places.passEndpointChecks[0].unknownText"])

    def test_outside_map_pass_endpoint_null_metadata_is_accepted(self):
        self.places["passEndpointChecks"][0].update(next=[0, -1], terrainClass=None,
            ground=None, relief=None, riverWidth=None, accepted=False)
        self.update_places()
        self.assertEqual(self.check()["placesDisplayAudit"]["status"], "PASS")

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
                      inputFingerprint=fingerprint, files=layers, shape=[4, 4],
                      roadEdgesFile=E.write_road_edges(directory, []))
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



class ExportRoadMetadataTest(unittest.TestCase):
    export_fixture = SmallGuards.export_fixture
    @staticmethod
    def edge(count=2):
        trail = [[1, 2]] * count
        return dict(edgeId="fixture:1", status="BUILT", fromProvinceId="a", toProvinceId="b",
                    fromTrail=trail, toTrail=[], cells=trail)

    def test_large_inline_is_red_then_split_restores_identical_geometry_and_pins(self):
        with tempfile.TemporaryDirectory() as d:
            directory = Path(d)
            pin, export = self.export_fixture(directory)
            edges = [self.edge(180000)]
            del export["roadEdgesFile"]
            export["roadEdges"] = edges
            A.write_json(directory / E.MANIFEST_FILE, export)
            inline_bytes = (directory / E.MANIFEST_FILE).stat().st_size
            self.assertGreater(inline_bytes, A.MAX_MANIFEST)
            with self.assertRaisesRegex(ValueError, "map-design-manifest.json bytes=.*cap=2097152"):
                A.expected_identity(pin, directory, {"zlibRuntime": "fixture"})
            del export["roadEdges"]
            export["roadEdgesFile"] = E.write_road_edges(directory, edges)
            A.write_json(directory / E.MANIFEST_FILE, export)
            normalized, blob, hashes = E.load_export_metadata(directory)
            self.assertEqual(normalized["roadEdges"], edges)
            self.assertLessEqual(len(blob), A.MAX_MANIFEST)
            self.assertGreater(export["roadEdgesFile"]["bytes"], A.MAX_MANIFEST)
            self.assertLessEqual(export["roadEdgesFile"]["bytes"], A.MAX_BYTES)
            identity = A.expected_identity(pin, directory, {"zlibRuntime": "fixture"})
            self.assertEqual(identity["inputs"]["export/manifest"], A.sha(blob))
            self.assertEqual(identity["inputs"]["export/roadEdges"], export["roadEdgesFile"]["sha256"])
            self.assertEqual(identity["inputs"]["repo/exportMetadata"], pin["files"][A.SOURCE_PATHS["exportMetadata"]]["sha256"])
            self.assertEqual(identity["inputFingerprint"]["bakeInputs"], identity["inputs"])
            self.assertFalse(A.FILE.fullmatch(E.ROADS_FILE))

    def test_small_legacy_inline_contract_stays_readable(self):
        with tempfile.TemporaryDirectory() as d:
            directory = Path(d)
            _, export = self.export_fixture(directory)
            del export["roadEdgesFile"]
            # Historical consumers only required edgeId/status/cells.
            export["roadEdges"] = [dict(edgeId="old", status="BUILT", cells=[[1, 2]])]
            A.write_json(directory / E.MANIFEST_FILE, export)
            normalized, _, hashes = E.load_export_metadata(directory)
            self.assertEqual(normalized["roadEdges"], export["roadEdges"])
            self.assertNotIn("export/roadEdges", hashes)

    def test_missing_mutated_wrong_size_and_symlink_roads_are_rejected(self):
        for mutation in ("missing", "mutated", "size", "symlink"):
            with self.subTest(mutation=mutation), tempfile.TemporaryDirectory() as d:
                directory = Path(d)
                pin, export = self.export_fixture(directory)
                path = directory / E.ROADS_FILE
                if mutation == "missing": path.unlink()
                if mutation == "mutated": path.write_bytes(path.read_bytes() + b" ")
                if mutation == "size":
                    export["roadEdgesFile"]["bytes"] += 1
                    A.write_json(directory / E.MANIFEST_FILE, export)
                if mutation == "symlink":
                    target = directory / "other.json"
                    target.write_bytes(path.read_bytes()); path.unlink(); path.symlink_to(target)
                with self.assertRaises(ValueError):
                    A.expected_identity(pin, directory, {"zlibRuntime": "fixture"})

    def test_road_file_and_descriptor_cap_are_both_enforced(self):
        with tempfile.TemporaryDirectory() as d:
            directory = Path(d)
            _, export = self.export_fixture(directory)
            cap = export["roadEdgesFile"]["bytes"]
            with self.assertRaisesRegex(ValueError, "byte count"):
                E.load_export_metadata(directory, road_cap=cap-1)
            (directory / E.ROADS_FILE).write_bytes(b" " * (cap+1))
            with self.assertRaisesRegex(ValueError, "map-design-roads.json bytes=.*cap="):
                E.load_export_metadata(directory, road_cap=cap)
            with mock.patch.object(E, "MAX_ROADS", 8), self.assertRaisesRegex(ValueError, "exceeds cap"):
                E.write_road_edges(directory, [self.edge()])

    def test_ambiguous_or_escaping_descriptor_is_rejected(self):
        for mutation in ("inline", "traversal", "extra", "boolBytes", "badSHA"):
            with self.subTest(mutation=mutation), tempfile.TemporaryDirectory() as d:
                directory = Path(d)
                _, export = self.export_fixture(directory)
                if mutation == "inline": export["roadEdges"] = []
                if mutation == "traversal": export["roadEdgesFile"]["file"] = "../map-design-roads.json"
                if mutation == "extra": export["roadEdgesFile"]["url"] = "private"
                if mutation == "boolBytes": export["roadEdgesFile"]["bytes"] = True
                if mutation == "badSHA": export["roadEdgesFile"]["sha256"] = "g" * 64
                A.write_json(directory / E.MANIFEST_FILE, export)
                with self.assertRaises(ValueError): E.load_export_metadata(directory)

    def test_rehashed_invalid_json_schema_and_geometry_are_rejected(self):
        edge = self.edge()
        invalid = [b'{"schemaVersion":1,"schemaVersion":1,"roadEdges":[]}',
                   b'{"schemaVersion":NaN,"roadEdges":[]}',
                   json.dumps(dict(schemaVersion=True, roadEdges=[])).encode(),
                   json.dumps(dict(schemaVersion=1, roadEdges=[], private=1)).encode(),
                   json.dumps(dict(schemaVersion=1, roadEdges=[dict(edge, secret={})])).encode(),
                   json.dumps(dict(schemaVersion=1, roadEdges=[dict(edge, cells=[])])).encode(),
                   json.dumps(dict(schemaVersion=1, roadEdges=[edge, edge])).encode()]
        for data in invalid:
            with self.subTest(data=data[:60]), tempfile.TemporaryDirectory() as d:
                directory = Path(d)
                _, export = self.export_fixture(directory)
                (directory / E.ROADS_FILE).write_bytes(data)
                export["roadEdgesFile"].update(bytes=len(data), sha256=A.sha(data))
                A.write_json(directory / E.MANIFEST_FILE, export)
                with self.assertRaises(ValueError): E.load_export_metadata(directory)

    def test_actual_metadata_bytes_and_hashes_are_retained_in_diagnostics(self):
        with tempfile.TemporaryDirectory() as d:
            directory = Path(d)
            self.export_fixture(directory)
            evidence = directory / "evidence"
            A.retain_export_metadata(directory, evidence)
            inventory = A.read_json((evidence / "export-metadata.json").read_bytes())
            for name in (E.MANIFEST_FILE, E.ROADS_FILE):
                self.assertEqual((evidence / name).read_bytes(), (directory / name).read_bytes())
                self.assertEqual(inventory[name]["sha256"], A.sha((directory / name).read_bytes()))
                self.assertEqual(inventory[name]["bytes"], (directory / name).stat().st_size)

    def test_published_manifest_cap_is_unchanged(self):
        self.assertEqual(A.MAX_MANIFEST, 2*1024*1024)
        self.assertEqual(A.MAX_BYTES, 16*1024*1024)
        with tempfile.TemporaryDirectory() as d:
            path = Path(d) / "manifest.json"
            with path.open("wb") as f: f.truncate(A.MAX_MANIFEST+1)
            with self.assertRaisesRegex(ValueError, "manifest.json bytes=2097153 cap=2097152"):
                A.read_regular(Path(d), "manifest.json", A.MAX_MANIFEST)


class HostedResourceAndPlacesTest(unittest.TestCase):
    def test_monitor_records_sampled_peaks_and_stops_without_killing_processes(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "resources"
            args = SimpleNamespace(output=output, build_output=Path(directory) / "build", interval=.01, max_seconds=1)
            def sample(_):
                n = sample.calls
                sample.calls += 1
                if n == 1:
                    (output / "stop").touch()
                return dict(observedAt="fixed", diskFreeBytes=20 if n == 0 else 0,
                            diskUsedBytes=40 if n == 0 else 60, memoryKiB={"MemAvailable": 30-n},
                            ownedOutputBytes={"bake": 3+n, "bundles": 4+n})
            sample.calls = 0
            with mock.patch.object(A, "resource_sample", side_effect=sample):
                A.observe_resources(args)
            summary = A.read_json((output / "summary.json").read_bytes())
            self.assertEqual(summary["status"], "STOPPED")
            self.assertEqual(summary["samples"], 2)
            self.assertEqual(summary["diskFreeMinimumBytes"], 0)
            self.assertEqual(summary["diskUsedMaximumBytes"], 60)
            self.assertEqual(summary["ownedOutputMaximumBytes"], 9)
            self.assertEqual(summary["memoryAvailableMinimumKiB"], 29)
            self.assertIn("sampled lower bound", summary["scope"])
            self.assertEqual(len((output / "samples.jsonl").read_text().splitlines()), 2)

    def test_monitor_timeout_is_failure_with_diagnostic_and_no_candidate(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "resources"
            args = SimpleNamespace(output=output, build_output=Path(directory) / "build", interval=.001, max_seconds=.001)
            with self.assertRaisesRegex(ValueError, "timed out"):
                A.observe_resources(args)
            self.assertEqual(A.read_json((output / "summary.json").read_bytes())["status"], "FAILED")
            self.assertFalse(args.build_output.exists())

    def test_source_city_ids_geometry_and_presets_are_checked_exhaustively(self):
        world = {"cities": [dict(id=7, name="원천", level=3, provinceId=0, spatialProvinceId="p", meta={"displayName":"표시", "isSeat":True})]}
        tiles = {"cities": [dict(id="p", row=4, col=9)]}
        moves = {"placements": [dict(cityId=7, to=[5,8])]}
        city = dict(id=7, name="표시", sourceName="원천", level=3, provinceIndex=0, cell=[8,5], isSeat=True)
        result = A.audit_places_ids({"cities":[city]}, tiles, world, moves)
        self.assertTrue(result["allCityIdsVerified"])
        for mutation in [[], [city, city], [{**city, "id":9}], [{**city, "cell":[9,4]}], [{**city, "provinceIndex":1}], [{**city, "isSeat":1}]]:
            with self.subTest(mutation=mutation), self.assertRaises(ValueError):
                A.audit_places_ids({"cities":mutation}, tiles, world, moves)
        with self.assertRaisesRegex(ValueError, "duplicate"):
            A.audit_places_ids({"cities":[city]}, tiles, {"cities":world["cities"]*2}, moves)

    def test_resource_preflight_thresholds_remain_unchanged(self):
        with mock.patch.object(A.platform, "system", return_value="Linux"), \
             mock.patch.object(A.platform, "machine", return_value="x86_64"), \
             mock.patch.object(A.os, "cpu_count", return_value=4), \
             mock.patch.object(Path, "read_text", return_value="MemTotal: 15728640 kB\nMemAvailable: 12582912 kB\n"), \
             mock.patch.object(A.shutil, "disk_usage", return_value=SimpleNamespace(free=10*1024**3-1)):
            with self.assertRaisesRegex(ValueError, "10GiB"):
                A.runner_resources(Path("."))
        with mock.patch.object(A.platform, "system", return_value="Linux"), \
             mock.patch.object(A.platform, "machine", return_value="x86_64"), \
             mock.patch.object(A.os, "cpu_count", return_value=4), \
             mock.patch.object(Path, "read_text", return_value="MemTotal: 15728639 kB\nMemAvailable: 12582912 kB\n"):
            with self.assertRaisesRegex(ValueError, "nominal 16GiB"):
                A.runner_resources(Path("."))


class WorkflowContract(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.workflow = yaml.load((A.ROOT / ".github/workflows/map-artifact.yml").read_text(), Loader=yaml.BaseLoader)

    def test_manual_single_job_hosted_runner_and_read_only_token(self):
        self.assertEqual(set(self.workflow["on"]), {"workflow_dispatch"})
        self.assertEqual(self.workflow["permissions"], {"contents": "read"})
        self.assertEqual(set(self.workflow["jobs"]), {"full-bake"})
        job = self.workflow["jobs"]["full-bake"]
        self.assertEqual(job["runs-on"], "ubuntu-24.04")
        self.assertEqual(self.workflow["concurrency"]["cancel-in-progress"], "false")
        self.assertEqual(job["timeout-minutes"], "360")

    def test_workflow_cannot_skip_guard_or_mask_failure(self):
        self.assertNotIn("defaults", self.workflow)
        job = self.workflow["jobs"]["full-bake"]
        self.assertNotIn("continue-on-error", job)
        self.assertNotIn("if", job)
        for step in job["steps"]:
            self.assertNotIn("continue-on-error", step)
            if "run" in step:
                self.assertIn(step.get("shell", "bash"), ("bash",))
                self.assertNotRegex(step["run"], r"\|\|\s*(true|:)|set\s+\+e")
        self.assertNotIn("if", job["steps"][0])

    def test_actual_source_guard_rejects_misdispatch_before_checkout(self):
        guard = self.workflow["jobs"]["full-bake"]["steps"][0]["run"]
        with tempfile.TemporaryDirectory() as directory:
            cases = [("a" * 40, "a" * 40, True),
                     ("a" * 40, "b" * 40, False),
                     ("main", "a" * 40, False),
                     ("A" * 40, "a" * 40, False),
                     ("a" * 40 + ";touch injected", "a" * 40, False)]
            for source, workflow, accepted in cases:
                with self.subTest(source=source, workflow=workflow):
                    env = os.environ.copy()
                    env.update(SOURCE_SHA=source, WORKFLOW_SHA=workflow)
                    result = subprocess.run(["bash", "-e", "-o", "pipefail", "-c", guard],
                                            cwd=directory, env=env, capture_output=True, text=True)
                    self.assertEqual(result.returncode == 0, accepted)
            self.assertFalse((Path(directory) / "injected").exists())

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
        checkout = next(s for s in steps if s.get("uses", "").startswith("actions/checkout"))
        self.assertEqual(checkout["with"]["persist-credentials"], "false")
        self.assertEqual(checkout["with"]["ref"], "${{ inputs.source_sha }}")
        for step in steps:
            self.assertNotIn("${{ inputs.", step.get("run", ""))

    def test_monitor_covers_dependencies_build_and_finishes_before_candidate_upload(self):
        steps = self.workflow["jobs"]["full-bake"]["steps"]
        names = [s.get("name", "") for s in steps]
        self.assertLess(names.index("Observe dependency and build resources"), names.index("Install pinned artifact dependencies"))
        self.assertLess(names.index("Build and verify a complete artifact candidate"), names.index("Finish owned resource observation"))
        self.assertLess(names.index("Finish owned resource observation"), names.index("Upload verified candidate and evidence"))
        finish = next(s for s in steps if s.get("id") == "resources")
        self.assertEqual(finish["if"], "always()")
        self.assertIn('touch "$resources/stop"', finish["run"])
        self.assertIn('cp -a "$resources" "$evidence/resources"', finish["run"])
        self.assertNotRegex(finish["run"], r"kill|rm -r")

    def test_failure_artifact_is_distinct_and_contains_no_candidate(self):
        uploads = [s for s in self.workflow["jobs"]["full-bake"]["steps"] if s.get("uses", "").startswith("actions/upload-artifact")]
        candidate, diagnostic = uploads
        self.assertEqual(candidate["if"], "success() && steps.build.outcome == 'success' && steps.resources.outcome == 'success'")
        self.assertIn("map-artifact-candidate-", candidate["with"]["name"])
        self.assertIn("/candidate/", candidate["with"]["path"])
        self.assertIn("/evidence/", candidate["with"]["path"])
        self.assertEqual(diagnostic["if"], "always() && (steps.build.outcome != 'success' || steps.resources.outcome != 'success')")
        self.assertIn("map-artifact-diagnostic-", diagnostic["with"]["name"])
        self.assertNotIn("candidate", diagnostic["with"]["path"])
        self.assertIn("/evidence/", diagnostic["with"]["path"])
        for step in self.workflow["jobs"]["full-bake"]["steps"]:
            self.assertNotRegex(step.get("run", ""), r"docker|gcloud|kubectl|deploy\.yml|TOPDOWN_BAKE_ID")


if __name__ == "__main__":
    unittest.main()
