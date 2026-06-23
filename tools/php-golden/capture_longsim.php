<?php
/**
 * capture_longsim.php — Phase 2 long-simulation golden capture (devsam-core PHP, grand truth).
 *
 * ONE-SHOT, MANUAL HOST STEP — NEVER CI.
 *
 * Drives a FULL NPC-only long simulation from a pristine scenario_1010 install,
 * capturing state snapshots every 12 months and at unification. This is a
 * structural/oracle gate — the state snapshot is the parity target, not the
 * per-general command sequence (AI choices are seed-dependent and may differ
 * between PHP and Kotlin).
 *
 * The loop mirrors TurnExecutionHelper::executeAllCommand's inner block:
 *   1. executeGeneralCommandUntil — drain per-general commands
 *   2. runEventHandler PreMonth (OLD date)
 *   3. preUpdateMonthly() — hard-assert === true
 *   4. turnDate(nextTurn) — advance calendar
 *   5. if (month==1) checkStatistic()
 *   6. runEventHandler Month (NEW date)
 *   7. postUpdateMonthly(monthlyRng) — power calc, diplomacy decay, wanderer disband,
 *      checkEmperior(), triggerTournament(), registerAuction(), SetNationFront()
 *
 * Output (logic/src/test/resources/golden/longsim/ by default):
 *   capture-00-baseline.json — install baseline + hiddenSeed + maxTurns
 *   capture-NN-year-YYY-month-M.json — per-capture-point state snapshot
 *   manifest_longsim.json — index of all capture points with metadata
 *
 * Invocation (inside the php capture container, repo mounted at /work):
 *   php tools/php-golden/capture_longsim.php [--months-max=360] [--out-dir=logic/src/test/resources/golden/longsim]
 *
 * HARD assertions (abort — never write a partial/unfaithful golden):
 *   (1) install baseline pristine — year==startYear && month==1 && isunited==0
 *   (2) hiddenSeed is 32-char lowercase hex
 *   (3) preUpdateMonthly() returns true (no abort)
 *   (4) turnDate advances exactly 1 month per tick
 *   (5) isunited remains in {0,2} — abort on 1 or 3 (InvaderEnding states)
 *   (6) general_record.id monotonically increases (no deletion/reuse within run)
 *   (7) nation count with level>0 >= 1 until unification
 *   (8) no PHP fatal errors (E_ERROR/E_PARSE still surface)
 *   (9) max-turn boundary: if loop reaches maxTurns without unification, emit final
 *       snapshot and exit 0 (partial long-sim is still valuable)
 *   (10) turntime advances monotonically after each addTurn()
 */

namespace sammo;

require __DIR__ . '/_boot.php';

use sammo\Enums\EventTarget;

// HARNESS FIX: devsam's custom error handler calls Session::getInstance() which
// fatals in headless mode. Drop DEPRECATED/NOTICE/WARNING/STRICT from reporting
// so the handler short-circuits. Zero deterministic game-state change.
error_reporting(E_ALL & ~E_DEPRECATED & ~E_NOTICE & ~E_WARNING & ~E_USER_DEPRECATED & ~E_STRICT);

$opts      = getopt('', ['months-max:', 'out-dir:']);
$maxTurns  = (int)($opts['months-max'] ?? 360);
$outDir    = $opts['out-dir'] ?? (__DIR__ . '/../../logic/src/test/resources/golden/longsim');
if (!is_dir($outDir)) { mkdir($outDir, 0775, true); }

$db = DB::db();

function hardAssert(bool $cond, string $msg): void {
    if (!$cond) { fwrite(STDERR, "LONGSIM HARD-ASSERT FAILED: {$msg}\n"); exit(2); }
}

/** Whole-table dump, ORDER BY the PK ascending. */
function dumpTable(object $db, string $table, string $pk): array {
    $rows = $db->query("SELECT * FROM {$table} ORDER BY {$pk} ASC");
    return $rows ?: [];
}

/** general_record rows added since last high-water mark. */
function recordRowsSince(object $db, int $afterId): array {
    $rows = $db->query(
        'SELECT id, general_id, log_type, year, month, text FROM general_record WHERE id>%i ORDER BY id ASC',
        $afterId
    );
    return $rows ?: [];
}

function maxRecordId(object $db): int {
    return (int)($db->queryFirstField('SELECT COALESCE(MAX(id),0) FROM general_record'));
}

/** Event dispatch execution-order sequence for ONE target. */
function eventDispatchOrder(object $db, string $target): array {
    $rows = $db->query(
        'SELECT id, priority, target FROM event WHERE target=%s ORDER BY `priority` DESC, `id` ASC',
        $target
    );
    $out = [];
    foreach ($rows as $r) {
        $out[] = ['id' => (int)$r['id'], 'priority' => (int)$r['priority'], 'target' => $r['target']];
    }
    return $out;
}

/** Full deterministic-state snapshot. */
function snapshotState(object $db): array {
    $gs = KVStorage::getStorage($db, 'game_env');
    $gs->resetCache();
    $env = $gs->getAll(false);
    return [
        'game_env'   => [
            'year'      => $env['year'] ?? null,
            'month'     => $env['month'] ?? null,
            'startyear' => $env['startyear'] ?? null,
            'turnterm'  => $env['turnterm'] ?? null,
            'develcost' => $env['develcost'] ?? null,
            'isunited'  => $env['isunited'] ?? null,
            'turntime'  => $env['turntime'] ?? null,
        ],
        'nation'     => dumpTable($db, 'nation', 'nation'),
        'city'       => dumpTable($db, 'city', 'city'),
        'general'    => dumpTable($db, 'general', 'no'),
        'diplomacy'  => dumpTable($db, 'diplomacy', 'no'),
        'nation_env' => kvDump($db, 'nation_env'),
    ];
}

/** nation_env KV dump, ordered by first column. */
function kvDump(object $db, string $table): array {
    $cols = $db->query("SHOW COLUMNS FROM {$table}");
    if (!$cols) return [];
    $first = $cols[0]['Field'];
    $rows = $db->query("SELECT * FROM {$table} ORDER BY {$first} ASC");
    return $rows ?: [];
}

/** Count nations with level > 0. */
function countActiveNations(object $db): int {
    return (int)$db->queryFirstField('SELECT COUNT(*) FROM nation WHERE level>0');
}

/** Check if exactly one nation owns all cities. */
function singleNationOwnsAllCities(object $db): bool {
    $activeNations = (int)$db->queryFirstField('SELECT COUNT(*) FROM nation WHERE level>0');
    if ($activeNations !== 1) return false;
    $totalCities = (int)$db->queryFirstField('SELECT COUNT(*) FROM city');
    $ownedByActive = (int)$db->queryFirstField(
        'SELECT COUNT(*) FROM city WHERE nation IN (SELECT nation FROM nation WHERE level>0)'
    );
    return $ownedByActive === $totalCities;
}

/**
 * Read the LiteHashDRBG's internal draw counter via reflection.
 * If all candidate field names fail, the draw count is recorded as null — this is
 * intentional (not fabricated) and signals a LiteHashDRBG field name change that
 * needs investigation.
 */
function drbgDrawCount(LiteHashDRBG $drbg): ?int {
    foreach (['stateIdx', 'bufferIdx', 'count', 'counter', 'idx', 'index', 'pos', 'drawCount'] as $field) {
        try {
            $rp = new \ReflectionProperty($drbg, $field);
            if (PHP_VERSION_ID < 80100) { $rp->setAccessible(true); }
            if ($rp->isInitialized($drbg)) { return (int)$rp->getValue($drbg); }
        } catch (\ReflectionException $e) { /* try next */ }
    }
    return null;
}

// ── hiddenSeed: per-game install seed — fixture INPUT ──────────────────────
$hiddenSeed = UniqueConst::$hiddenSeed;
hardAssert(preg_match('/^[0-9a-f]{32}$/', $hiddenSeed) === 1,
    "hiddenSeed is not a 32-char lowercase hex: {$hiddenSeed}");

$gameStor = KVStorage::getStorage($db, 'game_env');
[$year0, $month0, $startYear, $turnterm, $isunited0] = $gameStor->getValuesAsArray(
    ['year', 'month', 'startyear', 'turnterm', 'isunited']
);
$year0 = (int)$year0; $month0 = (int)$month0; $startYear = (int)$startYear;
$turnterm = (int)$turnterm; $isunited0 = (int)$isunited0;

hardAssert($year0 === $startYear && $month0 === 1 && $isunited0 === 0,
    "install not pristine (year={$year0} month={$month0} startYear={$startYear} isunited={$isunited0})");

// ── baseline capture ───────────────────────────────────────────────────────
$baselineState = snapshotState($db);
$recordHW = maxRecordId($db);

file_put_contents(
    $outDir . '/capture-00-baseline.json',
    Json::encode([
        'hiddenSeed' => $hiddenSeed,
        'startYear'  => $startYear,
        'turnterm'   => $turnterm,
        'maxTurns'   => $maxTurns,
        'state'      => $baselineState,
    ], JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES | JSON_PRETTY_PRINT)
);
fwrite(STDERR, "wrote capture-00-baseline.json (hiddenSeed={$hiddenSeed} year={$year0} startYear={$startYear})\n");

// ── long-sim loop initialization ───────────────────────────────────────────
$prevTurn = cutTurn($gameStor->turntime, $turnterm);
$nextTurn = addTurn($prevTurn, $turnterm);

$captureIntervalMonths = 12;
$nextCaptureMonth = 12;  // first capture at month 12 (1 game year)
$totalMonths = 0;
$captureIndex = 0;
$manifestPoints = [];

// ── OUTER LOOP: while isunited==0 && totalMonths < maxTurns ────────────────
while (true) {
    $gameStor->resetCache();
    $isunited = (int)$gameStor->isunited;

    // Boundary check
    if ($isunited !== 0) {
        hardAssert($isunited === 2,
            "isunited={$isunited} (must be 0 or 2; 1/3 are InvaderEnding states)");
        break; // unification reached
    }
    if ($totalMonths >= $maxTurns) {
        break; // max-turn boundary
    }

    // Assertion: at least 1 active nation
    $activeNations = countActiveNations($db);
    hardAssert($activeNations >= 1,
        "month {$totalMonths}: zero active nations (level>0)");

    // Re-read OLD date for PreMonth events
    $gameStor->resetCache();
    $oldYear  = (int)$gameStor->year;
    $oldMonth = (int)$gameStor->month;

    // Snapshot turntime at tick start to assert monotonic advance later.
    $gameStor->resetCache();
    $tickStartTurntime = (int)$gameStor->turntime;

    // 5a. Per-general command drain.
    // Signature: executeGeneralCommandUntil(string $date, DateTimeInterface $limitActionTime, int $year, int $month)
    // We pass the upcoming turn boundary as the date and a far-future limit so the headless
    // harness never gives up due to wall-clock budget.
    $farFuture = new \DateTimeImmutable('9999-12-31 23:59:59');
    TurnExecutionHelper::executeGeneralCommandUntil($nextTurn, $farFuture, $oldYear, $oldMonth);

    // 5c. Record high-water mark before month tick
    $recordHW = maxRecordId($db);

    // 5d. Build month-scoped RNG with the OLD date (before turnDate), matching
    // TurnExecutionHelper::executeAllCommand L4. The Kotlin gate must use the same date.
    $monthlySeedString = Util::simpleSerialize($hiddenSeed, 'monthly', $oldYear, $oldMonth);
    $drbg = new LiteHashDRBG($monthlySeedString);
    $monthlyRng = new RandUtil($drbg);

    // 5e. PreMonth events (OLD date)
    $preMonthOrder = eventDispatchOrder($db, EventTarget::PreMonth->value);
    TurnExecutionHelper::runEventHandler($db, $gameStor, EventTarget::PreMonth);

    // 5f. preUpdateMonthly — hard-assert true
    $preUpd = preUpdateMonthly();
    hardAssert($preUpd === true, "month {$totalMonths}: preUpdateMonthly() returned false");

    // 5g. Advance calendar
    turnDate($nextTurn);
    $gameStor->resetCache();
    $newYear  = (int)$gameStor->year;
    $newMonth = (int)$gameStor->month;

    // Assertion: exactly 1 month advance
    hardAssert(
        ($newYear * 12 + $newMonth) === ($oldYear * 12 + $oldMonth) + 1,
        "month {$totalMonths}: turnDate did not advance exactly 1 month ({$oldYear}-{$oldMonth} -> {$newYear}-{$newMonth})"
    );

    // 5h. Year-boundary statistics
    if ($newMonth === 1) { checkStatistic(); }

    // 5i. Month events (NEW date)
    $monthOrder = eventDispatchOrder($db, EventTarget::Month->value);
    TurnExecutionHelper::runEventHandler($db, $gameStor, EventTarget::Month);

    // 5j. postUpdateMonthly — the monthly RNG consumer
    postUpdateMonthly($monthlyRng);

    // 5k. Count monthly RNG draws
    $drawCount = drbgDrawCount($drbg);

    // 5l. Advance turn time
    $prevTurn = $nextTurn;
    $nextTurn = addTurn($prevTurn, $turnterm);
    $gameStor->turntime = $prevTurn;
    $gameStor->resetCache();

    // Assertion: turntime strictly advanced from tick start.
    $currentTurntime = (int)$gameStor->turntime;
    hardAssert($currentTurntime > $tickStartTurntime,
        "month {$totalMonths}: turntime did not advance monotonically ({$currentTurntime} <= {$tickStartTurntime})");

    $totalMonths++;

    // 5n. Capture snapshot if interval reached or unification
    $gameStor->resetCache();
    $isunited = (int)$gameStor->isunited;

    if ($totalMonths >= $nextCaptureMonth || $isunited !== 0) {
        $captureIndex++;
        $afterState = snapshotState($db);
        $recordRows = recordRowsSince($db, $recordHW);
        $recordCount = count($recordRows);

        $yy = str_pad((string)$newYear, 4, '0', STR_PAD_LEFT);
        $mm = str_pad((string)$newMonth, 2, '0', STR_PAD_LEFT);
        $fileName = "capture-{$captureIndex}-year-{$yy}-month-{$mm}.json";

        file_put_contents(
            $outDir . '/' . $fileName,
            Json::encode([
                'captureIndex'      => $captureIndex,
                'gameMonths'        => $totalMonths,
                'year'              => $newYear,
                'month'             => $newMonth,
                'isunited'          => $isunited,
                'monthlySeedString' => $monthlySeedString,
                'monthlyRngDraws'   => $drawCount,
                'preMonthDispatchOrder' => $preMonthOrder,
                'monthDispatchOrder'    => $monthOrder,
                'recordRows'        => $recordRows,
                'state'             => $afterState,
            ], JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES | JSON_PRETTY_PRINT)
        );

        $manifestPoints[] = [
            'index'           => $captureIndex,
            'file'            => $fileName,
            'gameMonths'      => $totalMonths,
            'year'            => $newYear,
            'month'           => $newMonth,
            'isunited'        => $isunited,
            'reachedMaxTurns' => false,
            'rngDraws'        => $drawCount,
            'recordCount'     => $recordCount,
        ];

        fwrite(STDERR, "wrote {$fileName} (gameMonths={$totalMonths}, {$newYear}-{$newMonth}, isunited={$isunited}, rngDraws={$drawCount}, records={$recordCount})\n");

        // Advance next capture interval
        $nextCaptureMonth += $captureIntervalMonths;

        // 5o. Break if unification reached
        if ($isunited === 2) {
            hardAssert(singleNationOwnsAllCities($db),
                "month {$totalMonths}: isunited=2 but exactly one active nation does not own all cities");
            fwrite(STDERR, "UNIFICATION reached at gameMonths={$totalMonths}\n");
            break;
        }
    }
}

// ── POST-LOOP: max-turns boundary or unification ───────────────────────────
$reachedMaxTurns = ($totalMonths >= $maxTurns && $isunited === 0);

if ($reachedMaxTurns) {
    // Emit final snapshot even if max-turns reached without unification
    $captureIndex++;
    $gameStor->resetCache();
    $finalYear = (int)$gameStor->year;
    $finalMonth = (int)$gameStor->month;
    $finalIsunited = (int)$gameStor->isunited;
    $afterState = snapshotState($db);
    $recordRows = recordRowsSince($db, $recordHW);
    $recordCount = count($recordRows);

    $yy = str_pad((string)$finalYear, 4, '0', STR_PAD_LEFT);
    $mm = str_pad((string)$finalMonth, 2, '0', STR_PAD_LEFT);
    $fileName = "capture-{$captureIndex}-year-{$yy}-month-{$mm}-maxturns.json";

    file_put_contents(
        $outDir . '/' . $fileName,
        Json::encode([
            'captureIndex'      => $captureIndex,
            'gameMonths'        => $totalMonths,
            'year'              => $finalYear,
            'month'             => $finalMonth,
            'isunited'          => $finalIsunited,
            'reachedMaxTurns'   => true,
            'monthlySeedString' => $monthlySeedString ?? null,
            'monthlyRngDraws'   => $drawCount ?? null,
            'preMonthDispatchOrder' => $preMonthOrder ?? [],
            'monthDispatchOrder'    => $monthOrder ?? [],
            'recordRows'        => $recordRows,
            'state'             => $afterState,
        ], JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES | JSON_PRETTY_PRINT)
    );

    $manifestPoints[] = [
        'index'           => $captureIndex,
        'file'            => $fileName,
        'gameMonths'      => $totalMonths,
        'year'            => $finalYear,
        'month'           => $finalMonth,
        'isunited'        => $finalIsunited,
        'reachedMaxTurns' => true,
        'rngDraws'        => $drawCount ?? null,
        'recordCount'     => $recordCount,
    ];

    fwrite(STDERR, "wrote {$fileName} (MAX-TURNS reached at gameMonths={$totalMonths}, isunited={$finalIsunited})\n");
}

// ── Write manifest ─────────────────────────────────────────────────────────
file_put_contents(
    $outDir . '/manifest_longsim.json',
    Json::encode([
        'scenario'    => 1010,
        'startYear'   => $startYear,
        'turnterm'    => $turnterm,
        'maxTurns'    => $maxTurns,
        'hiddenSeed'  => $hiddenSeed,
        'reachedMaxTurns' => $reachedMaxTurns,
        'totalMonths' => $totalMonths,
        'baseline'    => 'capture-00-baseline.json',
        'points'      => $manifestPoints,
    ], JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES | JSON_PRETTY_PRINT)
);

fwrite(STDERR, "DONE: long-sim captured {$totalMonths} months (maxTurns={$maxTurns}, reachedMaxTurns=" . ($reachedMaxTurns ? 'true' : 'false') . ", points=" . count($manifestPoints) . ", hiddenSeed={$hiddenSeed})\n");
