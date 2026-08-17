package opensamguk.gameapi.controller

import jakarta.servlet.http.HttpServletRequest
import opensamguk.common.auth.GatewayProfileClaims
import opensamguk.common.constants.GameConst
import opensamguk.gameapi.owner.GeneralResolver
import opensamguk.gameapi.reserve.CommandReserveService
import opensamguk.gameapi.security.JwtVerifyFilter
import opensamguk.common.wire.TurnDaemonCommand
import opensamguk.infra.read.SelectPoolReadRow
import opensamguk.infra.read.SelectPoolRepository
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Clock

@RestController
@RequestMapping("/api/select-pool")
class SelectPoolController(
    private val repository: SelectPoolRepository,
    private val resolver: GeneralResolver,
    private val clock: Clock = Clock.systemUTC(),
    private val reserve: CommandReserveService? = null,
) {
    @GetMapping
    fun candidates(
        @AuthenticationPrincipal userId: Long?,
        request: HttpServletRequest,
    ): ResponseEntity<SelectPoolResponse> {
        val profile = verifiedProfile(userId, request)
        if (profile == null || userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        }
        val now = clock.instant()
        val rows = repository.listForUser(userId.toInt(), now)
        val cards = rows.map(::toCard).sortedBy { it.dex.sum() }
        val options = repository.allowedCustomOptions()
        val showImageLevel = repository.showImageLevel() ?: DEFAULT_SHOW_IMAGE_LEVEL
        return ResponseEntity.ok(
            SelectPoolResponse(
                result = true,
                generalId = resolver.resolveGeneralId(userId),
                validUntil = rows.mapNotNull { it.reservedUntil }.minOrNull()?.toString(),
                pick = cards,
                customOptions = SelectPoolCustomOptions(
                    stat = "stat" in options,
                    personality = "ego" in options,
                    picture = "picture" in options,
                    personalities = GameConst.availablePersonality.map {
                        SelectPoolPersonalityOption(it, GameConst.personalityNameOf(it))
                    },
                ),
                member = SelectPoolMember(
                    name = profile.nickname?.takeIf { it.isNotBlank() } ?: profile.username,
                    canUsePicture = showImageLevel >= 1 && profile.grade >= 1 && !profile.picture.isNullOrBlank(),
                ),
            ),
        )
    }

    @PostMapping("/refresh")
    fun refresh(
        @AuthenticationPrincipal userId: Long?,
        request: HttpServletRequest,
    ): ResponseEntity<SelectPoolRefreshResponse> {
        if (verifiedProfile(userId, request) == null || userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        }
        val service = reserve ?: return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build()
        val accepted = service.publishImmediate(
            TurnDaemonCommand.SelectPoolRefresh(
                ownerUserId = userId.toInt(),
                requestedAt = clock.instant().toString(),
            ),
            // OPENSAM-197 — result-read ownership witness (this path records no general_id).
            ownerUserId = userId.toInt(),
        )
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(
            SelectPoolRefreshResponse(status = "AVAILABLE", requestId = accepted.requestId),
        )
    }

    private fun toCard(row: SelectPoolReadRow): SelectPoolCard {
        val info = row.info
        return SelectPoolCard(
            uniqueName = row.uniqueName,
            generalName = string(info, "generalName") ?: row.uniqueName,
            picture = string(info, "picture"),
            imageServer = int(info, "imgsvr") ?: int(info, "imageServer") ?: 0,
            leadership = int(info, "leadership"),
            strength = int(info, "strength"),
            intel = int(info, "intel"),
            politics = int(info, "politics"),
            charm = int(info, "charm"),
            dex = intList(info["dex"], 5),
            personality = string(info, "ego"),
            specialDomestic = string(info, "specialDomestic"),
            specialWar = string(info, "specialWar"),
            statEditable = row.statEditable,
        )
    }

    private fun string(info: Map<String, Any?>, key: String): String? = info[key] as? String

    private fun int(info: Map<String, Any?>, key: String): Int? = when (val value = info[key]) {
        is Number -> value.toInt()
        is String -> value.toIntOrNull()
        else -> null
    }

    private fun intList(value: Any?, size: Int): List<Int> {
        val values = (value as? List<*>).orEmpty().map {
            when (it) {
                is Number -> it.toInt()
                is String -> it.toIntOrNull() ?: 0
                else -> 0
            }
        }
        return List(size) { values.getOrElse(it) { 0 } }
    }

    private fun verifiedProfile(userId: Long?, request: HttpServletRequest): GatewayProfileClaims? =
        userId
            ?.takeIf { it in 1..Int.MAX_VALUE.toLong() }
            ?.let { JwtVerifyFilter.profile(request)?.takeIf { profile -> profile.userId == it } }

    private companion object {
        const val DEFAULT_SHOW_IMAGE_LEVEL = 3
    }
}

data class SelectPoolResponse(
    val result: Boolean,
    val generalId: Int?,
    val validUntil: String?,
    val pick: List<SelectPoolCard>,
    val customOptions: SelectPoolCustomOptions,
    val member: SelectPoolMember,
)

data class SelectPoolCustomOptions(
    val stat: Boolean,
    val personality: Boolean,
    val picture: Boolean,
    val personalities: List<SelectPoolPersonalityOption>,
)

data class SelectPoolPersonalityOption(
    val code: String,
    val name: String,
)

data class SelectPoolMember(
    val name: String,
    val canUsePicture: Boolean,
)

data class SelectPoolRefreshResponse(
    val status: String,
    val requestId: String,
)

data class SelectPoolCard(
    val uniqueName: String,
    val generalName: String,
    val picture: String?,
    val imageServer: Int,
    val leadership: Int?,
    val strength: Int?,
    val intel: Int?,
    val politics: Int?,
    val charm: Int?,
    val dex: List<Int>,
    val personality: String?,
    val specialDomestic: String?,
    val specialWar: String?,
    val statEditable: Boolean,
)
