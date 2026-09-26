package opensamguk.engine.invariance

import java.security.MessageDigest
import kotlin.test.assertEquals
import opensamguk.engine.turn.InMemoryTurnWorld

/** Test-only, versioned projection of gameplay state. See the task report for intentional omissions. */
internal object WorldStateBaseline {
    private const val EXPECTED_RESOURCE = "/invariance/world-state-sha256.txt"

    fun assertMatches(case: String, world: InMemoryTurnWorld) {
        val lines = requireNotNull(javaClass.getResourceAsStream(EXPECTED_RESOURCE))
            .bufferedReader(Charsets.UTF_8).use { it.readLines() }
        val expected = lines.filter { it.isNotBlank() && !it.startsWith("#") }
            .associate { line -> line.substringBefore(' ') to line.substringAfter(' ').trim() }
        val actual = sha256(world)
        println("behavior-baseline $case $actual")
        assertEquals(expected.getValue(case), actual, "$case final normalized world SHA-256")
    }

    fun sha256(world: InMemoryTurnWorld): String {
        val state = world.getState()
        val rows = buildList {
            add(listOf("calendar", state.currentYear, state.currentMonth, state.currentPhase, state.status,
                values(state.meta)))
            world.listGenerals().sortedBy { it.id }.forEach { g ->
                add(listOf("general", g.id, g.nationId, g.cityId, g.troopId, g.stats.leadership,
                    g.stats.strength, g.stats.intelligence, g.stats.politics, g.stats.charm,
                    g.experience, g.dedication, g.officerLevel, g.injury, g.gold, g.rice,
                    g.crew, g.crewTypeId, g.train, g.atmos, g.age, g.npcState, values(g.meta)))
            }
            world.listCities().sortedBy { it.id }.forEach { c ->
                add(listOf("city", c.id, c.nationId, c.level, c.state, c.population, c.populationMax,
                    c.dead, c.agriculture, c.agricultureMax, c.commerce, c.commerceMax,
                    c.security, c.securityMax, c.supplyState, c.frontState, c.defence,
                    c.defenceMax, c.wall, c.wallMax, c.trade, c.region, c.term, c.officerSet,
                    c.conflict, values(c.meta)))
            }
            world.listNations().sortedBy { it.id }.forEach { n ->
                add(listOf("nation", n.id, n.capitalCityId, n.chiefGeneralId, n.gold, n.rice,
                    n.power, n.tech, n.level, n.typeCode, values(n.meta)))
            }
            world.listTroops().sortedBy { it.id }.forEach { t -> add(listOf("troop", t.id, t.nationId)) }
            world.listDiplomacy().sortedWith(compareBy({ it.fromNationId }, { it.toNationId })).forEach { d ->
                add(listOf("diplomacy", d.fromNationId, d.toNationId, d.state, d.term, d.dead, values(d.meta)))
            }
            world.listRetainers().sortedBy { it.id }.forEach { r ->
                add(listOf("retainer", r.masterGeneralId, r.generalId, r.relation, r.role,
                    r.hasOwnBugok, r.releasePolicy, r.loyalty, r.task))
            }
            world.listBugoks().sortedBy { it.id }.forEach { b ->
                add(listOf("bugok", b.masterGeneralId, b.troops, b.crewTypeId, b.training,
                    b.morale, b.fatigue, b.provisions, b.commanderBonusApplied))
            }
            world.listOperations().sortedBy { it.id }.forEach { o ->
                add(listOf("operation", o.nationId, o.kind, o.targetCityId, o.declaredByGeneralId,
                    o.declaredYear, o.declaredMonth, o.declaredPhase, o.deadlineYear,
                    o.deadlineMonth, o.deadlinePhase, o.status, o.milestones.departed,
                    o.milestones.arrived, o.milestones.supplied, o.milestones.objective, o.closedReason))
            }
            world.listOperationUnits().sortedBy { it.id }.forEach { u ->
                add(listOf("operationUnit", u.generalId, u.role, u.joinedCityId,
                    u.joinedYear, u.joinedMonth, u.joinedPhase))
            }
            world.listBattlePlans().sortedBy { it.id }.forEach { p ->
                add(listOf("battlePlan", p.generalId, p.targetCityId, p.stance, p.retreatLossPct,
                    p.retreatMoraleBelow, p.sealedYear, p.sealedMonth, p.sealedPhase,
                    p.resolvedYear, p.resolvedMonth, p.resolvedPhase))
            }
            world.listSieges().sortedBy { it.countyId }.forEach { s ->
                add(listOf("siege", s.countyId, s.status, s.besiegerGeneralId,
                    s.besiegerOwnerGeneralId, s.besiegerNationId, s.defenderNationId,
                    s.startedYear, s.startedMonth, s.startedPhase, s.settledYear,
                    s.settledMonth, s.settledPhase, s.turns, s.morale, s.garrison,
                    s.endReason, s.timeline.map(::values)))
            }
            world.generalPositionSnapshot()?.statesByGeneralId?.toSortedMap()?.forEach { (id, p) ->
                add(listOf("position", id, p.node.canonicalKey, p.revision))
            }
        }
        val bytes = rows.map(::canonical).sorted().joinToString("\n", postfix = "\n").toByteArray(Charsets.UTF_8)
        return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }

    // Metadata keys are storage names scheduled for renaming. Values are sorted so map insertion order is irrelevant.
    private fun values(map: Map<*, *>): List<String> = map.entries.asSequence()
        .filterNot { (key, _) -> omittedMetadataKey(key.toString()) }
        .mapNotNull { metadata(it.value) }.sorted().toList()

    private fun omittedMetadataKey(key: String): Boolean {
        val snake = key.lowercase()
        if (key == "id" || key.endsWith("Id") || key.endsWith("Ids") ||
            snake.endsWith("_id") || snake.endsWith("_ids")) return true
        return listOf("Time", "Path", "Hash", "Revision", "Version").any(key::endsWith) ||
            listOf("_time", "_path", "_hash", "_revision", "_version").any(snake::endsWith) ||
            snake == "starttime" || snake == "lastturntime"
    }

    private fun metadata(value: Any?): String? = when (value) {
        null -> "null"
        is Map<*, *> -> canonical(values(value))
        is Iterable<*> -> canonical(value.mapNotNull(::metadata))
        is Array<*> -> canonical(value.mapNotNull(::metadata))
        // The format label is a storage contract, not gameplay state. The old profile label
        // was already excluded; exclude its replacement so this hash still compares behavior.
        is String -> if (value == opensamguk.logic.world.WorldFormat.GENERAL_RETAINER_CAMPAIGN.name ||
            value.contains("HWIHA", true) || value.contains("hwiha", true) ||
            value.startsWith('/') || Regex("\\d{4}-\\d{2}-\\d{2}T").containsMatchIn(value)) null else canonical(value)
        is Number, is Boolean -> canonical(value)
        else -> null
    }

    private fun canonical(value: Any?): String = when (value) {
        null -> "N"
        is String -> "S${value.length}:$value"
        is Boolean -> if (value) "T" else "F"
        is Number -> "D$value"
        is Iterable<*> -> value.joinToString(prefix = "[", postfix = "]", separator = ",") { canonical(it) }
        else -> error("Unsupported baseline value: ${value.javaClass.name}")
    }
}
