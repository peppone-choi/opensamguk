package opensamguk.logic.imperial

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ImperialWorldStateTest {
    private fun house() = ImperialHouse(
        code = "later_han", name = "後漢", status = ImperialLineStatus.ACTIVE,
        holderGeneralId = 1, designatedHeirGeneralId = 2,
        dynasticCandidateIds = listOf(2, 3), regentGeneralId = 4,
        courtNationId = 5, courtCityId = 6, legitimacy = 70,
    )

    private fun state() = ImperialWorldState(
        houses = listOf(house()),
        allegiances = listOf(ImperialAllegiance("later_han", 5,
            ImperialAllegianceRelation.COURT_GUARDIAN, ImperialRecognition.RECOGNIZED, 10)),
        transitions = emptyList(),
    )

    @Test
    fun `death succession keeps court and regent while changing only the imperial holder`() {
        val before = state()
        val after = before.succeedAfterDeath("later_han", "event:189:hong", null,
            listOf(ImperialCandidate(3, true), ImperialCandidate(2, true)), 189, 4, "HONG_DEATH")
        assertEquals(2, after.houses.single().holderGeneralId)
        assertEquals(4, after.houses.single().regentGeneralId)
        assertEquals(5, after.houses.single().courtNationId)
        assertEquals(ImperialTransitionType.DEATH_SUCCESSION, after.transitions.single().type)
        assertEquals(ImperialSuccessionSource.DESIGNATED, after.transitions.single().successionSource)
        assertEquals(before, state())
    }

    @Test
    fun `selection order and serialized bytes do not depend on candidate input order`() {
        val a = state().succeedAfterDeath("later_han", "event:189:hong", null,
            listOf(ImperialCandidate(2, true), ImperialCandidate(3, true)), 189, 4, "HONG_DEATH")
        val b = state().succeedAfterDeath("later_han", "event:189:hong", null,
            listOf(ImperialCandidate(3, true), ImperialCandidate(2, true)), 189, 4, "HONG_DEATH")
        assertEquals(ImperialWorldCodec.write(a), ImperialWorldCodec.write(b))
    }

    @Test
    fun `missing state stays absent and corrupt state throws`() {
        assertNull(ImperialWorldCodec.read(emptyMap()))
        assertFailsWith<IllegalArgumentException> {
            ImperialWorldCodec.read(mapOf(ImperialWorldCodec.META_KEY to mapOf("schemaVersion" to 1)))
        }
        assertFailsWith<IllegalArgumentException> {
            ImperialWorldCodec.read(mapOf(ImperialWorldCodec.META_KEY to
                (ImperialWorldCodec.write(state()) + ("schemaVersion" to 2))))
        }
    }

    @Test
    fun `codec round trip preserves transition and relationship without defaults`() {
        val after = state().succeedAfterDeath("later_han", "event:189:hong", null,
            listOf(ImperialCandidate(2, true)), 189, 4, "HONG_DEATH")
        assertEquals(after, ImperialWorldCodec.read(mapOf(ImperialWorldCodec.META_KEY to ImperialWorldCodec.write(after))))
    }

    @Test
    fun `one person cannot occupy two imperial lines`() {
        assertFailsWith<IllegalArgumentException> {
            ImperialWorldState(listOf(house(), house().copy(code = "cao_wei")), emptyList(), emptyList())
        }
    }

    @Test
    fun `duplicate transition request is rejected before a second succession`() {
        val once = state().succeedAfterDeath("later_han", "event:189:hong", null,
            listOf(ImperialCandidate(2, true)), 189, 4, "HONG_DEATH")
        assertFailsWith<IllegalArgumentException> {
            once.succeedAfterDeath("later_han", "event:189:hong", null,
                listOf(ImperialCandidate(3, true)), 189, 4, "HONG_DEATH")
        }
    }

    @Test
    fun `deposition and enthronement are distinct recorded events with no double emperor`() {
        val deposed = state().changeSovereign("later_han", "event:189:depose",
            ImperialTransitionType.DEPOSITION, null, 9, 189, 9, "DONG_ZHUO_DEPOSITION")
        assertEquals(ImperialLineStatus.VACANT, deposed.houses.single().status)
        assertNull(deposed.houses.single().holderGeneralId)
        val enthroned = deposed.changeSovereign("later_han", "event:189:enthrone",
            ImperialTransitionType.ENTHRONEMENT, 3, 9, 189, 9, "LIU_XIE_ENTHRONEMENT")
        assertEquals(3, enthroned.houses.single().holderGeneralId)
        assertEquals(listOf(ImperialTransitionType.DEPOSITION, ImperialTransitionType.ENTHRONEMENT),
            enthroned.transitions.map { it.type })
        assertEquals(enthroned, ImperialWorldCodec.read(mapOf(ImperialWorldCodec.META_KEY to ImperialWorldCodec.write(enthroned))))
    }

    @Test
    fun `ended line needs explicit foundation before enthronement`() {
        val ended = state().changeSovereign("later_han", "end", ImperialTransitionType.EXTINCTION,
            null, 8, 220, 10, "DYNASTY_ENDED")
        assertFailsWith<IllegalArgumentException> {
            ended.changeSovereign("later_han", "ordinary", ImperialTransitionType.ENTHRONEMENT,
                3, null, 221, 1, "ORDINARY_ACCESSION")
        }
        val founded = ended.changeSovereign("later_han", "restore", ImperialTransitionType.FOUNDATION,
            3, null, 221, 1, "EXPLICIT_REFOUNDATION")
        assertEquals(3, founded.houses.single().holderGeneralId)
    }
}
