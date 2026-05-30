package opensamguk.logic.actions.founding

import opensamguk.common.constants.GameConst
import opensamguk.common.rng.LiteHashDrbg
import opensamguk.common.rng.RandUtil
import opensamguk.common.rng.serializeSeed
import opensamguk.logic.actions.GeneralActionDraft
import opensamguk.logic.actions.GeneralActionResolveContext
import opensamguk.logic.domain.City
import opensamguk.logic.domain.General
import opensamguk.logic.domain.GetNationColors
import opensamguk.logic.domain.metaInt
import opensamguk.logic.stats.GeneralActionPipeline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * FND3 — 거병 (the canonical CREATED-set command).
 *
 * `che_거병.php:25-186` is the first command that INSERTs entities rather than mutating existing ones:
 *   - one `nation` row (color #330000, gold 0, rice 2000, rate 20, bill 100, strategic_cmd_limit 12,
 *     surlimit 72, secretlimit 3|1, type che_중립, gennum 1, level 0 wandering),
 *   - a `diplomacy` PAIR per OTHER nation (`{me:dest,you:new}` THEN `{me:new,you:dest}`, state 2 term 0,
 *     in nation-id ASCENDING order),
 *   - 24 `nation_turn` rows (outer officer_level [12,11], inner turn_idx 0..11), action 휴식 / brief 휴식,
 * plus the actor's JOIN transition (belong 1, officer_level 12, officer_city 0, nation new), exp/ded +100,
 * and a TRAILING unique-item lottery (reason 거병) on the SEPARATE unique rng AFTER all writes.
 *
 * The new nation's runtime id is supplied by the adapter as the `newNationId` placeholder (PHP
 * `insertId()`); the existing-nation set + scenario + the dup-name set are likewise preloaded (PHP
 * `getAllNationStaticInfo()` / `SELECT count(*) FROM nation WHERE name`).
 */
class GeobyeongTest {

    private val pipeline = GeneralActionPipeline()
    private val MONTH = 7
    private val NEW_NATION_ID = 99
    private val FIXTURE_HIDDEN_SEED = "00000000000000000000000000000000"
    private val env = opensamguk.logic.domain.WorldEnv(year = 190, startYear = 184, develCost = 120)
    private val date = "08:30"

    private fun freshRng() = RandUtil(
        LiteHashDrbg(serializeSeed(FIXTURE_HIDDEN_SEED, "generalCommand", 190, MONTH, 42, "che_거병")))

    /** A fresh neutral general at 성도 (city id 5). explevel/dedlevel preset so the +100 lands flat. */
    private fun actor(name: String = "유비") = General(
        id = 42, nationId = 0, cityId = 5,
        leadership = 80, strength = 70, intel = 75, injury = 0,
        experience = 0.0, dedication = 0.0, officerLevel = 0, gold = 100, rice = 100,
        npcType = 0,
        meta = linkedMapOf(
            "name" to name,
            "explevel" to 1,            // getExpLevel(100)==1 → no level log noise
            "dedlevel" to 1,            // getDedLevel(100)==1 → no level log noise
            "officer_city" to 5,
        ),
    )

    private fun homeCity() = City(
        id = 5, nationId = 0, level = 5,
        commerce = 100, commerceMax = 100, agriculture = 100, agricultureMax = 100,
        supplyState = 1, frontState = 0, trust = 50.0,
    )

    private fun ctx(
        draft: GeneralActionDraft,
        existingNationIds: List<Int> = listOf(1, 2, 3),
        existingNationNames: Set<String> = emptySet(),
        scenario: Int = 0,
        generalName: String = "유비",
    ) = GeneralActionResolveContext(
        draft, freshRng(), env, MONTH, date,
        args = linkedMapOf(
            "newNationId" to NEW_NATION_ID,
            "existingNationIds" to existingNationIds,
            "existingNationNames" to existingNationNames,
            "scenario" to scenario,
        ),
        generalName = generalName,
    )

    private fun resolveOnce(
        existingNationIds: List<Int> = listOf(1, 2, 3),
        existingNationNames: Set<String> = emptySet(),
        scenario: Int = 0,
        name: String = "유비",
    ): GeneralActionResolveContext {
        val draft = GeneralActionDraft(actor(name), homeCity(), null)
        val context = ctx(draft, existingNationIds, existingNationNames, scenario, name)
        CheGeobyeong(pipeline).resolve(context)
        return context
    }

    @Test
    fun `inserts one nation with the PHP literal shape`() {
        val context = resolveOnce()
        val d = context.draft
        assertEquals(1, d.createdNations.size, "exactly one nation INSERTed")
        val n = d.createdNations.single()
        assertEquals(NEW_NATION_ID, n.id)
        assertEquals("유비", n.name)
        assertEquals("#330000", n.color)
        assertEquals(0, n.gold)
        assertEquals(GameConst.baserice, n.rice)
        assertEquals(2000, n.rice)
        assertEquals(0, n.level, "level 0 (wandering) — not set in the INSERT")
        assertEquals(GameConst.neutralNationType, n.typeCode)
        assertEquals("che_중립", n.typeCode)
        assertEquals(1, n.gennum)
        // meta-resident fields (PHP INSERT order: rate, bill, strategic_cmd_limit, surlimit, secretlimit, gennum)
        assertEquals(20, metaInt(n.meta, "rate"))
        assertEquals(100, metaInt(n.meta, "bill"))
        assertEquals(12, metaInt(n.meta, "strategic_cmd_limit"))
        assertEquals(72, metaInt(n.meta, "surlimit"))
        assertEquals(3, metaInt(n.meta, "secretlimit"))
        assertEquals(1, metaInt(n.meta, "gennum"))
        assertEquals(
            listOf("rate", "bill", "strategic_cmd_limit", "surlimit", "secretlimit", "gennum"),
            n.meta.keys.toList(),
            "meta key INSERTION order matches the PHP nation INSERT",
        )
    }

    @Test
    fun `secretlimit is 1 when scenario gte 1000`() {
        val n = resolveOnce(scenario = 1000).draft.createdNations.single()
        assertEquals(1, metaInt(n.meta, "secretlimit"))
    }

    @Test
    fun `secretlimit is 3 when scenario lt 1000`() {
        val n = resolveOnce(scenario = 999).draft.createdNations.single()
        assertEquals(3, metaInt(n.meta, "secretlimit"))
    }

    @Test
    fun `creates 24 nation_turn rows in officer-level outer turn-idx inner order`() {
        val turns = resolveOnce().draft.createdNationTurns
        assertEquals(24, turns.size)
        // outer loop [12, 11]; inner Util::range(maxChiefTurn) = 0..11
        val expected = buildList {
            for (lvl in listOf(12, 11)) for (idx in 0 until GameConst.maxChiefTurn) add(lvl to idx)
        }
        assertEquals(expected, turns.map { it.officerLevel to it.turnIdx })
        assertTrue(turns.all { it.nationId == NEW_NATION_ID })
        assertTrue(turns.all { it.action == "휴식" }, "action 휴식")
        assertTrue(turns.all { it.brief == "휴식" }, "brief 휴식 (V2 column)")
        assertTrue(turns.all { it.arg == null }, "arg null")
    }

    @Test
    fun `creates a diplomacy pair per other nation in ascending order`() {
        val dip = resolveOnce(existingNationIds = listOf(3, 1, 2)).draft.createdDiplomacy
        // 2 rows per existing nation; the OUTER iteration is nation-id ASCENDING (getAllNationStaticInfo)
        assertEquals(6, dip.size)
        // per nation: {me:dest, you:new} then {me:new, you:dest}, state 2 term 0
        assertEquals(
            listOf(
                1 to NEW_NATION_ID, NEW_NATION_ID to 1,
                2 to NEW_NATION_ID, NEW_NATION_ID to 2,
                3 to NEW_NATION_ID, NEW_NATION_ID to 3,
            ),
            dip.map { it.me to it.you },
        )
        assertTrue(dip.all { it.state == 2 && it.term == 0 })
    }

    @Test
    fun `no diplomacy when there are no other nations`() {
        assertTrue(resolveOnce(existingNationIds = emptyList()).draft.createdDiplomacy.isEmpty())
    }

    @Test
    fun `single ㉥ prefix when the bare name collides`() {
        val n = resolveOnce(name = "유비", existingNationNames = setOf("유비")).draft.createdNations.single()
        assertEquals("㉥유비", n.name)
    }

    @Test
    fun `double ㉥ prefix when the single-prefixed name still collides`() {
        val n = resolveOnce(name = "유비", existingNationNames = setOf("유비", "㉥유비"))
            .draft.createdNations.single()
        assertEquals("㉥㉥유비", n.name)
    }

    @Test
    fun `applies the JOIN transition to the actor`() {
        val g = resolveOnce().draft.general
        assertEquals(NEW_NATION_ID, g.nationId)
        assertEquals(12, g.officerLevel)
        assertEquals(1, metaInt(g.meta, "belong"))
        assertEquals(0, metaInt(g.meta, "officer_city"))
    }

    @Test
    fun `grants exp and dedication +100 and sets the result last_turn`() {
        val g = resolveOnce().draft.general
        assertEquals(100.0, g.experience)
        assertEquals(100.0, g.dedication)
        assertEquals("거병", g.lastTurn.command)
        // PHP passes the non-null empty arg ($this->arg = []) → toRaw emits an (empty) arg
        assertEquals(emptyMap<String, Any?>(), g.lastTurn.arg)
        assertNull(g.lastTurn.term)
        assertNull(g.lastTurn.seq)
    }

    @Test
    fun `emits the general action log and the global action log`() {
        val context = resolveOnce()
        assertEquals(
            listOf("<C>●</>${MONTH}월:거병에 성공하였습니다. <1>$date</>"),
            context.logs(),
        )
        // <Y>{name}</>{josa이} <G><b>{city}</b></>에 거병하였습니다.  (city 성도, name 유비 → josa 가)
        assertEquals(
            listOf("<C>●</>${MONTH}월:<Y>유비</>가 <G><b>성도</b></>에 거병하였습니다."),
            context.globalActionLogs(),
        )
    }

    @Test
    fun `the trailing unique lottery does not perturb the action draw stream`() {
        // The created-set is purely a function of preloaded data — it must NOT consume from the action rng.
        val rngTracking = freshRng()
        val draft = GeneralActionDraft(actor(), homeCity(), null)
        val context = GeneralActionResolveContext(
            draft, rngTracking, env, MONTH, date,
            args = linkedMapOf(
                "newNationId" to NEW_NATION_ID,
                "existingNationIds" to listOf(1, 2, 3),
                "existingNationNames" to emptySet<String>(),
                "scenario" to 0,
            ),
            generalName = "유비",
        )
        CheGeobyeong(pipeline).resolve(context)

        // After resolve, a fresh rng from the same seed produces the SAME first draw as the used one —
        // i.e. the resolve drew nothing from the action stream (the lottery rides a separate rng seam).
        assertEquals(freshRng().nextInt(0, 1_000_000), rngTracking.nextInt(0, 1_000_000))
        // no acquisition for the default human general (P2 domestic lottery wins nothing → item slot untouched)
        assertEquals("None", draft.general.item)
    }

    @Test
    fun `GetNationColors is the 33-element legacy array`() {
        val colors = GetNationColors()
        assertEquals(33, colors.size)
        assertEquals("#FF0000", colors.first())
        assertEquals("#A9A9A9", colors.last())
    }
}
