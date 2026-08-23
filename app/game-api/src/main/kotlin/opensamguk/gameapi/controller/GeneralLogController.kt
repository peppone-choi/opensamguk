package opensamguk.gameapi.controller

import opensamguk.gameapi.owner.GeneralResolver
import opensamguk.gameapi.read.AdminGeneralLogReadRepository
import opensamguk.gameapi.read.GeneralReadRepository
import opensamguk.gameapi.read.SecretPermissionReader
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * Nation/GetGeneralLog 포팅 — 역사 PHP 기준 (ADR-LITE-042; 현재 제품 정본 아님) `hwe/sammo/API/Nation/GetGeneralLog.php`.
 * (`General/GetGeneralLog`는 이를 상속하되 checkPermission을 무력화해 본인 로그를
 * 누구나(재야 포함) 보게 하는 self-view 변형 — 별도 갭, 루프 LEDGER 백로그.)
 *
 * 권한 사슬(checkPermission, PHP :60-92)과 에러 메시지는 byte 그대로:
 *  1. secretPermission < 0 → '국가에 소속되어있지 않습니다.'
 *  2. permission < 1       → '권한이 부족합니다. 수뇌부가 아니거나 사관년도가 부족합니다.'
 *  3. 타깃 타국             → '같은 나라의 장수가 아닙니다.'
 *     (PHP는 타깃 행 미존재 시에도 null !== nation 비교로 같은 메시지에 떨어진다 — 동일 동작)
 *  4. generalAction && 타깃 npc<2 && 타깃≠본인 && permission<2
 *                          → '권한이 부족합니다. 유저 장수의 개인 기록은 수뇌만 열람 가능합니다.'
 *
 * 응답(launch, PHP :120-168): {result, reqType, generalID, log} — log는
 * `Util::convertPairArrayToDict`의 id desc 삽입 순서를 보존한 dict(LinkedHashMap 직렬화).
 * 리더는 func_history.php의 general_record SQL과 등가인 log_entry(scope=GENERAL) 쿼리
 * (recent=DESC LIMIT 30, more=id<reqTo). PHP `checkLimit`(refresh_score 접속 제한)은
 * general_access_log 영속 원천 부재(HARDCODE_INVENTORY D3-03)로 BLOCKED — 분기 자체가 발화 불가.
 *
 * secretPermission은 W0-3 공용 헬퍼 [SecretPermissionReader] 정본(0..4/-1) —
 * PHP `checkSecretPermission($me)`(GetGeneralLog.php:60, 기본 인자 = checkSecretLimit true)와
 * 동일하게 ambassador/auditor/penalty/사관년도(belong>=secretlimit) 분기가 모두 LIVE다.
 * (에러 문구 '…사관년도가 부족합니다'가 바로 belong<secretlimit 분기의 존재 증거.)
 */
@RestController
@RequestMapping("/api/general-log")
class GeneralLogController(
    private val resolver: GeneralResolver,
    private val generals: GeneralReadRepository,
    private val logs: AdminGeneralLogReadRepository,
    private val secretPermission: SecretPermissionReader,
) {

    @GetMapping
    fun generalLog(
        @AuthenticationPrincipal userId: Long?,
        @RequestParam("generalID") generalID: Int,
        @RequestParam("reqType") reqType: String,
        @RequestParam("reqTo", required = false) reqTo: Int?,
    ): Map<String, Any?> {
        val me = userId?.let { resolver.resolve(it) }
        val permission = secretPermission.of(me)
        if (permission < 0) return err("국가에 소속되어있지 않습니다.")
        if (permission < 1) return err("권한이 부족합니다. 수뇌부가 아니거나 사관년도가 부족합니다.")

        val target = generals.findById(generalID).orElse(null)
        if ((target?.nationId ?: -1) != me!!.nationId) return err("같은 나라의 장수가 아닙니다.")

        if (
            reqType == GENERAL_ACTION &&
            (target?.npcState ?: 0) < 2 &&
            generalID != me.general.id &&
            permission < 2
        ) {
            return err("권한이 부족합니다. 유저 장수의 개인 기록은 수뇌만 열람 가능합니다.")
        }

        val rows = when (reqType) {
            // PHP getGeneralHistoryLogWithLogID — LIMIT 없음.
            GENERAL_HISTORY -> logs.findAllHistoryByGeneral(generalID)
            GENERAL_ACTION -> page(generalID, "ACTION", reqTo)
            BATTLE_RESULT -> page(generalID, "BATTLE_BRIEF", reqTo)
            BATTLE_DETAIL -> page(generalID, "BATTLE_DETAIL", reqTo)
            else -> return err("잘못된 요청입니다: $reqType")
        }

        // PHP Util::convertPairArrayToDict — id desc 삽입 순서를 보존하는 dict.
        val log = LinkedHashMap<String, String>()
        for (r in rows) log[r.id.toString()] = r.text

        return linkedMapOf(
            "result" to true,
            "reqType" to reqType,
            "generalID" to generalID,
            "log" to log,
        )
    }

    private fun page(generalID: Int, category: String, reqTo: Int?) =
        if (reqTo == null) logs.findRecentByGeneral(generalID, category, PAGE_SIZE)
        else logs.findRecentByGeneralBefore(generalID, category, reqTo, PAGE_SIZE)

    private fun err(reason: String): Map<String, Any?> = linkedMapOf("result" to false, "reason" to reason)

    private companion object {
        const val GENERAL_HISTORY = "generalHistory"
        const val GENERAL_ACTION = "generalAction"
        const val BATTLE_RESULT = "battleResult"
        const val BATTLE_DETAIL = "battleDetail"
        /** PHP launch()의 고정 페이지 크기(:144,:152,:160 — 30건). */
        const val PAGE_SIZE = 30
    }
}
