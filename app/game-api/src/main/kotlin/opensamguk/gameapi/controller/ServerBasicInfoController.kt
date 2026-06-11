package opensamguk.gameapi.controller

import opensamguk.common.constants.GameConst
import opensamguk.gameapi.dto.ServerBasicInfoResponse
import opensamguk.gameapi.dto.ServerGameInfo
import opensamguk.gameapi.dto.ServerMeInfo
import opensamguk.gameapi.owner.GeneralResolver
import opensamguk.gameapi.read.GeneralReadRepository
import opensamguk.gameapi.read.NationReadRepository
import opensamguk.gameapi.read.WorldStateReadEntity
import opensamguk.gameapi.read.WorldStateReadRepository
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * K1 진입(엔트런스) — `GET /api/server-basic-info` (devsam `j_server_basic_info.php` 포팅).
 *
 * gateway 로비가 서버별로 fan-out 호출한다. 인증: [FrontInfoController]와 동일 패턴 — **permitAll +
 * optional principal**. `game{}`은 공개 정보(서버 현황)라 미로그인도 200; `me`는 검증된 JWT 토큰이
 * 있을 때만 해석(없거나 무효면 me=null, 절대 401로 로비 행을 깨뜨리지 않음). JwtVerifyFilter가 유효
 * 토큰이면 principal(userId:Long)을 채운다. 멀티서버에서 gateway가 각 서버 game-api로 같은 Bearer를
 * 포워딩하면, 각 서버가 자기 general_owner로 me를 따로 해석한다.
 *
 * 전 값은 실 world_state / general / nation 쿼리 결과 — 하드코딩·날조 없음(reserved 가오픈 분기는
 * opensamguk에 개념 부재라 미구현, devsam과의 의도적 갭).
 */
@RestController
@RequestMapping("/api")
class ServerBasicInfoController(
    private val resolver: GeneralResolver,
    private val world: WorldStateReadRepository,
    private val generals: GeneralReadRepository,
    private val nations: NationReadRepository,
) {

    @GetMapping("/server-basic-info")
    fun serverBasicInfo(@AuthenticationPrincipal userId: Long?): ResponseEntity<ServerBasicInfoResponse> {
        val w = world.findById(0).orElse(null) ?: world.findAll().firstOrNull()
        val game = w?.let { buildGame(it) }

        // me — owner=userID 장수(없으면 null). GeneralResolver가 정본 me 해석 경로.
        val me = userId?.let { resolver.resolve(it) }?.let { r ->
            ServerMeInfo(name = r.general.name, picture = r.general.picture, imageServer = r.general.imageServer)
        }

        return ResponseEntity.ok(ServerBasicInfoResponse(game = game, me = me))
    }

    private fun buildGame(w: WorldStateReadEntity): ServerGameInfo {
        val config = w.config
        val scenario = (config["title"] ?: w.meta["title"])?.toString()?.takeIf { it.isNotBlank() }
            ?: w.scenarioCode

        // userCnt = npc<2 (PC + 빙의됨), npcCnt = npc>=2 (순수 NPC). opensamguk npcState: 0=PC,1=빙의됨,≥2=NPC.
        val npcCnt = generals.countByNpcStateGreaterThan(1).toInt()
        val userCnt = (generals.count() - generals.countByNpcStateGreaterThan(1)).toInt()
        // nationCnt = nation level>0 (재야 id=0은 항상 level 0이라 제외됨). 전용 카운트 메서드 부재 → in-memory.
        val nationCnt = nations.findAll().count { it.level > 0 }

        // maxgeneral 미기재 시 GameConst.defaultMaxGeneral(패러티 기본 캡)로 폴백 — 0 폴백은 등록마감을
        // 항상 참으로 만들어 등록 자체를 막으므로 금지. 기본 상수는 날조가 아닌 패러티값.
        val maxUserCnt = intOrNull(config["maxgeneral"]) ?: GameConst.defaultMaxGeneral

        val joinMode = config["join_mode"]?.toString()
        val isFiction = boolOrNull(config["fiction"]) ?: false
        // devsam getAutorunInfo(autorun_user)는 opensamguk config에 미기재 → '표준'/'랜덤 임관 전용'만.
        val otherTextInfo = if (joinMode == "onlyRandom") "랜덤 임관 전용" else "표준"

        return ServerGameInfo(
            isUnited = intOrNull(config["isunited"]) ?: 0,
            npcMode = intOrNull(config["npcmode"]) ?: 0,
            year = w.currentYear,
            month = w.currentMonth,
            scenario = scenario,
            maxUserCnt = maxUserCnt,
            turnTerm = w.tickSeconds / 60,
            userCnt = userCnt,
            npcCnt = npcCnt,
            nationCnt = nationCnt,
            fictionMode = if (isFiction) "가상" else "사실",
            joinMode = joinMode,
            blockGeneralCreate = intOrNull(config["block_general_create"]) ?: 0,
            defaultStatTotal = GameConst.defaultStatTotal,
            otherTextInfo = otherTextInfo,
            status = w.status,
        )
    }

    /** jsonb 값(Number/String)을 Int로 안전 변환(부재/형변환 실패 시 null — 날조 없음). */
    private fun intOrNull(v: Any?): Int? = when (v) {
        is Number -> v.toInt()
        is String -> v.toIntOrNull()
        else -> null
    }

    /** jsonb 값(Boolean/Number 0/1/String)을 Boolean으로 안전 변환(부재 시 null). */
    private fun boolOrNull(v: Any?): Boolean? = when (v) {
        is Boolean -> v
        is Number -> v.toInt() != 0
        is String -> v == "1" || v.equals("true", ignoreCase = true)
        else -> null
    }
}
