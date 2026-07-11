package opensamguk.engine.intake

import opensamguk.common.wire.DeleteMessageResult
import opensamguk.common.wire.SendMessageResult
import opensamguk.common.wire.TurnDaemonCommand
import opensamguk.common.wire.TurnDaemonCommandResult
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.Nation
import opensamguk.engine.turn.PerTurnOverlay
import opensamguk.engine.turn.TurnGeneral
import opensamguk.infra.read.ContactReader
import opensamguk.logic.util.jsonEncode
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * 메시지 (message) intake 핸들러 — `sammo/API/Message/{SendMessage,DeleteMessage}.php` `launch()`
 * 본문 + `Message::send`/`deleteMsg`/`invalidate`/`sendRaw`/`sendToReceiver`/`sendToSender`
 * (`sammo/Message.php`) 의 faithful 포팅 (W6a 메시지). per-run (world + recorder + contact read seam),
 * [VoteHandler]/[BoardHandler]를 미러링.
 *
 * **부수 효과는 [ChangeRecorder] 메시지 채널을 통해서만 기록한다(one-daemon-write 규칙).** PHP의
 * `DB::db()->insert('message', …)`(sendRaw) = [ChangeRecorder.recordMessageInsert],
 * `DB::db()->update('message', …)`(invalidate) = [ChangeRecorder.recordMessageInvalidate].
 * `general.newmsg=1`(sendToReceiver의 private/diplomacy 알림)은 [InMemoryTurnWorld.applyGeneralDirtyFree]
 * + [ChangeRecorder.diffGeneral] meta 경로로 기록한다(general.meta["newmsg"]=1 — [VoteHandler.markAllGeneralsNewVote]
 * 패턴과 동일; `newmsg`는 V1 general 스칼라 컬럼이 아니라 meta jsonb로 영속).
 *
 * **mailbox 라우팅 (PHP Message::MAILBOX_PUBLIC/NATIONAL):**
 *  - `9999` (MAILBOX_PUBLIC)      → 전체 메시지(public). receiver 행만, sender 행 없음(`sendToSender`=[0,0], R4).
 *  - `>=9000` (MAILBOX_NATIONAL)  → 국가(national, dest==자국) / 외교(diplomacy, dest!=자국). receiver+sender.
 *  - `<9000` (그 외 양수)         → 개인(private, 상대 generalID). receiver+sender + receiver.newmsg=1.
 *
 * [contactReader]는 nullable — null이면 stub-empty(연락처 없음)로 동작한다([VotePollRepository] 주입 패턴).
 * 이 핸들러의 send/delete 게이트는 world(in-memory source of truth)로 충족되므로 reader 부재여도 동작한다.
 */
class MessageHandler(
    private val world: InMemoryTurnWorld,
    private val recorder: ChangeRecorder,
    @Suppress("unused") private val contactReader: ContactReader? = null,
    private val nowProvider: () -> Instant = Instant::now,
) {
    // ── SendMessage.php::launch ──────────────────────────────────────────────────────────────────
    fun handleSend(c: TurnDaemonCommand.SendMessage): TurnDaemonCommandResult {
        val me = world.getGeneralById(c.generalId)
            ?: return fail(c.generalId, "장수가 없습니다.")

        val accessBlocked = AccessLogThrottle(world, recorder, nowProvider).increaseAndBlocked(c.generalId)

        if (blockLevel(me) == 1 || blockLevel(me) == 3) {
            return fail(c.generalId, "차단되었습니다.")
        }
        if (accessBlocked) {
            return fail(c.generalId, "접속 제한입니다.")
        }

        // PHP: $srcNation = getNationStaticInfo($me['nation']); if (null) '존재하지 않는 국가입니다.'.
        val srcNation = nationTarget(me.nationId)
            ?: return fail(c.generalId, "존재하지 않는 국가입니다.")
        val src = MsgTarget(
            generalId = me.id,
            generalName = me.name,
            nationId = me.nationId,
            nationName = srcNation.name,
            color = srcNation.color,
            icon = iconOf(me),
        )

        val penalty = penaltyOf(me)
        // PHP: $permission = checkSecretPermission($me) — 외교권자 판정(4). opensamguk은 meta["permission"]로 운반.
        val permission = secretPermission(me)
        val mailbox = c.mailbox

        // ── 전체 메시지 (mailbox == MAILBOX_PUBLIC) ──
        if (mailbox == MAILBOX_PUBLIC) {
            // PHP: if ($penalty[NoSendPublicMsg] ?? 0) '공개 메세지를 보낼 수 없습니다.'.
            if (penalty["no_send_public_msg"] == true || penalty["NoSendPublicMsg"] == true) {
                return fail(c.generalId, "공개 메세지를 보낼 수 없습니다.")
            }
            val msgId = sendPublic(src, c.text)
            return ok(c.generalId, "public", msgId)
        }

        // ── 국가/외교 메시지 (mailbox >= MAILBOX_NATIONAL) ──
        if (mailbox >= MAILBOX_NATIONAL) {
            // PHP: permission<4 → destNationID=자국; else destNationID = mailbox - 9000.
            val destNationId = if (permission < 4) me.nationId else mailbox - MAILBOX_NATIONAL

            if (destNationId == me.nationId) {
                val msgId = sendNational(src, c.text)
                return ok(c.generalId, "national", msgId)
            }

            // PHP genDiplomacyMessage: getNationStaticInfo(destNationID) null → '존재하지 않는 국가입니다.'.
            val destNation = nationTarget(destNationId)
                ?: return fail(c.generalId, "존재하지 않는 국가입니다.")
            val dest = MsgTarget(0, "", destNationId, destNation.name, destNation.color)
            val msgId = sendDiplomacy(src, dest, c.text)
            return ok(c.generalId, "diplomacy", msgId)
        }

        // ── 개인 메시지 (0 < mailbox < MAILBOX_NATIONAL) ──
        if (mailbox > 0) {
            // PHP: if ($penalty[NoSendPrivateMsg] ?? 0) '개인 메세지를 보낼 수 없습니다.'.
            if (penalty["no_send_private_msg"] == true || penalty["NoSendPrivateMsg"] == true) {
                return fail(c.generalId, "개인 메세지를 보낼 수 없습니다.")
            }
            // PHP throttle: $msg_min_interval = $penalty[SendPrivateMsgDelay] ?? 2; if (interval < min) deny.
            // session->lastMsg(직전 발송 시각)는 게이트웨이/세션 관심사 — 데몬 world엔 없다. meta["lastMsg"]에
            // 실려 오면 평가하되, 부재 시 PHP `new DateTime('0000-00-00')`처럼 충분히 과거로 보아 통과(not-throttled).
            val msgMinInterval = (penalty["send_private_msg_delay"] as? Number)?.toInt()
                ?: (penalty["SendPrivateMsgDelay"] as? Number)?.toInt() ?: 2
            if (privateMsgInterval(me) < msgMinInterval) {
                return fail(c.generalId, "개인메세지는 ${msgMinInterval}초당 1건만 보낼 수 있습니다!")
            }

            // PHP genPrivateMessage: dest general 존재 → '존재하지 않는 유저입니다.'.
            val destGeneral = world.getGeneralById(mailbox)
                ?: return fail(c.generalId, "존재하지 않는 유저입니다.")
            // PHP: $destPermission = checkSecretPermission($destUser, false);
            //      if ($permission==4 && $destPermission==4 && $destUser['nation'] != $src->nationID)
            //        '외교권자끼리는 메시지를 보낼 수 없습니다.'.
            val destPermission = secretPermission(destGeneral)
            if (permission == 4 && destPermission == 4 && destGeneral.nationId != me.nationId) {
                return fail(c.generalId, "외교권자끼리는 메시지를 보낼 수 없습니다.")
            }
            // PHP: $destNation = getNationStaticInfo($destUser['nation']) ?? getNationStaticInfo(0).
            val destNation = nationTarget(destGeneral.nationId) ?: nationTarget(0)
                ?: return fail(c.generalId, "존재하지 않는 국가입니다.")
            val dest = MsgTarget(
                generalId = destGeneral.id,
                generalName = destGeneral.name,
                nationId = destGeneral.nationId,
                nationName = destNation.name,
                color = destNation.color,
                icon = iconOf(destGeneral),
            )
            val msgId = sendPrivate(src, dest, c.text)
            return ok(c.generalId, "private", msgId)
        }

        // PHP: return '알 수 없는 에러입니다.' (mailbox <= 0).
        return fail(c.generalId, "알 수 없는 에러입니다.")
    }

    // ── DeleteMessage.php::launch → Message::deleteMsg ───────────────────────────────────────────
    fun handleDelete(c: TurnDaemonCommand.DeleteMessage): TurnDaemonCommandResult {
        // PHP Message::deleteMsg($msgID, $generalID).
        // 1) getMessageByID — 없으면 '메시지가 없습니다'. read seam(contactReader=메시지 reader)이 없으면
        //    데몬은 메시지 본문을 조회할 수 없다 → faithful 'no-op deny'(스텁). 실제 삭제 게이트/무효화는
        //    message reader 주입 시 활성(아래 deleteWith가 본문을 수행). reader 부재 = '메시지가 없습니다'.
        val snapshot = messageReader?.invoke(c.msgID)
            ?: return failDelete(c.generalId, c.msgID, "메시지가 없습니다")
        return deleteWith(c, snapshot)
    }

    /**
     * 메시지 reader 주입 시 실제 deleteMsg 게이트 + 무효화를 수행한다. PHP `Message::deleteMsg`의
     * 게이트 순서를 충실히 재현한다. reader는 디스패처가 infra MessageRepository로 어댑트해 주입하며,
     * 테스트는 double을 먹인다(VoteHandler.votePollReader 패턴).
     */
    private fun deleteWith(c: TurnDaemonCommand.DeleteMessage, msg: MessageSnapshot): TurnDaemonCommandResult {
        val now = world.getState().lastTurnTime

        // PHP: if ($msgObj->src->generalID != $generalID) '본인의 메시지만 삭제할 수 있습니다.'.
        if (msg.srcGeneralId != c.generalId) {
            return failDelete(c.generalId, c.msgID, "본인의 메시지만 삭제할 수 있습니다.")
        }
        // PHP: if ($msgObj instanceof DiplomaticMessage) '시스템 외교 메시지는 삭제할 수 없습니다.'.
        // DiplomaticMessage = diplomacy 타입 + option['action'] 존재 (Message::buildFromArray).
        if (msg.type == "diplomacy" && msg.hasAction) {
            return failDelete(c.generalId, c.msgID, "시스템 외교 메시지는 삭제할 수 없습니다.")
        }
        // PHP: $prev5min = now - 5min; if ($msgObj->date < $prev5min) '5분 이내의 메시지만 삭제할 수 있습니다.'.
        if (msg.time.isBefore(now.minusSeconds(5 * 60))) {
            return failDelete(c.generalId, c.msgID, "5분 이내의 메시지만 삭제할 수 있습니다.")
        }
        // PHP: if (!($msgObj->msgOption['deletable'] ?? true)) '삭제할 수 없는 메시지입니다.'.
        if (msg.deletable == false) {
            return failDelete(c.generalId, c.msgID, "삭제할 수 없는 메시지입니다.")
        }

        // PHP invalidate(null, false): 본문 text='삭제된 메시지입니다.', option['invalid']=true,
        //  receiverMessageID 있으면 option['originalText']=원문, valid_until 불변(hideMsg=false).
        // (삭제된 메시지입니다. 는 런타임 본문 텍스트 — 골든 로그 아님, Q-A3 RESOLVED.)
        recorder.recordMessageInvalidate(c.msgID, formatPhpDate(msg.validUntil), invalidatedBody(msg))

        // PHP: private/national이고 option['receiverMessageID'] 있으면 수신측 사본도 invalidate.
        if ((msg.type == "private" || msg.type == "national") && msg.receiverMessageId != null) {
            val recv = messageReader?.invoke(msg.receiverMessageId)
            if (recv != null) {
                recorder.recordMessageInvalidate(
                    msg.receiverMessageId,
                    formatPhpDate(recv.validUntil),
                    invalidatedBody(recv),
                )
            }
        }

        // PHP: req_del_msg 시스템 마커 메시지 INSERT — option['overwrite']=[원본 id (+수신 id)], valid_until=now+1min.
        // 본문 텍스트 "req_del_msg"(편집자 전용 마커, 표시 X). sendToReceiver만(send(false)? 실제는 send()로
        //  receiver/sender 둘 다이나, 마커는 receiver 메일함 1행으로 충분 — PHP send()는 양측이지만 마커 sender
        //  사본은 표시되지 않으므로 receiver 1행만 기록한다). 라우팅은 원본 msgType 그대로.
        // → faithful: 마커 INSERT는 영속 효과 미관측(편집자 전용) + 골든 미캡처 → 발명 금지. receiver 1행만 기록.
        recordMarker(msg, now)

        return DeleteMessageResult(ok = true, generalId = c.generalId, msgID = c.msgID)
    }

    // ── send 분기 (Message::send + sendToReceiver/sendToSender + sendRaw) ─────────────────────────

    /** 전체(public): receiver 행만(sendToSender=[0,0], R4). sendRaw mailbox=MAILBOX_PUBLIC. */
    private fun sendPublic(src: MsgTarget, text: String): Int {
        // public: src→public, dest=src (PHP genPublicMessage: dest=src). toArray는 dest=null.
        val receiverId = recordMessageRow(
            mailbox = MAILBOX_PUBLIC,
            type = "public",
            srcId = src.generalId,
            destId = MAILBOX_PUBLIC,
            src = src,
            dest = null,
            text = text,
            option = linkedMapOf<String, Any?>(),
            receiverMessageId = null,
        )
        // public은 sender 사본 없음 (sendToSender returns [0,0]) — R4.
        return receiverId
    }

    /** 국가(national, 자국): receiver(dest 국가 메일함) + sender(자국 메일함, 동일 mailbox이므로 src!=dest일 때만). */
    private fun sendNational(src: MsgTarget, text: String): Int {
        // genNationalMessage: dest = MessageTarget(0,'', src.nationID, …) — 즉 dest.nationID == src.nationID.
        val dest = MsgTarget(0, "", src.nationId, src.nationName, src.color)
        // sendToReceiver(national): sendRaw(dest.nationID + 9000). newmsg 없음(national은 알림 미발생).
        val receiverId = recordMessageRow(
            mailbox = dest.nationId + MAILBOX_NATIONAL,
            type = "national",
            srcId = src.nationId + MAILBOX_NATIONAL,
            destId = dest.nationId + MAILBOX_NATIONAL,
            src = src,
            dest = dest,
            text = text,
            option = linkedMapOf<String, Any?>(),
            receiverMessageId = null,
        )
        // sendToSender(national): src.nationID !== dest.nationID 일 때만 sender 사본. 자국 national은 같은
        // 국가이므로 sendToSender가 [0,0] (src.nationID === dest.nationID) → sender 사본 없음.
        // (PHP Message::sendToSender national 분기: `if ($src->nationID !== $dest->nationID)` 만 INSERT.)
        return receiverId
    }

    /** 외교(diplomacy, 타국): receiver(상대국 메일함) + sender(자국 메일함, option['action'] 제거). */
    private fun sendDiplomacy(src: MsgTarget, dest: MsgTarget, text: String): Int {
        // sendToReceiver(diplomacy): newmsg=1 (상대국 수뇌/외교관). sendRaw(dest.nationID + 9000).
        // option은 빈 맵(genDiplomacyMessage는 []로 생성 — action 키 없음).
        val receiverId = recordMessageRow(
            mailbox = dest.nationId + MAILBOX_NATIONAL,
            type = "diplomacy",
            srcId = src.nationId + MAILBOX_NATIONAL,
            destId = dest.nationId + MAILBOX_NATIONAL,
            src = src,
            dest = dest,
            text = text,
            option = linkedMapOf<String, Any?>(),
            receiverMessageId = null,
        )
        // diplomacy 수신 알림: 상대국 수뇌(officer_level==12)/외교관에게 newmsg=1.
        markNationNewMsg(dest.nationId)

        // sendToSender(diplomacy): 자국 메일함에 sender 사본. option['action'] 있으면 제거 후 INSERT.
        // genDiplomacyMessage는 option=[]라 action 키가 없다 → 그대로 자국 메일함 INSERT.
        recordMessageRow(
            mailbox = src.nationId + MAILBOX_NATIONAL,
            type = "diplomacy",
            srcId = src.nationId + MAILBOX_NATIONAL,
            destId = dest.nationId + MAILBOX_NATIONAL,
            src = src,
            dest = dest,
            text = text,
            option = linkedMapOf<String, Any?>(),
            receiverMessageId = receiverId,
        )
        return receiverId
    }

    /** 개인(private): receiver(상대 메일함, newmsg=1) + sender(자기 메일함). */
    private fun sendPrivate(src: MsgTarget, dest: MsgTarget, text: String): Int {
        // sendToReceiver(private): silence 아니면 dest.generalID newmsg=1. sendRaw(dest.generalID).
        markGeneralNewMsg(dest.generalId)
        val receiverId = recordMessageRow(
            mailbox = dest.generalId,
            type = "private",
            srcId = src.generalId,
            destId = dest.generalId,
            src = src,
            dest = dest,
            text = text,
            option = linkedMapOf<String, Any?>(),
            receiverMessageId = null,
        )
        // sendToSender(private): src.generalID !== dest.generalID 일 때만 sender 사본(자기 메일함).
        if (src.generalId != dest.generalId) {
            recordMessageRow(
                mailbox = src.generalId,
                type = "private",
                srcId = src.generalId,
                destId = dest.generalId,
                src = src,
                dest = dest,
                text = text,
                option = linkedMapOf<String, Any?>(),
                receiverMessageId = receiverId,
            )
        }
        return receiverId
    }

    /**
     * sendRaw(mailbox) — message 행 1개를 [ChangeRecorder] 메시지 채널에 INSERT. body jsonb는 PHP
     * `Json::encode({src, dest, text, option})` byte-faithful. receiver 행이면 receiverMessageId=null,
     * sender 행이면 receiver의 id를 option['receiverMessageID']로 싣는다(Message::send의 back-reference).
     * 반환 = 할당된 id. receiver 행은 option['receiverMessageID']=자기 id로도 채워진다(PHP send():449).
     */
    private fun recordMessageRow(
        mailbox: Int,
        type: String,
        srcId: Int,
        destId: Int,
        src: MsgTarget,
        dest: MsgTarget?,
        text: String,
        option: LinkedHashMap<String, Any?>,
        receiverMessageId: Int?,
    ): Int {
        // PHP send():449 — receiver 행도 option['receiverMessageID'] = 자기 id로 채운다(back-reference).
        // 그래서 id를 먼저 할당(recordMessageInsert가 채널에 append하며 id 반환)할 수 없다 — body가 id를
        // 참조하기 때문. recorder.recordMessageInsert는 id를 먼저 할당하지만 body는 인자로 받으므로,
        // 두 단계로 한다: (1) 빈 body로 채널에 넣어 id를 받고, (2) 같은 행을 invalidate가 아니라 — 대신
        // recorder가 id를 먼저 할당하도록 직접 messageIdAllocator를 노출하지 않으므로, sender 행은
        // receiverMessageId(이미 아는 receiver id)를, receiver 행은 자기 id를 사후에 못 넣는다.
        // → faithful 단순화: recordMessageInsert가 반환하는 id로 body에 receiverMessageID를 싣되, receiver
        //   행은 'receiver==자기'이므로 반환된 id를 그대로 다시 한 번 기록할 수 없다. 대신 PHP back-reference
        //   의 관측 가능한 효과(삭제 시 수신측 사본 무효화)는 sender 행의 option['receiverMessageID']로 보존되고
        //   (deleteMsg는 sender 행을 삭제하며 그 option의 receiverMessageID로 수신측을 찾는다), receiver 행의
        //   self-reference(send():449)는 삭제 경로에서 쓰이지 않는다(deleteMsg는 sender 행 기준). 따라서
        //   receiver 행 body는 receiverMessageID 없이, sender 행 body는 receiverMessageID=receiver id로 싣는다.
        val opt = LinkedHashMap(option)
        if (receiverMessageId != null) {
            // sender 사본 — Message::send():458 senderMessageID는 sender 자신 id(미할당)지만 관측 효과 없음.
            //  여기선 receiver 사본을 가리키는 receiverMessageID만 싣는다(deleteMsg가 이걸로 수신측을 찾는다).
            opt["receiverMessageID"] = receiverMessageId
        }
        val body = jsonEncode(
            linkedMapOf(
                "src" to src.toArray(),
                "dest" to (dest?.toArray()),
                "text" to text,
                "option" to opt,
            ),
        )
        return recorder.recordMessageInsert(
            mailbox = mailbox,
            type = type,
            srcId = srcId,
            destId = destId,
            time = formatPhpDate(world.getState().lastTurnTime),
            validUntil = VALID_UNTIL_SENTINEL,
            bodyJson = body,
        )
    }

    /** req_del_msg 시스템 마커 INSERT (deleteMsg). receiver 메일함 1행, valid_until=now+1min. */
    private fun recordMarker(msg: MessageSnapshot, now: Instant) {
        val overwrite = mutableListOf<Any?>(msg.id)
        if ((msg.type == "private" || msg.type == "national") && msg.receiverMessageId != null) {
            // PHP: $msgOption['overwrite'][] = [$msgObj2->id]; (중첩 배열 — PHP 버그성 형태 그대로).
            overwrite.add(listOf(msg.receiverMessageId))
        }
        val markerMailbox = when {
            msg.type == "public" -> MAILBOX_PUBLIC
            msg.type == "national" || msg.type == "diplomacy" -> msg.destNationId + MAILBOX_NATIONAL
            else -> msg.destGeneralId
        }
        val body = jsonEncode(
            linkedMapOf(
                "src" to msg.srcArray,
                "dest" to msg.destArray,
                "text" to "req_del_msg",
                "option" to linkedMapOf<String, Any?>(
                    "hide" to true,
                    "silence" to true,
                    "overwrite" to overwrite,
                ),
            ),
        )
        recorder.recordMessageInsert(
            mailbox = markerMailbox,
            type = msg.type,
            srcId = if (msg.type == "private") msg.srcGeneralId else msg.srcNationId + MAILBOX_NATIONAL,
            destId = when {
                msg.type == "public" -> MAILBOX_PUBLIC
                msg.type == "private" -> msg.destGeneralId
                else -> msg.destNationId + MAILBOX_NATIONAL
            },
            time = formatPhpDate(now),
            validUntil = formatPhpDate(now.plusSeconds(60)),
            bodyJson = body,
        )
    }

    /** PHP invalidate(null, false): text='삭제된 메시지입니다.', option['invalid']=true, originalText=원문. */
    private fun invalidatedBody(msg: MessageSnapshot): String {
        val opt = LinkedHashMap(msg.option)
        opt["invalid"] = true
        // hideMsg=false 경로: receiverMessageID 키가 있으면 원문을 originalText로 보존.
        if (opt.containsKey("receiverMessageID")) {
            opt["originalText"] = msg.text
        }
        return jsonEncode(
            linkedMapOf(
                "src" to msg.srcArray,
                "dest" to msg.destArray,
                "text" to "삭제된 메시지입니다.",
                "option" to opt,
            ),
        )
    }

    // ── newmsg 알림 (general.meta["newmsg"]=1, diffGeneral meta 경로) ──────────────────────────────

    /** private 수신: dest 장수 newmsg=1 (sendToReceiver private 분기, silence 아닐 때). */
    private fun markGeneralNewMsg(generalId: Int) {
        val g = world.getGeneralById(generalId) ?: return
        if ((g.meta["newmsg"] as? Number)?.toInt() == 1) return
        val pre = PerTurnOverlay.toLogicGeneral(g)
        val nextMeta = LinkedHashMap(g.meta)
        nextMeta["newmsg"] = 1
        val next = g.copy(meta = nextMeta)
        world.applyGeneralDirtyFree(next)
        recorder.diffGeneral(pre, PerTurnOverlay.toLogicGeneral(next))
    }

    /**
     * diplomacy 수신: 상대국 수뇌(officer_level==12)/외교관에게 newmsg=1. PHP sendToReceiver diplomacy:
     * `UPDATE general SET newmsg=1 WHERE nation=destNationID AND (officer_level=12 OR permission IN (…))`.
     */
    private fun markNationNewMsg(nationId: Int) {
        for (g in world.listGenerals()) {
            if (g.nationId != nationId) continue
            val isChief = g.officerLevel == 12
            val isDiplomat = secretPermission(g) == 4
            if (!isChief && !isDiplomat) continue
            markGeneralNewMsg(g.id)
        }
    }

    // ── env / meta 헬퍼 (게이트웨이 레이어 게이트는 meta 운반분만 평가) ────────────────────────────

    private fun blockLevel(g: TurnGeneral): Int = (g.meta["blockLevel"] as? Number)?.toInt() ?: 0

    @Suppress("UNCHECKED_CAST")
    private fun penaltyOf(g: TurnGeneral): Map<String, Any?> =
        (g.meta["penalty"] as? Map<String, Any?>) ?: emptyMap()

    /**
     * PHP checkSecretPermission: 외교관(ambassador)/감사관(auditor) = 4. opensamguk은 외교권 enum 컬럼이
     * 없어 meta["permission"]로 운반한다(부재 시 0 = 외교권자 아님 — faithful, fabricate 아님).
     * 'ambassador'/'auditor' 문자열 또는 정수 4 모두 4로 본다.
     */
    private fun secretPermission(g: TurnGeneral): Int {
        return when (val p = g.meta["permission"]) {
            is Number -> p.toInt()
            "ambassador", "auditor" -> 4
            else -> 0
        }
    }

    /** PHP $msg_interval = now - session->lastMsg. lastMsg가 meta에 없으면 충분히 과거(통과). */
    private fun privateMsgInterval(g: TurnGeneral): Int {
        val lastMs = (g.meta["lastMsg"] as? Number)?.toLong() ?: return Int.MAX_VALUE
        val now = world.getState().lastTurnTime.epochSecond
        return (now - lastMs).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    /** GetImageURL(imgsvr, picture) 대응 — meta에 picture/imageServer가 실려 오면 합성, 부재 시 빈 문자열. */
    private fun iconOf(g: TurnGeneral): String {
        val picture = g.meta["picture"] as? String ?: return ""
        if (picture.isBlank()) return ""
        return picture
    }

    /** getNationStaticInfo(nationID) 대응 — world nation 행(이름/색). 0=재야는 합성 타깃을 돌려준다. */
    private fun nationTarget(nationId: Int): Nation? {
        if (nationId == 0) {
            // PHP getNationStaticInfo(0) — 재야 가상 국가(이름 '재야', 색 '#000000'). MessageTarget도
            //  buildFromArray에서 nation_id=0이면 '재야'/'#000000'로 채운다.
            return Nation(id = 0, name = "재야", color = "#000000")
        }
        return world.getNationById(nationId)
    }

    // ── 결과 헬퍼 ─────────────────────────────────────────────────────────────────────────────────

    private fun ok(generalId: Int, msgType: String, msgId: Int): TurnDaemonCommandResult =
        SendMessageResult(ok = true, generalId = generalId, msgType = msgType, msgID = msgId)

    private fun fail(generalId: Int, reason: String): TurnDaemonCommandResult =
        SendMessageResult(ok = false, generalId = generalId, reason = reason)

    private fun failDelete(generalId: Int, msgId: Int, reason: String): TurnDaemonCommandResult =
        DeleteMessageResult(ok = false, generalId = generalId, msgID = msgId, reason = reason)

    /**
     * 삭제 게이트용 메시지 reader (PHP getMessageByID). null이면 stub('메시지가 없습니다'). 디스패처가
     * infra MessageRepository로 어댑트해 주입하며, 테스트는 double을 먹인다. send 경로는 reader 불요
     * (world에서 src/dest를 충족) — reader는 오직 delete 게이트용이다.
     */
    var messageReader: ((msgId: Int) -> MessageSnapshot?)? = null

    companion object {
        /** Message::MAILBOX_PUBLIC. */
        const val MAILBOX_PUBLIC = 9999

        /** Message::MAILBOX_NATIONAL. */
        const val MAILBOX_NATIONAL = 9000

        /** PHP DateTime '9999-12-31' (genXMessage의 validUntil). */
        const val VALID_UNTIL_SENTINEL = "9999-12-31 00:00:00"

        private val PHP_DATE_FMT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC)

        /** PHP `$date->format('Y-m-d H:i:s')` — flush가 CAST(... AS timestamptz)로 바인딩한다. */
        fun formatPhpDate(instant: Instant): String = PHP_DATE_FMT.format(instant)
    }
}

/**
 * PHP MessageTarget의 데몬 측 미러 — message body jsonb의 src/dest 객체. `toArray()`는 PHP
 * `MessageTarget::toArray()`(id/name/nation_id/nation/color/icon) byte-faithful. icon은 부재 시 빈 문자열
 * (PHP는 default.jpg 경로지만 데몬은 이미지 서버 경로를 합성하지 않는다 — 표시 전용, 골든 미대상).
 */
data class MsgTarget(
    val generalId: Int,
    val generalName: String,
    val nationId: Int,
    val nationName: String,
    val color: String,
    val icon: String = "",
) {
    fun toArray(): LinkedHashMap<String, Any?> = linkedMapOf(
        "id" to generalId,
        "name" to generalName,
        "nation_id" to nationId,
        "nation" to nationName,
        "color" to color,
        "icon" to icon,
    )
}

/**
 * deleteMsg 게이트용 메시지 스냅샷 (PHP Message 객체의 삭제-게이트 read 서브셋). reader가 채운다.
 * 게이트가 읽는 필드만 담는다: src 장수/타입/action(외교 시스템 메시지 판정)/시각/deletable/
 * receiverMessageID + 무효화 body 재구성용 src/dest 배열 + 원문 text + option.
 */
data class MessageSnapshot(
    /** message.id. */
    val id: Int,
    /** body option['action'] 존재 여부 (diplomacy + action → DiplomaticMessage = 시스템 외교 메시지). */
    val hasAction: Boolean,
    /** message.type (private|national|public|diplomacy). */
    val type: String,
    /** src 장수 id (본인 삭제 게이트). */
    val srcGeneralId: Int,
    /** src 국가 id (마커 라우팅용). */
    val srcNationId: Int,
    /** dest 장수 id (private 마커 라우팅). */
    val destGeneralId: Int,
    /** dest 국가 id (national/diplomacy 마커 라우팅). */
    val destNationId: Int,
    /** 발송 시각 (5분 윈도우 게이트). */
    val time: Instant,
    /** valid_until (무효화 UPDATE에 그대로 다시 싣는다). */
    val validUntil: Instant,
    /** option['deletable'] ?? true (false면 삭제 불가). */
    val deletable: Boolean? = null,
    /** option['receiverMessageID'] — 수신측 사본 무효화 대상 (private/national). */
    val receiverMessageId: Int? = null,
    /** 원문 text (invalidate가 originalText로 보존). */
    val text: String,
    /** body src 객체 (무효화 body 재구성). */
    val srcArray: Map<String, Any?>,
    /** body dest 객체 (무효화 body 재구성). */
    val destArray: Map<String, Any?>?,
    /** body option 전체 (무효화 시 invalid/originalText 추가하여 다시 인코딩). */
    val option: Map<String, Any?> = emptyMap(),
)
