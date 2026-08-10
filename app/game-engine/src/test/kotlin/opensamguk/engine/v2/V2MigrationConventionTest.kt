package opensamguk.engine.v2

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
    fun `validator rejects top level select into table creation even with a normal create table`() {
        val sql = """
            -- V2-FORWARD-ONLY: rollback is a new compensating V900+ migration.
            CREATE TABLE v2_example (
                world_id integer NOT NULL REFERENCES world_state(id),
                PRIMARY KEY (world_id)
            );
            SELECT world_id INTO v2_select_into_bypass FROM v2_example;
        """.trimIndent()

        val violations = V2MigrationConvention.validate("V900__v2_example.sql", sql)

        assertTrue(
            violations.any { it.contains("SELECT ... INTO") },
            "top-level SELECT ... INTO table creation must fail closed: $violations",
        )
    }

    @Test
    fun `validator rejects parenthesized select into table creation`() {
        val sql = """
            -- V2-FORWARD-ONLY: rollback is a new compensating V900+ migration.
            CREATE TABLE v2_example (
                world_id integer NOT NULL REFERENCES world_state(id),
                PRIMARY KEY (world_id)
            );
            (SELECT world_id INTO v2_parenthesized_select_into FROM v2_example);
        """.trimIndent()

        val violations = V2MigrationConvention.validate("V900__v2_example.sql", sql)

        assertTrue(
            violations.any { it.contains("SELECT ... INTO") },
            "parenthesized SELECT ... INTO table creation must fail closed: $violations",
        )
    }

    @Test
    fun `validator accepts into used as an explicit select alias`() {
        val sql = """
            -- V2-FORWARD-ONLY: rollback is a new compensating V900+ migration.
            CREATE TABLE v2_example (
                world_id integer NOT NULL REFERENCES world_state(id),
                PRIMARY KEY (world_id)
            );
            SELECT 1 AS INTO;
        """.trimIndent()

        assertEquals(emptyList(), V2MigrationConvention.validate("V900__v2_example.sql", sql))
    }

    @Test
    fun `validator detects select into after a dollar quote containing comment markers`() {
        val sql = """
            -- V2-FORWARD-ONLY: rollback is a new compensating V900+ migration.
            CREATE TABLE v2_example (
                world_id integer NOT NULL REFERENCES world_state(id),
                PRIMARY KEY (world_id)
            );
            SELECT ${'$'}v2_body${'$'}
            -- this is text inside the dollar quote, not an SQL comment
            /* this is text inside the dollar quote, not an SQL comment
            ${'$'}v2_body${'$'};
            SELECT world_id INTO v2_after_dollar_body FROM v2_example;
        """.trimIndent()

        val violations = V2MigrationConvention.validate("V900__v2_example.sql", sql)

        assertTrue(
            violations.any { it.contains("SELECT ... INTO") },
            "SELECT ... INTO after a dollar quote must fail closed: $violations",
        )
    }

    @Test
    fun `validator ignores select into inside nested block comments`() {
        val sql = """
            -- V2-FORWARD-ONLY: rollback is a new compensating V900+ migration.
            CREATE TABLE v2_example (
                world_id integer NOT NULL REFERENCES world_state(id),
                PRIMARY KEY (world_id)
            );
            /* outer comment
                /* nested comment */
                SELECT world_id INTO v2_still_in_outer_comment FROM v2_example;
            */
        """.trimIndent()

        assertEquals(emptyList(), V2MigrationConvention.validate("V900__v2_example.sql", sql))
    }

    @Test
    fun `validator ignores select into inside an E string with an escaped quote`() {
        val sql = """
            -- V2-FORWARD-ONLY: rollback is a new compensating V900+ migration.
            CREATE TABLE v2_example (
                world_id integer NOT NULL REFERENCES world_state(id),
                PRIMARY KEY (world_id)
            );
            SELECT E'escaped quote: \' SELECT world_id INTO v2_e_string' AS note;
        """.trimIndent()

        assertEquals(emptyList(), V2MigrationConvention.validate("V900__v2_example.sql", sql))
    }

    @Test
    fun `created tables exposes an unscoped create after a dollar quoted literal with a comment marker`() {
        val sql = """
            -- V2-FORWARD-ONLY: rollback is a new compensating V900+ migration.
            CREATE TABLE v2_scoped_dollar (
                world_id integer NOT NULL REFERENCES world_state(id),
                PRIMARY KEY (world_id)
            );
            SELECT ${'$'}mask${'$'} /* literal text CREATE TABLE v2_dollar_literal_decoy ${'$'}mask${'$'} AS note;
            CREATE TABLE v2_unscoped_dollar (
                external_code integer NOT NULL PRIMARY KEY
            );
        """.trimIndent()

        assertEquals(
            listOf(
                V2CreatedTable("public", "v2_scoped_dollar"),
                V2CreatedTable("public", "v2_unscoped_dollar"),
            ),
            V2MigrationConvention.createdTables(sql),
        )
        assertEquals(emptyList(), V2MigrationConvention.validate("V900__v2_dollar_mask.sql", sql))
    }

    @Test
    fun `created tables exposes an unscoped create after an E string with an escaped quote and comment marker`() {
        val sql = """
            -- V2-FORWARD-ONLY: rollback is a new compensating V900+ migration.
            CREATE TABLE v2_scoped_e_string (
                world_id integer NOT NULL REFERENCES world_state(id),
                PRIMARY KEY (world_id)
            );
            SELECT E'escaped quote: \' /* still literal CREATE TABLE v2_e_string_literal_decoy' AS note;
            CREATE TABLE v2_unscoped_e_string (
                external_code integer NOT NULL PRIMARY KEY
            );
        """.trimIndent()

        assertEquals(
            listOf(
                V2CreatedTable("public", "v2_scoped_e_string"),
                V2CreatedTable("public", "v2_unscoped_e_string"),
            ),
            V2MigrationConvention.createdTables(sql),
        )
        assertEquals(emptyList(), V2MigrationConvention.validate("V900__v2_e_string_mask.sql", sql))
    }

    @Test
    fun `created tables does not treat dollar delimited identifiers as dollar quote boundaries`() {
        val sql = """
            -- V2-FORWARD-ONLY: rollback is a new compensating V900+ migration.
            CREATE TABLE v2_scoped_dollar_identifier (
                world_id integer NOT NULL REFERENCES world_state(id),
                PRIMARY KEY (world_id)
            );
            SELECT 1 AS foo${'$'}mask${'$'};
            CREATE TABLE v2_unscoped_dollar_identifier (
                external_code integer NOT NULL PRIMARY KEY
            );
            SELECT 1 AS bar${'$'}mask${'$'}
        """.trimIndent()

        assertEquals(
            listOf(
                V2CreatedTable("public", "v2_scoped_dollar_identifier"),
                V2CreatedTable("public", "v2_unscoped_dollar_identifier"),
            ),
            V2MigrationConvention.createdTables(sql),
        )
        assertEquals(emptyList(), V2MigrationConvention.validate("V900__v2_dollar_identifier.sql", sql))
    }

    @Test
    fun `created tables exposes an unscoped dollar suffixed table instead of truncating it to a scoped decoy`() {
        val sql = """
            -- V2-FORWARD-ONLY: rollback is a new compensating V900+ migration.
            CREATE TABLE v2_scoped_decoy (
                world_id integer NOT NULL REFERENCES world_state(id),
                PRIMARY KEY (world_id)
            );
            CREATE TABLE v2_scoped_decoy${'$'}hidden (
                external_code integer NOT NULL PRIMARY KEY
            );
        """.trimIndent()

        assertEquals(
            listOf(
                V2CreatedTable("public", "v2_scoped_decoy"),
                V2CreatedTable("public", "v2_scoped_decoy${'$'}hidden"),
            ),
            V2MigrationConvention.createdTables(sql),
        )
        assertEquals(emptyList(), V2MigrationConvention.validate("V900__v2_dollar_table_name.sql", sql))
    }

    @Test
    fun `created tables ignores create keywords inside tagged and untagged dollar quotes`() {
        val sql = """
            -- V2-FORWARD-ONLY: rollback is a new compensating V900+ migration.
            CREATE TABLE v2_real_dollar_quote (
                world_id integer NOT NULL REFERENCES world_state(id),
                PRIMARY KEY (world_id)
            );
            SELECT ${'$'}tag${'$'} CREATE TABLE v2_tagged_dollar_decoy ${'$'}tag${'$'} AS note;
            SELECT ${'$'}${'$'} CREATE TABLE v2_untagged_dollar_decoy ${'$'}${'$'} AS note;
        """.trimIndent()

        assertEquals(
            listOf(V2CreatedTable("public", "v2_real_dollar_quote")),
            V2MigrationConvention.createdTables(sql),
        )
        assertEquals(emptyList(), V2MigrationConvention.validate("V900__v2_dollar_quotes.sql", sql))
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

    @Test
    fun `validator exposes PostgreSQL table persistence modifiers to runtime world scope checks`() {
        listOf(
            "UNLOGGED" to "v2_unlogged_example",
            "TEMP" to "v2_temp_example",
            "TEMPORARY" to "v2_temporary_example",
            "GLOBAL TEMP" to "v2_global_temp_example",
            "GLOBAL TEMPORARY" to "v2_global_temporary_example",
            "LOCAL TEMP" to "v2_local_temp_example",
            "LOCAL TEMPORARY" to "v2_local_temporary_example",
        ).forEach { (modifier, tableName) ->
            val sql = """
                -- V2-FORWARD-ONLY: rollback is a new compensating V900+ migration.
                CREATE $modifier TABLE $tableName (
                    world_id integer NOT NULL REFERENCES world_state(id),
                    PRIMARY KEY (world_id)
                );
            """.trimIndent()

            assertEquals(
                listOf(V2CreatedTable("public", tableName)),
                V2MigrationConvention.createdTables(sql),
                "$modifier CREATE TABLE must reach runtime world-scope checks",
            )
            assertEquals(emptyList(), V2MigrationConvention.validate("V900__${tableName}.sql", sql))

            val unscopedSql = """
                -- V2-FORWARD-ONLY: rollback is a new compensating V900+ migration.
                CREATE $modifier TABLE $tableName (
                    external_code integer NOT NULL PRIMARY KEY
                );
            """.trimIndent()
            val violations = V2MigrationConvention.validate("V900__${tableName}.sql", unscopedSql)
            assertTrue(
                violations.any { it.contains("world_id") },
                "$modifier CREATE TABLE without world_id must not bypass validation: $violations",
            )
        }
    }

    @Test
    fun `validator fails closed when a recognized table modifier sequence is unsupported`() {
        val sql = """
            -- V2-FORWARD-ONLY: rollback is a new compensating V900+ migration.
            CREATE TEMP GLOBAL TABLE v2_invalid_modifier_order (
                world_id integer NOT NULL REFERENCES world_state(id),
                PRIMARY KEY (world_id)
            );
        """.trimIndent()

        assertFailsWith<IllegalArgumentException> {
            V2MigrationConvention.createdTables(sql)
        }
        val violations = V2MigrationConvention.validate("V900__invalid_modifier_order.sql", sql)
        assertTrue(
            violations.any { it.contains("cannot be world-scope checked") },
            "unparsed recognized CREATE TABLE modifier sequence must fail closed: $violations",
        )
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
    private const val UNQUOTED_POSTGRES_IDENTIFIER = """[a-z_][a-z0-9_$]*"""
    private const val UNQUOTED_POSTGRES_IDENTIFIER_END = """(?![a-z0-9_$])"""
    private const val TABLE_PERSISTENCE_MODIFIER =
        """(?:(?:unlogged|(?:(?:global|local)\s+)?(?:temporary|temp))\s+)?"""
    private val createTableKeyword = Regex(
        """\bcreate\s+(?:(?:unlogged|global|local|temporary|temp)\s+)*table\b""",
        RegexOption.IGNORE_CASE,
    )
    private val createTable = Regex(
        """\bcreate\s+${TABLE_PERSISTENCE_MODIFIER}table\s+(?:if\s+not\s+exists\s+)?(?:($UNQUOTED_POSTGRES_IDENTIFIER)\.)?($UNQUOTED_POSTGRES_IDENTIFIER)$UNQUOTED_POSTGRES_IDENTIFIER_END""",
        RegexOption.IGNORE_CASE,
    )

    fun validate(fileName: String, sql: String): List<String> {
        val match = migrationFileName.matchEntire(fileName)
            ?: return listOf("v2 SQL filenames must use V900+__snake_case.sql")
        val executableSql = maskedExecutableSql(sql)

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
            runCatching { createdTablesFromExecutableSql(executableSql) }.exceptionOrNull()?.let { error ->
                add("v2 migration CREATE TABLE syntax cannot be world-scope checked: ${error.message}")
            }
        }
    }

    fun createdTables(sql: String): List<V2CreatedTable> = createdTablesFromExecutableSql(maskedExecutableSql(sql))

    private fun createdTablesFromExecutableSql(executableSql: String): List<V2CreatedTable> {
        require(!containsSelectIntoTableCreation(executableSql)) {
            "unsupported PostgreSQL SELECT ... INTO table creation"
        }
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

    private fun maskedExecutableSql(sql: String): String {
        val result = StringBuilder(sql)
        var index = 0

        fun mask(start: Int, end: Int) {
            for (position in start until end) {
                if (sql[position] != '\n' && sql[position] != '\r') {
                    result.setCharAt(position, ' ')
                }
            }
        }

        while (index < sql.length) {
            when {
                startsLineComment(sql, index) -> {
                    val end = skipLineComment(sql, index)
                    mask(index, end)
                    index = end
                }

                startsBlockComment(sql, index) -> {
                    val end = skipNestedBlockComment(sql, index)
                    mask(index, end)
                    index = end
                }

                isEscapeStringStart(sql, index) -> {
                    val end = skipQuotedText(sql, index + 1, '\'', true)
                    mask(index, end)
                    index = end
                }

                sql[index] == '\'' || sql[index] == '"' -> {
                    val end = skipQuotedText(sql, index, sql[index])
                    mask(index, end)
                    index = end
                }

                sql[index] == '$' -> {
                    val end = skipDollarQuotedText(sql, index)
                    if (end == null) {
                        index++
                    } else {
                        mask(index, end)
                        index = end
                    }
                }

                else -> index++
            }
        }
        return result.toString()
    }

    private fun containsSelectIntoTableCreation(sql: String): Boolean {
        var index = 0
        var parenthesisDepth = 0
        val selectAtDepth = mutableSetOf<Int>()
        var previousToken: String? = null

        while (index < sql.length) {
            when (sql[index]) {
                '(' -> {
                    parenthesisDepth++
                    previousToken = null
                    index++
                }

                ')' -> {
                    selectAtDepth.remove(parenthesisDepth)
                    parenthesisDepth = (parenthesisDepth - 1).coerceAtLeast(0)
                    previousToken = null
                    index++
                }

                ';' -> {
                    selectAtDepth.clear()
                    previousToken = null
                    index++
                }

                else -> {
                    if (isPostgreSqlIdentifierStart(sql[index])) {
                        val tokenEnd = sql.indexOfFirstNonPostgreSqlIdentifierCharacter(index)
                        val token = sql.substring(index, tokenEnd).lowercase()
                        when (token) {
                            "select" -> selectAtDepth += parenthesisDepth
                            "into" -> if (parenthesisDepth in selectAtDepth && previousToken != "as") return true
                        }
                        previousToken = token
                        index = tokenEnd
                    } else {
                        index++
                    }
                }
            }
        }
        return false
    }

    private fun startsLineComment(sql: String, index: Int): Boolean =
        sql[index] == '-' && index + 1 < sql.length && sql[index + 1] == '-'

    private fun startsBlockComment(sql: String, index: Int): Boolean =
        sql[index] == '/' && index + 1 < sql.length && sql[index + 1] == '*'

    private fun skipLineComment(sql: String, start: Int): Int {
        var index = start + 2
        while (index < sql.length && sql[index] != '\n') index++
        return index
    }

    private fun skipNestedBlockComment(sql: String, start: Int): Int {
        var index = start + 2
        var depth = 1

        while (index < sql.length && depth > 0) {
            when {
                startsBlockComment(sql, index) -> {
                    depth++
                    index += 2
                }

                sql[index] == '*' && index + 1 < sql.length && sql[index + 1] == '/' -> {
                    depth--
                    index += 2
                }

                else -> index++
            }
        }
        return index
    }

    private fun isEscapeStringStart(sql: String, index: Int): Boolean =
        (sql[index] == 'E' || sql[index] == 'e') &&
            index + 1 < sql.length && sql[index + 1] == '\'' &&
            (index == 0 || !isPostgreSqlIdentifierContinuation(sql[index - 1]))

    private fun skipQuotedText(sql: String, start: Int, quote: Char, backslashEscapes: Boolean = false): Int {
        var index = start + 1
        while (index < sql.length) {
            if (backslashEscapes && sql[index] == '\\' && index + 1 < sql.length) {
                index += 2
            } else if (sql[index] == quote) {
                if (index + 1 < sql.length && sql[index + 1] == quote) {
                    index += 2
                } else {
                    return index + 1
                }
            } else {
                index++
            }
        }
        return sql.length
    }

    private fun skipDollarQuotedText(sql: String, start: Int): Int? {
        if (start > 0 && isPostgreSqlIdentifierContinuation(sql[start - 1])) return null

        val tagStart = start + 1
        if (tagStart >= sql.length) return null
        if (sql[tagStart] != '$' && !isDollarQuoteTagStart(sql[tagStart])) return null

        val tagEnd = sql.indexOfFirstNonDollarQuoteTagCharacter(tagStart)
        if (tagEnd >= sql.length || sql[tagEnd] != '$') return null

        val delimiter = sql.substring(start, tagEnd + 1)
        val closingDelimiter = sql.indexOf(delimiter, tagEnd + 1)
        return if (closingDelimiter == -1) sql.length else closingDelimiter + delimiter.length
    }

    private fun String.indexOfFirstNonPostgreSqlIdentifierCharacter(start: Int): Int {
        var index = start
        while (index < length && isPostgreSqlIdentifierContinuation(this[index])) index++
        return index
    }

    private fun String.indexOfFirstNonDollarQuoteTagCharacter(start: Int): Int {
        var index = start
        while (index < length && isDollarQuoteTagContinuation(this[index])) index++
        return index
    }

    private fun isPostgreSqlIdentifierStart(character: Char): Boolean = character.isLetter() || character == '_'

    private fun isPostgreSqlIdentifierContinuation(character: Char): Boolean =
        character.isLetterOrDigit() || character == '_' || character == '$'

    private fun isDollarQuoteTagStart(character: Char): Boolean = character.isLetter() || character == '_'

    private fun isDollarQuoteTagContinuation(character: Char): Boolean =
        character.isLetterOrDigit() || character == '_'

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
