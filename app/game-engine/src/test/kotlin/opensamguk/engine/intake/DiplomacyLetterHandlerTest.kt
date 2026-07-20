package opensamguk.engine.intake

import opensamguk.common.josa.JosaUtil
import opensamguk.common.wire.DiploLetterResult
import opensamguk.common.wire.TurnDaemonCommand
import opensamguk.engine.flush.DatabaseHooks
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.GeneralRole
import opensamguk.engine.turn.GeneralStats
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.Nation
import opensamguk.engine.turn.TurnGeneral
import opensamguk.engine.turn.TurnWorldState
import opensamguk.engine.turn.WorldSnapshot
import opensamguk.infra.read.DiplomacyLetterReadRow
import opensamguk.infra.read.DiplomacyLetterRepository
import opensamguk.logic.util.jsonDecode
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.lang.reflect.Proxy
import java.time.Instant
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * W5d 외교 서신 풀-사이클 테스트 — diplomacy_letter intake 명령(발송/회수/파기).
 *
 * 각 테스트는 스펙이 강제하는 데몬 경로를 실행한다:
 *   command → [DiplomacyLetterHandler] (검증 + 수뇌부 게이트 + 서신 read seam) →
 *   [ChangeRecorder] diplomacy_letter INSERT/UPDATE + message INSERT 채널 →
 *   [DatabaseHooks.toFlushPayload] → flush payload 검증.
 *
 * 결정론적(no RNG). 수뇌부 권한은 [opensamguk.logic.actions.intake.SecretPermission.check]로 판정
 * (officer_level 12 → 4). aux jsonb 삽입 순서(src/dest/reason/state_opt)와 메시지 Josa 이(가) byte-parity를
 * 검증한다. read seam은 [DiplomacyLetterRepository]를 in-memory로 override한 fake로 주입한다.
 */
class DiplomacyLetterHandlerTest {

    private val t0 = Instant.parse("0200-01-01T00:00:00Z")

    private fun general(id: Int = 10, nationId: Int = 1, officerLevel: Int = 12, name: String = "유비") = TurnGeneral(
        id = id, name = name, nationId = nationId, cityId = 5, troopId = 0,
        stats = GeneralStats(80, 70, 60), experience = 0, dedication = 0,
        officerLevel = officerLevel, gold = 100, turnTime = t0, role = GeneralRole(),
    )

    private fun world(general: TurnGeneral = general()): InMemoryTurnWorld = InMemoryTurnWorld(
        WorldSnapshot(
            state = TurnWorldState(id = 1, currentYear = 200, currentMonth = 3, tickSeconds = 3600, lastTurnTime = t0),
            generals = listOf(general),
            nations = listOf(
                Nation(id = 1, name = "촉", color = "#00ff00"),
                Nation(id = 2, name = "위", color = "#0000ff"),
            ),
            worldId = opensamguk.common.world.WorldId((TurnWorldState(id = 1, currentYear = 200, currentMonth = 3, tickSeconds = 3600, lastTurnTime = t0)).id),
        ),
    )

    /** flush payload 구성(TurnRunService와 동일). */
    private fun flush(world: InMemoryTurnWorld, recorder: ChangeRecorder) =
        DatabaseHooks.toFlushPayload(world, recorder, world.consumeDirtyState())

    /** jdbc를 절대 건드리지 않는 in-memory [DiplomacyLetterRepository] fake — findLetter/countNewerLetters override. */
    private class FakeRepo(
        private val letters: Map<Int, DiplomacyLetterReadRow> = emptyMap(),
        private val newerCounts: Map<Int, Int> = emptyMap(),
    ) : DiplomacyLetterRepository(noopNamedJdbc()) {
        override fun findLetter(letterNo: Int): DiplomacyLetterReadRow? = letters[letterNo]
        override fun countNewerLetters(prevNo: Int): Int = newerCounts[prevNo] ?: 0

        companion object {
            /** 쿼리되지 않는 NamedParameterJdbcTemplate(부모 생성자용) — Proxy DataSource로 구성. */
            fun noopNamedJdbc(): NamedParameterJdbcTemplate {
                val ds = Proxy.newProxyInstance(
                    DataSource::class.java.classLoader, arrayOf(DataSource::class.java),
                ) { _, _, _ -> null } as DataSource
                return NamedParameterJdbcTemplate(JdbcTemplate(ds))
            }
        }
    }

    private fun handler(
        world: InMemoryTurnWorld,
        recorder: ChangeRecorder,
        repo: DiplomacyLetterRepository? = FakeRepo(),
    ) = DiplomacyLetterHandler(world, recorder, repo)

    private fun letterRow(
        letterNo: Int = 100, src: Int = 1, dest: Int = 2, prevNo: Int? = null,
        state: String = "proposed", stateOpt: String? = null,
        aux: String = "{\"src\":{\"nationName\":\"촉\"},\"dest\":{\"nationName\":\"위\"}}",
    ) = DiplomacyLetterReadRow(
        letterNo = letterNo, srcNationId = src, destNationId = dest, prevNo = prevNo,
        state = state, stateOpt = stateOpt, auxJson = aux,
    )

    // ── send ────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `send a new letter records diplomacy_letter INSERT + diplomacy message and flushes`() {
        val world = world()
        val recorder = ChangeRecorder()
        val res = handler(world, recorder).handleSend(
            TurnDaemonCommand.DiploSendLetter(
                generalId = 10, destNationId = 2, prevLetterNo = null,
                textBrief = " 동맹 제의 ", textDetail = " 함께 합시다 ",
            ),
        )

        assertTrue((res as DiploLetterResult).ok)
        val inserts = recorder.diplomacyLetterInserts()
        assertEquals(1, inserts.size)
        val c = inserts.single().columns
        assertEquals(1, c["src_nation_id"])
        assertEquals(2, c["dest_nation_id"])
        assertNull(c["prev_id"])
        assertEquals("PROPOSED", c["state"]) // enum 대문자
        assertEquals("동맹 제의", c["text_brief"]) // trim
        assertEquals("함께 합시다", c["text_detail"])
        assertEquals(10, c["src_signer"])
        assertNull(c["dest_signer"])
        // letterNo echo == 할당된 id.
        assertEquals(inserts.single().allocatedId, res.letterNo)

        // aux 삽입 순서: src 먼저, dest 나중 (LinkedHashMap byte order).
        val aux = jsonDecode(c["aux"] as String)
        assertEquals(listOf("src", "dest"), aux.keys.toList())
        @Suppress("UNCHECKED_CAST")
        val srcAux = aux["src"] as Map<String, Any?>
        assertEquals(listOf("nationName", "nationColor", "generalName", "generalIcon"), srcAux.keys.toList())
        assertEquals("촉", srcAux["nationName"]); assertEquals("#00ff00", srcAux["nationColor"])
        assertEquals("유비", srcAux["generalName"])
        @Suppress("UNCHECKED_CAST")
        val destAux = aux["dest"] as Map<String, Any?>
        assertEquals(listOf("nationName", "nationColor"), destAux.keys.toList())
        assertEquals("위", destAux["nationName"])

        // diplomacy 메시지 2행(receiver+sender) — 양측 발송.
        assertEquals(2, recorder.createdMessages().size)
        assertTrue(recorder.createdMessages().all { it.type == "diplomacy" })

        // 풀-사이클: recorder 채널이 flush payload로 수렴.
        val payload = flush(world, recorder)
        assertEquals(1, payload.diplomacyLetterInserts.size)
        assertEquals(2, payload.createdMessages.size)
    }

    @Test
    fun `send message text uses Josa 이 가 and embeds the new letterNo`() {
        // letterNo=1 → '1'은 받침 있음(7,8,1 → rieul/jong) → JosaUtil.pick(1,'이')='이'.
        val world = world()
        val recorder = ChangeRecorder()
        val res = handler(world, recorder).handleSend(
            TurnDaemonCommand.DiploSendLetter(generalId = 10, destNationId = 2, textBrief = "x", textDetail = "y"),
        ) as DiploLetterResult
        val newNo = res.letterNo!!
        val body = jsonDecode(recorder.createdMessages().first().bodyJson)
        val text = body["text"] as String
        // PHP: "새로운 외교 문서 #{n}{josa} 준비되었습니다. 외교부에서 확인해주세요."
        val expectedJosa = if (newNo % 10 in setOf(0, 1, 3, 6, 7, 8)) "이" else "가"
        assertEquals("새로운 외교 문서 #$newNo$expectedJosa 준비되었습니다. 외교부에서 확인해주세요.", text)
    }

    @Test
    fun `send with a prevNo (proposed) replaces the previous letter and embeds the prevNo in the text`() {
        val world = world()
        val recorder = ChangeRecorder()
        val prev = letterRow(letterNo = 50, src = 1, dest = 2, state = "proposed")
        val res = handler(world, recorder, FakeRepo(letters = mapOf(50 to prev))).handleSend(
            TurnDaemonCommand.DiploSendLetter(generalId = 10, destNationId = 2, prevLetterNo = 50, textBrief = "갱신", textDetail = "d"),
        ) as DiploLetterResult
        assertTrue(res.ok)

        // prev #50 → replaced + aux 끝에 reason append (src/dest 보존, reason 마지막).
        val updates = recorder.diplomacyLetterUpdates()
        assertTrue(updates.containsKey(50))
        assertEquals("REPLACED", updates.getValue(50)["state"])
        val prevAux = jsonDecode(updates.getValue(50)["aux"] as String)
        assertEquals(listOf("src", "dest", "reason"), prevAux.keys.toList())
        @Suppress("UNCHECKED_CAST")
        val reason = prevAux["reason"] as Map<String, Any?>
        assertEquals(listOf("who", "action", "reason"), reason.keys.toList())
        assertEquals(10, reason["who"]); assertEquals("new_letter", reason["action"]); assertEquals("new_letter", reason["reason"])

        // 새 서신은 prev_id=50으로 INSERT, 메시지 텍스트는 '문서 #50의 …'.
        assertEquals(50, recorder.diplomacyLetterInserts().single().columns["prev_id"])
        val text = jsonDecode(recorder.createdMessages().first().bodyJson)["text"] as String
        assertTrue(text.startsWith("문서 #50의 새로운 외교 문서 #${res.letterNo}"))
    }

    @Test
    fun `send denies sending to own nation`() {
        val world = world()
        val res = handler(world, ChangeRecorder()).handleSend(
            TurnDaemonCommand.DiploSendLetter(generalId = 10, destNationId = 1, textBrief = "x", textDetail = "y"),
        )
        assertEquals("자국으로 보낼 수 없습니다.", (res as DiploLetterResult).reason)
    }

    @Test
    fun `send denies a blank brief after trim`() {
        val world = world()
        val res = handler(world, ChangeRecorder()).handleSend(
            TurnDaemonCommand.DiploSendLetter(generalId = 10, destNationId = 2, textBrief = "   ", textDetail = "y"),
        )
        assertEquals("요약문이 비어있습니다", (res as DiploLetterResult).reason)
    }

    @Test
    fun `send denies a non-chief (permission below 4)`() {
        val world = world(general(officerLevel = 2)) // permission 1 < 4
        val recorder = ChangeRecorder()
        val res = handler(world, recorder).handleSend(
            TurnDaemonCommand.DiploSendLetter(generalId = 10, destNationId = 2, textBrief = "x", textDetail = "y"),
        )
        assertEquals("권한이 부족합니다. 수뇌부가 아닙니다.", (res as DiploLetterResult).reason)
        assertTrue(recorder.diplomacyLetterInserts().isEmpty())
    }

    @Test
    fun `send denies when prevNo points to a missing letter`() {
        val world = world()
        val res = handler(world, ChangeRecorder(), FakeRepo(letters = emptyMap())).handleSend(
            TurnDaemonCommand.DiploSendLetter(generalId = 10, destNationId = 2, prevLetterNo = 999, textBrief = "x", textDetail = "y"),
        )
        assertEquals("이전 문서가 없습니다.", (res as DiploLetterResult).reason)
    }

    @Test
    fun `send denies when a newer letter already exists for the prevNo`() {
        val world = world()
        val prev = letterRow(letterNo = 50, src = 1, dest = 2, state = "proposed")
        val res = handler(
            world, ChangeRecorder(),
            FakeRepo(letters = mapOf(50 to prev), newerCounts = mapOf(50 to 1)),
        ).handleSend(
            TurnDaemonCommand.DiploSendLetter(generalId = 10, destNationId = 2, prevLetterNo = 50, textBrief = "x", textDetail = "y"),
        )
        assertEquals("해당 문서에 대한 새로운 문서가 이미 있습니다.", (res as DiploLetterResult).reason)
    }

    @Test
    fun `send denies a prevNo letter that does not involve both nations`() {
        val world = world()
        // prev는 국가 3↔4 사이 서신 — me(국가1)와 dest(국가2) 어느 쪽도 아님.
        val prev = letterRow(letterNo = 50, src = 3, dest = 4, state = "proposed")
        val res = handler(world, ChangeRecorder(), FakeRepo(letters = mapOf(50 to prev))).handleSend(
            TurnDaemonCommand.DiploSendLetter(generalId = 10, destNationId = 2, prevLetterNo = 50, textBrief = "x", textDetail = "y"),
        )
        assertEquals("이전 문서가 없습니다.", (res as DiploLetterResult).reason)
    }

    // ── rollback ──────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `rollback cancels a proposed letter sent by self and sends a message`() {
        val world = world()
        val recorder = ChangeRecorder()
        val letter = letterRow(letterNo = 100, src = 1, dest = 2, state = "proposed")
        val res = handler(world, recorder, FakeRepo(letters = mapOf(100 to letter))).handleRollback(
            TurnDaemonCommand.DiploRollbackLetter(generalId = 10, letterNo = 100),
        )
        assertTrue((res as DiploLetterResult).ok)
        assertEquals(100, res.letterNo)

        val upd = recorder.diplomacyLetterUpdates().getValue(100)
        assertEquals("CANCELLED", upd["state"])
        val aux = jsonDecode(upd["aux"] as String)
        assertEquals(listOf("src", "dest", "reason"), aux.keys.toList())
        @Suppress("UNCHECKED_CAST")
        val reason = aux["reason"] as Map<String, Any?>
        assertEquals("cancelled", reason["action"]); assertEquals("회수", reason["reason"]); assertEquals(10, reason["who"])

        val text = jsonDecode(recorder.createdMessages().first().bodyJson)["text"] as String
        assertEquals("외교 서신(#100)이 회수되었습니다.", text)
    }

    @Test
    fun `rollback denies a non-chief`() {
        val world = world(general(officerLevel = 2))
        val res = handler(world, ChangeRecorder(), FakeRepo(letters = mapOf(100 to letterRow()))).handleRollback(
            TurnDaemonCommand.DiploRollbackLetter(generalId = 10, letterNo = 100),
        )
        assertEquals("권한이 부족합니다. 수뇌부가 아닙니다.", (res as DiploLetterResult).reason)
    }

    @Test
    fun `rollback denies a missing letter`() {
        val world = world()
        val res = handler(world, ChangeRecorder(), FakeRepo(letters = emptyMap())).handleRollback(
            TurnDaemonCommand.DiploRollbackLetter(generalId = 10, letterNo = 100),
        )
        assertEquals("서신이 없습니다.", (res as DiploLetterResult).reason)
    }

    @Test
    fun `rollback denies a letter not in proposed state`() {
        val world = world()
        val res = handler(world, ChangeRecorder(), FakeRepo(letters = mapOf(100 to letterRow(state = "activated")))).handleRollback(
            TurnDaemonCommand.DiploRollbackLetter(generalId = 10, letterNo = 100),
        )
        assertEquals("서신이 없습니다.", (res as DiploLetterResult).reason)
    }

    @Test
    fun `rollback denies a letter not sent by self nation`() {
        val world = world()
        // src=2(상대국)인 서신은 me(국가1)가 회수할 수 없다.
        val res = handler(world, ChangeRecorder(), FakeRepo(letters = mapOf(100 to letterRow(src = 2, dest = 1, state = "proposed")))).handleRollback(
            TurnDaemonCommand.DiploRollbackLetter(generalId = 10, letterNo = 100),
        )
        assertEquals("서신이 없습니다.", (res as DiploLetterResult).reason)
    }

    // ── destroy (two-phase) ─────────────────────────────────────────────────────────────────────

    @Test
    fun `destroy first request sets state_opt and keeps the letter activated`() {
        val world = world()
        val recorder = ChangeRecorder()
        // me=국가1=src, state_opt 미설정 → 1단계.
        val letter = letterRow(letterNo = 100, src = 1, dest = 2, state = "activated", stateOpt = null)
        val res = handler(world, recorder, FakeRepo(letters = mapOf(100 to letter))).handleDestroy(
            TurnDaemonCommand.DiploDestroyLetter(generalId = 10, letterNo = 100),
        )
        assertTrue((res as DiploLetterResult).ok)

        val upd = recorder.diplomacyLetterUpdates().getValue(100)
        // 1단계는 state를 갱신하지 않는다(activated 유지) — aux에 state_opt만 append.
        assertFalse(upd.containsKey("state"))
        val aux = jsonDecode(upd["aux"] as String)
        assertEquals(listOf("src", "dest", "state_opt"), aux.keys.toList())
        assertEquals("try_destroy_src", aux["state_opt"])

        val text = jsonDecode(recorder.createdMessages().first().bodyJson)["text"] as String
        assertEquals("외교 서신(#100)을 파기 요청합니다.", text)
    }

    @Test
    fun `destroy second request (other party already requested) cancels the letter`() {
        val world = world()
        val recorder = ChangeRecorder()
        // src가 이미 try_destroy_src를 신청한 서신을, dest(me=국가2)가 파기 요청 → 2단계 cancelled.
        val me2 = general(id = 10, nationId = 2)
        val world2 = InMemoryTurnWorld(
            WorldSnapshot(
                state = TurnWorldState(id = 1, currentYear = 200, currentMonth = 3, tickSeconds = 3600, lastTurnTime = t0),
                generals = listOf(me2),
                nations = listOf(Nation(id = 1, name = "촉", color = "#00ff00"), Nation(id = 2, name = "위", color = "#0000ff")),
                worldId = opensamguk.common.world.WorldId((TurnWorldState(id = 1, currentYear = 200, currentMonth = 3, tickSeconds = 3600, lastTurnTime = t0)).id),
            ),
        )
        val letter = letterRow(letterNo = 100, src = 1, dest = 2, state = "activated", stateOpt = "try_destroy_src")
        val res = handler(world2, recorder, FakeRepo(letters = mapOf(100 to letter))).handleDestroy(
            TurnDaemonCommand.DiploDestroyLetter(generalId = 10, letterNo = 100),
        )
        assertTrue((res as DiploLetterResult).ok)

        val upd = recorder.diplomacyLetterUpdates().getValue(100)
        assertEquals("CANCELLED", upd["state"])
        // Q-W5d1: prev_no 체인은 cascade하지 않는다 — 단일 행(#100)만 UPDATE.
        assertEquals(setOf(100), recorder.diplomacyLetterUpdates().keys)

        val text = jsonDecode(recorder.createdMessages().first().bodyJson)["text"] as String
        assertEquals("외교 서신(#100)을 파기했습니다.", text)
    }

    @Test
    fun `destroy denies when the caller already requested destruction`() {
        val world = world()
        // me=국가1=src, 이미 try_destroy_src 신청 상태 → 중복 신청 deny.
        val letter = letterRow(letterNo = 100, src = 1, dest = 2, state = "activated", stateOpt = "try_destroy_src")
        val res = handler(world, ChangeRecorder(), FakeRepo(letters = mapOf(100 to letter))).handleDestroy(
            TurnDaemonCommand.DiploDestroyLetter(generalId = 10, letterNo = 100),
        )
        assertEquals("이미 파기 신청을 했습니다.", (res as DiploLetterResult).reason)
    }

    @Test
    fun `destroy denies a letter not in activated state`() {
        val world = world()
        val res = handler(world, ChangeRecorder(), FakeRepo(letters = mapOf(100 to letterRow(state = "proposed")))).handleDestroy(
            TurnDaemonCommand.DiploDestroyLetter(generalId = 10, letterNo = 100),
        )
        assertEquals("서신이 없습니다.", (res as DiploLetterResult).reason)
    }

    @Test
    fun `destroy denies a letter not involving the caller nation`() {
        val world = world()
        val res = handler(world, ChangeRecorder(), FakeRepo(letters = mapOf(100 to letterRow(src = 3, dest = 4, state = "activated")))).handleDestroy(
            TurnDaemonCommand.DiploDestroyLetter(generalId = 10, letterNo = 100),
        )
        assertEquals("서신이 없습니다.", (res as DiploLetterResult).reason)
    }

    @Test
    fun `destroy denies a non-chief`() {
        val world = world(general(officerLevel = 2))
        val res = handler(world, ChangeRecorder(), FakeRepo(letters = mapOf(100 to letterRow(state = "activated")))).handleDestroy(
            TurnDaemonCommand.DiploDestroyLetter(generalId = 10, letterNo = 100),
        )
        assertEquals("권한이 부족합니다. 수뇌부가 아닙니다.", (res as DiploLetterResult).reason)
    }

    // ── Josa(이/가) byte-parity — JosaUtil.pick(int, '이') (PHP `JosaUtil::pick($newLetterNo, '이')`) ──

    @Test
    fun `letterNo Josa 이 가 matches PHP digit-jongsung by last digit`() {
        // PHP getDigitJongsung: 0,3,6 → 받침 O; 1,7,8 → 받침 O(ㄹ); 2,4,5,9 → 받침 X.
        // '이'는 으로가 아니라 has-jongsung만 본다 → 받침 O면 '이', X면 '가'.
        // 메시지 텍스트가 마지막 자리 숫자로 조사를 고른다(JosaUtil은 끝 글자만 본다).
        val hasJong = setOf(0, 1, 3, 6, 7, 8)
        for (n in 1..29) {
            val world = world()
            val recorder = ChangeRecorder()
            // diplomacyLetterIdAllocator가 1부터 단조 증가하므로, 인스턴스를 새로 만들어 letterNo=1을 고정한 뒤
            // 텍스트의 임베드된 letterNo로 직접 검증한다.
            val res = handler(world, recorder).handleSend(
                TurnDaemonCommand.DiploSendLetter(generalId = 10, destNationId = 2, textBrief = "x", textDetail = "y"),
            ) as DiploLetterResult
            val newNo = res.letterNo!!
            val text = jsonDecode(recorder.createdMessages().first().bodyJson)["text"] as String
            val expectedJosa = if (newNo % 10 in hasJong) "이" else "가"
            assertEquals("새로운 외교 문서 #$newNo$expectedJosa 준비되었습니다. 외교부에서 확인해주세요.", text)
            // 새 인스턴스마다 letterNo=1 고정이라 n 루프는 사실상 동일 케이스 반복 — 대신 명시적 케이스로 보강.
        }

        // 다양한 letterNo에 대한 명시적 조사 케이스 (마지막 자리 → 받침 유무 → 이/가).
        assertEquals("이", JosaUtil.pick("1", "이")) // 받침 O
        assertEquals("이", JosaUtil.pick("10", "이")) // 끝 0 → 받침 O
        assertEquals("가", JosaUtil.pick("2", "이")) // 받침 X
        assertEquals("이", JosaUtil.pick("3", "이"))
        assertEquals("가", JosaUtil.pick("5", "이"))
        assertEquals("이", JosaUtil.pick("16", "이")) // 끝 6 → 받침 O
        assertEquals("이", JosaUtil.pick("17", "이")) // 끝 7 → 받침 O
        assertEquals("가", JosaUtil.pick("99", "이")) // 끝 9 → 받침 X
    }
}
