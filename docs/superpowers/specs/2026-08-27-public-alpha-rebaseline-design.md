# Public Alpha Rebaseline Design

**Date:** 2026-08-27

**Status:** user-approved design

**Supersedes:** realtime tactical timing and 3D-first release assumptions in the 2026-07-12
documents; the isometric-removal statement in the 2026-08-22 world-map plan; release-tail-only
onboarding

**Preserves:** deterministic simulation, one-daemon-write, frozen legacy regression baselines,
world/profile isolation, one-time Han province baking, source provenance

## 1. Product and release contract

OpenSamguk is a turn-based campaign with turn-based personal and command rings, durable
multi-turn plans, WEGO tactical resolution, and real-time-like replay. Public alpha is open to
anyone and begins only after the complete canonical command catalog is implemented across domain,
handler, UI, AI, help, tutorial, replay, and verification surfaces.

The roadmap has no calendar commitments. Each stage advances as soon as its evidence gate passes.
Public alpha may reset game worlds when required, but account identity and nicknames survive. Public
beta prohibits unannounced world resets and prioritizes save compatibility.

## 2. Command scope

The public-alpha catalog includes all approved command families:

1. legacy personal and chief-ring commands;
2. identity, appointment, founding, domestic, military, finance, personnel, and diplomacy;
3. travel, forced march, assignment, convoy, supply, infrastructure, and construction;
4. operation creation, support, reinforcement, intercept, blockade, escort, sabotage, retreat, and aftermath;
5. subordinate-person recruitment, oath, release, role, mission, and delegation;
6. Bugok creation, formation, replenishment, training, split, merge, commander assignment, and dissolution;
7. council, policy, national identity, court, office, edict, seal, reform, governorship, fief,
   vassal, tribute, and reinforcement obligation;
8. land, siege, and naval WEGO orders and campaign adapters;
9. explicit system resolvers and administrator commands required to operate the public world.

Aliases do not count as separate implementations. They adapt to one canonical command. Removed
commands need a named replacement and migration policy. Unknown commands fail closed.

## 3. Command completion state

Every canonical row carries:

```text
canonicalId, aliases, layer, actorType, authority, argumentSchema, resultSchema,
availabilityRules, reservationRules, executionRules, eventTypes, replayContract,
aiPolicy, helpTopicId, tutorialObjectiveId, replacementId
```

Its delivery state is:

```text
DOMAIN_READY → HANDLER_READY → UI_READY → AI_READY
→ HELP_READY → TUTORIAL_READY → REPLAY_READY → VERIFIED
```

`VERIFIED` requires deterministic focused tests, restart recovery where state is durable, actual UI
coverage, and campaign integration. A command cannot declare completion while help or tutorial is a
separate unfinished ticket.

## 4. World, map, and movement

The Han map follows the approved province design:

```text
raster cell → tactical province → county/direct territory → commandery/kingdom
```

All covered land is non-empty, connected, parented, movable, and supply-capable. Strategic terrain
and sites may own provinces. Major mountains and rivers are structural boundaries. Navigable water
uses directed hydrology; upstream and downstream costs differ while flow direction remains stable.
Land, river, coastal sea, reviewed open-sea, and crossing edges share one topology snapshot.

The strategic renderer is isometric. The selection renderer is flat top-down. Both consume the
same province and live-state identities. Nation color applies to territory and declared banner
masks, never to icon bodies. Formation symbols use a standard plus three-to-five representative
soldiers; detailed troop rendering remains a presentation expansion, not a missing formation model.

## 5. Subordinate people, Bugok, and armies

The user-facing umbrella is `휘하`, not `가신단` as a military container.

- A subordinate person is a named actor: staff officer, lieutenant, guest, or close retainer. It
  carries relationship, loyalty, capability, role, and mission.
- A Bugok is the general's private troop body. It carries manpower, composition, training, morale,
  fatigue, supply, and commander assignment.
- A lieutenant may command a Bugok but is not the Bugok.
- State armies belong to a nation, governorship, or garrison and remain distinct from private Bugok.

Person commands and troop commands have separate aggregates and events. Commander assignment is an
explicit relationship with transfer and revocation rules.

## 6. Command layers and time

| Layer | Purpose | Time contract |
|---|---|---|
| Personal ring | one general, subordinate people, owned Bugok | reserved turn execution |
| Chief ring | nation, office, policy, budget, fronts | reserved nation turn execution |
| Persistent plan | travel, construction, convoy, operation | multi-turn state machine |
| Battle round | formations and battlefield objectives | simultaneous sealed WEGO orders |
| System resolver | encounters, supply propagation, occupation, aftermath | deterministic derived resolution |

Movement, forced march, and assignment are non-combat travel. An operation may encounter or
intercept them, but travel does not become a battle command merely because the actor has troops.

## 7. Objective progress and encounter doctrine

Stalemates are resolved through operational objectives, control, supply, and time cost rather than
forcing the side judged tactically stronger to attack. An operation declares an objective such as
city occupation, road control, supply interdiction, passage, blockade, or relief, with target
provinces or edges, a deadline, progress rules, supply requirements, and interruption conditions.

Resolution order is deterministic:

```text
declare objective → seal WEGO orders → resolve movement, control, and progress
→ resolve intercept or encounter → battle only on contact → occupation and aftermath
```

Avoiding contact does not grant the opponent a free victory. Progress accrues only from actual
occupation, route control, uninterrupted work, and a valid supply connection. A force that remains
inactive loses time, supply, morale, or the opportunity to contest control according to its own
operation obligation. Counterattacks and sorties create their own declared obligations instead of
silently reversing which side must act.

Entrenchment grows to a cap rather than decaying merely because a force waits. Long occupation
continues to consume supply, accumulate fatigue, and increase reconnaissance exposure, and one force
cannot project control over every adjacent route. Disengagement and re-entry cost movement,
cohesion, and pursuit risk rather than an arbitrary cooldown.

Terrain, approach direction, entrenchment, weather, supply, and the operational objective belong to
a `BattleEnvironment`-like encounter input, not to troop-type base data. Initial balance tests keep
direct terrain combat modifiers around ±20–25% and all combined environmental combat effects around
an effective ±35–40% cap; these are test ranges, not immutable constants. Reconnaissance, engineers,
mixed formations, flanking, night action, and alternate routes provide explicit counterplay.

AI utility compares objective progress against travel time, supply risk, contact-battle risk, and
rear exposure. It does not choose solely from estimated battle win probability.

## 8. Tutorial and help architecture

The canonical command registry is the source for machine-readable cost, authority, arguments,
availability, and failure reason. Curated prose supplies explanation, examples, historical context,
and recovery advice. `helpTopicId` and `tutorialObjectiveId` link the same concept across command
modal, contextual help, searchable manual, guided scenario, and replay explanation.

Every feature's Definition of Done includes:

1. success and failure are explained in the UI;
2. searchable help exists;
3. contextual help appears at the decision surface;
4. a tutorial objective or explicit N/A exists;
5. cost, preconditions, and preview are visible before submission;
6. a real-UI success and failure path is verified.
7. affected public, user, administrator, design, module, and agent-facing documentation is updated;
8. when documentation is unaffected, the task report records `docs-impact: none` and the reason.

`README.md` remains public-facing and must not depend on private planning conversation. `CLAUDE.md`
and `AGENTS.md` are changed only when durable invariants or contributor workflows change, not for
ordinary feature status churn.

## 9. Priority and issue decision model

Importance and urgency are independent.

### Importance

- `importance-critical`: public-alpha contract, data integrity, security, deterministic recovery,
  or campaign completion fails without it.
- `importance-high`: a complete command family or major player loop is materially degraded.
- `importance-medium`: usability, content breadth, observability, or maintainability improves.
- `importance-low`: optional polish or expansion with a valid fallback.

### Urgency

- `urgency-now`: the current earliest unresolved stage or its direct blocker.
- `urgency-next`: dependency-ready immediately after the current stage.
- `urgency-later`: cannot or should not start until named predecessors pass.

### Additional labels

- `critical-path`: blocks one or more downstream public-alpha gates.
- `gate-public-alpha`: must be verified before opening registration and login.
- `gate-public-beta`: required for beta stability but not alpha entry.
- `stage-0` through `stage-10`: the earliest stage that owns the issue.

Selection order is gate, dependency readiness, critical path, urgency, importance, then the smaller
independently verifiable slice. Importance does not authorize starting a blocked issue.

## 10. Stage gates

0. Rebaseline and freeze the complete catalog.
1. Deliver common command lifecycle and contracts.
2. Deliver the province world and two renderers.
3. Deliver travel, routes, supply, and visibility.
4. Deliver operations, infrastructure, and construction.
5. Deliver subordinate people, Bugok, council, identity, court, office, governorship, fief, and vassal commands.
6. Deliver land, siege, and naval WEGO commands and replay.
7. Prove a complete AI and human campaign plus onboarding.
8. Run the locked production-candidate server, recovery rehearsal, and final world reset.
9. Open public alpha.
10. Promote to public beta only after migration and no-unannounced-reset gates pass.

## 11. Acceptance criteria

- Every canonical public-alpha command is `VERIFIED`; placeholder and silent fallback counts are 0.
- AI calls the same command contracts available to players.
- Every implemented command has linked help and tutorial disposition.
- A Han campaign progresses from start to terminal state without administrator mutation.
- Save/restart preserves active plans, battles, assignments, construction, and hierarchy state.
- Same snapshot, ordered input, and seed produce identical command, battle, and replay hashes.
- An operation can resolve by control, passage, blockade, relief, or supply loss without fabricating
  a contact battle, and waiting cannot freeze an objective without paying its declared time and
  supply costs.
- Public-alpha pre-open uses existing global join/login flags; no duplicate access-control feature is introduced.
- Account identity survives a game-world reset, and the reset leaves an announcement, snapshot,
  reason, and readable terminal chronicle.

## 12. Non-goals

- Calendar-date promises for alpha or beta.
- Treating a route, button, document, or test name as implementation evidence by itself.
- Combining subordinate people and Bugok into one aggregate.
- Coloring neutral city, terrain, fortress, or landmark icon bodies with nation color.
- Shipping an unimplemented command behind automatic success, rest, or opaque fallback.
- Forcing combat because a heuristic judges one side tactically favored.
