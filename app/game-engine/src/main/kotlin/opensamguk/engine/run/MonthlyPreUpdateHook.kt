package opensamguk.engine.run

import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.PerTurnOverlay
import opensamguk.infra.persistence.MetaJson
import opensamguk.common.constants.GameConst
import opensamguk.logic.tick.PreUpdateMonthly
import opensamguk.logic.world.PreUpdateAccessLog
import opensamguk.logic.world.PreUpdateCity
import opensamguk.logic.world.PreUpdateGeneral
import opensamguk.logic.world.PreUpdateMonthlyContext
import opensamguk.logic.world.PreUpdateMonthlyHook
import opensamguk.logic.world.PreUpdateMonthlyResult
import opensamguk.logic.world.PreUpdateNation

class MonthlyPreUpdateHook(
    private val world: InMemoryTurnWorld,
    private val recorder: ChangeRecorder,
    private val profileName: String,
) : PreUpdateMonthly {
    private var year = world.getState().currentYear
    private var month = world.getState().currentMonth

    override fun run(): Boolean {
        val succeeded = PreUpdateMonthlyHook(context(year, month)).run()
        if (succeeded) {
            if (month == 12) {
                year += 1
                month = 1
            } else {
                month += 1
            }
        }
        return succeeded
    }

    private fun context(year: Int, month: Int): PreUpdateMonthlyContext = object : PreUpdateMonthlyContext {
        private val state = world.getState()

        override val env: Map<String, Any?> = linkedMapOf(
            "year" to year,
            "startYear" to ((state.meta["startYear"] as? Number)?.toInt() ?: year),
        )

        override val currentEventID: Int = 0

        override fun logHistory(): Boolean {
            recorder.recordYearbookInsert(yearbookColumns(year, month))
            return true
        }

        override fun accessLogs(): List<PreUpdateAccessLog> = world.listAccessLogs()
            .sortedBy { it.generalId }
            .map { PreUpdateAccessLog(it.generalId, it.refreshScoreTotal) }

        override fun generals(): List<PreUpdateGeneral> = world.listGenerals()
            .sortedBy { it.id }
            .map { PreUpdateGeneral(it.id, (it.meta["makelimit"] as? Number)?.toInt() ?: 0) }

        override fun nations(): List<PreUpdateNation> = world.listNations()
            .sortedBy { it.id }
            .map { nation ->
                PreUpdateNation(
                    id = nation.id,
                    strategicCmdLimit = (nation.meta["strategic_cmd_limit"] as? Number)?.toInt() ?: 0,
                    surlimit = (nation.meta["surlimit"] as? Number)?.toInt() ?: 0,
                    rate = (nation.meta["rate"] as? Number)?.toDouble() ?: 0.0,
                    spy = spyMap(nation.meta["spy"]),
                )
            }

        override fun cities(): List<PreUpdateCity> = world.listCities()
            .sortedBy { it.id }
            .map { PreUpdateCity(it.id, it.state, it.term, it.conflict) }

        override fun apply(result: PreUpdateMonthlyResult) {
            for (update in result.accessLogs) {
                val pre = world.getAccessLog(update.generalId) ?: continue
                recorder.recordAccessLogUpsert(world, pre.copy(refreshScoreTotal = update.newRefreshScore))
            }

            for (update in result.generals) {
                val pre = world.getGeneralById(update.generalId) ?: continue
                val nextMeta = LinkedHashMap(pre.meta)
                nextMeta["makelimit"] = update.newMakelimit
                val post = pre.copy(meta = nextMeta)
                recorder.diffGeneral(PerTurnOverlay.toLogicGeneral(pre), PerTurnOverlay.toLogicGeneral(post))
                world.applyGeneralDirtyFree(post)
            }

            for (update in result.nations) {
                val pre = world.getNationById(update.nationId) ?: continue
                val nextMeta = LinkedHashMap(pre.meta)
                nextMeta["strategic_cmd_limit"] = update.newStrategicCmdLimit
                nextMeta["surlimit"] = update.newSurlimit
                nextMeta["rate_tmp"] = update.newRateTmp
                if (hasActiveSpy(pre.meta["spy"])) {
                    nextMeta["spy"] = update.newSpy.entries.associateTo(LinkedHashMap()) { (cityId, term) ->
                        cityId.toString() to term
                    }
                }
                val post = pre.copy(meta = nextMeta)
                recorder.diffNation(PerTurnOverlay.toLogicNation(pre), PerTurnOverlay.toLogicNation(post))
                world.applyNationDirtyFree(post)
            }

            recorder.recordKv("game_env", "game_env", "develcost", result.develcost)

            for (update in result.cities) {
                val pre = world.getCityById(update.cityId) ?: continue
                val post = pre.copy(
                    state = update.newState,
                    term = update.newTerm,
                    conflict = update.newConflict,
                )
                recorder.diffCity(PerTurnOverlay.toLogicCity(pre), PerTurnOverlay.toLogicCity(post))
                world.applyCityDirtyFree(post)
            }
        }
    }

    private fun yearbookColumns(year: Int, month: Int): Map<String, Any?> {
        val state = world.getState()
        val map = linkedMapOf<String, Any?>(
            "startYear" to ((state.meta["startYear"] as? Number)?.toInt() ?: year),
            "year" to year,
            "month" to month,
            "cityList" to world.listCities().map {
                listOf(it.id, it.level, it.state, it.nationId, it.region, it.supplyState)
            },
            "nationList" to world.listNations().filter { it.id != 0 }.map {
                listOf(it.id, it.name, it.color, it.capitalCityId ?: 0)
            },
            "spyList" to emptyMap<String, Any?>(),
            "shownByGeneralList" to emptyList<Int>(),
            "myCity" to null,
            "myNation" to null,
            "version" to 0,
            "result" to true,
        )
        val nations = world.listNations().filter { it.id != 0 }.map { nation ->
            linkedMapOf<String, Any?>(
                "nation" to nation.id,
                "name" to nation.name,
                "color" to nation.color,
                "type" to nation.typeCode,
                "level" to nation.level,
                "capital" to (nation.capitalCityId ?: 0),
                "gennum" to (nation.meta["gennum"] ?: world.listGenerals().count { it.nationId == nation.id }),
                "power" to nation.power,
            )
        }.toMutableList()
        nations += linkedMapOf(
            "nation" to 0,
            "name" to "재야",
            "color" to "#000000",
            "type" to GameConst.neutralNationType,
            "level" to 0,
            "capital" to 0,
            "gold" to 0,
            "rice" to 2000,
            "tech" to 0,
            "gennum" to 1,
            "power" to 1,
        )
        val nationsById = nations.associateByTo(LinkedHashMap()) { (it["nation"] as Number).toInt() }
        for (city in world.listCities()) {
            val nation = nationsById[city.nationId] ?: continue
            @Suppress("UNCHECKED_CAST")
            val cities = nation.getOrPut("cities") { mutableListOf<String>() } as MutableList<String>
            cities += city.name
        }
        val sortedNations = nations.sortedByDescending { (it["power"] as Number).toInt() }

        val mapJson = MetaJson.encode(map)
        val nationsJson = MetaJson.encode(sortedNations)
        val globalHistoryJson = MetaJson.encode(currentGlobalLogs("history", year, month))
        val globalActionJson = MetaJson.encode(currentGlobalLogs("action", year, month))
        return linkedMapOf(
            "server_id" to activeServerId(),
            "year" to year,
            "month" to month,
            "map" to mapJson,
            "nations" to nationsJson,
            "global_history" to globalHistoryJson,
            "global_action" to globalActionJson,
        )
    }

    private fun activeServerId(): String =
        world.archiveServerId()
            ?: world.getState().serverId
            ?: world.getState().meta["serverId"]?.toString()?.takeIf(String::isNotBlank)
            ?: world.getState().meta["server_id"]?.toString()?.takeIf(String::isNotBlank)
            ?: profileName

    private fun currentGlobalLogs(category: String, year: Int, month: Int): List<String> {
        val persisted = (world.getState().meta["globalLogs"] as? List<*>)
            .orEmpty()
            .mapNotNull { it as? Map<*, *> }
            .filter {
                it["category"]?.toString()?.equals(category, ignoreCase = true) == true &&
                    (it["year"] as? Number)?.toInt() == year &&
                    (it["month"] as? Number)?.toInt() == month
            }
            .mapNotNull { it["text"]?.toString() }
        val pending = world.peekLogs()
            .filter {
                it.scope.lowercase() in setOf("global", "system") &&
                    it.category.equals(category, ignoreCase = true) &&
                    (it.year ?: year) == year &&
                    (it.month ?: month) == month
            }
            .map { it.text }
            .asReversed()
        val logs = pending + persisted
        if (logs.isNotEmpty()) return logs
        return when (category.lowercase()) {
            "history" -> listOf("<C>●</>${year}년 ${month}월: 기록 없음")
            "action" -> listOf("<C>●</>${month}월: 기록 없음")
            else -> emptyList()
        }
    }

    private fun spyMap(raw: Any?): Map<Int, Int> {
        if (raw !is Map<*, *>) return emptyMap()
        val result = LinkedHashMap<Int, Int>()
        for ((cityId, term) in raw) {
            val id = when (cityId) {
                is Number -> cityId.toInt()
                else -> cityId?.toString()?.toIntOrNull()
            }
            val remain = (term as? Number)?.toInt()
            if (id != null && remain != null) result[id] = remain
        }
        return result
    }

    private fun hasActiveSpy(raw: Any?): Boolean = raw is Map<*, *> && raw.isNotEmpty()
}
