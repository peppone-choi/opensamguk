package opensamguk.infra.seed

import java.nio.file.Path
import opensamguk.logic.world.MapArtifactContract
import opensamguk.logic.world.WorldMapVariant

/** Current neutral release; the preceding release's files and pins remain immutable. */
internal object ProvinceWorldArtifacts {
    private const val CATALOG_SHA256 = "cecb1305be9890ce2adff947834af2afcb86f85d41a5de5464b072cd7b8c19a8"

    fun load(root: Path): ResolvedWorldArtifacts = Archive1447Artifacts.loadPinned(
        root, WorldMapVariant.PROVINCE_WORLD, "province-world-20261003-artifacts", CATALOG_SHA256,
        MapArtifactContract.CURRENT)
}
