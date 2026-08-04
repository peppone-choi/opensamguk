# Independent review — OPENSAM-34 predeploy Go grader

## Scope

- Manual workflow: `.github/workflows/predeploy-go-check.yml`.
- Read-only grader and hermetic contract:
  `tools/ops/predeploy_go_check.sh` and
  `tools/ops/predeploy_go_check_contract_test.sh`.
- Closeout documentation only. No EC2/prod access, workflow dispatch,
  `.env*`/secret access, commit, push, PR, merge, deploy, or Jira mutation is
  in scope.

## Review boundary

This review clears the **local grader implementation and its documented
contract**. It does not clear D4-31~35 as production observations. The actual
`ec2-prod` runner was observed offline in both repositories, Jira remains
`할 일`, and no production output exists.

## Initial independent review: fix-required findings and remediations

1. **Immutable-ref and checkout ordering.** The initial review required the
   workflow to reject unsafe raw refs before any checkout and to avoid exposing
   an unvalidated input directly to a shell. The remediation adds a step-level,
   env-only validator before checkout; it accepts only a full SHA or canonical
   semver, namespaces tags under `refs/tags/`, feeds checkout only its validated
   output, pins `actions/checkout`, and sets `persist-credentials: false`.
2. **Input and health validation.** The initial review required fail-closed
   positive integer bounds, a strict full health response, and the correct
   health client. The remediation preserves signed-64-bit/percent bounds and
   uses `curl -fsS` with a strict top-level `{"status":"UP"}` check for the
   actuator endpoints.
3. **Read-only production observation.** The initial review required the
   SQL surface to be demonstrably read-only. The remediation uses
   `PGOPTIONS=-c default_transaction_read_only=on` for every `psql` call; the
   hermetic contract rejects mutating SQL and unsupported Docker verbs.
4. **Authoritative scenario identity.** The initial review rejected a guessed
   server join. The remediation checks the selected `world_id` through the
   canonical `world_state.meta ->> 'serverId'` relation and requires both
   `world_state.scenario_code` and `ng_games.env.scenario_code` to match.
5. **Capacity and V29 fidelity.** The initial review required robust `df -Pk`
   parsing for both root paths plus an exact, not merely similarly named, V29
   index check. The remediation rejects malformed/failed `df` output and
   validates `log_entry_year_month_idx` validity, readiness, and its exact
   current world-scoped definition.

## Final independent re-review

The final re-review found no new blocker. It confirmed the pre-checkout
env-only `validate-ref` step, SHA/semver validation and `refs/tags` namespace,
validated checkout output with `persist-credentials: false`, and contract
coverage for ordering plus raw-ref unsafe inputs. The prior integer, `curl`,
read-only SQL, canonical `serverId`, `df`, and exact-index remediations remain
intact.

## Observed local evidence

- Both predeploy scripts passed `bash -n`; the hermetic contract passed.
- Workflow YAML parse passed, as did scoped untracked-file whitespace checking.
- Fresh migration Gradle evidence is V29 `2/0/0/0` and V32 `9/0/0/0`, with
  `BUILD SUCCESSFUL in 2m 2s`.
- `scripts/agent/verify-changes.sh` classification ran, but its `--run` form
  was not rerun for OPENSAM-34; it is therefore not claimed as fresh execution
  evidence for this ticket.

## Remaining external block and tooling baseline

The actual runner observation remains incomplete until EC2 is resumed and the
user explicitly authorizes a manual dispatch with all six approved inputs.
Until then, a local contract pass is not a production pass. Repeated generic
Fablize tool-failure notices during successful read-only discovery are isolated
as an external tooling baseline; direct scoped evidence above, not those
notices, supports this review.

ADR-LITE-026 remains mandatory for any later PR: three separate PR-conversation
review-agent rounds, remediation and revalidation for each finding, then an
explicit human merge approval.

Verdict: cleared
