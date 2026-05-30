package opensamguk.logic.actions.founding

import opensamguk.common.rng.LiteHashDrbg
import opensamguk.common.rng.RandUtil
import opensamguk.common.rng.serializeSeed
import opensamguk.logic.actions.GeneralActionDraft
import opensamguk.logic.actions.GeneralActionResolveContext
import opensamguk.logic.domain.City
import opensamguk.logic.domain.General
import opensamguk.logic.domain.GetNationColors
import opensamguk.logic.domain.Nation
import opensamguk.logic.domain.WorldEnv
import opensamguk.logic.domain.metaInt
import opensamguk.logic.stats.GeneralActionPipeline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * FND4 — 건국 / cr_건국 / 무작위건국.
 *
 * The three founding commands that MUTATE the actor's already-existing wandering nation (level 0→1)
 * rather than INSERT a new one (that is 거병, FND3). Faithful ports of:
 *   - `che_건국.php`        : ConstructableCity claim, aux can_국기변경=1, exp/ded +1000,
 *                             active_action+1 AND **unifier+250**, same-month guard → che_인재탐색.
 *   - `cr_건국.php`         : NeutralCity (no ConstructableCity/NoPenalty), **NO unifier+250** (divergence).
 *   - `che_무작위건국.php`   : rng->choice over neutral level-5/6 cities, relocate ALL the nation's generals
 *                             to the chosen city, aux can_국기변경=1 AND can_무작위수도이전=1, NO unifier.
 *
 * The new nation runtime-id is the actor's existing wandering nation; the dup-name set / scenario / the
 * candidate-city set / the same-month flag are preloaded by the adapter (PHP DB queries / env math).
 */
class GeongukTest {

    private val pipeline = GeneralActionPipeline()
    private val MONTH = 7
    private val NATION_ID = 7
    private val FIXTURE_HIDDEN_SEED = "00000000000000000000000000000000"
    private val env = WorldEnv(year = 190, startYear = 184, develCost = 120)
    private val date = "08:30"

    private fun freshRng(rawClassName: String) = RandUtil(
        LiteHashDrbg(serializeSeed(FIXTURE_HIDDEN_SEED, "generalCommand", 190, MONTH, 42, rawClassName)))

    /**
     * The wandering lord. explevel/dedlevel preset to the POST-grant levels (getExpLevel(1000)=10,
     * getDedLevel(1000)=4) so the +1000 lands flat (no level-log noise), mirroring GeobyeongTest.
     */
    private fun lord(name: String = "유비", cityId: Int = 5) = General(
        id = 42, nationId = NATION_ID, cityId = cityId,
        leadership = 80, strength = 70, intel = 75, injury = 0,
        experience = 0.0, dedication = 0.0, officerLevel = 12, gold = 100, rice = 100,
        npcType = 0,
        meta = linkedMapOf(
            "name" to name,
            "explevel" to 10,            // getExpLevel(1000)==10
            "dedlevel" to 4,             // getDedLevel(1000)==4
            "officer_city" to cityId,
        ),
    )

    private fun wanderingNation() = Nation(
        id = NATION_ID, level = 0, capitalCityId = 0,
        name = "유비", color = "#330000", typeCode = "che_중립",
        gold = 0, rice = 2000, gennum = 2,
        meta = linkedMapOf("gennum" to 2, "aux" to linkedMapOf<String, Any?>()),
    )

    private fun homeCity(id: Int = 5, nation: Int = 0) = City(
        id = id, nationId = nation, level = 5,
        commerce = 100, commerceMax = 100, agriculture = 100, agricultureMax = 100,
        supplyState = 1, frontState = 0, trust = 50.0,
        meta = linkedMapOf("conflict" to linkedMapOf<String, Any?>("x" to 1)),
    )

    private fun ctx(
        draft: GeneralActionDraft,
        rawClassName: String,
        args: Map<String, Any?> = emptyMap(),
        candidateCityIds: List<Int> = emptyList(),
        candidateGenerals: List<General> = emptyList(),
        name: String = "유비",
    ) = GeneralActionResolveContext(
        draft, freshRng(rawClassName), env, MONTH, date,
        args = args,
        candidateGenerals = candidateGenerals,
        candidateCityIds = candidateCityIds,
        generalName = name,
    )

    // ---- che_건국 ----

    @Test
    fun `che_건국 claims the city and raises the nation to level 1`() {
        val draft = GeneralActionDraft(lord(), homeCity(), wanderingNation())
        val context = ctx(draft, "che_건국", args = founeArgs())
        CheGeonguk(pipeline).resolve(context)

        val n = draft.nation!!
        assertEquals(1, n.level, "level 0 → 1")
        assertEquals("촉", n.name)
        assertEquals(GetNationColors()[5], n.color, "color = GetNationColors()[colorType]")
        assertEquals("che_명사", n.typeCode)
        assertEquals(5, n.capitalCityId)
        assertEquals(1, auxInt(n, "can_국기변경"))
        // city claimed by the actor's nation, conflict reset
        assertEquals(NATION_ID, draft.city.nationId)
        assertFalse(draft.city.meta.containsKey("conflict"), "conflict reset to {}")
    }

    @Test
    fun `che_건국 grants exp ded +1000 and sets the result last_turn with the full arg`() {
        val draft = GeneralActionDraft(lord(), homeCity(), wanderingNation())
        val context = ctx(draft, "che_건국", args = founeArgs())
        CheGeonguk(pipeline).resolve(context)

        val g = draft.general
        assertEquals(1000.0, g.experience)
        assertEquals(1000.0, g.dedication)
        assertEquals("건국", g.lastTurn.command)
        assertEquals(
            linkedMapOf("nationName" to "촉", "nationType" to "che_명사", "colorType" to 5),
            g.lastTurn.arg,
        )
        assertNull(g.lastTurn.term)
        assertNull(g.lastTurn.seq)
    }

    @Test
    fun `che_건국 grants the unifier inheritance point (+250)`() {
        val draft = GeneralActionDraft(lord(), homeCity(), wanderingNation())
        val cmd = CheGeonguk(pipeline)
        cmd.resolve(ctx(draft, "che_건국", args = founeArgs()))
        assertEquals(250, cmd.lastUnifierGrant, "che_건국 grants unifier +250")
    }

    @Test
    fun `che_건국 emits the general action log and the global action log`() {
        val draft = GeneralActionDraft(lord(), homeCity(), wanderingNation())
        val context = ctx(draft, "che_건국", args = founeArgs())
        CheGeonguk(pipeline).resolve(context)
        // <D><b>{nation}</b></>{josa을} 건국하였습니다. <1>{date}</>  (촉 → josa 을)
        assertEquals(
            listOf("<C>●</>${MONTH}월:<D><b>촉</b></>을 건국하였습니다. <1>$date</>"),
            context.logs(),
        )
        // <Y>{name}</>{josa이} <G><b>{city}</b></>에 국가를 건설하였습니다.  (성도, 유비 → josa 가)
        assertEquals(
            listOf("<C>●</>${MONTH}월:<Y>유비</>가 <G><b>성도</b></>에 국가를 건설하였습니다."),
            context.globalActionLogs(),
        )
    }

    @Test
    fun `che_건국 same-month-or-before blocks with the alternative seam`() {
        val draft = GeneralActionDraft(lord(), homeCity(), wanderingNation())
        val cmd = CheGeonguk(pipeline)
        val context = ctx(draft, "che_건국", args = founeArgs(sameMonthOrBefore = true))
        cmd.resolve(context)

        // the block log + no nation/city mutation
        assertEquals(
            listOf("<C>●</>${MONTH}월:다음 턴부터 건국할 수 있습니다. <1>$date</>"),
            context.logs(),
        )
        assertEquals(0, draft.nation!!.level, "nation untouched on same-month block")
        assertEquals(0, draft.city.nationId, "city untouched on same-month block")
        assertEquals(0.0, draft.general.experience, "no exp grant on block")
        assertEquals("che_인재탐색", cmd.lastAlternative)
    }

    // ---- cr_건국 (divergence: NeutralCity + NO unifier) ----

    @Test
    fun `cr_건국 founds the nation exactly like che_건국 but grants NO unifier`() {
        val draft = GeneralActionDraft(lord(), homeCity(), wanderingNation())
        val cmd = CrGeonguk(pipeline)
        cmd.resolve(ctx(draft, "cr_건국", args = founeArgs()))

        val n = draft.nation!!
        assertEquals(1, n.level)
        assertEquals("촉", n.name)
        assertEquals(1, auxInt(n, "can_국기변경"))
        assertEquals(NATION_ID, draft.city.nationId)
        assertEquals(1000.0, draft.general.experience)
        assertEquals(1000.0, draft.general.dedication)
        // the DIVERGENCE: cr_건국 grants NO unifier
        assertEquals(0, cmd.lastUnifierGrant, "cr_건국 grants NO unifier (divergence)")
    }

    @Test
    fun `cr_건국 carries the distinct cr_ rawClassName seed component`() {
        // cr_건국 is a DISTINCT class from che_건국 — its 6th seed component is `cr_건국` (mb_strlen 5).
        assertEquals("cr_건국", CrGeonguk(pipeline).rawClassName)
        assertEquals("che_건국", CheGeonguk(pipeline).rawClassName)
    }

    // ---- che_무작위건국 (rng->choice + relocate all generals) ----

    @Test
    fun `무작위건국 picks a city via rng-choice and relocates every nation general to it`() {
        // a fellow nation member preloaded to relocate alongside the actor
        val member = lord(name = "관우", cityId = 99).copy(id = 43, officerLevel = 1)
        val draft = GeneralActionDraft(lord(cityId = 99), homeCity(id = 99, nation = 0), wanderingNation())
        val cands = listOf(5, 8, 11)   // neutral level-5/6 city ids (DB-ascending), the rng->choice pool
        val context = ctx(
            draft, "che_무작위건국", args = founeArgs(),
            candidateCityIds = cands, candidateGenerals = listOf(member), name = "유비",
        )
        CheMujakwiGeonguk(pipeline).resolve(context)

        // the chosen city is rng->choice(cands) — must match a fresh rng draw against the same pool
        val chosen = freshRng("che_무작위건국").choice(cands)
        assertEquals(chosen, draft.general.cityId, "actor relocated to the chosen city")
        assertEquals(chosen, draft.cascadeGenerals.single().cityId, "every nation general relocated")
        // nation claims the chosen city
        assertEquals(1, draft.nation!!.level)
        assertEquals(chosen, draft.nation!!.capitalCityId)
        assertEquals(chosen, draft.city.id, "draft.city is the chosen city")
        assertEquals(NATION_ID, draft.city.nationId)
    }

    @Test
    fun `무작위건국 sets BOTH aux can_국기변경 and can_무작위수도이전, and NO unifier`() {
        val draft = GeneralActionDraft(lord(cityId = 5), homeCity(id = 5, nation = 0), wanderingNation())
        val cmd = CheMujakwiGeonguk(pipeline)
        cmd.resolve(ctx(draft, "che_무작위건국", args = founeArgs(), candidateCityIds = listOf(5)))

        val n = draft.nation!!
        assertEquals(1, auxInt(n, "can_국기변경"))
        assertEquals(1, auxInt(n, "can_무작위수도이전"))
        // aux jsonb key INSERTION order: can_국기변경 first, then can_무작위수도이전 (PHP set order)
        @Suppress("UNCHECKED_CAST")
        val aux = n.meta["aux"] as Map<String, Any?>
        assertEquals(listOf("can_국기변경", "can_무작위수도이전"), aux.keys.toList())
        assertEquals(0, cmd.lastUnifierGrant, "무작위건국 grants NO unifier")
    }

    @Test
    fun `무작위건국 stops with the 해산 alternative when there are no candidate cities`() {
        val draft = GeneralActionDraft(lord(cityId = 5), homeCity(id = 5, nation = 0), wanderingNation())
        val cmd = CheMujakwiGeonguk(pipeline)
        val context = ctx(draft, "che_무작위건국", args = founeArgs(), candidateCityIds = emptyList())
        cmd.resolve(context)

        assertEquals(
            listOf("<C>●</>${MONTH}월:건국할 수 있는 도시가 없습니다. <1>$date</>"),
            context.logs(),
        )
        assertEquals(0, draft.nation!!.level, "nation untouched on no-city")
        assertEquals("che_해산", cmd.lastAlternative)
    }

    @Test
    fun `무작위건국 carries its distinct rawClassName and lotteryActionName`() {
        val cmd = CheMujakwiGeonguk(pipeline)
        assertEquals("che_무작위건국", cmd.rawClassName)
        // static::$actionName = '무작위 도시 건국' (the lottery seed token, distinct from rawClassName)
        assertEquals("무작위 도시 건국", cmd.lotteryActionName)
    }

    @Test
    fun `the trailing unique lottery never perturbs the action draw stream for che_건국`() {
        // che_건국 draws nothing from the action rng (no rng usage); the lottery rides a separate seam.
        val rngTracking = freshRng("che_건국")
        val draft = GeneralActionDraft(lord(), homeCity(), wanderingNation())
        val context = GeneralActionResolveContext(
            draft, rngTracking, env, MONTH, date, args = founeArgs(), generalName = "유비")
        CheGeonguk(pipeline).resolve(context)
        assertEquals(freshRng("che_건국").nextInt(0, 1_000_000), rngTracking.nextInt(0, 1_000_000))
        assertEquals("None", draft.general.item, "default human general acquires nothing in P2")
    }

    // --- helpers ---

    private fun founeArgs(sameMonthOrBefore: Boolean = false): Map<String, Any?> = linkedMapOf(
        "nationName" to "촉",
        "nationType" to "che_명사",
        "colorType" to 5,
        "relYear" to env.relYear,
        "sameMonthOrBefore" to sameMonthOrBefore,
    )

    private fun auxInt(n: Nation, key: String): Int {
        @Suppress("UNCHECKED_CAST")
        val aux = (n.meta["aux"] as? Map<String, Any?>) ?: emptyMap()
        return (aux[key] as? Number)?.toInt() ?: 0
    }
}
