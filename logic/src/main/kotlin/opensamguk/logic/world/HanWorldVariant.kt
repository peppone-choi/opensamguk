package opensamguk.logic.world

/** Runtime identity only: never stored as a replacement for a world's logical map name. */
enum class HanWorldVariant(val artifactId: String, val cityCount: Int) {
    V3_832("han-world-v3-832", 832),
    V3_835("han-world-v3-835", 835),
    V3_846("han-world-v3-846", 846),
    V3_848("han-world-v3-848", 848),
    V3_1098("han-world-v3-1098", 1098),
    V3_1133("han-world-v3-1133", 1133),
}
