package opensamguk.infra.seed

import java.nio.file.Path
import opensamguk.logic.world.WorldMapVariant

/** 1428 release (2026-09-27): fourfold grid, duplicate synthetic counties retired; 1447 releases stay immutable. */
internal object Archive1428Artifacts {
    private const val CATALOG_SHA256 = "346325cae5f50ec0fe9fcd3ba37d771c2627e14ae4d07512e6fdee946314c254"

    fun load(root: Path): ResolvedWorldArtifacts = Archive1447Artifacts.loadPinned(
        root, WorldMapVariant.V3_1428, "han-world-v3-1428-artifacts-v1", CATALOG_SHA256)
}
