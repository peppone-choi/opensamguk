package opensamguk.gameapi.reserve

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import opensamguk.common.wire.TurnDaemonCommand

/**
 * F-INTAKE seam — maps a `POST /api/command/{code}` `{code, argJson, generalId}` onto the EXISTING
 * typed [TurnDaemonCommand] variants for the **immediate daemon-command** intake commands (the
 * betting/auction + F4 Wave C2 single-actor commands).
 *
 * **Why this exists.** Before this, [CommandReserveService] published a `Run(POKE)` for EVERY
 * AVAILABLE command and reserved the action-code into the `general_turn` ring. That is correct for
 * the **turn-reserved** `che_*` commands (resolved on the general's turn from the ring). But the
 * betting/auction + C2 commands are NOT turn-reserved — their engine handlers
 * ([opensamguk.engine.betting.PlaceBetHandler], …, the C2 intake handlers) are driven by the
 * [opensamguk.engine.run.TurnDaemonCommandDispatcher] off a TYPED command on the command stream, NOT
 * by the `general_turn` ring. So they need their typed [TurnDaemonCommand] published verbatim — a
 * `Run(POKE)` would reach the dispatcher and return `null` (no handler), silently dropping the action.
 *
 * This mapper is the ONLY place that knows the `{code → typed command}` translation. It returns the
 * typed command for an immediate-intake code, or `null` for any other code (the turn-reserved `che_*`
 * path, which [CommandReserveService] keeps handling via the ring + `Run(POKE)`).
 *
 * The arg shape is the JSON body the frontend `CommandModal`/`api.command(code, args, …)` posts —
 * the page-fixed `extraArgs` (auctionId/bettingId/…) merged with the picked arg (amount/value/…). The
 * `generalId` is the RESOLVED owner (from the controller, NOT from the body) so a caller can never
 * act as another general. NO new wire variant is introduced — every command here already exists in
 * `:common` ([TurnDaemonCommand]) and round-trips through the existing wire serializer.
 */
object CommandWireMapper {
    /** Lenient codec for the request body — tolerant of string/number coercion the UI may send. */
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** The immediate-intake command codes this mapper translates (everything else = turn-reserved). */
    val intakeCodes: Set<String> = setOf(
        "placeBet",
        "auctionBid",
        "setNotice",
        "setScoutMsg",
        "setRate",
        "setBill",
        "setSecretLimit",
        "setBlockWar",
        "setBlockScout",
        "tournamentEnroll",
        "inheritResetTurnTime",
        "inheritResetSpecialWar",
        "inheritSetNextSpecialWar",
        // F4 Wave C2 slice B — troop intake.
        "troopNew",
        "troopJoin",
        "troopExit",
        "troopKick",
        "troopSetName",
        // F4 Wave C2 슬라이스 C — 게시판(회의실/기밀실) 인테이크.
        "boardArticle",
        "boardComment",
    )

    /** True when [code] is an immediate-intake command (typed-publish, NOT general_turn reserve). */
    fun isIntakeCommand(code: String): Boolean = code in intakeCodes

    /**
     * Build the typed [TurnDaemonCommand] for an immediate-intake [code], or `null` when [code] is not
     * an immediate-intake command (the caller falls back to the turn-reserved path).
     *
     * @param code the action code (the `{code}` path var).
     * @param generalId the RESOLVED acting general (from the controller's ownership check — never the body).
     * @param requestId the generated request id (echoed back to the UI as the 202 requestId).
     * @param argJson the raw JSON body (the merged extraArgs + picked arg), or null/blank for no-arg commands.
     */
    fun toCommand(code: String, generalId: Int, requestId: String, argJson: String?): TurnDaemonCommand? {
        if (code !in intakeCodes) return null
        val args = parseArgs(argJson)
        return when (code) {
            "placeBet" -> TurnDaemonCommand.PlaceBet(
                requestId = requestId,
                bettingId = args.int("bettingId") ?: 0,
                generalId = generalId,
                bettingType = args.intList("bettingType"),
                amount = args.int("amount") ?: 0,
            )
            "auctionBid" -> TurnDaemonCommand.AuctionBid(
                requestId = requestId,
                auctionId = args.int("auctionId") ?: 0,
                generalId = generalId,
                amount = args.int("amount") ?: 0,
                tryExtendCloseDate = args.bool("tryExtendCloseDate"),
            )
            "setNotice" -> TurnDaemonCommand.SetNotice(
                requestId = requestId, generalId = generalId, msg = args.str("msg") ?: "",
            )
            "setScoutMsg" -> TurnDaemonCommand.SetScoutMsg(
                requestId = requestId, generalId = generalId, msg = args.str("msg") ?: "",
            )
            "setRate" -> TurnDaemonCommand.SetRate(
                requestId = requestId, generalId = generalId, amount = args.int("amount") ?: 0,
            )
            "setBill" -> TurnDaemonCommand.SetBill(
                requestId = requestId, generalId = generalId, amount = args.int("amount") ?: 0,
            )
            "setSecretLimit" -> TurnDaemonCommand.SetSecretLimit(
                requestId = requestId, generalId = generalId, amount = args.int("amount") ?: 0,
            )
            "setBlockWar" -> TurnDaemonCommand.SetBlockWar(
                requestId = requestId, generalId = generalId, value = args.bool("value") ?: false,
            )
            "setBlockScout" -> TurnDaemonCommand.SetBlockScout(
                requestId = requestId, generalId = generalId, value = args.bool("value") ?: false,
            )
            "tournamentEnroll" -> TurnDaemonCommand.TournamentEnroll(
                requestId = requestId, generalId = generalId, value = args.int("value") ?: 1,
            )
            "inheritResetTurnTime" -> TurnDaemonCommand.InheritResetTurnTime(
                requestId = requestId, generalId = generalId,
            )
            "inheritResetSpecialWar" -> TurnDaemonCommand.InheritResetSpecialWar(
                requestId = requestId, generalId = generalId,
            )
            "inheritSetNextSpecialWar" -> TurnDaemonCommand.InheritSetNextSpecialWar(
                requestId = requestId, generalId = generalId, specialWar = args.str("specialWar") ?: "",
            )
            // ── F4 Wave C2 slice B — troop intake. `generalId` is always the RESOLVED acting general;
            //    troopKick carries the TARGET separately (`targetGeneralId`, legacy `generalID`). ──
            "troopNew" -> TurnDaemonCommand.TroopNew(
                requestId = requestId, generalId = generalId, troopName = args.str("troopName") ?: "",
            )
            "troopJoin" -> TurnDaemonCommand.TroopJoin(
                requestId = requestId, generalId = generalId, troopId = args.int("troopId") ?: 0,
            )
            "troopExit" -> TurnDaemonCommand.TroopExit(
                requestId = requestId, generalId = generalId,
            )
            "troopKick" -> TurnDaemonCommand.TroopKick(
                requestId = requestId, generalId = generalId,
                troopId = args.int("troopId") ?: 0,
                targetGeneralId = args.int("targetGeneralId") ?: args.int("generalID") ?: 0,
            )
            "troopSetName" -> TurnDaemonCommand.TroopSetName(
                requestId = requestId, generalId = generalId,
                troopId = args.int("troopId") ?: 0,
                troopName = args.str("troopName") ?: "",
            )
            // ── F4 Wave C2 슬라이스 C — 게시판(회의실/기밀실). title/text는 nullable 유지(`?: ""` 없음)로
            //    PHP `Util::getPost`의 null(부재)-vs-blank를 보존; isSecret이 방을 고른다. ──
            "boardArticle" -> TurnDaemonCommand.BoardArticle(
                requestId = requestId, generalId = generalId,
                isSecret = args.bool("isSecret") ?: false,
                title = args.str("title"),
                text = args.str("text"),
            )
            "boardComment" -> TurnDaemonCommand.BoardComment(
                requestId = requestId, generalId = generalId,
                // `?: 0` 없음 — 부재 시 articleNo를 null로 유지(PHP getPost null)해, 엔진이
                // `$articleNo === null` 1차 게이트 '올바르지 않은 입력입니다.'를 발동하게 한다.
                articleNo = args.int("articleNo"),
                text = args.str("text"),
            )
            else -> null
        }
    }

    private fun parseArgs(argJson: String?): Map<String, JsonElement> {
        if (argJson.isNullOrBlank()) return emptyMap()
        return try {
            (json.parseToJsonElement(argJson) as? JsonObject)?.toMap() ?: emptyMap()
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun Map<String, JsonElement>.int(key: String): Int? =
        (this[key] as? JsonPrimitive)?.let { it.intOrNull ?: it.content.toIntOrNull() }

    private fun Map<String, JsonElement>.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.content

    private fun Map<String, JsonElement>.bool(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.let { it.booleanOrNull ?: it.content.toBooleanStrictOrNull() }

    private fun Map<String, JsonElement>.intList(key: String): List<Int> {
        val el = this[key] ?: return emptyList()
        return try {
            el.jsonArray.mapNotNull { (it as? JsonPrimitive)?.let { p -> p.intOrNull ?: p.content.toIntOrNull() } }
        } catch (_: Exception) {
            // single scalar → wrap (the UI may send a lone betting type)
            (el as? JsonPrimitive)?.let { it.intOrNull ?: it.content.toIntOrNull() }?.let { listOf(it) } ?: emptyList()
        }
    }
}
