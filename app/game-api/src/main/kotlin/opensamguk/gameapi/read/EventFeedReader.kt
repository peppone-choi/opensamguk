package opensamguk.gameapi.read

import opensamguk.gameapi.dto.GameEventDto
import opensamguk.gameapi.dto.GameEventPage
import opensamguk.gameapi.dto.GameEventTimeDto
import opensamguk.gameapi.owner.GeneralResolver
import opensamguk.logic.record.EventAudience
import opensamguk.logic.record.EventFact
import opensamguk.logic.record.EventKind
import opensamguk.logic.record.EventPayloadCodec
import opensamguk.logic.record.EventRef
import opensamguk.logic.record.EventSection
import opensamguk.logic.record.FactRole
import opensamguk.logic.record.RefRole
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.nio.charset.StandardCharsets
import java.util.Base64

/** The URL cursor is only a position. It never grants access to its row or world. */
object EventFeedCursor {
    fun encode(worldId: Int, section: EventSection, position: EventFeedPosition): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(
            "1|$worldId|${section.name}|${position.year}|${position.month}|${position.phase}|${position.ordinal}|${position.id}"
                .toByteArray(StandardCharsets.US_ASCII),
        )

    fun decode(value: String?, worldId: Int, section: EventSection): EventFeedPosition? {
        if (value == null) return null
        val raw = try {
            require(value.length in 1..160 && value.matches(Regex("[A-Za-z0-9_-]+")))
            String(Base64.getUrlDecoder().decode(value), StandardCharsets.US_ASCII)
        } catch (_: IllegalArgumentException) { throw badCursor() }
        val parts = raw.split('|')
        try {
            require(parts.size == 8 && parts[0] == "1" && parts[1].toInt() == worldId && parts[2] == section.name)
            return EventFeedPosition(parts[3].toInt(), parts[4].toInt(), parts[5].toInt(),
                parts[6].toInt(), parts[7].toLong())
        } catch (_: IllegalArgumentException) { throw badCursor() }
    }

    private fun badCursor() = ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid event cursor")
}

/** A second, typed authorization check after the world-scoped SQL candidate query. */
object EventFeedPolicy {
    fun project(row: EventFeedRow, generalId: Int?, nationId: Int, permission: Int,
                officerLevel: Int = 0): GameEventDto? {
        val kind = EventKind.fromCode(row.kind) ?: return null
        if (kind.section.name != row.section || row.hasDelayedPublication) return null
        val audience = EventAudience.entries.find { it.name == row.audience } ?: return null
        if (audience !in kind.audiences) return null
        val refs = runCatching { EventPayloadCodec.decodeRefs(row.refsJson) }.getOrNull() ?: return null
        val facts = runCatching { EventPayloadCodec.decodeFacts(row.factsJson) }.getOrNull() ?: return null
        if (!refs.keys.containsAll(kind.requiredRefs) || refs.any { (role, ref) ->
                role !in kind.allowedRefs || !role.type.isInstance(ref)
            } || facts.any { (role, fact) ->
                role !in kind.allowedFacts || !role.type.isInstance(fact)
            }) return null

        when (audience) {
            EventAudience.SELF -> {
                if (generalId == null || row.audienceGeneralId != generalId ||
                    row.audienceNationId != null || row.recipientGeneralIds.isNotEmpty() ||
                    row.publicationState != "PRIVATE") return null
            }
            EventAudience.RETINUE -> {
                val required = if (kind.section == EventSection.BATTLE) 2 else 1
                if (generalId == null || row.audienceGeneralId == null || row.audienceGeneralId <= 0 ||
                    row.audienceNationId == null || row.audienceNationId <= 0 ||
                    nationId != row.audienceNationId || generalId !in row.recipientGeneralIds ||
                    permission < required || row.publicationState != "PRIVATE") return null
            }
            EventAudience.NATION -> {
                if (generalId == null || row.audienceGeneralId != null || row.recipientGeneralIds.isNotEmpty() ||
                    row.audienceNationId == null || row.audienceNationId <= 0 ||
                    nationId != row.audienceNationId || permission < 2 || row.publicationState != "PRIVATE") return null
            }
            EventAudience.COURT -> {
                if (generalId == null || row.audienceGeneralId != null || row.audienceNationId == null ||
                    row.audienceNationId <= 0 || nationId != row.audienceNationId ||
                    generalId !in row.recipientGeneralIds || row.publicationState != "PRIVATE") return null
                val direct = (refs[RefRole.ISSUER] as? EventRef.General)?.id == generalId ||
                    (refs[RefRole.TARGET] as? EventRef.General)?.id == generalId
                if (direct) {
                    if (permission < 0) return null
                } else if (permission < 2 || officerLevel < 2) return null
            }
            EventAudience.PUBLIC -> {
                if (kind !in EventKind.publicKinds || kind.section != EventSection.WORLD ||
                    row.audienceGeneralId != null || row.audienceNationId != null ||
                    row.recipientGeneralIds.isNotEmpty() || row.publicationState != "PUBLISHED" ||
                    facts.isNotEmpty()) return null
            }
        }

        // Battle actor, location, corps and replay identifiers need separate vision/replay
        // authorization. Until that projection is available, only classification is returned.
        val safeRefs = refs.filterKeys { role ->
            !(kind.section == EventSection.BATTLE && role in setOf(RefRole.ACTOR, RefRole.CITY,
                RefRole.CORPS, RefRole.ROAD_FORT, RefRole.REPLAY)) &&
                !(kind == EventKind.PEOPLE_SEARCHED && role == RefRole.PERSON)
        }.mapValues { (_, ref) -> when (ref) {
            is EventRef.General -> ref.id
            is EventRef.City -> ref.id
            is EventRef.Nation -> ref.id
            is EventRef.Corps -> ref.id
            is EventRef.Request -> ref.id
            is EventRef.RoadFort -> ref.id
            is EventRef.Replay -> ref.id
        } }.mapKeys { it.key.name }
        val safeFacts = (if (kind.section == EventSection.BATTLE) emptyMap<FactRole, EventFact>() else facts).mapValues { (_, fact) -> when (fact) {
            is EventFact.Amount -> fact.value
            is EventFact.Change -> fact.value
            is EventFact.TroopsBand -> fact.value
            is EventFact.Outcome -> fact.code
        } }.mapKeys { it.key.name }
        return GameEventDto(row.id, kind.code, kind.section.name,
            GameEventTimeDto(row.year, row.month, row.phase, row.ordinal), safeRefs, safeFacts)
    }
}

@Service
class EventFeedReader(
    private val worlds: WorldStateReadRepository,
    private val resolver: GeneralResolver,
    private val secretPermission: SecretPermissionReader,
    private val events: EventFeedReadRepository,
) {
    fun privateFeed(userId: Long, section: EventSection, before: String?, limit: Int): GameEventPage {
        requireLimit(limit)
        if (section == EventSection.WORLD) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid event section")
        val world = worlds.findProcessWorld() ?: throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE)
        val position = EventFeedCursor.decode(before, world.id, section)
        val me = resolver.resolve(userId) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        if (me.general.worldId != world.id) throw ResponseStatusException(HttpStatus.NOT_FOUND)
        return page(world.id, section, position, limit, me.general.id, me.nationId,
            secretPermission.of(me), me.officerLevel)
    }

    fun publicFeed(before: String?, limit: Int): GameEventPage {
        requireLimit(limit)
        val world = worlds.findProcessWorld() ?: throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE)
        return page(world.id, EventSection.WORLD,
            EventFeedCursor.decode(before, world.id, EventSection.WORLD), limit, null, 0, -1, 0)
    }

    private fun page(worldId: Int, section: EventSection, before: EventFeedPosition?, limit: Int,
                     generalId: Int?, nationId: Int, permission: Int, officerLevel: Int): GameEventPage {
        val visible = ArrayList<Pair<GameEventDto, EventFeedPosition>>(limit + 1)
        var cursor = before
        var examined = 0
        var exhausted = false
        while (visible.size <= limit && examined < MAX_SCAN) {
            val batchSize = minOf(BATCH, MAX_SCAN - examined)
            val rows = if (section == EventSection.WORLD) events.publicCandidates(worldId, cursor, batchSize)
                else events.privateCandidates(worldId, section, requireNotNull(generalId), nationId, cursor, batchSize)
            if (rows.isEmpty()) { exhausted = true; break }
            for (row in rows) {
                examined++
                cursor = row.position
                val dto = EventFeedPolicy.project(row, generalId, nationId, permission, officerLevel) ?: continue
                visible += dto to row.position
                if (visible.size > limit) break
            }
            if (visible.size > limit) break
            if (rows.size < batchSize) { exhausted = true; break }
        }
        val hasMore = visible.size > limit || !exhausted
        val output = visible.take(limit)
        val nextPosition = if (!hasMore) null else output.lastOrNull()?.second ?: cursor
        return GameEventPage(output.map { it.first }, nextPosition?.let { EventFeedCursor.encode(worldId, section, it) })
    }

    private fun requireLimit(limit: Int) {
        if (limit !in 1..50) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Event limit must be 1..50")
    }

    private companion object {
        const val BATCH = 128
        const val MAX_SCAN = 4096
    }
}
