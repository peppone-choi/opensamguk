from pathlib import Path
import re
import unittest


ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = ROOT / ".github" / "workflows" / "deploy.yml"


class DeployServiceInventoryContractTest(unittest.TestCase):
    def test_compose_inventory_avoids_quiet_grep_pipelines(self) -> None:
        workflow = WORKFLOW.read_text(encoding="utf-8")

        unsafe_pipeline = re.search(
            r"\$COMPOSE config(?: --services)? \| grep -q", workflow
        )
        self.assertIsNone(
            unsafe_pipeline,
            "deploy inventory checks must not combine pipefail with grep -q pipelines",
        )
        self.assertIn('shared_services="$($COMPOSE config --services)"', workflow)
        self.assertIn("grep -Fxq 'board-api' <<<\"$shared_services\"", workflow)
        self.assertIn(
            "$COMPOSE config | grep -F 'BOARD_API_URL:' >/dev/null",
            workflow,
        )
        self.assertEqual(
            workflow.count("grep -Fxq 'game-frontend' <<<\"$shared_services\""),
            2,
        )

    def test_contract_runs_in_ci(self) -> None:
        ci_workflow = (ROOT / ".github" / "workflows" / "ci.yml").read_text(
            encoding="utf-8"
        )

        self.assertIn(
            "python3 tools/ops/test_deploy_service_inventory_contract.py",
            ci_workflow,
        )


if __name__ == "__main__":
    unittest.main()
