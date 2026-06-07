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
 * GOLDEN — che_종전제의 ([CheJongjeonjeui]) draw COUNT = 0.
 *
 * PHP `legacy/devsam-core/hwe/sammo/Command/Nation/che_종전제의.php` `run(RandUtil $rng)`는 RNG를
 * 단 한 번도 끌지 않는다(draw COUNT = 0). 따라서 docker 골든이 불필요하고, 본 테스트가 골든을 대신한다:
 *
 *  - **0-draw**: [NoRng]-backed [RandUtil]로 resolve를 실행한다. resolve()가 단 한 번이라도 draw하면
 *    [MustNotBeReachedException]이 던져져 테스트가 실패한다 — draw COUNT = 0의 byte-exact 강제.
 *  - **emitted effect**: DiplomaticMessage(msgType=diplomacy, option.action=stop_war, deletable=false)
 *    payload + title `{국명}의 종전 제의 서신`(che_종전제의.php:157) + validUntil = date+max(30,turnterm*3)분
 *    (che_종전제의.php:149-151) + 국가 메일함 라우팅(9000+destNationID, receiver-before-sender).
 *  - **actor log byte**: `<C>●</>{month}월:<D><b>{상대국}</b></>{로} 종전 제의 서신을 보냈습니다.<1>{date}</>`
 *    (che_종전제의.php:129 + ActionLogger MONTH 포맷).
 */
class CheJongjeonjeuiGoldenTest {

    @AfterTest fun reset() = opensamguk.logic.event.StaticEventHandler.clear()

    private fun pipeline() = GeneralActionPipeline()

    private fun context(
        rng: RandUtil,
        args: Map<String, Any?> = mapOf("destNationID" to 5),
        destGeneralName: String = "오국",
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
        val cmd = cheJongjeonjeui(pipeline())
        // NoRng: resolve 내부에서 단 한 번이라도 draw하면 MustNotBeReachedException → 실패. draw COUNT = 0 강제.
        cmd.resolve(context(RandUtil(NoRng())))
    }

    @Test
    fun `a stray draw on the NoRng path throws`() {
        val ctx = context(RandUtil(NoRng()))
        assertFailsWith<MustNotBeReachedException> { ctx.rng.nextRangeInt(1, 5) }
    }

    // ── emitted effect: payload + title + validUntil + 메일함 라우팅 ───────────────────────────────
    @Test
    fun `resolve emits one stopWar diplomacy message with the verbatim title and payload`() {
        val cmd = cheJongjeonjeui(pipeline())
        val ctx = context(RandUtil(NoRng()))
        cmd.resolve(ctx)

        val msgs = ctx.messages()
        assertEquals(1, msgs.size)
        val msg = msgs[0]
        assertEquals(MessageType.DIPLOMACY, msg.msgType)
        // title verbatim — `{국명}의 종전 제의 서신` (che_종전제의.php:157)
        assertEquals("촉의 종전 제의 서신", msg.msg)
        // option — action=stop_war, deletable=false (che_종전제의.php:161-163), insertion order preserved
        assertEquals(listOf("action", "deletable"), msg.msgOption!!.keys.toList())
        assertEquals("stop_war", msg.msgOption!!["action"])
        assertEquals(false, msg.msgOption!!["deletable"])
        // src/dest targets — src=실 장수/국가, dest=국가만(generalId=0)
        assertEquals(7, msg.src.generalId)
        assertEquals(1, msg.src.nationId)
        assertEquals("촉", msg.src.nationName)
        assertEquals(0, msg.dest.generalId)
        assertEquals(5, msg.dest.nationId)
        assertEquals("오국", msg.dest.nationName)
    }

    @Test
    fun `validUntil equals send date plus max 30 turnterm times 3 minutes`() {
        val cmd = cheJongjeonjeui(pipeline())
        // turnterm=60 → +180분
        val ctx = context(RandUtil(NoRng()), turnterm = 60)
        cmd.resolve(ctx)
        val msg = ctx.messages()[0]
        val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        val sent = LocalDateTime.parse(msg.date, fmt)
        val until = LocalDateTime.parse(msg.validUntil, fmt)
        assertEquals(180L, Duration.between(sent, until).toMinutes())

        // turnterm=5 → max(30, 15) = 30분 floor (DiplomacySeam 단일 공식)
        val ctx2 = context(RandUtil(NoRng()), turnterm = 5)
        cmd.resolve(ctx2)
        val msg2 = ctx2.messages()[0]
        val sent2 = LocalDateTime.parse(msg2.date, fmt)
        val until2 = LocalDateTime.parse(msg2.validUntil, fmt)
        assertEquals(30L, Duration.between(sent2, until2).toMinutes())
    }

    @Test
    fun `send routes to dest nation mailbox receiver-before-sender with sender option nulled`() {
        val cmd = cheJongjeonjeui(pipeline())
        val ctx = context(RandUtil(NoRng()))
        cmd.resolve(ctx)
        val drafts = ctx.messages()[0].send()
        assertEquals(2, drafts.size)
        // receiver row FIRST — 국가 메일함 = 9000 + destNationID
        assertEquals(MessageRowDraft.Row.RECEIVER, drafts[0].whichRow)
        assertEquals(Mailbox.NATIONAL_BASE + 5, drafts[0].mailbox)
        assertEquals("stop_war", drafts[0].option!!["action"])
        // sender row — 발신국 메일함 = 9000 + srcNationID, option NULL(diplomacy action 보유 행)
        assertEquals(MessageRowDraft.Row.SENDER, drafts[1].whichRow)
        assertEquals(Mailbox.NATIONAL_BASE + 1, drafts[1].mailbox)
        assertNull(drafts[1].option)
    }

    // ── actor log byte ───────────────────────────────────────────────────────────────────────────
    @Test
    fun `actor log is byte-exact`() {
        val cmd = cheJongjeonjeui(pipeline())
        val ctx = context(RandUtil(NoRng()))
        cmd.resolve(ctx)
        val logs = ctx.logs()
        assertEquals(1, logs.size)
        // month=3, date=12:34. josaRo = JosaUtil.pick(destNationName='오국', '로') → '오국'의 끝 '국'은
        // 종성(받침 ㄱ) 보유 → '으로'(che_종전제의.php:124). ActionLogger MONTH 프리픽스 `<C>●</>{month}월:`.
        assertEquals("<C>●</>3월:<D><b>오국</b></>으로 종전 제의 서신을 보냈습니다.<1>12:34</>", logs[0])
        // 제의는 부수 로그 없음(글로벌/dest 빈 flush)
        assertTrue(ctx.globalActionLogs().isEmpty())
    }
}
