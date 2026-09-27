package opensamguk.infra.seed

import java.nio.file.Path
import opensamguk.logic.world.WorldMapVariant

/** 1428 release (2026-09-27): fourfold grid, duplicate synthetic counties retired; 1447 releases stay immutable. */
internal object Archive1428Artifacts {
    private const val CATALOG_SHA256 = "25f83056fd4c031ca5a6595b30430bab5ae0a9ad6ba09678180c66c3c5155efc"

    fun load(root: Path): ResolvedWorldArtifacts = Archive1447Artifacts.loadPinned(
        root, WorldMapVariant.V3_1428, "han-world-v3-1428-artifacts-v1", CATALOG_SHA256)
}
