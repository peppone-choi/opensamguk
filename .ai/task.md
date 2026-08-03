# Current Task

## 2026-08-03 — RTK14 full-roster scenario and five-stat surfaces

- Status: implementation, real-workbook checks, and all known PR-finding remediation are green, including source `general_ex` RNG isolation, V36 possession request/reload reconciliation, and shared effective-scenario resolution for V26. Source PR #356 and Docker PR #25 each need three fresh mention reviews on their exact SHA, then merge, reseed, and live verification. Docker PR #24 is merged and its deploy workflow succeeded.
- Goal: use every officer row from `/Users/apple/Desktop/삼국지14 무장정보.xlsx` in every populated runtime scenario, preserving one-to-one duplicate identities, replacing the five stats, and applying birth/appearance/death lifecycle dates. Existing runtime-only officers remain with reviewed politics/charm overrides.
- In scope:
  - the local RTK14 source-data builder, validation/reporting, and private generated scenario artifacts;
  - runtime scenario tuple decoding and active/deferred appearance scheduling;
  - deferred NPC creation carrying politics/charm;
  - possession routing plus all affected API/UI five-stat surfaces;
  - source and orchestration deployment wiring for private generated scenarios;
  - tests, three sequential mention-triggered PR reviews with fixes, merge, deployment, and live verification.
- Non-goals: tracked generated Koei data, `legacy/**` or golden writes, unrelated archive universes under `data/extracted/scenario`, and secret disclosure.
- Allowed files: `tools/rtk14/**`, relevant scenario seed/import and deferred-NPC logic/tests in `infra/**`, `logic/**`, `app/game-engine/**`, affected `app/game-api/**` DTO/controller/tests, affected `web/game/**` and `web/gateway/**`, `.github/workflows/deploy.yml`, `tools/agent-system/check.py`, `.ai/{task,current-state,ownership,handoff}.md`, and one review artifact. The sibling Docker worktree may change only scenario mount Compose/deployer contract files.
- Acceptance evidence:
  - exactly 1,000 workbook rows are represented once per populated runtime scenario, including all duplicate-name rows; settings-only scenarios remain byte-identical;
  - five stats and birth/appearance/death lifecycle values round-trip through source JSON and generated tuples; no unresolved workbook row remains;
  - future appearances retain politics/charm and occur on the validated effective appearance year;
  - focused Python, logic, infra, engine, API, frontend, typecheck, static workflow, and diff gates pass with XML/tail evidence;
  - three fresh PR review rounds per PR have no unresolved `fix-required`; deployment and live DB/UI observations confirm the result.
- Approved external actions: create/update the private RTK14 GitHub Actions secret without printing it, commit, push, PR, three mention reviews, merge, deploy, and overwrite/reseed the target scenario as requested. Any broader data deletion remains prohibited.

## 2026-08-02 — canonical alphanumeric game-server ID release closeout

- PR: #354 — `https://github.com/peppone-choi/opensamguk/pull/354`.
- Status: the first Codex review found two valid issues against
  `8d1a64fee0651b2977f13af27eb6b91b43577342`. They were remediated in exact
  code SHA `1683100447be91abc6eb2629969c9ee3c16bac5e`; an independent re-review
  of that exact fixed code SHA is **CLEARED**. This source verdict does not
  satisfy the separate fresh three-round PR review requirement.
- Canonical public contract: accept raw `[A-Za-z0-9]+`, canonicalize to
  lowercase `[a-z0-9]+`, and derive the internal Compose/container identity as
  `s` + public ID. Examples: `pep` → `pep` / `spep`; `s1` → `s1` / `ss1`;
  `A1` → `a1` / `sa1`.
- `all`, `main`, and current top-level game route names are reserved to avoid
  control and URL collisions. `current` remains a valid public ID.
- First-review remediation:
  - compatibility Nginx now accepts canonical public paths and derives the
    internal `s<public>` upstream/container name only at that boundary;
  - deploy, promote, and reset workflows now apply matching reserved-public-ID
    guards instead of a one-off `all` check.
- Independent re-review evidence for the fixed SHA: gateway 64 tests; game 220
  tests; FrontInfo XML `27/0/0`; Admin XML `26/0/0`; and the fixed-SHA
  workflow/Nginx/Compose contract review.
- Approved external actions remain those of the governing OPENSAM-34 task:
  GCP changes/configuration, commit, push, PR creation, three mention-triggered
  reviews, merge, deployment, and live verification. They are authorized scope,
  not completed actions; secrets stay redacted and data deletion is out of
  scope.
- Remaining release gates: Docker repository review/fix; a new three-round
  `@codex` PR review sequence after the next documentation commit/current HEAD;
  then merge, deploy, and live-production verification. No final PR review,
  merge, deployment, or live result is claimed.

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
