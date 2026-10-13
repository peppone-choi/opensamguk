import copy
import unittest
from wait_for_pr_ci import eligible, verify_paths

REPO = 'peppone-choi/opensamguk'
SHA = 'a' * 40


class CandidateGateTest(unittest.TestCase):
    def setUp(self):
        self.pr = {'number': 1608, 'state': 'open', 'base': {'ref': 'main', 'repo': {'full_name': REPO}},
                   'head': {'sha': SHA, 'repo': {'full_name': REPO}}}
        self.run = {'id': 10, 'head_sha': SHA, 'event': 'pull_request', 'status': 'completed',
                    'conclusion': 'success', 'head_repository': {'full_name': REPO},
                    'pull_requests': [{'number': 1608}]}

    def test_exact_success(self):
        self.assertEqual(eligible(self.pr, [self.run], REPO, SHA), 10)

    def test_reject_pr_identity_mutations(self):
        for field, value in [('sha', 'b'*40), ('repo', {'full_name': 'fork/repo'})]:
            pr = copy.deepcopy(self.pr)
            pr['head'][field] = value
            with self.assertRaises(ValueError):
                eligible(pr, [self.run], REPO, SHA)
        for state, base in [('closed', 'main'), ('open', 'other')]:
            pr = copy.deepcopy(self.pr)
            pr['state'], pr['base']['ref'] = state, base
            with self.assertRaises(ValueError):
                eligible(pr, [self.run], REPO, SHA)

    def test_reject_missing_failed_pending_or_wrong_ci(self):
        for key, val in [('conclusion', 'failure'), ('status', 'in_progress'), ('event', 'push'),
                         ('head_sha', 'b'*40), ('pull_requests', [{'number': 2}]),
                         ('head_repository', {'full_name': 'fork/repo'})]:
            run = dict(self.run, **{key: val})
            with self.assertRaises(ValueError):
                eligible(self.pr, [run], REPO, SHA)
        with self.assertRaises(ValueError):
            eligible(self.pr, [], REPO, SHA)
        with self.assertRaises(ValueError):
            eligible(self.pr, [self.run, dict(self.run, id=11, conclusion='failure')], REPO, SHA)

    def test_supply_chain_paths_fail_closed_but_merged_baseline_does_not(self):
        for path in ['docker/game-api.Dockerfile', '.github/workflows/ci.yml',
                     '.github/workflows/build-pr-candidate-qa-images.yml', 'tools/ci/wait_for_pr_ci.py',
                     'tools/qa/local-stack.py', 'build.gradle.kts', 'web/game/package.json', 'gradlew', 'docker-compose.qa.yml',
                     '.dockerignore', 'tools/rtk14/build_rtk14_stats.py']:
            with self.subTest(path=path), self.assertRaises(ValueError):
                verify_paths([path])
        verify_paths([])  # Once merged, candidate infrastructure is absent from PR delta.
        verify_paths(['engine/src/main/kotlin/Product.kt', 'docs/admin/local-qa.md'])


if __name__ == '__main__':
    unittest.main()
