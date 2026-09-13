package opensamguk.logic.world

/** Runtime identity only: never stored as a replacement for a world's logical map name. */
enum class HanWorldVariant(val artifactId: String, val cityCount: Int) {
    V3_832("han-world-v3-832", 832),
    V3_835("han-world-v3-835", 835),
}
