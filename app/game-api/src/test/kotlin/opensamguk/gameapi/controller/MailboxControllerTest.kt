package opensamguk.gameapi.controller

import opensamguk.gameapi.owner.GeneralResolver
import opensamguk.gameapi.read.GeneralReadEntity
import opensamguk.infra.entity.MessageEntity
import opensamguk.infra.read.MessageRepository
import opensamguk.logic.message.Mailbox
import opensamguk.logic.message.MessageType
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyLong
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
 * W3 — [MailboxController] 슬라이스 테스트(MockMvc standalone + mocked 레포).
 *
 * 검증: message body jsonb의 `{src, dest, text, option}`이 srcTarget/destTarget(MsgTarget) + text +
 * option으로 디코드되고, raw message 문자열도 보존되며, body 디코드 실패/누락 시 graceful(null).
 * Identity is JWT principal → [GeneralResolver] (not first-playable fallback).
 */
class MailboxControllerTest {

    private val messages = mock(MessageRepository::class.java)
    private val resolver = mock(GeneralResolver::class.java)

    @AfterEach
    fun clearAuth() = SecurityContextHolder.clearContext()

    private fun mockMvc(): MockMvc =
        MockMvcBuilders.standaloneSetup(MailboxController(messages, resolver))
            .setCustomArgumentResolvers(AuthenticationPrincipalArgumentResolver())
            .build()

    private fun principal(userId: Long = 7L): RequestPostProcessor = RequestPostProcessor { req ->
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(userId, null, listOf(SimpleGrantedAuthority("ROLE_USER")))
        req
    }

    private fun stubResolved(general: GeneralReadEntity) {
        `when`(resolver.resolve(anyLong())).thenReturn(
            GeneralResolver.ResolvedGeneral(
                general = general,
                officerLevel = general.officerLevel,
                permission = GeneralResolver.derivePermission(general.officerLevel),
                nationId = general.nationId,
                nationLevel = 1,
            ),
        )
    }

    private fun message(id: Int, mailbox: Int, body: String) = MessageEntity(
        mailbox = mailbox,
        type = MessageType.PRIVATE,
        src = 42,
        dest = 7,
        time = Instant.parse("2026-06-01T00:00:00Z"),
        validUntil = Instant.parse("2026-06-01T00:05:00Z"),
        message = body,
        id = id,
    )

    @Test
    fun `mailbox decodes MsgTarget src dest, text and option from body jsonb`() {
        val body = """
            {"text":"안녕하시오",
             "src":{"id":42,"name":"조조","nation_id":1,"nation":"위","color":"#c62828","icon":"a.jpg"},
             "dest":{"id":7,"name":"손권","nation_id":2,"nation":"오","color":"#1565c0","icon":"b.jpg"},
             "option":{"deletable":true,"receiverMessageID":99}}
        """.trimIndent()
        `when`(messages.findByMailboxOrderById(100)).thenReturn(listOf(message(1, 100, body)))
        // mailbox() applies diplomacy masking via JWT → GeneralResolver → secretPermission.
        // officerLevel 12 → permission 4 (>=3) so masking is skipped; test target is decoding, not masking.
        stubResolved(me(id = 1, officerLevel = 12))

        mockMvc().perform(get("/api/mailbox/100").with(principal()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].id").value(1))
            .andExpect(jsonPath("$[0].src").value(42))   // int 라우팅 키 보존
            .andExpect(jsonPath("$[0].dest").value(7))
            .andExpect(jsonPath("$[0].text").value("안녕하시오"))
            // srcTarget MsgTarget
            .andExpect(jsonPath("$[0].srcTarget.id").value(42))
            .andExpect(jsonPath("$[0].srcTarget.name").value("조조"))
            .andExpect(jsonPath("$[0].srcTarget.nationId").value(1))
            .andExpect(jsonPath("$[0].srcTarget.nation").value("위"))
            .andExpect(jsonPath("$[0].srcTarget.color").value("#c62828"))
            .andExpect(jsonPath("$[0].srcTarget.icon").value("a.jpg"))
            // destTarget
            .andExpect(jsonPath("$[0].destTarget.name").value("손권"))
            .andExpect(jsonPath("$[0].destTarget.nationId").value(2))
            // option block
            .andExpect(jsonPath("$[0].option.deletable").value(true))
            .andExpect(jsonPath("$[0].option.receiverMessageID").value(99))
            // raw 보존
            .andExpect(jsonPath("$[0].message").exists())
    }

    @Test
    fun `message with empty body decodes to null targets gracefully`() {
        `when`(messages.findById(5)).thenReturn(Optional.of(message(5, 100, "{}")))

        mockMvc().perform(get("/api/messages/5"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(5))
            .andExpect(jsonPath("$.srcTarget").value(nullValue()))  // {} → src 키 없음 → JSON null
            .andExpect(jsonPath("$.text").value(nullValue()))
    }

    @Test
    fun `unknown message returns 404`() {
        `when`(messages.findById(999)).thenReturn(Optional.empty())
        mockMvc().perform(get("/api/messages/999"))
            .andExpect(status().isNotFound)
    }

    // ------------------------------------------------------------------
    // 바퀴 22 — diplomacy 마스킹 type 게이트 (legacy GetRecentMessage.php:125-139는
    // diplomacy 섹션 한정. 바퀴 18 회귀 = type 미검사로 일반 서신까지 위조 마스킹).
    // ------------------------------------------------------------------

    private val diploBody =
        """{"text":"불가침 제의","src":{"id":1,"nation_id":1},"dest":{"id":2,"nation_id":2},"option":{}}"""
    private val privateBody =
        """{"text":"개인 서신 원문","src":{"id":1,"nation_id":1},"dest":{"id":2,"nation_id":2},"option":{}}"""

    @Test
    fun `mailbox masks only diplomacy rows for permission below 3 - private and national stay verbatim`() {
        val rows = listOf(
            message(1, 100, privateBody),                                        // type PRIVATE
            msg(2, 100, MessageType.NATIONAL, privateBody),                      // type NATIONAL
            msg(3, 100, MessageType.DIPLOMACY, diploBody),                       // type DIPLOMACY
        )
        `when`(messages.findByMailboxOrderById(100)).thenReturn(rows)
        // officerLevel 1 → secretMin 0 → permission < 3.
        stubResolved(me(id = 1, officerLevel = 1))

        mockMvc().perform(get("/api/mailbox/100").with(principal()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].text").value("개인 서신 원문"))
            .andExpect(jsonPath("$[1].text").value("개인 서신 원문"))
            .andExpect(jsonPath("$[2].text").value("(외교 메시지입니다)"))
            .andExpect(jsonPath("$[2].option.invalid").value(true))
    }

    @Test
    fun `single message endpoint masks diplomacy for permission below 3`() {
        `when`(messages.findById(7)).thenReturn(Optional.of(msg(7, 100, MessageType.DIPLOMACY, diploBody)))
        stubResolved(me(id = 1, officerLevel = 1))

        mockMvc().perform(get("/api/messages/7").with(principal()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.text").value("(외교 메시지입니다)"))
            .andExpect(jsonPath("$.option.invalid").value(true))
    }

    @Test
    fun `single message endpoint keeps diplomacy verbatim for permission 3 plus`() {
        `when`(messages.findById(8)).thenReturn(Optional.of(msg(8, 100, MessageType.DIPLOMACY, diploBody)))
        // officerLevel 12 (군주) → permission 4.
        stubResolved(me(id = 1, officerLevel = 12))

        mockMvc().perform(get("/api/messages/8").with(principal()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.text").value("불가침 제의"))
    }

    @Test
    fun `single message endpoint keeps private verbatim even for permission below 3`() {
        `when`(messages.findById(9)).thenReturn(Optional.of(message(9, 100, privateBody)))
        stubResolved(me(id = 1, officerLevel = 1))

        mockMvc().perform(get("/api/messages/9").with(principal()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.text").value("개인 서신 원문"))
    }

    // ------------------------------------------------------------------
    // D7 GetRecentMessage — `/api/mailbox/recent`
    // PHP: GetRecentMessage.php:67-160 + Message::getMessagesFromMailBox (Message.php:170-193).
    // ------------------------------------------------------------------

    /** D7 fixture: a general resolved by currentGeneral() (npc_state<2 fallback). */
    private fun me(id: Int, name: String = "조조", nationId: Int = 1, officerLevel: Int = 5) =
        GeneralReadEntity(
            id = id, name = name, nationId = nationId, officerLevel = officerLevel, npcState = 0,
        )

    private fun msg(
        id: Int, mailbox: Int, type: MessageType, body: String,
        time: Instant = Instant.parse("2026-06-01T00:00:00Z"),
        validUntil: Instant = Instant.parse("2999-12-31T00:00:00Z"),
    ) = MessageEntity(
        mailbox = mailbox, type = type, src = 1, dest = 2, time = time, validUntil = validUntil,
        message = body, id = id,
    )

    /**
     * Wire all four section repo calls; default empty unless overridden.
     *
     * Mockito(no mockito-kotlin)에서 non-null Instant/MessageType에 `any()`+`eq()` 혼용이 NPE를
     * 일으키므로, 단일 `thenAnswer`로 (mailbox,type) 인자에 맞춰 디스패치한다.
     */
    /**
     * Mockito(no mockito-kotlin) — `ArgumentMatchers.any()`/`any(Class)`는 non-null Kotlin 파라미터에
     * null을 반환해 호출지점에서 NPE를 일으킨다. 매처는 스택에 등록하되, 비-null 폴백값을 반환해
     * Kotlin의 non-null 검사를 통과시킨다. (등록 순서는 인자 순서와 일치해야 한다.)
     */
    private fun <T : Any> anyNonNull(fallback: T): T {
        any<T>()
        return fallback
    }

    private fun stubRecent(
        general: GeneralReadEntity,
        private: List<MessageEntity> = emptyList(),
        public: List<MessageEntity> = emptyList(),
        national: List<MessageEntity> = emptyList(),
        diplomacy: List<MessageEntity> = emptyList(),
    ) {
        stubResolved(general)
        val natBox = Mailbox.NATIONAL_BASE + general.nationId
        `when`(
            messages.findByMailboxAndTypeAndValidUntilAfterOrderByIdDesc(
                anyInt(), anyNonNull(MessageType.PRIVATE), anyNonNull(Instant.EPOCH),
            ),
        ).thenAnswer { inv ->
            val mailbox = inv.getArgument<Int>(0)
            val type = inv.getArgument<MessageType>(1)
            when {
                mailbox == general.id && type == MessageType.PRIVATE -> private
                mailbox == Mailbox.PUBLIC && type == MessageType.PUBLIC -> public
                mailbox == natBox && type == MessageType.NATIONAL -> national
                mailbox == natBox && type == MessageType.DIPLOMACY -> diplomacy
                else -> emptyList<MessageEntity>()
            }
        }
    }

    @Test
    fun `recent returns 4-section envelope with type value and msgType lowercase`() {
        val g = me(id = 10, nationId = 1, officerLevel = 12) // 군주 → permission 4 (마스킹 면제)
        stubRecent(
            general = g,
            private = listOf(msg(5, 10, MessageType.PRIVATE, """{"text":"개인","src":{"id":1},"dest":{"id":10}}""")),
            public = listOf(msg(4, Mailbox.PUBLIC, MessageType.PUBLIC, """{"text":"공개","src":{"id":1}}""")),
        )

        mockMvc().perform(get("/api/mailbox/recent").with(principal()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.result").value(true))
            .andExpect(jsonPath("$.nationID").value(1))
            .andExpect(jsonPath("$.generalName").value("조조"))
            // 봉투에 4섹션 키 존재
            .andExpect(jsonPath("$.private").exists())
            .andExpect(jsonPath("$.public").exists())
            .andExpect(jsonPath("$.national").exists())
            .andExpect(jsonPath("$.diplomacy").exists())
            // msgType = MessageType.value (소문자), not enum .name (PRIVATE)
            .andExpect(jsonPath("$.private[0].msgType").value("private"))
            .andExpect(jsonPath("$.public[0].msgType").value("public"))
            // public 메시지는 dest=null (Message::toArray)
            .andExpect(jsonPath("$.public[0].dest").value(nullValue()))
            // latestRead BLOCKED → 0,0
            .andExpect(jsonPath("$.latestRead.diplomacy").value(0))
            .andExpect(jsonPath("$.latestRead.private").value(0))
    }

    @Test
    fun `recent applies sequence cursor id-ge-sequence and keeps only matching rows`() {
        // 레포는 id DESC 전체를 반환; 컨트롤러가 sequence 커서(id>=sequence)를 take(15) 이전에 적용해야 한다.
        val g = me(id = 10, nationId = 1, officerLevel = 12)
        stubRecent(
            general = g,
            private = listOf(
                msg(9, 10, MessageType.PRIVATE, """{"text":"a","src":{"id":1},"dest":{"id":10}}"""),
                msg(7, 10, MessageType.PRIVATE, """{"text":"b","src":{"id":1},"dest":{"id":10}}"""),
                msg(5, 10, MessageType.PRIVATE, """{"text":"c","src":{"id":1},"dest":{"id":10}}"""),
                msg(3, 10, MessageType.PRIVATE, """{"text":"d","src":{"id":1},"dest":{"id":10}}"""),
            ),
        )

        // sequence=4: 커서 id>=4 → 9,7,5 (id 3 제외). minSequence=4라 보존행(9,7,5) 중 id<=4가 없어
        // array_pop 트림이 발동하지 않는다(트림은 다음 테스트에서 단독 검증). PHP: id>=fromSeq + id<=minSeq.
        mockMvc().perform(get("/api/mailbox/recent").param("sequence", "4").with(principal()))
            .andExpect(status().isOk)
            // id>=4 만: 9,7,5 (3건); id 3 제외
            .andExpect(jsonPath("$.private.length()").value(3))
            .andExpect(jsonPath("$.private[0].id").value(9))
            .andExpect(jsonPath("$.private[2].id").value(5))
            // nextSequence = max id = 9
            .andExpect(jsonPath("$.sequence").value(9))
    }

    @Test
    fun `recent running-min array_pop trims last section whose id reaches minSequence`() {
        // PHP: minSequence는 reqSequence로 시드되어 섹션을 가로질러 변이; 마지막으로 id<=minSequence가
        // 발생한 섹션의 마지막 항목을 array_pop. sequence=8: private[8] (id 8<=8) → lastType=private → trim.
        val g = me(id = 10, nationId = 1, officerLevel = 12)
        stubRecent(
            general = g,
            private = listOf(
                msg(8, 10, MessageType.PRIVATE, """{"text":"p1","src":{"id":1},"dest":{"id":10}}"""),
            ),
            public = listOf(
                msg(12, Mailbox.PUBLIC, MessageType.PUBLIC, """{"text":"pub","src":{"id":1}}"""),
            ),
        )

        mockMvc().perform(get("/api/mailbox/recent").param("sequence", "8").with(principal()))
            .andExpect(status().isOk)
            // private의 유일 항목(id 8 == minSequence 8)이 array_pop으로 제거됨
            .andExpect(jsonPath("$.private.length()").value(0))
            // public(id 12 > 8)은 minSequence 미도달 → 유지
            .andExpect(jsonPath("$.public.length()").value(1))
            // nextSequence = max(8,12) = 12
            .andExpect(jsonPath("$.sequence").value(12))
    }

    @Test
    fun `recent masks diplomacy text and sets option invalid when permission below 3 and dest nation nonzero`() {
        // officer_level 5, no ambassador/auditor → secretMin=2 < 3 → 마스킹. dest.nation_id != 0.
        val g = me(id = 10, nationId = 1, officerLevel = 5)
        stubRecent(
            general = g,
            diplomacy = listOf(
                msg(
                    20, Mailbox.NATIONAL_BASE + 1, MessageType.DIPLOMACY,
                    """{"text":"비밀외교","src":{"id":1,"nation_id":1},"dest":{"id":2,"nation_id":2},"option":{}}""",
                ),
            ),
        )

        mockMvc().perform(get("/api/mailbox/recent").with(principal()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.diplomacy[0].text").value("(외교 메시지입니다)"))
            .andExpect(jsonPath("$.diplomacy[0].option.invalid").value(true))
    }

    @Test
    fun `recent does not mask diplomacy when permission 3 or higher`() {
        // auditor → secretMin=3, permission==3 → 마스킹 면제(PHP: permission < 3 일 때만).
        val g = me(id = 10, nationId = 1, officerLevel = 5)
            .apply { meta = linkedMapOf("permission" to "auditor") }
        stubRecent(
            general = g,
            diplomacy = listOf(
                msg(
                    20, Mailbox.NATIONAL_BASE + 1, MessageType.DIPLOMACY,
                    """{"text":"열람가능","src":{"id":1,"nation_id":1},"dest":{"id":2,"nation_id":2},"option":{}}""",
                ),
            ),
        )

        mockMvc().perform(get("/api/mailbox/recent").with(principal()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.diplomacy[0].text").value("열람가능"))
    }

    @Test
    fun `recent does not mask diplomacy broadcast where dest nation is zero`() {
        // dest.nation_id == 0 (전체 외교 공지) → 권한 무관 마스킹 안 함.
        val g = me(id = 10, nationId = 1, officerLevel = 5)
        stubRecent(
            general = g,
            diplomacy = listOf(
                msg(
                    20, Mailbox.NATIONAL_BASE + 1, MessageType.DIPLOMACY,
                    """{"text":"전체공지","src":{"id":1,"nation_id":1},"dest":{"id":0,"nation_id":0},"option":{}}""",
                ),
            ),
        )

        mockMvc().perform(get("/api/mailbox/recent").with(principal()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.diplomacy[0].text").value("전체공지"))
    }

    @Test
    fun `recent without principal returns empty envelope (no first-playable fallback)`() {
        mockMvc().perform(get("/api/mailbox/recent"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.result").value(false))
            .andExpect(jsonPath("$.generalName").value(""))
            .andExpect(jsonPath("$.nationID").value(0))
    }
}
