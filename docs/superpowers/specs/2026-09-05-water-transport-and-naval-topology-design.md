# Water Transport and Naval Strategic Topology Design

- Date: 2026-09-05
- Status: **APPROVED — hybrid water-zone direction confirmed by the user on 2026-09-05**
- Scope: Han strategic water topology, crossings, transport, supply, naval engagement handoff

## 1. Product decision

OpenSamguk keeps the canonical 1,524 `SpatialProvince` records as land administration, ownership,
occupation, and taxation units. Rivers, lakes, and verified coastal waters are represented by a
separate stable `WaterZone` topology connected to land through typed traversal edges.

Water cells do not become ordinary administrative provinces. A river may be a land barrier, a
navigable strategic zone, or both at different reaches. A lake becomes a strategic water zone only
when evidence supports transport or battle on it; small lakes remain impassable terrain. This hybrid
model makes crossings and naval battles explicit without changing land province identity or treating
water as taxable county territory.

## 2. Current-state evidence

The canonical `han-tiles.json` is 768 by 669 and currently contains:

- 1,524 spatial provinces, 172 commanderies, and 1,020 county-level jurisdictions;
- 3,421 `RIVER` cells, all assigned to 299 existing land provinces;
- 1,079 `LAKE` cells, all unowned;
- 174,997 `SEA` cells, all unowned;
- 4,161 raw county-adjacency edges derived from shared owner-grid boundaries;
- no exported roads, waterways, water zones, ports, crossings, or typed traversal edges.

The current generator excludes sea and lake cells from political land but treats river cells as
land. Raw province adjacency therefore cannot tell a dry border from a river crossing. The retained
automatic land/water A* code is not canonical: export was deliberately disabled because generated
roads and sea routes conflicted with historical evidence.

Runtime movement is also split. Spatial supply consumes raw province adjacency, while V2 transport
precheck and the command UI still use the 774-node legacy `CityConst.path` graph. The strategic map
shows 1,020 county-level jurisdictions. A visible shared border can consequently disagree with an
executable move.

## 3. Canonical domain model

```text
LandProvinceRef = SpatialProvinceRecord.id

WaterZoneRecord {
  id: string
  kind: RIVER_REACH | LAKE_BASIN | COASTAL_SEA
  geometryRef: string
  sourceRefs: string[]
  confidence: EXACT | REVIEWED | INFERRED
  flowDirection?: string
  depthBand?: SHALLOW | MEDIUM | DEEP
  seasonalAvailability: ALWAYS | SEASONAL | CLOSED
}

StrategicNodeRef = LAND_PROVINCE(id) | WATER_ZONE(id)

TraversalEdge {
  id: string
  from: StrategicNodeRef
  to: StrategicNodeRef
  mode: LAND | FORD | BRIDGE | FERRY |
        EMBARK | DISEMBARK |
        RIVER_UP | RIVER_DOWN | LAKE | COASTAL
  directed: boolean
  movementCost: integer
  capacity: integer
  riskBand: LOW | MEDIUM | HIGH
  seasonalAvailability: ALWAYS | SEASONAL | CLOSED
  supplyAllowed: boolean
  sourceRefs: string[]
  confidence: EXACT | REVIEWED | INFERRED
}
```

Land and water IDs use separate namespaces. Water IDs are derived from stable named reaches or
basins and curated endpoint identities, never from array ordinals or connected-component iteration
order alone.

The immutable `StrategicTopologySnapshot` contains the exact land ID set, water-zone records,
typed-edge records, artifact hashes, and a `topologyRevision`. Map API, command preview, game-api
precheck, game-engine execution, supply, battle handoff, and replay pin the same revision.

## 4. River and lake semantics

### 4.1 River barriers

A non-navigable or unverified river section is a barrier on a land-to-land border. It does not create
a water node. Crossing is possible only through a reviewed `FORD`, `BRIDGE`, or `FERRY` edge. The
resulting battle remains a land battle with a river/ford terrain patch.

A river cell inside one existing land province does not automatically split that province. A later
geometry repair may align existing province banks with a reviewed centerline while preserving the
exact 1,524 land IDs. The repair must not create new administrative entities or renumber records.

### 4.2 Navigable river reaches

A navigable river is divided at meaningful junctions: confluences, major ports, ferries, strategic
crossings, and verified limits of navigation. Each interval is one `RIVER_REACH`, not one node per
water tile. Upstream and downstream edges may have different costs and capacities.

Natural Earth raster appearance is insufficient evidence of historical navigability, width, depth,
or seasonality. Each canonical reach requires curated source references and confidence. Unreviewed
water never creates an automatic shortcut.

### 4.3 Lakes and coastal sea

A large, historically relevant lake is one or more `LAKE_BASIN` zones connected to reviewed shore
ports or landing points. Small lakes remain impassable terrain and create no strategic zone.

Sea movement begins with reviewed `COASTAL_SEA` zones and routes. Open-sea reachability is not
inferred from continuous sea pixels. A coast-adjacent province is not automatically a port.

## 5. Control, occupation, and blockade

Water zones are not assigned to a scenario nation as administrative ownership. Their control is a
runtime projection derived from fleets, ports, shore batteries where modeled, and active blockades.

```text
WaterControlState {
  topologyRevision
  waterZoneId
  controllingNationId?: long
  contestingNationIds: long[]
  blockadeState: OPEN | CONTESTED | BLOCKED
  revision: long
}
```

Capturing a county or commandery does not silently grant control of adjacent water. Capturing a port
changes access to embarkation and local support; fleet presence and battle outcomes determine water
control. The game-engine remains the single writer for campaign control state.

## 6. Movement and transport

All V2 strategic movement resolves through one topology service. UI and API never reimplement graph
rules independently.

`ResolvedStrategicPath` records ordered node and edge IDs, movement mode, total cost, capacity,
topology revision, and path hash. Precheck returns that exact path. The reserved command carries it,
and the engine revalidates revision and dynamic edge state before execution.

Rules:

1. Dry land movement uses `LAND` edges between adjacent land provinces.
2. A river barrier removes implicit dry crossing. Only an active reviewed crossing edge permits it.
3. Entering a water zone requires a compatible port, ferry, or landing edge plus sufficient ship or
   transport capacity.
4. Leaving water requires a reviewed disembark or landing edge.
5. Upstream/downstream costs, season, blockade, escort, and capacity are deterministic inputs.
6. A route cannot jump between disconnected reaches or across a lake without an explicit edge.
7. Legacy city paths remain isolated for legacy modes and are not authoritative for Han V2.

Movement requests may name a county, commandery, settlement, or province. The resolver converts the
target to deterministic land province candidates and returns the legal path or a typed denial reason
such as `NO_LAND_CONNECTION`, `RIVER_CROSSING_REQUIRED`, `NO_EMBARK_POINT`, `NO_TRANSPORT_CAPACITY`,
`WATERWAY_BLOCKED`, or `TOPOLOGY_REVISION_STALE`.

## 7. Supply

Supply consumes only typed edges whose `supplyAllowed` flag and live state permit supply. Raw shared
water cells or raw land adjacency never imply a water supply route.

Topology changes run a before/after reachability audit. A new or changed water barrier cannot trigger
destructive city decay until the supply-disconnection safety gate classifies the disagreement. The
city graph remains protective evidence while persistent province control and reviewed typed topology
are incomplete.

Water transport capacity can extend supply through controlled ports and zones, but blockade,
seasonal closure, or destroyed transport can remove that extension. Loss of a water route does not
rewrite administrative ownership.

## 8. Naval battle handoff

The approved battle-session architecture retains the three adapters `LAND`, `SIEGE`, and `NAVAL`.
No additional battle kind is introduced.

A `NAVAL` battle ticket requires:

- an engagement `WaterZone` and pinned `topologyRevision`;
- participating fleets, transports, commanders, and campaign locks;
- a versioned water terrain artifact containing depth, flow, wind inputs, banks, obstacles, ports,
  and valid landing objectives;
- deterministic objective packages such as defeat, escort, break blockade, protect convoy, or land.

Opposing fleets entering or occupying the same engagement zone under hostile orders create a
deterministically ordered encounter. Directed river reaches influence formation space and retreat.
Lake and coastal zones use their own bounded terrain artifacts rather than the full strategic raster.

An amphibious landing is a naval objective followed by an exactly-once campaign handoff to the target
land province. If land defenders require battle, the result creates or reinforces a land/siege battle
through the existing durable battle handoff; the naval adapter does not directly mutate the county.

## 9. Generator and evidence pipeline

The canonical water artifact is separate from `han-tiles.json`, for example
`han-water-topology-v1.json`, and is referenced by the map manifest. This avoids rewriting the large
land artifact for every reviewed crossing or reach.

The pipeline consists of:

1. a pure extractor for candidate river centerlines, lake basins, coast segments, and shoreline
   contacts;
2. a versioned adjudication ledger for names, reach endpoints, ports, ferries, fords, bridges,
   navigability, seasonality, and evidence;
3. a deterministic materializer that emits stable zones and typed edges;
4. structural, historical-evidence, route, supply, API, renderer, and replay audits.

The dormant automatic road/water A* exporter is not reactivated as authority. Candidate generation
may assist review, but only adjudicated rows enter the canonical artifact.

## 10. API and map presentation

The map API publishes land topology and the matching water-topology revision together. The web map
adds independent layers for navigable reaches, lake/coastal zones, barriers, crossings, ports,
blockades, selected routes, and route denial reasons.

Default political color remains land ownership. Water control uses a distinct visual channel and is
not painted as county territory. A route preview displays edge modes, turns/cost, required capacity,
seasonal limits, and likely battle/landing transitions. It must display the server-resolved path,
not run a separate browser BFS.

## 11. Invariants and tests

### Stable land contract

- Exact land province ID set remains 1,524; commanderies remain 172; jurisdictions remain 1,020.
- All historical seeds stay within their existing land province.
- Water generation cannot add, remove, renumber, or own a land province.

### Water topology

- Rebuilding identical inputs yields byte-identical water IDs, edge IDs, ordering, and hashes.
- Dangling, duplicate, and self edges are zero.
- Only reviewed directed-flow edges may be asymmetric.
- Every water zone has evidence, geometry, and at least one legal connection or an explicit isolated
  adjudication.
- Unapproved river crossings, lake shortcuts, and open-sea shortcuts are zero.
- Per-water-tile strategic nodes are forbidden.

### Runtime consistency

- Route preview, API precheck, reserved command, engine execution, supply, and replay share one path
  hash and topology revision.
- Changing dynamic blockade or capacity state revalidates the route with a typed deterministic
  result.
- Supply reachability changes require reviewed audit rows before destructive effects.
- Legacy modes remain unchanged.

### Required regression

Land provinces `45098` (盧縣) and `45022` (歷城縣) share six canonical raster edges, including five
plain-to-plain edges, and must resolve a one-edge dry land route under the current topology. A future
river-barrier adjudication may change this only with explicit crossing evidence and a reviewed
before/after route and supply diff.

### Naval determinism

- River, lake, coastal, blockade, escort, and landing fixtures replay to identical state hashes.
- Landing campaign effects are applied exactly once.
- A fleet cannot retreat through a closed, hostile, or geometrically disconnected edge.

## 12. Delivery sequence

1. **Topology contract:** records, stable IDs, revision/hash, loader, audits, and a small reviewed
   pilot artifact.
2. **Unified land resolver:** replace Han V2 `CityConst.path` authority and prove 盧縣↔歷城縣.
3. **Crossings and water transport:** path modes, capacity, embark/disembark, season, and blockade.
4. **Supply integration:** consume only approved supply edges with disconnection safety.
5. **API and map layers:** server path preview and water/control presentation.
6. **Naval adapter:** deterministic water terrain, fleet encounter, convoy, blockade, retreat, and
   landing handoff.
7. **Reviewed geometry alignment:** snap existing land banks to curated river evidence without land
   ID or count changes.

Each stage is independently gated. The topology foundation does not claim naval simulation is done,
and the naval adapter does not invent unreviewed strategic routes.

## 13. GitHub issue mapping

- **#473 / OPENSAM-213** owns strategic land/water topology, crossings, ports, and topology revision.
- **#474** consumes resolved paths for multi-turn movement, forced march, return, sortie, escort, and
  transport commands.
- **#475** consumes server paths and revisions for map preview and replay.
- **#349 / OPENSAM-172** owns the `NAVAL` battle adapter and its tactical rules.
- **#463** owns reviewed river direction, confluence, width, crossing, and bank-alignment evidence.
- **#492** owns reviewed sea-route and coastal provenance.
- **#598** receives only executed land-geometry repairs that preserve the canonical land ID set.

Issues receive comments only after the corresponding artifact, code, and verification evidence
exist. Partial foundations do not close downstream movement, renderer, or naval-battle issues.

## 14. Operations and documentation

The player guide must document barriers, crossing types, embarkation, blockade, route denial, and
naval/landing transitions when those behaviors become visible. Admin documentation must cover
topology revisions, artifact validation, rollback, and replay compatibility before deployment.

No deployment, merge, shared-branch push, scenario reset, or automatic canonical regeneration is
part of this design commit.
