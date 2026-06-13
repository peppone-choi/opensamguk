package opensamguk.logic.divergence

import opensamguk.common.rng.NoRng
import opensamguk.common.rng.RandUtil
import opensamguk.logic.actions.GeneralActionDraft
import opensamguk.logic.actions.GeneralActionResolveContext
import opensamguk.logic.actions.personnel.CheDeungyongSurak
import opensamguk.logic.domain.City
import opensamguk.logic.domain.General
import opensamguk.logic.domain.Nation
import opensamguk.logic.domain.WorldEnv
import opensamguk.logic.stats.GeneralActionPipeline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * DIVERGENCE TEST — 등용 매력 평판완화 (fiveStatLogic flag; RTK14 divergence).
 *
 * **NO PHP GOLDEN ORACLE.** devsam/core (the grand truth) is 3-stat with NO 매력(charm) influence on
 * the betrayal reputation penalty — the defecting general's experience/dedication is always multiplied
 * by the un-mitigated `1 - 0.1*betray` factor. This behavior CANNOT be replayed against a captured PHP
 * fixture, so the assertions here are purely BEHAVIORAL (relative: flag-ON keeps strictly MORE than
 * flag-OFF for the same high-charm recruiter).
 *
 * The flag-gated change is the ONE expression in [CheDeungyongSurak]'s non-재야 branch:
 *   - flag OFF (default) → charmMitig = 0 → factor == 원본 `1 - 0.1*betray` (byte-identical baseline),
 *   - flag ON → the recruiter (destGeneral) 매력이 평판패널티를 완화 → factor is LARGER → the defector
 *     retains MORE experience/dedication.
 *
 * Draw COUNT = 0 (망명 액션은 RNG 미사용) → [NoRng]-backed [RandUtil]; the flag adds/removes NO draws,
 * so the ONLY variable across flag states is the mitigation factor.
 */
class DeungyongCharmMitigationDivergenceTest {

    private fun pipeline() = GeneralActionPipeline()
    private val date = "12:34"
    private val MONTH = 3

    /** 망명 대상국 '오' (id=5, capital=30, gennum=4). */
    private fun destNation() = Nation(id = 5, level = 3, capitalCityId = 30, name = "오", gennum = 4)

    private fun city() = City(
        id = 1, nationId = 1, level = 5,
        commerce = 0, commerceMax = 0, agriculture = 0, agricultureMax = 0,
        supplyState = 1, frontState = 0, trust = 50.0,
    )

    /**
     * 비-재야 actor '조운' (nation=1, betray=2, exp/ded 500). betray>0 이라 평판완화 분기가 실제로 작동한다.
     */
    private fun memberActor() = General(
        id = 7, nationId = 1, cityId = 1,
        leadership = 80, strength = 80, intel = 80, injury = 0,
        experience = 500.0, dedication = 500.0, officerLevel = 3, gold = 3000, rice = 2500,
        troop = 0, npcType = 0,
        meta = linkedMapOf("name" to "조운", "betray" to 2, "explevel" to 5, "dedlevel" to 3, "belong" to 7),
    )

    /** 고매력 등용 주체(destGeneral) — charm=100 → charmMitig = 0.5 (최대 완화). */
    private fun highCharmRecruiter() = General(
        id = 99, nationId = 5, cityId = 30,
        leadership = 60, strength = 60, intel = 60, injury = 0,
        experience = 0.0, dedication = 0.0, officerLevel = 5, gold = 0, rice = 0,
        charm = 100,
        meta = linkedMapOf("explevel" to 1, "dedlevel" to 1),
    )

    private fun context(flag: Boolean, recruiter: General): GeneralActionResolveContext {
        val env = WorldEnv(year = 200, startYear = 190, develCost = 100, fiveStatLogic = flag)
        val draft = GeneralActionDraft(
            general = memberActor(),
            city = city(),
            nation = Nation(id = 1, level = 3, capitalCityId = 1, name = "촉", gennum = 6),
        ).apply {
            this.destNation = destNation()
            this.destGeneral = recruiter
        }
        return GeneralActionResolveContext(
            draft = draft, rng = RandUtil(NoRng()), env = env, month = MONTH, date = date,
            args = mapOf("destGeneralID" to 99, "destNationID" to 5),
        )
    }

    /** Resolve and return the defector's post-resolve (experience, dedication). */
    private fun resolveDefectorExpDed(flag: Boolean, recruiter: General): Pair<Double, Double> {
        val ctx = context(flag, recruiter)
        CheDeungyongSurak(pipeline()).resolve(ctx)
        val g = ctx.draft.general
        return g.experience to g.dedication
    }

    @Test
    fun `DIVERGENCE — flag OFF is byte-identical to the un-mitigated parity baseline`() {
        // betray=2 → factor 0.8 → exp 500*0.8=400, ded 500*0.8=400 (원본 1 - 0.1*betray, 매력 무관).
        val (exp, ded) = resolveDefectorExpDed(flag = false, recruiter = highCharmRecruiter())
        assertEquals(400.0, exp, "flag OFF: experience *= (1 - 0.1*betray) = 500*0.8 (no charm influence)")
        assertEquals(400.0, ded, "flag OFF: dedication *= (1 - 0.1*betray) = 500*0.8 (no charm influence)")
        // Determinism guard (no RNG, but pin the parity path).
        assertEquals(exp to ded, resolveDefectorExpDed(flag = false, recruiter = highCharmRecruiter()),
            "flag OFF is deterministic (parity baseline)")
    }

    @Test
    fun `DIVERGENCE — flag ON high-charm recruiter mitigates the rep penalty (defector keeps MORE)`() {
        val (offExp, offDed) = resolveDefectorExpDed(flag = false, recruiter = highCharmRecruiter())
        val (onExp, onDed) = resolveDefectorExpDed(flag = true, recruiter = highCharmRecruiter())

        // charm=100 → charmMitig=0.5 → factor = 1 - 0.1*2*(1-0.5) = 1 - 0.1 = 0.9 > 0.8 (off).
        // So the defector RETAINS strictly MORE experience/dedication under the flag.
        assertTrue(onExp > offExp,
            "flag ON: high-charm recruiter softens the betrayal rep penalty → MORE experience kept (on=$onExp off=$offExp)")
        assertTrue(onDed > offDed,
            "flag ON: high-charm recruiter softens the betrayal rep penalty → MORE dedication kept (on=$onDed off=$offDed)")
        // Exact mitigated value: 500 * 0.9 = 450 (> 400 baseline).
        assertEquals(450.0, onExp, "flag ON: experience *= (1 - 0.1*2*(1-0.5)) = 500*0.9")
        assertEquals(450.0, onDed, "flag ON: dedication *= (1 - 0.1*2*(1-0.5)) = 500*0.9")
        // Determinism guard on the flag-ON path.
        assertEquals(onExp to onDed, resolveDefectorExpDed(flag = true, recruiter = highCharmRecruiter()),
            "flag ON is deterministic")
    }

    @Test
    fun `DIVERGENCE — flag ON with null recruiter falls back to no mitigation`() {
        // destGeneral=null → charmMitig=0 even with the flag on → factor == baseline 0.8.
        val ctx = context(flag = true, recruiter = highCharmRecruiter()).let {
            // Re-build with a null destGeneral to exercise the (?: 0) guard.
            val env = WorldEnv(year = 200, startYear = 190, develCost = 100, fiveStatLogic = true)
            val draft = GeneralActionDraft(
                general = memberActor(), city = city(),
                nation = Nation(id = 1, level = 3, capitalCityId = 1, name = "촉", gennum = 6),
            ).apply {
                this.destNation = destNation()
                this.destGeneral = null
            }
            GeneralActionResolveContext(
                draft = draft, rng = RandUtil(NoRng()), env = env, month = MONTH, date = date,
                args = mapOf("destGeneralID" to 99, "destNationID" to 5),
            )
        }
        CheDeungyongSurak(pipeline()).resolve(ctx)
        assertEquals(400.0, ctx.draft.general.experience,
            "flag ON but null recruiter → charmMitig=0 → factor == baseline 0.8 (no mitigation)")
        assertEquals(400.0, ctx.draft.general.dedication,
            "flag ON but null recruiter → charmMitig=0 → factor == baseline 0.8 (no mitigation)")
    }
}
