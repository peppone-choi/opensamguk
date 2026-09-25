package opensamguk.logic.misinformation

import opensamguk.common.rng.LiteHashDrbg
import opensamguk.common.rng.RandUtil
import opensamguk.common.rng.serializeSeed
import opensamguk.common.world.WorldId
import opensamguk.logic.input.Phase
import opensamguk.logic.vision.CorpsSighting
import opensamguk.logic.vision.ScoutReport
import opensamguk.logic.vision.ScoutedCorps
import opensamguk.logic.vision.VisionTier
import opensamguk.logic.vision.VisionView

/** Only the server stores these records. They never become deployments or order rows. */
data class FalseSighting(
    val id: String,
    val casterGeneralId: Int,
    val victimGeneralId: Int,
    val commanderyId: String,
    val falseCorps: ScoutedCorps,
    val createdAt: Phase,
    val expiresAt: Phase,
) {
    init {
        require(id.matches(Regex("[A-Za-z0-9._:-]{1,128}")))
        require(casterGeneralId > 0 && victimGeneralId > 0 && casterGeneralId != victimGeneralId)
        require(commanderyId.isNotBlank() && expiresAt > createdAt)
    }
}

data class MisinformationRules(
    val durationTurns: Int,
    val detectionPermille: Int,
    val counterintelligenceMoneyCost: Int,
    val feignedCorpsMoneyCost: Int,
) {
    init {
        require(durationTurns > 0 && detectionPermille in 0..1000)
        require(counterintelligenceMoneyCost >= 0 && feignedCorpsMoneyCost >= 0)
    }
}

data class MisinformationResolution(val active: List<FalseSighting>, val detectedIds: List<String>)

object Misinformation {
    /** Scouting again replaces the victim's snapshot and clears false sightings of that commandery. */
    fun rescout(records: Collection<FalseSighting>, victimGeneralId: Int, commanderyId: String): List<FalseSighting> =
        records.filterNot { it.victimGeneralId == victimGeneralId && it.commanderyId == commanderyId }.sortedBy { it.id }

    /** Each false sighting has its own deterministic detection draw for each turn. */
    fun advance(hiddenSeed: String, worldId: WorldId, now: Phase, rules: MisinformationRules,
        records: Collection<FalseSighting>): MisinformationResolution {
        require(hiddenSeed.isNotBlank())
        require(records.map { it.id }.distinct().size == records.size) { "duplicate false sighting" }
        val active = mutableListOf<FalseSighting>()
        val detected = mutableListOf<String>()
        records.sortedBy { it.id }.forEach { record ->
            require(record.expiresAt == record.createdAt.plus(rules.durationTurns)) { "duration differs from confirmed rule" }
            if (now < record.createdAt || now >= record.expiresAt) return@forEach
            val seed = serializeSeed(hiddenSeed, "misinformationDetection", worldId.value,
                now.year, now.month, now.phase, record.id, record.casterGeneralId, record.victimGeneralId)
            if (RandUtil(LiteHashDrbg(seed)).nextRangeInt(0, 999) < rules.detectionPermille) detected += record.id
            else active += record
        }
        return MisinformationResolution(active, detected)
    }

    /** A victim sees ordinary scouting fields. Provenance, caster and detection state stay server-side. */
    fun victimReport(report: ScoutReport, victimGeneralId: Int, now: Phase,
        active: Collection<FalseSighting>): ScoutReport {
        val additions = active.filter { it.victimGeneralId == victimGeneralId && it.commanderyId == report.commanderyId &&
            it.createdAt <= now && now < it.expiresAt }.map { it.falseCorps }
        require(additions.map { it.corpsKey }.distinct().size == additions.size)
        require(additions.none { added -> report.corps.any { it.corpsKey == added.corpsKey } }) { "false sighting key collides with fact" }
        return report.copy(corps = (report.corps + additions).sortedBy { it.corpsKey })
    }

    /** Phantom corps enter only the view model. FOG sees nothing, and no deployment is created. */
    fun victimPhantoms(victimGeneralId: Int, view: VisionView, commanderyNoById: Map<String, Int>,
        provinceCommanderyNoById: Map<String, Int>, authoritativeCorpsKeys: Set<String>,
        active: Collection<FalseSighting>): List<CorpsSighting> = active.asSequence()
        .filter { it.victimGeneralId == victimGeneralId && it.createdAt <= view.now && view.now < it.expiresAt }
        .mapNotNull { record ->
            val no = commanderyNoById[record.commanderyId] ?: return@mapNotNull null
            val tier = view.tierOf(no)
            if (tier == VisionTier.FOG) return@mapNotNull null
            val seen = record.falseCorps
            require(provinceCommanderyNoById[seen.provinceId] == no) { "false sighting outside its commandery" }
            require(seen.corpsKey !in authoritativeCorpsKeys) { "false sighting key collides with real corps" }
            CorpsSighting(seen.corpsKey, null, seen.ownerGeneralId, seen.commanderGeneralId, seen.nationId,
                seen.provinceId, no, tier, false, null, seen.troopsBand,
                if (tier == VisionTier.INTEL) record.createdAt else null,
                if (tier == VisionTier.INTEL) opensamguk.logic.vision.Vision.ageTurns(record.createdAt, view.now) else null)
        }.sortedWith(compareBy({ it.commanderyNo }, { it.corpsKey })).toList()
}
