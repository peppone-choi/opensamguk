package opensamguk.gameapi.owner

import opensamguk.common.constants.GameConst
import opensamguk.common.rng.LiteHashDrbg
import opensamguk.common.rng.RandUtil
import opensamguk.common.rng.serializeSeed
import opensamguk.gameapi.dto.ClaimableGeneral
import opensamguk.gameapi.dto.ClaimableResponse
import opensamguk.gameapi.read.GeneralReadEntity
import opensamguk.gameapi.read.GeneralReadRepository
import opensamguk.gameapi.read.NationReadRepository
import opensamguk.gameapi.read.WorldStateReadRepository
import opensamguk.logic.util.phpRound
import opensamguk.logic.world.SpecialityHelper
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.max
import kotlin.math.pow

@Service
class SelectNpcTokenService(
    private val tokens: SelectNpcTokenRepository,
    private val owners: GeneralOwnerRepository,
    private val generals: GeneralReadRepository,
    private val nations: NationReadRepository,
    private val worldStates: WorldStateReadRepository,
    private val clock: Clock = Clock.systemUTC(),
    private val nonceRandom: SecureRandom = SecureRandom(),
) {
    @Transactional
    fun claimable(userId: Long): ClaimableResponse {
        if (owners.findByUserId(userId) != null) {
            return ClaimableResponse(result = true, hasGeneral = true, candidates = emptyList())
        }

        val now = Instant.now(clock)
        val active = tokens.findFirstByOwnerIdAndValidUntilAfterOrderByIdDesc(userId, now)
        if (active != null) return active.toResponse(hasGeneral = false)

        val token = issueToken(userId, now)
        return token.toResponse(hasGeneral = false)
    }

    private fun issueToken(userId: Long, now: Instant): SelectNpcTokenEntity {
        val claimedIds = owners.findAllByOrderByGeneralIdAsc().map { it.generalId.toInt() }.toSet()
        val reservedIds = (tokens.findValidOtherTokens(userId, now) ?: emptyList())
            .flatMap { it.pickResult.keys }
            .mapNotNull { it.toIntOrNull() }
            .toSet()
        val nationNames = nations.findAll().associate { it.id to it.name }

        val candidates = generals
            .findByNpcStateOrderByIdAsc(GeneralPossessionService.CLAIMABLE_NPC_STATE)
            .filter { it.id !in claimedIds && it.id !in reservedIds }
        val weights = LinkedHashMap<String, Double>()
        for (candidate in candidates) {
            weights[candidate.id.toString()] =
                (candidate.leadership + candidate.strength + candidate.intel).toDouble().pow(1.5)
        }

        val pickResult = LinkedHashMap<String, Any?>()
        if (weights.isNotEmpty()) {
            if (weights.values.none { it > 0.0 }) {
                for (candidate in candidates.take(PICK_COUNT)) {
                    pickResult[candidate.id.toString()] = candidate.toPickMap(nationNames[candidate.nationId])
                }
            } else {
                val rng = RandUtil(LiteHashDrbg(serializeSeed(hiddenSeed(), "SelectNPCToken", userId, legacyNow(now))))
                while (pickResult.size < PICK_COUNT && pickResult.size < weights.size) {
                    val pickedId = rng.choiceUsingWeight(weights)
                    if (pickResult.containsKey(pickedId)) continue
                    val candidate = candidates.first { it.id.toString() == pickedId }
                    pickResult[pickedId] = candidate.toPickMap(nationNames[candidate.nationId])
                }
            }
        }

        val turnTermMinutes = turnTermMinutes()
        val validSeconds = max(90, turnTermMinutes * 40)
        val pickMoreSeconds = max(10, phpRound(turnTermMinutes.toDouble().pow(0.672) * 8))
        pickResult[PICK_MORE_SECONDS_KEY] = pickMoreSeconds
        val entity = SelectNpcTokenEntity(
            ownerId = userId,
            validUntil = now.plusSeconds(validSeconds.toLong()),
            pickMoreFrom = LEGACY_PICK_MORE_EPOCH,
            pickResult = pickResult,
            nonce = nonceRandom.nextInt(0x10000000),
            createdAt = now,
            updatedAt = now,
        )
        tokens.save(entity)
        return entity
    }

    private fun SelectNpcTokenEntity.toResponse(hasGeneral: Boolean): ClaimableResponse {
        val metadata = pickResult
        val pickMoreSeconds = intOf(metadata[PICK_MORE_SECONDS_KEY]) ?: 0
        return ClaimableResponse(
            result = true,
            hasGeneral = hasGeneral,
            candidates = metadata
                .filterKeys { it != PICK_MORE_SECONDS_KEY }
                .values
                .mapNotNull { pick -> (pick as? Map<*, *>)?.toClaimableGeneral() },
            validUntil = validUntil.toString(),
            pickMoreFrom = pickMoreFrom.toString(),
            pickMoreSeconds = pickMoreSeconds,
        )
    }

    private fun GeneralReadEntity.toPickMap(nationName: String?): Map<String, Any?> = linkedMapOf(
        "generalId" to id,
        "name" to name,
        "nationId" to nationId,
        "nationName" to nationName,
        "leadership" to leadership,
        "strength" to strength,
        "intel" to intel,
        "picture" to picture,
        "imageServer" to imageServer,
        "special" to specialName(SpecialityHelper.domesticName(specialCode), specialCode),
        "special2" to specialName(SpecialityHelper.warName(special2Code), special2Code),
        "personal" to GameConst.personalityNameOf(personalCode),
        "keepCnt" to 3,
    )

    private fun Map<*, *>.toClaimableGeneral(): ClaimableGeneral = ClaimableGeneral(
        generalId = intOf(this["generalId"]) ?: 0,
        name = this["name"]?.toString() ?: "",
        nationId = intOf(this["nationId"]) ?: 0,
        nationName = this["nationName"]?.toString(),
        leadership = intOf(this["leadership"]) ?: 0,
        strength = intOf(this["strength"]) ?: 0,
        intel = intOf(this["intel"]) ?: 0,
        picture = this["picture"]?.toString(),
        imageServer = intOf(this["imageServer"]) ?: 0,
        special = this["special"]?.toString(),
        special2 = this["special2"]?.toString(),
        personal = this["personal"]?.toString(),
        keepCnt = intOf(this["keepCnt"]),
    )

    private fun hiddenSeed(): String {
        val world = worldState()
        return world?.meta?.get("hiddenSeed")?.toString()
            ?: world?.config?.get("hiddenSeed")?.toString()
            ?: world?.scenarioCode
            ?: ""
    }

    private fun turnTermMinutes(): Int {
        val seconds = worldState()?.tickSeconds ?: 60
        return max(1, seconds / 60)
    }

    private fun worldState() = runCatching { worldStates.findById(1)?.orElse(null) }.getOrNull()

    private fun legacyNow(now: Instant): String = LEGACY_NOW_FORMATTER.format(now)

    private fun specialName(resolved: String, code: String): String =
        if (code.isBlank() || code == "None") "-" else resolved

    companion object {
        private const val PICK_COUNT = 5
        private const val PICK_MORE_SECONDS_KEY = "__pickMoreSeconds"
        private val LEGACY_PICK_MORE_EPOCH: Instant = Instant.parse("2000-01-01T01:00:00Z")
        private val LEGACY_NOW_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.of("Asia/Seoul"))

        private fun intOf(value: Any?): Int? = when (value) {
            is Int -> value
            is Long -> value.toInt()
            is Short -> value.toInt()
            is Byte -> value.toInt()
            is Double -> value.toInt()
            is Float -> value.toInt()
            is Number -> value.toInt()
            is String -> value.toIntOrNull()
            else -> null
        }
    }
}
