#!/usr/bin/env python3
from __future__ import annotations

import os
from pathlib import Path
import re
import subprocess
import tempfile
import textwrap
import unittest


ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = ROOT / ".github" / "workflows" / "deploy.yml"
STEP_NAME = "Deploy shared stack on box"
IMAGE_TAG = "source-image-tag"
SECRET_TOKEN = "boundary-secret-token-value"
LEASE = "0123456789abcdef0123456789abcdef"


def extract_run_script() -> str:
    lines = WORKFLOW.read_text(encoding="utf-8").splitlines()
    step = lines.index(f"      - name: {STEP_NAME}")
    run = lines.index("        run: |", step) + 1
    script: list[str] = []
    for line in lines[run:]:
        if not line:
            script.append("")
        elif line.startswith("          "):
            script.append(line[10:])
        else:
            break
    return "\n".join(script) + "\n"


def extract_http_adapter() -> str:
    script = extract_run_script()
    match = re.search(
        r"docker exec opensamguk-deployer python3 -c '\n(?P<source>.*?)\n' "
        r'"\$method" "\$path" "\$timeout_seconds"',
        script,
        re.DOTALL,
    )
    if match is None:
        raise AssertionError("maintenance HTTP adapter was not found in the deploy step")
    return match.group("source")


class SourceDeployMaintenanceBoundaryTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp_dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp_dir.cleanup)
        self.case_root = Path(self.temp_dir.name)
        self.fake_bin = self.case_root / "bin"
        self.stack = self.case_root / "home" / "opensamguk-docker"
        self.fake_bin.mkdir()
        (self.stack / "servers").mkdir(parents=True)
        (self.stack / ".env").write_text(
            "IMAGE_TAG=before-admission\nJWT_SIGNING_MODE=HS256\n",
            encoding="utf-8",
        )
        self.server_env = self.stack / "servers" / "spep.env"
        self.server_env.write_text(
            "IMAGE_TAG=game-pin\nWEB_GAME_TAG=web-game-pin\n",
            encoding="utf-8",
        )
        self.workflow_script = self.case_root / "workflow.sh"
        self.workflow_script.write_text(extract_run_script(), encoding="utf-8")
        self.events = self.case_root / "events.log"
        self.events.write_text("", encoding="utf-8")
        self._write_fake("flock", "exit 0")
        self._write_fake("sleep", "exit 0")
        self._write_fake("sudo", 'exec "$@"')
        self._write_fake(
            "git",
            r'''
            printf 'git:%s\n' "$*" >> "$BOUNDARY_EVENTS"
            case "${1:-}" in
              status) exit 0 ;;
              fetch) exit 0 ;;
              merge) exit 0 ;;
              rev-parse) printf 'same-revision\n'; exit 0 ;;
            esac
            exit 93
            ''',
        )
        self._write_fake(
            "curl",
            r'''
            printf 'curl:%s\n' "$*" >> "$BOUNDARY_EVENTS"
            if [[ "$BOUNDARY_SCENARIO" == gateway-health-transport-success-json \
              && "$*" == *"/api/gateway/actuator/health"* ]]; then
              printf '{"status":"UP"}\n'
              exit 22
            fi
            printf '{"status":"UP"}\n'
            ''',
        )
        self._write_fake(
            "sed",
            r'''
            if [[ "${1:-}" == -i ]]; then
              expression="$2"
              target="$3"
              replacement="${expression#s/^IMAGE_TAG=.*/}"
              replacement="${replacement%/}"
              awk -v replacement="$replacement" '
                /^IMAGE_TAG=/ { print replacement; next }
                { print }
              ' "$target" > "$target.tmp"
              mv "$target.tmp" "$target"
              exit 0
            fi
            exec /usr/bin/sed "$@"
            ''',
        )
        self._write_fake(
            "docker",
            r'''
            args=("$@")
            joined="$*"
            if [[ "${1:-}" == ps ]]; then
              printf 'docker:ps\n' >> "$BOUNDARY_EVENTS"
              if [[ "$BOUNDARY_SCENARIO" != missing-controller ]]; then
                printf 'opensamguk-deployer\n'
                if [[ "$BOUNDARY_SCENARIO" == late-game-gate-failure ]]; then
                  printf 'spep-game-api\nspep-game-engine\nspep-web-game\n'
                fi
              fi
              exit 0
            fi
            if [[ "${1:-}" == login ]]; then
              printf 'docker:login\n' >> "$BOUNDARY_EVENTS"
              exit 0
            fi
            if [[ "${1:-}" == compose ]]; then
              if [[ "$joined" == *" config --services"* ]]; then
                printf 'board-api\ngateway-api\nweb-gateway\nnginx\n'
                exit 0
              fi
              if [[ "$joined" == *" config"* ]]; then
                printf '      BOARD_API_URL: http://board-api:8083\n'
                exit 0
              fi
              printf 'docker:compose:%s\n' "$joined" >> "$BOUNDARY_EVENTS"
              if [[ "$BOUNDARY_SCENARIO" == later-failure && "$joined" == *"board-api"* ]]; then
                exit 76
              fi
              exit 0
            fi
            if [[ "${1:-}" != exec ]]; then
              exit 94
            fi
            if [[ "$joined" == *"python3 -c"* ]]; then
              count=${#args[@]}
              method="${args[$((count - 3))]}"
              path="${args[$((count - 2))]}"
              printf 'http:%s:%s\n' "$method" "$path" >> "$BOUNDARY_EVENTS"
              case "$path" in
                /maintenance)
                  case "$BOUNDARY_SCENARIO" in
                    old-controller)
                      printf '{"error":"not found"}\n'
                      exit 8
                      ;;
                    closed)
                      printf '{"capability":"maintenance-v1","state":"drained"}\n'
                      exit 0
                      ;;
                  esac
                  if [[ -e "$BOUNDARY_ENTERED" ]]; then
                    printf '{"capability":"maintenance-v1","state":"drained"}\n'
                    if [[ "$BOUNDARY_SCENARIO" == postcheck-transport-success-json ]]; then
                      exit 8
                    fi
                  else
                    printf '{"capability":"maintenance-v1","state":"open"}\n'
                  fi
                  exit 0
                  ;;
                /maintenance/enter-if-idle)
                  case "$BOUNDARY_SCENARIO" in
                    busy)
                      printf '{"error":"maintenance idle admission unavailable"}\n'
                      exit 8
                      ;;
                    invalid-json)
                      printf 'not-json\n'
                      exit 0
                      ;;
                    transport-success-json)
                      printf '{"capability":"maintenance-v1","state":"drained","lease":"%s"}\n' "$BOUNDARY_LEASE"
                      exit 8
                      ;;
                  esac
                  : > "$BOUNDARY_ENTERED"
                  printf '{"capability":"maintenance-v1","state":"drained","lease":"%s"}\n' "$BOUNDARY_LEASE"
                  exit 0
                  ;;
                /maintenance/leave)
                  if [[ "$BOUNDARY_SCENARIO" == leave-transport-success-json ]]; then
                    printf '{"capability":"maintenance-v1","state":"open"}\n'
                    exit 8
                  fi
                  rm -f "$BOUNDARY_ENTERED"
                  printf '{"capability":"maintenance-v1","state":"open"}\n'
                  exit 0
                  ;;
                /maintenance/enter|/maintenance/repair)
                  exit 95
                  ;;
              esac
              exit 96
            fi
            printf 'docker:exec:%s\n' "$joined" >> "$BOUNDARY_EVENTS"
            if [[ "$joined" == *"exec psql"* ]]; then
              printf '202401|100|90|100\n'
              exit 0
            fi
            if [[ "$joined" == *"/admin/turn-daemon/status"* ]]; then
              printf '{"paused":false,"recoveryReady":false}\n'
              exit 0
            fi
            if [[ "$joined" == *"curl -sf"* || "$joined" == *"wget -qO-"* ]]; then
              printf '{"ok":true,"status":"UP"}\n'
            fi
            exit 0
            ''',
        )

    def _write_fake(self, name: str, body: str) -> None:
        path = self.fake_bin / name
        path.write_text(
            "#!/usr/bin/env bash\nset -euo pipefail\n"
            + textwrap.dedent(body).strip()
            + "\n",
            encoding="utf-8",
        )
        path.chmod(0o755)

    def run_workflow(self, scenario: str) -> tuple[subprocess.CompletedProcess[str], list[str]]:
        entered = self.case_root / "maintenance-entered"
        environment = os.environ.copy()
        environment.update(
            {
                "HOME": str(self.case_root / "home"),
                "PATH": f"{self.fake_bin}:{environment['PATH']}",
                "IMAGE_TAG": IMAGE_TAG,
                "GHCR_TOKEN": "fake-ghcr-token",
                "GHCR_USER": "fake-ghcr-user",
                "BOUNDARY_SCENARIO": scenario,
                "BOUNDARY_EVENTS": str(self.events),
                "BOUNDARY_ENTERED": str(entered),
                "BOUNDARY_LEASE": LEASE,
                "BOUNDARY_SECRET_TOKEN": SECRET_TOKEN,
            }
        )
        result = subprocess.run(
            ["bash", str(self.workflow_script)],
            cwd=self.stack,
            env=environment,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            timeout=10,
            check=False,
        )
        return result, self.events.read_text(encoding="utf-8").splitlines()

    def assert_no_mutation(self, events: list[str]) -> None:
        mutation_fragments = (
            "git:fetch",
            "git:merge",
            "docker:login",
            "docker:compose:",
            "docker:exec:",
        )
        self.assertFalse(
            any(event.startswith(mutation_fragments) for event in events),
            events,
        )
        self.assertEqual(
            "IMAGE_TAG=before-admission\nJWT_SIGNING_MODE=HS256\n",
            (self.stack / ".env").read_text(encoding="utf-8"),
        )
        self.assertEqual(
            "IMAGE_TAG=game-pin\nWEB_GAME_TAG=web-game-pin\n",
            self.server_env.read_text(encoding="utf-8"),
        )
        self.assertNotIn("http:POST:/maintenance/enter", events)
        self.assertNotIn("http:POST:/maintenance/repair", events)

    def test_admission_failures_stop_before_every_mutation(self) -> None:
        for scenario in (
            "busy",
            "old-controller",
            "missing-controller",
            "invalid-json",
            "closed",
            "transport-success-json",
        ):
            with self.subTest(scenario=scenario):
                self.events.write_text("", encoding="utf-8")
                result, events = self.run_workflow(scenario)
                self.assertNotEqual(0, result.returncode, result.stdout)
                self.assert_no_mutation(events)
                combined = result.stdout + "\n" + "\n".join(events)
                self.assertNotIn(SECRET_TOKEN, combined)
                self.assertNotIn(LEASE, combined)

    def test_owned_entry_precedes_mutation_and_later_failure_stays_closed(self) -> None:
        result, events = self.run_workflow("later-failure")

        self.assertNotEqual(0, result.returncode, result.stdout)
        entry = events.index("http:POST:/maintenance/enter-if-idle")
        sync = next(i for i, event in enumerate(events) if event.startswith("git:fetch"))
        replacement = next(
            i
            for i, event in enumerate(events)
            if event.startswith("docker:compose:") and "deployer" in event
        )
        self.assertLess(entry, sync)
        self.assertLess(sync, replacement)
        self.assertNotIn("http:POST:/maintenance/leave", events)
        self.assertNotIn("http:POST:/maintenance/repair", events)

    def test_post_replacement_transport_failure_stops_before_health_and_leave(self) -> None:
        result, events = self.run_workflow("postcheck-transport-success-json")

        self.assertNotEqual(0, result.returncode, result.stdout)
        self.assertEqual(2, events.count("http:GET:/maintenance"))
        self.assertNotIn("curl:-sf http://localhost/health", events)
        self.assertNotIn("http:POST:/maintenance/leave", events)
        self.assertNotIn("http:POST:/maintenance/repair", events)
        self.assertNotIn(LEASE, result.stdout + "\n" + "\n".join(events))

    def test_ambiguous_leave_transport_failure_is_not_retried(self) -> None:
        result, events = self.run_workflow("leave-transport-success-json")

        self.assertNotEqual(0, result.returncode, result.stdout)
        self.assertEqual(1, events.count("http:POST:/maintenance/leave"))
        self.assertNotIn("http:POST:/maintenance/repair", events)
        self.assertNotIn("Shared deploy complete", result.stdout)
        self.assertIn("leave result is unknown", result.stdout)
        self.assertNotIn(LEASE, result.stdout + "\n" + "\n".join(events))

    def test_late_registered_game_recovery_gate_failure_does_not_leave(self) -> None:
        result, events = self.run_workflow("late-game-gate-failure")

        self.assertNotEqual(0, result.returncode, result.stdout)
        self.assertTrue(
            any("/admin/turn-daemon/status" in event for event in events),
            events,
        )
        self.assertIn("daemon recovery-gated", result.stdout)
        self.assertNotIn("http:POST:/maintenance/leave", events)
        self.assertNotIn("http:POST:/maintenance/repair", events)

    def test_gateway_health_success_json_with_failed_transport_does_not_leave(self) -> None:
        result, events = self.run_workflow("gateway-health-transport-success-json")

        self.assertNotEqual(0, result.returncode, result.stdout)
        self.assertTrue(
            any("/api/gateway/actuator/health" in event for event in events),
            events,
        )
        self.assertNotIn("http:POST:/maintenance/leave", events)
        self.assertNotIn("http:POST:/maintenance/repair", events)
        self.assertNotIn("Shared deploy complete", result.stdout)

    def test_successful_full_verification_leaves_only_owned_window(self) -> None:
        result, events = self.run_workflow("success")

        self.assertEqual(0, result.returncode, result.stdout)
        initial_get = events.index("http:GET:/maintenance")
        entry = events.index("http:POST:/maintenance/enter-if-idle")
        sync = events.index("git:fetch origin main")
        post_replace_get = events.index("http:GET:/maintenance", initial_get + 1)
        health = events.index("curl:-sf http://localhost/health")
        leave = events.index("http:POST:/maintenance/leave")
        positions = [initial_get, entry, sync, post_replace_get, health, leave]
        self.assertEqual(sorted(positions), positions, events)
        self.assertEqual(1, events.count("http:POST:/maintenance/enter-if-idle"))
        self.assertEqual(1, events.count("http:POST:/maintenance/leave"))
        self.assertNotIn("http:POST:/maintenance/enter", events)
        self.assertNotIn("http:POST:/maintenance/repair", events)
        compose_events = [event for event in events if event.startswith("docker:compose:")]
        self.assertFalse(
            any(
                service in event
                for event in compose_events
                for service in ("spep-game-api", "spep-game-engine", "spep-web-game")
            ),
            compose_events,
        )
        self.assertEqual(
            "IMAGE_TAG=game-pin\nWEB_GAME_TAG=web-game-pin\n",
            self.server_env.read_text(encoding="utf-8"),
        )
        combined = result.stdout + "\n" + "\n".join(events)
        self.assertNotIn(SECRET_TOKEN, combined)
        self.assertNotIn(LEASE, combined)


class MaintenanceHttpAdapterTest(unittest.TestCase):
    def test_actual_adapter_uses_container_token_without_logging_it(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            fixture_root = Path(temp_dir)
            capture = fixture_root / "request.txt"
            (fixture_root / "sitecustomize.py").write_text(
                textwrap.dedent(
                    '''
                    import os
                    from pathlib import Path
                    import urllib.request


                    class FixtureResponse:
                        status = 200

                        def read(self, limit):
                            return b'{"capability":"maintenance-v1","state":"open"}'

                        def close(self):
                            return None


                    class FixtureOpener:
                        def open(self, request, timeout):
                            Path(os.environ["ADAPTER_CAPTURE"]).write_text(
                                request.method + "\\n"
                                + request.full_url + "\\n"
                                + request.get_header("Authorization") + "\\n",
                                encoding="utf-8",
                            )
                            return FixtureResponse()


                    urllib.request.build_opener = lambda *handlers: FixtureOpener()
                    '''
                ),
                encoding="utf-8",
            )
            environment = os.environ.copy()
            environment.update(
                {
                    "PYTHONPATH": str(fixture_root),
                    "DEPLOYER_TOKEN": SECRET_TOKEN,
                    "ADAPTER_CAPTURE": str(capture),
                }
            )

            result = subprocess.run(
                [python_executable(), "-c", extract_http_adapter(), "GET", "/maintenance", "10"],
                env=environment,
                text=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                timeout=5,
                check=False,
            )

            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual(
                '{"capability":"maintenance-v1","state":"open"}',
                result.stdout,
            )
            self.assertEqual(
                "GET\nhttp://localhost:9000/maintenance\n"
                f"Bearer {SECRET_TOKEN}\n",
                capture.read_text(encoding="utf-8"),
            )
            self.assertNotIn(SECRET_TOKEN, result.stdout)
            self.assertNotIn(SECRET_TOKEN, result.stderr)


def python_executable() -> str:
    return os.environ.get("PYTHON", "python3")


if __name__ == "__main__":
    unittest.main()
