"""A published release cannot be rewritten when mutable generation inputs change."""
import hashlib
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from tools.map import build_province_world_bundle as B


class ReleaseImmutabilityTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.prior = self.root / 'prior'
        self.prior.mkdir()
        (self.prior / 'catalog.json').write_text(json.dumps(dict(logicalMapName='han-world-v3',
            files=[dict(path='data/map/han-tiles.json')])))
        self.tile = self.root / 'data/map/province-tiles.json'
        self.tile.parent.mkdir(parents=True)
        self.tile.write_bytes(b'{"identity":"unchanged"}\n')
        self.source = self.root / 'constants/ArchiveCityConst.kt'
        self.source.parent.mkdir()
        self.source.write_text('object ArchiveCityConst { val ids = listOf(1, 2) }\n')
        self.snapshot = self.root / 'constants/ProvinceCityConst.kt'
        self.bundle = self.root / 'current'
        self.loader = self.root / 'Loader.kt'
        patches = patch.multiple(B, ROOT=self.root, PRIOR=self.prior, BUNDLE=self.bundle,
            LOADER=self.loader, CONSTANTS=(('constants/ArchiveCityConst.kt', 'constants/ProvinceCityConst.kt'),))
        patches.start()
        self.addCleanup(patches.stop)
        self.assertEqual(0, self.run_mode('--write'))
        digest = hashlib.sha256((self.bundle / 'catalog.json').read_bytes()).hexdigest()
        self.loader.write_text(f'const val CATALOG_SHA256 = "{digest}"\n')

    def run_mode(self, mode):
        with patch('sys.argv', ['build_province_world_bundle', mode]):
            return B.main()

    def preserved_files(self):
        return {str(p.relative_to(self.root)): p.read_bytes()
                for p in self.bundle.rglob('*') if p.is_file()} | {
                    str(self.snapshot.relative_to(self.root)): self.snapshot.read_bytes()}

    def test_current_sources_are_checked_and_repeated_write_is_identical(self):
        before = self.preserved_files()
        self.assertEqual(0, self.run_mode('--write'))
        self.assertEqual(0, self.run_mode('--check'))
        self.assertEqual(before, self.preserved_files())

    def test_changed_current_source_requires_a_new_release_without_any_rewrite(self):
        before = self.preserved_files()
        self.tile.write_bytes(b'{"identity":"changed"}\n')
        self.assertEqual(1, self.run_mode('--check'))
        self.assertEqual(1, self.run_mode('--write'))
        self.assertEqual(before, self.preserved_files())

    def test_changed_constant_source_cannot_overwrite_the_frozen_snapshot(self):
        before = self.preserved_files()
        self.source.write_text('object ArchiveCityConst { val ids = listOf(1, 3) }\n')
        self.assertEqual(1, self.run_mode('--write'))
        self.assertEqual(before, self.preserved_files())
