# V2 Realtime Battle Foundation Ticket Ledger

## Source binding

- Status: created and cross-linked on 2026-07-30
- Jira project: [OPENSAM](https://pepponechoi-jira.atlassian.net/jira/software/projects/OPENSAM)
- Common foundation Epic: [OPENSAM-25](https://pepponechoi-jira.atlassian.net/browse/OPENSAM-25)
- GitHub Epic mirror: [#167](https://github.com/peppone-choi/opensamguk/issues/167)
- Approved implementation plan:
  `docs/superpowers/plans/2026-07-30-v2-realtime-battle-foundation-implementation-plan.md`
- Plan commit:
  `fbfe095f19b147869b3c7594a1c14f1c7138a4b0`
- Plan SHA-256:
  `ff0e79977ff6e0f02fd80401c76ed0f030f0534c0ccb44f1a46908428feb47b0`
- Approved design:
  `docs/superpowers/specs/2026-07-30-v2-realtime-battle-session-command-replay-design.md`
- Independent review:
  `docs/superpowers/reviews/2026-07-30-v2-battle-foundation-plan-review.md`
  (`CLEAR`)

The local plan and design remain the source of truth. Jira is the execution
record. GitHub issues are public mirrors of the same approved scope and
Given-When-Then acceptance criteria.

## Common foundation execution chain

| Order | Jira | GitHub | Scope |
|---|---|---|---|
| F0 | [OPENSAM-156](https://pepponechoi-jira.atlassian.net/browse/OPENSAM-156) | [#333](https://github.com/peppone-choi/opensamguk/issues/333) | V2 campaign predecessor evidence gate |
| F1 | [OPENSAM-157](https://pepponechoi-jira.atlassian.net/browse/OPENSAM-157) | [#334](https://github.com/peppone-choi/opensamguk/issues/334) | Isolated modules and battle-engine skeleton |
| F2 | [OPENSAM-158](https://pepponechoi-jira.atlassian.net/browse/OPENSAM-158) | [#335](https://github.com/peppone-choi/opensamguk/issues/335) | Versioned contracts and adapter/artifact registries |
| F3 | [OPENSAM-159](https://pepponechoi-jira.atlassian.net/browse/OPENSAM-159) | [#336](https://github.com/peppone-choi/opensamguk/issues/336) | Deterministic kernel and pure reducers |
| F4 | [OPENSAM-160](https://pepponechoi-jira.atlassian.net/browse/OPENSAM-160) | [#337](https://github.com/peppone-choi/opensamguk/issues/337) | V2 schema, one-shot provisioner, DML ownership |
| F5 | [OPENSAM-161](https://pepponechoi-jira.atlassian.net/browse/OPENSAM-161) | [#338](https://github.com/peppone-choi/opensamguk/issues/338) | Durable epoch-fenced battle stores |
| F6 | [OPENSAM-162](https://pepponechoi-jira.atlassian.net/browse/OPENSAM-162) | [#339](https://github.com/peppone-choi/opensamguk/issues/339) | Campaign locks, handoff, deferred effects |
| F7 | [OPENSAM-163](https://pepponechoi-jira.atlassian.net/browse/OPENSAM-163) | [#340](https://github.com/peppone-choi/opensamguk/issues/340) | Exactly-once results and reinforcements |
| F8 | [OPENSAM-164](https://pepponechoi-jira.atlassian.net/browse/OPENSAM-164) | [#341](https://github.com/peppone-choi/opensamguk/issues/341) | Bounded actor, epoch lease, recovery |
| F9 | [OPENSAM-165](https://pepponechoi-jira.atlassian.net/browse/OPENSAM-165) | [#342](https://github.com/peppone-choi/opensamguk/issues/342) | Commit-before-ACK command ingress |
| F10 | [OPENSAM-166](https://pepponechoi-jira.atlassian.net/browse/OPENSAM-166) | [#343](https://github.com/peppone-choi/opensamguk/issues/343) | JoinTicket, WebSocket, faction-safe projection |
| F11 | [OPENSAM-167](https://pepponechoi-jira.atlassian.net/browse/OPENSAM-167) | [#344](https://github.com/peppone-choi/opensamguk/issues/344) | Disconnect AI, deputy, reinforcement, deadlines |
| F12 | [OPENSAM-168](https://pepponechoi-jira.atlassian.net/browse/OPENSAM-168) | [#345](https://github.com/peppone-choi/opensamguk/issues/345) | Replay, fault, and 32-formation server gates |
| F13 | [OPENSAM-169](https://pepponechoi-jira.atlassian.net/browse/OPENSAM-169) | [#346](https://github.com/peppone-choi/opensamguk/issues/346) | Local smoke and common-foundation release gate |

Jira `Blocks` links enforce `F0 -> F1 -> ... -> F13`. The hard predecessors
`OPENSAM-149`, `OPENSAM-35`, `OPENSAM-43` through `OPENSAM-48`, and
`OPENSAM-56` all block F0.

## Required follow-up Epics

| Track | Jira | GitHub | Start condition |
|---|---|---|---|
| Land | [OPENSAM-170](https://pepponechoi-jira.atlassian.net/browse/OPENSAM-170) | [#347](https://github.com/peppone-choi/opensamguk/issues/347) | OPENSAM-25 complete; separate approved adapter spec and plan |
| Siege | [OPENSAM-171](https://pepponechoi-jira.atlassian.net/browse/OPENSAM-171) | [#348](https://github.com/peppone-choi/opensamguk/issues/348) | OPENSAM-25 complete; separate approved adapter spec and plan |
| Naval | [OPENSAM-172](https://pepponechoi-jira.atlassian.net/browse/OPENSAM-172) | [#349](https://github.com/peppone-choi/opensamguk/issues/349) | OPENSAM-25 complete; separate approved adapter spec and plan |
| Three.js 2.5D | [OPENSAM-173](https://pepponechoi-jira.atlassian.net/browse/OPENSAM-173) | [#350](https://github.com/peppone-choi/opensamguk/issues/350) | OPENSAM-25 complete; renderer/HUD/assets plan and browser gate |
| G6 launch decision | [OPENSAM-174](https://pepponechoi-jira.atlassian.net/browse/OPENSAM-174) | [#351](https://github.com/peppone-choi/opensamguk/issues/351) | All adapter, server, browser, and multiplayer evidence submitted |

OPENSAM-25 blocks all five follow-up Epics. OPENSAM-41 is related to
OPENSAM-173 so the existing world/map Three.js proof can be reused without
collapsing it into the battle renderer scope.

## Historical broad tickets

- OPENSAM-58 / GitHub #200 remains the historical renderer/deployment/order
  bundle and is related to OPENSAM-170.
- OPENSAM-59 / GitHub #201 remains the historical runtime/replay bundle and is
  related to OPENSAM-156.
- OPENSAM-60 / GitHub #202 remains the historical campaign integration bundle
  and is related to OPENSAM-163.

These tickets were neither closed nor deleted. Comments direct future workers
to the approved F0-F13 and follow-up Epic boundaries to prevent duplicate
implementation.

## Tracker verification

- Jira creation: 14 common-foundation tasks and 5 follow-up Epics succeeded.
- Jira hierarchy: every F0-F13 task has parent OPENSAM-25.
- Jira links: 13 sequential Blocks, 9 hard-predecessor Blocks, 5 follow-up
  Blocks, and 4 Relates links succeeded.
- Jira reverse links: all 19 new items, OPENSAM-25, and OPENSAM-58 through
  OPENSAM-60 received tracker comments successfully.
- GitHub creation: mirrors #333 through #351 succeeded with `jira-mirror`;
  follow-up Epics also carry `epic`.
- GitHub Epic #167 contains the approved F0-F13 and follow-up checklists.
- Visibility note: GitHub reported `peppone-choi/opensamguk` as public at
  creation time. This conflicts with the repository onboarding statement that
  the repository should remain private until IP review and requires an owner
  visibility decision outside this ticketing task.
