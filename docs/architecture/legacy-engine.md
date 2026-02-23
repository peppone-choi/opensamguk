# Legacy Engine Map

The legacy engine lives in `legacy/hwe/sammo/` and follows domain-first
organization rather than endpoint-first routing.

## Core Domains

- `Command/`: turn actions (general/nation commands) and resolution rules
- `API/`: engine operations for UI and automation
- `Event/`, `StaticEvent/`: dynamic and scheduled event processing
- `General*`, `Nation*`, `WarUnit*`, `City*`: entities and combat/city state
- `Action*`, `Special*`: traits, personalities, special actions, scenario effects
- `Trigger*`, `*Trigger`: conditional logic and state transitions
- `Scenario/`, `Scenario.php`: scenario loading and rulesets
- `DTO/`, `VO/`, `Enums/`, `Constraint/`: shared types and validation

## Endpoint Patterns

- JSON APIs: `legacy/hwe/j_*.php`
- Vue multi-entry pages: `legacy/hwe/v_*.php`
- PHP + jQuery pages: `legacy/hwe/b_*.php` and handlers `legacy/hwe/c_*.php`
- Modern router: `legacy/hwe/api.php` dispatches into `legacy/hwe/API/`

## Frontend and Assets

- Vue/TypeScript UI: `legacy/hwe/ts/`
- Shared components: `legacy/hwe/ts/components/`
- Styles: `legacy/css/` and `legacy/hwe/scss/`
- Templates: `legacy/hwe/templates/`

## Trigger Composition for Generals (Outline)

- Trigger evaluation order and priority rules
- "attempt" then "execute" phases
- Common trigger categories (traits, specials, scenario effects)
- How triggers combine when multiple sources apply

## Detailed Notes

- Entity and schema overview: `docs/architecture/legacy-entities.md`
- Turn execution pipeline: `docs/architecture/legacy-engine-execution.md`
- General model and action stack: `docs/architecture/legacy-engine-general.md`
- General AI behavior and policy model: `docs/architecture/legacy-engine-ai.md`
- Trigger system (iAction + trigger callers): `docs/architecture/legacy-engine-triggers.md`
- Battle and war resolution: `docs/architecture/legacy-engine-war.md`
- Command catalog: `docs/architecture/legacy-commands.md`
- Scenario system and rule sets: `docs/architecture/legacy-scenarios.md`
- Inheritance points (유산 포인트): `docs/architecture/legacy-inherit-points.md`
- Event system (dynamic/static): `docs/architecture/legacy-engine-events.md`
- Economy and monthly updates: `docs/architecture/legacy-engine-economy.md`
- Auctions and betting: `docs/architecture/legacy-engine-auction.md`
- Items, specials, and traits: `docs/architecture/legacy-engine-items.md`
- City data and helpers: `docs/architecture/legacy-engine-city.md`
- Diplomacy and messaging: `docs/architecture/legacy-engine-diplomacy.md`
- Constraint system: `docs/architecture/legacy-engine-constraints.md`
- Game constants and unit sets: `docs/architecture/legacy-engine-constants.md`
- Server environment tools: `docs/architecture/legacy-engine-server-env.md`
- Logging and versioning: `docs/architecture/legacy-engine-logging.md`
- General pools: `docs/architecture/legacy-engine-pools.md`
