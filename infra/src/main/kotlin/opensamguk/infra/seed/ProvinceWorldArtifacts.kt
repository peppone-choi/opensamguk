package opensamguk.infra.seed

import java.nio.file.Path
import opensamguk.logic.world.MapArtifactContract
import opensamguk.logic.world.WorldMapVariant

/** Current neutral release; the preceding release's files and pins remain immutable. */
internal object ProvinceWorldArtifacts {
    private const val CATALOG_SHA256 = "de1f6ebc40100aadfe1843a14867facee4b53426aa16369a72cf658b6fce6188"

    fun load(root: Path): ResolvedWorldArtifacts = Archive1447Artifacts.loadPinned(
        root, WorldMapVariant.PROVINCE_WORLD, "province-world-20261003-artifacts", CATALOG_SHA256,
        MapArtifactContract.CURRENT)
}
