# GCP production migration review

Date: 2026-08-02
Scope: PR #352 `.github/workflows/` production workflows, active operator documentation, compatibility deploy surface, and PR review automation; base `1e540410bde7351e3717209e17f65838d356d42b`, reviewed implementation head `840bfa85d576c8441c9ab60f1d94f7cb12d1ad4f`.
Reviewer: CodeRabbit and Codex GitHub mention reviews, followed by independent LazyCodex code-quality and gate reviews.
Verdict: cleared

## Review rounds and remediation

1. CodeRabbit reviewed `09426e70a9e9e413f84a1055a0e86124299a5948`
   after the PR mention. It found that `README.md` still named EC2; the finding
   was accepted and fixed in `4a0fda75a8c8d310e4e16b0b86c3f18591588721`.
2. Codex reviewed `4a0fda75a8c8d310e4e16b0b86c3f18591588721`
   after the PR mention and posted no actionable finding.
3. Codex reviewed `c85ec358f65d5255e0518f47ba11ba5089d84511`
   after the PR mention and posted no actionable finding. The separate
   adversarial gate still found stale EC2 guidance in active agent documents;
   those files were updated in `840bfa85d576c8441c9ab60f1d94f7cb12d1ad4f`.

The independent reviews also caught production workflows that still selected
`ec2-prod`, stale provider-specific defaults in `scripts/deploy.sh`, and active
operator documents that described the retired EC2 path. Every valid finding
was fixed. The final exact-SHA gate review at `840bfa85d576c8441c9ab60f1d94f7cb12d1ad4f`
returned `APPROVE / cleared`.

## Final evidence

- The source repository runner API reports `gcp-prod-opensamguk` online with
  `self-hosted`, `Linux`, `X64`, and `gcp-prod` labels.
- All production workflows select `gcp-prod`; changed workflow YAML parses.
- `scripts/deploy.sh` passes `bash -n`, uses provider-generic deploy arguments,
  and its no-host usage boundary exits before making a connection.
- The active deployment documents describe the GHCR build/push, VM-local
  runner, `opensamguk-docker` shared-stack sync, nginx-last restart, and game
  server image-pin preservation.
- `git diff --check` passes. The PR CI JVM and both web jobs passed at
  `c85ec358f65d5255e0518f47ba11ba5089d84511`; the agent-system job failed only
  because this PR-visible critique artifact did not yet exist.

## Residual risks

- Dated session handoffs and historical incident records still mention the old
  EC2 deployment. They remain historical evidence and are not active operator
  instructions.
- The Claude action uses Anthropic's supported `sonnet` alias, but the action
  intentionally skips a PR that changes its own workflow file. Its next real
  review is therefore deferred to the next PR.
- Live-domain and shared-stack health are post-merge deployment gates and are
  not asserted by this code-review artifact.
