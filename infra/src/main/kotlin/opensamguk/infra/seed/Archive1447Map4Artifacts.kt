package opensamguk.infra.seed

import java.nio.file.Path
import opensamguk.logic.world.WorldMapVariant

/** Fourfold-grid 1447 release; the earlier 1447 catalog remains immutable. */
internal object Archive1447Map4Artifacts {
    private const val CATALOG_SHA256 = "62756b38573fdbd3a6c41c030cff2627c1979982ad18a5daf3834758224c1ad0"

    fun load(root: Path): ResolvedWorldArtifacts = Archive1447Artifacts.loadPinned(
        root, WorldMapVariant.V3_1447_MAP4, "han-world-v3-1447-map4-artifacts-v1", CATALOG_SHA256)
}
