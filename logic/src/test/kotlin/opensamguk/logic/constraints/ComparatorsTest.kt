package opensamguk.logic.constraints

import opensamguk.logic.domain.City
import opensamguk.logic.domain.General
import opensamguk.logic.domain.Nation
import opensamguk.logic.statview.MemoryStateView
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CP2 — the Req*Value comparator family + parsePercent.
 *
 * Port oracle = PHP ReqGeneralValue/ReqCityValue/ReqNationValue/ReqDest*Value/ReqNationAuxValue/
 * ReqEnvValue + Util::convPercentStrToFloat. The derived-reason matrix (compList closures) is shared
 * byte-for-byte across the Req*Value family; reqEnvValue/reqNationAuxValue ALWAYS use the caller errMsg.
 */
class ComparatorsTest {

    // ===== compareValues core: returns true on pass, else the PHP derived-reason fragment =====

    @Test fun `lt passes and denies with too-many`() {
        assertEquals(true, compareValues(1, 2, "<", "지력"))
        assertEquals("너무 많습니다.", compareValues(2, 2, "<", "지력"))
    }

    @Test fun `lte passes and denies with too-many`() {
        assertEquals(true, compareValues(2, 2, "<=", "지력"))
        assertEquals("너무 많습니다.", compareValues(3, 2, "<=", "지력"))
    }

    @Test fun `eq family denies with invalid-keyNick (keyNick interpolated into fragment)`() {
        assertEquals(true, compareValues(2, 2, "==", "지력"))
        assertEquals("올바르지 않은 지력 입니다.", compareValues(1, 2, "==", "지력"))
    }

    @Test fun `neq family denies with invalid-keyNick`() {
        assertEquals(true, compareValues(1, 2, "!=", "지력"))
        assertEquals("올바르지 않은 지력 입니다.", compareValues(2, 2, "!=", "지력"))
    }

    @Test fun `strict eq family denies with invalid-keyNick`() {
        assertEquals(true, compareValues(2, 2, "===", "지력"))
        assertEquals("올바르지 않은 지력 입니다.", compareValues(1, 2, "===", "지력"))
    }

    @Test fun `strict neq family denies with invalid-keyNick`() {
        assertEquals(true, compareValues(1, 2, "!==", "지력"))
        assertEquals("올바르지 않은 지력 입니다.", compareValues(2, 2, "!==", "지력"))
    }

    @Test fun `gte passes and denies-with-none when src is 1 (NO period)`() {
        assertEquals(true, compareValues(2, 1, ">=", "지력"))
        assertEquals("없습니다", compareValues(0, 1, ">=", "지력"))
    }

    @Test fun `gte denies-with-shortage when src is not 1`() {
        assertEquals("부족합니다.", compareValues(1, 2, ">=", "지력"))
    }

    @Test fun `gt passes and denies-with-none when src is 0 (NO period)`() {
        assertEquals(true, compareValues(1, 0, ">", "지력"))
        assertEquals("없습니다", compareValues(0, 0, ">", "지력"))
    }

    @Test fun `gt denies-with-shortage when src is not 0`() {
        assertEquals("부족합니다.", compareValues(1, 1, ">", "지력"))
    }

    // ===== parsePercent (Util::convPercentStrToFloat) =====

    @Test fun `parsePercent parses integer percent`() = assertEquals(0.5, parsePercent("50%"))
    @Test fun `parsePercent parses decimal percent`() = assertEquals(0.125, parsePercent("12.5%"))
    @Test fun `parsePercent returns null for non-percent`() = assertEquals(null, parsePercent("50"))
    @Test fun `parsePercent returns null for trailing junk`() = assertEquals(null, parsePercent("50% off"))

    // ===== reqGeneralValue (entity-resolved target) =====

    private fun gen(intel: Int = 50) = General(
        id = 1, nationId = 1, cityId = 5, leadership = 10, strength = 10, intel = intel, injury = 0,
        experience = 0.0, dedication = 0.0, officerLevel = 0, gold = 1000, rice = 1000,
    )
    private fun cityE(comm: Int = 100, commMax: Int = 1000) = City(
        id = 5, nationId = 1, level = 5, commerce = comm, commerceMax = commMax,
        agriculture = 100, agricultureMax = 1000, supplyState = 1, frontState = 0, trust = 50.0,
    )
    private fun nationE(gold: Int = 1000) = Nation(id = 1, level = 7, capitalCityId = 5, gold = gold)

    private fun view(g: General = gen(), c: City = cityE(), n: Nation = nationE()) =
        MemoryStateView(mapOf(g.id to g), mapOf(c.id to c), mapOf(n.id to n), emptyMap())
    private fun ctx() = ConstraintContext(actorId = 1, cityId = 5, nationId = 1, mode = ConstraintMode.FULL)
    private fun deny(r: ConstraintResult): String {
        assertTrue(r is ConstraintResult.Deny, "expected Deny but was $r"); return (r as ConstraintResult.Deny).reason
    }

    @Test fun `reqGeneralValue allows when comparator passes`() =
        assertEquals(ConstraintResult.Allow, reqGeneralValue("intel", "지력", ">=", 50).test(ctx(), view(g = gen(intel = 50))))

    @Test fun `reqGeneralValue deny uses derived reason with josa-i`() =
        // 지력 ends in ㄱ jongsung → 이; src=60 (not 1) → 부족합니다.
        assertEquals("지력이 부족합니다.", deny(reqGeneralValue("intel", "지력", ">=", 60).test(ctx(), view(g = gen(intel = 50)))))

    @Test fun `reqGeneralValue deny uses errMsg when provided (overrides derived)`() =
        assertEquals("지력이 부족하오.", deny(reqGeneralValue("intel", "지력", ">=", 60, errMsg = "지력이 부족하오.").test(ctx(), view(g = gen(intel = 50)))))

    @Test fun `reqGeneralValue gte src 1 emits no-period none`() =
        assertEquals("지력이 없습니다", deny(reqGeneralValue("intel", "지력", ">=", 1).test(ctx(), view(g = gen(intel = 0)))))

    @Test fun `reqGeneralValue josa-ga on vowel-final keyNick`() =
        // 무력보정 -> ends in ㅇ? use a vowel-final keyNick to exercise the 가 branch
        assertEquals("통솔치가 부족합니다.", deny(reqGeneralValue("intel", "통솔치", ">=", 60).test(ctx(), view(g = gen(intel = 50)))))

    // ===== reqCityValue numeric + percent =====

    @Test fun `reqCityValue numeric allow`() =
        assertEquals(ConstraintResult.Allow, reqCityValue("comm", "상업", ">=", 100).test(ctx(), view(c = cityE(comm = 100))))

    @Test fun `reqCityValue percent compares against key_max times fraction`() {
        // 50% of comm_max(1000) = 500; comm=400 < 500 → deny 부족
        assertEquals("상업이 부족합니다.", deny(reqCityValue("comm", "상업", ">=", "50%").test(ctx(), view(c = cityE(comm = 400, commMax = 1000)))))
    }

    @Test fun `reqCityValue percent allow when value meets fraction`() =
        assertEquals(ConstraintResult.Allow, reqCityValue("comm", "상업", ">=", "50%").test(ctx(), view(c = cityE(comm = 600, commMax = 1000))))

    // ===== reqNationValue =====

    @Test fun `reqNationValue allow and deny`() {
        assertEquals(ConstraintResult.Allow, reqNationValue("gold", "국고", ">=", 1000).test(ctx(), view(n = nationE(gold = 1000))))
        assertEquals("국고가 부족합니다.", deny(reqNationValue("gold", "국고", ">=", 2000).test(ctx(), view(n = nationE(gold = 1000)))))
    }

    // ===== reqDestCityValue / reqDestNationValue (target supplied via lambda; C-DEST wires the key later) =====

    @Test fun `reqDestCityValue allow and deny via target supplier`() {
        assertEquals(ConstraintResult.Allow, reqDestCityValue("def", "수비", ">=", 100) { _, _ -> 100 }.test(ctx(), view()))
        assertEquals("수비가 부족합니다.", deny(reqDestCityValue("def", "수비", ">=", 200) { _, _ -> 100 }.test(ctx(), view())))
    }

    @Test fun `reqDestCityValue percent path via target and max suppliers`() {
        // 50% of max(1000)=500; target 400 < 500 → deny
        assertEquals(
            "수비가 부족합니다.",
            deny(reqDestCityValue("def", "수비", ">=", "50%", max = { _, _ -> 1000 }) { _, _ -> 400 }.test(ctx(), view())),
        )
    }

    @Test fun `reqDestNationValue allow and deny via target supplier`() {
        assertEquals(ConstraintResult.Allow, reqDestNationValue("tech", "기술", ">=", 5) { _, _ -> 5 }.test(ctx(), view()))
        assertEquals("기술이 부족합니다.", deny(reqDestNationValue("tech", "기술", ">=", 9) { _, _ -> 5 }.test(ctx(), view())))
    }

    // ===== reqNationAuxValue — ALWAYS errMsg, no derived text =====

    @Test fun `reqNationAuxValue allows when comparator passes`() =
        assertEquals(
            ConstraintResult.Allow,
            reqNationAuxValue("did_특성초토화", defaultValue = 0, ">=", 1, errMsg = "특성 초토화가 필요합니다.")
                .test(ctx(), view(n = nationE().copy(meta = linkedMapOf("aux" to linkedMapOf("did_특성초토화" to 1))))),
        )

    @Test fun `reqNationAuxValue denies with errMsg only (no derived)`() =
        assertEquals(
            "특성 초토화가 필요합니다.",
            deny(
                reqNationAuxValue("did_특성초토화", defaultValue = 0, ">=", 1, errMsg = "특성 초토화가 필요합니다.")
                    .test(ctx(), view(n = nationE().copy(meta = linkedMapOf("aux" to linkedMapOf<String, Any?>())))),
            ),
        )

    @Test fun `reqNationAuxValue uses defaultValue when aux key absent`() =
        assertEquals(
            ConstraintResult.Allow,
            reqNationAuxValue("missing_key", defaultValue = 5, ">=", 1, errMsg = "no")
                .test(ctx(), view(n = nationE().copy(meta = linkedMapOf("aux" to linkedMapOf<String, Any?>())))),
        )

    // ===== reqEnvValue — ALWAYS errMsg, reads env =====

    private fun ctxEnv(env: Map<String, Any?>) = ConstraintContext(actorId = 1, cityId = 5, nationId = 1, env = env, mode = ConstraintMode.FULL)

    @Test fun `reqEnvValue allows when comparator passes`() =
        assertEquals(
            ConstraintResult.Allow,
            reqEnvValue("year", ">=", 200, "아직 이릅니다.").test(ctxEnv(mapOf("year" to 200)), MemoryStateView(emptyMap(), emptyMap(), emptyMap(), mapOf("year" to 200))),
        )

    @Test fun `reqEnvValue denies with errMsg only`() =
        assertEquals(
            "아직 이릅니다.",
            deny(reqEnvValue("year", ">=", 200, "아직 이릅니다.").test(ctxEnv(mapOf("year" to 190)), MemoryStateView(emptyMap(), emptyMap(), emptyMap(), mapOf("year" to 190)))),
        )

    // ===== reqCityCapacity percent path (CP2 widening of the CP1 numeric form) =====

    @Test fun `reqCityCapacity percent allows when value meets fraction`() =
        assertEquals(ConstraintResult.Allow, reqCityCapacityPercent("comm", "상업", "50%").test(ctx(), view(c = cityE(comm = 600, commMax = 1000))))

    @Test fun `reqCityCapacity percent denies with josa-i`() =
        assertEquals("상업이 부족합니다.", deny(reqCityCapacityPercent("comm", "상업", "50%").test(ctx(), view(c = cityE(comm = 400, commMax = 1000)))))
}
