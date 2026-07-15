package opensamguk.engine.turn

import opensamguk.common.constants.GameConst
import opensamguk.logic.world.BuiltGeneral

internal fun BuiltGeneral.toTurnGeneral(id: Int, state: TurnWorldState): TurnGeneral {
    val fullTurntimeSeconds = turntimeSecond.toDouble() + turntimeFraction.toDouble() / 1_000_000.0
    val wholeTurntimeSeconds = kotlin.math.floor(fullTurntimeSeconds).toLong()
    val turntimeMicros = ((fullTurntimeSeconds - wholeTurntimeSeconds) * 1_000_000.0).toLong()
    val turntime = state.lastTurnTime
        .plusSeconds(wholeTurntimeSeconds)
        .plusNanos(turntimeMicros * 1000L)
    return TurnGeneral(
        id = id,
        name = name,
        nationId = nation,
        cityId = cityId,
        troopId = 0,
        stats = GeneralStats(
            leadership = leadership,
            strength = strength,
            intelligence = intel,
            politics = 50,
            charm = 50,
        ),
        experience = experience,
        dedication = dedication,
        officerLevel = officerLevel,
        role = GeneralRole(
            personality = ego,
            specialDomestic = specialDomestic,
            specialWar = specialWar,
        ),
        gold = gold,
        rice = rice,
        crewTypeId = crewType,
        age = age,
        npcState = npc,
        turnTime = turntime,
        meta = linkedMapOf(
            "owner" to 0,
            "npc_org" to npc,
            "affinity" to affinity,
            "born_year" to birth,
            "bornyear" to birth,
            "dead_year" to death,
            "deadyear" to death,
            "picture" to picture,
            "npcmsg" to npcText,
            "image_server" to 0,
            "dedlevel" to 1,
            "start_age" to 20,
            "specage" to specAge,
            "specage2" to specAge2,
            "dex1" to dex1,
            "dex2" to dex2,
            "dex3" to dex3,
            "dex4" to dex4,
            "dex5" to dex5,
            "officer_city" to 0,
            "permission" to "normal",
            // GeneralBuilder preserves PHP's month-only golden; the engine executes three phase turns per month.
            "killturn" to killturn * GameConst.phasesPerMonth,
            "killturn_unit" to "phase",
            "block" to 0,
            "belong" to 0,
            "betray" to 0,
            "defence_train" to 80,
            "tnmt" to 1,
            "myset" to 6,
            "tournament" to 0,
            "newvote" to 0,
            "penalty" to emptyMap<String, Any?>(),
        ),
    )
}
