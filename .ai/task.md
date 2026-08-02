# Current Task

## 2026-08-02 — canonical alphanumeric game-server ID release closeout

- Status: source review cleared at exact SHA
  `8d1a64fee0651b2977f13af27eb6b91b43577342`; this closeout records the
  contract and evidence only. It does **not** record a merge, deployment, or
  live-production verification.
- Canonical public contract: accept raw `[A-Za-z0-9]+`, canonicalize to
  lowercase `[a-z0-9]+`, and derive the internal Compose/container identity as
  `s` + public ID. Examples: `pep` → `pep` / `spep`; `s1` → `s1` / `ss1`;
  `A1` → `a1` / `sa1`.
- `all`, `main`, and current top-level game route names are reserved to avoid
  control and URL collisions. `current` remains a valid public ID.
- Source-review evidence: gateway 64 tests; game 220 tests; FrontInfo XML
  `27/0/0`; Admin XML `26/0/0`; workflow and Compose contract checks.
- Approved external actions remain those of the governing OPENSAM-34 task:
  GCP changes/configuration, commit, push, PR creation, three mention-triggered
  reviews, merge, deployment, and live verification. They are authorized scope,
  not completed actions; secrets stay redacted and data deletion is out of
  scope.
- Remaining release gates: Docker repository review/fix, three `@codex` PR
  review rounds, merge, deploy, and live-production verification.

## 2026-08-02 OPENSAM-34 — GCP production migration and launch

- Status: in progress; GCP VM/runtime/network/DNS are provisioned, and PR
  review findings are being remediated before the approved merge and deploy.
- User-approved scope:
  - migrate active production GitHub Actions runner selectors and operator
    guidance from the retired EC2 target to GCP Compute Engine
    `e2-standard-2` / `gcp-prod`;
  - configure the GCP production control repository and generated runtime
    secrets without printing their values;
  - request three PR mention-triggered reviews, fix valid findings, then merge
    and deploy;
  - verify `sam.peppone.dev` through Cloudflare and live health routes.
- Approved external actions: GCP resource changes, production configuration,
  commit, push, PR creation, merge, and deployment. Secret values remain
  redacted and data deletion remains out of scope.
- Allowed repository files: production workflows, active deployment/operator
  documentation, the compatibility deploy script/compose header, and this task
  contract. No game logic, golden fixture, legacy source, image pin, or runtime
  secret may enter the diff.
- Completion evidence: matching online `gcp-prod` runners, three successful PR
  review rounds with no unresolved `fix-required`, green targeted syntax/diff
  checks, an observed deployment run, and live domain health.

---

- Status: approved, in progress.
- Updated: 2026-07-25
- Goal: refresh the current documentation and agent harness, with `README.md` as the primary onboarding surface.
- Approved plan:
  - Update `README.md` to match the current July 2026 implementation, local quick start, CQRS consistency/runtime state, and shared/server production model.
  - Update `.claude/HARNESS.md` from its obsolete parity-only/deploy narrative to the current Agent OS, parity, verification, and deployment entry points.
  - Correct the comment-language and deployment wording in the tracked parity skills.
  - Refresh `docs/agent/project-overview.md` so README can link to a current bounded status source.
- Allowed files:
  - `README.md`
  - `.claude/HARNESS.md`
  - `.claude/skills/parity-close/SKILL.md`
  - `.claude/skills/parity-ship/SKILL.md`
  - `docs/agent/project-overview.md`
  - `.ai/task.md`
  - `.ai/current-state.md`
  - `.ai/ownership.md`
  - `docs/superpowers/reviews/2026-07-25-docs-harness-refresh-review.md`
- Acceptance evidence:
  - No obsolete `V1..V10`, `appleboy/ssh-action`, unresolved `commandBlockMs` wiring-gap, or Korean-code-comment claim remains in the refreshed surfaces.
  - README quick start names required environment variables without exposing values or secrets.
  - Production guidance points to `opensamguk-docker` shared/server orchestration and clearly labels repository production compose/manual deploy as compatibility-only.
  - `git diff --check`, path/link checks, and `tools/agent-system/check.py` results are recorded.
- Human approval checkpoints:
  - Commit, push, merge, deploy, data deletion, or secret access remain prohibited without separate approval.
- Non-goals:
  - Runtime/code/schema changes, legacy or golden edits, production operations, and external tracker mutations.
