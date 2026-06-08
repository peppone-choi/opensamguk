package opensamguk.gateway.dto

/**
 * 한 서비스의 실행 중 빌드 버전. `reachable=false`면 내부망 조회 실패(미배포/다운). `version`/`buildTime`은
 * Gradle `buildInfo`(META-INF/build-info.properties), `imageTag`는 빌드 시 주입된 GHCR 태그.
 */
data class ServiceVersion(
    val reachable: Boolean,
    val version: String?,
    val imageTag: String?,
    val buildTime: String?,
)

/**
 * 한 게임 서버의 실행 버전 — 그 서버의 game-api/game-engine을 내부망 `/actuator/info` fan-out으로 수집.
 *
 * game-engine은 deployer 자동 재배포에서 제외되므로 스테이트리스만 올리면 엔진 버전이 뒤처질 수 있다 —
 * 이 서버의 game-api/game-engine 중 어느 하나라도 gateway와 버전이 다르면 [skew]=true.
 */
data class ServerVersion(
    val id: String,
    val name: String,
    val gameApi: ServiceVersion,
    val gameEngine: ServiceVersion,
    val skew: Boolean,
)

/**
 * 어드민 "서버 제어" — gateway 자신 + 각 게임 서버의 실제 실행 버전.
 *
 * gateway는 공유라 하나, game-api/game-engine은 서버마다 독립 스택이라 [servers]로 서버별 수집한다.
 * 어느 서버든 버전 불일치면 [skew]=true(전체 경고 플래그).
 */
data class VersionResponse(
    val gateway: ServiceVersion,
    val servers: List<ServerVersion>,
    val skew: Boolean,
)

/**
 * 업데이트 트리거 결과. `ok=false`면 `message`에 사유(미설정/알수없는서버/실패)를 담는다.
 * `detail`은 deployer의 원시 응답(compose pull/up 로그 등) 또는 null.
 */
data class DeployResult(
    val ok: Boolean,
    val message: String,
    val detail: String? = null,
)

/**
 * deployer 상태 — 한 서버(`serverId`)의 현재 배포된 IMAGE_TAG(`currentTag`) + 배포 가능한 태그 목록
 * (`availableTags`, GHCR). `configured=false`면 로컬/미배포(또는 미설정)이며 `message`에 사유.
 * 다운그레이드 = `availableTags` 중 더 낮은 태그 선택.
 */
data class DeployStatus(
    val configured: Boolean,
    val serverId: String?,
    val currentTag: String?,
    val availableTags: List<String>,
    val message: String? = null,
)

/** 재배포 요청 — 대상 서버(`serverId`) + 목표 버전 태그(선택/다운그레이드/특정버전 기동). */
data class DeployRequest(
    val serverId: String,
    val tag: String,
)

// ──────────────────────────────────────────────────────────────────────────
// B2 회원관리(루트DB) — legacy j_get_userlist / j_set_userlist / BanEmailAddress 패러티
// ──────────────────────────────────────────────────────────────────────────

/**
 * 회원관리 목록의 한 행 — legacy `j_get_userlist.php:28-40`의 userList 항목 대응.
 *
 * legacy 필드 매핑:
 *   userID→[id], userName→[username], email→[email], authType→[authType],
 *   userGrade→[grade]+[gradeLabel], blockUntil→[blockUntil], nickname→[nickname],
 *   icon→[icon], joinDate→[joinDate], loginDate→[lastLoginAt], deleteAfter→[deleteAfter].
 *
 * 0.9.0 divergence: legacy GRADE 다단계 대신 role(ADMIN/USER) 단일 게이트를 유지하되,
 * 회원관리 표시는 legacy 라벨 패러티를 위해 [grade]/[gradeLabel]을 노출한다(grade 미설정 시 role로 합성).
 *
 * [generalNamesByServer]: legacy admin_member.ts의 '장수명(서버별)'. 게임 general 데이터는 서버별 게임 DB에
 * 있고 루트DB(gateway)엔 없으므로, gateway-api는 채울 수 없다 → 항상 빈 맵(프론트/게임서버 read에서 별도 채움).
 */
data class AdminUserDto(
    val id: Long,
    val username: String,
    val email: String?,
    val authType: String?,
    val grade: Int?,
    val gradeLabel: String,
    val blockUntil: String?,
    val nickname: String?,
    val icon: String?,
    val joinDate: String?,
    val lastLoginAt: String?,
    val deleteAfter: String?,
    // legacy '장수명(서버별)' — 루트DB엔 general 데이터가 없어 gateway-api는 비운다(서버별 게임DB 소관).
    val generalNamesByServer: Map<String, String> = emptyMap(),
)

/**
 * 회원관리 목록 응답 — legacy `j_get_userlist.php:52-58` 대응.
 * users + 실행 중 서버 목록(servers) + 전역 가입/로그인 허용 플래그(allowJoin/allowLogin).
 */
data class AdminUserListResponse(
    val users: List<AdminUserDto>,
    val servers: List<String>,
    val allowJoin: Boolean,
    val allowLogin: Boolean,
)

/** 시스템 플래그 토글 요청 — legacy `j_set_userlist.php:36-70`의 param(0/1). */
data class SystemToggleRequest(
    val value: Boolean,
)

/** 시스템 플래그 토글 결과 — 갱신 후 전역 가입/로그인 허용 상태. */
data class SystemFlagResponse(
    val allowJoin: Boolean,
    val allowLogin: Boolean,
)

/** 계정 정리 결과 — legacy scrub_*의 `affected` 카운트. */
data class ScrubResult(
    val affected: Int,
)

/**
 * 회원 단일 명령 요청 — legacy `j_set_userlist.php`의 block/set_userlevel param.
 * block=차단 일수(≤0이면 50년), set_userlevel=설정 등급(≥1).
 */
data class UserCommandRequest(
    val param: Int? = null,
)

/**
 * 회원 단일 명령 결과 — legacy의 result/reason/detail 패러티.
 * reset_pw는 [detail]에 임시 비밀번호 안내 문자열을 담는다(legacy `:210`).
 */
data class UserCommandResult(
    val result: Boolean,
    val reason: String? = null,
    val detail: String? = null,
)

/** 이메일 영구차단 요청 — legacy `BanEmailAddress.php`의 email. */
data class BanEmailRequest(
    val email: String,
)

/** 이메일 영구차단 결과 — legacy `BanEmailAddress.php:54-57` 패러티. */
data class BanEmailResult(
    val result: Boolean,
    val reason: String,
)
