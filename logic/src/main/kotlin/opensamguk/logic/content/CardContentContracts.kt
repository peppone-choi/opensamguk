package opensamguk.logic.content

/** Shared identity and provenance for the four card families in redesign §6.1. */
enum class CardKind { PERSON, UNIT, STRATAGEM, TREASURE }
enum class CardAvailability { UNIQUE, COMMON }
enum class CostColor { MONEY, GRAIN, IRON, TIMBER, HORSES }

sealed interface CardProvenance {
    val badge: String

    data class Citation(val book: String, val volume: String) : CardProvenance {
        init {
            require(book.isNotBlank() && volume.isNotBlank())
        }

        override val badge: String get() = "$book $volume"
    }

    data object GameTerm : CardProvenance {
        override val badge: String = "게임 용어"
    }
}

data class CardHeader(
    val id: String,
    val name: String,
    val kind: CardKind,
    val availability: CardAvailability,
    val provenance: List<CardProvenance>,
    val renownCost: Int,
    val costColors: Set<CostColor>,
    val tags: Set<String>,
) {
    init {
        require(id.isNotBlank() && name.isNotBlank())
        require(provenance.isNotEmpty())
        require(renownCost >= 0)
        require(tags.none { it.isBlank() })
    }

    val provenanceBadges: List<String> get() = provenance.map { it.badge }
}

/** Only the six §8.1 county axes. Roads and post stations belong to construction. */
data class CountyStateContract(
    val households: Long,
    val fields: Long,
    val market: Long,
    val publicSentiment: Int,
    val defence: Long,
    val specialtyIds: Set<String>,
) {
    init {
        require(households >= 0 && fields >= 0 && market >= 0 && defence >= 0)
        require(publicSentiment >= 0)
        require(specialtyIds.none { it.isBlank() })
    }
}

enum class ConstructionKind { IRRIGATION, MILITARY_FARM, FORTIFICATION, ROAD, POST_STATION, WAREHOUSE, WATCHTOWER_BEACON, BARRACKS, MARKET_WATERWAY }

data class CountyConstructionContract(
    val id: String,
    val name: String,
    val kind: ConstructionKind,
    val resourceCosts: Map<CostColor, Long>,
    val requiredProgress: Int,
) {
    init {
        require(id.isNotBlank() && name.isNotBlank() && requiredProgress > 0)
        require(resourceCosts.values.all { it >= 0 })
    }
}

enum class SpecialtyKind { SALT, COPPER, SILK, IRON, TIMBER, HORSES, OTHER }
enum class ResourceSiteResolution { COUNTY, COMMANDERY, REGION, UNRESOLVED }

/** A specialty is a county modifier; a resource site may remain unresolved. */
data class SpecialtySiteContract(
    val id: String,
    val kind: SpecialtyKind,
    val resolution: ResourceSiteResolution,
    val countyId: String?,
    val commanderyId: String?,
    val provenance: List<CardProvenance>,
) {
    init {
        require(id.isNotBlank())
        require(resolution != ResourceSiteResolution.COUNTY || !countyId.isNullOrBlank())
        require(provenance.isNotEmpty() || resolution == ResourceSiteResolution.UNRESOLVED)
    }
}

data class ContentClaim(val id: String, val subjectId: String, val evidenceIds: List<String>)
data class ContentEvidence(val id: String, val provenance: CardProvenance)

/** An import batch consumes each source once and creates all its outputs together. */
data class ContentMaterialization(val sourceId: String, val createdIds: List<String>)

data class CardContentBundle(
    val sourceIds: Set<String>,
    val cards: List<CardHeader>,
    val constructions: List<CountyConstructionContract>,
    val sites: List<SpecialtySiteContract>,
    val evidence: List<ContentEvidence>,
    val claims: List<ContentClaim>,
    val materializations: List<ContentMaterialization>,
)

object CardContentValidator {
    fun violations(bundle: CardContentBundle): List<String> = buildList {
        val contentIds = bundle.cards.map { it.id } + bundle.constructions.map { it.id } + bundle.sites.map { it.id }
        val contentSet = contentIds.toSet()
        val evidenceIds = bundle.evidence.map { it.id }
        fun duplicates(ids: List<String>, label: String) {
            ids.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.sorted().forEach { add("duplicate $label: $it") }
        }
        duplicates(contentIds, "content id")
        duplicates(evidenceIds, "evidence id")
        duplicates(bundle.claims.map { it.id }, "claim id")
        duplicates(bundle.materializations.map { it.sourceId }, "consumed source")
        bundle.evidence.filter { it.id.isBlank() }.forEach { add("blank evidence id") }
        bundle.claims.forEach { claim ->
            if (claim.id.isBlank() || claim.subjectId !in contentSet) add("dangling claim ${claim.id}: ${claim.subjectId}")
            if (claim.evidenceIds.isEmpty()) add("claim ${claim.id} has no evidence")
            claim.evidenceIds.forEach { if (it !in evidenceIds) add("dangling evidence $it for claim ${claim.id}") }
            duplicates(claim.evidenceIds, "evidence reference in claim ${claim.id}")
        }
        bundle.materializations.forEach { row ->
            if (row.sourceId !in bundle.sourceIds || row.createdIds.any { it !in contentSet }) {
                add("dangling materialization: ${row.sourceId} -> ${row.createdIds}")
            }
            if (row.createdIds.isEmpty()) add("partial creation: ${row.sourceId} has no output")
        }
        duplicates(bundle.materializations.flatMap { it.createdIds }, "created content")
    }
}
