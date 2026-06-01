package opensamguk.logic.world

import kotlinx.serialization.json.JsonElement
import opensamguk.common.constants.GameConst
import opensamguk.common.josa.JosaUtil
import opensamguk.common.rng.LiteHashDrbg
import opensamguk.common.rng.RandUtil
import opensamguk.common.rng.serializeSeed
import opensamguk.logic.event.EventAction
import opensamguk.logic.event.EventActionContext

/**
 * A6b — AssignGeneralSpeciality (PHP `sammo/Event/Action/AssignGeneralSpeciality.php`), a gate-byte-matched
 * `$defaultEvents` leaf firing at month==1 (priority 9000, LAST in the January row). Self-seeds its OWN
 * DRBG (it does NOT borrow `$monthlyRng`).
 *
 * The pure body [assignGeneralSpeciality] mirrors the GREEN [randomizeCityTradeRate] / [raiseDisaster]
 * shape: plain input rows (the PHP `SELECT` columns) + a [RandUtil] → a deterministic
 * [AssignSpecialityResult]. [AssignGeneralSpecialityAction] is the F2 leaf (registered "AssignGeneralSpeciality").
 *
 * Faithful-port pins (PHP grand truth, AssignGeneralSpeciality.php):
 *   - `if ($year < $startYear + 3) return;` (`:27-29`) — early return BEFORE building the rng (NO draw).
 *   - seed `simpleSerialize(UniqueConst::$hiddenSeed, 'assignGeneralSpeciality', year, month)` (`:33-38`).
 *   - DOMESTIC loop (`:40-58`): `WHERE specage<=age AND special=GameConst::$defaultSpecialDomestic`, PK asc;
 *     each → `SpecialityHelper::pickSpecialDomestic(rng, general, aux['prev_types_special'] ?? [])`.
 *   - WAR loop (`:60-88`): `WHERE specage2<=age AND special2=GameConst::$defaultSpecialWar`, PK asc;
 *     if `aux['inheritSpecificSpecialWar']` is set → use it verbatim (NO RNG pick, unset the aux key);
 *     else `SpecialityHelper::pickSpecialWar(rng, general, aux['prev_types_special2'] ?? [])`.
 *   - the DOMESTIC loop FULLY precedes the WAR loop (they share the one rng stream); within each, the
 *     per-general draw order is the supplied (PK-ascending) iteration order — the SINGLE parity risk.
 *   - per-general logs (`:53-57`, `:83-87`): josaUl = JosaUtil::pick(name, '을'); the action/history strings
 *     embed the specialName verbatim.
 *
 * The SpecialityHelper selection engine (the `$type`/`$selectWeight`/`$selectWeightType` metadata +
 * pick weight math + calcCondDexterity) is the A6-owned [SpecialityHelper] port (distinct from the P1
 * `onCalcDomestic` hooks). The result is fully deterministic + byte-faithful.
 */

/** PHP domestic+war `SELECT` general row (PK-ascending). dex1..5/npc are read by the WAR pick. */
data class SpecialityGeneral(
    val no: Int,
    val name: String,
    val nation: Int,
    val age: Int,
    val specage: Int,
    val specage2: Int,
    val special: String,
    val special2: String,
    val leadership: Int,
    val strength: Int,
    val intel: Int,
    val dex1: Int = 0,
    val dex2: Int = 0,
    val dex3: Int = 0,
    val dex4: Int = 0,
    val dex5: Int = 0,
    val npc: Int = 0,
    val aux: Map<String, Any?> = emptyMap(),
)

/** One assigned specialty (domestic or war) with its rendered logs. */
data class SpecialityAssignment(
    val generalId: Int,
    val nation: Int,
    val special: String,          // the chosen specialID (e.g. "che_경작")
    val specialName: String,      // the display name (e.g. "경작")
    val actionLog: String,        // "특기 【<b><L>{name}</></b>】{josa} 익혔습니다!"
    val historyLog: String,       // "특기 【<b><C>{name}</></b>】{josa} 습득"
    /** WAR only: true when sourced from aux['inheritSpecificSpecialWar'] (no RNG pick). */
    val inheritedFromAux: Boolean = false,
    /** WAR only: the aux to write back when inheritSpecificSpecialWar was consumed (key removed); null otherwise. */
    val updatedAux: Map<String, Any?>? = null,
)

/** Deterministic AssignGeneralSpeciality outcome. */
data class AssignSpecialityResult(
    val skippedByYearGate: Boolean,
    val domesticAssignments: List<SpecialityAssignment>,
    val warAssignments: List<SpecialityAssignment>,
)

@Suppress("UNCHECKED_CAST")
private fun prevTypes(aux: Map<String, Any?>, key: String): List<String> =
    (aux[key] as? List<*>)?.mapNotNull { it as? String } ?: emptyList()

private fun domesticActionLog(name: String): String {
    val josa = JosaUtil.pick(name, "을")
    return "특기 【<b><L>$name</></b>】$josa 익혔습니다!"
}

private fun domesticHistoryLog(name: String): String {
    val josa = JosaUtil.pick(name, "을")
    return "특기 【<b><C>$name</></b>】$josa 습득"
}

/**
 * AssignGeneralSpeciality pure body. Generals MUST be supplied in PK-ascending order (the contract =
 * PHP `SELECT ... FROM general ... ` PK asc). The same `generals` list is filtered TWICE (domestic then
 * war), exactly as the two PHP queries scan the table in order.
 */
fun assignGeneralSpeciality(
    generals: List<SpecialityGeneral>,
    year: Int,
    month: Int,
    startYear: Int,
    rng: RandUtil,
): AssignSpecialityResult {
    if (year < startYear + 3) {
        return AssignSpecialityResult(skippedByYearGate = true, emptyList(), emptyList())
    }

    // DOMESTIC loop: WHERE specage<=age AND special==defaultSpecialDomestic, PK asc.
    val domestic = ArrayList<SpecialityAssignment>()
    for (g in generals) {
        if (g.specage > g.age) continue
        if (g.special != GameConst.defaultSpecialDomestic) continue
        val special = SpecialityHelper.pickSpecialDomestic(
            rng, g.leadership, g.strength, g.intel, prevTypes(g.aux, "prev_types_special"))
        val name = SpecialityHelper.domesticName(special)
        domestic.add(SpecialityAssignment(
            generalId = g.no, nation = g.nation, special = special, specialName = name,
            actionLog = domesticActionLog(name), historyLog = domesticHistoryLog(name)))
    }

    // WAR loop: WHERE specage2<=age AND special2==defaultSpecialWar, PK asc.
    val war = ArrayList<SpecialityAssignment>()
    for (g in generals) {
        if (g.specage2 > g.age) continue
        if (g.special2 != GameConst.defaultSpecialWar) continue

        val inherit = g.aux["inheritSpecificSpecialWar"] as? String
        val special2: String
        val inherited: Boolean
        val updatedAux: Map<String, Any?>?
        if (inherit != null) {
            special2 = inherit
            inherited = true
            // PHP unsets inheritSpecificSpecialWar and re-encodes aux.
            updatedAux = g.aux.filterKeys { it != "inheritSpecificSpecialWar" }
        } else {
            special2 = SpecialityHelper.pickSpecialWar(
                rng, g.leadership, g.strength, g.intel,
                g.dex1, g.dex2, g.dex3, g.dex4, g.dex5,
                prevTypes(g.aux, "prev_types_special2"))
            inherited = false
            updatedAux = null
        }
        val name = SpecialityHelper.warName(special2)
        war.add(SpecialityAssignment(
            generalId = g.no, nation = g.nation, special = special2, specialName = name,
            actionLog = domesticActionLog(name), historyLog = domesticHistoryLog(name),
            inheritedFromAux = inherited, updatedAux = updatedAux))
    }

    return AssignSpecialityResult(skippedByYearGate = false, domesticAssignments = domestic, warAssignments = war)
}

/**
 * The F2 event leaf for "AssignGeneralSpeciality". Self-seeds `RandUtil(LiteHashDrbg(serializeSeed(hidden,
 * 'assignGeneralSpeciality', year, month)))` and runs [assignGeneralSpeciality] over the world's generals
 * (PK-ascending). The env carries "hiddenSeed"/"year"/"month"/"startYear" + a [SpecialityWorldView] under
 * [ENV_WORLD]; absent → no-op. Registered into the [opensamguk.logic.event.EventActionFactory] by name;
 * F2 already names it LAST in the month==1 row.
 */
class AssignGeneralSpecialityAction(@Suppress("UNUSED_PARAMETER") args: List<JsonElement> = emptyList()) : EventAction {
    override fun run(ctx: EventActionContext) {
        val env = ctx.env
        val world = env[ENV_WORLD] as? SpecialityWorldView ?: return
        val hiddenSeed = env[ENV_HIDDEN_SEED] as? String ?: return
        val year = (env["year"] as? Number)?.toInt() ?: return
        val month = (env["month"] as? Number)?.toInt() ?: return
        val startYear = (env["startYear"] as? Number)?.toInt() ?: return

        // Early-return BEFORE building the rng (PHP parity: no DRBG constructed in the skip window).
        if (year < startYear + 3) return

        val rng = RandUtil(LiteHashDrbg(serializeSeed(hiddenSeed, "assignGeneralSpeciality", year, month)))
        val result = assignGeneralSpeciality(world.specialityGenerals(), year, month, startYear, rng)
        world.applySpeciality(result)
    }

    companion object {
        const val NAME = "AssignGeneralSpeciality"
        const val ENV_WORLD = "specialityWorld"
        const val ENV_HIDDEN_SEED = "hiddenSeed"

        /** Register the leaf into the F2-owned factory by name (plan §append protocol). */
        fun register(factory: opensamguk.logic.event.EventActionFactory): opensamguk.logic.event.EventActionFactory =
            factory.register(NAME) { args -> AssignGeneralSpecialityAction(args) }
    }
}

/**
 * The world seam AssignGeneralSpeciality needs (supplied by the tick's richer context). A6-owned + minimal
 * so the leaf stays inside A6's file boundary while the daemon-side world adapter (F1/G1 wiring) implements it.
 */
interface SpecialityWorldView {
    /** Generals in PK-ascending order (the PHP `SELECT ... FROM general` order). */
    fun specialityGenerals(): List<SpecialityGeneral>

    /** Apply the computed assignments: write general.special/special2 (+ aux), push the action/history logs. */
    fun applySpeciality(result: AssignSpecialityResult)
}
