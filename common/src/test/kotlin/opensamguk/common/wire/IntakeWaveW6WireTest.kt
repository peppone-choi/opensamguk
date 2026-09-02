package opensamguk.common.wire

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * W6/W5 REST mutation batch wire round-trip — the new intake command + result variants (FOUNDATION).
 *
 * Confirms (a) each new [TurnDaemonCommand] variant (sendMessage/deleteMessage, auctionOpen{BuyRice,
 * SellRice,Unique}, diplo{Send,Rollback,Destroy}Letter, selectPool{Pick,Update}) encodes/decodes
 * through the union discriminator, and (b) the [TurnDaemonCommandResultSerializer] `(type, ok)`
 * selector routes the new result classes: sendMessage/deleteMessage → single-type; the 3 auction-open
 * codes collapse to [AuctionOpenResult]; the 3 diplo-letter codes collapse to [DiploLetterResult]; the
 * 2 select-pool codes collapse to [SelectPoolActionResult]. `buildNationCandidate` stays in the
 * boolean-ok group ([GeneralBoolResult]) — Q-D1 RESOLVED — so it is asserted here too.
 *
 * Pure structural round-trip (no PHP golden) — the parity goldens for the RNG-bearing slices
 * (selectPool / buildNationCandidate) are captured + gated under /parity-wave.
 */
class IntakeWaveW6WireTest {

    private fun cmdRoundTrip(c: TurnDaemonCommand): TurnDaemonCommand {
        val raw = WireJson.encodeToString(TurnDaemonCommand.serializer(), c)
        return WireJson.decodeFromString(TurnDaemonCommand.serializer(), raw)
    }

    private fun resRoundTrip(r: TurnDaemonCommandResult): TurnDaemonCommandResult {
        val raw = WireJson.encodeToString(TurnDaemonCommandResult.serializer(), r)
        return WireJson.decodeFromString(TurnDaemonCommandResult.serializer(), raw)
    }

    @Test
    fun `message + auction-open + diplo-letter + select-pool commands round-trip`() {
        val send = TurnDaemonCommand.SendMessage(generalId = 10, mailbox = 9999, text = "안녕")
        assertEquals(send, cmdRoundTrip(send))

        val del = TurnDaemonCommand.DeleteMessage(generalId = 10, msgID = 42)
        assertEquals(del, cmdRoundTrip(del))

        val buyRice = TurnDaemonCommand.AuctionOpenBuyRice(
            generalId = 10, amount = 1000, closeTurnCnt = 12, startBidAmount = 100, finishBidAmount = 150,
        )
        assertEquals(buyRice, cmdRoundTrip(buyRice))

        val sellRice = TurnDaemonCommand.AuctionOpenSellRice(
            generalId = 10, amount = 1000, closeTurnCnt = 12, startBidAmount = 100, finishBidAmount = 150,
        )
        assertEquals(sellRice, cmdRoundTrip(sellRice))

        val unique = TurnDaemonCommand.AuctionOpenUnique(generalId = 10, itemId = "che_명마", amount = 5000)
        assertEquals(unique, cmdRoundTrip(unique))

        // prevLetterNo nullable — 부재(이전 문서 없음) 보존.
        val diploSend = TurnDaemonCommand.DiploSendLetter(
            generalId = 10, destNationId = 2, prevLetterNo = null, textBrief = "요약", textDetail = "본문",
        )
        assertEquals(diploSend, cmdRoundTrip(diploSend))

        val diploRollback = TurnDaemonCommand.DiploRollbackLetter(generalId = 10, letterNo = 7)
        assertEquals(diploRollback, cmdRoundTrip(diploRollback))

        val diploDestroy = TurnDaemonCommand.DiploDestroyLetter(generalId = 10, letterNo = 7)
        assertEquals(diploDestroy, cmdRoundTrip(diploDestroy))

        // 스탯/성격 nullable — 부재(풀 기본값) 보존.
        val pick = TurnDaemonCommand.SelectPoolPick(
            generalId = 10, uniqueName = "조조", leadership = 90, strength = 80, intel = 95,
            personalityName = "Random", useOwnPicture = true,
        )
        assertEquals(pick, cmdRoundTrip(pick))

        val update = TurnDaemonCommand.SelectPoolUpdate(generalId = 10, uniqueName = "조조")
        assertEquals(update, cmdRoundTrip(update))

        val refresh = TurnDaemonCommand.SelectPoolRefresh(
            ownerUserId = 77,
            requestedAt = "2026-07-10T03:00:00Z",
        )
        assertEquals(refresh, cmdRoundTrip(refresh))

        val moderation = TurnDaemonCommand.AdminGeneralModeration(
            actorGeneralId = 10,
            generalIds = listOf(20, 30),
            action = "block2",
        )
        assertEquals(moderation, cmdRoundTrip(moderation))

        val settings = TurnDaemonCommand.AdminWorldSettings(
            status = "PRE_OPEN",
            settings = listOf(
                AdminWorldSetting("turnterm", intValue = 30),
                AdminWorldSetting("msg", stringValue = "점검 중"),
            ),
        )
        assertEquals(settings, cmdRoundTrip(settings))
    }

    @Test
    fun `single-type message result selectors route SendMessageResult and DeleteMessageResult`() {
        val sendOk = SendMessageResult(
            ok = true,
            generalId = 10,
            msgType = "private",
            msgID = 100,
            recipientId = 7,
            recipientName = "관우",
        )
        val rSendOk = resRoundTrip(sendOk)
        assertIs<SendMessageResult>(rSendOk)
        assertEquals(sendOk, rSendOk)

        val sendFail = SendMessageResult(ok = false, generalId = 10, reason = "차단되었습니다.")
        assertIs<SendMessageResult>(resRoundTrip(sendFail))

        val delOk = DeleteMessageResult(ok = true, generalId = 10, msgID = 42)
        val rDelOk = resRoundTrip(delOk)
        assertIs<DeleteMessageResult>(rDelOk)
        assertEquals(delOk, rDelOk)
    }

    @Test
    fun `message results echo the full msgType matrix and msgID, and delete carries reason on fail`() {
        // W6a 슬라이스 추가: public/national/diplomacy/private 모든 msgType + msgID echo가 round-trip 보존되는지.
        for (msgType in listOf("public", "national", "diplomacy", "private")) {
            val ok = SendMessageResult(ok = true, generalId = 5, msgType = msgType, msgID = 777)
            val r = resRoundTrip(ok)
            assertIs<SendMessageResult>(r)
            assertEquals(ok, r)
            assertEquals(msgType, (r as SendMessageResult).msgType)
            assertEquals(777, r.msgID)
        }
        // deleteMessage 실패는 msgID echo + reason을 보존한다.
        val delFail = DeleteMessageResult(ok = false, generalId = 5, msgID = 42, reason = "5분 이내의 메시지만 삭제할 수 있습니다.")
        val rd = resRoundTrip(delFail)
        assertIs<DeleteMessageResult>(rd)
        assertEquals(delFail, rd)
        assertEquals(42, (rd as DeleteMessageResult).msgID)
        assertEquals("5분 이내의 메시지만 삭제할 수 있습니다.", rd.reason)
    }

    @Test
    fun `auction-open result collapses to AuctionOpenResult on both ok and fail`() {
        val ok = AuctionOpenResult(type = "auctionOpenBuyRice", ok = true, generalId = 10, auctionId = 5)
        val rok = resRoundTrip(ok)
        assertIs<AuctionOpenResult>(rok)
        assertEquals(ok, rok)

        val fail = AuctionOpenResult(type = "auctionOpenUnique", ok = false, generalId = 10, reason = "미구현")
        assertIs<AuctionOpenResult>(resRoundTrip(fail))
        assertIs<AuctionOpenResult>(resRoundTrip(AuctionOpenResult(type = "auctionOpenSellRice", ok = true, generalId = 10, auctionId = 6)))
    }

    @Test
    fun `diplo-letter result collapses to DiploLetterResult on both ok and fail`() {
        val ok = DiploLetterResult(type = "diploSendLetter", ok = true, generalId = 10, letterNo = 3)
        val rok = resRoundTrip(ok)
        assertIs<DiploLetterResult>(rok)
        assertEquals(ok, rok)

        assertIs<DiploLetterResult>(resRoundTrip(DiploLetterResult(type = "diploRollbackLetter", ok = false, generalId = 10, reason = "서신이 없습니다.")))
        assertIs<DiploLetterResult>(resRoundTrip(DiploLetterResult(type = "diploDestroyLetter", ok = true, generalId = 10, letterNo = 3)))
    }

    @Test
    fun `select-pool result collapses to SelectPoolActionResult on both ok and fail`() {
        val ok = SelectPoolActionResult(type = "selectPoolPick", ok = true, generalId = 10)
        val rok = resRoundTrip(ok)
        assertIs<SelectPoolActionResult>(rok)
        assertEquals(ok, rok)

        assertIs<SelectPoolActionResult>(resRoundTrip(SelectPoolActionResult(type = "selectPoolUpdate", ok = false, generalId = 10, reason = "미구현")))
        assertIs<SelectPoolActionResult>(resRoundTrip(SelectPoolActionResult(type = "selectPoolRefresh", ok = true, generalId = 0)))
    }

    @Test
    fun `buildNationCandidate stays in the boolean-ok group as GeneralBoolResult (Q-D1)`() {
        val ok = GeneralBoolResult(type = "buildNationCandidate", ok = true, generalId = 10)
        assertIs<GeneralBoolResult>(resRoundTrip(ok))
        val fail = GeneralBoolResult(type = "buildNationCandidate", ok = false, generalId = 10, reason = "미구현")
        assertIs<GeneralBoolResult>(resRoundTrip(fail))
    }
}
