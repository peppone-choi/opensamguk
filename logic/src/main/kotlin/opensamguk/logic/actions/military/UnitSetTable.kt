package opensamguk.logic.actions.military

import opensamguk.common.constants.GameConst
import kotlin.math.floor

/**
 * Declarative unit-set stat table — faithful port of the `che` unit-set data
 * (`legacy/devsam-core/hwe/sammo/GameUnitConstBase.php` getBuildData()) reduced to the per-unit
 * fields the military commands consume: `armType` / `cost` / `rice`, plus the shared `getTechCost`
 * curve + `costWithTech`/`riceWithTech` (GameUnitDetail.php:120-128).
 *
 * Pure data, no RNG. The full battle-coefficient/skill-trigger surface (attack/defence/coef/info/
 * iActionList) is OUT of scope here — only the recruit-cost-relevant fields are transcribed.
 *
 * armType constants mirror GameUnitConstBase.php:16-22 (T_CASTLE=0 … T_MISC=6). The CASTLE unit
 * (1000) is `Impossible` to recruit but is kept in the table (addDex folds CASTLE→SIEGE).
 */
object UnitSetTable {
    // GameUnitConstBase.php:16-22 — armType taxonomy.
    const val T_CASTLE = 0
    const val T_FOOTMAN = 1
    const val T_ARCHER = 2
    const val T_CAVALRY = 3
    const val T_WIZARD = 4
    const val T_SIEGE = 5
    const val T_MISC = 6

    /** GameUnitConstBase.php:25 — the default crew-type id (보병 1100). */
    const val DEFAULT_CREWTYPE = 1100

    /** GameUnitConstBase.php:23 — the castle/wall pseudo-unit id. */
    const val CREWTYPE_CASTLE = 1000

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

    // GameUnitConstBase.php getBuildData() — id/armType/name/cost/rice (tuple slots 0/1/2/9/10).
    private val UNITS: List<UnitDetail> = listOf(
        UnitDetail(1000, T_CASTLE, "성벽", 99, 9),
        UnitDetail(1100, T_FOOTMAN, "보병", 9, 9),
        UnitDetail(1101, T_FOOTMAN, "청주병", 10, 11),
        UnitDetail(1102, T_FOOTMAN, "수병", 11, 10),
        UnitDetail(1103, T_FOOTMAN, "자객병", 10, 10),
        UnitDetail(1104, T_FOOTMAN, "근위병", 12, 12),
        UnitDetail(1105, T_FOOTMAN, "등갑병", 13, 10),
        UnitDetail(1106, T_FOOTMAN, "백이병", 13, 11),
        UnitDetail(1200, T_ARCHER, "궁병", 10, 10),
        UnitDetail(1201, T_ARCHER, "궁기병", 11, 12),
        UnitDetail(1202, T_ARCHER, "연노병", 12, 11),
        UnitDetail(1203, T_ARCHER, "강궁병", 13, 13),
        UnitDetail(1204, T_ARCHER, "석궁병", 13, 13),
        UnitDetail(1300, T_CAVALRY, "기병", 11, 11),
        UnitDetail(1301, T_CAVALRY, "백마병", 12, 13),
        UnitDetail(1302, T_CAVALRY, "중장기병", 13, 12),
        UnitDetail(1303, T_CAVALRY, "돌격기병", 13, 11),
        UnitDetail(1304, T_CAVALRY, "철기병", 11, 13),
        UnitDetail(1305, T_CAVALRY, "수렵기병", 12, 12),
        UnitDetail(1306, T_CAVALRY, "맹수병", 16, 16),
        UnitDetail(1307, T_CAVALRY, "호표기병", 14, 14),
        UnitDetail(1400, T_WIZARD, "귀병", 9, 9),
        UnitDetail(1401, T_WIZARD, "신귀병", 10, 10),
        UnitDetail(1402, T_WIZARD, "백귀병", 9, 11),
        UnitDetail(1403, T_WIZARD, "흑귀병", 11, 9),
        UnitDetail(1404, T_WIZARD, "악귀병", 12, 12),
        UnitDetail(1405, T_WIZARD, "남귀병", 8, 8),
        UnitDetail(1406, T_WIZARD, "황귀병", 13, 10),
        UnitDetail(1407, T_WIZARD, "천귀병", 11, 12),
        UnitDetail(1408, T_WIZARD, "마귀병", 12, 11),
        UnitDetail(1500, T_SIEGE, "정란", 14, 5),
        UnitDetail(1501, T_SIEGE, "충차", 18, 5),
        UnitDetail(1502, T_SIEGE, "벽력거", 20, 5),
        UnitDetail(1503, T_SIEGE, "목우", 15, 5),
    )

    private val BY_ID: Map<Int, UnitDetail> = UNITS.associateBy { it.id }

    fun all(): List<UnitDetail> = UNITS

    /** GameUnitConstBase.php:382-388 — byID (ids must be >= 1000); null when absent. */
    fun byId(id: Int): UnitDetail? {
        require(id >= 1000) { "적절한 id는 1000이상이어야합니다:$id" }
        return BY_ID[id]
    }
}
