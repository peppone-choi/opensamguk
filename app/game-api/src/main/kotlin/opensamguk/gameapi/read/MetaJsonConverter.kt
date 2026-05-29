package opensamguk.gameapi.read

import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter
import opensamguk.infra.persistence.MetaJson

/**
 * JPA `@Convert` AttributeConverter for the `meta` jsonb columns on the READ path
 * (game-api precheck only — the legitimate JPA use per §7).
 *
 * Reuses the shared [MetaJson] codec (the SAME byte-faithful PHP `Json::encode` codec used by the
 * JDBC write path), so there is exactly one jsonb codec across read + write. Order need NOT be
 * preserved on the read path (precheck does not write back), but [MetaJson.decode] returns a
 * `LinkedHashMap` regardless, so the read map is insertion-ordered for free.
 *
 * The column type is jsonb; Hibernate binds the converted `String` as a `text`/`varchar` parameter.
 * On a Postgres jsonb column, an explicit text->jsonb cast is required for writes, but the precheck
 * read path only ever reads, so `convertToDatabaseColumn` is exercised only by tests / future code.
 */
@Converter
class MetaJsonConverter : AttributeConverter<Map<String, Any?>, String> {
    override fun convertToDatabaseColumn(attribute: Map<String, Any?>?): String =
        MetaJson.encode(attribute ?: emptyMap<String, Any?>())

    override fun convertToEntityAttribute(dbData: String?): Map<String, Any?> =
        MetaJson.decode(dbData)
}
