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
import opensamguk.engine.turn.WorldSnapshot
import opensamguk.infra.persistence.MetaJson
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
            archivedNationIds = archivedNationIds,
        )
        ActiveWorldMapValidator.validate(snapshot)
        return snapshot
    }

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
