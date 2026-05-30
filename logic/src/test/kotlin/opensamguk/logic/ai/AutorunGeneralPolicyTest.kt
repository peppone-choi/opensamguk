package opensamguk.logic.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * FP1 — [AutorunGeneralPolicy] parity tests.
 *
 * Port target = PHP `legacy/devsam-core/hwe/sammo/AutorunGeneralPolicy.php` (215 lines, read in full).
 * The merged `priority` array ORDER IS the `do<한글>` dispatch order, the log order, AND the draw order.
 *
 * B1 (decision #5, INVERTED from the old "dead-KV" draft, source-verified R-NATIONPOL +
 * AutorunGeneralPolicy.php:7-39,118-147): the KV `priority` override is LIVE. Each policy class declares
 * one `static` property per bare action name (`static $귀환='귀환'` … :7-39); `property_exists($this, $name)`
 * checks BOTH instance AND static props, so a valid bare-action-name KV item returns TRUE → it is KEPT →
 * the filtered list (if non-empty) REPLACES the default. A typo/unknown name is DROPPED with an
 * E_USER_NOTICE; an all-typo/empty filtered list leaves the default; the nation KV (applied SECOND)
 * wins over the server KV.
 */
class AutorunGeneralPolicyTest {

    // --- (1) default_priority — the EXACT ordered 14-list (AutorunGeneralPolicy.php:42-58) ---

    @Test
    fun `default_priority is the exact ordered 14-list`() {
        val expected = listOf(
            "NPC사망대비",
            "귀환",
            "금쌀구매",
            "출병",
            "긴급내정",
            "전투준비",
            "전방워프",
            // 'NPC증여' commented out (:50) — NOT in array
            "NPC헌납",
            "징병",
            "후방워프",
            "전쟁내정",
            "소집해제",
            "일반내정",
            "내정워프",
        )
        assertEquals(expected, AutorunGeneralPolicy.DEFAULT_PRIORITY)
        assertEquals(14, AutorunGeneralPolicy.DEFAULT_PRIORITY.size)
    }

    // --- (2) can-flag defaults (AutorunGeneralPolicy.php:61-90), pure-NPC path (npcType>=2, no doNPCState flip) ---

    @Test
    fun `can-flag defaults — all true except 모병 한계징병 고급병종 집합 선양`() {
        // npcType 3, nationID 0 → doNPCState only flips can국가선택/can건국 (nationID!=0) — here nationID==0 so none flip;
        // npc 3 is not 1 nor 5 → canNPC사망대비 stays true. So this exposes the raw class defaults.
        val p = AutorunGeneralPolicy(npcType = 3, nationId = 0)
        assertTrue(p.canNPC사망대비)
        assertTrue(p.can일반내정)
        assertTrue(p.can긴급내정)
        assertTrue(p.can전쟁내정)
        assertTrue(p.can금쌀구매)
        assertTrue(p.can상인무시)
        assertTrue(p.can징병)
        assertFalse(p.can모병)
        assertFalse(p.can한계징병)
        assertFalse(p.can고급병종)
        assertTrue(p.can전투준비)
        assertTrue(p.can소집해제)
        assertTrue(p.can출병)
        assertTrue(p.canNPC헌납)
        assertTrue(p.can후방워프)
        assertTrue(p.can전방워프)
        assertTrue(p.can내정워프)
        assertTrue(p.can귀환)
        assertTrue(p.can국가선택)
        assertFalse(p.can집합)
        assertTrue(p.can건국)
        assertFalse(p.can선양)
    }

    // --- (3) doNPCState (AutorunGeneralPolicy.php:94-116) ---

    @Test
    fun `doNPCState npc==5 flips 집합 선양 true and 국가선택 false`() {
        val p = AutorunGeneralPolicy(npcType = 5, nationId = 0)
        assertTrue(p.can집합)
        assertTrue(p.can선양)
        assertFalse(p.can국가선택)
    }

    @Test
    fun `doNPCState npc==1 branch is DEAD CODE — npc 1 takes the user-path, never doNPCState`() {
        // PHP: `if(getNPCType()>=2){ doNPCState(); return; }` (:149-152). npc==1 is < 2 → doNPCState is
        // NEVER called, so the `if($npc==1)` branch (:106-108) is UNREACHABLE dead code. npc 1 instead
        // takes the user-path reset (:154-179), which does NOT touch canNPC사망대비 → it stays default true.
        // Port the latent quirk verbatim; do NOT "fix" it to honor the dead branch (parity law).
        val p = AutorunGeneralPolicy(npcType = 1, nationId = 0, aiOptions = emptyMap())
        assertTrue(p.canNPC사망대비) // user-path leaves it at default true
        // user-path reset markers (proves npc 1 went user-path, not NPC path):
        assertFalse(p.can일반내정)
        assertTrue(p.can한계징병)
    }

    @Test
    fun `doNPCState nationID nonzero disables 국가선택 and 건국`() {
        val p = AutorunGeneralPolicy(npcType = 3, nationId = 7)
        assertFalse(p.can국가선택)
        assertFalse(p.can건국)
    }

    // --- (4) user-path baseline reset (npcType<2, :154-179) + aiOptions enable map (:181-213) ---

    @Test
    fun `user-path reset with no aiOptions forces the baseline-false set, keeps 한계징병 고급병종 소집해제 귀환 사망대비 true`() {
        val p = AutorunGeneralPolicy(npcType = 0, nationId = 0, aiOptions = emptyMap())
        // forced false (:154-179)
        assertFalse(p.can일반내정)
        assertFalse(p.can긴급내정)
        assertFalse(p.can전쟁내정)
        assertFalse(p.can금쌀구매)
        assertFalse(p.can상인무시)
        assertFalse(p.can징병)
        assertFalse(p.can모병)
        assertFalse(p.can전투준비)
        assertFalse(p.can출병)
        assertFalse(p.canNPC헌납)
        assertFalse(p.can후방워프)
        assertFalse(p.can전방워프)
        assertFalse(p.can내정워프)
        assertFalse(p.can국가선택)
        assertFalse(p.can집합)
        assertFalse(p.can건국)
        assertFalse(p.can선양)
        // explicitly flipped TRUE in the reset (:163-164)
        assertTrue(p.can한계징병)
        assertTrue(p.can고급병종)
        // left at default true (never reset)
        assertTrue(p.can소집해제)
        assertTrue(p.can귀환)
        assertTrue(p.canNPC사망대비)
    }

    @Test
    fun `aiOption develop enables 일반내정 전쟁내정 금쌀구매 NOT 긴급내정`() {
        val p = AutorunGeneralPolicy(npcType = 0, nationId = 0, aiOptions = mapOf("develop" to true))
        assertTrue(p.can일반내정)
        assertTrue(p.can전쟁내정)
        assertTrue(p.can금쌀구매)
        assertFalse(p.can긴급내정) // 유저장은 '긴급'을 하지 않음 (:186)
    }

    @Test
    fun `aiOption warp enables 후방 전방 내정워프 금쌀구매 상인무시`() {
        val p = AutorunGeneralPolicy(npcType = 0, nationId = 0, aiOptions = mapOf("warp" to true))
        assertTrue(p.can후방워프)
        assertTrue(p.can전방워프)
        assertTrue(p.can내정워프)
        assertTrue(p.can금쌀구매)
        assertTrue(p.can상인무시)
    }

    @Test
    fun `aiOption recruit enables 징병 소집해제 금쌀구매 NOT 모병`() {
        val p = AutorunGeneralPolicy(npcType = 0, nationId = 0, aiOptions = mapOf("recruit" to true))
        assertTrue(p.can징병)
        assertTrue(p.can소집해제)
        assertTrue(p.can금쌀구매)
        assertFalse(p.can모병)
    }

    @Test
    fun `aiOption recruit_high falls through into recruit, also enables 모병`() {
        val p = AutorunGeneralPolicy(npcType = 0, nationId = 0, aiOptions = mapOf("recruit_high" to true))
        assertTrue(p.can모병)   // recruit_high (:197-198)
        assertTrue(p.can징병)   // fall-through into recruit (no break, :199-203)
        assertTrue(p.can소집해제)
        assertTrue(p.can금쌀구매)
    }

    @Test
    fun `aiOption train enables 전투준비 금쌀구매`() {
        val p = AutorunGeneralPolicy(npcType = 0, nationId = 0, aiOptions = mapOf("train" to true))
        assertTrue(p.can전투준비)
        assertTrue(p.can금쌀구매)
    }

    @Test
    fun `aiOption battle enables 출병 금쌀구매`() {
        val p = AutorunGeneralPolicy(npcType = 0, nationId = 0, aiOptions = mapOf("battle" to true))
        assertTrue(p.can출병)
        assertTrue(p.can금쌀구매)
    }

    // --- (5) LIVE KV `priority` override (B1, decision #5) ---

    @Test
    fun `valid-name server-KV priority override REPLACES the default`() {
        // every name is a declared bare-action static → property_exists TRUE → KEPT → non-empty → REPLACES.
        val override = listOf("출병", "징병", "귀환")
        val p = AutorunGeneralPolicy(
            npcType = 3, nationId = 0,
            serverPolicy = mapOf("priority" to override),
        )
        assertEquals(override, p.priority)
        // it is NOT the default (the inverted assertion)
        assertTrue(p.priority != AutorunGeneralPolicy.DEFAULT_PRIORITY)
    }

    @Test
    fun `valid-name nation-KV priority override REPLACES the default`() {
        val override = listOf("일반내정", "전쟁내정")
        val p = AutorunGeneralPolicy(
            npcType = 3, nationId = 0,
            nationPolicy = mapOf("priority" to override),
        )
        assertEquals(override, p.priority)
    }

    @Test
    fun `a typo in the priority override DROPS that item, keeps the rest`() {
        // '귀한' is a typo of '귀환' → property_exists FALSE → dropped; the rest survive.
        val p = AutorunGeneralPolicy(
            npcType = 3, nationId = 0,
            serverPolicy = mapOf("priority" to listOf("출병", "귀한", "징병")),
        )
        assertEquals(listOf("출병", "징병"), p.priority)
    }

    @Test
    fun `an all-typo or empty filtered priority override LEAVES the default`() {
        val p = AutorunGeneralPolicy(
            npcType = 3, nationId = 0,
            serverPolicy = mapOf("priority" to listOf("귀한", "츨병")),
        )
        assertEquals(AutorunGeneralPolicy.DEFAULT_PRIORITY, p.priority)

        val pEmpty = AutorunGeneralPolicy(
            npcType = 3, nationId = 0,
            serverPolicy = mapOf("priority" to emptyList<String>()),
        )
        assertEquals(AutorunGeneralPolicy.DEFAULT_PRIORITY, pEmpty.priority)
    }

    @Test
    fun `nation-KV priority override WINS over server-KV priority override`() {
        val serverOverride = listOf("출병", "징병")
        val nationOverride = listOf("일반내정", "전쟁내정", "귀환")
        val p = AutorunGeneralPolicy(
            npcType = 3, nationId = 0,
            nationPolicy = mapOf("priority" to nationOverride),
            serverPolicy = mapOf("priority" to serverOverride),
        )
        // nation (applied SECOND, :135-147) overwrites server (:121-133)
        assertEquals(nationOverride, p.priority)
    }
}
