package opensamguk.engine.turn

import opensamguk.common.constants.GameConst
import opensamguk.logic.world.BuiltGeneral
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class BuiltGeneralMapperTest {
    @Test
    fun `legacy float addition truncates turntime microseconds like DateInterval`() {
        val state = TurnWorldState(
            id = 1,
            currentYear = 200,
            currentMonth = 1,
            tickSeconds = 3600,
            lastTurnTime = Instant.parse("2026-06-08T11:39:08Z"),
        )

        val mapped = builtGeneral(turntimeSecond = 932, turntimeFraction = 435810).toTurnGeneral(1, state)

        assertEquals(Instant.parse("2026-06-08T11:54:40.435809Z"), mapped.turnTime)
    }

    @Test
    fun `legacy builder killturn is converted to three phase turns`() {
        val built = builtGeneral()
        val state = TurnWorldState(
            id = 1,
            currentYear = 200,
            currentMonth = 1,
            tickSeconds = 3600,
            lastTurnTime = Instant.EPOCH,
        )

        val mapped = built.toTurnGeneral(1, state)

        assertEquals(120 * GameConst.phasesPerMonth, mapped.meta["killturn"])
        assertEquals("npc.png", mapped.meta["picture"])
        assertEquals(173, mapped.meta["bornyear"])
        assertEquals(173, mapped.meta["born_year"])
        assertEquals(220, mapped.meta["deadyear"])
        assertEquals(220, mapped.meta["dead_year"])
        assertEquals(0, mapped.meta["image_server"])
        assertEquals(0, mapped.meta["imgsvr"])
        assertEquals(1, mapped.meta["dedlevel"])
        assertEquals(0, mapped.meta["newmsg"])
        assertEquals(null, mapped.meta["owner_name"])
        assertEquals(0, mapped.meta["leadership_exp"])
        assertEquals(0, mapped.meta["strength_exp"])
        assertEquals(0, mapped.meta["intel_exp"])
        assertEquals(0, mapped.meta["makelimit"])
        assertEquals(0, mapped.meta["explevel"])
        assertEquals(20, mapped.meta["startage"])
        assertEquals(emptyMap<String, Any?>(), mapped.meta["last_turn"])
        assertEquals(emptyList<Any?>(), mapped.meta["aux"])
        assertEquals(emptyMap<String, Any?>(), mapped.meta["penalty"])
    }

    @Test
    fun `RTK built general persists five stats and appends stable source metadata`() {
        val state = TurnWorldState(
            id = 1,
            currentYear = 200,
            currentMonth = 1,
            tickSeconds = 3600,
            lastTurnTime = Instant.EPOCH,
        )
        val rtkMetadata = linkedMapOf<String, Any?>(
            "rtk14_officer_number" to 17001,
            "rtk14_gender" to "female",
            "rtk14_birth_year" to 173,
            "rtk14_appearance_year" to 190,
            "rtk14_death_year" to 220,
            "rtk14_lifespan" to 47,
            "rtk14_activity_years" to 27,
            "rtk14_total" to 345,
            "rtk14_ideology" to "왕도",
        )

        val mapped = builtGeneral(politics = 77, charm = 88, rtkMetadata = rtkMetadata).toTurnGeneral(1, state)

        assertEquals(77, mapped.stats.politics)
        assertEquals(88, mapped.stats.charm)
        assertEquals(rtkMetadata, mapped.meta.filterKeys { it.startsWith("rtk14_") })
        assertEquals(
            rtkMetadata.keys.toList(),
            mapped.meta.keys.toList().takeLast(rtkMetadata.size),
            message = "RTK keys are appended after PHP legacy metadata in deterministic source order",
        )
    }

    private fun builtGeneral(
        turntimeSecond: Int = 0,
        turntimeFraction: Int = 0,
        politics: Int = 50,
        charm: Int = 50,
        rtkMetadata: Map<String, Any?> = emptyMap(),
    ) =
        BuiltGeneral(
            name = "NPC", npc = 3, nation = 0, cityId = 1,
            leadership = 50, strength = 50, intel = 50, affinity = 50,
            ego = "의리", specialDomestic = "None", specialWar = "None",
            specAge = 30, specAge2 = 40, birth = 173, death = 220, age = 27,
            officerLevel = 0, killturn = 120, experience = 2000, dedication = 2000,
            gold = 1000, rice = 1000, crewType = 0,
            dex1 = 0, dex2 = 0, dex3 = 0, dex4 = 0, dex5 = 0,
            turntimeSecond = turntimeSecond, turntimeFraction = turntimeFraction,
            picture = "npc.png",
            politics = politics,
            charm = charm,
            rtkMetadata = rtkMetadata,
        )
}
