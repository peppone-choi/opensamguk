# Scenario Province Ownership Proof Design

**Date:** 2026-08-31

**Status:** user-approved design

**Supersedes:** the political-ownership derivation in
`2026-08-30-scenario-province-affiliations-design.md` and any renderer rule that treats a runtime
city sample, nearest neighbour, commandery representative, or surrounding political colour as a
province-ownership claim

**Preserves:** `han-world-v2` / `han`, 1,524 province records, 172 parent administrative regions,
15 active scenarios, explicit unowned territory, direct city ownership in the existing gameplay
engine, and the rule that unowned or non-playable territory exposes no nation colour or name

## 1. Goal

Every one of the 1,524 county provinces has one reviewable initial political result in every active
scenario:

```text
province -> scenario nation | UNOWNED
         -> claim and evidence
         -> deterministic materialization trace
```

The resulting 22,860 assignments are generated from reviewed claims rather than inferred from map
appearance. A reviewer can answer all of the following without reading renderer code:

1. Why is this province owned by this scenario nation?
2. Which source or explicit IF rule supports the claim?
3. Was the claim direct to a county, expanded from attested administrative control, carried through
   a bounded time interval, or deliberately left unowned?
4. Which broader claim and exception produced the final result?
5. Would changing the source claim regenerate the same assignment, audit, and map image?

This specification covers the first delivery slice only: source claims, deterministic ownership
materialization, topology audit, and 15 review images. Runtime capture persistence, commandery
administration, and province-based movement consume the identities defined here but receive their
own specifications and pull requests.

## 2. Current canonical facts

- `data/map/han-tiles.json` contains exactly 1,524 `provinceRecords` and 172 `parentRegions`.
- Province record indexes `0..1523` all own at least one map cell; there are no missing indexes.
- A province record's stable external identity is `provinceRecords[].id`, not its current array
  position. Array position remains the PNG/RLE encoding index and must resolve back to that ID.
- The 15 active scenarios are `1010`, `1020`, `1021`, `1030`, `1031`, `1040`, `1041`, `1050`,
  `1060`, `1070`, `1080`, `1090`, `1100`, `1110`, and `1120`.
- `tools/scenario/han_ownership.json` already contains valuable reviewed scenario, nation,
  commandery, county, source, and IF decisions. It is an input to migrate, not a reason to preserve
  its commandery-to-city rendering shortcut.
- Runtime `city.nation_id` remains the current engine's settlement/control-node owner. It is not a
  complete initial province-ownership table.

## 3. Terms and separation of concerns

### 3.1 Province

A province is the smallest territorial identity in this slice. It has a stable `provinceId`, one
parent administrative region, geometry, adjacency, and an initial scenario owner or `UNOWNED`.

A province is not a routine command queue. Adding 1,524 ownership rows must not create 1,524
monthly player decisions.

### 3.2 Parent administrative region

`parentRegionId` is the map's commandery, kingdom, or equivalent external administrative parent.
It supplies hierarchy and a possible scope for an attested control claim. It does not own land and
does not automatically colour its children.

### 3.3 Administrative holding

Future administration uses the derived key:

```text
AdministrativeHolding = (worldId, parentRegionId, nationId)
```

It means “the provinces of this parent region directly owned by this nation.” It is a command and
display view, not a second territorial owner. A split parent region therefore yields two or more
holdings without overwriting province ownership.

### 3.4 Settlement control node

Future gameplay capture attaches each province to zero or one reviewed `controllerCityId`. Initial
ownership and controller identity are independent:

- the source claim determines the scenario's initial province owner;
- the controller city determines which province bundle changes after a gameplay capture;
- a controller city never proves initial ownership;
- a province with no controller remains valid in this slice and uses `controllerCityId: null`.

The phase-1 generated schema reserves `controllerCityId` with nullable semantics. Phase 2 will
populate and validate the complete controller mapping before runtime capture consumes it.

## 4. Delivery decomposition

The accepted sequence is:

1. **Ownership proof:** this specification and its implementation.
2. **Capture bundles:** durable province control, controller-city mappings, city-capture fan-out,
   API, renderer, log, and replay.
3. **Administrative commands:** nation policy -> administrative holding policy -> province
   exception, with no province-sized routine queue.
4. **Movement:** player-selected destination and deterministic automatic routing over province
   edge adjacency; waterways, sea routes, crossings, ports, and ferries remain explicit networks.

No phase may silently implement a later phase by deriving values in the renderer.

## 5. Reviewed claim source

The authored source is:

```text
data/curated/han/scenario-province-claims-v1.json
```

It replaces political content in `tools/scenario/han_ownership.json` after a lossless migration.
The old file may remain temporarily as a generated compatibility view, but there may be only one
authored source.

### 5.1 Document shape

```json
{
  "schemaVersion": 1,
  "mapId": "han-world-v2",
  "unitSet": "han",
  "activeScenarioCodes": [1010],
  "evidence": [
    {
      "evidenceId": "SGZ-07-YANZHOU-194",
      "sourceType": "STANDARD_HISTORY",
      "work": "三國志",
      "section": "魏書七 張邈傳",
      "locator": "興平元年兆州叛迎呂布",
      "excerpt": "郡縣皆應，唯鄄城、東阿、范為太祖守。",
      "url": "https://ctext.org/text.pl?if=gb&node=602269"
    }
  ],
  "scenarios": [
    {
      "scenarioCode": 1030,
      "effectiveYear": 194,
      "placementBasis": "HISTORICAL",
      "nationRefs": [
        {"nationKey": "S1030-CAO_CAO", "scenarioNationName": "조조"}
      ],
      "claims": []
    }
  ]
}
```

The real top-level document additionally stores the lowercase SHA-256 of the complete province
catalog input. The abbreviated example omits the value so this design document does not freeze a
map build hash; the curated file and generated artifact must agree on the exact current hash.

`nationKey` is an authored stable key that is never derived from array position or display name.
`scenarioNationName` is the exact compatibility join to the current scenario resource. The
materializer requires that name to occur exactly once, records its numeric nation ID, and fails on
zero or multiple matches. A later scenario schema may store `nationKey` directly; until then the
reviewed key-to-name row is the single compatibility seam.

Accepted evidence source types are `STANDARD_HISTORY`, `CHRONICLE`,
`CONTEMPORARY_GEOGRAPHY`, `LATER_GEOGRAPHY`, `PROJECT_POLICY`, and `IF_DESIGN`. A negative corpus
search is recorded as a `PROJECT_POLICY` evidence row with the searched corpus, terms, date, and
result; it is not stated as proof of historical non-existence.

### 5.2 Claim kinds

Every claim has `claimId`, `claimKind`, `ownerNationKey` or `UNOWNED`, `evidenceIds`, `rationale`,
and an explicit target.

```text
SCENARIO_BASELINE_UNOWNED
  A required scenario-wide reviewed policy starts every catalog province as unowned. Its rationale
  states that legal title, neighbouring colour, and missing evidence do not create effective
  control. Positive claims replace this baseline; the baseline is not evidence of non-existence.

PROVINCE_DIRECT
  A source or reviewed placement names an exact county/province.

ADMIN_REGION_CONTROL
  A source proves control of a named commandery, kingdom, or comparable parent. It expands only to
  the catalogued child province IDs of that exact parent.

TEMPORAL_CARRY
  A prior reviewed claim remains valid through an explicit inclusive/exclusive interval and names
  the claim it inherits. An intervening transfer or loss ends the interval.

IF_SCENARIO
  An IF branch names its divergence point, placement rule, affected provinces, and balance
  rationale. Historical interpolation is forbidden.

UNOWNED_EXPLICIT
  A reviewed decision leaves a province or parent scope unowned because control is unattested,
  contested beyond the scenario abstraction, wandering, tribal without a represented polity, or
  intentionally outside political play.
```

`ADMIN_REGION_CONTROL` is not visual interpolation. It is allowed only when the evidence and
rationale explicitly support control at that administrative scope. A title alone does not prove
effective control unless the scenario policy expressly uses that title as the placement rule.

### 5.3 Precedence and conflicts

Materialization applies claims in this fixed order:

```text
SCENARIO_BASELINE_UNOWNED
  -> ADMIN_REGION_CONTROL / TEMPORAL_CARRY
  -> PROVINCE_DIRECT / UNOWNED_EXPLICIT
  -> IF_SCENARIO explicit changes
```

Later tiers may override earlier tiers only when the overriding claim names `overridesClaimIds`.
Two claims at the same tier assigning different owners are an error. Source array order, JSON map
order, nation order, and renderer order never resolve a conflict.

Every scenario must have exactly one `SCENARIO_BASELINE_UNOWNED` claim. A province untouched by a
positive claim retains that reviewed result. Missing or duplicate baselines fail materialization;
`UNOWNED` is therefore explicit policy, not an absent row or renderer fallback.

## 6. Generated ownership artifact

The deterministic output is:

```text
data/map/han-scenario-province-ownership-v1.json
```

It is generated and never edited by hand. It contains source hashes, generator policy version,
sorted scenario rows, and exactly 1,524 assignment rows per active scenario.

```json
{
  "scenarioCode": 1030,
  "provinceId": "85065",
  "ownerNationId": 4,
  "ownerNationKey": "S1030-CAO_CAO",
  "controllerCityId": null,
  "winningClaimId": "S1030-CAO-JUANCHENG",
  "claimTrace": ["S1030-LU-BU-YANZHOU", "S1030-CAO-JUANCHENG"],
  "basisType": "PROVINCE_DIRECT",
  "evidenceIds": ["SGZ-07-YANZHOU-194"],
  "confidence": "DIRECT"
}
```

For `UNOWNED`, both owner fields are `null`; `winningClaimId`, evidence, rationale, and trace remain
mandatory. Assignment rows sort by numeric scenario code and province catalog order. Hashes exclude
timestamps and machine paths.

The artifact does not duplicate nation colours. Runtime scenario nations remain the colour SSoT.
`confidence` is one of `DIRECT`, `ADMIN_SCOPE`, `TEMPORAL`, `IF`, or `EXPLICIT_UNOWNED` and is
derived from the winning claim kind rather than entered independently.

## 7. Materializer

The materializer is a focused tool under `tools/scenario/` with no renderer dependency. It:

1. loads and validates the province catalog and active scenario catalog;
2. validates all evidence and nation references;
3. expands only typed claim scopes;
4. applies explicit precedence and override edges;
5. emits complete assignments and claim traces;
6. runs the topology audit;
7. writes deterministic JSON and review images only after all validation passes.

The tool supports `--check`, which regenerates in memory and compares bytes and hashes without
writing. CI uses `--check`.

Unknown province IDs, parent IDs, scenario codes, nation keys, evidence IDs, override claim IDs,
or invalid effective intervals are fatal. The tool never substitutes a similarly named place.

## 8. Topology and political audit

The audit consumes the canonical province edge-adjacency graph derived from `han-tiles.json`.
Corner-only contact is not adjacency.

### 8.1 Completeness and identity

For every active scenario:

- assignment count is exactly 1,524;
- every catalog province ID occurs exactly once;
- every non-null owner resolves to a nation in that scenario;
- `UNOWNED` has a reviewed claim and reason;
- no assignment targets outside-map cells or a missing province record;
- no owner is obtained from `city.nation_id`, nearest neighbour, surrounding majority,
  `parentRegionId` alone, or renderer state.

### 8.2 Hole audit

An `UNOWNED` province whose edge-neighbours all have the same non-null owner is a hole candidate.
It is an error unless a reviewed allowlist entry identifies the province, scenario, evidence, and
reason. Multi-province holes are found as connected `UNOWNED` components and checked against their
boundary owners by the same rule.

Owned islands inside a different owner's connected component are enclave candidates, not
automatically errors. They require direct evidence or an allowlist entry.

### 8.3 Connectivity audit

For each scenario nation, the audit lists connected components of owned provinces. Every component
after the largest must be explained by at least one of:

- direct historical/IF evidence in its winning claims;
- an approved maritime or remote-gate relationship already present in the map contract;
- a reviewed disconnected-territory allowlist entry.

The audit reports but does not invent a bridge. Adjacency colour and commandery membership cannot
join disconnected components.

### 8.4 Parent split audit

For every `parentRegionId`, the report lists owner counts and `UNOWNED` counts. A split parent is
valid only when each minority or exceptional province has a direct or explicit unowned claim. This
is the gate that protects cases such as Cao Cao retaining Juancheng, Fan, and Dong'e while the rest
of the surrounding territory answers Lü Bu.

### 8.5 Allowlist

The authored allowlist lives inside the claim source and has no wildcard entries. Each row contains:

```text
scenarioCode, auditKind, provinceIds, evidenceIds, rationale, reviewState=APPROVED
```

An allowlist entry suppresses only the named audit finding. It does not create ownership.

### 8.6 Visual coherence audit

“Natural” political boundaries come from coherent reviewed claims and the existing province
geometry, not from paint smoothing. For every owner and `UNOWNED` component, the audit reports:

- isolated one-province components;
- one-province owner spikes whose edge-neighbours all have one different owner;
- alternating owner sequences along the province graph;
- perimeter-to-area outliers relative to other components in the same scenario;
- narrow connectors whose removal splits a component;
- small internal components completely enclosed by one other owner.

An isolated province, spike, enclave, or narrow connector fails unless its winning direct claim or
an exact approved allowlist entry explains it. The ratio reports are review signals rather than
automatic ownership rules: no threshold may rewrite a historically supported boundary.

The materializer must not run raster morphology, majority voting, nearest-neighbour fill, polygon
simplification that changes province identity, or colour interpolation. A failed coherence audit is
fixed in the authored claim, province geometry source, or explicit allowlist and then regenerated.

## 9. Review artifacts

The tool produces one overview and 15 full-resolution PNGs under a report output directory. These
images are review artifacts, not runtime assets or ownership input.

Every image uses:

- exact scenario nation colours from the scenario resource;
- black for non-playable/outside cells;
- a neutral unowned fill distinct from every nation colour;
- province borders at review resolution;
- a legend with nation name, province count, and unowned count;
- scenario code, title, effective date, source artifact hash, and audit result.

Political fill uses one flat, muted scenario colour per nation at fixed opacity over the canonical
terrain. Province edges remain legible at review scale; parent-region edges are stronger but do not
hide province boundaries. Texture may vary luminance inside a province by a small fixed amount, but
must not change hue, imply another owner, cross a boundary, or reduce text contrast. Labels and
symbols are clipped or anchored to their own province and may not cover audit markers.

The overview uses identical map bounds and scale for all scenarios. A browser-visible gallery links
the overview, individual PNGs, claim rows, and audit findings so visual review can lead back to
evidence. Passing the audit does not imply that the historical placement is correct; the images are
the required human review surface.

## 10. Command and statistic boundaries preserved for later phases

The current engine overloads `City` with ownership, commerce, agriculture, security, defence,
wall, population, trust, movement target, and battle target. Later phases must split these concepts
rather than renaming `City` to “commandery.”

The accepted target scopes are:

```text
NATION | ADMIN_HOLDING | PROVINCE | SETTLEMENT | ARMY | GENERAL
```

The later command contract carries `actorScope`, `targetScope`, and `effectScopes`.

| Domain value or action | Player-facing scope | Authoritative state |
| --- | --- | --- |
| agriculture, commerce, population, security, trust | administrative holding | province, aggregated for command/display |
| tax and development policy | administrative holding or nation | persistent policy |
| walls, fortifications, garrison, siege damage, local stockpile | settlement | settlement |
| troops, training, morale, movement | army/general | army/general |
| technology, treasury, diplomacy, law | nation | nation |
| terrain, direct ownership, facility, disaster, supply transit | normally no routine order | province |

An administrative order applies only to the acting nation's currently owned provinces in that
parent region. Enemy or unowned provinces are excluded and shown in preview. Province-level state
must not create province-level routine commands.

## 11. Movement boundary preserved for later phases

This slice does not change movement. It reserves these accepted rules for the movement spec:

- the player chooses a destination and optional waypoints, not every crossed province;
- land provinces sharing a raster edge are traversable without a `ROAD` requirement;
- corner-only contact is not adjacency;
- terrain, season, hostility, control, and supply change cost or risk, not base land existence;
- `WATERWAY`, `SEA_ROUTE`, crossing, port, ferry, and remote gate are explicit reviewed networks;
- transit does not transfer political ownership;
- capture of a reviewed control node changes only its explicit province bundle;
- route calculation, preview, AI, execution, log, and replay consume the same versioned topology.

Earlier route designs that require a universal road/corridor for ordinary land adjacency are not
the authority for this rule.

## 12. Error handling

All validation is fail closed. Errors are typed and identify scenario, province or claim, and the
violated rule. The minimum error families are:

```text
CATALOG_HASH_MISMATCH
ACTIVE_SCENARIO_SET_MISMATCH
UNKNOWN_SCENARIO
UNKNOWN_PROVINCE
UNKNOWN_PARENT_REGION
UNKNOWN_NATION_KEY
UNKNOWN_EVIDENCE
INVALID_EFFECTIVE_INTERVAL
INVALID_OVERRIDE_EDGE
CLAIM_CONFLICT
UNASSIGNED_PROVINCE
DUPLICATE_ASSIGNMENT
UNREVIEWED_UNOWNED
UNALLOWLISTED_HOLE
UNEXPLAINED_ENCLAVE
UNEXPLAINED_DISCONNECTED_COMPONENT
GENERATED_ARTIFACT_DRIFT
```

One failure prevents generated JSON and image replacement. A failed build never falls back to the
current city-colour renderer or the previous generated ownership artifact.

## 13. Testing and acceptance

### 13.1 Unit tests

- claim schema and reference validation;
- province-direct override of a broad administrative claim;
- same-tier conflict rejection independent of input order;
- explicit unowned assignment and rationale preservation;
- IF claim separation from historical claims;
- bounded temporal carry and intervening-transfer rejection;
- stable byte output under shuffled source object order;
- hole, enclave, disconnected-component, parent-split, and allowlist fixtures.

### 13.2 Repository contract tests

- active scenario set is exactly the 15 codes in this document;
- province catalog is exactly 1,524 unique stable IDs with live cells;
- generated output contains exactly 22,860 assignments;
- every assignment traces to reviewed claims and evidence;
- no code path in the materializer imports renderer ownership binding;
- `--check` is byte-clean after regeneration;
- scenario JSON nation identities and generated numeric nation IDs agree;
- existing scenario seed, roster lifecycle, route-node selection, map, typecheck, and production
  build gates remain green.

### 13.3 Historical regression fixtures

At minimum, fixtures pin:

- 194 Yanzhou: Juancheng, Fan, and Dong'e remain Cao Cao while the reviewed surrounding claims
  resolve to Lü Bu or explicit unowned results;
- Huang Turban scenario: Later Han represents effective court control, not legal title over the
  entire empire; country name and ruler identity remain separate;
- scenario 1100 and 1110: Shangyong and Fangling use explicit county claims rather than inheriting
  Hanzhong's owner;
- wandering forces receive no province without an exact reviewed placement;
- scenario 1120 uses `IF_SCENARIO` claims and never year interpolation.

### 13.4 Visual acceptance

- all 15 review maps render from the generated artifact, never from runtime city samples;
- no single- or multi-province rat-bite hole lacks a visible audit record;
- no unexplained isolated owner speck, alternating checkerboard run, ownership spike, or narrow
  connector remains;
- unowned areas are visually distinct and expose no nation name;
- split parent regions are visible and match the parent-split report;
- the browser gallery opens every full-resolution PNG and its evidence trace;
- screenshot review is recorded separately from build/deployment success.

## 14. Non-goals of the first pull request

- no `province_control` database table or migration;
- no city-capture fan-out;
- no change to live command resolution, income, AI, or replay;
- no commandery policy UI;
- no province-stat migration;
- no province pathfinder or movement command change;
- no runtime political renderer cutover;
- no automatic historical claim generation from prose;
- no filling of unknown territory for visual completeness.

The first pull request is complete only when the authored source, deterministic materializer,
generated 22,860-row artifact, audits, tests, and 15 review maps agree. Runtime political paint
continues to use the existing compatibility path until phase 2 adds durable capture state and cuts
the renderer over without freezing a live world to its initial scenario map.
