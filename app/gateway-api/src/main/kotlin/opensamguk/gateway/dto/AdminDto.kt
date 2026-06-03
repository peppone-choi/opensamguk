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
 * 어드민 "서버 제어" — 각 서비스의 실제 실행 버전.
 *
 * gateway는 자기 BuildProperties에서, gameApi/gameEngine은 내부망 `/actuator/info` fan-out으로 수집.
 * **game-engine은 deployer 자동 재배포에서 제외**되므로 스테이트리스만 올리면 엔진 버전이 뒤처질 수 있다 —
 * 그래서 엔진 버전을 따로 보여주고, 어느 서비스든 gateway와 버전이 다르면 [skew]=true로 표시한다.
 */
data class VersionResponse(
    val gateway: ServiceVersion,
    val gameApi: ServiceVersion,
    val gameEngine: ServiceVersion,
    val skew: Boolean,
)

/**
 * 업데이트 트리거 결과. `ok=false`면 `message`에 사유(미설정/실패)를 담는다. `detail`은 Watchtower의
 * 원시 응답(스캔/갱신/실패 카운트 등) 또는 null.
 */
data class DeployResult(
    val ok: Boolean,
    val message: String,
    val detail: String? = null,
)

/**
 * deployer 상태 — 현재 배포된 IMAGE_TAG(`currentTag`) + 배포 가능한 태그 목록(`availableTags`, GHCR).
 * `configured=false`면 로컬/미배포(또는 미설정)이며 `message`에 사유. 다운그레이드 = 더 낮은 태그 선택.
 */
data class DeployStatus(
    val configured: Boolean,
    val currentTag: String?,
    val availableTags: List<String>,
    val message: String? = null,
)

/** 재배포 요청 — 목표 버전 태그(선택/다운그레이드/특정버전 기동). */
data class DeployRequest(
    val tag: String,
)
