package com.opensam.engine.turn.cqrs.memory

import com.opensam.engine.turn.cqrs.TurnDomainEvent
import com.opensam.engine.turn.cqrs.TurnResult
import com.opensam.entity.WorldState
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.OffsetDateTime

@Service
class InMemoryTurnProcessor {
    private val logger = LoggerFactory.getLogger(InMemoryTurnProcessor::class.java)

    fun process(state: InMemoryWorldState, dirtyTracker: DirtyTracker, world: WorldState): TurnResult {
        val now = OffsetDateTime.now()
        val tickDuration = Duration.ofSeconds(world.tickSeconds.toLong())
        var nextTurnAt = world.updatedAt.plus(tickDuration)
        var advancedTurns = 0
        val events = mutableListOf<TurnDomainEvent>()

        while (!now.isBefore(nextTurnAt)) {
            executeReservedCommands(state, dirtyTracker, world)
            resetStrategicCommandLimits(state, dirtyTracker)
            advanceMonth(world)

            world.updatedAt = nextTurnAt
            nextTurnAt = nextTurnAt.plus(tickDuration)
            advancedTurns += 1

            events += TurnDomainEvent(
                type = EVENT_TURN_ADVANCED,
                payload = mapOf(
                    "worldId" to world.id.toLong(),
                    "year" to world.currentYear.toInt(),
                    "month" to world.currentMonth.toInt(),
                ),
            )
        }

        return TurnResult(
            advancedTurns = advancedTurns,
            events = events,
        )
    }

    private fun executeReservedCommands(
        state: InMemoryWorldState,
        dirtyTracker: DirtyTracker,
        _world: WorldState,
    ) {
        val now = OffsetDateTime.now()
        val generals = state.generals.values.sortedBy { it.turnTime }

        for (general in generals) {
            if (general.blockState >= 2) {
                val killTurn = general.killTurn
                if (killTurn != null) {
                    val nextKillTurn = killTurn - 1
                    if (nextKillTurn <= 0) {
                        general.npcState = 5
                        general.nationId = 0
                        general.killTurn = null
                    } else {
                        general.killTurn = nextKillTurn.toShort()
                    }
                }
                general.turnTime = now
                general.updatedAt = now
                dirtyTracker.markDirty(DirtyTracker.EntityType.GENERAL, general.id)
                continue
            }

            if (general.officerLevel >= 5 && general.nationId > 0) {
                val nationKey = NationTurnKey(general.nationId, general.officerLevel)
                val nationQueue = state.nationTurnsByNationAndLevel[nationKey]
                if (!nationQueue.isNullOrEmpty()) {
                    nationQueue.removeAt(0)
                    if (nationQueue.isEmpty()) {
                        state.nationTurnsByNationAndLevel.remove(nationKey)
                    }
                }
            }

            val actionCode: String
            val arg: MutableMap<String, Any>
            if (general.npcState >= 2) {
                actionCode = "휴식"
                arg = mutableMapOf()
                state.generalTurnsByGeneralId.remove(general.id)
            } else {
                val queue = state.generalTurnsByGeneralId[general.id]
                if (queue.isNullOrEmpty()) {
                    actionCode = "휴식"
                    arg = mutableMapOf()
                } else {
                    val turn = queue.removeAt(0)
                    actionCode = turn.actionCode
                    arg = turn.arg
                    if (queue.isEmpty()) {
                        state.generalTurnsByGeneralId.remove(general.id)
                    }
                }
            }

            general.lastTurn = mutableMapOf(
                "actionCode" to actionCode,
                "arg" to arg,
                "queuedInMemory" to true,
            )
            general.turnTime = now
            general.updatedAt = now
            dirtyTracker.markDirty(DirtyTracker.EntityType.GENERAL, general.id)
        }

        logger.debug(
            "Processed in-memory command queues (general queues={}, nation queues={})",
            state.generalTurnsByGeneralId.size,
            state.nationTurnsByNationAndLevel.size,
        )
    }

    private fun resetStrategicCommandLimits(state: InMemoryWorldState, dirtyTracker: DirtyTracker) {
        state.nations.values.forEach { nation ->
            if (nation.strategicCmdLimit > 0) {
                nation.strategicCmdLimit = (nation.strategicCmdLimit - 1).toShort()
                dirtyTracker.markDirty(DirtyTracker.EntityType.NATION, nation.id)
            }
        }
    }

    private fun advanceMonth(world: WorldState) {
        val nextMonth = world.currentMonth + 1
        if (nextMonth > 12) {
            world.currentMonth = 1
            world.currentYear = (world.currentYear + 1).toShort()
        } else {
            world.currentMonth = nextMonth.toShort()
        }
    }

    companion object {
        const val EVENT_TURN_ADVANCED = "TURN_ADVANCED"
    }
}
