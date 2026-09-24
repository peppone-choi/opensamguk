package opensamguk.infra.seed

import java.nio.file.Path
import opensamguk.logic.world.HanWorldVariant

/** Fourfold-grid 1447 release; the earlier 1447 catalog remains immutable. */
internal object Han1447Map4Artifacts {
    private const val CATALOG_SHA256 = "b35b3cce628b5bad7d544fffefee5d0eaf3fe5be644cc4d5c5ea4ed792b4ff78"

    fun load(root: Path): ResolvedHanWorldArtifacts = Han1447Artifacts.loadPinned(
        root, HanWorldVariant.V3_1447_MAP4, "han-world-v3-1447-map4-artifacts-v1", CATALOG_SHA256)
}
