import os
import sys
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parent))
import wait_for_main_ci as gate  # noqa: E402


class MainCiGateTest(unittest.TestCase):
    def test_only_exact_push_sha_success_releases_deploy(self):
        runs = [
            {"id": 10, "head_sha": "other", "event": "push", "status": "completed", "conclusion": "success"},
            {"id": 11, "head_sha": "abc", "event": "schedule", "status": "completed", "conclusion": "success"},
            {"id": 12, "head_sha": "abc", "event": "push", "status": "completed", "conclusion": "success",
             "html_url": "https://example.invalid/run/12"},
        ]
        with patch.dict(os.environ, {"DEPLOY_SHA": "abc"}), patch.object(gate, "runs", return_value=runs):
            self.assertEqual(0, gate.main())

    def test_failed_ci_blocks_deploy(self):
        runs = [{"id": 12, "head_sha": "abc", "event": "push", "status": "completed",
                 "conclusion": "failure", "html_url": "https://example.invalid/run/12"}]
        with patch.dict(os.environ, {"DEPLOY_SHA": "abc"}), patch.object(gate, "runs", return_value=runs):
            self.assertEqual(1, gate.main())


if __name__ == "__main__":
    unittest.main()
