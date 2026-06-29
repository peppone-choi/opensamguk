package opensamguk.gameapi.web

import opensamguk.gameapi.owner.GeneralResolver
import opensamguk.gameapi.read.GeneralReadRepository
import opensamguk.gameapi.read.GeneralTurnReadRepository
import opensamguk.gameapi.read.TurnTimeFormatter
import opensamguk.gameapi.read.WorldStateReadRepository
import opensamguk.logic.actions.CommandRegistry
import opensamguk.logic.tick.GameDate
import opensamguk.logic.tick.ServerClock
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

/**
 * F2 Wave 6 — `GET /api/reserved-commands?generalId=` (spec §5). The general's reserved-turn ring:
 * the durable `general_turn` slots the daemon will execute, rendered as the reserved-command panel.
 *
 * READ-ONLY: the daemon owns the ring write (JDBC `ReservedTurnRepository` — §0.1 #3); this controller
 * only reads it through the [GeneralTurnReadRepository] JPA read entity. Each slot is returned as
 * `{turnIdx, action, brief, arg}` ordered by `turn_idx` (the ring slot order). A general with no
 * reserved rows returns an empty `slots` list (the daemon default-seeds 휴식 lazily on reserve).
 *
 * W0-2(P1-004) — PHP `GetReservedCommand.php:69-92` 메타 필드 widen:
 *  - `turnTime`  : 장수 다음 턴 시각(general.turn_time, TURNTIME_FULL 문자열).
     *  - `turnTerm`  : 턴 텀(분, world_state.tick_seconds/60).
     *  - `year/month/turnPhase`: 게임 클럭 — `cutTurn(turnTime) > cutTurn(lastExecute)`면 이미 이번 순 턴이
     *    실행된 것이므로 1순 전진 — PHP :74-81의 cutTurn 비교를 삼모 상/중/하순 달력에 적용한다.
 *    lastExecute(game_env.turntime)는 world_state.config에서 방어적으로 읽고, 부재/파싱 불가면 비교를
 *    생략(클럭 원값 그대로 — 날조 금지).
 *  - `date`      : 서버 현재 시각(PHP `TimeUtil::now(true)`; 본 포팅은 초 단위 'yyyy-MM-dd HH:mm:ss' —
 *    마이크로초 꼬리는 표시 전용이라 절단, TURNTIME_FULL 규약과 통일).
 *  - `autorunLimit`: [§2 BLOCKED — W3_PLAN §2 general.aux 컬럼 부재, P1-004/P1-020] PHP는
 *    `general.aux.autorun_limit`(:71-72,91)인데 opensamguk엔 aux read 원천이 없다 → null(날조 금지).
 *    ChiefReservedResponse.autorunLimit과 동일 격리.
 *
 * Identity: the verified JWT principal resolves the caller's own general; the `?generalId=` query
 * param is the F2 transition fallback, gated OFF (403) when it does not match the principal's owned
 * general (Task 4 hardening — a player may only read their OWN reserved ring).
 */
@RestController
@RequestMapping("/api")
class ReservedCommandsController(
    private val resolver: GeneralResolver,
    private val reservedTurns: GeneralTurnReadRepository,
    private val world: WorldStateReadRepository,
    private val generals: GeneralReadRepository,
    private val registry: CommandRegistry,
) {

    /** One reserved ring slot (matches the W5 reserved-command panel shape). */
    data class ReservedSlot(
        val turnIdx: Int,
        val action: String,
        val brief: String,
        val arg: Map<String, Any?>,
    )

    /** The reserved-ring envelope (+ W0-2 P1-004 메타 필드 — 부재 시 null, 날조 금지). */
    data class ReservedCommandsResponse(
        val result: Boolean,
        val generalId: Int?,
        val slots: List<ReservedSlot>,
        /** 장수 다음 턴 시각 'yyyy-MM-dd HH:mm:ss'(PHP turnTime — general.turntime). */
        val turnTime: String? = null,
        /** 턴 텀(분, PHP turnTerm — game_env.turnterm). */
        val turnTerm: Int? = null,
        /** 게임 연도(cutTurn 월 전진 반영 — PHP :74-88). */
        val year: Int? = null,
        /** 게임 월(cutTurn 월 전진 반영). */
        val month: Int? = null,
        val turnPhase: Int? = null,
        val turnPhaseText: String? = null,
        /** 서버 현재 시각(PHP date — TimeUtil::now). */
        val date: String? = null,
        /** [§2 BLOCKED] general.aux.autorun_limit — aux read 원천 부재(P1-004/P1-020) → 항상 null. */
        val autorunLimit: Int? = null,
    )

    @GetMapping("/reserved-commands")
    fun reservedCommands(
        @AuthenticationPrincipal userId: Long?,
        @RequestParam(required = false) generalId: Int?,
    ): ResponseEntity<Any> {
        val resolvedId = userId?.let { resolver.resolveGeneralId(it) }
        if (userId != null && generalId != null && generalId != resolvedId) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build()
        }
        val effectiveId = resolvedId ?: generalId
            ?: return ResponseEntity.ok(ReservedCommandsResponse(result = false, generalId = null, slots = emptyList()))

        val slots = reservedTurns.findByGeneralIdOrderByTurnIdxAsc(effectiveId).map { row ->
            ReservedSlot(
                turnIdx = row.turnIdx,
                action = row.actionCode,
                brief = displayBrief(row.actionCode, row.brief),
                arg = row.arg,
            )
        }

        // ── W0-2(P1-004) 메타 — PHP GetReservedCommand.php:69-92. ────────────────────────────────────
        val w = world.findAll().firstOrNull()
        val turnTermMin = w?.tickSeconds?.takeIf { it > 0 }?.let { it / 60 }
        val generalTurnTime = generals.findById(effectiveId).orElse(null)?.turnTime
        // lastExecute(game_env.turntime) — config 방어적 read(데몬 미기재 시 비교 생략).
        val lastExecute = TurnTimeFormatter.parseFull(w?.config?.get("turntime")?.toString())

        var gameDate = w?.let { GameDate(it.currentYear, it.currentMonth, it.currentPhase.coerceIn(1, 3)) }
        if (generalTurnTime != null && lastExecute != null && turnTermMin != null && turnTermMin > 0 &&
            gameDate != null &&
            TurnTimeFormatter.cutTurn(generalTurnTime, turnTermMin).isAfter(TurnTimeFormatter.cutTurn(lastExecute, turnTermMin))
        ) {
            gameDate = ServerClock.advance(gameDate, turns = 1)
        }

        return ResponseEntity.ok(
            ReservedCommandsResponse(
                result = true,
                generalId = effectiveId,
                slots = slots,
                turnTime = TurnTimeFormatter.full(generalTurnTime),
                turnTerm = turnTermMin,
                year = gameDate?.year,
                month = gameDate?.month,
                turnPhase = gameDate?.phase,
                turnPhaseText = gameDate?.phaseText,
                date = TurnTimeFormatter.full(Instant.now()),
                // autorunLimit — §2 BLOCKED(aux 원천 부재) 항상 null(날조 금지).
                autorunLimit = null,
            ),
        )
    }

    private fun displayBrief(actionCode: String, brief: String): String =
        if (actionCode != "휴식" && (brief.isBlank() || brief == "휴식")) {
            registry.resolve(actionCode).name
        } else {
            brief
        }

}
