package opensamguk.common.constants

/**
 * Faithful port of `legacy/devsam-core/hwe/sammo/GameUnitConstraint/` getInfo() bodies.
 *
 * Historical PHP strings are frozen regression evidence here (ADR-LITE-042), not current product authority; TS devsam-core2026 emits boolean
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

    /**
     * 지역 금제 — 주둔한 땅이 이 목록에 들면 뽑을 수 없다. [ReqRegions] 의 거울이 아니다:
     * 저쪽은 "나라가 그 땅을 가졌나"를 보고, 이쪽은 "지금 이 부대가 어디 서 있나"를 본다.
     * 없는 것을 뽑을 수 없다는 규칙이라 소유가 아니라 위치가 기준이어야 한다.
     * (倭人傳 「其地無牛、馬、虎、豹、羊、鵲」 — 왜 땅에는 말이 없으므로 기병을 뽑지 못한다.)
     *
     * devsam/core 에는 없는 v2 확장이다. 기존 34행 중 이 제약을 쓰는 행이 없으므로 패러티 불변.
     */
    data class ForbidRegions(val forbidRegions: List<String>) : UnitConstraint() {
        override fun getInfo() = "${forbidRegions.joinToString(", ")} 지역에서는 불가"
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
