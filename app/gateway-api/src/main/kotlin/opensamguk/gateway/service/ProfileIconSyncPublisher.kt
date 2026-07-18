package opensamguk.gateway.service

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClient

/**
 * OPENSAM-94 — 프로필 아이콘 typed sync fan-out (gateway → 각 게임 서버 game-api 인테이크).
 *
 * gateway 계정의 canonical 전콘(picture/imgsvr) 변경이 DB commit된 뒤, [ServerRegistry]의 모든 게임
 * 서버로 typed sync를 발행한다. **bounded best-effort** — target 하나의 timeout/5xx가 canonical
 * mutation을 rollback하거나 나머지 target 시도를 막지 않는다(criterion 11). exactly-once/영구 큐/무제한
 * retry를 주장하지 않는다. 실패는 PII 없이 관측 가능하게 남긴다(criterion 12).
 *
 * grade는 gateway 계정의 authoritative 원값(서버측 read)을 그대로 싣는다 — 게임 서버가 eligibility
 * `show_img_level >= 1 && grade >= 1`을 재평가한다. 클라 eligibility boolean은 싣지 않는다.
 */
@Service
class ProfileIconSyncPublisher(
    private val serverRegistry: ServerRegistry,
    @Value("\${PROFILE_SYNC_TOKEN:}") private val syncToken: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val rest = RestClient.create()

    data class SyncPayload(val userId: Long, val picture: String, val imgsvr: Int, val grade: Int)

    /** 등록된 모든 게임 서버로 sync를 발행한다 — target별 격리(한 서버 실패가 나머지를 막지 않음). */
    fun publish(userId: Long, picture: String, imgsvr: Int, grade: Int) {
        val payload = SyncPayload(userId = userId, picture = picture, imgsvr = imgsvr, grade = grade)
        for (server in serverRegistry.all()) {
            try {
                rest.post()
                    .uri("${server.gameApiUrl}/api/internal/profile-icon-sync")
                    .contentType(MediaType.APPLICATION_JSON)
                    .apply { if (syncToken.isNotBlank()) header("X-Profile-Sync-Token", syncToken) }
                    .body(payload)
                    .retrieve()
                    .toBodilessEntity()
            } catch (e: Exception) {
                // 한 target 실패는 격리 — 나머지 target 시도와 canonical 성공을 rollback하지 않는다.
                log.warn("profile-icon.sync fan-out failed server={} error={}", server.id, e.javaClass.simpleName)
            }
        }
    }
}
