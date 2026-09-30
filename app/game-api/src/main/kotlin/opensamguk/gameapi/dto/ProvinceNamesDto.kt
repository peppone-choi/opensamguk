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

data class ProvinceNamesMetadataDto(
    val worldId: Int,
    val mapRelease: String,
    val topologyRevision: String,
    val topologyHash: String,
    val sourceSha256: String,
    val representationSha256: String,
    /** A server API path; the client must use its currently selected server's normal API transport. */
    val representationPath: String,
)
