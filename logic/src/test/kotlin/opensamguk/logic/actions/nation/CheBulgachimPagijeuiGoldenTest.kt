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
 * GOLDEN — che_불가침파기제의 ([CheBulgachimPagijeui]) draw COUNT = 0.
 *
 * PHP `legacy/devsam-core/hwe/sammo/Command/Nation/che_불가침파기제의.php` `run(RandUtil $rng)`는 RNG를
 * 끌지 않는다(draw COUNT = 0). docker 골든 불필요 — 본 테스트가 골든을 대신한다:
 *
 *  - **0-draw**: [NoRng]로 resolve 실행 → 단 한 번이라도 draw하면 [MustNotBeReachedException] 발생.
 *  - **emitted effect**: DiplomaticMessage(action=cancel_na, deletable=false) + title
 *    `{국명}의 불가침 파기 제의 서신`(che_불가침파기제의.php:159) + validUntil=date+max(30,turnterm*3)분
 *    (che_불가침파기제의.php:151-153) + 국가 메일함(9000+destNationID, receiver-before-sender).
 *  - **actor log byte**:
 *    `<C>●</>{month}월:<D><b>{상대국}</b></>{로} 불가침 파기 제의 서신을 보냈습니다.<1>{date}</>`
 *    (che_불가침파기제의.php:131).
 */
class CheBulgachimPagijeuiGoldenTest {

    @AfterTest fun reset() = opensamguk.logic.event.StaticEventHandler.clear()

    private fun pipeline() = GeneralActionPipeline()

    private fun context(
        rng: RandUtil,
        args: Map<String, Any?> = mapOf("destNationID" to 5),
        destGeneralName: String = "위국",
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
        val nation = Nation(id = 1, level = 5, capitalCityId = 1, name = "촉", color = "#00ff00")
        val draft = GeneralActionDraft(general = general, city = city, nation = nation).apply {
            destNation = Nation(id = 5, level = 5, capitalCityId = 5, name = destGeneralName, color = "#1a2b3c")
        }
        val env = WorldEnv(year = 200, startYear = 190, develCost = 100)
        return GeneralActionResolveContext(
            draft = draft, rng = rng, env = env, month = 3, date = "12:34",
            args = args, generalName = "관우", destGeneralName = destGeneralName, turnterm = turnterm,
        )
    }

    // ── 0-draw ──────────────────────────────────────────────────────────────────────────────────
    @Test
    fun `resolve makes zero rng draws`() {
        val cmd = cheBulgachimPagijeui(pipeline())
        cmd.resolve(context(RandUtil(NoRng())))
    }

    @Test
    fun `a stray draw on the NoRng path throws`() {
        val ctx = context(RandUtil(NoRng()))
        assertFailsWith<MustNotBeReachedException> { ctx.rng.nextRangeInt(1, 5) }
    }

    // ── emitted effect ───────────────────────────────────────────────────────────────────────────
    @Test
    fun `resolve emits one cancelNA diplomacy message with the verbatim title and payload`() {
        val cmd = cheBulgachimPagijeui(pipeline())
        val ctx = context(RandUtil(NoRng()))
        cmd.resolve(ctx)

        val msgs = ctx.messages()
        assertEquals(1, msgs.size)
        val msg = msgs[0]
        assertEquals(MessageType.DIPLOMACY, msg.msgType)
        // title verbatim — `{국명}의 불가침 파기 제의 서신` (che_불가침파기제의.php:159)
        assertEquals("촉의 불가침 파기 제의 서신", msg.msg)
        assertEquals(listOf("action", "deletable"), msg.msgOption!!.keys.toList())
        assertEquals("cancel_na", msg.msgOption!!["action"])
        assertEquals(false, msg.msgOption!!["deletable"])
        assertEquals(7, msg.src.generalId)
        assertEquals(1, msg.src.nationId)
        assertEquals(0, msg.dest.generalId)
        assertEquals(5, msg.dest.nationId)
        assertEquals("위국", msg.dest.nationName)
        // PHP che_불가침파기제의.php:139-145 copies $destNation['color'] into the message target.
        assertEquals("#1a2b3c", msg.dest.color)
    }

    @Test
    fun `validUntil equals send date plus max 30 turnterm times 3 minutes`() {
        val cmd = cheBulgachimPagijeui(pipeline())
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
        val cmd = cheBulgachimPagijeui(pipeline())
        val ctx = context(RandUtil(NoRng()))
        cmd.resolve(ctx)
        val drafts = ctx.messages()[0].send()
        assertEquals(2, drafts.size)
        assertEquals(MessageRowDraft.Row.RECEIVER, drafts[0].whichRow)
        assertEquals(Mailbox.NATIONAL_BASE + 5, drafts[0].mailbox)
        assertEquals("cancel_na", drafts[0].option!!["action"])
        assertEquals(MessageRowDraft.Row.SENDER, drafts[1].whichRow)
        assertEquals(Mailbox.NATIONAL_BASE + 1, drafts[1].mailbox)
        assertNull(drafts[1].option)
    }

    // ── actor log byte ───────────────────────────────────────────────────────────────────────────
    @Test
    fun `actor log is byte-exact`() {
        val cmd = cheBulgachimPagijeui(pipeline())
        val ctx = context(RandUtil(NoRng()))
        cmd.resolve(ctx)
        val logs = ctx.logs()
        assertEquals(1, logs.size)
        // josaRo = JosaUtil.pick(destNationName='위국', '로') → '위국'의 끝 '국'은 종성(받침 ㄱ) 보유 →
        // '으로'(che_불가침파기제의.php:126). month=3, date=12:34, ActionLogger MONTH 프리픽스.
        assertEquals("<C>●</>3월:<D><b>위국</b></>으로 불가침 파기 제의 서신을 보냈습니다.<1>12:34</>", logs[0])
        assertTrue(ctx.globalActionLogs().isEmpty())
    }
}
