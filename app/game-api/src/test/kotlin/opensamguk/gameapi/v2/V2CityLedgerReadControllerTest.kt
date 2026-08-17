package opensamguk.gameapi.v2

import opensamguk.gameapi.config.GameApiProcessWorld
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.core.namedparam.SqlParameterSource
import java.sql.ResultSet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * OPENSAM-155 (v2 R6) — 원장 열람의 SQL 경계와 빈-원장 시멘틱.
 *
 * 컨트롤러가 JPA를 쓰지 않으므로 검증 대상은 (1) SQL이 항상 월드로 좁혀지는가, (2) 정렬이 결정적인가,
 * (3) 행이 없는 도시가 404가 아니라 0/0/0인가 — 세 가지다.
 */
class V2CityLedgerReadControllerTest {

    /** `query(sql, params, rowMapper)` 만 가로채고 나머지는 손대지 않는 fake. */
    private class FakeJdbc(private val rows: List<List<Number>>) :
        NamedParameterJdbcTemplate(mock(JdbcOperations::class.java)) {

        var lastSql: String? = null
        var lastParams: Map<String, Any?> = emptyMap()

        override fun <T> query(sql: String, params: SqlParameterSource, rowMapper: RowMapper<T>): List<T> {
            lastSql = sql
            lastParams = (params as MapSqlParameterSource).values
            return rows.mapIndexed { index, row -> rowMapper.mapRow(resultSet(row), index)!! }
        }

        private fun resultSet(row: List<Number>): ResultSet {
            val rs = mock(ResultSet::class.java)
            `when`(rs.getInt("city_id")).thenReturn(row[0].toInt())
            `when`(rs.getLong("gold")).thenReturn(row[1].toLong())
            `when`(rs.getLong("rice")).thenReturn(row[2].toLong())
            `when`(rs.getInt("garrison")).thenReturn(row[3].toInt())
            return rs
        }
    }

    private fun controller(rows: List<List<Number>>): Pair<V2CityLedgerReadController, FakeJdbc> {
        val jdbc = FakeJdbc(rows)
        return V2CityLedgerReadController(jdbc, GameApiProcessWorld(7)) to jdbc
    }

    @Test
    fun `list returns every ledger row of the process world`() {
        val (api, jdbc) = controller(listOf(listOf(3, 1_200L, 800L, 5_000), listOf(9, 0L, 0L, 100)))

        val entries = api.list().entries

        assertEquals(
            listOf(
                V2CityLedgerReadController.CityLedgerView(3, 1_200, 800, 5_000),
                V2CityLedgerReadController.CityLedgerView(9, 0, 0, 100),
            ),
            entries,
        )
        assertEquals(7, jdbc.lastParams["world_id"])
    }

    @Test
    fun `the list SQL is world-scoped, deterministically ordered, and never SELECT star`() {
        val (api, jdbc) = controller(emptyList())
        api.list()

        val sql = jdbc.lastSql!!
        assertTrue(sql.contains("WHERE world_id = :world_id"), sql)
        assertTrue(sql.contains("ORDER BY city_id"), sql)
        assertFalse(sql.contains("SELECT *"), sql)
    }

    @Test
    fun `single city read is scoped by both world and city`() {
        val (api, jdbc) = controller(listOf(listOf(4, 50L, 60L, 70)))

        assertEquals(V2CityLedgerReadController.CityLedgerView(4, 50, 60, 70), api.one(4))
        assertEquals(7, jdbc.lastParams["world_id"])
        assertEquals(4, jdbc.lastParams["city_id"])
        assertTrue(jdbc.lastSql!!.contains("city_id = :city_id"), jdbc.lastSql!!)
    }

    /** 원장 행이 아직 없는 도시는 "없는 도시"가 아니다 — 엔진 `V2CityLedgerEntry.EMPTY`와 같은 값. */
    @Test
    fun `a city without a ledger row reads as zeros, not as missing`() {
        val (api, _) = controller(emptyList())

        assertEquals(V2CityLedgerReadController.CityLedgerView(11, 0, 0, 0), api.one(11))
    }
}
