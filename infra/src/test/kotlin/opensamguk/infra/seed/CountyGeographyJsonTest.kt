package opensamguk.infra.seed

import java.nio.file.Path
import java.lang.management.ManagementFactory
import java.security.MessageDigest
import opensamguk.infra.persistence.MetaJson
import opensamguk.logic.input.CountyGeography
import opensamguk.logic.input.CountyPlace
import kotlin.test.*
import opensamguk.logic.world.WorldMapVariant

/** Reads the pinned 1133 bundle: every administrative county gets the runtime map's commandery, never an inferred one. */
class CountyGeographyJsonTest {
    @Test fun `administrative counties map to their runtime commandery and a unique jurisdiction`() {
        val bundle = WorldArtifactsResolver(Path.of("..")).artifacts(WorldMapVariant.V3_1133)
        val geography = CountyGeographyJson.load(bundle)
        val admin = bundle.projection.administrativeCountyIds
        assertEquals(admin, geography.byCounty.keys, "every administrative county carries meta.junCh")
        assertTrue(geography.byCounty.values.all { it.jurisdictionId != null })
        // Commandery groups are non-trivial: many counties share one commandery key.
        val groups = geography.byCounty.values.groupBy { it.commanderyId }
        assertTrue(groups.size in 100..admin.size, "${groups.size} commanderies")
        assertTrue(groups.values.any { it.size > 1 })
        // A county's jurisdiction resolves back to itself (hometown lookup is unambiguous when it answers).
        for (place in geography.byCounty.values) {
            val back = geography.countyOfJurisdiction(place.jurisdictionId!!)
            assertTrue(back == null || back == place.countyId)
        }
    }

    @Test fun `non administrative cities and cities without a commandery are left out`() {
        val map = """{"cities":[{"id":1,"meta":{"junCh":"甲郡","jun":"갑군"},"provinceId":0},
            {"id":2,"meta":{"jun":"을군"},"provinceId":1},{"id":3,"meta":{"junCh":"丙郡"},"provinceId":1}]}""".toByteArray()
        val tiles = """{"provinceRecords":[{"id":"seat","jurisdictionId":"j1"},
            {"id":"other","jurisdictionId":"j2"},{"id":"cityless","jurisdictionId":"j1"}]}""".toByteArray()
        val geography = CountyGeographyJson.parse(map, tiles, setOf(1, 2))
        assertEquals(setOf(1), geography.byCounty.keys)
        assertEquals("甲郡", geography.commanderyOf(1))
        assertEquals(1, geography.countyOfJurisdiction("j1"))
        assertEquals(setOf("seat", "cityless"), geography.provincesOfCounty(1))
    }

    @Test fun `selective terrain parsing matches the original permissive codec`() {
        val records = """[{"id":"seat","jurisdictionId":"j1"},null,{"id":"cityless","jurisdictionId":"j1"}]"""
        val cases = listOf(
            """{"provinceRecords":[]}""", "null", "", " \t\r\n", "[]", "1", "true",
            """{"provinceRecords":null}""", """{"provinceRecords":{}}""",
            """{"provinceRecords":[null]}""", """{"provinceRecords":[{},false]}""",
            """{"provinceRecords":[{"jurisdictionId":"j1"}]}""",
            """{"provinceRecords":[{"id":1,"jurisdictionId":"j1"}]}""",
            """{"provinceRecords":[{"id":"seat","jurisdictionId":""}]}""",
            """{"provinceRecords":[{"id":"seat","jurisdictionId":2}]}""",
            """{"provinceRecords":[{"id":"seat","jurisdictionId":"j1","jurisdictionId":null}]}""",
            """{"provinceRecords":[{"id":null,"id":"seat","jurisdictionId":"j1"}]}""",
            """{"provinceRecords":$records,"provinceRecords":[]}""",
            """{"provinceRecords":[],"provinceRecords":$records}""",
            """{"provinceRecords":[],"provinceRecords":false}""",
            """{"provinceRecords":[{"id":"seat","jurisdictionId":"j1"}],"unused":[01,.5,5.,-0,1e309,9223372036854775808]}""",
            """{"provin\u0063eRecords":[{"\u0069d":"seat","jurisdictionId":"j1"}]}""",
            """{"provinceRecords":[{"id":"s\u+041\u-001","jurisdictionId":"j1"}]}""",
            """{"provinceRecords":[{"id":"한글甲\uD83D\uDE00","jurisdictionId":"j1"}]}""",
            """{"provinceRecords":[],"unused":"raw"}""".replace("raw", "control\u0001\n"),
            "\u2003\u00a0" + """{"provinceRecords":[]}""" + "\u2003\u00a0",
            """{"provinceRecords":[],"unused":{"deep":[true,false,null,{"x":"\\\"\/\b\f\n\r\t"}]}}""",
            """{"provinceRecords":[],"unused":[1,]}""",
            """{"provinceRecords":[],"unused":{"x":1,}}""",
            """{"provinceRecords":[],"unused":"\q"}""",
            """{"provinceRecords":[],"unused":"\u12xz"}""",
            """{"provinceRecords":[],"unused":"\u12""",
            """{"provinceRecords":[],"unused":tru}""",
            """{"provinceRecords":[],"unused":nul}""",
            """{"provinceRecords":[],"unused":1e}""",
            """{"provinceRecords":[],"unused":.}""",
            """{"provinceRecords":[]} trailing""",
            """{"provinceRecords":[1,],"provinceRecords":[]}""",
        )
        for ((index, tiles) in cases.withIndex()) {
            compareOriginal(simpleMap, tiles.toByteArray(), setOf(1), "terrain case $index")
        }
        // ByteArray.toString(UTF_8) replaces malformed sequences; streaming decoding must agree.
        for (invalid in listOf(byteArrayOf(0xc0.toByte(), 0xaf.toByte()), byteArrayOf(0xe2.toByte()),
            byteArrayOf(0xed.toByte(), 0xa0.toByte(), 0x80.toByte()))) {
            val bytes = """{"provinceRecords":[{"id":"""".toByteArray() + invalid +
                """","jurisdictionId":"j1"}]}""".toByteArray()
            compareOriginal(simpleMap, bytes, setOf(1), "malformed UTF-8")
        }
    }

    @Test fun `generated inputs buffer boundaries and malformed unused values match the oracle`() {
        val random = kotlin.random.Random(766)
        val ids = listOf<Any?>(null, 1, "seat", "甲\\\"", "s\u0001", "")
        val jurisdictions = listOf<Any?>(null, 1, "j1", "", " ", "관할")
        repeat(200) { index ->
            val provinces = List(random.nextInt(1, 6)) {
                if (random.nextInt(4) == 0) listOf(1, null) else linkedMapOf(
                    "id" to ids.random(random), "jurisdictionId" to jurisdictions.random(random),
                    "unused" to listOf(true, false, null, 3.25, Long.MAX_VALUE, mapOf("a" to "가")))
            }
            val tiles = MetaJson.encode(linkedMapOf("unused" to listOf(mapOf("a" to "b")),
                "provinceRecords" to provinces))
            compareOriginal(simpleMap, tiles.toByteArray(), setOf(1), "generated $index")
            val corrupt = tiles.replace("[true,false,null", "[true,false,nul")
            compareOriginal(simpleMap, corrupt.toByteArray(), setOf(1), "corrupted $index")
        }
        for (length in listOf(4093, 4094, 4095, 4096, 8191)) {
            val padding = "甲".repeat(length)
            val tiles = """{"unused":"$padding\u+041","provinceRecords":[{"id":"$padding\uD83D\uDE00","jurisdictionId":"j1"}]}"""
            compareOriginal(simpleMap, tiles.toByteArray(), setOf(1), "buffer boundary $length")
            val truncated = """{"provinceRecords":[],"unused":"$padding\u1""" + "\u2003\r\n"
            compareOriginal(simpleMap, truncated.toByteArray(), setOf(1), "truncated escape $length")
        }
    }

    @Test fun `runtime number coercion city validation and parse ordering remain unchanged`() {
        val goodTiles = """{"provinceRecords":[{"id":"seat","jurisdictionId":"j1"}]}""".toByteArray()
        val maps = listOf("null", "", "[]", "{}", """{"cities":null}""", """{"cities":[null]}""",
            """{"cities":[{"meta":{}}]}""", """{"cities":[{"id":"1"}]}""",
            """{"cities":[{"id":1,"meta":false}]}""", """{"cities":[{"id":1,"meta":{"junCh":""}}]}""",
            """{"cities":[{"id":1,"meta":{"junCh":false}}]}""",
            """{"cities":[{"id":1,"id":2,"meta":{"junCh":"甲郡"}}]}""",
            """{"cities":[{"id":1,"meta":{"junCh":"甲郡"}},{"id":1,"meta":{"junCh":"甲郡"}}]}""") +
            listOf("1.9", "4294967297", "9223372036854775808", "1e309", ".5", "5.").map {
                """{"cities":[{"id":$it,"provinceId":-0.5,"meta":{"junCh":"甲郡","jun":42}}]}"""
            }
        for ((index, map) in maps.withIndex()) {
            compareOriginal(map.toByteArray(), goodTiles, setOf(1, 2, 5), "runtime case $index")
        }
        for (map in listOf("{", "{}", """{"cities":[{}]}""", """{"cities":[null]}""")) {
            for (tiles in listOf("{", "{}", """{"provinceRecords":[null]}""")) {
                compareOriginal(map.toByteArray(), tiles.toByteArray(), emptySet(), "validation order")
            }
        }
    }

    @Test fun `malformed unused subtrees retain exception types and redact input bearing diagnostics`() {
        val prefix = """{"provinceRecords":[],"unused":"CANARY-input","number":"""
        for (token in listOf(".", "-", "1e", "falsee")) {
            compareOriginal(simpleMap, (prefix + token + "}").toByteArray(), setOf(1), "invalid number")
        }
        for (tiles in listOf("""["CANARY-input"]""", """{"provinceRecords":[]} CANARY-input""")) {
            val old = runCatching { original(simpleMap, tiles.toByteArray(), setOf(1)) }.exceptionOrNull()!!
            val new = runCatching { CountyGeographyJson.parse(simpleMap, tiles.toByteArray(), setOf(1)) }.exceptionOrNull()!!
            assertEquals(old.javaClass, new.javaClass)
            assertTrue(old.message!!.contains("CANARY-input"))
            assertEquals(old.message!!.substringBefore(": ") + ": [redacted]", new.message)
        }
    }

    @Test fun `large unused raster has bounded allocation while the original violates the same budget`() {
        val raster = buildString {
            append("""{"unusedRaster":[""")
            repeat(1_300_000) { if (it > 0) append(','); append("123456789") }
            append("""],"provinceRecords":[{"id":"seat","jurisdictionId":"j1"}]}""")
        }.toByteArray()
        assertTrue(raster.size >= 11 * 1024 * 1024)
        val bean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        assertTrue(bean.isThreadAllocatedMemorySupported, "allocation measurement is required")
        bean.isThreadAllocatedMemoryEnabled = true
        // Warm class initialization outside the measured parses; the large input itself is prebuilt.
        repeat(3) {
            CountyGeographyJson.parse(simpleMap, """{"provinceRecords":[]}""".toByteArray(), setOf(1))
            original(simpleMap, """{"provinceRecords":[]}""".toByteArray(), setOf(1))
        }
        val thread = Thread.currentThread().threadId()
        var before = bean.getThreadAllocatedBytes(thread)
        val expected = original(simpleMap, raster, setOf(1))
        val originalBytes = bean.getThreadAllocatedBytes(thread) - before
        before = bean.getThreadAllocatedBytes(thread)
        val actual = CountyGeographyJson.parse(simpleMap, raster, setOf(1))
        val selectiveBytes = bean.getThreadAllocatedBytes(thread) - before
        equalGeography(expected, actual)
        val budget = raster.size / 10L
        println("COUNTY_ALLOCATION input=${raster.size} original=$originalBytes selective=$selectiveBytes budget=$budget")
        assertTrue(originalBytes > budget, "the oracle must demonstrate the RED allocation failure")
        assertTrue(selectiveBytes < budget, "terrain allocation $selectiveBytes exceeds $budget")
    }

    @Test fun `selected 1133 previous 1428 and current 1428 bundles match without altering bytes or topology pins`() {
        val resolver = WorldArtifactsResolver(Path.of(".."))
        for (variant in listOf(WorldMapVariant.V3_1133, WorldMapVariant.V3_1428, WorldMapVariant.PROVINCE_WORLD)) {
            val bundle = resolver.artifacts(variant)
            val paths = listOf(CountyGeographyJson.RUNTIME_MAP, CountyGeographyJson.TILES)
            val hashes = paths.map { sha256(bundle.artifactBytes(it)) }
            val topologyHash = bundle.projection.topology.contentHash
            val runtime = bundle.artifactBytes(paths[0])
            val tiles = bundle.artifactBytes(paths[1])
            val admin = bundle.projection.administrativeCountyIds
            equalGeography(original(runtime, tiles, admin), CountyGeographyJson.parse(runtime, tiles, admin))
            assertEquals(hashes, paths.map { sha256(bundle.artifactBytes(it)) })
            assertEquals(topologyHash, bundle.projection.topology.contentHash)
            println("COUNTY_BUNDLE variant=$variant runtime=${hashes[0]} tiles=${hashes[1]} topology=$topologyHash")
        }
    }

    private val simpleMap = """{"cities":[{"id":1,"provinceId":0,"meta":{"junCh":"甲郡","jun":"갑군"}}]}""".toByteArray()

    private fun compareOriginal(runtime: ByteArray, tiles: ByteArray, admin: Set<Int>, label: String) {
        val expected = runCatching { original(runtime, tiles, admin) }
        val actual = runCatching { CountyGeographyJson.parse(runtime, tiles, admin) }
        val error = expected.exceptionOrNull()
        if (error == null) {
            assertTrue(actual.isSuccess, "$label unexpectedly failed: ${actual.exceptionOrNull()?.javaClass}")
            equalGeography(expected.getOrThrow(), actual.getOrThrow())
        } else {
            val changed = assertNotNull(actual.exceptionOrNull(), "$label unexpectedly succeeded")
            assertEquals(error.javaClass, changed.javaClass, label)
            val message = error.message.orEmpty()
            val runtimeValid = runCatching {
                MetaJson.decode(runtime.toString(Charsets.UTF_8))["cities"] as? List<*>
                    ?: error("runtime map cities missing")
            }.isSuccess
            when {
                runtimeValid && (message.startsWith("trailing content in jsonb: ") ||
                    message.startsWith("jsonb root is not an object: ")) ->
                    assertEquals(message.substringBefore(": ") + ": [redacted]", changed.message, label)
                error is NumberFormatException && changed.message == "invalid jsonb number: [redacted]" -> Unit
                else -> assertEquals(message, changed.message, label)
            }
        }
    }

    private fun equalGeography(expected: CountyGeography, actual: CountyGeography) {
        assertEquals(expected.byCounty, actual.byCounty)
        for (id in expected.byCounty.keys) assertEquals(expected.provincesOfCounty(id), actual.provincesOfCounty(id))
        for (jurisdiction in expected.byCounty.values.mapNotNull { it.jurisdictionId }) {
            assertEquals(expected.countyOfJurisdiction(jurisdiction), actual.countyOfJurisdiction(jurisdiction))
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

    // Frozen baseline oracle (d28f766), intentionally independent of the selective reader.
    private fun original(runtimeMap: ByteArray, tiles: ByteArray, administrativeCountyIds: Set<Int>): CountyGeography {
        val cities = MetaJson.decode(runtimeMap.toString(Charsets.UTF_8))["cities"] as? List<*>
            ?: error("runtime map cities missing")
        val provinces = MetaJson.decode(tiles.toString(Charsets.UTF_8))["provinceRecords"] as? List<*>
            ?: error("han-tiles provinceRecords missing")
        val places = cities.mapNotNull { raw ->
            val city = raw as? Map<*, *> ?: error("runtime map city is not an object")
            val id = (city["id"] as? Number)?.toInt() ?: error("runtime map city id missing")
            if (id !in administrativeCountyIds) return@mapNotNull null
            val meta = city["meta"] as? Map<*, *> ?: return@mapNotNull null
            val commandery = (meta["junCh"] as? String)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val provinceIndex = (city["provinceId"] as? Number)?.toInt()
            val jurisdiction = provinceIndex?.takeIf { it in provinces.indices }
                ?.let { (provinces[it] as? Map<*, *>)?.get("jurisdictionId") as? String }?.takeIf { it.isNotBlank() }
            CountyPlace(id, commandery, (meta["jun"] as? String)?.takeIf { it.isNotBlank() }, jurisdiction)
        }
        val provinceIdsByJurisdiction = provinces.mapNotNull { raw ->
            val province = raw as? Map<*, *> ?: error("han-tiles province is not an object")
            val jurisdictionId = province["jurisdictionId"] as? String ?: return@mapNotNull null
            val provinceId = province["id"] as? String ?: error("han-tiles province id missing")
            jurisdictionId to provinceId
        }.groupBy({ it.first }, { it.second }).mapValues { it.value.toSet() }
        return CountyGeography(places, provinceIdsByJurisdiction)
    }
}
