package opensamguk.engine.run

import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.PerTurnOverlay
import opensamguk.infra.persistence.MetaJson
import opensamguk.logic.tick.PreUpdateMonthly
import opensamguk.logic.world.PreUpdateAccessLog
import opensamguk.logic.world.PreUpdateCity
import opensamguk.logic.world.PreUpdateGeneral
import opensamguk.logic.world.PreUpdateMonthlyContext
import opensamguk.logic.world.PreUpdateMonthlyHook
import opensamguk.logic.world.PreUpdateMonthlyResult
import opensamguk.logic.world.PreUpdateNation
import java.security.MessageDigest

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
        val map = linkedMapOf<String, Any?>()
        val rawMap = state.meta["map"]
        if (rawMap is Map<*, *>) {
            rawMap.forEach { (key, value) -> map[key.toString()] = value }
        } else if (rawMap != null) {
            map["mapName"] = rawMap
        }
        map["startYear"] = (state.meta["startYear"] as? Number)?.toInt() ?: year
        map["year"] = year
        map["month"] = month

        val citiesByNation = world.listCities()
            .groupBy { it.nationId }
            .mapValues { (_, cities) -> cities.sortedBy { it.id }.map { it.name } }
        val nations = world.listNations()
            .sortedWith(compareByDescending<opensamguk.engine.turn.Nation> { it.power }.thenBy { it.id })
            .map { nation ->
                linkedMapOf<String, Any?>(
                    "nation" to nation.id,
                    "name" to nation.name,
                    "color" to nation.color,
                    "power" to nation.power,
                    "level" to nation.level,
                    "capital" to nation.capitalCityId,
                    "cities" to (citiesByNation[nation.id] ?: emptyList<String>()),
                )
            }

        val mapJson = MetaJson.encode(map)
        val nationsJson = MetaJson.encode(nations)
        val globalHistoryJson = "[]"
        val globalActionJson = "[]"
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$mapJson\n$nationsJson\n$globalHistoryJson\n$globalActionJson".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

        return linkedMapOf(
            "profile_name" to profileName,
            "year" to year,
            "month" to month,
            "map" to mapJson,
            "nations" to nationsJson,
            "global_history" to globalHistoryJson,
            "global_action" to globalActionJson,
            "hash" to digest,
        )
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
