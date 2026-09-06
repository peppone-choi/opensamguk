package opensamguk.gameapi.controller

import opensamguk.gameapi.owner.GeneralResolver
import opensamguk.gameapi.read.BoardPostReadRepository
import opensamguk.gameapi.read.CityReadEntity
import opensamguk.gameapi.read.CityReadRepository
import opensamguk.gameapi.read.GeneralReadEntity
import opensamguk.gameapi.read.GeneralReadRepository
import opensamguk.gameapi.read.OperationReadEntity
import opensamguk.gameapi.read.OperationReadRepository
import opensamguk.gameapi.read.OperationUnitReadEntity
import opensamguk.gameapi.read.RetainerReadRepository
import opensamguk.gameapi.read.SecretPermissionReader
import opensamguk.gameapi.read.WorldStateReadEntity
import opensamguk.gameapi.read.WorldStateReadRepository
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers
import org.mockito.ArgumentMatchers.anyInt
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
import java.util.Optional

/** spec v4.1 §8 — 401 / 재야 빈 목록+rules / 200 / 타국 403 / kinds declarable·reason / myPermission / remainingMonths 종료 null. */
class OperationReadControllerTest {

    @BeforeEach fun before() = SecurityContextHolder.clearContext()
    @AfterEach fun after() = SecurityContextHolder.clearContext()

    private fun principal(userId: Long): RequestPostProcessor = RequestPostProcessor { req ->
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(userId, null, listOf(SimpleGrantedAuthority("ROLE_USER"))); req
    }

    private fun gen(id: Int, nationId: Int, officerLevel: Int = 5) = GeneralReadEntity(id = id, name = "장수$id", nationId = nationId, cityId = 1, officerLevel = officerLevel, crew = 300, crewTypeId = 0)

    private fun harness(me: GeneralReadEntity, ops: List<OperationReadEntity>, units: List<OperationUnitReadEntity> = emptyList()): MockMvc {
        val resolver = mock(GeneralResolver::class.java)
        `when`(resolver.resolve(7L)).thenReturn(GeneralResolver.ResolvedGeneral(me, me.officerLevel, if (me.officerLevel >= 5) 2 else 0, me.nationId, 3))
        val generals = mock(GeneralReadRepository::class.java); `when`(generals.findById(anyInt())).thenReturn(Optional.of(me))
        val cities = mock(CityReadRepository::class.java); `when`(cities.findById(anyInt())).thenReturn(Optional.of(CityReadEntity(id = 2, name = "허창", nationId = 2)))
        val operations = mock(OperationReadRepository::class.java)
        `when`(operations.operationsOf(me.nationId)).thenReturn(ops)
        for (o in ops) `when`(operations.findById(o.id)).thenReturn(o)
        // Kotlin non-null 파라미터: 매처 등록 뒤 더미 값을 넘긴다(any() 는 null).
        `when`(operations.unitsOf(ArgumentMatchers.anyCollection() ?: emptyList())).thenReturn(units)
        val retinue = mock(RetainerReadRepository::class.java); `when`(retinue.bugoksOf(anyInt())).thenReturn(emptyList())
        val boards = mock(BoardPostReadRepository::class.java); `when`(boards.findByOperationIds(ArgumentMatchers.anyCollection() ?: emptyList())).thenReturn(emptyList())
        val worlds = mock(WorldStateReadRepository::class.java); `when`(worlds.findAll()).thenReturn(listOf(WorldStateReadEntity(id = 1, currentYear = 200, currentMonth = 3, currentPhase = 1)))
        val perms = mock(SecretPermissionReader::class.java)
        `when`(perms.of(ArgumentMatchers.any(GeneralResolver.ResolvedGeneral::class.java))).thenReturn(if (me.officerLevel >= 5) 2 else 0)
        return mvc(OperationController(resolver, generals, cities, operations, retinue, boards, worlds, perms))
    }

    private fun mvc(vararg controllers: Any): MockMvc = MockMvcBuilders.standaloneSetup(*controllers).setCustomArgumentResolvers(AuthenticationPrincipalArgumentResolver()).build()

    private fun op(id: Int, nationId: Int, status: String = "active") = OperationReadEntity(worldId = 1, id = id, nationId = nationId, kind = "capture_city", targetCityId = 2, title = "허창 공략",
        declaredYear = 200, declaredMonth = 2, declaredPhase = 1, deadlineYear = 200, deadlineMonth = 5, deadlinePhase = 1, status = status, departed = true)

    @Test
    fun `list is 401 anonymous, empty with rules for 재야, and full for a nation member`() {
        val m = harness(gen(10, 1), listOf(op(1, 1), op(2, 1, status = "failed")), listOf(OperationUnitReadEntity(worldId = 1, id = 1, operationId = 1, generalId = 10, role = "main", joinedCityId = 1)))
        m.perform(get("/api/operations")).andExpect(status().isUnauthorized)
        m.perform(get("/api/operations").with(principal(7L)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.myPermission").value(2))
            .andExpect(jsonPath("$.operations[0].title").value("허창 공략"))
            .andExpect(jsonPath("$.operations[0].remainingMonths").value(2))
            .andExpect(jsonPath("$.operations[0].milestoneDisplayPct").value(25))
            .andExpect(jsonPath("$.operations[0].units[0].roleLabel").value("본대"))
            .andExpect(jsonPath("$.operations[0].units[0].crewTypeName").value("-"))
            .andExpect(jsonPath("$.operations[1].remainingMonths").doesNotExist())
            .andExpect(jsonPath("$.rules.provisional").value(true))
            .andExpect(jsonPath("$.rules.kinds[3].declarable").value(false))
            .andExpect(jsonPath("$.rules.kinds[3].reason").value("아직 선언할 수 없는 작전 종류입니다."))
        val wanderer = harness(gen(20, 0, officerLevel = 1), emptyList())
        wanderer.perform(get("/api/operations").with(principal(7L))).andExpect(status().isOk).andExpect(jsonPath("$.nationId").value(0)).andExpect(jsonPath("$.operations").isEmpty).andExpect(jsonPath("$.rules.maxUnits").isNumber)
    }

    @Test
    fun `detail forbids other nations and returns own`() {
        val m = harness(gen(10, 1), listOf(op(1, 1), op(9, 2)))
        m.perform(get("/api/operations/1")).andExpect(status().isUnauthorized)
        m.perform(get("/api/operations/1").with(principal(7L))).andExpect(status().isOk).andExpect(jsonPath("$.operation.id").value(1))
        m.perform(get("/api/operations/9").with(principal(7L))).andExpect(status().isForbidden)
        m.perform(get("/api/operations/77").with(principal(7L))).andExpect(status().isNotFound)
    }
}
