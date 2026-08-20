package opensamguk.gameapi.web

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.http.HttpServletRequest
import opensamguk.common.auth.GatewayPrincipal
import opensamguk.common.constants.CityConst
import opensamguk.common.constants.GameConst
import opensamguk.common.wire.TurnDaemonCommand
import opensamguk.gameapi.member.toMemberProfile
import opensamguk.gameapi.owner.GeneralOwnershipClassifier
import opensamguk.gameapi.read.CityReadRepository
import opensamguk.gameapi.read.GameKvReadRepository
import opensamguk.gameapi.read.GeneralReadRepository
import opensamguk.gameapi.read.WorldStateReadEntity
import opensamguk.gameapi.read.WorldStateReadRepository
import opensamguk.gameapi.reserve.CommandReserveService
import opensamguk.gameapi.security.JwtVerifyFilter
import opensamguk.infra.read.UserRepository
import opensamguk.logic.inheritance.InheritCatalog
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * B1 장수생성(재야→일반/Join) 인테이크 컨트롤러.
 *
 * PHP `hwe/sammo/API/General/Join.php` 의 validateArgs + launch() 중 인테이크/검증 부분을 담당.
 * POST `/api/join` — 로그인 유저가 새 장수를 생성(재야 등록). 즉시 데몬커맨드
 * (`TurnDaemonCommand.MakeGeneral`)를 발행해 엔진 핸들러가 RNG draw + INSERT를 수행.
 *
 * Precheck (PHP Join.php:41–85, 178–256):
 *   - 로그인 필요
 *   - user당 1장수 (general.user_id = userId 이미 존재 시 deny)
 *   - 이름 중복 불가 (general.name = name 이미 존재 시 deny)
 *   - five-stat 합계 ≤ defaultStatTotal(275), 각 stat ∈ [defaultStatMin(15), defaultStatMax(80)]
 *
 * 응답 규약:
 *   - 성공 → 202 Accepted + {status:"AVAILABLE", requestId}
 *   - deny → 200 OK + {status:"BLOCKED", reason}
 *   - 미인증 → 401 Unauthorized
 */
@RestController
@RequestMapping("/api/join")
class JoinController(
    private val generals: GeneralReadRepository,
    private val worldStates: WorldStateReadRepository,
    private val reserve: CommandReserveService,
    private val gameKv: GameKvReadRepository,
    private val cities: CityReadRepository,
    private val objectMapper: ObjectMapper,
    private val ownership: GeneralOwnershipClassifier,
    private val users: UserRepository,
) {

    data class JoinRequest(
        val name: String,
        val leadership: Int,
        val strength: Int,
        val intel: Int,
        val politics: Int = 50,
        val charm: Int = 50,
        val character: String = "Random",
        val pic: Boolean = true,
        val inheritSpecial: String? = null,
        val inheritTurntimeZone: Int? = null,
        val inheritCity: Int? = null,
        val inheritBonusStat: List<Int>? = null,
    )

    data class JoinResponse(
        val status: String,
        val reason: String? = null,
        val requestId: String? = null,
    )

    data class JoinMember(
        val name: String,
        val picture: String?,
        val imageServer: Int,
        val canUsePicture: Boolean,
    )

    data class JoinCity(
        val id: Int,
        val name: String,
        val region: String,
    )

    data class JoinInheritCosts(
        val special: Int,
        val turntime: Int,
        val city: Int,
        val stat: Int,
    )

    data class JoinFormResponse(
        val result: Boolean,
        val member: JoinMember,
        val inheritTotalPoint: Int,
        val inheritCosts: JoinInheritCosts,
        val turnTermMinutes: Int,
        val cities: List<JoinCity>,
        val availableSpecialWar: Map<String, Map<String, String>>,
        val geniusRemaining: Int,
    )

    @GetMapping
    fun form(
        @AuthenticationPrincipal userId: Long?,
        servletRequest: HttpServletRequest,
    ): ResponseEntity<JoinFormResponse> {
        if (userId == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        val profile = verifiedProfile(userId, servletRequest)
            ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        val world = activeWorld()
        val member = resolveMember(profile, world?.config ?: emptyMap(), requested = true)
            ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        val cityRows = cities.findAll()
            .map {
                JoinCity(
                    id = it.id,
                    name = it.name,
                    region = CityConst.regionMap[it.region]?.toString() ?: it.region.toString(),
                )
            }
            .sortedWith(compareBy(JoinCity::region, JoinCity::name))

        return ResponseEntity.ok(
            JoinFormResponse(
                result = true,
                member = member,
                inheritTotalPoint = currentPreviousPoint(userId.toInt()).toInt(),
                inheritCosts = JoinInheritCosts(
                    special = GameConst.inheritBornSpecialPoint,
                    turntime = GameConst.inheritBornTurntimePoint,
                    city = GameConst.inheritBornCityPoint,
                    stat = GameConst.inheritBornStatPoint,
                ),
                turnTermMinutes = (world?.tickSeconds?.div(60) ?: 60).coerceAtLeast(1),
                cities = cityRows,
                availableSpecialWar = InheritCatalog.availableSpecialWar(),
                geniusRemaining = currentGeniusRemaining(),
            ),
        )
    }

    @PostMapping
    fun join(
        @AuthenticationPrincipal userId: Long?,
        servletRequest: HttpServletRequest,
        @RequestBody request: JoinRequest,
    ): ResponseEntity<JoinResponse> {
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(JoinResponse(status = "BLOCKED", reason = "로그인이 필요합니다."))
        }

        val profile = verifiedProfile(userId, servletRequest)
            ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(JoinResponse(status = "BLOCKED", reason = "로그인이 필요합니다."))
        val worldConfig = activeWorld()?.config ?: emptyMap()
        val blockGeneralCreate = intConfig(worldConfig["block_general_create"]) ?: 0
        if (blockGeneralCreate and 1 != 0) {
            return ResponseEntity.ok(
                JoinResponse(status = "BLOCKED", reason = "장수 직접 생성이 불가능한 모드입니다."),
            )
        }

        val generalCount = generals.countByNpcStateLessThan(2)
        val maxGeneral = intConfig(worldConfig["maxgeneral"]) ?: GameConst.defaultMaxGeneral

        var currentOwnership = ownership.classify(userId)
        if (currentOwnership is GeneralOwnershipClassifier.Ownership.Stale) {
            ownership.repair(currentOwnership)
            currentOwnership = ownership.classify(userId)
        }
        if (currentOwnership is GeneralOwnershipClassifier.Ownership.LiveOwned ||
            currentOwnership is GeneralOwnershipClassifier.Ownership.CorrelatedPending ||
            currentOwnership is GeneralOwnershipClassifier.Ownership.Stale
        ) {
            return ResponseEntity.ok(
                JoinResponse(status = "BLOCKED", reason = "이미 등록하셨습니다!"),
            )
        }

        // 2. Name non-empty + uniqueness (Join.php:184)
        val trimmedName = request.name.trim()
        if (generals.existsByName(trimmedName)) {
            return ResponseEntity.ok(
                JoinResponse(status = "BLOCKED", reason = "이미 있는 장수입니다. 다른 이름으로 등록해 주세요!"),
            )
        }
        if (maxGeneral <= generalCount) {
            return ResponseEntity.ok(
                JoinResponse(status = "BLOCKED", reason = "더이상 등록할 수 없습니다!"),
            )
        }
        if (trimmedName.isEmpty()) {
            return ResponseEntity.ok(
                JoinResponse(status = "BLOCKED", reason = "이름이 짧습니다. 다시 가입해주세요!"),
            )
        }
        if (legacyDisplayWidth(trimmedName) > 18 || trimmedName.any { it.isISOControl() }) {
            return ResponseEntity.ok(
                JoinResponse(status = "BLOCKED", reason = "이름이 유효하지 않습니다. 다시 가입해주세요!"),
            )
        }

        // 3. Stat range checks (Join.php:196)
        val l = request.leadership
        val s = request.strength
        val i = request.intel
        val p = request.politics
        val c = request.charm
        if (l < GameConst.defaultStatMin || l > GameConst.defaultStatMax ||
            s < GameConst.defaultStatMin || s > GameConst.defaultStatMax ||
            i < GameConst.defaultStatMin || i > GameConst.defaultStatMax ||
            p < GameConst.defaultStatMin || p > GameConst.defaultStatMax ||
            c < GameConst.defaultStatMin || c > GameConst.defaultStatMax
        ) {
            return ResponseEntity.ok(
                JoinResponse(
                    status = "BLOCKED",
                    reason = "능력치는 ${GameConst.defaultStatMin}~${GameConst.defaultStatMax} 사이여야 합니다.",
                ),
            )
        }
        val total = l + s + i + p + c
        if (total > GameConst.defaultStatTotal) {
            return ResponseEntity.ok(
                JoinResponse(
                    status = "BLOCKED",
                    reason = "능력치가 ${GameConst.defaultStatTotal}을 넘어섰습니다. 다시 가입해주세요!",
                ),
            )
        }
        if (request.character != "Random" && request.character !in GameConst.availablePersonality) {
            return ResponseEntity.ok(JoinResponse(status = "BLOCKED", reason = "'character' 항목이 올바르지 않습니다."))
        }
        if (request.inheritSpecial != null && request.inheritSpecial !in GameConst.availableSpecialWar) {
            return ResponseEntity.ok(JoinResponse(status = "BLOCKED", reason = "'inheritSpecial' 항목이 올바르지 않습니다."))
        }
        if (request.inheritTurntimeZone != null && request.inheritTurntimeZone !in 0..59) {
            return ResponseEntity.ok(JoinResponse(status = "BLOCKED", reason = "'inheritTurntimeZone' 항목이 올바르지 않습니다."))
        }
        if (request.inheritCity != null && !cities.existsById(request.inheritCity)) {
            return ResponseEntity.ok(
                JoinResponse(status = "BLOCKED", reason = "도시가 잘못 지정되었습니다. 다시 가입해주세요!"),
            )
        }
        val inheritBonusStat = when {
            request.inheritBonusStat == null -> null
            request.inheritBonusStat.size != 3 -> {
                return ResponseEntity.ok(
                    JoinResponse(status = "BLOCKED", reason = "보너스 능력치가 잘못 지정되었습니다. 다시 가입해주세요!"),
                )
            }
            request.inheritBonusStat.any { it < 0 } -> {
                return ResponseEntity.ok(
                    JoinResponse(status = "BLOCKED", reason = "보너스 능력치가 음수입니다. 다시 가입해주세요!"),
                )
            }
            request.inheritBonusStat.sum() == 0 -> null
            request.inheritBonusStat.sum() !in 3..5 -> {
                return ResponseEntity.ok(
                    JoinResponse(status = "BLOCKED", reason = "보너스 능력치 합이 잘못 지정되었습니다. 다시 가입해주세요!"),
                )
            }
            else -> request.inheritBonusStat
        }
        val inheritRequiredPoint =
            (if (request.inheritCity != null) GameConst.inheritBornCityPoint else 0) +
                (if (inheritBonusStat != null) GameConst.inheritBornStatPoint else 0) +
                (if (request.inheritSpecial != null) GameConst.inheritBornSpecialPoint else 0) +
                (if (request.inheritTurntimeZone != null) GameConst.inheritBornTurntimePoint else 0)
        if (currentPreviousPoint(userId.toInt()) < inheritRequiredPoint) {
            return ResponseEntity.ok(
                JoinResponse(status = "BLOCKED", reason = "유산 포인트가 부족합니다. 다시 가입해주세요!"),
            )
        }
        if (request.inheritSpecial != null && currentGeniusRemaining() == 0) {
            return ResponseEntity.ok(
                JoinResponse(status = "BLOCKED", reason = "이미 천재가 모두 나타났습니다. 다시 가입해주세요!"),
            )
        }

        // 4. Publish immediate daemon command (no general_turn ring — Model B intake)
        val member = resolveMember(profile, worldConfig, requested = request.pic)
            ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(JoinResponse(status = "BLOCKED", reason = "로그인이 필요합니다."))
        val command = TurnDaemonCommand.MakeGeneral(
            userId = userId.toInt(),
            name = trimmedName,
            leadership = l,
            strength = s,
            intel = i,
            politics = p,
            charm = c,
            character = request.character,
            picture = member.picture,
            ownerName = member.name,
            imgsvr = member.imageServer,
            inheritSpecial = request.inheritSpecial,
            inheritTurntimeZone = request.inheritTurntimeZone,
            inheritCity = request.inheritCity,
            inheritBonusStat = inheritBonusStat,
        )
        // OPENSAM-197 — result-read ownership witness. Join creates the general, so there is no
        // general_id to own the row yet.
        val result = reserve.publishImmediate(command, ownerUserId = userId.toInt())
        return ResponseEntity.status(HttpStatus.ACCEPTED)
            .body(JoinResponse(status = "AVAILABLE", requestId = result.requestId))
    }

    private fun intConfig(value: Any?): Int? = when (value) {
        is Number -> value.toInt()
        is String -> value.toIntOrNull()
        else -> null
    }

    private fun activeWorld(): WorldStateReadEntity? =
        worldStates.findById(1).orElse(null) ?: worldStates.findById(0).orElse(null)

    /** 표시용 회원 정보는 토큰이 아니라 `users` 행에서 읽는다(OPENSAM-220). 행이 없으면 null → 401. */
    private fun resolveMember(
        principal: GatewayPrincipal,
        worldConfig: Map<String, Any?>,
        requested: Boolean,
    ): JoinMember? {
        val member = users.findById(principal.userId).orElse(null)?.toMemberProfile() ?: return null
        val showImageLevel = intConfig(worldConfig["show_img_level"]) ?: 3
        val canUsePicture = showImageLevel >= 1 && member.grade >= 1 && !member.picture.isNullOrBlank()
        val usePicture = requested && canUsePicture
        return JoinMember(
            name = member.name,
            picture = if (usePicture) member.picture else null,
            imageServer = if (usePicture) member.imageServer else 0,
            canUsePicture = canUsePicture,
        )
    }

    private fun verifiedProfile(userId: Long, request: HttpServletRequest): GatewayPrincipal? =
        JwtVerifyFilter.principal(request)?.takeIf { it.userId == userId }

    private fun currentPreviousPoint(userId: Int): Double {
        val row = gameKv.findByTableAndNamespaceAndKey(
            "inheritance",
            "inheritance_$userId",
            "previous",
        ) ?: return 0.0
        val node = runCatching { objectMapper.readTree(row.value) }.getOrNull() ?: return 0.0
        return when {
            node.isArray -> node.get(0)?.asDouble(0.0) ?: 0.0
            node.isNumber -> node.asDouble(0.0)
            else -> 0.0
        }
    }

    private fun currentGeniusRemaining(): Int {
        val row = gameKv.findByTableAndNamespaceAndKey("game_env", "game_env", "genius")
            ?: return GameConst.defaultMaxGenius
        val node = runCatching { objectMapper.readTree(row.value) }.getOrNull()
            ?: return GameConst.defaultMaxGenius
        return node.asInt(GameConst.defaultMaxGenius)
    }

    private fun legacyDisplayWidth(value: String): Int =
        value.codePoints()
            .map { codePoint ->
                when {
                    codePoint <= 0x1F || codePoint == 0x7F -> 0
                    codePoint < 0x100 -> 1
                    else -> 2
                }
            }
            .sum()
}
