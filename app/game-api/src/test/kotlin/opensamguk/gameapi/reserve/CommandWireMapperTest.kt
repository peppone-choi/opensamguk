package opensamguk.gameapi.reserve

import opensamguk.common.wire.TurnDaemonCommand
import opensamguk.common.wire.TurnDaemonCommandEnvelope
import opensamguk.common.wire.decodeCommandEnvelope
import opensamguk.common.wire.encodeCommandPayload
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * F-INTAKE publisher-side test (no container) — proves the `{code, argJson, generalId}` → typed
 * [TurnDaemonCommand] mapping AND that the typed command survives the EXACT wire round-trip the
 * daemon's `RedisCommandStream` consumer reads back (encode → decode through the `:common`
 * serializer = the `payload` field bytes `CommandReserveService` XADDs).
 *
 * This is the publisher half of the seam: the controller's resolved `generalId` is threaded onto the
 * typed command (never trusted from the body), and the immediate-intake codes map to their typed
 * variants while the turn-reserved `che_*` codes return `null` (→ the general_turn ring + Run/POKE).
 */
class CommandWireMapperTest {

    /** Encode the mapped command through the wire serializer, then decode it back (the daemon's read). */
    private fun roundTrip(command: TurnDaemonCommand): TurnDaemonCommand {
        val payload = encodeCommandPayload(
            TurnDaemonCommandEnvelope(requestId = "req-1", sentAt = "0200-01-01T00:00:00Z", command = command),
        )
        return decodeCommandEnvelope(payload).command
    }

    @Test
    fun `che_ command is NOT an intake command (turn-reserved path)`() {
        assertNull(CommandWireMapper.toCommand("che_농지개간", generalId = 10, requestId = "r", argJson = """{"amount":100}"""))
        assertTrue(!CommandWireMapper.isIntakeCommand("che_농지개간"))
    }

    @Test
    fun `placeBet maps the merged extraArgs body and threads the resolved generalId`() {
        val cmd = CommandWireMapper.toCommand(
            code = "placeBet",
            generalId = 42,
            requestId = "req-bet",
            argJson = """{"bettingId":7,"bettingType":[1,3],"amount":500}""",
        )
        val bet = roundTrip(cmd!!) as TurnDaemonCommand.PlaceBet
        assertEquals("req-bet", bet.requestId)
        assertEquals(7, bet.bettingId)
        assertEquals(42, bet.generalId) // resolved id, NOT from the body
        assertEquals(listOf(1, 3), bet.bettingType)
        assertEquals(500, bet.amount)
    }

    @Test
    fun `auctionBid maps auctionId amount and optional tryExtendCloseDate`() {
        val cmd = CommandWireMapper.toCommand(
            code = "auctionBid",
            generalId = 11,
            requestId = "req-bid",
            argJson = """{"auctionId":9,"amount":1200,"isUnique":true}""",
        )
        val bid = roundTrip(cmd!!) as TurnDaemonCommand.AuctionBid
        assertEquals(9, bid.auctionId)
        assertEquals(11, bid.generalId)
        assertEquals(1200, bid.amount)
        assertNull(bid.tryExtendCloseDate) // not supplied → null (handler defaults to true)
    }

    @Test
    fun `nation finance setters map their amount or value or msg fields`() {
        val rate = roundTrip(CommandWireMapper.toCommand("setRate", 10, "r", """{"amount":25}""")!!) as TurnDaemonCommand.SetRate
        assertEquals(25, rate.amount)
        assertEquals(10, rate.generalId)

        val bill = roundTrip(CommandWireMapper.toCommand("setBill", 10, "r", """{"amount":150}""")!!) as TurnDaemonCommand.SetBill
        assertEquals(150, bill.amount)

        val secret = roundTrip(CommandWireMapper.toCommand("setSecretLimit", 10, "r", """{"amount":30}""")!!) as TurnDaemonCommand.SetSecretLimit
        assertEquals(30, secret.amount)

        val notice = roundTrip(CommandWireMapper.toCommand("setNotice", 10, "r", """{"msg":"전군 대기"}""")!!) as TurnDaemonCommand.SetNotice
        assertEquals("전군 대기", notice.msg)

        val scoutMsg = roundTrip(CommandWireMapper.toCommand("setScoutMsg", 10, "r", """{"msg":"함께"}""")!!) as TurnDaemonCommand.SetScoutMsg
        assertEquals("함께", scoutMsg.msg)
    }

    @Test
    fun `block-war and block-scout map the boolean value`() {
        val war = roundTrip(CommandWireMapper.toCommand("setBlockWar", 10, "r", """{"value":true}""")!!) as TurnDaemonCommand.SetBlockWar
        assertTrue(war.value)

        val scout = roundTrip(CommandWireMapper.toCommand("setBlockScout", 10, "r", """{"value":false}""")!!) as TurnDaemonCommand.SetBlockScout
        assertTrue(!scout.value)
    }

    @Test
    fun `tournament enroll maps value and inherit resets are no-arg`() {
        val enroll = roundTrip(CommandWireMapper.toCommand("tournamentEnroll", 10, "r", """{"value":1}""")!!) as TurnDaemonCommand.TournamentEnroll
        assertEquals(1, enroll.value)

        val resetTt = roundTrip(CommandWireMapper.toCommand("inheritResetTurnTime", 10, "r", null)!!) as TurnDaemonCommand.InheritResetTurnTime
        assertEquals(10, resetTt.generalId)

        val resetSp = roundTrip(CommandWireMapper.toCommand("inheritResetSpecialWar", 10, "r", "")!!) as TurnDaemonCommand.InheritResetSpecialWar
        assertEquals(10, resetSp.generalId)

        val nextSp = roundTrip(CommandWireMapper.toCommand("inheritSetNextSpecialWar", 10, "r", """{"specialWar":"귀병"}""")!!) as TurnDaemonCommand.InheritSetNextSpecialWar
        assertEquals("귀병", nextSp.specialWar)
        assertEquals(10, nextSp.generalId)
    }

    @Test
    fun `troop intake codes map their args and thread the resolved acting generalId`() {
        val new = roundTrip(CommandWireMapper.toCommand("troopNew", 7, "r", """{"troopName":"제1군단"}""")!!) as TurnDaemonCommand.TroopNew
        assertEquals("제1군단", new.troopName)
        assertEquals(7, new.generalId)

        val join = roundTrip(CommandWireMapper.toCommand("troopJoin", 9, "r", """{"troopId":7}""")!!) as TurnDaemonCommand.TroopJoin
        assertEquals(7, join.troopId)
        assertEquals(9, join.generalId)

        val exit = roundTrip(CommandWireMapper.toCommand("troopExit", 9, "r", null)!!) as TurnDaemonCommand.TroopExit
        assertEquals(9, exit.generalId)

        // troopKick: generalId is the RESOLVED kicker; the target rides the body (targetGeneralId, legacy generalID).
        val kick = roundTrip(CommandWireMapper.toCommand("troopKick", 7, "r", """{"troopId":7,"targetGeneralId":8}""")!!) as TurnDaemonCommand.TroopKick
        assertEquals(7, kick.generalId)
        assertEquals(7, kick.troopId)
        assertEquals(8, kick.targetGeneralId)
        val kickLegacy = CommandWireMapper.toCommand("troopKick", 7, "r", """{"troopId":7,"generalID":8}""") as TurnDaemonCommand.TroopKick
        assertEquals(8, kickLegacy.targetGeneralId)

        val setName = roundTrip(CommandWireMapper.toCommand("troopSetName", 7, "r", """{"troopId":7,"troopName":"새이름"}""")!!) as TurnDaemonCommand.TroopSetName
        assertEquals(7, setName.troopId)
        assertEquals("새이름", setName.troopName)
    }

    @Test
    fun `board intake codes map isSecret title text and the resolved acting generalId`() {
        assertTrue(CommandWireMapper.isIntakeCommand("boardArticle"))
        assertTrue(CommandWireMapper.isIntakeCommand("boardComment"))

        val secret = roundTrip(
            CommandWireMapper.toCommand("boardArticle", 10, "r", """{"isSecret":true,"title":"기밀","text":"본문"}""")!!,
        ) as TurnDaemonCommand.BoardArticle
        assertEquals(10, secret.generalId)
        assertTrue(secret.isSecret)
        assertEquals("기밀", secret.title)
        assertEquals("본문", secret.text)

        // 회의실 기본값: isSecret 생략 → false. title/text는 nullable 유지(PHP null-부재 vs blank).
        val open = roundTrip(
            CommandWireMapper.toCommand("boardArticle", 10, "r", """{"text":"본문만"}""")!!,
        ) as TurnDaemonCommand.BoardArticle
        assertFalse(open.isSecret)
        assertNull(open.title)
        assertEquals("본문만", open.text)

        val comment = roundTrip(
            CommandWireMapper.toCommand("boardComment", 10, "r", """{"articleNo":5,"text":"의견"}""")!!,
        ) as TurnDaemonCommand.BoardComment
        assertEquals(10, comment.generalId)
        assertEquals(5, comment.articleNo)
        assertEquals("의견", comment.text)
    }

    @Test
    fun `string-coerced numbers and malformed json degrade gracefully`() {
        // the UI may send numbers as strings; lenient parse coerces them.
        val rate = CommandWireMapper.toCommand("setRate", 10, "r", """{"amount":"15"}""") as TurnDaemonCommand.SetRate
        assertEquals(15, rate.amount)

        // malformed body → empty args → fields fall to their command defaults (handler then denies/validates).
        val bet = CommandWireMapper.toCommand("placeBet", 10, "r", "not json") as TurnDaemonCommand.PlaceBet
        assertEquals(0, bet.bettingId)
        assertEquals(0, bet.amount)
        assertTrue(bet.bettingType.isEmpty())
    }
}
