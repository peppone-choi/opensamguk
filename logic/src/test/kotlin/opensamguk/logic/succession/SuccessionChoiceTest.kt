package opensamguk.logic.succession

import kotlin.test.*

class SuccessionChoiceTest {
    private val case = SuccessionCase(1, 2, 3, 4, "permanent-death:3", true)
    private val npc = SuccessionNpc(5, 4, 3, null, mapOf("leadership" to 37, "intelligence" to 42),
        mapOf(9L to -12, 10L to 15), setOf("unit:5", "unit:6"), setOf("estate:5", "estate:6"))
    private val inherit = SuccessionRequest("choice:1", SuccessionOption.INHERIT, 5)

    @Test
    fun `direct NPC retains abilities relationships units and personal assets without dead general merge`() {
        val result = SuccessionChoice(case).choose(1, inherit, listOf(npc))
        assertEquals(SuccessionStatus.SELECTED, result.status)
        assertEquals(case, result.state.case)
        assertEquals(npc, result.state.selection?.inheritedNpc)
        assertEquals(1L, result.state.case.accountId)
        assertEquals(2L, result.state.case.worldId)
        assertEquals(setOf("unit:5", "unit:6"), result.state.selection?.inheritedNpc?.ownedUnitIds)
    }

    @Test
    fun `replay is idempotent without candidate reload and alternate choice conflicts`() {
        val first = SuccessionChoice(case).choose(1, inherit, listOf(npc))
        val retry = first.state.choose(1, inherit)
        assertEquals(SuccessionStatus.ALREADY_COMPLETED, retry.status)
        assertSame(first.state, retry.state)
        for (request in listOf(inherit.copy(requestId = "different"),
            SuccessionRequest("new", SuccessionOption.NEW_GENERAL))) {
            val conflict = first.state.choose(1, request, newGeneralEligible = true)
            assertEquals(SuccessionStatus.CONFLICT, conflict.status)
            assertSame(first.state, conflict.state)
        }
    }

    @Test
    fun `other players foreign indirect missing duplicate and deceased targets are rejected`() {
        val candidates = listOf(npc.copy(playerAccountId = 6), npc.copy(nationId = 6),
            npc.copy(directLordId = 6), npc.copy(directLordId = null))
        for (candidate in candidates) {
            assertEquals(SuccessionStatus.INVALID_TARGET, SuccessionChoice(case).choose(1, inherit,
                listOf(candidate)).status)
        }
        for (list in listOf(emptyList(), listOf(npc, npc))) {
            assertEquals(SuccessionStatus.INVALID_TARGET, SuccessionChoice(case).choose(1, inherit, list).status)
        }
        assertEquals(SuccessionStatus.INVALID_TARGET, SuccessionChoice(case).choose(1,
            inherit.copy(targetId = 3), listOf(npc.copy(generalId = 3))).status)
    }

    @Test
    fun `unaffiliated direct NPC can be inherited without inventing a nation`() {
        val result = SuccessionChoice(case.copy(nationId = null)).choose(1, inherit,
            listOf(npc.copy(nationId = null)))
        assertEquals(SuccessionStatus.SELECTED, result.status)
        assertNull(result.state.selection?.inheritedNpc?.nationId)
    }

    @Test
    fun `account mismatch never records choice including retries`() {
        val state = SuccessionChoice(case)
        assertEquals(SuccessionStatus.ACCOUNT_MISMATCH, state.choose(99, inherit, listOf(npc)).status)
        assertNull(state.selection)
        val selected = state.choose(1, inherit, listOf(npc)).state
        assertEquals(SuccessionStatus.ACCOUNT_MISMATCH, selected.choose(99, inherit).status)
    }

    @Test
    fun `disabled unknown and unconfirmed death cannot start either choice`() {
        for (request in listOf(inherit, SuccessionRequest("new", SuccessionOption.NEW_GENERAL))) {
            for ((input, status) in listOf(
                case.copy(allowPlayerPermadeath = false) to SuccessionStatus.PERMADEATH_DISABLED,
                case.copy(allowPlayerPermadeath = null) to SuccessionStatus.NOT_DECIDED,
                case.copy(permanentDeathEventId = null) to SuccessionStatus.NOT_DECIDED,
            )) {
                val state = SuccessionChoice(input)
                val result = state.choose(1, request, listOf(npc), true)
                assertEquals(status, result.status)
                assertSame(state, result.state)
                assertNull(result.state.selection)
            }
        }
    }

    @Test
    fun `new general eligibility remains a seam and creates no fake general`() {
        val request = SuccessionRequest("new", SuccessionOption.NEW_GENERAL)
        val state = SuccessionChoice(case)
        assertEquals(SuccessionStatus.NOT_DECIDED, state.choose(1, request).status)
        assertEquals(SuccessionStatus.NEW_GENERAL_NOT_ALLOWED, state.choose(1, request, newGeneralEligible = false).status)
        val result = state.choose(1, request, newGeneralEligible = true)
        assertEquals(SuccessionStatus.SELECTED, result.status)
        assertEquals(SuccessionOption.NEW_GENERAL, result.state.selection?.request?.option)
        assertNull(result.state.selection?.inheritedNpc)
        assertEquals(case, result.state.case)
        assertEquals(SuccessionStatus.ALREADY_COMPLETED, result.state.choose(1, request).status)
    }

    @Test
    fun `selected NPC is detached from mutable inputs and returned snapshots`() {
        val abilities = mutableMapOf("leadership" to 37, "intelligence" to 42)
        val relationships = mutableMapOf(9L to -12, 10L to 15)
        val units = mutableSetOf("unit:5", "unit:6")
        val assets = mutableSetOf("estate:5", "estate:6")
        val selected = SuccessionChoice(case).choose(1, inherit, listOf(npc.copy(
            abilities = abilities, relationships = relationships, ownedUnitIds = units, personalAssetIds = assets))).state
        abilities["leadership"] = 100
        relationships.clear()
        units.clear()
        assets.clear()
        assertEquals(npc, selected.selection?.inheritedNpc)
        val returned = selected.selection?.inheritedNpc ?: fail("missing selected NPC")
        (returned.abilities as? MutableMap<String, Int>)?.clear()
        (returned.relationships as? MutableMap<Long, Int>)?.clear()
        (returned.ownedUnitIds as? MutableSet<String>)?.clear()
        (returned.personalAssetIds as? MutableSet<String>)?.clear()
        assertEquals(npc, selected.selection?.inheritedNpc)
    }

    @Test
    fun `invalid input identity and choice payloads fail closed`() {
        assertFailsWith<IllegalArgumentException> { case.copy(accountId = 0) }
        assertFailsWith<IllegalArgumentException> { case.copy(permanentDeathEventId = " ") }
        assertFailsWith<IllegalArgumentException> { SuccessionRequest("x", SuccessionOption.INHERIT) }
        assertFailsWith<IllegalArgumentException> { SuccessionRequest("x", SuccessionOption.INHERIT, -1) }
        assertFailsWith<IllegalArgumentException> { SuccessionRequest("x", SuccessionOption.NEW_GENERAL, 5) }
        assertFailsWith<IllegalArgumentException> { inherit.copy(requestId = "") }
        assertFailsWith<IllegalArgumentException> { npc.copy(ownedUnitIds = setOf("")) }
    }
}
