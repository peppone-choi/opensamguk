package com.opensam.engine.ai

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class NpcPolicyTest {

    @Test
    fun `default general policy has all standard actions enabled`() {
        val policy = NpcGeneralPolicy()
        assertTrue(policy.canDo("일반내정"))
        assertTrue(policy.canDo("징병"))
        assertTrue(policy.canDo("출병"))
        assertTrue(policy.canDo("전방워프"))
        assertFalse(policy.canDo("한계징병"))
        assertFalse(policy.canDo("고급병종"))
        assertEquals(500, policy.minWarCrew)
        assertEquals(80, policy.properWarTrainAtmos)
    }

    @Test
    fun `default nation policy has all standard actions enabled`() {
        val policy = NpcNationPolicy()
        assertTrue(policy.canDo("부대전방발령"))
        assertTrue(policy.canDo("NPC포상"))
        assertTrue(policy.canDo("선전포고"))
        assertEquals(40, policy.minNPCWarLeadership)
        assertEquals(5000, policy.minNPCRecruitCityPopulation)
        assertEquals(0.4, policy.safeRecruitCityPopulationRatio)
    }

    @Test
    fun `default priority lists match expected order`() {
        val genPolicy = NpcGeneralPolicy()
        assertEquals("긴급내정", genPolicy.priority.first())
        assertEquals("중립", genPolicy.priority.last())

        val nationPolicy = NpcNationPolicy()
        assertEquals("부대전방발령", nationPolicy.priority.first())
        assertEquals("천도", nationPolicy.priority.last())
    }

    @Test
    fun `builds general policy from nation meta`() {
        val meta = mapOf(
            "npcGeneralPolicy" to mapOf(
                "minWarCrew" to 300,
                "properWarTrainAtmos" to 90,
            )
        )

        val policy = NpcPolicyBuilder.buildGeneralPolicy(meta)
        assertEquals(300, policy.minWarCrew)
        assertEquals(90, policy.properWarTrainAtmos)
        assertEquals(NpcGeneralPolicy.DEFAULT_GENERAL_PRIORITY, policy.priority)
    }

    @Test
    fun `builds nation policy from nation meta`() {
        val meta = mapOf(
            "npcNationPolicy" to mapOf(
                "minNPCWarLeadership" to 50,
                "reqNationGold" to 3000,
            )
        )

        val policy = NpcPolicyBuilder.buildNationPolicy(meta)
        assertEquals(50, policy.minNPCWarLeadership)
        assertEquals(3000, policy.reqNationGold)
    }

    @Test
    fun `returns default policy when meta is empty`() {
        val genPolicy = NpcPolicyBuilder.buildGeneralPolicy(emptyMap())
        assertEquals(NpcGeneralPolicy(), genPolicy)

        val nationPolicy = NpcPolicyBuilder.buildNationPolicy(emptyMap())
        assertEquals(NpcNationPolicy(), nationPolicy)
    }

    @Test
    fun `custom priority is respected`() {
        val meta = mapOf(
            "npcGeneralPolicy" to mapOf(
                "priority" to listOf("출병", "징병", "일반내정"),
            )
        )

        val policy = NpcPolicyBuilder.buildGeneralPolicy(meta)
        assertEquals(listOf("출병", "징병", "일반내정"), policy.priority)
    }
}
