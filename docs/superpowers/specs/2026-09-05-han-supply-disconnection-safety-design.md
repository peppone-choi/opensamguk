# Han Supply Disconnection Safety Design

> **V3 compatibility amendment (2026-09-05):** Preserve persisted `han`/`han-world-v2` as
> the 774-city domain and its schema-1 ledger. The new 781-city `han-world-v3` uses a separate
> schema-2 ledger keyed by runtime ID + physicalPlaceRef + routeNodeKey + jurisdiction.
> Never copy old numeric policy IDs into V3. A reviewed geometry defect may disconnect both
> graphs: an exact `BOTH_UNSUPPLIED` expectation with a `PROTECT_*` decision yields
> `BOTH_UNSUPPLIED_PROTECTED`; it is not a fabricated movement/supply edge. An expectation
> mismatch never applies the policy. Unclassified CITY_ONLY remains runtime-safe/CI-red.
> Current V3 policy covers physical 43252 and 40740 only, in their explicit scenario ranges.
> The audit rejects scenarios from a different city-ID domain before evaluating numeric IDs.

## Status and scope

This design protects owned Han cities from becoming neutral because an incomplete or defective
spatial-province projection reports a false supply cut. It also defines how confirmed geometry
defects are repaired without inventing adjacency or changing the canonical roster of 1,524 spatial
provinces and 172 commanderies.

The work covers monthly city supply, the Han spatial-supply provider, supply diagnostics, reviewed
geometry repairs that are executable from current evidence, regression tests, and the overlapping
GitHub issues. It does not introduce the future persistent province-control write model, sea-route
simulation, or a new commandery/county roster.

## Root cause

The monthly supply path currently treats `SpatialSupplyNetwork` as the only authority for every
mapped city. The network starts from the committed scenario projection and overwrites only the
canonical seat province of each live runtime city. There is no runtime producer for the remaining
non-seat province controls.

This ordering is unsafe for destructive monthly effects:

1. The approved administrative hierarchy keeps `SpatialProvince`, `County`, and `Commandery`
   separate. A city conquest changes the seat spatial province; other province controls are meant
   to change through later province-control events.
2. Those province-control events are not implemented yet.
3. The committed geometry also contains reviewed defects, including city-bearing degree-zero
   provinces and commandery corridors cut by displaced anchors or modern-water masks.
4. Supply loss applies a 10 percent city-stat decay, a 5 percent general decay, and eventual
   neutralization. A topology candidate therefore becomes an irreversible gameplay decision before
   its control state and geometry are authoritative.

The defect is not the decay formula. The defect is using one incomplete evidence source as the sole
proof that decay is warranted.

## Decision: dual-evidence destructive supply gate

The engine computes two independent reachability sets for the same live snapshot:

- **City reachability** uses the current owned-city graph and live city ownership.
- **Spatial reachability** uses the Han spatial-province graph and its projected ownership.

The final supplied set is the union of the two sets except for explicitly adjudicated spatial-only
cuts. A mapped city receives destructive supply loss only when both independent paths classify it
as unsupplied, or when a reviewed policy explicitly forbids the city-graph fallback.

This is a safety rule, not a claim that the city graph is the final movement network. It prevents a
known-incomplete spatial projection from destroying live state while preserving spatial corridors
that supply cities the city graph cannot reach.

### Reachability verdicts

Each owned city is classified as one of:

| Verdict | City graph | Spatial graph | Runtime result |
|---|---:|---:|---|
| `BOTH_SUPPLIED` | yes | yes | supplied |
| `CITY_ONLY_PROTECTED` | yes | no | supplied; topology debt recorded |
| `SPATIAL_ONLY_SUPPLIED` | no | yes | supplied |
| `BOTH_UNSUPPLIED` | no | no | unsupplied; existing decay and neutralization apply |
| `SPATIAL_CUT_UPHELD` | yes | no | unsupplied only when an explicit reviewed policy forbids fallback |

Neutral cities retain the existing force-supplied behavior. Unmapped legacy cities retain the city
graph result and never bridge two spatial provinces.

## Reviewed fallback policy

A new versioned Han supply-disconnection ledger maps runtime city IDs to the reason a disagreement
is protected or upheld. Entries use stable city and jurisdiction identities, never province array
ordinals alone.

Allowed decisions are:

- `PROTECT_GEOMETRY_DEFECT`: city reachability protects the city until the reviewed tile defect is
  repaired.
- `PROTECT_PARENT_MISASSIGNMENT`: city reachability protects the city until the reviewed parent
  correction is applied.
- `UPHOLD_WATER_ROUTE_ONLY`: a real water separation does not gain a free land route. It remains
  unsupplied until an authoritative water route supplies it.
- `UPHOLD_HISTORICAL_EXCLAVE`: a real exclave does not gain a direct administrative shortcut; it
  must be reached through actual controlled neighbors.

Every entry records the effective scenario range, source ledger row, rationale, and expected current
reachability verdict. A canonical audit fails when an entry becomes stale, a new city-only mismatch
appears without adjudication, or a protected mismatch is removed without the corresponding geometry
or parent correction.

Runtime behavior is fail-safe for destructive state: an unclassified city-only mismatch is protected
and emitted as diagnostic debt. CI is fail-closed: the same mismatch fails the canonical audit until
it is adjudicated or repaired.

## Spatial ownership projection

This task does not pretend that conquering a city updates every spatial province in its jurisdiction
or commandery. The committed scenario assignment remains the spatial occupancy baseline, and the live
city continues to override its canonical seat province only. This preserves the approved direct-
province ownership contract and the two reviewed mixed-ownership jurisdictions in scenarios 1100 and
1110.

Authoritative live province control requires a durable province-control aggregate and conquest,
movement, replay, API, and snapshot producers. That work belongs to the province transport/operation
track. Until it exists, projected spatial ownership may supply a city but may not be the sole evidence
that destroys a city.

## Geometry repair policy

The canonical tile artifact is changed only through deterministic, evidence-checked patchers and
versioned ledgers. No synthetic adjacency edge, nearest-parent shortcut, representative-colour fill,
or roster count change is allowed.

For each executable defect:

1. Add a failing invariant or scenario regression first.
2. Record exact source/target cells, anchors, parent identities, input/output hashes, and historical
   evidence in the applicable curated ledger.
3. Materialize through the narrow patcher.
4. Recompute `owner`, `parentOwner`, county/commandery adjacency, counts, and dependent manifests.
5. Re-run disconnection, containment, parent, scenario ownership, route-node, and supply audits.
6. Remove the supply protection only when both graphs agree after the repair.

Repairs must preserve 1,524 spatial province IDs, 172 commandery IDs, all stable jurisdiction IDs,
and all scenario codes. Repairs that require a missing county, a parent-ledger decision, or a roster
change remain protected and blocked rather than being approximated.

The initial repair candidates are 徐縣, 鄮縣, 北海國 劇縣, 東郡 陽平/東武陽, 廬江郡 陽泉縣,
and the 合浦/徐聞/朱崖 group. 河南尹 is repaired only if the current parent and missing-county
ledgers make the complete corridor executable without inventing 鞏縣 geometry; otherwise it remains
protected for Track C.

## Tests and acceptance criteria

### Pure logic

- A mapped city supplied only by the city graph remains supplied and does not decay.
- A mapped city supplied only by the spatial graph remains supplied.
- A city unsupplied in both graphs retains the existing 10 percent/5 percent decay and trust-based
  neutralization behavior.
- An explicit `UPHOLD_*` decision can reject city-only fallback.
- Unmapped cities preserve the existing city-graph behavior and cannot bridge spatial provinces.
- Output ordering remains deterministic.

### Canonical Han integration

- The previous vacuous 漁陽 潞縣 test is red-probed with a different live owner and proves that a
  non-seat ownership disagreement is classified instead of silently accepted.
- Known false cuts for the reviewed supply-impacting cases cannot neutralize a city solely because
  the spatial graph is disconnected.
- A canonical disagreement audit has zero unclassified rows.
- City-bearing degree-zero Han provinces are either repaired or carry a current reviewed protection.
- Frozen exact triples such as `owned/supplied/blocked` are not product specifications. Tests assert
  intent, monotonic safety, and exact reviewed exceptions instead.
- All 15 active scenarios retain 1,524 province assignments, 1,020 jurisdictions, and 172
  commanderies.

### Repository gates

- Focused logic, game-engine, game-api, and map tests pass with red-first evidence recorded.
- `audit_territory_disconnections.py --check` passes with no stale or unadjudicated rows.
- `build_han_world.py --check-gate` reports no drift.
- The full map and scenario Python suites pass.
- Relevant Gradle module tests pass under JDK 21 with XML/tail confirmation.

## GitHub issue handling

- **#596 / OPENSAM-237** receives the live supply disagreement audit, reviewed exceptions, and exact
  completion evidence for the scenario/topology portion finished here.
- **#598 / OPENSAM-236** receives each executed geometry repair, remaining blocked rows, before/after
  topology counts, and validation evidence.
- **#473 / OPENSAM-213** receives the authoritative runtime province-control prerequisite and the
  rule that spatial occupancy cannot become destructive truth before that write model exists.
- **#541** changes state only if its 京兆尹 generator defect is independently fixed and verified;
  sharing the word "isolation" is not sufficient.
- Other issues may be handled concurrently only when their files and acceptance criteria do not
  overlap this task. No issue is closed from partial evidence.

## Documentation and operations

The spatial supply projection plan and administrative hierarchy design are updated to state the
dual-evidence safety boundary. User-facing documentation changes only if the visible supply rule is
documented today; otherwise the task report records `docs-impact: internal design and operator audit`.

No production reset, deployment, merge, or shared-branch push is part of implementation without a
separate explicit action. The final metarepo report records result, commits, verification, GitHub
issue mutations, and remaining risks.
