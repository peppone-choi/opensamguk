"""Run the portable PR-loop gates in the required contracts CI discovery."""
import importlib.util
from pathlib import Path

path = Path(__file__).resolve().parents[1] / "pr-loop/tests/pr_loop_test.py"
spec = importlib.util.spec_from_file_location("pr_loop_contracts", path)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
FastLoopTest = module.FastLoopTest
