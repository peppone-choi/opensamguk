#!/usr/bin/env python3
"""bootstrap-deployer.yml `diagnose` 계약 — 읽기 전용 진단은 **죽지 않고 상태를 찍는다**.

2026-09-16 프로덕션 실측. 실패한 배포 런이 유지보수 창을 잡은 채 죽어 `state=drained` 인
고아 창이 남았고, 이후 모든 공유 스택 배포가 「source deployment requires an initially open
maintenance-v1 controller」 에서 거부됐다(02:55~07:38, 약 5시간). 복구 도구(`release`)는
처음부터 있었는데도 창이 5시간 방치된 이유는 운영자가 가장 먼저 쓰는 읽기 전용 경로가
둘 다 망가져 있었기 때문이다:

1. `diagnose` 분기가 `maintenance_http` **정의보다 위에서** `exit 0` 했다. 즉 이 장애 유형에서
   유일하게 중요한 값(`GET /maintenance` 몸통)을 읽기 전용 경로로는 영영 볼 수 없었다.
2. 컨테이너 로그를 훑는 `grep -aiE … | tail | sed` 가 `set -o pipefail` 아래 있었다. grep 이
   한 건도 못 맞히면 1 을 돌려주므로 잡이 그 자리에서 죽는다 — 런 35069161480 이 실제로
   `opensamguk-web-gateway` 에서 끊겨 뒤 컨테이너도 완료 줄도 못 찍었다. 「걸릴 줄이 없다」는
   정상적인 진단 결과이지 실패가 아니다.

`deploy.yml` 쪽 계약은 tools/ops/test_source_deploy_maintenance.py 가 잡는다. 이 파일은
복구 도구 자체가 장애 중에 쓸 수 있는 상태로 남아 있는지를 잡는다.
"""

from __future__ import annotations

import os
from pathlib import Path
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = ROOT / ".github" / "workflows" / "bootstrap-deployer.yml"
STEP_NAME = "Replace the deployer and verify atomic idle admission"
DRAINED_BODY = '{"capability":"maintenance-v1","state":"drained"}'


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


class BootstrapDeployerDiagnoseTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp_dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp_dir.cleanup)
        self.case_root = Path(self.temp_dir.name)
        self.fake_bin = self.case_root / "bin"
        self.fake_bin.mkdir()
        self.home = self.case_root / "home"
        (self.home / "opensamguk-docker").mkdir(parents=True)
        self.script = self.case_root / "workflow.sh"
        self.script.write_text(extract_run_script(), encoding="utf-8")
        self._write_fake("flock", "exit 0")
        self._write_fake("sudo", 'exec "$@"')

    def _write_fake(self, name: str, body: str) -> None:
        path = self.fake_bin / name
        path.write_text(f"#!/usr/bin/env bash\n{body}\n", encoding="utf-8")
        path.chmod(0o755)

    def _write_docker(self, *, root_cause_lines: str, maintenance_body: str) -> None:
        """`docker` 스텁. root_cause_lines 가 비면 grep 이 한 건도 못 맞히는 상황이다."""
        self._write_fake(
            "docker",
            rf'''
            if [[ "${{1:-}}" == exec ]]; then
              # maintenance_http: docker exec opensamguk-deployer python3 -c '<src>' METHOD PATH
              printf '%s' {maintenance_body!r}
              exit 0
            fi
            if [[ "${{1:-}}" == ps ]]; then
              printf 'opensamguk-deployer\tUp 5 hours\n'
              exit 0
            fi
            if [[ "${{1:-}}" == inspect ]]; then
              if [[ "${{2:-}}" == --format ]]; then
                printf 'running restarts=0 health=none\n'
              fi
              exit 0
            fi
            if [[ "${{1:-}}" == logs ]]; then
              printf '%s' {root_cause_lines!r}
              exit 0
            fi
            exit 0
            ''',
        )

    def _run(self, action: str, confirm: str) -> subprocess.CompletedProcess[str]:
        env = dict(os.environ)
        env.update({
            "PATH": f"{self.fake_bin}:{env['PATH']}",
            "HOME": str(self.home),
            "INPUT_ACTION": action,
            "INPUT_CONFIRM": confirm,
            "GHCR_TOKEN": "unused-in-diagnose",
            "GHCR_USER": "unused-in-diagnose",
        })
        return subprocess.run(
            ["bash", str(self.script)],
            env=env, capture_output=True, text=True, timeout=120,
        )

    def test_diagnose_reports_the_maintenance_state(self) -> None:
        """읽기 전용 진단이 `GET /maintenance` 몸통을 찍는다 — 고아 창을 알아볼 수 있어야 한다."""
        self._write_docker(
            root_cause_lines="Caused by: java.lang.IllegalStateException\n",
            maintenance_body=DRAINED_BODY,
        )
        result = self._run("diagnose", "DIAGNOSE")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn(f"GET /maintenance rc=0 body={DRAINED_BODY}", result.stdout)
        self.assertIn("Diagnose complete", result.stdout)

    def test_diagnose_survives_logs_without_root_cause_lines(self) -> None:
        """걸릴 줄이 하나도 없어도 끝까지 간다 — grep 의 「no match」는 실패가 아니다."""
        self._write_docker(
            root_cause_lines="nothing interesting here\nstill nothing\n",
            maintenance_body=DRAINED_BODY,
        )
        result = self._run("diagnose", "DIAGNOSE")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("Diagnose complete", result.stdout)
        self.assertIn("opensamguk-nginx", result.stdout)

    def test_wrong_confirmation_touches_nothing(self) -> None:
        self._write_docker(root_cause_lines="", maintenance_body=DRAINED_BODY)
        result = self._run("diagnose", "NOPE")
        self.assertEqual(2, result.returncode)
        self.assertIn("confirmation phrase mismatch", result.stderr)


if __name__ == "__main__":
    unittest.main()
