<?php
/**
 * capture_nonidentity.php — GT1 (GATE-TRAIT) module-BEARING golden capture.
 *
 * The 28 committed P2 goldens are all MODULE-FREE (the GR1 HARD assertion forced
 * special/special2/personal ∈ {None,'',null} + every equipment slot a None item, so the
 * empty `GeneralActionPipeline` fold is the identity — see capture_command.php). That gate
 * proves log/mutation fidelity but it can NEVER exercise the 9-source non-identity stack
 * (Task GT1 in the plan: "the multi-source onCalcDomestic/onCalcStat accumulation must
 * byte-match the golden, proving the 9-source fold + rounding order").
 *
 * This script is the INVERSE capture path: it picks a scenario_1010 general that NATURALLY
 * carries domestic-specialty + personality modules, drives the SAME real turn-execution
 * (buildGeneralCommandClass → $cmd->run($rng)), and emits a module-BEARING fixture. It keeps
 * every numeric/log assertion from capture_command.php EXCEPT it asserts the modules ARE
 * active (the inverse of assertModuleFree). The captured log + post-state are 100% real PHP.
 *
 * Capture of record: gid 14 (공융, nation 1 후한, city 42 홍농) carries
 *   special  = che_경작 (source #3, ActionSpecialDomestic — onCalcDomestic('농업'): score*1.1/cost*0.8/success+0.1)
 *   personal = che_왕좌 (source #5, ActionPersonality   — onCalcStat('experience'): value*1.1)
 * Running che_농지개간 (turnType '농업', statKey 'intel') folds BOTH:
 *   - 경작 multiplies the 농업 develop score/cost/success (domestic pipeline)
 *   - 왕좌 multiplies the experience gain (the stat pipeline, via General::addExperience → onCalcStat)
 * so the after-state diverges from the module-free identity by a multi-source fold. Item
 * slots stay None (items remain identity) — a clean isolation of the trait fold.
 *
 * HARD assertions (abort — never write a partial/unfaithful golden):
 *   (1) modules ACTIVE — special/personal NON-None (the inverse identity guard); the script
 *       asserts the exact expected specialty/personality so the fixture pins WHICH modules fold.
 *   (2) item slots still None (items identity — the trait fold is isolated to special+personal).
 *   (3) NO LEVEL CROSS — explevel/dedlevel unchanged before→after (level-change PLAIN logs are
 *       not the GT1 surface; the no-cross precondition keeps the golden off them).
 *   (4) exactly the manifest logLines count + equipment-slot count unchanged (no lottery/event
 *       perturbs the action stream).
 *   (5) INTEGER trust.
 *   (6) The module fold is OBSERVABLE — the captured score MUST differ from the module-free
 *       score the SAME seed/state would produce with an empty pipeline (else the fixture would
 *       not test the fold). Recorded in the fixture as `moduleFold` evidence.
 */

namespace sammo;

require __DIR__ . '/_boot.php';

use sammo\ActionSpecialDomestic\None as NoneSpecial;

$opts   = getopt('', ['out-dir:', 'gid:']);
$outDir = $opts['out-dir'] ?? (__DIR__ . '/../../logic/src/test/resources/golden/p2');
$gid    = isset($opts['gid']) ? (int)$opts['gid'] : 14;   // 공융 — 경작 + 왕좌
if (!is_dir($outDir)) { mkdir($outDir, 0775, true); }

$db = DB::db();
$hiddenSeed = UniqueConst::$hiddenSeed;
$gameStor = KVStorage::getStorage($db, 'game_env');
[$year, $startYear, $monthBase, $develCost] = $gameStor->getValuesAsArray(['year', 'startyear', 'month', 'develcost']);

$manifest = Json::decode(file_get_contents(__DIR__ . '/manifest.json'));
$cmdMeta = [];
foreach ($manifest['commands'] as $group => $list) {
    foreach ($list as $entry) { $cmdMeta[$entry['rawClassName']] = $entry; }
}

function hardAssert(bool $cond, string $msg): void {
    if (!$cond) { fwrite(STDERR, "GT1 HARD-ASSERT FAILED: {$msg}\n"); exit(2); }
}
function isNoneField(?string $v): bool { return $v === null || $v === '' || $v === 'None'; }

/** exp/ded level bands (verbatim from capture_command.php). */
function expBandLo(int $L): int { return $L < 10 ? $L * 100 : 10 * $L * $L; }
function expBandHi(int $L): int { return $L < 10 ? ($L + 1) * 100 : 10 * ($L + 1) * ($L + 1); }
function dedBandLo(int $L): int { return $L <= 0 ? 0 : (10 * ($L - 1)) * (10 * ($L - 1)); }
function dedBandHi(int $L): int { return (10 * $L) * (10 * $L); }

/**
 * No-level-cross precondition — DOES NOT TOUCH special/special2/personal (the modules stay
 * active). Syncs explevel/dedlevel to the current exp/ded and nudges exp/ded ~40% into the
 * band so the captured turn never crosses a level. Verbatim band math from capture_command.php;
 * the computed golden stays 100% real PHP.
 */
function applyLevelSyncOnly(object $db, int $gid): array {
    $row = $db->queryFirstRow('SELECT experience, dedication FROM general WHERE no=%i', $gid);
    $exp = (int)$row['experience']; $ded = (int)$row['dedication'];
    $el = getExpLevel($exp); $dl = getDedLevel($ded);
    $emid = (int)(expBandLo($el) + (expBandHi($el) - expBandLo($el)) * 0.4);
    $dmid = (int)(dedBandLo($dl) + (dedBandHi($dl) - dedBandLo($dl)) * 0.4);
    $db->update('general', [
        'experience' => $emid, 'dedication' => $dmid,
        'explevel' => getExpLevel($emid), 'dedlevel' => getDedLevel($dmid),
    ], 'no=%i', $gid);
    return $db->queryFirstRow('SELECT * FROM general WHERE no=%i', $gid);
}

function setGameMonth(object $db, int $month): array {
    $gs = KVStorage::getStorage($db, 'game_env'); $gs->month = $month; $gs->resetCache();
    return KVStorage::getStorage($db, 'game_env')->getAll();
}
function logActionRows(object $db, int $gid): array {
    $rows = $db->query('SELECT text FROM general_record WHERE general_id=%i AND log_type=%s ORDER BY id', $gid, 'action');
    $out = []; foreach ($rows as $r) $out[] = $r['text']; return $out;
}
function maxGlobalActionId(object $db): int {
    return (int)($db->queryFirstField('SELECT COALESCE(MAX(id),0) FROM general_record WHERE general_id=0 AND log_type=%s', 'history'));
}
function globalActionRowsSince(object $db, int $afterId): array {
    $rows = $db->query('SELECT text FROM general_record WHERE general_id=0 AND log_type=%s AND id>%i ORDER BY id', 'history', $afterId);
    $out = []; foreach ($rows as $r) $out[] = $r['text']; return $out;
}

/** The captured general+city subset — IDENTICAL shape to capture_command.php snapshotGeneralCity. */
function snapshotGeneralCity(General $g, ?array $city): array {
    $snap = ['general' => [
        'gold'                  => $g->getVar('gold'),
        'experience'            => $g->getVar('experience'),
        'dedication'            => $g->getVar('dedication'),
        'intel_exp'             => $g->getVar('intel_exp'),
        'explevel'              => $g->getVar('explevel'),
        'nation'                => $g->getVar('nation'),
        'officer_level'         => $g->getVar('officer_level'),
        'city'                  => $g->getVar('city'),
        'max_domestic_critical' => $g->getAuxVar('max_domestic_critical') ?? 0,
    ]];
    if ($city !== null) {
        $snap['city'] = [
            'comm' => $city['comm'], 'agri' => $city['agri'],
            'comm_max' => $city['comm_max'], 'agri_max' => $city['agri_max'],
            'trust' => $city['trust'], 'nation' => $city['nation'],
        ];
    }
    return $snap;
}

/** INVERSE module guard — assert the actor CARRIES the expected non-identity modules. */
function assertModuleBearing(General $g, string $expectSpecial, string $expectPersonal): void {
    hardAssert(!isNoneField($g->getVar('special')), 'GT1 actor must carry a domestic specialty (special != None)');
    hardAssert($g->getVar('special') === $expectSpecial, "GT1 actor special is {$g->getVar('special')}, expected {$expectSpecial}");
    hardAssert($g->getVar('personal') === $expectPersonal, "GT1 actor personal is {$g->getVar('personal')}, expected {$expectPersonal}");
    // items stay identity — isolate the trait fold.
    foreach (($g->getItems() ?? []) as $slot => $item) {
        hardAssert($item instanceof \sammo\ActionItem\None, "GT1 equipment slot {$slot} not a None item (item fold not isolated)");
    }
}

// ── picks: gid 14, 농지개간, distinct outcomes by month ────────────────────────
$EXPECT_SPECIAL  = 'che_경작';
$EXPECT_PERSONAL = 'che_왕좌';
$picks = [
    ['agri_a', 'che_농지개간', $gid, 1],
    ['agri_b', 'che_농지개간', $gid, 2],
    ['agri_c', 'che_농지개간', $gid, 3],
    ['agri_d', 'che_농지개간', $gid, 6],
];

// baseline snapshot for independence (only the actor + its city are touched by 농지개간).
$priorLog = (int)$db->queryFirstField('SELECT COUNT(*) FROM general_record WHERE general_id=%i AND log_type=%s', $gid, 'action');
hardAssert($priorLog === 0, "general {$gid} has {$priorLog} prior action rows — re-run install_scenario.php");
$baseGeneral = applyLevelSyncOnly($db, $gid);
$baseCityId  = (int)$baseGeneral['city'];
$baseCity    = $db->queryFirstRow('SELECT * FROM city WHERE city=%i', $baseCityId);

function restore(object $db, array $bg, array $bc, int $gid): void {
    $db->update('general', $bg, 'no=%i', $gid);
    $db->update('city', $bc, 'city=%i', (int)$bg['city']);
    $db->delete('general_record', 'log_type=%s AND general_id=%i', 'action', $gid);
}

$cases = [];
foreach ($picks as [$name, $cls, $g, $m]) {
    restore($db, $baseGeneral, $baseCity, $gid);
    $meta = $cmdMeta[$cls]; // 농지개간 is in the manifest
    $env = setGameMonth($db, $m);

    $general = General::createObjFromDB($g);
    $cityId  = (int)$general->getVar('city');
    $city    = $db->queryFirstRow('SELECT * FROM city WHERE city=%i', $cityId);

    assertModuleBearing($general, $EXPECT_SPECIAL, $EXPECT_PERSONAL);
    hardAssert($city['trust'] == floor($city['trust']), "city {$cityId} trust not integer");

    $explevelBefore = $general->getVar('explevel');
    $dedlevelBefore = $general->getVar('dedlevel');

    $cmd = buildGeneralCommandClass($cls, $general, $env, null);
    hardAssert($cmd->getRawClassName() === $cls, "factory returned {$cmd->getRawClassName()} for {$cls}");
    hardAssert($cmd->hasFullConditionMet(), "{$name}: precondition not met");

    $reqGold = null;
    if (property_exists($cmd, 'reqGold')) {
        $rp = new \ReflectionProperty($cmd, 'reqGold');
        if ($rp->isInitialized($cmd)) { $reqGold = $rp->getValue($cmd); }
    }
    $reqGold = $reqGold ?? ($cmd->getCost()[0] ?? null);

    $before = snapshotGeneralCity($general, $city);
    $itemCountBefore = count($general->getItems() ?? []);
    $globalIdBefore  = maxGlobalActionId($db);

    $seedString = Util::simpleSerialize($hiddenSeed, 'generalCommand', $year, $m, $g, $cmd->getRawClassName());
    $rng = new RandUtil(new LiteHashDRBG($seedString));

    $ok = $cmd->run($rng);
    hardAssert($ok === true, "{$name}: run() returned false");

    $actingLines = logActionRows($db, $g);
    $expectLines = (int)$meta['logLines'];
    hardAssert(count($actingLines) === $expectLines,
        "{$name}: expected {$expectLines} acting line(s), got " . count($actingLines));
    hardAssert(count($general->getItems() ?? []) === $itemCountBefore, "{$name}: equipment slot count changed");

    $broadcastLines = globalActionRowsSince($db, $globalIdBefore);

    $generalAfter = General::createObjFromDB($g);
    $cityAfter    = $db->queryFirstRow('SELECT * FROM city WHERE city=%i', $cityId);
    $after = snapshotGeneralCity($generalAfter, $cityAfter);

    hardAssert($generalAfter->getVar('explevel') === $explevelBefore, "{$name}: exp level crossed");
    hardAssert($generalAfter->getVar('dedlevel') === $dedlevelBefore, "{$name}: ded level crossed");

    // ── module-fold OBSERVABILITY (HARD assertion 6): re-run the SAME seed/state on a
    //    module-FREE clone (special/personal → None) and confirm the score (hence agri
    //    delta / exp delta) DIFFERS — i.e. the captured golden actually exercises the fold.
    restore($db, $baseGeneral, $baseCity, $gid);
    setGameMonth($db, $m);
    $db->update('general', ['special' => 'None', 'special2' => 'None', 'personal' => 'None'], 'no=%i', $g);
    $freeGeneral = General::createObjFromDB($g);
    $freeCmd = buildGeneralCommandClass($cls, $freeGeneral, $env, null);
    $freeRng = new RandUtil(new LiteHashDRBG($seedString));   // SAME seed
    $freeCmd->run($freeRng);
    $freeAfter = General::createObjFromDB($g);
    $freeAgri  = (int)($db->queryFirstRow('SELECT agri FROM city WHERE city=%i', $cityId)['agri']);
    $foldObservable = ($freeAfter->getVar('experience') !== $generalAfter->getVar('experience'))
                   || ($freeAgri !== (int)$cityAfter['agri']);
    // restore the modules for the next case (the clone wiped them).
    restore($db, $baseGeneral, $baseCity, $gid);

    $case = [
        'case'          => $name,
        'command'       => $cls,
        'ctor'          => $meta['ctor'],
        'scope'         => 'generalCommand',
        'generalId'     => $g,
        'cityId'        => $cityId,
        'special'       => $EXPECT_SPECIAL,
        'personal'      => $EXPECT_PERSONAL,
        'env'           => ['year' => $year, 'startYear' => $env['startyear'], 'month' => $m, 'develCost' => $env['develcost']],
        'seedString'    => $seedString,
        'reqGold'       => $reqGold,
        'before'        => $before,
        'after'         => $after,
        'logLines'      => $actingLines,
        'broadcastLines'=> $broadcastLines,
        'moduleFold'    => [
            'observable'        => $foldObservable,
            'moduleFreeExp'     => $freeAfter->getVar('experience'),
            'moduleFreeAgri'    => $freeAgri,
            'moduleBearingExp'  => $generalAfter->getVar('experience'),
            'moduleBearingAgri' => (int)$cityAfter['agri'],
        ],
    ];
    hardAssert($foldObservable, "{$name}: module fold is NOT observable (golden would not test the 9-source stack)");
    $cases[] = $case;
    fwrite(STDERR, "captured {$name}: agri {$before['city']['agri']}→{$after['city']['agri']} "
        . "(module-free agri {$freeAgri}), exp {$before['general']['experience']}→{$after['general']['experience']} "
        . "(module-free exp {$freeAfter->getVar('experience')})\n");
}

// final restore of the actor's modules + level state.
$db->update('general', $baseGeneral, 'no=%i', $gid);
$db->update('city', $baseCity, 'city=%i', $baseCityId);
$db->delete('general_record', 'log_type=%s AND general_id=%i', 'action', $gid);

$out = [
    'hiddenSeed' => $hiddenSeed,
    'command'    => 'che_농지개간',
    'note'       => 'GT1 non-identity (module-bearing) fixture: gid 14 공융 carries special=che_경작 (source #3) '
                  . '+ personal=che_왕좌 (source #5). Captured on a DEDICATED install (NOT the cff8658592... '
                  . 'module-free install) — its own hiddenSeed is the byte oracle.',
    'special'    => $EXPECT_SPECIAL,
    'personal'   => $EXPECT_PERSONAL,
    'env'        => ['year' => $year, 'startYear' => $startYear, 'develCost' => $develCost],
    'cases'      => $cases,
];
$outPath = $outDir . '/che_농지개간-nonidentity-fixtures.json';
file_put_contents($outPath, Json::encode($out, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES | JSON_PRETTY_PRINT));
fwrite(STDERR, "wrote " . basename($outPath) . " (" . count($cases) . " cases)\n");
