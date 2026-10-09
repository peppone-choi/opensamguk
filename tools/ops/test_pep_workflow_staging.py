"""Offline contracts across automatic and manual pep workflow entry points."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

import yaml

ROOT = Path(__file__).resolve().parents[2]
WORKFLOWS = ROOT / '.github/workflows'


def workflow(name):
    return yaml.safe_load((WORKFLOWS / name).read_text())


def events(document):
    # PyYAML's YAML 1.1 loader treats GitHub's unquoted `on` key as True.
    return document.get('on', document.get(True))


class PepWorkflowStagingTest(unittest.TestCase):
    def setUp(self):
        self.loop = workflow('pep-loop.yml')

    def test_default_and_push_cannot_reach_runtime(self):
        inputs = events(self.loop)['workflow_call']['inputs']
        self.assertEqual(inputs['apply_changes']['type'], 'boolean')
        self.assertIs(inputs['apply_changes']['default'], False)
        self.assertIs(inputs['apply_changes']['required'], False)
        deploy = workflow('deploy.yml')
        self.assertEqual(events(deploy)['push']['branches'], ['main'])
        self.assertEqual(set(deploy['jobs']), {'pep'})
        caller = deploy['jobs']['pep']
        self.assertEqual(caller['uses'], './.github/workflows/pep-loop.yml')
        self.assertEqual(caller['with']['mode'], 'auto')
        self.assertIs(caller['with']['apply_changes'], False)
        runtime = self.loop['jobs']['apply']
        self.assertEqual(runtime['if'], '${{ inputs.apply_changes }}')
        self.assertEqual(runtime['needs'], ['source', 'images'])
        for name, job in self.loop['jobs'].items():
            if name != 'apply':
                self.assertEqual(job['runs-on'], 'ubuntu-24.04')
                self.assertNotIn('if', job)

    def test_all_reusable_callers_have_explicit_intent(self):
        callers = {}
        for path in WORKFLOWS.glob('*.yml'):
            for job in workflow(path.name).get('jobs', {}).values():
                if job.get('uses') == './.github/workflows/pep-loop.yml':
                    callers[path.name] = job['with']
        self.assertEqual(set(callers), {'deploy.yml', 'pep-refresh.yml', 'pep-reset.yml'})
        for name, mode in [('pep-refresh.yml', 'refresh'), ('pep-reset.yml', 'reset')]:
            self.assertIs(callers[name]['apply_changes'], True)
            self.assertEqual(callers[name]['mode'], mode)
            self.assertIn('workflow_dispatch', events(workflow(name)))
        self.assertEqual(callers['pep-reset.yml']['scenario_code'], '${{ inputs.scenario_code }}')
        for name, destination in [('promote-game-server.yml', 'pep-refresh.yml'),
                                  ('reset-game-server.yml', 'pep-reset.yml')]:
            entry = workflow(name)
            self.assertEqual(set(events(entry)), {'workflow_dispatch'})
            self.assertEqual(entry['jobs']['pep']['uses'], './.github/workflows/' + destination)

    def test_exact_ci_and_immutable_images_precede_apply(self):
        source = self.loop['jobs']['source']
        steps = source['steps']
        checkout = next(s for s in steps if s.get('uses', '').startswith('actions/checkout@'))
        self.assertEqual(checkout['with']['ref'], 'main')
        self.assertEqual(checkout['with']['fetch-depth'], 0)
        wait = next(s for s in steps if s.get('run') == 'python3 tools/ci/wait_for_main_ci.py')
        admit = next(s for s in steps if 'pep_loop.py admit-ci' in s.get('run', ''))
        self.assertEqual(wait['env']['DEPLOY_SHA'], '${{ steps.source.outputs.sha }}')
        self.assertEqual(admit['env']['SOURCE'], '${{ steps.source.outputs.sha }}')
        self.assertLess(steps.index(wait), steps.index(admit))
        images = self.loop['jobs']['images']
        self.assertEqual(images['needs'], 'source')
        self.assertEqual(images['steps'][0]['with']['ref'], '${{ needs.source.outputs.sha }}')
        contract = next(s for s in images['steps'] if s.get('id') == 'contract')
        self.assertEqual(contract['run'], 'python3 tools/ops/pep_loop.py images --metadata-dir "$RUNNER_TEMP/pep-images"')
        self.assertEqual(images['outputs']['images'], '${{ steps.contract.outputs.images }}')
        apply = next(s for s in self.loop['jobs']['apply']['steps'] if 'PEP_IMAGES' in s.get('env', {}))
        self.assertEqual(apply['env']['PEP_IMAGES'], '${{ needs.images.outputs.images }}')
        self.assertEqual(apply['env']['SOURCE'], '${{ needs.source.outputs.sha }}')

    def test_artifact_exec_preserves_each_digest_and_source(self):
        steps = self.loop['jobs']['images']['steps']
        writer = next(s for s in steps if s.get('name') == 'Write immutable candidate contracts')
        self.assertEqual(writer['env'], {'SOURCE': '${{ needs.source.outputs.sha }}',
                                        'PEP_IMAGES': '${{ steps.contract.outputs.images }}'})
        upload = next(s for s in steps if s.get('uses', '').startswith('actions/upload-artifact@'))
        self.assertLess(steps.index(writer), steps.index(upload))
        self.assertEqual(upload['with']['if-no-files-found'], 'error')
        self.assertEqual(upload['with']['path'], '${{ runner.temp }}/pep-candidate/*.json')
        self.assertIn('${{ needs.source.outputs.sha }}', upload['with']['name'])
        self.assertIn('${{ github.run_attempt }}', upload['with']['name'])
        fixtures = {role: {'ref': 'ghcr.io/peppone-choi/opensamguk@sha256:' + str(i) * 64,
                           'manifest': 'sha256:' + str(i) * 64, 'config': 'sha256:' + str(i + 3) * 64}
                    for i, role in enumerate(('game-api', 'game-engine', 'web-game'), 1)}
        with tempfile.TemporaryDirectory() as directory:
            env = {**os.environ, 'RUNNER_TEMP': directory, 'SOURCE': 'a' * 40,
                   'PEP_IMAGES': json.dumps(fixtures)}
            subprocess.run(['bash', '-euo', 'pipefail', '-c', writer['run']], env=env, check=True)
            target = Path(directory) / 'pep-candidate'
            self.assertEqual(json.loads((target / 'candidate.json').read_text()),
                             {'source': 'a' * 40, 'images': fixtures})
            self.assertEqual({p.name for p in target.iterdir()},
                             {'candidate.json', 'game-api.json', 'game-engine.json', 'web-game.json'})
            for role, contract in fixtures.items():
                self.assertEqual(json.loads((target / (role + '.json')).read_text()), contract)


if __name__ == '__main__':
    unittest.main()
