package opensamguk.logic.succession

import java.security.MessageDigest
import kotlin.test.*

class InheritanceTest {
    private val case = SuccessionCase(1, 2, 3, 4, "permanent-death:3", true)
    private val assets = listOf(EstateAsset("gold", "gold", 90), EstateAsset("unit", "unit", 7),
        EstateAsset("building", "building", 1))
    private val personal = assets.associate { it.id to EstateOwnership.PERSONAL }
    private fun estate(input: SuccessionCase = case) = Inheritance(input, "county:7", assets)

    @Test
    fun `undecided B5 freezes every asset even when an heir is designated`() {
        val result = estate().plan(InheritancePolicy(designatedHeirId = 5))
        assertEquals(InheritanceStatus.NOT_DECIDED, result.status)
        assertEquals(assets, result.state.frozenAssets)
        assertTrue(result.state.destinations.isEmpty())
        assertEquals(assets.associate { it.id to InheritancePending.ASSET_CLASSIFICATION }, result.pending)
    }

    @Test
    fun `designated heir takes precedence over family and does not require treasury definition`() {
        val result = estate().plan(InheritancePolicy(personal, 5, listOf(6, 7)))
        assertEquals(InheritanceStatus.PLANNED, result.status)
        assertEquals(assets.associate { it.id to InheritanceDestination.Heir(5) }, result.state.destinations)
        assertTrue(result.state.frozenAssets.isEmpty())
        assertEquals(case, result.state.case)
    }

    @Test
    fun `family order alone cannot decide distribution or fall through to treasury`() {
        val frozen = estate().plan(InheritancePolicy(personal, treasuryId = "capital"))
        assertEquals(InheritanceStatus.NOT_DECIDED, frozen.status)
        assertTrue(frozen.pending.values.all { it == InheritancePending.FAMILY_PRIORITY })
        for (family in listOf(listOf(7L, 6L), listOf(6L, 7L), listOf(7L))) {
            val result = frozen.state.plan(InheritancePolicy(personal, familyPriority = family, treasuryId = "capital"))
            assertEquals(InheritanceStatus.NOT_DECIDED, result.status)
            assertSame(frozen.state, result.state)
            assertEquals(assets, result.state.frozenAssets)
            assertTrue(result.state.destinations.isEmpty())
            assertEquals(assets.associate { it.id to InheritancePending.FAMILY_DISTRIBUTION }, result.pending)
        }
    }

    @Test
    fun `no heir or family requires treasury definition and never guesses capital warehouse`() {
        val policy = InheritancePolicy(personal, familyPriority = emptyList())
        val frozen = estate().plan(policy)
        assertEquals(InheritanceStatus.NOT_DECIDED, frozen.status)
        assertEquals(assets, frozen.state.frozenAssets)
        assertTrue(frozen.pending.values.all { it == InheritancePending.TREASURY_DEFINITION })
        val result = frozen.state.plan(policy.copy(treasuryId = "fixture-treasury"))
        assertEquals(InheritanceStatus.PLANNED, result.status)
        assertEquals(assets.associate { it.id to InheritanceDestination.Treasury(4, "fixture-treasury") },
            result.state.destinations)
    }

    @Test
    fun `unaffiliated estate stays local and never enters supplied treasury`() {
        val policy = InheritancePolicy(personal, familyPriority = emptyList(), treasuryId = "foreign")
        val result = estate(case.copy(nationId = null)).plan(policy)
        assertEquals(assets.associate { it.id to InheritanceDestination.LocalEstate("county:7") },
            result.state.destinations)
        assertEquals(InheritanceStatus.PLANNED, result.status)
    }

    @Test
    fun `unaffiliated estate prefers designated heir but undecided family cannot become local remainder`() {
        val independent = estate(case.copy(nationId = null))
        val designated = independent.plan(InheritancePolicy(personal, designatedHeirId = 5))
        assertEquals(InheritanceStatus.PLANNED, designated.status)
        assertEquals(assets.associate { it.id to InheritanceDestination.Heir(5) }, designated.state.destinations)
        val family = independent.plan(InheritancePolicy(personal, familyPriority = listOf(5)))
        assertEquals(InheritanceStatus.NOT_DECIDED, family.status)
        assertSame(independent, family.state)
        assertTrue(family.state.destinations.isEmpty())
    }

    @Test
    fun `undecided family distribution leaves even a mixed estate unchanged`() {
        val state = estate()
        val ownership = personal + mapOf("unit" to EstateOwnership.FACTION)
        val result = state.plan(InheritancePolicy(ownership, familyPriority = listOf(7, 6)))
        assertEquals(InheritanceStatus.NOT_DECIDED, result.status)
        assertSame(state, result.state)
        assertEquals(assets, result.state.frozenAssets)
        assertTrue(result.state.destinations.isEmpty())
        assertEquals(setOf("gold", "building"), result.pending.keys)
        assertTrue(result.pending.values.all { it == InheritancePending.FAMILY_DISTRIBUTION })
    }

    @Test
    fun `faction units and buildings await reassignment and never follow personal heir`() {
        val ownership = personal + mapOf("unit" to EstateOwnership.FACTION, "building" to EstateOwnership.FACTION)
        val result = estate().plan(InheritancePolicy(ownership, 5))
        assertEquals(InheritanceDestination.Heir(5), result.state.destinations["gold"])
        for (id in listOf("unit", "building")) {
            assertEquals(InheritanceDestination.FactionReassignment(4), result.state.destinations[id])
        }
        val invalid = estate(case.copy(nationId = null)).plan(InheritancePolicy(ownership, 5))
        assertEquals(setOf("unit", "building"), invalid.pending.keys)
        assertTrue(invalid.pending.values.all { it == InheritancePending.FACTION_WITHOUT_NATION })
    }

    @Test
    fun `partial plans retry frozen assets only without redirecting or duplicating routed assets`() {
        val first = estate().plan(InheritancePolicy(mapOf("gold" to EstateOwnership.PERSONAL), 5))
        assertEquals(InheritanceStatus.NOT_DECIDED, first.status)
        assertEquals(listOf(assets[1], assets[2]), first.state.frozenAssets)
        for (changed in listOf(
            InheritancePolicy(personal, 6),
            InheritancePolicy(personal, familyPriority = emptyList(), treasuryId = "capital"),
            InheritancePolicy(personal, 5, listOf(6)),
            InheritancePolicy(personal, 5, treasuryId = "capital"),
        )) {
            val blocked = first.state.plan(changed)
            assertEquals(InheritanceStatus.NOT_DECIDED, blocked.status)
            assertSame(first.state, blocked.state)
            assertEquals(mapOf("gold" to InheritanceDestination.Heir(5)), blocked.state.destinations)
            assertEquals(listOf(assets[1], assets[2]), blocked.state.frozenAssets)
            assertEquals(mapOf("unit" to InheritancePending.POLICY_CHANGE,
                "building" to InheritancePending.POLICY_CHANGE), blocked.pending)
        }
        val next = first.state.plan(InheritancePolicy(personal, 5))
        assertEquals(assets.associate { it.id to InheritanceDestination.Heir(5) }, next.state.destinations)
        assertEquals(InheritanceStatus.PLANNED, next.status)
        val retry = next.state.plan()
        assertEquals(InheritanceStatus.ALREADY_COMPLETED, retry.status)
        assertSame(next.state, retry.state)
        assertEquals(assets.size, retry.state.destinations.size)
    }

    @Test
    fun `partial personal policy snapshots cannot be changed through mutable family input`() {
        val family = mutableListOf(6L, 7L)
        val policy = InheritancePolicy(mapOf("gold" to EstateOwnership.PERSONAL), 5, family)
        val first = estate().plan(policy)
        family.reverse()
        val blocked = first.state.plan(policy.copy(ownershipByAsset = personal))
        assertEquals(InheritanceStatus.NOT_DECIDED, blocked.status)
        assertSame(first.state, blocked.state)
        val next = first.state.plan(InheritancePolicy(personal, 5, listOf(6, 7)))
        assertEquals(InheritanceStatus.PLANNED, next.status)
        assertEquals(assets.associate { it.id to InheritanceDestination.Heir(5) }, next.state.destinations)
    }

    @Test
    fun `disabled unknown and missing permanent event produce no inheritance path`() {
        for ((input, status) in listOf(
            case.copy(allowPlayerPermadeath = false) to InheritanceStatus.PERMADEATH_DISABLED,
            case.copy(allowPlayerPermadeath = null) to InheritanceStatus.NOT_DECIDED,
            case.copy(permanentDeathEventId = null) to InheritanceStatus.NOT_DECIDED,
        )) {
            val state = estate(input)
            val result = state.plan(InheritancePolicy(personal, 5))
            assertEquals(status, result.status)
            assertSame(state, result.state)
            assertTrue(result.state.destinations.isEmpty())
            assertEquals(assets, result.state.frozenAssets)
        }
    }

    @Test
    fun `every asset is either frozen or routed exactly once throughout repeated plans`() {
        val many = (1..96).map { EstateAsset("asset:$it", "kind:${it % 4}", it.toLong() * 100) }
        var state = Inheritance(case, "county:7", many)
        val classified = linkedMapOf<String, EstateOwnership>()
        for ((index, asset) in many.withIndex()) {
            classified[asset.id] = if (index % 3 == 0) EstateOwnership.FACTION else EstateOwnership.PERSONAL
            state = state.plan(InheritancePolicy(classified, 5)).state
            val frozenIds = state.frozenAssets.map { it.id }.toSet()
            assertTrue(frozenIds.intersect(state.destinations.keys).isEmpty())
            assertEquals(many.map { it.id }.toSet(), frozenIds + state.destinations.keys)
            for (kind in many.map { it.kind }.distinct()) {
                val routed = state.assets.filter { it.kind == kind && it.id in state.destinations }.sumOf { it.quantity }
                val frozen = state.frozenAssets.filter { it.kind == kind }.sumOf { it.quantity }
                assertEquals(many.filter { it.kind == kind }.sumOf { it.quantity }, routed + frozen)
            }
        }
        assertTrue(state.frozenAssets.isEmpty())
        assertEquals(InheritanceStatus.ALREADY_COMPLETED, state.plan().status)
    }

    @Test
    fun `same inputs produce same routing hash independent of classification map insertion order`() {
        fun digest(policy: InheritancePolicy): String {
            val result = estate().plan(policy)
            val text = result.state.assets.joinToString("|") {
                "${it.id}:${it.kind}:${it.quantity}:${result.state.destinations[it.id]}:${result.pending[it.id]}"
            }
            return MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
        }
        assertEquals(digest(InheritancePolicy(personal, 5)),
            digest(InheritancePolicy(personal.entries.reversed().associate { it.toPair() }, 5)))
        assertNotEquals(digest(InheritancePolicy(personal, 5)), digest(InheritancePolicy(personal, 6)))
    }

    @Test
    fun `invalid identity quantities duplicate assets and self inheritance fail closed`() {
        assertFailsWith<IllegalArgumentException> { EstateAsset("x", "gold", 0) }
        assertFailsWith<IllegalArgumentException> { EstateAsset("", "gold", 1) }
        assertFailsWith<IllegalArgumentException> { Inheritance(case, "x", listOf(assets[0], assets[0])) }
        assertFailsWith<IllegalArgumentException> { Inheritance(case, "", assets) }
        assertFailsWith<IllegalArgumentException> { InheritancePolicy(familyPriority = listOf(5, 5)) }
        assertFailsWith<IllegalArgumentException> { InheritancePolicy(treasuryId = " ") }
        assertFailsWith<IllegalArgumentException> { estate().plan(InheritancePolicy(personal, 3)) }
        assertFailsWith<IllegalArgumentException> { estate().plan(InheritancePolicy(personal, familyPriority = listOf(3))) }
    }

    @Test
    fun `estate input collection cannot change a pending plan`() {
        val input = assets.toMutableList()
        val state = Inheritance(case, "county:7", input)
        input.clear()
        assertEquals(assets, state.frozenAssets)
        assertEquals(assets, state.plan().state.assets)
    }
}
