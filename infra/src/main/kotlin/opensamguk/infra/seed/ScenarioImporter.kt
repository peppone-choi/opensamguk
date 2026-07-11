package opensamguk.infra.seed

import opensamguk.common.constants.ScenarioLifecycleMeta
import opensamguk.logic.event.EventStore
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
     * NPC 빙의 모드 (PHP `npcmode`). 0=불가 / 1=가능 / 2=선택 생성.
     * Legacy install.php 기본값 0 (`npcmode_0` checked) — entrance 3버튼 게이트에 사용.
     */
    private val npcMode: Int = 0,
    /**
     * 장수 임의 생성 제어 (PHP `block_general_create`). 비트마스크:
     *   0=가능, 1=불가, 2=장수명 무작위.
     * Legacy install.php 기본값 0 (`block_general_create_0` checked).
     */
    private val blockGeneralCreate: Int = 0,
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
        val event: Int,
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

        val eventCount = insertEvents(jdbc)

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
            event = eventCount,
        )
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────────
    // 4a world_state
    // ─────────────────────────────────────────────────────────────────────────────────────────────
    private fun insertWorldState(jdbc: JdbcTemplate, startYear: Int): Int {
        val tickSeconds = turnTerm * 60
        val ts = Timestamp.from(installTime.toInstant())
        val mapConfig = scenarioMapConfig()
        val mapName = mapConfig["mapName"] as? String ?: "che"
        val unitSet = mapConfig["unitSet"] as? String ?: "che"
        // meta keys consumed by EngineEventConfig.monthlyPipeline: hiddenSeed/startYear/startTime.
        val meta = jsonObject(
            "hiddenSeed" to hiddenSeed,
            "startYear" to startYear,
            "startTime" to installTime.toString(),
            "map" to mapName,
            "unitSet" to unitSet,
        )
        val config = jsonObject(
            "startyear" to startYear,
            "starttime" to installTime.toString(),
            "turnterm" to turnTerm,
            "npcmode" to npcMode,
            "block_general_create" to blockGeneralCreate,
            "ignoreDefaultEvents" to scenario.ignoreDefaultEvents,
            "map" to mapConfig,
            "mapName" to mapName,
            "unitSet" to unitSet,
        )
        jdbc.update(
            """
            INSERT INTO world_state
                (id, scenario_code, current_year, current_month, current_phase, tick_seconds,
                 config, meta, start_year, start_time, turn_term, isunited, hidden_seed, status)
            VALUES (1, ?, ?, 1, 1, ?, ?, ?, ?, ?, ?, 0, ?, 'OPEN')
            """.trimIndent(),
            scenarioCode, startYear, tickSeconds,
            jsonb(config), jsonb(meta), startYear, ts, turnTerm, hiddenSeed,
        )
        return 1
    }

    private fun scenarioMapConfig(): Map<String, Any?> {
        val merged = LinkedHashMap<String, Any?>()
        merged.putAll(scenario.map)
        merged.putAll(scenario.const)
        if (merged["mapName"] !is String) merged["mapName"] = "che"
        if (merged["unitSet"] !is String) merged["unitSet"] = "che"
        return merged
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────────
    // 4b nation
    // ─────────────────────────────────────────────────────────────────────────────────────────────
    private fun insertNations(jdbc: JdbcTemplate): Int {
        // gennum(국가별 장수 수) — PHP `Scenario/Nation.php::postBuild` 가 nation.gennum = count(generals).
        // 시드 장수는 nationId 로 소속되므로 시나리오 장수를 nationId 별로 센다.
        val gennumByNation = scenario.generals.groupingBy { it.nationId }.eachCount()
        var n = 0
        for (nation in scenario.nations) {
            val typeCode = nationTypeCode(nation.ideology)
            // PHP `Scenario/Nation.php::build` 의 nation INSERT 패러티 — 스칼라 PHP `nation` 컬럼들을
            // opensamguk V1 스키마가 meta jsonb 로 접는다(NationFinanceSetters 가 쓰는 키와 동일).
            //  - rate=15 / bill=100 / scout=0 / war=0 / strategic_cmd_limit=24 / surlimit=72 (시나리오 고정값).
            //  - gennum = 소속 장수 수(postBuild). 종전 시드는 infoText 만 넣어 rate/bill 부재 → 내무부
            //    예산/정책 표가 전부 '-'(FE 가드 `policy.rate/bill != null` 미충족). 이 누락이 그 근본 원인.
            // (secretlimit 는 PHP INSERT 에서도 미지정 → 스키마 기본값 → 여기서도 미기재; FE 는 null→'-'.)
            val meta = jsonObject(
                "infoText" to nation.desc,
                "rate" to 15,
                "bill" to 100,
                "scout" to 0,
                "war" to 0,
                "strategic_cmd_limit" to 24,
                "surlimit" to 72,
                "gennum" to (gennumByNation[nation.id] ?: 0),
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
    /**
     * 도시 소유 = **시나리오 `nation.cities`(도시명) 기준**. 도시 리소스(`cities_1010.json`)에 baked된
     * `nation_id`가 아니라, 각 시나리오가 정의한 `nation[].cities` 목록을 소유 정본으로 쓴다.
     *
     * WHY: 여러 시나리오가 `cities_1010.json`(che 풀맵 94도시) 리소스를 **재사용**한다(빼섭 `scenario_1030`
     * 등). 그 리소스의 baked `nation_id`는 `scenario_1010`(2국) 전용 배정이라, 재사용하는 시나리오에선
     * 자기 국가들이 도시를 하나도 소유하지 못한다(→ capital 보유인데 supplyCities 빔 → UpdateCitySupply
     * 미시드 → `doNPC구출발령`의 `RandUtil.choice(빈 보급도시)`가 throw → 턴데몬 크래시-루프 동결).
     * 소유를 시나리오 `nation.cities`에서 배정하면 모든 시나리오가 올바르게 도시를 소유한다. `scenario_1010`은
     * baked `nation_id`와 동일 집합이라 **무변(no-op)**, `scenario_1030`은 21국 소유가 복구된다(PHP는
     * 시나리오 `nation.cities`로 소유를 정한다 — 이쪽이 grand-truth 정합).
     */
    private val cityOwnerByName: Map<String, Int> =
        scenario.nations.flatMap { n -> n.cities.map { cityName -> cityName to n.id } }.toMap()

    private fun insertCities(jdbc: JdbcTemplate): Int {
        var n = 0
        for (c in cities) {
            // 소유: 시나리오 nation.cities 기준(baked c.nationId 무시). 미소유 도시 = 공백지(0).
            val cityNationId = cityOwnerByName[c.name] ?: 0
            // 초기 스탯 분기:
            //  - 점령지(nation_id!=0): PHP initialEvents ChangeCity가 *_max의 70%로 부스트(parity). trust 80.
            //  - 공백지(nation_id==0): 부스트 없음 → CityConstBase 베이스 initial(데이터의 *_init). trust 50.
            //    (initialEvents는 점령지만 대상이라 공백지는 베이스 그대로 — 70도시 신규.)
            val occupied = cityNationId != 0
            val pop = if (occupied) ratio70(c.popMax) else (c.popInit ?: ratio70(c.popMax))
            val agri = if (occupied) ratio70(c.agriMax) else (c.agriInit ?: ratio70(c.agriMax))
            val comm = if (occupied) ratio70(c.commMax) else (c.commInit ?: ratio70(c.commMax))
            val secu = if (occupied) ratio70(c.secuMax) else (c.secuInit ?: ratio70(c.secuMax))
            val def = if (occupied) ratio70(c.defMax) else (c.defInit ?: ratio70(c.defMax))
            val wall = if (occupied) ratio70(c.wallMax) else (c.wallInit ?: ratio70(c.wallMax))
            val trust = if (occupied) 80.0 else 50.0
            jdbc.update(
                """
                INSERT INTO city
                    (id, name, level, nation_id, supply_state, front_state,
                     pop, pop_max, agri, agri_max, comm, comm_max, secu, secu_max,
                     trust, trade, def, def_max, wall, wall_max, region,
                     term, officer_set, conflict, meta)
                VALUES (?, ?, ?, ?, 1, 0,
                        ?, ?, ?, ?, ?, ?, ?, ?,
                        ?, 100, ?, ?, ?, ?, ?,
                        0, 0, '{}'::jsonb, '{}'::jsonb)
                """.trimIndent(),
                c.id, c.name, c.level, cityNationId,
                pop, c.popMax, agri, c.agriMax, comm, c.commMax, secu, c.secuMax,
                trust, def, c.defMax, wall, c.wallMax, c.region,
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
                 last_turn, meta, penalty,
                 politics, charm)
            VALUES
                (?, ?, ?, ?, 0, ?, ?,
                 ?, ?, ?, 0,
                 ?, ?, ?, 0, ?, ?, ?,
                 1000, 1000, 0, 0, 0, 0,
                 'None', 'None', 'None', 'None',
                 ?, ?, ?, ?, ?, 'None', 0,
                 '{}'::jsonb, ?, '{}'::jsonb,
                 ?, ?)
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
                // killturn은 장수별 사망년도 파생값(startMonth=1 = world_state 시드 current_month).
                jsonb(ScenarioLifecycleMeta.initialGeneralMeta(dead, startYear, SEED_START_MONTH)),
                g.politics, g.charm,
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

    // ─────────────────────────────────────────────────────────────────────────────────────────────
    // 4k event — persisted merged default + scenario rows
    // ─────────────────────────────────────────────────────────────────────────────────────────────
    private fun insertEvents(jdbc: JdbcTemplate): Int {
        val defaults = if (scenario.ignoreDefaultEvents) {
            emptyList()
        } else {
            EventStore.defaultWireRows().map { row ->
                EventRowToInsert(
                    target = row.target,
                    priority = row.priority,
                    condition = row.condition,
                    action = row.action,
                )
            }
        }
        val scenarioRows = scenario.events.map { row ->
            EventRowToInsert(
                target = row.target,
                priority = row.priority,
                condition = opensamguk.infra.persistence.MetaJson.encode(row.condition),
                action = opensamguk.infra.persistence.MetaJson.encode(row.actions),
            )
        }
        for (row in defaults + scenarioRows) {
            jdbc.update(
                """
                INSERT INTO event (target_code, priority, condition, action)
                VALUES (?, ?, ?, ?)
                """.trimIndent(),
                row.target, row.priority, jsonb(row.condition), jsonb(row.action),
            )
        }
        return defaults.size + scenarioRows.size
    }

    private data class EventRowToInsert(
        val target: String,
        val priority: Int,
        val condition: String,
        val action: String,
    )

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

    private fun jsonb(map: Map<String, Any?>): PGobject =
        jsonb(opensamguk.infra.persistence.MetaJson.encode(map))

    /** PHP `Util::round` half-away-from-zero (NOT Math.round / banker's rounding). */
    private fun phpRoundHalfAway(v: Double): Int =
        if (v >= 0) Math.floor(v + 0.5).toInt() else Math.ceil(v - 0.5).toInt()

    companion object {
        /** GeneralAi/reserved ring capacity (= common GameConst.maxTurn). */
        const val MAX_GENERAL_TURNS = 30

        /** Nation/chief ring capacity (= common GameConst.maxChiefTurn). */
        const val MAX_CHIEF_TURNS = 12

        /** 시드 시작 월 — insertWorldState가 current_month=1로 고정 시드하므로 killturn 파생도 월1 기준. */
        const val SEED_START_MONTH = 1

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
