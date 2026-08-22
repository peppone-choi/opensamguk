package opensamguk.logic.actions.intake

import opensamguk.logic.domain.General

/**
 * `checkSecretPermission()` 충실 포팅 — 역사 PHP 기준 (ADR-LITE-042; 현재 제품 정본 아님) `legacy/devsam-core/hwe/func.php:390-435`
 * (+ `checkSecretMaxPermission` 상한 `func.php:377-389`).
 *
 * W0-3 권한 파운데이션(감사 `docs/superpowers/gap/PAGE_PARITY_AUDIT_2026-06-10.md` §5):
 * 이 객체가 비밀 권한(0..4 / -1)의 **단일 정본**이다. 엔진 intake(Board/Troop/DiplomacyLetter/
 * 재정 세터)와 game-api read 헬퍼(`gameapi.read.SecretPermissionReader`)가 모두 여기로 수렴한다.
 *
 * PHP 원천 대응(전 분기 커버 — BLOCKED 분기 없음):
 *  - `permission`(ambassador/auditor 문자열) — opensamguk은 전용 컬럼 없이 `general.meta["permission"]`로 운반.
 *  - `penalty` — PHP `Json::decode($me['penalty'])`(func.php:395)는 **전용 jsonb 컬럼**
 *    (`V1__baseline.sql:99`, [General.penalty] 필드). `meta["penalty"]` 키가 아니다 — meta를 읽으면
 *    penalty 분기 전체가 무음(no-op)이 된다. 키는 `PenaltyKey.php`의 camelCase enum 값
 *    (`noTopSecret`/`noChief`/`noAmbassador`) — snake_case 금지.
 *  - `belong` — `general.meta["belong"]`. 키 부재 = 시드 미기록 = PHP DDL 기본값 1로 복원
 *    (`hwe/sql/schema.sql:57` `belong INT(2) NULL DEFAULT '1'`).
 *  - `secretlimit` — `nation.meta["secretlimit"]`(읽기는 호출자가 [secretLimit] 공급자로 주입).
 *    PHP는 이 분기에서만 게으르게 DB 조회(func.php:422-424)하므로 공급자도 게으르게 평가한다.
 */
object SecretPermission {

    /** 비밀 접근을 높이는 `general.permission` 문자열 값. */
    const val AMBASSADOR = "ambassador"
    const val AUDITOR = "auditor"

    /** `PenaltyKey.php` camelCase enum 값 — penalty jsonb의 실제 키. */
    const val PENALTY_NO_TOP_SECRET = "noTopSecret"
    const val PENALTY_NO_CHIEF = "noChief"
    const val PENALTY_NO_AMBASSADOR = "noAmbassador"

    /** `general.belong` DDL 기본값(`schema.sql:57`) — meta 키 부재 시의 충실 복원값. */
    const val DEFAULT_BELONG = 1

    /**
     * [General] 진입점 — 엔진 intake 핸들러용(checkSecretLimit 기본 false: 재정 세터/보드/부대가
     * PHP에서 명시적으로 false를 넘기거나 분기 결과에 둔감하다).
     *
     * @param secretLimit nation `secretlimit`(checkSecretLimit=true일 때만 소비)
     */
    fun check(general: General, secretLimit: Int = 0, checkSecretLimit: Boolean = false): Int =
        check(
            nationId = general.nationId,
            officerLevel = general.officerLevel,
            meta = general.meta,
            penalty = general.penalty,
            checkSecretLimit = checkSecretLimit,
            secretLimit = { secretLimit },
        )

    /**
     * raw 진입점 — 도메인 [General]이 없는 호출자(game-api read 엔티티)용 단일 정본.
     *
     * @param meta `general.meta`(permission/belong 운반)
     * @param penalty `general.penalty` 전용 jsonb 컬럼(meta 아님)
     * @param secretLimit nation `secretlimit` 게으른 공급자 — PHP가 이 분기에서만 DB를 조회하듯
     *   officer_level==1 belong 분기에서만 평가된다(func.php:421-427)
     * @return PHP `min(secretMin, secretMax)`; `-1` = 무소속/officer_level 0(하드 deny)
     */
    fun check(
        nationId: Int,
        officerLevel: Int,
        meta: Map<String, Any?>,
        penalty: Map<String, Any?>,
        checkSecretLimit: Boolean = false,
        secretLimit: () -> Int = { 0 },
    ): Int {
        // func.php:397-403 — 무소속/무관직 하드 deny.
        if (nationId == 0) return -1
        if (officerLevel == 0) return -1

        // func.php:407-409 — noChief penalty는 즉시 0(PHP truthy 판정).
        if (phpTruthy(penalty[PENALTY_NO_CHIEF])) return 0

        // func.php:377-389 — checkSecretMaxPermission(penalty) 상한.
        val secretMax = checkSecretMaxPermission(penalty)

        val permission = meta["permission"] as? String
        // func.php:411-427 — secretMin 사다리(분기 순서 = PHP 순서).
        val secretMin = when {
            officerLevel == 12 -> 4
            permission == AMBASSADOR -> 4
            permission == AUDITOR -> 3
            officerLevel >= 5 -> 2
            officerLevel > 1 -> 1
            checkSecretLimit -> {
                // func.php:421-427 — 사관년도(belong) >= nation.secretlimit이면 1.
                val belong = (meta["belong"] as? Number)?.toInt() ?: DEFAULT_BELONG
                if (belong >= secretLimit()) 1 else 0
            }
            else -> 0
        }
        return minOf(secretMin, secretMax)
    }

    /**
     * `checkSecretMaxPermission($penalty)` 포팅(func.php:377-389) — penalty 상한:
     * noTopSecret→1, noChief→1, noAmbassador→2, 무벌점→4 (else-if 사다리 순서 그대로).
     */
    fun checkSecretMaxPermission(penalty: Map<String, Any?>): Int = when {
        phpTruthy(penalty[PENALTY_NO_TOP_SECRET]) -> 1
        phpTruthy(penalty[PENALTY_NO_CHIEF]) -> 1
        phpTruthy(penalty[PENALTY_NO_AMBASSADOR]) -> 2
        else -> 4
    }

    /**
     * PHP `($penalty[key] ?? false)` 진리값 — PHP truthy: null/false/0/"0"/"" 만 falsy.
     * 플래그가 boolean true가 아니라 1/문자열로 인코드돼도 패러티를 유지한다.
     */
    fun phpTruthy(v: Any?): Boolean = when (v) {
        null, false -> false
        is Boolean -> v
        is Number -> v.toDouble() != 0.0
        is String -> v.isNotEmpty() && v != "0"
        else -> true
    }

    /**
     * 모든 내무부 재정 세터(SetNotice/SetScoutMsg/SetRate/SetBill/SetSecretLimit/SetBlockWar/
     * SetBlockScout)가 공유하는 정확한 2줄 게이트: `permission = check(me, false)`,
     * `permission < 0`이면 deny, `officer_level < 5 && permission != 4`이면 deny.
     * PHP 충실 deny 사유를, 허용 시 null을 돌려준다.
     */
    fun financeSetterDenyReason(general: General): String? {
        val permission = check(general, checkSecretLimit = false)
        if (permission < 0) return "권한이 부족합니다."
        if (general.officerLevel < 5 && permission != 4) return "권한이 부족합니다."
        return null
    }
}
