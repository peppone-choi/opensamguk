package opensamguk.logic.battle.realtime

import opensamguk.common.world.WorldId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BattleOwnerAuthorityBindingTest {
    private fun key(id: Int) = TacticalV2SourceKey(TacticalV2SourceKind.RETINUE, id.toString())
    private fun unit(id: Int = 1, owner: Int = 7, commander: Int = 9,
                     side: BattleSide = BattleSide.ATTACKER, revision: Long = 1) =
        TacticalV2UnitSource(key(id), side, owner, commander, 30, revision)
    private fun facts(owner: Int = 7, account: Int? = 42, user: String? = account?.toString(),
                      npc: Int = if (account == null) 2 else 0, playable: Boolean = account != null,
                      revision: Long = 1, status: String = "ACTIVE", side: BattleSide = BattleSide.ATTACKER,
                      keys: Set<TacticalV2SourceKey> = setOf(key(1))) =
        BattleOwnerAuthorityFacts(owner, user, npc, account, playable, revision, status, side, keys)
    private fun bind(sources: List<TacticalV2UnitSource> = listOf(unit()),
                     owners: List<BattleOwnerAuthorityFacts> = listOf(facts()),
                     world: WorldId = WorldId(1), battle: String = "battle", sha: String = "a".repeat(64)) =
        BattleOwnerAuthorityBinding.bind(world, battle, sha, sources, owners)

    @Test fun `actual owner controls union across commanders without granting commander rights`() {
        val sources = listOf(unit(2, commander = 11), unit(1))
        val binding = bind(sources, listOf(facts(keys = setOf(key(1), key(2))))).single()
        assertEquals(7, binding.ownerGeneralId)
        assertEquals(42, binding.accountId)
        assertEquals(setOf(key(1), key(2)), binding.controlledSourceKeys)
        assertEquals(mapOf(key(1) to 1L, key(2) to 1L), binding.sourceRevisions)
    }

    @Test fun `same side other owner stays separate and AI sources remain input facts`() {
        val sources = listOf(unit(), unit(2, owner = 8), unit(3, owner = 10))
        val bindings = bind(sources, listOf(facts(), facts(8, 43, keys = setOf(key(2))),
            facts(10, null, keys = setOf(key(3)))))
        assertEquals(listOf(7, 8), bindings.map { it.ownerGeneralId })
        assertEquals(setOf(key(1)), bindings[0].controlledSourceKeys)
        assertEquals(setOf(key(2)), bindings[1].controlledSourceKeys)
        assertEquals(3, sources.size)
    }

    @Test fun `unknown human and synthetic AI account are rejected`() {
        for (owner in listOf(facts(account = null, npc = 0), facts(account = null, user = "42"),
            facts(npc = 2), facts(user = "042"), facts(user = ""), facts(playable = false))) {
            assertFailsWith<IllegalArgumentException> { bind(owners = listOf(owner)) }
        }
    }

    @Test fun `full source and owner multiset must match without partial unions`() {
        assertFailsWith<IllegalArgumentException> { bind(listOf(unit(), unit())) }
        assertFailsWith<IllegalArgumentException> { bind(owners = emptyList()) }
        assertFailsWith<IllegalArgumentException> { bind(owners = listOf(facts(), facts())) }
        assertFailsWith<IllegalArgumentException> { bind(owners = listOf(facts(owner = 9))) }
        assertFailsWith<IllegalArgumentException> {
            bind(listOf(unit(), unit(2)), listOf(facts()))
        }
        assertFailsWith<IllegalArgumentException> {
            bind(owners = listOf(facts(keys = setOf(key(1), key(2)))))
        }
    }

    @Test fun `one account cannot own two participants and one owner cannot cross sides`() {
        assertFailsWith<IllegalArgumentException> {
            bind(listOf(unit(), unit(2, owner = 8)), listOf(facts(), facts(8, keys = setOf(key(2)))))
        }
        assertFailsWith<IllegalArgumentException> {
            bind(listOf(unit(), unit(2, side = BattleSide.DEFENDER)),
                listOf(facts(keys = setOf(key(1), key(2)))))
        }
    }

    @Test fun `unpersisted zero revisions and revoked authority fail closed`() {
        assertFailsWith<IllegalArgumentException> { bind(listOf(unit(revision = 0))) }
        assertFailsWith<IllegalArgumentException> { bind(owners = listOf(facts(revision = 0))) }
        assertFailsWith<IllegalArgumentException> { bind(owners = listOf(facts(status = "REVOKED"))) }
    }

    @Test fun `same account restored at a newer revision cannot match the frozen binding`() {
        val expected = bind().single()
        assertTrue(expected.matches(bind().single()))
        assertFalse(expected.matches(bind(owners = listOf(facts(revision = 3))).single()))
        assertFalse(expected.matches(bind(listOf(unit(revision = 2))).single()))
        assertFalse(expected.matches(bind(world = WorldId(2)).single()))
        assertFalse(expected.matches(bind(battle = "other").single()))
        assertFalse(expected.matches(bind(sha = "b".repeat(64)).single()))
    }

    @Test fun `binding and input facts defensively preserve source collections`() {
        val keys = linkedSetOf(key(1))
        val owner = facts(keys = keys)
        keys.add(key(2))
        val bindings = bind(owners = listOf(owner))
        val binding = bindings.single()
        assertEquals(setOf(key(1)), binding.controlledSourceKeys)
        assertFailsWith<UnsupportedOperationException> {
            (binding.controlledSourceKeys as MutableSet).clear()
        }
        assertFailsWith<UnsupportedOperationException> { (binding.sourceRevisions as MutableMap).clear() }
        assertFailsWith<UnsupportedOperationException> { (bindings as MutableList).clear() }
    }
    private fun origin(): CampaignBattleOriginSnapshot {
        val attacker = CampaignBattleOriginParticipant("attack", 7, 9, 1, listOf(1))
        val defender = CampaignBattleOriginParticipant("defend", 8, 11, 2, listOf(2))
        fun source(id: Int, owner: Int, commander: Int, side: BattleSide) =
            CampaignBattleOriginUnit(key(id), side, owner, commander, 1, 30, 50, 50, 0, 20, null, 1)
        return CampaignBattleOriginSnapshot(1, "battle", "c".repeat(64), "d".repeat(64), 1, 1,
            "topology", "e".repeat(64), "province", "approach", "f".repeat(64), 1, 1, attacker,
            listOf(defender), listOf(source(1, 7, 9, BattleSide.ATTACKER),
                source(2, 8, 11, BattleSide.DEFENDER)), listOf(
                CampaignBattleOriginOwner(7, 42, 0, true, BattleSide.ATTACKER, listOf(key(1)), 1,
                    CampaignBattleAuthorityStatus.ACTIVE),
                CampaignBattleOriginOwner(8, null, 2, false, BattleSide.DEFENDER, listOf(key(2)), 1,
                    CampaignBattleAuthorityStatus.ACTIVE)))
    }
    private fun native() = listOf(BattleOwnerNativeGeneral(1, 7, "42", 0),
        BattleOwnerNativeGeneral(1, 8, null, 2))

    @Test fun `typed CBO1 projection preserves exact RETINUE identity and real native owner`() {
        val origin = origin()
        val bindings = BattleOwnerAuthorityBinding.fromOrigin(origin, native())
        assertEquals(7, bindings.single().ownerGeneralId)
        assertEquals(setOf(key(1)), bindings.single().controlledSourceKeys)
        assertEquals(CampaignBattleOriginCodec.sha256(CampaignBattleOriginCodec.encode(origin)),
            bindings.single().originSha256)
        assertEquals(2, origin.units.size)
    }

    @Test fun `typed source rejects missing duplicate foreign or stale native rows`() {
        val origin = origin()
        for (rows in listOf(native().take(1), native() + native()[0],
            native().map { it.copy(worldId = 2) },
            native().map { if (it.generalId == 7) it.copy(userId = "43") else it },
            native().map { if (it.generalId == 7) it.copy(npcState = 2) else it })) {
            assertFailsWith<IllegalArgumentException> { BattleOwnerAuthorityBinding.fromOrigin(origin, rows) }
        }
    }

    @Test fun `mutated supplier list cannot bypass canonical full source validation`() {
        val mutableUnits = origin().units.toMutableList()
        val origin = origin().copy(units = mutableUnits)
        mutableUnits.add(mutableUnits[0])
        assertFailsWith<IllegalArgumentException> { BattleOwnerAuthorityBinding.fromOrigin(origin, native()) }
    }

}
