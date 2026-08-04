package opensamguk.gameapi.read

import jakarta.persistence.AttributeConverter
import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.Converter
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import opensamguk.common.world.WorldId
import opensamguk.gameapi.config.GameApiProcessWorld
import opensamguk.infra.persistence.MetaJson
import org.springframework.stereotype.Repository
import org.springframework.data.repository.Repository as SpringDataRepository

data class HistoryJsonValue(val value: Any? = emptyList<Any?>())

@Converter
class HistoryJsonValueConverter : AttributeConverter<HistoryJsonValue, String> {
    override fun convertToDatabaseColumn(attribute: HistoryJsonValue?): String =
        MetaJson.encode(attribute?.value)

    override fun convertToEntityAttribute(dbData: String?): HistoryJsonValue =
        HistoryJsonValue(decodeHistoryJsonValue(dbData))
}

@Converter
class HistoryJsonStringListConverter : AttributeConverter<List<String>, String> {
    override fun convertToDatabaseColumn(attribute: List<String>?): String =
        MetaJson.encode(attribute ?: emptyList<String>())

    override fun convertToEntityAttribute(dbData: String?): List<String> =
        (decodeHistoryJsonValue(dbData) as? List<*>)?.mapNotNull { it as? String }.orEmpty()
}

private fun decodeHistoryJsonValue(dbData: String?): Any? {
    val json = dbData?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return MetaJson.decode("""{"value":$json}""")["value"]
}

/**
 * yearbook_history READ — process-world scoped (OPENSAM-127 residual / GWT cold-history).
 */
@Entity
@Table(name = "yearbook_history")
class YearbookHistoryReadEntity(
    @Id
    @Column(name = "id")
    var id: Int = 0,

    @Column(name = "world_id")
    var worldId: Int = 0,

    @Column(name = "profile_name")
    var profileName: String = "",

    @Column(name = "year")
    var year: Int = 0,

    @Column(name = "month")
    var month: Int = 0,

    @Convert(converter = MetaJsonConverter::class)
    @Column(name = "map", columnDefinition = "jsonb")
    var map: Map<String, Any?> = linkedMapOf(),

    @Convert(converter = HistoryJsonValueConverter::class)
    @Column(name = "nations", columnDefinition = "jsonb")
    var nations: HistoryJsonValue = HistoryJsonValue(),

    @Convert(converter = HistoryJsonStringListConverter::class)
    @Column(name = "global_history", columnDefinition = "jsonb")
    var globalHistory: List<String> = emptyList(),

    @Convert(converter = HistoryJsonStringListConverter::class)
    @Column(name = "global_action", columnDefinition = "jsonb")
    var globalAction: List<String> = emptyList(),

    @Column(name = "hash")
    var hash: String = "",
)

interface HistoryReadRawRepository : SpringDataRepository<YearbookHistoryReadEntity, Int> {
    fun findByWorldIdOrderByYearAscMonthAsc(worldId: Int): List<YearbookHistoryReadEntity>
    fun findByWorldIdAndYearAndMonthOrderByIdAsc(worldId: Int, year: Int, month: Int): List<YearbookHistoryReadEntity>
}

@Repository
class HistoryReadRepository(
    private val raw: HistoryReadRawRepository,
    processWorld: GameApiProcessWorld,
) {
    private val worldId: WorldId = processWorld.worldId

    fun findAllByOrderByYearAscMonthAsc(): List<YearbookHistoryReadEntity> =
        raw.findByWorldIdOrderByYearAscMonthAsc(worldId.value)

    fun findByYearAndMonthOrderByIdAsc(year: Int, month: Int): List<YearbookHistoryReadEntity> =
        raw.findByWorldIdAndYearAndMonthOrderByIdAsc(worldId.value, year, month)
}
