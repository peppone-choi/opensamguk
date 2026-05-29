<?php
/**
 * capture_che.php — P1 golden capture harness (devsam-core PHP, grand truth).
 *
 * ONE-SHOT, MANUAL HOST STEP — NEVER CI.
 *
 * Boots a real devsam-core install, picks module-free generals in owned/supplied/
 * non-front cities, and DRIVES the REAL turn-execution path for che_상업투자 /
 * che_농지개간 exactly as hwe/sammo/TurnExecutionHelper.php does — building the
 * GeneralCommand through the game's own factory (buildGeneralCommandClass), then
 * re-deriving the per-turn RNG with the SIX-component simpleSerialize seed and
 * calling $cmd->run($rng) (the success branch of TurnExecutionHelper::process-
 * Command). The captured log + RNG draws are byte/draw-identical to a real turn.
 *
 * Oracle = PHP devsam-core. The fixture captures, per action case: the SIX-
 * component seed string, the env (year/startYear/develCost), general BEFORE+AFTER,
 * city BEFORE+AFTER, the action-log row(s) CHAR-FOR-CHAR (the ActionLogger MONTH
 * format: "<C>●</>{month}월:…<C>score</> 상승했습니다. <1>HH:MM</>"), the computed
 * reqGold, and the per-game hiddenSeed (committed as a fixture INPUT).
 *
 * Boot/DB binding: this revision binds the DB at INSTALL time by substituting the
 * container creds into d_setting/DB.php (Util::generateFileUsingSimpleTemplate,
 * exactly what hwe/j_install_db.php does) and accesses it through DB::db(). There
 * is NO DB::setSelfConnInfo / GameConst::getServerConnInfo here. _boot.php does the
 * binding; install_scenario.php drives ResetHelper::buildScenario (the real
 * headless install, hwe/j_install.php's call).
 *
 * MODULE-FREE precondition: this revision ships NO general with an empty
 * personality column (scenarios always assign one). The picked generals carry a
 * '-'/None specialty (special/special2='None') and a personality that is a domestic
 * NO-OP (onCalcDomestic identity for 상업/농업). To make the P1 empty-pipeline
 * identity guard LITERAL, the harness sets personal='None' (the no-op personality
 * class) on the picked generals — a STATIC-input precondition that does not change
 * the computed golden (the personality was already a domestic no-op; stats are
 * unaffected). The computed values (RNG draws, score, log, mutations) are 100% real
 * PHP. assertModuleFree then proves the domestic pipeline is identity.
 *
 * Invocation (inside the php capture container, repo mounted at /work):
 *   php tools/php-golden/capture_che.php \
 *       --out=logic/src/test/resources/golden/p1/che-action-fixtures.json
 * (DB creds via SAMMO_DB_* env — see _boot.php.)
 *
 * HARD assertions (abort the capture if violated — NOT "verify" prose):
 *   (1) DISTINCT success/normal/fail — each an INDEPENDENT reproducible fixture
 *       (vary generalId/month), each capturing its full env.
 *   (2) MODULE-FREE general — special/special2/personal ∈ {None,'',null}, every
 *       equipment slot is a 'None' item, AND the domestic pipeline is IDENTITY for
 *       상업/농업 × {score,cost,success,fail} (the real empty-pipeline fold guard
 *       for this revision — getActionList folds nationType/officer/special/personal/
 *       crew/inherit/scenario/items; identity proves none perturb the action).
 *   (3) NO LEVEL CROSS — explevel(before)==explevel(after) AND
 *       dedlevel(before)==dedlevel(after) for every case (the deferred level-change
 *       log path — General::checkStatChange, General.php:455-495 — is never
 *       exercised by the golden).
 *   (4) NO unique item won + NO static event fired — tryUniqueItemLottery granted
 *       nothing (item-slot count unchanged) and StaticEventHandler::handleEvent
 *       produced no extra log (so neither perturbs the action RNG stream).
 *   (P1 #4) INTEGER trust — the golden city's trust == floor(trust).
 *
 * DEFERRAL (P2, NOT an open question): the level-change side effects
 * (explevel/dedlevel recompute + the secondary PLAIN 레벨업/레벨다운/승급/강등 logs,
 * General.php:455-495) are EXPLICITLY deferred to P2. Hard assertion (3) guarantees
 * P1's golden never needs them.
 */

namespace sammo;

require __DIR__ . '/_boot.php';

use sammo\Command\General\che_상업투자;
use sammo\Command\General\che_농지개간;

// ── CLI args (getopt `=` form per devsam capture-env quirk) ──────────────────
$opts = getopt('', ['out:']);
$outPath = $opts['out'] ?? (__DIR__ . '/../../logic/src/test/resources/golden/p1/che-action-fixtures.json');

$db = DB::db();

// hiddenSeed is the per-game seed oracle — captured as a fixture INPUT (OQ4).
// Read from the running install's UniqueConst (set per d_setting), NOT a :common
// constant. C2/G2/F3 seed off this exact value.
$hiddenSeed = UniqueConst::$hiddenSeed;

// game_env: year / startYear / month / develCost are part of each case's env.
$gameStor = KVStorage::getStorage($db, 'game_env');
[$year, $startYear, $monthBase, $develCost] = $gameStor->getValuesAsArray(
    ['year', 'startyear', 'month', 'develcost']
);
$env = $gameStor->getAll();

/**
 * Assert a HARD invariant; abort the whole capture on violation (never write a
 * partial/unfaithful golden).
 */
function hardAssert(bool $cond, string $msg): void {
    if (!$cond) {
        fwrite(STDERR, "G1 HARD-ASSERT FAILED: {$msg}\n");
        exit(2);
    }
}

/** Is a static-input field one of the no-op sentinels ('None'/''/null)? */
function isNoneField(?string $v): bool {
    return $v === null || $v === '' || $v === 'None';
}

/**
 * che_상업투자/농지개간 share an empty (no-op) onCalcDomestic in the P1 slice; this
 * guard proves the captured general carries NO domestic module so the empty-
 * pipeline identity fold is faithful (HARD assertion 2). For THIS revision the
 * faithful check is: blank specialty/personality sentinels + every equipment slot
 * a 'None' item + IDENTITY domestic fold for the action keys.
 */
function assertModuleFree(General $g): void {
    hardAssert(isNoneField($g->getVar('special')),  'general has special (내정특기) module');
    hardAssert(isNoneField($g->getVar('special2')), 'general has special2 (전투특기) module');
    hardAssert(isNoneField($g->getVar('personal')), 'general has personal (성격) module');
    foreach (($g->getItems() ?? []) as $slot => $item) {
        // every equipment slot must be the no-op None item (ActionItem\None,
        // whose rawName is '-'); a real item would fold into onCalcDomestic.
        hardAssert($item instanceof \sammo\ActionItem\None, "equipment slot {$slot} not a None item");
    }
    foreach (['상업', '농업'] as $actionKey) {
        foreach ([['score', 123.0], ['cost', 100.0], ['success', 0.5], ['fail', 0.25]] as [$vt, $v]) {
            hardAssert(abs($g->onCalcDomestic($actionKey, $vt, $v) - $v) < 1e-9,
                "domestic pipeline not identity: {$actionKey}/{$vt}");
        }
    }
}

/** Read the persisted action-log rows (char-for-char) for a general. The command
 *  flushes its ActionLogger buffer into general_record on $general->applyDB($db)
 *  (General.php:749), so the turn's log lines are the general_record 'action'
 *  rows — exactly the rows dump_golden_db.php emits as log_entry. */
function logActionRows(object $db, int $gid): array {
    $rows = $db->query(
        'SELECT text FROM general_record WHERE general_id=%i AND log_type=%s ORDER BY id',
        $gid, 'action'
    );
    $out = [];
    foreach ($rows as $r) $out[] = $r['text'];
    return $out;
}

/** Set the game clock month (the ActionLogger reads it via createObjFromDB) and
 *  return the refreshed env. */
function setGameMonth(object $db, int $month): array {
    $gs = KVStorage::getStorage($db, 'game_env');
    $gs->month = $month;
    $gs->resetCache();
    return KVStorage::getStorage($db, 'game_env')->getAll();
}

/** exp band [lo,hi) for getExpLevel level L; ded band (lo,hi] for getDedLevel L. */
function expBandLo(int $L): int { return $L < 10 ? $L * 100 : 10 * $L * $L; }
function expBandHi(int $L): int { return $L < 10 ? ($L + 1) * 100 : 10 * ($L + 1) * ($L + 1); }
function dedBandLo(int $L): int { return $L <= 0 ? 0 : (10 * ($L - 1)) * (10 * ($L - 1)); }
function dedBandHi(int $L): int { return (10 * $L) * (10 * $L); }

/**
 * Apply the MODULE-FREE + NO-LEVEL-CROSS precondition (static-input setup; the
 * computed golden stays 100% real PHP):
 *   - personal='None' — the no-op personality (the picked general's personality is
 *     already a domestic no-op; this makes assertion (2) literal).
 *   - explevel/dedlevel SYNCED to getExpLevel(exp)/getDedLevel(ded). The install
 *     leaves explevel=0 with experience>0, so an unsynced first turn would log a
 *     spurious 레벨업 (assertion 3's deferred path). Syncing puts the general in the
 *     consistent state the game reaches after its first checkStatChange.
 *   - exp/ded nudged to ~40% into their current level band so the turn's gain does
 *     not cross a level (assertion 3 — the deferred P2 level-change log path).
 * Returns the new baseline general row.
 */
function applyPrecondition(object $db, int $gid): array {
    $row = $db->queryFirstRow('SELECT experience, dedication FROM general WHERE no=%i', $gid);
    $exp = (int)$row['experience'];
    $ded = (int)$row['dedication'];
    $el  = getExpLevel($exp);
    $dl  = getDedLevel($ded);
    $emid = (int)(expBandLo($el) + (expBandHi($el) - expBandLo($el)) * 0.4);
    $dmid = (int)(dedBandLo($dl) + (dedBandHi($dl) - dedBandLo($dl)) * 0.4);
    $db->update('general', [
        'personal'   => 'None',
        'experience' => $emid,
        'dedication' => $dmid,
        'explevel'   => getExpLevel($emid),
        'dedlevel'   => getDedLevel($dmid),
    ], 'no=%i', $gid);
    return $db->queryFirstRow('SELECT * FROM general WHERE no=%i', $gid);
}

/**
 * Capture ONE che action case as an independent reproducible fixture.
 *
 * Builds the command through the GAME's own factory (buildGeneralCommandClass —
 * the 3-arg General/env/arg path BaseCommand::__construct uses), re-derives the
 * exact per-turn RNG (TurnExecutionHelper.php:340-348 — six-component
 * simpleSerialize seed), and runs the command via $cmd->run($rng) (process-
 * Command's success branch), capturing BEFORE/AFTER + the char-for-char log.
 */
function captureCase(
    object $db,
    string $caseName,
    string $rawClassName,   // 'che_상업투자' | 'che_농지개간'
    int $generalId,
    int $year,
    int $month,
    array $env,
    string $hiddenSeed
): array {
    // Set the game clock to the case month so the ActionLogger "N월:" prefix and
    // the seed's month component agree (createObjFromDB builds the logger from
    // game_env year/month — General.php:1037). The caller already restored this
    // general+city to the canonical baseline (module-free precondition incl.
    // personal='None' + mid-band exp/ded, and an empty action log) before this call.
    $env = setGameMonth($db, $month);

    $general = General::createObjFromDB($generalId);
    $cityId  = (int)$general->getVar('city');
    $city    = $db->queryFirstRow('SELECT * FROM city WHERE city=%i', $cityId);

    assertModuleFree($general);

    $explevelBefore = $general->getVar('explevel');
    $dedlevelBefore = $general->getVar('dedlevel');
    $trust          = $city['trust'];
    // (P1 #4) integer trust — the Int baseline column must be lossless.
    hardAssert($trust == floor($trust), "city trust {$trust} not integer");

    // Build the command exactly as the game does (factory → 3-arg ctor).
    $cmd = buildGeneralCommandClass($rawClassName, $general, $env, null);
    hardAssert($cmd->getRawClassName() === $rawClassName,
        "factory returned {$cmd->getRawClassName()} for {$rawClassName}");
    hardAssert($cmd->hasFullConditionMet(), "{$caseName}: command precondition not met");
    $reqGold = (new \ReflectionProperty($cmd, 'reqGold'))->getValue($cmd) // populated in init()
            ?? $cmd->getCost()[0];

    // BEFORE snapshot.
    $before = snapshotGeneralCity($general, $city);

    // Detect unique-item / static-event perturbation (HARD assertion 4). The
    // caller cleared this general's action rows, so this turn's flush is isolated.
    $itemCountBefore = count($general->getItems() ?? []);

    // The exact per-turn RNG (TurnExecutionHelper.php:340-348).
    $seedString = Util::simpleSerialize(
        $hiddenSeed, 'generalCommand', $year, $month, $generalId, $cmd->getRawClassName()
    );
    $rng = new RandUtil(new LiteHashDRBG($seedString));

    // Drive the real command (processCommand's success branch). run() flushes the
    // ActionLogger into general_record via $general->applyDB($db).
    $ok = $cmd->run($rng);
    hardAssert($ok === true, "{$caseName}: run() returned false");

    // The char-for-char action-log lines produced THIS turn (the flushed
    // general_record 'action' rows; baseline restore cleared prior rows).
    $newLines = logActionRows($db, $generalId);

    // HARD assertion (4): exactly ONE action line — no unique-item / static-event
    // PLAIN line slipped into the buffer (those would perturb the stream / golden).
    hardAssert(count($newLines) === 1,
        "{$caseName}: expected 1 action log line, got " . count($newLines)
        . " (unique-item/static-event fired?)");
    hardAssert(count($general->getItems() ?? []) === $itemCountBefore,
        "{$caseName}: equipment slot count changed (unique item granted)");

    // AFTER (reload from DB — the command applyDB-flushed the mutation).
    $generalAfter = General::createObjFromDB($generalId);
    $cityAfter    = $db->queryFirstRow('SELECT * FROM city WHERE city=%i', $cityId);
    $after = snapshotGeneralCity($generalAfter, $cityAfter);

    // HARD assertion (3): no level cross.
    hardAssert($generalAfter->getVar('explevel') === $explevelBefore, "{$caseName}: exp level crossed");
    hardAssert($generalAfter->getVar('dedlevel') === $dedlevelBefore, "{$caseName}: ded level crossed");

    // unchanged caps — sanity (asserted equal before/after by the Kotlin gate too).
    hardAssert($cityAfter['comm_max'] === $city['comm_max'], "{$caseName}: comm_max changed");
    hardAssert($cityAfter['agri_max'] === $city['agri_max'], "{$caseName}: agri_max changed");

    return [
        'case'       => $caseName,
        'command'    => $rawClassName,           // che_상업투자 | che_농지개간
        'generalId'  => $generalId,
        'cityId'     => $cityId,
        'env'        => [
            'year'      => $year,
            'startYear' => $env['startyear'],
            'month'     => $month,
            'develCost' => $env['develcost'],
        ],
        'seedString' => $seedString,             // the G2 oracle seed string
        'reqGold'    => $reqGold,
        'before'     => $before,
        'after'      => $after,
        'logLines'   => $newLines,               // ordered, char-for-char
    ];
}

/** General + city snapshot — exactly the fields the Kotlin gate compares. */
function snapshotGeneralCity(General $g, array $city): array {
    return [
        'general' => [
            'gold'                  => $g->getVar('gold'),
            'experience'            => $g->getVar('experience'),
            'dedication'            => $g->getVar('dedication'),
            'intel_exp'             => $g->getVar('intel_exp'),                  // column
            'explevel'              => $g->getVar('explevel'),                  // meta/column
            'max_domestic_critical' => $g->getAuxVar('max_domestic_critical') ?? 0, // meta
        ],
        'city' => [
            'comm'     => $city['comm'],
            'agri'     => $city['agri'],
            'comm_max' => $city['comm_max'],   // unchanged — asserted equal before/after
            'agri_max' => $city['agri_max'],   // unchanged
            'trust'    => $city['trust'],
        ],
    ];
}

// ── Picks (filled by the operator via tools/php-golden/probe_picks.php for the
//    running install/hiddenSeed — see README). caseName, rawClassName, gid, month.
// commerce_success / commerce_normal / commerce_fail : che_상업투자, DISTINCT picks
//   (HARD assertion 1) — varied by month on a single module-free general (the game
//   clock is set to the case month so the log date/seed-month agree).
// agri_normal : che_농지개간, normal pick.
$picks = [
    // caseName            rawClassName     generalId  month
    ['commerce_success',   'che_상업투자',  76, 1],
    ['commerce_normal',    'che_상업투자',  76, 3],
    ['commerce_fail',      'che_상업투자',  76, 2],
    ['agri_normal',        'che_농지개간',  76, 2],
];

// INDEPENDENCE (HARD assertion 1): every case is captured from the SAME canonical
// baseline state of the chosen general + city (one hiddenSeed, one install). Each
// che command mutates ONLY the general row + its city row (+ the general_record
// log buffer); we apply the module-free/no-cross precondition ONCE, snapshot those
// baseline rows, and restore them before each case so the N captures are mutually
// independent reproducible fixtures.
$baselineGids = [];
foreach ($picks as [, , $gid,]) $baselineGids[$gid] = true;
$baselineGeneral = [];   // gid => raw general row
$baselineCity    = [];   // cityId => raw city row
foreach (array_keys($baselineGids) as $gid) {
    // FRESHNESS GUARD: the baseline must be a PRISTINE install — capture is only
    // reproducible from a fresh install_scenario.php run (gold/comm/intel_exp are
    // install values the harness cannot reconstruct). A leftover 'action' log row
    // means a prior capture mutated this general → abort rather than snapshot a
    // dirty baseline (install fresh, then re-probe + re-capture).
    $priorLog = (int)$db->queryFirstField(
        'SELECT COUNT(*) FROM general_record WHERE general_id=%i AND log_type=%s', $gid, 'action'
    );
    hardAssert($priorLog === 0,
        "general {$gid} has {$priorLog} prior action-log rows — DB is dirty; "
        . "re-run install_scenario.php for a pristine baseline before capturing");
    $gRow = applyPrecondition($db, $gid);   // personal='None' + synced/nudged exp/ded
    $baselineGeneral[$gid] = $gRow;
    $cid = (int)$gRow['city'];
    $baselineCity[$cid] = $db->queryFirstRow('SELECT * FROM city WHERE city=%i', $cid);
}
function restoreBaseline(object $db, int $gid, array $bg, array $bc): void {
    $gRow = $bg[$gid];
    $cid  = (int)$gRow['city'];
    $db->update('general', $gRow, 'no=%i', $gid);
    $db->update('city', $bc[$cid], 'city=%i', $cid);
    // clear this turn's action-log rows so the buffer/record stays canonical.
    $db->delete('general_record', 'general_id=%i AND log_type=%s', $gid, 'action');
}

$cases = [];
$seenPick = [];
foreach ($picks as [$name, $cls, $gid, $m]) {
    hardAssert($gid !== null && $m !== null,
        "operator must fill generalId/month for case '{$name}' (see README)");
    restoreBaseline($db, $gid, $baselineGeneral, $baselineCity);
    $case = captureCase($db, $name, $cls, $gid, $year, $m, $env, $hiddenSeed);

    // HARD assertion (1): distinct success/normal/fail among the commerce cases.
    $pickKind = inferPickFromLog($case['logLines'][0] ?? '');
    if (str_starts_with($name, 'commerce_')) {
        hardAssert(!isset($seenPick[$pickKind]), "duplicate commerce pick '{$pickKind}'");
        $seenPick[$pickKind] = true;
        $expected = substr($name, strlen('commerce_'));
        hardAssert($pickKind === $expected, "case {$name} produced pick '{$pickKind}'");
    }

    // success case: max_domestic_critical == prior + score/2 (updateMaxDomesticCritical).
    if ($pickKind === 'success') {
        $score = scoreFromLog($case['logLines'][0]);
        $expectedMax = $case['before']['general']['max_domestic_critical'] + $score / 2;
        hardAssert(
            abs($case['after']['general']['max_domestic_critical'] - $expectedMax) < 1e-9,
            "{$name}: max_domestic_critical != prior + score/2"
        );
    }
    $cases[] = $case;
}

// ── blocked_notSupplied — locks getFailString()/the deny reason ──────────────
$cases[] = captureBlockedCase($db, $env);

// ── leave the DB in the canonical commerce_success post-turn state for the DB
//    dump (dump_golden_db.sh): restore baseline + replay the FIRST pick
//    (commerce_success) so che-golden-db.json's general/city/log_entry rows equal
//    that fixture's `after` (the D1 row-mapper byte oracle). Because every case
//    restores baseline first, this is idempotent: a fresh capture run re-derives
//    the same canonical state from the same install. ────────────────────────────
[$canonName, $canonCls, $canonGid, $canonMonth] = $picks[0];
restoreBaseline($db, $canonGid, $baselineGeneral, $baselineCity);
$canonEnv = setGameMonth($db, $canonMonth);
$canonGeneral = General::createObjFromDB($canonGid);
$canonCmd = buildGeneralCommandClass($canonCls, $canonGeneral, $canonEnv, null);
$canonSeed = Util::simpleSerialize($hiddenSeed, 'generalCommand', $year, $canonMonth, $canonGid, $canonCmd->getRawClassName());
$canonCmd->run(new RandUtil(new LiteHashDRBG($canonSeed)));
fwrite(STDERR, "DB left in canonical {$canonName} post-turn state (gid={$canonGid}, month={$canonMonth}) for dump_golden_db.sh\n");

// ── emit committed JSON (PHP Json::encode key insertion order, compact, ──────
//    UTF-8 literal Korean, unescaped slashes — mirrors the meta jsonb encoder) ─
$out = [
    // hiddenSeed committed as a fixture INPUT — the seed oracle for C2/G2/F3.
    'hiddenSeed' => $hiddenSeed,
    'env'        => [
        'year'      => $year,
        'startYear' => $startYear,
        'develCost' => $develCost,
    ],
    'cases'      => $cases,
];
file_put_contents(
    $outPath,
    Json::encode($out, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES | JSON_PRETTY_PRINT)
);
fwrite(STDERR, "wrote " . basename($outPath) . " (" . count($cases) . " cases)\n");

// ── helpers ──────────────────────────────────────────────────────────────────
function inferPickFromLog(string $line): string {
    if (str_contains($line, "ev_failed")) return 'fail';
    if (str_contains($line, "<S>성공</>")) return 'success';
    return 'normal';
}
function scoreFromLog(string $line): int {
    // <C>1,234</> → 1234  (the comma-grouped scoreText)
    if (preg_match("#<C>([0-9,]+)</>#u", $line, $m)) {
        return (int) str_replace(',', '', $m[1]);
    }
    throw new \RuntimeException("no scoreText in log: {$line}");
}
function captureBlockedCase(object $db, array $env): array {
    // A neutral general (nation 0) fails NotBeNeutral/OccupiedCity →
    // hasFullConditionMet() == false → getFailString() text (the BLOCKED deny).
    $gid = (int)(getenv('SAMMO_BLOCKED_GID') ?: throw new \RuntimeException('SAMMO_BLOCKED_GID required'));
    $general = General::createObjFromDB($gid);
    $cmd = buildGeneralCommandClass('che_농지개간', $general, $env, null);
    hardAssert(!$cmd->hasFullConditionMet(), 'blocked case unexpectedly met full condition');
    return [
        'case'       => 'blocked_notSupplied',
        'command'    => 'che_농지개간',
        'generalId'  => $gid,
        'cityId'     => $general->getVar('city'),
        'denyReason' => $cmd->getFailString(),   // the locked deny reason
    ];
}
