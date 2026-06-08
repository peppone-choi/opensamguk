package opensamguk.logic.tick

import opensamguk.logic.domain.City
import opensamguk.logic.domain.General
import opensamguk.logic.domain.Nation
import opensamguk.logic.util.phpRound

/**
 * Pure logic year-boundary national-statistics computation.
 * Faithful port of PHP `func_gamerule.php:checkStatistic()` (lines 469-651).
 *
 * Called once per game year when the NEW month is January.
 * Computes aggregates over ALL generals/nations/cities and returns
 * the row that should be INSERTed into the `statistic` table.
 */
object CheckStatisticCalculator {

    data class StatisticRow(
        val year: Int,
        val month: Int,
        val nationCount: Int,
        val nationName: String,
        val nationHist: String,
        val genCount: String,
        val personalHist: String,
        val specialHist: String,
        val powerHist: String,
        val crewtype: String,
        val etc: String,
        val aux: Map<String, Any?>,
    )

    fun compute(
        year: Int,
        month: Int,
        generals: List<General>,
        nations: List<Nation>,
        cities: List<City>,
        nationTypeNameOf: (String) -> String,
        personalityNameOf: (Any?) -> String,
        specialDomesticNameOf: (String) -> String,
        specialWarNameOf: (String) -> String,
        crewtypeShortNameOf: (Int) -> String,
    ): StatisticRow {
        // --- general aggregates (PHP lines 489-500) ---
        val avgGold = if (generals.isNotEmpty()) phpRound(generals.map { it.gold }.average()) else 0
        val avgRice = if (generals.isNotEmpty()) phpRound(generals.map { it.rice }.average()) else 0
        val dexValues = generals.map { it.dexSum() }
        val avgDex = if (dexValues.isNotEmpty()) phpRound(dexValues.average()) else 0
        val maxDex = if (dexValues.isNotEmpty()) dexValues.max() else 0
        val expDedValues = generals.map { it.experience + it.dedication }
        val avgExpDed = if (expDedValues.isNotEmpty()) phpRound(expDedValues.average()) else 0
        val maxExpDed = if (expDedValues.isNotEmpty()) expDedValues.max() else 0

        val etc = "평균 금/쌀 (${avgGold}/${avgRice}), 평균/최고 숙련(${avgDex}/${maxDex}), 평균/최고 경험공헌(${avgExpDed}/${maxExpDed}), "

        // --- nation aggregates (PHP lines 502-513) ---
        val activeNations = nations.filter { it.level > 0 }
        val techValues = activeNations.map { it.tech }
        val minTech = if (techValues.isNotEmpty()) techValues.min() else 0
        val maxTech = if (techValues.isNotEmpty()) techValues.max() else 0
        val avgTech = if (techValues.isNotEmpty()) phpRound(techValues.average()) else 0
        val powerValues = activeNations.map { it.power }
        val minPower = if (powerValues.isNotEmpty()) powerValues.min() else 0
        val maxPower = if (powerValues.isNotEmpty()) powerValues.max() else 0
        val avgPower = if (powerValues.isNotEmpty()) phpRound(powerValues.average()) else 0

        val etcFull = etc +
            "최저/평균/최고 기술(${minTech}/${avgTech}/${maxTech}), " +
            "최저/평균/최고 국력(${minPower}/${avgPower}/${maxPower}), "

        // --- per-nation info (PHP lines 518-539) ---
        val nationGeneralInfos = generals.groupBy { it.nationId }
            .mapValues { (_, gs) ->
                mapOf(
                    "abil" to gs.sumOf { it.leadership + it.strength + it.intel },
                    "goldrice" to gs.sumOf { it.gold + it.rice },
                    "dex" to gs.sumOf { it.dexSum() },
                    "expded" to gs.sumOf { it.experience + it.dedication },
                )
            }

        val nationCityInfos = cities.groupBy { it.nationId }
            .mapValues { (_, cs) ->
                mapOf(
                    "cnt" to cs.size,
                    "pop" to cs.sumOf { it.population },
                    "pop_max" to cs.sumOf { it.populationMax },
                )
            }

        // --- nation name list + power hist (PHP lines 515-555) ---
        val sortedNations = activeNations.sortedByDescending { it.power }
        var nationName = ""
        var powerHist = ""
        val nationHists = mutableMapOf<String, Int>()

        for (nation in sortedNations) {
            val general = nationGeneralInfos[nation.id] ?: mapOf(
                "abil" to 0,
                "goldrice" to 0,
                "dex" to 0,
                "expded" to 0,
            )
            val city = nationCityInfos[nation.id] ?: mapOf("cnt" to 0, "pop" to 0, "pop_max" to 0)
            val goldRice = (nation.gold) + (nation.rice)

            nationName += "${nation.name}(${nationTypeNameOf(nation.typeCode)}), "
            powerHist += "${nation.name}(${nation.power}/${nation.gennum}/${city["cnt"]}/${city["pop"]}/${city["pop_max"]}/${goldRice}/${general["goldrice"]}/${general["abil"]}/${general["dex"]}/${general["expded"]}), "
            nationHists[nation.typeCode] = (nationHists[nation.typeCode] ?: 0) + 1
        }

        // --- nation type histogram (PHP lines 560-566) ---
        val availableNationTypes = listOf(
            "che_도적", "che_명가", "che_음양가", "che_종횡가", "che_불가", "che_오두미도",
            "che_태평도", "che_도가", "che_묵가", "che_덕가", "che_병가", "che_유가", "che_법가",
        )
        var nationHist = ""
        for (nationType in availableNationTypes) {
            val cnt = nationHists[nationType] ?: "-"
            nationHist += "${nationTypeNameOf(nationType)}(${cnt}), "
        }

        // --- general histograms (PHP lines 568-611) ---
        val personalHists = mutableMapOf<String, Int>()
        val specialHists = mutableMapOf<String, Int>()
        val specialHists2 = mutableMapOf<String, Int>()
        val crewtypeHists = mutableMapOf<Int, Int>()
        var genCount = 0
        var npcCount = 0

        for (general in generals) {
            val personal = general.metaString("personal", "None")
            val special = general.metaString("special", "None")
            val special2 = general.metaString("special2", "None")
            personalHists[personal] = (personalHists[personal] ?: 0) + 1
            specialHists[special] = (specialHists[special] ?: 0) + 1
            specialHists2[special2] = (specialHists2[special2] ?: 0) + 1
            if (general.npcType < 2) genCount++ else npcCount++
        }

        // crewtype histogram for generals with recent_war != null (PHP lines 598-602)
        val warGenerals = generals.filter { it.meta["recent_war"] != null }
        for (g in warGenerals.groupBy { it.crewTypeId }.entries.sortedBy { it.key }) {
            crewtypeHists[g.key] = g.value.size
        }

        val generalCount = generals.size
        val generalCountStr = "${generalCount}(${genCount}+${npcCount})"

        val personalHistStr = personalHists.entries.joinToString(", ") {
            "${personalityNameOf(it.key)}(${it.value})"
        }
        val specialHistsStr = specialHists.entries.joinToString(", ") {
            "${specialDomesticNameOf(it.key)}(${it.value})"
        }
        val specialHists2Str = specialHists2.entries.joinToString(", ") {
            "${specialWarNameOf(it.key)}(${it.value})"
        }
        val specialHistsAllStr = "$specialHistsStr // $specialHists2Str"
        val crewtypeHistsStr = crewtypeHists.entries.joinToString(", ") {
            "${crewtypeShortNameOf(it.key)}(${it.value})"
        }

        val auxData = mapOf(
            "generals" to mapOf(
                "avg" to mapOf(
                    "avggold" to avgGold,
                    "avgrice" to avgRice,
                    "avgdex" to avgDex,
                    "maxdex" to maxDex,
                    "avgexpded" to avgExpDed,
                    "maxexpded" to maxExpDed,
                ),
                "hists" to mapOf(
                    "personal" to personalHists,
                    "special" to specialHists,
                    "special2" to specialHists2,
                    "crewtype" to crewtypeHists,
                    "userCnt" to genCount,
                    "npcCnt" to npcCount,
                ),
            ),
            "nations" to mapOf(
                "avg" to mapOf(
                    "mintech" to minTech,
                    "maxtech" to maxTech,
                    "avgtech" to avgTech,
                    "avgpower" to avgPower,
                    "minpower" to minPower,
                    "maxpower" to maxPower,
                ),
                "all" to sortedNations.map { n ->
                    mapOf(
                        "nation" to n.id,
                        "name" to n.name,
                        "type" to n.typeCode,
                        "power" to n.power,
                        "gennum" to n.gennum,
                        "goldrice" to ((n.gold) + (n.rice)),
                        "generalInfo" to (nationGeneralInfos[n.id] ?: emptyMap<String, Any>()),
                        "cityInfo" to (nationCityInfos[n.id] ?: emptyMap<String, Any>()),
                    )
                },
            ),
        )

        return StatisticRow(
            year = year,
            month = month,
            nationCount = activeNations.size,
            nationName = nationName,
            nationHist = nationHist,
            genCount = generalCountStr,
            personalHist = personalHistStr,
            specialHist = specialHistsAllStr,
            powerHist = powerHist,
            crewtype = crewtypeHistsStr,
            etc = etcFull,
            aux = auxData,
        )
    }

    private fun General.dexSum(): Int =
        metaInt("dex1") + metaInt("dex2") + metaInt("dex3") + metaInt("dex4")

    private fun General.metaInt(key: String): Int = when (val value = meta[key]) {
        is Number -> value.toInt()
        is String -> value.toIntOrNull() ?: 0
        else -> 0
    }

    private fun General.metaString(key: String, default: String): String =
        meta[key]?.toString()?.takeIf { it.isNotEmpty() } ?: default
}
