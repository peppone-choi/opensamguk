package opensamguk.infra.persistence

import opensamguk.logic.world.GeneralPositionState
import opensamguk.logic.world.StrategicNodeRef
import java.lang.reflect.Proxy
import java.sql.ResultSet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class GeneralPositionRowCodecTest {
    private val hash = "c".repeat(64)

    @Test
    fun `decodes both exact strategic node discriminators`() {
        val land = GeneralPositionRowCodec.decode(row())
        assertEquals(12, land.generalId)
        assertEquals("land-a", assertIs<StrategicNodeRef.LandProvince>(land.node).id)

        val water = GeneralPositionRowCodec.decode(row(mapOf("node_kind" to "WATER_ZONE", "node_id" to "river-a")))
        assertEquals("river-a", assertIs<StrategicNodeRef.WaterZone>(water.node).id)
    }

    @Test
    fun `null or non-integer general and revision columns fail closed`() {
        for ((column, value) in listOf(
            "general_id" to null,
            "general_id" to 12L,
            "revision" to null,
            "revision" to 9,
        )) {
            assertFailsWith<IllegalArgumentException>("$column=$value") {
                GeneralPositionRowCodec.decode(row(mapOf(column to value)))
            }
        }
    }

    @Test
    fun `invalid node kind revision hash and scalar ranges fail closed`() {
        for ((column, value) in listOf(
            "node_kind" to null,
            "node_kind" to "CITY",
            "node_kind" to "land_province",
            "node_id" to " ",
            "topology_revision" to "",
            "topology_hash" to "not-a-hash",
            "general_id" to 0,
            "revision" to -1L,
        )) {
            assertFailsWith<IllegalArgumentException>("$column=$value") {
                GeneralPositionRowCodec.decode(row(mapOf(column to value)))
            }
        }
    }

    @Test
    fun `write batch is stable immutable and rejects duplicate keys mixed pins and invalid expected revisions`() {
        val first = GeneralPositionWriteRow(
            1,
            GeneralPositionState("r1", hash, 12, StrategicNodeRef.LandProvince("a"), 3),
        )
        val second = GeneralPositionWriteRow(
            null,
            GeneralPositionState("r1", hash, 13, StrategicNodeRef.WaterZone("w"), 1),
        )
        val input = mutableListOf(first, second)
        val batch = GeneralPositionWriteBatch(input)

        input.reverse()
        input.clear()
        assertEquals(listOf(12, 13), batch.map { it.state.generalId })
        assertFailsWith<UnsupportedOperationException> { (batch as MutableList<*>).clear() }
        assertFailsWith<IllegalArgumentException> { GeneralPositionWriteBatch(listOf(first, first)) }
        assertFailsWith<IllegalArgumentException> {
            GeneralPositionWriteBatch(
                listOf(first, GeneralPositionWriteRow(null, second.state.copy(topologyRevision = "r2"))),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            GeneralPositionWriteBatch(
                listOf(first, GeneralPositionWriteRow(null, second.state.copy(topologyHash = "d".repeat(64)))),
            )
        }
        assertFailsWith<IllegalArgumentException> { GeneralPositionWriteRow(-1, first.state) }
        assertFailsWith<IllegalArgumentException> { GeneralPositionWriteRow(3, first.state) }
    }

    private fun row(overrides: Map<String, Any?> = emptyMap()): ResultSet {
        val values = linkedMapOf<String, Any?>(
            "topology_revision" to "r1",
            "topology_hash" to hash,
            "general_id" to 12,
            "node_kind" to "LAND_PROVINCE",
            "node_id" to "land-a",
            "revision" to 9L,
        ).apply { putAll(overrides) }
        return Proxy.newProxyInstance(
            ResultSet::class.java.classLoader,
            arrayOf(ResultSet::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "getObject" -> values[args!![0] as String]
                "getString" -> values[args!![0] as String] as? String
                "toString" -> "GeneralPositionResultSet"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args!![0]
                else -> throw UnsupportedOperationException("Unexpected ResultSet call: ${method.name}")
            }
        } as ResultSet
    }
}
