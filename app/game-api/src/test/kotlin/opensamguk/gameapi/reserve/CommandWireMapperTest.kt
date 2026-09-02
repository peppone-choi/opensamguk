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

        assertTrue(CommandWireMapper.isIntakeCommand("ResetStat"))
        val resetStat = roundTrip(
            CommandWireMapper.toCommand(
                "ResetStat",
                10,
                "r",
                """{"leadership":55,"strength":55,"intel":55,"inheritBonusStat":[1,1,1]}""",
            )!!,
        ) as TurnDaemonCommand.ResetStat
        assertEquals(10, resetStat.generalId)
        assertEquals(55, resetStat.leadership)
        assertEquals(55, resetStat.strength)
        assertEquals(55, resetStat.intel)
        assertEquals(listOf(1, 1, 1), resetStat.inheritBonusStat)
    }

    @Test
    fun `tournament admin start and reset map to immediate daemon commands`() {
        assertTrue(CommandWireMapper.isIntakeCommand("tournamentStart"))
        assertTrue(CommandWireMapper.isIntakeCommand("tournamentReset"))

        val start = roundTrip(
            CommandWireMapper.toCommand("tournamentStart", 10, "req-start", """{"type":2}""")!!,
        ) as TurnDaemonCommand.TournamentStart
        assertEquals("req-start", start.requestId)
        assertEquals(10, start.generalId)
        assertEquals(2, start.tournamentType)

        val reset = roundTrip(
            CommandWireMapper.toCommand("tournamentReset", 10, "req-reset", null)!!,
        ) as TurnDaemonCommand.TournamentReset
        assertEquals("req-reset", reset.requestId)
        assertEquals(10, reset.generalId)
    }

    @Test
    fun `registered instant actions map to typed commands with authenticated general identity`() {
        val die = roundTrip(CommandWireMapper.toCommand("DieOnPrestart", 10, "r", null)!!) as TurnDaemonCommand.DieOnPrestart
        assertEquals(10, die.generalId)
        val drop = roundTrip(CommandWireMapper.toCommand("DropItem", 10, "r", "{\"itemType\":\"weapon\"}")!!) as TurnDaemonCommand.DropItem
        assertEquals(10, drop.generalId)
        assertEquals("weapon", drop.itemType)
        val retreat = roundTrip(CommandWireMapper.toCommand("InstantRetreat", 10, "r", null)!!) as TurnDaemonCommand.InstantRetreat
        assertEquals(10, retreat.generalId)
        val owner = roundTrip(CommandWireMapper.toCommand("CheckOwner", 10, "r", "{\"destGeneralID\":27}")!!) as TurnDaemonCommand.CheckOwner
        assertEquals(10, owner.generalId)
        assertEquals(27, owner.destGeneralId)
    }

    @Test
    fun `select pool owner comes only from the verified intake argument`() {
        val pick = roundTrip(
            CommandWireMapper.toCommand(
                "selectPoolPick",
                0,
                "req-pick",
                """{"uniqueName":"청룡","ownerUserId":999}""",
                ownerUserId = 7,
            )!!,
        ) as TurnDaemonCommand.SelectPoolPick

        assertEquals(0, pick.generalId)
        assertEquals(7, pick.ownerUserId)
        assertEquals("청룡", pick.uniqueName)
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

    // ── W0-7 — 외교 서신 승인/거부 + 인사(임명/추방/외교권자·조언자 임명) 인테이크 ──────────────

    @Test
    fun `diploRespondLetter는 letterNo-isAgree-reason을 PHP 인자명 verbatim으로 매핑한다`() {
        assertTrue(CommandWireMapper.isIntakeCommand("diploRespondLetter"))

        // 승인 — j_diplomacy_respond_letter.php:16-18 POST {letterNo, isAgree, reason}
        val approve = roundTrip(
            CommandWireMapper.toCommand("diploRespondLetter", 10, "r", """{"letterNo":7,"isAgree":true}""")!!,
        ) as TurnDaemonCommand.DiploRespondLetter
        assertEquals(10, approve.generalId)
        assertEquals(7, approve.letterNo)
        assertTrue(approve.isAgree)
        assertEquals("", approve.reason)

        // 거부 + 사유. isAgree 부재 → false (PHP getPost('isAgree','bool',false)).
        val decline = roundTrip(
            CommandWireMapper.toCommand("diploRespondLetter", 10, "r", """{"letterNo":7,"reason":"조건 불충분"}""")!!,
        ) as TurnDaemonCommand.DiploRespondLetter
        assertFalse(decline.isAgree)
        assertEquals("조건 불충분", decline.reason)
    }

    @Test
    fun `appoint는 officerLevel-destGeneralID-destCityID를 매핑한다 - 수뇌임명과 도시임명 겸용`() {
        assertTrue(CommandWireMapper.isIntakeCommand("appoint"))

        // 수뇌 임명 (j_myBossInfo.php action=임명, officerLevel 5..11 — destCityID 부재 → 0)
        val chief = roundTrip(
            CommandWireMapper.toCommand("appoint", 10, "r", """{"officerLevel":11,"destGeneralID":42}""")!!,
        ) as TurnDaemonCommand.Appoint
        assertEquals(10, chief.generalId)
        assertEquals(42, chief.destGeneralId)
        assertEquals(0, chief.destCityId)
        assertEquals(11, chief.officerLevel)

        // 도시 임명 (officerLevel 2..4 — destCityID 필수, j_myBossInfo.php:331-352)
        val city = roundTrip(
            CommandWireMapper.toCommand("appoint", 10, "r", """{"officerLevel":4,"destGeneralID":42,"destCityID":15}""")!!,
        ) as TurnDaemonCommand.Appoint
        assertEquals(15, city.destCityId)
        assertEquals(4, city.officerLevel)
    }

    @Test
    fun `kick은 destGeneralID를 매핑한다`() {
        assertTrue(CommandWireMapper.isIntakeCommand("kick"))

        val kick = roundTrip(
            CommandWireMapper.toCommand("kick", 10, "r", """{"destGeneralID":42}""")!!,
        ) as TurnDaemonCommand.Kick
        assertEquals(10, kick.generalId)
        assertEquals(42, kick.destGeneralId)

        // destGeneralID 부재 → 0 (PHP j_myBossInfo.php:42 '장수가 지정되지 않았습니다.' 게이트는 엔진).
        val none = CommandWireMapper.toCommand("kick", 10, "r", "{}") as TurnDaemonCommand.Kick
        assertEquals(0, none.destGeneralId)
    }

    @Test
    fun `changePermission은 isAmbassador-genlist를 매핑한다`() {
        assertTrue(CommandWireMapper.isIntakeCommand("changePermission"))

        // 외교권자 임명 (j_general_set_permission.php:11-12 POST {isAmbassador, genlist})
        val ambassador = roundTrip(
            CommandWireMapper.toCommand("changePermission", 10, "r", """{"isAmbassador":true,"genlist":[42,43]}""")!!,
        ) as TurnDaemonCommand.ChangePermission
        assertEquals(10, ambassador.generalId)
        assertTrue(ambassador.isAmbassador)
        assertEquals(listOf(42, 43), ambassador.targetGeneralIds)

        // 조언자 전체 해제 — genlist 부재 → 빈 배열(PHP: normal 리셋 후 success 종료).
        val reset = CommandWireMapper.toCommand("changePermission", 10, "r", """{"isAmbassador":false}""") as TurnDaemonCommand.ChangePermission
        assertFalse(reset.isAmbassador)
        assertTrue(reset.targetGeneralIds.isEmpty())
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

    @Test
    fun `account and mailbox commands map to typed immediate commands`() {
        val setting = CommandWireMapper.toCommand(
            "setMySetting",
            10,
            "r",
            """{"tnmt":0,"defence_train":85,"use_treatment":40,"use_auto_nation_turn":0}""",
        ) as TurnDaemonCommand.SetMySetting
        assertEquals(85, setting.settings.defenceTrain)
        assertEquals(40, setting.settings.useTreatment)

        val read = CommandWireMapper.toCommand(
            "readLatestMessage",
            10,
            "r",
            """{"type":"private","msgID":42}""",
        ) as TurnDaemonCommand.ReadLatestMessage
        assertEquals("private", read.messageType)
        assertEquals(42, read.msgID)

        val missingRecipient = CommandWireMapper.toCommand(
            "sendMessage",
            10,
            "r",
            """{"text":"수신자 없음"}""",
        ) as TurnDaemonCommand.SendMessage
        assertEquals(0, missingRecipient.mailbox)

        assertTrue(CommandWireMapper.toCommand("vacation", 10, "r", "{}") is TurnDaemonCommand.Vacation)

        val invader = CommandWireMapper.toCommand(
            "acceptRaiseInvaderMessage",
            10,
            "r",
            """{"messageId":77}""",
        ) as TurnDaemonCommand.AcceptRaiseInvaderMessage
        assertEquals(77, invader.messageId)
    }
}
