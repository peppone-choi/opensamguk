import importlib.util
import re
import shlex
import sys
import unittest
from pathlib import Path


RUNNER_PATH = Path(__file__).with_name("run-runtime-baseline.py")
RUNNER_SPEC = importlib.util.spec_from_file_location("cqrs_runtime_baseline_compose_contract", RUNNER_PATH)
assert RUNNER_SPEC is not None and RUNNER_SPEC.loader is not None
runner = importlib.util.module_from_spec(RUNNER_SPEC)
sys.modules[RUNNER_SPEC.name] = runner
RUNNER_SPEC.loader.exec_module(runner)


class ComposeRuntimeBaselineContractTest(unittest.TestCase):
    @staticmethod
    def jvm_option_name(argument: str) -> str:
        if argument.startswith(("-XX:+", "-XX:-")):
            return f"-XX:{argument[5:]}"
        return argument.partition("=")[0]

    def assert_required_jvm_arguments(self, configured: str, label: str) -> None:
        tokens = shlex.split(configured)
        for required_argument in runner.REQUIRED_JVM_ARGS:
            option_name = self.jvm_option_name(required_argument)
            matching_options = [
                token
                for token in tokens
                if self.jvm_option_name(token) == option_name
            ]
            self.assertEqual([required_argument], matching_options, label)

    def test_game_engine_jvm_arguments_match_baseline_probe_contract(self) -> None:
        configured_options: list[str] = []
        for compose_name in ("docker-compose.yml", "docker-compose.production.yml"):
            compose = (runner.REPOSITORY_ROOT / compose_name).read_text(encoding="utf-8")
            game_engine = compose.split("\n  game-engine:\n", maxsplit=1)[1]
            game_engine = re.split(r"\n  [a-z][a-z0-9-]*:\n", game_engine, maxsplit=1)[0]
            java_opts = next(line for line in game_engine.splitlines() if "JAVA_OPTS:" in line)
            configured = java_opts.split("JAVA_OPTS:", maxsplit=1)[1].strip().strip('"')
            configured = configured.removeprefix("${GAME_ENGINE_JAVA_OPTS:-").removesuffix("}")
            configured_options.append(configured)
            self.assert_required_jvm_arguments(configured, compose_name)

        self.assertEqual(configured_options[0], configured_options[1])

    def test_contract_rejects_noncanonical_or_duplicate_percentage_arguments(self) -> None:
        canonical = " ".join(runner.REQUIRED_JVM_ARGS)
        invalid_options = (
            canonical.replace("MaxRAMPercentage=60", "MaxRAMPercentage=60.0"),
            canonical.replace("MaxRAMPercentage=60", "MaxRAMPercentage=60oops"),
            f"{canonical} -XX:MaxRAMPercentage=60",
            f"{canonical} -XX:-UseG1GC",
            f"{canonical} -XX:+UseG1GC",
        )

        for invalid in invalid_options:
            with self.subTest(invalid=invalid), self.assertRaises(AssertionError):
                self.assert_required_jvm_arguments(invalid, "invalid fixture")


if __name__ == "__main__":
    unittest.main()
