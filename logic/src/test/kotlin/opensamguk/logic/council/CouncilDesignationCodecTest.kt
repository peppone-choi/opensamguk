package opensamguk.logic.council

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CouncilDesignationCodecTest {
    private val row = CouncilDesignation("grant-1", 3, 101, "ruler-receipt", 102, "grant-1")

    @Test
    fun `지정과 회수 영수증을 왕복하고 과거 지정 이력을 보존한다`() {
        val state = CouncilDesignationState("revoke-1", listOf(row.copy(revision = "revoke-1", revokedByRequestId = "revoke-1")))
        assertEquals(state, CouncilDesignationCodec.decode(CouncilDesignationCodec.encode(state)))
        assertEquals("grant-1", state.grants.single().id)
    }

    @Test
    fun `미시드 원문과 정상 빈 상태를 구분하고 다른 버전은 거절한다`() {
        assertNull(CouncilDesignationCodec.decode(null))
        val empty = CouncilDesignationState("seed-receipt", emptyList())
        assertEquals(empty, CouncilDesignationCodec.decode(CouncilDesignationCodec.encode(empty)))
        assertThrows(IllegalArgumentException::class.java) {
            CouncilDesignationCodec.decode(CouncilDesignationCodec.encode(empty).replace("\"version\":1", "\"version\":2"))
        }
    }

    @Test
    fun `같은 세력 대상의 중복 활성 지정과 중복 영수증은 저장할 수 없다`() {
        assertThrows(IllegalArgumentException::class.java) { CouncilDesignationState("receipt", listOf(row, row)) }
        assertThrows(IllegalArgumentException::class.java) { CouncilDesignationState("receipt", listOf(row, row.copy(id = "grant-2"))) }
        val ended = row.copy(revokedByRequestId = "revoke-1", revision = "revoke-1")
        assertEquals(2, CouncilDesignationState("grant-2", listOf(ended, row.copy(id = "grant-2"))).grants.size)
    }

    @Test
    fun `문자열 숫자 권한 주입과 null 영수증을 정상 이력으로 바꾸지 않는다`() {
        val raw = CouncilDesignationCodec.encode(CouncilDesignationState("grant-1", listOf(row)))
        listOf(raw.replace("\"version\":1", "\"version\":\"1\""),
            raw.replace("\"nationId\":3", "\"nationId\":\"3\""),
            raw.replace("\"revision\":\"grant-1\"", "\"revision\":null"),
            raw.replace("\"targetGeneralId\":102", "\"targetGeneralId\":102,\"permission\":2")).forEach { corrupt ->
            assertThrows(IllegalArgumentException::class.java) { CouncilDesignationCodec.decode(corrupt) }
        }
    }
}
