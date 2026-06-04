package opensamguk.gameapi.controller

import opensamguk.gameapi.read.GeneralReadEntity
import opensamguk.gameapi.read.GeneralReadRepository
import opensamguk.gameapi.reserve.CommandReserveService
import opensamguk.infra.entity.MessageEntity
import opensamguk.infra.read.MessageRepository
import opensamguk.logic.message.MessageType
import opensamguk.logic.util.jsonDecodeAny
import opensamguk.logic.util.jsonEncode
import org.junit.jupiter.api.Test
import org.mockito.Mockito.any
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.time.Instant
import java.util.Optional
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Slice test for [DiplomaticMessageController] — MockMvc standalone over a mocked [MessageRepository]
 * + [GeneralReadRepository] + a hand-rolled recording [CommandReserveService] mock (NO Spring context,
 * NO Testcontainers).
 *
 * 검증 핵심:
 *  1. **방향(orientation)**: A국(제의국, message.src.nation_id)이 B국(수신국, message.dest.nation_id)에
 *     제의했을 때 B가 수락하면, 예약되는 argJson의 destNationID는 **제의국 A**여야 한다 (dest=B가 아님).
 *  2. **destGeneralID(FIX #1)**: 모든 수락 케이스의 argJson에 destGeneralID = **제의자 장수 id**(=message.src.id)가
 *     실려야 한다 (PHP che_*수락 argTest 필수 — 누락·<=0·==self 거부).
 *  3. 불가침 수락은 year/month까지 argJson에 실린다.
 *  4. 종전/불가침 파기 수락은 올바른 명령 키 + destNationID(=제의국)로 예약된다.
 *  5. **소유권/유효성 가드(FIX #4, #5)**: 만료된 서신·타국 장수 수락·잘못된 year/month는 400으로 거부하며
 *     어떤 명령도 예약하지 않는다.
 *  6. 거절(decline)은 (소유권 통과 후) 어떤 명령도 예약하지 않고 메시지 invalidate만 수행한다.
 *
 * (Mockito captor가 Kotlin 비널 primitive Int에서 NPE를 내므로, reserve 호출 인자는 `thenAnswer`로
 *  직접 캡처한다.)
 */
class DiplomaticMessageControllerTest {

    /** 헬퍼 메시지가 본문 src.id로 박는 제의자 장수 id(= che_*수락 argTest의 destGeneralID). */
    private val proposerGeneralId = 100

    /** reserve() 한 번의 호출 인자 기록. */
    private data class ReserveCall(val generalId: Int, val actionCode: String, val turnIdx: Int, val argJson: String?)

    private val messageRepository = mock(MessageRepository::class.java)
    private val generalReadRepository = mock(GeneralReadRepository::class.java)
    private val reserve = mock(CommandReserveService::class.java)
    private val reserveCalls = mutableListOf<ReserveCall>()

    private fun mockMvc(): MockMvc {
        // reserve() 호출 인자를 직접 캡처(captor의 primitive NPE 회피).
        `when`(reserve.reserve(anyInt(), any() ?: "", anyInt(), any())).thenAnswer { inv ->
            reserveCalls.add(
                ReserveCall(
                    generalId = inv.getArgument(0),
                    actionCode = inv.getArgument(1),
                    turnIdx = inv.getArgument(2),
                    argJson = inv.getArgument(3),
                ),
            )
            CommandReserveService.ReserveResult(requestId = "test-req", turnIdx = inv.getArgument(2))
        }
        return MockMvcBuilders.standaloneSetup(
            DiplomaticMessageController(messageRepository, reserve, generalReadRepository),
        ).build()
    }

    /** 수락 장수(generalId)가 nation 국가 소속이라고 read repo가 응답하도록 스텁한다. */
    private fun stubActingGeneral(generalId: Int, nationId: Int) {
        val entity = GeneralReadEntity(id = generalId, nationId = nationId)
        `when`(generalReadRepository.findById(generalId)).thenReturn(Optional.of(entity))
    }

    /**
     * 제의국 A(=proposerNationId)가 수신국 B(=destNationId)에 보낸 외교 제의 메시지 본문을 만든다.
     * 엔진이 INSERT하는 receiver row의 byte-faithful jsonb 형태 `{src, dest, text, option}`를 재현한다 —
     * src=제의국, dest=수신국, option은 평탄(flat) {action[, year, month]}.
     */
    private fun diplomaticMessage(
        id: Int,
        proposerNationId: Int,
        destNationId: Int,
        action: String,
        year: Int? = null,
        month: Int? = null,
        expired: Boolean = false,
    ): MessageEntity {
        val option = linkedMapOf<String, Any?>("action" to action)
        if (year != null) option["year"] = year
        if (month != null) option["month"] = month
        val body = linkedMapOf<String, Any?>(
            "src" to linkedMapOf(
                "id" to proposerGeneralId, "name" to "제의장수",
                "nation_id" to proposerNationId, "nation" to "제의국", "color" to "#c62828", "icon" to "",
            ),
            "dest" to linkedMapOf(
                "id" to 0, "name" to "",
                "nation_id" to destNationId, "nation" to "수신국", "color" to "#1565c0", "icon" to "",
            ),
            "text" to "불가침 제의 서신",
            "option" to option,
        )
        val validUntil =
            if (expired) Instant.now().minusSeconds(3600) else Instant.now().plusSeconds(3600)
        return MessageEntity(
            mailbox = destNationId + 9000,
            type = MessageType.DIPLOMACY,
            src = proposerNationId + 9000,
            dest = destNationId + 9000,
            time = Instant.now(),
            validUntil = validUntil,
            message = jsonEncode(body),
            id = id,
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun decodeArgs(json: String?): Map<String, Any?> = jsonDecodeAny(json) as Map<String, Any?>

    @Test
    fun `accepting a non-aggression proposal reserves che_불가침수락 with proposer nation and proposer general as dest ids`() {
        // A=3국이 B=7국(수락국)에 불가침 제의 → B가 수락. destNationID는 제의국 3이어야 함(7이 아님).
        val msg = diplomaticMessage(
            id = 42, proposerNationId = 3, destNationId = 7,
            action = "no_aggression", year = 2026, month = 5,
        )
        `when`(messageRepository.findById(42)).thenReturn(Optional.of(msg))
        // 수락하는 장수(B국 수뇌) generalId = 55, B=7국 소속.
        stubActingGeneral(generalId = 55, nationId = 7)

        mockMvc().perform(post("/api/messages/{id}/accept", 42).param("generalId", "55"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("accepted"))
            .andExpect(jsonPath("$.commandKey").value("che_불가침수락"))

        assertEquals(1, reserveCalls.size)
        val call = reserveCalls.single()
        assertEquals(55, call.generalId)
        assertEquals("che_불가침수락", call.actionCode)
        // PHP getPreReqTurn()/getPostReqTurn() == 0 → 즉시 슬롯 ⇒ turnIdx 0 (제의 인테이크와 동일 기본값).
        assertEquals(0, call.turnIdx)

        val args = decodeArgs(call.argJson)
        // 방향 검증: destNationID = 제의국 3 (수락국 7이 아님).
        assertEquals(3, (args["destNationID"] as Number).toInt())
        // FIX #1: destGeneralID = 제의자 장수 id(=100).
        assertEquals(proposerGeneralId, (args["destGeneralID"] as Number).toInt())
        assertEquals(2026, (args["year"] as Number).toInt())
        assertEquals(5, (args["month"] as Number).toInt())

        // 메시지 invalidate 확인.
        verify(messageRepository).save(msg)
    }

    @Test
    fun `accepting a stop-war proposal reserves che_종전수락 with proposer nation and general and no year month`() {
        // A=2국이 B=9국에 종전 제의 → B 수락. destNationID = 제의국 2.
        val msg = diplomaticMessage(id = 11, proposerNationId = 2, destNationId = 9, action = "stop_war")
        `when`(messageRepository.findById(11)).thenReturn(Optional.of(msg))
        stubActingGeneral(generalId = 30, nationId = 9)

        mockMvc().perform(post("/api/messages/{id}/accept", 11).param("generalId", "30"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.commandKey").value("che_종전수락"))

        val call = reserveCalls.single()
        assertEquals("che_종전수락", call.actionCode)
        val args = decodeArgs(call.argJson)
        assertEquals(2, (args["destNationID"] as Number).toInt())
        // FIX #1: destGeneralID = 제의자 장수 id(=100).
        assertEquals(proposerGeneralId, (args["destGeneralID"] as Number).toInt())
        // 종전 수락은 year/month 불필요.
        assertNull(args["year"])
        assertNull(args["month"])
    }

    @Test
    fun `accepting a cancel-na proposal reserves che_불가침파기수락 with proposer nation and general`() {
        // A=5국이 B=1국에 불가침 파기 제의 → B 수락. destNationID = 제의국 5.
        val msg = diplomaticMessage(id = 77, proposerNationId = 5, destNationId = 1, action = "cancel_na")
        `when`(messageRepository.findById(77)).thenReturn(Optional.of(msg))
        stubActingGeneral(generalId = 12, nationId = 1)

        mockMvc().perform(post("/api/messages/{id}/accept", 77).param("generalId", "12"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.commandKey").value("che_불가침파기수락"))

        val call = reserveCalls.single()
        assertEquals("che_불가침파기수락", call.actionCode)
        val args = decodeArgs(call.argJson)
        assertEquals(5, (args["destNationID"] as Number).toInt())
        // FIX #1: destGeneralID = 제의자 장수 id(=100).
        assertEquals(proposerGeneralId, (args["destGeneralID"] as Number).toInt())
    }

    @Test
    fun `declining a proposal reserves nothing and only invalidates the message`() {
        val msg = diplomaticMessage(
            id = 99, proposerNationId = 3, destNationId = 7,
            action = "no_aggression", year = 2026, month = 5,
        )
        `when`(messageRepository.findById(99)).thenReturn(Optional.of(msg))
        stubActingGeneral(generalId = 55, nationId = 7)

        mockMvc().perform(post("/api/messages/{id}/decline", 99).param("generalId", "55"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("declined"))

        // 거절은 어떤 명령도 예약하지 않음 — 외교 상태 변화 없음(PHP declineMessage 동일).
        assertTrue(reserveCalls.isEmpty())
        // 메시지는 invalidate 됨.
        verify(messageRepository).save(msg)
    }

    @Test
    fun `accept returns 404 for unknown message id`() {
        `when`(messageRepository.findById(1234)).thenReturn(Optional.empty())

        mockMvc().perform(post("/api/messages/{id}/accept", 1234).param("generalId", "1"))
            .andExpect(status().isNotFound)

        assertTrue(reserveCalls.isEmpty())
    }

    @Test
    fun `accept rejects an expired message with 400 and reserves nothing`() {
        // FIX #4(a): 이미 만료(valid_until <= now)된 서신 → 재생·이중수락 차단.
        val msg = diplomaticMessage(
            id = 50, proposerNationId = 3, destNationId = 7,
            action = "no_aggression", year = 2026, month = 5, expired = true,
        )
        `when`(messageRepository.findById(50)).thenReturn(Optional.of(msg))
        stubActingGeneral(generalId = 55, nationId = 7)

        mockMvc().perform(post("/api/messages/{id}/accept", 50).param("generalId", "55"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("유효하지 않은 외교서신입니다."))

        // 예약 없음 + 메시지 재저장(invalidate) 없음.
        assertTrue(reserveCalls.isEmpty())
        verify(messageRepository, never()).save(msg)
    }

    @Test
    fun `accept rejects a general from the wrong nation with 400 and reserves nothing`() {
        // FIX #4(b): 수락 장수가 수신국(B=7)이 아닌 타국(99) 소속 → 우편함 소유권 위반.
        val msg = diplomaticMessage(
            id = 60, proposerNationId = 3, destNationId = 7,
            action = "no_aggression", year = 2026, month = 5,
        )
        `when`(messageRepository.findById(60)).thenReturn(Optional.of(msg))
        // 수락 장수 55가 엉뚱한 99국 소속이라고 read repo가 응답.
        stubActingGeneral(generalId = 55, nationId = 99)

        mockMvc().perform(post("/api/messages/{id}/accept", 60).param("generalId", "55"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("해당 국가의 외교서신을 처리할 권한이 없습니다."))

        assertTrue(reserveCalls.isEmpty())
        verify(messageRepository, never()).save(msg)
    }

    @Test
    fun `accept rejects when acting general is unknown with 400 and reserves nothing`() {
        // FIX #4(b): read repo가 장수를 찾지 못함(소속 미상) → 권한 거부.
        val msg = diplomaticMessage(
            id = 61, proposerNationId = 3, destNationId = 7,
            action = "no_aggression", year = 2026, month = 5,
        )
        `when`(messageRepository.findById(61)).thenReturn(Optional.of(msg))
        `when`(generalReadRepository.findById(55)).thenReturn(Optional.empty())

        mockMvc().perform(post("/api/messages/{id}/accept", 61).param("generalId", "55"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("해당 국가의 외교서신을 처리할 권한이 없습니다."))

        assertTrue(reserveCalls.isEmpty())
        verify(messageRepository, never()).save(msg)
    }

    @Test
    fun `accept rejects a non-aggression proposal with malformed month with 400 and reserves nothing`() {
        // FIX #5: month=13 (1..12 범위 밖) → 예약 전 거부.
        val msg = diplomaticMessage(
            id = 70, proposerNationId = 3, destNationId = 7,
            action = "no_aggression", year = 2026, month = 13,
        )
        `when`(messageRepository.findById(70)).thenReturn(Optional.of(msg))
        stubActingGeneral(generalId = 55, nationId = 7)

        mockMvc().perform(post("/api/messages/{id}/accept", 70).param("generalId", "55"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("유효하지 않은 외교서신입니다."))

        assertTrue(reserveCalls.isEmpty())
        verify(messageRepository, never()).save(msg)
    }

    @Test
    fun `accept rejects a non-aggression proposal missing month with 400 and reserves nothing`() {
        // FIX #5: month 누락 → 예약 전 거부.
        val msg = diplomaticMessage(
            id = 71, proposerNationId = 3, destNationId = 7,
            action = "no_aggression", year = 2026, month = null,
        )
        `when`(messageRepository.findById(71)).thenReturn(Optional.of(msg))
        stubActingGeneral(generalId = 55, nationId = 7)

        mockMvc().perform(post("/api/messages/{id}/accept", 71).param("generalId", "55"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("유효하지 않은 외교서신입니다."))

        assertTrue(reserveCalls.isEmpty())
        verify(messageRepository, never()).save(msg)
    }

    @Test
    fun `accept rejects when proposer general id equals the acting general with 400 and reserves nothing`() {
        // FIX #1: destGeneralID == self 거부(자기수락 가드). 수락 장수 id를 제의자 장수 id(100)와 동일하게.
        val msg = diplomaticMessage(
            id = 80, proposerNationId = 3, destNationId = 7,
            action = "stop_war",
        )
        `when`(messageRepository.findById(80)).thenReturn(Optional.of(msg))
        stubActingGeneral(generalId = proposerGeneralId, nationId = 7)

        mockMvc().perform(post("/api/messages/{id}/accept", 80).param("generalId", proposerGeneralId.toString()))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("유효하지 않은 외교서신입니다."))

        assertTrue(reserveCalls.isEmpty())
        verify(messageRepository, never()).save(msg)
    }

    @Test
    fun `decline rejects a general from the wrong nation with 400`() {
        // FIX #4(b): 거절에도 동일한 우편함 소유권 가드.
        val msg = diplomaticMessage(
            id = 90, proposerNationId = 3, destNationId = 7,
            action = "no_aggression", year = 2026, month = 5,
        )
        `when`(messageRepository.findById(90)).thenReturn(Optional.of(msg))
        stubActingGeneral(generalId = 55, nationId = 99)

        mockMvc().perform(post("/api/messages/{id}/decline", 90).param("generalId", "55"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("해당 국가의 외교서신을 처리할 권한이 없습니다."))

        assertTrue(reserveCalls.isEmpty())
        verify(messageRepository, never()).save(msg)
    }
}
