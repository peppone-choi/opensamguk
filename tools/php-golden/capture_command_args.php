<?php
/**
 * capture_command_args.php — P2 ARG-AWARE golden capture (devsam-core PHP, grand truth).
 *
 * ONE-SHOT, MANUAL HOST STEP — NEVER CI. Sibling of capture_command.php (which covers
 * the zero-arg develop family). This EXTENDS the harness to the P2 commands that need
 * a constructed $arg and/or a state gate (join/founding/recruit/trade/nation-internal),
 * driving the REAL turn-execution path exactly as TurnExecutionHelper does:
 *   - GENERAL commands: buildGeneralCommandClass(General,env,arg) (3-arg ctor),
 *     seeded under 'generalCommand', run via $cmd->run($rng).
 *   - NATION commands: buildNationCommandClass(General,env,LastTurn,arg) (4-arg ctor),
 *     seeded under 'nationCommand', run via $cmd->run($rng). Gate: nation!=0 && officer>=5.
 *
 * Per command a PLAN entry supplies: actorKind (the candidate-actor query), arg
 * (built from the live install's ids), and any documented static-input precondition
 * (e.g. crew>0 for the train family — analogous to P1's mid-band exp/ded; the score is
 * still 100% real PHP). The HARD assertions are KEPT verbatim from capture_command.php:
 * module-free acting general, no level cross, exact acting action-line count
 * (manifest logLines), integer trust. A command whose plan cannot meet the full
 * condition on this install is SKIPPED (printed PLAN-MISS) — never faked; it is
 * recorded in tools/php-golden/p2-capture-backlog.md with the exact reason.
 *
 * Emits ONE fixture file per command into the same dir / same shape as
 * capture_command.php: golden/p2/<command>-fixtures.json.
 *
 * Invocation (inside the php capture container, repo mounted at /work):
 *   php tools/php-golden/capture_command_args.php [--command=che_등용]
 *       [--out-dir=logic/src/test/resources/golden/p2]
 */

namespace sammo;

require __DIR__ . '/_boot.php';

$opts   = getopt('', ['command:', 'out-dir:']);
$onlyCmd = $opts['command'] ?? null;
$outDir  = $opts['out-dir'] ?? (__DIR__ . '/../../logic/src/test/resources/golden/p2');
if (!is_dir($outDir)) { mkdir($outDir, 0775, true); }

$db = DB::db();
$hiddenSeed = UniqueConst::$hiddenSeed;
$gameStor = KVStorage::getStorage($db, 'game_env');
[$year, $startYear, $monthBase, $develCost] = $gameStor->getValuesAsArray(['year','startyear','month','develcost']);

$manifest = Json::decode(file_get_contents(__DIR__ . '/manifest.json'));
$cmdMeta = [];
foreach ($manifest['commands'] as $group => $list) {
    foreach ($list as $entry) { $cmdMeta[$entry['rawClassName']] = $entry; }
}

function hardAssert(bool $cond, string $msg): void {
    if (!$cond) { fwrite(STDERR, "FG2 HARD-ASSERT FAILED: {$msg}\n"); exit(2); }
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

/** Module-free + no-level-cross precondition (static input; computed golden stays real PHP). */
function applyPrecondition(object $db, int $gid): array {
    $row = $db->queryFirstRow('SELECT experience, dedication FROM general WHERE no=%i', $gid);
    $el = getExpLevel((int)$row['experience']); $dl = getDedLevel((int)$row['dedication']);
    $emid = (int)(expBandLo($el) + (expBandHi($el)-expBandLo($el))*0.4);
    $dmid = (int)(dedBandLo($dl) + (dedBandHi($dl)-dedBandLo($dl))*0.4);
    $db->update('general', ['personal'=>'None','experience'=>$emid,'dedication'=>$dmid,'explevel'=>getExpLevel($emid),'dedlevel'=>getDedLevel($dmid)], 'no=%i', $gid);
    return $db->queryFirstRow('SELECT * FROM general WHERE no=%i', $gid);
}

function snapshotGeneralCity(General $g, ?array $city): array {
    $snap = ['general' => [
        'gold' => $g->getVar('gold'), 'rice' => $g->getVar('rice'),
        'crew' => $g->getVar('crew'), 'train' => $g->getVar('train'), 'atmos' => $g->getVar('atmos'),
        'experience' => $g->getVar('experience'), 'dedication' => $g->getVar('dedication'),
        'intel_exp' => $g->getVar('intel_exp'), 'explevel' => $g->getVar('explevel'),
        'nation' => $g->getVar('nation'), 'officer_level' => $g->getVar('officer_level'),
        'city' => $g->getVar('city'), 'max_domestic_critical' => $g->getAuxVar('max_domestic_critical') ?? 0,
    ]];
    if ($city !== null) {
        $snap['city'] = ['comm'=>$city['comm'],'agri'=>$city['agri'],'pop'=>$city['pop'],
            'comm_max'=>$city['comm_max'],'agri_max'=>$city['agri_max'],'trust'=>$city['trust'],'nation'=>$city['nation']];
    }
    return $snap;
}

/**
 * The PLAN registry: per command, how to select an actor + build the live arg + apply
 * a documented static-input precondition. Returns null if no actor satisfies the gate
 * on this install (→ PLAN-MISS → backlog). Each plan returns [gid, arg, preFn].
 */
function planFor(object $db, string $raw, array $env): ?array {
    $year = (int)$env['year']; $start = (int)$env['startyear'];

    // candidate-actor queries (module-free, like P1)
    $neutral = fn() => (int)$db->queryFirstField(
        "SELECT no FROM general WHERE nation=0 AND (special='None' OR special='' OR special IS NULL)
           AND (special2='None' OR special2='' OR special2 IS NULL) ORDER BY no LIMIT 1");
    $officer = fn() => (int)$db->queryFirstField(
        "SELECT g.no FROM general g JOIN city c ON g.city=c.city
           WHERE c.nation<>0 AND c.supply=1 AND c.front NOT IN (1,3) AND c.trust=floor(c.trust)
             AND g.nation=c.nation AND g.officer_level>=1
             AND (g.special='None' OR g.special='' OR g.special IS NULL)
             AND (g.special2='None' OR g.special2='' OR g.special2 IS NULL) ORDER BY g.no LIMIT 1");
    $chief = fn() => (int)$db->queryFirstField(
        "SELECT g.no FROM general g JOIN city c ON g.city=c.city
           WHERE c.nation<>0 AND c.supply=1 AND g.nation=c.nation AND g.officer_level>=5
             AND (g.special='None' OR g.special='' OR g.special IS NULL)
             AND (g.special2='None' OR g.special2='' OR g.special2 IS NULL) ORDER BY g.no LIMIT 1");

    $nations = $db->queryFirstColumn("SELECT nation FROM nation ORDER BY nation");
    $colors = array_keys(GetNationColors());
    $color0 = (int)$colors[0];

    switch ($raw) {
        case 'che_랜덤임관': {
            $gid = $neutral(); if (!$gid) return null;
            // exclude an empty list so any nation is reachable; PHP picks via RNG.
            return [$gid, ['destNationIDList' => []], null];
        }
        case 'che_거병': {
            $gid = $neutral(); if (!$gid) return null;
            return [$gid, null, null];
        }
        case 'che_등용': {
            $gid = $officer(); if (!$gid) return null;
            $target = $neutral(); if (!$target) return null;
            return [$gid, ['destGeneralID' => $target], null];
        }
        case 'che_이동': {
            $gid = $officer(); if (!$gid) return null;
            $cid = (int)$db->queryFirstField('SELECT city FROM general WHERE no=%i', $gid);
            $cityObj = CityConst::byID($cid);
            $adj = null;
            foreach (($cityObj->path ?? []) as $nbr => $dist) { $adj = (int)$nbr; break; }
            if (!$adj) return null;
            return [$gid, ['destCityID' => $adj], null];
        }
        case 'che_증여': {
            $gid = $officer(); if (!$gid) return null;
            $nid = (int)$db->queryFirstField('SELECT nation FROM general WHERE no=%i', $gid);
            $target = (int)$db->queryFirstField('SELECT no FROM general WHERE nation=%i AND no<>%i ORDER BY no LIMIT 1', $nid, $gid);
            if (!$target) return null;
            // ensure actor has gold to give (it ships with install gold ~1000 > minimum).
            return [$gid, ['isGold' => true, 'amount' => 200, 'destGeneralID' => $target], null];
        }
        case 'che_헌납': {
            $gid = $officer(); if (!$gid) return null;
            return [$gid, ['isGold' => true, 'amount' => 200], null];
        }
        case 'che_군량매매': {
            // requires a trader city (ReqCityTrader): pick an officer whose city has a
            // trade rate set (trade<>NULL). Buy rice (deterministic price draw).
            $gid = (int)$db->queryFirstField(
                "SELECT g.no FROM general g JOIN city c ON g.city=c.city
                   WHERE c.nation<>0 AND c.supply=1 AND c.trade IS NOT NULL AND g.nation=c.nation
                     AND g.officer_level>=1 AND c.trust=floor(c.trust)
                     AND (g.special='None' OR g.special='' OR g.special IS NULL)
                     AND (g.special2='None' OR g.special2='' OR g.special2 IS NULL) ORDER BY g.no LIMIT 1");
            if (!$gid) return null;
            $pre = function(object $db, int $gid) { $db->update('general', ['gold'=>50000, 'rice'=>50000], 'no=%i', $gid); };
            return [$gid, ['buyRice' => true, 'amount' => 200], $pre];
        }
        case 'che_장비매매': {
            $gid = $officer(); if (!$gid) return null;
            // buy a weapon (the cheapest buyable weapon item, confirmed via isBuyable()).
            // static-input: ensure the actor can afford it.
            $pre = function(object $db, int $gid) { $db->update('general', ['gold'=>50000], 'no=%i', $gid); };
            return [$gid, ['itemType' => 'weapon', 'itemCode' => 'che_무기_01_단도'], $pre];
        }
        case 'che_징병': case 'che_모병': {
            $gid = $chief() ?: $officer(); if (!$gid) return null;
            // static-input precondition: bump city pop + actor gold/rice so recruit
            // preconditions (pop margin, gold, rice) are met; the recruited amount/score
            // is 100% real PHP for the set inputs.
            $pre = function(object $db, int $gid) {
                $cid = (int)$db->queryFirstField('SELECT city FROM general WHERE no=%i', $gid);
                $db->update('city', ['pop'=>$db->sqleval('GREATEST(pop, 50000)'), 'trust'=>80], 'city=%i', $cid);
                $db->update('general', ['gold'=>50000, 'rice'=>50000], 'no=%i', $gid);
            };
            // crewType 1100 (보병) is the basic infantry recruitable nation-wide; this
            // was confirmed via AvailableRecruitCrewType for the recruit actor.
            return [$gid, ['crewType' => 1100, 'amount' => 100], $pre];
        }
        case 'che_훈련': case 'che_사기진작': case 'che_소집해제': case 'cr_맹훈련': {
            $gid = $officer(); if (!$gid) return null;
            // static-input precondition: give the actor a starting crew (this install
            // ships every general at crew=0). train/atmos/dex deltas are real PHP for
            // the set crew; documented in p2-capture-backlog.md / README.
            $pre = function(object $db, int $gid) {
                $db->update('general', ['crew'=>5000, 'train'=>40, 'atmos'=>40, 'gold'=>50000, 'rice'=>50000], 'no=%i', $gid);
            };
            return [$gid, null, $pre];
        }
        case 'che_발령': {
            $gid = $chief(); if (!$gid) return null;
            $nid = (int)$db->queryFirstField('SELECT nation FROM general WHERE no=%i', $gid);
            $target = (int)$db->queryFirstField('SELECT no FROM general WHERE nation=%i AND no<>%i ORDER BY no LIMIT 1', $nid, $gid);
            $actorCity = (int)$db->queryFirstField('SELECT city FROM general WHERE no=%i', $gid);
            $destCity = (int)$db->queryFirstField('SELECT city FROM city WHERE nation=%i AND city<>%i ORDER BY city LIMIT 1', $nid, $actorCity);
            if (!$target || !$destCity) return null;
            return [$gid, ['destGeneralID' => $target, 'destCityID' => $destCity], null];
        }
        case 'che_포상': {
            $gid = $chief(); if (!$gid) return null;
            $nid = (int)$db->queryFirstField('SELECT nation FROM general WHERE no=%i', $gid);
            $target = (int)$db->queryFirstField('SELECT no FROM general WHERE nation=%i AND no<>%i ORDER BY no LIMIT 1', $nid, $gid);
            if (!$target) return null;
            // static-input: ensure the nation treasury can afford the reward.
            $pre = function(object $db, int $gid) use ($nid) {
                $db->update('nation', ['gold'=>$db->sqleval('GREATEST(gold, 100000)')], 'nation=%i', $nid);
            };
            return [$gid, ['isGold' => true, 'amount' => 200, 'destGeneralID' => $target], $pre];
        }
        case 'che_천도': {
            $gid = $chief(); if (!$gid) return null;
            $nid = (int)$db->queryFirstField('SELECT nation FROM general WHERE no=%i', $gid);
            $cap = (int)$db->queryFirstField('SELECT capital FROM nation WHERE nation=%i', $nid);
            $destCity = (int)$db->queryFirstField('SELECT city FROM city WHERE nation=%i AND supply=1 AND city<>%i ORDER BY city LIMIT 1', $nid, $cap);
            if (!$destCity) return null;
            $pre = function(object $db, int $gid) use ($nid) {
                $db->update('nation', ['gold'=>$db->sqleval('GREATEST(gold, 100000)'), 'rice'=>$db->sqleval('GREATEST(rice, 100000)')], 'nation=%i', $nid);
            };
            return [$gid, ['destCityID' => $destCity], $pre];
        }
        case 'che_국호변경': {
            $gid = $chief(); if (!$gid) return null;
            return [$gid, ['nationName' => '신후한'], null];
        }
        case 'che_국기변경': {
            $gid = $chief(); if (!$gid) return null;
            // pick a color different from the nation's current one.
            $nid = (int)$db->queryFirstField('SELECT nation FROM general WHERE no=%i', $gid);
            $cur = (int)$db->queryFirstField('SELECT color FROM nation WHERE nation=%i', $nid);
            $pick = $color0;
            foreach ($colors as $c) { if ((int)$c !== $cur) { $pick = (int)$c; break; } }
            return [$gid, ['colorType' => $pick], null];
        }
    }
    return null;
}

/** Capture ONE arg-aware command case (deterministic — single outcome). */
function captureCase(object $db, array $meta, string $caseName, string $raw, int $gid, array $bg, ?array $bc,
                     ?array $arg, ?callable $preFn, int $year, int $month, string $hiddenSeed): ?array {
    $env = setGameMonth($db, $month);

    // restore baseline, apply module-free precondition, then any command-specific pre.
    $db->update('general', $bg, 'no=%i', $gid);
    if ($bc) $db->update('city', $bc, 'city=%i', (int)$bg['city']);
    applyPrecondition($db, $gid);
    if ($preFn) $preFn($db, $gid);

    $general = General::createObjFromDB($gid);
    assertModuleFree($general);
    $cityId = (int)$general->getVar('city');
    $city = $cityId ? $db->queryFirstRow('SELECT * FROM city WHERE city=%i', $cityId) : null;
    if ($city !== null) hardAssert($city['trust'] == floor($city['trust']), "city {$cityId} trust not integer");

    $explevelBefore = $general->getVar('explevel');
    $dedlevelBefore = $general->getVar('dedlevel');

    $isNation = ($meta['ctor'] === 'nation');
    $scope = $isNation ? 'nationCommand' : 'generalCommand';
    if ($isNation) {
        hardAssert((int)$general->getVar('nation') !== 0, "{$caseName}: nation cmd but neutral actor");
        hardAssert((int)$general->getVar('officer_level') >= 5, "{$caseName}: nation cmd but officer<5");
        $cmd = buildNationCommandClass($raw, $general, $env, LastTurn::fromRaw(null), $arg);
    } else {
        $cmd = buildGeneralCommandClass($raw, $general, $env, $arg);
    }
    hardAssert($cmd->getRawClassName() === $raw, "factory returned {$cmd->getRawClassName()} for {$raw}");
    if (!$cmd->hasFullConditionMet()) {
        planMiss($raw, "{$caseName}: full condition not met (" . str_replace("\n"," ", (string)(method_exists($cmd,'getFailString') ? @$cmd->getFailString() : '')) . ")");
        return null;
    }

    $reqGold = null;
    if (property_exists($cmd, 'reqGold')) {
        $rp = new \ReflectionProperty($cmd, 'reqGold');
        if ($rp->isInitialized($cmd)) { $reqGold = $rp->getValue($cmd); }
    }
    $reqGold = $reqGold ?? ($cmd->getCost()[0] ?? null);

    $before = snapshotGeneralCity($general, $city);
    $itemCountBefore = count($general->getItems() ?? []);
    $globalIdBefore = maxGlobalActionId($db);

    $seedString = Util::simpleSerialize($hiddenSeed, $scope, $year, $month, $gid, $cmd->getRawClassName());
    $rng = new RandUtil(new LiteHashDRBG($seedString));

    $ok = $cmd->run($rng);
    if ($ok !== true) {
        // run() passed hasFullConditionMet but produced no committed outcome (e.g. a
        // join command with no nation accepting at this relYear). State limitation,
        // not a harness fault → PLAN-MISS (backlog), never a faked golden.
        planMiss($raw, "{$caseName}: run() returned false (no reachable outcome on this install)");
        return null;
    }

    $actingLines = logActionRows($db, $gid);
    $expectLines = (int)$meta['logLines'];
    hardAssert(count($actingLines) === $expectLines,
        "{$caseName}: expected {$expectLines} acting line(s), got " . count($actingLines));
    hardAssert(count($general->getItems() ?? []) === $itemCountBefore,
        "{$caseName}: equipment slot count changed");

    $broadcastLines = globalActionRowsSince($db, $globalIdBefore);

    $generalAfter = General::createObjFromDB($gid);
    $cityAfter = $cityId ? $db->queryFirstRow('SELECT * FROM city WHERE city=%i', $cityId) : null;
    $after = snapshotGeneralCity($generalAfter, $cityAfter);
    hardAssert($generalAfter->getVar('explevel') === $explevelBefore, "{$caseName}: exp level crossed");
    hardAssert($generalAfter->getVar('dedlevel') === $dedlevelBefore, "{$caseName}: ded level crossed");

    $case = [
        'case'=>$caseName, 'command'=>$raw, 'ctor'=>$meta['ctor'], 'scope'=>$scope,
        'generalId'=>$gid, 'cityId'=>$cityId,
        'env'=>['year'=>$year,'startYear'=>$env['startyear'],'month'=>$month,'develCost'=>$env['develcost']],
        'arg'=>$arg, 'seedString'=>$seedString, 'reqGold'=>$reqGold,
        'before'=>$before, 'after'=>$after,
        'logLines'=>$actingLines, 'broadcastLines'=>$broadcastLines,
    ];

    if (!empty($meta['crossGeneral'])) {
        $rows = $db->query('SELECT DISTINCT general_id FROM general_record WHERE log_type=%s AND general_id<>0 AND general_id<>%i', 'action', $gid);
        $destOut = [];
        foreach ($rows as $r) {
            $did = (int)$r['general_id'];
            $dG = General::createObjFromDB($did);
            $dC = (int)$dG->getVar('city') ? $db->queryFirstRow('SELECT * FROM city WHERE city=%i', (int)$dG->getVar('city')) : null;
            $destOut[] = ['generalId'=>$did, 'after'=>snapshotGeneralCity($dG, $dC), 'logLines'=>logActionRows($db, $did)];
        }
        $case['destGenerals'] = $destOut;
    }
    return $case;
}

// ── tractable command set (the rest are backlogged with reasons) ─────────────
$targets = [
    'che_거병', 'che_등용',
    'che_이동', 'che_훈련', 'che_사기진작', 'che_소집해제', 'cr_맹훈련', 'che_징병', 'che_모병',
    'che_증여', 'che_헌납', 'che_장비매매', 'che_군량매매',
    'che_발령', 'che_포상', 'che_천도', 'che_국호변경', 'che_국기변경',
    // che_랜덤임관: backlogged — its tryUniqueItemLottery seam grants an item on this
    // install's seed (2 acting lines), tripping HARD assertion 4. See p2-capture-backlog.md.
];
if ($onlyCmd !== null) $targets = [$onlyCmd];

$env0 = $gameStor->getAll();
$byCommand = [];
foreach ($targets as $raw) {
    if (!isset($cmdMeta[$raw])) { planMiss($raw, "not in manifest"); continue; }
    $meta = $cmdMeta[$raw];
    $plan = planFor($db, $raw, $env0);
    if ($plan === null) { planMiss($raw, "no actor/plan on this install"); continue; }
    [$gid, $arg, $preFn] = $plan;

    // freshness: a pristine actor (no prior action rows).
    $prior = (int)$db->queryFirstField('SELECT COUNT(*) FROM general_record WHERE general_id=%i AND log_type=%s', $gid, 'action');
    if ($prior !== 0) { planMiss($raw, "actor {$gid} dirty ({$prior} prior rows) — reinstall"); continue; }

    // snapshot baseline (full nation set, like capture_command.php restore breadth).
    $bg = $db->queryFirstRow('SELECT * FROM general WHERE no=%i', $gid);
    $cid = (int)$bg['city'];
    $bc = $cid ? $db->queryFirstRow('SELECT * FROM city WHERE city=%i', $cid) : null;
    $nid = (int)$bg['nation'];
    $natGenerals = $nid ? $db->query('SELECT * FROM general WHERE nation=%i', $nid) : [];
    $natCities   = $nid ? $db->query('SELECT * FROM city WHERE nation=%i', $nid) : [];
    $natRow      = $nid ? $db->queryFirstRow('SELECT * FROM nation WHERE nation=%i', $nid) : null;

    $case = captureCase($db, $meta, $raw, $raw, $gid, $bg, $bc, $arg, $preFn, $year, $monthBase, $hiddenSeed);

    // restore the whole touched nation set so the captures stay independent.
    foreach ($natGenerals as $r) $db->update('general', $r, 'no=%i', (int)$r['no']);
    foreach ($natCities as $r)   $db->update('city', $r, 'city=%i', (int)$r['city']);
    if ($natRow) $db->update('nation', $natRow, 'nation=%i', $nid);
    $db->update('general', $bg, 'no=%i', $gid);
    if ($bc) $db->update('city', $bc, 'city=%i', $cid);
    foreach ($natGenerals as $r) $db->delete('general_record', 'log_type=%s AND general_id=%i', 'action', (int)$r['no']);
    $db->delete('general_record', 'log_type=%s AND general_id=%i', 'action', $gid);

    if ($case === null) continue;
    $byCommand[$raw][] = $case;
}

foreach ($byCommand as $cls => $cases) {
    $out = ['hiddenSeed'=>$hiddenSeed, 'command'=>$cls,
        'env'=>['year'=>$year,'startYear'=>$startYear,'develCost'=>$develCost], 'cases'=>$cases];
    $outPath = $outDir . '/' . $cls . '-fixtures.json';
    file_put_contents($outPath, Json::encode($out, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES | JSON_PRETTY_PRINT));
    fwrite(STDERR, "wrote " . basename($outPath) . " (" . count($cases) . " cases)\n");
}
if (!$byCommand) fwrite(STDERR, "no arg-command captured\n");
