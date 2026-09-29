package opensamguk.logic.battle.realtime

import java.security.MessageDigest

/** Loads the exact owner-accepted catalog bytes packaged with the game server. */
object WaryongBoardCatalogResource {
    const val PATH = "battle/waryong/catalog-v1.json"
    const val BYTE_COUNT = 954_038
    const val SHA256 = "2eb021038ccf36178127247d25c18f03f538e6f139a2e699fdb40e5ed5f4bb27"

    fun load(classLoader: ClassLoader = TacticalBoardCatalog::class.java.classLoader): TacticalBoardCatalog {
        val bytes = requireNotNull(classLoader.getResourceAsStream(PATH)) { "Waryong catalog resource missing" }
            .use { it.readBytes() }
        require(bytes.size == BYTE_COUNT) { "Waryong catalog resource byte count mismatch" }
        val actual = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        require(actual == SHA256) { "Waryong catalog resource SHA-256 mismatch" }
        return TacticalBoardCatalog.parse(String(bytes, Charsets.UTF_8)).also {
            require(it.catalogSha256 == SHA256)
        }
    }
}
