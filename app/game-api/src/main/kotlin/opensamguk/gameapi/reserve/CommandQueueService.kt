package opensamguk.gameapi.reserve

import opensamguk.common.constants.GameConst
import opensamguk.common.world.WorldId
import opensamguk.gameapi.config.GameApiProcessWorld
import opensamguk.infra.persistence.CommandInboxRepository
import opensamguk.infra.persistence.CommandInboxRepository.AcceptedCommand
import opensamguk.infra.persistence.CommandInboxRepository.CommandKind
import opensamguk.infra.persistence.CommandResultRepository
import opensamguk.infra.persistence.ReservedTurnRepository
import opensamguk.infra.persistence.ReservedTurnRepository.Companion.MAX_CHIEF_TURNS
import opensamguk.infra.persistence.ReservedTurnRepository.Companion.MAX_GENERAL_TURNS
import opensamguk.logic.actions.CommandRegistry
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionOperations
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * W6e — Command-queue(예약 큐) 조작 서비스. `legacy/devsam-core/hwe/sammo/API/{Command,NationCommand}/
 * {ReserveBulk,Push,Repeat}.php` + `func_command.php`의 ReserveBulk/Push/Repeat를 그대로 옮긴 것.
 *
 * **REST 전용 — 인테이크 코드/와이어 변형/데몬 핸들러 없음.** 데몬 명령 경로(`CommandReserveService`의
 * Model B)와 달리, 이 슬라이스는 game-api 프로세스 안에서 `general_turn`/`nation_turn` 링을 직접
 * 갱신한다. JDBC-only([ReservedTurnRepository]) — one-daemon-write-rule 비위반(JPA EntityManager
 * write 없음, 데몬 ChangeRecorder 경로와 무관).
 *
 * 결정성(Deterministic): RNG 없음. 모든 분기는 입력값과 deny 문자열에만 의존한다.
 *
 * **deny 문자열은 PHP byte-for-byte.** bulk의 인덱스 변형(`{idx}: …`)은 PHP가 배열 키(`$idx`)를
 * 그대로 박아 넣는 것을 재현한다.
 *
 * **brief(R5 갭).** PHP는 `$commandObj->getBrief()`로 brief를 만든다(arg 의존 1줄 요약). `:logic`에는
 * `getBrief(arg)` 시임이 아직 없으므로, 충실 대체로 액션 정의의 [name]을 brief로 쓴다([resolveBrief]).
 * 풀 패러티(brief 골든)는 `:logic` getBrief 시임이 생긴 뒤 닫는다. **fabricate 금지** — 임의의
 * brief 문자열을 지어내지 않는다.
 */
@Service
class CommandQueueService(
    private val reservedTurns: ReservedTurnRepository,
    private val registry: CommandRegistry,
    private val commandInbox: CommandInboxRepository,
    private val commandResults: CommandResultRepository,
    private val transactions: TransactionOperations,
    processWorld: GameApiProcessWorld,
    private val clock: Clock = Clock.systemUTC(),
    private val requestIds: () -> String = { UUID.randomUUID().toString() },
) {
    private val worldId: WorldId = processWorld.worldId

    // --- 입출력 DTO --------------------------------------------------------------------------------

    /**
     * bulk 1건: `{ action, turnList, arg? }` (PHP `ReserveBulkCommand`의 배열 원소).
     *
     * [argIsArray]: PHP `is_array($arg)` 게이트를 위한 플래그. arg 가 없거나(`?? []`) map 이면 true,
     * 클라이언트가 arg 를 객체가 아닌 값(숫자/문자열 등)으로 보냈으면 false → `올바른 arg 형태가 아닙니다.`.
     */
    data class CommandBulkItem(
        val action: String,
        val turnList: List<Int>,
        val arg: Map<String, Any?>? = null,
        val argIsArray: Boolean = true,
    )

    /**
     * bulk 결과 — PHP `ReserveBulkCommand::launch`의 반환 배열과 동형.
     *  - 성공: `result=true, briefList(전체), reason="success"`
     *  - 실패: `result=false, briefList(부분 누적), errorIdx, reason(부분 실패 사유)`
     */
    data class BulkReserveResult(
        val result: Boolean,
        val briefList: List<String>,
        val reason: String,
        val errorIdx: Int? = null,
        val requestId: String? = null,
    )

    data class QueueAccepted(val requestId: String)

    /** 큐 조작 실패(검증 deny). 컨트롤러가 200 OK + reason으로 매핑한다(PHP가 문자열을 그대로 반환). */
    class CommandQueueDenied(val reason: String) : RuntimeException(reason)

    // --- General: Bulk -----------------------------------------------------------------------------

    /**
     * ReserveBulk(장수) — `ReserveBulkCommand::launch` + `setGeneralCommand`.
     * 첫 실패 원소에서 fast-fail: 그 시점까지의 briefList + errorIdx + reason을 반환한다.
     *
     * 원소별 검증 순서(PHP 그대로):
     *  1. turnList 비어있음 → `"{idx}: 턴이 입력되지 않았습니다"`
     *  2. action ∉ flatten(availableGeneralCommand) → `"{idx}: 사용할 수 없는 커맨드입니다."`
     *  3. arg 가 map 이 아님 → `"{idx}: 올바른 arg 형태가 아닙니다."`
     *     (Kotlin 타입상 arg 는 Map?/null 이므로 이 게이트는 컨트롤러 바디 파싱에서 보장된다.)
     *  4. setGeneralCommand 부분 실패 → 부분 결과 반환(errorIdx=idx).
     */
    fun reserveBulkGeneral(generalId: Int, commands: List<CommandBulkItem>): BulkReserveResult {
        val requestId = requestIds()
        return transactions.execute { status ->
            val briefList = ArrayList<String>(commands.size)
            for ((idx, item) in commands.withIndex()) {
                if (item.turnList.isEmpty()) {
                    throw CommandQueueDenied("$idx: 턴이 입력되지 않았습니다")
                }
                if (item.action !in GENERAL_COMMAND_CODES) {
                    throw CommandQueueDenied("$idx: 사용할 수 없는 커맨드입니다.")
                }
                if (!item.argIsArray) {
                    throw CommandQueueDenied("$idx: 올바른 arg 형태가 아닙니다.")
                }
                val partial = setGeneralCommand(generalId, item.turnList, item.action, item.arg)
                if (!partial.result) {
                    status.setRollbackOnly()
                    return@execute BulkReserveResult(
                        result = false,
                        briefList = briefList,
                        reason = partial.reason,
                        errorIdx = idx,
                    )
                }
                briefList.add(partial.brief ?: "")
            }
            insertQueueAccepted(
                requestId = requestId,
                operation = "bulkGeneral",
                generalId = generalId,
                nationId = null,
                officerLevel = null,
                turnIdx = null,
                payload = encodeJsonObject(
                    linkedMapOf("operation" to "bulkGeneral", "generalId" to generalId, "commandCount" to commands.size),
                ),
            )
            BulkReserveResult(result = true, briefList = briefList, reason = "success", requestId = requestId)
        } ?: throw IllegalStateException("queue transaction returned null")
    }

    /** ReserveBulk(국가) — `NationCommand/ReserveBulkCommand` + `setNationCommand`. */
    fun reserveBulkNation(
        generalId: Int,
        nationId: Int,
        officerLevel: Int,
        commands: List<CommandBulkItem>,
    ): BulkReserveResult {
        val requestId = requestIds()
        return transactions.execute { status ->
            val briefList = ArrayList<String>(commands.size)
            for ((idx, item) in commands.withIndex()) {
                if (item.turnList.isEmpty()) {
                    throw CommandQueueDenied("$idx: 턴이 입력되지 않았습니다")
                }
                if (item.action !in CHIEF_COMMAND_CODES) {
                    throw CommandQueueDenied("$idx: 사용할 수 없는 커맨드입니다.")
                }
                if (!item.argIsArray) {
                    throw CommandQueueDenied("$idx: 올바른 arg 형태가 아닙니다.")
                }
                val partial = setNationCommand(nationId, officerLevel, item.turnList, item.action, item.arg)
                if (!partial.result) {
                    status.setRollbackOnly()
                    return@execute BulkReserveResult(
                        result = false,
                        briefList = briefList,
                        reason = partial.reason,
                        errorIdx = idx,
                    )
                }
                briefList.add(partial.brief ?: "")
            }
            insertQueueAccepted(
                requestId = requestId,
                operation = "bulkNation",
                generalId = generalId,
                nationId = nationId,
                officerLevel = officerLevel,
                turnIdx = null,
                payload = encodeJsonObject(
                    linkedMapOf(
                        "operation" to "bulkNation",
                        "generalId" to generalId,
                        "nationId" to nationId,
                        "officerLevel" to officerLevel,
                        "commandCount" to commands.size,
                    ),
                ),
            )
            BulkReserveResult(result = true, briefList = briefList, reason = "success", requestId = requestId)
        } ?: throw IllegalStateException("queue transaction returned null")
    }

    // --- General: Push / Repeat --------------------------------------------------------------------

    /**
     * Push(장수) — `PushCommand` + `pushGeneralCommand`. amount==0 → deny.
     * 양수: 링을 아래로 밀고 휴식 슬롯 삽입. 음수: pull(앞에서 제거). |amount| >= MAX → 내부 no-op.
     */
    fun pushGeneral(generalId: Int, amount: Int): QueueAccepted {
        if (amount == 0) throw CommandQueueDenied("0은 불가능합니다")
        return queueMutation("pushGeneral", generalId, null, null, amount) {
            if (amount > 0) {
                reservedTurns.pushGeneralTurn(worldId = worldId, generalId = generalId, turnCnt = amount)
            } else {
                reservedTurns.pullGeneralTurn(worldId = worldId, generalId = generalId, turnCnt = -amount)
            }
        }
    }

    /**
     * Repeat(장수) — `RepeatCommand` + `repeatGeneralCommand`. **precheck 없음**(PHP 동일 —
     * 검증 범위 1..12 는 컨트롤러 validator가 책임). amount<=0 / amount>=MAX → 내부 no-op.
     */
    fun repeatGeneral(generalId: Int, amount: Int): QueueAccepted =
        queueMutation("repeatGeneral", generalId, null, null, amount) {
            reservedTurns.repeatGeneralTurn(worldId = worldId, generalId = generalId, turnCnt = amount)
        }

    // --- Nation: Push / Repeat ---------------------------------------------------------------------

    /**
     * Push(국가) — `NationCommand/PushCommand` + `pushNationCommand`.
     * 검증 순서(PHP 그대로): amount==0 → `0은 불가능합니다`; 장수/국가/수뇌 precheck는 컨트롤러가
     * [GeneralResolver]로 캐시해 전달(TOCTOU 회피). 여기선 캐시된 (nationId, officerLevel)만 쓴다.
     */
    fun pushNation(generalId: Int, nationId: Int, officerLevel: Int, amount: Int): QueueAccepted {
        if (amount == 0) throw CommandQueueDenied("0은 불가능합니다")
        return queueMutation("pushNation", generalId, nationId, officerLevel, amount) {
            if (amount > 0) {
                reservedTurns.pushNationTurn(
                    worldId = worldId,
                    nationId = nationId,
                    officerLevel = officerLevel,
                    turnCnt = amount,
                )
            } else {
                reservedTurns.pullNationTurn(
                    worldId = worldId,
                    nationId = nationId,
                    officerLevel = officerLevel,
                    turnCnt = -amount,
                )
            }
        }
    }

    /** Repeat(국가) — `NationCommand/RepeatCommand` + `repeatNationCommand`. amount 범위는 컨트롤러 validator. */
    fun repeatNation(generalId: Int, nationId: Int, officerLevel: Int, amount: Int): QueueAccepted =
        queueMutation("repeatNation", generalId, nationId, officerLevel, amount) {
            reservedTurns.repeatNationTurn(
                worldId = worldId,
                nationId = nationId,
                officerLevel = officerLevel,
                turnCnt = amount,
            )
        }

    // --- setGeneralCommand / setNationCommand 포팅(링 쓰기) ----------------------------------------

    /** `setGeneralCommand`/`setNationCommand`의 결과 배열 축약(result/brief/reason). */
    private data class SetCommandResult(val result: Boolean, val brief: String?, val reason: String)

    /**
     * `func_command.php:304-400 setGeneralCommand` 포팅.
     *  - turnIdx 검증: `!is_int || turnIdx < -3 || turnIdx >= MAX` → `올바른 턴이 아닙니다. : {turnIdx}`
     *  - 음수 확장: -1=홀수(0,2,4,…), -2=짝수(1,3,5,…), -3=전체(0..MAX-1)
     *  - 중복 제거(PHP `$turnList[idx]=true` → `array_keys`): 동일 슬롯 1회만.
     *  - 각 슬롯에 (action, arg, brief) UPSERT(`_setGeneralCommand`의 단일 UPDATE를 슬롯별 reserve로).
     *
     * NOTE: PHP는 buildGeneralCommandClass로 arg 유효성/예약권한도 검사한다. 그 시임(arg 빌더 +
     * isArgValid + hasPermissionToReserve)은 game-api precheck 경로(CommandPrecheckService)에서
     * 별도로 다뤄지며, 본 슬라이스는 링 쓰기 + turnIdx 검증 + brief에 한정한다(W6e 스코프).
     */
    private fun setGeneralCommand(
        generalId: Int,
        rawTurnList: List<Int>,
        command: String,
        arg: Map<String, Any?>?,
    ): SetCommandResult {
        val slots = LinkedHashSet<Int>()
        for (turnIdx in rawTurnList) {
            if (turnIdx < -3 || turnIdx >= MAX_GENERAL_TURNS) {
                return SetCommandResult(false, null, "올바른 턴이 아닙니다. : $turnIdx")
            }
            when {
                turnIdx >= 0 -> slots.add(turnIdx)
                turnIdx == -1 -> { var i = 0; while (i < MAX_GENERAL_TURNS) { slots.add(i); i += 2 } } // 홀수(0부터)
                turnIdx == -2 -> { var i = 1; while (i < MAX_GENERAL_TURNS) { slots.add(i); i += 2 } } // 짝수(1부터)
                turnIdx == -3 -> { var i = 0; while (i < MAX_GENERAL_TURNS) { slots.add(i); i += 1 } } // 전체
            }
        }
        val brief = resolveBrief(command)
        val argJson = encodeArg(arg)
        for (slot in slots) {
            reservedTurns.reserve(
                worldId = worldId,
                generalId = generalId,
                turnIdx = slot,
                actionCode = command,
                argJson = argJson,
                brief = brief,
            )
        }
        return SetCommandResult(true, brief, "success")
    }

    /**
     * `func_command.php:402-497 setNationCommand` 포팅(턴 검증 부분).
     *  - 중복 제거(`array_unique`) 후 turnIdx 검증: `turnIdx < 0 || turnIdx >= MAX_CHIEF` →
     *    `올바른 턴이 아닙니다. : {turnIdx}` (장수와 달리 음수 확장 없음).
     *  - officer_level<5 게이트(`수뇌가 아닙니다`)는 컨트롤러 precheck에서 보장(캐시된 officerLevel).
     *  - 각 슬롯에 (action, arg, brief) UPSERT.
     */
    private fun setNationCommand(
        nationId: Int,
        officerLevel: Int,
        rawTurnList: List<Int>,
        command: String,
        arg: Map<String, Any?>?,
    ): SetCommandResult {
        val slots = LinkedHashSet<Int>()
        for (turnIdx in rawTurnList) {
            if (turnIdx < 0 || turnIdx >= MAX_CHIEF_TURNS) {
                return SetCommandResult(false, null, "올바른 턴이 아닙니다. : $turnIdx")
            }
            slots.add(turnIdx)
        }
        val brief = resolveBrief(command)
        val argJson = encodeArg(arg)
        for (slot in slots) {
            reservedTurns.reserveNationTurn(
                worldId = worldId,
                nationId = nationId,
                officerLevel = officerLevel,
                turnIdx = slot,
                actionCode = command,
                argJson = argJson,
                brief = brief,
            )
        }
        return SetCommandResult(true, brief, "success")
    }

    /**
     * brief 해석(R5 갭 대체). PHP `$commandObj->getBrief()` 자리.
     * `:logic`에 arg-의존 `getBrief` 시임이 없으므로 액션 정의의 [name]을 쓴다. 미지/휴식 코드는
     * 레지스트리 fallback(RestAction.name = "휴식")으로 떨어진다. **fabricate 금지.**
     */
    private fun resolveBrief(command: String): String = registry.resolve(command).name

    private fun queueMutation(
        operation: String,
        generalId: Int,
        nationId: Int?,
        officerLevel: Int?,
        amount: Int,
        mutate: () -> Unit,
    ): QueueAccepted {
        val requestId = requestIds()
        transactions.executeWithoutResult {
            mutate()
            insertQueueAccepted(
                requestId = requestId,
                operation = operation,
                generalId = generalId,
                nationId = nationId,
                officerLevel = officerLevel,
                turnIdx = null,
                payload = encodeJsonObject(
                    linkedMapOf(
                        "operation" to operation,
                        "generalId" to generalId,
                        "nationId" to nationId,
                        "officerLevel" to officerLevel,
                        "amount" to amount,
                    ),
                ),
            )
        }
        return QueueAccepted(requestId)
    }

    private fun insertQueueAccepted(
        requestId: String,
        operation: String,
        generalId: Int?,
        nationId: Int?,
        officerLevel: Int?,
        turnIdx: Int?,
        payload: String,
    ) {
        val result = commandInbox.insertAccepted(
            AcceptedCommand(
                worldId = worldId,
                requestId = requestId,
                commandKind = CommandKind.QUEUE_MUTATION,
                intentFingerprint = queueFingerprint(operation, payload),
                generalId = null,
                turnIdx = null,
                actionCode = operation,
                payloadJson = payload,
            ),
        )
        if (result is CommandInboxRepository.InsertResult.Conflict) {
            throw IllegalStateException("command_inbox request_id conflict")
        }
        if (result is CommandInboxRepository.InsertResult.Inserted) {
            commandResults.insertTerminalResult(
                worldId = worldId,
                row = CommandTerminalResultFactory.acceptedRow(
                    worldId = worldId,
                    requestId = requestId,
                    sentAt = Instant.now(clock),
                    type = "queueMutation",
                    commandKind = CommandKind.QUEUE_MUTATION,
                    actionCode = operation,
                    generalId = generalId,
                    turnIdx = turnIdx,
                ),
                expectedInboxStatuses = setOf("ACCEPTED"),
            )
        }
    }

    private fun queueFingerprint(operation: String, payload: String): String {
        val canonical = listOf("v1", worldId.value.toString(), CommandKind.QUEUE_MUTATION.name, operation, payload)
            .joinToString("\u001f")
        val bytes = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /** arg map → jsonb 문자열. null/빈 map → `{}` (ReservedTurnRepository.normalizeArgs가 정규화). */
    private fun encodeArg(arg: Map<String, Any?>?): String? {
        if (arg.isNullOrEmpty()) return null
        return encodeJsonObject(arg)
    }

    companion object {
        /**
         * PHP `Util::array_flatten(GameConst::$availableGeneralCommand)` — 카테고리 맵의 값들을 평탄화한
         * 사용 가능한 장수 커맨드 코드 집합. ReserveBulk의 `사용할 수 없는 커맨드입니다.` 게이트 원천.
         */
        val GENERAL_COMMAND_CODES: Set<String> =
            GameConst.availableGeneralCommand.values.flatten().toSet()

        /** PHP `Util::array_flatten(GameConst::$availableChiefCommand)` — 사용 가능한 국가(수뇌) 커맨드 코드 집합. */
        val CHIEF_COMMAND_CODES: Set<String> =
            GameConst.availableChiefCommand.values.flatten().toSet()

        /**
         * 최소 jsonb 직렬화(arg map → 문자열). 키 삽입 순서 보존(LinkedHashMap 가정). 값 타입:
         * 숫자/불리언은 그대로, 문자열은 escape, 중첩 map/list 는 재귀. PHP `Json::encode($arg)`의
         * 결과(키 순서)를 따른다(arg 는 클라이언트가 보낸 순서 그대로).
         */
        internal fun encodeJsonObject(map: Map<String, Any?>): String =
            buildString {
                append('{')
                var first = true
                for ((k, v) in map) {
                    if (!first) append(',')
                    first = false
                    append('"'); append(escapeJson(k)); append('"'); append(':')
                    append(encodeJsonValue(v))
                }
                append('}')
            }

        private fun encodeJsonValue(v: Any?): String = when (v) {
            null -> "null"
            is Boolean -> v.toString()
            is Int, is Long, is Short, is Byte -> v.toString()
            is Double -> if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()
            is Float -> encodeJsonValue(v.toDouble())
            is String -> "\"${escapeJson(v)}\""
            is Map<*, *> -> {
                @Suppress("UNCHECKED_CAST")
                encodeJsonObject(v as Map<String, Any?>)
            }
            is List<*> -> buildString {
                append('[')
                v.forEachIndexed { i, e -> if (i > 0) append(','); append(encodeJsonValue(e)) }
                append(']')
            }
            else -> "\"${escapeJson(v.toString())}\""
        }

        private fun escapeJson(s: String): String = buildString {
            for (c in s) when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(c)
            }
        }
    }
}
