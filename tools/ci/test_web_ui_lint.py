"""Web UI (mobile parity) ratchet tests, including red probes for each rule."""

import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from web_ui_lint import KINDS, check, scan

SCRIPT = Path(__file__).with_name("web_ui_lint.py")
ZERO = {kind: 0 for kind in KINDS}


class WebUiLintTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)

    def write(self, name, content):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")
        return path

    def run_cli(self, baseline):
        base = self.write("baseline.json", json.dumps(baseline))
        return subprocess.run([sys.executable, str(SCRIPT), "--root", str(self.root), "--baseline", str(base)],
                              capture_output=True, text=True)

    def test_title_only_counts_on_intrinsic_elements(self):
        self.write("web/game/components/P.tsx",
                   'export const P = () => (<>\n  <button type="button" title="이유">가</button>\n'
                   '  <Panel title="편성">x</Panel>\n  <a\n    href="#"\n    title={why}>나</a>\n</>);\n')
        counts, findings = scan(self.root)
        self.assertEqual(counts["title_attr"], 2)
        self.assertEqual(findings["title_attr"], ["web/game/components/P.tsx:2:<button>", "web/game/components/P.tsx:6:<a>"])

    def test_native_disabled_but_not_aria_or_props(self):
        self.write("web/shared/src/B.tsx",
                   'const x = { disabled: true };\nexport const B = () => (<>\n  <button disabled={busy}>a</button>\n'
                   '  <button aria-disabled="true">b</button>\n  <Button disabled>c</Button>\n  <input disabled />\n</>);\n')
        counts, findings = scan(self.root)
        self.assertEqual(counts["native_disabled"], 2)
        self.assertEqual([f.rsplit(":", 1)[1] for f in findings["native_disabled"]], ["<button>", "<input>"])

    def test_expression_variables_named_disabled_or_title_are_not_attributes(self):
        # 리뷰 지적(#1087): {disabled || busy} 의 변수는 속성이 아니다. 권장 수정 aria-disabled 는 0 건이어야 한다.
        self.write("web/gateway/components/F.tsx",
                   'export const F = ({ disabled, busy, title }) => (<>\n'
                   '  <button aria-disabled={disabled || busy} onClick={() => go(a >= b)}>a</button>\n'
                   '  <button disabled={disabled || busy} type="submit">b</button>\n'
                   '  <p>{title}</p>{disabled && <em>x</em>}{disabled ? 1 : 2}\n'
                   '  <label>좌우<input disabled={x >= 1} /></label>\n'
                   '  <button onClick={() => setI((i) => i + 1)} title={i >= n ? "끝" : undefined}>c</button>\n'
                   '</>);\nconst t: Array<string> = []; const disabled = a<b;\n')
        counts, findings = scan(self.root)
        self.assertEqual(counts["native_disabled"], 2)
        self.assertEqual(findings["native_disabled"], ["web/gateway/components/F.tsx:3:<button>", "web/gateway/components/F.tsx:5:<input>"])
        self.assertEqual(findings["title_attr"], ["web/gateway/components/F.tsx:6:<button>"])

    def test_disabled_inside_not_is_active_state(self):
        self.write("web/game/app/globals.css", "button:not(:disabled):hover { opacity: .8 }\n.a:not([aria-disabled='true']) { opacity: 1 }\n")
        counts, _ = scan(self.root)
        self.assertEqual(counts["dimmed_disabled"], 0)

    def test_range_syntax_and_em_widths_are_counted(self):
        self.write("web/game/app/a.css", "@media (width >= 900px) { a{} }\n@media (768px <= width) { b{} }\n"
                   "@media (width >= 1200px) { c{} }\n@media (max-width: 48em) { d{} }\n")
        counts, findings = scan(self.root)
        self.assertEqual(sorted(f.rsplit(":", 1)[1] for f in findings["adhoc_breakpoint"]), ["48em", "900px"])

    def test_dimmed_disabled_css(self):
        self.write("web/game/app/globals.css",
                   "button:disabled { opacity: .5 }\n.x[aria-disabled='true'] { opacity: 0.4; }\n"
                   "button:disabled { border-style: dashed }\n/* button:disabled { opacity: .5 } */\n")
        counts, _ = scan(self.root)
        self.assertEqual(counts["dimmed_disabled"], 2)

    def test_only_the_three_bands_are_allowed(self):
        self.write("web/gateway/app/globals.css",
                   "@media (max-width: 767px) { a{} }\n@media (min-width: 768px) and (max-width: 1199.98px) { b{} }\n"
                   "@media (min-width: 1200px) { c{} }\n@media (prefers-reduced-motion: reduce) { d{} }\n@media (max-width: 900px) { e{} }\n")
        self.write("web/shared/src/M.ts", "const m = window.matchMedia('(max-width: 640px)');\nconst ok = matchMedia(MEDIA.mobile);\n")
        counts, findings = scan(self.root)
        self.assertEqual(counts["adhoc_breakpoint"], 3)
        self.assertEqual(sorted(f.rsplit(":", 1)[1] for f in findings["adhoc_breakpoint"]),
                         ["767px", "900px", "matchMedia 640px"])

    def test_comments_tests_and_e2e_are_skipped(self):
        self.write("web/game/components/C.tsx", "// <button disabled title='x'>\n{/* <a title='y'> */}\n")
        self.write("web/game/__tests__/T.tsx", "<button disabled title='x'>\n")
        self.write("web/game/components/T.test.tsx", "<button disabled title='x'>\n")
        self.write("web/game/e2e/E.spec.ts", "matchMedia('(max-width: 10px)')\n")
        counts, _ = scan(self.root)
        self.assertEqual(dict(counts), ZERO)

    def test_ratchet_fails_up_and_notes_down(self):
        self.write("web/game/components/P.tsx", '<button title="a">x</button>\n')
        up = self.run_cli(ZERO)
        self.assertEqual(up.returncode, 1)
        self.assertIn("FAIL title_attr: 1 > baseline 0", up.stdout)
        self.assertIn("web/game/components/P.tsx:1:<button>", up.stdout)
        down = self.run_cli({**ZERO, "title_attr": 2})
        self.assertEqual(down.returncode, 0)
        self.assertIn("NOTE title_attr: 1 < baseline 2", down.stdout)
        same = self.run_cli({**ZERO, "title_attr": 1})
        self.assertEqual(same.returncode, 0)

    def test_baseline_must_name_every_kind(self):
        with self.assertRaises(ValueError):
            check(scan(self.root)[0], {"title_attr": 0})
        bad = self.run_cli({"title_attr": 0})
        self.assertEqual(bad.returncode, 2)


if __name__ == "__main__":
    unittest.main()
