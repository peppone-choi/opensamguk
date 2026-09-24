package opensamguk.infra.seed

import java.nio.file.Path
import opensamguk.logic.world.HanWorldVariant

/** Fourfold-grid 1447 release; the earlier 1447 catalog remains immutable. */
internal object Han1447Map4Artifacts {
    private const val CATALOG_SHA256 = "5796119de8cd60a28a13a88f2a177776be5bdc063a0e9a2202321834d5f528a0"

    fun load(root: Path): ResolvedHanWorldArtifacts = Han1447Artifacts.loadPinned(
        root, HanWorldVariant.V3_1447_MAP4, "han-world-v3-1447-map4-artifacts-v1", CATALOG_SHA256)
}
