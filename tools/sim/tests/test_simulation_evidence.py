import hashlib
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "tools/sim"))
import simulation_evidence as E


class SimulationEvidenceTest(unittest.TestCase):
    def test_both_clis_bind_results_to_exact_inputs_and_revision(self):
        for name, inputs in (
            ('march_tempo', ['data/map/han-tiles.json']),
            ('siege_supply', ['data/map/han-tiles.json',
                              'data/curated/han/march-tempo-targets-v1.json',
                              'data/curated/han/county-economy-inputs-v1.json']),
        ):
            with self.subTest(simulator=name):
                command = [sys.executable, str(ROOT / f'tools/sim/{name}.py'), '--evidence']
                first = subprocess.run(command, cwd=ROOT, capture_output=True, text=True)
                self.assertEqual(first.returncode, 0, first.stderr)
                second = subprocess.run(command, cwd=ROOT, capture_output=True, text=True)
                self.assertEqual(second.returncode, 0, second.stderr)
                self.assertEqual(first.stdout, second.stdout)
                evidence = json.loads(first.stdout)
                self.assertEqual(evidence['status'], 'EXPLORATORY')
                self.assertFalse(evidence['s2GatePassed'])
                head = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()
                self.assertEqual(evidence['sourceRevision'], head)
                paths = inputs + ['tools/sim/march_tempo.py', 'tools/sim/simulation_evidence.py']
                if name == 'siege_supply':
                    paths.append('tools/sim/siege_supply.py')
                self.assertEqual(set(evidence['sourceFiles']), set(paths))
                for path in paths:
                    self.assertEqual(evidence['sourceFiles'][path], hashlib.sha256((ROOT / path).read_bytes()).hexdigest())
                canonical = json.dumps(evidence['result'], ensure_ascii=False, sort_keys=True, separators=(',', ':'), allow_nan=False)
                self.assertEqual(evidence['resultSha256'], hashlib.sha256(canonical.encode()).hexdigest())
                legacy = subprocess.check_output(command[:-1] + ['--json'], cwd=ROOT, text=True)
                self.assertEqual(evidence['result'], json.loads(legacy))


class EvidenceIntegrityTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.path = self.root / 'input.json'
        self.path.write_text('{"value": 1}\n')
        subprocess.run(['git', 'init', '-q'], cwd=self.root, check=True)
        subprocess.run(['git', 'add', 'input.json'], cwd=self.root, check=True)
        subprocess.run(['git', '-c', 'user.name=Test', '-c', 'user.email=test@example.invalid',
                        '-c', 'commit.gpgsign=false', 'commit', '-qm', 'fixture'], cwd=self.root, check=True)

    def test_changed_input_during_calculation_is_rejected(self):
        before = E.snapshot(self.root, [self.path])
        self.path.write_text('{"value": 2}\n')
        with self.assertRaisesRegex(ValueError, 'sources changed'):
            E.evidence(self.root, [self.path], before, {'value': 1})

    def test_missing_input_is_not_replaced_by_default(self):
        with self.assertRaises(FileNotFoundError):
            E.snapshot(self.root, [self.root / 'missing.json'])

    def test_dirty_and_untracked_sources_are_not_presented_as_clean_revision(self):
        clean = E.snapshot(self.root, [self.path])
        self.assertEqual(clean['sourceChanges'], [])
        self.path.write_text('{"value": 2}\n')
        extra = self.root / 'new.py'
        extra.write_text('print(2)\n')
        dirty = E.snapshot(self.root, [self.path, extra])
        self.assertEqual(set(dirty['sourceChanges']), {' M input.json', '?? new.py'})
        self.assertNotEqual(clean['sourceFiles']['input.json'], dirty['sourceFiles']['input.json'])
        self.assertEqual(clean['sourceRevision'], dirty['sourceRevision'])


if __name__ == '__main__':
    unittest.main()
