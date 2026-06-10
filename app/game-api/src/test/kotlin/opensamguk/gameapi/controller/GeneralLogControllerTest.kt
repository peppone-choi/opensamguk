package opensamguk.gameapi.controller

import opensamguk.gameapi.owner.GeneralOwnerEntity
import opensamguk.gameapi.owner.GeneralOwnerRepository
import opensamguk.gameapi.owner.GeneralResolver
import opensamguk.gameapi.read.AdminGeneralLogReadRepository
import opensamguk.gameapi.read.GeneralReadEntity
import opensamguk.gameapi.read.GeneralReadRepository
import opensamguk.gameapi.read.NationReadRepository
import opensamguk.gameapi.read.WorldLogReadEntity
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
import java.util.Optional
import kotlin.test.assertTrue

/**
 * Nation/GetGeneralLog 포팅 (page-parity 루프 1바퀴) — PHP 정본:
 * `legacy/devsam-core/hwe/sammo/API/Nation/GetGeneralLog.php` (General/GetGeneralLog는 이를 상속한 alias).
 *
 * 권한 사슬 (checkPermission, PHP :60-92 — 메시지 byte 그대로):
 *  1. checkSecretPermission < 0  → '국가에 소속되어있지 않습니다.'
 *  2. permission < 1             → '권한이 부족합니다. 수뇌부가 아니거나 사관년도가 부족합니다.'
 *  3. 타깃이 같은 나라 아님       → '같은 나라의 장수가 아닙니다.'
 *     (PHP는 타깃 미존재 시에도 null !== nationID 비교로 이 메시지에 떨어진다)
 *  4. generalAction && 타깃 npc<2 && 타깃≠본인 && permission<2
 *                                → '권한이 부족합니다. 유저 장수의 개인 기록은 수뇌만 열람 가능합니다.'
 *
 * 응답 (launch, PHP :120-168): {result:true, reqType, generalID, log:{id:text,...}} —
 * log는 `Util::convertPairArrayToDict`(id desc 삽입 순서 보존 dict). 리더 SQL은
 * func_history.php:136-279 (recent=ORDER BY id DESC LIMIT 30, more=AND id < reqTo).
 * log_type→log_entry category 매핑은 AdminGeneralLogReadRepository 문서의 엔진 정본
 * (action→ACTION, battle_brief→BATTLE_BRIEF, battle→BATTLE_DETAIL, history→HISTORY).
 */
class GeneralLogControllerTest {

    private val owners = mock(GeneralOwnerRepository::class.java)
    private val generals = mock(GeneralReadRepository::class.java)
    private val nations = mock(NationReadRepository::class.java)
    private val resolver = GeneralResolver(owners, generals, nations)
    private val logs = mock(AdminGeneralLogReadRepository::class.java)

    private fun mvc(): MockMvc =
        MockMvcBuilders.standaloneSetup(GeneralLogController(resolver, generals, logs))
            .setCustomArgumentResolvers(AuthenticationPrincipalArgumentResolver())
            .build()

    private fun principal(userId: Long): RequestPostProcessor = RequestPostProcessor { req ->
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(userId, null, listOf(SimpleGrantedAuthority("ROLE_USER")))
        req
    }

    private fun gen(id: Int, name: String, nationId: Int, officerLevel: Int, npcState: Int = 0) =
        GeneralReadEntity(id = id, name = name, nationId = nationId, officerLevel = officerLevel, npcState = npcState)

    /** userId가 generalId 장수를 소유 + 그 장수 row를 양쪽 repo에 심는다. */
    private fun own(userId: Long, me: GeneralReadEntity) {
        `when`(owners.findByUserId(userId)).thenReturn(GeneralOwnerEntity(generalId = me.id.toLong(), userId = userId))
        `when`(generals.findById(me.id)).thenReturn(Optional.of(me))
    }

    private fun row(id: Int, text: String) = WorldLogReadEntity(id = id, year = 181, month = 1, text = text)

    @Test
    fun `재야는 국가 소속 아님 에러`() {
        own(10L, gen(1, "방랑", nationId = 0, officerLevel = 0))
        mvc().perform(get("/api/general-log").param("generalID", "2").param("reqType", "generalHistory").with(principal(10L)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.result").value(false))
            .andExpect(jsonPath("$.reason").value("국가에 소속되어있지 않습니다."))
    }

    @Test
    fun `일반 장수는 수뇌부 권한 부족 에러`() {
        // officer_level=1(일반) → secretPermission 0 → PHP permission<1 분기.
        own(10L, gen(1, "졸병", nationId = 1, officerLevel = 1))
        mvc().perform(get("/api/general-log").param("generalID", "2").param("reqType", "generalHistory").with(principal(10L)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.result").value(false))
            .andExpect(jsonPath("$.reason").value("권한이 부족합니다. 수뇌부가 아니거나 사관년도가 부족합니다."))
    }

    @Test
    fun `타국 장수 조회는 거부`() {
        own(10L, gen(1, "군주", nationId = 1, officerLevel = 12))
        `when`(generals.findById(2)).thenReturn(Optional.of(gen(2, "타국인", nationId = 9, officerLevel = 1)))
        mvc().perform(get("/api/general-log").param("generalID", "2").param("reqType", "generalHistory").with(principal(10L)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.result").value(false))
            .andExpect(jsonPath("$.reason").value("같은 나라의 장수가 아닙니다."))
    }

    @Test
    fun `유저 장수 개인 기록은 수뇌 미만이면 거부`() {
        // officer_level=2(관직자) → permission 1. 타깃 npc<2 유저 장수의 generalAction → 수뇌(2) 필요.
        own(10L, gen(1, "관직자", nationId = 1, officerLevel = 2))
        `when`(generals.findById(2)).thenReturn(Optional.of(gen(2, "유저장수", nationId = 1, officerLevel = 1, npcState = 0)))
        mvc().perform(get("/api/general-log").param("generalID", "2").param("reqType", "generalAction").with(principal(10L)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.result").value(false))
            .andExpect(jsonPath("$.reason").value("권한이 부족합니다. 유저 장수의 개인 기록은 수뇌만 열람 가능합니다."))
    }

    @Test
    fun `본인 개인 기록은 수뇌 미만이어도 허용`() {
        // PHP :85 — generalID === me['no']이면 npc<2 유저 장수라도 개인 기록 열람 허용.
        own(10L, gen(1, "관직자", nationId = 1, officerLevel = 2, npcState = 0))
        `when`(generals.findById(1)).thenReturn(Optional.of(gen(1, "관직자", nationId = 1, officerLevel = 2, npcState = 0)))
        `when`(logs.findRecentByGeneral(1, "ACTION", 30)).thenReturn(listOf(row(3, "내 기록")))

        mvc().perform(get("/api/general-log").param("generalID", "1").param("reqType", "generalAction").with(principal(10L)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.result").value(true))
    }

    @Test
    fun `npc 장수 개인 기록은 수뇌 미만이어도 허용`() {
        // PHP :83 — 타깃 npc>=2면 permission<2 게이트가 발화하지 않는다.
        own(10L, gen(1, "관직자", nationId = 1, officerLevel = 2, npcState = 0))
        `when`(generals.findById(2)).thenReturn(Optional.of(gen(2, "NPC장수", nationId = 1, officerLevel = 1, npcState = 2)))
        `when`(logs.findRecentByGeneral(2, "ACTION", 30)).thenReturn(listOf(row(6, "NPC 기록")))

        mvc().perform(get("/api/general-log").param("generalID", "2").param("reqType", "generalAction").with(principal(10L)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.result").value(true))
    }

    @Test
    fun `generalAction recent는 id desc dict로 반환`() {
        own(10L, gen(1, "군주", nationId = 1, officerLevel = 12))
        `when`(generals.findById(2)).thenReturn(Optional.of(gen(2, "동료", nationId = 1, officerLevel = 1, npcState = 2)))
        `when`(logs.findRecentByGeneral(2, "ACTION", 30)).thenReturn(listOf(row(7, "둘째 기록"), row(5, "첫 기록")))

        val body = mvc().perform(get("/api/general-log").param("generalID", "2").param("reqType", "generalAction").with(principal(10L)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.result").value(true))
            .andExpect(jsonPath("$.reqType").value("generalAction"))
            .andExpect(jsonPath("$.generalID").value(2))
            .andReturn().response.contentAsString
        // PHP convertPairArrayToDict — id desc 삽입 순서가 JSON 텍스트에 보존된다.
        assertTrue(body.contains("\"log\":{\"7\":\"둘째 기록\",\"5\":\"첫 기록\"}"), body)
    }

    @Test
    fun `reqTo가 있으면 more 페이지네이션 쿼리를 쓴다`() {
        own(10L, gen(1, "군주", nationId = 1, officerLevel = 12))
        `when`(generals.findById(2)).thenReturn(Optional.of(gen(2, "동료", nationId = 1, officerLevel = 1, npcState = 2)))
        `when`(logs.findRecentByGeneralBefore(2, "BATTLE_BRIEF", 5, 30)).thenReturn(listOf(row(4, "이전 전투")))

        val body = mvc().perform(
            get("/api/general-log").param("generalID", "2").param("reqType", "battleResult").param("reqTo", "5").with(principal(10L)),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.result").value(true))
            .andReturn().response.contentAsString
        assertTrue(body.contains("\"log\":{\"4\":\"이전 전투\"}"), body)
    }

    @Test
    fun `generalHistory는 전량 반환`() {
        own(10L, gen(1, "군주", nationId = 1, officerLevel = 12))
        `when`(generals.findById(2)).thenReturn(Optional.of(gen(2, "동료", nationId = 1, officerLevel = 1, npcState = 2)))
        `when`(logs.findAllHistoryByGeneral(2)).thenReturn(listOf(row(9, "열전 둘"), row(3, "열전 하나")))

        val body = mvc().perform(get("/api/general-log").param("generalID", "2").param("reqType", "generalHistory").with(principal(10L)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.result").value(true))
            .andReturn().response.contentAsString
        assertTrue(body.contains("\"log\":{\"9\":\"열전 둘\",\"3\":\"열전 하나\"}"), body)
    }

    @Test
    fun `battleDetail은 BATTLE_DETAIL 카테고리를 읽는다`() {
        own(10L, gen(1, "군주", nationId = 1, officerLevel = 12))
        `when`(generals.findById(2)).thenReturn(Optional.of(gen(2, "동료", nationId = 1, officerLevel = 1, npcState = 2)))
        `when`(logs.findRecentByGeneral(2, "BATTLE_DETAIL", 30)).thenReturn(listOf(row(8, "전투 상세")))

        val body = mvc().perform(get("/api/general-log").param("generalID", "2").param("reqType", "battleDetail").with(principal(10L)))
            .andExpect(status().isOk)
            .andReturn().response.contentAsString
        assertTrue(body.contains("\"log\":{\"8\":\"전투 상세\"}"), body)
    }
}
