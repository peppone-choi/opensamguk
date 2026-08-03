package opensamguk.gameapi.controller

import opensamguk.common.wire.TurnDaemonCommand
import opensamguk.gameapi.owner.GeneralOwnerEntity
import opensamguk.gameapi.owner.GeneralOwnerRepository
import opensamguk.gameapi.owner.GeneralPossessionService
import opensamguk.gameapi.owner.SelectNpcTokenEntity
import opensamguk.gameapi.owner.SelectNpcTokenRepository
import opensamguk.gameapi.owner.SelectNpcTokenService
import opensamguk.gameapi.read.GeneralReadEntity
import opensamguk.gameapi.read.GeneralReadRepository
import opensamguk.gameapi.read.NationReadEntity
import opensamguk.gameapi.read.NationReadRepository
import opensamguk.gameapi.read.WorldStateReadEntity
import opensamguk.gameapi.read.WorldStateReadRepository
import opensamguk.gameapi.reserve.CommandReserveService
import opensamguk.gameapi.security.GameApiJwtVerifier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockingDetails
import org.mockito.Mockito.`when`
import org.springframework.http.MediaType
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.RequestPostProcessor
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Optional

/**
 * F2 Wave 1 slice test for [PossessionController] + [GeneralPossessionService] — MockMvc standalone over
 * mocked account/read repos (NO Spring context, NO Testcontainers). Asserts the claimable candidate
 * filter, the one-per-user / unowned guards, idempotency, and 409 conflicts — the JSON contract Wave 2
 * builds against. The verified-userId principal is injected via [AuthenticationPrincipalArgumentResolver]
 * + a UsernamePasswordAuthenticationToken post-processor (no real JWT filter in the slice).
 */
class PossessionControllerTest {

    private val owners = mock(GeneralOwnerRepository::class.java)
    private val generals = mock(GeneralReadRepository::class.java)
    private val nations = mock(NationReadRepository::class.java)
    private val npcTokens = mock(SelectNpcTokenRepository::class.java)
    private val worldStates = mock(WorldStateReadRepository::class.java)
    private val reserve = mock(CommandReserveService::class.java)
    private val jwtVerifier = mock(GameApiJwtVerifier::class.java)
    private val fixedClock = Clock.fixed(Instant.parse("2026-06-02T00:00:00Z"), ZoneOffset.UTC)
    private val possession = GeneralPossessionService(owners, generals, npcTokens, worldStates, fixedClock)
    private val selectNpcTokens =
        SelectNpcTokenService(npcTokens, owners, generals, nations, worldStates, fixedClock)

    private fun mockMvc(): MockMvc =
        MockMvcBuilders.standaloneSetup(PossessionController(possession, selectNpcTokens, reserve, jwtVerifier))
            .setCustomArgumentResolvers(AuthenticationPrincipalArgumentResolver())
            .build()

    private fun principal(userId: Long): RequestPostProcessor = RequestPostProcessor { req ->
        org.springframework.security.core.context.SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(userId, null, listOf(SimpleGrantedAuthority("ROLE_USER")))
        req
    }

    private fun npc(
        id: Int, name: String, nationId: Int = 0, npcState: Int = 2,
        specialCode: String = "None", special2Code: String = "None", personalCode: String = "None",
        politics: Int = 50, charm: Int = 50,
    ) = GeneralReadEntity(
        id = id, name = name, nationId = nationId, npcState = npcState,
        leadership = 50, strength = 50, intel = 50, politics = politics, charm = charm,
        specialCode = specialCode, special2Code = special2Code, personalCode = personalCode,
    )

    private fun activeToken(vararg ids: Int) = SelectNpcTokenEntity(
        ownerId = 7L,
        validUntil = Instant.parse("2026-06-02T00:01:00Z"),
        pickMoreFrom = Instant.parse("2000-01-01T01:00:00Z"),
        pickResult = ids.associate { it.toString() to linkedMapOf("generalId" to it) },
        nonce = 1,
        createdAt = Instant.parse("2026-06-02T00:00:00Z"),
        updatedAt = Instant.parse("2026-06-02T00:00:00Z"),
    )

    private fun seedNpcMode(mode: Int = 1) {
        `when`(worldStates.findById(1)).thenReturn(
            Optional.of(WorldStateReadEntity(id = 1, scenarioCode = "scenario_1010", tickSeconds = 3600, config = mapOf("npcmode" to mode))),
        )
    }

    @Test
    fun `claimable lists unowned npc=2 candidates minus already-claimed, with nation names`() {
        seedNpcMode()
        `when`(owners.findByUserId(7L)).thenReturn(null)
        `when`(
            npcTokens.findFirstByOwnerIdAndValidUntilAfterOrderByIdDesc(
                7L,
                Instant.parse("2026-06-02T00:00:00Z"),
            ),
        ).thenReturn(null)
        `when`(npcTokens.findValidOtherTokens(7L, Instant.parse("2026-06-02T00:00:00Z"))).thenReturn(emptyList())
        `when`(owners.findAllByOrderByGeneralIdAsc()).thenReturn(
            listOf(GeneralOwnerEntity(generalId = 11L, userId = 99L, claimedAt = Instant.EPOCH)),
        )
        `when`(generals.findByNpcStateOrderByIdAsc(2)).thenReturn(
            listOf(
                npc(
                    10,
                    "여포",
                    nationId = 1,
                    specialCode = "che_경작",
                    special2Code = "che_돌격",
                    personalCode = "che_정복",
                    politics = 84,
                    charm = 67,
                ),
                npc(11, "장료", nationId = 1), // 11 already claimed → dropped
            ),
        )
        `when`(nations.findAll()).thenReturn(listOf(NationReadEntity(id = 1, name = "동탁", color = "#000")))

        mockMvc().perform(get("/api/generals/claimable").with(principal(7L)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.result").value(true))
            .andExpect(jsonPath("$.hasGeneral").value(false))
            .andExpect(jsonPath("$.candidates.length()").value(1))
            .andExpect(jsonPath("$.candidates[0].generalId").value(10))
            .andExpect(jsonPath("$.candidates[0].name").value("여포"))
            .andExpect(jsonPath("$.candidates[0].nationName").value("동탁"))
            .andExpect(jsonPath("$.candidates[0].politics").value(84))
            .andExpect(jsonPath("$.candidates[0].charm").value(67))
            .andExpect(jsonPath("$.candidates[0].special").value("경작"))
            .andExpect(jsonPath("$.candidates[0].special2").value("돌격"))
            .andExpect(jsonPath("$.candidates[0].personal").value("정복"))
    }

    @Test
    fun `claimable rehydrates politics and charm from an active token snapshot`() {
        seedNpcMode()
        `when`(owners.findByUserId(7L)).thenReturn(null)
        `when`(
            npcTokens.findFirstByOwnerIdAndValidUntilAfterOrderByIdDesc(
                7L,
                Instant.parse("2026-06-02T00:00:00Z"),
            ),
        ).thenReturn(
            SelectNpcTokenEntity(
                ownerId = 7L,
                validUntil = Instant.parse("2026-06-02T00:01:00Z"),
                pickMoreFrom = Instant.parse("2000-01-01T01:00:00Z"),
                pickResult = linkedMapOf(
                    "10" to linkedMapOf(
                        "generalId" to 10,
                        "name" to "여포",
                        "nationId" to 1,
                        "nationName" to "동탁",
                        "leadership" to 90,
                        "strength" to 99,
                        "intel" to 72,
                        "politics" to 84,
                        "charm" to 67,
                        "picture" to "yeopo.png",
                        "imageServer" to 2,
                        "special" to "경작",
                        "special2" to "돌격",
                        "personal" to "정복",
                        "keepCnt" to 3,
                    ),
                    "__pickMoreSeconds" to 10,
                ),
                nonce = 1,
                createdAt = Instant.parse("2026-06-02T00:00:00Z"),
                updatedAt = Instant.parse("2026-06-02T00:00:00Z"),
            ),
        )

        mockMvc().perform(get("/api/generals/claimable").with(principal(7L)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.candidates[0].leadership").value(90))
            .andExpect(jsonPath("$.candidates[0].strength").value(99))
            .andExpect(jsonPath("$.candidates[0].intel").value(72))
            .andExpect(jsonPath("$.candidates[0].politics").value(84))
            .andExpect(jsonPath("$.candidates[0].charm").value(67))
    }

    @Test
    fun `claimable issues a tokenized shortlist capped at five picks`() {
        seedNpcMode()
        `when`(owners.findByUserId(7L)).thenReturn(null)
        `when`(
            npcTokens.findFirstByOwnerIdAndValidUntilAfterOrderByIdDesc(
                7L,
                Instant.parse("2026-06-02T00:00:00Z"),
            ),
        ).thenReturn(null)
        `when`(npcTokens.findValidOtherTokens(7L, Instant.parse("2026-06-02T00:00:00Z"))).thenReturn(emptyList())
        `when`(owners.findAllByOrderByGeneralIdAsc()).thenReturn(emptyList())
        `when`(generals.findByNpcStateOrderByIdAsc(2)).thenReturn(
            (10..15).map { id -> npc(id, "후보$id", nationId = 1) },
        )
        `when`(nations.findAll()).thenReturn(listOf(NationReadEntity(id = 1, name = "동탁", color = "#000")))

        mockMvc().perform(get("/api/generals/claimable").with(principal(7L)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.result").value(true))
            .andExpect(jsonPath("$.validUntil").exists())
            .andExpect(jsonPath("$.pickMoreFrom").exists())
            .andExpect(jsonPath("$.pickMoreSeconds").exists())
            .andExpect(jsonPath("$.candidates.length()").value(5))
            .andExpect(jsonPath("$.candidates[0].keepCnt").value(3))
    }

    @Test
    fun `claimable returns empty when the caller already owns a general`() {
        seedNpcMode()
        `when`(owners.findByUserId(7L)).thenReturn(GeneralOwnerEntity(generalId = 10L, userId = 7L, claimedAt = Instant.EPOCH))

        mockMvc().perform(get("/api/generals/claimable").with(principal(7L)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.hasGeneral").value(true))
            .andExpect(jsonPath("$.candidates.length()").value(0))
    }

    @Test
    fun `claim inserts general_owner for an unowned npc=2 candidate`() {
        seedNpcMode()
        `when`(owners.findByUserId(7L)).thenReturn(null)
        `when`(generals.findById(10)).thenReturn(Optional.of(npc(10, "여포")))
        `when`(
            npcTokens.findFirstByOwnerIdAndValidUntilAfterOrderByIdDesc(
                7L,
                Instant.parse("2026-06-02T00:00:00Z"),
            ),
        ).thenReturn(activeToken(10))
        `when`(owners.existsByGeneralId(10L)).thenReturn(false)
        `when`(reserve.publishImmediate(org.mockito.ArgumentMatchers.any(TurnDaemonCommand::class.java) ?: TurnDaemonCommand.Pause()))
            .thenReturn(CommandReserveService.ReserveResult("req-claim-10", 0))

        mockMvc().perform(
            post("/api/general/claim").with(principal(7L))
                .contentType(MediaType.APPLICATION_JSON).content("""{"generalId":10}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.result").value(true))
            .andExpect(jsonPath("$.generalId").value(10))
            .andExpect(jsonPath("$.requestId").value("req-claim-10"))

        val claim = mockingDetails(reserve).invocations
            .single { it.method.name == "publishImmediate" }
            .arguments[0] as TurnDaemonCommand.ClaimNpc
        assertEquals(10, claim.generalId)
        assertEquals(7L, claim.userId)
        assertEquals("7", claim.userNick)
    }

    @Test
    fun `claim 409 when the requested general is outside the active token`() {
        seedNpcMode()
        `when`(owners.findByUserId(7L)).thenReturn(null)
        `when`(generals.findById(10)).thenReturn(Optional.of(npc(10, "여포")))
        `when`(
            npcTokens.findFirstByOwnerIdAndValidUntilAfterOrderByIdDesc(
                7L,
                Instant.parse("2026-06-02T00:00:00Z"),
            ),
        ).thenReturn(activeToken(11))

        mockMvc().perform(
            post("/api/general/claim").with(principal(7L))
                .contentType(MediaType.APPLICATION_JSON).content("""{"generalId":10}"""),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.result").value(false))
    }

    @Test
    fun `claim is idempotent when the caller already owns exactly that general`() {
        seedNpcMode()
        `when`(owners.findByUserId(7L)).thenReturn(GeneralOwnerEntity(generalId = 10L, userId = 7L, claimedAt = Instant.EPOCH))

        mockMvc().perform(
            post("/api/general/claim").with(principal(7L))
                .contentType(MediaType.APPLICATION_JSON).content("""{"generalId":10}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.result").value(true))
            .andExpect(jsonPath("$.generalId").value(10))
    }

    @Test
    fun `claim 409 when the caller already owns a different general`() {
        seedNpcMode()
        `when`(owners.findByUserId(7L)).thenReturn(GeneralOwnerEntity(generalId = 5L, userId = 7L, claimedAt = Instant.EPOCH))

        mockMvc().perform(
            post("/api/general/claim").with(principal(7L))
                .contentType(MediaType.APPLICATION_JSON).content("""{"generalId":10}"""),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.result").value(false))
    }

    @Test
    fun `claim 409 when the general is already claimed by someone else`() {
        seedNpcMode()
        `when`(owners.findByUserId(7L)).thenReturn(null)
        `when`(generals.findById(10)).thenReturn(Optional.of(npc(10, "여포")))
        `when`(
            npcTokens.findFirstByOwnerIdAndValidUntilAfterOrderByIdDesc(
                7L,
                Instant.parse("2026-06-02T00:00:00Z"),
            ),
        ).thenReturn(activeToken(10))
        `when`(owners.existsByGeneralId(10L)).thenReturn(true)

        mockMvc().perform(
            post("/api/general/claim").with(principal(7L))
                .contentType(MediaType.APPLICATION_JSON).content("""{"generalId":10}"""),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.result").value(false))
    }

    @Test
    fun `claim 409 when the target is not a claimable npc=2 candidate`() {
        seedNpcMode()
        `when`(owners.findByUserId(7L)).thenReturn(null)
        `when`(generals.findById(10)).thenReturn(Optional.of(npc(10, "조조", npcState = 0)))

        mockMvc().perform(
            post("/api/general/claim").with(principal(7L))
                .contentType(MediaType.APPLICATION_JSON).content("""{"generalId":10}"""),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.result").value(false))
    }

    @Test
    fun `claimable blocks when server does not allow possession`() {
        seedNpcMode(0)

        mockMvc().perform(get("/api/generals/claimable").with(principal(7L)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.result").value(false))
            .andExpect(jsonPath("$.reason").value("빙의 가능한 서버가 아닙니다"))
            .andExpect(jsonPath("$.candidates.length()").value(0))
    }

    @Test
    fun `claim blocks when server does not allow possession`() {
        seedNpcMode(0)

        mockMvc().perform(
            post("/api/general/claim").with(principal(7L))
                .contentType(MediaType.APPLICATION_JSON).content("""{"generalId":10}"""),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.result").value(false))
            .andExpect(jsonPath("$.reason").value("빙의 가능한 서버가 아닙니다"))
    }
}
