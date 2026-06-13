package opensamguk.logic.domain

/**
 * General as the logic layer sees it. `intel` (not intelligence) matches the DB column.
 *
 * P2 expansion (Task FD0): adds the military/equip surface that the develop/military/trade
 * resolvers consume — crew/train/atmos/crewTypeId/troop + the four equip slots (horse/weapon/
 * book/item, the V1 `*_code` columns) + `npcType` (V1 `npc_state`) + `lastTurn` (the
 * `general.last_turn` jsonb, the general-command `setResultTurn` target).
 *
 * `leadershipExp`/`strengthExp`/`intelExp`/`dedlevel`/`explevel`/`aux` are NOT data-class fields —
 * they ride the `meta` jsonb (accessors in [GeneralMeta]; verified against `V1__baseline.sql`:
 * the `general` table has crew/crew_type_id/train/atmos/weapon_code/book_code/horse_code/item_code/
 * last_turn/personal_code columns but NO leadership_exp/strength_exp/dedlevel/intel_exp columns —
 * consistent with P1's intel_exp/explevel already on meta).
 */
data class General(
    val id: Int,
    val nationId: Int,
    val cityId: Int,
    val leadership: Int,
    val strength: Int,
    val intel: Int,
    val injury: Int,
    val experience: Double,   // raw accumulator (PHP increaseVar adds float, no per-add round); truncated → int only at flush (D1)
    val dedication: Double,   // same — see C2 resolve + D1 General row mapper
    val officerLevel: Int,
    val gold: Int,
    val rice: Int,
    // --- P2 military / unit surface (V1 general columns) ---
    val crew: Int = 0,            // V1 general.crew
    val train: Double = 0.0,      // V1 general.train (float math: train/100 etc.)
    val atmos: Double = 0.0,      // V1 general.atmos
    val crewTypeId: Int = 0,      // V1 general.crew_type_id
    val troop: Int = 0,           // V1 general.troop_id (troop membership)
    // --- P2 equip slots (V1 general.*_code, default 'None') ---
    val horse: String = "None",   // V1 general.horse_code
    val weapon: String = "None",  // V1 general.weapon_code
    val book: String = "None",    // V1 general.book_code
    val item: String = "None",    // V1 general.item_code
    // --- P2 npc / last-turn ---
    val npcType: Int = 0,         // V1 general.npc_state (NPCType taxonomy 0/1/2/3/5/6/9 — see NpcType)
    // --- P4 war/conquest surface (Task FU3) ---
    val officerCity: Int = 0,     // V1 general.officer_city — the city a 태수/군사/종사 governs; ConquerCity resets to 0 + officer_level 1 (process_war.php:705-708)
    val lastTurn: LastTurn = LastTurn(),  // rides the general.last_turn jsonb (delete-on-default)
    val userId: String? = null,    // V1 general.user_id — B2 possession owner; nullable for unclaimed/NPC rows.
    val penalty: Map<String, Any?> = linkedMapOf(), // V1 general.penalty jsonb, separate from meta.
    val meta: Map<String, Any?> = linkedMapOf(),   // explevel, intel_exp, leadership_exp, strength_exp, dedlevel, aux, max_domestic_critical, killturn …
    // --- DIVERGENCE (1.0.0+ 독자기능, 레거시 devsam/core에 없음) ---
    // 정치(politics)/매력(charm): 오픈삼국 5스탯 확장. PHP 골든 오라클 없음 → 패러티 경로(leadership/strength/intel
    // getStatValue·battle·draw)에는 절대 주입하지 않는다. 기본 0으로 inert; 값 부여·UI는 후속 바퀴(W2/W4).
    val politics: Int = 0,        // 정치 (divergence)
    val charm: Int = 0,           // 매력 (divergence)
)

/**
 * City — comm/agri/supply_state/front_state/trust align to DB; P2 adds secu/def/wall/pop (+each _max),
 * trade (95..105 or null) and region.
 *
 * There is NO `city.tech` — tech is a NATION stat (`V1 nation.tech double precision`); names align to
 * the V1 city columns secu/secu_max/def/def_max/wall/wall_max/pop/pop_max/trade/region.
 */
data class City(
    val id: Int,
    val nationId: Int,
    val level: Int,
    val commerce: Int, val commerceMax: Int,
    val agriculture: Int, val agricultureMax: Int,
    val supplyState: Int,           // truthy = supplied
    val frontState: Int,            // 1|3 = front (debuff)
    val trust: Double,              // PHP schema.sql:202 trust FLOAT; che math uses trust/100.0 & trust/80.0 — port faithfully as Double
    // --- P2 develop / defense surface (V1 city columns) ---
    val security: Int = 0, val securityMax: Int = 0,        // V1 secu / secu_max
    val defense: Int = 0, val defenseMax: Int = 0,          // V1 def / def_max
    val wall: Int = 0, val wallMax: Int = 0,                // V1 wall / wall_max
    val population: Int = 0, val populationMax: Int = 0,     // V1 pop / pop_max
    val dead: Int = 0,              // PHP city.dead — accumulated battle dead (부상병); ProcessWarIncome reads dead/10 then resets to 0
    val trade: Int? = null,         // V1 city.trade — 95..105, or null (no-trade / disabled)
    val region: Int = 0,            // V1 city.region
    // --- P4 war/conquest surface (Task FU3) ---
    val term: Int = 0,              // V1 city.term — owner-tenure turn counter; ConquerCity resets to 0 (process_war.php:779)
    val officerSet: Int = 0,        // V1 city.officer_set — officer-assignment seq; ConquerCity resets to 0 (process_war.php:785)
    val conflict: String = "{}",    // V1 city.conflict jsonb — the nation→Double ConflictMap (A4 writes; insertion-ordered JSON string, byte-faithful); ConquerCity resets to '{}' (process_war.php:780)
    val meta: Map<String, Any?> = linkedMapOf(),
)

/**
 * Nation — full P2 shape. V1 nation columns: id/name/color/capital_city_id/gold/rice/tech/level/type_code/meta.
 * `gennum`/`capset` ride the `meta` jsonb (no dedicated columns); alongside rate/bill/surlimit/secretlimit/
 * strategicCmdLimit/aux. `capset` is the term-stack sequence (감축/증축/천도 bump it — see TermStack).
 */
data class Nation(
    val id: Int,
    val level: Int,
    val capitalCityId: Int?,
    val name: String = "",
    val color: String = "",
    val typeCode: String = "che_중립",   // V1 nation.type_code default
    val gold: Int = 0,
    val rice: Int = 0,
    val power: Int = 0,
    val tech: Double = 0.0,
    val gennum: Int = 0,                  // rides meta
    val capset: Int = 0,                  // rides meta (term-stack seq)
    val meta: Map<String, Any?> = linkedMapOf(),   // rate/bill/surlimit/secretlimit/strategicCmdLimit/aux + gennum/capset source
)

/** World env read by cost/debuff math. */
data class WorldEnv(val year: Int, val startYear: Int, val develCost: Int) {
    val relYear: Int get() = year - startYear
}
