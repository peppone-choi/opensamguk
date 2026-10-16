"""Regression/positive controls for the four-rule, immutable-debt ratchet."""
import copy
import json
import subprocess
import tempfile
import unittest
from collections import Counter
from pathlib import Path
from unittest.mock import patch

import anti_slop_lint as lint


class RatchetTests(unittest.TestCase):
    def setUp(self):
        self.item = lint.finding('web/game/example.ts', lint.KINDS[0], '({} as unknown) as Thing')
        self.debt = Counter({self.item: 1})
        self.baseline = {kind: int(kind == lint.KINDS[0]) for kind in lint.KINDS}

    def verdict(self, current, base=None, added=()):
        return lint.evaluate(current, self.debt, self.baseline, base, set(added))[1]

    def test_existing_debt_passes_and_decrease_is_allowed(self):
        self.assertFalse(self.verdict(self.debt, self.debt))
        self.assertFalse(self.verdict(Counter(), self.debt))

    def test_increase_and_new_file_fail(self):
        self.assertTrue(self.verdict(Counter({self.item: 2}), self.debt))
        self.assertTrue(self.verdict(self.debt, self.debt, [self.item[0]]))

    def test_cannot_restore_fixed_debt_or_swap_for_new_violation(self):
        self.assertTrue(self.verdict(self.debt, Counter()))
        other = lint.finding(self.item[0], self.item[1], '({} as unknown) as Other')
        self.assertTrue(self.verdict(Counter({other: 1}), self.debt))

    def test_source_hash_not_line_number_is_identity(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            path = root / self.item[0]
            path.parent.mkdir(parents=True)
            text = '한글\nconst value = ({} as unknown) as Thing;\n'
            source = '({} as unknown) as Thing'
            path.write_text(text)
            offset = text.encode().index(source.encode())
            def payload(at):
                return {'diagnostics': [{'code': f'anti-slop({lint.KINDS[0]})',
                    'severity': 'error', 'filename': self.item[0],
                    'labels': [{'span': {'offset': at, 'length': len(source.encode())}}]}],
                    'number_of_files': 1, 'number_of_rules': 4}
            first = lint.parse_output(json.dumps(payload(offset)), 1, root, {self.item[0]})
            path.write_text('// moved\n' + text)
            second = lint.parse_output(json.dumps(payload(offset + 9)), 1, root, {self.item[0]})
            self.assertEqual(first, second)
            self.assertEqual(first, self.debt)

    def test_tool_or_json_failure_is_closed(self):
        for output, status in [('{}', 2), ('not json', 0), ('{}', 0),
                               (json.dumps({'diagnostics': []}), 1)]:
            with self.subTest(output=output, status=status), self.assertRaises(ValueError):
                lint.parse_output(output, status, Path('.'), {'web/game/a.ts'})

    def test_unknown_rule_parser_error_and_incomplete_scan_fail(self):
        for diagnostic in [{'code': 'oxc', 'severity': 'error'},
                           {'code': 'anti-slop(no-runtime-typeof)', 'severity': 'error'}]:
            with self.assertRaises(ValueError):
                lint.parse_output(json.dumps({'diagnostics': [diagnostic],
                    'number_of_files': 1, 'number_of_rules': 4}), 1, Path('.'), {'a.ts'})
        for files, rules in [(0, 4), (1, 0), (2, 4)]:
            with self.assertRaises(ValueError):
                lint.parse_output(json.dumps({'diagnostics': [], 'number_of_files': files,
                    'number_of_rules': rules}), 0, Path('.'), {'a.ts'})

    def test_snapshot_tampering_cannot_refresh_debt(self):
        doc = lint.snapshot_document(self.debt, {self.item: '({} as unknown) as Thing'})
        self.assertEqual(lint.load_snapshot(doc, self.debt)[0], self.debt)
        for change in ('source', 'sha256', 'count', 'sourceCommit', 'counts'):
            bad = copy.deepcopy(doc)
            if change == 'sourceCommit':
                bad[change] = '0' * 40
            elif change == 'counts':
                bad[change][lint.KINDS[0]] += 1
            elif change == 'count':
                bad['findings'][0][change] = 2
                bad['counts'][lint.KINDS[0]] = 2
            else:
                bad['findings'][0][change] = 'changed'
            with self.subTest(change=change), self.assertRaises(ValueError):
                lint.load_snapshot(bad, self.debt)


class PluginTests(unittest.TestCase):
    def test_real_plugin_four_positive_controls_and_const_negative_control(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            path = root / 'web/game/control.ts'
            path.parent.mkdir(parents=True)
            path.write_text('''type T = { value: number };
const chained = ({} as unknown) as T;
const original = { value: 1 };
const widened: unknown = original;
const narrowed = widened as T;
const applied = Reflect.apply(() => 1, null, []);
const read = Reflect.get(original, "value");
const allowed = { value: 1 } as const;
''')
            actual = lint.scan(root)
            self.assertEqual(lint.counts(actual), dict.fromkeys(lint.KINDS, 1))
            path.write_text('const allowed = { value: 1 } as const;\n')
            self.assertEqual(lint.scan(root), Counter())
            path.write_text('const broken = ;\n')
            with self.assertRaises(ValueError):
                lint.scan(root)

    def test_real_source_decrease_increase_movement_and_suppression(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            path = root / 'web/game/debt.ts'
            path.parent.mkdir(parents=True)
            assertion = 'const value = ({} as unknown) as { value: number };\n'
            path.write_text(assertion)
            debt = lint.scan(root)
            baseline = lint.counts(debt)
            for source, fails in [(assertion, False), ('// moved\n' + assertion, False),
                                  ('const fixed = { value: 1 };\n', False),
                                  (assertion + assertion.replace('value =', 'second ='), True)]:
                path.write_text(source)
                self.assertEqual(lint.evaluate(lint.scan(root), debt, baseline, debt, set())[1], fails)
            path.write_text(assertion)
            self.assertTrue(lint.evaluate(lint.scan(root), debt, baseline, debt,
                                         {'web/game/debt.ts'})[1])
            for directive in ['// oxlint-disable', '/* eslint-disable anti-slop/no-chained-type-assertions */']:
                path.write_text(directive + '\n' + assertion)
                with self.assertRaises(ValueError):
                    lint.scan(root)

    def test_subprocess_crash_or_timeout_fails(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            path = root / 'web/game/a.ts'
            path.parent.mkdir(parents=True)
            path.write_text('const a = 1;')
            for error in (OSError('not installed'), subprocess.TimeoutExpired('oxlint', 120)):
                with patch.object(lint.subprocess, 'run', side_effect=error), self.assertRaises((ValueError, OSError, subprocess.SubprocessError)):
                    lint.scan(root)


if __name__ == '__main__':
    unittest.main()
