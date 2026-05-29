package opensamguk.logic.world

import opensamguk.common.rng.RandUtil
import opensamguk.logic.util.phpRound
import kotlin.math.max
import kotlin.math.sqrt

/**
 * A6 — faithful port of PHP `sammo/SpecialityHelper.php` (the specialty SELECTION engine used by
 * [assignGeneralSpeciality]). This is the selection-weight side of the specialty system; it is DISTINCT
 * from the P1 `logic/traits/ActionSpecialDomestic.kt` port, which carries only the `onCalcDomestic`
 * value-transform HOOKS (NOT the `$type`/`$selectWeight`/`$selectWeightType` selection metadata). A6 owns
 * this file (a new file, not a shared widen) so AssignGeneralSpeciality can byte-match the PHP pick.
 *
 * Bit-mask constants verbatim from `SpecialityHelper.php:6-25`.
 *
 * `pickSpecialDomestic` (`:198-257`) and `pickSpecialWar` (`:259-328`) are ported with their EXACT RNG
 * draw order:
 *   - `calcCondGeneric` (`:79-123`) consumes NO RNG.
 *   - `calcCondDexterity` (`:125-150`, war only) consumes RNG: `nextBool(0.8)` then (if false)
 *     `nextRangeInt(0,99)` then (if not below dexBase) `choice(...)`.
 *   - the candidate pool is iterated in `GameConst.availableSpecial*` order (= `getSpecialList(onlyAvailable=true)`,
 *     which excludes None/거상). The percent/relative split + the `$pAbs[0] = max(0, 100-sum)` sentinel +
 *     the prev-specials recursion are byte-faithful.
 *
 * The `$rng->choiceUsingWeight` over `$pAbs`/`$pRel`/`$reqDex` maps is keyed by the STRING specialID
 * (the legacy keys are the class-name strings, e.g. 'che_경작'); the sentinel `$pAbs[0]` is modelled as
 * the string key "0" (PHP `if($id)` treats the int 0 as falsy → we treat "0" as "fall through").
 */
object SpecialityHelper {
    // bit-masks (SpecialityHelper.php:6-25)
    const val DISABLED = 0x1

    const val STAT_LEADERSHIP = 0x2
    const val STAT_STRENGTH = 0x4
    const val STAT_INTEL = 0x8

    const val ARMY_FOOTMAN = 0x100
    const val ARMY_ARCHER = 0x200
    const val ARMY_CAVALRY = 0x400
    const val ARMY_WIZARD = 0x800
    const val ARMY_SIEGE = 0x1000

    const val REQ_DEXTERITY = 0x4000

    const val STAT_NOT_LEADERSHIP = 0x20000
    const val STAT_NOT_STRENGTH = 0x40000
    const val STAT_NOT_INTEL = 0x80000

    const val WEIGHT_NORM = 1
    const val WEIGHT_PERCENT = 2

    /**
     * `GameConst::$chiefStatMin` (`d_setting/GameConst.php:11` = 65). Defined locally (A6-owned) rather
     * than co-widening the F7/`:common` `GameConst` (the shared-extension append protocol — A6 imports
     * foundation constants, never adds to them). If F7 later exposes `GameConst.chiefStatMin`, this
     * mirrors it; the byte value is the single source of `calcCondGeneric`'s threshold.
     */
    const val chiefStatMin = 65

    /** A selectable specialty's selection metadata (PHP `BaseSpecial` static fields). */
    data class SpecialMeta(
        val id: String,            // the class-name specialID (e.g. "che_경작")
        val name: String,          // PHP `$name` (e.g. "경작")
        val selectWeightType: Int, // WEIGHT_NORM | WEIGHT_PERCENT
        val selectWeight: Double,
        val type: List<Int>,       // the OR'd condition masks; valid iff any `cond == (cond & myCond)`
    )

    /** The 8 available domestic specials, in `GameConst.availableSpecialDomestic` order (= getSpecialDomesticList()). */
    val DOMESTIC: List<SpecialMeta> = listOf(
        SpecialMeta("che_경작", "경작", WEIGHT_NORM, 1.0, listOf(STAT_INTEL)),
        SpecialMeta("che_상재", "상재", WEIGHT_NORM, 1.0, listOf(STAT_INTEL)),
        SpecialMeta("che_발명", "발명", WEIGHT_NORM, 1.0, listOf(STAT_INTEL)),
        SpecialMeta("che_축성", "축성", WEIGHT_NORM, 1.0, listOf(STAT_STRENGTH)),
        SpecialMeta("che_수비", "수비", WEIGHT_NORM, 1.0, listOf(STAT_STRENGTH)),
        SpecialMeta("che_통찰", "통찰", WEIGHT_NORM, 1.0, listOf(STAT_STRENGTH)),
        SpecialMeta("che_인덕", "인덕", WEIGHT_NORM, 1.0, listOf(STAT_LEADERSHIP)),
        SpecialMeta("che_귀모", "귀모", WEIGHT_PERCENT, 2.5,
            listOf(STAT_LEADERSHIP, STAT_STRENGTH, STAT_INTEL)),
    )

    /** The 20 available war specials, in `GameConst.availableSpecialWar` order (= getSpecialWarList()). */
    val WAR: List<SpecialMeta> = listOf(
        SpecialMeta("che_귀병", "귀병", WEIGHT_NORM, 1.0,
            listOf(STAT_INTEL or ARMY_WIZARD or REQ_DEXTERITY or STAT_NOT_STRENGTH)),
        SpecialMeta("che_신산", "신산", WEIGHT_NORM, 1.0, listOf(STAT_INTEL)),
        SpecialMeta("che_환술", "환술", WEIGHT_PERCENT, 5.0, listOf(STAT_INTEL)),
        SpecialMeta("che_집중", "집중", WEIGHT_NORM, 1.0, listOf(STAT_INTEL)),
        SpecialMeta("che_신중", "신중", WEIGHT_NORM, 1.0, listOf(STAT_INTEL)),
        SpecialMeta("che_반계", "반계", WEIGHT_NORM, 1.0, listOf(STAT_INTEL)),
        SpecialMeta("che_보병", "보병", WEIGHT_NORM, 1.0, listOf(
            STAT_LEADERSHIP or REQ_DEXTERITY or ARMY_FOOTMAN or STAT_NOT_INTEL,
            STAT_STRENGTH or REQ_DEXTERITY or ARMY_FOOTMAN,
        )),
        SpecialMeta("che_궁병", "궁병", WEIGHT_NORM, 1.0, listOf(
            STAT_LEADERSHIP or REQ_DEXTERITY or ARMY_ARCHER or STAT_NOT_INTEL,
            STAT_STRENGTH or REQ_DEXTERITY or ARMY_ARCHER,
        )),
        SpecialMeta("che_기병", "기병", WEIGHT_NORM, 1.0, listOf(
            STAT_LEADERSHIP or REQ_DEXTERITY or ARMY_CAVALRY or STAT_NOT_INTEL,
            STAT_STRENGTH or REQ_DEXTERITY or ARMY_CAVALRY,
        )),
        SpecialMeta("che_공성", "공성", WEIGHT_NORM, 1.0, listOf(
            STAT_LEADERSHIP or REQ_DEXTERITY or ARMY_SIEGE,
        )),
        SpecialMeta("che_돌격", "돌격", WEIGHT_NORM, 1.0, listOf(STAT_STRENGTH)),
        SpecialMeta("che_무쌍", "무쌍", WEIGHT_NORM, 1.0, listOf(STAT_STRENGTH)),
        SpecialMeta("che_견고", "견고", WEIGHT_NORM, 1.0, listOf(STAT_STRENGTH)),
        SpecialMeta("che_위압", "위압", WEIGHT_NORM, 1.0, listOf(STAT_STRENGTH)),
        SpecialMeta("che_저격", "저격", WEIGHT_NORM, 1.0,
            listOf(STAT_LEADERSHIP, STAT_STRENGTH, STAT_INTEL)),
        SpecialMeta("che_필살", "필살", WEIGHT_NORM, 1.0,
            listOf(STAT_LEADERSHIP, STAT_STRENGTH, STAT_INTEL)),
        SpecialMeta("che_징병", "징병", WEIGHT_NORM, 1.0,
            listOf(STAT_LEADERSHIP, STAT_STRENGTH, STAT_INTEL)),
        SpecialMeta("che_의술", "의술", WEIGHT_PERCENT, 2.0,
            listOf(STAT_LEADERSHIP, STAT_STRENGTH, STAT_INTEL)),
        SpecialMeta("che_격노", "격노", WEIGHT_NORM, 1.0, listOf(STAT_STRENGTH)),
        SpecialMeta("che_척사", "척사", WEIGHT_NORM, 1.0,
            listOf(STAT_LEADERSHIP, STAT_STRENGTH, STAT_INTEL)),
    )

    /** Name lookup (PHP `buildGeneralSpecial*Class($id)->getName()`). */
    fun domesticName(id: String): String =
        DOMESTIC.firstOrNull { it.id == id }?.name ?: id

    fun warName(id: String): String =
        WAR.firstOrNull { it.id == id }?.name ?: id

    /** PHP `calcCondGeneric` (SpecialityHelper.php:79-123) — NO RNG. */
    fun calcCondGeneric(leadership: Int, strength: Int, intel: Int): Int {
        var myCond = 0
        val chiefMin = chiefStatMin

        if (leadership > chiefMin) myCond = myCond or STAT_LEADERSHIP
        if (strength >= intel * 0.95 && strength > chiefMin) myCond = myCond or STAT_STRENGTH
        if (intel >= strength * 0.95 && intel > chiefMin) myCond = myCond or STAT_INTEL

        if (myCond != 0) {
            if (leadership < chiefMin) myCond = myCond or STAT_NOT_LEADERSHIP
            if (strength < chiefMin) myCond = myCond or STAT_NOT_STRENGTH
            if (intel < chiefMin) myCond = myCond or STAT_NOT_INTEL
        }

        if (myCond == 0) {
            myCond = if (leadership * 0.9 > strength && leadership * 0.9 > intel) {
                myCond or STAT_LEADERSHIP
            } else if (strength >= intel) {
                myCond or STAT_STRENGTH
            } else {
                myCond or STAT_INTEL
            }
        }

        return myCond
    }

    /** PHP `calcCondDexterity` (SpecialityHelper.php:125-150) — CONSUMES RNG (war only). */
    fun calcCondDexterity(rng: RandUtil, dex1: Int, dex2: Int, dex3: Int, dex4: Int, dex5: Int): Int {
        // PHP iteration order of $dex: FOOTMAN, ARCHER, CAVALRY, WIZARD, SIEGE.
        val dex = linkedMapOf(
            ARMY_FOOTMAN to dex1, ARMY_ARCHER to dex2, ARMY_CAVALRY to dex3,
            ARMY_WIZARD to dex4, ARMY_SIEGE to dex5,
        )
        val dexSum = dex.values.sum()
        val dexBase = phpRound(sqrt(dexSum.toDouble()) / 4)

        if (rng.nextBool(0.8)) return 0
        if (rng.nextRangeInt(0, 99) < dexBase) return 0
        if (dexSum == 0) {
            // PHP `$rng->choice($dex)` — choice over the VALUES (all 0 here); returns a value (0).
            return rng.choice(dex.values.toList())
        }
        // PHP `$rng->choice(array_keys($dex, max($dex)))` — keys whose value == max, in insertion order.
        val maxV = dex.values.max()
        val maxKeys = dex.entries.filter { it.value == maxV }.map { it.key }
        return rng.choice(maxKeys)
    }

    /** PHP `pickSpecialDomestic` (SpecialityHelper.php:198-257). */
    fun pickSpecialDomestic(
        rng: RandUtil,
        leadership: Int, strength: Int, intel: Int,
        prevSpecials: List<String> = emptyList(),
    ): String {
        val pAbs = LinkedHashMap<String, Double>()
        val pRel = LinkedHashMap<String, Double>()

        val myCond = calcCondGeneric(leadership, strength, intel)

        for (meta in DOMESTIC) {
            var valid = false
            for (cond in meta.type) {
                if (cond == (cond and myCond)) { valid = true; break }
            }
            if (!valid) continue
            if (meta.id in prevSpecials) continue

            if (meta.selectWeightType == WEIGHT_PERCENT) pAbs[meta.id] = meta.selectWeight
            else pRel[meta.id] = meta.selectWeight
        }

        if (pAbs.isNotEmpty()) {
            if (pRel.isNotEmpty()) pAbs["0"] = max(0.0, 100.0 - pAbs.values.sum())
            val id = rng.choiceUsingWeight(pAbs)
            if (id != "0") return id
        }

        if (pRel.isNotEmpty()) {
            val id = rng.choiceUsingWeight(pRel)
            if (id != "0") return id
        }

        if (prevSpecials.isNotEmpty()) {
            return pickSpecialDomestic(rng, leadership, strength, intel, emptyList())
        }

        throw IllegalStateException("MustNotBeReached: domestic pick ($myCond)")
    }

    /** PHP `pickSpecialWar` (SpecialityHelper.php:259-328). */
    fun pickSpecialWar(
        rng: RandUtil,
        leadership: Int, strength: Int, intel: Int,
        dex1: Int, dex2: Int, dex3: Int, dex4: Int, dex5: Int,
        prevSpecials: List<String> = emptyList(),
    ): String {
        val reqDex = LinkedHashMap<String, Double>()
        val pAbs = LinkedHashMap<String, Double>()
        val pRel = LinkedHashMap<String, Double>()

        var myCond = calcCondGeneric(leadership, strength, intel)
        myCond = myCond or calcCondDexterity(rng, dex1, dex2, dex3, dex4, dex5)
        myCond = myCond or REQ_DEXTERITY

        for (meta in WAR) {
            var valid = false
            var matchedCond = 0
            for (cond in meta.type) {
                if (cond == (cond and myCond)) { valid = true; matchedCond = cond; break }
            }
            if (!valid) continue
            if (meta.id in prevSpecials) continue

            // PHP reuses the loop var $cond AFTER the inner foreach (the LAST evaluated cond → the matched one).
            if (matchedCond and REQ_DEXTERITY != 0) reqDex[meta.id] = meta.selectWeight
            else if (meta.selectWeightType == WEIGHT_PERCENT) pAbs[meta.id] = meta.selectWeight
            else pRel[meta.id] = meta.selectWeight
        }

        if (reqDex.isNotEmpty()) return rng.choiceUsingWeight(reqDex)

        if (pAbs.isNotEmpty()) {
            if (pRel.isNotEmpty()) pAbs["0"] = max(0.0, 100.0 - pAbs.values.sum())
            val id = rng.choiceUsingWeight(pAbs)
            if (id != "0") return id
        }

        if (pRel.isNotEmpty()) {
            val id = rng.choiceUsingWeight(pRel)
            if (id != "0") return id
        }

        if (prevSpecials.isNotEmpty()) {
            return pickSpecialWar(rng, leadership, strength, intel, dex1, dex2, dex3, dex4, dex5, emptyList())
        }

        throw IllegalStateException("MustNotBeReached: war pick")
    }
}
