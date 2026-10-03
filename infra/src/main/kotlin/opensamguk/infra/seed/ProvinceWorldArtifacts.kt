package opensamguk.infra.seed

import java.nio.file.Path
import opensamguk.logic.world.MapArtifactContract
import opensamguk.logic.world.WorldMapVariant

/** Current neutral release; the preceding release's files and pins remain immutable. */
internal object ProvinceWorldArtifacts {
    private const val CATALOG_SHA256 = "893e2c167ae6602827eadcd578e6e5b98969f4649bf17259efc8c5b1d84a990d"

    fun load(root: Path): ResolvedWorldArtifacts = Archive1447Artifacts.loadPinned(
        root, WorldMapVariant.PROVINCE_WORLD, "province-world-20261003-artifacts", CATALOG_SHA256,
        MapArtifactContract.CURRENT)
}
