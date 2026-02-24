package com.opensam.command

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.opensam.command.util.StatChangeUtil
import com.opensam.entity.City
import com.opensam.entity.General
import com.opensam.entity.Nation

/**
 * CommandResult.message JSON을 파싱하여 엔티티에 적용하는 유틸리티.
 *
 * 일반 장수 커맨드는 run()에서 직접 엔티티를 수정하지 않고,
 * JSON 형태의 델타값을 반환한다. 이 클래스가 해당 델타를 실제 엔티티에 반영한다.
 *
 * 국가 커맨드는 run()에서 직접 엔티티를 수정하므로 이 클래스를 사용하지 않는다.
 */
object CommandResultApplicator {

    private val mapper = jacksonObjectMapper()

    /** SET 연산 필드 (delta가 아닌 직접 대입) */
    private val SET_FIELDS = setOf("crewType")

    /**
     * CommandResult.message JSON을 파싱하여 엔티티들에 적용한다.
     * 실패한 커맨드나 message가 없는 경우는 무시한다.
     */
    fun apply(
        result: CommandResult,
        general: General,
        city: City?,
        nation: Nation?,
        destGeneral: General? = null,
        destCity: City? = null,
        destNation: Nation? = null,
    ) {
        val message = result.message ?: return
        if (!result.success) return

        val json: Map<String, Any> = try {
            mapper.readValue(message)
        } catch (_: Exception) {
            return
        }

        @Suppress("UNCHECKED_CAST")
        (json["statChanges"] as? Map<String, Any>)?.let { applyStatChanges(general, it) }

        @Suppress("UNCHECKED_CAST")
        (json["cityChanges"] as? Map<String, Any>)?.let {
            if (city != null) applyCityChanges(city, it)
        }

        @Suppress("UNCHECKED_CAST")
        (json["nationChanges"] as? Map<String, Any>)?.let {
            if (nation != null) applyNationChanges(nation, it)
        }

        @Suppress("UNCHECKED_CAST")
        (json["destGeneralChanges"] as? Map<String, Any>)?.let {
            if (destGeneral != null) applyStatChanges(destGeneral, it)
        }

        @Suppress("UNCHECKED_CAST")
        (json["destCityChanges"] as? Map<String, Any>)?.let {
            if (destCity != null) applyCityChanges(destCity, it)
        }

        @Suppress("UNCHECKED_CAST")
        (json["destNationChanges"] as? Map<String, Any>)?.let {
            if (destNation != null) applyNationChanges(destNation, it)
        }

        @Suppress("UNCHECKED_CAST")
        (json["dexChanges"] as? Map<String, Any>)?.let { applyDexChanges(general, it) }

        // Consumable item: delete item on use (legacy: tryConsumeNow + deleteItem)
        if (json["consumeItem"] == true) {
            general.itemCode = "None"
        }

        // Own nation changes (e.g., 탈취 resource transfer)
        @Suppress("UNCHECKED_CAST")
        (json["ownNationChanges"] as? Map<String, Any>)?.let {
            if (nation != null) applyNationChanges(nation, it)
        }

        // Handle cityId in statChanges → move general to new city (legacy: 이동/강행/귀환)
        @Suppress("UNCHECKED_CAST")
        (json["statChanges"] as? Map<String, Any>)?.let { changes ->
            val cityIdRaw = changes["cityId"]
            if (cityIdRaw != null) {
                val id = when (cityIdRaw) {
                    is Number -> cityIdRaw.toLong()
                    is String -> cityIdRaw.toLongOrNull()
                    else -> null
                }
                if (id != null && id > 0) {
                    general.cityId = id
                }
            }
        }

        // Handle inheritancePoint: {"key": "active_action", "amount": 1}
        @Suppress("UNCHECKED_CAST")
        (json["inheritancePoint"] as? Map<String, Any>)?.let { ip ->
            val key = ip["key"] as? String ?: return@let
            val amount = (ip["amount"] as? Number)?.toInt() ?: 1
            @Suppress("UNCHECKED_CAST")
            val inheritMeta = general.meta.getOrPut("inheritancePoints") {
                mutableMapOf<String, Any>()
            } as? MutableMap<String, Any> ?: return@let
            inheritMeta[key] = ((inheritMeta[key] as? Number)?.toInt() ?: 0) + amount
        }

        // Handle cityStateUpdate: {"cityId": X, "state": 43, "term": 3}
        // Updates the dest city's state/term for battle preparation
        @Suppress("UNCHECKED_CAST")
        (json["cityStateUpdate"] as? Map<String, Any>)?.let { csu ->
            val targetCityId = when (val raw = csu["cityId"]) {
                is Number -> raw.toLong()
                is String -> raw.toLongOrNull()
                else -> null
            }
            val stateVal = (csu["state"] as? Number)?.toShort()
            val termVal = (csu["term"] as? Number)?.toShort()
            if (targetCityId != null && destCity != null && destCity.id == targetCityId) {
                if (stateVal != null) destCity.state = stateVal
                if (termVal != null) destCity.term = termVal
            }
        }

        // Handle checkStatChange: check if stat exp crossed level threshold
        if (json["checkStatChange"] == true) {
            StatChangeUtil.checkStatChange(general)
        }
    }

    private fun applyStatChanges(general: General, changes: Map<String, Any>) {
        for ((key, rawValue) in changes) {
            val value = (rawValue as? Number) ?: continue
            if (key in SET_FIELDS) {
                applyStatSet(general, key, value.toInt())
            } else {
                applyStatDelta(general, key, value.toInt())
            }
        }
    }

    private fun applyStatSet(general: General, key: String, value: Int) {
        when (key) {
            "crewType" -> general.crewType = value.toShort()
        }
    }

    private fun applyStatDelta(general: General, key: String, delta: Int) {
        when (key) {
            "gold" -> general.gold = maxOf(0, general.gold + delta)
            "rice" -> general.rice = maxOf(0, general.rice + delta)
            "crew" -> general.crew = maxOf(0, general.crew + delta)
            "train" -> general.train = maxOf(0, minOf(100, general.train + delta)).toShort()
            "atmos" -> general.atmos = maxOf(0, minOf(100, general.atmos + delta)).toShort()
            "experience" -> general.experience = maxOf(0, general.experience + delta)
            "dedication" -> general.dedication = maxOf(0, general.dedication + delta)
            "leadershipExp" -> general.leadershipExp = (general.leadershipExp + delta).toShort()
            "strengthExp" -> general.strengthExp = (general.strengthExp + delta).toShort()
            "intelExp" -> general.intelExp = (general.intelExp + delta).toShort()
            "injury" -> general.injury = maxOf(0, minOf(100, general.injury + delta)).toShort()
            "belong" -> general.belong = (general.belong + delta).toShort()
            "betray" -> general.betray = (general.betray + delta).toShort()
        }
    }

    private fun applyCityChanges(city: City, changes: Map<String, Any>) {
        for ((key, rawValue) in changes) {
            val num = rawValue as? Number ?: continue
            when (key) {
                // trust uses float delta for decimal precision (legacy: 선동 민심 1 decimal)
                "trust" -> {
                    val delta = num.toFloat()
                    city.trust = maxOf(0f, minOf(100f, city.trust + delta))
                }
                else -> {
                    val delta = num.toInt()
                    when (key) {
                        "agri" -> city.agri = maxOf(0, minOf(city.agriMax, city.agri + delta))
                        "comm" -> city.comm = maxOf(0, minOf(city.commMax, city.comm + delta))
                        "secu" -> city.secu = maxOf(0, minOf(city.secuMax, city.secu + delta))
                        "def" -> city.def = maxOf(0, minOf(city.defMax, city.def + delta))
                        "wall" -> city.wall = maxOf(0, minOf(city.wallMax, city.wall + delta))
                        "pop" -> city.pop = maxOf(0, minOf(city.popMax, city.pop + delta))
                        "dead" -> city.dead = maxOf(0, city.dead + delta)
                        "trade" -> city.trade = maxOf(0, city.trade + delta)
                        "state" -> city.state = delta.toShort()
                    }
                }
            }
        }
    }

    private fun applyNationChanges(nation: Nation, changes: Map<String, Any>) {
        for ((key, rawValue) in changes) {
            val value = (rawValue as? Number)?.toInt() ?: continue
            when (key) {
                "gold" -> nation.gold = maxOf(0, nation.gold + value)
                "rice" -> nation.rice = maxOf(0, nation.rice + value)
                "tech" -> nation.tech = maxOf(0f, nation.tech + value)
                "power" -> nation.power = maxOf(0, nation.power + value)
            }
        }
    }

    /** 병종 숙련도 변경. crewType → dex1~5 매핑. */
    private fun applyDexChanges(general: General, changes: Map<String, Any>) {
        val crewType = (changes["crewType"] as? Number)?.toInt() ?: return
        val amount = (changes["amount"] as? Number)?.toInt() ?: return
        when (crewType) {
            0 -> general.dex1 = maxOf(0, general.dex1 + amount)
            1 -> general.dex2 = maxOf(0, general.dex2 + amount)
            2 -> general.dex3 = maxOf(0, general.dex3 + amount)
            3 -> general.dex4 = maxOf(0, general.dex4 + amount)
            4 -> general.dex5 = maxOf(0, general.dex5 + amount)
        }
    }
}
