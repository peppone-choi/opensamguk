package opensamguk.engine.boot

import opensamguk.common.constants.ScenarioLifecycleMeta
import opensamguk.engine.turn.City
import opensamguk.engine.turn.GeneralStats
import opensamguk.engine.turn.GeneralAccessLog
import opensamguk.engine.turn.Nation
import opensamguk.engine.turn.TurnDiplomacy
import opensamguk.engine.turn.TurnGeneral
import opensamguk.engine.turn.TurnWorldState
import opensamguk.engine.turn.WorldSnapshot
import opensamguk.infra.persistence.MetaJson
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime

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
@Component
class WorldSnapshotLoader(
    private val jdbc: JdbcTemplate,
    private val seedBootstrap: SeedBootstrap,
) {
    private val log = LoggerFactory.getLogger(WorldSnapshotLoader::class.java)

    fun buildSnapshot(): WorldSnapshot {
        // Guarantee the seed has run (idempotent) before reading.
        seedBootstrap.ensureSeeded(jdbc)

        val state = loadWorldState().let { loaded ->
            val merged = LinkedHashMap(loaded.meta)
            merged.putAll(loadGameEnv())
            val snapshotKeys = listOf(
                "isunited",
                "lastTurnTime",
                "hiddenSeed",
                "startYear",
                "startTime",
                "scenario",
                "map",
                "maxNationId",
                "maxGeneralId",
            )
            for (key in snapshotKeys) {
                if (loaded.meta.containsKey(key)) merged[key] = loaded.meta[key]
            }
            loaded.copy(meta = merged)
        }
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
        log.info(
            "WorldSnapshot loaded — generals={} cities={} nations={} diplomacy={} accessLogs={} troops=0",
            generals.size, cities.size, nations.size, diplomacy.size, accessLogs.size,
        )
        return WorldSnapshot(
            state = state,
            generals = generals,
            cities = cities,
            nations = nations,
            troops = emptyList(),
            diplomacy = diplomacy,
            accessLogs = accessLogs,
        )
    }

    private fun loadWorldState(): TurnWorldState {
        val rows = jdbc.query(
            "SELECT id, current_year, current_month, current_phase, tick_seconds, isunited, status, meta, config, start_time FROM world_state ORDER BY id ASC LIMIT 1",
        ) { rs, _ ->
            val meta = LinkedHashMap(MetaJson.decode(rs.getString("meta")))
            val config = MetaJson.decode(rs.getString("config"))
            for ((key, value) in config) {
                if (!meta.containsKey(key)) meta[key] = value
            }
            // isunited: dedicated column is source of truth (flush writes it; meta-only fallback for legacy rows).
            meta["isunited"] = rs.getInt("isunited")
            // lastTurnTime: prefer the persisted clock; fall back to start_time, then now.
            val lastTurn = (meta["lastTurnTime"] as? String)?.let { Instant.parse(it) }
                ?: rs.getObject("start_time", OffsetDateTime::class.java)?.toInstant()
                ?: Instant.now()
            TurnWorldState(
                id = rs.getInt("id"),
                currentYear = rs.getInt("current_year"),
                currentMonth = rs.getInt("current_month"),
                currentPhase = rs.getInt("current_phase").takeIf { it in 1..3 } ?: 1,
                tickSeconds = rs.getInt("tick_seconds"),
                lastTurnTime = lastTurn,
                meta = meta,
                status = rs.getString("status"),
                config = config,
            )
        }
        return rows.firstOrNull()
            ?: error("world_state singleton row is missing — scenario seed did not run (cannot build WorldSnapshot)")
    }

    private fun loadNations(): List<Nation> = jdbc.query(
        // tech를 SELECT에 포함해야 한다. 누락 시 in-memory Nation.tech가 기본 0.0으로 떨어지고,
        // 다음 월틱 flush가 UPDATE nation SET tech=0 으로 시드값(예: 후한 1500)을 영구히 덮어쓴다.
        "SELECT id, name, color, capital_city_id, gold, rice, tech, power, level, type_code, meta " +
            "FROM nation ORDER BY id ASC",
    ) { rs, _ ->
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
    }

    private fun loadGameEnv(): Map<String, Any?> = linkedMapOf<String, Any?>().apply {
        jdbc.query(
            """SELECT key, CAST(value AS VARCHAR) AS value_json FROM game_kv WHERE "table" = 'game_env' AND namespace IN ('', 'game_env') ORDER BY id ASC""",
        ) { rs ->
            this[rs.getString("key")] = decodeKvValue(rs.getString("value_json"))
        }
    }

    private fun loadNationEnv(): Map<Int, Map<String, Any?>> {
        val result = LinkedHashMap<Int, LinkedHashMap<String, Any?>>()
        jdbc.query("SELECT namespace, key, CAST(value AS VARCHAR) AS value_json FROM nation_env ORDER BY id ASC") { rs ->
            result.getOrPut(rs.getInt("namespace")) { LinkedHashMap() }[rs.getString("key")] =
                decodeKvValue(rs.getString("value_json"))
        }
        return result
    }

    private fun decodeKvValue(json: String): Any? = MetaJson.decode("{\"value\":$json}")["value"]

    private fun loadCities(): List<City> = jdbc.query(
        // state(V14 재해/호황 코드)를 SELECT에 포함해야 한다. 누락 시 in-memory City.state가 기본 0으로
        // 떨어지고, 재기동 직후 flush가 UPDATE city SET state=0 으로 직전 달 재해 표시를 지운다(P0-36).
        """
        SELECT id, name, nation_id, level, state, supply_state, front_state,
               pop, pop_max, dead, agri, agri_max, comm, comm_max, secu, secu_max, trust,
               def, def_max, wall, wall_max, trade, region, term, officer_set, conflict, meta
          FROM city ORDER BY id ASC
        """.trimIndent(),
    ) { rs, _ ->
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
    }

    private fun loadGenerals(state: TurnWorldState): List<TurnGeneral> {
        // killturn 누락 legacy 행 보정용 시작 연/월 — 시드 시점(startYear, 월1) 기준.
        val seedStartYear = (state.meta["startYear"] as? Number)?.toInt() ?: state.currentYear
        val seedStartMonth = 1
        return jdbc.query(
        """
        SELECT id, name, nation_id, city_id, troop_id, npc_state,
               leadership, strength, intel, politics, charm, experience, dedication, officer_level,
               injury, gold, rice, crew, crew_type_id, train, atmos, age,
               turn_time, recent_war_time, user_id, dead_year, penalty, meta
          FROM general ORDER BY id ASC
        """.trimIndent(),
        ) { rs, _ ->
            val generalMeta = ScenarioLifecycleMeta.ensureGeneralMeta(
                MetaJson.decode(rs.getString("meta")),
                deadYear = rs.getInt("dead_year"),
                startYear = seedStartYear,
                startMonth = seedStartMonth,
            ).toMutableMap()
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
                injury = rs.getInt("injury"),
                gold = rs.getInt("gold"),
                rice = rs.getInt("rice"),
                crew = rs.getInt("crew"),
                crewTypeId = rs.getInt("crew_type_id"),
                train = rs.getInt("train"),
                atmos = rs.getInt("atmos"),
                age = rs.getInt("age"),
                npcState = rs.getInt("npc_state"),
                turnTime = rs.getObject("turn_time", OffsetDateTime::class.java).toInstant(),
                recentWarTime = rs.getObject("recent_war_time", OffsetDateTime::class.java)?.toInstant(),
                // user_id(소유 유저) — 미적재 시 rehydrate 후 PlaceBet 누적한도/유산 분기가 무음 발산(P0-07 채점 F1).
                userId = rs.getString("user_id"),
                meta = generalMeta,
            )
        }
    }

    private fun loadDiplomacy(): List<TurnDiplomacy> = jdbc.query(
        "SELECT src_nation_id, dest_nation_id, state_code, term, is_dead, meta FROM diplomacy ORDER BY id ASC",
    ) { rs, _ ->
        TurnDiplomacy(
            fromNationId = rs.getInt("src_nation_id"),
            toNationId = rs.getInt("dest_nation_id"),
            state = rs.getInt("state_code"),
            term = rs.getInt("term"),
            dead = if (rs.getBoolean("is_dead")) 1 else 0,
            meta = MetaJson.decode(rs.getString("meta")),
        )
    }

    private fun loadAccessLogs(): List<GeneralAccessLog> = jdbc.query(
        """
        SELECT general_id, user_id, last_refresh, refresh, refresh_total, refresh_score, refresh_score_total
          FROM general_access_log ORDER BY general_id ASC
        """.trimIndent(),
    ) { rs, _ ->
        GeneralAccessLog(
            generalId = rs.getInt("general_id"),
            userId = rs.getLong("user_id").let { if (rs.wasNull()) null else it },
            lastRefresh = rs.getObject("last_refresh", OffsetDateTime::class.java)?.toInstant(),
            refresh = rs.getInt("refresh"),
            refreshTotal = rs.getInt("refresh_total"),
            refreshScore = rs.getInt("refresh_score"),
            refreshScoreTotal = rs.getInt("refresh_score_total"),
        )
    }

    private fun nullableInt(rs: ResultSet, col: String): Int? {
        val v = rs.getInt(col)
        return if (rs.wasNull()) null else v
    }
}
