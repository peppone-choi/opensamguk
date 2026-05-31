<?php
/**
 * capture_ai_world.php — P5 GT1b: the scenario-1010 PRE-TURN WORLD SNAPSHOT (devsam-core PHP, grand truth).
 *
 * ONE-SHOT, MANUAL HOST STEP — NEVER CI (devsam capture quirks; see README).
 *
 * Companion to capture_ai.php (GT1). capture_ai.php banked, per due general, the ordered AI
 * draw stream + the chosen (action, args, reason) the LIVE GeneralAI produced — but it NEVER
 * serialized the GAME-ENTITY ROWS the live AI branched on (it read them straight off the live
 * install DB). The AiReplayGateTest therefore can only RE-ISSUE the recorded draws on a fresh
 * DRBG (proves the RNG kernel) — it cannot run the LIVE Kotlin AI over the real world and assert
 * it PULLS the same draws + picks the same command. That live-selection gate is BLOCKED on a
 * missing world fixture (the documented GAP-WORLD cluster).
 *
 * THIS tool closes GAP-WORLD: it dumps, for the SAME installed scenario-1010 DB at the SAME
 * pre-turn instant (year 181 month 1, BEFORE any autorun mutates state), a byte-identical
 * snapshot of every table/KV the GeneralAI READS, so the Kotlin replay can deserialize it into
 * an InMemoryTurnWorld and drive the live AiTurnAdapter draw-for-draw.
 *
 * What the GeneralAI reads (and therefore what we dump — FULL tables, all columns):
 *   - general      : SELECT * FROM general  (174 rows). createObjListFromDB(nation generals) +
 *                    getReservedTurn read EVERY column the stat fold / candidate sets branch on
 *                    (leadership/strength/intel + _exp, crew/train/atmos, gold/rice, city/troop,
 *                    officer_level, npc, killturn, recent_war, turntime, injury, dedication,
 *                    experience, items weapon/book/horse/item, personal/special/special2 + ages,
 *                    aux, last_turn, penalty, …). turntime is the due-ORDER key.
 *   - city         : SELECT * FROM city     (94 rows). 'SELECT * FROM city' (GeneralAI:99,3486)
 *                    + nation-city scans (region/secu/level/front/supply/def/wall/pop/agri/comm
 *                    + _max/trust/trade/state/conflict). NOTE: city ADJACENCY (path) is from
 *                    CityConst (compiled map), NOT a DB column — the Kotlin side uses its own
 *                    CityConst, so it is intentionally NOT in this snapshot.
 *   - nation       : SELECT * FROM nation   (2 rows). The ctor SELECTs a column subset + aux;
 *                    we dump every column (incl. tech/gold/rice/level/capital/capset/type/aux).
 *   - diplomacy    : SELECT * FROM diplomacy (2 rows). state/term per (me,you) drive dipState.
 *   - nation_env   : the per-nation KV the ctor cacheValues (npc_nation_policy, npc_general_policy,
 *                    prev_income_gold, prev_income_rice) + the lord-only KV (recv_assist,
 *                    resp_assist, resp_assist_try, last_attackable, …). Dumped VERBATIM (raw JSON
 *                    value strings) so the Kotlin replay decodes them with the SAME Json::decode.
 *   - game_env     : the storage 'game_env' KV the ctor reads via getAll(true) (year/month/
 *                    startyear/turnterm/develcost/opentime/turntime/scenario/autorun_user/…).
 *                    THIS is the env the per-general 'GeneralAI' seed (year,month) is built from.
 *   - general_turn : the turn_idx=0 reserved command per general (getReservedTurn(0)) — the
 *                    reservedTurn fed into chooseGeneralTurn.
 *   - nation_turn  : the turn_idx=0 reserved command per (nation,officer_level) — the
 *                    reservedTurn fed into chooseNationTurn for lords (officer_level>=5).
 *
 * PRE-TURN HONESTY: emitted against the INSTALLED, UN-ADVANCED DB (the values the AI READ),
 * NOT post-turn. We run NO autorun, NO processCommand — just SELECTs. The hiddenSeed is pinned
 * exactly as GT1 (manifest_ai.json) — this snapshot is only valid for that same install.
 *
 * Output (logic/src/test/resources/golden/p5/):
 *   world-1010.json — {meta{hiddenSeed,scenario,year,month,turnterm,startYear,counts{...}},
 *                      gameEnv{key->rawJsonString}, nations[], cities[], generals[], diplomacy[],
 *                      nationEnv[{nation,kv{key->rawJsonString}}], generalTurns[], nationTurns[]}
 *
 * Invocation (inside the php capture container, repo mounted at /work; scenario_1010 already
 * installed by install_scenario.php — the SAME install that produced manifest_ai.json):
 *   php tools/php-golden/capture_ai_world.php [--out-dir=logic/src/test/resources/golden/p5]
 *
 * BYTE-STABILITY: run TWICE and diff -rq — world-1010.json MUST be byte-identical across runs of
 * the SAME install (pure SELECT dump, insertion-order preserved, PK-ascending).
 */

namespace sammo;

require __DIR__ . '/_boot.php';

error_reporting(E_ALL & ~E_DEPRECATED & ~E_NOTICE & ~E_WARNING & ~E_USER_DEPRECATED & ~E_STRICT);

$opts   = getopt('', ['out-dir:']);
$outDir = $opts['out-dir'] ?? (__DIR__ . '/../../logic/src/test/resources/golden/p5');
if (!is_dir($outDir)) { mkdir($outDir, 0775, true); }

$db = DB::db();

function hardAssert(bool $cond, string $msg): void {
    if (!$cond) { fwrite(STDERR, "GT1b HARD-ASSERT FAILED: {$msg}\n"); exit(2); }
}

// ── 0) pin the install identity (must equal manifest_ai.json — the GT1 install) ──────────
$hiddenSeed = UniqueConst::$hiddenSeed;
hardAssert(preg_match('/^[0-9a-f]{32}$/', $hiddenSeed) === 1,
    "hiddenSeed is not 32-char lowercase hex (install scenario_1010 first): {$hiddenSeed}");

$gameStor = KVStorage::getStorage($db, 'game_env');
[$startYear, $year, $month, $turnterm, $develCost] = $gameStor->getValuesAsArray(
    ['startyear', 'year', 'month', 'turnterm', 'develcost']
);
hardAssert((int)$startYear > 0, "startYear not set — install scenario_1010 first");
hardAssert((int)$year === 181 && (int)$month === 1,
    "snapshot must be the PRE-TURN instant year=181 month=1 (got year={$year} month={$month}) — do NOT capture post-autorun");

/**
 * Dump every column of a table verbatim, PK-ascending. Columns whose name is in $jsonCols are
 * emitted as RAW JSON (decoded then re-held as a value) so the snapshot carries the structured
 * jsonb the AI's Json::decode sees; all other columns are kept as-is (int/string) exactly as the
 * driver returns. We KEEP the driver's string scalars (no numeric coercion) so a re-dump is
 * byte-stable and the Kotlin side parses with explicit types.
 */
function dumpTable($db, string $sql, array $jsonCols, array $args = []): array {
    $rows = $args ? $db->query($sql, ...$args) : $db->query($sql);
    $out = [];
    foreach ($rows as $r) {
        $row = [];
        foreach ($r as $col => $val) {
            if (in_array($col, $jsonCols, true)) {
                // RAW JSON column — decode to a structured value (faithful to Json::decode the AI uses).
                $row[$col] = Json::decode($val ?? 'null');
            } else {
                $row[$col] = $val;
            }
        }
        $out[] = $row;
    }
    return $out;
}

// ── 1) game_env KV — VERBATIM raw JSON value strings (insertion order preserved) ─────────
// We hold each value as a DECODED structure (the AI reads getAll(true) → decoded). Keys are
// emitted in `key` ASC for byte-stability; the Kotlin replay re-keys into its own env map.
$gameEnvRows = $db->query("SELECT `key`, `value` FROM storage WHERE namespace = 'game_env' ORDER BY `key` ASC");
$gameEnv = [];
foreach ($gameEnvRows as $r) {
    $gameEnv[$r['key']] = Json::decode($r['value']);
}

// ── 2) FULL nation table (PK ascending) ──────────────────────────────────────────────────
$nations = dumpTable(
    $db,
    'SELECT * FROM nation ORDER BY nation ASC',
    ['aux', 'spy'] // jsonb columns the AI decodes
);

// ── 3) FULL city table (PK ascending) ─────────────────────────────────────────────────────
$cities = dumpTable(
    $db,
    'SELECT * FROM city ORDER BY city ASC',
    ['conflict'] // jsonb column
);

// ── 4) FULL general table (PK ascending = the candidate-set order; turntime is the due key) ─
$generals = dumpTable(
    $db,
    'SELECT * FROM general ORDER BY `no` ASC',
    ['last_turn', 'aux', 'penalty'] // jsonb columns the AI / stat-fold decode
);

// ── 5) diplomacy (PK ascending) ────────────────────────────────────────────────────────────
$diplomacy = dumpTable($db, 'SELECT * FROM diplomacy ORDER BY `no` ASC', []);

// ── 6) per-nation nation_env KV — VERBATIM (decoded), per nation, key ASC ──────────────────
$nationEnv = [];
foreach ($nations as $n) {
    $nid = (int)$n['nation'];
    $kvRows = $db->query(
        "SELECT `key`, `value` FROM nation_env WHERE namespace = %i ORDER BY `key` ASC",
        $nid
    );
    $kv = [];
    foreach ($kvRows as $r) {
        $kv[$r['key']] = Json::decode($r['value']);
    }
    $nationEnv[] = ['nation' => $nid, 'kv' => (object)$kv];
}

// ── 7) reserved commands: general_turn / nation_turn at turn_idx=0 ─────────────────────────
// getReservedTurn(0) / chooseNationTurn read the turn_idx=0 row. arg is jsonb (Json::decode).
$generalTurns = dumpTable(
    $db,
    'SELECT general_id, turn_idx, action, arg FROM general_turn WHERE turn_idx = 0 ORDER BY general_id ASC',
    ['arg']
);
$nationTurns = dumpTable(
    $db,
    'SELECT nation_id, officer_level, turn_idx, action, arg FROM nation_turn WHERE turn_idx = 0 ORDER BY nation_id ASC, officer_level ASC',
    ['arg']
);

// ── 8) assemble + emit ─────────────────────────────────────────────────────────────────────
$counts = [
    'generals'     => count($generals),
    'cities'       => count($cities),
    'nations'      => count($nations),
    'diplomacy'    => count($diplomacy),
    'nationEnv'    => count($nationEnv),
    'generalTurns' => count($generalTurns),
    'nationTurns'  => count($nationTurns),
    'gameEnvKeys'  => count($gameEnv),
];

// Hard invariants pinning this snapshot to the GT1 install shape.
hardAssert($counts['generals'] === 174, "expected 174 generals, got {$counts['generals']} — wrong install");
hardAssert($counts['cities'] === 94, "expected 94 cities, got {$counts['cities']} — wrong install");
hardAssert($counts['nations'] === 2, "expected 2 nations, got {$counts['nations']} — wrong install");
hardAssert($counts['generalTurns'] === 174, "expected 174 turn_idx=0 general reserved commands, got {$counts['generalTurns']}");

$world = [
    '_doc' => 'P5 GT1b — scenario-1010 PRE-TURN world snapshot (year 181 month 1, the values the live GeneralAI READ before any autorun mutation). FULL general/city/nation/diplomacy tables + game_env/nation_env KV + turn_idx=0 reserved commands. Byte-identical across two capture_ai_world.php runs of the SAME install (hiddenSeed pinned to manifest_ai.json). Deserialize into InMemoryTurnWorld to drive the LIVE Kotlin AiTurnAdapter draw-for-draw against the GT1 ai-turn-NNN goldens. PRE-TURN, NOT post-turn. City adjacency (path) is from CityConst (compiled map), NOT this snapshot.',
    'meta' => [
        'scenario'   => 1010,
        'hiddenSeed' => $hiddenSeed,
        'startYear'  => (int)$startYear,
        'year'       => (int)$year,
        'month'      => (int)$month,
        'turnterm'   => (int)$turnterm,
        'develCost'  => (int)$develCost,
        'counts'     => $counts,
        'columnDoc'  => [
            'jsonbColumns' => [
                'nation'   => ['aux', 'spy'],
                'city'     => ['conflict'],
                'general'  => ['last_turn', 'aux', 'penalty'],
                'general_turn' => ['arg'],
                'nation_turn'  => ['arg'],
            ],
            'note' => 'jsonb columns are emitted DECODED (Json::decode) as structured values; all other columns are emitted verbatim as the driver returns them (string/int). KV values (gameEnv, nationEnv.kv) are decoded too — the AI reads getAll(true)/cacheValues decoded.',
        ],
    ],
    'gameEnv'      => (object)$gameEnv,
    'nations'      => $nations,
    'cities'       => $cities,
    'generals'     => $generals,
    'diplomacy'    => $diplomacy,
    'nationEnv'    => $nationEnv,
    'generalTurns' => $generalTurns,
    'nationTurns'  => $nationTurns,
];

$outFile = $outDir . '/world-1010.json';
file_put_contents(
    $outFile,
    Json::encode($world, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES | JSON_PRETTY_PRINT)
);

fwrite(STDERR, "=== capture_ai_world DONE ===\n");
fwrite(STDERR, sprintf(
    "world-1010.json: hiddenSeed=%s year=%d month=%d turnterm=%d | generals=%d cities=%d nations=%d diplomacy=%d nationEnv=%d generalTurns=%d nationTurns=%d gameEnvKeys=%d\n",
    $hiddenSeed, (int)$year, (int)$month, (int)$turnterm,
    $counts['generals'], $counts['cities'], $counts['nations'], $counts['diplomacy'],
    $counts['nationEnv'], $counts['generalTurns'], $counts['nationTurns'], $counts['gameEnvKeys']
));
fwrite(STDERR, sprintf("written to %s (%d bytes)\n", $outFile, filesize($outFile)));
