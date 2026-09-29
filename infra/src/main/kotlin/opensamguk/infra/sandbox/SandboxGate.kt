package opensamguk.infra.sandbox

/**
 * OPENSAM-35 0A-b — canonical names for the v2 bean-registration gate.
 *
 * A v2 bean is registered only when both `V2_ENABLED=true` (property [PROPERTY]) and profile [PROFILE] are
 * active. Either condition alone must never enable it. The configurations in each application's component-scan
 * root apply the conditions: `opensamguk.engine.v2.V2SandboxConfiguration` and
 * `opensamguk.gameapi.v2.V2SandboxConfiguration`.
 *
 * Both applications use constants from this single owner so a spelling divergence cannot silently open the gate.
 */
object SandboxGate {
    /**
     * Spring property key. Container environment variable `V2_ENABLED` maps here through Spring relaxed binding
     * (`SystemEnvironmentPropertySource`: `_` becomes `.`, then lowercased).
     *
     * `havingValue = "true"` with `matchIfMissing = false` (the default) means unset is disabled.
     */
    const val PROPERTY: String = "v2.enabled"

    /** Profile enabled by `SPRING_PROFILES_ACTIVE=v2-sandbox`. */
    const val PROFILE: String = "v2-sandbox"

    /** Type packages whose beans must remain behind the sandbox gate in all three applications. */
    fun isGatedTypeName(type: String): Boolean =
        type.startsWith("opensamguk.") &&
            (type.contains(".v2.") ||
                type.startsWith("opensamguk.infra.content.") ||
                type.startsWith("opensamguk.infra.sandbox.") ||
                type.startsWith("opensamguk.engine.city.") ||
                type.startsWith("opensamguk.gameapi.city.") ||
                type.startsWith("opensamguk.gameapi.command."))
}

/**
 * Marker bean that exists only when the gate is open.
 *
 * It contains no game logic, database access, or scheduler. Its existence is the gate state; the 0A-f (S4)
 * executable tests assert this type has zero beans in a v1 context.
 *
 * Future v2 beans (ledger stores, command handlers, read/intake controllers, and so on) belong inside each
 * application's `V2SandboxConfiguration`; do not create a new v2 bean outside the gate.
 */
class SandboxMarker
