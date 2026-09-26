package opensamguk.logic.record

import opensamguk.logic.record.EventAudience.PUBLIC
import opensamguk.logic.record.EventAudience.RETINUE
import opensamguk.logic.record.EventAudience.SELF
import opensamguk.logic.record.EventSection.*
import opensamguk.logic.record.RefRole.*
import opensamguk.logic.record.FactRole.*

/** The only accepted event kinds. Each entry fixes its feed, audience, and structured payload schema. */
enum class EventKind(
    val code: String,
    val section: EventSection,
    val audiences: Set<EventAudience>,
    val requiredRefs: Set<RefRole> = emptySet(),
    val allowedRefs: Set<RefRole> = requiredRefs,
    val allowedFacts: Set<FactRole> = emptySet(),
) {
    MARCH_ASSIGNMENT("march.assignment", PERSONAL, setOf(SELF), allowedRefs = setOf(ACTOR, CITY)),
    MARCH_CORPS("march.corps", BATTLE, setOf(SELF, RETINUE, EventAudience.NATION), allowedRefs = setOf(ACTOR, CORPS, CITY)),
    MARCH_DIRECT("march.direct", BATTLE, setOf(SELF), allowedRefs = setOf(ACTOR, CITY)),
    PERSONAL_ENCOUNTER("encounter.personal", BATTLE, setOf(SELF), allowedRefs = setOf(ACTOR, CITY, REPLAY)),
    MUSTER_ORDERED("military.musterOrdered", BATTLE, setOf(SELF, EventAudience.NATION), allowedRefs = setOf(ACTOR, CITY, CORPS)),
    DEPLOY_STARTED("deploy.started", BATTLE, setOf(SELF, RETINUE, EventAudience.NATION), allowedRefs = setOf(ACTOR, CITY, CORPS)),
    ENCOUNTER_PENDING("encounter.pending", BATTLE, setOf(SELF, RETINUE, EventAudience.NATION), allowedRefs = setOf(ACTOR, CITY, CORPS)),
    ENCOUNTER_DISBANDED("encounter.disbanded", BATTLE, setOf(SELF, RETINUE, EventAudience.NATION), allowedRefs = setOf(ACTOR, CITY, CORPS)),
    DISPATCH_ISSUED("court.dispatchIssued", COURT, setOf(EventAudience.COURT), requiredRefs = setOf(REQUEST), allowedRefs = setOf(REQUEST, ISSUER, TARGET)),
    DISPATCH_RECEIVED("court.dispatchReceived", COURT, setOf(EventAudience.COURT), requiredRefs = setOf(REQUEST), allowedRefs = setOf(REQUEST, ISSUER, TARGET)),
    DISPATCH_ACCEPTED("court.dispatchAccepted", COURT, setOf(EventAudience.COURT), requiredRefs = setOf(REQUEST), allowedRefs = setOf(REQUEST, ISSUER, TARGET)),
    DISPATCH_REFUSED("court.dispatchRefused", COURT, setOf(EventAudience.COURT), requiredRefs = setOf(REQUEST), allowedRefs = setOf(REQUEST, ISSUER, TARGET)),
    DISPATCH_CANCELLED("court.dispatchCancelled", COURT, setOf(EventAudience.COURT), requiredRefs = setOf(REQUEST), allowedRefs = setOf(REQUEST, ISSUER, TARGET)),
    ENLISTED("enlist.joined", PERSONAL, setOf(SELF), requiredRefs = setOf(NATION), allowedRefs = setOf(NATION, ACTOR)),
    RETAINER_JOINED("enlist.retainerJoined", RETINUE_NATION, setOf(SELF, RETINUE), requiredRefs = setOf(PERSON), allowedRefs = setOf(PERSON, ACTOR)),
    INPUT_REJECTED("input.rejected", PERSONAL, setOf(SELF), allowedRefs = setOf(ACTOR)),
    FIELD_APPLIED("field.applied", RETINUE_NATION, setOf(SELF), allowedRefs = setOf(ACTOR, CITY)),
    PERSONAL_APPLIED("personal.applied", PERSONAL, setOf(SELF), allowedRefs = setOf(ACTOR)),
    PEOPLE_SEARCHED("people.searched", RETINUE_NATION, setOf(SELF, RETINUE), allowedRefs = setOf(ACTOR, CITY, PERSON)),
    PEOPLE_JOINED("people.joined", RETINUE_NATION, setOf(SELF, RETINUE), requiredRefs = setOf(PERSON), allowedRefs = setOf(ACTOR, PERSON)),
    PEOPLE_RESISTED("people.resisted", RETINUE_NATION, setOf(SELF), allowedRefs = setOf(ACTOR, PERSON)),
    RENOWN_EVENT("renown.event", PERSONAL, setOf(SELF), allowedRefs = setOf(ACTOR, CITY), allowedFacts = setOf(RENOWN_CHANGE)),
    YUEDAN_ASSESSED("yuedan.assessed", PERSONAL, setOf(SELF), allowedRefs = setOf(ACTOR),
        allowedFacts = setOf(RENOWN_BEFORE, RENOWN_AFTER, RENOWN_CHANGE)),
    DEPARTURE_JUDGED("retinue.departureJudged", RETINUE_NATION, setOf(SELF), requiredRefs = setOf(PERSON), allowedRefs = setOf(ACTOR, PERSON)),
    RETINUE_DEPARTED("retinue.departed", RETINUE_NATION, setOf(SELF), allowedRefs = setOf(ACTOR, PERSON)),
    ROAD_FORT_SIEGE("roadFort.siege", BATTLE, setOf(SELF, RETINUE, EventAudience.NATION), requiredRefs = setOf(ROAD_FORT), allowedRefs = setOf(ROAD_FORT, ACTOR, CORPS)),
    INCOME_MONTHLY("income.monthly", RETINUE_NATION, setOf(EventAudience.NATION), allowedRefs = setOf(NATION),
        allowedFacts = setOf(COUNTIES, MONEY, GRAIN, IRON, TIMBER, HORSES)),
    /** Legacy input kinds: writers must fold these two rows into one OWNER_CHANGED event. */
    COUNTY_CAPTURED("county.captured", WORLD, emptySet()),
    COUNTY_LOST("county.lost", WORLD, emptySet()),
    ROAD_FORT_CAPTURED("roadFort.captured", WORLD, setOf(PUBLIC), requiredRefs = setOf(ROAD_FORT, TO_NATION),
        allowedRefs = setOf(ROAD_FORT, FROM_NATION, TO_NATION)),
    YUEDAN_ANNOUNCED("yuedan.announced", WORLD, setOf(PUBLIC)),
    OWNER_CHANGED("county.ownerChanged", WORLD, setOf(PUBLIC), requiredRefs = setOf(CITY, FROM_NATION, TO_NATION)),
    ;

    init {
        require(requiredRefs.all { it in allowedRefs })
        require((section == WORLD) == (PUBLIC in audiences || audiences.isEmpty()))
        require(PUBLIC !in audiences || audiences == setOf(PUBLIC))
    }

    companion object {
        private val byCode = entries.associateBy { it.code }
        fun fromCode(code: String): EventKind? = byCode[code]
        val publicKinds: Set<EventKind> = entries.filterTo(mutableSetOf()) { PUBLIC in it.audiences }
    }
}
