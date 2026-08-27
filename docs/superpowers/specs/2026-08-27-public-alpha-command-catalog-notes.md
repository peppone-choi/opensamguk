# Public Alpha Command Catalog Notes

**Catalog:** `data/commands/public-alpha-command-catalog.json`

**Contract:** `2026-08-27-public-alpha-command-contract-freeze.md`

**Stage:** 0 — contract inventory only

## Legacy evidence sets

Three counts describe different things and must not be substituted for each other:

| Set | Count | Authority | Meaning |
|---|---:|---|---|
| Personal public menu surfaces | 46 | `GameConst.availableGeneralCommand` | Commands selectable from the personal reservation ring |
| Chief public menu surfaces | 24 | `GameConst.availableChiefCommand` | Commands selectable from the chief reservation ring |
| Extracted legacy PHP commands | 93 | `data/extracted/commands/constraints.json` | General and nation command classes with extracted constraint evidence, including non-menu commands |

The catalog contains one canonical adapter row for each of the 70 menu surfaces, as required by
OPENSAM-76. Rows may reference a shared `normalizedIntentId`, but each adapter keeps its legacy
payload, cost, authority check, RNG order, log order, result, and failure behavior.

The reviewed normalized-intent references are limited to decisions already stated in
`2026-07-12-v2-command-catalog-and-rollout.md`:

- recruitment: `che_징병`, `che_모병` → `personal.recruit`;
- readiness: `che_훈련`, `che_사기진작` → `personal.retinue.prepare`;
- formation organization: `che_집합`, `che_소집해제` → `personal.formation.organize`;
- relocation: `che_이동`, `che_강행` → `personal.retinue.relocate`;
- immediate domestic work: eight preserved domestic surfaces → `personal.cityAction`;
- diplomatic proposals: material aid and proposal surfaces → `chief.diplomacy.propose`;
- declaration: `che_선전포고` → `chief.diplomacy.declare`;
- sortie: `che_출병` → `personal.sortie`.

Every legacy surface keeps a distinct canonical adapter row. Similar wording or a shared
`normalizedIntentId` is not an implementation-merge criterion.

## Contextual rest aliases

The literal `휴식` exists in both public menus, but the personal and chief rings have different
authority and reservation contracts. The catalog therefore exposes:

```text
general_turn:휴식 → personal.rest
nation_turn:휴식  → chief.rest
```

Unqualified `휴식` fails closed. Callers already know the source ring and must include it during
normalization. This avoids a canonical row whose declared layer disagrees with half of its uses.

## Delivery-state interpretation

All Stage 0 rows are `DOMAIN_READY`. This means identity, layer, ownership, schema disposition,
legacy preservation policy, and required downstream surfaces are contractually named. It does not
claim that the current runtime loads this JSON or that AI, help, tutorial, UI, or replay work is
complete. Those claims advance only with lifecycle evidence in later stages.

AI and help/tutorial dispositions are obligations. Player-selectable planned rows use `REQUIRED`;
system and administrator rows use a reason-bearing `N/A`. None is inferred from existing menu
buttons, AI code, documents, or legacy logs.

## Generated data and review boundary

`tools/commands/build_legacy_command_catalog.py` performs a deterministic mechanical extraction of
the two Kotlin menu blocks and applies the reviewed normalization table above. The checked-in JSON
is the reviewed artifact. The builder prevents transcription drift; it is not runtime authority.
Tests independently parse the Kotlin source, require exactly 46/24 surfaces, reject duplicates,
verify every non-rest menu code exists in `CommandRegistry`, and verify provenance paths.

The 93-command extracted PHP evidence is used when a legacy adapter's detailed preconditions are
implemented or reviewed. Its count does not expand the public menu, and new public-alpha commands
must not cite it as an implementation oracle.

## Planned public-alpha identities

The catalog adds 54 `DOMAIN_READY` rows for the product families that have no complete legacy-menu
contract. Together with the 70 one-to-one legacy adapter rows, the Stage 0 catalog contains 124
canonical identities. The number is an inventory result, not a marketing quota and not an
implementation count.

The planned rows are grouped by downstream owner:

- OPENSAM-213..215: travel, forced march, assignment, convoy, supply, infrastructure, and construction;
- OPENSAM-200: operation creation, support, reinforcement, intercept, blockade, escort, sabotage,
  retreat, and aftermath;
- OPENSAM-61: subordinate-person recruitment, oath, release, role, mission, and delegation;
- OPENSAM-48: Bugok creation, formation, replenishment, training, split, merge, commander assignment,
  and dissolution;
- OPENSAM-62..69: council, policy, identity, court, office, edict, seal, reform, governorship, fief,
  vassalage, tribute, and reinforcement obligations;
- OPENSAM-25 and OPENSAM-156..158: deterministic system resolution and land/siege/naval WEGO;
- OPENSAM-29 and OPENSAM-70..72: audited public-world join policy, snapshot, reset, and recovery.

An owner ticket may split a row into smaller commands after contract review. It must update the
catalog, preserve aliases and migrations, and keep the Stage 0 gate closed during the change. It
may not collapse rows with different authority, time, replay, or aggregate boundaries.

Each planned row freezes command-specific required arguments, result envelope fields, authority,
availability checks, reservation behavior, execution sequence, replay disposition, and AI
disposition. Downstream tickets may add optional versioned fields but may not replace these required
fields without reopening Stage 0 and documenting migration.

System resolvers are deterministic derived transitions, not selectable player or AI commands.
Administrator commands likewise use reason-bearing `aiPolicy: N/A`; their replay disposition is a
durable audit log rather than a player battle replay. This keeps world join policy, snapshot, reset,
and recovery operations behind authenticated authority and an explicit audit trail.

Every persistent plan owns `recovery.resumed` and `recovery.failed`. The dedicated
`system.notification.create` resolver owns `notification.created`; administrator recovery also
emits the two recovery outcomes. This closes every minimum P-1 event family without pretending all
commands generate user notifications.

Lifecycle evidence uses `kind:path::command-specific-marker`. The validator checks that the path
exists in the correct source area, the marker is present, and the marker contains the canonical ID.
An unrelated test file or UI scenario cannot advance another command to `VERIFIED`.

`operation.create` has one objective source of truth. Its objective selects province targets,
edge targets, or both and carries deadline, progress, supply, and interruption rules; participants
carry explicit MAIN/SUPPORT/SCOUT/SUPPLY/RESERVE roles and operation rules freeze intercept,
retreat, siege, and supply policy. WEGO batches enumerate every eligible formation and require
`HOLD` or `FORMATION_DOCTRINE` for formations omitted from the submitted order list. Land, siege,
and naval orders each use mode-specific typed payload variants.
