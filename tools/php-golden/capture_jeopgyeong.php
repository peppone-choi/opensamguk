<?php
namespace sammo;
require '/work/tools/php-golden/_boot.php';
require '/work/tools/php-golden/RandUtilDrawRecorder.php';

$db = DB::db();
$hiddenSeed = UniqueConst::$hiddenSeed;
$gameStor = KVStorage::getStorage($db, 'game_env');
[$year, $startYear, $monthBase, $develCost] = $gameStor->getValuesAsArray(['year','startyear','month','develcost']);

function hardAssert(bool $cond, string $msg): void {
    if (!$cond) { fwrite(STDERR, "HARD-ASSERT FAILED: {$msg}\n"); exit(2); }
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

function applyPrecondition(object $db, int $gid): array {
    $row = $db->queryFirstRow('SELECT experience, dedication FROM general WHERE no=%i', $gid);
    $el = getExpLevel((int)$row['experience']); $dl = getDedLevel((int)$row['dedication']);
    $emid = (int)(($el < 10 ? $el * 100 : 10 * $el * $el) + (($el < 10 ? 100 : 10 * (2 * $el + 1))) * 0.4);
    $dmid = (int)((($dl <= 0 ? 0 : (10 * ($dl - 1)) * (10 * ($dl - 1)))) + (((10 * $dl) * (10 * $dl)) - (($dl <= 0 ? 0 : (10 * ($dl - 1)) * (10 * ($dl - 1))))) * 0.4);
    $db->update('general', ['personal'=>'None','experience'=>$emid,'dedication'=>$dmid,'explevel'=>getExpLevel($emid),'dedlevel'=>getDedLevel($dmid)], 'no=%i', $gid);
    return $db->queryFirstRow('SELECT * FROM general WHERE no=%i', $gid);
}

function snapshotGeneralCity(General $g, ?array $city): array {
    $snap = ['general' => [
        'gold' => $g->getVar('gold'), 'rice' => $g->getVar('rice'),
        'crew' => $g->getVar('crew'), 'train' => $g->getVar('train'), 'atmos' => $g->getVar('atmos'),
        'experience' => $g->getVar('experience'), 'dedication' => $g->getVar('dedication'),
        'explevel' => $g->getVar('explevel'), 'dedlevel' => $g->getVar('dedlevel'),
        'nation' => $g->getVar('nation'), 'officer_level' => $g->getVar('officer_level'),
        'city' => $g->getVar('city'), 'troop' => $g->getVar('troop'),
    ]];
    if ($city !== null) {
        $snap['city'] = ['comm'=>$city['comm'],'agri'=>$city['agri'],'pop'=>$city['pop'],
            'comm_max'=>$city['comm_max'],'agri_max'=>$city['agri_max'],'trust'=>$city['trust'],'nation'=>$city['nation']];
    }
    return $snap;
}

function snapshotWorld(object $db): array {
    return [
        'generals'   => $db->query('SELECT * FROM general'),
        'cities'     => $db->query('SELECT * FROM city'),
        'nations'    => $db->query('SELECT * FROM nation'),
        'maxGlobalId'=> maxGlobalActionId($db),
    ];
}

function restoreWorld(object $db, array $w): void {
    foreach ($w['generals'] as $r) $db->update('general', $r, 'no=%i', (int)$r['no']);
    foreach ($w['cities'] as $r)   $db->update('city', $r, 'city=%i', (int)$r['city']);
    foreach ($w['nations'] as $r)  $db->update('nation', $r, 'nation=%i', (int)$r['nation']);
    $db->delete('general_record', 'log_type=%s AND id>%i', 'history', $w['maxGlobalId']);
    $db->query('DELETE FROM general_record WHERE log_type=%s', 'action');
}

function resetClock(object $db, array $baseEnv): void {
    $gs = KVStorage::getStorage($db, 'game_env');
    $gs->year = (int)$baseEnv['year'];
    $gs->month = (int)$baseEnv['month'];
    $gs->resetCache();
}

// ── CAPTURE ───────────────────────────────────────────────────────────────

$raw = 'che_접경귀환';
$month = (int)$monthBase;

// Pick a module-free officer from nation 2
$officer = $db->queryFirstRow(
    "SELECT g.no, g.nation, g.city FROM general g JOIN city c ON g.city=c.city
     WHERE c.nation<>0 AND c.supply=1 AND g.nation=c.nation AND g.officer_level>=1
       AND (g.special='None' OR g.special='' OR g.special IS NULL)
       AND (g.special2='None' OR g.special2='' OR g.special2 IS NULL)
     ORDER BY g.no LIMIT 1");
$gid = (int)$officer['no'];
$nid = (int)$officer['nation'];
$origCity = (int)$officer['city'];

// Position the actor into an enemy city (nation 1) that has friendly cities within dist 3
// City 3 (nation 1) has city 1 (nation 2, friendly) at dist 2
$enemyCity = 3; // nation 1 city

$world = snapshotWorld($db);

// Move the actor to the enemy city (static-input precondition)
$db->update('general', ['city'=>$enemyCity], 'no=%i', $gid);

$env = setGameMonth($db, $month);

// defensive freshness
$db->query('DELETE FROM general_record WHERE log_type=%s', 'action');

applyPrecondition($db, $gid);
\sammo\refreshNationStaticInfo();

$general = General::createObjFromDB($gid);
assertModuleFree($general);
$cityId = (int)$general->getVar('city');
$city = $cityId ? $db->queryFirstRow('SELECT * FROM city WHERE city=%i', $cityId) : null;
if ($city !== null) hardAssert($city['trust'] == floor($city['trust']), "city {$cityId} trust not integer");

$explevelBefore = $general->getVar('explevel');
$dedlevelBefore = $general->getVar('dedlevel');

hardAssert((int)$general->getVar('nation') !== 0, "che_접경귀환: neutral actor");

$cmd = buildGeneralCommandClass($raw, $general, $env, null);
hardAssert($cmd->getRawClassName() === $raw, "factory returned {$cmd->getRawClassName()} for {$raw}");
if (!$cmd->hasFullConditionMet()) {
    $fail = method_exists($cmd, 'getFailString') ? (string)@$cmd->getFailString() : '';
    fwrite(STDERR, "PLAN-MISS che_접경귀환: full condition not met (" . str_replace("\n", " ", $fail) . ")\n");
    restoreWorld($db, $world);
    resetClock($db, $gameStor->getAll());
    exit(1);
}

$before = snapshotGeneralCity($general, $city);
$itemCountBefore = count($general->getItems() ?? []);
$globalIdBefore = maxGlobalActionId($db);

$seedString = Util::simpleSerialize($hiddenSeed, 'generalCommand', $year, $month, $gid, $cmd->getRawClassName());
$rng = new RandUtilDrawRecorder(new LiteHashDRBG($seedString));

$ok = $cmd->run($rng);
$result = ($ok === true) ? 'success' : 'fail';

$actingLines = logActionRows($db, $gid);
hardAssert(count($actingLines) === 1, "che_접경귀환: expected 1 acting line, got " . count($actingLines) . " :: " . implode(' | ', $actingLines));
hardAssert(count($general->getItems() ?? []) === $itemCountBefore, "che_접경귀환: equipment slot count changed");

$broadcastLines = globalActionRowsSince($db, $globalIdBefore);

$generalAfter = General::createObjFromDB($gid);
$cityAfter = $cityId ? $db->queryFirstRow('SELECT * FROM city WHERE city=%i', $cityId) : null;
$after = snapshotGeneralCity($generalAfter, $cityAfter);
hardAssert($generalAfter->getVar('explevel') === $explevelBefore, "che_접경귀환: exp level crossed");
hardAssert($generalAfter->getVar('dedlevel') === $dedlevelBefore, "che_접경귀환: ded level crossed");

// The dest city is chosen by RNG; capture it from the after-state
$destCityId = (int)$generalAfter->getVar('city');

$case = [
    'case'=>$raw, 'command'=>$raw, 'ctor'=>'general', 'scope'=>'generalCommand',
    'generalId'=>$gid, 'cityId'=>$cityId,
    'env'=>['year'=>$year,'startYear'=>$startYear,'month'=>$month,'develCost'=>$develCost],
    'arg'=>null, 'seedString'=>$seedString, 'reqGold'=>0,
    'before'=>$before, 'after'=>$after,
    'logLines'=>$actingLines, 'broadcastLines'=>$broadcastLines,
    'draws'=>['draw_count'=>$rng->getDrawCount(), 'draw_stream'=>$rng->getDrawStream()],
];

$out = ['hiddenSeed'=>$hiddenSeed, 'command'=>$raw,
    'env'=>['year'=>$year,'startYear'=>$startYear,'develCost'=>$develCost], 'cases'=>[$case]];

$outDir = '/work/logic/src/test/resources/golden/military';
if (!is_dir($outDir)) { mkdir($outDir, 0775, true); }
$outPath = $outDir . '/' . $raw . '-fixtures.json';
file_put_contents($outPath, Json::encode($out, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES | JSON_PRETTY_PRINT));
fwrite(STDERR, "wrote " . basename($outPath) . " (1 case, " . $rng->getDrawCount() . " draws, result=" . $result . ")\n");

restoreWorld($db, $world);
resetClock($db, $gameStor->getAll());
\sammo\refreshNationStaticInfo();
