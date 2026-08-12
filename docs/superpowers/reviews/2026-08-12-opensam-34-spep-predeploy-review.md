# Review: OPENSAM-34 `pep` / `spep` predeploy target normalization

Scope: `tools/ops/predeploy_go_check.sh`, `tools/ops/predeploy_go_check_contract_test.sh`, and `.github/workflows/predeploy-go-check.yml` — changed areas `tools/` and `.github/workflows/` for the manual, read-only predeploy grader.
Verdict: cleared

## Independent re-review

The initial independent review found that the new `spep` success cases did not
exercise the complete read-only Docker/SQL assertion, whose expected targets
were still hard-coded to `s1`. It also required the public `pep` to internal
`spep` mapping to be explicit.

The remediated diff was independently re-reviewed by
`/root/fix_opensam34_spep_predeploy_target/spep_predeploy_rereview` and was
**CLEARED**. The reviewer confirmed all of the following on the remediated
files:

- `pep` and `spep` both derive to internal `spep`.
- Both positive cases assert the derived env/container targets and the complete
  parameterized read-only command contract.
- Traversal and injection inputs fail before Docker or server-env-file access.
- Existing `s<number>` input acceptance remains intact.
- The workflow remains `workflow_dispatch` only and invokes a read-only
  predeploy grader.

## Canonical mapping and safety evidence

The existing promotion and reset workflows establish the canonical relation:
`.github/workflows/promote-game-server.yml:179` derives
`internal_server="s${public_server}"`, and
`.github/workflows/reset-game-server.yml:109` derives
`INTERNAL_SERVER="s${PUBLIC_SERVER}"`. Therefore the promoted public server
`pep` is the existing internal Compose/container target `spep`; this change does
not modify those workflows.

`canonical_internal_server` in `tools/ops/predeploy_go_check.sh:29-37` accepts
only exact `pep` or `spep` for that mapping, otherwise preserves the existing
`^s[0-9][A-Za-z0-9_-]*$` internal-server contract. All env-file and container
names are subsequently derived from the normalized value. The hermetic contract
test covers `pep -> spep` and `spep -> spep`, then runs the complete
parameterized `assert_read_only_commands spep` assertion for each.

Negative cases `../s1`, `spep/../s1`, and `spep;touch` assert invalid-input exit
status 2 and assert that neither Docker nor the server env file was reached.

## TDD and validation evidence

- Initial RED: the new `pep` contract case failed against the old grader with
  `FAIL: expected success for success spep pep` because the old input gate
  accepted only `s<number>`.
- Mapping mutation: temporarily removing `pep` from the normalization branch
  made direct `pep` input fail with `NO-GO: invalid input`, then restoring the
  mapping returned the positive case to green.
- Review-remediation RED: adding the `spep` read-only assertion while retaining
  its former `s1`-only matcher failed with
  `FAIL: grader invoked an unsafe docker command shape: inspect --format {{.State.Running}} spep-game-api`.
  Parameterizing the matcher and assertions by expected internal target made it
  green again.
- Observed before this review artifact: `bash -n` passed for both Bash files,
  Ruby YAML parsing printed `YAML_OK`,
  `bash tools/ops/predeploy_go_check_contract_test.sh` printed
  `PASS: predeploy-go-check hermetic contract`, and `git diff --check` passed.

## Release boundary

This evidence is hermetic and source-derived only. No workflow dispatch,
production predeploy check, deployment, merge, or secret access occurred. CI is
intentionally left for the PR after this local validation.
