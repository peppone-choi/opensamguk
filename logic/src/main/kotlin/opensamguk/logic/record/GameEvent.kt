package opensamguk.logic.record

import opensamguk.logic.renown.RenownEventSource
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

enum class EventSection { PERSONAL, RETINUE_NATION, COURT, BATTLE, WORLD }
enum class EventAudience { SELF, RETINUE, NATION, COURT, PUBLIC }
enum class PublicationState { PRIVATE, PUBLISHED }

/** Stable identifiers only. Display names are resolved from the event-time world projection. */
sealed interface EventRef {
    data class General(val id: Int) : EventRef { init { require(id > 0) } }
    data class City(val id: Int) : EventRef { init { require(id > 0) } }
    data class Nation(val id: Int) : EventRef { init { require(id >= 0) } }
    data class Corps(val id: String) : EventRef { init { require(isStableKey(id)) } }
    data class Request(val id: String) : EventRef { init { require(isStableKey(id)) } }
    data class RoadFort(val id: String) : EventRef { init { require(isStableKey(id)) } }
    data class Replay(val id: String) : EventRef { init { require(isStableKey(id)) } }
}

private fun isStableKey(value: String): Boolean = value.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}"))

enum class RefRole(val type: Class<out EventRef>) {
    ACTOR(EventRef.General::class.java), PERSON(EventRef.General::class.java), ISSUER(EventRef.General::class.java),
    TARGET(EventRef.General::class.java), CITY(EventRef.City::class.java), NATION(EventRef.Nation::class.java),
    FROM_NATION(EventRef.Nation::class.java), TO_NATION(EventRef.Nation::class.java),
    CORPS(EventRef.Corps::class.java), REQUEST(EventRef.Request::class.java),
    ROAD_FORT(EventRef.RoadFort::class.java), REPLAY(EventRef.Replay::class.java),
}

sealed interface EventFact {
    data class Amount(val value: Long) : EventFact { init { require(value >= 0) } }
    data class Change(val value: Long) : EventFact
    data class TroopsBand(val value: Int) : EventFact { init { require(value in 0..5) } }
    data class Outcome(val code: String) : EventFact { init { require(isStableKey(code)) } }
    data class RewardReason(val code: RewardReasonCode) : EventFact
    data class RenownSource(val code: RenownEventSource) : EventFact
}

enum class RewardReasonCode { WAR_MERIT, DOMESTIC_MERIT, LOYALTY_SUPPORT, ROUTINE_SERVICE }

enum class FactRole(val type: Class<out EventFact>) {
    COUNTIES(EventFact.Amount::class.java), MONEY(EventFact.Amount::class.java), GRAIN(EventFact.Amount::class.java),
    IRON(EventFact.Amount::class.java), TIMBER(EventFact.Amount::class.java), HORSES(EventFact.Amount::class.java),
    RENOWN_BEFORE(EventFact.Amount::class.java), RENOWN_AFTER(EventFact.Amount::class.java),
    RENOWN_CHANGE(EventFact.Change::class.java),
    TROOPS_BAND(EventFact.TroopsBand::class.java), OUTCOME(EventFact.Outcome::class.java),
    REASON(EventFact.RewardReason::class.java),
    SOURCE(EventFact.RenownSource::class.java),
}

data class OccurredAt(val year: Int, val month: Int, val phase: Int, val ordinal: Int) {
    init {
        require(year in 1..9999 && month in 1..12 && phase in 1..3 && ordinal >= 0)
    }
}

/** Recipient snapshots for RETINUE and COURT are sealed at event time; readers also recheck current permission. */
sealed interface AudienceTarget {
    val audience: EventAudience

    data class Self(val generalId: Int) : AudienceTarget {
        init { require(generalId > 0) }
        override val audience = EventAudience.SELF
    }
    class Retinue(val ownerGeneralId: Int, val nationId: Int, authorizedGeneralIds: Set<Int>) : AudienceTarget {
        val authorizedGeneralIds: Set<Int> = authorizedGeneralIds.toSet()
        init { require(ownerGeneralId > 0 && nationId > 0 && this.authorizedGeneralIds.isNotEmpty() && this.authorizedGeneralIds.all { it > 0 }) }
        override val audience = EventAudience.RETINUE
        override fun equals(other: Any?): Boolean = other is Retinue && ownerGeneralId == other.ownerGeneralId && nationId == other.nationId &&
            authorizedGeneralIds == other.authorizedGeneralIds
        override fun hashCode(): Int = 31 * (31 * ownerGeneralId + nationId) + authorizedGeneralIds.hashCode()
        override fun toString(): String = "Retinue(ownerGeneralId=$ownerGeneralId, nationId=$nationId, authorizedGeneralIds=$authorizedGeneralIds)"
    }
    data class Nation(val nationId: Int) : AudienceTarget {
        init { require(nationId > 0) }
        override val audience = EventAudience.NATION
    }
    class Court(val nationId: Int, authorizedGeneralIds: Set<Int>) : AudienceTarget {
        val authorizedGeneralIds: Set<Int> = authorizedGeneralIds.toSet()
        init { require(nationId > 0 && this.authorizedGeneralIds.isNotEmpty() && this.authorizedGeneralIds.all { it > 0 }) }
        override val audience = EventAudience.COURT
        override fun equals(other: Any?): Boolean = other is Court && nationId == other.nationId &&
            authorizedGeneralIds == other.authorizedGeneralIds
        override fun hashCode(): Int = 31 * nationId + authorizedGeneralIds.hashCode()
        override fun toString(): String = "Court(nationId=$nationId, authorizedGeneralIds=$authorizedGeneralIds)"
    }
    data object Public : AudienceTarget { override val audience = EventAudience.PUBLIC }
}

data class Publication(val state: PublicationState, val publishAfter: OccurredAt? = null) {
    init { require(publishAfter == null) { "Delayed publication is reserved, not supported in v1" } }
}

/** Deterministic across retries. The caller supplies stable source coordinates, never a rendered sentence. */
@JvmInline
value class EventKey(val value: String) {
    init { require(value.matches(Regex("[0-9a-f]{64}"))) }

    companion object {
        fun derive(vararg coordinates: String): EventKey {
            require(coordinates.isNotEmpty() && coordinates.all(::isStableKey))
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(coordinates.joinToString("\u001f").toByteArray(StandardCharsets.UTF_8))
            return EventKey(digest.joinToString("") { "%02x".format(it.toInt() and 0xff) })
        }
    }
}

data class GameEvent(
    val worldId: Int,
    val kind: EventKind,
    val occurredAt: OccurredAt,
    val audience: AudienceTarget,
    val publication: Publication,
    val eventKey: EventKey,
    val refs: Map<RefRole, EventRef> = emptyMap(),
    val facts: Map<FactRole, EventFact> = emptyMap(),
) {
    val section: EventSection get() = kind.section

    init {
        require(worldId > 0)
        require(audience.audience in kind.audiences) { "${kind.code} does not allow ${audience.audience}" }
        require((audience == AudienceTarget.Public) == (publication.state == PublicationState.PUBLISHED))
        require(refs.keys.containsAll(kind.requiredRefs)) { "${kind.code} requires ${kind.requiredRefs - refs.keys}" }
        require(facts.keys.containsAll(kind.requiredFacts)) { "${kind.code} requires ${kind.requiredFacts - facts.keys}" }
        require(refs.keys.all { it in kind.allowedRefs && it.type.isInstance(refs.getValue(it)) })
        require(facts.keys.all { it in kind.allowedFacts && it.type.isInstance(facts.getValue(it)) })
        if (kind == EventKind.REWARD_RECEIVED) {
            require((refs.getValue(RefRole.TARGET) as EventRef.General).id ==
                (audience as AudienceTarget.Self).generalId) { "reward target must be the private recipient" }
        }
        if (kind == EventKind.RENOWN_EVENT) {
            require((refs.getValue(RefRole.ACTOR) as EventRef.General).id ==
                (audience as AudienceTarget.Self).generalId) { "renown actor must be the private recipient" }
        }
    }
}
