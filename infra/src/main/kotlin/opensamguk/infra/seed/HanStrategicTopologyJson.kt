package opensamguk.infra.seed

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import opensamguk.logic.world.*
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** Shared API/engine loader. Artifact mistakes are fatal; legacy Han never enters this domain. */
object HanStrategicTopologyJson {
    private const val MAP = "han-world-v3"
    private const val TILES = "data/map/han-tiles.json"
    private const val WATER = "data/map/han-water-topology-v1.json"
    private const val LEDGER = "data/curated/han/water-topology-adjudications-v1.json"
    private const val MANIFEST = "data/map/han-strategic-topology-manifest-v1.json"
    private const val WORLD = "infra/src/main/resources/map/han-world-v3.json"
    private const val WORLD_MANIFEST = "data/map/han-world-v3-manifest-v1.json"
    private const val SELECTION = "data/curated/han/route-node-selection-v1.json"
    private const val MIGRATION = "data/curated/han/route-node-migration-v1.json"
    private const val LEGACY = "infra/src/main/resources/map/han-780-v1.json"
    private val paths = listOf(TILES, WATER, LEDGER, MANIFEST, WORLD, WORLD_MANIFEST, SELECTION, MIGRATION, LEGACY)
    private val mapper = ObjectMapper()
        .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
    private val dryNames = setOf("PLAIN", "MOUNTAIN", "DESERT", "PLATEAU", "BASIN", "HILL")
    private val defaultProjection by lazy {
        load(MAP) { path ->
            if (path.startsWith("infra/src/main/resources/")) {
                val resource = path.removePrefix("infra/src/main/resources/")
                requireNotNull(javaClass.classLoader.getResourceAsStream(resource)) { "Missing strategic resource $resource" }
                    .use { it.readBytes() }
            } else {
                Files.readAllBytes(Path.of(path))
            }
        }
    }

    fun loadDefault(): HanStrategicRouteProjection = defaultProjection

    fun loadFromDirectory(root: Path, mapName: String): HanStrategicRouteProjection =
        load(mapName) { Files.readAllBytes(root.resolve(it)) }

    /** The reader also permits classpath packaging without introducing Spring into the route contract. */
    fun load(mapName: String, readArtifact: (String) -> ByteArray): HanStrategicRouteProjection {
        require(mapName == MAP) { "Strategic topology is only supported for $MAP; got $mapName" }
        try {
            val bytes = paths.associateWith { readArtifact(it).copyOf() }
            val hashes = bytes.mapValues { sha(it.value) }
            val docs = bytes.mapValues { (path, data) ->
                mapper.readTree(data).also { require(it != null && it.isObject) { "$path must be a JSON object" } }
            }
            val manifest = docs.getValue(MANIFEST)
            manifest.fieldsExactly("schemaVersion", "manifestId", "topologyRevision", "files", "counts", "zoneKinds", "edgeModes")
            require(manifest.integer("schemaVersion") == 1 && manifest.text("manifestId") == "han-strategic-topology-manifest-v1")
            val filePins = manifest.objectField("files")
            filePins.fieldsExactly("baseHanTiles", "waterTopology", "adjudications")
            for ((key, path) in mapOf("baseHanTiles" to TILES, "waterTopology" to WATER, "adjudications" to LEDGER)) {
                val pin = filePins.objectField(key)
                pin.fieldsExactly("path", "sha256", "bytes")
                require(pin.text("path") == path && pin.text("sha256") == hashes.getValue(path) &&
                    pin.integer("bytes") == bytes.getValue(path).size) { "Strategic manifest byte pin mismatch: $path" }
            }
            validateWorldPins(docs.getValue(WORLD_MANIFEST), hashes)

            val tiles = docs.getValue(TILES)
            val meta = tiles.objectField("_meta")
            val rows = meta.integer("rows")
            val cols = meta.integer("cols")
            require(rows in 1..4096 && cols in 1..4096 && rows.toLong() * cols <= 4_000_000) { "Invalid terrain dimensions" }
            val provinces = tiles.array("provinceRecords")
            val landIds = provinces.map { it.text("id") }
            require(landIds.size == 1524 && landIds.toSet().size == landIds.size) { "Canonical land identity set changed" }
            val terrain = tiles.array("terrain").map { it.stringValue() }
            require(terrain.size == rows && terrain.all { it.length == cols }) { "Malformed terrain rows" }
            val legend = meta.objectField("terrainLegend")
            require(legend.fields().asSequence().all { (code, name) -> code.length == 1 && name.isTextual })
            require(legend.map(JsonNode::asText).toSet() == dryNames + setOf("SEA", "RIVER", "LAKE", "OUT_OF_SCOPE")) {
                "Unknown terrain policy"
            }
            require(terrain.all { row -> row.all { legend.has(it.toString()) } }) { "Unknown terrain code" }
            val dryCodes = legend.fields().asSequence().filter { it.value.asText() in dryNames }.map { it.key.single() }.toSet()
            val owner = decodeOwner(tiles.array("owner"), rows * cols, provinces.size)
            val water = docs.getValue(WATER)
            val ledger = docs.getValue(LEDGER)
            water.fieldsExactly("schemaVersion", "artifactId", "topologyRevision", "base", "landProvinceIds",
                "geometryComponents", "waterZones", "riverBarriers", "traversalEdges", "routeCandidates", "activationBlockers")
            ledger.fieldsExactly("schemaVersion", "ledgerId", "topologyRevision", "base", "sourceCatalog",
                "zoneAdjudications", "barrierAdjudications", "edgeAdjudications", "routeCandidates", "activationBlockers")
            require(water.integer("schemaVersion") == 1 && water.text("artifactId") == "han-water-topology-v1")
            require(ledger.integer("schemaVersion") == 1 && ledger.text("ledgerId") == "han-water-topology-adjudications-v1")
            val revision = manifest.text("topologyRevision")
            require(water.text("topologyRevision") == revision && ledger.text("topologyRevision") == revision) { "Topology revision drift" }
            val sortedLand = landIds.sorted()
            require(water.array("landProvinceIds").map { it.stringValue() } == sortedLand) { "Water overlay changes canonical land IDs" }
            // han_tiles_contract hashes compact JSON without the artifact writer's trailing newline.
            val landHash = sha(mapper.writeValueAsBytes(sortedLand))
            for (base in listOf(water.objectField("base"), ledger.objectField("base"))) {
                base.fieldsExactly("path", "sha256", "bytes", "rows", "cols", "projection", "terrainLegend", "landProvinceIdsSha256", "landProvinceIds")
                require(base.text("path") == TILES && base.text("sha256") == hashes.getValue(TILES) &&
                    base.integer("bytes") == bytes.getValue(TILES).size && base.integer("rows") == rows && base.integer("cols") == cols &&
                    base["projection"] == meta["projection"] && base["terrainLegend"] == legend &&
                    base.text("landProvinceIdsSha256") == landHash) { "Water topology base binding drift" }
                require(base.array("landProvinceIds").map { it.stringValue() } == sortedLand)
            }
            val sources = ledger.array("sourceCatalog")
            val sourceIds = uniqueBy(sources, "sourceId").keys
            require(sources.all { it.text("reviewState") == "UPHELD" }) { "Source catalog evidence must be UPHELD" }
            val geometries = validateGeometries(water.array("geometryComponents"), terrain, legend)
            val zones = water.array("waterZones").map { row -> parseZone(row, sourceIds, geometries) }
            uniqueBy(water.array("waterZones"), "id")
            val zoneReviews = uniqueBy(ledger.array("zoneAdjudications"), "stableKey")
            require(zoneReviews.size == zones.size && geometries.size == zones.size) { "Zone review/geometry inventory drift" }
            zones.forEach { zone ->
                val row = requireNotNull(zoneReviews[zone.id.removePrefix("water-zone:")]) { "Unreviewed zone ${zone.id}" }
                row.fieldsExactly("stableKey", "kind", "geometrySelector", "sourceRefs", "confidence", "flowDirection",
                    "depthBand", "seasonalAvailability", "status", "connectionStatus")
                require(row.text("status") == "APPROVED" && zone.id == "water-zone:${row.text("stableKey")}" &&
                    zone.geometryRef == "geometry:${row.text("stableKey")}") { "Unapproved zone identity" }
                val materialized = water.array("waterZones").single { it.text("id") == zone.id }
                for (field in listOf("kind", "confidence", "flowDirection", "depthBand", "seasonalAvailability", "connectionStatus")) {
                    require(row[field] == materialized[field]) { "Zone $field review drift" }
                }
                require(row.sourceRefs(sourceIds).sorted() == materialized.sourceRefs(sourceIds).sorted()) { "Zone source review drift" }
                val selector = row.objectField("geometrySelector")
                if (zone.kind == WaterZoneKind.COASTAL_SEA) require(selector.text("kind") == "CELL_RANGES") {
                    "Coastal geometry requires reviewed cell ranges, never a sea flood fill"
                }
                validateGeometrySelector(selector, geometries.getValue(zone.geometryRef), terrain)
            }
            validateZoneSourceBoundaries(zones, geometries, sources, provinces, owner, cols)
            val barriers = water.array("riverBarriers").map { row ->
                row.fieldsExactly("id", "firstLandProvinceId", "secondLandProvinceId", "sourceRefs", "confidence")
                RiverBarrier(row.text("id"), row.text("firstLandProvinceId"), row.text("secondLandProvinceId"),
                    row.sourceRefs(sourceIds), row.reviewedConfidence())
            }
            val barrierReviews = uniqueBy(ledger.array("barrierAdjudications"), "stableKey")
            require(barrierReviews.size == barriers.size)
            barriers.forEach { barrier ->
                val row = requireNotNull(barrierReviews[barrier.id.removePrefix("river-barrier:")]) { "Unreviewed barrier ${barrier.id}" }
                row.fieldsExactly("stableKey", "firstLandProvinceId", "secondLandProvinceId", "sourceRefs", "confidence", "status")
                require(row.text("status") == "APPROVED" && barrier.id == "river-barrier:${row.text("stableKey")}" &&
                    row.text("firstLandProvinceId") == barrier.firstLandProvinceId && row.text("secondLandProvinceId") == barrier.secondLandProvinceId &&
                    row.sourceRefs(sourceIds).sorted() == barrier.sourceRefs.sorted() && row.reviewedConfidence() == barrier.confidence)
                require(touchesOwners(barrier.firstLandProvinceId, barrier.secondLandProvinceId, landIds, owner, cols)) { "Nonadjacent river barrier" }
            }
            val typedRows = water.array("traversalEdges")
            val typedEdges = typedRows.map { parseEdge(it, sourceIds) }
            validateEdgeReviews(typedRows, ledger.array("edgeAdjudications"), sourceIds)
            validateEvidenceCoverage(barriers, typedEdges, sources)
            validateTypedEdges(typedRows, typedEdges, barriers, zones, water.array("waterZones"), geometries, landIds, owner, cols)
            val blockers = validateBlockedCandidates(water, ledger, landIds.toSet(), zones.map { it.id }.toSet(), sourceIds)
            if ("NO_REVIEWED_RIVER_CROSSING_EVIDENCE" in blockers) {
                require(barriers.isEmpty() && typedEdges.none { it.mode in setOf(TraversalMode.FORD, TraversalMode.BRIDGE, TraversalMode.FERRY) }) {
                    "Blocked river crossings cannot become executable"
                }
            }
            validateCounts(manifest, water)
            val dryEdges = projectHanDryLandEdges(landIds, owner, terrain, dryCodes, barriers, hashes.getValue(TILES))
            val explicitLand = typedEdges.filter { it.mode == TraversalMode.LAND }
            val dryKeys = dryEdges.map { setOf(it.from, it.to) }.toSet()
            require(explicitLand.all { setOf(it.from, it.to) in dryKeys }) {
                "Reviewed LAND edges must still share a dry canonical boundary"
            }
            val explicitKeys = explicitLand.map { setOf(it.from, it.to) }.toSet()
            val topology = StrategicTopologySnapshot(revision, landIds.toSet(), zones,
                dryEdges.filter { setOf(it.from, it.to) !in explicitKeys } + typedEdges, barriers,
                hashes + ("dryLandProjectionPolicy" to sha("dry-v1:4-neighbour:both-dry:PLAIN,MOUNTAIN,DESERT,PLATEAU,BASIN,HILL:cost=1:capacity=2147483647:supply=true".toByteArray())))
            val presentation = StrategicMapPresentation(cols, rows, hashes.getValue(TILES),
                geometries.toSortedMap().map { (id, geometry) ->
                    StrategicWaterGeometry(id, geometry.row.integer("terrainCode"), geometry.cells.size,
                        geometry.row.array("cellRuns").map { run ->
                            StrategicCellRun(run.integer("row"), run.integer("startCol"), run.integer("endCol"))
                        })
                },
                water.array("waterZones").sortedBy { it.text("id") }.associate { it.text("id") to it.text("connectionStatus") },
            )
            return HanStrategicRouteProjection(topology, routeBindings(docs, hashes, provinces, landIds), blockers, presentation)
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (e: Exception) {
            throw IllegalArgumentException("Unable to load validated Han V3 strategic topology", e)
        }
    }

    private fun validateWorldPins(manifest: JsonNode, hashes: Map<String, String>) {
        require(manifest.integer("schemaVersion") == 1 && manifest.text("manifestId") == "han-world-v3-manifest-v1" &&
            manifest.text("worldVersion") == MAP) { "Wrong Han world manifest domain" }
        val inputs = manifest.objectField("inputs")
        for ((key, path) in mapOf("selectionSha256" to SELECTION, "migrationSha256" to MIGRATION,
            "hanTilesSha256" to TILES, "legacy780Sha256" to LEGACY)) {
            require(inputs.text(key) == hashes.getValue(path)) { "World manifest byte pin mismatch: $path" }
        }
        require(manifest.objectField("outputs").text("worldJsonSha256") == hashes.getValue(WORLD)) { "World JSON byte pin mismatch" }
    }

    private fun routeBindings(docs: Map<String, JsonNode>, hashes: Map<String, String>, provinces: List<JsonNode>, landIds: List<String>): List<HanStrategicRouteBinding> {
        val world = docs.getValue(WORLD)
        val selection = docs.getValue(SELECTION)
        val migration = docs.getValue(MIGRATION)
        require(world.objectField("_meta").text("map") == MAP && selection.text("worldVersion") == MAP &&
            selection.integer("schemaVersion") == 1 && selection.text("reviewState") == "APPROVED" &&
            migration.integer("schemaVersion") == 1 && migration.text("targetWorldVersion") == MAP && migration.text("mode") == "NEW_WORLD_ONLY" &&
            migration.text("sourceSelectionSha256") == hashes.getValue(SELECTION) &&
            migration.text("sourceSelectionId") == selection.text("selectionId")) { "Runtime identity domain/provenance drift" }
        val runtime = world.array("cities")
        val selected = selection.array("routeNodes")
        val manifested = docs.getValue(WORLD_MANIFEST).array("routeNodes")
        require(runtime.size == 781 && selected.size == 781 && manifested.size == 781) { "V3 route roster must contain 781 nodes" }
        data class Identity(val id: Int, val key: String, val physical: String)
        fun identities(rows: List<JsonNode>, idField: String): Set<Identity> {
            val result = rows.map { Identity(it.integer(idField), it.text("routeNodeKey"), it.text("physicalPlaceRef")) }
            require(result.map { it.id }.toSet() == (1..781).toSet() && result.map { it.key }.toSet().size == 781 &&
                result.map { it.physical }.toSet().size == 781) { "Duplicate or missing runtime/route/physical identity" }
            return result.toSet()
        }
        val expected = identities(selected, "numericCityId")
        require(selected.all { it.text("reviewState") == "APPROVED" } && identities(runtime, "id") == expected &&
            identities(manifested, "numericCityId") == expected) { "Runtime route triple differs from approved selection" }
        val physicalCities = docs.getValue(TILES).array("cities")
        uniqueBy(physicalCities, "id")
        val physicalIndex = physicalCities.mapIndexed { index, city -> city.text("id") to index }.toMap()
        val provinceByCity = mutableMapOf<Int, Int>()
        provinces.forEachIndexed { index, province ->
            val cityIndex = province["cityIndex"]
            if (cityIndex != null && !cityIndex.isNull) {
                require(cityIndex.isIntegralNumber && cityIndex.canConvertToInt() && cityIndex.intValue() in physicalCities.indices)
                require(provinceByCity.put(cityIndex.intValue(), index) == null) { "Physical place has ambiguous canonical province" }
            }
        }
        return runtime.map { city ->
            val physical = city.text("physicalPlaceRef")
            val placeId = when {
                physical.startsWith("chgis:v6:cnty:") -> physical.removePrefix("chgis:v6:cnty:")
                physical.startsWith("external:v1:") -> physical.removePrefix("external:v1:")
                else -> throw IllegalArgumentException("Unsupported physical reference domain")
            }
            val placeIndex = requireNotNull(physicalIndex[placeId]) { "Unknown physical place $physical" }
            val provinceIndex = provinceByCity[placeIndex]
            if (provinceIndex == null) {
                require(listOf("provinceId", "spatialProvinceIndex", "spatialProvinceId").all { !city.hasNonNull(it) }) {
                    "Unmapped physical place must not inherit an ordinal province"
                }
            } else {
                require(city.integer("provinceId") == provinceIndex && city.integer("spatialProvinceIndex") == provinceIndex &&
                    city.text("spatialProvinceId") == landIds[provinceIndex]) { "Runtime physical-to-province identity drift" }
            }
            HanStrategicRouteBinding(city.integer("id"), city.text("routeNodeKey"), physical, provinceIndex?.let(landIds::get))
        }
    }

    private data class Geometry(val row: JsonNode, val cells: Set<Int>, val cols: Int)

    private fun validateGeometries(rows: List<JsonNode>, terrain: List<String>, legend: JsonNode): Map<String, Geometry> {
        uniqueBy(rows, "id")
        val cols = terrain.first().length
        val occupied = hashSetOf<Int>()
        return rows.associate { row ->
            row.fieldsExactly("id", "kind", "terrainCode", "waterScope", "cellCount", "cellRuns")
            require(row.text("id").startsWith("geometry:") && row.text("kind") == "CELL_RANGES")
            val code = row.integer("terrainCode").toString()
            require(code.length == 1 && legend[code]?.asText() in setOf("SEA", "RIVER", "LAKE")) { "Water geometry is not water terrain" }
            val scope = when (legend[code].asText()) { "SEA" -> "REVIEWED_STRAIT"; "RIVER" -> "REVIEWED_RIVER_REACH"; else -> "INLAND_LAKE" }
            require(row.text("waterScope") == scope)
            val cells = hashSetOf<Int>()
            for (run in row.array("cellRuns")) {
                run.fieldsExactly("row", "startCol", "endCol")
                val r = run.integer("row"); val start = run.integer("startCol"); val end = run.integer("endCol")
                require(r in terrain.indices && start in 0 until cols && end in start until cols)
                for (c in start..end) {
                    require(terrain[r][c] == code.single() && cells.add(r * cols + c) && occupied.add(r * cols + c)) { "Overlapping or mismatched water geometry" }
                }
            }
            require(cells.size >= 2 && cells.size == row.integer("cellCount")) { "Invalid water component size" }
            val reached = hashSetOf(cells.first())
            val pending = ArrayDeque<Int>().apply { add(cells.first()) }
            while (pending.isNotEmpty()) for (next in neighbors(pending.removeFirst(), cols, terrain.size * cols)) {
                if (next in cells && reached.add(next)) pending.add(next)
            }
            require(reached.size == cells.size) { "Disconnected water geometry" }
            row.text("id") to Geometry(row, cells, cols)
        }
    }

    private fun validateGeometrySelector(selector: JsonNode, geometry: Geometry, terrain: List<String>) {
        require(selector.integer("terrainCode") == geometry.row.integer("terrainCode") &&
            selector.integer("expectedCellCount") == geometry.cells.size) { "Geometry selection binding drift" }
        when (selector.text("kind")) {
            "CELL_RANGES" -> {
                selector.fieldsExactly("kind", "terrainCode", "cellRuns", "expectedCellCount")
                // The builder sorts, deduplicates and coalesces reviewed ranges into canonical runs.
                val selectedCells = hashSetOf<Int>()
                for (run in selector.array("cellRuns")) {
                    run.fieldsExactly("row", "startCol", "endCol")
                    val row = run.integer("row"); val start = run.integer("startCol"); val end = run.integer("endCol")
                    require(row in terrain.indices && start in 0 until geometry.cols && end in start until geometry.cols)
                    for (col in start..end) selectedCells.add(row * geometry.cols + col)
                }
                require(selectedCells == geometry.cells) { "Geometry cell range review drift" }
            }
            "TERRAIN_COMPONENT" -> {
                selector.fieldsExactly("kind", "terrainCode", "seedRow", "seedCol", "expectedCellCount")
                val row = selector.integer("seedRow"); val col = selector.integer("seedCol")
                require(row in terrain.indices && col in 0 until geometry.cols && row * geometry.cols + col in geometry.cells)
                val code = selector.integer("terrainCode").toString().single()
                require(geometry.cells.all { cell -> neighbors(cell, geometry.cols, terrain.size * geometry.cols).all { next ->
                    terrain[next / geometry.cols][next % geometry.cols] != code || next in geometry.cells
                } }) { "Reviewed terrain component was truncated" }
            }
            else -> throw IllegalArgumentException("Unknown geometry selector")
        }
    }

    /** Match the builder's decoded owner adjacency, including a direct province's jurisdiction. */
    private fun validateZoneSourceBoundaries(
        zones: List<WaterZoneRecord>, geometries: Map<String, Geometry>, sources: List<JsonNode>,
        provinces: List<JsonNode>, owner: IntArray, cols: Int,
    ) {
        val sourceMembers = sources.associate { row -> row.text("sourceId") to row.array("memberIds").map { it.stringValue() }.toSet() }
        for (zone in zones) {
            if (zone.kind !in setOf(WaterZoneKind.COASTAL_SEA, WaterZoneKind.LAKE_BASIN)) continue
            val touchingOwners = geometries.getValue(zone.geometryRef).cells
                .flatMap { neighbors(it, cols, owner.size) }.map { owner[it] }.filter { it >= 0 }.toSet()
            if (zone.kind == WaterZoneKind.COASTAL_SEA) require(touchingOwners.isNotEmpty()) {
                "Coastal water zone must touch decoded land ownership"
            }
            val touchingIds = touchingOwners.flatMap { index ->
                val province = provinces[index]
                listOfNotNull(province.text("id"), province["jurisdictionId"]?.takeUnless { it.isNull }?.stringValue())
            }.toSet()
            require(zone.sourceRefs.all { ref -> sourceMembers.getValue(ref).any { it in touchingIds } }) {
                "Water geometry must touch a member boundary of every cited source: ${zone.id}"
            }
        }
    }

    private fun parseZone(row: JsonNode, sources: Set<String>, geometries: Map<String, Geometry>): WaterZoneRecord {
        row.fieldsExactly("id", "kind", "geometryRef", "sourceRefs", "confidence", "flowDirection", "depthBand", "seasonalAvailability", "connectionStatus")
        val geometry = requireNotNull(geometries[row.text("geometryRef")]) { "Dangling geometry reference" }
        val kind = enumValueOf<WaterZoneKind>(row.text("kind"))
        val expectedScope = when (kind) { WaterZoneKind.RIVER_REACH -> "REVIEWED_RIVER_REACH"; WaterZoneKind.LAKE_BASIN -> "INLAND_LAKE"; WaterZoneKind.COASTAL_SEA -> "REVIEWED_STRAIT" }
        require(geometry.row.text("waterScope") == expectedScope)
        require(row.text("connectionStatus") in setOf("CONNECTED", "ISOLATED_NO_REVIEWED_CONNECTION"))
        return WaterZoneRecord(row.text("id"), kind, row.text("geometryRef"), row.sourceRefs(sources), row.reviewedConfidence(),
            row.nullableText("flowDirection"), row.nullableText("depthBand")?.let { enumValueOf<DepthBand>(it) },
            enumValueOf(row.text("seasonalAvailability")))
    }

    private fun parseEdge(row: JsonNode, sources: Set<String>): TraversalEdge {
        row.fieldsExactly("id", "from", "to", "mode", "directed", "movementCost", "capacity", "riskBand", "seasonalAvailability",
            "supplyAllowed", "sourceRefs", "confidence", "barrierId", "directionPairKey")
        return TraversalEdge(row.text("id"), node(row.objectField("from")), node(row.objectField("to")), enumValueOf(row.text("mode")),
            row.boolean("directed"), row.integer("movementCost"), row.integer("capacity"), enumValueOf(row.text("riskBand")),
            enumValueOf(row.text("seasonalAvailability")), row.boolean("supplyAllowed"), row.sourceRefs(sources), enumValueOf(row.text("confidence")))
    }

    private fun validateEdgeReviews(rows: List<JsonNode>, reviews: List<JsonNode>, sources: Set<String>) {
        uniqueBy(rows, "id")
        val byKey = uniqueBy(reviews, "stableKey")
        require(byKey.size == rows.size)
        for (edge in rows) {
            val row = requireNotNull(byKey[edge.text("id").removePrefix("traversal-edge:")]) { "Unreviewed traversal edge" }
            row.fieldsExactly("stableKey", "from", "to", "mode", "directed", "movementCost", "capacity", "riskBand", "seasonalAvailability",
                "supplyAllowed", "sourceRefs", "confidence", "status", "barrierStableKey", "directionPairKey")
            require(row.text("status") == "APPROVED" && edge.text("id") == "traversal-edge:${row.text("stableKey")}")
            require(row.sourceRefs(sources).sorted() == edge.sourceRefs(sources).sorted()) { "Traversal source review drift" }
            for (key in listOf("mode", "directed", "movementCost", "capacity", "riskBand", "seasonalAvailability", "supplyAllowed", "confidence", "directionPairKey")) {
                require(row[key] == edge[key]) { "Traversal edge $key review drift" }
            }
            for (key in listOf("from", "to")) {
                val expected = node(row.objectField(key), materialized = false)
                require(expected == node(edge.objectField(key))) { "Traversal endpoint review drift" }
            }
            require(edge.nullableText("barrierId") == row.nullableText("barrierStableKey")?.let { "river-barrier:$it" }) { "Barrier review drift" }
        }
    }

    private fun validateEvidenceCoverage(barriers: List<RiverBarrier>, edges: List<TraversalEdge>, sources: List<JsonNode>) {
        val catalog = sources.associateBy { it.text("sourceId") }
        val pathsByType = mapOf(
            "territory-disconnection" to "data/curated/han/territory-disconnection-adjudications-v1.json",
            "river-barrier" to "data/curated/han/river-barrier-adjudications-v1.json",
            "river-crossing" to "data/curated/han/river-crossing-adjudications-v1.json",
            "port-or-landing" to "data/curated/han/port-or-landing-adjudications-v1.json",
        )
        catalog.forEach { (id, source) ->
            source.fieldsExactly("sourceId", "path", "selector", "claim", "reviewState", "unitId", "memberIds")
            require(pathsByType[id.substringBefore(':')] == source.text("path")) { "Evidence source type/path mismatch" }
            val selector = source.objectField("selector")
            selector.fieldsExactly("componentKey")
            selector.text("componentKey"); source.text("claim"); source.text("unitId")
            val members = source.array("memberIds").map { it.stringValue() }
            require(members.isNotEmpty() && members.toSet().size == members.size) { "Invalid evidence membership" }
        }
        fun requireCoverage(refs: List<String>, land: Set<String>, type: String?) {
            if (type != null) require(refs.all { it.startsWith("$type:") }) { "Traversal requires exact $type evidence" }
            val coverage = refs.flatMap { ref ->
                val source = catalog.getValue(ref)
                source.array("memberIds").map { it.stringValue() } + source.text("unitId")
            }.toSet()
            require(coverage.containsAll(land)) { "Evidence does not cover traversal land endpoints" }
        }
        barriers.forEach { requireCoverage(it.sourceRefs, setOf(it.firstLandProvinceId, it.secondLandProvinceId), "river-barrier") }
        edges.forEach { edge ->
            val type = when (edge.mode) {
                TraversalMode.FORD, TraversalMode.BRIDGE, TraversalMode.FERRY -> "river-crossing"
                TraversalMode.EMBARK, TraversalMode.DISEMBARK -> "port-or-landing"
                else -> null
            }
            requireCoverage(edge.sourceRefs, listOf(edge.from, edge.to).filterIsInstance<StrategicNodeRef.LandProvince>().map { it.id }.toSet(), type)
        }
    }

    private fun validateTypedEdges(rows: List<JsonNode>, edges: List<TraversalEdge>, barriers: List<RiverBarrier>, zones: List<WaterZoneRecord>,
        zoneRows: List<JsonNode>, geometry: Map<String, Geometry>, landIds: List<String>, owner: IntArray, cols: Int) {
        val barrierById = barriers.associateBy { it.id }
        for ((index, edge) in edges.withIndex()) {
            val row = rows[index]
            if (edge.mode in setOf(TraversalMode.FORD, TraversalMode.BRIDGE, TraversalMode.FERRY)) {
                val barrier = requireNotNull(barrierById[row.nullableText("barrierId")]) { "Crossing without a reviewed barrier" }
                require(setOf(edge.from, edge.to) == setOf(StrategicNodeRef.LandProvince(barrier.firstLandProvinceId), StrategicNodeRef.LandProvince(barrier.secondLandProvinceId)))
            } else require(row.nullableText("barrierId") == null) { "Only crossings can claim a barrier" }
            if (edge.mode in setOf(TraversalMode.LAND, TraversalMode.FORD, TraversalMode.BRIDGE, TraversalMode.FERRY)) {
                require(touchesOwners((edge.from as? StrategicNodeRef.LandProvince)?.id ?: "", (edge.to as? StrategicNodeRef.LandProvince)?.id ?: "", landIds, owner, cols))
            }
            if (edge.mode in setOf(TraversalMode.EMBARK, TraversalMode.DISEMBARK)) {
                val land = listOf(edge.from, edge.to).filterIsInstance<StrategicNodeRef.LandProvince>().single()
                val water = listOf(edge.from, edge.to).filterIsInstance<StrategicNodeRef.WaterZone>().single()
                val zone = zones.single { it.id == water.id }
                val landIndex = landIds.indexOf(land.id)
                require(landIndex >= 0 && geometry.getValue(zone.geometryRef).cells.any { cell ->
                    neighbors(cell, cols, owner.size).any { owner[it] == landIndex }
                }) { "Water access does not touch its land endpoint" }
            }
            val river = edge.mode in setOf(TraversalMode.RIVER_UP, TraversalMode.RIVER_DOWN)
            require((row.nullableText("directionPairKey") != null) == river) { "Missing or spurious flow pair" }
        }
        for ((_, pair) in rows.filter { it.nullableText("directionPairKey") != null }.groupBy { it.text("directionPairKey") }) {
            require(pair.size == 2 && pair.map { it.text("mode") }.toSet() == setOf("RIVER_UP", "RIVER_DOWN") &&
                pair[0]["from"] == pair[1]["to"] && pair[0]["to"] == pair[1]["from"]) { "Malformed upstream/downstream pair" }
        }
        for (zone in zoneRows) {
            val id = zone.text("id")
            val incident = edges.any { it.from == StrategicNodeRef.WaterZone(id) || it.to == StrategicNodeRef.WaterZone(id) }
            require((zone.text("connectionStatus") == "CONNECTED") == incident) { "Water zone connection status drift" }
        }
    }

    private fun validateBlockedCandidates(water: JsonNode, ledger: JsonNode, landIds: Set<String>, zoneIds: Set<String>, sources: Set<String>): Set<String> {
        val blockers = water.array("activationBlockers")
        val blockerReviews = uniqueBy(ledger.array("activationBlockers"), "code")
        require(uniqueBy(blockers, "code").keys == blockerReviews.keys)
        blockers.forEach { row ->
            row.fieldsExactly("code", "feature", "status", "requiredEvidence")
            require(row.text("status") == "BLOCKED" && row.text("feature").isNotBlank() && row.array("requiredEvidence").isNotEmpty())
            val review = blockerReviews.getValue(row.text("code"))
            review.fieldsExactly("code", "feature", "status", "requiredEvidence")
            for (field in listOf("code", "feature", "status")) require(row[field] == review[field]) { "Activation blocker review drift" }
            require(row.array("requiredEvidence").map { it.stringValue() }.sorted() ==
                review.array("requiredEvidence").map { it.stringValue() }.sorted()) { "Activation checklist review drift" }
        }
        val candidates = water.array("routeCandidates")
        uniqueBy(candidates, "id")
        val reviews = uniqueBy(ledger.array("routeCandidates"), "stableKey")
        require(candidates.size == reviews.size)
        for (row in candidates) {
            row.fieldsExactly("id", "fromLandProvinceId", "toLandProvinceId", "viaWaterZoneId", "mode", "status", "blockerCode", "sourceRefs")
            val review = requireNotNull(reviews[row.text("id").removePrefix("route-candidate:")])
            review.fieldsExactly("stableKey", "fromLandProvinceId", "toLandProvinceId", "viaZoneStableKey", "mode", "status", "blockerCode", "sourceRefs")
            require(row.text("id") == "route-candidate:${review.text("stableKey")}" && row.text("status") == "BLOCKED_PENDING_REVIEW" &&
                row.text("fromLandProvinceId") in landIds && row.text("toLandProvinceId") in landIds && row.text("viaWaterZoneId") in zoneIds &&
                row.text("viaWaterZoneId") == "water-zone:${review.text("viaZoneStableKey")}")
            enumValueOf<TraversalMode>(row.text("mode"))
            row.text("blockerCode")
            require(row.sourceRefs(sources).sorted() == review.sourceRefs(sources).sorted()) { "Route candidate source review drift" }
            for (field in listOf("fromLandProvinceId", "toLandProvinceId", "mode", "status", "blockerCode")) require(row[field] == review[field])
        }
        return blockers.map { it.text("code") }.toSet() + candidates.map { it.text("blockerCode") }
    }

    private fun validateCounts(manifest: JsonNode, water: JsonNode) {
        val counts = manifest.objectField("counts")
        counts.fieldsExactly("activationBlockers", "evidenceSources", "geometryComponents", "landProvinceIds", "riverBarriers", "routeCandidates", "traversalEdges", "waterZones")
        for (field in listOf("activationBlockers", "geometryComponents", "landProvinceIds", "riverBarriers", "routeCandidates", "traversalEdges", "waterZones")) {
            require(counts.integer(field) == water.array(field).size) { "Manifest $field count drift" }
        }
        val usedSources = listOf("waterZones", "riverBarriers", "traversalEdges", "routeCandidates")
            .flatMap { collection -> water.array(collection).flatMap { row -> row.array("sourceRefs").map { it.stringValue() } } }.toSet()
        require(counts.integer("evidenceSources") == usedSources.size) { "Manifest referenced evidence count drift" }
        for ((field, rows, kind) in listOf(Triple("zoneKinds", "waterZones", "kind"), Triple("edgeModes", "traversalEdges", "mode"))) {
            val expected = water.array(rows).groupingBy { it.text(kind) }.eachCount()
            val actual = manifest.objectField(field).fields().asSequence().associate { it.key to it.value.strictInt() }
            require(actual == expected) { "Manifest $field histogram drift" }
        }
    }

    private fun decodeOwner(runs: List<JsonNode>, size: Int, provinceCount: Int): IntArray {
        val result = IntArray(size)
        var offset = 0
        for (run in runs) {
            require(run.isArray && run.size() == 2)
            val id = run[0].strictInt(); val count = run[1].strictInt()
            require(id == -1 || id in 0 until provinceCount)
            require(count > 0 && count.toLong() + offset <= size) { "Invalid owner RLE" }
            result.fill(id, offset, offset + count)
            offset += count
        }
        require(offset == size) { "Incomplete owner RLE" }
        return result
    }

    private fun touchesOwners(first: String, second: String, landIds: List<String>, owner: IntArray, cols: Int): Boolean {
        val a = landIds.indexOf(first); val b = landIds.indexOf(second)
        if (a < 0 || b < 0 || a == b) return false
        return owner.indices.any { owner[it] == a && neighbors(it, cols, owner.size).any { next -> owner[next] == b } }
    }

    private fun neighbors(index: Int, cols: Int, size: Int): List<Int> = buildList(4) {
        if (index % cols > 0) add(index - 1)
        if (index % cols + 1 < cols) add(index + 1)
        if (index >= cols) add(index - cols)
        if (index + cols < size) add(index + cols)
    }

    private fun node(row: JsonNode, materialized: Boolean = true): StrategicNodeRef {
        row.fieldsExactly("kind", "id")
        return when (row.text("kind")) {
            "LAND_PROVINCE" -> StrategicNodeRef.LandProvince(row.text("id"))
            "WATER_ZONE" -> StrategicNodeRef.WaterZone(if (materialized) row.text("id") else "water-zone:${row.text("id")}")
            else -> throw IllegalArgumentException("Unknown strategic node kind")
        }
    }

    private fun uniqueBy(rows: List<JsonNode>, field: String): Map<String, JsonNode> = rows.associateBy { it.text(field) }
        .also { require(it.size == rows.size) { "Duplicate $field" } }
    private fun JsonNode.fieldsExactly(vararg expected: String) {
        require(isObject && fieldNames().asSequence().toSet() == expected.toSet()) { "Malformed fields: expected ${expected.toList()}, got ${fieldNames().asSequence().toList()}" }
    }
    private fun JsonNode.objectField(key: String): JsonNode = requireNotNull(get(key)) { "Missing $key" }.also { require(it.isObject) { "$key must be an object" } }
    private fun JsonNode.array(key: String): List<JsonNode> = requireNotNull(get(key)) { "Missing $key" }.also { require(it.isArray) { "$key must be an array" } }.toList()
    private fun JsonNode.text(key: String): String = requireNotNull(get(key)) { "Missing $key" }.stringValue()
    private fun JsonNode.stringValue(): String { require(isTextual && textValue().isNotBlank()) { "Expected nonblank string" }; return textValue() }
    private fun JsonNode.nullableText(key: String): String? = requireNotNull(get(key)) { "Missing $key" }.let { if (it.isNull) null else it.stringValue() }
    private fun JsonNode.integer(key: String): Int = requireNotNull(get(key)) { "Missing $key" }.strictInt()
    private fun JsonNode.strictInt(): Int { require(isIntegralNumber && canConvertToInt()) { "Expected bounded integer" }; return intValue() }
    private fun JsonNode.boolean(key: String): Boolean = requireNotNull(get(key)).also { require(it.isBoolean) }.booleanValue()
    private fun JsonNode.sourceRefs(known: Set<String>): List<String> = array("sourceRefs").map { it.stringValue() }.also {
        require(it.isNotEmpty() && it.toSet().size == it.size && known.containsAll(it)) { "Unknown or duplicate evidence reference" }
    }
    private fun JsonNode.reviewedConfidence(): EvidenceConfidence = enumValueOf<EvidenceConfidence>(text("confidence")).also { require(it != EvidenceConfidence.INFERRED) { "Unreviewed topology evidence" } }
    private fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
