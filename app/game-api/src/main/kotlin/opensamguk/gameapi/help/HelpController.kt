package opensamguk.gameapi.help

import com.fasterxml.jackson.databind.ObjectMapper
import opensamguk.gameapi.read.WorldStateReadRepository
import opensamguk.gameapi.read.processRuleProfile
import opensamguk.logic.input.InputEntry
import opensamguk.logic.input.RuleProfile
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/help")
class HelpController(
    private val provider: HelpStoreProvider,
    private val worlds: WorldStateReadRepository,
    private val mapper: ObjectMapper,
) {
    private val store get() = provider.store

    @GetMapping("/topics/{topicId}")
    fun topic(@PathVariable topicId: String): ResponseEntity<Any> = inWorld {
        val topic = store.topic(topicId) ?: return@inWorld error(HttpStatus.NOT_FOUND, "HELP_TOPIC_NOT_FOUND", "도움말 주제를 찾을 수 없습니다.")
        ResponseEntity.ok<Any>(mapOf("schemaVersion" to 1, "topic" to topic))
    }

    @GetMapping("/search")
    fun search(@RequestParam(required = false) q: String?, @RequestParam(required = false) limit: String?): ResponseEntity<Any> = inWorld {
        val query = q?.trim().orEmpty()
        val count = if (limit == null) 20 else limit.toIntOrNull() ?: 0
        if (query.length !in 2..80 || count !in 1..50) {
            return@inWorld error(HttpStatus.BAD_REQUEST, "INVALID_SEARCH_QUERY", "검색어 또는 결과 수가 올바르지 않습니다.")
        }
        ResponseEntity.ok<Any>(mapOf("schemaVersion" to 1, "query" to query, "hits" to store.search(query, count)))
    }

    @GetMapping("/context")
    fun context(@RequestParam inputId: String): ResponseEntity<Any> = inWorld {
        val input = store.input(inputId) ?: return@inWorld error(HttpStatus.NOT_FOUND, "INPUT_NOT_FOUND", "입력을 찾을 수 없습니다.")
        val topic = store.topic(input.helpTopicId)
            ?: return@inWorld error(HttpStatus.NOT_FOUND, "HELP_TOPIC_NOT_FOUND", "도움말 주제를 찾을 수 없습니다.")
        ResponseEntity.ok<Any>(mapOf("schemaVersion" to 1, "topic" to topic, "input" to project(input)))
    }

    @GetMapping("/failures/{reason}")
    fun failure(@PathVariable reason: String, @RequestParam(required = false) inputId: String?): ResponseEntity<Any> = inWorld {
        val input = inputId?.let { store.input(it) ?: return@inWorld error(HttpStatus.NOT_FOUND, "INPUT_NOT_FOUND", "입력을 찾을 수 없습니다.") }
        val help = store.reason(reason)
            ?: return@inWorld error(HttpStatus.NOT_FOUND, "FAILURE_REASON_NOT_FOUND", "실패 사유를 찾을 수 없습니다.")
        if (input != null && reason !in input.failureReasons) {
            return@inWorld error(HttpStatus.BAD_REQUEST, "REASON_NOT_FOR_INPUT", "이 입력의 실패 사유가 아닙니다.")
        }
        val contextual = inputId?.let(help.byInputId::get)
        val related = if (input != null) listOf(input.helpTopicId) else store.catalog.entries
            .filter { reason in it.failureReasons }.map { it.helpTopicId }.distinct().sorted()
        ResponseEntity.ok<Any>(mapOf(
            "schemaVersion" to 1, "reason" to reason,
            "reviewState" to help.reviewState,
            "explanation" to (contextual?.explanation ?: help.explanation),
            "recoveryAdvice" to (contextual?.recoveryAdvice ?: help.recoveryAdvice),
            "relatedTopicIds" to related,
        ))
    }

    private fun project(input: InputEntry): Map<String, Any?> = mapOf(
        "inputId" to input.inputId,
        "kind" to input.kind.name,
        "displayName" to input.displayName,
        "deliveryState" to input.deliveryState.name,
        "actor" to input.actor,
        "authorityRule" to input.authorityRule,
        "targetSchema" to mapper.readTree(input.targetSchema.toString()),
        "costSchema" to mapper.readTree(input.costSchema.toString()),
        "timing" to mapper.readTree(input.timing.toString()),
        "effectScope" to input.effectScope,
        "failureReasons" to input.failureReasons,
        "helpTopicId" to input.helpTopicId,
        "tutorialObjectiveId" to input.tutorialObjectiveId.takeUnless { it == "N/A" },
    )

    private fun inWorld(action: () -> ResponseEntity<Any>): ResponseEntity<Any> = when (worlds.processRuleProfile()) {
        null -> error(HttpStatus.SERVICE_UNAVAILABLE, "WORLD_UNAVAILABLE", "활성 월드가 없습니다.")
        RuleProfile.HWIHA -> action()
        else -> error(HttpStatus.NOT_FOUND, "WORLD_PROFILE_UNAVAILABLE", "이 월드의 도움말을 제공하지 않습니다.")
    }

    private fun error(status: HttpStatus, code: String, message: String): ResponseEntity<Any> =
        ResponseEntity.status(status).body<Any>(mapOf("error" to mapOf("code" to code, "message" to message)))
}
