package opensamguk.gameapi.retinue

import java.util.Optional
import kotlin.test.*
import opensamguk.gameapi.owner.GeneralResolver
import opensamguk.gameapi.read.*
import org.junit.jupiter.api.AfterEach
import org.mockito.Mockito.*
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.springframework.test.web.servlet.setup.MockMvcBuilders

class RetinueHierarchyReadTest {
    private val owners = mock(GeneralResolver::class.java)
    private val generals = mock(GeneralReadRepository::class.java)
    private val worlds = mock(WorldStateReadRepository::class.java)
    private val retainers = mock(RetainerReadRepository::class.java)
    private val reader = RetinueHierarchyReader(owners, generals, worlds, retainers)
    private val controller = RetinueHierarchyController(RetinueHierarchyQuery(reader))
    private fun person(id: Int) = GeneralReadEntity(id = id, worldId = 7, nationId = 1, name = "G$id",
        userId = if (id == 11) "42" else null, cityId = 999, politics = 98, charm = 97)
    private fun card(id: Int, master: Int, person: Int) =
        GeneralRetainerReadEntity(worldId = 7, id = id, masterGeneralId = master, generalId = person)
    private val cards = listOf(card(1, 10, 11), card(2, 10, 14), card(3, 11, 12), card(4, 12, 13))
    private fun setup() {
        `when`(owners.resolveGeneralId(42)).thenReturn(11)
        `when`(generals.findById(11)).thenReturn(Optional.of(person(11)))
        `when`(worlds.findProcessWorld()).thenReturn(WorldStateReadEntity(id = 7,
            config = mapOf("worldFormat" to "GENERAL_RETAINER_CAMPAIGN")))
        `when`(generals.findAll()).thenReturn((10..14).map(::person).reversed())
        `when`(retainers.findAll()).thenReturn(cards.reversed())
    }
    @AfterEach fun clearIdentity() = SecurityContextHolder.clearContext()

    @Test fun `own structural subtree distinguishes direct and all descendants without including siblings`() {
        setup()
        val result = reader.hierarchy(11, 42)
        assertEquals("READY", result.status)
        assertEquals(listOf(RetinueSuperiorDto(10, "G10", null)), result.superiors)
        assertEquals(listOf(
            RetinueHierarchyNodeDto(11, "G11", 10, 1, 2),
            RetinueHierarchyNodeDto(12, "G12", 11, 1, 1),
            RetinueHierarchyNodeDto(13, "G13", 12, 0, 0)), result.nodes)
        `when`(retainers.findAll()).thenReturn(cards)
        assertEquals(result, reader.hierarchy(11, 42))
    }

    @Test fun `invalid forests and foreign worlds expose no partial relationships`() {
        setup()
        for (invalid in listOf(
            cards.drop(1) + card(5, 13, 11), // cycle with one parent per person
            cards + card(5, 14, 12), // duplicate parent
            cards + card(5, 12, 99), // dangling child
            cards + card(5, 99, 12), // dangling parent
            cards.map { if (it.id == 4) card(4, 12, 13).apply { worldId = 8 } else it },
        )) {
            `when`(retainers.findAll()).thenReturn(invalid)
            assertEquals(RetinueHierarchyDto("UNAVAILABLE", 11), reader.hierarchy(11, 42))
        }
        `when`(retainers.findAll()).thenReturn(cards.take(3))
        `when`(generals.findAll()).thenReturn((10..14).map { person(it).apply { if (id == 12) nationId = 2 } })
        assertEquals(RetinueHierarchyDto("UNAVAILABLE", 11), reader.hierarchy(11, 42))
    }

    @Test fun `missing world unsupported rules and a stale owner expose no hierarchy`() {
        setup()
        for (world in listOf(null, WorldStateReadEntity(id = 8), WorldStateReadEntity(id = 7))) {
            `when`(worlds.findProcessWorld()).thenReturn(world)
            assertEquals(RetinueHierarchyDto("UNAVAILABLE", 11), reader.hierarchy(11, 42))
        }
        `when`(generals.findById(11)).thenReturn(Optional.of(person(11).apply { userId = "43" }))
        assertFailsWith<CampForbidden> { reader.hierarchy(11, 42) }
        verify(generals, never()).findAll()
        verify(retainers, never()).findAll()
    }

    @Test fun `nonpositive actor is forbidden even when a corrupt ownership projection resolves it`() {
        setup()
        `when`(owners.resolveGeneralId(42)).thenReturn(0)
        `when`(generals.findById(0)).thenReturn(Optional.of(person(0).apply { userId = "42" }))
        `when`(generals.findAll()).thenReturn(listOf(person(0)) + (10..14).map(::person))
        assertFailsWith<CampForbidden> { reader.hierarchy(0, 42) }
        verify(generals, never()).findById(0)
    }

    @Test fun `HTTP requires the current owner and exposes only structural fields with no-store`() {
        setup()
        val mvc = MockMvcBuilders.standaloneSetup(controller)
            .setCustomArgumentResolvers(AuthenticationPrincipalArgumentResolver()).build()
        mvc.perform(get("/api/retinue/hierarchy").param("generalId", "11")).andExpect(status().isUnauthorized)
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(42L, null, emptyList())
        mvc.perform(get("/api/retinue/hierarchy")).andExpect(status().isBadRequest)
        mvc.perform(get("/api/retinue/hierarchy").param("generalId", "invalid")).andExpect(status().isBadRequest)
        mvc.perform(get("/api/retinue/hierarchy").param("generalId", "0")).andExpect(status().isForbidden)
        mvc.perform(get("/api/retinue/hierarchy").param("generalId", "10")).andExpect(status().isForbidden)
        verify(generals, never()).findById(10)
        mvc.perform(get("/api/retinue/hierarchy").param("generalId", "11"))
            .andExpect(status().isOk).andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.nodes.length()").value(3))
            .andExpect(jsonPath("$.nodes[0].descendantCount").value(2))
            .andExpect(jsonPath("$.nodes[1].parentId").value(11))
            .andExpect(jsonPath("$.nodes[1].stats").doesNotExist())
            .andExpect(jsonPath("$.nodes[1].location").doesNotExist())
            .andExpect(jsonPath("$.nodes[1].renown").doesNotExist())
            .andExpect(jsonPath("$.nodes[1].retainerId").doesNotExist())
            .andExpect(jsonPath("$.nodes[1].authority").doesNotExist())
        mvc.perform(post("/api/retinue/hierarchy").param("generalId", "11"))
            .andExpect(status().isMethodNotAllowed)
    }
}
