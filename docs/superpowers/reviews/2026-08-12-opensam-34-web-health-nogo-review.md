# Review: OPENSAM-34 web-game health No-Go remediation

Scope: `docker/web-game.Dockerfile`, `tools/ops/predeploy_go_check.sh`, and
`tools/ops/predeploy_go_check_contract_test.sh` — the web-game bind contract
and D4-33's read-only health probe.
Verdict: cleared

## Failure linkage and root cause

GitHub Actions run `31556833770` accepted the Linux/X64 runner metadata and
then stopped at `NO-GO: D4-33 web health check failed`. The prior probe used
`wget -qO /dev/null http://localhost:3001/` inside the web-game container.

The standalone Next.js server reads `HOSTNAME` when selecting its bind address,
while Docker injects `HOSTNAME` with the container ID. A controlled
`node:22-alpine` reproduction showed a service bound to that hostname rejects
container loopback and accepts the container IP. A public read-only request to
`https://sam.peppone.dev/game/pep` returned HTTP 200, so the evidence does not
indicate a live-service outage.

The remediation starts the actual web-game process with
`HOSTNAME=0.0.0.0`, rather than a Dockerfile `ENV` value which Docker overrides
at runtime. D4-33 now probes the service's dedicated
`127.0.0.1:3001/api/health` endpoint and requires a parsed top-level JSON
`status` value of `UP`.

## Independent re-review

`/root/fix_opensam34_web_health_nogo/review_web_health_nogo` initially blocked
the diff because a regular expression could accept malformed JSON. The grader
now parses the payload with the Node runtime already present in the web-game
container and rejects null, arrays, non-objects, non-`UP` statuses, and invalid
JSON. The reviewer then required the hermetic double to execute the actual
inner `sh -ceu` command rather than short-circuiting it. The final independent
re-review was **CLEAR / APPROVE**, confirming:

- Valid `DOWN` JSON and malformed JSON both reach the real `JSON.parse` path.
- A failed `wget` is covered separately.
- The command-level `HOSTNAME=0.0.0.0` bind remains correct.
- No critical, important, medium, or minor findings remain.

## TDD and runtime evidence

- RED: the new contract assertion failed against the old grader with
  `FAIL: grader invoked an unsafe docker command shape: exec s1-web-game wget -qO /dev/null http://localhost:3001/`.
- GREEN: `bash tools/ops/predeploy_go_check_contract_test.sh` printed
  `PASS: predeploy-go-check hermetic contract` after the final malformed,
  `DOWN`, and unavailable-response cases were added.
- Exact rebuilt `docker/web-game.Dockerfile` image: an in-container
  `wget -qO- http://127.0.0.1:3001/api/health` returned
  `{"status":"UP","app":"web-game"}`.
- The exact production parser command completed successfully against that
  response and rejected `{"status":"UP",not-json}`.
- `bash -n` passed for both Bash files and `git diff --check` passed.

## Release boundary

All Docker containers/images used for the local proof were removed afterward.
No production write, workflow dispatch, deployment, merge, secret access, or
predeploy rerun occurred. The D4-33 rerun remains an operator action after the
reviewed change is merged and deployed.
