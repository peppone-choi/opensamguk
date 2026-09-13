import copy
import hashlib
import json
import unittest
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import han_world_artifact_sets as subject

ROOT = Path(__file__).resolve().parents[3]

class ArtifactSetsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.directory = ROOT / 'data/map/han-world-artifacts-v1'
        cls.raw = (cls.directory / 'catalog.json').read_bytes()
        cls.catalog = json.loads(cls.raw)

    def read_blob(self, path):
        return (self.directory / path).read_bytes()

    def validate(self, catalog, reader=None):
        raw = subject.encode(catalog)
        return subject.validate(raw, reader or self.read_blob, hashlib.sha256(raw).hexdigest())

    def test_approved_catalog_preserves_existing_identities(self):
        versions = subject.validate(self.raw, self.read_blob, subject.APPROVED_CATALOG_SHA256)
        self.assertEqual([len(v) for v in versions.values()], [832, 835])
        self.assertLessEqual(set(versions['han-world-v3-832']), set(versions['han-world-v3-835']))

    def test_unapproved_wrapper_rejected(self):
        with self.assertRaisesRegex(ValueError, 'catalog hash'):
            subject.validate(self.raw + b' ', self.read_blob, subject.APPROVED_CATALOG_SHA256)

    def test_missing_and_duplicate_paths_rejected(self):
        for duplicate in (False, True):
            catalog = copy.deepcopy(self.catalog)
            files = catalog['variants'][0]['files']
            if duplicate: files[-1] = files[0]
            else: files.pop()
            with self.assertRaisesRegex(ValueError, 'path set'):
                self.validate(catalog)

    def test_blob_corruption_rejected(self):
        with self.assertRaisesRegex(ValueError, 'blob hash'):
            self.validate(self.catalog, lambda path: self.read_blob(path) + b' ')

    def test_other_variant_world_rejected_by_inner_manifest(self):
        catalog = copy.deepcopy(self.catalog)
        first, second = catalog['variants']
        incoming = next(f for f in second['files'] if f['path'] == subject.WORLD)
        first['files'] = [incoming if f['path'] == subject.WORLD else f for f in first['files']]
        with self.assertRaisesRegex(ValueError, 'world JSON pin'):
            self.validate(catalog)

    def test_same_count_changed_identity_rejected(self):
        catalog = copy.deepcopy(self.catalog)
        variant = catalog['variants'][0]
        override = {}
        def replace(path, transform):
            entry = next(f for f in variant['files'] if f['path'] == path)
            doc = json.loads(self.read_blob(entry['blob']))
            transform(doc)
            data = subject.encode(doc)
            entry.update(sha256=hashlib.sha256(data).hexdigest(), bytes=len(data))
            entry['blob'] = 'blobs/' + entry['sha256'] + '.json'
            override[entry['blob']] = data
            return entry['sha256']
        world_hash = replace(subject.WORLD, lambda d: d['cities'][0].update(id=9999))
        replace(subject.WORLD_MANIFEST, lambda d: d['outputs'].update(worldJsonSha256=world_hash))
        with self.assertRaisesRegex(ValueError, 'identity'):
            self.validate(catalog, lambda path: override[path] if path in override else self.read_blob(path))

if __name__ == '__main__': unittest.main()
