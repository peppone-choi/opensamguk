package opensamguk.gameapi.v2

import opensamguk.gameapi.config.GameApiProcessWorld
import opensamguk.gameapi.precheck.PrecheckStateViewFactory
import opensamguk.gameapi.read.CityReadEntity
import opensamguk.gameapi.read.CityReadRepository
import opensamguk.gameapi.read.DiplomacyReadRepository
import opensamguk.gameapi.read.GeneralReadEntity
import opensamguk.gameapi.read.GeneralReadRepository
import opensamguk.gameapi.read.NationReadEntity
import opensamguk.gameapi.read.NationReadRepository
import opensamguk.gameapi.read.WorldStateReadEntity
import opensamguk.gameapi.read.WorldStateReadRepository
import opensamguk.logic.v2.command.V2CityTransportArgs
import opensamguk.logic.v2.command.V2CommandAvailability
import opensamguk.logic.v2.command.V2CommandRegistry
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.sql.ResultSet
import java.util.Optional
import kotlin.test.assertEquals

class V2CommandPrecheckServiceTest {

    @Test
    fun `compatibility map preserves current Han transport allow and deny prechecks`() {
        val args = V2CityTransportArgs(
            fromCityId = 1,
            toCityId = 2,
            gold = 100,
            rice = 0,
            garrison = 0,
            routeRevision = null,
        )
        val available = V2CommandAvailability.Available(V2CommandRegistry.cityTransportSchema, args)
        val escortDenied = V2CommandAvailability.Blocked(
            code = "ESCORT_INSUFFICIENT",
            reason = "수송에는 병사 2000명이 필요합니다.",
        )

        assertEquals(available, service("han", crew = 2_000).precheck(10, available))
        assertEquals(available, service("han-780-v1", crew = 2_000).precheck(10, available))
        assertEquals(escortDenied, service("han", crew = 1_999).precheck(10, available))
        assertEquals(escortDenied, service("han-780-v1", crew = 1_999).precheck(10, available))
    }

    private fun service(mapName: String, crew: Int): V2CommandPrecheckService {
        val generals = mock(GeneralReadRepository::class.java)
        val cities = mock(CityReadRepository::class.java)
        val nations = mock(NationReadRepository::class.java)
        val diplomacies = mock(DiplomacyReadRepository::class.java)
        val worlds = mock(WorldStateReadRepository::class.java)
        val jdbc = mock(NamedParameterJdbcTemplate::class.java)

        `when`(generals.findById(10)).thenReturn(
            Optional.of(
                GeneralReadEntity(
                    id = 10,
                    nationId = 1,
                    cityId = 1,
                    leadership = 70,
                    strength = 50,
                    intel = 50,
                    crew = crew,
                ),
            ),
        )
        `when`(cities.findById(1)).thenReturn(Optional.of(CityReadEntity(id = 1, nationId = 1, level = 5)))
        `when`(cities.findById(2)).thenReturn(Optional.of(CityReadEntity(id = 2, nationId = 1, level = 5)))
        `when`(cities.findByNationIdOrderByIdAsc(1)).thenReturn(emptyList())
        `when`(nations.findById(1)).thenReturn(Optional.of(NationReadEntity(id = 1, level = 7)))
        `when`(diplomacies.findBySrcNationId(1)).thenReturn(emptyList())
        `when`(worlds.findAll()).thenReturn(
            listOf(
                WorldStateReadEntity(
                    id = 1,
                    scenarioCode = "scenario_1010",
                    currentYear = 184,
                    currentMonth = 1,
                    config = mapOf("startYear" to 184, "mapName" to mapName),
                ),
            ),
        )

        doAnswer { invocation ->
            @Suppress("UNCHECKED_CAST")
            val mapper = invocation.getArgument<RowMapper<Any>>(2)
            val resultSet = mock(ResultSet::class.java)
            `when`(resultSet.getLong("gold")).thenReturn(100L)
            `when`(resultSet.getLong("rice")).thenReturn(0L)
            `when`(resultSet.getInt("garrison")).thenReturn(0)
            listOf(mapper.mapRow(resultSet, 0))
        }.`when`(jdbc).query(
            anyString(),
            any(MapSqlParameterSource::class.java),
            any<RowMapper<Any>>(),
        )

        val states = PrecheckStateViewFactory(generals, cities, nations, diplomacies, worlds)
        return V2CommandPrecheckService(states, jdbc, GameApiProcessWorld(1))
    }
}
