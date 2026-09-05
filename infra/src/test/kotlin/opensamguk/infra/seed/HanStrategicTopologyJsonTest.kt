package opensamguk.infra.seed

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import opensamguk.logic.world.PathDenialCode
import opensamguk.logic.world.StrategicEdgeStateSnapshot
import opensamguk.logic.world.StrategicPathResult
import opensamguk.logic.world.TraversalMode
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class HanStrategicTopologyJsonTest {
    private val mapper = ObjectMapper()
    private val root = Path.of("..").toAbsolutePath().normalize()

    @Test
    fun `validated presentation exposes exact isolated water cells without inferred connections`() {
        val loaded = HanStrategicTopologyJson.loadFromDirectory(root, "han-world-v3")
        val json = mapper.valueToTree<JsonNode>(loaded)
        val presentation = json.path("presentation")
        assertEquals(768, presentation.path("cols").asInt())
        assertEquals(669, presentation.path("rows").asInt())
        assertEquals("8291649cf6636c289d60bb980ce7f50080965a9ca16ca01630604524be307520",
            presentation.path("baseTilesSha256").asText())
        assertEquals(listOf(47, 83), presentation.path("geometries").map { it.path("cellCount").asInt() })
        assertEquals(listOf("ISOLATED_NO_REVIEWED_CONNECTION", "ISOLATED_NO_REVIEWED_CONNECTION"),
            presentation.path("zoneConnections").fields().asSequence().map { it.value.asText() }.toList())
        val coast = presentation.path("geometries")[0].path("cellRuns")
        assertEquals(1, coast.size())
        assertEquals(543, coast[0].path("row").asInt())
        assertEquals(305, coast[0].path("startCol").asInt())
        assertEquals(351, coast[0].path("endCol").asInt())
    }

    @Test
    fun `committed topology keeps stable land identities and resolves Lu to Licheng over dry ground`() {
        val loaded = HanStrategicTopologyJson.loadFromDirectory(root, "han-world-v3")
        val topology = loaded.topology

        assertEquals(1524, topology.landProvinceIds.size)
        assertTrue(topology.landProvinceIds.any { it.startsWith("DIRECT-PARENT-") })
        assertEquals(2, topology.waterZones.size)
        assertEquals(0, topology.riverBarriers.size)
        assertTrue(topology.traversalEdges.all { it.mode == TraversalMode.LAND })
        assertEquals(781, loaded.bindingsByCityId.size)
        assertEquals(setOf("NO_REVIEWED_RIVER_CROSSING_EVIDENCE", "NO_REVIEWED_PORT_OR_LANDING_EVIDENCE"), loaded.activationBlockerCodes)
        assertEquals("45098", loaded.bindingsByCityId.getValue(273).landProvinceId)
        assertEquals("45022", loaded.bindingsByCityId.getValue(781).landProvinceId)
        assertEquals("chgis:v6:cnty:45022", loaded.bindingsByCityId.getValue(781).physicalPlaceRef)
        assertEquals("f1aae98e-ead0-49f7-b4da-e427277a66ef", loaded.bindingsByCityId.getValue(781).routeNodeKey)
        val path = assertIs<StrategicPathResult.Resolved>(loaded.resolve(273, 781, 1, state(topology))).path
        assertEquals(listOf("land:45098", "land:45022"), path.nodeKeys)
        assertEquals(listOf(TraversalMode.LAND), path.modes)
        assertEquals(1, path.edgeIds.size)
        assertEquals(1L, path.totalCost)
        assertEquals(
            path.pathHash,
            assertIs<StrategicPathResult.Resolved>(loaded.resolve(273, 781, 1, state(topology))).path.pathHash,
        )
        assertFailsWith<UnsupportedOperationException> { (loaded.bindingsByCityId as MutableMap).clear() }
        assertFailsWith<UnsupportedOperationException> { (topology.landProvinceIds as MutableSet).clear() }
        assertFailsWith<UnsupportedOperationException> { (loaded.activationBlockerCodes as MutableSet).clear() }
        assertEquals(PathDenialCode.UNKNOWN_NODE,
            assertIs<StrategicPathResult.Denied>(loaded.resolve(704, 781, 1, state(topology))).code)
    }

    @Test
    fun `legacy domain and stale route snapshots fail closed`() {
        for (mapName in listOf("han", "han-world-v2", "han-780-v1")) {
            assertFailsWith<IllegalArgumentException> {
                HanStrategicTopologyJson.loadFromDirectory(root, mapName)
            }
        }
        val loaded = HanStrategicTopologyJson.loadFromDirectory(root, "han-world-v3")
        assertEquals(
            PathDenialCode.TOPOLOGY_REVISION_STALE,
            assertIs<StrategicPathResult.Denied>(loaded.resolve(273, 781, 1,
                StrategicEdgeStateSnapshot("stale", loaded.topology.contentHash, emptyMap()),
            )).code,
        )
        assertEquals(
            PathDenialCode.UNKNOWN_NODE,
            assertIs<StrategicPathResult.Denied>(loaded.resolve(9999, 781, 1, state(loaded.topology))).code,
        )
    }

    @Test
    fun `every required artifact is byte pinned before parsing`() {
        for (path in listOf(TILES, WATER, ADJUDICATIONS, WORLD, SELECTION, MIGRATION)) {
            val files = files()
            files[path] = files.getValue(path) + byteArrayOf(32)
            assertFailsWith<IllegalArgumentException>(path) { load(files) }
        }
    }

    @Test
    fun `malformed duplicate and unknown fields cannot masquerade as valid artifacts`() {
        val invalidCases: List<(MutableMap<String, ByteArray>) -> Unit> = listOf(
            { files -> update(files, WATER) { it.put("ownerNationId", 7) } },
            { files -> update(files, WATER) { it.put("schemaVersion", 2) } },
            { files -> update(files, WATER) { it.withArray("waterZones").add(it.withArray("waterZones")[0].deepCopy<JsonNode>()) } },
            { files -> update(files, WATER) { (it["waterZones"][0] as ObjectNode).put("geometryRef", "missing") } },
            { files -> update(files, WATER) { it.withArray("landProvinceIds").remove(0) } },
            { files -> update(files, WATER) { (it["base"] as ObjectNode).put("sha256", "stale") } },
            { files -> update(files, WORLD) { (it["_meta"] as ObjectNode).put("map", "han-world-v2") } },
            { files -> update(files, WORLD) { (it["cities"][780] as ObjectNode).put("routeNodeKey", it["cities"][0]["routeNodeKey"].asText()) } },
            { files -> update(files, WORLD) { (it["cities"][780] as ObjectNode).put("physicalPlaceRef", "chgis:v6:cnty:45098") } },
            { files -> update(files, WORLD) { (it["cities"][780] as ObjectNode).put("provinceId", 359) } },
        )
        for ((index, mutate) in invalidCases.withIndex()) {
            val files = files()
            mutate(files)
            repinManifests(files)
            assertFailsWith<IllegalArgumentException>("case $index") { load(files) }
        }
    }

    @Test
    fun `strict reader rejects duplicate JSON keys and overlong owner raster even when repinned`() {
        val duplicate = files()
        duplicate[WATER] = String(duplicate.getValue(WATER)).replaceFirst("{", "{\"schemaVersion\":1,").toByteArray()
        repinManifests(duplicate)
        assertFailsWith<IllegalArgumentException> { load(duplicate) }
        val owner = files()
        update(owner, TILES) { (it["owner"][0] as com.fasterxml.jackson.databind.node.ArrayNode).set(1, mapper.nodeFactory.numberNode(600000)) }
        for (path in listOf(WATER, ADJUDICATIONS)) update(owner, path) {
            (it["base"] as ObjectNode).put("sha256", sha(owner.getValue(TILES))).put("bytes", owner.getValue(TILES).size)
        }
        repinManifests(owner)
        update(owner, WORLD_MANIFEST) { (it["inputs"] as ObjectNode).put("hanTilesSha256", sha(owner.getValue(TILES))) }
        assertFailsWith<IllegalArgumentException> { load(owner) }
    }

    @Test
    fun `reviewed crossing replaces its dry border without activating production candidates`() {
        val loaded = load(crossingFixture())
        val path = assertIs<StrategicPathResult.Resolved>(loaded.resolve(273, 781, 1, state(loaded.topology))).path
        assertEquals(listOf(TraversalMode.FORD), path.modes)
        assertEquals(listOf("traversal-edge:test-crossing"), path.edgeIds)
        assertEquals(500, path.capacity)
        assertTrue(loaded.topology.traversalEdges.none {
            it.mode == TraversalMode.LAND && setOf(it.from.canonicalKey, it.to.canonicalKey) == setOf("land:45098", "land:45022")
        })
        assertEquals(1, loaded.topology.riverBarriers.size)
        assertEquals(setOf("NO_REVIEWED_PORT_OR_LANDING_EVIDENCE"), loaded.activationBlockerCodes)
    }

    @Test
    fun `unreviewed crossing and unrelated evidence cannot become executable`() {
        for (mutation in listOf<(MutableMap<String, ByteArray>) -> Unit>(
            { files -> update(files, ADJUDICATIONS) { (it["edgeAdjudications"][0] as ObjectNode).put("status", "PENDING") } },
            { files -> update(files, WATER) { (it["traversalEdges"][0] as ObjectNode).put("barrierId", "missing") } },
            { files ->
                for ((file, rows) in listOf(WATER to "traversalEdges", ADJUDICATIONS to "edgeAdjudications")) update(files, file) {
                    (it[rows][0] as ObjectNode).putArray("sourceRefs").add("territory-disconnection:PARENT-0053@446:337")
                }
            },
        )) {
            val files = crossingFixture()
            mutation(files)
            repinManifests(files)
            assertFailsWith<IllegalArgumentException> { load(files) }
        }
    }

    @Test
    fun `unused approved catalog evidence does not change the manifest referenced evidence count`() {
        val files = files()
        update(files, ADJUDICATIONS) {
            val unused = (it["sourceCatalog"][0] as ObjectNode).deepCopy()
                .put("sourceId", "territory-disconnection:unused-test")
            it.withArray("sourceCatalog").add(unused)
        }
        repinManifests(files)
        val projection = load(files)
        assertEquals(listOf(TraversalMode.LAND),
            assertIs<StrategicPathResult.Resolved>(projection.resolve(273, 781, 1, state(projection.topology))).path.modes)
    }

    @Test
    fun `catalog evidence must be upheld and retain an exact component selector`() {
        for (mutation in listOf<(ObjectNode) -> Unit>(
            { it.put("reviewState", "APPROVED") },
            { it.putObject("selector").put("stableKey", "not-the-component-selector") },
        )) {
            val files = files()
            update(files, ADJUDICATIONS) { mutation(it["sourceCatalog"][0] as ObjectNode) }
            repinManifests(files)
            assertFailsWith<IllegalArgumentException> { load(files) }
        }
    }

    @Test
    fun `approved source reference ordering preserves the canonical route`() {
        val files = files()
        update(files, ADJUDICATIONS) {
            val candidate = it["routeCandidates"][0] as ObjectNode
            val reversed = candidate["sourceRefs"].toList().reversed()
            candidate.putArray("sourceRefs").addAll(reversed)
        }
        repinManifests(files)
        assertLuToLicheng(files)
    }

    @Test
    fun `evidence checklist ordering preserves the canonical route`() {
        val files = files()
        update(files, ADJUDICATIONS) {
            val blocker = it["activationBlockers"][0] as ObjectNode
            val reversed = blocker["requiredEvidence"].toList().reversed()
            blocker.putArray("requiredEvidence").addAll(reversed)
        }
        repinManifests(files)
        assertLuToLicheng(files)
    }

    @Test
    fun `reviewed range splitting and ordering preserves the canonical water geometry`() {
        val files = files()
        update(files, ADJUDICATIONS) {
            val zone = it["zoneAdjudications"].single { row -> row["kind"].asText() == "COASTAL_SEA" }
            val runs = (zone["geometrySelector"] as ObjectNode).putArray("cellRuns")
            runs.addObject().put("row", 543).put("startCol", 328).put("endCol", 351)
            runs.addObject().put("row", 543).put("startCol", 305).put("endCol", 327)
        }
        repinManifests(files)
        assertLuToLicheng(files)
    }

    private fun assertLuToLicheng(files: Map<String, ByteArray>) {
        val projection = load(files)
        val path = assertIs<StrategicPathResult.Resolved>(projection.resolve(273, 781, 1, state(projection.topology))).path
        assertEquals(listOf("land:45098", "land:45022"), path.nodeKeys)
        assertEquals(listOf(TraversalMode.LAND), path.modes)
    }

    @Test
    fun `water geometry must touch every cited source member even when source and review are repinned`() {
        for (replaceLakeSource in listOf(false, true)) {
            val files = files()
            for ((path, collection, keyField, key) in listOf(
                listOf(WATER, "waterZones", "id", "water-zone:lake-pengli-poyang"),
                listOf(ADJUDICATIONS, "zoneAdjudications", "stableKey", "lake-pengli-poyang"),
            )) update(files, path) {
                val zone = it[collection].single { row -> row[keyField].asText() == key } as ObjectNode
                val refs = if (replaceLakeSource) zone.putArray("sourceRefs") else zone.withArray("sourceRefs")
                refs.add("territory-disconnection:42524@367:500")
            }
            // Replacing removes the only Poyang reference; adding keeps all three used sources.
            update(files, WATER_MANIFEST) { (it["counts"] as ObjectNode).put("evidenceSources", if (replaceLakeSource) 2 else 3) }
            repinManifests(files)
            assertFailsWith<IllegalArgumentException> { load(files) }
        }
    }

    @Test
    fun `coastal geometry cannot use a terrain flood fill even for a bounded component touching land`() {
        val files = files()
        val geometry = mapper.readTree(files.getValue(WATER))["geometryComponents"]
            .single { it["id"].asText() == "geometry:lake-pengli-poyang" }
        update(files, TILES) { tiles ->
            val terrain = tiles.withArray("terrain")
            for (run in geometry["cellRuns"]) {
                val row = run["row"].asInt()
                val chars = terrain[row].asText().toCharArray()
                for (col in run["startCol"].asInt()..run["endCol"].asInt()) chars[col] = '0'
                terrain.set(row, mapper.nodeFactory.textNode(String(chars)))
            }
        }
        update(files, WATER) { water ->
            (water["geometryComponents"].single { it["id"].asText() == "geometry:lake-pengli-poyang" } as ObjectNode)
                .put("terrainCode", 0).put("waterScope", "REVIEWED_STRAIT")
            (water["waterZones"].single { it["id"].asText() == "water-zone:lake-pengli-poyang" } as ObjectNode)
                .put("kind", "COASTAL_SEA")
        }
        update(files, ADJUDICATIONS) { ledger ->
            val zone = ledger["zoneAdjudications"].single { it["stableKey"].asText() == "lake-pengli-poyang" } as ObjectNode
            zone.put("kind", "COASTAL_SEA")
            (zone["geometrySelector"] as ObjectNode).put("terrainCode", 0)
        }
        for (path in listOf(WATER, ADJUDICATIONS)) update(files, path) {
            (it["base"] as ObjectNode).put("sha256", sha(files.getValue(TILES))).put("bytes", files.getValue(TILES).size)
        }
        update(files, WORLD_MANIFEST) { (it["inputs"] as ObjectNode).put("hanTilesSha256", sha(files.getValue(TILES))) }
        update(files, WATER_MANIFEST) { it.putObject("zoneKinds").put("COASTAL_SEA", 2) }
        repinManifests(files)
        assertFailsWith<IllegalArgumentException> { load(files) }
    }

    /** Synthetic approved evidence exists only in this in-memory fixture, never in production files. */
    private fun crossingFixture(): MutableMap<String, ByteArray> {
        val files = files()
        val barrier = mapper.readTree("""{"id":"river-barrier:test-boundary","firstLandProvinceId":"45098","secondLandProvinceId":"45022","sourceRefs":["river-barrier:test"],"confidence":"REVIEWED"}""") as ObjectNode
        val edge = mapper.readTree("""{"id":"traversal-edge:test-crossing","from":{"kind":"LAND_PROVINCE","id":"45098"},"to":{"kind":"LAND_PROVINCE","id":"45022"},"mode":"FORD","directed":false,"movementCost":1,"capacity":500,"riskBand":"LOW","seasonalAvailability":"ALWAYS","supplyAllowed":false,"sourceRefs":["river-crossing:test"],"confidence":"REVIEWED","barrierId":"river-barrier:test-boundary","directionPairKey":null}""") as ObjectNode
        update(files, WATER) {
            it.withArray("riverBarriers").add(barrier)
            it.withArray("traversalEdges").add(edge)
            it.putArray("activationBlockers")
        }
        update(files, ADJUDICATIONS) {
            val reviewedBarrier = barrier.deepCopy().apply { remove("id"); put("stableKey", "test-boundary"); put("status", "APPROVED") }
            val reviewedEdge = edge.deepCopy().apply {
                remove("id"); remove("barrierId"); put("stableKey", "test-crossing"); put("status", "APPROVED"); put("barrierStableKey", "test-boundary")
            }
            it.withArray("barrierAdjudications").add(reviewedBarrier)
            it.withArray("edgeAdjudications").add(reviewedEdge)
            it.putArray("activationBlockers")
            for (kind in listOf("river-barrier", "river-crossing")) {
                it.withArray("sourceCatalog").addObject().put("sourceId", "$kind:test")
                    .put("path", "data/curated/han/$kind-adjudications-v1.json").put("claim", "Synthetic reviewed test crossing")
                    .put("reviewState", "UPHELD").put("unitId", "45098").apply {
                        putObject("selector").put("componentKey", "test")
                        putArray("memberIds").add("45098").add("45022")
                    }
            }
        }
        update(files, WATER_MANIFEST) {
            (it["counts"] as ObjectNode).put("activationBlockers", 0).put("evidenceSources", 5).put("riverBarriers", 1).put("traversalEdges", 1)
            it.putObject("edgeModes").put("FORD", 1)
        }
        repinManifests(files)
        return files
    }

    private fun state(topology: opensamguk.logic.world.StrategicTopologySnapshot) =
        StrategicEdgeStateSnapshot(topology.topologyRevision, topology.contentHash, emptyMap())

    private fun files(): MutableMap<String, ByteArray> = listOf(
        TILES, WATER, ADJUDICATIONS, WATER_MANIFEST, WORLD, WORLD_MANIFEST, SELECTION, MIGRATION, LEGACY,
    ).associateWith { Files.readAllBytes(root.resolve(it)) }.toMutableMap()

    private fun load(files: Map<String, ByteArray>) = HanStrategicTopologyJson.load("han-world-v3") { files.getValue(it) }

    private fun update(files: MutableMap<String, ByteArray>, path: String, mutate: (ObjectNode) -> Unit) {
        val doc = mapper.readTree(files.getValue(path)) as ObjectNode
        mutate(doc)
        files[path] = mapper.writeValueAsBytes(doc)
    }

    private fun repinManifests(files: MutableMap<String, ByteArray>) {
        update(files, WATER_MANIFEST) { manifest ->
            for ((key, path) in mapOf("baseHanTiles" to TILES, "waterTopology" to WATER, "adjudications" to ADJUDICATIONS)) {
                (manifest["files"][key] as ObjectNode).put("sha256", sha(files.getValue(path)))
                    .put("bytes", files.getValue(path).size)
            }
        }
        update(files, WORLD_MANIFEST) { manifest ->
            (manifest["outputs"] as ObjectNode).put("worldJsonSha256", sha(files.getValue(WORLD)))
            (manifest["inputs"] as ObjectNode).put("selectionSha256", sha(files.getValue(SELECTION)))
                .put("migrationSha256", sha(files.getValue(MIGRATION)))
        }
    }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

    companion object {
        const val TILES = "data/map/han-tiles.json"
        const val WATER = "data/map/han-water-topology-v1.json"
        const val ADJUDICATIONS = "data/curated/han/water-topology-adjudications-v1.json"
        const val WATER_MANIFEST = "data/map/han-strategic-topology-manifest-v1.json"
        const val WORLD = "infra/src/main/resources/map/han-world-v3.json"
        const val WORLD_MANIFEST = "data/map/han-world-v3-manifest-v1.json"
        const val SELECTION = "data/curated/han/route-node-selection-v1.json"
        const val MIGRATION = "data/curated/han/route-node-migration-v1.json"
        const val LEGACY = "infra/src/main/resources/map/han-780-v1.json"
    }
}
