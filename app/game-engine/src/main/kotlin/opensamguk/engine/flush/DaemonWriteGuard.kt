package opensamguk.engine.flush

/**
 * The structural invariant for the daemon WRITE path (design §0.1 #3 / §7, research N4):
 * **JDBC-only — never a JPA `EntityManager`/`EntityManagerFactory` and never a Spring-Data
 * write repository.**
 *
 * This is not enforceable by the type system alone: `:app:game-engine` carries
 * `spring-boot-starter-data-jpa` (for the read side that lives in `:app:game-api`) and `kotlin("jpa")`,
 * so an `EntityManager` could be wired onto the write path by a later edit and still compile. The
 * guarantee is therefore made a TEST (`DaemonNoEntityManagerTest`): the write-path package class
 * files are scanned and asserted to carry NO reference to the forbidden persistence types.
 *
 * This object is the single source of truth for **which packages constitute the write path** and
 * **which types are forbidden** — the guard test reads both lists from here so the contract and its
 * enforcement can never drift. `JdbcFlushExecutor` ([opensamguk.infra.persistence.JdbcFlushExecutor])
 * — the actual SQL sink threaded into [DatabaseHooks] — speaks ONLY
 * `org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate` (plain JDBC) inside a
 * `DataSourceTransactionManager`-backed transaction; the `:infra` half of the same invariant is the
 * sibling `InfraNoEntityManagerTest`.
 */
object DaemonWriteGuard {

    /**
     * The compiled-class package paths (slash form, relative to a `classes/.../` root) that make up
     * the daemon write path. The guard scans whichever of these directories exist (the `run` package
     * arrives in Task F5; an absent directory is vacuously clean).
     */
    val writePathPackages: List<String> = listOf(
        "opensamguk/engine/flush",
        "opensamguk/engine/turn",
        "opensamguk/engine/run",
    )

    /**
     * Internal JVM names (slash form, as they appear verbatim in a class constant pool) of the
     * persistence types BANNED on the write path. A class that imported or referenced any of these
     * would carry the internal name in its constant pool, so a byte-level substring scan detects it
     * without an ArchUnit/bytecode-parser dependency.
     */
    val forbiddenInternalNames: List<String> = listOf(
        "jakarta/persistence/EntityManager",
        "jakarta/persistence/EntityManagerFactory",
        "org/springframework/data/jpa/repository/JpaRepository",
        "org/springframework/data/repository/CrudRepository",
    )
}
