package opensamguk.logic.council

import opensamguk.logic.input.PoliticalInput
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CouncilAuthorityProjectorTest {
    private val people = listOf(CouncilAuthorityPerson(10, 1, 0, mapOf("lord" to true)),
        CouncilAuthorityPerson(11, 1, 0, mapOf("lord" to true)), CouncilAuthorityPerson(12, 1, 0, emptyMap()))
    private val meta = CurrentRulerBinding.with(emptyMap(), 10, "ruler-1", PoliticalInput.RISE)
    private val missing = CouncilDesignationSnapshot(CouncilSourceState.NOT_SEEDED)
    private val unavailableVassals = CouncilVassalSnapshot(CouncilSourceState.UNAVAILABLE)
    private fun state(vararg grants: CouncilDesignation) = CouncilDesignationSnapshot(CouncilSourceState.AVAILABLE,
        CouncilDesignationState("designation-1", grants.toList()))
    private fun grant(issuer: Int = 10, revision: String = "ruler-1", target: Int = 12, revoked: String? = null) =
        CouncilDesignation("grant-1", 1, issuer, revision, target, "grant-1", revoked)

    @Test fun `주공 표지와 높은 직함만으로 군주나 봉신 기밀 권한을 합성하지 않는다`() {
        val result = CouncilAuthorityProjector.project(1, mapOf("officerLevel" to 12), people,
            state(grant()), unavailableVassals)
        assertTrue(result.readers.isEmpty()); assertNull(result.ruler); assertFalse(result.complete)
    }

    @Test fun `검증된 군주는 다른 선택 원천 부재와 별개로 허용하며 전체 명부는 partial이다`() {
        val result = CouncilAuthorityProjector.project(1, meta, people, missing, unavailableVassals)
        assertEquals(setOf(10), result.readers)
        assertEquals(result.readers, result.writers); assertEquals(result.readers, result.noticeWriters)
        assertEquals("군주", result.roles[10]); assertFalse(result.complete)
        assertNull(result.designationRevision); assertTrue(result.designationWritable)
    }

    @Test fun `현재 군주의 실제 지정은 허용하고 회수 뒤 다음 투영은 차단한다`() {
        val before = CouncilAuthorityProjector.project(1, meta, people, state(grant()), unavailableVassals)
        assertEquals(setOf(10, 12), before.readers); assertEquals("군주 지정", before.roles[12])
        val after = CouncilAuthorityProjector.project(1, meta, people, state(grant(revoked = "revoke-1")), unavailableVassals)
        assertEquals(setOf(10), after.readers)
        assertFalse(after.complete)
    }

    @Test fun `과거 군주 지정 이력은 보존하고 미정 승계 정책을 상속이나 정상 빈값으로 해석하지 않는다`() {
        val history = state(grant(issuer = 11, revision = "ruler-old"))
        val result = CouncilAuthorityProjector.project(1, meta, people, history,
            CouncilVassalSnapshot(CouncilSourceState.AVAILABLE))
        assertEquals(setOf(10), result.readers); assertFalse(result.complete)
        assertEquals("designation-1", result.designationRevision)
        assertNull(history.value!!.grants.single().revokedByRequestId)
        assertEquals("ruler-old", history.value!!.grants.single().issuerRevision)
    }

    @Test fun `봉신으로 이미 검증한 주공만 허용하며 평범한 주공은 참가자로 추가하지 않는다`() {
        val result = CouncilAuthorityProjector.project(1, meta, people, state(),
            CouncilVassalSnapshot(CouncilSourceState.AVAILABLE, setOf(11)))
        assertEquals(setOf(10, 11), result.readers); assertEquals("봉신 주공", result.roles[11])
        assertTrue(result.complete)
        assertThrows(IllegalArgumentException::class.java) { CouncilAuthorityProjector.project(1, meta, people,
            state(), CouncilVassalSnapshot(CouncilSourceState.AVAILABLE, setOf(999))) }
    }

    @Test fun `죽은 군주나 다른 소속 roster와 손상된 binding은 권한 근거가 아니다`() {
        val dead = people.map { if (it.id == 10) it.copy(npcState = 5) else it }
        assertTrue(CouncilAuthorityProjector.project(1, meta, dead, state(grant()), unavailableVassals).readers.isEmpty())
        assertTrue(CouncilAuthorityProjector.project(1, mapOf(CurrentRulerBinding.META_KEY to "bad"), people,
            state(grant()), unavailableVassals).readers.isEmpty())
        assertThrows(IllegalArgumentException::class.java) { CouncilAuthorityProjector.project(1, meta,
            people + CouncilAuthorityPerson(99, 2, 0, emptyMap()), state(), unavailableVassals) }
    }

    @Test fun `부재 정상빈명부 손상은 각각 다른 원천상태이고 손상은 관리권한을 열지 않는다`() {
        assertEquals(CouncilSourceState.NOT_SEEDED, CouncilAuthorityProjector.designation(null).state)
        val empty = CouncilDesignationCodec.encode(CouncilDesignationState("receipt-empty", emptyList()))
        assertEquals(CouncilSourceState.AVAILABLE, CouncilAuthorityProjector.designation(empty).state)
        val broken = CouncilAuthorityProjector.designation("{}")
        assertEquals(CouncilSourceState.UNAVAILABLE, broken.state)
        val result = CouncilAuthorityProjector.project(1, meta, people, broken, unavailableVassals)
        assertEquals(setOf(10), result.readers); assertFalse(result.designationWritable); assertFalse(result.complete)
    }
}
