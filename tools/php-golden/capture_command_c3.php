<?php
/**
 * capture_command_c3.php — C3 chief-command ARG/STATE-gated golden capture
 * (devsam-core PHP, grand truth). ONE-SHOT, MANUAL HOST — NEVER CI.
 *
 * Sibling of capture_command_args.php. Captures the 12 missing C3 chief commands
 * (hwe/sammo/Command/Nation/): che_급습, che_몰수, che_물자원조, che_백성동원,
 * che_부대탈퇴지시, che_수몰, che_의병모집, che_이호경식, che_초토화, che_피장파장,
 * che_필사즉생, che_허보. All are NATION-scope (4-arg buildNationCommandClass,
 * 'nationCommand' seed). Most are 전략(strategic) commands gated by:
 *   - AvailableStrategicCommand: nation.strategic_cmd_limit <= 0,
 *   - a diplomacy-STATE gate vs the dest nation (state 0=전쟁중 / 1=선포 / 2=평시),
 *   - some by surlimit==0 and/or NotOpeningPart(relYear>=GameConst::$openingPartYear=3).
 *
 * The harness drives the SAME real path TurnExecutionHelper does:
 *   $cmd = buildNationCommandClass($raw, $general, $env, LastTurn::fromRaw(null), $arg);
 *   $seed = Util::simpleSerialize($hiddenSeed,'nationCommand',$year,$month,$gid,$raw);
 *   $cmd->run(new RandUtilDrawRecorder(new LiteHashDRBG($seed)));   // ← draw-recording
 * RandUtilDrawRecorder `extends RandUtil` and is DRAW-NEUTRAL (byte-identical output),
 * so the recorded draw stream is the EXACT stream a real turn pulls. che_허보/che_몰수/
 * che_의병모집 actually draw; the rest are deterministic (draw_count=0).
 *
 * Per command a PLAN entry supplies: the actor (the nation-1 module-free chief), the
 * live arg (dest city/general/nation ids from the running install), and a documented
 * STATIC-INPUT precondition (strategic_cmd_limit=0, surlimit=0, a diplomacy state row,
 * a year bump past the opening window, a friendly troop member, a target with gold).
 * These position the gate exactly as a mid-game turn would; the action's draws, log
 * strings, and row deltas are 100% real PHP. NO computed value is fabricated.
 *
 * The HARD assertions from capture_command_args.php are KEPT verbatim (module-free
 * acting general, no level cross, exact acting action-line count = manifest_c3 logLines,
 * integer trust). A command whose plan cannot meet hasFullConditionMet() on this
 * install, or whose run() finds no reachable outcome, is SKIPPED (PLAN-MISS) — never
 * faked — and recorded in tools/php-golden/c3-capture-backlog.md with the exact reason.
 *
 * Emits ONE fixture per command: golden/p2/<command>-fixtures.json (same shape as
 * capture_command_args.php PLUS a `draws` block: draw_count + draw_stream).
 *
 * Invocation (inside the php capture container, repo mounted at /work):
 *   php tools/php-golden/capture_command_c3.php [--command=che_수몰]
 *       [--out-dir=logic/src/test/resources/golden/p2]
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

$manifest = Json::decode(file_get_contents(__DIR__ . '/manifest_c3.json'));
$cmdMeta = [];
foreach ($manifest['commands'] as $group => $list) {
    foreach ($list as $entry) { $cmdMeta[$entry['rawClassName']] = $entry; }
}

function hardAssert(bool $cond, string $msg): void {
    if (!$cond) { fwrite(STDERR, "C3 HARD-ASSERT FAILED: {$msg}\n"); exit(2); }
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
/** general-history rows (general_record log_type='history', YEAR_MONTH fmt) for one general
 *  added AFTER $afterId — the event_* research command's pushGeneralHistoryLog surface. */
function generalHistoryRowsSince(object $db, int $gid, int $afterId): array {
    $rows = $db->query('SELECT text FROM general_record WHERE general_id=%i AND log_type=%s AND id>%i ORDER BY id', $gid, 'history', $afterId);
    $out = []; foreach ($rows as $r) $out[] = $r['text']; return $out;
}
function maxGeneralHistoryId(object $db, int $gid): int {
    return (int)($db->queryFirstField('SELECT COALESCE(MAX(id),0) FROM general_record WHERE general_id=%i AND log_type=%s', $gid, 'history'));
}
/** national-history rows (world_history, nation_id=nationID, YEAR_MONTH fmt) added AFTER
 *  $afterId — the event_* research command's pushNationalHistoryLog surface. */
function nationalHistoryRowsSince(object $db, int $nationID, int $afterId): array {
    $rows = $db->query('SELECT text FROM world_history WHERE nation_id=%i AND id>%i ORDER BY id', $nationID, $afterId);
    $out = []; foreach ($rows as $r) $out[] = $r['text']; return $out;
}
function maxWorldHistoryId(object $db): int {
    return (int)($db->queryFirstField('SELECT COALESCE(MAX(id),0) FROM world_history'));
}
function globalActionRowsSince(object $db, int $afterId): array {
    $rows = $db->query('SELECT id, text FROM general_record WHERE general_id=0 AND log_type=%s AND id>%i ORDER BY id', 'history', $afterId);
    $out = []; foreach ($rows as $r) $out[] = $r['text']; return $out;
}
function maxGlobalActionId(object $db): int {
    return (int)($db->queryFirstField('SELECT COALESCE(MAX(id),0) FROM general_record WHERE general_id=0 AND log_type=%s', 'history'));
}
/** dest-general/dest-nation PLAIN broadcast action lines for an arbitrary recipient. */
function actionRowsForGeneral(object $db, int $gid): array {
    return logActionRows($db, $gid);
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
 * Positions the actor ~40% into its current exp/ded band so the per-command addExperience/
 * addDedication (and 초토화's -10% exp) keep explevel/dedlevel unchanged.
 */
function applyPrecondition(object $db, int $gid): array {
    $row = $db->queryFirstRow('SELECT experience, dedication FROM general WHERE no=%i', $gid);
    $el = getExpLevel((int)$row['experience']); $dl = getDedLevel((int)$row['dedication']);
    $emid = (int)(expBandLo($el) + (expBandHi($el)-expBandLo($el))*0.4);
    $dmid = (int)(dedBandLo($dl) + (dedBandHi($dl)-dedBandLo($dl))*0.4);
    $db->update('general', ['personal'=>'None','experience'=>$emid,'dedication'=>$dmid,'explevel'=>getExpLevel($emid),'dedlevel'=>getDedLevel($dmid)], 'no=%i', $gid);
    return $db->queryFirstRow('SELECT * FROM general WHERE no=%i', $gid);
}

/**
 * Snapshot a nation's treasury + aux (event_극병연구's parity surface). gold/rice are
 * integer columns; aux is a JSON column — decode it so the fixture carries the live
 * key→value map (can_극병사용 unset before → ==1 after). 100% real PHP row values.
 */
function snapshotNationAux(object $db, int $nationID): array {
    $row = $db->queryFirstRow('SELECT gold, rice, aux FROM nation WHERE nation=%i', $nationID);
    $aux = $row['aux'];
    if (is_string($aux)) { $aux = $aux === '' ? [] : Json::decode($aux); }
    if (!is_array($aux)) { $aux = []; }
    return ['nation' => $nationID, 'gold' => (int)$row['gold'], 'rice' => (int)$row['rice'], 'aux' => $aux];
}

/**
 * Read the active_action inheritance point for a general's owner (the value the
 * increaseInheritancePoint(active_action,1) bumps). Stored in the `storage` table,
 * namespace `inheritance_{ownerID}`, key `active_action`, value=Json([value, aux]).
 * Returns ['owner'=>ownerID, 'active_action'=>floatOrNull]. owner==0 / npc>=2 → no bump.
 */
function snapshotInheritanceActiveAction(object $db, General $g): array {
    $owner = (int)$g->getVar('owner');
    if (!$owner) return ['owner' => 0, 'active_action' => null];
    $stor = KVStorage::getStorage($db, "inheritance_{$owner}");
    $entry = $stor->getValue(\sammo\Enums\InheritanceKey::active_action->value);
    $val = null;
    if (is_array($entry)) { $val = $entry[0]; }   // [value, aux]
    return ['owner' => $owner, 'active_action' => $val];
}

function snapshotGeneralCity(General $g, ?array $city): array {
    $snap = ['general' => [
        'gold' => $g->getVar('gold'), 'rice' => $g->getVar('rice'),
        'crew' => $g->getVar('crew'), 'train' => $g->getVar('train'), 'atmos' => $g->getVar('atmos'),
        'experience' => $g->getVar('experience'), 'dedication' => $g->getVar('dedication'),
        'intel_exp' => $g->getVar('intel_exp'), 'explevel' => $g->getVar('explevel'),
        'nation' => $g->getVar('nation'), 'officer_level' => $g->getVar('officer_level'),
        'city' => $g->getVar('city'), 'troop' => $g->getVar('troop'),
        'betray' => $g->getVar('betray'),
        'max_domestic_critical' => $g->getAuxVar('max_domestic_critical') ?? 0,
    ]];
    if ($city !== null) {
        $snap['city'] = ['comm'=>$city['comm'],'agri'=>$city['agri'],'pop'=>$city['pop'],
            'comm_max'=>$city['comm_max'],'agri_max'=>$city['agri_max'],
            'def'=>$city['def'],'wall'=>$city['wall'],'def_max'=>$city['def_max'],'wall_max'=>$city['wall_max'],
            'secu'=>$city['secu'],'secu_max'=>$city['secu_max'],'level'=>$city['level'],
            'trust'=>$city['trust'],'nation'=>$city['nation'],'front'=>$city['front']];
    }
    return $snap;
}

/** Snapshot the FULL world rows the C3 captures can touch (both nations + all generals
 *  + all cities + diplomacy + select_pool + game_env npccount) so each capture is
 *  restored byte-for-byte and the N captures stay mutually independent. */
function snapshotWorld(object $db): array {
    return [
        'generals'   => $db->query('SELECT * FROM general'),
        'cities'     => $db->query('SELECT * FROM city'),
        'nations'    => $db->query('SELECT * FROM nation'),
        'diplomacy'  => $db->query('SELECT * FROM diplomacy'),
        'troops'     => $db->query('SELECT * FROM troop'),
        'npccount'   => (int)(KVStorage::getStorage($db, 'game_env')->npccount ?? 0),
        'maxGenNo'   => (int)$db->queryFirstField('SELECT COALESCE(MAX(no),0) FROM general'),
        'maxGlobalId'=> maxGlobalActionId($db),
        'maxWorldHistoryId' => maxWorldHistoryId($db),
        'maxPoolId'  => (int)$db->queryFirstField('SELECT COALESCE(MAX(id),0) FROM select_pool'),
        // nation_env is its OWN table (namespace=int nationID, column `key`).
        'nationEnv'  => $db->query('SELECT namespace, `key`, value FROM nation_env'),
    ];
}

/** Restore the world to the pre-capture snapshot (drop created NPCs, restore rows,
 *  clear this turn's general_record + select_pool reservations, reset npccount). */
function restoreWorld(object $db, array $w): void {
    // delete generals created during the capture (의병모집).
    $db->delete('general', 'no > %i', $w['maxGenNo']);
    // restore base general/city/nation/diplomacy rows.
    foreach ($w['generals'] as $r) $db->update('general', $r, 'no=%i', (int)$r['no']);
    foreach ($w['cities'] as $r)   $db->update('city', $r, 'city=%i', (int)$r['city']);
    foreach ($w['nations'] as $r)  $db->update('nation', $r, 'nation=%i', (int)$r['nation']);
    // diplomacy: delete-all + reinsert (rows are few; preserves exact state/term).
    $db->query('DELETE FROM diplomacy');
    foreach ($w['diplomacy'] as $r) $db->insert('diplomacy', $r);
    // troop: delete-all + reinsert the base snapshot (부대탈퇴지시 inserts a temp troop).
    $db->query('DELETE FROM troop');
    foreach ($w['troops'] as $r) $db->insert('troop', $r);
    // nation_env (own table): delete-all + reinsert the base snapshot.
    $db->query('DELETE FROM nation_env');
    foreach ($w['nationEnv'] as $r) {
        $db->insert('nation_env', ['namespace'=>$r['namespace'],'key'=>$r['key'],'value'=>$r['value']]);
    }
    // select_pool: drop rows 의병모집 inserted beyond the base max id.
    $db->query('DELETE FROM select_pool WHERE id > %i', $w['maxPoolId']);
    // game_env npccount.
    $gs = KVStorage::getStorage($db, 'game_env'); $gs->npccount = $w['npccount']; $gs->resetCache();
    // wipe THIS-capture action rows + new global-action history rows.
    $db->delete('general_record', 'log_type=%s AND id>%i', 'history', $w['maxGlobalId']);
    $db->query('DELETE FROM general_record WHERE log_type=%s', 'action');
    // wipe THIS-capture national-history rows (event_* research pushNationalHistoryLog).
    if (isset($w['maxWorldHistoryId'])) {
        $db->delete('world_history', 'id>%i', $w['maxWorldHistoryId']);
    }
}

/** Refresh the static nation/city caches after a precondition mutation. */
function refreshCaches(): void {
    \sammo\refreshNationStaticInfo();
    if (function_exists('\\sammo\\CityConst::reset')) { /* noop guard */ }
}

/**
 * The PLAN registry: per C3 command, [gid, arg, preFn, year]. preFn applies the
 * documented static-input precondition on the LIVE install (strategic gate open,
 * diplomacy state, surlimit, target state). year overrides the env year (for the
 * NotOpeningPart commands). Returns null → PLAN-MISS → backlog.
 *
 * Diplomacy state codes (verified from the constraint impls + che_선전포고):
 *   0 = 전쟁중(active war), 1 = 선포(declared), 2 = 평시(peace).
 */
function planFor(object $db, string $raw, array $env): ?array {
    $startYear = (int)$env['startyear'];

    // the nation-1 module-free chief (officer>=5). Nation 2 has no module-free chief,
    // so the acting general is always nation 1's chief; dest = nation 2 / a friendly.
    $chief = (int)$db->queryFirstField(
        "SELECT g.no FROM general g JOIN city c ON g.city=c.city
           WHERE c.nation<>0 AND c.supply=1 AND g.nation=c.nation AND g.officer_level>=5
             AND (g.special='None' OR g.special='' OR g.special IS NULL)
             AND (g.special2='None' OR g.special2='' OR g.special2 IS NULL)
           ORDER BY g.no LIMIT 1");
    if (!$chief) return null;
    $nid = (int)$db->queryFirstField('SELECT nation FROM general WHERE no=%i', $chief);

    // a different (enemy) nation.
    $enemy = (int)$db->queryFirstField('SELECT nation FROM nation WHERE nation<>%i ORDER BY nation LIMIT 1', $nid);

    // helper closures for preconditions.
    $openStrategic = function(object $db) use ($nid) {
        $db->update('nation', ['strategic_cmd_limit'=>0, 'surlimit'=>0], 'nation=%i', $nid);
    };
    $setDiplomacy = function(object $db, int $a, int $b, int $state, int $term) {
        // both directions (me=a/you=b and me=b/you=a). devsam stores both rows.
        $db->update('diplomacy', ['state'=>$state, 'term'=>$term], 'me=%i AND you=%i', $a, $b);
        $db->update('diplomacy', ['state'=>$state, 'term'=>$term], 'me=%i AND you=%i', $b, $a);
    };

    switch ($raw) {
        case 'che_백성동원': {
            // own occupied supplied city as dest (the actor's own city works).
            $cid = (int)$db->queryFirstField('SELECT city FROM general WHERE no=%i', $chief);
            $pre = function(object $db) use ($openStrategic) { $openStrategic($db); };
            return [$chief, ['destCityID' => $cid], $pre, null];
        }
        case 'che_수몰': {
            // enemy BattleGroundCity: an enemy supplied non-neutral city with diplomacy state 0.
            $destCity = (int)$db->queryFirstField('SELECT city FROM city WHERE nation=%i AND supply=1 ORDER BY city LIMIT 1', $enemy);
            if (!$destCity) return null;
            $pre = function(object $db) use ($openStrategic, $setDiplomacy, $nid, $enemy) {
                $openStrategic($db); $setDiplomacy($db, $nid, $enemy, 0, 0);
            };
            return [$chief, ['destCityID' => $destCity], $pre, null];
        }
        case 'che_허보': {
            // enemy supplied city that HAS enemy generals (so rng->choice fires).
            $destCity = (int)$db->queryFirstField(
                'SELECT c.city FROM city c WHERE c.nation=%i AND c.supply=1
                   AND EXISTS(SELECT 1 FROM general g WHERE g.nation=%i AND g.city=c.city)
                 ORDER BY c.city LIMIT 1', $enemy, $enemy);
            if (!$destCity) return null;
            $pre = function(object $db) use ($openStrategic, $setDiplomacy, $nid, $enemy) {
                $openStrategic($db); $setDiplomacy($db, $nid, $enemy, 1, 0);
            };
            return [$chief, ['destCityID' => $destCity], $pre, null];
        }
        case 'che_이호경식': {
            $pre = function(object $db) use ($openStrategic, $setDiplomacy, $nid, $enemy) {
                $openStrategic($db); $setDiplomacy($db, $nid, $enemy, 1, 0);
            };
            return [$chief, ['destNationID' => $enemy], $pre, null];
        }
        case 'che_급습': {
            // AllowDiplomacyWithTerm(1,12): declared (state 1) with term>=12.
            $pre = function(object $db) use ($openStrategic, $setDiplomacy, $nid, $enemy) {
                $openStrategic($db); $setDiplomacy($db, $nid, $enemy, 1, 12);
            };
            return [$chief, ['destNationID' => $enemy], $pre, null];
        }
        case 'che_피장파장': {
            // commandType: another 전략 command != self that is currently available.
            $pre = function(object $db) use ($openStrategic, $setDiplomacy, $nid, $enemy) {
                $openStrategic($db); $setDiplomacy($db, $nid, $enemy, 1, 0);
            };
            return [$chief, ['destNationID' => $enemy, 'commandType' => 'che_백성동원'], $pre, null];
        }
        case 'che_필사즉생': {
            // AllowDiplomacyStatus([0]) = at active war (state 0).
            $pre = function(object $db) use ($openStrategic, $setDiplomacy, $nid, $enemy) {
                $openStrategic($db); $setDiplomacy($db, $nid, $enemy, 0, 0);
            };
            return [$chief, [], $pre, null];   // zero-arg ($this->arg = [])
        }
        case 'che_의병모집': {
            // NotOpeningPart(relYear>=3): bump year past the opening window.
            $pre = function(object $db) use ($openStrategic) { $openStrategic($db); };
            return [$chief, null, $pre, $startYear + 3];   // zero-arg ($this->arg = null)
        }
        case 'che_초토화': {
            // own NON-capital supplied city as dest; surlimit==0; peacetime (state != 0).
            $cap = (int)$db->queryFirstField('SELECT capital FROM nation WHERE nation=%i', $nid);
            $destCity = (int)$db->queryFirstField(
                'SELECT city FROM city WHERE nation=%i AND supply=1 AND city<>%i ORDER BY city LIMIT 1', $nid, $cap);
            if (!$destCity) return null;
            $pre = function(object $db) use ($openStrategic, $setDiplomacy, $nid, $enemy, $chief) {
                $openStrategic($db);
                $setDiplomacy($db, $nid, $enemy, 2, 0);   // peacetime
                // 초토화 deliberately does addExperience(-exp*0.1) BEFORE the +5*(pre+1)
                // gain. At a high explevel ANY in-band exp drops a level after -10% (band
                // width < 10%), so the actor is floored to explevel 0 with a low exp where
                // -10% + the gain stays inside [0,100) — keeps the no-level-cross gate true
                // (the deferred checkStatChange 레벨다운 path is NOT this golden's target).
                // The seized amount / city deltas / log are unaffected by exp (100% real PHP).
                $db->update('general', ['experience'=>40,'explevel'=>getExpLevel(40)], 'no=%i', $chief);
            };
            return [$chief, ['destCityID' => $destCity], $pre, null];
        }
        case 'che_몰수': {
            // NotOpeningPart + FriendlyDestGeneral with gold to seize.
            $target = (int)$db->queryFirstField(
                'SELECT no FROM general WHERE nation=%i AND no<>%i ORDER BY no LIMIT 1', $nid, $chief);
            if (!$target) return null;
            $pre = function(object $db) use ($openStrategic, $target) {
                $openStrategic($db);
                $db->update('general', ['gold'=>$db->sqleval('GREATEST(gold, 1000)')], 'no=%i', $target);
            };
            return [$chief, ['isGold' => true, 'amount' => 500, 'destGeneralID' => $target], $pre, $startYear + 3];
        }
        case 'che_부대탈퇴지시': {
            // friendly troop MEMBER: pick two friendly generals; make one the leader and
            // the other its member (troop = leaderGid). Detach path is 100% real PHP.
            $leader = (int)$db->queryFirstField(
                'SELECT no FROM general WHERE nation=%i AND no<>%i ORDER BY no LIMIT 1', $nid, $chief);
            $member = (int)$db->queryFirstField(
                'SELECT no FROM general WHERE nation=%i AND no<>%i AND no<>%i ORDER BY no LIMIT 1', $nid, $chief, $leader);
            if (!$leader || !$member) return null;
            $pre = function(object $db) use ($leader, $member) {
                // a real troop: leader heads its own troop, member belongs to leader's troop.
                $db->insert('troop', ['troop_leader'=>$leader, 'nation'=>(int)$db->queryFirstField('SELECT nation FROM general WHERE no=%i',$leader), 'name'=>'골든부대']);
                $db->update('general', ['troop'=>$leader], 'no=%i', $leader);
                $db->update('general', ['troop'=>$leader], 'no=%i', $member);
            };
            return [$chief, ['destGeneralID' => $member], $pre, null];
        }
        case 'che_물자원조': {
            // DifferentDestNation + surlimit==0 on BOTH nations; gold/rice on the actor nation.
            $pre = function(object $db) use ($nid, $enemy) {
                $db->update('nation', ['surlimit'=>0, 'gold'=>$db->sqleval('GREATEST(gold, 50000)'), 'rice'=>$db->sqleval('GREATEST(rice, 50000)')], 'nation=%i', $nid);
                $db->update('nation', ['surlimit'=>0], 'nation=%i', $enemy);
            };
            return [$chief, ['destNationID' => $enemy, 'amountList' => [1000, 1000]], $pre, null];
        }
        case 'event_극병연구': {
            // BeChief + OccupiedCity national-research command (zero-arg). Reachability:
            //   ReqNationGold(basegold+100000) → nation.gold >= 100000,
            //   ReqNationRice(baserice+100000) → nation.rice >= 2000+100000 = 102000,
            //   ReqNationAuxValue(can_극병사용,0,"<",1) → aux[can_극병사용] unset/<1.
            // The actor is the nation-1 module-free chief (gid152 하진). preFn bumps the
            // nation treasury past the threshold and clears the aux flag (static-input gate
            // positioning ONLY; the action's gold/rice/aux/exp/ded/log deltas stay 100% real
            // PHP). The seed gold/rice values are large round numbers so the -100000 delta is
            // unambiguous in the fixture.
            $pre = function(object $db) use ($nid) {
                $auxRow = $db->queryFirstField('SELECT aux FROM nation WHERE nation=%i', $nid);
                $aux = is_string($auxRow) ? ($auxRow === '' ? [] : Json::decode($auxRow)) : [];
                if (!is_array($aux)) { $aux = []; }
                unset($aux[\sammo\Enums\NationAuxKey::can_극병사용->value]);   // ensure the flag is UNSET (<1)
                $db->update('nation', [
                    'gold' => $db->sqleval('GREATEST(gold, %i)', 1000000),    // >= basegold+100000
                    'rice' => $db->sqleval('GREATEST(rice, %i)', 1000000),    // >= baserice+100000
                    'aux'  => Json::encode($aux),
                ], 'nation=%i', $nid);
            };
            return [$chief, null, $pre, null];   // zero-arg ($this->arg = null)
        }
        case 'event_대검병연구': {
            // BeChief + OccupiedCity national-research command (zero-arg). Reachability:
            //   ReqNationGold(basegold+50000)  → nation.gold >= 50000,
            //   ReqNationRice(baserice+50000)  → nation.rice >= 2000+50000 = 52000,
            //   ReqNationAuxValue(can_대검병사용,0,"<",1) → aux[can_대검병사용] unset/<1.
            // The actor is the nation-1 module-free chief (gid152 ⓝ하진). preFn bumps the
            // nation treasury past the threshold and clears the aux flag (static-input gate
            // positioning ONLY; the action's gold/rice/aux/exp/ded/log deltas stay 100% real
            // PHP). The seed gold/rice values are large round numbers so the -50000 delta is
            // unambiguous in the fixture.
            $pre = function(object $db) use ($nid) {
                $auxRow = $db->queryFirstField('SELECT aux FROM nation WHERE nation=%i', $nid);
                $aux = is_string($auxRow) ? ($auxRow === '' ? [] : Json::decode($auxRow)) : [];
                if (!is_array($aux)) { $aux = []; }
                unset($aux[\sammo\Enums\NationAuxKey::can_대검병사용->value]);   // ensure the flag is UNSET (<1)
                $db->update('nation', [
                    'gold' => $db->sqleval('GREATEST(gold, %i)', 1000000),    // >= basegold+50000
                    'rice' => $db->sqleval('GREATEST(rice, %i)', 1000000),    // >= baserice+50000
                    'aux'  => Json::encode($aux),
                ], 'nation=%i', $nid);
            };
            return [$chief, null, $pre, null];   // zero-arg ($this->arg = null)
        }
        case 'event_화시병연구': {
            // BeChief + OccupiedCity national-research command (zero-arg, argTest()=true).
            // hwe/sammo/Command/Nation/event_화시병연구.php — byte-identical run() to event_대검병연구
            // (auxType=can_화시병사용, actionName='화시병 연구', getPreReqTurn()=11, getCost()=[50000,50000]).
            // Reachability:
            //   ReqNationGold(basegold+50000)  → nation.gold >= 0+50000 = 50000,
            //   ReqNationRice(baserice+50000)  → nation.rice >= 2000+50000 = 52000,
            //   ReqNationAuxValue(can_화시병사용,0,"<",1) → aux[can_화시병사용] unset/<1.
            // Actor = the nation-1 module-free chief (gid152 ⓝ하진, scenario npc=2/owner=0 default
            // → increaseInheritancePoint(active_action,1) is a faithful NO-OP: captured as null,
            // NOT a fabricated +1). preFn bumps the treasury past threshold + clears the aux flag
            // (static-input gate positioning ONLY; the action's gold/rice/aux/exp/ded/log deltas
            // stay 100% real PHP). Large round seed gold/rice so the -50000 delta is unambiguous.
            $pre = function(object $db) use ($nid) {
                $auxRow = $db->queryFirstField('SELECT aux FROM nation WHERE nation=%i', $nid);
                $aux = is_string($auxRow) ? ($auxRow === '' ? [] : Json::decode($auxRow)) : [];
                if (!is_array($aux)) { $aux = []; }
                unset($aux[\sammo\Enums\NationAuxKey::can_화시병사용->value]);   // ensure the flag is UNSET (<1)
                $db->update('nation', [
                    'gold' => $db->sqleval('GREATEST(gold, %i)', 1000000),    // >= basegold+50000
                    'rice' => $db->sqleval('GREATEST(rice, %i)', 1000000),    // >= baserice+50000
                    'aux'  => Json::encode($aux),
                ], 'nation=%i', $nid);
            };
            return [$chief, null, $pre, null];   // zero-arg ($this->arg = null)
        }
        case 'event_무희연구': {
            // BeChief + OccupiedCity national-research command (zero-arg). Reachability:
            //   ReqNationGold(basegold+100000) → nation.gold >= 100000,
            //   ReqNationRice(baserice+100000) → nation.rice >= 2000+100000 = 102000,
            //   ReqNationAuxValue(can_무희사용,0,"<",1) → aux[can_무희사용] unset/<1.
            // The actor is the nation-1 module-free chief (gid152 ⓝ하진). preFn bumps the
            // nation treasury past the threshold and clears the aux flag (static-input gate
            // positioning ONLY; the action's gold/rice/aux/exp/ded/log deltas stay 100% real
            // PHP). The seed gold/rice values are large round numbers so the -100000 delta is
            // unambiguous in the fixture. NOTE: 하진 is npc=2/owner=0 (scenario default), so
            // increaseInheritancePoint(active_action,1) is a faithful NO-OP (the impl
            // short-circuits on npc>=2 AND owner falsy) — captured as null, NOT fabricated +1.
            $pre = function(object $db) use ($nid) {
                $auxRow = $db->queryFirstField('SELECT aux FROM nation WHERE nation=%i', $nid);
                $aux = is_string($auxRow) ? ($auxRow === '' ? [] : Json::decode($auxRow)) : [];
                if (!is_array($aux)) { $aux = []; }
                unset($aux[\sammo\Enums\NationAuxKey::can_무희사용->value]);   // ensure the flag is UNSET (<1)
                $db->update('nation', [
                    'gold' => $db->sqleval('GREATEST(gold, %i)', 1000000),    // >= basegold+100000
                    'rice' => $db->sqleval('GREATEST(rice, %i)', 1000000),    // >= baserice+100000
                    'aux'  => Json::encode($aux),
                ], 'nation=%i', $nid);
            };
            return [$chief, null, $pre, null];   // zero-arg ($this->arg = null)
        }
        case 'event_상병연구': {
            // BeChief + OccupiedCity national-research command (zero-arg, argTest()=true).
            // hwe/sammo/Command/Nation/event_상병연구.php. Reachability:
            //   ReqNationGold(basegold+getCost()[0]=0+100000) → nation.gold >= 100000,
            //   ReqNationRice(baserice+getCost()[1]=2000+100000) → nation.rice >= 102000,
            //   ReqNationAuxValue(can_상병사용,0,"<",1) → aux[can_상병사용] unset/<1.
            // The actor is the nation-1 module-free chief (gid152 ⓝ하진, scenario npc=2/owner=0
            // default → increaseInheritancePoint(active_action,1) is a faithful NO-OP: the impl
            // short-circuits on npc>=2 AND owner falsy, captured as null, NOT a fabricated +1).
            // preFn bumps the treasury past the threshold + clears the aux flag (static-input
            // gate positioning ONLY; the action's gold/rice/aux/exp/ded/log deltas stay 100%
            // real PHP). Large round seed gold/rice so the -100000 delta is unambiguous.
            $pre = function(object $db) use ($nid) {
                $auxRow = $db->queryFirstField('SELECT aux FROM nation WHERE nation=%i', $nid);
                $aux = is_string($auxRow) ? ($auxRow === '' ? [] : Json::decode($auxRow)) : [];
                if (!is_array($aux)) { $aux = []; }
                unset($aux[\sammo\Enums\NationAuxKey::can_상병사용->value]);   // ensure the flag is UNSET (<1)
                $db->update('nation', [
                    'gold' => $db->sqleval('GREATEST(gold, %i)', 1000000),    // >= basegold+100000
                    'rice' => $db->sqleval('GREATEST(rice, %i)', 1000000),    // >= baserice+100000
                    'aux'  => Json::encode($aux),
                ], 'nation=%i', $nid);
            };
            return [$chief, null, $pre, null];   // zero-arg ($this->arg = null)
        }
        case 'event_화륜차연구': {
            // BeChief + OccupiedCity national-research command (zero-arg, argTest()=true).
            // hwe/sammo/Command/Nation/event_화륜차연구.php. Reachability:
            //   ReqNationGold(basegold+getCost()[0]=0+100000) → nation.gold >= 100000,
            //   ReqNationRice(baserice+getCost()[1]=2000+100000) → nation.rice >= 102000,
            //   ReqNationAuxValue(can_화륜차사용,0,"<",1) → aux[can_화륜차사용] unset/<1.
            // Actor = the nation-1 module-free chief (gid152 ⓝ하진, scenario npc/owner default
            // → increaseInheritancePoint(active_action,1) is a faithful NO-OP: captured as null,
            // NOT fabricated +1). preFn bumps the treasury past threshold + clears the aux flag
            // (static-input gate positioning ONLY; the action's gold/rice/aux/exp/ded/log deltas
            // stay 100% real PHP). Large round seed gold/rice so the -100000 delta is unambiguous.
            $pre = function(object $db) use ($nid) {
                $auxRow = $db->queryFirstField('SELECT aux FROM nation WHERE nation=%i', $nid);
                $aux = is_string($auxRow) ? ($auxRow === '' ? [] : Json::decode($auxRow)) : [];
                if (!is_array($aux)) { $aux = []; }
                unset($aux[\sammo\Enums\NationAuxKey::can_화륜차사용->value]);   // ensure the flag is UNSET (<1)
                $db->update('nation', [
                    'gold' => $db->sqleval('GREATEST(gold, %i)', 1000000),    // >= basegold+100000
                    'rice' => $db->sqleval('GREATEST(rice, %i)', 1000000),    // >= baserice+100000
                    'aux'  => Json::encode($aux),
                ], 'nation=%i', $nid);
            };
            return [$chief, null, $pre, null];   // zero-arg ($this->arg = null)
        }
    }
    return null;
}

/** Capture ONE C3 command case (single deterministic-or-seeded outcome). */
function captureCase(object $db, array $meta, string $raw, int $gid, ?array $arg, ?callable $preFn,
                     int $year, int $month, string $hiddenSeed): ?array {
    $env = setGameMonth($db, $month);
    // also reflect the (possibly bumped) year into game_env so env + seed agree.
    $gs = KVStorage::getStorage($db, 'game_env'); $gs->year = $year; $gs->resetCache();
    $env = KVStorage::getStorage($db, 'game_env')->getAll();

    // defensive freshness: clear ANY lingering action rows on every general before this
    // capture (so a prior crashed/aborted capture cannot leak log lines into this one).
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
    hardAssert((int)$general->getVar('officer_level') >= 5, "{$raw}: officer<5");

    $cmd = buildNationCommandClass($raw, $general, $env, LastTurn::fromRaw(null), $arg);
    hardAssert($cmd->getRawClassName() === $raw, "factory returned {$cmd->getRawClassName()} for {$raw}");
    if (!$cmd->hasFullConditionMet()) {
        $fail = method_exists($cmd, 'getFailString') ? (string)@$cmd->getFailString() : '';
        planMiss($raw, "full condition not met (" . str_replace("\n", " ", $fail) . ")");
        return null;
    }

    $reqGold = $cmd->getCost()[0] ?? null;

    // dest recipients (per command type) — snapshot their pre-state + capture their lines.
    $destGids = collectDestGids($db, $raw, $arg, $gid, (int)$general->getVar('nation'));

    $before = snapshotGeneralCity($general, $city);
    $itemCountBefore = count($general->getItems() ?? []);
    $globalIdBefore = maxGlobalActionId($db);

    // event_* research command (대검병/극병 연구) parity surface = nation gold/rice/aux +
    // general/national history lines + inheritance active_action. (These commands write NO
    // city/general-stat delta beyond exp/ded — the parity gate is the Korean log byte-strings
    // + post-state deltas, with zero RNG.)
    $capturesNation = str_starts_with($raw, 'event_');
    $nationBefore = $inheritBefore = null;
    $genHistIdBefore = $natHistIdBefore = 0;
    if ($capturesNation) {
        $nid = (int)$general->getVar('nation');
        $nationBefore = snapshotNationAux($db, $nid);
        $inheritBefore = snapshotInheritanceActiveAction($db, $general);
        $genHistIdBefore = maxGeneralHistoryId($db, $gid);
        $natHistIdBefore = maxWorldHistoryId($db);
    }

    $seedString = Util::simpleSerialize($hiddenSeed, 'nationCommand', $year, $month, $gid, $cmd->getRawClassName());
    $rng = new RandUtilDrawRecorder(new LiteHashDRBG($seedString));

    $ok = $cmd->run($rng);
    if ($ok !== true) {
        planMiss($raw, "run() returned false (no reachable outcome on this install)");
        return null;
    }

    $actingLines = logActionRows($db, $gid);
    $expectLines = (int)$meta['logLines'];
    hardAssert(count($actingLines) === $expectLines,
        "{$raw}: expected {$expectLines} acting line(s), got " . count($actingLines) . " :: " . implode(' | ', $actingLines));
    hardAssert(count($general->getItems() ?? []) === $itemCountBefore, "{$raw}: equipment slot count changed");

    $broadcastLines = globalActionRowsSince($db, $globalIdBefore);

    $generalAfter = General::createObjFromDB($gid);
    $cityAfter = $cityId ? $db->queryFirstRow('SELECT * FROM city WHERE city=%i', $cityId) : null;
    $after = snapshotGeneralCity($generalAfter, $cityAfter);
    hardAssert($generalAfter->getVar('explevel') === $explevelBefore, "{$raw}: exp level crossed ({$explevelBefore}->" . $generalAfter->getVar('explevel') . ")");
    hardAssert($generalAfter->getVar('dedlevel') === $dedlevelBefore, "{$raw}: ded level crossed");

    $case = [
        'case'=>$raw, 'command'=>$raw, 'ctor'=>$meta['ctor'], 'scope'=>'nationCommand',
        'generalId'=>$gid, 'cityId'=>$cityId,
        'env'=>['year'=>$year,'startYear'=>(int)$env['startyear'],'month'=>$month,'develCost'=>(int)$env['develcost']],
        'arg'=>$arg, 'seedString'=>$seedString, 'reqGold'=>$reqGold,
        'before'=>$before, 'after'=>$after,
        'logLines'=>$actingLines, 'broadcastLines'=>$broadcastLines,
        'draws'=>['draw_count'=>$rng->getDrawCount(), 'draw_stream'=>$rng->getDrawStream()],
    ];

    // event_극병연구: attach the nation gold/rice/aux + inheritance active_action deltas
    // (the parity-load-bearing surface — this command writes NO city/general-stat deltas
    // beyond exp/ded). HARD-assert the documented deltas so an unfaithful capture aborts.
    if ($capturesNation) {
        $nidA = (int)$generalAfter->getVar('nation');
        $nationAfter = snapshotNationAux($db, $nidA);
        $inheritAfter = snapshotInheritanceActiveAction($db, $generalAfter);
        [$reqGoldC, $reqRiceC] = $cmd->getCost();
        hardAssert($nationAfter['gold'] === $nationBefore['gold'] - (int)$reqGoldC,
            "{$raw}: nation gold delta != -{$reqGoldC} ({$nationBefore['gold']}->{$nationAfter['gold']})");
        hardAssert($nationAfter['rice'] === $nationBefore['rice'] - (int)$reqRiceC,
            "{$raw}: nation rice delta != -{$reqRiceC} ({$nationBefore['rice']}->{$nationAfter['rice']})");
        // aux key = the command's OWN static $auxType (NationAuxKey) — read reflectively so
        // every event-research command (극병/대검병/무희/…) asserts its own flag, not a
        // hardcoded one. run() writes aux[static::$auxType->value] = 1.
        $rpAux = new \ReflectionProperty($cmd, 'auxType');
        if (PHP_VERSION_ID < 80100) { $rpAux->setAccessible(true); }
        $auxKey = $rpAux->getValue($cmd)->value;
        hardAssert((int)($nationAfter['aux'][$auxKey] ?? 0) === 1,
            "{$raw}: aux[{$auxKey}] != 1 after run");
        hardAssert(!isset($nationBefore['aux'][$auxKey]) || (int)$nationBefore['aux'][$auxKey] < 1,
            "{$raw}: aux[{$auxKey}] was already set before run (gate not reachable)");
        // actor exp/ded each +5*(getPreReqTurn()+1).
        $expGain = 5 * ($cmd->getPreReqTurn() + 1);
        hardAssert((int)$after['general']['experience'] === (int)$before['general']['experience'] + $expGain,
            "{$raw}: exp delta != +{$expGain}");
        hardAssert((int)$after['general']['dedication'] === (int)$before['general']['dedication'] + $expGain,
            "{$raw}: ded delta != +{$expGain}");
        // general-history (general_record history) + national-history (world_history) — each
        // ONE byte-string, the pushGeneralHistoryLog / pushNationalHistoryLog parity surface.
        $generalHistoryLines  = generalHistoryRowsSince($db, $gid, $genHistIdBefore);
        $nationalHistoryLines = nationalHistoryRowsSince($db, $nidA, $natHistIdBefore);
        hardAssert(count($generalHistoryLines) === 1,
            "{$raw}: expected 1 general-history line, got " . count($generalHistoryLines) . " :: " . implode(' | ', $generalHistoryLines));
        hardAssert(count($nationalHistoryLines) === 1,
            "{$raw}: expected 1 national-history line, got " . count($nationalHistoryLines) . " :: " . implode(' | ', $nationalHistoryLines));
        // inheritance active_action += 1*pointCoeff (iff owner!=0 and npc<2; null/no-op otherwise —
        // recorded AS-IS, never fabricated). active_action's pointCoeff is read from the live
        // point-type registry (currently 3), so the stored delta is +pointCoeff, NOT +1.
        $coeff = InheritancePointManager::getInstance()
            ->getInheritancePointType(\sammo\Enums\InheritanceKey::active_action)->pointCoeff;
        if ($inheritBefore['owner'] !== 0 && (int)$generalAfter->getVar('npc') < 2) {
            $before0 = $inheritBefore['active_action'] ?? 0;
            hardAssert(($inheritAfter['active_action'] ?? 0) == $before0 + $coeff,
                "{$raw}: inheritance active_action delta != +{$coeff} ({$before0}->" . ($inheritAfter['active_action'] ?? 'null') . ")");
        }
        $case['nationBefore'] = $nationBefore;
        $case['nationAfter']  = $nationAfter;
        $case['generalHistoryLines']  = $generalHistoryLines;
        $case['nationalHistoryLines'] = $nationalHistoryLines;
        $case['inheritanceBefore'] = $inheritBefore;
        $case['inheritanceAfter']  = $inheritAfter;
        $case['inheritancePointCoeff'] = $coeff;
    }

    // cross-target dest generals (몰수/부대탈퇴지시/필사즉생) — their action lines + post-state.
    if (!empty($destGids)) {
        $destOut = [];
        foreach ($destGids as $did) {
            $dG = General::createObjFromDB($did);
            $dCid = (int)$dG->getVar('city');
            $dC = $dCid ? $db->queryFirstRow('SELECT * FROM city WHERE city=%i', $dCid) : null;
            $lines = actionRowsForGeneral($db, $did);
            $destOut[] = ['generalId'=>$did, 'after'=>snapshotGeneralCity($dG, $dC), 'logLines'=>$lines];
        }
        $case['destGenerals'] = $destOut;
    }

    return $case;
}

/** Recipients whose cross-general action log this command writes (so we capture them). */
function collectDestGids(object $db, string $raw, ?array $arg, int $actorGid, int $nationID): array {
    switch ($raw) {
        case 'che_몰수':
        case 'che_부대탈퇴지시':
            return isset($arg['destGeneralID']) ? [(int)$arg['destGeneralID']] : [];
        case 'che_필사즉생':
            // every OTHER general in the actor's nation receives a PLAIN line + train/atmos set.
            return array_map('intval', $db->queryFirstColumn('SELECT no FROM general WHERE nation=%i AND no<>%i ORDER BY no', $nationID, $actorGid));
        case 'che_허보':
            // enemy generals in the targeted enemy city are moved (captured via their post-state).
            if (!isset($arg['destCityID'])) return [];
            $enemyNation = (int)$db->queryFirstField('SELECT nation FROM city WHERE city=%i', (int)$arg['destCityID']);
            return array_map('intval', $db->queryFirstColumn('SELECT no FROM general WHERE nation=%i AND city=%i ORDER BY no', $enemyNation, (int)$arg['destCityID']));
        default:
            return [];
    }
}

// NOTE: dest-nation national-history (수몰/이호경식/급습/피장파장's pushNationalHistoryLog to the
// DEST nation) lands in the `world_history` table (func_history.php), NOT in general_record —
// it is the national chronicle, not a per-action log, so (matching capture_command_args.php)
// it is intentionally NOT captured here. The parity-load-bearing surface is: the acting
// general's action lines, the cross-target dest-general action lines, the broadcast
// globalAction lines (general_id=0 history), and the RNG draw stream.

// ── the 12 C3 commands ────────────────────────────────────────────────────────
$targets = [
    'che_백성동원', 'che_수몰', 'che_허보', 'che_의병모집', 'che_이호경식',
    'che_급습', 'che_피장파장', 'che_필사즉생', 'che_초토화',
    'che_몰수', 'che_부대탈퇴지시', 'che_물자원조',
    'event_극병연구', 'event_대검병연구', 'event_화륜차연구', 'event_무희연구', 'event_상병연구',
    'event_화시병연구',
];
if ($onlyCmd !== null) $targets = [$onlyCmd];

$baseEnv = $gameStor->getAll();
$byCommand = [];
foreach ($targets as $raw) {
    if (!isset($cmdMeta[$raw])) { planMiss($raw, "not in manifest_c3"); continue; }
    $meta = $cmdMeta[$raw];

    // full-world snapshot BEFORE the plan mutates anything → restore after.
    $world = snapshotWorld($db);

    $plan = planFor($db, $raw, $baseEnv);
    if ($plan === null) { planMiss($raw, "no actor/plan on this install"); restoreWorld($db, $world); resetClock($db, $baseEnv); continue; }
    [$gid, $arg, $preFn, $yearOverride] = $plan;
    $year = $yearOverride ?? (int)$baseEnv['year'];

    $case = captureCase($db, $meta, $raw, $gid, $arg, $preFn, $year, (int)$baseEnv['month'], $hiddenSeed);

    // restore the whole world so the next capture starts pristine.
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
    file_put_contents($outPath, Json::encode($out, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES | JSON_PRETTY_PRINT));
    fwrite(STDERR, "wrote " . basename($outPath) . " (" . count($cases) . " cases, " . $cases[0]['draws']['draw_count'] . " draws)\n");
}
if (!$byCommand) fwrite(STDERR, "no C3 command captured\n");
