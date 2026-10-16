package opensamguk.logic.battle.realtime.soldier

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import opensamguk.logic.battle.realtime.*
import opensamguk.logic.battle.realtime.space.*

/** Versioned checkpoint. Restore requires the same committed origin/config, never caller-supplied new facts. */
object TacticalV2BattleCodec {
    private const val MAGIC = 0x53425431 // SBT1
    private const val VERSION = 1
    private const val SOLDIER_BYTES = 60

    fun encode(state: TacticalV2BattleState): ByteArray = bytes { out ->
        out.writeInt(MAGIC)
        out.writeInt(VERSION)
        out.writeUTF(state.context.sha256)
        out.writeLong(state.tick)
        out.writeInt(state.gateHp)
        out.writeInt(state.status.ordinal)
        out.writeInt(state.ledger.sources.size)
        state.ledger.sources.forEach { source ->
            val balance = state.ledger.balances.getValue(source.key)
            out.writeInt(balance.active)
            out.writeInt(balance.escaped)
            out.writeInt(balance.casualties)
        }
        out.writeInt(state.soldiers.size)
        val sourceIndices = state.context.facts.mapIndexed { index, fact -> fact.source.key to index }.toMap()
        state.soldiers.forEach { soldier ->
            out.writeLong(soldier.id)
            out.writeInt(sourceIndices.getValue(soldier.sourceKey))
            out.writeLong(soldier.pos.row.raw)
            out.writeLong(soldier.pos.col.raw)
            out.writeInt(soldier.hp)
            out.writeInt(soldier.morale)
            out.writeInt(soldier.status.ordinal)
            out.writeInt(soldier.goal?.row ?: -1)
            out.writeInt(soldier.goal?.col ?: -1)
            out.writeLong(soldier.targetId ?: 0)
            out.writeInt(soldier.attackWait)
        }
    }

    fun decode(payload: ByteArray, context: TacticalV2BattleContext, expectedHash: String): TacticalV2BattleState {
        require(expectedHash.matches(Regex("[0-9a-f]{64}")) && soldierSha(payload) == expectedHash) {
            "battle checkpoint state hash mismatch"
        }
        try {
            return DataInputStream(ByteArrayInputStream(payload)).use { input ->
                require(input.readInt() == MAGIC && input.readInt() == VERSION) { "battle checkpoint version mismatch" }
                require(input.readUTF() == context.sha256) { "battle checkpoint context pin mismatch" }
                val tick = input.readLong()
                val gateHp = input.readInt()
                val status = TacticalV2SoldierRunStatus.entries.getOrNull(input.readInt())
                    ?: throw IllegalArgumentException("invalid run status")
                require(input.readInt() == context.facts.size)
                var ledger = TacticalV2TroopLedger.fromSources(context.deployment.units)
                context.facts.forEach { fact ->
                    val active = input.readInt()
                    val escaped = input.readInt()
                    val casualties = input.readInt()
                    require(active >= 0 && escaped == 0 && casualties >= 0 &&
                        active.toLong() + casualties == fact.source.initialTroops.toLong())
                    ledger = ledger.takeCasualties(fact.source.key, casualties)
                }
                val count = input.readInt()
                require(count >= 0 && count.toLong() == context.facts.sumOf { it.source.initialTroops.toLong() })
                // Physical byte bound before allocation; no arbitrary game population cap.
                require(count.toLong() * SOLDIER_BYTES <= input.available().toLong())
                val soldiers = List(count) {
                    val id = input.readLong()
                    val sourceIndex = input.readInt()
                    val source = requireNotNull(context.facts.getOrNull(sourceIndex)).source
                    val pos = TacticalV2Pos(Fx(input.readLong()), Fx(input.readLong()))
                    val hp = input.readInt()
                    val morale = input.readInt()
                    val soldierStatus = TacticalV2SoldierStatus.entries.getOrNull(input.readInt())
                        ?: throw IllegalArgumentException("invalid soldier status")
                    val row = input.readInt()
                    val col = input.readInt()
                    val goal = if (row == -1 && col == -1) null else TacticalV2Cell(row, col)
                    val target = input.readLong().let { value ->
                        require(value >= 0)
                        value.takeUnless { it == 0L }
                    }
                    TacticalV2Soldier(id, source.key, pos, hp, morale, soldierStatus, goal, target, input.readInt())
                }
                require(input.available() == 0) { "battle checkpoint trailing bytes" }
                TacticalV2BattleState(context, tick, soldiers, ledger, gateHp, status).also {
                    require(encode(it).contentEquals(payload)) { "noncanonical battle checkpoint" }
                }
            }
        } catch (error: IllegalArgumentException) {
            throw error
        } catch (error: Exception) {
            throw IllegalArgumentException("battle checkpoint corrupt", error)
        }
    }

    internal fun contextSha256(context: TacticalV2BattleContext): String = soldierSha(bytes { out ->
        out.writeInt(MAGIC)
        out.writeInt(VERSION)
        out.writeLong(context.seed)
        out.writeInt(context.battlefield.id)
        out.writeUTF(context.battlefield.kind)
        context.battlefield.rows.forEach { out.writeUTF(it) }
        val deployment = TacticalV2DeploymentCodec.encode(context.deployment)
        out.writeInt(deployment.size)
        out.write(deployment)
        // All data-class rule fields are pinned, including v1-only fields; none is a v2 timeout.
        out.writeUTF(context.rules.toString())
        out.writeUTF(context.combatParams.sha256)
        out.writeInt(context.combatParams.hitPoints)
        out.writeInt(context.combatParams.damage)
        out.writeUTF(context.spaceParams.sha256)
        out.writeInt(context.spaceParams.cellCapacity)
        out.writeLong(context.spaceParams.soldierRadius.raw)
        out.writeInt(context.humanSides.fold(0) { mask, side -> mask or (1 shl side.ordinal) })
        out.writeInt(context.gate?.row ?: -1)
        out.writeInt(context.gate?.col ?: -1)
        out.writeInt(context.initialGateHp)
        out.writeLong(context.runBudgetTicks ?: -1)
        context.facts.forEach { fact ->
            out.writeInt(fact.crewTypeId)
            out.writeInt(fact.kind.ordinal)
            out.writeBoolean(fact.general != null)
            fact.general?.let { general ->
                out.writeInt(general.id)
                out.writeInt(general.leadership)
                out.writeInt(general.strength)
                out.writeInt(general.intelligence)
                out.writeInt(general.politics)
                out.writeInt(general.charisma)
            }
            out.writeInt(fact.training)
            out.writeInt(fact.morale)
            out.writeInt(fact.fatigue)
            out.writeInt(fact.provisions)
        }
    })

    private fun bytes(write: (DataOutputStream) -> Unit): ByteArray = ByteArrayOutputStream().also { buffer ->
        DataOutputStream(buffer).use(write)
    }.toByteArray()
}
