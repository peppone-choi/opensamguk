package opensamguk.logic.actions.nation

import opensamguk.common.rng.MustNotBeReachedException
import opensamguk.common.rng.NoRng
import opensamguk.common.rng.RandUtil
import opensamguk.logic.actions.GeneralActionDraft
import opensamguk.logic.actions.GeneralActionResolveContext
import opensamguk.logic.domain.City
import opensamguk.logic.domain.General
import opensamguk.logic.domain.Nation
import opensamguk.logic.domain.WorldEnv
import opensamguk.logic.message.Mailbox
import opensamguk.logic.message.MessageRowDraft
import opensamguk.logic.message.MessageType
import opensamguk.logic.stats.GeneralActionPipeline
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * GOLDEN — che_불가침제의 ([CheBulgachimJeui]) draw COUNT = 0.
 *
 * PHP `legacy/devsam-core/hwe/sammo/Command/Nation/che_불가침제의.php` `run(RandUtil $rng)`는 RNG를
 * 끌지 않는다(draw COUNT = 0). docker 골든 불필요 — 본 테스트가 골든을 대신한다:
 *
 *  - **0-draw**: [NoRng]로 resolve 실행 → 단 한 번이라도 draw하면 [MustNotBeReachedException] 발생.
 *  - **emitted effect**: DiplomaticMessage(action=no_aggression, year, month) + title
 *    `{국명}{와} {year}년 {month}월까지 불가침 제의 서신`(che_불가침제의.php:212,
 *    josaWa=JosaUtil::pick($nationName,'와')) + validUntil=date+max(30,turnterm*3)분
 *    (che_불가침제의.php:202-204) + 국가 메일함(9000+destNationID, receiver-before-sender).
 *  - **actor log byte**: `<D><b>{상대국}</b></>{로} 불가침 제의 서신을 보냈습니다.<1>{date}</>`
 *    (che_불가침제의.php:182). byte-parity quirk: josaRo는 **행동 장수 자신의 국명**(`$nationName`)으로
 *    고른다(:170) — 표시는 상대국명. 즉 텍스트는 상대국, 조사 형은 자국 기준.
 */
class CheBulgachimJeuiGoldenTest {

    @AfterTest fun reset() = opensamguk.logic.event.StaticEventHandler.clear()

    private fun pipeline() = GeneralActionPipeline()

    private fun context(
        rng: RandUtil,
        args: Map<String, Any?> = mapOf("destNationID" to 5, "year" to 201, "month" to 7),
        destGeneralName: String = "위국",
        nationName: String = "촉",
        turnterm: Int = 60,
    ): GeneralActionResolveContext {
        val general = General(
            id = 7, nationId = 1, cityId = 1,
            leadership = 80, strength = 80, intel = 80, injury = 0,
            experience = 0.0, dedication = 0.0, officerLevel = 12, gold = 1000, rice = 1000,
        )
        val city = City(
            id = 1, nationId = 1, level = 5,
            commerce = 0, commerceMax = 0, agriculture = 0, agricultureMax = 0,
            supplyState = 1, frontState = 0, trust = 50.0,
        )
        val nation = Nation(id = 1, level = 5, capitalCityId = 1, name = nationName, color = "#00ff00")
        val draft = GeneralActionDraft(general = general, city = city, nation = nation)
        val env = WorldEnv(year = 200, startYear = 190, develCost = 100)
        return GeneralActionResolveContext(
            draft = draft, rng = rng, env = env, month = 3, date = "12:34",
            args = args, generalName = "관우", destGeneralName = destGeneralName, turnterm = turnterm,
        )
    }

    // ── 0-draw ──────────────────────────────────────────────────────────────────────────────────
    @Test
    fun `resolve makes zero rng draws`() {
        val cmd = cheBulgachimJeui(pipeline())
        cmd.resolve(context(RandUtil(NoRng())))
    }

    @Test
    fun `a stray draw on the NoRng path throws`() {
        val ctx = context(RandUtil(NoRng()))
        assertFailsWith<MustNotBeReachedException> { ctx.rng.nextRangeInt(1, 5) }
    }

    // ── emitted effect ───────────────────────────────────────────────────────────────────────────
    @Test
    fun `resolve emits one noAggression diplomacy message with the verbatim title and year-month payload`() {
        val cmd = cheBulgachimJeui(pipeline())
        val ctx = context(RandUtil(NoRng()))
        cmd.resolve(ctx)

        val msgs = ctx.messages()
        assertEquals(1, msgs.size)
        val msg = msgs[0]
        assertEquals(MessageType.DIPLOMACY, msg.msgType)
        // title verbatim — `{국명}{와} {year}년 {month}월까지 불가침 제의 서신`. '촉' 끝 받침(ㄱ) → '과'.
        assertEquals("촉과 201년 7월까지 불가침 제의 서신", msg.msg)
        // option — action + year + month, insertion order preserved (che_불가침제의.php:215-219)
        assertEquals(listOf("action", "year", "month"), msg.msgOption!!.keys.toList())
        assertEquals("no_aggression", msg.msgOption!!["action"])
        assertEquals(201, msg.msgOption!!["year"])
        assertEquals(7, msg.msgOption!!["month"])
        assertEquals(7, msg.src.generalId)
        assertEquals(1, msg.src.nationId)
        assertEquals(0, msg.dest.generalId)
        assertEquals(5, msg.dest.nationId)
        assertEquals("위국", msg.dest.nationName)
    }

    @Test
    fun `title josaWa picks 와 for a vowel-ending nation name`() {
        val cmd = cheBulgachimJeui(pipeline())
        // 국명 '오'(받침 없음) → josaWa='와'
        val ctx = context(RandUtil(NoRng()), nationName = "오")
        cmd.resolve(ctx)
        assertEquals("오와 201년 7월까지 불가침 제의 서신", ctx.messages()[0].msg)
    }

    @Test
    fun `validUntil equals send date plus max 30 turnterm times 3 minutes`() {
        val cmd = cheBulgachimJeui(pipeline())
        val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

        val ctx = context(RandUtil(NoRng()), turnterm = 60)
        cmd.resolve(ctx)
        val msg = ctx.messages()[0]
        assertEquals(
            180L,
            Duration.between(LocalDateTime.parse(msg.date, fmt), LocalDateTime.parse(msg.validUntil, fmt)).toMinutes(),
        )

        val ctx2 = context(RandUtil(NoRng()), turnterm = 5)
        cmd.resolve(ctx2)
        val msg2 = ctx2.messages()[0]
        assertEquals(
            30L,
            Duration.between(LocalDateTime.parse(msg2.date, fmt), LocalDateTime.parse(msg2.validUntil, fmt)).toMinutes(),
        )
    }

    @Test
    fun `send routes to dest nation mailbox receiver-before-sender with sender option nulled`() {
        val cmd = cheBulgachimJeui(pipeline())
        val ctx = context(RandUtil(NoRng()))
        cmd.resolve(ctx)
        val drafts = ctx.messages()[0].send()
        assertEquals(2, drafts.size)
        assertEquals(MessageRowDraft.Row.RECEIVER, drafts[0].whichRow)
        assertEquals(Mailbox.NATIONAL_BASE + 5, drafts[0].mailbox)
        assertEquals("no_aggression", drafts[0].option!!["action"])
        assertEquals(MessageRowDraft.Row.SENDER, drafts[1].whichRow)
        assertEquals(Mailbox.NATIONAL_BASE + 1, drafts[1].mailbox)
        // diplomacy sender row carries an 'action' → option NULLed (che_불가침제의 send 분해)
        assertNull(drafts[1].option)
    }

    // ── actor log byte (josaRo = actor 국명 기준 quirk) ───────────────────────────────────────────
    @Test
    fun `actor log is byte-exact and josaRo follows the actor nation name`() {
        val cmd = cheBulgachimJeui(pipeline())
        val ctx = context(RandUtil(NoRng()))
        cmd.resolve(ctx)
        val logs = ctx.logs()
        assertEquals(1, logs.size)
        // 텍스트는 상대국명('위국'), josaRo는 ACTOR 국명('촉')으로 고른다(che_불가침제의.php:170,182).
        // '촉' 끝 받침(ㄱ, ㄹ 아님) → 으로. month=3, date=12:34.
        assertEquals("<C>●</>3월:<D><b>위국</b></>으로 불가침 제의 서신을 보냈습니다.<1>12:34</>", logs[0])
        assertTrue(ctx.globalActionLogs().isEmpty())
    }

    @Test
    fun `josaRo is 로 when the actor nation name has no jongsung`() {
        val cmd = cheBulgachimJeui(pipeline())
        // 국명 '오'(받침 없음) → josa 로='로'. 표시는 상대국명 그대로.
        val ctx = context(RandUtil(NoRng()), nationName = "오", destGeneralName = "위국")
        cmd.resolve(ctx)
        assertEquals("<C>●</>3월:<D><b>위국</b></>로 불가침 제의 서신을 보냈습니다.<1>12:34</>", ctx.logs()[0])
    }
}
