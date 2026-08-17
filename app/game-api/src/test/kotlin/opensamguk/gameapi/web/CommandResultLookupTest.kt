package opensamguk.gameapi.web

import com.fasterxml.jackson.databind.ObjectMapper
import opensamguk.common.world.WorldId
import opensamguk.common.wire.NationSettingResult
import opensamguk.common.wire.PlaceBetFail
import opensamguk.common.wire.CommandLifecycleResult
import opensamguk.common.wire.TurnDaemonCommandResult
import opensamguk.common.wire.TurnDaemonEvent
import opensamguk.common.wire.TurnDaemonEventEnvelope
import opensamguk.common.wire.WireJson
import opensamguk.common.wire.commandResultKey
import opensamguk.gameapi.config.GameApiProcessWorld
import opensamguk.gameapi.owner.GeneralResolver
import opensamguk.gameapi.precheck.CommandPrecheckService
import opensamguk.gameapi.read.GeneralReadRepository
import opensamguk.gameapi.reserve.CommandQueueService
import opensamguk.gameapi.reserve.CommandReserveService
import opensamguk.infra.persistence.CommandInboxRepository
import opensamguk.infra.persistence.CommandResultRepository
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.RequestPostProcessor
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders

/**
 * W0-4 인테이크 결과 회신 채널 — game-api 조회 절반 (컨테이너 없는 단위, CommandControllerSecurityTest 미러).
 *
 * `GET /api/command/result/{requestId}` 계약:
 *  - 키 부재(아직 미처리 or TTL 만료) → 200 `{status:"PENDING", requestId}` — FE는 폴링을 계속한다.
 *  - 키 존재 → 200 `{status:"RESOLVED", requestId, ok, type, reason?, result}` — `result`는 엔진이
 *    발행한 [TurnDaemonCommandResult] JSON 객체 그대로(타입별 부가 필드 보존). deny(ok=false)도
 *    RESOLVED로 돌아온다 — 성공 토스트 위조 금지의 근거 데이터.
 *  - 손상 페이로드 → PENDING (RESOLVED를 위조하지 않는다).
 *
 * 저장 페이로드 픽스처는 손으로 쓰지 않고 엔진과 같은 [WireJson] 인코더로 생성한다 — 인코더/디코더
 * 호환성이 테스트 대상의 일부다. Redis 왕복은 Docker-게이트 CommandControllerIT가 닫는다.
 */
class CommandResultLookupTest {

    private val precheck = mock(CommandPrecheckService::class.java)
    private val reserve = mock(CommandReserveService::class.java)
    private val resolver = mock(GeneralResolver::class.java)
    private val queue = mock(CommandQueueService::class.java)
    private val generals = mock(GeneralReadRepository::class.java)
    private val commandResults = mock(CommandResultRepository::class.java)
    private val redis = mock(StringRedisTemplate::class.java)
    private val commandInbox = mock(CommandInboxRepository::class.java)

    @Suppress("UNCHECKED_CAST")
    private val valueOps = mock(ValueOperations::class.java) as ValueOperations<String, String>

    private val profile = "che:scenario_2"

    private fun mockMvc(): MockMvc = MockMvcBuilders
        .standaloneSetup(
            CommandController(
                precheck, reserve, resolver, queue, generals, commandResults, commandInbox, redis,
                ObjectMapper(), profile, GameApiProcessWorld(1),
            ),
        )
        .setCustomArgumentResolvers(AuthenticationPrincipalArgumentResolver())
        .build()

    /** 엔진 발행과 동일한 인코딩으로 저장 페이로드를 만든다. */
    private fun storedPayload(
        requestId: String,
        result: TurnDaemonCommandResult,
        committedWorldVersion: Long? = null,
    ): String =
        WireJson.encodeToString(
            TurnDaemonEventEnvelope.serializer(),
            TurnDaemonEventEnvelope(
                requestId = requestId,
                sentAt = "0200-01-01T01:00:00Z",
                event = TurnDaemonEvent.CommandResult(result),
                committedWorldVersion = committedWorldVersion,
            ),
        )

    private fun stubKey(requestId: String, payload: String?) {
        `when`(redis.opsForValue()).thenReturn(valueOps)
        `when`(valueOps.get(commandResultKey(profile, WorldId(1), requestId))).thenReturn(payload)
    }

    private fun stubDurable(requestId: String, payload: String?) {
        `when`(commandResults.findResultPayload(WorldId(1), requestId)).thenReturn(payload)
    }

    /** 인증 주체를 심는다 — 컨트롤러의 `@AuthenticationPrincipal userId`가 이 값을 받는다. */
    private fun principal(userId: Long): RequestPostProcessor = RequestPostProcessor { req ->
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(userId, null, listOf(SimpleGrantedAuthority("ROLE_USER")))
        req
    }

    /**
     * OPENSAM-197 — 결과 조회는 제출자만 읽는다. 기존 계약 테스트는 모두 "제출한 본인이 읽는" 경우이므로
     * 인테이크가 남긴 소유자([OWNER_USER_ID])를 함께 세워 두고 그 주체로 읽는다.
     */
    private fun readOwnResult(requestId: String): ResultActions {
        `when`(commandInbox.findRequestOwner(WorldId(1), requestId))
            .thenReturn(CommandInboxRepository.RequestOwner(generalId = 10, ownerUserId = OWNER_USER_ID.toInt()))
        return mockMvc().perform(
            get("/api/command/result/{requestId}", requestId).with(principal(OWNER_USER_ID)),
        )
    }

    @BeforeEach
    fun clearAuthBefore() = SecurityContextHolder.clearContext()

    @AfterEach
    fun clearAuthAfter() = SecurityContextHolder.clearContext()

    @Test
    fun `키 부재면 PENDING으로 응답한다`() {
        stubKey("req-x", null)
        stubDurable("req-x", null)

        readOwnResult("req-x")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andExpect(jsonPath("$.requestId").value("req-x"))
    }

    @Test
    fun `Redis 키 부재 시 durable result fallback으로 RESOLVED 응답한다`() {
        stubKey("req-db", null)
        stubDurable(
            "req-db",
            storedPayload(
                "req-db",
                NationSettingResult(type = "tournamentEnroll", ok = true, generalId = 10, nationId = 1),
                committedWorldVersion = 12,
            ),
        )

        readOwnResult("req-db")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("RESOLVED"))
            .andExpect(jsonPath("$.requestId").value("req-db"))
            .andExpect(jsonPath("$.ok").value(true))
            .andExpect(jsonPath("$.type").value("tournamentEnroll"))
            .andExpect(jsonPath("$.committedWorldVersion").value(12))
            .andExpect(jsonPath("$.result.generalId").value(10))
    }

    @Test
    fun `Redis result includes committedWorldVersion from event envelope`() {
        val payload = storedPayload(
            "req-ryw",
            NationSettingResult(type = "tournamentEnroll", ok = true, generalId = 10, nationId = 1),
            committedWorldVersion = 34,
        )
        stubKey("req-ryw", payload)
        stubDurable("req-ryw", null)

        readOwnResult("req-ryw")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("RESOLVED"))
            .andExpect(jsonPath("$.committedWorldVersion").value(34))
    }

    @Test
    fun `reservation admission is pending until execution has a terminal result`() {
        val accepted = storedPayload(
            "req-admission",
            CommandLifecycleResult(
                type = "reservationAccepted",
                ok = true,
                commandKind = "RESERVED_TURN",
                actionCode = "che_징병",
                generalId = 10,
                turnIdx = 0,
            ),
        )
        stubKey("req-admission", accepted)
        stubDurable("req-admission", accepted)

        readOwnResult("req-admission")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andExpect(jsonPath("$.phase").value("reservationAccepted"))
            .andExpect(jsonPath("$.ok").doesNotExist())
    }

    @Test
    fun `queue mutation remains a resolved admission and is not presented as execution`() {
        val queueMutation = storedPayload(
            "req-queue",
            CommandLifecycleResult(
                type = "queueMutation",
                ok = true,
                commandKind = "QUEUE_MUTATION",
                actionCode = "nationBulk",
                generalId = 10,
            ),
        )
        stubKey("req-queue", queueMutation)
        stubDurable("req-queue", queueMutation)

        readOwnResult("req-queue")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("RESOLVED"))
            .andExpect(jsonPath("$.type").value("queueMutation"))
            .andExpect(jsonPath("$.result.commandKind").value("QUEUE_MUTATION"))
    }

    @Test
    fun `execution result supersedes a stale Redis admission without deleting the durable admission row`() {
        stubKey(
            "req-executed",
            storedPayload(
                "req-executed",
                CommandLifecycleResult(
                    type = "reservationAccepted",
                    ok = true,
                    commandKind = "RESERVED_TURN",
                    actionCode = "che_징병",
                    generalId = 10,
                    turnIdx = 0,
                ),
            ),
        )
        stubDurable(
            "req-executed",
            storedPayload(
                "req-executed",
                CommandLifecycleResult(
                    type = "executionApplied",
                    ok = true,
                    commandKind = "RESERVED_TURN",
                    actionCode = "che_징병",
                    generalId = 10,
                    turnIdx = 0,
                ),
                committedWorldVersion = 35,
            ),
        )

        readOwnResult("req-executed")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("RESOLVED"))
            .andExpect(jsonPath("$.type").value("executionApplied"))
            .andExpect(jsonPath("$.committedWorldVersion").value(35))
    }

    @Test
    fun `성공 결과는 RESOLVED + ok=true + 원본 result 객체로 회신된다`() {
        stubKey(
            "req-a",
            storedPayload(
                "req-a",
                NationSettingResult(type = "tournamentEnroll", ok = true, generalId = 10, nationId = 1),
            ),
        )

        readOwnResult("req-a")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("RESOLVED"))
            .andExpect(jsonPath("$.requestId").value("req-a"))
            .andExpect(jsonPath("$.ok").value(true))
            .andExpect(jsonPath("$.type").value("tournamentEnroll"))
            .andExpect(jsonPath("$.result.generalId").value(10))
            .andExpect(jsonPath("$.result.nationId").value(1))
    }

    @Test
    fun `deny 결과도 RESOLVED + ok=false + 사유로 회신된다`() {
        stubKey(
            "req-b",
            storedPayload(
                "req-b",
                PlaceBetFail(bettingId = 7, reason = "금이 부족합니다."),
            ),
        )

        readOwnResult("req-b")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("RESOLVED"))
            .andExpect(jsonPath("$.ok").value(false))
            .andExpect(jsonPath("$.type").value("placeBet"))
            .andExpect(jsonPath("$.reason").value("금이 부족합니다."))
    }

    @Test
    fun `손상 페이로드는 RESOLVED를 위조하지 않고 PENDING으로 응답한다`() {
        stubKey("req-broken", "not-json{{{")
        stubDurable("req-broken", null)

        readOwnResult("req-broken")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andExpect(jsonPath("$.requestId").value("req-broken"))
    }

    @Test
    fun `Redis 페이로드가 손상되어도 durable result fallback을 시도한다`() {
        stubKey("req-durable-broken-redis", "not-json{{{")
        stubDurable(
            "req-durable-broken-redis",
            storedPayload(
                "req-durable-broken-redis",
                NationSettingResult(type = "tournamentEnroll", ok = true, generalId = 10, nationId = 1),
            ),
        )

        readOwnResult("req-durable-broken-redis")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("RESOLVED"))
            .andExpect(jsonPath("$.ok").value(true))
            .andExpect(jsonPath("$.type").value("tournamentEnroll"))
    }

    // ── OPENSAM-197 소유권 ──────────────────────────────────────────────────────
    //
    // 이 엔드포인트는 경로 값만으로 결과를 돌려줬다. requestId를 아는 사람이 곧 남의 명령 성공 여부·
    // deny 사유·payload를 읽는 사람이었다. 거절은 **미처리와 똑같은 PENDING 본문**으로 답한다 —
    // 엔드포인트 계약이 "항상 200 폴링"이라 404를 쓸 수 없고, 응답이 같아야 존재 여부도 새지 않는다.

    /** 저장된 결과가 실제로 RESOLVED인데도 남에게는 PENDING으로 보여야 한다 — 응답 구분 불가. */
    private fun stubResolvedPayload(requestId: String) {
        stubKey(
            requestId,
            storedPayload(
                requestId,
                NationSettingResult(type = "tournamentEnroll", ok = true, generalId = 10, nationId = 1),
            ),
        )
    }

    @Test
    fun `남의 requestId는 결과가 준비돼 있어도 PENDING과 구분되지 않는다`() {
        stubResolvedPayload("req-other")
        `when`(commandInbox.findRequestOwner(WorldId(1), "req-other"))
            .thenReturn(CommandInboxRepository.RequestOwner(generalId = 10, ownerUserId = OWNER_USER_ID.toInt()))
        `when`(resolver.resolveGeneralId(99L)).thenReturn(42)

        mockMvc().perform(get("/api/command/result/{requestId}", "req-other").with(principal(99L)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andExpect(jsonPath("$.requestId").value("req-other"))
            .andExpect(jsonPath("$.ok").doesNotExist())
            .andExpect(jsonPath("$.result").doesNotExist())
    }

    @Test
    fun `인증이 없으면 결과를 읽지 못한다`() {
        stubResolvedPayload("req-anon")

        mockMvc().perform(get("/api/command/result/{requestId}", "req-anon"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andExpect(jsonPath("$.result").doesNotExist())
    }

    /**
     * 장수선택(selectPoolPick/Update)은 **아직 소유하지 않은** 장수를 대상으로 제출한다. general_id만으로
     * 소유권을 판정하면 제출자 본인이 자기 결과를 못 읽는다 — 그래서 owner_user_id를 따로 남긴다.
     */
    @Test
    fun `아직 소유하지 않은 장수에 낸 명령도 제출 계정이면 읽는다`() {
        stubResolvedPayload("req-pool")
        `when`(commandInbox.findRequestOwner(WorldId(1), "req-pool"))
            .thenReturn(CommandInboxRepository.RequestOwner(generalId = 777, ownerUserId = OWNER_USER_ID.toInt()))
        `when`(resolver.resolveGeneralId(OWNER_USER_ID)).thenReturn(null)

        mockMvc().perform(get("/api/command/result/{requestId}", "req-pool").with(principal(OWNER_USER_ID)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("RESOLVED"))
            .andExpect(jsonPath("$.ok").value(true))
    }

    /**
     * 마이그레이션 이전 행은 owner_user_id가 NULL이다. 그 행은 general_id로만 판정하며, 일치하지 않으면
     * 거절한다(폴링 창은 제출 직후 6초라 실사용 영향이 없다).
     */
    @Test
    fun `마이그레이션 이전 행은 자기 장수의 명령일 때만 읽는다`() {
        stubResolvedPayload("req-legacy")
        `when`(commandInbox.findRequestOwner(WorldId(1), "req-legacy"))
            .thenReturn(CommandInboxRepository.RequestOwner(generalId = 10, ownerUserId = null))
        `when`(resolver.resolveGeneralId(OWNER_USER_ID)).thenReturn(10)

        mockMvc().perform(get("/api/command/result/{requestId}", "req-legacy").with(principal(OWNER_USER_ID)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("RESOLVED"))
    }

    @Test
    fun `인테이크 기록이 없는 requestId는 읽지 못한다`() {
        stubResolvedPayload("req-norow")
        `when`(commandInbox.findRequestOwner(WorldId(1), "req-norow")).thenReturn(null)

        mockMvc().perform(get("/api/command/result/{requestId}", "req-norow").with(principal(OWNER_USER_ID)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("PENDING"))
    }

    private companion object {
        /** 인테이크가 남긴 제출 계정(JWT subject). */
        const val OWNER_USER_ID = 7L
    }
}
