package opensamguk.engine.intake

import opensamguk.common.wire.DiploLetterResult
import opensamguk.common.wire.GeneralBoolResult
import opensamguk.common.wire.MakeGeneralFail
import opensamguk.common.wire.TurnDaemonCommand
import opensamguk.common.wire.TurnDaemonCommandEnvelope
import opensamguk.common.wire.WireJson
import opensamguk.engine.run.TurnDaemonCommandDispatcher
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.GeneralStats
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.Nation
import opensamguk.engine.turn.TurnGeneral
import opensamguk.engine.turn.TurnWorldState
import opensamguk.engine.turn.WorldSnapshot
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

/**
 * W0-7 dispatcher deny 스텁 검증 — wire 계약은 widen됐지만 엔진 핸들러가 아직 없는 변형이
 * **silent-drop(`else -> null`)이 아니라 명시적 deny 결과**를 남기는지 확인한다.
 *
 *  - [TurnDaemonCommand.DiploRespondLetter] → [DiploLetterResult] ok=false (핸들러는 W1 G)
 *  - [TurnDaemonCommand.Appoint]/[TurnDaemonCommand.Kick]/[TurnDaemonCommand.ChangePermission]
 *    → [GeneralBoolResult] ok=false (핸들러는 W1 N)
 *  - [TurnDaemonCommand.MakeGeneral] 유산/전콘 옵션 게이트 → [MakeGeneralFail] (소비 구현은 W1 K);
 *    옵션 없는 명령은 기존 핸들러 경로 유지(스텁 사유가 아닌 핸들러 사유).
 *
 * deny 사유는 [TurnDaemonCommandDispatcher.UNSUPPORTED_REASON] — PHP 패러티 문자열이 아닌
 * 스텁 마커(W1 핸들러가 PHP deny 문자열로 대체). 페이로드는 publisher가 XADD하는 그대로의
 * envelope 라운드트립을 거쳐 디스패치한다(IntakeCommandConsumeDispatchTest와 동일 시임).
 */
class W07DenyStubDispatchTest {

    private val t0 = Instant.parse("0200-01-01T00:00:00Z")

    private fun world(): InMemoryTurnWorld = InMemoryTurnWorld(
        WorldSnapshot(
            state = TurnWorldState(id = 1, currentYear = 200, currentMonth = 3, tickSeconds = 3600, lastTurnTime = t0),
            generals = listOf(
                TurnGeneral(
                    id = 10, name = "유비", nationId = 1, cityId = 5, troopId = 0,
                    stats = GeneralStats(80, 70, 60), experience = 0, dedication = 0,
                    officerLevel = 12, gold = 1000, turnTime = t0,
                ),
            ),
            nations = listOf(Nation(id = 1, name = "촉", color = "#0f0", gold = 1000)),
        ),
    )

    /** publisher가 encode하는 그대로 → consumer가 decode하는 그대로 (RedisCommandStream과 동일 코덱). */
    private fun decodeAsConsumer(command: TurnDaemonCommand): TurnDaemonCommand {
        val payload = WireJson.encodeToString(
            TurnDaemonCommandEnvelope.serializer(),
            TurnDaemonCommandEnvelope(requestId = "req-1", sentAt = "0200-01-01T00:00:00Z", command = command),
        )
        return WireJson.decodeFromString(TurnDaemonCommandEnvelope.serializer(), payload).command
    }

    /** 스텁 deny 경로는 어떤 repo 메서드도 호출하지 않는다 — no-op 프록시면 충분. */
    private inline fun <reified T> noopRepo(): T = java.lang.reflect.Proxy.newProxyInstance(
        T::class.java.classLoader,
        arrayOf(T::class.java),
    ) { _, method, _ ->
        when (method.returnType) {
            java.util.List::class.java -> emptyList<Any>()
            java.lang.Boolean.TYPE -> false
            else -> null
        }
    } as T

    private fun dispatcher(world: InMemoryTurnWorld, recorder: ChangeRecorder) = TurnDaemonCommandDispatcher(
        world, recorder,
        noopRepo<opensamguk.infra.read.AuctionRepository>(),
        noopRepo<opensamguk.infra.read.AuctionBidRepository>(),
        noopRepo<opensamguk.infra.read.BoardPostRepository>(),
    )

    @Test
    fun `diploRespondLetter는 silent-drop이 아니라 명시적 deny 결과를 남긴다`() {
        val w = world()
        val d = dispatcher(w, ChangeRecorder())

        val cmd = decodeAsConsumer(
            TurnDaemonCommand.DiploRespondLetter(generalId = 10, letterNo = 7, isAgree = true),
        )
        val result = assertIs<DiploLetterResult>(d.dispatch(cmd))
        assertEquals("diploRespondLetter", result.type)
        assertEquals(false, result.ok)
        assertEquals(10, result.generalId)
        assertEquals(7, result.letterNo)
        assertEquals(TurnDaemonCommandDispatcher.UNSUPPORTED_REASON, result.reason)
    }

    @Test
    fun `appoint-kick-changePermission은 GeneralBoolResult deny를 남긴다`() {
        val w = world()
        val d = dispatcher(w, ChangeRecorder())

        val appoint = assertIs<GeneralBoolResult>(
            d.dispatch(decodeAsConsumer(
                TurnDaemonCommand.Appoint(generalId = 10, destGeneralId = 42, destCityId = 0, officerLevel = 11),
            )),
        )
        assertEquals("appoint", appoint.type)
        assertEquals(false, appoint.ok)
        assertEquals(10, appoint.generalId)
        assertEquals(TurnDaemonCommandDispatcher.UNSUPPORTED_REASON, appoint.reason)

        val kick = assertIs<GeneralBoolResult>(
            d.dispatch(decodeAsConsumer(TurnDaemonCommand.Kick(generalId = 10, destGeneralId = 42))),
        )
        assertEquals("kick", kick.type)
        assertEquals(false, kick.ok)

        val perm = assertIs<GeneralBoolResult>(
            d.dispatch(decodeAsConsumer(
                TurnDaemonCommand.ChangePermission(generalId = 10, isAmbassador = true, targetGeneralIds = listOf(42)),
            )),
        )
        assertEquals("changePermission", perm.type)
        assertEquals(false, perm.ok)
    }

    @Test
    fun `dispatchAll은 deny 스텁 결과를 순서대로 모두 반환한다 - 유실 없음`() {
        val w = world()
        val d = dispatcher(w, ChangeRecorder())

        val results = d.dispatchAll(listOf(
            decodeAsConsumer(TurnDaemonCommand.Appoint(generalId = 10, destGeneralId = 42, destCityId = 15, officerLevel = 4)),
            decodeAsConsumer(TurnDaemonCommand.Kick(generalId = 10, destGeneralId = 42)),
            decodeAsConsumer(TurnDaemonCommand.DiploRespondLetter(generalId = 10, letterNo = 3)),
        ))
        assertEquals(3, results.size)
        assertEquals(listOf("appoint", "kick", "diploRespondLetter"), results.map { it.type })
        assertEquals(listOf(false, false, false), results.map { it.ok })
    }

    @Test
    fun `MakeGeneral 유산-전콘 옵션은 핸들러 도달 전에 명시적 deny된다`() {
        val w = world()
        val d = dispatcher(w, ChangeRecorder())
        val before = w.listGenerals().size

        // 유산 옵션 4종 각각 + imgsvr — 전부 deny (포인트 미차감 silent 발산 차단).
        val variants = listOf(
            TurnDaemonCommand.MakeGeneral(userId = 3, name = "조운", leadership = 80, strength = 75, intel = 65, inheritSpecial = "che_귀병"),
            TurnDaemonCommand.MakeGeneral(userId = 3, name = "조운", leadership = 80, strength = 75, intel = 65, inheritTurntimeZone = 30),
            TurnDaemonCommand.MakeGeneral(userId = 3, name = "조운", leadership = 80, strength = 75, intel = 65, inheritCity = 15),
            TurnDaemonCommand.MakeGeneral(userId = 3, name = "조운", leadership = 80, strength = 75, intel = 65, inheritBonusStat = listOf(3, 2, 1)),
            TurnDaemonCommand.MakeGeneral(userId = 3, name = "조운", leadership = 80, strength = 75, intel = 65, picture = "1.jpg", imgsvr = 1),
        )
        for (v in variants) {
            val fail = assertIs<MakeGeneralFail>(d.dispatch(decodeAsConsumer(v)))
            assertEquals(TurnDaemonCommandDispatcher.UNSUPPORTED_REASON, fail.reason)
        }
        // deny는 world를 변이하지 않는다 — 장수 수 불변.
        assertEquals(before, w.listGenerals().size)
    }

    @Test
    fun `옵션 없는 MakeGeneral은 기존 핸들러 경로로 흐른다 - 스텁 사유가 아니다`() {
        val w = world() // 도시 없는 월드 → 핸들러 자체 게이트('공백지가 없습니다.')가 응답한다.
        val d = dispatcher(w, ChangeRecorder())

        val plain = decodeAsConsumer(
            TurnDaemonCommand.MakeGeneral(userId = 3, name = "조운", leadership = 80, strength = 75, intel = 65),
        )
        val fail = assertIs<MakeGeneralFail>(d.dispatch(plain))
        // 핸들러에 도달했다는 증거: deny 사유가 W0-7 스텁 마커가 아니라 핸들러의 도시-풀 게이트.
        assertNotEquals(TurnDaemonCommandDispatcher.UNSUPPORTED_REASON, fail.reason)
        assertEquals("공백지가 없습니다.", fail.reason)
    }
}
