package opensamguk.infra.persistence

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue
import kotlin.test.fail

class JdbcFlushExecutorWorldScopeTest {

    private fun source(): String {
        val candidates = listOf(
            File("src/main/kotlin/opensamguk/infra/persistence/JdbcFlushExecutor.kt"),
            File("infra/src/main/kotlin/opensamguk/infra/persistence/JdbcFlushExecutor.kt"),
        )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: fail("JdbcFlushExecutor source not found in ${candidates.map(File::getAbsolutePath)}")
    }

    private fun functionBlock(source: String, name: String): String {
        val start = source.indexOf("private fun $name(")
        assertTrue(start >= 0, "missing JdbcFlushExecutor.$name")
        val next = source.indexOf("\n    private fun ", start + 1).let { if (it < 0) source.length else it }
        return source.substring(start, next)
    }

    @Test
    fun `every V32 world-owned flush method requires and binds canonical world id`() {
        val source = source()
        val worldOwnedMethods = listOf(
            "ngOldNationsUpsert",
            "ngOldGeneralsUpsert",
            "historyRows",
            "troopCreateMany",
            "troopDeleteMany",
            "troopDeleteByNation",
            "troopUpdate",
            "diplomacyUpdate",
            "generalAccessLogUpsertMany",
            "diplomacyCreateMany",
            "rankDataDeleteMany",
            "generalOwnerDeleteMany",
            "generalAccessLogDeleteMany",
            "rankDataUpdate",
            "rankDataNationSync",
            "nationEnvKvWrite",
            "gameKvWrite",
            "logEntryCreateMany",
            "auctionUpsertMany",
            "auctionBidInsertMany",
            "bettingUpsertMany",
            "boardPostInsertMany",
            "boardCommentInsertMany",
            "votePollInsertMany",
            "voteInsertMany",
            "voteCommentInsertMany",
            "votePollUpdateMany",
            "messageCreateMany",
            "messageInvalidateMany",
            "diplomacyLetterInsertMany",
            "diplomacyLetterUpdateMany",
            "inheritanceResultInsertMany",
            "statisticInsertMany",
            "yearbookInsertMany",
            "gameWinnerUpdateMany",
            "emperiorInsertMany",
            "hallUpsertMany",
            "eventInsertMany",
            "eventDeleteMany",
            "selectPoolMutate",
        )

        for (method in worldOwnedMethods) {
            val block = functionBlock(source, method)
            assertContains(block, "worldId: WorldId", message = "$method must require canonical WorldId")
            assertContains(block, "world_id", message = "$method must bind or predicate world_id")
        }
    }

    @Test
    fun `mixed game kv writer preserves global inheritance and scoped world conflict domains`() {
        val source = source()
        val kvFlush = functionBlock(source, "kvWriteFlush")
        val gameKv = functionBlock(source, "gameKvWrite")

        assertContains(kvFlush, "writes.all { it.table == \"inheritance\" }")
        assertContains(kvFlush, "writes.none { it.table == \"inheritance\" }")
        assertContains(gameKv, "world_id IS NULL")
        assertContains(
            gameKv,
            "(\"table\", namespace, key) WHERE \"table\" = 'inheritance' AND world_id IS NULL",
        )
        assertContains(
            gameKv,
            "(world_id, \"table\", namespace, key) WHERE \"table\" <> 'inheritance' AND world_id IS NOT NULL",
        )
    }

    @Test
    fun `world-owned DML statements include world_id except documented global tables`() {
        val source = source()
        val allowedUnscopedTables = setOf("inheritance_log")
        val tableStmt = Regex("""(?im)^\s*(DELETE FROM|UPDATE|INSERT INTO)\s+([a-z_]+)""")
        val gaps = mutableListOf<String>()
        for (match in tableStmt.findAll(source)) {
            val verb = match.groupValues[1].uppercase()
            val table = match.groupValues[2]
            if (table in allowedUnscopedTables || table == "world_state") continue
            val start = match.range.first
            val window = source.substring(start, minOf(source.length, start + 2000))
            if ("world_id" !in window && "worldId" !in window) {
                gaps.add("$verb $table")
            }
        }
        assertTrue(gaps.isEmpty(), "unscoped world-owned DML: $gaps")
    }
}
