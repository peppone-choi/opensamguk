"""Offline declaration checks; prepared data and JVM importer validation are separate gates."""
from copy import deepcopy
import json
from pathlib import Path
import tempfile
import unittest

from pep_scenarios import CATALOG, RESOURCES, approval, scenario

ROOT = Path(__file__).resolve().parents[2]


class PepScenarioTests(unittest.TestCase):
    def test_default_and_current_two_prepared_resources(self):
        self.assertEqual(len(approval(ROOT)['approvedCodes']), 17)
        self.assertEqual(scenario(ROOT)[0], 'scenario_3190')
        self.assertEqual(scenario(ROOT, 'scenario_990002')[0], 'scenario_990002')
        for code in ('', 3190, None, 'scenario_9200', 'scenario_999999', '../scenario_3190'):
            if code is None:
                continue  # Only omission has default semantics.
            with self.subTest(code=code), self.assertRaises(ValueError):
                scenario(ROOT, code)

    def test_approval_does_not_materialize_historical_data(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / CATALOG).parent.mkdir(parents=True)
            (root / CATALOG).write_text((ROOT / CATALOG).read_text())
            with self.assertRaisesRegex(ValueError, 'no packaged prepared'):
                scenario(root, 'scenario_1010')
            (root / RESOURCES).mkdir(parents=True)
            (root / RESOURCES / 'scenario_1010.json').write_text('{"title":"archive","worldFormat":"unprepared"}')
            with self.assertRaisesRegex(ValueError, 'not a prepared'):
                scenario(root, 'scenario_1010')

    def test_any_approved_prepared_resource_can_be_selected(self):
        # Contract fixture only, not a claim about historical scenario provenance or playability.
        prepared = json.loads((ROOT / RESOURCES / 'scenario_990002.json').read_text())
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / CATALOG).parent.mkdir(parents=True)
            (root / CATALOG).write_text((ROOT / CATALOG).read_text())
            (root / RESOURCES).mkdir(parents=True)
            for code in approval(root)['approvedCodes']:
                (root / RESOURCES / (code + '.json')).write_text(json.dumps(prepared))
                self.assertEqual(scenario(root, code), (code, prepared['title']))
            path = root / RESOURCES / 'scenario_1010.json'
            for field in ('worldFormat', 'rulers', 'personPolicies', 'warehouses', 'seedContract'):
                bad = deepcopy(prepared)
                del bad[field]
                path.write_text(json.dumps(bad))
                with self.subTest(field=field), self.assertRaises(ValueError):
                    scenario(root, 'scenario_1010')


if __name__ == '__main__':
    unittest.main()
