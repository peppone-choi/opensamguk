# Han Tactical Province, Administrative Hierarchy, and Map Symbol Design

**Date:** 2026-08-27

**Status:** user-approved design

**Supersedes:** the hierarchy encoding and city-marker level model in
`2026-08-26-static-province-id-map-design.md`; the marker tiers in
`2026-08-20-han-map-visual-design.md`

**Preserves:** one-time lossless map baking, neutral marker bodies, pointer-centered wheel zoom,
no straight all-adjacency road web, deterministic output, and terrain-only failure behavior

## 1. Goal

The `han` map uses a Paradox-like territorial model:

```text
raster cell -> tactical province -> county/direct territory -> commandery/kingdom
                                \-> settlement or strategic site
```

- A tactical province is the smallest selectable, ownable, movable, and supplied land unit.
- A county is a collection of tactical provinces and has one county seat.
- A commandery or kingdom is a collection of counties or explicit direct territories.
- The administrative hierarchy is resolved for the scenario year; it is not baked into pixels.
- The map is baked once into one lossless province-ID image and reused by all `han` scenarios.
- City size, administrative role, and site type are independent data axes.
- Nation color fills territory but never the body of a city, fortress, landmark, or terrain icon.

The first production partition targets an average of **3-5 tactical provinces per county**.
With 962 current county points and 332,914 politically covered raster cells, the deterministic
baseline target area is 88 covered cells per tactical province. Small counties may remain one or
two provinces; large counties and strategically dense counties may exceed five.

## 2. Terms and identities

### 2.1 Tactical province

`provinceId` is a stable positive integer. A province has:

- one connected raster component;
- non-zero covered area;
- one `ProvinceKind`;
- terrain composition and a representative anchor;
- adjacency derived from shared raster edges;
- exactly one effective county or direct-territory parent for every supported scenario year;
- zero or one settlement and zero or one primary strategic site.

```text
ProvinceKind = SETTLEMENT | RURAL | FORTRESS | PORT | LANDMARK | NATURAL_STRATEGIC
```

Every covered land cell belongs to exactly one province. Sea/outside uses ID `0` in the PNG and
has no catalog entry. A province with no city is not empty: its rural, fortress, port, landmark, or
natural-strategic identity is explicit.

### 2.2 Administrative unit

Administrative units use stable string IDs because names and parentage change over time.

```text
AdministrativeUnitKind = COUNTY | DIRECT_TERRITORY | COMMANDERY | KINGDOM | EXTERNAL_POLITY
```

- `COUNTY` represents 縣, 邑, 道, and 侯國 while preserving the historical subtype as metadata.
- `DIRECT_TERRITORY` is an explicit compatibility unit used only when a commandery is attested but
  no child county can yet be placed. Its display name is `<군국명> 직할령` and its provenance is
  `PROVISIONAL`, never fabricated as a historical county.
- A playable commandery or kingdom may not have zero children.
- A direct territory must be replaced by sourced counties when evidence and coordinates become
  available; its stable ID remains as retired history rather than being silently reused.

Effective-dated relations carry inclusive `effectiveFrom` and exclusive `effectiveTo` years.
`null` means open-ended. The scenario resolver chooses exactly one active relation at `startYear`.

### 2.3 Settlement

A settlement is a physical place located in one tactical province. Existing runtime city IDs are
preserved as `settlementId` during migration so saves and scenario references do not silently point
to different places.

Settlement properties are orthogonal:

```text
SettlementRank = 1 SMALL_SETTLEMENT
               | 2 TOWN
               | 3 WALLED_TOWN
               | 4 GREAT_CITY
               | 5 METROPOLIS

SettlementType = ORDINARY | FORTIFIED | PORT | TRIBAL_CENTER

AdministrativeRole = COUNTY_SEAT | COMMANDERY_SEAT | PROVINCIAL_SEAT
                   | POLITY_CAPITAL | IMPERIAL_CAPITAL
```

One settlement may hold multiple effective-dated roles. Changing a seat moves a role marker; it
does not automatically change settlement rank. Rank may change through scenario initialization or
gameplay growth and decline.

The historical 令/長 distinction is stored as `magistrateClass = LING | ZHANG | UNKNOWN`. It is not
a settlement rank. The current numeric levels 10/11 therefore stop making 令縣/長縣 visually larger
than capital level 9.

### 2.4 Strategic site

Historically or strategically meaningful places may receive their own tactical province even when
they are not counties or cities.

```text
SiteKind = MOUNTAIN_BATTLEFIELD | PASS | FORTRESS | FORD | PORT
         | BATTLEFIELD | PALACE | TOMB | SHRINE | MONUMENT

SiteRank = 1 LOCAL | 2 REGIONAL | 3 REALM
```

Inclusion requires at least one strong criterion:

1. controls movement, a pass, a crossing, or a navigable approach;
2. materially affects defence or supply;
3. is the location of a major attested battle, garrison, or political event;
4. has realm-level ritual or legitimacy value;
5. can be placed with sufficient geographic confidence.

The initial candidate families include 定軍山, 劍閣, 陽安關, 街亭, 祁山, 五丈原, 官渡, 赤壁,
夷陵, 長阪, 濡須, major passes, and major crossings. This is a candidate policy, not permission to
assert unsourced names or locations.

Examples confirmed by the bundled historical index include:

- 《三國志》卷36: `建安二十四年，於漢中定軍山擊夏侯淵。`
- 《三國志》卷28: `維遂東引，還守劍閣。鍾會攻維未能克。`
- 《三國志》卷35: `與郃戰于街亭。`
- 《三國志》卷44: `分護陽安關口、陰平橋頭，以防未然。`

`陽平關` and `陽安關` are not merged merely because later sources or games use the names
interchangeably. Alias identity requires an explicit geographic claim.

## 3. Historical evidence policy

The reference corpus is `https://github.com/peppone-choi/shiliao`, checked out read-only at
`references/sources/shiliao`. Its 1,271-volume local index is queried in traditional Chinese.

Every historical administrative unit, seat relation, strategic site, or historically assigned
rank carries one or more evidence records:

```json
{
  "book": "三國志",
  "volume": "卷36",
  "section": "蜀書六 關張馬黃趙傳",
  "quote": "建安二十四年，於漢中定軍山擊夏侯淵。",
  "grade": "STANDARD_HISTORY",
  "claim": "site-attested",
  "locationConfidence": "MEDIUM"
}
```

Evidence grades are not averaged:

```text
STANDARD_HISTORY | CHRONICLE | CONTEMPORARY_GEOGRAPHY
LATER_GEOGRAPHY | COMMENTARY | NOVEL
```

Search order is:

1. exact traditional term in era sources;
2. attested name variants;
3. later geographic works for placement and distances;
4. novel search recorded separately, never promoted to historical fact.

Zero results mean `UNKNOWN in this corpus`, not non-existence. Quotes remain verbatim and carry
book, volume, and section. Coordinates from CHGIS or another geographic dataset are separate
claims from textual attestation.

## 4. Static source and generated artifacts

### 4.1 Committed sources

- `data/map/han-tiles.json`: terrain, current place anchors, and source grids.
- `data/map/han-administrative-history.json`: stable units, lifecycles, county-to-commandery
  memberships, and seats.
- `data/map/han-strategic-sites.json`: reviewed strategic-site candidates and provenance.
- `data/map/junguozhi.json`: extracted 郡國志 evidence already used by the project.

The deterministic partitioner additionally emits committed generated
`data/map/han-province-assignments.json`, which maps the newly allocated province IDs to
effective-dated county/direct-territory parents. It is regenerated together with
`han-tiles.json`, never edited by hand.

Generated data is never hand-edited. Historical source manifests are reviewed data, not generator
output.

### 4.2 Partition algorithm

The province partition is deterministic:

1. Resolve the baseline county/direct-territory coverage for the map reference year.
2. Count covered cells for each parent.
3. Compute `baseCount = clamp(round(coveredCells / 88), 1, 12)`.
4. Add mandatory seeds for the county seat and each accepted strategic site.
5. Add remaining seeds by deterministic farthest-point selection using terrain-weighted grid cost;
   ties resolve by row, column, then stable source ID.
6. Grow regions within the parent coverage using deterministic multi-source priority expansion.
7. Repair single-cell necks and disconnected fragments without crossing the parent coverage.
8. Assign stable IDs through a committed registry; existing IDs are never renumbered.

Mandatory strategic seeds may raise a parent's count above `baseCount`. The global audit must show
an average between 3.0 and 5.0 provinces per sourced county, while reporting one-province counties
and counties above ten individually.

### 4.3 Lossless province image

The deployment artifact remains one image:

```text
<generated-map-dir>/han-provinces.png
<generated-map-dir>/han-provinces.meta.json
```

The RGB value stores only the tactical province identity:

```text
0                = sea / outside
provinceId + 1   = covered province, encoded as unsigned RGB24 big-endian
```

The PNG is lossless RGB8, non-interlaced, unscaled, and one pixel per source cell. Commandery,
county, nation, settlement rank, and site kind are forbidden in pixel bits. The metadata records
schema version, dimensions, source and output hashes, province count, covered-cell count, and ID
registry hash without timestamps or machine paths.

## 5. Scenario-year hierarchy snapshot

At scenario load, `HanHierarchyResolver.resolve(startYear)` produces an immutable snapshot:

```text
provinceToCounty: provinceId -> administrativeUnitId
countyToCommandery: administrativeUnitId -> administrativeUnitId
countySeat: countyId -> settlementId or direct-anchor siteId
rolesBySettlement: settlementId -> set<AdministrativeRole>
```

Validation fails the scenario when:

- a land province has zero or multiple active parents;
- a county/direct territory has no province;
- a county has no seat or has multiple active seats;
- a commandery/kingdom has no active child;
- a relation references an inactive unit;
- an effective range overlaps another relation for the same child.

Commandery and county boundaries are derived at runtime by comparing the resolved parents of
neighboring province IDs. A boundary change between scenarios requires metadata only; the PNG is
not regenerated.

## 6. Ownership, movement, and supply

### 6.1 Ownership

`ProvinceState` is the authoritative territorial state:

```text
worldId, provinceId, ownerNationId, controllerNationId, supplyState, revision
```

County and commandery ownership are derived views. They are never independently written. A county
may be contested when its provinces have different controllers.

Settlement economic state remains attached to `settlementId`. Its containing province determines
territorial control; the settlement can have a siege or occupation state without becoming a second
territorial owner.

### 6.2 Adjacency

Two land provinces are adjacent when their raster cells share at least one cardinal edge. Diagonal
corner contact is not adjacency. No minimum shared-edge threshold may delete a real contact; noisy
one-cell contacts must be fixed in the partition itself.

Every land adjacency permits movement and supply. Terrain, passes, crossings, hostility, damage,
and weather may change cost and capacity, but may not silently remove the edge. Islands and
intentional sea routes use explicit reviewed maritime edges.

One canonical `ProvinceTopologySnapshot` is consumed by movement, pathfinding, AI distance, supply,
and the renderer. The current generated settlement-to-seat shortcuts and straight city adjacency
roads are retired for `han` after cutover.

### 6.3 Supply

Supply starts from controlled capitals and configured supply hubs, then traverses controlled or
permitted province edges. The result is province-level reachability plus capacity metadata.
Settlement `supplyState` is a derived projection of its containing province during the compatibility
period.

The first vertical slice preserves deterministic FIFO traversal and stable ordering. Capacity and
attrition balancing may follow, but the graph identity must already be province-based.

## 7. Rendering and interaction

### 7.0 Isometric projection contract

Cells and symbols are isometric. The rectangular PNG is an identity storage surface, not a flat
screen projection. Runtime rendering preserves the existing 2:1 diamond transform:

```text
screenX = (col - row) * scale + offsetX
screenY = (col + row) * 0.5 * scale + offsetY
```

- Every logical raster pixel renders as one 2:1 isometric diamond cell.
- Province, county, and commandery borders follow diamond edges, not square pixel edges on screen.
- Settlement and site sprites use an isometric three-quarter view consistent with the terrain.
- A sprite is anchored by its ground-contact footpoint at the owning cell center; transparent
  height extends upward and does not change hit identity.
- Fortresses, passes, mountains, ports, ruins, and cities share the same light direction, camera
  pitch, baseline, and nominal footprint scale.
- Zoom scales cells and sprites together. Administrative overlays may keep a minimum readable
  stroke width, but icon geometry may not billboard into a flat top-down symbol set.
- Hit testing converts the pointer through `screenToCell` first, resolves `provinceId`, then tests
  the isometric sprite footprint for the contained settlement or site.

### 7.1 Political layer

- Decode the static province PNG once per map code.
- Compose nation-color fills from live `ProvinceState` ownership.
- Draw thin province edges for every differing adjacent province ID.
- Draw stronger county and commandery edges from the resolved hierarchy snapshot.
- Do not draw political coastline against ID `0`.
- Cache decoded IDs, paths, and political canvases; zoom and pan only transform prepared layers.

### 7.1.1 Joined terrain and waterway tiles

The visual terrain is a composable isometric tile system, not a collection of isolated diamond
illustrations. Every base tile uses one shared 2:1 diamond, camera, light, edge height, and four
edge sockets. Adjacent tiles must be pixel-compatible at their shared edge.

Rivers have two representations with one generated source contract:

- a hydrology skeleton on cardinal cell-to-cell edges, carrying stable river ID, flow direction,
  width class, navigability, and crossing metadata;
- a visual footprint derived from that skeleton. Minor rivers use connected channel overlays;
  major rivers dilate to multiple water cells and receive explicit shoreline edge/corner overlays.

Autotiling uses deterministic connection masks. Channel pieces cover end, straight, bend,
T-junction, cross, source, confluence, and mouth cases. Shore pieces cover exposed diamond edges,
outer corners, and inner corners. Bridges, fords, ports, rapids, and waterfalls are overlays on the
same hydrology edge or node; they do not replace the continuous channel below them.

The large-river rule is structural: a major river is never represented by enlarging a single thin
river icon. Its width-class dilation creates a continuous multi-cell water surface with two banks.
Province identity remains underneath the visual footprint, while movement and supply crossing cost
is resolved from the hydrology skeleton. This preserves territorial completeness without making a
wide river an accidental ownership hole.

Seam QA renders every legal pair and every mask at DPR 1 and 2, then rejects transparent cracks,
double banks, mismatched waterlines, discontinuous flow, or shadows leaking across an undeclared
edge socket.

### 7.2 Symbols

Icons are neutral and semantic:

- settlement body: rank 1-5 controls size and detail;
- settlement type: ordinary, fortified, port, or tribal silhouette;
- administrative roles: non-exclusive rings, standards, crowns, or label priority;
- strategic site: mountain, pass, fortress, ford, battlefield, shrine, or monument silhouette;
- nation indication: a historically styled flag, standard, or ownership ring only;
- capital, selection, event, danger, and supply states remain overlays.

The icon body and glow never receive nation color.

Ownership flags are a separate layered asset family. They use reconstructed Han-period military
standard forms rather than modern rectangular national flags. At minimum, the family distinguishes
a local ownership pennant, a county/commandery-seat standard, and a capital `牙旗`/`纛` silhouette.
The pole, finial, cords, and neutral cloth shading are authored pixels; only an explicit cloth mask
and emblem mask receive the polity palette or sigil at runtime. Each settlement and fortress symbol
declares a banner socket so the flag shares the same isometric footpoint, wind direction, light,
and occlusion order without repainting the underlying icon.

Textual names and functions of standards are cited from `shiliao`. Exact shape, material, or color
not established by the corpus is labeled `VISUAL_RECONSTRUCTION` in the asset manifest, with any
separate archaeological or museum visual reference recorded independently. A faction name glyph is
not baked into the master; the renderer supplies a legible emblem/glyph layer and a color-blind-safe
outline pattern from nation metadata.

### 7.2.1 Symbol asset production

`opensamguk-images` is the source of truth for icon masters, generation records, preview sheets,
and the atlas manifest. `opensamguk` contains only deterministic web deployment exports and their
runtime loader.

The production pipeline is hybrid and uses the installed Apache-2.0 `sprite-gen` tool as the
curation and deterministic still-export layer:

1. Generate high-resolution isometric still candidates with the built-in ImageGen path. AI is
   confined to raw candidate creation; no provider or runtime generation is part of the game.
2. Import the candidates with `unpack_atlas_run.py --pngs-dir`. A sibling `meta.json` declares the
   2:1 tile and ground-contact anchor so the Korean curation webview shows its isometric alignment
   grid.
3. Approve one style board before producing the family. The board contains a rank-1 settlement, a
   rank-5 metropolis, a pass, and 定軍山 so scale, camera, materials, and terrain treatment can be
   compared together.
4. Generate/edit masters against the approved reference, preserving a fixed three-quarter camera,
   2:1 ground footprint, upper-left light, neutral materials, transparent background, and a common
   ground-contact baseline.
5. Save selection and non-destructive alignment in `curation.json`, then export still masters only
   through `export_curated_pngs.py`. Pre-curation `frames/` and raw candidates are never shipped.
6. A project-specific deterministic image compiler validates alpha, normalizes the footpoint,
   downsamples with one fixed filter, consumes the curated stills, packs the atlas in manifest
   order, and emits hashes. It does not redraw or creatively alter masters.
7. Dynamic administrative and political overlays remain code-native Canvas/SVG layers: nation
   flag/ring, county-seat and commandery-seat standards, capital crown, selection, event, danger,
   and supply state.

Master families are:

- settlement rank 1-5, with ordinary/fortified/port/tribal variants where data requires them;
- pass, fortress, port, ford, mountain battlefield, battlefield, shrine, tomb, and monument;
- local ownership pennant, administrative-seat standard, and capital standard, each with separate
  neutral body, cloth-color mask, emblem mask, and attachment socket metadata;
- a neutral fallback for each family.

All masters must be original generated work with provenance recorded in the manifest. Third-party
city or strategy-game icons are not tracing references and are not copied into the source set.

### 7.3 Semantic zoom

- Overview: imperial/polity capitals, provincial seats, realm-rank sites, major terrain labels.
- Regional: commandery seats, rank 4-5 settlements, regional strategic sites.
- County: county seats, rank 2-5 settlements, passes, ports, fortresses, battlefields.
- Close: every settlement, local site, province label, and province hover outline.

Wheel zoom remains pointer-centered. The maximum is 32 CSS pixels per isometric grid unit,
independent of device-pixel ratio. Hit testing selects provinces first and resolves the contained
settlement or site as secondary information.

## 8. Migration and compatibility

The cutover is staged because the current engine treats `cityId` as settlement, territory, movement
node, supply node, and command target simultaneously.

1. Add province/admin/settlement catalogs without changing current gameplay.
2. Switch the PNG to province-only RGB24 and derive hierarchy boundaries from metadata.
3. Add world-scoped `ProvinceState` and seed it from existing city ownership through explicit
   settlement-to-province mappings.
4. Switch supply and distance/path queries to `ProvinceTopologySnapshot`; project results back to
   settlement state for unchanged consumers.
5. Add province location to operations/armies and convert movement one vertical slice at a time.
6. Remove `han` dependence on `CityConst.path` only after command, AI, supply, war, and scenario
   regression gates consume province topology.

Non-`han` maps retain the legacy city graph and level map. The shared `CityConst.levelMap` is not
globally renumbered. `han` receives a new settlement-rank adapter so che/miniche compatibility and
frozen tests are not rewritten as collateral damage.

## 9. Failure behavior

- Invalid or missing province PNG: terrain-only rendering; no cross-map fallback.
- Invalid hierarchy snapshot: scenario startup fails with typed validation errors.
- Missing evidence: candidate remains `UNKNOWN` or excluded; no plausible replacement is invented.
- Missing live province ownership: render neutral territory.
- Missing settlement/site symbol: render a neutral generic symbol of the correct semantic type.
- Province graph mismatch between server and client hashes: disable territorial commands and show a
  stale-map error rather than issuing commands against different topology.

## 10. Acceptance criteria

1. Every covered land pixel decodes to exactly one stable tactical province ID.
2. Every tactical province is connected, non-empty, named, typed, and has an effective parent.
3. Every supported-year county/direct territory contains at least one province; every commandery or
   kingdom contains at least one child.
4. The audited mean is 3-5 tactical provinces per sourced county, with deterministic exceptions.
5. Accepted strategic sites such as 定軍山 have their own province and verbatim evidence record.
6. Province adjacency is cardinal shared-edge adjacency; every such land edge is traversable by
   movement and supply with deterministic cost/capacity metadata.
7. The baked PNG contains province IDs only. Scenario-year hierarchy changes do not rebake it.
8. Province ownership is authoritative; county/commandery ownership and boundaries are derived.
9. Settlement rank 1-5, administrative roles, and site type render independently.
10. Nation color never fills icon bodies.
11. Cells, border edges, settlement icons, and site icons use the same 2:1 isometric projection and
    ground-contact anchor contract.
12. Ownership flags are historically styled layered assets; polity color and emblem pixels are
    confined to declared masks and remain independently readable without color.
13. Every legal terrain adjacency is seam-safe; major rivers form continuous multi-cell channels,
    and every gameplay river crossing maps to a hydrology edge.
14. Wheel zoom is pointer-centered and reaches the approved maximum without repeated grid baking.
15. `shiliao` queries, verbatim quotes, source grades, location confidence, generator hashes, graph
    invariants, backend tests, frontend tests, and visual QA are recorded in the task report.

## 11. Non-goals

- Hand-authoring province polygons in the browser.
- Baking one province image per scenario or commandery layout.
- Treating every minor shrine, tomb, or local toponym as a province.
- Inventing county names to eliminate source gaps.
- Replacing all non-`han` maps or globally changing legacy city-level semantics.
- Drawing adjacency as a straight road network.
- Final balancing of supply capacity, march speed, attrition, or settlement growth rates.
