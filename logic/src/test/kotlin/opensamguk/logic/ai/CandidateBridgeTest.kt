package opensamguk.logic.ai

import opensamguk.logic.actions.CommandRegistry
import opensamguk.logic.actions.GeneralActionDefinition
import opensamguk.logic.actions.GeneralActionResolveContext
import opensamguk.logic.constraints.Constraint
import opensamguk.logic.constraints.ConstraintContext
import opensamguk.logic.constraints.ConstraintMode
import opensamguk.logic.constraints.ConstraintResult
import opensamguk.logic.constraints.RequirementKey
import opensamguk.logic.constraints.StateView
import opensamguk.logic.domain.City
import opensamguk.logic.domain.Diplomacy
import opensamguk.logic.domain.General
import opensamguk.logic.domain.Nation
import opensamguk.logic.statview.MemoryStateView
import opensamguk.logic.stats.GeneralActionPipeline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Task FR3 — `CandidateBridge.candidateAllowed` (the argTest gate + the constraint bridge).
 *
 * Port oracle = PHP `Command/BaseCommand.php:377-413` (`testFullConditionMet`):
 *   isArgValid() (→ `'인자가 올바르지 않습니다.'` on a missing required key) FIRST →
 *   `Constraint::testAll($fullConditionConstraints)` (FULL, first-deny-wins, list order) →
 *   `testPostReqTurn()` tail. `hasFullConditionMet()` = this returning null.
 *
 * `candidateAllowed` MUST be the IDENTICAL gate `ReservedTurnHandler.handle` makes (A13):
 *   - the AI emits RAW args; the bridge canonicalizes EXACTLY ONCE (M2 — no double-canonicalize),
 *   - FULL mode (PRECHECK's `Unknown` would phantom-deny),
 *   - FRESH ctx per candidate,
 *   - the gate-EXEMPT path list (do집합/do요양 + 사망/재야유저/do예약턴 reserved-returns) BYPASSES it.
 *
 * The CheBallyeong typo (`destGenaralID`) reproduces the PHP latent always-null bug: argTest rejects
 * the missing-key emit → `candidateAllowed` returns false → `do부대전방발령` null-returns (R-BRIDGE §4).
 */
class CandidateBridgeTest {

    private val pipeline = GeneralActionPipeline()
    private val registry = CommandRegistry(pipeline)
    private val resolve: (String) -> GeneralActionDefinition = registry::resolve

    // ---- fixtures (the DiploPresetsTest idiom) ----

    private fun general(id: Int = 1, nationId: Int = 1, cityId: Int = 5) = General(
        id = id, nationId = nationId, cityId = cityId,
        leadership = 10, strength = 10, intel = 10, injury = 0,
        experience = 0.0, dedication = 0.0, officerLevel = 12,
        gold = 100000, rice = 100000,
        crew = 100, train = 50.0, atmos = 50.0, troop = 0,
        npcType = 2, meta = linkedMapOf(),
    )

    private fun nation(id: Int = 1, level: Int = 7) =
        Nation(id = id, level = level, capitalCityId = 5, gold = 100000, rice = 100000, meta = linkedMapOf())

    private fun city(id: Int = 5, nationId: Int = 1) = City(
        id = id, nationId = nationId, level = 5,
        commerce = 100, commerceMax = 1000, agriculture = 100, agricultureMax = 1000,
        supplyState = 1, frontState = 0, trust = 50.0, trade = 100,
    )

    private fun view(
        generals: Map<Int, General> = mapOf(1 to general(), 3 to general(id = 3, cityId = 6)),
        cities: Map<Int, City> = mapOf(5 to city(), 6 to city(id = 6)),
        nations: Map<Int, Nation> = mapOf(1 to nation(1), 2 to nation(2)),
        diplomacy: List<Diplomacy> = emptyList(),
        env: Map<String, Any?> = emptyMap(),
    ) = MemoryStateView(
        generals = generals, cities = cities, nations = nations, env = env, diplomacy = diplomacy,
    )

    private fun ctx(
        actorId: Int = 1, cityId: Int? = 5, nationId: Int? = 1,
        destGeneralId: Int? = null, destCityId: Int? = null, destNationId: Int? = null,
        args: Map<String, Any?> = emptyMap(),
        env: Map<String, Any?> = emptyMap(),
    ) = ConstraintContext(
        actorId = actorId, cityId = cityId, nationId = nationId,
        destGeneralId = destGeneralId, destCityId = destCityId, destNationId = destNationId,
        args = args, env = env, mode = ConstraintMode.FULL,
    )

    // ─────────────────────────────────────────────────────────────────────────────────────────────
    // (1) the gate matches ReservedTurnHandler's evaluateConstraints(FULL) call (A13).
    // ─────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun `candidateAllowed mirrors evaluateConstraints FULL for a no-arg def`() {
        // che_견문 has an EMPTY full pack → the gate passes on argValid alone (no-arg → argValid).
        val def = registry.resolve("che_견문")
        assertTrue(
            candidateAllowed("che_견문", emptyMap(), ctx(), view(), resolve),
            "an EMPTY-pack no-arg command passes the gate",
        )
        // ... and equals the raw evaluateConstraints call the handler would make.
        assertEquals(
            ConstraintResult.Allow,
            opensamguk.logic.constraints.evaluateConstraints(
                def.buildConstraints(ctx()),
                ctx(),
                view(),
            ),
        )
    }

    @Test fun `candidateAllowed returns false when the FULL pack denies`() {
        // che_귀환: NotBeNeutral / NotWanderingNation / NotCapital. The actor IS in the capital (city 5
        // == nation 1 capitalCityId 5) → NotCapital denies → gate false.
        assertFalse(
            candidateAllowed("che_귀환", emptyMap(), ctx(), view(), resolve),
            "an actor in the capital fails che_귀환's NotCapital",
        )
    }

    @Test fun `candidate verdict preserves the first deny reason and canonical args`() {
        val raw = linkedMapOf<String, Any?>(
            "destGeneralID" to 1L,
            "destCityID" to 6L,
            "ignored" to "drop",
        )

        val verdict = candidateVerdict("che_발령", raw, ctx(), view(), resolve)

        assertEquals(
            CandidateVerdict.Deny(
                reason = "본인입니다",
                canonicalArgs = linkedMapOf("destGeneralID" to 1, "destCityID" to 6),
            ),
            verdict,
        )
        assertEquals(1L, raw["destGeneralID"], "the raw emit remains untouched")
        assertTrue(raw.containsKey("ignored"))
    }

    @Test fun `invalid arg verdict carries the PHP reason and partial canonical args`() {
        val verdict = candidateVerdict(
            "che_발령",
            linkedMapOf("destGenaralID" to 3, "destCityID" to 6L),
            ctx(),
            view(),
            resolve,
        )

        assertEquals(
            CandidateVerdict.Deny(
                reason = INVALID_ARG_REASON,
                canonicalArgs = linkedMapOf("destGeneralID" to null, "destCityID" to 6),
            ),
            verdict,
        )
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────────
    // (2) the argTest gate runs BEFORE constraints and rejects a missing required key
    //     — the CheBallyeong `destGenaralID` typo → always-null (R-BRIDGE §4, the PHP latent bug).
    // ─────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun `argTest rejects the CheBallyeong destGenaralID typo before constraints`() {
        // do부대전방발령 emits the misspelled key `destGenaralID` (extra `a`) + a valid destCityID.
        // PHP che_발령.argTest requires the CORRECT `destGeneralID` key → key_exists false → argTest
        // false → testFullConditionMet returns '인자가 올바르지 않습니다.' BEFORE any constraint runs.
        val rawWithTypo = linkedMapOf<String, Any?>("destGenaralID" to 3, "destCityID" to 6)
        assertFalse(
            candidateAllowed("che_발령", rawWithTypo, ctx(destGeneralId = null, destCityId = 6), view(), resolve),
            "the typo key makes argTest reject → do부대전방발령 always-null (port the latent bug)",
        )
    }

    @Test fun `argTest accepts the correctly-spelled destGeneralID`() {
        // The 11 OTHER 발령 methods emit the correct `destGeneralID`; with a friendly dest general +
        // friendly occupied/supplied dest city the gate passes.
        val raw = linkedMapOf<String, Any?>("destGeneralID" to 3, "destCityID" to 6)
        assertTrue(
            candidateAllowed("che_발령", raw, ctx(destGeneralId = 3, destCityId = 6), view(), resolve),
            "a correct destGeneralID passes argTest and the FULL pack",
        )
    }

    @Test fun `argTest rejects a missing required key for a reqArg command via the schema`() {
        // che_출병 declares argsSchema {destCityID}; an emit lacking destCityID → argTest reject.
        assertFalse(
            candidateAllowed("che_출병", emptyMap(), ctx(), view(), resolve),
            "a missing required schema key rejects at argTest before constraints",
        )
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────────
    // (3) single canonicalization (M2): the AI's emitted RAW args are UNCHANGED while the ctx the
    //     constraints see carries the canonicalized args. No double-canonicalize.
    // ─────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun `the emitted RAW args are not mutated and canonicalization happens once`() {
        // che_NPC능동 canonicalizes {optionText,destCityID,extra} → {optionText,destCityID}. The bridge
        // canonicalizes ONCE; the RAW map the AI emitted keeps its `extra` key (the bridge does not
        // write back into the AI-emit layer).
        val realCity = opensamguk.common.constants.CityConst.all().keys.first()
        val raw = linkedMapOf<String, Any?>("optionText" to "순간이동", "destCityID" to realCity, "extra" to "x")
        val rawSnapshot = LinkedHashMap(raw)
        candidateAllowed("che_NPC능동", raw, ctx(), view(), resolve)
        assertEquals(rawSnapshot, raw, "the AI-emitted RAW args must NOT be mutated by the bridge")

        // The canonical map fed to the command drops `extra` (single canonicalization in the bridge).
        val def = registry.resolve("che_NPC능동")
        val canonical = def.parseArgs(raw)
        assertEquals(linkedMapOf<String, Any?>("optionText" to "순간이동", "destCityID" to realCity), canonical)
        assertFalse(canonical.containsKey("extra"), "canonicalization strips undeclared keys exactly once")
    }

    @Test fun `npc warp accepts Han-only destination only on the active Han map`() {
        val raw = linkedMapOf<String, Any?>(
            "optionText" to "\uC21C\uAC04\uC774\uB3D9",
            "destCityID" to 421,
        )
        val staged = view(
            generals = mapOf(1 to general(cityId = 3)),
            cities = mapOf(3 to city(id = 3), 421 to city(id = 421)),
        )

        assertFalse(candidateAllowed("che_NPC능동", raw, ctx(cityId = 3, env = mapOf("mapName" to "che")), staged, resolve))
        assertTrue(candidateAllowed("che_NPC능동", raw, ctx(cityId = 3, env = mapOf("mapName" to "han")), staged, resolve))
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────────
    // (4) fresh ctx per candidate (no memo bleed): two calls with different args do not leak.
    // ─────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun `each candidate gets a fresh ctx (no memo bleed)`() {
        // First call: che_발령 with a self-target (destGeneralID == actorId) → AlwaysFail '본인입니다'.
        val selfTarget = linkedMapOf<String, Any?>("destGeneralID" to 1, "destCityID" to 6)
        assertFalse(
            candidateAllowed("che_발령", selfTarget, ctx(destGeneralId = 1, destCityId = 6), view(), resolve),
            "self-target 발령 → AlwaysFail",
        )
        // Second call: a DIFFERENT dest general on a fresh ctx → passes (no bleed from the first).
        val other = linkedMapOf<String, Any?>("destGeneralID" to 3, "destCityID" to 6)
        assertTrue(
            candidateAllowed("che_발령", other, ctx(destGeneralId = 3, destCityId = 6), view(), resolve),
            "a fresh ctx per candidate — no memo bleed from the prior self-target call",
        )
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────────
    // (5) FULL mode is used (PRECHECK's Unknown would phantom-deny on an absent preload).
    // ─────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun `the bridge uses FULL mode — a missing preload does NOT phantom-deny`() {
        // A constraint that REQUIRES a preload the view lacks. In PRECHECK this is Unknown (→ false);
        // in FULL the constraint's test() runs and (here) Allows. candidateAllowed must use FULL.
        val def = stubDef("che_stub_full", listOf(allowButRequires(RequirementKey.General(999))))
        val reg = registryWith(def)
        assertTrue(
            candidateAllowed("che_stub_full", emptyMap(), ctx(), view(), reg),
            "FULL mode runs test() even when a requirement is absent (PRECHECK would phantom-deny)",
        )
    }

    @Test fun `a cityless chief confiscation candidate is denied without aborting the turn`() {
        val citylessChief = general(id = 1136, cityId = 0)
        val target = general(id = 3, cityId = 6)
        val staged = view(
            generals = mapOf(1136 to citylessChief, 3 to target),
            cities = mapOf(6 to city(id = 6)),
        )
        val raw = linkedMapOf<String, Any?>(
            "isGold" to true,
            "amount" to 1_000,
            "destGeneralID" to 3,
        )

        val verdict = candidateVerdict(
            "che_몰수",
            raw,
            ctx(actorId = 1136, cityId = 0, destGeneralId = 3),
            staged,
            resolve,
        )

        assertEquals(
            CandidateVerdict.Deny(
                reason = CANDIDATE_UNAVAILABLE_REASON,
                canonicalArgs = raw,
            ),
            verdict,
        )
        assertFalse(
            candidateAllowed(
                "che_몰수",
                raw,
                ctx(actorId = 1136, cityId = 0, destGeneralId = 3),
                staged,
                resolve,
            ),
        )
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────────
    // (6) the gate-EXEMPT reason list (G14): do집합/do요양 + 사망/재야유저/do예약턴 BYPASS the gate.
    // ─────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun `the gate-exempt reasons bypass candidateAllowed`() {
        // The list is keyed by the AI `do<한글>` reason (not the che_* code). do집합/do요양 are built +
        // returned directly; 사망/재야유저/do예약턴 are reserved-returns. All BYPASS the gate.
        assertTrue(GATE_EXEMPT_REASONS.containsAll(listOf("do집합", "do요양", "사망", "재야유저", "do예약턴")))
        assertTrue(isGateExempt("do집합"))
        assertTrue(isGateExempt("do요양"))
        assertTrue(isGateExempt("사망"))
        assertTrue(isGateExempt("재야유저"))
        assertTrue(isGateExempt("do예약턴"))
        assertFalse(isGateExempt("do출병"), "a normal do<한글> is NOT gate-exempt")
    }

    // ---- stub helpers for the FULL-mode test ----

    private fun stubDef(k: String, cons: List<Constraint>): GeneralActionDefinition =
        object : GeneralActionDefinition {
            override val key: String get() = k
            override val name: String get() = k
            override fun buildConstraints(ctx: ConstraintContext): List<Constraint> = cons
            override fun resolve(context: GeneralActionResolveContext) {}
        }

    private fun allowButRequires(req: RequirementKey): Constraint = object : Constraint {
        override val name = "allowButRequires"
        override fun requires(ctx: ConstraintContext): List<RequirementKey> = listOf(req)
        override fun test(ctx: ConstraintContext, view: StateView): ConstraintResult = ConstraintResult.Allow
    }

    private fun registryWith(def: GeneralActionDefinition): (String) -> GeneralActionDefinition =
        { code -> if (code == def.key) def else registry.resolve(code) }
}
