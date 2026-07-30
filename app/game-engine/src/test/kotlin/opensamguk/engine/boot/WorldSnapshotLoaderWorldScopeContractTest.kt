package opensamguk.engine.boot

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorldSnapshotLoaderWorldScopeContractTest {
    private fun loaderSource(): String = listOf(
        File("src/main/kotlin/opensamguk/engine/boot/WorldSnapshotLoader.kt"),
        File("app/game-engine/src/main/kotlin/opensamguk/engine/boot/WorldSnapshotLoader.kt"),
        File("../app/game-engine/src/main/kotlin/opensamguk/engine/boot/WorldSnapshotLoader.kt"),
    ).firstOrNull { it.isFile }?.readText()
        ?: error("WorldSnapshotLoader.kt not found from ${File(".").absolutePath}")

    private fun privateMethodBody(source: String, methodName: String): String {
        val start = source.indexOf("private fun $methodName")
        require(start >= 0) { "private method $methodName not found" }
        val next = source.indexOf("\n    private fun ", start + 1)
        return source.substring(start, if (next >= 0) next else source.length)
    }

    @Test
    fun `world owned cold boot queries predicate and bind the configured world`() {
        val source = loaderSource()
        val worldOwnedMethods = listOf(
            "resolveActiveGame",
            "loadArchivedNationIds",
            "loadServerCount",
            "loadActiveUniqueAuctionItems",
            "loadStoredUniqueItemCounts",
            "loadGameEnv",
            "loadNationEnv",
            "loadDiplomacy",
            "loadAccessLogs",
            "loadTroops",
        )

        for (methodName in worldOwnedMethods) {
            val body = privateMethodBody(source, methodName)
            assertTrue(
                Regex("""\bworld_id\s*=\s*\?""").containsMatchIn(body),
                "$methodName must predicate its world-owned query by world_id",
            )
            assertTrue(
                Regex("""\bworldId\.value\b""").containsMatchIn(body),
                "$methodName must bind the configured WorldId",
            )
        }
    }

    @Test
    fun `troop restart projection is exact and inheritance remains globally owned`() {
        val source = loaderSource()
        val troops = privateMethodBody(source, "loadTroops").replace(Regex("""\s+"""), " ")
        assertTrue(
            "SELECT troop_leader, nation, name FROM troop WHERE world_id = ? ORDER BY troop_leader ASC" in troops,
            troops,
        )

        val inheritance = privateMethodBody(source, "loadInheritancePoints")
        assertTrue("kv.world_id IS NULL" in inheritance, inheritance)
        assertFalse(Regex("""kv\.world_id\s*=\s*\?""").containsMatchIn(inheritance), inheritance)
    }
}
