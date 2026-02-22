package com.opensam.engine

import com.opensam.engine.turn.cqrs.memory.DirtyTracker
import com.opensam.engine.turn.cqrs.memory.GeneralSnapshot
import com.opensam.engine.turn.cqrs.memory.GeneralTurnSnapshot
import com.opensam.engine.turn.cqrs.memory.InMemoryTurnProcessor
import com.opensam.engine.turn.cqrs.memory.InMemoryWorldState
import com.opensam.engine.turn.cqrs.memory.NationSnapshot
import com.opensam.engine.turn.cqrs.memory.NationTurnKey
import com.opensam.engine.turn.cqrs.memory.NationTurnSnapshot
import com.opensam.entity.WorldState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime

class DeterministicReplayParityTest {
    private val processor = InMemoryTurnProcessor()

    @Test
    fun `replay fixture consumes player and nation queues with expected snapshot`() {
        val fixture = replayFixturePlayerAndNationQueues()
        val canonical = runFixture(fixture)

        assertEquals(1, canonical.advancedTurns)
        assertEquals(200, canonical.worldYear)
        assertEquals(2, canonical.worldMonth)
        assertEquals(2, canonical.strategicCmdLimit)
        assertEquals("개간", canonical.lastActionCode)
        assertEquals(mapOf("cityId" to 1), canonical.lastActionArg)
        assertEquals(0, canonical.generalQueueSize)
        assertEquals(0, canonical.nationQueueSize)
        assertEquals(0, canonical.generalNpcState)
        assertEquals(10, canonical.generalNationId)
        assertNull(canonical.generalKillTurn)
    }

    @Test
    fun `replay fixture handles blocked kill-turn general exactly once per tick`() {
        val fixture = replayFixtureBlockedKillTurn()
        val canonical = runFixture(fixture)

        assertEquals(1, canonical.advancedTurns)
        assertEquals(5, canonical.generalNpcState)
        assertEquals(0, canonical.generalNationId)
        assertNull(canonical.generalKillTurn)
        assertEquals("NONE", canonical.lastActionCode)
        assertEquals(emptyMap<String, Any>(), canonical.lastActionArg)
        assertEquals(1, canonical.generalQueueSize)
    }

    @Test
    fun `same replay fixture produces same canonical output`() {
        val left = runFixture(replayFixturePlayerAndNationQueues())
        val right = runFixture(replayFixturePlayerAndNationQueues())

        assertEquals(left, right)
    }

    private fun runFixture(fixture: ReplayFixture): CanonicalReplayOutput {
        val dirtyTracker = DirtyTracker()
        val result = processor.process(fixture.state, dirtyTracker, fixture.world)
        val general = fixture.state.generals.getValue(fixture.generalId)
        val nation = fixture.state.nations.getValue(fixture.nationId)
        val lastActionCode = general.lastTurn["actionCode"] as? String ?: "NONE"
        @Suppress("UNCHECKED_CAST")
        val lastActionArg = (general.lastTurn["arg"] as? Map<String, Any>) ?: emptyMap()

        return CanonicalReplayOutput(
            advancedTurns = result.advancedTurns,
            worldYear = fixture.world.currentYear.toInt(),
            worldMonth = fixture.world.currentMonth.toInt(),
            strategicCmdLimit = nation.strategicCmdLimit.toInt(),
            lastActionCode = lastActionCode,
            lastActionArg = lastActionArg,
            generalQueueSize = fixture.state.generalTurnsByGeneralId[fixture.generalId]?.size ?: 0,
            nationQueueSize = fixture.state.nationTurnsByNationAndLevel[
                NationTurnKey(fixture.nationId, fixture.generalOfficerLevel)
            ]?.size ?: 0,
            generalNpcState = general.npcState.toInt(),
            generalNationId = general.nationId,
            generalKillTurn = general.killTurn?.toInt(),
        )
    }

    private fun replayFixturePlayerAndNationQueues(): ReplayFixture {
        val now = OffsetDateTime.now()
        val world = WorldState(
            id = 1,
            name = "replay-world",
            scenarioCode = "test",
            currentYear = 200,
            currentMonth = 1,
            tickSeconds = 300,
            updatedAt = now.minusSeconds(301),
        )

        val general = generalSnapshot(
            id = 101,
            worldId = 1,
            nationId = 10,
            officerLevel = 5,
            npcState = 0,
            killTurn = null,
            blockState = 0,
            turnTime = now.minusSeconds(20),
        )

        val nation = nationSnapshot(
            id = 10,
            worldId = 1,
            strategicCmdLimit = 3,
        )

        val state = InMemoryWorldState(
            worldId = 1,
            generals = mutableMapOf(101L to general),
            nations = mutableMapOf(10L to nation),
            generalTurnsByGeneralId = mutableMapOf(
                101L to mutableListOf(
                    GeneralTurnSnapshot(
                        id = 1,
                        worldId = 1,
                        generalId = 101,
                        turnIdx = 0,
                        actionCode = "개간",
                        arg = mutableMapOf("cityId" to 1),
                        brief = null,
                        createdAt = now.minusMinutes(1),
                    )
                )
            ),
            nationTurnsByNationAndLevel = mutableMapOf(
                NationTurnKey(10L, 5) to mutableListOf(
                    NationTurnSnapshot(
                        id = 2,
                        worldId = 1,
                        nationId = 10,
                        officerLevel = 5,
                        turnIdx = 0,
                        actionCode = "Nation휴식",
                        arg = mutableMapOf(),
                        brief = null,
                        createdAt = now.minusMinutes(1),
                    )
                )
            ),
        )

        return ReplayFixture(
            world = world,
            state = state,
            generalId = 101,
            nationId = 10,
            generalOfficerLevel = 5,
        )
    }

    private fun replayFixtureBlockedKillTurn(): ReplayFixture {
        val now = OffsetDateTime.now()
        val world = WorldState(
            id = 1,
            name = "replay-world",
            scenarioCode = "test",
            currentYear = 200,
            currentMonth = 1,
            tickSeconds = 300,
            updatedAt = now.minusSeconds(301),
        )

        val general = generalSnapshot(
            id = 201,
            worldId = 1,
            nationId = 20,
            officerLevel = 5,
            npcState = 2,
            killTurn = 1,
            blockState = 2,
            turnTime = now.minusSeconds(10),
        )

        val nation = nationSnapshot(
            id = 20,
            worldId = 1,
            strategicCmdLimit = 1,
        )

        val state = InMemoryWorldState(
            worldId = 1,
            generals = mutableMapOf(201L to general),
            nations = mutableMapOf(20L to nation),
            generalTurnsByGeneralId = mutableMapOf(
                201L to mutableListOf(
                    GeneralTurnSnapshot(
                        id = 3,
                        worldId = 1,
                        generalId = 201,
                        turnIdx = 0,
                        actionCode = "휴식",
                        arg = mutableMapOf(),
                        brief = null,
                        createdAt = now.minusMinutes(1),
                    )
                )
            ),
        )

        return ReplayFixture(
            world = world,
            state = state,
            generalId = 201,
            nationId = 20,
            generalOfficerLevel = 5,
        )
    }

    private fun nationSnapshot(
        id: Long,
        worldId: Long,
        strategicCmdLimit: Int,
    ): NationSnapshot {
        val now = OffsetDateTime.now()
        return NationSnapshot(
            id = id,
            worldId = worldId,
            name = "nation-$id",
            color = "#ffffff",
            capitalCityId = null,
            gold = 1000,
            rice = 1000,
            bill = 0,
            rate = 0,
            rateTmp = 0,
            secretLimit = 3,
            chiefGeneralId = 0,
            scoutLevel = 0,
            warState = 0,
            strategicCmdLimit = strategicCmdLimit.toShort(),
            surrenderLimit = 72,
            tech = 0f,
            power = 0,
            level = 0,
            typeCode = "che_중립",
            spy = mutableMapOf(),
            meta = mutableMapOf(),
            createdAt = now,
            updatedAt = now,
        )
    }

    private fun generalSnapshot(
        id: Long,
        worldId: Long,
        nationId: Long,
        officerLevel: Int,
        npcState: Int,
        killTurn: Int?,
        blockState: Int,
        turnTime: OffsetDateTime,
    ): GeneralSnapshot {
        val now = OffsetDateTime.now()
        return GeneralSnapshot(
            id = id,
            worldId = worldId,
            userId = null,
            name = "general-$id",
            nationId = nationId,
            cityId = 1,
            troopId = 0,
            npcState = npcState.toShort(),
            npcOrg = null,
            affinity = 0,
            bornYear = 180,
            deadYear = 260,
            picture = "",
            imageServer = 0,
            leadership = 50,
            leadershipExp = 0,
            strength = 50,
            strengthExp = 0,
            intel = 50,
            intelExp = 0,
            politics = 50,
            charm = 50,
            dex1 = 0,
            dex2 = 0,
            dex3 = 0,
            dex4 = 0,
            dex5 = 0,
            injury = 0,
            experience = 0,
            dedication = 0,
            officerLevel = officerLevel.toShort(),
            officerCity = 0,
            permission = "normal",
            gold = 1000,
            rice = 1000,
            crew = 1000,
            crewType = 0,
            train = 0,
            atmos = 0,
            weaponCode = "None",
            bookCode = "None",
            horseCode = "None",
            itemCode = "None",
            ownerName = "",
            newmsg = 0,
            turnTime = turnTime,
            recentWarTime = null,
            makeLimit = 0,
            killTurn = killTurn?.toShort(),
            blockState = blockState.toShort(),
            dedLevel = 0,
            expLevel = 0,
            age = 30,
            startAge = 30,
            belong = 1,
            betray = 0,
            personalCode = "None",
            specialCode = "None",
            specAge = 0,
            special2Code = "None",
            spec2Age = 0,
            defenceTrain = 80,
            tournamentState = 0,
            commandPoints = 10,
            commandEndTime = null,
            lastTurn = mutableMapOf(),
            meta = mutableMapOf(),
            penalty = mutableMapOf(),
            createdAt = now,
            updatedAt = now,
        )
    }

    private data class ReplayFixture(
        val world: WorldState,
        val state: InMemoryWorldState,
        val generalId: Long,
        val nationId: Long,
        val generalOfficerLevel: Short,
    )

    private data class CanonicalReplayOutput(
        val advancedTurns: Int,
        val worldYear: Int,
        val worldMonth: Int,
        val strategicCmdLimit: Int,
        val lastActionCode: String,
        val lastActionArg: Map<String, Any>,
        val generalQueueSize: Int,
        val nationQueueSize: Int,
        val generalNpcState: Int,
        val generalNationId: Long,
        val generalKillTurn: Int?,
    )
}
