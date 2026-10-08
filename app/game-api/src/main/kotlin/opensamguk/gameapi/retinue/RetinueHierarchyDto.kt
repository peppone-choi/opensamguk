package opensamguk.gameapi.retinue

/** Structural relationships only. These rows confer no command authority or private person access. */
data class RetinueSuperiorDto(val generalId: Int, val name: String, val parentId: Int?)
data class RetinueHierarchyNodeDto(
    val generalId: Int,
    val name: String,
    val parentId: Int?,
    val directCount: Int,
    val descendantCount: Int,
)
data class RetinueHierarchyDto(
    val status: String,
    val actorGeneralId: Int,
    val superiors: List<RetinueSuperiorDto> = emptyList(),
    val nodes: List<RetinueHierarchyNodeDto> = emptyList(),
)
