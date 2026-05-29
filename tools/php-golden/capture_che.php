<?php
/**
 * capture_che.php — P1 golden capture harness (devsam-core PHP, grand truth).
 *
 * ONE-SHOT, MANUAL HOST STEP — NEVER CI.
 *
 * Boots the devsam-core scenario_0 seed, picks ONE module-free general in an
 * owned/supplied/non-front city, reserves + executes `che_상업투자` and
 * `che_농지개간` turns, and emits the committed golden fixture
 *   logic/src/test/resources/golden/p1/che-action-fixtures.json
 * that the Kotlin byte-match tests (G2/G4) assert against.
 *
 * Oracle = PHP devsam-core (the parity grand truth). The fixture captures, per
 * action case: the per-action SIX-component seed string, the env
 * (year/startYear/develCost), general BEFORE+AFTER, city BEFORE+AFTER, the
 * action-log row(s) CHAR-FOR-CHAR (with <C>/<S>/<span class='ev_failed'> +
 * <1>date</> markup), the computed reqGold, and the per-game hiddenSeed (the
 * seed oracle for C2/G2/F3 — committed as a fixture INPUT).
 *
 * Run ONCE, commit the produced JSON + the DB fragment (dump_golden_db.sh),
 * regenerate ONLY when the PHP source (che_상업투자.php / func_process.php /
 * func_gamerule.php / func_converter.php / RandUtil / Util::simpleSerialize)
 * changes. See README.md for the devsam capture-env prerequisite + quirks.
 *
 * Invocation (from the devsam-core hwe/ root, see README):
 *   php /ABS/PATH/opensamguk/tools/php-golden/capture_che.php \
 *       --serverID=<server> --out=/ABS/PATH/opensamguk/logic/src/test/resources/golden/p1/che-action-fixtures.json
 *
 * HARD assertions (abort the capture if violated — NOT "verify" prose):
 *   (1) DISTINCT success/normal/fail — each an INDEPENDENT reproducible fixture
 *       (vary generalId/month), each capturing its full env.
 *   (2) MODULE-FREE general — special/special2/personal empty, all 8 effect
 *       slots null, itemObjs empty (so the P1 empty-pipeline identity fold is
 *       provably faithful — §14 9-source-stack OQ guard).
 *   (3) NO LEVEL CROSS — getExpLevel(before)==getExpLevel(after) AND
 *       getDedLevel(before)==getDedLevel(after) for every case (the deferred
 *       level-change log path is never exercised by the golden — P0 #3 / P2).
 *   (4) NO unique item won + NO static event fired — tryUniqueItemLottery
 *       granted nothing and StaticEventHandler::handleEvent produced no
 *       event/log (so neither perturbs the action RNG stream).
 *   (P1 #4) INTEGER trust — the golden city's trust == floor(trust) (the Int
 *       baseline city.trust column is lossless + byte-comparable).
 *
 * DEFERRAL (P2, NOT an open question): the level-change side effects
 * (explevel/dedlevel recompute + the secondary PLAIN 레벨업/레벨다운/승급/강등
 * logs, General.php:448-495) are EXPLICITLY deferred to P2. Hard assertion (3)
 * guarantees P1's golden never needs them.
 */

namespace sammo;

require_once __DIR__ . '/../../legacy/devsam-core/hwe/lib.php';
require_once __DIR__ . '/../../legacy/devsam-core/hwe/func.php';

use sammo\Command\General\che_상업투자;
use sammo\Command\General\che_농지개간;

// ── CLI args (getopt `=` form per devsam capture-env quirk) ──────────────────
$opts = getopt('', ['serverID:', 'out:']);
$serverID = $opts['serverID'] ?? getenv('SAMMO_SERVER_ID') ?: throw new \RuntimeException('--serverID required');
$outPath  = $opts['out'] ?? (__DIR__ . '/../../logic/src/test/resources/golden/p1/che-action-fixtures.json');

// Bind the devsam server connection (reflection credentials per capture quirk).
DB::setSelfConnInfo(GameConst::getServerConnInfo($serverID));
$db = DB::db();

// hiddenSeed is the per-game seed oracle — captured as a fixture INPUT (OQ4).
// It is read from the running install's UniqueConst (set per d_setting), NOT a
// :common constant. C2/G2/F3 seed off this exact value.
$hiddenSeed = UniqueConst::$hiddenSeed;

// game_env: year / startYear / develCost are part of each case's captured env.
$gameStor   = KVStorage::getStorage($db, 'game_env');
[$year, $startYear, $month, $develCost] = $gameStor->getValuesAsArray(
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

/** che_상업투자/농지개간 share an empty (no-op) onCalcDomestic in the P1 slice;
 *  this guard proves the captured general carries NO domestic modules so the
 *  empty-pipeline identity fold is faithful (HARD assertion 2). */
function assertModuleFree(General $g): void {
    hardAssert(($g->getVar('special')  ?? '') === '', 'general has special (내정특기) module');
    hardAssert(($g->getVar('special2') ?? '') === '', 'general has special2 (전투특기) module');
    hardAssert(($g->getVar('personal') ?? '') === '', 'general has personal (성격) module');
    for ($i = 0; $i < 8; $i++) {
        hardAssert($g->getRawSpecialDomestic($i) === null
                || $g->getRawSpecialDomestic($i) === '', "effect slot {$i} not null");
    }
    hardAssert(count($g->getItems() ?? []) === 0, 'general carries an item (itemObjs non-empty)');
}

/**
 * Capture ONE che action case as an independent reproducible fixture.
 *
 * Reserves the command on the chosen general, then RE-DERIVES the exact PHP
 * per-turn RNG (TurnExecutionHelper.php:340-347 — six-component simpleSerialize
 * seed) and runs the command, capturing BEFORE/AFTER + the char-for-char log.
 */
function captureCase(
    \mysqli|object $db,
    string $caseName,
    string $cmdClass,   // che_상업투자::class | che_농지개간::class
    int $generalId,
    int $year,
    int $month,
    array $env,
    string $hiddenSeed
): array {
    $general = General::createObjFromDB($generalId);
    $city    = CityHelper::loadRawCity($general->getVar('city'));

    assertModuleFree($general);

    $expLevelBefore = $general->getExpLevel();
    $dedLevelBefore = $general->getDedLevel();
    $trust          = $city['trust'];
    // (P1 #4) integer trust — the Int baseline column must be lossless.
    hardAssert($trust == floor($trust), "city trust {$trust} not integer");

    $rawClassName = (new \ReflectionClass($cmdClass))->getShortName(); // che_상업투자
    $seedString = Util::simpleSerialize(
        $hiddenSeed, 'generalCommand', $year, $month, $generalId, $rawClassName
    );
    $rng = new RandUtil(new LiteHashDRBG($seedString));

    $cmd = new $cmdClass($general, $env, [], null);
    hardAssert($cmd->hasFullConditionMet(), "{$caseName}: command precondition not met");
    $reqGold = (new \ReflectionProperty($cmd, 'reqGold'))->getValue($cmd) // populated in init()
            ?? $cmd->getCost()[0];

    // BEFORE snapshot.
    $before = snapshotGeneralCity($general, $city);

    // Detect unique-item / static-event perturbation (HARD assertion 4).
    $logCountBefore  = count($general->getLogger()->getRawGeneralActionLog() ?? []);
    $itemCountBefore = count($general->getItems() ?? []);

    $cmd->run($rng);

    // AFTER (reload from DB — the command applyDB-flushed the mutation).
    $generalAfter = General::createObjFromDB($generalId);
    $cityAfter    = CityHelper::loadRawCity($generalAfter->getVar('city'));
    $after = snapshotGeneralCity($generalAfter, $cityAfter);

    // HARD assertion (3): no level cross.
    hardAssert($generalAfter->getExpLevel() === $expLevelBefore, "{$caseName}: exp level crossed");
    hardAssert($generalAfter->getDedLevel() === $dedLevelBefore, "{$caseName}: ded level crossed");

    // HARD assertion (4): no unique item won, no static event.
    hardAssert(count($generalAfter->getItems() ?? []) === $itemCountBefore,
        "{$caseName}: unique item granted (RNG stream perturbed)");

    // The char-for-char action-log lines produced THIS turn.
    $allLogs  = $generalAfter->getLogger()->getRawGeneralActionLog() ?? [];
    $newLines = array_slice($allLogs, 0, count($allLogs) - $logCountBefore);

    return [
        'case'       => $caseName,
        'command'    => $rawClassName,           // che_상업투자 | che_농지개간
        'generalId'  => $generalId,
        'cityId'     => $general->getVar('city'),
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
            'intel_exp'             => $g->getAuxVar('intel_exp') ?? 0,        // meta.intel_exp
            'explevel'              => $g->getVar('explevel'),                 // meta.explevel
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

// ── Capture all five fixtures (vary generalId/month for distinct picks) ──────
// commerce_success / commerce_normal / commerce_fail : che_상업투자, DISTINCT
//   success/normal/fail picks (HARD assertion 1) — varied by month/generalId.
// agri_normal : che_농지개간, normal pick.
// blocked_notSupplied : non-owned / unsupplied / insufficient-gold — locks
//   getFailString()/the deny reason (the BLOCKED case).
//
// The generalId/month tuples below MUST be filled by the OPERATOR from the
// scenario_0 seed when running the capture: pick a module-free general in an
// owned, SUPPLIED, NON-FRONT city with commerce<commerceMax / agri<agriMax and
// gold>=round(develCost), and probe months until DISTINCT success/normal/fail
// picks fall out (HARD assertion 1 aborts if any two collide). See README.
$picks = [
    // caseName              cmdClass           generalId  month
    ['commerce_success',     che_상업투자::class,   /*GID*/ null, /*M*/ null],
    ['commerce_normal',      che_상업투자::class,   /*GID*/ null, /*M*/ null],
    ['commerce_fail',        che_상업투자::class,   /*GID*/ null, /*M*/ null],
    ['agri_normal',          che_농지개간::class,   /*GID*/ null, /*M*/ null],
];

$cases = [];
$seenPick = [];
foreach ($picks as [$name, $cls, $gid, $m]) {
    hardAssert($gid !== null && $m !== null,
        "operator must fill generalId/month for case '{$name}' (see README)");
    $case = captureCase($db, $name, $cls, $gid, $year, $m, $env, $hiddenSeed);

    // HARD assertion (1): distinct success/normal/fail among the commerce cases.
    $pickKind = inferPickFromLog($case['logLines'][0] ?? '');
    if (str_starts_with($name, 'commerce_')) {
        hardAssert(!isset($seenPick[$pickKind]), "duplicate commerce pick '{$pickKind}'");
        $seenPick[$pickKind] = true;
        $expected = substr($name, strlen('commerce_'));
        hardAssert($pickKind === $expected, "case {$name} produced pick '{$pickKind}'");
    }

    // success case: max_domestic_critical == score/2 (updateMaxDomesticCritical).
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
    // <C>1,234</> → 1234
    if (preg_match("#<C>([0-9,]+)</>#u", $line, $m)) {
        return (int) str_replace(',', '', $m[1]);
    }
    throw new \RuntimeException("no scoreText in log: {$line}");
}
function captureBlockedCase(\mysqli|object $db, array $env): array {
    // Operator fills a general whose city is NOT supplied (or non-owned, or
    // gold < reqGold). hasFullConditionMet() == false → getFailString() text.
    $gid = getenv('SAMMO_BLOCKED_GID') ?: throw new \RuntimeException('SAMMO_BLOCKED_GID required');
    $general = General::createObjFromDB((int) $gid);
    $cmd = new che_농지개간($general, $env, [], null);
    hardAssert(!$cmd->hasFullConditionMet(), 'blocked case unexpectedly met full condition');
    return [
        'case'       => 'blocked_notSupplied',
        'command'    => 'che_농지개간',
        'generalId'  => (int) $gid,
        'cityId'     => $general->getVar('city'),
        'denyReason' => $cmd->getFailString(),   // the locked deny reason
    ];
}
