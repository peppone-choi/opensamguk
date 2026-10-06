"""정리 범위를 검증하며 Docker 변경은 모두 mock 처리한다."""
import fcntl
import io
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parent))
import production_disk_cleanup as cleanup


class CleanupTest(unittest.TestCase):
    def test_plan_is_read_only_and_apply_preserves_volumes_containers_and_tagged_images(self):
        with tempfile.TemporaryDirectory() as temp, patch.object(cleanup, "run") as run, redirect_stdout(io.StringIO()):
            lock = Path(temp) / "production.lock"
            cleanup.cleanup(72, False, lock)
            self.assertEqual([["df", "-Pk", "/"], ["docker", "system", "df"]], [c.args[0] for c in run.call_args_list])
            run.reset_mock()
            cleanup.cleanup(72, True, lock)
            commands = [c.args[0] for c in run.call_args_list]
            self.assertEqual(["docker", "image", "prune", "--force", "--filter", "dangling=true", "--filter", "until=72h"], commands[2])
            self.assertEqual(["docker", "builder", "prune", "--force", "--filter", "until=72h"], commands[3])
            self.assertFalse(any("volume" in c or "container" in c or "-a" in c or "--all" in c for c in commands))

    def test_busy_lock_prevents_even_disk_inspection(self):
        with tempfile.TemporaryDirectory() as temp, patch.object(cleanup, "run") as run, redirect_stdout(io.StringIO()):
            lock = Path(temp) / "production.lock"
            with lock.open("a") as owner:
                fcntl.flock(owner, fcntl.LOCK_EX | fcntl.LOCK_NB)
                self.assertEqual(0, cleanup.cleanup(72, True, lock))
            run.assert_not_called()

    def test_apply_requires_explicit_confirmation_before_any_command(self):
        with patch.object(sys, "argv", ["cleanup", "--minimum-age-hours", "72", "--apply"]), \
                patch.object(cleanup, "cleanup") as perform, redirect_stdout(io.StringIO()), patch("sys.stderr", io.StringIO()):
            with self.assertRaises(SystemExit) as error:
                cleanup.main()
            self.assertEqual(2, error.exception.code)
            perform.assert_not_called()


if __name__ == "__main__":
    unittest.main()
