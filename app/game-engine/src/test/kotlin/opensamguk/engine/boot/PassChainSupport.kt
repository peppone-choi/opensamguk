package opensamguk.engine.boot

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import opensamguk.common.world.WorldId
import opensamguk.engine.hwiha.HwihaEncounterResolver
import opensamguk.engine.hwiha.HwihaMonthlyAssessment
import opensamguk.engine.hwiha.HwihaMonthlyCountyIncome
import opensamguk.engine.hwiha.HwihaMonthlySalary
import opensamguk.engine.hwiha.HwihaSiegeService
import opensamguk.engine.run.TurnRunService
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.infra.persistence.MetaJson
import opensamguk.infra.seed.HanWorldArtifactsResolver
import opensamguk.infra.seed.ScenarioImporter
import opensamguk.infra.seed.ScenarioJson
import opensamguk.logic.input.*
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate

/**
 * S3 pass chain shared by [PassChainInvarianceIT] and its red probe [HwihaS3PassChainProbeIT]: the 豫州 slice seeded by
 * the real importer, one human player who signs up and reserves 출사 through the normal reservation tables, and
 * nothing else. Every later link (발령 → 행군 → 조우 → 공성 → 점령 → 징세 → 월단평) must come from the loop itself.
 */
internal object PassChainSupport {
    const val HUMAN = 9001
    const val HUMAN_USER = 42
    const val PHASES = 48
    val START: Instant = Instant.parse("0190-01-01T00:00:00Z")

    fun repoRoot(): Path {
        var at: Path? = Path.of("").toAbsolutePath()
        while (at != null && !Files.isDirectory(at.resolve("data/map"))) at = at.parent
        return requireNotNull(at) { "data/map 을 가진 저장소 루트를 찾지 못했다" }
    }

    /** Seeds the scenario through the production importer, then the human's own signup and reservation. */
    fun seed(jdbc: JdbcTemplate, world: Int, withUnits: Boolean = true) {
        val root = repoRoot()
        val scenario = ScenarioJson.loadScenario(Files.readString(root.resolve("tools/e2e/fixtures/hwiha-yuzhou/scenario_990002.json")))
        val cities = ScenarioJson.loadMapCities(Files.readString(root.resolve("infra/src/main/resources/map/han-world-v3.json")))
        ScenarioImporter(scenario = scenario, cities = cities, scenarioCode = "scenario_990002",
            installTime = OffsetDateTime.ofInstant(START, ZoneOffset.UTC), artifactsRoot = root).importAll(jdbc, WorldId(world))
        // boundaryDate falls back to Instant.now() without these; pin the calendar like HwihaMonthBoundaryLoopIT.
        jdbc.update("""UPDATE world_state SET meta = meta || ?::jsonb WHERE id=?""",
            MetaJson.encode(mapOf("startYear" to 190, "startTime" to START.toString(), "lastTurnTime" to START.toString())), world)
        // The loader takes start_time as the clock authority. The importer writes it (and every turn_time) through
        // java.sql.Timestamp, whose Julian conversion moves year 190 by a day — the world then idled 24 phases before
        // anyone's turn came. Pin start_time exactly and shift the turn times back by the same drift (see end of seed).
        val imported = jdbc.queryForObject("SELECT start_time FROM world_state WHERE id=?", OffsetDateTime::class.java, world)!!
        val drift = java.time.Duration.between(START, imported.toInstant())
        jdbc.update("""UPDATE world_state SET start_time = ? WHERE id=?""", OffsetDateTime.ofInstant(START, ZoneOffset.UTC), world)
        if (!withUnits) jdbc.update("DELETE FROM general_bugok WHERE world_id=?", world)

        // The human player: a created character (npc_state 0) standing in the first lord's capital.
        val capital = scenario.nations.first().cities.first().toInt()
        val projection = HanWorldArtifactsResolver(root).resolve(cities.map { it.id }, emptyList()).projection
        val province = requireNotNull(projection.bindingsByCityId[capital]?.landProvinceId)
        val policy = HwihaPersonPolicyState(30, false, "synthetic-qa:yuzhou-player", "v1", 900).toMetaValue()
        jdbc.update("""INSERT INTO general(world_id,id,name,user_id,nation_id,city_id,npc_state,officer_level,gold,rice,crew,
            leadership,strength,intel,politics,charm,turn_time,last_turn,meta)
            VALUES (?,?,'플레이어',?,0,?,0,0,0,0,0,60,60,60,60,60,?,'{"command":"휴식"}'::jsonb,?::jsonb)""",
            world, HUMAN, HUMAN_USER.toString(), capital, java.sql.Timestamp.from(START.plusSeconds(30)),
            MetaJson.encode(mapOf(HwihaLordStatus.META_KEY to false, HwihaPersonPolicyState.META_KEY to policy)))
        // Every general carries its rank_data rows (the importer and the flush insert them with the general);
        // a hand-inserted player without them breaks the first rank write of a battle.
        jdbc.update("""INSERT INTO rank_data (world_id, nation_id, general_id, type, value)
            SELECT ?, 0, ?, type, 0 FROM rank_data WHERE world_id=?
              AND general_id=(SELECT min(general_id) FROM rank_data WHERE world_id=?)""", world, HUMAN, world, world)
        jdbc.update("""INSERT INTO general_spatial_position(world_id,general_id,topology_revision,topology_hash,node_kind,node_id,revision)
            VALUES (?,?,?,?,'LAND_PROVINCE',?,1)""", world, HUMAN, projection.topology.topologyRevision,
            projection.topology.contentHash, province)
        // 출사 — reserved exactly as the reservation API writes it (inbox row + ring slot 0).
        val requestId = "s3-enlist-$world"
        val envelope = opensamguk.common.wire.TurnDaemonCommandEnvelope(requestId, START.toString(),
            opensamguk.common.wire.TurnDaemonCommand.Run(opensamguk.common.wire.RunReason.POKE))
        val named = NamedParameterJdbcTemplate(jdbc)
        opensamguk.infra.persistence.CommandInboxRepository(named).insertAccepted(
            opensamguk.infra.persistence.CommandInboxRepository.AcceptedCommand(WorldId(world), requestId,
                commandKind = opensamguk.infra.persistence.CommandInboxRepository.CommandKind.RESERVED_TURN,
                intentFingerprint = "b".repeat(64), generalId = HUMAN, turnIdx = 0, actionCode = "action.enlist",
                payloadJson = opensamguk.common.wire.encodeCommandPayload(envelope), ownerUserId = HUMAN_USER))
        // Same Julian drift on every turn_time (the importer's and the player's above) — undo it once for all generals.
        jdbc.update("UPDATE general SET turn_time = turn_time - make_interval(secs => ?) WHERE world_id=?",
            drift.seconds.toDouble(), world)
        opensamguk.infra.persistence.ReservedTurnRepository(named).reserve(WorldId(world), HUMAN, 0, "action.enlist",
            HwihaEnlistmentInput.canonicalJson(EnlistmentRequest(HUMAN, EnlistmentMode.NATION, 1)), requestId = requestId)
    }

    /** One tick per phase, so each personal turn and each world boundary runs exactly once. */
    fun run(service: TurnRunService, phases: Int = PHASES, measuredWorld: InMemoryTurnWorld? = null) {
        val first = linkedMapOf<String, Int>()
        for (k in 1..phases) {
            service.runTick(START.plusSeconds(3600L * k))
            val world = measuredWorld ?: continue
            val human = world.getGeneralById(HUMAN)
            val sieges = world.listHwihaSieges()
            val meta = world.getState().meta
            val links = linkedMapOf(
                "enlist" to ((human?.nationId ?: 0) > 0),
                "dispatch" to (human?.meta?.containsKey(HwihaCountyAssignment.META_KEY) == true),
                "march" to (world.listGenerals().any { HwihaCorpsMarchState.META_KEY in it.meta || HwihaEncounterResolver.BATTLE_RECORD_KEY in it.meta } || sieges.isNotEmpty()),
                "encounter" to world.listGenerals().any { HwihaEncounterResolver.BATTLE_RECORD_KEY in it.meta },
                "siege" to sieges.isNotEmpty(),
                "capture" to sieges.any { it.status == HwihaSiegeService.FALLEN },
                "income" to (meta[HwihaMonthlyCountyIncome.STAMP_KEY] != null),
                "salary" to (meta[HwihaMonthlySalary.STAMP_KEY] != null),
                "assessment" to (meta[HwihaMonthlyAssessment.STAMP_KEY] != null),
                "ranking" to ((meta[HwihaMonthlyAssessment.RANKING_KEY] as? List<*>).orEmpty().isNotEmpty()),
            )
            links.forEach { (name, met) -> if (met && name !in first) first[name] = k }
        }
        if (measuredWorld != null) println("s3-first-phase " + first.entries.joinToString(" ") { "${it.key}=${it.value}" })
    }

    /** The S3 gate. Each assertion names its link so a red run says which link broke. */
    fun assertChain(world: InMemoryTurnWorld, jdbc: JdbcTemplate, id: Int) {
        val human = assertNotNull(world.getGeneralById(HUMAN))
        // 출사
        assertTrue(human.nationId > 0, "출사: the player joined a lord's nation")
        assertTrue(world.listRetainers().any { it.generalId == HUMAN }, "출사: the player is a lord's person card")
        // 발령 (the NPC lord issues it; an unanswered dispatch is accepted at its deadline)
        assertTrue(HwihaCountyAssignment.META_KEY in human.meta, "발령: the player holds an accepted county assignment")
        // 행군
        assertTrue(world.listGenerals().any { HwihaCorpsMarchState.META_KEY in it.meta || HwihaEncounterResolver.BATTLE_RECORD_KEY in it.meta } ||
            world.listHwihaSieges().isNotEmpty(), "행군: an NPC corps marched")
        // 조우
        assertTrue(world.listGenerals().any { HwihaEncounterResolver.BATTLE_RECORD_KEY in it.meta }, "조우: a sealed encounter was resolved")
        // 공성 · 점령 (persisted)
        assertTrue(jdbc.queryForObject("SELECT count(*) FROM hwiha_siege WHERE world_id=?", Int::class.java, id)!! > 0, "공성: a siege row was flushed")
        val fallen = jdbc.queryForList("SELECT county_id, besieger_nation_id FROM hwiha_siege WHERE world_id=? AND status='FALLEN'", id)
        assertTrue(fallen.isNotEmpty(), "점령: a county fell")
        // A captured county can change hands again later — retaken, or neutralized by the monthly isolation decay
        // (UpdateCitySupply). So each fallen county's owner in the database must equal the live world's (the capture
        // and every later transfer were flushed), and at least one is still held by its captor (the capture itself).
        var held = 0
        for (row in fallen) {
            val county = (row["county_id"] as Number).toInt()
            val stored = jdbc.queryForObject("SELECT nation_id FROM city WHERE world_id=? AND id=?", Int::class.java, id, county)
            assertEquals(world.getCityById(county)?.nationId, stored, "점령: county $county owner is flushed as the live world holds it")
            if (stored == (row["besieger_nation_id"] as Number).toInt()) held++
        }
        assertTrue(held > 0, "점령: a fallen county is held by its captor in the database")
        // 징세 · 녹봉 · 월단평
        val meta = world.getState().meta
        assertNotNull(meta[HwihaMonthlyCountyIncome.STAMP_KEY], "징세: the loop credited county warehouses")
        assertNotNull(meta[HwihaMonthlySalary.STAMP_KEY], "녹봉: the loop paid salaries")
        assertNotNull(meta[HwihaMonthlyAssessment.STAMP_KEY], "월단평: the loop assessed renown")
        assertTrue((meta[HwihaMonthlyAssessment.RANKING_KEY] as? List<*>).orEmpty().isNotEmpty(), "월단평: a ranking was published")
    }

    /** Cold reload: the flushed siege rows come back as they are in memory (V61 round trip). */
    fun assertSiegesReload(world: InMemoryTurnWorld, loader: WorldSnapshotLoader) {
        val cold = loader.buildSnapshot().hwihaSieges.associateBy { it.countyId }
        val live = world.listHwihaSieges().associateBy { it.countyId }
        assertEquals(live.keys, cold.keys)
        for ((county, siege) in live) {
            val stored = cold.getValue(county)
            assertEquals(listOf(siege.status, siege.besiegerGeneralId, siege.turns, siege.morale, siege.garrison, siege.endReason),
                listOf(stored.status, stored.besiegerGeneralId, stored.turns, stored.morale, stored.garrison, stored.endReason))
            assertEquals(siege.timeline.size, stored.timeline.size)
        }
        assertTrue(live.values.none { it.status == HwihaSiegeService.ACTIVE && it.besiegerGeneralId !in world.listGenerals().map { g -> g.id } })
    }
}
