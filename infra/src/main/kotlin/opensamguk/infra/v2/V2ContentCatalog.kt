package opensamguk.infra.v2

import org.springframework.core.io.support.PathMatchingResourcePatternResolver

/**
 * OPENSAM-35 0A-d — read-only catalog loader for `content/v2/`.
 *
 * It reuses the [PathMatchingResourcePatternResolver] pattern from
 * `ScenarioCatalogService` (`app/gateway-api/.../service/ScenarioCatalogService.kt:13-16`). The scope is
 * the only difference: this loader sees only direct `*.json` files in [DEFAULT_LOCATION].
 *
 * ## What this class does **not** do (the core of 0A-d)
 *
 * - **No startup seed.** It does not implement `ApplicationRunner` or `CommandLineRunner`. No method is
 *   invoked automatically at boot; the classpath is read only when the caller invokes [names] or [read].
 *   It has no connection to the v1 `ScenarioSeedRunner` path.
 * - **No database writes.** It does not depend on `DataSource`, `JdbcTemplate`, `EntityManager`, or
 *   `ChangeRecorder`. `V2ContentCatalogTest` enforces both properties with a class-file constant-pool scan.
 * - **No global classpath scan.** The pattern is limited to direct `.json` files at the location and omits
 *   `**`, so it cannot recurse into subdirectories. This prevents the inverse of S1's recursive Flyway scan.
 * - **No write methods.** The two read methods are its entire API.
 *
 * When there are no content files, [names] returns an empty list rather than throwing. The `classpath*:`
 * prefix also returns an empty array when the root is absent.
 *
 * @param location A directory relative to the classpath root. The default is [DEFAULT_LOCATION]. The
 *   parameter exists so tests can measure scope isolation; production code uses the default.
 */
class V2ContentCatalog(private val location: String = DEFAULT_LOCATION) {

    private val resolver = PathMatchingResourcePatternResolver()

    /** Sorted names of direct `*.json` files in [location], or an empty list when none exist. */
    fun names(): List<String> = entries().mapNotNull { it.filename }.sorted()

    /** Raw contents of an entry returned by [names], or `null`; filename matching prevents path traversal. */
    fun read(name: String): String? = entries()
        .firstOrNull { it.filename == name }
        ?.inputStream
        ?.use { it.readBytes().toString(Charsets.UTF_8) }

    private fun entries() = resolver.getResources("classpath*:$location/*.json")

    companion object {
        /** Contractual sibling path that cannot overlap v1 resources (`scenario/`, `db/migration/`). */
        const val DEFAULT_LOCATION: String = "content/v2"
    }
}
