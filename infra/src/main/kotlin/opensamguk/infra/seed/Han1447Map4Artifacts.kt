package opensamguk.infra.seed

import java.nio.file.Path
import opensamguk.logic.world.HanWorldVariant

/** Fourfold-grid 1447 release; the earlier 1447 catalog remains immutable. */
internal object Han1447Map4Artifacts {
    private const val CATALOG_SHA256 = "39b05b13b44149efc7e44d7793f626dba3dab3095f214e88e3eee4b9af48e496"

    fun load(root: Path): ResolvedHanWorldArtifacts = Han1447Artifacts.loadPinned(
        root, HanWorldVariant.V3_1447_MAP4, "han-world-v3-1447-map4-artifacts-v1", CATALOG_SHA256)
}
