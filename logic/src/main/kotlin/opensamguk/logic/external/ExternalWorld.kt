package opensamguk.logic.external

import opensamguk.common.rng.LiteHashDrbg
import opensamguk.common.rng.RandUtil
import opensamguk.common.rng.serializeSeed
import opensamguk.common.world.WorldId

/** These IDs name diplomatic actors outside the map, never entries in the nation table. */
@JvmInline
value class ExternalActorId(val value: String) {
    init { require(value.startsWith("external:") && value.length > "external:".length) }
}

enum class ExternalRelation { HOSTILE, TRIBUTARY, SUBMITTED, TRADE, NEUTRAL }
enum class ExternalEventKind { BORDER_RAID, TRIBUTE, SUBMISSION, TRADE }

data class ExternalContact(
    val nationId: Int,
    val actorId: ExternalActorId,
    val relation: ExternalRelation,
    val borderCountyId: Int,
) {
    init { require(nationId > 0 && borderCountyId > 0) }
}

data class ExternalEffect(
    val money: Int = 0,
    val grain: Int = 0,
    val trust: Int = 0,
    val population: Int = 0,
    val defence: Int = 0,
)

data class ExternalEventRule(
    val kind: ExternalEventKind,
    val relations: Set<ExternalRelation>,
    val resultingRelation: ExternalRelation,
    val chancePermille: Int,
    val effect: ExternalEffect,
) {
    init {
        require(relations.isNotEmpty())
        require(chancePermille in 0..1000)
    }
}

data class ExternalOccurrence(
    val nationId: Int,
    val actorId: ExternalActorId,
    val countyId: Int,
    val kind: ExternalEventKind,
    val resultingRelation: ExternalRelation,
    val effect: ExternalEffect,
)

object ExternalWorld {
    /** Fail closed if an external actor is accidentally projected into a playable nation key set. */
    fun assertOutsideNationTable(nationKeys: Collection<String>) {
        require(nationKeys.none { it.startsWith("external:") }) { "external actor cannot become a nation" }
    }

    /** Each contact/event owns its RNG stream, so collection iteration order cannot change results. */
    fun decide(
        hiddenSeed: String,
        worldId: WorldId,
        year: Int,
        month: Int,
        phase: Int,
        actors: Collection<ExternalActor>,
        contacts: Collection<ExternalContact>,
        rules: Collection<ExternalEventRule>,
    ): List<ExternalOccurrence> {
        require(hiddenSeed.isNotBlank() && month in 1..12 && phase in 1..3)
        val actorById = actors.associateBy { it.id }
        require(actorById.size == actors.size) { "duplicate external actor" }
        require(contacts.all { it.actorId in actorById }) { "orphan external contact" }
        require(contacts.all { contact ->
            val actor = actorById.getValue(contact.actorId)
            actor.activation == "ACTIVE" && actor.subjectPeriod?.contains(year) == true
        }) { "inactive or out-of-period external actor" }
        require(contacts.map { Triple(it.nationId, it.actorId, it.borderCountyId) }.distinct().size == contacts.size) {
            "duplicate external contact"
        }
        require(rules.map { it.kind }.distinct().size == rules.size) { "duplicate external event rule" }
        return contacts.sortedWith(compareBy({ it.nationId }, { it.actorId.value }, { it.borderCountyId })).flatMap { contact ->
            rules.sortedBy { it.kind.name }.mapNotNull { rule ->
                if (contact.relation !in rule.relations) return@mapNotNull null
                val seed = serializeSeed(hiddenSeed, "externalWorld", worldId.value, year, month, phase,
                    contact.actorId.value, contact.nationId, contact.borderCountyId, rule.kind.name)
                val roll = RandUtil(LiteHashDrbg(seed)).nextRangeInt(0, 999)
                if (roll < rule.chancePermille) ExternalOccurrence(contact.nationId, contact.actorId,
                    contact.borderCountyId, rule.kind, rule.resultingRelation, rule.effect) else null
            }
        }
    }
}
