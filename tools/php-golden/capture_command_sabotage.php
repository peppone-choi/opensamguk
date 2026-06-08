<?php
/**
 * capture_command_sabotage.php — GeneralCommand 계략(sabotage) ARG/STATE-gated golden
 * capture (devsam-core PHP, grand truth). ONE-SHOT, MANUAL HOST — NEVER CI.
 *
 * Sibling of capture_command_c3.php, but for the GENERAL-scope 계략 family under
 * hwe/sammo/Command/General/: che_화계(base) / che_선동 / che_파괴 / che_탈취. All four
 * are GeneralCommand (3-arg buildGeneralCommandClass, 'generalCommand' seed) gated by:
 *   - NotBeNeutral / OccupiedCity / SuppliedCity   (actor's own city),
 *   - NotOccupiedDestCity / NotNeutralDestCity     (dest = an ENEMY city),
 *   - ReqGeneralGold / ReqGeneralRice (= develcost*5),
 *   - DisallowDiplomacyBetweenStatus([7]) (not 불가침국 with the dest nation).
 *
 * The harness drives the SAME real path TurnExecutionHelper does:
 *   $cmd  = buildGeneralCommandClass($raw, $general, $env, $arg);   // $arg = ['destCityID'=>…]
 *   $seed = Util::simpleSerialize($hiddenSeed,'generalCommand',$year,$month,$gid,$raw);
 *   $cmd->run(new RandUtilDrawRecorder(new LiteHashDRBG($seed)));   // ← draw-recording
 * RandUtilDrawRecorder `extends RandUtil` and is DRAW-NEUTRAL (byte-identical output),
 * so the recorded draw stream is the EXACT stream a real turn pulls.
 *
 * che_화계 run() DRAW STREAM (success branch):
 *   1) nextBool($prob)               — the 성공 roll (prob = sabotageDefaultProb + atkProb
 *                                       - defProb, /=dist, clamped [0,0.5]).
 *   2) SabotageInjury per enemy general in the dest city: nextBool($injuryProb=0.3) and,
 *      on a HIT, nextRangeInt(1,16) (injury amount).
 *   3) affectDestCity: nextRangeInt($sabotageDamageMin,$sabotageDamageMax) ×2 (agri, comm).
 *   4) success exp/ded: nextRangeInt(201,300) + nextRangeInt(141,210).
 * On a FAIL roll (step 1 false) the stream is just nextBool + nextRangeInt(1,100) (exp)
 * + nextRangeInt(1,70) (ded). The captured outcome is whatever the install seed produces —
 * NOT forced; the fixture records the real branch + its real draws.
 *
 * Per command a PLAN entry supplies: the actor (a module-free officer that can reach an
 * enemy city within dist<=5), the live arg (destCityID from the running install), and the
 * static-input precondition is just the module-free/no-level-cross positioning shared with
 * every sibling capture (applyPrecondition). The diplomacy default in scenario_1010 is
 * state 2 (평시, NOT 불가침 7) so the [7] gate passes without mutation. The action's draws,
 * log strings, and city/general deltas are 100% real PHP. NO computed value is fabricated.
 *
 * HARD assertions kept verbatim from capture_command_c3.php (module-free acting general,
 * no level cross, exact acting action-line count = manifest_sabotage logLines, integer
 * trust). A command whose plan cannot meet hasFullConditionMet() on this install, or whose
 * run() finds no reachable outcome, is SKIPPED (PLAN-MISS) — never faked — and recorded in
 * tools/php-golden/p2-capture-backlog.md with the exact reason.
 *
 * Emits ONE fixture per command: golden/p2/<command>-fixtures.json (same shape as
 * capture_command_c3.php: before/after + logLines + broadcastLines + draws + destGenerals).
 *
 * Invocation (inside the php capture container, repo mounted at /work):
 *   php tools/php-golden/capture_command_sabotage.php [--command=che_화계]
 *       [--out-dir=logic/src/test/resources/golden/p2]
 */

namespace sammo;

require __DIR__ . '/_boot.php';
require __DIR__ . '/RandUtilDrawRecorder.php';

use function \sammo\searchDistance;

$opts   = getopt('', ['command:', 'out-dir:']);
$onlyCmd = $opts['command'] ?? null;
$outDir  = $opts['out-dir'] ?? (__DIR__ . '/../../logic/src/test/resources/golden/p2');
if (!is_dir($outDir)) { mkdir($outDir, 0775, true); }

$db = DB::db();
$hiddenSeed = UniqueConst::$hiddenSeed;
$gameStor = KVStorage::getStorage($db, 'game_env');
[$year, $startYear, $monthBase, $develCost] = $gameStor->getValuesAsArray(['year','startyear','month','develcost']);

$manifest = Json::decode(file_get_contents(__DIR__ . '/manifest_sabotage.json'));
$cmdMeta = [];
foreach ($manifest['commands'] as $group => $list) {
    foreach ($list as $entry) { $cmdMeta[$entry['rawClassName']] = $entry; }
}

function hardAssert(bool $cond, string $msg): void {
    if (!$cond) { fwrite(STDERR, "SABOTAGE HARD-ASSERT FAILED: {$msg}\n"); exit(2); }
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
 *
 * che_화계 SUCCESS adds up to +300 exp / +210 ded (fail +100/+70). To keep the actor off the
 * deferred checkStatChange 레벨업 path REGARDLESS of which branch the install seed rolls, we
 * position the actor at the LOW edge of a HIGH exp/ded band whose width comfortably exceeds the
 * max gain, so [low, low+maxGain] stays strictly inside the band → explevel/dedlevel unchanged.
 *
 *   getExpLevel(e) = (e>=1000) ? toInt(sqrt(e/10)) : intdiv(e,100)  → exp level L band = [10L², 10(L+1)²).
 *   getDedLevel(d) = ceil(sqrt(d)/10)                              → ded level L band = ((10(L-1))², (10L)²].
 *
 * exp level 56: band-low 10*56² = 31360, width 10*(2*56+1) = 1130 >> +300, low+300=31660 < 32490. ✓
 * ded level 13: band-low (10*12)²+1 = 14401, width (10*13)²-(10*12)² = 2500 >> +210, low+210=14611 < 16900. ✓
 *
 * This is a pure static-input gate positioning (a mid-game high-rank veteran); it does NOT touch
 * L/S/I, gold, rice, or any value the action computes — the captured draws/log/deltas are 100%
 * real PHP. The fixture's before/after exp/ded carry these positioned values verbatim. (Asserted:
 * the post-state stays in-band, else the no-level-cross HARD assertion aborts the capture.)
 */
function applyPrecondition(object $db, int $gid): array {
    $expFloor = 10 * 56 * 56;        // exp level 56 band-low = 31360
    $dedFloor = (10 * 12) * (10 * 12) + 1;  // ded level 13 band-low = 14401
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

/** Snapshot the FULL world rows the sabotage captures can touch so each capture restores
 *  byte-for-byte and the N captures stay mutually independent. */
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
 * The PLAN registry: per sabotage command, [gid, arg, preFn, year]. Returns null → PLAN-MISS.
 *
 * The actor is the MOST sabotage-capable module-free officer reachable: among all module-free
 * officers (supplied owned non-front-blocked city) that can reach an ENEMY city (different
 * non-neutral nation, dist<=5) with >=1 enemy general, pick the (actor,destCity) pair with the
 * HIGHEST computed success prob (= sabotageDefaultProb + atkProb - defProb, /=dist, clamped
 * [0,0.5]). This is a deterministic, install-derived PLAN choice (the "best sabotager" — a
 * natural mid-game actor), NOT a seed-fishing: the success/fail roll is then 100% the real
 * nextBool($prob) draw for that actor's per-action seed, captured AS-IS via `result`.
 *
 * preFn=null — the only positioning is the shared module-free/no-level-cross applyPrecondition;
 * scenario_1010 diplomacy default = state 2 (평시), not 불가침 7, so the [7] gate already passes.
 * (The prob calc uses the actor's intel; applyPrecondition does NOT touch L/S/I, so the
 * captured prob matches this PLAN ranking.)
 */
function planFor(object $db, string $raw, array $env): ?array {
    $statType = $env['__statType'] ?? 'intel';   // 화계=intel, 선동=leadership, 파괴/탈취=strength
    $statCol = ['intel'=>'intel','leadership'=>'leadership','strength'=>'strength'][$statType] ?? 'intel';
    $defaultProb = GameConstBase::$sabotageDefaultProb;
    $coefStat    = GameConstBase::$sabotageProbCoefByStat;
    $coefCnt     = GameConstBase::$sabotageDefenceCoefByGeneralCnt;

    $actorRows = $db->query(
        "SELECT g.no, g.nation, g.city, g.{$statCol} AS stat FROM general g JOIN city c ON g.city=c.city
           WHERE c.nation<>0 AND c.supply=1 AND g.nation=c.nation AND g.officer_level>=1
             AND c.trust=floor(c.trust)
             AND (g.special='None' OR g.special='' OR g.special IS NULL)
             AND (g.special2='None' OR g.special2='' OR g.special2 IS NULL)
           ORDER BY g.no");

    $best = null;
    foreach ($actorRows as $a) {
        $gid = (int)$a['no']; $nid = (int)$a['nation']; $cid = (int)$a['city']; $stat = (int)$a['stat'];
        $dist = searchDistance($cid, 5, false);
        foreach ($dist as $destCity => $d) {
            $destCity = (int)$destCity; $d = (float)$d;
            $dc = $db->queryFirstRow('SELECT nation, secu, secu_max, supply FROM city WHERE city=%i', $destCity);
            $destNat = (int)$dc['nation'];
            if ($destNat === 0 || $destNat === $nid) continue;   // dest must be ENEMY
            $defs = $db->query("SELECT {$statCol} AS stat FROM general WHERE city=%i AND nation=%i", $destCity, $destNat);
            $cnt = count($defs);
            if ($cnt < 1) continue;   // need >=1 enemy general so the injury loop fires on success
            $maxStat = 0; foreach ($defs as $df) $maxStat = max($maxStat, (int)$df['stat']);
            $prob = $defaultProb + $stat / $coefStat;
            $prob -= $maxStat / $coefStat;
            $prob -= (log($cnt + 1, 2) - 1.25) * $coefCnt;
            $prob -= $dc['secu'] / $dc['secu_max'] / 5;
            $prob -= $dc['supply'] ? 0.1 : 0;
            $prob /= $d;
            $prob = max(0, min(0.5, $prob));
            if ($best === null || $prob > $best['prob']) {
                $best = ['gid'=>$gid, 'destCity'=>$destCity, 'prob'=>$prob];
            }
        }
    }
    if ($best === null) return null;
    return [$best['gid'], ['destCityID' => $best['destCity']], null, null];
}

/** Capture ONE sabotage command case (single seeded outcome — whatever the install produces). */
function captureCase(object $db, array $meta, string $raw, int $gid, ?array $arg, ?callable $preFn,
                     int $year, int $month, string $hiddenSeed): ?array {
    $env = setGameMonth($db, $month);
    $gs = KVStorage::getStorage($db, 'game_env'); $gs->year = $year; $gs->resetCache();
    $env = KVStorage::getStorage($db, 'game_env')->getAll();

    // defensive freshness: clear ANY lingering action rows on every general before this capture.
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

    // dest enemy generals in the targeted city — snapshot their pre-state + capture their lines.
    $destGids = collectDestGids($db, $raw, $arg);
    $destBefore = [];
    foreach ($destGids as $did) {
        $dG = General::createObjFromDB($did);
        $dCid = (int)$dG->getVar('city');
        $dC = $dCid ? $db->queryFirstRow('SELECT * FROM city WHERE city=%i', $dCid) : null;
        $destBefore[$did] = snapshotGeneralCity($dG, $dC);
    }

    $before = snapshotGeneralCity($general, $city);
    $itemCountBefore = count($general->getItems() ?? []);
    $globalIdBefore = maxGlobalActionId($db);

    $seedString = Util::simpleSerialize($hiddenSeed, 'generalCommand', $year, $month, $gid, $cmd->getRawClassName());
    $rng = new RandUtilDrawRecorder(new LiteHashDRBG($seedString));

    // the actor's own city distance to the dest city (the prob /= dist divisor) — pinned as
    // a documented fixture input (the Kotlin gate recomputes prob and needs the same divisor).
    $destCityID = (int)$arg['destCityID'];
    $dist = searchDistance($cityId, 5, false)[$destCityID] ?? 99;

    $ok = $cmd->run($rng);
    // both branches are FAITHFUL outcomes (run() returns false on a 계략실패 roll, true on
    // success). We capture WHICHEVER the install seed produced — NOT forced. `result` records
    // the branch so the Kotlin gate asserts the right one.
    $result = ($ok === true) ? 'success' : 'fail';

    $actingLines = logActionRows($db, $gid);
    $expectLines = ($ok === true) ? (int)$meta['logLines'] : (int)$meta['logLinesFail'];
    hardAssert(count($actingLines) === $expectLines,
        "{$raw}: expected {$expectLines} acting line(s), got " . count($actingLines) . " :: " . implode(' | ', $actingLines));
    hardAssert(count($general->getItems() ?? []) === $itemCountBefore, "{$raw}: equipment slot count changed");

    $broadcastLines = globalActionRowsSince($db, $globalIdBefore);

    $generalAfter = General::createObjFromDB($gid);
    $cityAfter = $cityId ? $db->queryFirstRow('SELECT * FROM city WHERE city=%i', $cityId) : null;
    $after = snapshotGeneralCity($generalAfter, $cityAfter);
    hardAssert($generalAfter->getVar('explevel') === $explevelBefore, "{$raw}: exp level crossed ({$explevelBefore}->" . $generalAfter->getVar('explevel') . ")");
    hardAssert($generalAfter->getVar('dedlevel') === $dedlevelBefore, "{$raw}: ded level crossed");

    // dest city after-state (agri/comm/secu/etc. mutated on success).
    $destCityAfter = $db->queryFirstRow('SELECT * FROM city WHERE city=%i', $destCityID);
    $destCityRowBefore = null; // captured implicitly via destBefore[…]['city'] when a dest gen lives there

    $case = [
        'case'=>$raw, 'command'=>$raw, 'ctor'=>$meta['ctor'], 'scope'=>'generalCommand',
        'generalId'=>$gid, 'cityId'=>$cityId,
        'destCityId'=>$destCityID, 'destNationId'=>(int)$db->queryFirstField('SELECT nation FROM city WHERE city=%i', $destCityID),
        'cityDistance'=>$dist, 'result'=>$result,
        'env'=>['year'=>$year,'startYear'=>(int)$env['startyear'],'month'=>$month,'develCost'=>(int)$env['develcost']],
        'arg'=>$arg, 'seedString'=>$seedString, 'reqGold'=>$reqGold,
        'before'=>$before, 'after'=>$after,
        'destCityBefore'=>snapshotCityRow($db, $destCityID, $destBefore, $destGids),
        'destCityAfter'=>['city'=>cityColsOf($destCityAfter)],
        'logLines'=>$actingLines, 'broadcastLines'=>$broadcastLines,
        'draws'=>['draw_count'=>$rng->getDrawCount(), 'draw_stream'=>$rng->getDrawStream()],
    ];

    // dest enemy generals (injured on success) — their PLAIN injury lines + post-state.
    if (!empty($destGids)) {
        $destOut = [];
        foreach ($destGids as $did) {
            $dG = General::createObjFromDB($did);
            $dCid = (int)$dG->getVar('city');
            $dC = $dCid ? $db->queryFirstRow('SELECT * FROM city WHERE city=%i', $dCid) : null;
            $lines = logActionRows($db, $did);
            $destOut[] = ['generalId'=>$did, 'before'=>$destBefore[$did], 'after'=>snapshotGeneralCity($dG, $dC), 'logLines'=>$lines];
        }
        $case['destGenerals'] = $destOut;
    }

    return $case;
}

/** Extract just the city columns we snapshot (for dest city before/after). */
function cityColsOf(array $city): array {
    return ['comm'=>$city['comm'],'agri'=>$city['agri'],'pop'=>$city['pop'],
        'comm_max'=>$city['comm_max'],'agri_max'=>$city['agri_max'],
        'def'=>$city['def'],'wall'=>$city['wall'],'def_max'=>$city['def_max'],'wall_max'=>$city['wall_max'],
        'secu'=>$city['secu'],'secu_max'=>$city['secu_max'],'level'=>$city['level'],
        'trust'=>$city['trust'],'nation'=>$city['nation'],'front'=>$city['front'],'state'=>$city['state']];
}

/** Dest city BEFORE snapshot (reuse a dest-gen's captured before city if present, else read). */
function snapshotCityRow(object $db, int $destCityID, array $destBefore, array $destGids): array {
    foreach ($destGids as $did) {
        if (isset($destBefore[$did]['city'])) {
            return ['city'=>$destBefore[$did]['city']];
        }
    }
    return ['city'=>cityColsOf($db->queryFirstRow('SELECT * FROM city WHERE city=%i', $destCityID))];
}

/** Enemy generals in the targeted dest city (SabotageInjury iterates them on success). */
function collectDestGids(object $db, string $raw, ?array $arg): array {
    if (!isset($arg['destCityID'])) return [];
    $destCityID = (int)$arg['destCityID'];
    $destNation = (int)$db->queryFirstField('SELECT nation FROM city WHERE city=%i', $destCityID);
    return array_map('intval', $db->queryFirstColumn('SELECT no FROM general WHERE nation=%i AND city=%i ORDER BY no', $destNation, $destCityID));
}

// ── the 계략(sabotage) GeneralCommand family ────────────────────────────────────
$targets = ['che_화계', 'che_선동', 'che_파괴', 'che_탈취'];
if ($onlyCmd !== null) $targets = [$onlyCmd];

$baseEnv = $gameStor->getAll();
$byCommand = [];
foreach ($targets as $raw) {
    if (!isset($cmdMeta[$raw])) { planMiss($raw, "not in manifest_sabotage"); continue; }
    $meta = $cmdMeta[$raw];

    $world = snapshotWorld($db);

    $planEnv = $baseEnv;
    $planEnv['__statType'] = $meta['statType'] ?? 'intel';
    $plan = planFor($db, $raw, $planEnv);
    if ($plan === null) { planMiss($raw, "no actor/plan on this install"); restoreWorld($db, $world); resetClock($db, $baseEnv); continue; }
    [$gid, $arg, $preFn, $yearOverride] = $plan;
    $year = $yearOverride ?? (int)$baseEnv['year'];

    $case = captureCase($db, $meta, $raw, $gid, $arg, $preFn, $year, (int)$baseEnv['month'], $hiddenSeed);

    restoreWorld($db, $world);
    resetClock($db, $baseEnv);
    \sammo\refreshNationStaticInfo();

    if ($case === null) continue;
    $byCommand[$raw][] = $case;
}

function resetClock(object $db, array $baseEnv): void {
    $gs = KVStorage::getStorage($db, 'game_env');
    $gs->year = (int)$baseEnv['year'];
    $gs->month = (int)$baseEnv['month'];
    $gs->resetCache();
}

foreach ($byCommand as $cls => $cases) {
    $out = ['hiddenSeed'=>$hiddenSeed, 'command'=>$cls,
        'env'=>['year'=>(int)$baseEnv['year'],'startYear'=>$startYear,'develCost'=>$develCost], 'cases'=>$cases];
    $outPath = $outDir . '/' . $cls . '-fixtures.json';
    // Minified single-line (matches the committed sibling p2 fixtures' canonical Json::encode bytes).
    file_put_contents($outPath, Json::encode($out, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES));
    fwrite(STDERR, "wrote " . basename($outPath) . " (" . count($cases) . " cases, " . $cases[0]['draws']['draw_count'] . " draws, " . $cases[0]['result'] . ")\n");
}
if (!$byCommand) fwrite(STDERR, "no sabotage command captured\n");
