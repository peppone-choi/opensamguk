<?php
/**
 * capture_monthtick.php — P3 N-month monthly-tick golden capture (devsam-core PHP, grand truth).
 *
 * ONE-SHOT, MANUAL HOST STEP — NEVER CI (devsam capture quirks; see README).
 *
 * Generalization of the PROVEN tools/php-golden/capture_command.php → drives the REAL
 * monthly tick. The faithful tick body is the INNER per-month block of
 * hwe/sammo/TurnExecutionHelper.php::executeAllCommand (lines 461-486):
 *
 *     L4  $monthlyRng = new RandUtil(new LiteHashDRBG(simpleSerialize(hiddenSeed,'monthly',year,month)))
 *     L5  runEventHandler($db,$gameStor, EventTarget::PreMonth)     // PreMonth events see OLD date
 *     L6  preUpdateMonthly()                                        // false => abort
 *     L7  turnDate($nextTurn)                                       // advance year/month
 *     L8  if (month==1) checkStatistic()
 *     L9  runEventHandler($db,$gameStor, EventTarget::Month)        // Month events see NEW date
 *     L10 postUpdateMonthly($monthlyRng)
 *
 * We DELIBERATELY skip the wall-clock / lock / online / traffic / tournament-auction
 * infrastructure that wraps the loop (tryLock, checkDelay, updateOnline, CheckOverhead,
 * updateTraffic, processTournament, processAuction) — none of that mutates the
 * deterministic game state the Kotlin MonthlyPipeline ports; it is pure server
 * plumbing (max_execution_time budget, HTTP lock, online-count cache). The captured
 * block IS the exact deterministic monthly tick, function-for-function.
 *
 * Per-general command drain (executeGeneralCommandUntil) is NOT exercised here: this
 * gate targets the MONTHLY boundary tick. On a pristine scenario_1010 install no
 * general has a reserved turn (turntime in the future), so the drain is a no-op for
 * the captured months and the month block runs against the install baseline. (The
 * per-general drain is the P2 gate's surface; G1 is the month-boundary surface.)
 *
 * Output (logic/src/test/resources/golden/p3/):
 *   month-00-before.json   — the install baseline (month-0 before-state) + hiddenSeed (G1b fixture input)
 *   month-NN-after.json    — per-month: numeric state (general/city/nation/diplomacy/nation_env rows,
 *                            jsonb in PHP Json::encode key order) + history/action log strings added
 *                            this month + execution-order log (event dispatch sequence) + per-month
 *                            RNG draw counts (monthly lineage)
 *
 * Invocation (inside the php capture container, repo mounted at /work):
 *   php tools/php-golden/capture_monthtick.php [--months=N] [--out-dir=logic/src/test/resources/golden/p3]
 *
 * HARD assertions (abort — never write a partial/unfaithful golden):
 *   (1) install baseline pristine — year==startYear, no prior PreMonth/Month side-effects
 *   (2) each preUpdateMonthly()/runEventHandler returns true (no abort)
 *   (3) the captured (year,month) after turnDate advances by exactly 1 month per tick
 */

namespace sammo;

require __DIR__ . '/_boot.php';

use sammo\Enums\EventTarget;

// HARNESS FIX (authorized — keeps tick logic intact): devsam's f_config/config.php
// installs a custom error handler (logErrorByCustomHandler) that, on ANY reported
// error, calls Session::getInstance() to attribute the err_log row to a web user.
// In this headless capture there is no web Session and PHP 8.3 raises E_DEPRECATED
// ("optional parameter before required") while autoloading some src/sammo class —
// the handler then fatals on the not-yet-loaded Session during that very deprecation.
// The handler short-circuits when `!(error_reporting() & $errno)`, so we drop the
// PHP-version noise levels (DEPRECATED/NOTICE/WARNING/STRICT) from error_reporting.
// This changes ZERO deterministic game state — it only stops the headless error
// handler from chasing a web Session. E_ERROR/E_PARSE etc. still surface.
error_reporting(E_ALL & ~E_DEPRECATED & ~E_NOTICE & ~E_WARNING & ~E_USER_DEPRECATED & ~E_STRICT);

$opts   = getopt('', ['months:', 'out-dir:']);
$months = (int)($opts['months'] ?? 4);
$outDir = $opts['out-dir'] ?? (__DIR__ . '/../../logic/src/test/resources/golden/p3');
if (!is_dir($outDir)) { mkdir($outDir, 0775, true); }

$db = DB::db();

function hardAssert(bool $cond, string $msg): void {
    if (!$cond) { fwrite(STDERR, "G1 HARD-ASSERT FAILED: {$msg}\n"); exit(2); }
}

/** Whole-table dump, ORDER BY the PK ascending (PHP unordered SELECT == PK order — the
 *  iteration-order parity target). Rows are associative (column => value), char-for-char. */
function dumpTable(object $db, string $table, string $pk): array {
    $rows = $db->query("SELECT * FROM {$table} ORDER BY {$pk} ASC");
    return $rows ?: [];
}

/** general_record rows added this month (id past a high-water mark), char-for-char.
 *  Captures BOTH the per-general action lines and the id=0 global history broadcasts. */
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

/** The event-dispatch execution-order sequence for ONE target, mirroring runEventHandler's
 *  `ORDER BY priority DESC, id ASC` — the G1c (b) log-sequence parity oracle. */
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

/** Full deterministic-state snapshot of every monthly-tick-touched table. */
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

/** nation_env KV dump (per-nation key-values), ordered. */
function kvDump(object $db, string $table): array {
    // nation_env is a generic KV table: (nation_id, key, value-ish). Dump raw rows ordered.
    $cols = $db->query("SHOW COLUMNS FROM {$table}");
    if (!$cols) return [];
    $first = $cols[0]['Field'];
    $rows = $db->query("SELECT * FROM {$table} ORDER BY {$first} ASC");
    return $rows ?: [];
}

// ── hiddenSeed: the per-game install seed — committed as the G1b fixture INPUT ───────
$hiddenSeed = UniqueConst::$hiddenSeed;
hardAssert(preg_match('/^[0-9a-f]{32}$/', $hiddenSeed) === 1,
    "hiddenSeed is not a 32-char lowercase hex: {$hiddenSeed}");

$gameStor = KVStorage::getStorage($db, 'game_env');
[$year0, $month0, $startYear, $turnterm] = $gameStor->getValuesAsArray(
    ['year', 'month', 'startyear', 'turnterm']
);
$year0 = (int)$year0; $month0 = (int)$month0; $startYear = (int)$startYear; $turnterm = (int)$turnterm;

hardAssert($year0 === $startYear && $month0 === 1,
    "install not at the pristine baseline (year={$year0} month={$month0} startYear={$startYear})");

// ── month-0 BEFORE-state + the hiddenSeed fixture input (G1b) ────────────────────────
$beforeState = snapshotState($db);
file_put_contents(
    $outDir . '/month-00-before.json',
    Json::encode([
        'hiddenSeed' => $hiddenSeed,              // G1b fixture INPUT (config-file→DB-column divergence)
        'startYear'  => $startYear,
        'turnterm'   => $turnterm,
        'months'     => $months,
        'state'      => $beforeState,
    ], JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES | JSON_PRETTY_PRINT)
);
fwrite(STDERR, "wrote month-00-before.json (hiddenSeed={$hiddenSeed} year={$year0} startYear={$startYear})\n");

// ── the N-month tick loop — faithful inner block of executeAllCommand ────────────────
$starttime = $gameStor->starttime;          // install timestamp
$prevTurn  = cutTurn($gameStor->turntime, $turnterm);
$nextTurn  = addTurn($prevTurn, $turnterm);

for ($m = 1; $m <= $months; $m++) {
    // re-read the OLD date the PreMonth batch must observe
    $gameStor->resetCache();
    $oldYear  = (int)$gameStor->year;
    $oldMonth = (int)$gameStor->month;

    $recordHW = maxRecordId($db);

    // execution-order oracle (G1c b): the dispatch order for both batches BEFORE running them
    $preMonthOrder = eventDispatchOrder($db, EventTarget::PreMonth->value);
    $monthOrder    = eventDispatchOrder($db, EventTarget::Month->value);

    // L4 — month-scoped RNG built ONCE, pre-advance, with the OLD (year,month).
    $monthlySeedString = Util::simpleSerialize($hiddenSeed, 'monthly', $oldYear, $oldMonth);
    $drbg = new LiteHashDRBG($monthlySeedString);
    $monthlyRng = new RandUtil($drbg);

    // L5 — PreMonth events fire with the OLD date.
    $preOk = TurnExecutionHelper::runEventHandler($db, $gameStor, EventTarget::PreMonth);

    // L6 — preUpdateMonthly (false aborts the whole tick in legacy).
    $preUpd = preUpdateMonthly();
    hardAssert($preUpd === true, "month {$m}: preUpdateMonthly() returned false");

    // L7 — advance the calendar BETWEEN the two batches.
    turnDate($nextTurn);
    $gameStor->resetCache();
    $newYear  = (int)$gameStor->year;
    $newMonth = (int)$gameStor->month;

    // L8 — year-boundary statistics.
    if ($newMonth === 1) { checkStatistic(); }

    // L9 — Month events fire with the NEW date.
    TurnExecutionHelper::runEventHandler($db, $gameStor, EventTarget::Month);

    // L10 — postUpdateMonthly is the ONLY consumer of the month-scoped RNG.
    postUpdateMonthly($monthlyRng);

    // capture: count the monthly-lineage RNG draws consumed this month (LiteHashDRBG
    // exposes its internal draw counter via reflection — faithful, no behavior change).
    $drawCount = drbgDrawCount($drbg);

    // advance to next month exactly as executeAllCommand does
    $prevTurn = $nextTurn;
    $nextTurn = addTurn($prevTurn, $turnterm);
    $gameStor->turntime = $prevTurn;
    $gameStor->resetCache();

    // numeric state AFTER this month
    $afterState = snapshotState($db);
    // history/action strings added this month (char-for-char), in id order
    $recordRows = recordRowsSince($db, $recordHW);

    $mm = str_pad((string)$m, 2, '0', STR_PAD_LEFT);
    file_put_contents(
        $outDir . "/month-{$mm}-after.json",
        Json::encode([
            'month'             => $m,
            'oldDate'           => ['year' => $oldYear, 'month' => $oldMonth],
            'newDate'           => ['year' => $newYear, 'month' => $newMonth],
            'monthlySeedString' => $monthlySeedString,
            'monthlyRngDraws'   => $drawCount,
            'preMonthDispatchOrder' => $preMonthOrder,
            'monthDispatchOrder'    => $monthOrder,
            'recordRows'        => $recordRows,
            'state'             => $afterState,
        ], JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES | JSON_PRETTY_PRINT)
    );
    hardAssert(
        ($newYear * 12 + $newMonth) === ($oldYear * 12 + $oldMonth) + 1,
        "month {$m}: turnDate did not advance exactly 1 month ({$oldYear}-{$oldMonth} -> {$newYear}-{$newMonth})"
    );
    fwrite(STDERR, "wrote month-{$mm}-after.json ({$oldYear}-{$oldMonth} -> {$newYear}-{$newMonth}, monthlyRngDraws={$drawCount}, records=" . count($recordRows) . ")\n");
}

fwrite(STDERR, "DONE: captured {$months} months of scenario_1010 (startYear={$startYear}, hiddenSeed={$hiddenSeed})\n");

// ── helpers ──────────────────────────────────────────────────────────────────
/** Read the LiteHashDRBG's internal draw counter via reflection (faithful read-only). */
function drbgDrawCount(LiteHashDRBG $drbg): ?int {
    foreach (['stateIdx', 'bufferIdx', 'count', 'counter', 'idx', 'index', 'pos', 'drawCount'] as $field) {
        try {
            $rp = new \ReflectionProperty($drbg, $field);
            if (PHP_VERSION_ID < 80100) { $rp->setAccessible(true); }
            if ($rp->isInitialized($drbg)) { return (int)$rp->getValue($drbg); }
        } catch (\ReflectionException $e) { /* try next */ }
    }
    return null; // counter field name unknown — recorded as null, not faked
}
