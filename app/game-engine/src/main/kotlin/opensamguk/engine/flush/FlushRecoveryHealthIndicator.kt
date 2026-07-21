package opensamguk.engine.flush

import org.springframework.boot.actuate.health.Health
import org.springframework.boot.actuate.health.HealthIndicator
import org.springframework.stereotype.Component

/**
 * OPENSAM-132 — readiness fails while world is in FLUSH_RETRY / RELOAD_REQUIRED.
 * Exposes non-sensitive recovery details only (mode, worldId, generation, reason).
 */
@Component
class FlushRecoveryHealthIndicator(
    private val gateProvider: FlushRecoveryGateProvider,
) : HealthIndicator {

    override fun health(): Health {
        val gate = gateProvider.gateOrNull() ?: return Health.up()
            .withDetail("recovery", "no_world")
            .build()
        val snap = gate.snapshot()
        return if (snap.ready) {
            Health.up()
                .withDetail("mode", snap.mode.name)
                .build()
        } else {
            Health.down()
                .withDetail("mode", snap.mode.name)
                .withDetail("worldId", snap.worldId)
                .withDetail("generation", snap.generation)
                .withDetail("reason", snap.reason)
                .build()
        }
    }
}

/**
 * Holds the live [FlushRecoveryGate] once [TurnRunService] materializes.
 * Health indicator must not force world load.
 */
@Component
class FlushRecoveryGateProvider {
    @Volatile
    private var gate: FlushRecoveryGate? = null

    fun bind(gate: FlushRecoveryGate) {
        this.gate = gate
    }

    fun gateOrNull(): FlushRecoveryGate? = gate
}
