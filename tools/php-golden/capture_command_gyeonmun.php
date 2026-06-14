<?php
/**
 * capture_command_gyeonmun.php — che_견문 (sightseeing) golden capture.
 *
 * ONE-SHOT, MANUAL HOST STEP — NEVER CI.
 *
 * Captures a REAL PHP turn execution of che_견문 from scenario_1010 via the
 * standard devsam-core harness. Produces ONE fixture file:
 *
 *   logic/src/test/resources/golden/p2/che_견문-fixtures.json
 *
 * carrying a single case whose `draws` block records the full RNG stream:
 *   seq 0: choiceUsingWeightPair  — picks one of 17 outcome entries (weighted)
 *   seq 1: choice                 — picks one text from the chosen entry's text list
 *   seq 2 (COND): nextRangeInt(10,20)  — only if Wounded  (type & 0x1000)
 *   seq 2 (COND): nextRangeInt(20,50)  — only if HeavyWounded (type & 0x2000)
 *
 * The draw count is 2 (no wound), 3 (one wound flag), or 3 (both flags, though
 * the PHP uses || — Wounded and HeavyWounded are mutually-inclusive bitmask
 * bits; both can appear together, giving 4 draws on a single-entry hit). The
 * captured draw_count is whatever the install seed produces — NEVER forced.
 *
 * HARD assertions (abort capture — never write a partial/unfaithful golden):
 *   (1) Module-free acting general — special/special2/personal ∈ {None,'',null},
 *       every equipment slot a None item.
 *   (2) No level cross — explevel/dedlevel unchanged before→after.
 *   (3) Exactly 1 acting action-log line (manifest logLines=1).
 *   (4) Equipment slot count unchanged (no unique item won).
 *   (5) Integer trust — city trust == floor(trust).
 *
 * Static-input precondition (positions gate only; computed golden stays 100% PHP):
 *   - personal = 'None' (module-free).
 *   - experience / dedication nudged to the 40% point of the current band so
 *     the captured +exp does not cross a level (che_견문 adds at most +60 exp
 *     via IncHeavyExp; the band precondition uses high-exp level 56, width 1130,
 *     comfortably absorbing +60). ded is unchanged by che_견문 — any position works.
 *
 * Run (manual host, inside the php:8.3-cli container, repo mounted at /work):
 *
 *   # 1. Install scenario_1010 (draws a fresh hiddenSeed — use a FRESH DB).
 *   php /work/tools/php-golden/install_scenario.php --scenario=1010 --turnterm=120 --sync=0
 *
 *   # 2. Run this capture.
 *   php /work/tools/php-golden/capture_command_gyeonmun.php \
 *       [--out-dir=logic/src/test/resources/golden/p2]
 *
 *   # 3. Verify: re-install on a FRESH DB, re-run, confirm sha256 identical.
 *
 * Exit codes: 0 = success, 2 = HARD-ASSERT failed (unfaithful → do NOT commit).
 */

namespace sammo;

require __DIR__ . '/_boot.php';
require __DIR__ . '/RandUtilDrawRecorder.php';

$opts   = getopt('', ['out-dir:']);
$outDir = $opts['out-dir'] ?? (__DIR__ . '/../../logic/src/test/resources/golden/p2');
if (!is_dir($outDir)) { mkdir($outDir, 0775, true); }

$db = DB::db();
$hiddenSeed = UniqueConst::$hiddenSeed;
$gameStor   = KVStorage::getStorage($db, 'game_env');
[$year, $startYear, $monthBase, $develCost] =
    $gameStor->getValuesAsArray(['year', 'startyear', 'month', 'develcost']);

// ── helpers (verbatim from capture_command_sabotage.php / capture_command.php) ──

function hardAssert(bool $cond, string $msg): void {
    if (!$cond) { fwrite(STDERR, "GYEONMUN HARD-ASSERT FAILED: {$msg}\n"); exit(2); }
}
function planMiss(string $why): void {
    fwrite(STDERR, "PLAN-MISS che_견문: {$why}\n");
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
    $rows = $db->query(
        'SELECT text FROM general_record WHERE general_id=%i AND log_type=%s ORDER BY id', $gid, 'action');
    $out = []; foreach ($rows as $r) $out[] = $r['text']; return $out;
}
function globalActionRowsSince(object $db, int $afterId): array {
    $rows = $db->query(
        'SELECT id, text FROM general_record WHERE general_id=0 AND log_type=%s AND id>%i ORDER BY id',
        'history', $afterId);
    $out = []; foreach ($rows as $r) $out[] = $r['text']; return $out;
}
function maxGlobalActionId(object $db): int {
    return (int)($db->queryFirstField(
        'SELECT COALESCE(MAX(id),0) FROM general_record WHERE general_id=0 AND log_type=%s', 'history'));
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
 * Module-free + no-level-cross precondition.
 *
 * che_견문 adds at most +60 exp (IncHeavyExp). We position the actor at the
 * low edge of exp level 56 (band [31360, 32490), width 1130 >> 60). Dedication
 * is NOT mutated by che_견문, so we only position experience. The band choice
 * guarantees explevel/dedlevel are unchanged after the action (HARD assertion 2).
 *
 * This is a static-input gate positioning. It does NOT touch L/S/I, gold, rice,
 * or injury — the captured draws/log/deltas are 100% real PHP.
 */
function applyPrecondition(object $db, int $gid): array {
    $row = $db->queryFirstRow('SELECT experience, dedication FROM general WHERE no=%i', $gid);
    $exp = (int)$row['experience'];
    $ded = (int)$row['dedication'];

    // Exp: position at low end of level-56 band (10*56^2 = 31360, width 1130).
    // Use the HIGHER of the current exp and the band-low so we never shrink a
    // high-exp general below their current level (that would itself be a ded-cross).
    $el = getExpLevel($exp);
    $newExp = max($exp, 10 * 56 * 56); // band-low = 31360; place at floor if already higher
    if ($newExp !== $exp) {
        $newExp = 10 * 56 * 56;        // place exactly at band-low for repeatability
    }

    // Ded: nudge to 40% of current band (same formula as capture_command.php).
    $dl  = getDedLevel($ded);
    $dmid = (int)(dedBandLo($dl) + (dedBandHi($dl) - dedBandLo($dl)) * 0.4);

    $db->update('general', [
        'personal'   => 'None',
        'experience' => $newExp,
        'dedication' => $dmid,
        'explevel'   => getExpLevel($newExp),
        'dedlevel'   => getDedLevel($dmid),
    ], 'no=%i', $gid);
    return $db->queryFirstRow('SELECT * FROM general WHERE no=%i', $gid);
}

/**
 * Snapshot the fields the Kotlin gate compares.
 * che_견문 can mutate: gold (+300 IncGold / -200 DecGold), rice (+300 IncRice / -200 DecRice),
 * injury (+nextRangeInt Wounded/HeavyWounded), experience (+30 IncExp / +60 IncHeavyExp),
 * leadership_exp (+2 IncLeadership), strength_exp (+2 IncStrength), intel_exp (+2 IncIntel).
 * We capture all of them + the unchanged fields for completeness.
 */
function snapshotGeneralCity(General $g, ?array $city): array {
    $snap = ['general' => [
        'gold'           => $g->getVar('gold'),
        'rice'           => $g->getVar('rice'),
        'injury'         => $g->getVar('injury'),
        'experience'     => $g->getVar('experience'),
        'dedication'     => $g->getVar('dedication'),
        'leadership_exp' => $g->getVar('leadership_exp'),
        'strength_exp'   => $g->getVar('strength_exp'),
        'intel_exp'      => $g->getVar('intel_exp'),
        'explevel'       => $g->getVar('explevel'),
        'nation'         => $g->getVar('nation'),
        'officer_level'  => $g->getVar('officer_level'),
        'city'           => $g->getVar('city'),
        'max_domestic_critical' => $g->getAuxVar('max_domestic_critical') ?? 0,
    ]];
    if ($city !== null) {
        $snap['city'] = [
            'comm'     => $city['comm'],
            'agri'     => $city['agri'],
            'comm_max' => $city['comm_max'],
            'agri_max' => $city['agri_max'],
            'trust'    => $city['trust'],
            'nation'   => $city['nation'],
        ];
    }
    return $snap;
}

/** Full-world snapshot for mutually-independent captures. */
function snapshotWorld(object $db): array {
    return [
        'generals'    => $db->query('SELECT * FROM general'),
        'cities'      => $db->query('SELECT * FROM city'),
        'nations'     => $db->query('SELECT * FROM nation'),
        'maxGlobalId' => maxGlobalActionId($db),
    ];
}

/** Restore to the pre-capture snapshot. */
function restoreWorld(object $db, array $w): void {
    foreach ($w['generals'] as $r) $db->update('general', $r, 'no=%i', (int)$r['no']);
    foreach ($w['cities']   as $r) $db->update('city',    $r, 'city=%i', (int)$r['city']);
    foreach ($w['nations']  as $r) $db->update('nation',  $r, 'nation=%i', (int)$r['nation']);
    $db->delete('general_record', 'log_type=%s AND id>%i', 'history', $w['maxGlobalId']);
    $db->query('DELETE FROM general_record WHERE log_type=%s', 'action');
}

/**
 * Select the actor: the FIRST module-free officer in a supplied owned non-front city
 * whose trust is an integer. che_견문 has fullConditionConstraints=[] — any such
 * general passes. We pick by smallest gid (deterministic across installs).
 */
function pickActor(object $db): ?int {
    return (int)($db->queryFirstField(
        "SELECT g.no FROM general g JOIN city c ON g.city=c.city
           WHERE c.nation<>0 AND c.supply=1 AND c.front NOT IN (1,3) AND c.trust=floor(c.trust)
             AND g.nation=c.nation AND g.officer_level>=1
             AND (g.special='None'  OR g.special=''  OR g.special  IS NULL)
             AND (g.special2='None' OR g.special2='' OR g.special2 IS NULL)
           ORDER BY g.no LIMIT 1") ?: null);
}

// ── single capture ────────────────────────────────────────────────────────────

$gid = pickActor($db);
if (!$gid) {
    planMiss("no module-free officer in a supplied owned non-front city on this install");
    exit(1);
}

// Freshness guard: actor must have zero prior action rows.
$prior = (int)$db->queryFirstField(
    'SELECT COUNT(*) FROM general_record WHERE general_id=%i AND log_type=%s', $gid, 'action');
hardAssert($prior === 0,
    "actor {$gid} has {$prior} prior action-log rows — DB is dirty; re-run install_scenario.php");

$world = snapshotWorld($db);

$env = setGameMonth($db, $monthBase);
applyPrecondition($db, $gid);

$general = General::createObjFromDB($gid);
assertModuleFree($general);

$cityId = (int)$general->getVar('city');
$city   = $cityId ? $db->queryFirstRow('SELECT * FROM city WHERE city=%i', $cityId) : null;
if ($city !== null) {
    hardAssert($city['trust'] == floor($city['trust']), "city {$cityId} trust not integer");
}

$explevelBefore = $general->getVar('explevel');
$dedlevelBefore = $general->getVar('dedlevel');

$raw = 'che_견문';
$cmd = buildGeneralCommandClass($raw, $general, $env, null);
hardAssert($cmd->getRawClassName() === $raw, "factory returned {$cmd->getRawClassName()} for {$raw}");
if (!$cmd->hasFullConditionMet()) {
    hardAssert(false, "che_견문: full condition not met");
}

$reqGold = $cmd->getCost()[0] ?? null;

$before = snapshotGeneralCity($general, $city);
$itemCountBefore = count($general->getItems() ?? []);
$globalIdBefore  = maxGlobalActionId($db);

// Derive the exact per-turn seed (6-component, generalCommand scope).
$seedString = Util::simpleSerialize($hiddenSeed, 'generalCommand', $year, $monthBase, $gid, $cmd->getRawClassName());
$rng = new RandUtilDrawRecorder(new LiteHashDRBG($seedString));

$ok = $cmd->run($rng);   // flushes via general->applyDB($db)
hardAssert($ok === true, "che_견문: run() returned false");

$actingLines = logActionRows($db, $gid);
hardAssert(count($actingLines) === 1,
    "che_견문: expected 1 acting line, got " . count($actingLines) . " — unique-item or static-event fired?");
hardAssert(count($general->getItems() ?? []) === $itemCountBefore,
    "che_견문: equipment slot count changed (unique item won)");

$broadcastLines = globalActionRowsSince($db, $globalIdBefore);

$generalAfter = General::createObjFromDB($gid);
$cityAfter    = $cityId ? $db->queryFirstRow('SELECT * FROM city WHERE city=%i', $cityId) : null;
$after = snapshotGeneralCity($generalAfter, $cityAfter);

hardAssert($generalAfter->getVar('explevel') === $explevelBefore,
    "che_견문: exp level crossed ({$explevelBefore}->" . $generalAfter->getVar('explevel') . ")");
hardAssert($generalAfter->getVar('dedlevel') === $dedlevelBefore,
    "che_견문: ded level crossed");

$case = [
    'case'           => 'gyeonmun',
    'command'        => $raw,
    'ctor'           => 'general',
    'scope'          => 'generalCommand',
    'generalId'      => $gid,
    'cityId'         => $cityId,
    'env'            => [
        'year'      => $year,
        'startYear' => (int)$env['startyear'],
        'month'     => $monthBase,
        'develCost' => (int)$env['develcost'],
    ],
    'arg'            => null,
    'seedString'     => $seedString,
    'reqGold'        => $reqGold,
    'before'         => $before,
    'after'          => $after,
    'logLines'       => $actingLines,
    'broadcastLines' => $broadcastLines,
    'draws'          => [
        'draw_count'  => $rng->getDrawCount(),
        'draw_stream' => $rng->getDrawStream(),
    ],
];

// Restore to pre-capture state (required even for a single case — clean baseline).
restoreWorld($db, $world);

// ── emit the fixture ──────────────────────────────────────────────────────────

$out = [
    'hiddenSeed' => $hiddenSeed,
    'command'    => $raw,
    'env'        => ['year' => $year, 'startYear' => $startYear, 'develCost' => $develCost],
    'cases'      => [$case],
];
$outPath = $outDir . '/' . $raw . '-fixtures.json';
file_put_contents(
    $outPath,
    Json::encode($out, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES | JSON_PRETTY_PRINT)
);

// Report draw stream to stderr for verification.
fwrite(STDERR, "wrote " . basename($outPath) . "\n");
fwrite(STDERR, "  actor gid={$gid} city={$cityId} month={$monthBase} year={$year}\n");
fwrite(STDERR, "  draw_count=" . $rng->getDrawCount() . "\n");
fwrite(STDERR, "  logLine=" . ($actingLines[0] ?? '(none)') . "\n");
fwrite(STDERR, "  before.injury=" . ($before['general']['injury'] ?? 0)
              . " after.injury=" . ($after['general']['injury'] ?? 0) . "\n");
fwrite(STDERR, "  before.gold=" . $before['general']['gold']
              . " after.gold=" . $after['general']['gold'] . "\n");
fwrite(STDERR, "  before.rice=" . $before['general']['rice']
              . " after.rice=" . $after['general']['rice'] . "\n");
fwrite(STDERR, "  before.exp=" . $before['general']['experience']
              . " after.exp=" . $after['general']['experience'] . "\n");
fwrite(STDERR, "seedString={$seedString}\n");
