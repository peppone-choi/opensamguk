package opensamguk.engine.v2

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class V2MigrationConventionTest {

    @Test
    fun `v2 migrations are V900 plus world-scoped and forward-only`() {
        val files = V2MigrationSources.v2SqlFiles()
        assertTrue(files.isNotEmpty(), "the test-only V900 probe must remain covered by this convention")

        val violations = files.flatMap { file ->
            V2MigrationConvention.validate(file.name, file.readText()).map { violation ->
                "${V2MigrationSources.relativePath(file)}: $violation"
            }
        }

        assertEquals(emptyList(), violations, "v2 migration convention violations")
    }

    @Test
    fun `validator requires the exact forward-only header on the first line`() {
        val sql = "\n${V2MigrationConvention.FORWARD_ONLY_HEADER}\n" +
            "CREATE TABLE v2_example (world_id integer NOT NULL REFERENCES world_state(id), PRIMARY KEY (world_id));"

        val violations = V2MigrationConvention.validate("V900__v2_example.sql", sql)

        assertTrue(violations.any { it.contains("first line") }, "header placement violations: $violations")
    }

    @Test
    fun `validator accepts an executable world scoped table declaration`() {
        val sql = """
            -- V2-FORWARD-ONLY: rollback is a new compensating V900+ migration.
            CREATE TABLE v2_example (
                world_id integer NOT NULL REFERENCES world_state(id),
                PRIMARY KEY (world_id)
            );
        """.trimIndent()

        assertEquals(emptyList(), V2MigrationConvention.validate("V900__v2_example.sql", sql))
    }

    @Test
    fun `validator rejects a world id declaration that exists only in SQL comments`() {
        val sql = """
            -- V2-FORWARD-ONLY: rollback is a new compensating V900+ migration.
            CREATE TABLE v2_example (
                local_id integer NOT NULL PRIMARY KEY
            );
            -- world_id integer NOT NULL REFERENCES world_state(id)
            /* world_id integer NOT NULL */
        """.trimIndent()

        val violations = V2MigrationConvention.validate("V900__v2_example.sql", sql)

        assertTrue(violations.any { it.contains("world_id") }, "comment-only world_id must not satisfy the contract: $violations")
    }
}

internal object V2MigrationConvention {
    const val MINIMUM_VERSION = 900
    const val FORWARD_ONLY_HEADER = "-- V2-FORWARD-ONLY: rollback is a new compensating V900+ migration."

    private val migrationFileName = Regex("""V(\d+)__[a-z0-9][a-z0-9_]*\.sql""")
    private val worldIdColumn = Regex(
        """\bworld_id\s+(?:integer|bigint|int)\s+not\s+null\b""",
        RegexOption.IGNORE_CASE,
    )
    private val createTableKeyword = Regex("""\bcreate\s+table\b""", RegexOption.IGNORE_CASE)
    private val createTable = Regex(
        """\bcreate\s+table\s+(?:if\s+not\s+exists\s+)?(?:([a-z_][a-z0-9_]*)\.)?([a-z_][a-z0-9_]*)\b""",
        RegexOption.IGNORE_CASE,
    )

    fun validate(fileName: String, sql: String): List<String> {
        val match = migrationFileName.matchEntire(fileName)
            ?: return listOf("v2 SQL filenames must use V900+__snake_case.sql")
        val executableSql = stripSqlComments(sql)

        return buildList {
            if (match.groupValues[1].toInt() < MINIMUM_VERSION) {
                add("v2 migration version must be V$MINIMUM_VERSION or greater")
            }
            if (sql.substringBefore('\n') != FORWARD_ONLY_HEADER) {
                add("v2 migration must declare the exact forward-only rule on its first line")
            }
            if (!worldIdColumn.containsMatchIn(executableSql)) {
                add("v2 migration must declare a non-null world_id column outside SQL comments")
            }
            runCatching { createdTables(sql) }.exceptionOrNull()?.let { error ->
                add("v2 migration CREATE TABLE syntax cannot be world-scope checked: ${error.message}")
            }
        }
    }

    fun createdTables(sql: String): List<V2CreatedTable> {
        val executableSql = stripSqlComments(sql)
        val declarations = createTable.findAll(executableSql).map { match ->
            V2CreatedTable(
                schema = match.groupValues[1].ifEmpty { "public" }.lowercase(),
                name = match.groupValues[2].lowercase(),
            )
        }.toList()
        val declarationCount = createTableKeyword.findAll(executableSql).count()
        require(declarationCount == declarations.size) {
            "unsupported CREATE TABLE syntax (found $declarationCount declarations, parsed ${declarations.size})"
        }
        return declarations
    }

    fun stripSqlComments(sql: String): String {
        val result = StringBuilder(sql.length)
        var index = 0
        var quote: Char? = null

        while (index < sql.length) {
            val current = sql[index]
            when {
                quote != null -> {
                    result.append(current)
                    if (current == quote) {
                        if (index + 1 < sql.length && sql[index + 1] == quote) {
                            result.append(sql[index + 1])
                            index += 2
                        } else {
                            quote = null
                            index++
                        }
                    } else {
                        index++
                    }
                }

                current == '\'' || current == '"' -> {
                    quote = current
                    result.append(current)
                    index++
                }

                current == '-' && index + 1 < sql.length && sql[index + 1] == '-' -> {
                    result.append(' ')
                    index += 2
                    while (index < sql.length && sql[index] != '\n') index++
                    if (index < sql.length) {
                        result.append('\n')
                        index++
                    }
                }

                current == '/' && index + 1 < sql.length && sql[index + 1] == '*' -> {
                    result.append(' ')
                    index += 2
                    while (index < sql.length) {
                        if (sql[index] == '*' && index + 1 < sql.length && sql[index + 1] == '/') {
                            index += 2
                            break
                        }
                        if (sql[index] == '\n') result.append('\n')
                        index++
                    }
                }

                else -> {
                    result.append(current)
                    index++
                }
            }
        }
        return result.toString()
    }
}

internal data class V2CreatedTable(
    val schema: String,
    val name: String,
)

internal object V2MigrationSources {
    private val migrationDirectories = listOf(
        "infra/src/main/resources/db/migration_v2",
        "app/game-engine/src/test/resources/db/migration_v2",
    )

    fun v2SqlFiles(): List<File> {
        val root = repoRoot()
        val directories = migrationDirectories.map { File(root, it) }
        check(directories.all(File::isDirectory)) { "v2 migration directories: $migrationDirectories" }
        return directories.flatMap { directory ->
            directory.walkTopDown().filter { it.isFile && it.extension == "sql" }.toList()
        }.sortedBy { relativePath(it) }
    }

    fun sourceForAppliedScript(script: String): File {
        val matches = v2SqlFiles().filter { it.name == script }
        check(matches.size == 1) {
            "expected exactly one source SQL file for applied v2 migration $script, found ${matches.map { relativePath(it) }}"
        }
        return matches.single()
    }

    fun relativePath(file: File): String = file.relativeTo(repoRoot()).path

    private fun repoRoot(): File {
        var directory: File? = File("").absoluteFile
        while (directory != null) {
            if (File(directory, "settings.gradle.kts").isFile) return directory
            directory = directory.parentFile
        }
        error("repo root (settings.gradle.kts) not found from ${File("").absolutePath}")
    }
}
