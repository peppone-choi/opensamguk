"""Tiny synthetic PNGs only: no paid APIs, imported artwork or gameplay QA."""
import copy
import hashlib
import json
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from PIL import Image
from tools.assets.artpack.cli import build_pack, check_pack, check_chroma, read_json
from tools.assets.artpack.manifest import validate_manifest, ARTIFACT_ID, ACTIONS, EDGE_BITS

ROOT = Path(__file__).resolve().parents[4]


def fixture(root, ppb=1):
    """Write minimal pre-authored RGBA sources with off-centre unit feet."""
    root.mkdir(parents=True, exist_ok=True)
    Image.new('RGBA', (32 * ppb, 16 * ppb), (80, 90, 60, 128)).save(root / 'ground.png')
    image = Image.new('RGBA', (7, 11), (0, 0, 0, 0))
    image.putpixel((2, 8), (130, 60, 20, 120))
    image.save(root / 'body.png')
    image = Image.new('RGBA', (7, 11), (0, 0, 0, 0))
    image.putpixel((2, 8), (180, 180, 180, 120))
    image.save(root / 'mask.png')
    ground = dict(file='ground.png', ax=16 * ppb, ay=8 * ppb)
    body = dict(file='body.png', ax=2.5, ay=9)
    mask = dict(file='mask.png', ax=2.5, ay=9)
    actions = {}
    for action, count in ACTIONS.items():
        actions[action] = dict(frames=count, directions={
            f: dict(frames=[copy.deepcopy(body) for _ in range(count)], masks=[copy.deepcopy(mask) for _ in range(count)])
            for f in ('sw', 'nw')})
    spec = dict(schemaVersion=1, artifactId=ARTIFACT_ID, packId='synthetic-a5', pixelsPerBoardPx=ppb,
                shadow='baked', terrain={t: dict(ground=[copy.deepcopy(ground)], tallByEdges=[None]*16) for t in 'PFMRW'},
                transitions={t: [None] + [copy.deepcopy(ground) for _ in range(15)] for t in 'RFM'},
                units={'spear': dict(actions=actions)},
                provenance=[dict(source='procedural', note='A5 synthetic test; not production artwork')])
    (root / 'spec.json').write_text(json.dumps(spec), encoding='utf-8')
    return spec


def hashes(root):
    return {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(root.iterdir())}


class ArtpackTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.sources = self.root / 'src'
        self.spec = fixture(self.sources)
        self.path = self.sources / 'spec.json'
        self.output = self.root / 'out'

    def save(self):
        self.path.write_text(json.dumps(self.spec), encoding='utf-8')

    def build(self, size=64):
        return build_pack(self.path, self.output, size)

    def test_deterministic_bytes_preserve_pixels_and_feet(self):
        first = self.build()
        self.spec = dict(reversed(list(self.spec.items())))
        self.save()
        other = self.root / 'different-location'
        build_pack(self.path, other, 64)
        self.assertEqual(hashes(self.output), hashes(other))
        self.assertEqual(check_pack(self.output / 'manifest.json'), first)
        for kind, file in [('frames', 'body.png'), ('masks', 'mask.png')]:
            f = first['units']['spear']['actions']['idle']['directions']['sw'][kind][0]
            self.assertEqual((f['ax'], f['ay']), (2.5, 9))
            with Image.open(self.output / first['atlases'][f['atlas']]['file']) as atlas, Image.open(self.sources / file) as src:
                self.assertEqual(atlas.crop((f['x'], f['y'], f['x']+f['w'], f['y']+f['h'])).tobytes(), src.tobytes())
        self.assertEqual(EDGE_BITS, dict(NE=1, SE=2, SW=4, NW=8))

    def test_all_ppb_and_action_limits(self):
        for ppb in range(1, 5):
            with self.subTest(ppb=ppb):
                folder = self.root / str(ppb)
                fixture(folder, ppb)
                m = build_pack(folder / 'spec.json', self.root / f'out-{ppb}', 128)
                self.assertFalse(validate_manifest(m))
        for action, limit in ACTIONS.items():
            with self.subTest(action=action):
                bad = copy.deepcopy(self.spec)
                bad['units']['spear']['actions'][action]['frames'] = limit + 1
                self.path.write_text(json.dumps(bad))
                with self.assertRaises(ValueError):
                    self.build()

    def test_shelf_wrap_multiatlas_and_maximum_boundary(self):
        m = self.build(32)
        self.assertGreater(len(m['atlases']), 1)
        for atlas in m['atlases']:
            self.assertLessEqual(max(atlas['width'], atlas['height']), 32)
        # Exactly 2048 is legal, 2049 isn't; no scaling/cropping is performed.
        Image.new('RGBA', (2048, 1)).save(self.sources / 'wide.png')
        self.spec['terrain']['W']['tall'] = [dict(file='wide.png', ax=2048, ay=1)]
        self.save()
        large = build_pack(self.path, self.root / 'large')
        self.assertEqual(max(a['width'] for a in large['atlases']), 2048)
        Image.new('RGBA', (2049, 1)).save(self.sources / 'wide.png')
        with self.assertRaisesRegex(ValueError, '1–2048'):
            build_pack(self.path, self.root / 'oversize')
        self.assertFalse((self.root / 'oversize').exists())

    def test_bad_manifest_contract_rejected(self):
        good = self.build()
        mutations = [
            lambda m: m.update(schemaVersion=2), lambda m: m.update(artifactId='other'),
            lambda m: m.update(pixelsPerBoardPx=0), lambda m: m.update(pixelsPerBoardPx=5),
            lambda m: m.update(shadow='separate'), lambda m: m['atlases'][0].update(width=2049),
            lambda m: m['terrain'].pop('W'), lambda m: m['terrain']['P']['ground'][0].update(x=9999),
            lambda m: m['terrain']['P']['ground'][0].update(atlas=-1),
            lambda m: m['terrain']['P']['ground'][0].update(ax=float('nan')),
            lambda m: m['terrain']['P']['ground'][0].update(ay=-1),
            lambda m: m['terrain']['P'].update(tall='not-array'),
            lambda m: m['terrain']['P'].update(tallByEdges=[None]*15),
            lambda m: m['transitions'].update(W=[None]*16),
            lambda m: m['transitions']['R'].__setitem__(0, m['terrain']['P']['ground'][0]),
            lambda m: m['units']['spear']['actions'].pop('idle'),
            lambda m: m['units']['spear']['actions']['idle']['directions'].update(sw={'mirror':'nw'}),
            lambda m: m['units']['spear']['actions']['idle']['directions'].update(ne={'mirror':'ne'}),
            lambda m: m['units']['spear']['actions']['idle']['directions']['sw'].update(masks=[]),
            lambda m: m['units']['spear']['actions']['idle']['directions']['sw']['masks'][0].update(w=1),
            lambda m: m.update(provenance=[dict(source='generated', note='missing required attribution')]),
            lambda m: m.update(units=[]), lambda m: m.pop('units'), lambda m: m.update(units=None),
            lambda m: m['units']['spear']['actions']['idle']['directions'].update(nw={'mirror':'sw'}),
            lambda m: m.update(provenance=3),
        ]
        for index, change in enumerate(mutations):
            with self.subTest(mutation=index):
                bad = copy.deepcopy(good)
                change(bad)
                self.assertTrue(validate_manifest(bad))
        empty = copy.deepcopy(good)
        empty['units'] = {}
        self.assertFalse(validate_manifest(empty))
        for malformed in (None, [], 3, {'terrain':None, 'atlases':[None], 'units':{'spear':None}}):
            self.assertTrue(validate_manifest(malformed))

    def test_no_partial_output_or_input_overwrite(self):
        self.spec['units']['spear']['actions']['idle']['directions']['sw']['masks'].pop()
        self.save()
        with self.assertRaises(ValueError):
            self.build()
        self.assertFalse(self.output.exists())
        self.assertFalse(list(self.root.glob('.artpack-*')))
        fixture(self.sources)
        self.build()
        before = hashes(self.output)
        with self.assertRaisesRegex(ValueError, 'already exists'):
            self.build()
        self.assertEqual(before, hashes(self.output))

    def test_bad_source_paths_masks_and_formats(self):
        for filename in ('../outside.png', '/absolute.png', 'body.jpg'):
            self.spec['terrain']['P']['ground'][0]['file'] = filename
            self.save()
            with self.assertRaises(ValueError):
                self.build()
        self.spec = fixture(self.sources)
        outside = self.root / 'outside.png'
        Image.new('RGBA', (32,16)).save(outside)
        (self.sources / 'escape.png').symlink_to(outside)
        self.spec['terrain']['P']['ground'][0]['file'] = 'escape.png'
        self.save()
        with self.assertRaises(ValueError):
            self.build()
        self.spec = fixture(self.sources)
        Image.new('RGBA', (7,11), (255,0,0,255)).save(self.sources / 'mask.png')
        with self.assertRaisesRegex(ValueError, 'grayscale'):
            self.build()
        fixture(self.sources)
        Image.new('RGB', (7,11)).save(self.sources / 'body.png')
        with self.assertRaisesRegex(ValueError, 'RGBA'):
            self.build()

    def test_pack_hash_dimensions_mask_pixels_and_json_rejection(self):
        m = self.build()
        atlas = self.output / m['atlases'][0]['file']
        atlas.write_bytes(atlas.read_bytes() + b'tampered')
        with self.assertRaisesRegex(ValueError, 'sha256'):
            check_pack(self.output / 'manifest.json')
        for text in ('{"x":1,"x":2}', '{"x":NaN}'):
            self.path.write_text(text)
            with self.assertRaises(ValueError):
                read_json(self.path)
        m['atlases'][0].pop('sha256')
        m['atlases'][0]['width'] += 1
        (self.output / 'manifest.json').write_text(json.dumps(m))
        with self.assertRaisesRegex(ValueError, 'dimensions'):
            check_pack(self.output / 'manifest.json')

    def test_cli_build_check_and_rejection_exit_codes(self):
        self.spec['provenance'] = [dict(source='generated', note='synthetic metadata only',
                                       model='fixture-model', prompt='fixture-prompt', createdAt='2026-10-16T00:00:00Z')]
        self.save()
        command = [sys.executable, '-m', 'tools.assets.artpack']
        for arguments in (['build', str(self.path), str(self.output), '--atlas-size', '64'],
                          ['check', str(self.output / 'manifest.json')]):
            done = subprocess.run(command + arguments, cwd=ROOT, capture_output=True, text=True, timeout=30)
            self.assertEqual(done.returncode, 0, done.stderr)
        m = read_json(self.output / 'manifest.json')
        self.assertEqual(m['provenance'], self.spec['provenance'])
        m['shadow'] = 'not-baked'
        (self.output / 'manifest.json').write_text(json.dumps(m))
        done = subprocess.run(command + ['check', str(self.output / 'manifest.json')], cwd=ROOT,
                              capture_output=True, text=True, timeout=30)
        self.assertEqual(done.returncode, 1)
        self.assertIn('shadow', done.stderr)

    def test_shared_chroma_inspection_no_edits(self):
        raw = self.root / 'raw.png'
        Image.new('RGB', (8,8), (255,0,255)).save(raw)
        before = raw.read_bytes()
        check_chroma(raw)
        self.assertEqual(raw.read_bytes(), before)
        Image.new('RGB', (8,8), (0,0,0)).save(raw)
        with self.assertRaises(ValueError):
            check_chroma(raw)
        Image.new('RGB', (2,2)).save(raw)
        with self.assertRaises(ValueError):
            check_chroma(raw)

    def test_present_renderer_contract_requires_explicit_recomparison(self):
        current = ROOT / 'web/shared/src/battle/artpack/manifest.ts'
        pinned = Path(__file__).with_name('fixtures') / 'a2-manifest.ts'
        if current.exists():
            self.assertEqual(current.read_bytes(), pinned.read_bytes(),
                             'A2 contract changed: explicitly re-compare validator, pipeline and fixture; do not silently re-pin')
        # Main may precede A2: this is not evidence of renderer integration.
        self.assertTrue(pinned.is_file())

    def test_pinned_a2_validator_and_renderer_frame_resolver(self):
        self.build()
        self.assertIsNotNone(shutil.which('node'), 'Node >=22.15 required; compatibility must not silently skip')
        done = subprocess.run(['node', str(Path(__file__).with_name('verify_a2.mjs')), str(self.output / 'manifest.json')], capture_output=True, text=True, timeout=30)
        self.assertEqual(done.returncode, 0, done.stdout + done.stderr)
        self.assertIn('A2 validator/resolver PASS', done.stdout)


if __name__ == '__main__':
    unittest.main()
