<?php
/**
 * capture_command_danryeon.php — che_단련 golden capture (devsam-core PHP, grand truth).
 *
 * ONE-SHOT, MANUAL HOST STEP — NEVER CI.
 *
 * che_단련 is a GeneralCommand (3-arg buildGeneralCommandClass, 'generalCommand' seed)
 * gated by: NotBeNeutral, ReqGeneralCrew, ReqGeneralValue(train>=defaultTrainLow),
 * ReqGeneralValue(atmos>=defaultAtmosLow), ReqGeneralGold, ReqGeneralRice.
 *
 * The harness drives the SAME real path TurnExecutionHelper does:
 *   $cmd  = buildGeneralCommandClass('che_단련', $general, $env, null);
 *   $seed = Util::simpleSerialize(hiddenSeed,'generalCommand',year,month,gid,'che_단련');
 *   $cmd->run(new RandUtilDrawRecorder(new LiteHashDRBG($seed)));
 *
 * che_단련 run() DRAW STREAM:
 *   1) choiceUsingWeightPair — picks success/normal/fail branch + multiplier
 *   2) choiceUsingWeight — picks which stat_exp to increment (leadership/strength/intel)
 *
 * The captured outcome is whatever the install seed produces — NOT forced.
 *
 * HARD assertions: module-free acting general, no level cross, exact acting action-line
 * count = 1, integer trust, equipment slot count unchanged.
 *
 * Emits: golden/p2/che_단련-fixtures.json
 */

namespace sammo;

require __DIR__ . '/_boot.php';
require __DIR__ . '/RandUtilDrawRecorder.php';

$opts   = getopt('', ['command:', 'out-dir:']);
$onlyCmd = $opts['command'] ?? null;
$outDir  = $opts['out-dir'] ?? (__DIR__ . '/../../logic/src/test/resources/golden/p2');
if (!is_dir($outDir)) { mkdir($outDir, 0775, true); }

$db = DB::db();
$hiddenSeed = UniqueConst::$hiddenSeed;
$gameStor = KVStorage::getStorage($db, 'game_env');
[$year, $startYear, $monthBase, $develCost] = $gameStor->getValuesAsArray(['year','startyear','month','develcost']);

function hardAssert(bool $cond, string $msg): void {
    if (!$cond) { fwrite(STDERR, "DANRYEON HARD-ASSERT FAILED: {$msg}\n"); exit(2); }
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

/** Module-free + no-level-cross precondition (static input; computed golden stays real PHP). */
function applyPrecondition(object $db, int $gid): array {
    $row = $db->queryFirstRow('SELECT experience, dedication FROM general WHERE no=%i', $gid);
    $el = getExpLevel((int)$row['experience']); $dl = getDedLevel((int)$row['dedication']);
    $emid = (int)(($el < 10 ? $el * 100 : 10 * $el * $el) + (($el < 10 ? ($el + 1) * 100 : 10 * ($el + 1) * ($el + 1)) - ($el < 10 ? $el * 100 : 10 * $el * $el)) * 0.4);
    $dmid = (int)(($dl <= 0 ? 0 : (10 * ($dl - 1)) * (10 * ($dl - 1))) + ((10 * $dl) * (10 * $dl) - ($dl <= 0 ? 0 : (10 * ($dl - 1)) * (10 * ($dl - 1)))) * 0.4);
    $db->update('general', [
        'personal'=>'None','experience'=>$emid,'dedication'=>$dmid,
        'explevel'=>getExpLevel($emid),'dedlevel'=>getDedLevel($dmid),
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

/** Snapshot the FULL world rows so each capture restores byte-for-byte. */
function snapshotWorld(object $db): array {
    return [
        'generals'   => $db->query('SELECT * FROM general'),
        'cities'     => $db->query('SELECT * FROM city'),
        'nations'    => $db->query('SELECT * FROM nation'),
        'diplomacy'  => $db->query('SELECT * FROM diplomacy'),
        'maxGlobalId'=> maxGlobalActionId($db),
    ];
}

/** Restore the world to the pre-capture snapshot. */
function restoreWorld(object $db, array $w): void {
    foreach ($w['generals'] as $r) $db->update('general', $r, 'no=%i', (int)$r['no']);
    foreach ($w['cities'] as $r)   $db->update('city', $r, 'city=%i', (int)$r['city']);
    foreach ($w['nations'] as $r)  $db->update('nation', $r, 'nation=%i', (int)$r['nation']);
    $db->query('DELETE FROM diplomacy');
    foreach ($w['diplomacy'] as $r) $db->insert('diplomacy', $r);
    $db->delete('general_record', 'log_type=%s AND id>%i', 'history', $w['maxGlobalId']);
    $db->query('DELETE FROM general_record WHERE log_type=%s', 'action');
}

/**
 * PLAN registry for che_단련.
 *
 * The actor is the first module-free officer (supplied, owned city). Static-input
 * precondition sets crew/train/atmos/gold/rice so the gate passes. The score/draws
 * are 100% real PHP for the positioned inputs.
 */
function planFor(object $db, array $env): ?array {
    $gid = (int)$db->queryFirstField(
        "SELECT g.no FROM general g JOIN city c ON g.city=c.city
         WHERE c.nation<>0 AND c.supply=1 AND g.nation=c.nation AND g.officer_level>=1
           AND (g.special='None' OR g.special='' OR g.special IS NULL)
           AND (g.special2='None' OR g.special2='' OR g.special2 IS NULL)
         ORDER BY g.no LIMIT 1");
    if (!$gid) return null;

    $pre = function(object $db, int $gid) use ($env) {
        $db->update('general', [
            'personal' => 'None',
            'crew' => 5000,
            'train' => 60,
            'atmos' => 60,
            'gold' => 50000,
            'rice' => 50000,
        ], 'no=%i', $gid);
    };
    return [$gid, null, $pre];
}

/** Capture ONE che_단련 case. */
function captureCase(object $db, string $raw, int $gid, ?array $arg, ?callable $preFn,
                     int $year, int $month, string $hiddenSeed): ?array {
    $env = setGameMonth($db, $month);
    $gs = KVStorage::getStorage($db, 'game_env'); $gs->year = $year; $gs->resetCache();
    $env = KVStorage::getStorage($db, 'game_env')->getAll();

    $db->query('DELETE FROM general_record WHERE log_type=%s', 'action');

    applyPrecondition($db, $gid);
    if ($preFn) $preFn($db, $gid);
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

    $ok = $cmd->run($rng);
    hardAssert($ok === true, "{$raw}: run() returned false");

    $actingLines = logActionRows($db, $gid);
    hardAssert(count($actingLines) === 1,
        "{$raw}: expected 1 acting line, got " . count($actingLines) . " :: " . implode(' | ', $actingLines));
    hardAssert(count($general->getItems() ?? []) === $itemCountBefore, "{$raw}: equipment slot count changed");

    $broadcastLines = globalActionRowsSince($db, $globalIdBefore);

    $generalAfter = General::createObjFromDB($gid);
    $cityAfter = $cityId ? $db->queryFirstRow('SELECT * FROM city WHERE city=%i', $cityId) : null;
    $after = snapshotGeneralCity($generalAfter, $cityAfter);
    hardAssert($generalAfter->getVar('explevel') === $explevelBefore, "{$raw}: exp level crossed");
    hardAssert($generalAfter->getVar('dedlevel') === $dedlevelBefore, "{$raw}: ded level crossed");

    return [
        'case'=>$raw, 'command'=>$raw, 'ctor'=>'general', 'scope'=>'generalCommand',
        'generalId'=>$gid, 'cityId'=>$cityId,
        'env'=>['year'=>$year,'startYear'=>(int)$env['startyear'],'month'=>$month,'develCost'=>(int)$env['develcost']],
        'arg'=>$arg, 'seedString'=>$seedString, 'reqGold'=>$reqGold,
        'before'=>$before, 'after'=>$after,
        'logLines'=>$actingLines, 'broadcastLines'=>$broadcastLines,
        'draws'=>['draw_count'=>$rng->getDrawCount(), 'draw_stream'=>$rng->getDrawStream()],
    ];
}

function resetClock(object $db, array $baseEnv): void {
    $gs = KVStorage::getStorage($db, 'game_env');
    $gs->year = (int)$baseEnv['year'];
    $gs->month = (int)$baseEnv['month'];
    $gs->resetCache();
}

// ── capture che_단련 ──────────────────────────────────────────────────────────
$raw = 'che_단련';
$baseEnv = $gameStor->getAll();

$world = snapshotWorld($db);

$plan = planFor($db, $baseEnv);
if ($plan === null) {
    planMiss($raw, "no actor/plan on this install");
    exit(1);
}
[$gid, $arg, $preFn] = $plan;

$case = captureCase($db, $raw, $gid, $arg, $preFn, (int)$baseEnv['year'], (int)$baseEnv['month'], $hiddenSeed);

restoreWorld($db, $world);
resetClock($db, $baseEnv);
\sammo\refreshNationStaticInfo();

if ($case === null) {
    fwrite(STDERR, "no danryeon command captured\n");
    exit(1);
}

$out = ['hiddenSeed'=>$hiddenSeed, 'command'=>$raw,
    'env'=>['year'=>(int)$baseEnv['year'],'startYear'=>$startYear,'develCost'=>$develCost], 'cases'=>[$case]];
$outPath = $outDir . '/' . $raw . '-fixtures.json';
file_put_contents($outPath, Json::encode($out, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES));
fwrite(STDERR, "wrote " . basename($outPath) . " (1 case, " . $case['draws']['draw_count'] . " draws)\n");
