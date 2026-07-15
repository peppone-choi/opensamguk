package opensamguk.common.constants

object ScenarioLifecycleMeta {
    const val KILLTURN_UNIT_PHASE = "phase"
    /**
     * 장수별 killturn(사망 예정 턴) 파생값.
     *
     * devsam GeneralBuilder.php:662 month-only grand truth:
     *   killturn = (death - year) * 12 + rng.nextRangeInt(0, 11) + month - 1
     * (death=사망년도, year/month=장수 생성 시점의 게임 연/월)
     *
     * Opensamguk's approved calendar expands each legacy month into three executable phases
     * (상순/중순/하순). NPC killturn still decrements once per executable turn, so the legacy
     * month count must be multiplied by [GameConst.phasesPerMonth] or NPCs die at one-third age.
     *
     * [legacyMonthJitter] is the PHP `rng.nextRangeInt(0, 11)` draw. Deterministic seed/import paths
     * pass the default 0 because they do not own a PHP scenario RNG stream; runtime GeneralBuilder
     * paths pass the actual draw before conversion.
     *
     * 과거 버그: 전역 baseline(EffectiveGameConst.killturn)을 전원에게 동일 주입 → 같은 턴에 전 장수
     * 동시 사망. 그 전역값을 사망년도 파생 per-general 값으로 대체한다. `deadYear <= startYear`(이미
     * 사망 예정이거나 데이터 이상)일 때 한 달 분량보다 작아지지 않도록 최소 1 legacy month로 보정한다.
     */
    fun killturnFor(
        deadYear: Int,
        startYear: Int,
        startMonth: Int,
        legacyMonthJitter: Int = 0,
    ): Int {
        require(legacyMonthJitter in 0..11) { "legacyMonthJitter must be in 0..11" }
        return ((deadYear - startYear) * 12 + legacyMonthJitter + (startMonth - 1))
            .coerceAtLeast(1) * GameConst.phasesPerMonth
    }

    fun initialGeneralMeta(deadYear: Int, startYear: Int, startMonth: Int): Map<String, Any?> =
        linkedMapOf(
            "killturn" to killturnFor(deadYear, startYear, startMonth),
            "killturn_unit" to KILLTURN_UNIT_PHASE,
            "deadyear" to deadYear,
        )

    fun ensureGeneralMeta(
        meta: Map<String, Any?>,
        deadYear: Int,
        startYear: Int,
        startMonth: Int,
        convertLegacyNpcKillturn: Boolean = false,
    ): Map<String, Any?> {
        val out = LinkedHashMap(meta)
        if (!out.containsKey("killturn")) {
            out["killturn"] = killturnFor(deadYear, startYear, startMonth)
            out["killturn_unit"] = KILLTURN_UNIT_PHASE
        } else if (convertLegacyNpcKillturn && out["killturn_unit"] != KILLTURN_UNIT_PHASE) {
            val legacyKillturn = (out["killturn"] as? Number)?.toInt()
            if (legacyKillturn != null) {
                out["killturn"] = legacyKillturn * GameConst.phasesPerMonth
                out["killturn_unit"] = KILLTURN_UNIT_PHASE
            }
        }
        if (!out.containsKey("deadyear")) {
            out["deadyear"] = deadYear
        }
        return out
    }
}
