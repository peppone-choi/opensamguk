# Public Alpha Command Contract Freeze — P-1 through P-15

**Date:** 2026-08-27

**Status:** Stage 0 contract authority

**Tickets:** OPENSAM-73 / GitHub #215, OPENSAM-74 / GitHub #216, OPENSAM-75 / GitHub #217

**Supersedes:** the normative command and battle decisions in
`2026-08-16-v2-contract-freeze-p1-p15.md` where this document says `REVISE` or `REPLACE`.
The older document remains historical evidence.

**Product authority:** `2026-08-27-public-alpha-rebaseline-design.md`

This document freezes contracts only. A catalog row at `DOMAIN_READY` is not evidence that its
handler, UI, AI, help, tutorial, or replay implementation exists.

## 1. Decision crosswalk

| ID | Decision | Disposition | Public-alpha contract |
|---|---|---|---|
| P-1 | Client event consumption | REVISE | Consume typed command, plan, battle-round, notification, and recovery events; names are schema-owned rather than an immutable four-event list. |
| P-2 | Command subject | REVISE | Keep actor and ordering identities but separate subordinate people from Bugok and declare the command layer explicitly. |
| P-3 | Operation | REVISE | Make objectives, route control, supply, deadlines, encounter rules, and aftermath first-class persistent-plan state. |
| P-4 | Deterministic replay | REVISE | Preserve canonical hashing and versioned inputs; replace realtime phase/session assumptions with sealed WEGO rounds. |
| P-5 | Subordinate proposal | REVISE | A named subordinate person owns relationships, roles, missions, and proposals; a Bugok is a separate troop aggregate. Runtime LLM remains forbidden. |
| P-6 | Feudal contract | PRESERVE | Preserve parties, fiefs, tribute, obligations, autonomy, loyalty, breach, and expiry; connect commands through the canonical catalog. |
| P-7 | Command layer boundary | REPLACE | Use five layers: personal ring, chief ring, persistent plan, battle round, and system resolver. |
| P-8 | Catalog evolution | REVISE | Use canonical IDs plus aliases, explicit replacements and migration, and fail-closed unknown lookup. |
| P-9 | Construction | PRESERVE | Preserve durable projects, capability-based facilities, staff/resource/route prerequisites, and separate plan versus field execution. |
| P-10 | Mission command | REVISE | Preserve objectives, risk, behavior, and reports; apply them to deterministic operation and WEGO inputs, not realtime control. |
| P-11 | Product surfaces | REVISE | Command modal, contextual help, searchable manual, tutorial, replay explanation, admin operations, and recovery share registry identities. |
| P-12 | Geography | REVISE | Bind travel, supply, operation, and battle inputs to the province/topology snapshot and its content version. |
| P-13 | Tactical engine | REPLACE | Land, siege, and naval adapters resolve simultaneous sealed WEGO orders and emit deterministic replay. |
| P-14 | Content packs | PRESERVE | Keep versioned pack interfaces and provenance; command availability refers to capabilities rather than hard-coded presentation assets. |
| P-15 | Success gate | REPLACE | Stage 0 is a mechanical catalog-closure gate; later delivery states require evidence for every named surface. |

No P-number from the historical split is silently discarded. The following sections own the
replacement details.

## 2. P-1 — event consumption

`P-1` is **REVISED**. The frontend consumes versioned events linked to `canonicalId` and an
idempotent request or plan identity. The minimum event families are:

- `command.accepted`, `command.rejected`, and `command.resolved`;
- `plan.created`, `plan.progressed`, `plan.interrupted`, and `plan.resolved`;
- `battle.round.sealed`, `battle.round.resolved`, and `battle.replay.published`;
- `notification.created`;
- `recovery.resumed` and `recovery.failed` for durable work.

An implementation may add a typed event through schema review. It must not overload an existing
event name with incompatible payloads. A `202 Accepted` response is not a resolved command.
Navigation never substitutes for result polling or event reconciliation.

## 3. P-2 — command subject

`P-2` is **REVISED**:

```text
actorType: GENERAL | SUBORDINATE_PERSON | BUGOK | FORMATION | OFFICE | NATION | SYSTEM | ADMIN
actorId
orderedByGeneralId?       // identity of the accountable player or AI actor
executionOwnerId
layer: PERSONAL_RING | CHIEF_RING | PERSISTENT_PLAN | BATTLE_ROUND | SYSTEM_RESOLVER
reservationScope?         // required only for a reserved ring
idempotencyKey
```

A subordinate person and a Bugok are never interchangeable subjects. A lieutenant may command a
Bugok through an explicit assignment relation. `SYSTEM` and `ADMIN` commands require named
authority and audit policy; neither is a player-command fallback.

## 4. P-3 — operation

`P-3` is **REVISED**. An operation is durable state, not a battle synonym:

```text
operationId, ownerId, objectiveType, targetProvinceIds[], targetEdgeIds[]
deadline, progressRules, supplyRequirements, interruptionRules
participants[], roles[], route[], rules
status, version, createdByCommandId
```

Objective types include occupation, passage, route control, supply interdiction, blockade, and
relief. Resolution order is:

```text
declare objective → seal WEGO orders → resolve movement/control/progress
→ resolve intercept or encounter → battle only on contact → occupation/aftermath
```

Avoiding contact is not a free victory or defeat. Progress requires actual occupation, route
control, uninterrupted work, and valid supply. Travel and forced march remain non-combat plans even
when an encounter can interrupt them.

## 5. P-4 — deterministic command and battle replay

`P-4` is **REVISED**. Determinism uses a persistence-neutral body:

```text
ReplayEnvelope
  replayId, worldId, operationId?, battleId?, createdAt, persistedLogEntryIds[]

DeterministicReplayBody
  worldSnapshotHash, orderedInputHash, seed
  contentVersion, balanceVersion, geographyVersion, commandCatalogVersion
  rounds[]: sealedOrders, environmentInput, rngDraws, orderedStateDiff, events

deterministicReplayHash = hash(canonicalSerialize(DeterministicReplayBody))
```

The same snapshot, ordered inputs, seed, and versions produce the same body, hash, and outcome.
Persistence IDs and wall-clock creation timestamps are excluded from equality. Land, siege, and
naval resolution use the same envelope and may supply mode-specific round bodies. Real-time-like
playback changes presentation speed only.

## 6. P-5 and P-6 — subordinate people, Bugok, and feudal contracts

`P-5` is **REVISED**. A subordinate proposal belongs to a named person:

```text
subordinatePersonId, proposalType, targetId
score, confidence, evidence[], biasFactors[], expiresAt, status
```

Rules and recorded features generate proposals; runtime LLM generation is forbidden. Recruitment,
oath, release, role, mission, and delegation mutate the subordinate-person aggregate. Formation,
replenishment, training, split, merge, commander assignment, and dissolution mutate the Bugok
aggregate. Cross-aggregate commands name both identities and produce explicit transfer or
assignment events.

`P-6` is **PRESERVED**:

```text
lordSubfactionId, vassalSubfactionId, fiefIds
tributeRate, reinforcementObligation, diplomacyRight, autonomy
loyalty, breachConditions, expiresAt
```

Fief, vassalage, tribute, reinforcement, breach, and release commands each receive canonical IDs.
No contract is automatically created merely because an office or territory exists.

## 7. P-7 — five command layers

`P-7` is **REPLACED**:

| Layer | Authority and time | Storage boundary |
|---|---|---|
| `PERSONAL_RING` | one general, owned subordinate people, or owned Bugok; reserved turn | personal reservation ring and existing flush |
| `CHIEF_RING` | nation, office, policy, budget, and open fronts; reserved nation turn | chief reservation ring and existing flush |
| `PERSISTENT_PLAN` | travel, convoy, construction, assignment, and operation; multi-turn state machine | durable plan plus restart recovery |
| `BATTLE_ROUND` | formations and battlefield objectives; simultaneous sealed WEGO orders | ordered round input, deterministic result, replay |
| `SYSTEM_RESOLVER` | deterministic encounters, supply, occupation, aftermath, and audited admin operation | explicit resolver input/result and audit trail |

Prefixes do not determine layers. A canonical catalog row does. Personal and chief reservations
remain separate. Plans and battle orders do not consume hidden ring slots. A system resolver cannot
be selected as a player command unless a distinct audited admin command explicitly invokes it.

## 8. P-8 — canonical catalog and evolution

`P-8` is **REVISED**. Every canonical row carries:

```text
canonicalId, aliases, layer, actorType, authority
argumentSchema, resultSchema, availabilityRules, reservationRules, executionRules
eventTypes, replayContract, aiPolicy, helpTopicId, tutorialObjectiveId, replacementId
deliveryState, contractStatus, ownerIssues, provenance
```

Rules:

1. `canonicalId` is globally unique and stable.
2. An alias resolves to exactly one canonical ID and never owns an implementation.
3. Unknown IDs fail closed. They must not become rest, success, or a generic resolver call.
4. Removed commands name `replacementId` and a migration policy. Replacement cycles are invalid.
5. Legacy aliases preserve ring position, cost, authority, RNG order, log order, result, and failure
   behavior until their documented removal gate passes.
6. Adding optional payload fields requires a versioned default. Required-field changes require a
   new contract version or canonical command.
7. Different authority, cost, RNG, log order, or side effects forbid implementation merging even
   when two commands share presentation wording.

The legacy public menus have 46 personal surfaces and 24 chief surfaces. The shared `휴식` spelling
has different ring, authority, and reservation contracts, so it uses contextual aliases
`general_turn:휴식` and `nation_turn:휴식` for `personal.rest` and `chief.rest`. An unqualified
lookup is ambiguous and fails closed. The extracted 93-command PHP evidence set includes non-menu
commands and is not a second catalog.

## 9. P-9 — construction and infrastructure

`P-9` is **PRESERVED**. Construction is a durable `PERSISTENT_PLAN` with project identity, sponsor,
location, cost, upkeep, progress, prerequisites, assignment, status, and timestamps. Chief-ring
commands establish plan, budget, and assignment policy; personal-ring or delegated plan commands
perform field work. Facilities expose capabilities only while their people, resources, routes,
condition, and authority requirements pass. Completion emits domain events through the campaign
engine; a battle UI never writes campaign construction directly.

## 10. P-10 — mission command

`P-10` is **REVISED**. Mission command retains four layers:

1. objective;
2. acceptable risk;
3. behavior and delegation rules;
4. delayed reports and maintain/change/withdraw decisions.

The rules become deterministic inputs to operations and sealed WEGO rounds. Decisions expose their
basis in traits, relationships, reconnaissance confidence, terrain, supply, time, objective
progress, and rear risk. AI and humans submit through the same command contracts. Runtime generated
prose is not a source of authority or state transition.

## 11. P-11 — command-linked product surfaces

`P-11` is **REVISED**. Every player-facing command identity links:

- command selection and preview;
- cost, authority, preconditions, and failure reason;
- contextual help and searchable manual;
- tutorial objective or a reason-bearing `N/A` disposition;
- AI policy and identical availability evaluation;
- replay explanation and recovery guidance when durable;
- administrator observability and recovery operations when applicable.

A route, button, topic, or test name is not completion evidence by itself. Actual UI success and
failure paths are required before `VERIFIED`.

## 12. P-12 — geography binding

`P-12` is **REVISED**. Commands refer to stable province, county/direct-territory,
commandery/kingdom, node, and edge identities from one topology snapshot. Land, directed river,
coastal sea, reviewed open sea, and crossing edges retain mode and direction. Availability,
movement, supply, objective, encounter, and replay inputs record the relevant geography version.
Strategic isometric and selection flat renderers consume these identities but do not own them.

## 13. P-13 — deterministic WEGO battle adapters

`P-13` is **REPLACED**. Battle commands submit sealed orders for a declared round. The resolver:

```text
validate authority and round → seal all eligible orders → resolve ordered movement and control
→ resolve observation, fire, contact, morale, supply, and objective progress
→ emit ordered diffs/events → publish deterministic replay
```

Land, siege, and naval modes share command and replay envelopes but own reviewed mode-specific
rules. Missing orders use an explicit doctrine or hold policy, never an implicit success. Terrain,
approach, entrenchment, weather, supply, and objectives are encounter inputs rather than troop base
data. Campaign state changes only through the aftermath adapter and the campaign's single flush.

## 14. P-14 — content and capability packs

`P-14` is **PRESERVED**. Content packs provide versioned command availability data, capabilities,
formations, facilities, geography profiles, help references, and provenance. A command depends on a
capability ID and validated inputs, not on a renderer asset or a hard-coded icon. Unreviewed pack
entries and budget placeholders do not satisfy command availability or catalog completion.

## 15. P-15 — delivery lifecycle and Stage 0 gate

`P-15` is **REPLACED**. Delivery proceeds monotonically:

```text
DOMAIN_READY → HANDLER_READY → UI_READY → AI_READY
→ HELP_READY → TUTORIAL_READY → REPLAY_READY → VERIFIED
```

`N/A` is permitted only in an individual evidence field with a non-empty reason. It is not a
lifecycle shortcut. A later state requires evidence for every earlier state. `VERIFIED` additionally
requires deterministic focused tests, real UI success and failure coverage, restart recovery for
durable state, and campaign integration.

Stage 0 passes only when a machine-readable validator proves all of the following:

1. required fields and enums are valid for every row;
2. canonical IDs, aliases, and legacy surfaces are unique and closed;
3. replacements exist, have migration policy, and contain no cycles;
4. the 46 personal and 24 chief legacy menu surfaces are represented exactly;
5. every approved public-alpha family in the product authority has at least one final owned row;
6. every row has final `contractStatus`, owner issues, provenance, AI policy, help topic, tutorial
   objective or reason-bearing disposition, and replay policy;
7. no row claims a delivery state beyond its checked evidence;
8. no unknown, provisional, placeholder, automatic-rest, or automatic-success path remains;
9. legacy command and ring regression tests remain green without runtime changes;
10. GitHub and Jira child tickets carry matching evidence before their OPENSAM-30/#172 umbrella is
    closed.

## Non-goals

- Implementing handlers, runtime registry loading, UI, AI, help prose, tutorial content, or battle
  resolution in Stage 0.
- Moving legacy commands between personal and chief rings.
- Treating the PHP implementation as an oracle for new operations, subordinate people, Bugok,
  government, WEGO, system, or admin commands.
- Calendar promises, private/invite alpha, presentation-first 3D work, or release-tail onboarding.
- Inferring implementation completion from existing names, routes, documents, or tests.
