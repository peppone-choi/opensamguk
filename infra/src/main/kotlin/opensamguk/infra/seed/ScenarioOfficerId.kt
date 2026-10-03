package opensamguk.infra.seed

/** The committed pilot uses numeric portraits; RTK14 materialization writes the same ID as `<id>.png`. */
internal fun scenarioOfficerId(picture: String?): Int? {
    val token = picture ?: return null
    val id = token.toIntOrNull() ?: token.takeIf { it.endsWith(".png") }
        ?.removeSuffix(".png")?.toIntOrNull()
    return id?.takeIf { it > 0 }
}
