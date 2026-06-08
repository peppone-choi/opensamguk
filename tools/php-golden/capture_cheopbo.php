<?php
/**
 * capture_cheopbo.php — che_첩보 (spy/reconnaissance) GeneralCommand golden capture
 * (devsam-core PHP, grand truth). ONE-SHOT, MANUAL HOST — NEVER CI.
 *
 * Sibling of capture_command_sabotage.php / capture_command_args.php.
 * che_첩보 is a GeneralCommand (3-arg buildGeneralCommandClass, 'generalCommand' seed)
 * gated by: NotBeNeutral, ReqGeneralGold/Rice (=develcost*3 each), NotOccupiedDestCity.
 *
 * The run() method has NO success/fail RNG branch — it always succeeds and draws:
 *   1) nextRangeInt(1, 100) — exp
 *   2) nextRangeInt(1, 70)  — ded
 *
 * Log lines vary by distance:
 *   dist<=1: 5 acting lines (info + cityBrief + cityDevel + crewTypes + techDiff)
 *   dist==2: 3 acting lines (info + cityBrief + cityDevel)
 *   dist>=3: 2 acting lines (info + cityBrief)
 * Plus 1 broadcast line (globalActionLog).
 *
 * The harness drives the SAME real path: buildGeneralCommandClass → $cmd->run($rng)
 * with RandUtilDrawRecorder for draw recording.
 */

namespace sammo;

require __DIR__ . '/_boot.php';
require __DIR__ . '/RandUtilDrawRecorder.php';

use function \sammo\searchDistance;

$opts   = getopt('', ['command:', 'out-dir:']);
$onlyCmd = $opts['command'] ?? null;
$outDir  = $opts['out-dir'] ?? (__DIR__ . '/../../logic/src/test/resources/golden/military');
if (!is_dir($outDir)) { mkdir($outDir, 0775, true); }

$db = DB::db();
$hiddenSeed = UniqueConst::$hiddenSeed;
$gameStor = KVStorage::getStorage($db, 'game_env');
[$year, $startYear, $monthBase, $develCost] = $gameStor->getValuesAsArray(['year','startyear','month','develcost']);

function hardAssert(bool $cond, string $msg): void {
    if (!$cond) { fwrite(STDERR, "CHEOPBO HARD-ASSERT FAILED: {$msg}\n"); exit(2); }
}
function planMiss(string $raw, string $why): void {
    fwrite(STDERR, "PLAN-MISS {$raw}: {$why}\n");
}
function isNoneField(?string $v): bool { return $v === null || $v === '' || $v === 'None'; }

function assertModuleFree(General $g): void {
    hardAssert(isNoneField($g->getVar('special')),  'general has special module');
    hardAssert(isNoneField($g->getVar('special2')), 'general has special2 module');
    hardAssert(isNoneField($g->getVar('personal')), 'general has personal module');
    foreach (($g->getItems() ?? []) as $slot => $item) {
        hardAssert($item instanceof \sammo\ActionItem\None, "equipment slot {$slot} not a None item");
    }
}

function logActionRows(object $db, int $gid): array {
    $rows = $db->query('SELECT text FROM general_record WHERE general_id=%i AND log_type=%s ORDER BY id', $gid, 'action');
    $out = []; foreach ($rows as $r) $out[] = $r['text']; return $out;
}
function globalActionRowsSince(object $db, int $afterId): array {
    $rows = $db->query('SELECT id, text FROM general_record WHERE general_id=0 AND log_type=%s AND id>%i ORDER BY id', 'history', $afterId);
    $out = []; foreach ($rows as $r) $out[] = $r['text']; return $out;
}
function maxGlobalActionId(object $db): int {
    return (int)($db->queryFirstField('SELECT COALESCE(MAX(id),0) FROM general_record WHERE general_id=0 AND log_type=%s', 'history'));
}
function setGameMonth(object $db, int $month): array {
    $gs = KVStorage::getStorage($db, 'game_env'); $gs->month = $month; $gs->resetCache();
    return KVStorage::getStorage($db, 'game_env')->getAll();
}
function expBandLo(int $L): int { return $L < 10 ? $L * 100 : 10 * $L * $L; }
function expBandHi(int $L): int { return $L < 10 ? ($L + 1) * 100 : 10 * ($L + 1) * ($L + 1); }
function dedBandLo(int $L): int { return $L <= 0 ? 0 : (10 * ($L - 1)) * (10 * ($L - 1)); }
function dedBandHi(int $L): int { return (10 * $L) * (10 * $L); }

/**
 * Module-free + no-level-cross precondition (static input; computed golden stays real PHP).
 * che_첩보 adds up to +100 exp / +70 ded. Position at low edge of high band.
 * exp level 56: band-low 31360, width 1130 >> +100.
 * ded level 13: band-low 14401, width 2500 >> +70.
 */
function applyPrecondition(object $db, int $gid): array {
    $expFloor = 10 * 56 * 56;
    $dedFloor = (10 * 12) * (10 * 12) + 1;
    $db->update('general', [
        'personal'   => 'None',
        'experience' => $expFloor,
        'dedication' => $dedFloor,
        'explevel'   => getExpLevel($expFloor),
        'dedlevel'   => getDedLevel($dedFloor),
    ], 'no=%i', $gid);
    return $db->queryFirstRow('SELECT * FROM general WHERE no=%i', $gid);
}

function snapshotGeneralCity(General $g, ?array $city): array {
    $snap = ['general' => [
        'gold' => $g->getVar('gold'), 'rice' => $g->getVar('rice'),
        'crew' => $g->getVar('crew'), 'train' => $g->getVar('train'), 'atmos' => $g->getVar('atmos'),
        'experience' => $g->getVar('experience'), 'dedication' => $g->getVar('dedication'),
        'leadership_exp' => $g->getVar('leadership_exp'), 'strength_exp' => $g->getVar('strength_exp'),
        'intel_exp' => $g->getVar('intel_exp'), 'explevel' => $g->getVar('explevel'),
        'nation' => $g->getVar('nation'), 'officer_level' => $g->getVar('officer_level'),
        'city' => $g->getVar('city'), 'troop' => $g->getVar('troop'),
        'injury' => $g->getVar('injury'),
        'leadership' => $g->getVar('leadership'), 'strength' => $g->getVar('strength'), 'intel' => $g->getVar('intel'),
        'max_domestic_critical' => $g->getAuxVar('max_domestic_critical') ?? 0,
    ]];
    if ($city !== null) {
        $snap['city'] = ['comm'=>$city['comm'],'agri'=>$city['agri'],'pop'=>$city['pop'],
            'comm_max'=>$city['comm_max'],'agri_max'=>$city['agri_max'],
            'def'=>$city['def'],'wall'=>$city['wall'],'def_max'=>$city['def_max'],'wall_max'=>$city['wall_max'],
            'secu'=>$city['secu'],'secu_max'=>$city['secu_max'],'level'=>$city['level'],
            'trust'=>$city['trust'],'nation'=>$city['nation'],'front'=>$city['front'],'state'=>$city['state']];
    }
    return $snap;
}

function snapshotWorld(object $db): array {
    return [
        'generals'   => $db->query('SELECT * FROM general'),
        'cities'     => $db->query('SELECT * FROM city'),
        'nations'    => $db->query('SELECT * FROM nation'),
        'diplomacy'  => $db->query('SELECT * FROM diplomacy'),
        'maxGlobalId'=> maxGlobalActionId($db),
    ];
}

function restoreWorld(object $db, array $w): void {
    foreach ($w['generals'] as $r) $db->update('general', $r, 'no=%i', (int)$r['no']);
    foreach ($w['cities'] as $r)   $db->update('city', $r, 'city=%i', (int)$r['city']);
    foreach ($w['nations'] as $r)  $db->update('nation', $r, 'nation=%i', (int)$r['nation']);
    $db->query('DELETE FROM diplomacy');
    foreach ($w['diplomacy'] as $r) $db->insert('diplomacy', $r);
    $db->delete('general_record', 'log_type=%s AND id>%i', 'history', $w['maxGlobalId']);
    $db->query('DELETE FROM general_record WHERE log_type=%s', 'action');
}

function resetClock(object $db, array $baseEnv): void {
    $gs = KVStorage::getStorage($db, 'game_env');
    $gs->year = (int)$baseEnv['year'];
    $gs->month = (int)$baseEnv['month'];
    $gs->resetCache();
}

/**
 * Find the best module-free officer with an enemy city within dist 2.
 * Returns [gid, arg, preFn, year] or null.
 */
function planFor(object $db, string $raw, array $env): ?array {
    $actorRows = $db->query(
        "SELECT g.no, g.nation, g.city FROM general g JOIN city c ON g.city=c.city
           WHERE c.nation<>0 AND c.supply=1 AND g.nation=c.nation AND g.officer_level>=1
             AND c.trust=floor(c.trust)
             AND (g.special='None' OR g.special='' OR g.special IS NULL)
             AND (g.special2='None' OR g.special2='' OR g.special2 IS NULL)
           ORDER BY g.no");

    foreach ($actorRows as $a) {
        $gid = (int)$a['no']; $nid = (int)$a['nation']; $cid = (int)$a['city'];
        $dist = searchDistance($cid, 2, false);
        foreach ($dist as $destCity => $d) {
            $destCity = (int)$destCity; $d = (float)$d;
            $dc = $db->queryFirstRow('SELECT nation, supply FROM city WHERE city=%i', $destCity);
            $destNat = (int)$dc['nation'];
            if ($destNat === 0 || $destNat === $nid) continue;
            return [$gid, ['destCityID' => $destCity], null, null];
        }
    }
    return null;
}

/** Capture ONE che_첩보 case. */
function captureCase(object $db, string $raw, int $gid, ?array $arg, ?callable $preFn,
                     int $year, int $month, string $hiddenSeed): ?array {
    $env = setGameMonth($db, $month);
    $gs = KVStorage::getStorage($db, 'game_env'); $gs->year = $year; $gs->resetCache();
    $env = KVStorage::getStorage($db, 'game_env')->getAll();

    $db->query('DELETE FROM general_record WHERE log_type=%s', 'action');

    applyPrecondition($db, $gid);
    if ($preFn) $preFn($db);
    \sammo\refreshNationStaticInfo();

    $general = General::createObjFromDB($gid);
    assertModuleFree($general);
    $cityId = (int)$general->getVar('city');
    $city = $cityId ? $db->queryFirstRow('SELECT * FROM city WHERE city=%i', $cityId) : null;
    if ($city !== null) hardAssert($city['trust'] == floor($city['trust']), "city {$cityId} trust not integer");

    $explevelBefore = $general->getVar('explevel');
    $dedlevelBefore = $general->getVar('dedlevel');

    hardAssert((int)$general->getVar('nation') !== 0, "{$raw}: neutral actor");

    $cmd = buildGeneralCommandClass($raw, $general, $env, $arg);
    hardAssert($cmd->getRawClassName() === $raw, "factory returned {$cmd->getRawClassName()} for {$raw}");
    if (!$cmd->hasFullConditionMet()) {
        $fail = method_exists($cmd, 'getFailString') ? (string)@$cmd->getFailString() : '';
        planMiss($raw, "full condition not met (" . str_replace("\n", " ", $fail) . ")");
        return null;
    }

    $reqGold = $cmd->getCost()[0] ?? null;

    $before = snapshotGeneralCity($general, $city);
    $itemCountBefore = count($general->getItems() ?? []);
    $globalIdBefore = maxGlobalActionId($db);

    $seedString = Util::simpleSerialize($hiddenSeed, 'generalCommand', $year, $month, $gid, $cmd->getRawClassName());
    $rng = new RandUtilDrawRecorder(new LiteHashDRBG($seedString));

    $destCityID = (int)$arg['destCityID'];
    $dist = searchDistance($cityId, 2, false)[$destCityID] ?? 99;

    $ok = $cmd->run($rng);
    hardAssert($ok === true, "{$raw}: run() returned false — che_첩보 should always succeed");

    $actingLines = logActionRows($db, $gid);
    // Expected lines by distance
    $expectedLines = ($dist <= 1) ? 5 : (($dist == 2) ? 3 : 2);
    hardAssert(count($actingLines) === $expectedLines,
        "{$raw}: expected {$expectedLines} acting line(s), got " . count($actingLines) . " :: " . implode(' | ', $actingLines));
    hardAssert(count($general->getItems() ?? []) === $itemCountBefore, "{$raw}: equipment slot count changed");

    $broadcastLines = globalActionRowsSince($db, $globalIdBefore);
    hardAssert(count($broadcastLines) === 1, "{$raw}: expected 1 broadcast line, got " . count($broadcastLines));

    $generalAfter = General::createObjFromDB($gid);
    $cityAfter = $cityId ? $db->queryFirstRow('SELECT * FROM city WHERE city=%i', $cityId) : null;
    $after = snapshotGeneralCity($generalAfter, $cityAfter);
    hardAssert($generalAfter->getVar('explevel') === $explevelBefore, "{$raw}: exp level crossed");
    hardAssert($generalAfter->getVar('dedlevel') === $dedlevelBefore, "{$raw}: ded level crossed");

    // Spy info stored in nation table
    $nationID = (int)$general->getVar('nation');
    $rawSpy = $db->queryFirstField('SELECT spy FROM nation WHERE nation = %i', $nationID);
    $spyInfo = Json::decode($rawSpy) ?? [];

    $destCityAfter = $db->queryFirstRow('SELECT * FROM city WHERE city=%i', $destCityID);

    $case = [
        'case'=>$raw, 'command'=>$raw, 'ctor'=>'general', 'scope'=>'generalCommand',
        'generalId'=>$gid, 'cityId'=>$cityId,
        'destCityId'=>$destCityID, 'destNationId'=>(int)$db->queryFirstField('SELECT nation FROM city WHERE city=%i', $destCityID),
        'cityDistance'=>$dist, 'result'=>'success',
        'env'=>['year'=>$year,'startYear'=>(int)$env['startyear'],'month'=>$month,'develCost'=>(int)$env['develcost']],
        'arg'=>$arg, 'seedString'=>$seedString, 'reqGold'=>$reqGold,
        'before'=>$before, 'after'=>$after,
        'destCityBefore'=>['city'=>cityColsOf($db->queryFirstRow('SELECT * FROM city WHERE city=%i', $destCityID))],
        'destCityAfter'=>['city'=>cityColsOf($destCityAfter)],
        'spyAfter'=>$spyInfo,
        'logLines'=>$actingLines, 'broadcastLines'=>$broadcastLines,
        'draws'=>['draw_count'=>$rng->getDrawCount(), 'draw_stream'=>$rng->getDrawStream()],
    ];

    return $case;
}

function cityColsOf(array $city): array {
    return ['comm'=>$city['comm'],'agri'=>$city['agri'],'pop'=>$city['pop'],
        'comm_max'=>$city['comm_max'],'agri_max'=>$city['agri_max'],
        'def'=>$city['def'],'wall'=>$city['wall'],'def_max'=>$city['def_max'],'wall_max'=>$city['wall_max'],
        'secu'=>$city['secu'],'secu_max'=>$city['secu_max'],'level'=>$city['level'],
        'trust'=>$city['trust'],'nation'=>$city['nation'],'front'=>$city['front'],'state'=>$city['state']];
}

// ── capture che_첩보 ────────────────────────────────────────────────────────────
$raw = 'che_첩보';

$baseEnv = $gameStor->getAll();
$world = snapshotWorld($db);

$plan = planFor($db, $raw, $baseEnv);
if ($plan === null) {
    planMiss($raw, "no actor/plan on this install");
    restoreWorld($db, $world);
    resetClock($db, $baseEnv);
    exit(1);
}
[$gid, $arg, $preFn, $yearOverride] = $plan;
$year = $yearOverride ?? (int)$baseEnv['year'];

$case = captureCase($db, $raw, $gid, $arg, $preFn, $year, (int)$baseEnv['month'], $hiddenSeed);

restoreWorld($db, $world);
resetClock($db, $baseEnv);
\sammo\refreshNationStaticInfo();

if ($case === null) {
    exit(1);
}

$out = ['hiddenSeed'=>$hiddenSeed, 'command'=>$raw,
    'env'=>['year'=>(int)$baseEnv['year'],'startYear'=>$startYear,'develCost'=>$develCost], 'cases'=>[$case]];
$outPath = $outDir . '/' . $raw . '-fixtures.json';
file_put_contents($outPath, Json::encode($out, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES));
fwrite(STDERR, "wrote " . basename($outPath) . " (1 case, " . $case['draws']['draw_count'] . " draws, " . $case['result'] . ")\n");
