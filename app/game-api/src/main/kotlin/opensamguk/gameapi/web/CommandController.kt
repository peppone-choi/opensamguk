package opensamguk.gameapi.web

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.core.type.TypeReference
import opensamguk.common.wire.CommandLifecycleResult
import opensamguk.common.wire.TurnDaemonCommandResultSerializer
import opensamguk.common.wire.TurnDaemonEvent
import opensamguk.common.wire.TurnDaemonEventEnvelope
import opensamguk.common.wire.WireJson
import opensamguk.common.wire.commandResultKey
import opensamguk.gameapi.config.GameApiProcessWorld
import opensamguk.gameapi.owner.GeneralResolver
import opensamguk.gameapi.precheck.CommandPrecheckService
import opensamguk.gameapi.precheck.PrecheckResult
import opensamguk.gameapi.read.GeneralReadRepository
import opensamguk.gameapi.reserve.CommandQueueService
import opensamguk.gameapi.reserve.CommandReserveService
import opensamguk.gameapi.reserve.CommandWireMapper
import opensamguk.infra.persistence.CommandResultRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * Step 1 + step 2 of the 8-step flow, exposed to the Next.js client.
 *
 * `POST /api/command/{code}?generalId=` runs the E2 precheck (the SHARED `:logic` constraints) and
 * then reserves known general-turn or immediate-intake commands (durable `general_turn` or typed publish):
 *
 *  - `AVAILABLE` → `202 Accepted` carrying the reserve `requestId` (the command is queued; the daemon
 *    will process it on its next run).
 *  - known `BLOCKED`/`UNKNOWN` command → `202 Accepted`; the daemon re-evaluates on the execution turn.
 *  - unknown command code → `200 OK` carrying the unavailable reason.
 *  - `UNKNOWN`   → `200 OK` carrying a generic unavailable reason (a precheck input row was absent).
 *
 * `turnIdx` defaults to `0` (the next reservable slot); the TS `setGeneralTurn` route accepts an
 * explicit `turnIndex` (0..MAX-1) chosen by the caller, so it is an optional query param here.
 *
 * **Task 4 — generalId ownership.** The `?generalId=` param was previously TRUSTED, letting any caller
 * reserve a command on ANY general. When a verified JWT principal is present, the passed `generalId`
 * MUST equal the principal's OWN general (via [GeneralResolver]); a mismatch → `403 Forbidden`. The
 * `?generalId=` value is honored unchanged ONLY when there is no principal (the F2 unauthenticated
 * transition) — once web/game always carries the Bearer, the param becomes purely confirmatory.
 */
@RestController
@RequestMapping("/api/command")
class CommandController(
    private val precheck: CommandPrecheckService,
    private val reserve: CommandReserveService,
    private val resolver: GeneralResolver,
    private val queue: CommandQueueService,
    private val generals: GeneralReadRepository,
    private val commandResults: CommandResultRepository,
    /** W0-4 인테이크 결과 회신 — 엔진이 SET한 [commandResultKey] string 키를 읽는 폴링 read seam. */
    private val redis: StringRedisTemplate,
    /** 엔진 발행 result JSON을 응답 트리로 그대로 옮기는 변환기(타입별 부가 필드 보존). */
    private val objectMapper: ObjectMapper,
    @Value("\${opensamguk.profile:che:scenario_2}") private val profile: String,
    processWorld: GameApiProcessWorld,
) {
    private val worldId = processWorld.worldId

    /** The JSON body of a 202 reserve response. */
    data class ReservedResponse(val status: String, val requestId: String, val turnIdx: Int)

    /** The JSON body of a 200 non-reservable response. */
    data class BlockedResponse(val status: String, val reason: String, val constraintName: String? = null)

    @PostMapping("/{code}")
    fun command(
        @AuthenticationPrincipal userId: Long?,
        @PathVariable code: String,
        @RequestParam generalId: Int,
        @RequestParam(required = false, defaultValue = "0") turnIdx: Int,
        @RequestBody(required = false) argJson: String? = null,
    ): ResponseEntity<Any> {
        val ownerUserId = if (code in SELECT_POOL_COMMANDS) {
            if (userId == null || userId <= 0 || userId > Int.MAX_VALUE.toLong()) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
            }
            userId.toInt()
        } else {
            null
        }
        if (code == SELECT_POOL_PICK) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build()
        }

        // Task 4 — when authenticated, the passed generalId MUST be the caller's own general.
        if (userId != null && generalId != resolver.resolveGeneralId(userId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build()
        }
        if (!isForecastReservable(code)) {
            return blocked("사용할 수 없는 커맨드입니다.")
        }
        val result = if (argJson == null) {
            precheck.precheck(generalId = generalId, actionCode = code)
        } else {
            precheck.precheck(generalId = generalId, actionCode = code, args = precheckArgs(argJson))
        }
        return when (result) {
            PrecheckResult.Available -> reserveAccepted(generalId, code, turnIdx, argJson, ownerUserId)

            is PrecheckResult.Blocked ->
                if (isForecastReservable(code)) {
                    reserveAccepted(generalId, code, turnIdx, argJson, ownerUserId)
                } else {
                    ResponseEntity.ok(
                        BlockedResponse(status = "BLOCKED", reason = result.reason, constraintName = result.constraintName),
                    )
                }

            is PrecheckResult.Unknown ->
                if (isForecastReservable(code)) {
                    reserveAccepted(generalId, code, turnIdx, argJson, ownerUserId)
                } else {
                    ResponseEntity.ok(
                        BlockedResponse(status = "UNKNOWN", reason = "명령을 확인할 수 없습니다."),
                    )
                }
        }
    }

    private fun precheckArgs(argJson: String): Map<String, Any?> =
        runCatching {
            objectMapper.readValue(argJson, object : TypeReference<Map<String, Any?>>() {})
        }.getOrNull() ?: emptyMap()

    private fun reserveAccepted(
        generalId: Int,
        code: String,
        turnIdx: Int,
        argJson: String?,
        ownerUserId: Int? = null,
    ): ResponseEntity<Any> {
        val reserved = if (ownerUserId == null) {
            reserve.reserve(
                generalId = generalId,
                actionCode = code,
                turnIdx = turnIdx,
                argJson = argJson,
            )
        } else {
            reserve.reserveForOwner(
                generalId = generalId,
                actionCode = code,
                turnIdx = turnIdx,
                argJson = argJson,
                ownerUserId = ownerUserId,
            )
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(
            ReservedResponse(status = "AVAILABLE", requestId = reserved.requestId, turnIdx = reserved.turnIdx),
        )
    }

    // ── W0-4 인테이크 결과 회신: GET /api/command/result/{requestId} ───────────────────────────────
    //
    // 응답 규약 (항상 200 — 폴링 채널이므로 404를 쓰지 않는다):
    //   - 키 부재(아직 미처리 or TTL 만료)와 reservation admission → `{status:"PENDING", requestId}`
    //     (admission is additionally marked `phase:"reservationAccepted"`) — FE는 폴링을 계속한다.
    //   - execution terminal → `{status:"RESOLVED", requestId, ok, type, reason?, result}` — `result`는
    //     엔진이 발행한 [opensamguk.common.wire.TurnDaemonCommandResult] JSON 객체 그대로(타입별 부가
    //     필드 보존). deny(ok=false)도 RESOLVED로 돌아온다 — 성공 토스트 위조 금지의 근거 데이터.
    //   - 손상 페이로드 → PENDING (RESOLVED를 위조하지 않는다).

    /** 키 부재/손상 시의 PENDING 폴링 응답. */
    private fun pending(requestId: String, phase: String? = null): ResponseEntity<Any> =
        ResponseEntity.ok(
            linkedMapOf<String, Any?>("status" to "PENDING", "requestId" to requestId).apply {
                phase?.let { this["phase"] = it }
            },
        )

    private data class DecodedCommandResult(
        val envelope: TurnDaemonEventEnvelope,
        val result: opensamguk.common.wire.TurnDaemonCommandResult,
    )

    private fun decodeCommandResult(payload: String?): DecodedCommandResult? {
        if (payload == null) return null
        val envelope = runCatching {
            WireJson.decodeFromString(TurnDaemonEventEnvelope.serializer(), payload)
        }.getOrNull() ?: return null
        val result = (envelope.event as? TurnDaemonEvent.CommandResult)?.result ?: return null
        return DecodedCommandResult(envelope, result)
    }

    private fun isPendingReservation(result: opensamguk.common.wire.TurnDaemonCommandResult?): Boolean =
        result is CommandLifecycleResult &&
            result.commandKind == "RESERVED_TURN" &&
            result.type != "executionApplied" &&
            result.type != "executionRejected"

    @GetMapping("/result/{requestId}")
    fun commandResult(@PathVariable requestId: String): ResponseEntity<Any> {
        val redisResult = decodeCommandResult(redis.opsForValue().get(commandResultKey(profile, worldId, requestId)))
        val durableResult = if (redisResult == null || isPendingReservation(redisResult.result)) {
            decodeCommandResult(commandResults.findResultPayload(worldId, requestId))
        } else {
            null
        }
        val selected = when {
            isPendingReservation(redisResult?.result) && !isPendingReservation(durableResult?.result) -> durableResult
            redisResult != null -> redisResult
            else -> durableResult
        } ?: return pending(requestId)
        if (isPendingReservation(selected.result)) {
            return pending(requestId, phase = selected.result.type)
        }
        val envelope = selected.envelope
        val result = selected.result

        // result를 엔진과 동일한 WireJson 인코딩으로 재방출해 Jackson 트리로 — `type`/`ok`가 항상
        // 포함되고(커스텀 serializer가 encodeDefaults 강제) 타입별 부가 필드가 그대로 보존된다.
        val resultNode = objectMapper.readTree(
            WireJson.encodeToString(TurnDaemonCommandResultSerializer, result),
        )

        val body = linkedMapOf<String, Any?>(
            "status" to "RESOLVED",
            "requestId" to requestId,
            "ok" to result.ok,
            "type" to result.type,
        )
        envelope.committedWorldVersion?.let { body["committedWorldVersion"] = it }
        // reason은 deny류에만 존재 — 있을 때만 톱레벨로 끌어올린다(FE 토스트가 바로 읽는 필드).
        resultNode.get("reason")?.takeIf { !it.isNull }?.let { body["reason"] = it.asText() }
        body["result"] = resultNode
        return ResponseEntity.ok(body)
    }

    // ── W6e Command-queue: bulk / push / repeat (×{general, nation}) ──────────────────────────────
    //
    // REST 전용 — CommandQueueService가 game-api 안에서 general_turn/nation_turn 링을 직접 갱신한다.
    // 데몬 인테이크/precheck 경로를 타지 않는다(예약 큐 조작 자체는 precheck 대상이 아님 — PHP도
    // ReserveBulk/Push/Repeat는 단순 검증 후 setGeneralCommand/링 함수를 직접 호출).
    //
    // 응답 규약(/{code} 와 동일):
    //   - 성공 → 202 Accepted (큐가 갱신됨; bulk 는 briefList 동봉)
    //   - deny(PHP가 문자열 반환) → 200 OK + BlockedResponse(reason=PHP byte-for-byte)
    //   - 소유권 불일치(인증된 principal ≠ 대상 generalId) → 403 Forbidden
    //   - validator 범위 위반(amount 범위 등) → 400 Bad Request (PHP validateArgs 400 대응)

    /** bulk 202 본문 — PHP `ReserveBulkCommand` 성공 반환 배열과 동형. */
    data class BulkAcceptedResponse(
        val status: String = "AVAILABLE",
        val requestId: String,
        val result: Boolean,
        val briefList: List<String>,
        val reason: String,
    )

    /** bulk 200(부분 실패) 본문 — errorIdx + 부분 briefList + 사유. */
    data class BulkBlockedResponse(
        val status: String = "BLOCKED",
        val result: Boolean = false,
        val briefList: List<String>,
        val errorIdx: Int,
        val reason: String,
    )

    /**
     * ReserveBulk(장수) — `POST /api/command/bulk`.
     * Body: `[{ action, turnList, arg? }]`. 첫 실패 원소에서 fast-fail.
     */
    @PostMapping("/bulk")
    fun bulkReserve(
        @AuthenticationPrincipal userId: Long?,
        @RequestParam generalId: Int,
        @RequestBody commandArray: List<Map<String, Any?>>,
    ): ResponseEntity<Any> {
        ownershipGuard(userId, generalId)?.let { return it }
        return runBulk { queue.reserveBulkGeneral(generalId, commandArray.map(::toBulkItem)) }
    }

    /** Push(장수) — `POST /api/command/push`. Body: `{ amount: -12..12, amount != 0 }`. */
    @PostMapping("/push")
    fun push(
        @AuthenticationPrincipal userId: Long?,
        @RequestParam generalId: Int,
        @RequestBody request: Map<String, Any?>,
    ): ResponseEntity<Any> {
        ownershipGuard(userId, generalId)?.let { return it }
        val amount = readAmount(request) ?: return badRequest()
        if (amount < -12 || amount > 12) return badRequest()
        return runQueue { queue.pushGeneral(generalId, amount) }
    }

    /** Repeat(장수) — `POST /api/command/repeat`. Body: `{ amount: 1..12 }`. */
    @PostMapping("/repeat")
    fun repeat(
        @AuthenticationPrincipal userId: Long?,
        @RequestParam generalId: Int,
        @RequestBody request: Map<String, Any?>,
    ): ResponseEntity<Any> {
        ownershipGuard(userId, generalId)?.let { return it }
        val amount = readAmount(request) ?: return badRequest()
        if (amount < 1 || amount > 12) return badRequest()
        return runQueue { queue.repeatGeneral(generalId, amount) }
    }

    /** ReserveBulk(국가) — `POST /api/command/nation/bulk`. officer_level>=5 + nation_id>0 precheck. */
    @PostMapping("/nation/bulk")
    fun nationBulkReserve(
        @AuthenticationPrincipal userId: Long?,
        @RequestParam generalId: Int,
        @RequestBody commandArray: List<Map<String, Any?>>,
    ): ResponseEntity<Any> {
        ownershipGuard(userId, generalId)?.let { return it }
        val chief = chiefStateOrDeny(generalId) ?: return blocked("올바르지 않은 장수입니다.")
        chiefPrecheck(chief)?.let { return blocked(it) }
        return runBulk { queue.reserveBulkNation(generalId, chief.nationId, chief.officerLevel, commandArray.map(::toBulkItem)) }
    }

    /** Push(국가) — `POST /api/command/nation/push`. */
    @PostMapping("/nation/push")
    fun nationPush(
        @AuthenticationPrincipal userId: Long?,
        @RequestParam generalId: Int,
        @RequestBody request: Map<String, Any?>,
    ): ResponseEntity<Any> {
        ownershipGuard(userId, generalId)?.let { return it }
        val amount = readAmount(request) ?: return badRequest()
        if (amount < -12 || amount > 12) return badRequest()
        // PHP 순서: amount==0 게이트가 장수/국가/수뇌 precheck보다 먼저.
        if (amount == 0) return blocked("0은 불가능합니다")
        val chief = chiefStateOrDeny(generalId) ?: return blocked("올바르지 않은 장수입니다.")
        chiefPrecheck(chief)?.let { return blocked(it) }
        return runQueue { queue.pushNation(generalId, chief.nationId, chief.officerLevel, amount) }
    }

    /** Repeat(국가) — `POST /api/command/nation/repeat`. */
    @PostMapping("/nation/repeat")
    fun nationRepeat(
        @AuthenticationPrincipal userId: Long?,
        @RequestParam generalId: Int,
        @RequestBody request: Map<String, Any?>,
    ): ResponseEntity<Any> {
        ownershipGuard(userId, generalId)?.let { return it }
        val amount = readAmount(request) ?: return badRequest()
        if (amount < 1 || amount > 12) return badRequest()
        // PHP `NationCommand/RepeatCommand`: amount==0 게이트 없음(validator min=1), 장수/국가/수뇌 precheck만.
        val chief = chiefStateOrDeny(generalId) ?: return blocked("올바르지 않은 장수입니다.")
        chiefPrecheck(chief)?.let { return blocked(it) }
        return runQueue { queue.repeatNation(generalId, chief.nationId, chief.officerLevel, amount) }
    }

    // ── 헬퍼 ──────────────────────────────────────────────────────────────────────────────────────

    /** 수뇌 precheck 입력(캐시된 1회 읽기 — TOCTOU 회피). */
    private data class ChiefState(val nationId: Int, val officerLevel: Int)

    /**
     * 대상 generalId의 (nation_id, officer_level)를 1회 읽어 캐시한다(PHP `general WHERE no = generalId`).
     * 장수 미존재 → null(`올바르지 않은 장수입니다.`).
     */
    private fun chiefStateOrDeny(generalId: Int): ChiefState? {
        val g = generals.findById(generalId).orElse(null) ?: return null
        return ChiefState(nationId = g.nationId, officerLevel = g.officerLevel)
    }

    /** PHP 수뇌 push/repeat precheck: nation==0 / officer<5. (장수 미존재는 chiefStateOrDeny에서 처리.) */
    private fun chiefPrecheck(chief: ChiefState): String? = when {
        chief.nationId == 0 -> "국가에 소속되어 있지 않습니다."
        chief.officerLevel < 5 -> "수뇌가 아닙니다."
        else -> null
    }

    /** 인증된 principal이 있을 때 generalId가 본인 소유가 아니면 403. 없으면 null(통과). */
    private fun ownershipGuard(userId: Long?, generalId: Int): ResponseEntity<Any>? =
        if (userId != null && generalId != resolver.resolveGeneralId(userId)) {
            ResponseEntity.status(HttpStatus.FORBIDDEN).build()
        } else {
            null
        }

    /**
     * `{ action, turnList, arg? }` map → CommandBulkItem.
     * argIsArray: PHP `is_array($arg)` — arg 부재/null(`?? []`)이거나 객체(map)면 true,
     * 객체가 아닌 값(숫자/문자열/배열-아님)으로 보냈으면 false(→ 인덱스 deny).
     */
    @Suppress("UNCHECKED_CAST")
    private fun toBulkItem(raw: Map<String, Any?>): CommandQueueService.CommandBulkItem {
        val action = (raw["action"] as? String) ?: ""
        val turnList = (raw["turnList"] as? List<*>).orEmpty().mapNotNull { (it as? Number)?.toInt() }
        val rawArg = raw["arg"]
        val arg = rawArg as? Map<String, Any?>
        val argIsArray = rawArg == null || rawArg is Map<*, *>
        return CommandQueueService.CommandBulkItem(
            action = action, turnList = turnList, arg = arg, argIsArray = argIsArray,
        )
    }

    /** `{ amount }` 추출. int 로 강제(Number만 허용); 누락/비숫자 → null(400). */
    private fun readAmount(request: Map<String, Any?>): Int? = (request["amount"] as? Number)?.toInt()

    private fun badRequest(): ResponseEntity<Any> = ResponseEntity.badRequest().build()

    private fun blocked(reason: String): ResponseEntity<Any> =
        ResponseEntity.ok(BlockedResponse(status = "BLOCKED", reason = reason))

    /** push/repeat 실행 래퍼: deny → 200 BLOCKED, 성공 → 202. */
    private inline fun runQueue(op: () -> CommandQueueService.QueueAccepted): ResponseEntity<Any> =
        try {
            val accepted = op()
            ResponseEntity.status(HttpStatus.ACCEPTED).body(ReservedResponse(status = "AVAILABLE", requestId = accepted.requestId, turnIdx = 0))
        } catch (e: CommandQueueService.CommandQueueDenied) {
            blocked(e.reason)
        }

    /** bulk 실행 래퍼: 부분 실패 → 200 + errorIdx, deny → 200 BLOCKED, 성공 → 202 + briefList. */
    private inline fun runBulk(op: () -> CommandQueueService.BulkReserveResult): ResponseEntity<Any> =
        try {
            val r = op()
            if (r.result) {
                ResponseEntity.status(HttpStatus.ACCEPTED)
                    .body(BulkAcceptedResponse(requestId = r.requestId ?: "", result = true, briefList = r.briefList, reason = r.reason))
            } else {
                ResponseEntity.ok(
                    BulkBlockedResponse(briefList = r.briefList, errorIdx = r.errorIdx ?: 0, reason = r.reason),
                )
            }
        } catch (e: CommandQueueService.CommandQueueDenied) {
            blocked(e.reason)
        }

    private companion object {
        private const val SELECT_POOL_PICK = "selectPoolPick"
        private val SELECT_POOL_COMMANDS = setOf(SELECT_POOL_PICK, "selectPoolUpdate")
        private val FORECAST_RESERVABLE_COMMANDS: Set<String> =
            CommandQueueService.GENERAL_COMMAND_CODES

        private fun isForecastReservable(code: String): Boolean =
            code in FORECAST_RESERVABLE_COMMANDS || CommandWireMapper.isIntakeCommand(code)
    }
}
