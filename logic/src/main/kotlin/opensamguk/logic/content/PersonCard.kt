package opensamguk.logic.content

import opensamguk.logic.input.Aptitude
import opensamguk.logic.renown.RenownRules

/** §2.7 bonds. Historical scenarios supply links and evidence in #596. */
enum class PersonBondKind { BLOOD_KIN, NATIVE_COUNTY, PATRONAGE, OATH, RENOWN }
enum class UnitBondKind { VOLUNTEER, CAPTIVE }

data class PersonBond(
    val kind: PersonBondKind,
    /** A person card id, except NATIVE_COUNTY which references a county id. */
    val targetId: String,
    val evidenceIds: Set<String> = emptySet(),
) {
    init {
        require(targetId.isNotBlank())
        require(evidenceIds.none { it.isBlank() })
    }
}

data class UnitBond(val kind: UnitBondKind, val sourceId: String, val evidenceIds: Set<String> = emptySet()) {
    init {
        require(sourceId.isNotBlank() && evidenceIds.none { it.isBlank() })
    }
}

/** Persisted on the person general, ready for #596's source-backed historical prefill. */
data class PersonBondState(val bonds: Set<PersonBond>) {
    init { require(bonds.map { it.kind to it.targetId }.distinct().size == bonds.size) }
    fun toMetaValue(): Map<String, Any> = mapOf("version" to 1, "bonds" to bonds.sortedWith(
        compareBy({ it.kind.name }, { it.targetId })).map { bond -> mapOf(
        "kind" to bond.kind.name, "targetId" to bond.targetId, "evidenceIds" to bond.evidenceIds.sorted()) })

    companion object {
        const val META_KEY = "hwihaPersonBonds"

        fun read(meta: Map<String, Any?>): PersonBondState? {
            if (META_KEY !in meta) return null
            val row = meta[META_KEY] as? Map<*, *> ?: invalid()
            require(row.keys == setOf("version", "bonds") && row["version"] == 1)
            val bonds = (row["bonds"] as? List<*>)?.map { raw ->
                val item = raw as? Map<*, *> ?: invalid()
                require(item.keys == setOf("kind", "targetId", "evidenceIds"))
                val kind = (item["kind"] as? String)?.let { key -> PersonBondKind.entries.firstOrNull { it.name == key } }
                    ?: invalid()
                val evidence = (item["evidenceIds"] as? List<*>)?.map { it as? String ?: invalid() } ?: invalid()
                require(evidence.distinct().size == evidence.size)
                PersonBond(kind, item["targetId"] as? String ?: invalid(), evidence.toSet())
            } ?: invalid()
            require(bonds.distinct().size == bonds.size)
            return PersonBondState(bonds.toSet())
        }

        private fun invalid(): Nothing = throw IllegalArgumentException("invalid person bonds")
    }
}

/** Nullable historical fields are genuinely unknown, never importer defaults. */
data class PersonCardIdentity(
    val generalId: Int?,
    val bornYear: Int?,
    val deathYear: Int?,
    val personality: String?,
    val specialties: Set<String>,
    val portrait: String?,
) {
    init {
        require(generalId == null || generalId > 0)
        require(bornYear == null || deathYear == null || bornYear <= deathYear)
        require(specialties.none { it.isBlank() })
    }
}

data class PersonCardSeason(
    val provinceId: String?,
    val office: String?,
    val loyalty: Int,
    val experience: Int,
    val injury: Int,
    val fatigue: Int,
    val salary: Long,
    /** Slot count is undecided; IDs are attachments, with no invented maximum. */
    val attachedTreasureIds: List<String>,
) {
    init {
        require(loyalty in 0..100 && experience >= 0 && injury >= 0 && fatigue >= 0 && salary >= 0)
        require(attachedTreasureIds.none { it.isBlank() })
        require(attachedTreasureIds.distinct().size == attachedTreasureIds.size)
    }
}

/** One card carries the §6.1 header, §6.2 person fields, and current season state. */
data class PersonCard(
    val header: CardHeader,
    val identity: PersonCardIdentity,
    val stats: Aptitude.Stats,
    val bonds: Set<PersonBond>,
    /** Card catalogue ids contributed to the direct holder's deck; count is not fixed here. */
    val stratagemCardIds: Set<String>,
    val season: PersonCardSeason,
) {
    init {
        require(header.kind == CardKind.PERSON)
        require((identity.generalId == null) == (header.availability == CardAvailability.COMMON))
        require(bonds.map { it.kind to it.targetId }.distinct().size == bonds.size)
        require(stratagemCardIds.none { it.isBlank() })
        require(header.renownCost == RenownRules.personCost(
            stats.leadership, stats.strength, stats.intelligence, stats.politics, stats.charm))
    }

    val aptitude: Aptitude.Aptitudes get() = Aptitude.compute(stats)
}

/** Stored on the linked general; #596 can seed historical contributions without changing the hand format. */
data class PersonContributionState(val stratagemCardIds: Set<String>) {
    init { require(stratagemCardIds.none { it.isBlank() }) }

    fun toMetaValue(): Map<String, Any> = mapOf("version" to 1, "stratagemCardIds" to stratagemCardIds.sorted())

    companion object {
        const val META_KEY = "hwihaPersonContribution"

        fun read(meta: Map<String, Any?>): PersonContributionState? {
            if (META_KEY !in meta) return null
            val row = meta[META_KEY] as? Map<*, *> ?: invalid()
            require(row.keys == setOf("version", "stratagemCardIds") && row["version"] == 1)
            val ids = (row["stratagemCardIds"] as? List<*>)?.map { it as? String ?: invalid() } ?: invalid()
            require(ids.distinct().size == ids.size)
            return PersonContributionState(ids.toSet())
        }

        private fun invalid(): Nothing = throw IllegalArgumentException("invalid person stratagem contribution")
    }
}

data class DirectPersonHolding(val holderGeneralId: Int, val card: PersonCard, val cardIsNpc: Boolean) {
    init { require(holderGeneralId > 0) }
}

object PersonStratagemDeck {
    /** Contribution follows the immediate holder, including an NPC subordinate of a person general. */
    fun contributedIds(holderGeneralId: Int, holdings: List<DirectPersonHolding>, catalogue: Collection<CardHeader>): List<String> {
        require(holderGeneralId > 0)
        require(holdings.map { it.card.header.id }.distinct().size == holdings.size) { "one person card can have only one holder" }
        require(catalogue.map { it.id }.distinct().size == catalogue.size)
        val stratagemIds = catalogue.filter { it.kind == CardKind.STRATAGEM }.mapTo(hashSetOf()) { it.id }
        require(holdings.all { holding -> holding.card.stratagemCardIds.all { it in stratagemIds } }) {
            "person contribution references an unknown stratagem card"
        }
        return holdings.filter { it.holderGeneralId == holderGeneralId && it.cardIsNpc }
            .sortedBy { it.card.header.id }.flatMap { it.card.stratagemCardIds.sorted() }
    }
}
