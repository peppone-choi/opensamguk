package opensamguk.common.constants

object ScenarioLifecycleMeta {
    fun initialGeneralMeta(deadYear: Int, turnterm: Int, npcmode: Int): Map<String, Any?> =
        linkedMapOf(
            "killturn" to EffectiveGameConst.killturn(turnterm, npcmode),
            "deadyear" to deadYear,
        )

    fun ensureGeneralMeta(
        meta: Map<String, Any?>,
        deadYear: Int,
        turnterm: Int,
        npcmode: Int,
    ): Map<String, Any?> {
        val out = LinkedHashMap(meta)
        if (!out.containsKey("killturn")) {
            out["killturn"] = EffectiveGameConst.killturn(turnterm, npcmode)
        }
        if (!out.containsKey("deadyear")) {
            out["deadyear"] = deadYear
        }
        return out
    }
}
