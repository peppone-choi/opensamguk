<?php
/**
 * capture_conquercity.php — P4 G1c conquest golden capture (devsam-core PHP, grand truth).
 *
 * ONE-SHOT, MANUAL HOST STEP — NEVER CI (devsam capture quirks; see README).
 *
 * Drives a REAL processWar()→ConquerCity against installed scenario_1010 to a city fall,
 * and a standalone ConflictMap fixture. Emits the oracle the Kotlin B2 (ConquerCity) +
 * A4 (ConflictMap) ports must reproduce as ChangeRecorder flush DELTAS.
 *
 * DETERMINISM: install draws a fresh random hiddenSeed; we PIN UniqueConst::$hiddenSeed to
 * the plan's fixed live value (8ebfeb6fa932a181ec9ef43b7473f4c9) at the top so the warSeed
 * AND both ConquerCity seed strings (simpleSerialize(hiddenSeed,'ConquerCity',y,m,…)) are
 * fully reproducible regardless of the install's random seed. The hiddenSeed is a fixed
 * per-game config value (d_setting/UniqueConst.php::$hiddenSeed) — pinning it is faithful,
 * and the plan specifies this exact hex as the golden fixture INPUT.
 *
 * SETUP IS A LEGITIMATE GAME STATE: each branch weakens the target city's def/wall (a
 * state a heavily-damaged city legitimately reaches) and mobilizes a real attacker general
 * with crew (the simulator/che_출병 path), then calls the UNMODIFIED processWar(). We never
 * touch process_war.php. The capture is byte-stable because re-running re-installs a fresh
 * DB and re-applies the identical deterministic setup → identical delta.
 *
 * DB-DIFF-AS-DELTA: we snapshot every conquest-touched table (nation/city/general/
 * diplomacy) BEFORE and AFTER processWar, then emit created/updated/deleted ROW deltas —
 * the exact oracle the Kotlin ChangeRecorder must produce (no inline DB write on the
 * Kotlin side).
 *
 * Branches captured:
 *   conquercity-survive-01.json  — non-capital city falls, defender nation survives
 *                                  (officer demote + city reset + SetNationFront)
 *   conquercity-capital-01.json  — defender CAPITAL falls, nation survives → 긴급천도
 *                                  (findNextCapital BFS-ring + capital move + atmos×0.8)
 *   conflict-01.json             — a 2-3-nation siege ConflictMap arsort winner fixture
 *                                  (addConflict ×1.05 선타/막타, getConquerNation=firstKey,
 *                                   deleteConflict) — NO RNG, pure arithmetic+sort
 *
 * The COLLAPSE branch (cityCount==1 → DestroyNation + the collapse per-general draw
 * sub-stream at process_war.php:589-664) is documented as a gap in p4-capture-backlog.md:
 * its draws come off a LOCAL `$rng` created inside ConquerCity() (:549,:589) that cannot
 * be wrapped with the recorder without editing process_war.php (grand truth — must not
 * alter). The seed STRINGS are captured (computed identically); the sub-stream needs the
 * B2 standalone-replay harness. The two NON-collapse branches DO exercise the :599
 * onArbitraryAction defender loop + the full side-effect order + both seed strings.
 *
 * Invocation (inside the php container, repo mounted at /work):
 *   php tools/php-golden/capture_conquercity.php [--out-dir=logic/src/test/resources/golden/p4]
 *   NOTE: re-installs scenario_1010 itself (per branch) — pass nothing else.
 */

namespace sammo;

require __DIR__ . '/_boot.php';
require __DIR__ . '/RandUtilDrawRecorder.php';

error_reporting(E_ALL & ~E_DEPRECATED & ~E_NOTICE & ~E_WARNING & ~E_USER_DEPRECATED & ~E_STRICT);

// PIN the hiddenSeed (the plan's fixed live config value) BEFORE any seed derivation.
$PINNED_HIDDEN_SEED = '8ebfeb6fa932a181ec9ef43b7473f4c9';
UniqueConst::$hiddenSeed = $PINNED_HIDDEN_SEED;

$opts   = getopt('', ['out-dir:']);
$outDir = $opts['out-dir'] ?? (__DIR__ . '/../../logic/src/test/resources/golden/p4');
if (!is_dir($outDir)) { mkdir($outDir, 0775, true); }

$db = DB::db();

function hardAssert(bool $cond, string $msg): void {
    if (!$cond) { fwrite(STDERR, "G1c HARD-ASSERT FAILED: {$msg}\n"); exit(2); }
}

/** Re-install scenario_1010 fresh (deterministic baseline) + re-pin the hiddenSeed. */
function freshInstall(object $db, string $pinnedHidden): void {
    // Mirror install_scenario.php's RootDB binding + member table + buildScenario.
    $DEVSAM_HWE = realpath(__DIR__ . '/../../legacy/devsam-core/hwe');
    $rootOrig = file_get_contents(realpath($DEVSAM_HWE . '/..') . '/f_install/templates/RootDB.orig.php');
    $rootBound = strtr($rootOrig, [
        '_tK_host_'       => getenv('SAMMO_DB_HOST') ?: 'devsam-golden-db',
        '_tK_user_'       => getenv('SAMMO_DB_USER') ?: 'root',
        '_tK_password_'   => getenv('SAMMO_DB_PASS') ?: 'rootpw',
        '_tK_dbName_'     => getenv('SAMMO_DB_NAME') ?: 'samdb',
        '_tK_port_'       => (string)((int)(getenv('SAMMO_DB_PORT') ?: 3306)),
        '_tK_globalSalt_' => 'goldensalt',
    ]);
    file_put_contents($DEVSAM_HWE . '/d_setting/RootDB.php', $rootBound);
    $db->query('CREATE TABLE IF NOT EXISTS member (
        `no` INT PRIMARY KEY AUTO_INCREMENT, `name` VARCHAR(64) DEFAULT NULL,
        `grade` INT DEFAULT 0, `picture` VARCHAR(255) DEFAULT NULL, `imgsvr` INT DEFAULT 0
    ) DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin');

    $result = ResetHelper::buildScenario(120, 0, 1010, 0, 0, 0, 0, 1, false, 'full', TimeUtil::now(), null);
    hardAssert(($result['result'] ?? false) === true, 'buildScenario failed: ' . Json::encode($result));

    // Re-pin (buildScenario regenerates UniqueConst.php with a fresh random seed).
    UniqueConst::$hiddenSeed = $pinnedHidden;
    refreshNationStaticInfo();
}

/** Whole-table snapshot keyed by PK (assoc PK => row), for the before/after delta. */
function snapTable(object $db, string $table, string $pk): array {
    $rows = $db->query("SELECT * FROM {$table} ORDER BY {$pk} ASC");
    $out = [];
    foreach ($rows as $r) { $out[(string)$r[$pk]] = $r; }
    return $out;
}

/** created/updated/deleted row delta between two PK-keyed snapshots. */
function tableDelta(array $before, array $after): array {
    $created = []; $updated = []; $deleted = [];
    foreach ($after as $pk => $row) {
        if (!array_key_exists($pk, $before)) {
            $created[$pk] = $row;
        } elseif ($before[$pk] != $row) {
            $changed = [];
            foreach ($row as $col => $val) {
                $old = $before[$pk][$col] ?? null;
                if ((string)$old !== (string)$val) {
                    $changed[$col] = ['from' => $old, 'to' => $val];
                }
            }
            if ($changed) { $updated[$pk] = $changed; }
        }
    }
    foreach ($before as $pk => $row) {
        if (!array_key_exists($pk, $after)) { $deleted[$pk] = $row; }
    }
    return ['created' => $created, 'updated' => $updated, 'deleted' => $deleted];
}

function snapAll(object $db): array {
    return [
        'nation'    => snapTable($db, 'nation', 'nation'),
        'city'      => snapTable($db, 'city', 'city'),
        'general'   => snapTable($db, 'general', 'no'),
        'diplomacy' => snapTable($db, 'diplomacy', 'no'),
    ];
}

function deltaAll(array $before, array $after): array {
    return [
        'nation'    => tableDelta($before['nation'], $after['nation']),
        'city'      => tableDelta($before['city'], $after['city']),
        'general'   => tableDelta($before['general'], $after['general']),
        'diplomacy' => tableDelta($before['diplomacy'], $after['diplomacy']),
    ];
}

/** general_record rows added since a high-water mark (the conquest log oracle). */
function recordsSince(object $db, int $afterId): array {
    $rows = $db->query(
        'SELECT id, general_id, log_type, year, month, text FROM general_record WHERE id>%i ORDER BY id ASC',
        $afterId
    );
    return $rows ?: [];
}

/** Compute the two IDENTICAL ConquerCity seed strings (process_war.php:549 AND :589). */
function conquerCitySeeds(string $hidden, int $year, int $month, int $attNation, int $attId, int $cityId): array {
    $s = Util::simpleSerialize($hidden, 'ConquerCity', $year, $month, $attNation, $attId, $cityId);
    // Built TWICE identically → stream RESETS to idx 0 after the OccupyCity event.
    return ['seed1' => $s, 'seed2' => $s, 'identical' => true];
}

/**
 * Run one ConquerCity branch: fresh install, apply setup, snapshot, real processWar with
 * a FIXED warSeed, snapshot, emit delta + logs + both ConquerCity seed strings.
 */
function captureConquerBranch(
    object $db, string $hidden, string $outFile, string $label,
    int $attackerId, int $atkCrew, callable $setup, int $targetCityId
): void {
    global $PINNED_HIDDEN_SEED;
    freshInstall($db, $hidden);

    $gameStor = KVStorage::getStorage($db, 'game_env');
    [$startYear, $year, $month] = $gameStor->getValuesAsArray(['startyear', 'year', 'month']);
    $startYear = (int)$startYear; $year = (int)$year; $month = (int)$month;

    // Deterministic setup (mobilize attacker + weaken target). Faithful legit game state.
    $setup($db, $attackerId, $atkCrew, $targetCityId);

    $attacker = General::createObjFromDB($attackerId);
    $attackerNationId = $attacker->getNationID();
    $rawAttackerNation = $db->queryFirstRow(
        'SELECT nation,`level`,`name`,capital,gennum,tech,`type`,gold,rice FROM nation WHERE nation=%i',
        $attackerNationId
    );
    $rawDefenderCity = $db->queryFirstRow('SELECT * FROM city WHERE city=%i', $targetCityId);
    $defenderNationId = (int)$rawDefenderCity['nation'];

    // FIXED warSeed — derived exactly as che_출병.php (year/month from the install date).
    $pre = new LiteHashDRBG(Util::simpleSerialize($hidden, 'war', $year, $month, $attackerId, $targetCityId));
    $warSeed = bin2hex($pre->nextBytes(16));

    // The ConquerCity double-seed strings (computed identically to process_war.php:549/:589).
    $ccSeeds = conquerCitySeeds($hidden, $year, $month, $attackerNationId, $attackerId, $targetCityId);

    $before = snapAll($db);
    $recordHW = (int)$db->queryFirstField('SELECT COALESCE(MAX(id),0) FROM general_record');

    // ── the REAL, UNMODIFIED processWar → ConquerCity ──
    processWar($warSeed, $attacker, $rawAttackerNation, $rawDefenderCity);

    // che_출병.php:259 flushes the attacker logger via $general->applyDB($db) AFTER
    // processWar; that flush is what writes the attacker's 지배/점령/정복/공략 history lines
    // to general_record. We call it here so the conquest_records oracle includes them
    // (faithful — it is the next line the real command runs).
    $attacker->getLogger()->flush();

    $after = snapAll($db);
    $delta = deltaAll($before, $after);
    $records = recordsSince($db, $recordHW);

    $cityAfter = $after['city'][(string)$targetCityId] ?? null;
    hardAssert($cityAfter !== null, "target city {$targetCityId} vanished");
    $conquered = ((int)$cityAfter['nation'] !== $defenderNationId);
    hardAssert($conquered, "branch {$label}: city {$targetCityId} did NOT fall (still nation {$cityAfter['nation']})");

    $golden = [
        'label'      => $label,
        'hiddenSeed' => $hidden,                 // PINNED fixture INPUT
        'startYear'  => $startYear,
        'year'       => $year,
        'month'      => $month,
        'attackerId' => $attackerId,
        'attackerNationId' => $attackerNationId,
        'defenderNationId' => $defenderNationId,
        'targetCityId'     => $targetCityId,
        'warSeed'    => $warSeed,
        'conquerCitySeeds' => $ccSeeds,          // the DISTINCT double-seed (both strings)
        'db_delta'   => $delta,                  // the ChangeRecorder flush-delta oracle
        'conquest_records' => $records,          // 지배/긴급천도/정복/분쟁협상 log lines (byte-exact)
    ];

    file_put_contents(
        $outFile,
        Json::encode($golden, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES | JSON_PRETTY_PRINT)
    );

    $upd = count($delta['city']['updated']) + count($delta['nation']['updated']) + count($delta['general']['updated']);
    fwrite(STDERR, sprintf(
        "wrote %s (label=%s warSeed=%s ccSeed=%s cityUpd+nationUpd+genUpd=%d records=%d)\n",
        basename($outFile), $label, $warSeed, substr($ccSeeds['seed1'], 0, 24) . '…', $upd, count($records)
    ));
}

// ── CONFLICT-01 fixture (ConflictMap, NO RNG — pure arithmetic + arsort) ──────────────
// Drives WarUnitCity::addConflict on a city under siege by 2-3 nations, recording the
// stored conflict JSON (×1.05 선타/막타 floats, arsort-stable DESC), getConquerNation
// (=array_key_first), and deleteConflict. NO processWar — addConflict is a pure side
// effect. We synthesize the oppose nations by constructing WarUnitCity + setting oppose.
function captureConflictFixture(object $db, string $hidden, string $outFile): void
{
    global $PINNED_HIDDEN_SEED;
    freshInstall($db, $hidden);
    $gameStor = KVStorage::getStorage($db, 'game_env');
    [$startYear, $year, $month] = $gameStor->getValuesAsArray(['startyear', 'year', 'month']);
    $startYear = (int)$startYear; $year = (int)$year; $month = (int)$month;

    // A nation-2 city as the contested target; start with empty conflict.
    $targetCityId = 80; // 관도
    $db->update('city', ['conflict' => '{}'], 'city=%i', $targetCityId);
    $rawCity = $db->queryFirstRow('SELECT * FROM city WHERE city=%i', $targetCityId);

    // dummy RandUtil — WarUnitCity ctor takes it but addConflict makes NO draws.
    $rng = new RandUtil(new LiteHashDRBG('conflict-fixture'));
    $rawNation2 = $db->queryFirstRow('SELECT nation,`level`,`name`,capital,gennum,tech,`type`,gold,rice FROM nation WHERE nation=2');

    // Simulate 3 nations each landing a siege with a different `dead` total, in a fixed
    // order — the arsort tie-break + 선타(first)/막타(last,HP==0) ×1.05 bonus are the
    // parity targets. We build a fresh WarUnitCity per "attacker nation" sharing the same
    // raw conflict JSON (carried forward), exactly as repeated processWar siege rounds do.
    $sieges = [
        // [attackerNationId, deadInflicted, hpZeroAtEnd]
        [1, 3000, false],   // nation 1 lands 3000 (first → 선타 ×1.05)
        [3, 5000, false],   // nation 3 lands 5000 (larger → should win after sort)
        [1, 1000, false],   // nation 1 again (+1000 → arsort re-sort)
    ];

    $steps = [];
    $conflictJson = '{}';
    foreach ($sieges as $idx => [$natId, $dead, $hpZero]) {
        // Rebuild a WarUnitCity carrying the running conflict JSON + the running dead.
        $rawCity['conflict'] = $conflictJson;
        $city = new WarUnitCity($rng, $rawCity, $rawNation2, $year, $month, $startYear);
        // set oppose to a stub WarUnitGeneral of the attacking nation (only getNationVar
        // 'nation' is read by addConflict).
        $opposeNation = ['nation' => $natId, 'name' => "N{$natId}", 'capital' => 0, 'level' => 1,
            'gold' => 0, 'rice' => 10000, 'type' => GameConst::$neutralNationType, 'tech' => 0, 'gennum' => 1];
        // Drive dead into the city + HP to simulate the siege outcome.
        // addConflict reads $this->dead and $this->getHP(); we set them via reflection-free
        // public API: decreaseHP accumulates dead. Start HP high, decrease by `dead`.
        $cityReflect = new \ReflectionObject($city);
        $pDead = $cityReflect->getProperty('dead'); $pDead->setAccessible(true); $pDead->setValue($city, $dead);
        if ($hpZero) {
            $pHp = $cityReflect->getProperty('hp'); $pHp->setAccessible(true); $pHp->setValue($city, 0);
        }
        // oppose: a minimal stub exposing getNationVar('nation').
        $stubGeneral = General::createObjFromDB(
            (int)$db->queryFirstField('SELECT no FROM general WHERE nation=%i LIMIT 1', 1)
        );
        $stub = new WarUnitGeneral($rng, $stubGeneral, $opposeNation, true);
        $city->setOppose($stub);

        $newConflict = $city->addConflict();
        $conflictJson = $city->getVar('conflict');
        $steps[] = [
            'siegeIdx'     => $idx,
            'attackerNation' => $natId,
            'deadInflicted'  => $dead,
            'hpZero'         => $hpZero,
            'newConflict'    => $newConflict,
            'conflictAfter'  => Json::decode($conflictJson),
            'conflictJsonRaw' => $conflictJson,
        ];
    }

    // getConquerNation = array_key_first(arsort-sorted) — the winner.
    $winner = getConquerNation(['conflict' => $conflictJson]);

    // deleteConflict semantics: unset a nation key WITHOUT re-sorting (process_war.php:504).
    $afterDelete = Json::decode($conflictJson);
    $deleteNationId = 3;
    unset($afterDelete[$deleteNationId]);

    $golden = [
        'label'        => 'conflict_3nation_siege_arsort_winner',
        'targetCityId' => $targetCityId,
        'siege_steps'  => $steps,
        'final_conflict_json' => $conflictJson,
        'getConquerNation_winner' => $winner,
        'deleteConflict' => [
            'removedNation' => $deleteNationId,
            'result'        => $afterDelete,
            'note'          => 'unset(key) with NO re-sort (insertion order preserved on remaining keys)',
        ],
    ];

    file_put_contents(
        $outFile,
        Json::encode($golden, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES | JSON_PRETTY_PRINT)
    );
    fwrite(STDERR, "wrote " . basename($outFile) . " (winner=N{$winner} steps=" . count($steps) . ")\n");
}

// ── SETUP CLOSURES (deterministic, legit game state) ─────────────────────────────────

/**
 * Mobilize the attacker with crew; weaken the target city so HP=def*10 is breachable.
 * PINS the attacker's home city + turntime so the capture is byte-stable: buildScenario
 * places generals into cities via its OWN install RNG (seeded by the install's random
 * hiddenSeed BEFORE we re-pin), so 132's home city + recent_war timestamp vary per run.
 * The attacker's home city is the city that takes the 0.4 dead-split (process_war.php:120),
 * so it MUST be deterministic for the db_delta to be byte-stable.
 */
$ATTACKER_HOME_CITY = 17; // 계 (nation 1, a fixed friendly city)
$PINNED_TURNTIME    = '0181-01-01 00:00:00.000000';
$setupSurvive = function (object $db, int $attackerId, int $atkCrew, int $cityId)
    use ($ATTACKER_HOME_CITY, $PINNED_TURNTIME): void {
    $db->update('general', [
        'crew' => $atkCrew, 'crewtype' => 1100, 'train' => 100, 'atmos' => 100,
        'rice' => 99999, 'gold' => 99999, 'injury' => 0,
        'city' => $ATTACKER_HOME_CITY,   // PIN home city (the 0.4 dead-split target)
        'turntime' => $PINNED_TURNTIME,  // PIN turntime (recent_war + 진격 log <1>date</>)
    ], 'no=%i', $attackerId);
    // weaken city (a legit heavily-damaged state). def*10 = HP; keep def_max for the reset.
    $db->update('city', ['def' => 30, 'wall' => 30], 'city=%i', $cityId);
    // PIN the defender nation's chief generals (officer_level>=5) into the defender CAPITAL.
    // buildScenario places them via install-RNG, so their pre-천도 `city` varies per run; the
    // 긴급천도 branch UPDATEs `city` for nation officer_level>=5, surfacing the random `from`
    // city in the delta. Seating chiefs in their capital is the legit canonical pre-state and
    // makes the delta byte-stable. (No-op for the survive branch — that city isn't a capital.)
    $defNation = (int)($db->queryFirstField('SELECT nation FROM city WHERE city=%i', $cityId) ?: 0);
    if ($defNation) {
        $defCapital = (int)$db->queryFirstField('SELECT capital FROM nation WHERE nation=%i', $defNation);
        if ($defCapital) {
            $db->update('general', ['city' => $defCapital],
                'nation=%i AND officer_level>=5', $defNation);
        }
    }
    refreshNationStaticInfo();
};

// ── RUN ──────────────────────────────────────────────────────────────────────────────

// Survive branch: 조조(132, nation 1) takes 황건적 non-capital city 80(관도) → nation 2
// survives with 9 cities; officers demote, city resets, SetNationFront fires.
captureConquerBranch(
    $db, $PINNED_HIDDEN_SEED,
    $outDir . '/conquercity-survive-01.json', 'survive_noncapital_fall',
    132, 80000, $setupSurvive, 80
);

// Capital-fall branch: 조조 takes 황건적 CAPITAL city 1(업) → nation survives → 긴급천도
// (findNextCapital BFS-ring max-pop, capital move, gold/rice ×0.5, all generals atmos ×0.8).
captureConquerBranch(
    $db, $PINNED_HIDDEN_SEED,
    $outDir . '/conquercity-capital-01.json', 'capital_fall_emergency_move',
    132, 80000, $setupSurvive, 1
);

// Conflict fixture (no RNG): the arsort winner + ×1.05 선타/막타 + deleteConflict.
captureConflictFixture($db, $PINNED_HIDDEN_SEED, $outDir . '/conflict-01.json');

fwrite(STDERR, "DONE: conquest fixtures written to {$outDir}\n");
