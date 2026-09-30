package opensamguk.gameapi.dto

/** Static geographic labels only; no live ownership, visibility, geometry or identity fields. */
data class ProvinceNameDto(val provinceId: String, val displayName: String)
data class ProvinceNamesDto(
    val worldId: Int,
    val mapRelease: String,
    val topologyRevision: String,
    val topologyHash: String,
    val sourceSha256: String,
    val names: List<ProvinceNameDto>,
)
