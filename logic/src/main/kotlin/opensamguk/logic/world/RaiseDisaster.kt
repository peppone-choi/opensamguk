package opensamguk.logic.world

import kotlinx.serialization.json.JsonElement
import opensamguk.common.rng.LiteHashDrbg
import opensamguk.common.rng.RandUtil
import opensamguk.common.rng.serializeSeed
import opensamguk.logic.event.EventAction
import opensamguk.logic.event.EventActionContext
import opensamguk.logic.util.valueFit

/**
 * A6a — RaiseDisaster (PHP `sammo/Event/Action/RaiseDisaster.php`), a gate-byte-matched `$defaultEvents`
 * leaf firing at month 1/4/7/10 (priority 9000). Self-seeds its OWN DRBG (it does NOT borrow
 * `$monthlyRng`, which is reserved for postUpdateMonthly).
 *
 * The pure body [raiseDisaster] mirrors the GREEN [randomizeCityTradeRate] shape: it takes plain input
 * rows (the PHP `SELECT city,name,secu,secu_max FROM city` columns, PK-ascending) + a [RandUtil] and
 * returns a fully-deterministic [RaiseDisasterResult]. The [RaiseDisasterAction] wrapper is the F2
 * leaf (registered by name "RaiseDisaster").
 *
 * Faithful-port pins (PHP frozen historical baseline (ADR-LITE-042; not current product authority)):
 *   - seed `simpleSerialize(UniqueConst::$hiddenSeed, 'disater', year, month)` — the LEGACY MISSPELLING
 *     'disater' is byte-matched verbatim (RaiseDisaster.php:25-30). Do NOT "correct" to 'disaster'.
 *   - `$db->update('city', ['state'=>0], 'state <= 10')` — UNCONDITIONAL state reset, always (even within
 *     the startYear+3 skip window), BEFORE the year gate (RaiseDisaster.php:32-35).
 *   - `if ($startYear + 3 > $year) return;` — skip the disaster table for the first 3 years (`:38`).
 *   - `$isGood = $rng->nextBool($boomingRate[$month])`, boomingRate {1:0, 4:0.25, 7:0.25, 10:0} (`:40-47`).
 *     boomingRate 0 → `nextBool(0)` returns false WITHOUT a draw; 0.25 → exactly one `nextFloat1()` draw.
 *   - per city in PK-ascending order: raiseProp = isGood ? `0.02 + secu/secu_max*0.05` : `0.06 - secu/secu_max*0.05`;
 *     `if ($rng->nextBool($raiseProp)) $targetCityList[] = $city` (`:52-64`). The per-city draw ORDER is the
 *     RNG stream — PK ascending; selecting/skipping a city changes the downstream stream identically to PHP.
 *   - if NO target → return (`:66-68`); else `$rng->choice(textList[$month])` picks ONE log triple
 *     `[logTitle, stateCode, logBody]` (`:107`) and the global history log is
 *     `"{$logTitle}<G><b>{names joined by ' '}</b></>에 {$logBody}"` (`:70-111`).
 *   - per-target stat multiplier (RaiseDisaster.php:122-160): isGood(호황) → `affectRatio = 1.01 + valueFit(secu/secu_max/0.8,0,1)*0.04`,
 *     each stat `least(stat * ratio, max)`; bad(재난) → `affectRatio = 0.8 + valueFit(secu/secu_max/0.8,0,1)*0.15`,
 *     each stat `stat * ratio` (no ceiling). The chosen `stateCode` writes to `city.state`.
 *     무츠(mutated) 스탯 = pop·agri·comm·secu·def·wall·**trust** (PHP `trust` 도 동일 `affectRatio` 곱).
 *     trust 는 schema FLOAT 컬럼 → RaiseDisaster.php:129 `trust * %d` (재난, 무캡) /
 *     RaiseDisaster.php:154 `least(trust * %d, 100)` (호황, 리터럴 100 캡, trust_max 아님). round() 없음 = 생 float 곱.
 *     trust 는 (Int·max 캡과 무관한) Double 이라 [DisasterCityEffect] 자체엔 별도 필드 없이
 *     엔진 적용단(WorldActionContext.applyDisaster)이 `affectRatio`+`capped` 로 `preTrust * affectRatio` 를 계산한다.
 *
 * SEAM (documented bound): the BAD branch also runs `SabotageInjury($rng, generalsInTargetCity, '재난')`
 * (RaiseDisaster.php:114-145) which draws PER GENERAL via the un-ported `onCalcStat('injuryProb')` general
 * stat stack + `increaseVarWithLimit`/`multiplyVar`. That subsystem is outside A6's file boundary and, for
 * the gate oracle scenario_1010 (startYear=181), the whole disaster table is in the year<184 skip window
 * for low N. The pure body therefore EXPOSES the ordered target list ([RaiseDisasterResult.targetCityIds],
 * which is the exact `SabotageInjury` city order) and STOPS before the per-general injury draws — the
 * caller (a richer wiring context once the general-stat module stack is wired) drives SabotageInjury over
 * those cities. The state reset + booming/disaster rolls + log choice (all the RNG that runs BEFORE
 * SabotageInjury) are fully byte-faithful here. See [RaiseDisasterResult.rngBeforeInjury].
 */

/** PHP `SELECT city,name,secu,secu_max FROM city` row (+ `state`, read for the reset map). PK-ascending. */
data class DisasterCity(
    val cityId: Int,
    val name: String,
    val state: Int,
    val secu: Int,
    val secuMax: Int,
)

/** One disaster/booming city mutation (the `$stateCode` + `affectRatio` applied to the stat block). */
data class DisasterCityEffect(
    val cityId: Int,
    val stateCode: Int,
    val affectRatio: Double,
    /**
     * true(호황) → 정수 스탯은 `least(stat*ratio, max)`, trust 는 `least(trust*ratio, 100)`(리터럴 100 캡);
     * false(재난) → 정수 스탯은 `stat*ratio`, trust 는 `trust*ratio`(무캡). trust(FLOAT)는 round() 없이 생 float 곱.
     * (RaiseDisaster.php:129 재난 / :154 호황 — trust 는 pop·agri·comm·secu·def·wall 과 SAME city-update.)
     */
    val capped: Boolean,
)

/** Deterministic RaiseDisaster outcome (everything up to, but not including, the per-general SabotageInjury). */
data class RaiseDisasterResult(
    /** `city.state = 0 WHERE state <= 10` — the unconditional reset map (cityId → 0). */
    val stateResets: Map<Int, Int>,
    /** true iff `year < startYear + 3` (disaster table skipped; only the reset ran). */
    val skippedByYearGate: Boolean,
    /** `nextBool(boomingRate[month])` (false in the skip window — never drawn there). */
    val isGood: Boolean,
    /** target cities in PK-ascending iteration order (= the SabotageInjury city order). */
    val targetCityIds: List<Int>,
    /** the chosen global history log line (null when no target / skip window). */
    val logLine: String?,
    /** the `stateCode` of the chosen log triple (null when no target). */
    val stateCode: Int?,
    /** per-target stat mutation (PK order); empty in the skip window / no-target case. */
    val effects: List<DisasterCityEffect>,
    /** marker: the bad branch still owes SabotageInjury draws over [targetCityIds] (the documented seam). */
    val rngBeforeInjury: Boolean = true,
)

/** boomingRate table (RaiseDisaster.php:40-45); any other month is off-table (no booming). */
private val BOOMING_RATE: Map<Int, Double> = mapOf(1 to 0.0, 4 to 0.25, 7 to 0.25, 10 to 0.0)

/** One log triple `[title, stateCode, body]` (PHP `$rng->choice` element). */
private data class LogTriple(val title: String, val stateCode: Int, val body: String)

/**
 * disaster (bad) text list per month (RaiseDisaster.php:71-94).
 * Product divergence: state 9 is a generic city-level 민란. 황건적 is a proper faction/태평도
 * identity and 도적 is a nation type, so neither belongs in a reusable city disaster state.
 */
private val DISASTER_TEXT: Map<Int, List<LogTriple>> = mapOf(
    1 to listOf(
        LogTriple("<M><b>【재난】</b></>", 4, "역병이 발생하여 도시가 황폐해지고 있습니다."),
        LogTriple("<M><b>【재난】</b></>", 5, "지진으로 피해가 속출하고 있습니다."),
        LogTriple("<M><b>【재난】</b></>", 3, "추위가 풀리지 않아 얼어죽는 백성들이 늘어나고 있습니다."),
        LogTriple("<M><b>【재난】</b></>", 9, "민란이 일어나 관아와 도시가 혼란에 빠졌습니다."),
    ),
    4 to listOf(
        LogTriple("<M><b>【재난】</b></>", 7, "홍수로 인해 피해가 급증하고 있습니다."),
        LogTriple("<M><b>【재난】</b></>", 5, "지진으로 피해가 속출하고 있습니다."),
        LogTriple("<M><b>【재난】</b></>", 6, "태풍으로 인해 피해가 속출하고 있습니다."),
    ),
    7 to listOf(
        LogTriple("<M><b>【재난】</b></>", 8, "메뚜기 떼가 발생하여 도시가 황폐해지고 있습니다."),
        LogTriple("<M><b>【재난】</b></>", 5, "지진으로 피해가 속출하고 있습니다."),
        LogTriple("<M><b>【재난】</b></>", 8, "흉년이 들어 굶어죽는 백성들이 늘어나고 있습니다."),
    ),
    10 to listOf(
        LogTriple("<M><b>【재난】</b></>", 3, "혹한으로 도시가 황폐해지고 있습니다."),
        LogTriple("<M><b>【재난】</b></>", 5, "지진으로 피해가 속출하고 있습니다."),
        LogTriple("<M><b>【재난】</b></>", 3, "눈이 많이 쌓여 도시가 황폐해지고 있습니다."),
        LogTriple("<M><b>【재난】</b></>", 9, "민란이 일어나 관아와 도시가 혼란에 빠졌습니다."),
    ),
)

/** booming (good) text list per month (RaiseDisaster.php:96-105); months 1/10 are null (never good). */
private val BOOMING_TEXT: Map<Int, List<LogTriple>> = mapOf(
    4 to listOf(LogTriple("<C><b>【호황】</b></>", 2, "호황으로 도시가 번창하고 있습니다.")),
    7 to listOf(LogTriple("<C><b>【풍작】</b></>", 1, "풍작으로 도시가 번창하고 있습니다.")),
)

/**
 * RaiseDisaster pure body. Cities MUST be supplied in PK-ascending order (PHP `SELECT ... FROM city`).
 * Returns everything the Action computes up to (not including) the per-general SabotageInjury draws.
 */
fun raiseDisaster(
    cities: List<DisasterCity>,
    year: Int,
    month: Int,
    startYear: Int,
    rng: RandUtil,
): RaiseDisasterResult {
    // 1) UNCONDITIONAL state reset (state <= 10 → 0), always — BEFORE the year gate.
    val stateResets = LinkedHashMap<Int, Int>()
    for (c in cities) if (c.state <= 10) stateResets[c.cityId] = 0

    // 2) 초반 3년 스킵: skip the disaster table (NO booming roll, NO per-city rolls).
    if (startYear + 3 > year) {
        return RaiseDisasterResult(
            stateResets = stateResets,
            skippedByYearGate = true,
            isGood = false,
            targetCityIds = emptyList(),
            logLine = null,
            stateCode = null,
            effects = emptyList(),
        )
    }

    // 3) booming roll — nextBool(0) (months 1/10) draws NOTHING; 0.25 draws one float.
    val isGood = rng.nextBool(BOOMING_RATE[month] ?: 0.0)

    // 4) per-city selection in PK-ascending order (the RNG stream order).
    val targets = ArrayList<DisasterCity>()
    for (c in cities) {
        val ratio = if (c.secuMax == 0) 0.0 else c.secu.toDouble() / c.secuMax
        val raiseProp = if (isGood) 0.02 + ratio * 0.05 else 0.06 - ratio * 0.05
        if (rng.nextBool(raiseProp)) targets.add(c)
    }

    if (targets.isEmpty()) {
        return RaiseDisasterResult(
            stateResets = stateResets,
            skippedByYearGate = false,
            isGood = isGood,
            targetCityIds = emptyList(),
            logLine = null,
            stateCode = null,
            effects = emptyList(),
        )
    }

    // 5) choose ONE log triple for the month (PHP `$rng->choice`) AFTER target selection.
    val textList = (if (isGood) BOOMING_TEXT else DISASTER_TEXT).getValue(month)
    val chosen = rng.choice(textList)

    // 6) global history log line: "{title}<G><b>{names ' '}</b></>에 {body}".
    val names = targets.joinToString(" ") { it.name }
    val targetCityNames = "<G><b>$names</b></>"
    val logLine = "${chosen.title}${targetCityNames}에 ${chosen.body}"

    // 7) per-target stat mutation (the `affectRatio` + stateCode write).
    val effects = targets.map { c ->
        val base = if (c.secuMax == 0) 0.0 else c.secu.toDouble() / c.secuMax / 0.8
        val fit = valueFit(base, 0.0, 1.0)
        val affectRatio = if (isGood) 1.01 + fit * 0.04 else 0.8 + fit * 0.15
        DisasterCityEffect(cityId = c.cityId, stateCode = chosen.stateCode, affectRatio = affectRatio, capped = isGood)
    }

    return RaiseDisasterResult(
        stateResets = stateResets,
        skippedByYearGate = false,
        isGood = isGood,
        targetCityIds = targets.map { it.cityId },
        logLine = logLine,
        stateCode = chosen.stateCode,
        effects = effects,
    )
}

/**
 * The F2 event leaf for "RaiseDisaster". Self-seeds `RandUtil(LiteHashDrbg(serializeSeed(hidden,'disater',
 * year,month)))` and runs [raiseDisaster] over the world's cities (PK-ascending). Registered into the
 * [opensamguk.logic.event.EventActionFactory] by name; F2 already names it in the month 1/4/7/10 rows.
 *
 * The env must carry: "hiddenSeed" (String), "year"/"month"/"startYear" (Int), and a world snapshot the
 * context exposes as [DisasterWorldView]. The default dispatcher [EventActionContext] only carries `env`,
 * so the tick supplies a richer context implementing [EventActionContext] whose `env` also holds the
 * world view + sinks under the keys below. This leaf reads them defensively (no-op if absent).
 */
class RaiseDisasterAction(@Suppress("UNUSED_PARAMETER") args: List<JsonElement> = emptyList()) : EventAction {
    override fun run(ctx: EventActionContext) {
        val env = ctx.env
        val world = env[ENV_WORLD] as? DisasterWorldView ?: return
        val hiddenSeed = env[ENV_HIDDEN_SEED] as? String ?: return
        val year = (env["year"] as? Number)?.toInt() ?: return
        val month = (env["month"] as? Number)?.toInt() ?: return
        val startYear = (env["startYear"] as? Number)?.toInt() ?: return

        val rng = RandUtil(LiteHashDrbg(serializeSeed(hiddenSeed, "disater", year, month)))
        val result = raiseDisaster(world.disasterCities(), year, month, startYear, rng)
        world.applyDisaster(result)
    }

    companion object {
        const val NAME = "RaiseDisaster"
        const val ENV_WORLD = "disasterWorld"
        const val ENV_HIDDEN_SEED = "hiddenSeed"

        /** Register the leaf into the F2-owned factory by name (plan §append protocol). */
        fun register(factory: opensamguk.logic.event.EventActionFactory): opensamguk.logic.event.EventActionFactory =
            factory.register(NAME) { args -> RaiseDisasterAction(args) }
    }
}

/**
 * The world seam RaiseDisaster needs (supplied by the tick's richer context). Kept minimal + A6-owned so
 * the leaf stays inside A6's file boundary while the daemon-side world adapter (F1/G1 wiring) implements it.
 */
interface DisasterWorldView {
    /** Cities in PK-ascending order (PHP `SELECT city,name,secu,secu_max FROM city`). */
    fun disasterCities(): List<DisasterCity>

    /** Apply the computed result: the state resets, the per-target stat mutations + log, the SabotageInjury seam. */
    fun applyDisaster(result: RaiseDisasterResult)
}
