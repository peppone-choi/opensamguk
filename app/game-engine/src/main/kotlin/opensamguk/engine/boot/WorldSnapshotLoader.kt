package opensamguk.engine.boot

import opensamguk.common.constants.GameUnitConst
import opensamguk.common.constants.ScenarioLifecycleMeta
import opensamguk.common.world.WorldId
import opensamguk.engine.turn.City
import opensamguk.engine.turn.GeneralAccessLog
import opensamguk.engine.turn.GeneralItems
import opensamguk.engine.turn.GeneralRole
import opensamguk.engine.turn.GeneralStats
import opensamguk.engine.turn.GeneralTurnSeed
import opensamguk.engine.turn.Nation
import opensamguk.engine.turn.Troop
import opensamguk.engine.turn.TurnDiplomacy
import opensamguk.engine.turn.TurnGeneral
import opensamguk.engine.turn.TurnWorldState
import opensamguk.engine.turn.Bugok
import opensamguk.engine.turn.Operation
import opensamguk.engine.turn.OperationMilestones
import opensamguk.engine.turn.OperationUnit
import opensamguk.engine.turn.Retainer
import opensamguk.engine.turn.WorldSnapshot
import opensamguk.infra.persistence.MetaJson
import opensamguk.infra.persistence.WaterControlRowCodec
import opensamguk.infra.persistence.ProvinceControlRowCodec
import opensamguk.infra.persistence.GeneralPositionRowCodec
import opensamguk.infra.seed.HanStrategicTopologyJson
import opensamguk.logic.world.ActiveWorldMap
import opensamguk.logic.world.StrategicTopologySnapshot
import opensamguk.logic.world.WaterControlSnapshot
import opensamguk.logic.world.ProvinceControlSnapshot
import opensamguk.logic.world.GeneralPositionSnapshot
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.ResultSet
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * F1b — DB→[WorldSnapshot] loader. Reads the seeded relational rows from PostgreSQL via
 * [JdbcTemplate] and materializes the engine-domain in-memory snapshot the daemon's
 * [opensamguk.engine.turn.InMemoryTurnWorld] is constructed from.
 *
 * Maps:
 *  - `world_state` (singleton) → [TurnWorldState] (THROWS if the row is missing).
 *  - `general`    → [TurnGeneral]
 *  - `city`       → [City]
 *  - `nation`     → [Nation]
 *  - `diplomacy`  → [TurnDiplomacy]
 *  - `troop`      → `emptyList()` (no troops at scenario start; there is no engine-domain troop mapper
 *    and the seed inserts zero `troop` rows — [WorldSnapshot.troops] defaults to an empty list).
 *
 * **Seed→load ordering.** [buildSnapshot] calls [SeedBootstrap.ensureSeeded] FIRST (idempotent — a
 * no-op if the world already exists), so the world is guaranteed to be seeded before it is read,
 * regardless of when this loader / the `@Bean InMemoryTurnWorld` is constructed.
 *
 * **JDBC-only.** Pure read via [JdbcTemplate]; no `EntityManager`, no Spring-Data repository, no
 * `ChangeRecorder`. Lives outside the architecture-test write-path packages.
 */
class WorldSnapshotLoader(
    private val jdbc: JdbcTemplate,
    private val seedBootstrap: SeedBootstrap,
    private val worldId: WorldId,
    private val snapshotValidator: (WorldSnapshot) -> Unit = ActiveWorldMapValidator::validate,
    private val waterTopologyLoader: () -> StrategicTopologySnapshot = { HanStrategicTopologyJson.loadDefault().topology },
) {
    private val log = LoggerFactory.getLogger(WorldSnapshotLoader::class.java)

    fun buildSnapshot(): WorldSnapshot {
        // Guarantee the seed has run (idempotent) before reading.
        seedBootstrap.ensureSeeded(jdbc)

        val loadedState = loadWorldState().let { loaded ->
            val merged = LinkedHashMap(loaded.meta)
            merged.putAll(loadGameEnv())
            val snapshotKeys = listOf(
                "isunited",
                "lastTurnTime",
                "hiddenSeed",
                "startYear",
                "startTime",
                "currentYear",
                "currentMonth",
                "currentPhase",
                "scenario",
                "map",
                "maxNationId",
                "maxGeneralId",
                "maxRetainerId",
                "maxBugokId",
                "maxOperationId",
                "maxOperationUnitId",
            )
            for (key in snapshotKeys) {
                if (loaded.meta.containsKey(key)) merged[key] = loaded.meta[key]
            }
            for (key in coldBootMetaKeys) {
                merged.remove(key)
            }
            loaded.copy(meta = merged)
        }
        val activeGame = resolveActiveGame(loadedState.meta)
        val activeServerId = activeGame?.serverId
        val serverCount = loadServerCount()
        val activeUniqueAuctionsById = loadActiveUniqueAuctionItems()
        val storedUniqueItemCounts = loadStoredUniqueItemCounts()
        val inheritancePoints = loadInheritancePoints()
        val inheritancePrevious = inheritancePoints.mapValues { (_, values) ->
            (values["previous"]?.getOrNull(0) as? Number)?.toDouble() ?: 0.0
        }.filterValues { it != 0.0 }
        val state = loadedState.copy(
            serverId = activeServerId,
            meta = LinkedHashMap(loadedState.meta).apply {
                if (activeGame != null) {
                    for ((key, value) in activeGame.env) putIfAbsent(key, value)
                    this["serverId"] = activeGame.serverId
                    this["server_id"] = activeGame.serverId
                    this["ngGameId"] = activeGame.id
                    this["season"] = activeGame.season
                    this["scenario"] = activeGame.scenario
                    this["scenario_text"] = activeGame.scenarioName
                    this["scenarioName"] = activeGame.scenarioName
                    activeGame.map?.let { this["map_theme"] = it }
                }
                this["serverCount"] = serverCount
                this["activeUniqueAuctionItems"] = activeUniqueAuctionsById.values.toList()
                this["activeUniqueAuctionItemsById"] = LinkedHashMap(activeUniqueAuctionsById)
                this["storedUniqueItemCounts"] = storedUniqueItemCounts
                this["inheritancePoints"] = inheritancePoints
                this["inheritancePrevious"] = inheritancePrevious
            },
        )
        val nationEnv = loadNationEnv()
        val nations = loadNations().map { nation ->
            val env = nationEnv[nation.id] ?: return@map nation
            val meta = LinkedHashMap(nation.meta)
            val mergedEnv = LinkedHashMap<String, Any?>()
            (meta["nation_env"] as? Map<*, *>)?.forEach { (key, value) -> mergedEnv[key.toString()] = value }
            mergedEnv.putAll(env)
            meta["nation_env"] = mergedEnv
            nation.copy(meta = meta)
        }
        val cities = loadCities()
        val generals = loadGenerals(state)
        val diplomacy = loadDiplomacy()
        val accessLogs = loadAccessLogs()
        val archivedNationIds = loadArchivedNationIds(activeServerId)
        val troops = loadTroops()
        val retainers = loadRetainers()
        val bugoks = loadBugoks()
        val operations = loadOperations()
        val operationUnits = loadOperationUnits()
        log.info(
            "WorldSnapshot loaded — generals={} cities={} nations={} archivedNations={} diplomacy={} accessLogs={} troops={}",
            generals.size,
            cities.size,
            nations.size,
            archivedNationIds.size,
            diplomacy.size,
            accessLogs.size,
            troops.size,
        )
        val topology = spatialTopologyFor(state)
        val snapshot = WorldSnapshot(
            state = state,
            worldId = worldId,
            serverId = activeServerId,
            generals = generals,
            cities = cities,
            nations = nations,
            troops = troops,
            diplomacy = diplomacy,
            accessLogs = accessLogs,
            retainers = retainers,
            bugoks = bugoks,
            operations = operations,
            operationUnits = operationUnits,
            archivedNationIds = archivedNationIds,
            waterControlSnapshot = topology?.let(::loadWaterControlSnapshot),
            provinceControlSnapshot = topology?.let(::loadProvinceControlSnapshot),
            generalPositionSnapshot = topology?.let(::loadGeneralPositionSnapshot),
        )
        snapshotValidator(snapshot)
        return snapshot
    }

    private fun spatialTopologyFor(state: TurnWorldState): StrategicTopologySnapshot? {
        // Small historical test snapshots may omit map identity; the production map validator still rejects them.
        val hasMap = listOf(state.config, state.meta).any { it.containsKey("mapName") || it.containsKey("map") }
        if (!hasMap || ActiveWorldMap.requireName(state.config, state.meta) != "han-world-v3") return null
        return waterTopologyLoader()
    }

    private fun loadWaterControlSnapshot(topology: StrategicTopologySnapshot): WaterControlSnapshot {
        val rows = jdbc.query(
            "SELECT water_zone_id, topology_revision, topology_hash, controlling_nation_id, " +
                "contesting_nation_ids, blockade_state, revision FROM water_zone_control WHERE world_id = ? ORDER BY water_zone_id",
            { rs, _ -> WaterControlRowCodec.decode(rs) }, worldId.value,
        )
        // Always validate, even when an injected map validator permits a reduced test fixture.
        return WaterControlSnapshot.fromTopology(topology, rows)
    }

    private fun loadProvinceControlSnapshot(topology: StrategicTopologySnapshot): ProvinceControlSnapshot {
        val rows = jdbc.query(
            "SELECT province_id, topology_revision, topology_hash, nation_id, revision " +
                "FROM province_control WHERE world_id = ? ORDER BY province_id",
            { rs, _ -> ProvinceControlRowCodec.decode(rs) }, worldId.value,
        )
        return ProvinceControlSnapshot.fromTopology(topology, rows)
    }

    private fun loadGeneralPositionSnapshot(topology: StrategicTopologySnapshot): GeneralPositionSnapshot {
        val rows = jdbc.query(
            "SELECT general_id, topology_revision, topology_hash, node_kind, node_id, revision " +
                "FROM general_spatial_position WHERE world_id = ? ORDER BY general_id",
            { rs, _ -> GeneralPositionRowCodec.decode(rs) }, worldId.value,
        )
        // WorldSnapshot checks these IDs against the same world's loaded core generals.
        return GeneralPositionSnapshot.fromTopology(topology, rows)
    }

    /** Phase 4X-A 가신 적재(부팅·rehydrate 동일 경로). 행 0 이면 빈 목록. */
    private fun loadRetainers(): List<Retainer> = jdbc.query(
        "SELECT id, master_general_id, origin, general_id, name, relation, role, has_own_bugok, release_policy, loyalty, task " +
            "FROM general_retainers WHERE world_id = ? ORDER BY id",
        { rs, _ ->
            Retainer(
                id = rs.getInt("id"), masterGeneralId = rs.getInt("master_general_id"), origin = rs.getString("origin"),
                generalId = rs.getObject("general_id")?.let { (it as Number).toInt() }, name = rs.getString("name"),
                relation = rs.getString("relation"), role = rs.getString("role"), hasOwnBugok = rs.getBoolean("has_own_bugok"),
                releasePolicy = rs.getString("release_policy"), loyalty = rs.getInt("loyalty"), task = rs.getString("task"),
            )
        },
        worldId.value,
    )

    private fun loadBugoks(): List<Bugok> = jdbc.query(
        "SELECT id, master_general_id, name, troops, crew_type_id, training, morale, fatigue, provisions, commander_retainer_id, commander_bonus_applied " +
            "FROM general_bugok WHERE world_id = ? ORDER BY id",
        { rs, _ ->
            Bugok(
                id = rs.getInt("id"), masterGeneralId = rs.getInt("master_general_id"), name = rs.getString("name"),
                troops = rs.getInt("troops"), crewTypeId = rs.getInt("crew_type_id"), training = rs.getInt("training"),
                morale = rs.getInt("morale"), fatigue = rs.getInt("fatigue"), provisions = rs.getInt("provisions"),
                commanderRetainerId = rs.getObject("commander_retainer_id")?.let { (it as Number).toInt() },
                commanderBonusApplied = rs.getBoolean("commander_bonus_applied"),
            )
        },
        worldId.value,
    )

    /** Phase 4X-B 작전 적재(부팅·rehydrate 동일 경로). 행 0 이면 빈 목록. */
    private fun loadOperations(): List<Operation> = jdbc.query(
        "SELECT id, nation_id, kind, target_city_id, title, fallback_text, declared_by_general_id, declared_year, declared_month, declared_phase, " +
            "deadline_year, deadline_month, deadline_phase, status, m_departed, m_arrived, m_supplied, m_objective, closed_reason " +
            "FROM operation WHERE world_id = ? ORDER BY id",
        { rs, _ ->
            Operation(
                id = rs.getInt("id"), nationId = rs.getInt("nation_id"), kind = rs.getString("kind"), targetCityId = rs.getInt("target_city_id"),
                title = rs.getString("title"), fallbackText = rs.getString("fallback_text"),
                declaredByGeneralId = rs.getObject("declared_by_general_id")?.let { (it as Number).toInt() },
                declaredYear = rs.getInt("declared_year"), declaredMonth = rs.getInt("declared_month"), declaredPhase = rs.getInt("declared_phase"),
                deadlineYear = rs.getInt("deadline_year"), deadlineMonth = rs.getInt("deadline_month"), deadlinePhase = rs.getInt("deadline_phase"),
                status = rs.getString("status"),
                milestones = OperationMilestones(rs.getBoolean("m_departed"), rs.getBoolean("m_arrived"), rs.getBoolean("m_supplied"), rs.getBoolean("m_objective")),
                closedReason = rs.getString("closed_reason"),
            )
        },
        worldId.value,
    )

    private fun loadOperationUnits(): List<OperationUnit> = jdbc.query(
        "SELECT id, operation_id, general_id, bugok_id, role, joined_city_id, joined_year, joined_month, joined_phase " +
            "FROM operation_unit WHERE world_id = ? ORDER BY id",
        { rs, _ ->
            OperationUnit(
                id = rs.getInt("id"), operationId = rs.getInt("operation_id"), generalId = rs.getInt("general_id"),
                bugokId = rs.getObject("bugok_id")?.let { (it as Number).toInt() }, role = rs.getString("role"),
                joinedCityId = rs.getInt("joined_city_id"), joinedYear = rs.getInt("joined_year"), joinedMonth = rs.getInt("joined_month"), joinedPhase = rs.getInt("joined_phase"),
            )
        },
        worldId.value,
    )

    private fun loadWorldState(): TurnWorldState {
        val rows = jdbc.query(
            "SELECT id, current_year, current_month, current_phase, tick_seconds, isunited, status, meta, config, start_time, world_version, writer_epoch FROM world_state WHERE id = ?",
            { rs, _ ->
                val meta = LinkedHashMap(MetaJson.decode(rs.getString("meta")))
                val config = LinkedHashMap(MetaJson.decode(rs.getString("config")))
                val persistedStartTime = rs.getObject("start_time", OffsetDateTime::class.java)?.toInstant()
                    ?: parseStartTime(config["startTime"] ?: meta["startTime"])
                persistedStartTime?.toString()?.let { startTime ->
                    config["startTime"] = startTime
                    meta["startTime"] = startTime
                }
                for ((key, value) in config) {
                    if (!meta.containsKey(key)) meta[key] = value
                }
                val currentYear = rs.getInt("current_year")
                val currentMonth = rs.getInt("current_month")
                val currentPhase = rs.getInt("current_phase").takeIf { it in 1..3 } ?: 1
                meta["currentYear"] = currentYear
                meta["currentMonth"] = currentMonth
                meta["currentPhase"] = currentPhase
                // isunited: dedicated column is source of truth (flush writes it; meta-only fallback for legacy rows).
                meta["isunited"] = rs.getInt("isunited")
                // lastTurnTime: prefer the persisted clock; fall back to start_time, then now.
                val lastTurn = (meta["lastTurnTime"] as? String)?.let { Instant.parse(it) }
                    ?: persistedStartTime
                    ?: Instant.now()
                meta["lastTurnTime"] = lastTurn.toString()
                TurnWorldState(
                    id = rs.getInt("id"),
                    currentYear = currentYear,
                    currentMonth = currentMonth,
                    currentPhase = currentPhase,
                    tickSeconds = rs.getInt("tick_seconds"),
                    lastTurnTime = lastTurn,
                    meta = meta,
                    status = rs.getString("status"),
                    config = config,
                    worldVersion = rs.getLong("world_version"),
                    writerEpoch = rs.getLong("writer_epoch"),
                )
            },
            worldId.value,
        )
        return rows.firstOrNull()
            ?: error("configured world_state.id=${worldId.value} is missing — scenario seed did not run (cannot build WorldSnapshot)")
    }

    private fun parseStartTime(value: Any?): Instant? {
        val raw = value?.toString()?.takeIf { it.isNotBlank() } ?: return null
        return runCatching { Instant.parse(raw) }.getOrNull()
            ?: runCatching {
                LocalDateTime.parse(raw, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                    .atZone(ZoneId.of("Asia/Seoul"))
                    .toInstant()
            }.getOrNull()
    }

    private fun loadNations(): List<Nation> = jdbc.query(
        // tech를 SELECT에 포함해야 한다. 누락 시 in-memory Nation.tech가 기본 0.0으로 떨어지고,
        // 다음 월틱 flush가 UPDATE nation SET tech=0 으로 시드값(예: 후한 1500)을 영구히 덮어쓴다.
        "SELECT id, name, color, capital_city_id, gold, rice, tech, power, level, type_code, meta " +
            "FROM nation WHERE world_id = ? ORDER BY id ASC",
        { rs, _ ->
        Nation(
            id = rs.getInt("id"),
            name = rs.getString("name"),
            color = rs.getString("color"),
            capitalCityId = rs.getObject("capital_city_id") as? Int,
            gold = rs.getInt("gold"),
            rice = rs.getInt("rice"),
            tech = rs.getDouble("tech"),
            power = rs.getInt("power"),
            level = rs.getInt("level"),
            typeCode = rs.getString("type_code"),
            meta = MetaJson.decode(rs.getString("meta")),
        )
        }, worldId.value)

    private fun resolveActiveGame(meta: Map<String, Any?>): ActiveGame? {
        val configured = listOf(meta["serverId"], meta["server_id"])
            .mapNotNull { it?.toString()?.takeIf(String::isNotBlank) }
            .firstOrNull()
        if (configured != null) {
            return jdbc.query(
                """
                SELECT id, server_id, season, scenario, scenario_name, map, CAST(env AS VARCHAR) AS env
                  FROM ng_games
                 WHERE world_id = ? AND server_id = ?
                """.trimIndent(),
                { rs, _ -> activeGame(rs) },
                worldId.value,
                configured,
            ).singleOrNull()
                ?: error("world_state.meta serverId '$configured' does not exist in ng_games")
        }

        val rows = jdbc.query(
            """
            SELECT id, server_id, season, scenario, scenario_name, map, CAST(env AS VARCHAR) AS env
              FROM ng_games
             WHERE world_id = ?
             ORDER BY id ASC
            """.trimIndent(),
            { rs, _ -> activeGame(rs) },
            worldId.value,
        )
        return when (rows.size) {
            0 -> null
            1 -> rows.single()
            else -> error("active serverId is missing from world_state.meta; refusing to infer it from newest ng_games row")
        }
    }

    private fun loadArchivedNationIds(serverId: String?): List<Int> {
        if (serverId == null) return emptyList()
        return jdbc.query(
            "SELECT nation FROM ng_old_nations WHERE world_id = ? AND server_id = ? ORDER BY nation ASC",
            { rs, _ -> rs.getInt("nation") },
            worldId.value,
            serverId,
        )
    }

    private fun loadServerCount(): Int = jdbc.queryForObject(
        "SELECT count(*) FROM ng_games WHERE world_id = ?",
        Int::class.java,
        worldId.value,
    ) ?: 0

    private fun loadActiveUniqueAuctionItems(): Map<Int, String?> {
        val auctions = LinkedHashMap<Int, String?>()
        jdbc.query(
            """
            SELECT id, target
              FROM ng_auction
             WHERE world_id = ?
               AND type = 'uniqueItem'
               AND finished = false
             ORDER BY id ASC
            """.trimIndent(),
            { rs -> auctions[rs.getInt("id")] = rs.getString("target") },
            worldId.value,
        )
        return auctions
    }

    private fun loadStoredUniqueItemCounts(): Map<String, Int> {
        val counts = LinkedHashMap<String, Int>()
        jdbc.query(
            """
            SELECT namespace, count(*) AS cnt
              FROM game_kv
             WHERE world_id = ?
               AND "table" <> 'inheritance'
               AND left(namespace, 3) = 'ut_'
             GROUP BY namespace
             ORDER BY namespace ASC
            """.trimIndent(),
            { rs ->
                counts[rs.getString("namespace").removePrefix("ut_")] = rs.getInt("cnt")
            },
            worldId.value,
        )
        return counts
    }

    private fun loadGameEnv(): Map<String, Any?> = linkedMapOf<String, Any?>().apply {
        jdbc.query(
            """
            SELECT key, CAST(value AS VARCHAR) AS value_json
              FROM game_kv
             WHERE world_id = ?
               AND "table" = 'game_env'
               AND namespace IN ('', 'game_env')
             ORDER BY id ASC
            """.trimIndent(),
            { rs ->
                this[rs.getString("key")] = decodeKvValue(rs.getString("value_json"))
            },
            worldId.value,
        )
    }

    private fun loadNationEnv(): Map<Int, Map<String, Any?>> {
        val result = LinkedHashMap<Int, LinkedHashMap<String, Any?>>()
        jdbc.query(
            """
            SELECT namespace, key, CAST(value AS VARCHAR) AS value_json
              FROM nation_env
             WHERE world_id = ?
             ORDER BY id ASC
            """.trimIndent(),
            { rs ->
                result.getOrPut(rs.getInt("namespace")) { LinkedHashMap() }[rs.getString("key")] =
                    decodeKvValue(rs.getString("value_json"))
            },
            worldId.value,
        )
        return result
    }

    private fun loadInheritancePoints(): Map<Int, Map<String, List<Any?>>> {
        val result = LinkedHashMap<Int, LinkedHashMap<String, List<Any?>>>()
        jdbc.query(
            """
            WITH active_owner AS (
                SELECT DISTINCT user_id::integer AS owner_id,
                       'inheritance_' || user_id AS namespace
                  FROM general
                 WHERE world_id = ?
                   AND user_id IS NOT NULL
                   AND user_id ~ '^[0-9]+$'
            )
            SELECT kv.namespace, kv.key, CAST(kv.value AS VARCHAR) AS value_json
             FROM game_kv kv
              JOIN active_owner owner ON owner.namespace = kv.namespace
             WHERE kv."table" = 'inheritance'
               AND kv.world_id IS NULL
             ORDER BY owner.owner_id ASC, kv.id ASC
            """.trimIndent(),
            { rs ->
            val ownerId = rs.getString("namespace").removePrefix("inheritance_").toIntOrNull()
                ?: return@query
            val decoded = decodeKvValue(rs.getString("value_json")) as? List<*> ?: return@query
            result.getOrPut(ownerId) { LinkedHashMap() }[rs.getString("key")] = decoded.toList()
            },
            worldId.value,
        )
        return result
    }

    private fun decodeKvValue(json: String): Any? = MetaJson.decode("{\"value\":$json}")["value"]

    // OPENSAM-149 D2. flush는 troop을 created/dirty/deleted 세 경로로 다 쓰는데(JdbcFlushExecutor
    // troopCreateMany/troopUpdate/troopDeleteMany/troopDeleteByNation) 여기서 읽지 않아 재기동한 데몬이
    // 부대 0으로 출발했다. PK는 troop_leader(= Troop.id, 부대장 장수 id)다.
    private fun loadTroops(): List<Troop> = jdbc.query(
        "SELECT troop_leader, nation, name FROM troop WHERE world_id = ? ORDER BY troop_leader ASC",
        { rs, _ ->
            Troop(
                id = rs.getInt("troop_leader"),
                nationId = rs.getInt("nation"),
                name = rs.getString("name"),
            )
        },
        worldId.value,
    )

    private fun loadCities(): List<City> = jdbc.query(
        // state(V14 재해/호황 코드)를 SELECT에 포함해야 한다. 누락 시 in-memory City.state가 기본 0으로
        // 떨어지고, 재기동 직후 flush가 UPDATE city SET state=0 으로 직전 달 재해 표시를 지운다(P0-36).
        """
        SELECT id, name, nation_id, level, state, supply_state, front_state,
               pop, pop_max, dead, agri, agri_max, comm, comm_max, secu, secu_max, trust,
               def, def_max, wall, wall_max, trade, region, term, officer_set, conflict, meta
          FROM city WHERE world_id = ? ORDER BY id ASC
        """.trimIndent(),
        { rs, _ ->
        val cityMeta = MetaJson.decode(rs.getString("meta")).toMutableMap()
        cityMeta["trust"] = rs.getDouble("trust")
        City(
            id = rs.getInt("id"),
            name = rs.getString("name"),
            nationId = rs.getInt("nation_id"),
            level = rs.getInt("level"),
            state = rs.getInt("state"),
            supplyState = rs.getInt("supply_state"),
            frontState = rs.getInt("front_state"),
            population = rs.getInt("pop"),
            populationMax = rs.getInt("pop_max"),
            dead = rs.getInt("dead"),
            agriculture = rs.getInt("agri"),
            agricultureMax = rs.getInt("agri_max"),
            commerce = rs.getInt("comm"),
            commerceMax = rs.getInt("comm_max"),
            security = rs.getInt("secu"),
            securityMax = rs.getInt("secu_max"),
            defence = rs.getInt("def"),
            defenceMax = rs.getInt("def_max"),
            wall = rs.getInt("wall"),
            wallMax = rs.getInt("wall_max"),
            trade = nullableInt(rs, "trade"),
            region = rs.getInt("region"),
            term = rs.getInt("term"),
            officerSet = rs.getInt("officer_set"),
            conflict = rs.getString("conflict") ?: "{}",
            meta = cityMeta,
        )
        }, worldId.value)

    private fun loadGenerals(state: TurnWorldState): List<TurnGeneral> {
        // killturn 누락 legacy 행 보정용 시작 연/월 — 시드 시점(startYear, 월1) 기준.
        val seedStartYear = (state.meta["startYear"] as? Number)?.toInt() ?: state.currentYear
        val seedStartMonth = 1
        val rankValues = loadRankValues()
        val generalTurns = loadGeneralTurns()
        return jdbc.query(
        """
        SELECT id, name, nation_id, city_id, troop_id, npc_state, affinity,
               leadership, strength, intel, politics, charm, experience, dedication, officer_level,
               injury, gold, rice, crew, crew_type_id, train, atmos, age,
               weapon_code, book_code, horse_code, item_code,
               turn_time, recent_war_time, user_id, born_year, dead_year, picture, image_server,
               start_age, personal_code, special_code, special2_code, officer_city,
               last_turn, penalty, meta
          FROM general WHERE world_id = ? ORDER BY id ASC
        """.trimIndent(),
        { rs, _ ->
            val npcState = rs.getInt("npc_state")
            val generalMeta = ScenarioLifecycleMeta.ensureGeneralMeta(
                MetaJson.decode(rs.getString("meta")),
                deadYear = rs.getInt("dead_year"),
                startYear = seedStartYear,
                startMonth = seedStartMonth,
                convertLegacyNpcKillturn = npcState >= 2,
            ).toMutableMap()
            generalMeta.putIfAbsent("born_year", rs.getInt("born_year"))
            generalMeta.putIfAbsent("bornyear", rs.getInt("born_year"))
            generalMeta.putIfAbsent("dead_year", rs.getInt("dead_year"))
            generalMeta["deadyear"] = rs.getInt("dead_year")
            nullableInt(rs, "affinity")?.let { generalMeta.putIfAbsent("affinity", it) }
            generalMeta.putIfAbsent("picture", rs.getString("picture") ?: "default.jpg")
            generalMeta.putIfAbsent("image_server", rs.getInt("image_server"))
            generalMeta.putIfAbsent("imgsvr", rs.getInt("image_server"))
            generalMeta.putIfAbsent("start_age", rs.getInt("start_age"))
            generalMeta.putIfAbsent("startage", rs.getInt("start_age"))
            generalMeta.putIfAbsent("personal_code", rs.getString("personal_code") ?: "None")
            generalMeta.putIfAbsent("special_code", rs.getString("special_code") ?: "None")
            generalMeta.putIfAbsent("special2_code", rs.getString("special2_code") ?: "None")
            generalMeta.putIfAbsent("officer_city", rs.getInt("officer_city"))
            generalMeta["last_turn"] = MetaJson.decode(rs.getString("last_turn"))
            generalMeta.putAll(rankValues[rs.getInt("id")].orEmpty())
            generalMeta["penalty"] = MetaJson.decode(rs.getString("penalty"))
            TurnGeneral(
                id = rs.getInt("id"),
                name = rs.getString("name"),
                nationId = rs.getInt("nation_id"),
                cityId = rs.getInt("city_id"),
                troopId = rs.getInt("troop_id"),
                stats = GeneralStats(
                    leadership = rs.getInt("leadership"),
                    strength = rs.getInt("strength"),
                    intelligence = rs.getInt("intel"),
                    politics = rs.getInt("politics"),
                    charm = rs.getInt("charm"),
                ),
                experience = rs.getInt("experience"),
                dedication = rs.getInt("dedication"),
                officerLevel = rs.getInt("officer_level"),
                role = GeneralRole(
                    personality = rs.getString("personal_code") ?: "None",
                    specialDomestic = rs.getString("special_code") ?: "None",
                    specialWar = rs.getString("special2_code") ?: "None",
                    items = GeneralItems(
                        horse = rs.getString("horse_code") ?: "None",
                        weapon = rs.getString("weapon_code") ?: "None",
                        book = rs.getString("book_code") ?: "None",
                        item = rs.getString("item_code") ?: "None",
                    ),
                ),
                injury = rs.getInt("injury"),
                gold = rs.getInt("gold"),
                rice = rs.getInt("rice"),
                crew = rs.getInt("crew"),
                crewTypeId = rs.getInt("crew_type_id").takeIf { it >= GameUnitConst.CREWTYPE_CASTLE }
                    ?: GameUnitConst.DEFAULT_CREWTYPE,
                train = rs.getInt("train"),
                atmos = rs.getInt("atmos"),
                age = rs.getInt("age"),
                npcState = npcState,
                turnTime = rs.getObject("turn_time", OffsetDateTime::class.java).toInstant(),
                recentWarTime = rs.getObject("recent_war_time", OffsetDateTime::class.java)?.toInstant(),
                // user_id(소유 유저) — 미적재 시 rehydrate 후 PlaceBet 누적한도/유산 분기가 무음 발산(P0-07 채점 F1).
                userId = rs.getString("user_id"),
                meta = generalMeta,
                initialTurns = generalTurns[rs.getInt("id")].orEmpty(),
            )
        }, worldId.value)
    }

    private fun loadRankValues(): Map<Int, Map<String, Int>> {
        val result = LinkedHashMap<Int, LinkedHashMap<String, Int>>()
        jdbc.query(
            "SELECT general_id, type, value FROM rank_data WHERE world_id = ? ORDER BY general_id, id",
            { rs ->
            result.getOrPut(rs.getInt("general_id")) { LinkedHashMap() }[rs.getString("type")] = rs.getInt("value")
            },
            worldId.value,
        )
        return result
    }

    private fun loadGeneralTurns(): Map<Int, List<GeneralTurnSeed>> {
        val result = LinkedHashMap<Int, MutableList<GeneralTurnSeed>>()
        jdbc.query(
            """
            SELECT general_id, action_code, arg::text AS arg_json, brief
              FROM general_turn
             WHERE world_id = ?
             ORDER BY general_id, turn_idx
            """.trimIndent(),
            { rs ->
                result.getOrPut(rs.getInt("general_id")) { mutableListOf() }.add(
                    GeneralTurnSeed(
                        actionCode = rs.getString("action_code"),
                        argJson = rs.getString("arg_json"),
                        brief = rs.getString("brief"),
                    ),
                )
            },
            worldId.value,
        )
        return result
    }

    private fun loadDiplomacy(): List<TurnDiplomacy> = jdbc.query(
        """
        SELECT src_nation_id, dest_nation_id, state_code, term, casualties, meta
          FROM diplomacy
         WHERE world_id = ?
         ORDER BY id ASC
        """.trimIndent(),
        { rs, _ ->
            TurnDiplomacy(
                fromNationId = rs.getInt("src_nation_id"),
                toNationId = rs.getInt("dest_nation_id"),
                state = rs.getInt("state_code"),
                term = rs.getInt("term"),
                dead = rs.getInt("casualties"),
                meta = MetaJson.decode(rs.getString("meta")),
            )
        },
        worldId.value,
    )

    private fun loadAccessLogs(): List<GeneralAccessLog> = jdbc.query(
        """
        SELECT general_id, user_id, last_refresh, refresh, refresh_total, refresh_score, refresh_score_total
          FROM general_access_log WHERE world_id = ? ORDER BY general_id ASC
        """.trimIndent(),
        { rs, _ ->
            GeneralAccessLog(
                generalId = rs.getInt("general_id"),
                userId = rs.getLong("user_id").let { if (rs.wasNull()) null else it },
                lastRefresh = rs.getObject("last_refresh", OffsetDateTime::class.java)?.toInstant(),
                refresh = rs.getInt("refresh"),
                refreshTotal = rs.getInt("refresh_total"),
                refreshScore = rs.getInt("refresh_score"),
                refreshScoreTotal = rs.getInt("refresh_score_total"),
            )
        },
        worldId.value,
    )

    private fun nullableInt(rs: ResultSet, col: String): Int? {
        val v = rs.getInt(col)
        return if (rs.wasNull()) null else v
    }

    private fun activeGame(rs: ResultSet): ActiveGame = ActiveGame(
        id = rs.getInt("id"),
        serverId = rs.getString("server_id"),
        season = rs.getInt("season"),
        scenario = rs.getInt("scenario"),
        scenarioName = rs.getString("scenario_name"),
        map = rs.getString("map"),
        env = MetaJson.decode(rs.getString("env") ?: "{}"),
    )

    private data class ActiveGame(
        val id: Int,
        val serverId: String,
        val season: Int,
        val scenario: Int,
        val scenarioName: String,
        val map: String?,
        val env: Map<String, Any?>,
    )

    private companion object {
        val coldBootMetaKeys: Set<String> = setOf("statisticRows", "nationHistory", "generalHistory", "globalLogs")
    }

}
