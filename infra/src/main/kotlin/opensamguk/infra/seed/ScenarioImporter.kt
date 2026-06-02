package opensamguk.infra.seed

import org.postgresql.util.PGobject
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.Timestamp
import java.time.OffsetDateTime

/**
 * A-minimal scenario-seed importer (F1a). Turns a fresh/empty PostgreSQL into a playable world by
 * INSERTing self-consistent rows for `scenario_1010`: `world_state` / `nation` / `city` / `general` /
 * `general_turn` / `nation_turn` / `diplomacy` / `rank_data` / `ng_games`.
 *
 * ## Why this is JDBC-only (NOT a one-daemon-write-rule violation)
 * The "ONE daemon-write rule" (CLAUDE.md / design §0.1 #3) forbids the game-engine daemon from using a
 * JPA `EntityManager` for *gameplay* writes — two competing dirty-truths (JPA dirty-checking +
 * ChangeRecorder) would silently diverge. This class is a **bootstrap row-loader via raw
 * [JdbcTemplate]**, in the SAME category as Flyway migrations and `AdminSeeder`: it runs ONCE, before
 * any turn loop, makes ZERO RNG draws, and never touches `ChangeRecorder` or an `EntityManager`. It is
 * therefore NOT a gameplay write and not subject to the flush-delta discipline. (The architecture
 * guards `DaemonNoEntityManagerTest` / `InfraNoEntityManagerTest` only scan the write-path packages
 * `opensamguk.engine.{flush,turn,run}` and `opensamguk.infra.persistence`; this `opensamguk.infra.seed`
 * package is outside both, and uses only `org.springframework.jdbc.*` regardless.)
 *
 * ## Parity boundary (A-minimal, non-strict)
 * This is the (A) playable seed — it makes ZERO RNG draws and is NOT gated against a PHP golden. Stat
 * splits are VERBATIM from the JSON. RNG/sync fields are deterministic A-approximations:
 *  - `world_state.hidden_seed` = the committed live config hex (`UniqueConst::$hiddenSeed`) — fixed, not drawn.
 *  - `general.city_id` = the nation's capital (deterministic) for faction generals; 0 for neutrals.
 *  - `general.affinity` / `personal_code` when null in JSON → null / 'None' (PHP would RNG-pick; B only).
 *  - `general.turn_time` = the install instant (PHP applies getRandTurn jitter; B only).
 * See `docs/superpowers/research/2026-06-02-F1-scenario-seed-spec.md` §5 for the full A↔B ledger.
 */
class ScenarioImporter(
    private val scenario: Scenario,
    private val cities: List<ScenarioCity>,
    /** Scenario code written to `world_state.scenario_code` / used as the resource key. */
    private val scenarioCode: String = "scenario_1010",
    /** `ng_games.scenario` numeric id (1010). */
    private val scenarioNumber: Int = 1010,
    /** Turn cadence in minutes (PHP `turnterm`). `tick_seconds = turnTerm * 60`. */
    private val turnTerm: Int = 60,
    /**
     * The fixed deterministic hidden seed (A). This is the committed live value from
     * `legacy/devsam-core/hwe/d_setting/UniqueConst.php::$hiddenSeed` (a 32-char lowercase hex
     * = `bin2hex(random_bytes(16))`), already used as the G1b golden input and the V4 calendar IT
     * fixture. A does NOT replay PHP RNG (B-parity is out of scope); it commits this exact hex so the
     * seed is reproducible and the monthly pipeline (`EngineEventConfig` reads `meta.hiddenSeed`) boots.
     */
    private val hiddenSeed: String = "8ebfeb6fa932a181ec9ef43b7473f4c9",
    /** The install instant; also `general.turn_time` / `world_state.start_time` / `ng_games.date`. */
    private val installTime: OffsetDateTime = OffsetDateTime.now(),
) {

    /** Result counts for the boot log + idempotency assertions. */
    data class ImportCounts(
        val worldState: Int,
        val nation: Int,
        val city: Int,
        val general: Int,
        val generalTurn: Int,
        val nationTurn: Int,
        val diplomacy: Int,
        val rankData: Int,
        val ngGames: Int,
    )

    /**
     * INSERT every row in dependency order (steps 4a–4j). Idempotency is the CALLER's responsibility
     * (`ScenarioSeedRunner` gates on `world_state` count); calling this on a non-empty DB will violate
     * primary-key uniqueness and throw — by design (no silent double-seed).
     */
    fun importAll(jdbc: JdbcTemplate): ImportCounts {
        val startYear = scenario.startYear

        // 4a — world_state (singleton id=1). meta carries the fields EngineEventConfig.monthlyPipeline reads.
        val worldStateCount = insertWorldState(jdbc, startYear)

        // 4b — nation (2 rows; neutral id 0 has NO row — generals just carry nation_id 0).
        val nationCount = insertNations(jdbc)

        // 4c — city (24). nation_id from the cities resource (already reverse-mapped to ids).
        val cityCount = insertCities(jdbc)

        // 4d — UPDATE nation.capital_city_id = first owned city in nation[].cities order.
        updateCapitals(jdbc)

        // 4e — general (678). id = sequential icon id; see assignGeneralIds.
        val (general, byId) = buildGenerals()
        val generalCount = insertGenerals(jdbc, general, startYear)

        // 4f — general_turn (678 × 30 ring rows, all 휴식).
        val generalTurnCount = insertGeneralTurns(jdbc, general)

        // 4g — nation_turn (per nation: officer_levels chiefLevel..12 × 12 turn_idx, all 휴식).
        val nationTurnCount = insertNationTurns(jdbc)

        // 4h — diplomacy (ordered neutral pairs + JSON overrides).
        val diplomacyCount = insertDiplomacy(jdbc, startYear)

        // 4i — rank_data (678 × 37 rows, value 0).
        val rankCount = insertRankData(jdbc, general)

        // 4j — ng_games (1 session record).
        val ngGamesCount = insertNgGames(jdbc)

        return ImportCounts(
            worldState = worldStateCount,
            nation = nationCount,
            city = cityCount,
            general = generalCount,
            generalTurn = generalTurnCount,
            nationTurn = nationTurnCount,
            diplomacy = diplomacyCount,
            rankData = rankCount,
            ngGames = ngGamesCount,
        )
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────────
    // 4a world_state
    // ─────────────────────────────────────────────────────────────────────────────────────────────
    private fun insertWorldState(jdbc: JdbcTemplate, startYear: Int): Int {
        val tickSeconds = turnTerm * 60
        val ts = Timestamp.from(installTime.toInstant())
        // meta keys consumed by EngineEventConfig.monthlyPipeline: hiddenSeed/startYear/startTime.
        val meta = jsonObject(
            "hiddenSeed" to hiddenSeed,
            "startYear" to startYear,
            "startTime" to installTime.toString(),
        )
        val config = jsonObject(
            "startyear" to startYear,
            "starttime" to installTime.toString(),
            "turnterm" to turnTerm,
        )
        jdbc.update(
            """
            INSERT INTO world_state
                (id, scenario_code, current_year, current_month, tick_seconds,
                 config, meta, start_year, start_time, turn_term, isunited, hidden_seed)
            VALUES (1, ?, ?, 1, ?, ?, ?, ?, ?, ?, 0, ?)
            """.trimIndent(),
            scenarioCode, startYear, tickSeconds,
            jsonb(config), jsonb(meta), startYear, ts, turnTerm, hiddenSeed,
        )
        return 1
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────────
    // 4b nation
    // ─────────────────────────────────────────────────────────────────────────────────────────────
    private fun insertNations(jdbc: JdbcTemplate): Int {
        var n = 0
        for (nation in scenario.nations) {
            val typeCode = nationTypeCode(nation.ideology)
            val meta = jsonObject(
                "infoText" to nation.desc,
            )
            jdbc.update(
                """
                INSERT INTO nation
                    (id, name, color, capital_city_id, gold, rice, tech, level, type_code, meta)
                VALUES (?, ?, ?, NULL, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                nation.id, nation.name, nation.color,
                nation.gold, nation.rice, nation.tech.toDouble(), nation.scale, typeCode, jsonb(meta),
            )
            n++
        }
        return n
    }

    /** `che_` is prefixed unless the ideology already contains `_` (PHP `Nation` type rule). */
    private fun nationTypeCode(ideology: String?): String {
        if (ideology.isNullOrBlank()) return "che_중립"
        return if (ideology.contains('_')) ideology else "che_$ideology"
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────────
    // 4c city
    // ─────────────────────────────────────────────────────────────────────────────────────────────
    private fun insertCities(jdbc: JdbcTemplate): Int {
        var n = 0
        for (c in cities) {
            // Occupied-city initial stats: PHP initialEvents ChangeCity ratio 70% of *_max (parity-OK:
            // the ratio is in the scenario JSON's initialEvents). trust 80, trade 100.
            val pop = ratio70(c.popMax)
            val agri = ratio70(c.agriMax)
            val comm = ratio70(c.commMax)
            val secu = ratio70(c.secuMax)
            val def = ratio70(c.defMax)
            val wall = ratio70(c.wallMax)
            jdbc.update(
                """
                INSERT INTO city
                    (id, name, level, nation_id, supply_state, front_state,
                     pop, pop_max, agri, agri_max, comm, comm_max, secu, secu_max,
                     trust, trade, def, def_max, wall, wall_max, region,
                     term, officer_set, conflict, meta)
                VALUES (?, ?, ?, ?, 1, 0,
                        ?, ?, ?, ?, ?, ?, ?, ?,
                        80.0, 100, ?, ?, ?, ?, ?,
                        0, 0, '{}'::jsonb, '{}'::jsonb)
                """.trimIndent(),
                c.id, c.name, c.level, c.nationId,
                pop, c.popMax, agri, c.agriMax, comm, c.commMax, secu, c.secuMax,
                def, c.defMax, wall, c.wallMax, c.region,
            )
            n++
        }
        return n
    }

    /** PHP `Util::round` = half-away-from-zero. 70% initial ratio for occupied cities. */
    private fun ratio70(max: Int): Int = phpRoundHalfAway(max * 0.7)

    // ─────────────────────────────────────────────────────────────────────────────────────────────
    // 4d capital
    // ─────────────────────────────────────────────────────────────────────────────────────────────
    private fun updateCapitals(jdbc: JdbcTemplate) {
        val cityIdByName = cities.associate { it.name to it.id }
        for (nation in scenario.nations) {
            val firstCityName = nation.cities.firstOrNull() ?: continue
            val capitalId = cityIdByName[firstCityName] ?: continue
            jdbc.update("UPDATE nation SET capital_city_id = ? WHERE id = ?", capitalId, nation.id)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────────
    // 4e general
    // ─────────────────────────────────────────────────────────────────────────────────────────────

    /** A general row with its assigned id + resolved derived fields. */
    private data class BuiltGeneral(val id: Int, val src: ScenarioGeneral)

    /**
     * Assign general ids by build order (general[] then general_ex[]) starting at 1001 — the PHP icon-id
     * base. ids are sequential and stable so general_turn/rank_data foreign keys line up.
     */
    private fun buildGenerals(): Pair<List<BuiltGeneral>, Map<Int, BuiltGeneral>> {
        val built = scenario.generals.mapIndexed { idx, g -> BuiltGeneral(id = 1001 + idx, src = g) }
        return built to built.associateBy { it.id }
    }

    private fun insertGenerals(jdbc: JdbcTemplate, generals: List<BuiltGeneral>, startYear: Int): Int {
        // capital city id per nation, for deterministic A placement of faction generals.
        val cityIdByName = cities.associate { it.name to it.id }
        val capitalByNation = scenario.nations.associate { n ->
            n.id to (n.cities.firstOrNull()?.let { cityIdByName[it] } ?: 0)
        }
        // EVERY general is physically located in a city (PHP: even 재야/neutral wanderers occupy a city —
        // the reserved-turn handler resolves over the general's city, so city 0 is not a valid location).
        // A-deterministic placement: faction generals → their nation capital; neutrals (nation 0) → the
        // first seeded city (deterministic). PHP would `rng->choice(nationCities | allCities)` — B only.
        val defaultNeutralCity = cities.firstOrNull()?.id ?: 0
        val ts = Timestamp.from(installTime.toInstant())

        val sql = """
            INSERT INTO general
                (id, name, nation_id, city_id, troop_id, npc_state, affinity,
                 born_year, dead_year, picture, image_server,
                 leadership, strength, intel, injury, experience, dedication, officer_level,
                 gold, rice, crew, crew_type_id, train, atmos,
                 weapon_code, book_code, horse_code, item_code,
                 turn_time, age, start_age, personal_code, special_code, special2_code, officer_city,
                 last_turn, meta, penalty)
            VALUES
                (?, ?, ?, ?, 0, ?, ?,
                 ?, ?, ?, 0,
                 ?, ?, ?, 0, ?, ?, ?,
                 1000, 1000, 0, 0, 0, 0,
                 'None', 'None', 'None', 'None',
                 ?, ?, ?, ?, ?, 'None', 0,
                 '{}'::jsonb, '{}'::jsonb, '{}'::jsonb)
        """.trimIndent()

        var n = 0
        for (bg in generals) {
            val g = bg.src
            val born = g.bornYear ?: 180
            val dead = g.deadYear ?: 300
            val age = (startYear - born).coerceAtLeast(0)
            // npc_state: all 1010 rosters are NPC type 2 (general[]/general_ex[] both npcType=2).
            val npcState = 2
            // city placement (A deterministic): faction generals → nation capital; neutrals → first city.
            val cityId = if (g.nationId != 0) capitalByNation[g.nationId] ?: defaultNeutralCity else defaultNeutralCity
            // experience/dedication default branch: age*100 (matches PHP GeneralBuilder default).
            val exp = age * 100
            val ded = age * 100
            val personal = personalCode(g.ego)
            val special = g.special ?: "None"
            jdbc.update(
                sql,
                bg.id, g.name, g.nationId, cityId, npcState, g.affinity,
                born, dead, "default.jpg",
                g.leadership, g.strength, g.intel, exp, ded, g.officerLevel,
                ts, age, age, personal, special,
            )
            n++
        }
        return n
    }

    /** PHP stores `Util::getClassName(getPersonalityClass($ego))` = `che_<ego>`. A: 'None' when null. */
    private fun personalCode(ego: String?): String {
        if (ego.isNullOrBlank()) return "None"
        return if (ego.contains('_')) ego else "che_$ego"
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────────
    // 4f general_turn — full 30-row ring, all 휴식
    // ─────────────────────────────────────────────────────────────────────────────────────────────
    private fun insertGeneralTurns(jdbc: JdbcTemplate, generals: List<BuiltGeneral>): Int {
        val rows = ArrayList<Array<Any?>>(generals.size * MAX_GENERAL_TURNS)
        for (bg in generals) {
            for (idx in 0 until MAX_GENERAL_TURNS) {
                rows.add(arrayOf(bg.id, idx))
            }
        }
        jdbc.batchUpdate(
            """
            INSERT INTO general_turn (general_id, turn_idx, action_code, arg, brief)
            VALUES (?, ?, '휴식', '{}'::jsonb, '휴식')
            """.trimIndent(),
            rows,
        )
        return rows.size
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────────
    // 4g nation_turn — per nation, officer_levels chiefLevel..12 × 12 turn_idx, all 휴식
    // ─────────────────────────────────────────────────────────────────────────────────────────────
    private fun insertNationTurns(jdbc: JdbcTemplate): Int {
        val rows = ArrayList<Array<Any?>>()
        for (nation in scenario.nations) {
            val chiefLevel = getNationChiefLevel(nation.scale)
            // PHP UpdateNationLevel seeds Util::range(chiefLevel, 12) officer seats.
            for (officerLevel in chiefLevel..12) {
                for (idx in 0 until MAX_CHIEF_TURNS) {
                    rows.add(arrayOf(nation.id, officerLevel, idx))
                }
            }
        }
        jdbc.batchUpdate(
            """
            INSERT INTO nation_turn (nation_id, officer_level, turn_idx, action_code, arg, brief)
            VALUES (?, ?, ?, '휴식', '{}'::jsonb, '휴식')
            """.trimIndent(),
            rows,
        )
        return rows.size
    }

    /** Mirror of `common` GameConst.getNationChiefLevel (kept local to avoid a game-engine dependency). */
    private fun getNationChiefLevel(level: Int): Int = when (level) {
        9, 8, 7, 6 -> 5
        5, 4 -> 7
        3, 2 -> 9
        1, 0 -> 11
        else -> 11
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────────
    // 4h diplomacy — ordered neutral pairs + JSON overrides (insertion-order preserved)
    // ─────────────────────────────────────────────────────────────────────────────────────────────
    private fun insertDiplomacy(jdbc: JdbcTemplate, startYear: Int): Int {
        // LinkedHashMap keyed by (src,dest) preserves insertion order — never re-keyed by id.
        val pairs = LinkedHashMap<Pair<Int, Int>, DiploRow>()
        val ids = scenario.nations.map { it.id }
        for (me in ids) {
            for (you in ids) {
                if (me == you) continue
                pairs[me to you] = DiploRow(state = 2, term = 0) // neutral default
            }
        }
        // Apply JSON diplomacy[] overrides. At startYear/month1, monthDiff=0 ⇒ term = remainMonths.
        for (d in scenario.diplomacy) {
            val key = d.me to d.you
            if (pairs.containsKey(key)) {
                pairs[key] = DiploRow(state = d.state, term = d.remainMonths)
            }
        }
        var n = 0
        for ((key, row) in pairs) {
            val (src, dest) = key
            jdbc.update(
                """
                INSERT INTO diplomacy
                    (src_nation_id, dest_nation_id, state_code, term, is_dead, is_showing, meta)
                VALUES (?, ?, ?, ?, false, true, '{}'::jsonb)
                """.trimIndent(),
                src, dest, row.state, row.term,
            )
            n++
        }
        return n
    }

    private data class DiploRow(val state: Int, val term: Int)

    // ─────────────────────────────────────────────────────────────────────────────────────────────
    // 4i rank_data — 37 rows/general, value 0, nation_id 0
    // ─────────────────────────────────────────────────────────────────────────────────────────────
    private fun insertRankData(jdbc: JdbcTemplate, generals: List<BuiltGeneral>): Int {
        val rows = ArrayList<Array<Any?>>(generals.size * RANK_COLUMNS.size)
        for (bg in generals) {
            for (type in RANK_COLUMNS) {
                rows.add(arrayOf(bg.id, type))
            }
        }
        jdbc.batchUpdate(
            """
            INSERT INTO rank_data (nation_id, general_id, type, value)
            VALUES (0, ?, ?, 0)
            """.trimIndent(),
            rows,
        )
        return rows.size
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────────
    // 4j ng_games — session record
    // ─────────────────────────────────────────────────────────────────────────────────────────────
    private fun insertNgGames(jdbc: JdbcTemplate): Int {
        val serverId = "opensamguk_${scenarioNumber}_${installTime.toEpochSecond()}"
        val ts = Timestamp.from(installTime.toInstant())
        val env = jsonObject(
            "scenario" to scenarioCode,
            "startyear" to scenario.startYear,
            "turnterm" to turnTerm,
            "hiddenSeed" to hiddenSeed,
        )
        jdbc.update(
            """
            INSERT INTO ng_games
                (server_id, date, winner_nation, map, season, scenario, scenario_name, env)
            VALUES (?, ?, NULL, NULL, 1, ?, ?, ?)
            """.trimIndent(),
            serverId, ts, scenarioNumber, scenario.title, jsonb(env),
        )
        return 1
    }

    // ── jsonb helpers ──
    private fun jsonObject(vararg pairs: Pair<String, Any?>): String {
        // Insertion-order-preserving compact JSON (PHP-faithful). Reuse MetaJson via opensamguk.infra.
        val map = LinkedHashMap<String, Any?>()
        for ((k, v) in pairs) map[k] = v
        return opensamguk.infra.persistence.MetaJson.encode(map)
    }

    private fun jsonb(json: String): PGobject {
        val pg = PGobject()
        pg.type = "jsonb"
        pg.value = json
        return pg
    }

    /** PHP `Util::round` half-away-from-zero (NOT Math.round / banker's rounding). */
    private fun phpRoundHalfAway(v: Double): Int =
        if (v >= 0) Math.floor(v + 0.5).toInt() else Math.ceil(v - 0.5).toInt()

    companion object {
        /** GeneralAi/reserved ring capacity (= common GameConst.maxTurn). */
        const val MAX_GENERAL_TURNS = 30

        /** Nation/chief ring capacity (= common GameConst.maxChiefTurn). */
        const val MAX_CHIEF_TURNS = 12

        /**
         * The 37 `rank_data.type` column names — VERBATIM mirror of
         * `app/game-engine/.../turn/TurnWorldModel.kt RankColumn` (one row per enum case). Mirrored here
         * (not referenced) because `infra` must NOT depend on `app:game-engine`; a test cross-checks the
         * two lists stay identical so they can never drift.
         */
        val RANK_COLUMNS: List<String> = listOf(
            "firenum", "warnum", "killnum", "deathnum", "killcrew", "deathcrew",
            "ttw", "ttd", "ttl", "ttg", "ttp",
            "tlw", "tld", "tll", "tlg", "tlp",
            "tsw", "tsd", "tsl", "tsg", "tsp",
            "tiw", "tid", "til", "tig", "tip",
            "betwin", "betgold", "betwingold",
            "killcrew_person", "deathcrew_person", "occupied",
            "inherit_earned", "inherit_spent", "inherit_earned_dyn", "inherit_earned_act", "inherit_spent_dyn",
        )
    }
}
