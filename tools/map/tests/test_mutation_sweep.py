"""The sweep reports "N/N killed". Nothing proved N was not zero.

If `rule_spans` stopped recognising a rule shape, the sweep would find nothing to mutate,
print `0/0 rules killed by the test suite`, exit 0, and read as a clean bill of health —
the same vacuous-pass failure the sweep exists to find in other people's gates.
"""

from __future__ import annotations

import unittest

from tools.map import audit_territory_disconnections as audit
from tools.map import mutation_sweep


class RuleSpanTest(unittest.TestCase):
    def test_it_finds_both_rule_shapes_and_nothing_else(self):
        source = (
            "def f(errors, other):\n"
            "    if 1:\n"
            "        raise ValueError('a')\n"
            "    errors.append('b')\n"
            "    other.append('c')\n"
            "    errors.extend(['d'])\n"
            "    errors.append(\n"
            "        'e'\n"
            "    )\n"
        )
        spans = mutation_sweep.rule_spans(source)
        self.assertEqual([(start, kind) for start, _, kind in spans],
                         [(3, "raise"), (4, "errors.append"), (7, "errors.append")])
        self.assertEqual(spans[-1][1], 9, "a multi-line rule must span all of its lines")

    def test_the_committed_gate_still_presents_rules_to_mutate(self):
        source = audit.__file__ and open(audit.__file__, encoding="utf-8").read()
        spans = mutation_sweep.rule_spans(source)
        kinds = {kind for _, _, kind in spans}
        self.assertEqual(kinds, {"raise", "errors.append"})
        self.assertGreaterEqual(
            len(spans), 50,
            "the sweep found almost no rules in the disconnection gate; a 'N/N killed' "
            "result would be meaningless",
        )

    def test_spans_never_overlap_so_one_mutation_removes_one_rule(self):
        source = open(audit.__file__, encoding="utf-8").read()
        spans = mutation_sweep.rule_spans(source)
        for (a_start, a_end, _), (b_start, _, _) in zip(spans, spans[1:]):
            self.assertLess(a_end, b_start, f"rules at {a_start} and {b_start} overlap")


if __name__ == "__main__":
    unittest.main()
