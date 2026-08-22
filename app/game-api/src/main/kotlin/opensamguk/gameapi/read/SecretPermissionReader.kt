package opensamguk.gameapi.read

import opensamguk.gameapi.owner.GeneralResolver
import opensamguk.logic.actions.intake.SecretPermission
import org.springframework.stereotype.Component

/**
 * W0-3 권한 파운데이션 — read API 표면의 정본 secretPermission(0..4 / -1) 헬퍼.
 * (감사 `docs/superpowers/gap/PAGE_PARITY_AUDIT_2026-06-10.md` §5 W0-3)
 *
 * 역사 PHP 기준 (ADR-LITE-042; 현재 제품 정본 아님) `checkSecretPermission($me)`(func.php:390-435)를 logic 단일 정본
 * [SecretPermission.check]로 위임한다. read API의 PHP 호출자들은 모두 **기본 인자**
 * (`$checkSecretLimit = true`)로 호출하므로(j_diplomacy_get_letter.php:33,
 * API/Nation/GetGeneralLog.php:60) 이 헬퍼도 belong/secretlimit 분기를 LIVE로 켠다.
 *
 * 전 분기 커버 — BLOCKED 분기 없음(감사 P1-031의 'BLOCKED 사유 stale' 해소):
 *  - officer_level 사다리: 12→4 / ≥5→2 / >1→1 (func.php:411-421)
 *  - `meta["permission"]`: ambassador→4 / auditor→3 (전용 컬럼 없이 meta 운반)
 *  - `general.penalty` 전용 jsonb 컬럼: noChief→0 즉시 + noTopSecret/noChief/noAmbassador
 *    상한 1/1/2 (func.php:377-389, PenaltyKey.php camelCase 키)
 *  - lv1 사관년도: `meta["belong"]`(부재 시 DDL 기본 1, schema.sql:57) >=
 *    `nation.meta["secretlimit"]`(부재 시 DDL 기본 3, schema.sql:126) → 1.
 *    nation 조회는 PHP의 게으른 DB 조회(func.php:422-424)처럼 이 분기에서만 일어난다.
 *
 * 의도된 소비처(감사 §5 W0-3 — W1 에이전트가 각자 파일에서 이 헬퍼로 수렴):
 *  - P0-08  board: BoardController — `permission<0` 즉시 deny + nation 스코프(회의실 누출 차단)
 *  - P0-15  diplomacy: 응답에 secretPermission 동봉, canWrite = 값>=4
 *  - P0-34/35 mailbox: 외교 메시지 마스킹 + 수락/거절 게이트(MailboxController 자체 포팅 대체)
 *  - P1-023 chief-center: permission>=1 read-only 열람 게이트
 *  - P1-084 nation-finance: editable = officer_level>=5 ∥ permission==4(ambassador)
 */
@Component
class SecretPermissionReader(
    private val nations: NationReadRepository,
) {
    /** 미인증/장수 미보유(null) → -1 (PHP는 로그인 장수 행이 항상 있으므로 -1 하드 deny와 등가). */
    fun of(resolved: GeneralResolver.ResolvedGeneral?): Int =
        resolved?.let { of(it.general) } ?: -1

    /** 정본 산식 — logic [SecretPermission.check] raw 오버로드 위임(checkSecretLimit=true LIVE). */
    fun of(general: GeneralReadEntity): Int =
        SecretPermission.check(
            nationId = general.nationId,
            officerLevel = general.officerLevel,
            meta = general.meta,
            penalty = general.penalty,
            checkSecretLimit = true,
        ) { nationSecretLimit(general.nationId) }

    /**
     * `SELECT secretlimit FROM nation`(func.php:422-424) 등가 — opensamguk은 `nation.meta` 운반.
     * meta 키 부재(시드 1010 미기록) = PHP 신설 행의 DDL 기본 3(schema.sql:126)으로 충실 복원.
     * nation 행 부재는 불능 상태(소속 장수가 있는 국가 행은 항상 존재) — 보수적으로 동일 기본값.
     */
    private fun nationSecretLimit(nationId: Int): Int =
        nations.findById(nationId)
            .map { (it.meta["secretlimit"] as? Number)?.toInt() ?: DEFAULT_SECRET_LIMIT }
            .orElse(DEFAULT_SECRET_LIMIT)

    companion object {
        /** `nation.secretlimit` DDL 기본값(`hwe/sql/schema.sql:126`). */
        const val DEFAULT_SECRET_LIMIT = 3
    }
}
