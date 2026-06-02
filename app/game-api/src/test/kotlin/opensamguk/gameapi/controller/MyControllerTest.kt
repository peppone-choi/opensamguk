package opensamguk.gameapi.controller

import opensamguk.gameapi.owner.GeneralOwnerEntity
import opensamguk.gameapi.owner.GeneralOwnerRepository
import opensamguk.gameapi.owner.GeneralResolver
import opensamguk.gameapi.read.CityReadEntity
import opensamguk.gameapi.read.CityReadRepository
import opensamguk.gameapi.read.GeneralReadEntity
import opensamguk.gameapi.read.GeneralReadRepository
import opensamguk.gameapi.read.NationReadEntity
import opensamguk.gameapi.read.NationReadRepository
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.RequestPostProcessor
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.time.Instant
import java.util.Optional

/**
 * F2 Wave 1 slice test for [MyController] — MockMvc standalone over mocked repos. Asserts the resolved
 * my-page shape, the per-nation my-generals list (isMe flag), and the no-character contract (404 on
 * my-page, result:false empty on the list endpoints).
 */
class MyControllerTest {

    private val owners = mock(GeneralOwnerRepository::class.java)
    private val generals = mock(GeneralReadRepository::class.java)
    private val cities = mock(CityReadRepository::class.java)
    private val nations = mock(NationReadRepository::class.java)
    private val resolver = GeneralResolver(owners, generals, nations)

    private fun mockMvc(): MockMvc =
        MockMvcBuilders.standaloneSetup(MyController(resolver, generals, cities, nations))
            .setCustomArgumentResolvers(AuthenticationPrincipalArgumentResolver())
            .build()

    private fun principal(userId: Long): RequestPostProcessor = RequestPostProcessor { req ->
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(userId, null, listOf(SimpleGrantedAuthority("ROLE_USER")))
        req
    }

    private fun gen(id: Int, name: String, nationId: Int, cityId: Int = 0, officerLevel: Int = 1, npcState: Int = 0) =
        GeneralReadEntity(id = id, name = name, nationId = nationId, cityId = cityId, officerLevel = officerLevel, npcState = npcState)

    @Test
    fun `my-page returns the resolved general detail`() {
        `when`(owners.findByUserId(7L)).thenReturn(GeneralOwnerEntity(generalId = 10L, userId = 7L, claimedAt = Instant.EPOCH))
        `when`(generals.findById(10)).thenReturn(Optional.of(gen(10, "순욱", nationId = 1, cityId = 5, officerLevel = 5)))
        `when`(nations.findById(1)).thenReturn(Optional.of(NationReadEntity(id = 1, name = "위", color = "#00f", level = 7)))
        `when`(cities.findById(5)).thenReturn(Optional.of(CityReadEntity(id = 5, name = "허창", nationId = 1)))

        mockMvc().perform(get("/api/my-page").with(principal(7L)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.generalId").value(10))
            .andExpect(jsonPath("$.name").value("순욱"))
            .andExpect(jsonPath("$.nationName").value("위"))
            .andExpect(jsonPath("$.cityName").value("허창"))
            .andExpect(jsonPath("$.officerLevel").value(5))
            .andExpect(jsonPath("$.permission").value(2)) // officer_level 5 → 수뇌 (showSecret)
    }

    @Test
    fun `my-page 404 when the caller has no character`() {
        `when`(owners.findByUserId(7L)).thenReturn(null)

        mockMvc().perform(get("/api/my-page").with(principal(7L)))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `my-generals lists the nation roster with the isMe flag`() {
        `when`(owners.findByUserId(7L)).thenReturn(GeneralOwnerEntity(generalId = 10L, userId = 7L, claimedAt = Instant.EPOCH))
        `when`(generals.findById(10)).thenReturn(Optional.of(gen(10, "순욱", nationId = 1, officerLevel = 5)))
        `when`(nations.findById(1)).thenReturn(Optional.of(NationReadEntity(id = 1, name = "위", color = "#00f", level = 7)))
        `when`(generals.findByNationIdOrderByOfficerLevelDescIdAsc(1)).thenReturn(
            listOf(gen(1, "조조", nationId = 1, officerLevel = 12), gen(10, "순욱", nationId = 1, officerLevel = 5)),
        )

        mockMvc().perform(get("/api/my-generals").with(principal(7L)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.result").value(true))
            .andExpect(jsonPath("$.nationId").value(1))
            .andExpect(jsonPath("$.generals.length()").value(2))
            .andExpect(jsonPath("$.generals[0].name").value("조조"))
            .andExpect(jsonPath("$.generals[0].mine").value(false))
            .andExpect(jsonPath("$.generals[1].name").value("순욱"))
            .andExpect(jsonPath("$.generals[1].mine").value(true))
    }

    @Test
    fun `my-generals returns result-false empty when no character`() {
        `when`(owners.findByUserId(7L)).thenReturn(null)

        mockMvc().perform(get("/api/my-generals").with(principal(7L)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.result").value(false))
            .andExpect(jsonPath("$.generals.length()").value(0))
    }

    @Test
    fun `my-boss returns the highest officer in the nation`() {
        `when`(owners.findByUserId(7L)).thenReturn(GeneralOwnerEntity(generalId = 10L, userId = 7L, claimedAt = Instant.EPOCH))
        `when`(generals.findById(10)).thenReturn(Optional.of(gen(10, "순욱", nationId = 1, officerLevel = 5)))
        `when`(nations.findById(1)).thenReturn(Optional.of(NationReadEntity(id = 1, name = "위", color = "#00f", level = 7)))
        `when`(generals.findFirstByNationIdOrderByOfficerLevelDesc(1)).thenReturn(gen(1, "조조", nationId = 1, officerLevel = 12))

        mockMvc().perform(get("/api/my-boss").with(principal(7L)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.hasBoss").value(true))
            .andExpect(jsonPath("$.bossName").value("조조"))
            .andExpect(jsonPath("$.bossOfficerLevel").value(12))
    }
}
