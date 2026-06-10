package opensamguk.engine.boot

import opensamguk.engine.turn.City
import opensamguk.engine.turn.GeneralStats
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

        val state = loadWorldState()
        val nations = loadNations()
        val cities = loadCities()
        val generals = loadGenerals()
        val diplomacy = loadDiplomacy()
        log.info(
            "WorldSnapshot loaded — generals={} cities={} nations={} diplomacy={} troops=0",
            generals.size, cities.size, nations.size, diplomacy.size,
        )
        return WorldSnapshot(
            state = state,
            generals = generals,
            cities = cities,
            nations = nations,
            troops = emptyList(),
            diplomacy = diplomacy,
        )
    }

    private fun loadWorldState(): TurnWorldState {
        val rows = jdbc.query(
            "SELECT id, current_year, current_month, tick_seconds, meta, start_time FROM world_state ORDER BY id ASC LIMIT 1",
        ) { rs, _ ->
            val meta = MetaJson.decode(rs.getString("meta"))
            // lastTurnTime: prefer the persisted clock; fall back to start_time, then now.
            val lastTurn = (meta["lastTurnTime"] as? String)?.let { Instant.parse(it) }
                ?: rs.getObject("start_time", OffsetDateTime::class.java)?.toInstant()
                ?: Instant.now()
            TurnWorldState(
                id = rs.getInt("id"),
                currentYear = rs.getInt("current_year"),
                currentMonth = rs.getInt("current_month"),
                tickSeconds = rs.getInt("tick_seconds"),
                lastTurnTime = lastTurn,
                meta = meta,
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

    private fun loadCities(): List<City> = jdbc.query(
        // state(V14 재해/호황 코드)를 SELECT에 포함해야 한다. 누락 시 in-memory City.state가 기본 0으로
        // 떨어지고, 재기동 직후 flush가 UPDATE city SET state=0 으로 직전 달 재해 표시를 지운다(P0-36).
        """
        SELECT id, name, nation_id, level, state, supply_state, front_state,
               pop, pop_max, agri, agri_max, comm, comm_max, secu, secu_max,
               def, def_max, wall, wall_max, trade, region, meta
          FROM city ORDER BY id ASC
        """.trimIndent(),
    ) { rs, _ ->
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
            meta = MetaJson.decode(rs.getString("meta")),
        )
    }

    private fun loadGenerals(): List<TurnGeneral> = jdbc.query(
        """
        SELECT id, name, nation_id, city_id, troop_id, npc_state,
               leadership, strength, intel, experience, dedication, officer_level,
               injury, gold, rice, crew, crew_type_id, train, atmos, age,
               turn_time, recent_war_time, meta
          FROM general ORDER BY id ASC
        """.trimIndent(),
    ) { rs, _ ->
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
            meta = MetaJson.decode(rs.getString("meta")),
        )
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

    private fun nullableInt(rs: ResultSet, col: String): Int? {
        val v = rs.getInt(col)
        return if (rs.wasNull()) null else v
    }
}
