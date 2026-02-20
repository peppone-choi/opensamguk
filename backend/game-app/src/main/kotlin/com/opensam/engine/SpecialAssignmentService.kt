package com.opensam.engine

import com.opensam.entity.General
import com.opensam.entity.WorldState
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random

@Service
class SpecialAssignmentService {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        private const val CHIEF_STAT_MIN = 60
        private const val RETIREMENT_AGE = 80

        // Stat bitmask constants
        private const val STAT_LEADERSHIP = 0x2
        private const val STAT_STRENGTH = 0x4
        private const val STAT_INTEL = 0x8
        private const val STAT_NOT_LEADERSHIP = 0x20000
        private const val STAT_NOT_STRENGTH = 0x40000
        private const val STAT_NOT_INTEL = 0x80000

        // Army type bitmask constants (for dexterity-based selection)
        private const val ARMY_FOOTMAN = 0x100
        private const val ARMY_ARCHER = 0x200
        private const val ARMY_CAVALRY = 0x400
        private const val ARMY_WIZARD = 0x800
        private const val ARMY_SIEGE = 0x1000
        private const val REQ_DEXTERITY = 0x4000

        // Dex meta key → army bitmask (dex0=보병, dex1=궁병, dex2=기병, dex3=극병, dex4=차병)
        private val DEX_ARMY_MAP = listOf(
            "dex0" to ARMY_FOOTMAN,
            "dex1" to ARMY_ARCHER,
            "dex2" to ARMY_CAVALRY,
            "dex3" to ARMY_WIZARD,
            "dex4" to ARMY_SIEGE,
        )

        // Domestic specials: code → (name, required bitmask conditions, weight)
        private val DOMESTIC_SPECIALS = listOf(
            SpecialCandidate("농업", listOf(STAT_INTEL, STAT_LEADERSHIP), 10),
            SpecialCandidate("상업", listOf(STAT_INTEL, STAT_LEADERSHIP), 10),
            SpecialCandidate("징수", listOf(STAT_LEADERSHIP), 8),
            SpecialCandidate("보수", listOf(STAT_STRENGTH, STAT_LEADERSHIP), 8),
            SpecialCandidate("발명", listOf(STAT_INTEL), 5),
            SpecialCandidate("의술", listOf(STAT_INTEL), 5),
            SpecialCandidate("치료", listOf(STAT_INTEL), 3),
            SpecialCandidate("인덕", listOf(STAT_LEADERSHIP), 8),
            SpecialCandidate("등용", listOf(STAT_LEADERSHIP, STAT_INTEL), 6),
            SpecialCandidate("정치", listOf(STAT_LEADERSHIP, STAT_INTEL), 10),
            SpecialCandidate("건축", listOf(STAT_STRENGTH, STAT_LEADERSHIP), 6),
            SpecialCandidate("훈련_특기", listOf(STAT_STRENGTH, STAT_LEADERSHIP), 8),
            SpecialCandidate("모병_특기", listOf(STAT_STRENGTH, STAT_LEADERSHIP), 8),
        )

        // War specials: dexterity-gated entries use compound bitmasks with REQ_DEXTERITY + ARMY_*
        private val WAR_SPECIALS = listOf(
            // Dexterity-gated specials (require crew-type dex match)
            SpecialCandidate("기병", listOf(
                STAT_LEADERSHIP or REQ_DEXTERITY or ARMY_CAVALRY or STAT_NOT_INTEL,
                STAT_STRENGTH or REQ_DEXTERITY or ARMY_CAVALRY
            ), 10),
            SpecialCandidate("보병", listOf(
                STAT_LEADERSHIP or REQ_DEXTERITY or ARMY_FOOTMAN or STAT_NOT_INTEL,
                STAT_STRENGTH or REQ_DEXTERITY or ARMY_FOOTMAN
            ), 10),
            SpecialCandidate("궁병", listOf(
                STAT_LEADERSHIP or REQ_DEXTERITY or ARMY_ARCHER or STAT_NOT_INTEL,
                STAT_STRENGTH or REQ_DEXTERITY or ARMY_ARCHER
            ), 8),
            SpecialCandidate("공성", listOf(
                STAT_LEADERSHIP or REQ_DEXTERITY or ARMY_SIEGE
            ), 5),
            // Stat-only specials
            SpecialCandidate("필살", listOf(STAT_STRENGTH), 5),
            SpecialCandidate("회피", listOf(STAT_STRENGTH, STAT_LEADERSHIP), 5),
            SpecialCandidate("화공", listOf(STAT_INTEL), 8),
            SpecialCandidate("기습", listOf(STAT_STRENGTH, STAT_INTEL), 5),
            SpecialCandidate("저격", listOf(STAT_STRENGTH), 5),
            SpecialCandidate("매복", listOf(STAT_INTEL, STAT_LEADERSHIP), 5),
            SpecialCandidate("방어", listOf(STAT_LEADERSHIP), 8),
            SpecialCandidate("돌격", listOf(STAT_STRENGTH), 5),
            SpecialCandidate("반계", listOf(STAT_INTEL), 5),
            SpecialCandidate("신산", listOf(STAT_INTEL), 3),
            SpecialCandidate("귀모", listOf(STAT_INTEL), 3),
            SpecialCandidate("수군", listOf(STAT_LEADERSHIP), 6),
            SpecialCandidate("연사", listOf(STAT_STRENGTH), 6),
            SpecialCandidate("위압", listOf(STAT_LEADERSHIP), 5),
            SpecialCandidate("격노", listOf(STAT_STRENGTH), 5),
            SpecialCandidate("분투", listOf(STAT_STRENGTH), 5),
            SpecialCandidate("용병", listOf(STAT_LEADERSHIP), 5),
            SpecialCandidate("철벽", listOf(STAT_LEADERSHIP), 5),
        )
    }

    data class SpecialCandidate(
        val code: String,
        val requiredStats: List<Int>,
        val weight: Int,
    )

    fun checkAndAssignSpecials(world: WorldState, generals: List<General>) {
        val startYear = try {
            (world.config["startYear"] as? Number)?.toInt() ?: world.currentYear.toInt()
        } catch (_: Exception) {
            world.currentYear.toInt()
        }

        // Skip first 3 years
        if (world.currentYear.toInt() < startYear + 3) return

        val rng = DeterministicRng.create(
            "${world.id}", "assignSpeciality",
            world.currentYear, world.currentMonth
        )

        val relYear = maxOf(world.currentYear.toInt() - startYear, 0)

        for (general in generals) {
            // Domestic special assignment: age >= specAge threshold
            if (general.specialCode == "None" && general.age >= general.specAge) {
                val prevSpecials = getPrevSpecials(general, "prev_special")
                val newSpecial = pickDomesticSpecial(rng, general, prevSpecials)
                if (newSpecial != null) {
                    general.specialCode = newSpecial
                    general.specAge = calcDomesticSpecAge(general.age.toInt(), relYear)
                    log.info("General {} assigned domestic special: {}", general.name, newSpecial)
                }
            }

            // War special assignment: age >= spec2Age threshold
            if (general.special2Code == "None" && general.age >= general.spec2Age) {
                // Check if inherited special is designated
                val inheritedSpecial = general.meta["inheritSpecificSpecialWar"] as? String
                if (inheritedSpecial != null) {
                    general.special2Code = inheritedSpecial
                    general.spec2Age = calcWarSpecAge(general.age.toInt(), relYear)
                    general.meta.remove("inheritSpecificSpecialWar")
                    log.info("General {} assigned inherited war special: {}", general.name, inheritedSpecial)
                } else {
                    val prevSpecials = getPrevSpecials(general, "prev_special2")
                    val newSpecial = pickWarSpecial(rng, general, prevSpecials)
                    if (newSpecial != null) {
                        general.special2Code = newSpecial
                        general.spec2Age = calcWarSpecAge(general.age.toInt(), relYear)
                        log.info("General {} assigned war special: {}", general.name, newSpecial)
                    }
                }
            }
        }
    }

    /** Domestic specAge threshold: max(round((80 - age) / 12 - relYear / 2), 3) + age */
    private fun calcDomesticSpecAge(age: Int, relYear: Int): Short {
        val wait = maxOf(((RETIREMENT_AGE - age) / 12.0 - relYear / 2.0).roundToInt(), 3)
        return (wait + age).toShort()
    }

    /** War spec2Age threshold: max(round((80 - age) / 6 - relYear / 2), 3) + age */
    private fun calcWarSpecAge(age: Int, relYear: Int): Short {
        val wait = maxOf(((RETIREMENT_AGE - age) / 6.0 - relYear / 2.0).roundToInt(), 3)
        return (wait + age).toShort()
    }

    private fun getPrevSpecials(general: General, metaKey: String): List<String> {
        @Suppress("UNCHECKED_CAST")
        return (general.meta[metaKey] as? List<String>) ?: emptyList()
    }

    private fun calcStatCondition(general: General): Int {
        var cond = 0
        val leadership = general.leadership.toInt()
        val strength = general.strength.toInt()
        val intel = general.intel.toInt()

        if (leadership > CHIEF_STAT_MIN) {
            cond = cond or STAT_LEADERSHIP
        }
        if (strength >= (intel * 0.95).toInt() && strength > CHIEF_STAT_MIN) {
            cond = cond or STAT_STRENGTH
        }
        if (intel >= (strength * 0.95).toInt() && intel > CHIEF_STAT_MIN) {
            cond = cond or STAT_INTEL
        }

        if (cond != 0) {
            if (leadership < CHIEF_STAT_MIN) cond = cond or STAT_NOT_LEADERSHIP
            if (strength < CHIEF_STAT_MIN) cond = cond or STAT_NOT_STRENGTH
            if (intel < CHIEF_STAT_MIN) cond = cond or STAT_NOT_INTEL
        }

        // Fallback: pick dominant stat
        if (cond == 0) {
            cond = when {
                leadership * 0.9 > strength && leadership * 0.9 > intel -> STAT_LEADERSHIP
                strength >= intel -> STAT_STRENGTH
                else -> STAT_INTEL
            }
        }

        return cond
    }

    /**
     * Calculate dexterity-based army type condition bits.
     * Legacy: calcCondDexterity() in SpecialityHelper.php
     * Returns 0 (no dexterity condition) or an ARMY_* flag.
     */
    private fun calcCondDexterity(rng: Random, general: General): Int {
        val dexEntries = DEX_ARMY_MAP.map { (key, flag) ->
            flag to ((general.meta[key] as? Number)?.toInt() ?: 0)
        }

        val dexSum = dexEntries.sumOf { it.second }
        val dexBase = (sqrt(dexSum.toDouble()) / 4).roundToInt()

        // 80% chance to skip dexterity selection
        if (rng.nextDouble() < 0.8) return 0

        // Higher total dex → more likely to skip
        if (rng.nextInt(100) < dexBase) return 0

        // If no dex at all, pick random army type
        if (dexSum == 0) {
            return dexEntries.random(rng).first
        }

        // Return army flag of the highest dex value
        val maxDex = dexEntries.maxOf { it.second }
        val maxFlags = dexEntries.filter { it.second == maxDex }.map { it.first }
        return if (maxFlags.size == 1) maxFlags[0] else maxFlags.random(rng)
    }

    private fun pickDomesticSpecial(rng: Random, general: General, prevSpecials: List<String>): String? {
        val myCond = calcStatCondition(general)
        val candidates = buildCandidates(DOMESTIC_SPECIALS, myCond, prevSpecials)

        // Fallback: if no candidates after excluding prevSpecials, retry without exclusion
        if (candidates.isEmpty() && prevSpecials.isNotEmpty()) {
            return choiceByWeight(rng, buildCandidates(DOMESTIC_SPECIALS, myCond, emptyList()))
        }

        return choiceByWeight(rng, candidates)
    }

    /**
     * War special selection with dexterity-based priority.
     * Legacy: pickSpecialWar() in SpecialityHelper.php
     */
    private fun pickWarSpecial(rng: Random, general: General, prevSpecials: List<String>): String? {
        var myCond = calcStatCondition(general)
        myCond = myCond or calcCondDexterity(rng, general)
        myCond = myCond or REQ_DEXTERITY

        val reqDex = mutableMapOf<String, Int>()
        val normal = mutableMapOf<String, Int>()

        for (spec in WAR_SPECIALS) {
            if (spec.code in prevSpecials) continue
            // Find first matching condition (legacy uses break on first match)
            val matchedCond = spec.requiredStats.firstOrNull { req -> req == (req and myCond) }
                ?: continue

            if (matchedCond and REQ_DEXTERITY != 0) {
                reqDex[spec.code] = spec.weight
            } else {
                normal[spec.code] = spec.weight
            }
        }

        // Dexterity-matched specials take priority
        if (reqDex.isNotEmpty()) {
            return choiceByWeight(rng, reqDex)
        }

        if (normal.isNotEmpty()) {
            return choiceByWeight(rng, normal)
        }

        // Fallback: retry without prevSpecials exclusion
        if (prevSpecials.isNotEmpty()) {
            return pickWarSpecial(rng, general, emptyList())
        }

        return null
    }

    private fun buildCandidates(specials: List<SpecialCandidate>, myCond: Int, exclude: List<String>): Map<String, Int> {
        val candidates = mutableMapOf<String, Int>()
        for (spec in specials) {
            if (spec.code in exclude) continue
            val valid = spec.requiredStats.any { reqStat -> reqStat == (reqStat and myCond) }
            if (valid) {
                candidates[spec.code] = spec.weight
            }
        }
        return candidates
    }

    private fun choiceByWeight(rng: Random, weightMap: Map<String, Int>): String? {
        if (weightMap.isEmpty()) return null
        val totalWeight = weightMap.values.sum()
        if (totalWeight <= 0) return null

        var roll = rng.nextInt(totalWeight)
        for ((key, weight) in weightMap) {
            roll -= weight
            if (roll < 0) return key
        }
        return weightMap.keys.last()
    }
}
