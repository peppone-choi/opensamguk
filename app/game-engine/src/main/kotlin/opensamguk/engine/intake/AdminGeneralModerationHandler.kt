package opensamguk.engine.intake

import opensamguk.common.wire.GeneralBoolResult
import opensamguk.common.wire.TurnDaemonCommand
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.PerTurnOverlay

class AdminGeneralModerationHandler(
    private val world: InMemoryTurnWorld,
    private val recorder: ChangeRecorder,
) {
    fun handle(command: TurnDaemonCommand.AdminGeneralModeration): GeneralBoolResult {
        if (command.action !in SUPPORTED_ACTIONS) {
            return GeneralBoolResult(command.type, false, command.actorGeneralId, "지원하지 않는 관리자 조치입니다.")
        }

        val generalIds = command.generalIds.distinct().filter { it > 0 }
        if (generalIds.isEmpty()) {
            return GeneralBoolResult(command.type, false, command.actorGeneralId, "대상 장수가 없습니다.")
        }
        val missingIds = generalIds.filter { world.getGeneralById(it) == null }
        if (missingIds.isNotEmpty()) {
            return GeneralBoolResult(
                command.type,
                false,
                command.actorGeneralId,
                "존재하지 않는 장수입니다: ${missingIds.joinToString(",")}",
            )
        }

        generalIds.forEach { generalId ->
            val pre = requireNotNull(world.getGeneralById(generalId))
            if (command.action == "allowAccess" || command.action == "denyAccess") {
                world.getAccessLog(generalId)?.let { accessLog ->
                    recorder.recordAccessLogUpsert(
                        world,
                        accessLog.copy(refreshScore = if (command.action == "allowAccess") 0 else 1000),
                    )
                }
                return@forEach
            }
            val meta = LinkedHashMap(pre.meta)
            val penalty = linkedMapOf<String, Any?>().apply {
                val raw = pre.meta["penalty"] as? Map<*, *>
                raw?.forEach { (key, value) -> if (key is String) this[key] = value }
            }
            var gold = pre.gold
            var rice = pre.rice
            var turnTime = pre.turnTime

            when (command.action) {
                "unblock" -> setBlock(meta, penalty, 0)
                "block1" -> {
                    setBlock(meta, penalty, 1)
                    setKillturn(meta, penalty, 24)
                }
                "block2", "block3" -> {
                    setBlock(meta, penalty, if (command.action == "block2") 2 else 3)
                    setKillturn(meta, penalty, 24)
                    gold = 0
                    rice = 0
                }
                "infiniteKillturn" -> setKillturn(meta, penalty, 8000)
                "forceDeath" -> {
                    setKillturn(meta, penalty, 0)
                    turnTime = world.getState().lastTurnTime
                }
                else -> meta[command.action] = ((meta[command.action] as? Number)?.toInt() ?: 0) + 10_000
            }
            meta["penalty"] = penalty
            val post = pre.copy(gold = gold, rice = rice, turnTime = turnTime, meta = meta)
            recorder.diffGeneral(PerTurnOverlay.toLogicGeneral(pre), PerTurnOverlay.toLogicGeneral(post))
            world.applyGeneralDirtyFree(post)
        }

        return GeneralBoolResult(command.type, true, command.actorGeneralId)
    }

    private fun setBlock(meta: MutableMap<String, Any?>, penalty: MutableMap<String, Any?>, value: Int) {
        meta["block"] = value
        meta["blockLevel"] = value
        penalty["block"] = value
        penalty["blockLevel"] = value
    }

    private fun setKillturn(meta: MutableMap<String, Any?>, penalty: MutableMap<String, Any?>, value: Int) {
        meta["killturn"] = value
        penalty["killturn"] = value
    }

    companion object {
        private val SUPPORTED_ACTIONS = setOf(
            "unblock", "block1", "block2", "block3", "infiniteKillturn", "forceDeath",
            "dex1", "dex2", "dex3", "dex4", "dex5", "allowAccess", "denyAccess",
        )
    }
}
