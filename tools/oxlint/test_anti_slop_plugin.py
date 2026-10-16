"""Required real oxlint controls, executed after pinned npm ci in naming-lint."""
import sys
import tempfile
import unittest
from collections import Counter
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'ci'))
import anti_slop_lint as lint


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


if __name__ == '__main__':
    unittest.main()
