package opensamguk.logic.ai

/**
 * F-POLICY — `AutorunGeneralPolicy`, a faithful port of
 * `legacy/devsam-core/hwe/sammo/AutorunGeneralPolicy.php` (215 lines, frozen historical baseline (ADR-LITE-042; not current product authority), read in full).
 *
 * The general-AI 4-layer policy merge. The merged [priority] list ORDER **is** the `do<한글>` dispatch
 * order in `chooseGeneralTurn`, which is the log order AND the per-decision RNG draw order. This class
 * itself makes ZERO draws — it only computes the priority spine + the `can*` gates the dispatcher reads.
 *
 * PURE: no Spring, no DB. The PHP ctor takes `GeneralBase $general` + `$nation` + `$env`, but its body
 * only dereferences `$general->getNPCType()` (:95,149) and `$general->getNationID()` (:96); `$nation`/`$env`
 * are forwarded for symmetry and never read here (G1 §4.C) — so this port takes only what it reads.
 *
 * **B1 / decision #5 (INVERTED from the old "dead-KV" draft — source-verified R-NATIONPOL +
 * AutorunGeneralPolicy.php:7-39,118-147):** the KV `priority` override is LIVE. Each bare action name is
 * a declared `static` property (`static $귀환='귀환'` … :7-39); PHP `property_exists($this, $name)` checks
 * BOTH instance AND static props, so a valid bare-action-name KV item returns TRUE → it is KEPT → the
 * filtered list (if non-empty) REPLACES the default. A typo/unknown name is DROPPED (E_USER_NOTICE in PHP);
 * an all-typo/empty filtered list leaves the default. The nation KV (applied SECOND) wins over the server KV.
 *
 * The 4 layers (:118-213):
 *  1. default — `$this->priority = $default_priority` (:119).
 *  2. serverPolicy (global KV) — filter+replace the priority array (:121-133).
 *  3. nationPolicy (nation KV) — filter+replace, runs AFTER #2 so it WINS (:135-147).
 *  4. npcType branch — npcType>=2 → `doNPCState` + return (:149-152); npcType<2 → user-path baseline
 *     reset (:154-179) then the `aiOptions` enable map (:181-213). aiOptions flip `can*` ONLY, never reorder.
 */
class AutorunGeneralPolicy(
    npcType: Int,
    nationId: Int,
    aiOptions: Map<String, Any?>? = null,
    nationPolicy: Map<String, Any?>? = null,
    serverPolicy: Map<String, Any?>? = null,
) {
    // --- can* action gates (AutorunGeneralPolicy.php:61-90); defaults: all true EXCEPT the five below ---
    var canNPC사망대비: Boolean = true
    var can일반내정: Boolean = true
    var can긴급내정: Boolean = true
    var can전쟁내정: Boolean = true

    var can금쌀구매: Boolean = true
    var can상인무시: Boolean = true

    var can징병: Boolean = true
    var can모병: Boolean = false
    var can한계징병: Boolean = false
    var can고급병종: Boolean = false
    var can전투준비: Boolean = true
    var can소집해제: Boolean = true

    var can출병: Boolean = true

    var canNPC헌납: Boolean = true

    var can후방워프: Boolean = true
    var can전방워프: Boolean = true
    var can내정워프: Boolean = true

    var can귀환: Boolean = true

    var can국가선택: Boolean = true
    var can집합: Boolean = false
    var can건국: Boolean = true
    var can선양: Boolean = false

    /** The merged dispatch/log/draw order spine (AutorunGeneralPolicy.php:92, set :119). */
    var priority: List<String> = DEFAULT_PRIORITY
        private set

    /**
     * The priority-loop guard accessor (GeneralAI.php:3830-3835, consumed by [GeneralAI.chooseGeneralTurn]).
     * `property_exists($this, 'can'.$actionName)` (guard-A, :3830) — an unknown action name has no `can<name>`
     * property → PHP `trigger_error(E_USER_NOTICE)` + `continue` (here: returns null → the loop SKIPS it without
     * a `do<name>` call). A known action returns its live `can<name>` boolean (guard-B, :3834).
     *
     * Returns null when no `can<name>` property exists (the guard-A skip-with-notice), else the flag value.
     */
    fun canFor(actionName: String): Boolean? = when (actionName) {
        "NPC사망대비" -> canNPC사망대비
        "일반내정" -> can일반내정
        "긴급내정" -> can긴급내정
        "전쟁내정" -> can전쟁내정
        "금쌀구매" -> can금쌀구매
        "상인무시" -> can상인무시
        "징병" -> can징병
        "모병" -> can모병
        "한계징병" -> can한계징병
        "고급병종" -> can고급병종
        "전투준비" -> can전투준비
        "소집해제" -> can소집해제
        "출병" -> can출병
        "NPC헌납" -> canNPC헌납
        "후방워프" -> can후방워프
        "전방워프" -> can전방워프
        "내정워프" -> can내정워프
        "귀환" -> can귀환
        "국가선택" -> can국가선택
        "집합" -> can집합
        "건국" -> can건국
        "선양" -> can선양
        else -> null // guard-A: no `can<name>` property → skip with notice (the loop drops it)
    }

    init {
        // Layer 1: default (AutorunGeneralPolicy.php:119)
        priority = DEFAULT_PRIORITY

        // Layer 2: serverPolicy (global) KV priority override (:121-133)
        applyPriorityOverride(serverPolicy)
        // Layer 3: nationPolicy (nation) KV priority override (:135-147) — runs AFTER #2 → wins
        applyPriorityOverride(nationPolicy)

        // Layer 4: the npcType branch (:149-213).
        // PHP: `if(npcType>=2){ doNPCState(); return; }` then the user-path block. Modelled as an
        // if/else so the user-path runs ONLY for npcType<2 (init blocks forbid an early `return`).
        if (npcType >= 2) {
            doNPCState(npcType, nationId)
        } else {
            userPathReset()
            applyAiOptions(aiOptions)
        }
    }

    /**
     * `doNPCState` (AutorunGeneralPolicy.php:94-116), the npcType>=2 path.
     * npc==5 → 집합/선양 enabled, 국가선택 disabled (early return, :98-104);
     * npc==1 → canNPC사망대비 disabled (:106-108);
     * nationID!=0 → 국가선택/건국 disabled (:110-113).
     */
    private fun doNPCState(npcType: Int, nationId: Int) {
        if (npcType == 5) {
            can집합 = true
            can선양 = true
            can집합 = true            // PHP sets can집합 twice (:99,101) — faithful, idempotent
            can국가선택 = false
            return
        }

        if (npcType == 1) {
            canNPC사망대비 = false
        }

        if (nationId != 0) {
            can국가선택 = false
            can건국 = false
        }
    }

    /** User-path baseline reset (npcType<2, AutorunGeneralPolicy.php:154-179) — almost all forced false. */
    private fun userPathReset() {
        can일반내정 = false
        can긴급내정 = false
        can전쟁내정 = false

        can금쌀구매 = false
        can상인무시 = false

        can징병 = false
        can모병 = false
        can한계징병 = true   // explicitly flipped TRUE (:163)
        can고급병종 = true   // explicitly flipped TRUE (:164)
        can전투준비 = false

        can출병 = false

        canNPC헌납 = false

        can후방워프 = false
        can전방워프 = false
        can내정워프 = false

        can국가선택 = false
        can집합 = false
        can건국 = false
        can선양 = false
        // can소집해제 / can귀환 / canNPC사망대비 are NOT reset here → stay at default true.
    }

    /**
     * aiOptions enable map (AutorunGeneralPolicy.php:181-213). Flips `can*` ONLY, never reorders priority.
     * PHP `assert($value)` (:182): an option value must be truthy. `recruit_high` falls through into
     * `recruit` (no break, :197-203).
     */
    private fun applyAiOptions(aiOptions: Map<String, Any?>?) {
        if (aiOptions == null) return
        for ((key, value) in aiOptions) {
            check(isTruthy(value)) { "aiOptions['$key'] must be truthy (PHP assert(\$value), :182)" }
            when (key) {
                "develop" -> {
                    can일반내정 = true
                    // 유저장은 '긴급'을 하지 않음 (:186) — 긴급내정 NOT enabled
                    can전쟁내정 = true
                    can금쌀구매 = true
                }
                "warp" -> {
                    can후방워프 = true
                    can전방워프 = true
                    can내정워프 = true
                    can금쌀구매 = true
                    can상인무시 = true
                }
                "recruit_high" -> {
                    can모병 = true
                    // fall-through into 'recruit' (no break, :198-203)
                    can징병 = true
                    can소집해제 = true
                    can금쌀구매 = true
                }
                "recruit" -> {
                    can징병 = true
                    can소집해제 = true
                    can금쌀구매 = true
                }
                "train" -> {
                    can전투준비 = true
                    can금쌀구매 = true
                }
                "battle" -> {
                    can출병 = true
                    can금쌀구매 = true
                }
            }
        }
    }

    /**
     * The LIVE KV `priority` override (AutorunGeneralPolicy.php:121-133 / :135-147 — byte-identical blocks).
     * Filter each item through `property_exists($this, $item)` (TRUE for any declared static/instance prop —
     * here the bare-action-name statics ∪ `can*` ∪ `priority`); drop unknown names (E_USER_NOTICE in PHP);
     * if the filtered list is non-empty, REPLACE the priority (decision #5 / B1). NO secondary sort —
     * insertion order is preserved (PHP `$priority[] = $item`).
     */
    private fun applyPriorityOverride(policy: Map<String, Any?>?) {
        if (policy == null || !policy.containsKey("priority")) return
        @Suppress("UNCHECKED_CAST")
        val items = (policy["priority"] as? List<String>) ?: return
        val filtered = items.filter { it in VALID_PRIORITY_NAMES }
        if (filtered.isNotEmpty()) {
            priority = filtered
        }
    }

    companion object {
        /**
         * `$default_priority` (AutorunGeneralPolicy.php:42-58) — the EXACT ordered 14-list.
         * `//'NPC증여'` (:50) is commented out → NOT in the array. THIS ORDER is the dispatch/log/draw spine.
         */
        val DEFAULT_PRIORITY: List<String> = listOf(
            "NPC사망대비",
            "귀환",
            "금쌀구매",
            "출병",
            "긴급내정",
            "전투준비",
            "전방워프",
            "NPC헌납",
            "징병",
            "후방워프",
            "전쟁내정",
            "소집해제",
            "일반내정",
            "내정워프",
        )

        /**
         * The names PHP `property_exists($this, $name)` returns TRUE for (the override-guard's KEEP set):
         * the 22 declared bare-action-name statics (:7-39, NPC증여/전투이동/내정이동 commented out) ∪ the 22
         * `can*` instance props (:61-90) ∪ `priority` (:92). A typo like `귀한` is in NONE → dropped.
         */
        private val STATIC_ACTION_NAMES: Set<String> = setOf(
            // :7-39 (the live `static $X = 'X'` declarations; commented ones excluded)
            "일반내정", "긴급내정", "전쟁내정",
            "금쌀구매", "상인무시",
            "징병", "모병", "한계징병", "고급병종", "전투준비", "소집해제",
            "출병",
            "NPC헌납", "NPC사망대비",
            "후방워프", "전방워프", "내정워프",
            "귀환",
            "국가선택", "집합", "건국", "선양",
        )

        private val CAN_FLAG_NAMES: Set<String> = STATIC_ACTION_NAMES.mapTo(LinkedHashSet()) { "can$it" }

        private val VALID_PRIORITY_NAMES: Set<String> =
            (STATIC_ACTION_NAMES + CAN_FLAG_NAMES + "priority")
    }
}

/** PHP truthiness for the aiOptions `assert($value)` (:182). */
private fun isTruthy(value: Any?): Boolean = when (value) {
    null -> false
    is Boolean -> value
    is Int -> value != 0
    is Long -> value != 0L
    is Double -> value != 0.0
    is String -> value.isNotEmpty() && value != "0"
    is Collection<*> -> value.isNotEmpty()
    is Map<*, *> -> value.isNotEmpty()
    else -> true
}
