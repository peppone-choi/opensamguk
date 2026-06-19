package opensamguk.infra.entity

import jakarta.persistence.AttributeConverter
import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.Converter
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import opensamguk.logic.auction.AuctionType
import opensamguk.logic.auction.ResourceType
import java.time.Instant

/**
 * JPA entity for `ng_auction` table (PHP `ng_auction`, schema.sql:690).
 *
 * The `type`/`reqResource` columns are Postgres ENUMs (`ng_auction_type`/`ng_auction_resource`).
 * `detail` is jsonb — stored as raw string here (the logic `AuctionInfoDetail` codec owns the shape).
 */
@Entity
@Table(name = "ng_auction")
class AuctionEntity(
    @Convert(converter = AuctionTypeValueConverter::class)
    @Column(name = "type", nullable = false, columnDefinition = "ng_auction_type")
    var type: AuctionType,

    @Column(name = "finished", nullable = false)
    var finished: Boolean = false,

    @Column(name = "target")
    var target: String? = null,

    @Column(name = "host_general_id", nullable = false)
    var hostGeneralId: Int,

    @Convert(converter = AuctionResourceValueConverter::class)
    @Column(name = "req_resource", nullable = false, columnDefinition = "ng_auction_resource")
    var reqResource: ResourceType,

    @Column(name = "open_date", nullable = false)
    var openDate: Instant,

    @Column(name = "close_date", nullable = false)
    var closeDate: Instant,

    @Column(name = "detail", nullable = false, columnDefinition = "jsonb")
    var detail: String = "{}",

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Int? = null,
)

@Converter
class AuctionTypeValueConverter : AttributeConverter<AuctionType, String> {
    override fun convertToDatabaseColumn(attribute: AuctionType?): String? = attribute?.value

    override fun convertToEntityAttribute(dbData: String?): AuctionType? =
        dbData?.let(AuctionType::from)
}

@Converter
class AuctionResourceValueConverter : AttributeConverter<ResourceType, String> {
    override fun convertToDatabaseColumn(attribute: ResourceType?): String? = attribute?.value

    override fun convertToEntityAttribute(dbData: String?): ResourceType? =
        dbData?.let(ResourceType::from)
}
