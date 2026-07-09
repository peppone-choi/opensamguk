package opensamguk.common.constants

object ScenarioLifecycleMeta {
    /**
     * 장수별 killturn(사망 예정 턴) 파생값.
     *
     * devsam GeneralBuilder.php:662 grand truth:
     *   killturn = (death - year) * 12 + rng.nextRangeInt(0, 11) + month - 1
     * (death=사망년도, year/month=장수 생성 시점의 게임 연/월)
     *
     * 시드 경로(ScenarioImporter)는 divergence-flag된 B-track이다(ScenarioImporter.kt "Parity boundary"
     * 주석 참조). 이 경로는 RNG draw를 전혀 하지 않는 결정론적 부트스트랩이므로 PHP의 지터
     * `nextRangeInt(0, 11)`는 의도적으로 생략한다 — 지터를 넣으려면 시나리오 시드 기반 결정론 RNG를
     * 도입해야 하며, 시드 단순성을 위해 배제한다.
     *
     * 과거 버그: 전역 baseline(EffectiveGameConst.killturn)을 전원에게 동일 주입 → 같은 턴에 전 장수
     * 동시 사망. 그 전역값을 사망년도 파생 per-general 값으로 대체한다. `deadYear <= startYear`(이미
     * 사망 예정이거나 데이터 이상)일 때 0 이하가 되지 않도록 최소 1로 보정한다.
     */
    fun killturnFor(deadYear: Int, startYear: Int, startMonth: Int): Int =
        ((deadYear - startYear) * 12 + (startMonth - 1)).coerceAtLeast(1)

    fun initialGeneralMeta(deadYear: Int, startYear: Int, startMonth: Int): Map<String, Any?> =
        linkedMapOf(
            "killturn" to killturnFor(deadYear, startYear, startMonth),
            "deadyear" to deadYear,
        )

    fun ensureGeneralMeta(
        meta: Map<String, Any?>,
        deadYear: Int,
        startYear: Int,
        startMonth: Int,
    ): Map<String, Any?> {
        val out = LinkedHashMap(meta)
        if (!out.containsKey("killturn")) {
            out["killturn"] = killturnFor(deadYear, startYear, startMonth)
        }
        if (!out.containsKey("deadyear")) {
            out["deadyear"] = deadYear
        }
        return out
    }
}
