package opensamguk.common.constants

/**
 * Faithful port of `legacy/devsam-core/hwe/sammo/GameUnitConstraint/` getInfo() bodies.
 *
 * PHP is the SOLE oracle for these human-facing strings — TS devsam-core2026 emits boolean
 * constraint logic only (no strings). The PHP method name `getInfo()` is preserved (NOT `info()`).
 * `cityNameText`/`regionNameText` = PHP `implode(', ', ...)` = comma-space join;
 * `cityLevelText` = `getCityLevelList()[reqCityLevel]` label.
 */
sealed class UnitConstraint {
    abstract fun getInfo(): String

    object Impossible : UnitConstraint() { override fun getInfo() = "불가능" }

    data class ReqTech(val reqTech: Int) : UnitConstraint() {
        override fun getInfo() = "기술력 ${reqTech} 이상 필요"
    }

    data class ReqCities(val reqCities: List<String>) : UnitConstraint() {
        private val cityNameText get() = reqCities.joinToString(", ")
        override fun getInfo() = "${cityNameText} 소유시 가능"
    }

    data class ReqRegions(val reqRegions: List<String>) : UnitConstraint() {
        private val regionNameText get() = reqRegions.joinToString(", ")
        override fun getInfo() = "${regionNameText} 지역 소유시 가능"
    }

    data class ReqMinRelYear(val reqMinRelYear: Int) : UnitConstraint() {
        override fun getInfo() = "${reqMinRelYear}년 경과 후 사용 가능"
    }

    object ReqChief : UnitConstraint() { override fun getInfo() = "군주 및 수뇌부만 가능" }

    object ReqNotChief : UnitConstraint() { override fun getInfo() = "군주 및 수뇌부는 불가" }

    data class ReqCitiesWithCityLevel(val reqCityLevel: Int, val reqCities: List<String>) : UnitConstraint() {
        private val cityNameText get() = reqCities.joinToString(", ")
        private val cityLevelText get() = getCityLevelList()[reqCityLevel]
        override fun getInfo() = "${cityNameText} ${cityLevelText}성 소유시 가능"
    }

    data class ReqHighLevelCities(val reqCityLevel: Int, val reqCityCount: Int) : UnitConstraint() {
        private val cityLevelText get() = getCityLevelList()[reqCityLevel]
        override fun getInfo() = "${cityLevelText}성 ${reqCityCount}개 이상 소유시 가능"
    }

    // ReqNationAux.getInfo() ports the per-key switch from ReqNationAux.php:62-119 verbatim,
    // then the generic ==/!=/default fallback. nationAuxKey carries the PHP NationAuxKey->value string.
    data class ReqNationAux(val reqNationAuxKey: String, val cmp: String, val value: Double) : UnitConstraint() {
        override fun getInfo(): String {
            // Enum별 특수한 경우 (NationAuxKey switch)
            when (reqNationAuxKey) {
                "can_대검병사용"   -> if (cmp == "==" && value == 1.0) return "대검병 연구 시 가능"
                "can_극병사용"     -> if (cmp == "==" && value == 1.0) return "극병 연구 시 가능"
                "can_화시병사용"   -> if (cmp == "==" && value == 1.0) return "화시병 연구 시 가능"
                "can_원융노병사용" -> if (cmp == "==" && value == 1.0) return "원융노병 연구 시 가능"
                "can_산저병사용"   -> if (cmp == "==" && value == 1.0) return "산저병 연구 시 가능"
                "can_상병사용"     -> if (cmp == "==" && value == 1.0) return "상병 연구 시 가능"
                "can_음귀병사용"   -> if (cmp == "==" && value == 1.0) return "음귀병 연구 시 가능"
                "can_무희사용"     -> if (cmp == "==" && value == 1.0) return "무희 연구 시 가능"
                "can_화륜차사용"   -> if (cmp == "==" && value == 1.0) return "화륜차 연구 시 가능"
                "did_특성초토화"   -> if (cmp == ">=" && value == 1.0) return "특성 초토화 시 가능"
            }
            // 범용 fallback
            return when (cmp) {
                "==" -> when {
                    value == 0.0 -> "${reqNationAuxKey} 없을 때"
                    value == 1.0 -> "${reqNationAuxKey} 있을 때"
                    else         -> "${reqNationAuxKey} = ${value} 일 때"
                }
                "!=" -> when {
                    value == 0.0 -> "${reqNationAuxKey} 없을 때"
                    value == 1.0 -> "${reqNationAuxKey} 있을 때"
                    else         -> "${reqNationAuxKey} != ${value} 일 때"
                }
                else -> "${reqNationAuxKey} ${cmp} ${value} 일 때"
            }
        }
    }
}
