# Public Alpha Roadmap and Issue Portfolio Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rebaseline OpenSamguk around complete pre-alpha commands, province strategy, WEGO combat, continuous onboarding, and an issue model that selects the next implementation slice by gate, dependencies, urgency, and importance.

**Architecture:** This is a portfolio plan, not a single code-change plan. A master catalog and release-gate ledger own cross-track identities; seven bounded implementation plans deliver command foundation, world/map, travel/operations, subordinate people and Bugok, government, WEGO, and onboarding/operations. GitHub and Jira mirror the same classification without redefining product rules.

**Tech Stack:** Markdown product specs, GitHub Issues/labels, Jira issues/labels/priority, Kotlin/Spring/JDBC, React/TypeScript/Canvas, Python map generators, JUnit, Vitest, Playwright.

**Spec:** `docs/superpowers/specs/2026-08-27-public-alpha-rebaseline-design.md`

## Global Constraints

- Public alpha is open to anyone but begins only after every canonical command is `VERIFIED`.
- Roadmap stages have no calendar dates.
- Importance and urgency remain independent fields.
- Tutorial and help ship with each feature and command, not as a release-hardening tail.
- Every implementation task assesses and updates affected README, user/admin/design docs, and
  `CLAUDE.md`/`AGENTS.md`; no-impact decisions are recorded explicitly.
- Subordinate people and Bugok are separate aggregates.
- Strategic map is isometric; selection map is flat; both consume one province SSoT.
- Battle commands use deterministic WEGO for land, siege, and naval adapters.
- GitHub and Jira issues link to the spec and never redefine its counts or rules.

---

### Task 1: Publish the rebaseline documentation

**Files:**
- Create: `docs/superpowers/specs/2026-08-27-public-alpha-rebaseline-design.md`
- Create: `docs/superpowers/plans/2026-08-27-public-alpha-roadmap-and-issue-plan.md`
- Modify: `docs/design/roadmap.md`

**Interfaces:**
- Produces the product stages, command completion state, issue taxonomy, and public-alpha reset policy consumed by every implementation plan and tracking issue.

- [ ] Check the three documents for conflicting realtime, 3D-first, isometric-removal, late-help, invite-alpha, or calendar-date language.
- [ ] Run `rg -n '실시간 전투|3D 기본|아이소 폐기|초대|TBD|TODO'` across the three files and resolve every normative conflict.
- [ ] Verify every stage includes an evidence gate and public alpha follows full command, WEGO, campaign, help, and tutorial completion.
- [ ] Commit the documentation as one reviewable rebaseline change.

### Task 2: Establish issue classification fields

**Files:**
- Modify: GitHub repository labels
- Modify: Jira project labels and issue priority fields where available
- Create: `reports/opensamguk/tasks/2026-08-27-public-alpha-roadmap-rebaseline.md`

**Interfaces:**
- Produces labels `importance-{critical,high,medium,low}`, `urgency-{now,next,later}`,
  `critical-path`, `gate-public-alpha`, `gate-public-beta`, and `stage-{0..10}`.
- Each rebaselined issue body contains `Stage`, `Importance`, `Urgency`, `Gate`, `Depends on`,
  `Blocks`, `Disposition`, and `Next selection rule`.

- [ ] Create missing GitHub labels with stable descriptions and colors.
- [ ] Create the public-alpha master tracking issue and link the spec and roadmap.
- [ ] Create or revise the matching Jira master issue; use Jira standard priority only as a view convenience and retain independent importance/urgency labels.
- [ ] Record every external mutation and any connector failure in the task report.

### Task 3: Rebaseline the critical existing epics

**Files:**
- Modify: GitHub/Jira command, map, operation, Bugok, subordinate-person, governance, battle, and onboarding epics

**Interfaces:**
- Consumes the labels and body schema from Task 2.
- Produces a dependency-ordered portfolio with no realtime/3D/invite-alpha/late-onboarding premise.

- [ ] Rebaseline contract and catalog issues: OPENSAM-30, OPENSAM-73..77 and GitHub mirrors.
- [ ] Rebaseline world and operation issues: OPENSAM-200, OPENSAM-213..215 and GitHub mirrors.
- [ ] Rebaseline Bugok and subordinate-person issues: OPENSAM-48, OPENSAM-61 and GitHub mirrors; replace `가신 전체` wording with `휘하 인물` and keep Bugok private troops.
- [ ] Rebaseline council, identity, court, office, and vassal issues: OPENSAM-62..69 and GitHub mirrors.
- [ ] Rebaseline battle issues: OPENSAM-25, OPENSAM-49, OPENSAM-56..60, OPENSAM-156..174 and GitHub mirrors from realtime deadlines to WEGO round contracts.
- [ ] Rebaseline onboarding/hardening issues: OPENSAM-29, OPENSAM-70..72 so help/tutorial completion is embedded upstream and the epic owns only cross-feature onboarding and operations gates.

### Task 4: Create bounded implementation plans

**Files:**
- Create: `docs/superpowers/plans/2026-08-27-command-foundation.md`
- Create: `docs/superpowers/plans/2026-08-27-province-world-and-map.md`
- Create: `docs/superpowers/plans/2026-08-27-travel-operation-infrastructure.md`
- Create: `docs/superpowers/plans/2026-08-27-subordinates-and-bugok.md`
- Create: `docs/superpowers/plans/2026-08-27-government-and-vassalage.md`
- Create: `docs/superpowers/plans/2026-08-27-wego-battle.md`
- Create: `docs/superpowers/plans/2026-08-27-onboarding-campaign-and-public-alpha.md`

**Interfaces:**
- Each plan consumes the rebaseline spec and canonical command ledger.
- Each plan produces independently reviewable slices whose final task moves owned commands through `VERIFIED`.

- [ ] Map current source files, tests, and unresolved tickets for each bounded track.
- [ ] Write TDD-sized tasks with exact files, types, commands, expected RED/GREEN results, and commit boundaries.
- [ ] Put help, tutorial, AI, replay, and recovery in the same command-family task instead of tail tasks.
- [ ] Cross-check plan dependencies against issue `Depends on` and `Blocks` fields.

### Task 5: Verify and report the roadmap rebaseline

**Files:**
- Modify: `reports/opensamguk/tasks/2026-08-27-public-alpha-roadmap-rebaseline.md`

**Interfaces:**
- Produces a closed audit of local documents, external issues, verification, commit, and remaining risks.

- [ ] Run Markdown link and stale-premise searches over the changed documents.
- [ ] Export GitHub issue labels/bodies for the rebaselined set and verify every issue has Stage, Importance, Urgency, Gate, and dependencies.
- [ ] Query Jira for the same set and record any divergence from GitHub.
- [ ] Record the document commit, issue URLs, verification output, and unresolved Jira connector or authentication risk.
- [ ] Verify `README.md` reads as a public project entrance without private planning context, and
  confirm `CLAUDE.md` and `AGENTS.md` carry the living-document completion rule.
