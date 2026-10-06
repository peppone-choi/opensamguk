"""depcruise_counts: one count per violation under its owning app, unresolved `@/` is a configuration error, the CLI judges."""

from __future__ import annotations

import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import depcruise_counts  # noqa: E402

SCRIPT = Path(__file__).resolve().parent / "depcruise_counts.py"


def result(*violations: tuple[str, str, str], modules: list | None = None) -> dict:
    return {
        "modules": modules if modules is not None else [{"source": "game/app/page.tsx", "dependencies": []}],
        "summary": {"violations": [{"from": f, "to": t, "rule": {"name": r, "severity": "error"}} for r, f, t in violations]},
    }


SHARED_CYCLE = ("no-circular", "shared/src/a.ts", "shared/src/b.ts")


class CountTest(unittest.TestCase):
    def test_rules_match_the_config(self) -> None:
        self.assertEqual(sorted(depcruise_counts.RULES), sorted(depcruise_counts.config_rules(depcruise_counts.WEB / depcruise_counts.CONFIG)))

    def test_shared_violation_seen_by_every_app_counts_once_under_shared(self) -> None:
        counts, findings = depcruise_counts.count({
            "game": result(SHARED_CYCLE, ("lib-not-to-hooks", "game/lib/x.ts", "game/hooks/useY.ts")),
            "gateway": result(SHARED_CYCLE),
            "shared": result(SHARED_CYCLE),
        })
        self.assertEqual(1, counts["no_circular_shared"])
        self.assertEqual(0, counts["no_circular_game"])
        self.assertEqual(["game/lib/x.ts → game/hooks/useY.ts"], findings["lib_not_to_hooks_game"])

    def test_unresolved_alias_is_a_configuration_error(self) -> None:
        broken = result(modules=[{"source": "game/components/A.tsx",
                                  "dependencies": [{"module": "@/lib/api", "couldNotResolve": True}]}])
        with self.assertRaisesRegex(ValueError, "unresolved alias"):
            depcruise_counts.count({"game": broken})

    def test_unresolved_workspace_alias_is_a_configuration_error(self) -> None:
        broken = result(modules=[{"source": "game/components/A.tsx",
                                  "dependencies": [{"module": "@opensamguk/ui", "couldNotResolve": True}]}])
        with self.assertRaisesRegex(ValueError, "unresolved alias"):
            depcruise_counts.count({"game": broken})

    def test_empty_cruise_and_unknown_rule_are_configuration_errors(self) -> None:
        with self.assertRaisesRegex(ValueError, "no module"):
            depcruise_counts.count({"game": result(modules=[])})
        with self.assertRaisesRegex(ValueError, "unknown rule"):
            depcruise_counts.count({"game": result(("made-up", "game/a.ts", "game/b.ts"))})


class CliTest(unittest.TestCase):
    def run_cli(self, folder: Path, baseline: dict, *extra: str) -> subprocess.CompletedProcess:
        base = folder / "baseline.json"
        base.write_text(json.dumps(baseline), encoding="utf-8")
        return subprocess.run([sys.executable, str(SCRIPT), "--from-json", str(folder), "--baseline", str(base), *extra],
                              capture_output=True, text=True)

    def test_ratchet_fails_above_baseline_and_report_only_does_not(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            folder = Path(tmp)
            for app in depcruise_counts.APPS:
                violations = [("view-not-to-api", "game/lib/a-view.ts", "game/lib/api.ts")] if app == "game" else []
                (folder / f"{app}.json").write_text(json.dumps(result(*violations)), encoding="utf-8")
            exact = {kind: 0 for kind in depcruise_counts.KINDS} | {"view_not_to_api_game": 1}
            ok = self.run_cli(folder, exact)
            self.assertEqual(0, ok.returncode, ok.stdout)
            self.assertIn("OK view_not_to_api_game: 1 <= baseline 1", ok.stdout)
            tighter = exact | {"view_not_to_api_game": 0}
            red = self.run_cli(folder, tighter)
            self.assertEqual(1, red.returncode, red.stdout)
            self.assertIn("FAIL view_not_to_api_game: 1 > baseline 0", red.stdout)
            self.assertIn("view_not_to_api_game examples: game/lib/a-view.ts → game/lib/api.ts", red.stdout)
            report = self.run_cli(folder, tighter, "--report-only")
            self.assertEqual(0, report.returncode, report.stdout)
            self.assertIn("WOULD FAIL view_not_to_api_game", report.stdout)

    def test_files_the_pr_adds_must_be_clean_after_the_rule_took_effect(self) -> None:
        import os
        with tempfile.TemporaryDirectory() as tmp:
            repo = Path(tmp)

            def git(*args: str, when: str | None = None) -> str:
                env = {**os.environ, **({"GIT_COMMITTER_DATE": when} if when else {})}
                return subprocess.run(["git", "-C", str(repo), "-c", "user.name=t", "-c", "user.email=t@t", *args],
                                      check=True, capture_output=True, text=True, env=env).stdout

            git("init", "-q")
            (repo / "web/game/lib").mkdir(parents=True)
            (repo / "web/game/lib/old-view.ts").write_text("export const a = 1;\n", encoding="utf-8")
            (repo / "tools/ci").mkdir(parents=True)
            # the rule turns on when this check's marker reaches the base (its ratchet PR merge) — 03:00Z here
            (repo / "tools/ci/depcruise_counts.py").write_text(f"M = {depcruise_counts.NEW_FILE_RULE_MARKER!r}\n", encoding="utf-8")
            git("add", "-A")
            git("commit", "-qm", "base", when="2026-10-05T03:00:00+00:00")
            base = git("rev-parse", "HEAD").strip()
            (repo / "web/game/lib/new-view.ts").write_text("export const b = 2;\n", encoding="utf-8")
            git("add", "-A")
            git("commit", "-qm", "head")
            folder = repo / "json"
            folder.mkdir()
            for app in depcruise_counts.APPS:
                violations = [("view-not-to-api", "game/lib/old-view.ts", "game/lib/api.ts"),
                              ("view-not-to-api", "game/lib/new-view.ts", "game/lib/api.ts")] if app == "game" else []
                (folder / f"{app}.json").write_text(json.dumps(result(*violations)), encoding="utf-8")
            loose = {kind: 99 for kind in depcruise_counts.KINDS}
            after = self.run_cli(folder, loose, "--base-ref", base, "--repo", str(repo), "--pr-created", "2026-10-05T03:00:01Z")
            self.assertEqual(1, after.returncode, after.stdout)
            self.assertIn("FAIL new-file view_not_to_api_game: game/lib/new-view.ts → game/lib/api.ts", after.stdout)
            self.assertNotIn("old-view", "\n".join(line for line in after.stdout.splitlines() if "new-file" in line))
            before = self.run_cli(folder, loose, "--base-ref", base, "--repo", str(repo), "--pr-created", "2026-10-05T02:59:59Z")
            self.assertEqual(0, before.returncode, before.stdout)
            self.assertIn("NOTE new-file (PR opened before the rule took effect", before.stdout)

    def test_configuration_error_exits_2_even_in_report_only(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            folder = Path(tmp)
            for app in depcruise_counts.APPS:
                (folder / f"{app}.json").write_text(json.dumps(result(modules=[])), encoding="utf-8")
            run = self.run_cli(folder, {kind: 0 for kind in depcruise_counts.KINDS}, "--report-only")
            self.assertEqual(2, run.returncode, run.stdout)


if __name__ == "__main__":
    unittest.main()
