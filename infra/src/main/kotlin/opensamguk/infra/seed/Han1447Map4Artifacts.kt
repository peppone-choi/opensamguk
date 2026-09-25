package opensamguk.infra.seed

import java.nio.file.Path
import opensamguk.logic.world.HanWorldVariant

/** Fourfold-grid 1447 release; the earlier 1447 catalog remains immutable. */
internal object Han1447Map4Artifacts {
    private const val CATALOG_SHA256 = "628daab9b3ca96f759fb48561e75eb95514be176986eb4f1ee5dc1223437bb5c"

    fun load(root: Path): ResolvedHanWorldArtifacts = Han1447Artifacts.loadPinned(
        root, HanWorldVariant.V3_1447_MAP4, "han-world-v3-1447-map4-artifacts-v1", CATALOG_SHA256)
}
