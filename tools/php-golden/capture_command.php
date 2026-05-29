<?php
/**
 * capture_command.php — P2 golden capture harness (devsam-core PHP, grand truth).
 *
 * ONE-SHOT, MANUAL HOST STEP — NEVER CI.
 *
 * Generalization of P1's capture_che.php (kept GREEN; this EXTENDS it). It boots a
 * real devsam-core install and, per command in tools/php-golden/manifest.json,
 * DRIVES the REAL turn-execution path exactly as hwe/sammo/TurnExecutionHelper.php
 * does:
 *   - GENERAL commands: built through buildGeneralCommandClass(General,env,arg) (the
 *     3-arg BaseCommand ctor), seeded with the SIX-component simpleSerialize seed
 *     under the 'generalCommand' scope, then run via $cmd->run($rng) — the success
 *     branch of TurnExecutionHelper::processCommand (capture_che.php's exact path).
 *   - NATION commands (the P2 fork): built through the 4-arg
 *     buildNationCommandClass(General,env,LastTurn,arg) (NationCommand ctor —
 *     func_converter.php:434), seeded under the SEPARATE 'nationCommand' scope
 *     (TurnExecutionHelper.php:310-317), then run via $cmd->run($rng) — the success
 *     branch of processNationCommand. The acting general must satisfy the nation-turn
 *     gate (nation!=0 AND officer_level>=5).
 * The captured log + RNG draws are byte/draw-identical to a real turn.
 *
 * Oracle = PHP devsam-core. Per command this emits ONE fixture FILE:
 *   logic/src/test/resources/golden/p2/<command>-fixtures.json
 * carrying, per case: the SIX-component seed string (with its scope token), the env
 * (year/startYear/month/develCost), acting general BEFORE+AFTER, every TOUCHED
 * row's BEFORE+AFTER (dest-general(s), created nation, cascade cities), the acting
 * general's action-log row(s) CHAR-FOR-CHAR, the per-target dest-general action
 * lines (incl. PLAIN), the broadcast globalAction lines (general_record
 * general_id=0/log_type='history'), the computed reqGold, and the per-game
 * hiddenSeed (committed as a fixture INPUT — verbatim from P1).
 *
 * The hard "exactly 1 action line" P1 assert is REPLACED by a per-command EXPECTED
 * acting-general line count read from manifest.json (logLines). restoreBaseline is
 * BROADENED to restore ALL touched rows so the N captures stay mutually independent.
 *
 * Invocation (inside the php capture container, repo mounted at /work):
 *   php tools/php-golden/capture_command.php \
 *       [--command=che_상업투자] [--out-dir=logic/src/test/resources/golden/p2]
 *   (omit --command to capture EVERY pick in $picks. DB creds via SAMMO_DB_* — see
 *    _boot.php. picks come from probe_command.php; see README.)
 *
 * HARD assertions (abort the capture — never write a partial/unfaithful golden):
 *   (1) DISTINCT reachable outcomes per command (varied pick) — each an INDEPENDENT
 *       reproducible fixture capturing its full env.
 *   (2) MODULE-FREE acting general — special/special2/personal ∈ {None,'',null},
 *       every equipment slot a 'None' item (the empty-pipeline identity guard).
 *   (3) NO LEVEL CROSS — explevel/dedlevel unchanged before→after (the deferred
 *       General::checkStatChange path, General.php:455-495, is never exercised).
 *   (4) NO unique item won + NO static event fired — acting-general action line count
 *       == the manifest's logLines AND equipment-slot count unchanged (so neither
 *       perturbs the action RNG stream).
 *   (P1 #5) INTEGER trust — every touched city's trust == floor(trust).
 */

namespace sammo;

require __DIR__ . '/_boot.php';

// ── CLI args (getopt `=` form per devsam capture-env quirk) ──────────────────
$opts    = getopt('', ['command:', 'out-dir:']);
$onlyCmd = $opts['command'] ?? null;                       // null → all picks
$outDir  = $opts['out-dir'] ?? (__DIR__ . '/../../logic/src/test/resources/golden/p2');
if (!is_dir($outDir)) { mkdir($outDir, 0775, true); }

$db = DB::db();

// hiddenSeed — the per-game seed oracle, captured as a fixture INPUT (verbatim P1).
$hiddenSeed = UniqueConst::$hiddenSeed;

// game_env: year / startYear / month / develCost are part of each case's env.
$gameStor = KVStorage::getStorage($db, 'game_env');
[$year, $startYear, $monthBase, $develCost] = $gameStor->getValuesAsArray(
    ['year', 'startyear', 'month', 'develcost']
);

// ── manifest (FG1) — the per-command classification: ctor scope + expected acting-
//    general line count + lottery token. The capture asserts the line count and forks
//    the ctor by `ctor`. ───────────────────────────────────────────────────────────
$manifest = Json::decode(file_get_contents(__DIR__ . '/manifest.json'));
$cmdMeta = [];
foreach ($manifest['commands'] as $group => $list) {
    foreach ($list as $entry) { $cmdMeta[$entry['rawClassName']] = $entry; }
}

/** Look up a command's manifest entry; abort if it is not in the P2 surface. */
function metaFor(array $cmdMeta, string $raw): array {
    hardAssert(isset($cmdMeta[$raw]), "command {$raw} is not in manifest.json (not a P2 command?)");
    return $cmdMeta[$raw];
}

function hardAssert(bool $cond, string $msg): void {
    if (!$cond) {
        fwrite(STDERR, "FG2 HARD-ASSERT FAILED: {$msg}\n");
        exit(2);
    }
}

function isNoneField(?string $v): bool {
    return $v === null || $v === '' || $v === 'None';
}

/** MODULE-FREE acting general (HARD assertion 2). */
function assertModuleFree(General $g): void {
    hardAssert(isNoneField($g->getVar('special')),  'general has special (내정특기) module');
    hardAssert(isNoneField($g->getVar('special2')), 'general has special2 (전투특기) module');
    hardAssert(isNoneField($g->getVar('personal')), 'general has personal (성격) module');
    foreach (($g->getItems() ?? []) as $slot => $item) {
        hardAssert($item instanceof \sammo\ActionItem\None, "equipment slot {$slot} not a None item");
    }
}

/** Acting/dest general action-log rows (char-for-char): general_record
 *  general_id=<gid>, log_type='action' (func_history.php:114). */
function logActionRows(object $db, int $gid): array {
    $rows = $db->query(
        'SELECT text FROM general_record WHERE general_id=%i AND log_type=%s ORDER BY id', $gid, 'action'
    );
    $out = [];
    foreach ($rows as $r) $out[] = $r['text'];
    return $out;
}

/** Broadcast globalAction rows: general_record general_id=0, log_type='history'
 *  (pushGlobalActionLog — func_history.php:344). Returns rows added THIS turn (rows
 *  whose id exceeds the pre-turn max — the baseline restore does NOT clear id=0). */
function globalActionRowsSince(object $db, int $afterId): array {
    $rows = $db->query(
        'SELECT id, text FROM general_record WHERE general_id=0 AND log_type=%s AND id>%i ORDER BY id',
        'history', $afterId
    );
    $out = [];
    foreach ($rows as $r) $out[] = $r['text'];
    return $out;
}

function maxGlobalActionId(object $db): int {
    return (int)($db->queryFirstField(
        'SELECT COALESCE(MAX(id),0) FROM general_record WHERE general_id=0 AND log_type=%s', 'history'
    ));
}

/** Set the game clock month (the ActionLogger reads it via createObjFromDB). */
function setGameMonth(object $db, int $month): array {
    $gs = KVStorage::getStorage($db, 'game_env');
    $gs->month = $month;
    $gs->resetCache();
    return KVStorage::getStorage($db, 'game_env')->getAll();
}

/** exp band [lo,hi) for getExpLevel L; ded band (lo,hi] for getDedLevel L. */
function expBandLo(int $L): int { return $L < 10 ? $L * 100 : 10 * $L * $L; }
function expBandHi(int $L): int { return $L < 10 ? ($L + 1) * 100 : 10 * ($L + 1) * ($L + 1); }
function dedBandLo(int $L): int { return $L <= 0 ? 0 : (10 * ($L - 1)) * (10 * ($L - 1)); }
function dedBandHi(int $L): int { return (10 * $L) * (10 * $L); }

/** Module-free + no-level-cross precondition (static input; computed golden stays
 *  100% real PHP) — verbatim from capture_che.php::applyPrecondition. */
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

/** General + city snapshot (the fields the Kotlin gate compares). */
function snapshotGeneralCity(General $g, ?array $city): array {
    $snap = [
        'general' => [
            'gold'                  => $g->getVar('gold'),
            'experience'            => $g->getVar('experience'),
            'dedication'            => $g->getVar('dedication'),
            'intel_exp'             => $g->getVar('intel_exp'),
            'explevel'              => $g->getVar('explevel'),
            'nation'                => $g->getVar('nation'),
            'officer_level'         => $g->getVar('officer_level'),
            'city'                  => $g->getVar('city'),
            'max_domestic_critical' => $g->getAuxVar('max_domestic_critical') ?? 0,
        ],
    ];
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

/**
 * Capture ONE command case as an independent reproducible fixture. Forks the ctor +
 * seed scope on the manifest's `ctor` (general | nation). The caller has restored
 * ALL touched rows to baseline before this call.
 */
function captureCase(
    object $db, array $meta, string $caseName, string $rawClassName,
    int $generalId, int $year, int $month, string $hiddenSeed
): array {
    $env = setGameMonth($db, $month);

    $general = General::createObjFromDB($generalId);
    $cityId  = (int)$general->getVar('city');
    $city    = $db->queryFirstRow('SELECT * FROM city WHERE city=%i', $cityId);

    assertModuleFree($general);

    $explevelBefore = $general->getVar('explevel');
    $dedlevelBefore = $general->getVar('dedlevel');
    if ($city !== null) {
        hardAssert($city['trust'] == floor($city['trust']), "city {$cityId} trust not integer");
    }

    $isNation = ($meta['ctor'] === 'nation');
    $scope    = $isNation ? 'nationCommand' : 'generalCommand';

    if ($isNation) {
        // Nation-turn gate (TurnExecutionHelper.php:260): nation!=0 AND officer>=5.
        hardAssert((int)$general->getVar('nation') !== 0, "{$caseName}: nation command but acting general is neutral");
        hardAssert((int)$general->getVar('officer_level') >= 5, "{$caseName}: nation command but officer_level<5");
        $lastTurn = LastTurn::fromRaw(null);   // fresh nation-turn state for the capture
        $cmd = buildNationCommandClass($rawClassName, $general, $env, $lastTurn, null);
    } else {
        $cmd = buildGeneralCommandClass($rawClassName, $general, $env, null);
    }
    hardAssert($cmd->getRawClassName() === $rawClassName,
        "factory returned {$cmd->getRawClassName()} for {$rawClassName}");
    hardAssert($cmd->hasFullConditionMet(), "{$caseName}: command precondition not met");
    // reqGold: the gold component of getCost() (every command implements getCost();
    // only some commands declare a $reqGold property — guard it via reflection).
    $reqGold = null;
    if (property_exists($cmd, 'reqGold')) {
        $rp = new \ReflectionProperty($cmd, 'reqGold');
        if (PHP_VERSION_ID < 80100) { $rp->setAccessible(true); }
        if ($rp->isInitialized($cmd)) { $reqGold = $rp->getValue($cmd); }
    }
    $reqGold = $reqGold ?? ($cmd->getCost()[0] ?? null);

    $before = snapshotGeneralCity($general, $city);
    $itemCountBefore = count($general->getItems() ?? []);
    $globalIdBefore  = maxGlobalActionId($db);

    // The exact per-turn RNG (six-component seed; scope token forks general/nation).
    $seedString = Util::simpleSerialize(
        $hiddenSeed, $scope, $year, $month, $generalId, $cmd->getRawClassName()
    );
    $rng = new RandUtil(new LiteHashDRBG($seedString));

    $ok = $cmd->run($rng);   // flushes the ActionLogger into general_record (applyDB)
    hardAssert($ok === true, "{$caseName}: run() returned false");

    // Acting-general action lines.
    $actingLines = logActionRows($db, $generalId);
    $expectLines = (int)$meta['logLines'];
    hardAssert(count($actingLines) === $expectLines,
        "{$caseName}: expected {$expectLines} acting action line(s), got " . count($actingLines)
        . " (unique-item/static-event fired, or manifest logLines wrong?)");
    hardAssert(count($general->getItems() ?? []) === $itemCountBefore,
        "{$caseName}: equipment slot count changed (unique item granted)");

    // Broadcast globalAction lines added this turn (id past the pre-turn max).
    $broadcastLines = globalActionRowsSince($db, $globalIdBefore);

    // AFTER (reload — the command applyDB-flushed every mutation).
    $generalAfter = General::createObjFromDB($generalId);
    $cityAfter    = $cityId ? $db->queryFirstRow('SELECT * FROM city WHERE city=%i', $cityId) : null;
    $after = snapshotGeneralCity($generalAfter, $cityAfter);

    hardAssert($generalAfter->getVar('explevel') === $explevelBefore, "{$caseName}: exp level crossed");
    hardAssert($generalAfter->getVar('dedlevel') === $dedlevelBefore, "{$caseName}: ded level crossed");

    $case = [
        'case'          => $caseName,
        'command'       => $rawClassName,
        'ctor'          => $meta['ctor'],
        'scope'         => $scope,
        'generalId'     => $generalId,
        'cityId'        => $cityId,
        'env'           => [
            'year'      => $year,
            'startYear' => $env['startyear'],
            'month'     => $month,
            'develCost' => $env['develcost'],
        ],
        'seedString'    => $seedString,
        'reqGold'       => $reqGold,
        'before'        => $before,
        'after'         => $after,
        'logLines'      => $actingLines,       // acting general, char-for-char
        'broadcastLines'=> $broadcastLines,    // pushGlobalActionLog, char-for-char
    ];

    // crossGeneral: capture each dest-general's action lines (incl. PLAIN) + after row.
    if (!empty($meta['crossGeneral'])) {
        $destIds = resolveDestGenerals($db, $cmd, $generalId);
        $destOut = [];
        foreach ($destIds as $did) {
            $destAfter = General::createObjFromDB($did);
            $destCity  = (int)$destAfter->getVar('city')
                ? $db->queryFirstRow('SELECT * FROM city WHERE city=%i', (int)$destAfter->getVar('city')) : null;
            $destOut[] = [
                'generalId' => $did,
                'after'     => snapshotGeneralCity($destAfter, $destCity),
                'logLines'  => logActionRows($db, $did),
            ];
        }
        $case['destGenerals'] = $destOut;
    }

    return $case;
}

/**
 * Resolve the dest-general id(s) a cross-general command touched THIS turn — the
 * generals (other than the actor) that gained an 'action' log row from this command.
 * The caller cleared every touched general's action rows at baseline restore, so a
 * non-actor general carrying an 'action' row was written by this turn.
 */
function resolveDestGenerals(object $db, \sammo\Command\BaseCommand $cmd, int $actingId): array {
    $arg = $cmd->getArg() ?? [];
    $candidates = [];
    foreach (['destGeneralID', 'destGeneralId', 'targetGeneralID', 'general'] as $k) {
        if (isset($arg[$k]) && (int)$arg[$k] > 0 && (int)$arg[$k] !== $actingId) $candidates[(int)$arg[$k]] = true;
    }
    if (!$candidates) {
        // fall back to any non-actor general that gained an action row this turn.
        $rows = $db->query(
            'SELECT DISTINCT general_id FROM general_record WHERE log_type=%s AND general_id<>0 AND general_id<>%i',
            'action', $actingId
        );
        foreach ($rows as $r) $candidates[(int)$r['general_id']] = true;
    }
    return array_keys($candidates);
}

// ── picks (operator fills via probe_command.php for the running install/seed) ────
//    Each row: [caseName, rawClassName, generalId, month]. The P1 commerce/agri set
//    is kept verbatim as the proven smoke (proves the harness still produces a valid
//    fixture end-to-end — the F-GOLDEN-0 gate). Append the new P2 command picks.
$picks = [
    // caseName            rawClassName     generalId  month
    // ── develop family (zero-arg domestic; probe_command.php PICKS, gid=8 city=19,
    //    hiddenSeed=cff8658592f2d3c55d404232a019e016) ──
    ['commerce_success',   'che_상업투자',  8, 1],
    ['commerce_fail',      'che_상업투자',  8, 3],
    ['agri_success',       'che_농지개간',  8, 2],
    ['agri_fail',          'che_농지개간',  8, 1],
    ['wall_success',       'che_성벽보수',  8, 6],
    ['wall_normal',        'che_성벽보수',  8, 1],
    ['def_success',        'che_수비강화',  8, 6],
    ['def_normal',         'che_수비강화',  8, 2],
    ['def_fail',           'che_수비강화',  8, 1],
    ['security_success',   'che_치안강화',  8, 1],
    ['security_normal',    'che_치안강화',  8, 2],
    ['tech_success',       'che_기술연구',  8, 3],
    ['tech_fail',          'che_기술연구',  8, 1],
    ['settle_success',     'che_정착장려',  8, 2],
    ['settle_fail',        'che_정착장려',  8, 1],
    ['select_success',     'che_주민선정',  8, 1],
    ['select_fail',        'che_주민선정',  8, 4],
    ['procure_success',    'che_물자조달',  8, 2],
    ['procure_normal',     'che_물자조달',  8, 1],
    ['procure_fail',       'che_물자조달',  8, 5],
    // ── personnel: 하야 (NotBeNeutral + NotLord; broadcast) ──
    ['resign_normal',      'che_하야',      8, 1],
];

// ── independence (HARD assertion 1): snapshot EVERY touched row at baseline, restore
//    all of them before each case. The touched set is broadened beyond P1's single
//    general+city to dest-general(s), the created nation, and cascade cities. ───────
$baselineGids   = [];
$baselineCids   = [];
foreach ($picks as [, , $gid,]) $baselineGids[$gid] = true;
$baselineGeneral = [];   // gid    => raw general row
$baselineCity    = [];   // cityId => raw city row
$baselineNation  = [];   // nationId => raw nation row (created/mutated by founding/nation cmds)
foreach (array_keys($baselineGids) as $gid) {
    $priorLog = (int)$db->queryFirstField(
        'SELECT COUNT(*) FROM general_record WHERE general_id=%i AND log_type=%s', $gid, 'action'
    );
    hardAssert($priorLog === 0,
        "general {$gid} has {$priorLog} prior action-log rows — DB is dirty; "
        . "re-run install_scenario.php for a pristine baseline before capturing");
    $gRow = applyPrecondition($db, $gid);
    $baselineGeneral[$gid] = $gRow;
    $cid = (int)$gRow['city'];
    if ($cid) {
        $baselineCity[$cid] = $db->queryFirstRow('SELECT * FROM city WHERE city=%i', $cid);
        $baselineCids[$cid] = true;
    }
}

/**
 * BROADENED restore (FG2): restore the acting general + its city, PLUS every other
 * row this command could touch — dest-general(s), the created/mutated nation, and
 * cascade cities — so the N captures are mutually independent. We snapshot rows
 * lazily (the FIRST time a row is seen post-mutation we cannot know its pristine
 * value, so we snapshot the FULL general/city/nation tables once up front for the
 * picked generals' nations and restore from those).
 */
$baselineAllGeneral = [];  // gid => row — every general in a picked general's nation
$baselineAllCity    = [];  // cid => row
$baselineAllNation  = [];  // nid => row
foreach (array_keys($baselineGids) as $gid) {
    $nid = (int)$db->queryFirstField('SELECT nation FROM general WHERE no=%i', $gid);
    if ($nid === 0) continue;
    foreach ($db->query('SELECT * FROM general WHERE nation=%i', $nid) as $r) $baselineAllGeneral[(int)$r['no']] = $r;
    foreach ($db->query('SELECT * FROM city WHERE nation=%i', $nid) as $r) $baselineAllCity[(int)$r['city']] = $r;
    $nrow = $db->queryFirstRow('SELECT * FROM nation WHERE nation=%i', $nid);
    if ($nrow) $baselineAllNation[$nid] = $nrow;
}

function restoreBaseline(
    object $db, int $gid, array $bg, array $bc,
    array $ballg, array $ballc, array $balln
): void {
    // acting general + city (the always-touched pair, verbatim P1).
    $gRow = $bg[$gid];
    $cid  = (int)$gRow['city'];
    $db->update('general', $gRow, 'no=%i', $gid);
    if ($cid && isset($bc[$cid])) $db->update('city', $bc[$cid], 'city=%i', $cid);

    // broadened: restore every general/city/nation in the picked nation set (covers
    // dest-general reassignment/reward, founding cascade, capital move).
    foreach ($ballg as $no => $row) $db->update('general', $row, 'no=%i', $no);
    foreach ($ballc as $c  => $row) $db->update('city', $row, 'city=%i', $c);
    foreach ($balln as $nid => $row) $db->update('nation', $row, 'nation=%i', $nid);

    // clear THIS turn's action rows on every touched general + the broadcast id=0
    // history rows added after the dump (so the buffer/record stays canonical). We
    // delete acting + all picked-nation generals' action rows.
    $db->delete('general_record', 'log_type=%s AND general_id=%i', 'action', $gid);
    foreach (array_keys($ballg) as $no) {
        $db->delete('general_record', 'log_type=%s AND general_id=%i', 'action', $no);
    }
}

// ── capture loop ─────────────────────────────────────────────────────────────
$byCommand = [];        // rawClassName => [cases]
foreach ($picks as [$name, $cls, $gid, $m]) {
    if ($onlyCmd !== null && $cls !== $onlyCmd) continue;
    hardAssert($gid !== null && $m !== null,
        "operator must fill generalId/month for case '{$name}' (see README)");
    $meta = metaFor($cmdMeta, $cls);
    restoreBaseline($db, $gid, $baselineGeneral, $baselineCity,
                    $baselineAllGeneral, $baselineAllCity, $baselineAllNation);
    $case = captureCase($db, $meta, $name, $cls, $gid, $year, $m, $hiddenSeed);

    // HARD assertion (1) for the commerce success/normal/fail trio (proven P1 smoke).
    if (str_starts_with($name, 'commerce_')) {
        $pickKind = inferPickFromLog($case['logLines'][0] ?? '');
        $expected = substr($name, strlen('commerce_'));
        hardAssert($pickKind === $expected, "case {$name} produced pick '{$pickKind}'");
    }
    $byCommand[$cls][] = $case;
}

// ── emit ONE fixture file per command (PHP Json::encode key insertion order, ─────
//    compact, UTF-8 literal Korean, unescaped slashes — mirrors the meta jsonb). ──
foreach ($byCommand as $cls => $cases) {
    $out = [
        'hiddenSeed' => $hiddenSeed,           // committed as a fixture INPUT (verbatim P1)
        'command'    => $cls,
        'env'        => [
            'year'      => $year,
            'startYear' => $startYear,
            'develCost' => $develCost,
        ],
        'cases'      => $cases,
    ];
    $outPath = $outDir . '/' . $cls . '-fixtures.json';
    file_put_contents(
        $outPath,
        Json::encode($out, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES | JSON_PRETTY_PRINT)
    );
    fwrite(STDERR, "wrote " . basename($outPath) . " (" . count($cases) . " cases)\n");
}
if (!$byCommand) { fwrite(STDERR, "no picks matched (--command filter?)\n"); }

// ── helpers ──────────────────────────────────────────────────────────────────
function inferPickFromLog(string $line): string {
    if (str_contains($line, "ev_failed")) return 'fail';
    if (str_contains($line, "<S>성공</>")) return 'success';
    return 'normal';
}
