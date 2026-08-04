<?php
/**
 * capture_longsim.php — Phase 2 long-simulation golden capture (devsam-core PHP, grand truth).
 *
 * ONE-SHOT, MANUAL HOST STEP — NEVER CI.
 *
 * Drives a FULL NPC-only long simulation from a pristine scenario_1010 install,
 * capturing state snapshots every 12 months and at unification. This is a
 * structural/oracle gate. The two SQL ORDER BY RAND() choices are external
 * oracle inputs: a disposable-copy observer records their selected ids without
 * changing either query or result, and Kotlin replays that recorded stream
 * while keeping the LiteHashDRBG stream draw-for-draw exact.
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
 *                           plus the ordered SQL RAND selection stream
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

$opts      = getopt('', ['months-max:', 'out-dir:', 'expected-hidden-seed:', 'expected-turntime:']);
$maxTurns  = (int)($opts['months-max'] ?? 360);
$outDir    = $opts['out-dir'] ?? (__DIR__ . '/../../logic/src/test/resources/golden/longsim');
$expectedHiddenSeed = $opts['expected-hidden-seed'] ?? null;
$expectedTurntime = $opts['expected-turntime'] ?? null;
if (!is_dir($outDir)) { mkdir($outDir, 0775, true); }

$db = DB::db();
$longsimSqlRandSelections = [];
$longsimRandomImgwanPermutations = [];
$longsimPhaseDrains = [];
$longsimCompositePhase = 1;
$longsimCompositeBoundary = '';
$longsimHandledCommands = [];
$longsimHandledCommandContext = null;
$longsimHandledCommandSidecar = $outDir . '/handled-command-sidecar.jsonl';
file_put_contents($longsimHandledCommandSidecar, '');
$longsimActionStatus = 'blocked';
$longsimActionSuccess = false;
$longsimDomesticDecisionContext = null;
$longsimDomesticDecisions = [];
$longsimDomesticActorEnv = getenv('LONGSIM_DOMESTIC_ACTOR');
$longsimDomesticPhaseEnv = getenv('LONGSIM_DOMESTIC_PHASE');
$longsimDomesticActor = (int)($longsimDomesticActorEnv === false ? 36 : $longsimDomesticActorEnv);
$longsimDomesticPhase = (int)($longsimDomesticPhaseEnv === false ? 2 : $longsimDomesticPhaseEnv);
$longsimAmbientMtSeedEnv = getenv('LONGSIM_AMBIENT_MT_SEED');
$longsimAmbientMtSeed =
    ($longsimAmbientMtSeedEnv === false || $longsimAmbientMtSeedEnv === '')
        ? null
        : (int)$longsimAmbientMtSeedEnv;
if ($longsimAmbientMtSeed !== null) {
    mt_srand($longsimAmbientMtSeed);
}
$longsimKillturnTransitions = [];
$longsimDiplomacyIdentitySidecar = $outDir . '/diplomacy-identity-sidecar.jsonl';
file_put_contents($longsimDiplomacyIdentitySidecar, '');
$longsimDiplomacyIdentityTransitionCount = 0;

function readLongsimActorCitySnapshot(int $actorGeneralId): ?array {
    $db = DB::db();
    $cityId = $db->queryFirstField(
        'SELECT city FROM general WHERE no=%i',
        $actorGeneralId
    );
    if ($cityId === null) {
        return null;
    }
    $row = $db->queryFirstRow(
        'SELECT * FROM city WHERE city=%i',
        (int)$cityId
    );
    return $row ?: null;
}

function beginLongsimHandledCommand(int $actorGeneralId): void {
    global $longsimHandledCommandContext, $longsimActionStatus, $longsimActionSuccess;
    hardAssert(
        $longsimHandledCommandContext === null,
        'handled-command context already active'
    );
    $db = DB::db();
    $longsimHandledCommandContext = [
        'actorGeneralId' => $actorGeneralId,
        'cityBefore' => readLongsimActorCitySnapshot($actorGeneralId),
        'recordHighWater' => (int)$db->queryFirstField(
            'SELECT COALESCE(MAX(id),0) FROM general_record'
        ),
    ];
    $longsimActionStatus = 'blocked';
    $longsimActionSuccess = false;
}

function recordLongsimActionOutcome(bool $success, string $status): void {
    global $longsimActionStatus, $longsimActionSuccess;
    $longsimActionStatus = $status;
    $longsimActionSuccess = $success;
}

function recordLongsimKillturnTransition(
    int $generalId,
    ?int $from,
    int $to,
    string $provenance,
    string $family
): void {
    global $longsimKillturnTransitions;
    if ($from !== null && $from === $to) {
        return;
    }
    hardAssert(
        in_array($family, ['month-derived', 'execution-constant'], true),
        "unknown killturn transition family {$family} from {$provenance}"
    );
    $longsimKillturnTransitions[] = [
        'ordinal' => count($longsimKillturnTransitions),
        'generalId' => $generalId,
        'from' => $from,
        'to' => $to,
        'provenance' => $provenance,
        'family' => $family,
    ];
}

/**
 * The command drain owns a context until TurnExecutionHelper persists its next
 * turn. Capture its ordinal before that final append so an instrumented
 * founding/deletion transition stays associated with the exact phase action.
 */
function longsimDiplomacyIdentityContext(): array {
    global $longsimCompositePhase, $longsimCompositeBoundary;
    global $longsimHandledCommands, $longsimHandledCommandContext;
    return [
        'phase' => $longsimCompositePhase,
        'phaseBoundary' => $longsimCompositeBoundary,
        'handledCommandOrdinal' => $longsimHandledCommandContext === null
            ? null
            : count($longsimHandledCommands),
        'actorGeneralId' => $longsimHandledCommandContext['actorGeneralId'] ?? null,
    ];
}

/** Normalize actual MariaDB rows in the explicit query order (no ascending). */
function normalizeLongsimDiplomacyIdentityRows(array $rows): array {
    return array_values(array_map(
        static fn(array $row): array => [
            'no' => (int)$row['no'],
            // src/dest deliberately mirror the legacy diplomacy me/you columns.
            'src' => (int)$row['me'],
            'dest' => (int)$row['you'],
        ],
        $rows
    ));
}

/** Append one ordered JSONL event; the manifest records its byte hash and count. */
function appendLongsimDiplomacyIdentityTransition(array $entry): void {
    global $longsimDiplomacyIdentitySidecar, $longsimDiplomacyIdentityTransitionCount;
    $entry['seq'] = $longsimDiplomacyIdentityTransitionCount;
    $written = file_put_contents(
        $longsimDiplomacyIdentitySidecar,
        Json::encode($entry, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES) . "\n",
        FILE_APPEND
    );
    hardAssert($written !== false, 'cannot append diplomacy identity sidecar');
    $longsimDiplomacyIdentityTransitionCount++;
}

/**
 * Disposable-copy che_거병 instrumentation calls this after its real bulk
 * INSERT. `orderedExistingNationIds` comes from the original single
 * getAllNationStaticInfo() result, and `created` comes back from the live
 * diplomacy table with its MariaDB-assigned `no` values.
 */
function recordLongsimFoundNationDiplomacyIdentity(
    int $actorGeneralId,
    int $nationId,
    string $nationName,
    array $orderedExistingNationIds,
    array $createdRows
): void {
    global $longsimHandledCommandContext;
    hardAssert(
        $longsimHandledCommandContext !== null
            && $longsimHandledCommandContext['actorGeneralId'] === $actorGeneralId,
        "found-nation diplomacy context mismatch for actor {$actorGeneralId}"
    );
    appendLongsimDiplomacyIdentityTransition(longsimDiplomacyIdentityContext() + [
        'type' => 'create',
        'source' => 'che_거병',
        'provenance' => [
            'derivation' => 'instrumented che_거병 bulk diplomacy INSERT',
        ],
        'commandCode' => 'che_거병',
        'nation' => [
            'id' => $nationId,
            'name' => $nationName,
            'lordGeneralId' => $actorGeneralId,
            'applyDb' => false,
        ],
        'nationQueryOrder' => array_values(array_map('intval', $orderedExistingNationIds)),
        'created' => normalizeLongsimDiplomacyIdentityRows($createdRows),
    ]);
}

/**
 * Disposable-copy deleteNation() instrumentation calls this immediately before
 * its real diplomacy DELETE. The caller-derived provenance distinguishes
 * che_해산, succession failure, and conquest without replacing their behavior.
 */
function recordLongsimNationDeletionDiplomacyIdentity(
    int $lordGeneralId,
    int $nationId,
    string $nationName,
    bool $applyDb,
    array $provenance,
    array $deletedRows
): void {
    appendLongsimDiplomacyIdentityTransition(longsimDiplomacyIdentityContext() + [
        'type' => 'delete',
        'source' => 'deleteNation',
        'provenance' => $provenance,
        'nation' => [
            'id' => $nationId,
            'name' => $nationName,
            'lordGeneralId' => $lordGeneralId,
            'applyDb' => $applyDb,
        ],
        'deleted' => normalizeLongsimDiplomacyIdentityRows($deletedRows),
    ]);
}

function readLongsimDrbgCursor(RNG $rng): array {
    $cursor = [];
    foreach (['stateIdx', 'bufferIdx'] as $field) {
        $rp = new \ReflectionProperty($rng, $field);
        if (PHP_VERSION_ID < 80100) { $rp->setAccessible(true); }
        $cursor[$field] = (int)$rp->getValue($rng);
    }
    return $cursor;
}

function beginLongsimDomesticDecision(
    int $actorGeneralId,
    int $year,
    int $month,
    string $seedString,
    array $stats,
    array $city,
    array $rates,
    array $candidates
): void {
    global $longsimCompositePhase, $longsimCompositeBoundary;
    global $longsimDomesticDecisionContext, $longsimDomesticDecisions;
    global $longsimDomesticActor, $longsimDomesticPhase;
    if (
        $actorGeneralId !== $longsimDomesticActor
        || ($longsimDomesticPhase > 0 && $longsimCompositePhase !== $longsimDomesticPhase)
        || $longsimDomesticDecisions
        || $longsimDomesticDecisionContext !== null
    ) {
        return;
    }
    $db = DB::db();
    $actorNationId = (int)$db->queryFirstField(
        'SELECT nation FROM general WHERE no=%i',
        $actorGeneralId
    );
    $liveNation = $db->queryFirstRow(
        'SELECT nation,gold,rice,level,tech,chief_set FROM nation WHERE nation=%i',
        $actorNationId
    );
    $liveNationGenerals = $db->query(
        'SELECT no,nation,officer_level,npc,gold,rice,killturn FROM general WHERE nation=%i ORDER BY no',
        $actorNationId
    );
    $longsimDomesticDecisionContext = [
        'ordinal' => count($longsimDomesticDecisions),
        'phase' => $longsimCompositePhase,
        'phaseBoundary' => $longsimCompositeBoundary,
        'actorGeneralId' => $actorGeneralId,
        'year' => $year,
        'month' => $month,
        'seedString' => $seedString,
        'stats' => $stats,
        'city' => $city,
        'rates' => $rates,
        'candidates' => $candidates,
        'liveNation' => $liveNation,
        'liveNationGenerals' => $liveNationGenerals,
    ];
}

function recordLongsimDomesticWeightedDraw(
    array $cursorBefore,
    array $cursorAfter,
    float $drawValue,
    float $sum,
    array $items
): void {
    global $longsimDomesticDecisionContext, $longsimDomesticDecisions;
    if ($longsimDomesticDecisionContext === null) {
        return;
    }
    $remaining = $drawValue * $sum;
    $picked = null;
    foreach ($items as [$item, $weight]) {
        $effectiveWeight = $weight > 0 ? $weight : 0;
        if ($remaining <= $effectiveWeight) {
            $picked = $item->getRawClassName();
            break;
        }
        $remaining -= $effectiveWeight;
    }
    $longsimDomesticDecisions[] = $longsimDomesticDecisionContext + [
        'cursorBefore' => $cursorBefore,
        'cursorAfter' => $cursorAfter,
        'drawValue' => $drawValue,
        'weightSum' => $sum,
        'scaledDraw' => $drawValue * $sum,
        'pickedCommandCode' => $picked,
    ];
    $longsimDomesticDecisionContext = null;
}

function recordLongsimSqlRandSelection(
    string $branch,
    int $actorGeneralId,
    int $year,
    int $month,
    int $sourceNationId,
    ?int $selectedId
): void {
    global $longsimSqlRandSelections;
    $longsimSqlRandSelections[] = [
        'ordinal' => count($longsimSqlRandSelections),
        'branch' => $branch,
        'actorGeneralId' => $actorGeneralId,
        'year' => $year,
        'month' => $month,
        'sourceNationId' => $sourceNationId,
        'selectedId' => $selectedId,
    ];
}

function recordLongsimRandomImgwanPermutation(
    int $actorGeneralId,
    int $year,
    int $month,
    array $orderedNationIds
): void {
    global $longsimRandomImgwanPermutations, $longsimCompositePhase;
    $db = DB::db();
    $eligibilityRows = $db->query(
        'SELECT n.nation, n.scout, n.gennum, ' .
        'EXISTS(SELECT 1 FROM general g WHERE g.nation=n.nation AND g.officer_level=12) AS has_lord, ' .
        '(SELECT COUNT(*) FROM general g2 WHERE g2.nation=n.nation) AS actual_gennum ' .
        'FROM nation n ORDER BY n.nation ASC'
    ) ?: [];
    $longsimRandomImgwanPermutations[] = [
        'ordinal' => count($longsimRandomImgwanPermutations),
        'actorGeneralId' => $actorGeneralId,
        'year' => $year,
        'month' => $month,
        'phase' => $longsimCompositePhase,
        'orderedNationIds' => array_map('intval', $orderedNationIds),
        'eligibilityRows' => array_map(static fn(array $row): array => [
            'nationId' => (int)$row['nation'],
            'scout' => (int)$row['scout'],
            'storedGennum' => (int)$row['gennum'],
            'actualGennum' => (int)$row['actual_gennum'],
            'hasLord' => (bool)$row['has_lord'],
        ], $eligibilityRows),
    ];
}

function recordLongsimHandledCommand(
    int $actorGeneralId,
    string $dueTimestamp,
    string $commandCode,
    ?string $nationCommandCode,
    string $nextScheduledTimestamp
): void {
    global $longsimHandledCommands, $longsimCompositePhase, $longsimCompositeBoundary;
    global $longsimHandledCommandContext, $longsimHandledCommandSidecar;
    global $longsimActionStatus, $longsimActionSuccess;
    hardAssert(
        $longsimHandledCommandContext !== null
            && $longsimHandledCommandContext['actorGeneralId'] === $actorGeneralId,
        "handled-command context mismatch for actor {$actorGeneralId}"
    );
    $ordinal = count($longsimHandledCommands);
    $entry = [
        'ordinal' => $ordinal,
        'phase' => $longsimCompositePhase,
        'phaseBoundary' => $longsimCompositeBoundary,
        'dueTimestamp' => $dueTimestamp,
        'actorGeneralId' => $actorGeneralId,
        'commandCode' => $commandCode,
        'nationCommandCode' => $nationCommandCode,
        'nextScheduledTimestamp' => $nextScheduledTimestamp,
    ];
    $longsimHandledCommands[] = $entry;

    $db = DB::db();
    $records = $db->query(
        'SELECT id, general_id, log_type, year, month, text ' .
        'FROM general_record WHERE id>%i AND general_id=%i ORDER BY id ASC',
        $longsimHandledCommandContext['recordHighWater'],
        $actorGeneralId
    ) ?: [];
    $sidecar = $entry + [
        'cityBefore' => $longsimHandledCommandContext['cityBefore'],
        'cityAfter' => readLongsimActorCitySnapshot($actorGeneralId),
        'success' => $longsimActionSuccess,
        'status' => $longsimActionStatus,
        'actionLogs' => array_values(array_map(
            static fn(array $row): array => [
                'logType' => $row['log_type'],
                'year' => (int)$row['year'],
                'month' => (int)$row['month'],
                'text' => $row['text'],
            ],
            $records
        )),
    ];
    file_put_contents(
        $longsimHandledCommandSidecar,
        Json::encode(
            $sidecar,
            JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES
        ) . "\n",
        FILE_APPEND
    );
    $longsimHandledCommandContext = null;
}

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

function dueGeneralActorOrder(object $db, string $boundary): array {
    $rows = $db->query(
        'SELECT no FROM general WHERE turntime < %s ORDER BY turntime ASC, `no` ASC',
        $boundary
    );
    return array_map(static fn(array $row): int => (int)$row['no'], $rows ?: []);
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
        // Preserve the complete production policy surface. GeneralAI consumes
        // autorun_user.options and npc_*_policy directly from game_env.
        'game_env'   => $env,
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
if ($expectedHiddenSeed !== null) {
    hardAssert($hiddenSeed === $expectedHiddenSeed,
        "hiddenSeed {$hiddenSeed} != expected {$expectedHiddenSeed}");
}

$gameStor = KVStorage::getStorage($db, 'game_env');
[$year0, $month0, $startYear, $turnterm, $isunited0] = $gameStor->getValuesAsArray(
    ['year', 'month', 'startyear', 'turnterm', 'isunited']
);
$year0 = (int)$year0; $month0 = (int)$month0; $startYear = (int)$startYear;
$turnterm = (int)$turnterm; $isunited0 = (int)$isunited0;
if ($expectedTurntime !== null) {
    $gameStor->resetCache();
    hardAssert($gameStor->turntime === $expectedTurntime,
        "turntime {$gameStor->turntime} != expected {$expectedTurntime}");
}

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
        'installTurntime' => $gameStor->turntime,
        'state'      => $baselineState,
    ], JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES | JSON_PRETTY_PRINT)
);
fwrite(STDERR, "wrote capture-00-baseline.json (hiddenSeed={$hiddenSeed} year={$year0} startYear={$startYear})\n");

// ── long-sim loop initialization ───────────────────────────────────────────
$prevTurn = cutTurn($gameStor->turntime, $turnterm);
$nextTurn = addTurn($prevTurn, $turnterm);
$calendarPrevTurn = $prevTurn;
$calendarNextTurn = addTurn($calendarPrevTurn, $turnterm);

$captureIntervalMonths = 12;
$nextCaptureMonth = 12;  // first capture at month 12 (1 game year)
$totalMonths = 0;
$captureIndex = 0;
$manifestPoints = [];
$sqlRandSelectionHW = 0;

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
    // game_env.turntime is a MySQL datetime string; compare via DateTime, not int.
    $gameStor->resetCache();
    $tickStartTurntime = new \DateTimeImmutable($gameStor->turntime);

    // 5a. Composite V1-36 cadence: three general+nation command drains while the DB game
    // year/month remains fixed. The monthly pipeline runs only after phase 3.
    $farFuture = new \DateTimeImmutable('9999-12-31 23:59:59');
    for ($phase = 1; $phase <= 3; $phase++) {
        $longsimCompositePhase = $phase;
        $longsimCompositeBoundary = $nextTurn;
        $actorOrder = dueGeneralActorOrder($db, $nextTurn);
        $longsimPhaseDrains[] = [
            'ordinal' => count($longsimPhaseDrains),
            'gameMonthIndex' => $totalMonths,
            'phase' => $phase,
            'year' => $oldYear,
            'month' => $oldMonth,
            'boundary' => $nextTurn,
            'actorGeneralIds' => $actorOrder,
        ];
        TurnExecutionHelper::executeGeneralCommandUntil($nextTurn, $farFuture, $oldYear, $oldMonth);
        $prevTurn = $nextTurn;
        $nextTurn = addTurn($prevTurn, $turnterm);
        $gameStor->turntime = $prevTurn;
        $gameStor->resetCache();
    }

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
    turnDate($calendarNextTurn);
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

    // 5l. Advance the virtual legacy calendar by one month. The command schedule
    // already advanced three turnterm boundaries above.
    $calendarPrevTurn = $calendarNextTurn;
    $calendarNextTurn = addTurn($calendarPrevTurn, $turnterm);
    $gameStor->resetCache();

    // Assertion: turntime strictly advanced from tick start.
    $currentTurntime = new \DateTimeImmutable($gameStor->turntime);
    hardAssert($currentTurntime > $tickStartTurntime,
        "month {$totalMonths}: turntime did not advance monotonically ({$currentTurntime->format('Y-m-d H:i:s')} <= {$tickStartTurntime->format('Y-m-d H:i:s')})");

    $totalMonths++;

    // 5n. Capture snapshot if interval reached or unification
    $gameStor->resetCache();
    $isunited = (int)$gameStor->isunited;

    if ($totalMonths >= $nextCaptureMonth || $isunited !== 0) {
        $captureIndex++;
        $afterState = snapshotState($db);
        $recordRows = recordRowsSince($db, $recordHW);
        $recordCount = count($recordRows);
        $sqlRandSelections = array_slice($longsimSqlRandSelections, $sqlRandSelectionHW);
        $sqlRandSelectionHW = count($longsimSqlRandSelections);

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
                'sqlRandSelections' => $sqlRandSelections,
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
    $sqlRandSelections = array_slice($longsimSqlRandSelections, $sqlRandSelectionHW);
    $sqlRandSelectionHW = count($longsimSqlRandSelections);

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
            'sqlRandSelections' => $sqlRandSelections,
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
        'schemaVersion' => 4,
        'scenario'    => 1010,
        'startYear'   => $startYear,
        'turnterm'    => $turnterm,
        'maxTurns'    => $maxTurns,
        'hiddenSeed'  => $hiddenSeed,
        'installTurntime' => $baselineState['game_env']['turntime'] ?? null,
        'reachedMaxTurns' => $reachedMaxTurns,
        'totalMonths' => $totalMonths,
        'baseline'    => 'capture-00-baseline.json',
        'points'      => $manifestPoints,
        'sqlRandSelections' => $longsimSqlRandSelections,
        'randomImgwanPermutations' => $longsimRandomImgwanPermutations,
        'phaseDrains' => $longsimPhaseDrains,
        'handledCommands' => $longsimHandledCommands,
        'handledCommandSidecar' => [
            'file' => basename($longsimHandledCommandSidecar),
            'count' => count($longsimHandledCommands),
            'sha256' => hash_file('sha256', $longsimHandledCommandSidecar),
        ],
        'domesticDecisions' => $longsimDomesticDecisions,
        'domesticObserver' => [
            'actorGeneralId' => $longsimDomesticActor,
            'phase' => $longsimDomesticPhase,
        ],
        'ambientMtSeed' => $longsimAmbientMtSeed,
        'killturnTransitions' => $longsimKillturnTransitions,
        'diplomacyIdentitySidecar' => [
            'file' => basename($longsimDiplomacyIdentitySidecar),
            'count' => $longsimDiplomacyIdentityTransitionCount,
            'sha256' => hash_file('sha256', $longsimDiplomacyIdentitySidecar),
        ],
        'cadence' => 'composite-v1-36',
    ], JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES | JSON_PRETTY_PRINT)
);

fwrite(STDERR, "DONE: long-sim captured {$totalMonths} months (maxTurns={$maxTurns}, reachedMaxTurns=" . ($reachedMaxTurns ? 'true' : 'false') . ", points=" . count($manifestPoints) . ", hiddenSeed={$hiddenSeed})\n");
