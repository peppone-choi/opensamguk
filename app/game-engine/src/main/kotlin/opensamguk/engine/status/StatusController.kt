package opensamguk.engine.status

import org.springframework.beans.factory.annotation.Value
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class TurnDaemonStatus(
    val profile: String,
    val state: String,
    val running: Boolean,
    val paused: Boolean,
    val queueDepth: Int,
)

@RestController
@RequestMapping("/admin/turn-daemon")
class StatusController(
    @Value("\${opensamguk.profile}") private val profile: String,
) {
    @GetMapping("/status")
    fun status(): TurnDaemonStatus =
        TurnDaemonStatus(profile = profile, state = "idle", running = false, paused = false, queueDepth = 0)
}
