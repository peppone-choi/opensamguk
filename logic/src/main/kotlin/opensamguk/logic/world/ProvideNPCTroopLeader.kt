package opensamguk.logic.world

import opensamguk.common.rng.LiteHashDrbg
import opensamguk.common.rng.RandUtil
import opensamguk.common.rng.serializeSeed
import opensamguk.logic.domain.General
import opensamguk.logic.domain.Nation
import opensamguk.logic.event.EventAction
import opensamguk.logic.event.EventActionContext

/**
 * A3 / Task NL4 — `ProvideNPCTroopLeader` (the Action paired with [UpdateNationLevel] under the
 * F2-owned `month/1000` `true` row, firing EVERY month). Faithful port of PHP frozen historical baseline (ADR-LITE-042; not current product authority)
 * `legacy/devsam-core/hwe/sammo/Event/Action/ProvideNPCTroopLeader.php`.
 *
 * Per nation: a max NPC-troop-leader count keyed on nation level ([maxNpcTroopLeaderCnt], defined
 * ONLY for levels 1..7 — levels 0/8/9 → 0 via the PHP `?? 0`); count the nation's current `npc = 5`
 * generals; while `current < max`, mint a new NPC troop-leader from a self-seeded `troopLeader` DRBG
 * (`simpleSerialize(hidden,'troopLeader',y,m,nationID)`), bumping the shared monotonic
 * `lastNPCTroopLeaderID` KV counter.
 *
 * The actual `GeneralBuilder` mint + `troop`-row insert + 집합-command reservation is a P6/scenario
 * seam (the daemon/G1 wires the world mutation). NL4 ports the byte-match-critical pieces: the
 * per-nation create-count gate, the `troopLeader` seed derivation, the `lastNPCTroopLeaderID`
 * progression, and the `부대장%4d` name format.
 */
object ProvideNPCTroopLeader {

    /**
     * PHP `MaxNPCTroopLeaderCnt` const (`:20-28`) — defined for levels 1..7 only; any other level
     * (0, 8, 9) yields 0 (the PHP `self::MaxNPCTroopLeaderCnt[$level] ?? 0`). The 0-9 APPEND tiers
     * 8/9 inherit no NPC troop leaders (intended — the table is not extended above lv7).
     */
    fun maxNpcTroopLeaderCnt(level: Int): Int = when (level) {
        1 -> 0
        2 -> 1
        3 -> 3
        4 -> 4
        5 -> 6
        6 -> 7
        7 -> 9
        else -> 0
    }

    /** The number of leaders to mint: `max(0, maxByLevel - currentNpc5Count)` (PHP `>= max` continue). */
    fun createCount(level: Int, currentNpc5Count: Int): Int =
        (maxNpcTroopLeaderCnt(level) - currentNpc5Count).coerceAtLeast(0)

    /** `simpleSerialize(hidden, 'troopLeader', year, month, nationID)` (`:54-60`). */
    fun troopLeaderSeed(hiddenSeed: String, year: Int, month: Int, nationId: Int): String =
        serializeSeed(hiddenSeed, "troopLeader", year, month, nationId)

    /** Fresh self-seeded `troopLeader` DRBG (NOT the month-scoped `$monthlyRng`). */
    fun troopLeaderRng(hiddenSeed: String, year: Int, month: Int, nationId: Int): RandUtil =
        RandUtil(LiteHashDrbg(troopLeaderSeed(hiddenSeed, year, month, nationId)))

    /** PHP `sprintf('부대장%4d', $id)` — the int right-justified to width 4 with spaces. */
    fun troopLeaderName(npcId: Int): String = "부대장%4d".format(npcId)

    /**
     * The per-nation mint plan (IO-free): how many NPC troop-leaders to create, with their pre-assigned
     * monotonic ids + names, and the resulting `lastNPCTroopLeaderID`.
     *
     * PHP `:46-87`: `lastNPCTroopLeaderID` is read from KV (shared across nations within ONE dispatch);
     * each iteration `+= 1` BEFORE the mint, so the first new id is `startLastNpcId + 1`. The daemon
     * threads [nextLastNpcId] into the next nation's plan and persists it back to KV.
     */
    fun planNation(
        hiddenSeed: String,
        year: Int,
        month: Int,
        nationId: Int,
        level: Int,
        currentNpc5Count: Int,
        startLastNpcId: Int,
    ): NationMintPlan {
        val toCreate = createCount(level, currentNpc5Count)
        val leaders = ArrayList<NewLeader>(toCreate)
        var lastId = startLastNpcId
        repeat(toCreate) {
            lastId += 1
            leaders.add(NewLeader(npcId = lastId, name = troopLeaderName(lastId)))
        }
        return NationMintPlan(
            nationId = nationId,
            newLeaders = leaders,
            nextLastNpcId = lastId,
            seed = troopLeaderSeed(hiddenSeed, year, month, nationId),
        )
    }

    /** One nation's mint plan. [seed] is the self-seeded troopLeader DRBG seed used for the mints. */
    data class NationMintPlan(
        val nationId: Int,
        val newLeaders: List<NewLeader>,
        val nextLastNpcId: Int,
        val seed: String,
    )

    /** A planned NPC troop-leader: its pre-assigned id + `부대장%4d` name (the GeneralBuilder input). */
    data class NewLeader(val npcId: Int, val name: String)
}

/** World-context seam for [ProvideNPCTroopLeaderAction]. Implemented by the daemon/G1 adapter. */
interface ProvideNPCTroopLeaderContext : EventActionContext {
    fun nations(): List<Nation>
    fun generals(): List<General>
    fun hiddenSeed(): String
    fun year(): Int
    fun month(): Int
    fun lastNpcTroopLeaderId(): Int
    fun setLastNpcTroopLeaderId(id: Int)
    fun mintTroopLeaders(
        nationId: Int,
        leaders: List<ProvideNPCTroopLeader.NewLeader>,
        seed: String,
    )
}

/** The `ProvideNPCTroopLeader` leaf: binds the GREEN pure planner to the live world. */
class ProvideNPCTroopLeaderAction : EventAction {
    override fun run(ctx: EventActionContext) {
        val context = ctx as ProvideNPCTroopLeaderContext
        var lastId = context.lastNpcTroopLeaderId()
        val initialLastId = lastId
        val nations = context.nations()
        val generals = context.generals()
        for (nation in nations) {
            if (nation.level <= 0) {
                continue
            }
            val count = generals.count { it.nationId == nation.id && it.npcType == 5 }
            val plan = ProvideNPCTroopLeader.planNation(
                context.hiddenSeed(),
                context.year(),
                context.month(),
                nation.id,
                nation.level,
                count,
                lastId,
            )
            lastId = plan.nextLastNpcId
            if (plan.newLeaders.isNotEmpty()) {
                context.mintTroopLeaders(nation.id, plan.newLeaders, plan.seed)
            }
        }
        if (lastId != initialLastId) {
            context.setLastNpcTroopLeaderId(lastId)
        }
    }
}
