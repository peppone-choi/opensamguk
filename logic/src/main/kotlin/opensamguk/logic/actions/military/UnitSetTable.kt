package opensamguk.logic.actions.military

import opensamguk.common.constants.GameConst
import opensamguk.common.constants.GameUnitConst
import opensamguk.common.constants.UnitCatalog
import kotlin.math.floor

/**
 * Declarative unit-set stat view over the canonical `che` [GameUnitConst] catalog, reduced to the
 * per-unit fields the military commands consume: `armType` / `cost` / `rice`, plus the shared
 * `getTechCost` curve + `costWithTech`/`riceWithTech` (GameUnitDetail.php:120-128).
 *
 * Pure data, no RNG. The full battle-coefficient/skill-trigger surface (attack/defence/coef/info/
 * iActionList) is OUT of scope here — only the recruit-cost-relevant fields are transcribed.
 *
 * The CASTLE unit (1000) is `Impossible` to recruit but is kept in the table (addDex folds
 * CASTLE→SIEGE).
 */
object UnitSetTable {
    const val CHE_UNIT_SET = "che"

    /**
     * Per-unit recruit-cost stat row. `cost`/`rice` are the BASE per-100-crew values (GameUnitConstBase
     * tuple slots 9/10); `armType` slot 2. Other tuple slots are not modelled here.
     */
    data class UnitDetail(
        val id: Int,
        val armType: Int,
        val name: String,
        val cost: Int,
        val rice: Int,
    ) {
        /** GameUnitDetail.php:125-128 — `cost * getTechCost(tech) * crew / 100`. */
        fun costWithTech(tech: Int, crew: Int = 100): Double = cost * getTechCost(tech) * crew / 100.0

        /** GameUnitDetail.php:120-123 — `rice * getTechCost(tech) * crew / 100`. */
        fun riceWithTech(tech: Int, crew: Int = 100): Double = rice * getTechCost(tech) * crew / 100.0
    }

    /**
     * func_converter.php:676-682 — getTechLevel = valueFit(floor(tech/1000), 0, maxTechLevel).
     */
    fun getTechLevel(tech: Int): Int {
        val raw = floor(tech / 1000.0)
        return raw.coerceIn(0.0, GameConst.maxTechLevel.toDouble()).toInt()
    }

    /**
     * func_converter.php:703-705 — getTechCost = 1 + getTechLevel(tech) * 0.15.
     */
    fun getTechCost(tech: Int): Double = 1.0 + getTechLevel(tech) * 0.15

    private fun row(unit: opensamguk.common.constants.GameUnitDetail) = UnitDetail(
        id = unit.id,
        armType = unit.armType,
        name = unit.name,
        cost = unit.cost,
        rice = unit.rice,
    )

    private val BY_SET: Map<String, List<UnitDetail>> =
        UnitCatalog.sets().keys.associateWith { set -> UnitCatalog.all(set).values.map(::row) }

    /** id 대역이 세트마다 갈려 있어 세트를 몰라도 찾을 수 있다 (UnitCatalog 참조). */
    private val BY_ID: Map<Int, UnitDetail> = BY_SET.values.flatten().associateBy { it.id }

    fun all(): List<UnitDetail> = BY_SET.getValue(CHE_UNIT_SET)

    fun all(unitSet: String?): List<UnitDetail> = BY_SET[normalizedUnitSet(unitSet)].orEmpty()

    fun isSupported(unitSet: String?): Boolean = normalizedUnitSet(unitSet) in BY_SET

    /** 그 세트의 기본 병종. che 는 1100, han 은 2006 — 세트마다 다르므로 상수로 굳히지 않는다. */
    fun defaultCrewTypeId(unitSet: String?): Int? =
        UnitCatalog.meta(normalizedUnitSet(unitSet))?.defaultCrewTypeId

    fun castleCrewTypeId(unitSet: String?): Int? =
        UnitCatalog.meta(normalizedUnitSet(unitSet))?.castleCrewTypeId

    fun activeUnitSet(config: Map<String, Any?>, meta: Map<String, Any?>): String =
        stringField(config["unitSet"])
            ?: mapField(config["map"], "unitSet")
            ?: stringField(meta["unitSet"])
            ?: mapField(meta["map"], "unitSet")
            ?: CHE_UNIT_SET

    /** GameUnitConstBase.php:382-388 — byID (ids must be >= 1000); null when absent. */
    fun byId(id: Int): UnitDetail? {
        require(id >= 1000) { "적절한 id는 1000이상이어야합니다:$id" }
        return BY_ID[id]
    }

    fun byId(unitSet: String?, id: Int): UnitDetail? =
        if (isSupported(unitSet)) byId(id) else null

    private fun normalizedUnitSet(unitSet: String?): String = unitSet ?: CHE_UNIT_SET

    private fun mapField(raw: Any?, key: String): String? = when (raw) {
        is Map<*, *> -> raw[key]?.toString()
        else -> null
    }

    private fun stringField(raw: Any?): String? = raw?.toString()
}
