"""Include A5's small offline asset tests in existing contracts discovery."""
import unittest
from pathlib import Path

import yaml

from tools.assets.artpack.tests.test_artpack import ArtpackTests  # noqa: F401


class ArtpackCiDependenciesTest(unittest.TestCase):
    def test_pillow_is_installed_without_map_gate_before_discovery(self):
        workflow = Path(__file__).resolve().parents[2] / '.github/workflows/ci.yml'
        steps = yaml.safe_load(workflow.read_text())['jobs']['contracts-execution']['steps']
        discovery = next(i for i, step in enumerate(steps)
                         if "unittest discover -s tools/ci -p 'test_*.py'" in step.get('run', ''))
        installs = [step for step in steps[:discovery]
                    if step.get('run') == 'python3 -m pip install Pillow==12.2.0'
                    and step.get('if', '!cancelled()') == '!cancelled()'
                    and not step.get('continue-on-error', False)]
        self.assertTrue(installs, 'artpack discovery requires Pillow even for map=false PRs')
