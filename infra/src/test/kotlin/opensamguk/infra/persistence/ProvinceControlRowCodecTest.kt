package opensamguk.infra.persistence

import opensamguk.logic.world.ProvinceControlState
import java.lang.reflect.Proxy
import java.sql.ResultSet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ProvinceControlRowCodecTest {
    private val hash = "a".repeat(64)

    @Test
    fun `decodes exact PostgreSQL integer and bigint values`() {
        assertEquals(
            ProvinceControlState("r1", hash, "province-a", 7, 11),
            ProvinceControlRowCodec.decode(row()),
        )
    }

    @Test
    fun `null or non-integer nation and revision columns fail closed`() {
        for ((column, value) in listOf(
            "nation_id" to null,
            "nation_id" to 7L,
            "revision" to null,
            "revision" to 11,
        )) {
            assertFailsWith<IllegalArgumentException>("$column=$value") {
                ProvinceControlRowCodec.decode(row(mapOf(column to value)))
            }
        }
    }

    @Test
    fun `invalid topology revision hash and scalar ranges fail closed`() {
        for ((column, value) in listOf(
            "topology_revision" to null,
            "topology_revision" to " ",
            "topology_hash" to "A".repeat(64),
            "province_id" to "",
            "nation_id" to -1,
            "revision" to 0L,
        )) {
            assertFailsWith<IllegalArgumentException>("$column=$value") {
                ProvinceControlRowCodec.decode(row(mapOf(column to value)))
            }
        }
    }

    @Test
    fun `write batch is stable immutable and rejects duplicate keys mixed pins and invalid expected revisions`() {
        val first = ProvinceControlWriteRow(2, ProvinceControlState("r1", hash, "a", 1, 4))
        val second = ProvinceControlWriteRow(null, ProvinceControlState("r1", hash, "b", 0, 1))
        val input = mutableListOf(first, second)
        val batch = ProvinceControlWriteBatch(input)

        input.reverse()
        input.clear()
        assertEquals(listOf("a", "b"), batch.map { it.state.provinceId })
        assertFailsWith<UnsupportedOperationException> { (batch as MutableList<*>).clear() }
        assertFailsWith<IllegalArgumentException> { ProvinceControlWriteBatch(listOf(first, first)) }
        assertFailsWith<IllegalArgumentException> {
            ProvinceControlWriteBatch(
                listOf(first, ProvinceControlWriteRow(null, ProvinceControlState("r2", hash, "b", 2, 1))),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ProvinceControlWriteBatch(
                listOf(first, ProvinceControlWriteRow(null, ProvinceControlState("r1", "b".repeat(64), "b", 2, 1))),
            )
        }
        assertFailsWith<IllegalArgumentException> { ProvinceControlWriteRow(0, first.state) }
        assertFailsWith<IllegalArgumentException> { ProvinceControlWriteRow(4, first.state) }
    }

    private fun row(overrides: Map<String, Any?> = emptyMap()): ResultSet {
        val values = linkedMapOf<String, Any?>(
            "topology_revision" to "r1",
            "topology_hash" to hash,
            "province_id" to "province-a",
            "nation_id" to 7,
            "revision" to 11L,
        ).apply { putAll(overrides) }
        return Proxy.newProxyInstance(
            ResultSet::class.java.classLoader,
            arrayOf(ResultSet::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "getObject" -> values[args!![0] as String]
                "getString" -> values[args!![0] as String] as? String
                "toString" -> "ProvinceControlResultSet"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args!![0]
                else -> throw UnsupportedOperationException("Unexpected ResultSet call: ${method.name}")
            }
        } as ResultSet
    }
}
