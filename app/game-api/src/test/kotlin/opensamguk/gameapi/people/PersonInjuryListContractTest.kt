package opensamguk.gameapi.people

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import opensamguk.gameapi.controller.GeneralListController
import opensamguk.gameapi.controller.MyController
import opensamguk.gameapi.owner.GeneralResolver
import opensamguk.gameapi.read.*
import opensamguk.gameapi.web.CityDetailController
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.util.Optional
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PersonInjuryListContractTest {
    private val owners = mock(GeneralResolver::class.java)
    private val generals = mock(GeneralReadRepository::class.java)
    private val cities = mock(CityReadRepository::class.java)
    private val nations = mock(NationReadRepository::class.java)
    private val worlds = mock(WorldStateReadRepository::class.java)
    private val turns = mock(GeneralTurnReadRepository::class.java)
    private val ranks = mock(RankDataReadRepository::class.java)
    private val troops = mock(TroopReadRepository::class.java)
    private val retainers = mock(RetainerReadRepository::class.java)
    private val mapper = ObjectMapper()
    private val people = (1..4).map { GeneralReadEntity(id = it, worldId = 7, userId = if (it == 1) "41" else null,
        nationId = 10, cityId = 9, name = "합성$it", injury = 20, leadership = 61, strength = 72, intel = 83) }
    private val cards = listOf(GeneralRetainerReadEntity(id = 1, worldId = 7, masterGeneralId = 1, generalId = 2),
        GeneralRetainerReadEntity(id = 2, worldId = 7, masterGeneralId = 2, generalId = 3))

    @AfterEach fun clearIdentity() = SecurityContextHolder.clearContext()

    @Test fun `nation list injury is private for ordinary and administrator roles`() {
        for (role in listOf("USER", "ADMIN")) {
            setup(role)
            val mvc = mvc(GeneralListController(owners, generals, nations, turns, ranks, troops, worlds, retainers = retainers))
            val body = read(mvc.perform(get("/api/nation/general-list")).andExpect(status().isOk).andReturn().response.contentAsString)
            val columns = body["column"].map { it.asText() }
            val id = columns.indexOf("no"); val injury = columns.indexOf("injury")
            assertTrue(injury >= 0, "preserve nullable flat-array column")
            assertRows(body["list"], { it[id].asInt() }, { it[injury] })
        }
    }

    @Test fun `my generals injury is private for ordinary and administrator roles`() {
        for (role in listOf("USER", "ADMIN")) {
            setup(role)
            val mvc = mvc(MyController(owners, generals, cities, nations, worlds, retainers = retainers))
            val body = read(mvc.perform(get("/api/my-generals")).andExpect(status().isOk).andReturn().response.contentAsString)
            assertRows(body["generals"], { it["generalId"].asInt() }, { it["injury"] })
        }
    }

    @Test fun `city general rows injury is private for ordinary and administrator roles`() {
        for (role in listOf("USER", "ADMIN")) {
            setup(role)
            val mvc = mvc(CityDetailController(owners, cities, generals, nations, worlds, retainers = retainers))
            val body = read(mvc.perform(get("/api/city/9")).andExpect(status().isOk).andReturn().response.contentAsString)
            assertRows(body["generals"], { it["no"].asInt() }, { it["wounded"] })
        }
    }

    @Test fun `ambiguous direct cards stay null in every list`() {
        setup("USER")
        `when`(retainers.findAll()).thenReturn(cards +
            GeneralRetainerReadEntity(id = 3, worldId = 7, masterGeneralId = 1, generalId = 2))
        for (rows in listRows()) for ((id, injury) in rows) {
            if (id == 1) assertEquals(20, injury.asInt()) else assertTrue(injury.isNull, "person $id")
        }
    }

    @Test fun `cross world cards do not authorize injury in any list`() {
        setup("USER")
        `when`(retainers.findAll()).thenReturn(cards +
            GeneralRetainerReadEntity(id = 3, worldId = 8, masterGeneralId = 1, generalId = 4))
        for (rows in listRows()) for ((id, injury) in rows) assertTrue(injury.isNull, "person $id")
    }

    @Test fun `missing process world does not authorize injury in any list`() {
        setup("USER")
        `when`(worlds.findProcessWorld()).thenReturn(null)
        for (rows in listRows()) for ((id, injury) in rows) assertTrue(injury.isNull, "person $id")
    }

    @Test fun `invalid authorized injury and valid zero remain distinct in every list`() {
        setup("USER")
        people[0].injury = -1
        people[1].injury = 0
        for (rows in listRows()) for ((id, injury) in rows) {
            if (id == 2) assertEquals(0, injury.asInt()) else assertTrue(injury.isNull, "person $id")
        }
    }

    private fun listRows(): List<List<Pair<Int, JsonNode>>> {
        val nation = read(mvc(GeneralListController(owners, generals, nations, turns, ranks, troops, worlds,
            retainers = retainers)).perform(get("/api/nation/general-list")).andExpect(status().isOk)
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Cache-Control", "no-store"))
            .andReturn().response.contentAsString)
        val columns = nation["column"].map { it.asText() }
        val my = read(mvc(MyController(owners, generals, cities, nations, worlds, retainers = retainers))
            .perform(get("/api/my-generals")).andExpect(status().isOk)
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Cache-Control", "no-store"))
            .andReturn().response.contentAsString)
        val city = read(mvc(CityDetailController(owners, cities, generals, nations, worlds, retainers = retainers))
            .perform(get("/api/city/9")).andExpect(status().isOk)
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Cache-Control", "no-store"))
            .andReturn().response.contentAsString)
        return listOf(nation["list"].map { it[columns.indexOf("no")].asInt() to it[columns.indexOf("injury")] },
            my["generals"].map { it["generalId"].asInt() to it["injury"] },
            city["generals"].map { it["no"].asInt() to it["wounded"] }).also { rows ->
                assertTrue(rows.all { it.size == 4 }, "all sources retain their original four rows")
            }
    }

    private fun setup(role: String) {
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(41L, null,
            listOf(org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_$role")))
        `when`(owners.resolve(41)).thenReturn(GeneralResolver.ResolvedGeneral(people[0], 5, 2, 10, 1))
        `when`(worlds.findProcessWorld()).thenReturn(WorldStateReadEntity(id = 7))
        `when`(worlds.findAll()).thenReturn(listOf(WorldStateReadEntity(id = 7)))
        `when`(nations.findById(10)).thenReturn(Optional.of(NationReadEntity(id = 10, worldId = 7, name = "합성국")))
        `when`(nations.findAll()).thenReturn(listOf(NationReadEntity(id = 10, worldId = 7, name = "합성국")))
        `when`(cities.findById(9)).thenReturn(Optional.of(CityReadEntity(id = 9, worldId = 7, nationId = 10, name = "합성성")))
        `when`(generals.findByNationIdOrderByTurnTimeAsc(10)).thenReturn(people)
        `when`(generals.findByNationIdOrderByOfficerLevelDescIdAsc(10)).thenReturn(people)
        `when`(generals.findByCityIdOrderByTurnTimeAsc(9)).thenReturn(people)
        `when`(retainers.findAll()).thenReturn(cards)
    }

    private fun mvc(controller: Any) = MockMvcBuilders.standaloneSetup(controller)
        .setCustomArgumentResolvers(AuthenticationPrincipalArgumentResolver()).build()
    private fun read(body: String) = mapper.readTree(body)
    private fun assertRows(rows: JsonNode, id: (JsonNode) -> Int, injury: (JsonNode) -> JsonNode) {
        assertEquals(4, rows.size())
        for (row in rows) {
            val value = injury(row)
            if (id(row) in setOf(1, 2)) assertEquals(20, value.asInt(), "allowed person ${id(row)}")
            else assertTrue(value.isNull, "private person ${id(row)} injury must be null, received $value")
        }
    }
}
