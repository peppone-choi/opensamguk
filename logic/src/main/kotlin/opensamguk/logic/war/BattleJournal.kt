package opensamguk.logic.war.hwiha

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.Collections
import opensamguk.logic.world.HwihaBattlefieldGeometry.Position

class HwihaRoundInput(val round: Int, movements: List<HwihaGridExchange.MovementPlan>,
    attacks: List<HwihaGridExchange.AttackIntent>) {
    val movements: List<HwihaGridExchange.MovementPlan> = Collections.unmodifiableList(movements
        .map { HwihaGridExchange.MovementPlan(it.bugokId,it.path) }.sortedBy { it.bugokId })
    val attacks: List<HwihaGridExchange.AttackIntent> = Collections.unmodifiableList(attacks.sortedBy { it.attackerId })
    init {
        require(round in 1..HwihaBattlePlans.MAX_ROUNDS)
        require(this.movements.all { it.bugokId > 0 } && this.movements.map { it.bugokId }.distinct().size == this.movements.size)
        require(this.attacks.all { it.attackerId > 0 && it.targetId > 0 && it.attackerId != it.targetId } &&
            this.attacks.map { it.attackerId }.distinct().size == this.attacks.size)
    }
    fun toMetaValue(): Map<String,Any> = linkedMapOf("round" to round,
        "movements" to movements.map { linkedMapOf("bugokId" to it.bugokId,
            "path" to it.path.map { p -> linkedMapOf("col" to p.col,"row" to p.row) }) },
        "attacks" to attacks.map { linkedMapOf("attackerId" to it.attackerId,"targetId" to it.targetId) })
}

/** Input history only; the caller verifies context authority and replays outcomes. */
class HwihaBattleJournal(val encounterId: String, val contextHash: String, rounds: List<HwihaRoundInput>) {
    val rounds: List<HwihaRoundInput> = Collections.unmodifiableList(ArrayList(rounds))
    init {
        require(listOf(encounterId,contextHash).all { it.matches(Regex("[0-9a-f]{64}")) })
        require(this.rounds.size <= HwihaBattlePlans.MAX_ROUNDS && this.rounds.map { it.round } == (1..this.rounds.size).toList())
    }
    fun append(input: HwihaRoundInput): HwihaBattleJournal {
        if(input.round <= rounds.size) {
            require(rounds[input.round-1].toMetaValue() == input.toMetaValue()) { "Changed duplicate battle round" }
            return this
        }
        require(input.round == rounds.size+1) { "Missing battle round" }
        return HwihaBattleJournal(encounterId,contextHash,rounds+input)
    }
    val snapshotId: String get() {
        val bytes=ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            fun text(s:String) { val b=s.toByteArray(Charsets.UTF_8);out.writeInt(b.size);out.write(b) }
            text("hwihaBattleJournal:v1");text(encounterId);text(contextHash);out.writeInt(rounds.size)
            rounds.forEach { input ->
                out.writeInt(input.round);out.writeInt(input.movements.size)
                input.movements.forEach { move -> out.writeInt(move.bugokId);out.writeInt(move.path.size)
                    move.path.forEach { out.writeInt(it.col);out.writeInt(it.row) } }
                out.writeInt(input.attacks.size)
                input.attacks.forEach { out.writeInt(it.attackerId);out.writeInt(it.targetId) }
            }
        }
        return MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()).joinToString("") { "%02x".format(it) }
    }
    fun toMetaValue(): Map<String,Any> = linkedMapOf("version" to 1,"encounterId" to encounterId,
        "contextHash" to contextHash,"snapshotId" to snapshotId,"rounds" to rounds.map { it.toMetaValue() })
    companion object {
        const val META_KEY="hwihaBattleJournal"
        fun read(meta: Map<String,Any?>): HwihaBattleJournal? {
            if(META_KEY !in meta)return null
            val root=row(meta[META_KEY],setOf("version","encounterId","contextHash","snapshotId","rounds"))
            require(root["version"] == 1)
            val rounds=list(root["rounds"]).map { raw ->
                val r=row(raw,setOf("round","movements","attacks"))
                val movements=list(r["movements"]).map { rawMove ->
                    val m=row(rawMove,setOf("bugokId","path"))
                    HwihaGridExchange.MovementPlan(int(m["bugokId"]),list(m["path"]).map { rawPosition ->
                        val p=row(rawPosition,setOf("col","row"));Position(int(p["col"]),int(p["row"]))
                    })
                }
                val attacks=list(r["attacks"]).map { rawAttack ->
                    val a=row(rawAttack,setOf("attackerId","targetId"))
                    HwihaGridExchange.AttackIntent(int(a["attackerId"]),int(a["targetId"]))
                }
                require(movements.map { it.bugokId } == movements.map { it.bugokId }.sorted())
                require(attacks.map { it.attackerId } == attacks.map { it.attackerId }.sorted())
                HwihaRoundInput(int(r["round"]),movements,attacks)
            }
            return HwihaBattleJournal(root["encounterId"] as? String ?: invalid(),
                root["contextHash"] as? String ?: invalid(),rounds).also { require(root["snapshotId"] == it.snapshotId) }
        }
        private fun row(raw:Any?,keys:Set<String>):Map<*,*> = (raw as? Map<*,*> ?: invalid()).also { require(it.keys==keys) }
        private fun list(raw:Any?):List<*> = raw as? List<*> ?: invalid()
        private fun int(raw:Any?):Int = raw as? Int ?: invalid()
        private fun invalid():Nothing=throw IllegalArgumentException("Invalid battle input journal")
    }
}
