package opensamguk.logic.war

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.Collections
import opensamguk.logic.input.CorpsEncounter

enum class BattlePlanAction { ADVANCE, HOLD, RETREAT }
enum class BattlePlanCondition { LOSS_AT_LEAST, MORALE_BELOW, ROUND_AT_LEAST }
data class BattlePlanCommand(val slot: Int, val condition: BattlePlanCondition, val threshold: Int, val action: BattlePlanAction) {
    init {
        require(slot in 0..2)
        require(threshold in 1..when(condition) {
            BattlePlanCondition.ROUND_AT_LEAST -> BattlePlans.MAX_ROUNDS
            else -> 100
        })
    }
    internal fun toMetaValue(): Map<String,Any> = linkedMapOf("slot" to slot,"condition" to condition.name,
        "threshold" to threshold,"action" to action.name)
}
class CommanderBattlePlan(val commanderGeneralId: Int, val initialAction: BattlePlanAction, commands: List<BattlePlanCommand>) {
    val commands: List<BattlePlanCommand> = Collections.unmodifiableList(commands.sortedBy { it.slot })
    init {
        require(commanderGeneralId > 0 && initialAction != BattlePlanAction.RETREAT)
        require(this.commands.size <= 3 && this.commands.map { it.slot }.distinct().size == this.commands.size)
    }
    internal fun toMetaValue(): Map<String,Any> = linkedMapOf("commanderGeneralId" to commanderGeneralId,
        "initialAction" to initialAction.name,"commands" to commands.map { it.toMetaValue() })
}

/** Sealed plans only; evaluation and once-only command activation belong to the round executor. */
class BattlePlans(val encounterId: String, plans: List<CommanderBattlePlan>) {
    val plans: List<CommanderBattlePlan> = Collections.unmodifiableList(plans.sortedBy { it.commanderGeneralId })
    init {
        require(encounterId.matches(Regex("[0-9a-f]{64}")))
        require(this.plans.isNotEmpty() && this.plans.map { it.commanderGeneralId }.distinct().size == this.plans.size)
    }
    fun requireBinding(encounter: CorpsEncounter) {
        require(encounterId == encounter.encounterId && plans.map { it.commanderGeneralId }.toSet() ==
            (listOf(encounter.attacker)+encounter.defenders).map { it.commanderGeneralId }.toSet())
    }
    val snapshotId: String get() {
        val bytes=ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            fun text(s:String) { val b=s.toByteArray(Charsets.UTF_8);out.writeInt(b.size);out.write(b) }
            text("battlePlans:v1");text(encounterId);out.writeInt(plans.size)
            plans.forEach { plan ->
                out.writeInt(plan.commanderGeneralId);text(plan.initialAction.name);out.writeInt(plan.commands.size)
                plan.commands.forEach { out.writeInt(it.slot);text(it.condition.name);out.writeInt(it.threshold);text(it.action.name) }
            }
        }
        return MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()).joinToString("") { "%02x".format(it) }
    }
    fun toMetaValue(): Map<String,Any> = linkedMapOf("version" to 1,"encounterId" to encounterId,
        "snapshotId" to snapshotId,"plans" to plans.map { it.toMetaValue() })
    companion object {
        const val META_KEY="battlePlans"
        const val MAX_ROUNDS=24
        fun defaultFor(encounter: CorpsEncounter): BattlePlans {
            val commands=listOf(BattlePlanCommand(0,BattlePlanCondition.LOSS_AT_LEAST,50,BattlePlanAction.RETREAT),
                BattlePlanCommand(1,BattlePlanCondition.MORALE_BELOW,20,BattlePlanAction.RETREAT),
                BattlePlanCommand(2,BattlePlanCondition.ROUND_AT_LEAST,MAX_ROUNDS,BattlePlanAction.RETREAT))
            return BattlePlans(encounter.encounterId,(listOf(encounter.attacker)+encounter.defenders).map {
                CommanderBattlePlan(it.commanderGeneralId,if(it.commanderGeneralId==encounter.attacker.commanderGeneralId)
                    BattlePlanAction.ADVANCE else BattlePlanAction.HOLD,commands)
            }).also { it.requireBinding(encounter) }
        }
        fun read(meta: Map<String,Any?>, encounter: CorpsEncounter): BattlePlans? {
            if(META_KEY !in meta)return null
            val root=meta[META_KEY] as? Map<*,*> ?: invalid()
            require(root.keys==setOf("version","encounterId","snapshotId","plans") && root["version"]==1)
            val plans=(root["plans"] as? List<*>)?.map { raw ->
                val row=raw as? Map<*,*> ?: invalid()
                require(row.keys==setOf("commanderGeneralId","initialAction","commands"))
                val commands=(row["commands"] as? List<*>)?.map { rawCommand ->
                    val command=rawCommand as? Map<*,*> ?: invalid()
                    require(command.keys==setOf("slot","condition","threshold","action"))
                    BattlePlanCommand(command["slot"] as? Int ?: invalid(),
                        BattlePlanCondition.valueOf(command["condition"] as? String ?: invalid()),
                        command["threshold"] as? Int ?: invalid(),BattlePlanAction.valueOf(command["action"] as? String ?: invalid()))
                } ?: invalid()
                require(commands==commands.sortedBy { it.slot })
                CommanderBattlePlan(row["commanderGeneralId"] as? Int ?: invalid(),
                    BattlePlanAction.valueOf(row["initialAction"] as? String ?: invalid()),commands)
            } ?: invalid()
            require(plans.map { it.commanderGeneralId }==plans.map { it.commanderGeneralId }.sorted())
            return BattlePlans(root["encounterId"] as? String ?: invalid(),plans).also {
                it.requireBinding(encounter);require(root["snapshotId"]==it.snapshotId)
            }
        }
        private fun invalid(): Nothing=throw IllegalArgumentException("Invalid sealed battle plans")
    }
}
