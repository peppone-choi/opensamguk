<?php
/**
 * capture_event_산저병연구.php — single-command C3-event golden capture
 * (devsam-core PHP, grand truth). ONE-SHOT, MANUAL HOST — NEVER CI.
 *
 * Standalone sibling of capture_command_c3.php / capture_event_wonyungnobyeong.php (the shared
 * harness is co-edited for the OTHER event-research commands — this stays a DISJOINT file to
 * avoid co-widening). Mirrors the C3 event path EXACTLY for the ONE target
 * hwe/sammo/Command/Nation/event_산저병연구.php (run() is byte-identical to event_대검병연구/
 * event_화시병연구; only $actionName='산저병 연구' + $auxType=can_산저병사용 differ):
 *   - NATION-scope (4-arg buildNationCommandClass, 'nationCommand' seed),
 *   - module-free nation-1 chief actor (gid152 ⓝ하진) via applyPrecondition (mid-band exp/ded,
 *     so the +60 exp/ded gain keeps explevel/dedlevel unchanged),
 *   - full-world snapshot/restore so the capture is independent + idempotent,
 *   - DRAW-NEUTRAL RandUtilDrawRecorder → draw_count == 0 (run() never draws),
 *   - the SAME real path TurnExecutionHelper does ($cmd->run($rng)).
 *
 * event_산저병연구 PARITY SURFACE (per the manifest_c3 event[] entries / the event_대검병연구 sibling):
 *   - actor action line (general_record action) + general-history line (general_record history),
 *   - national-history line (world_history) "<Y>{name}</>{josaYi} <M>산저병 연구</> 완료",
 *   - nation gold -= getCost()[0]=50000, rice -= getCost()[1]=50000, aux[can_산저병사용]=1
 *     (APPENDED last → insertion order),
 *   - actor exp/ded each += 5*(getPreReqTurn()+1) = 5*(11+1) = 60,
 *   - inheritance active_action += 1*pointCoeff(3) = 3 in storage inheritance_{owner}.
 *
 * REACHABILITY (documented static-input preconditions; the computed deltas stay 100% real PHP):
 *   - ReqNationGold(basegold(0)+50000)=50000 / ReqNationRice(baserice(2000)+50000)=52000:
 *     scenario_1010 후한 starts gold/rice=10000 (short) → bumped to a large round number (1000000)
 *     so the -50000 delta is unambiguous in the fixture.
 *   - ReqNationAuxValue(can_산저병사용,0,"<",1): the flag is UNSET on this install → reachable; the
 *     precondition explicitly clears it so the constraint passes and the after-state delta is clean.
 *   - increaseInheritancePoint(active_action,1) writes inheritance_{owner} ONLY when owner!=0 && npc<2.
 *     scenario_1010 has ZERO player-owned generals (every general is npc>=2/owner=0), so the increment
 *     is otherwise an UNREACHABLE no-op on this install (identical to che_초토화's captured no-op). We
 *     position the actor as a player-owned chief (owner=777, npc=0) — a reachable mid-game state in the
 *     real game (players own generals) — so the inheritance KV write FIRES with a 100% real PHP value.
 *     The owner/npc positioning is static input ONLY; the stored value comes verbatim from
 *     increaseInheritancePointRaw (never fabricated).
 *
 * A HARD-ASSERT failure (exit 2) means the golden is UNFAITHFUL — nothing is emitted.
 *
 * Invocation (inside the php capture container, repo mounted at /work):
 *   php tools/php-golden/capture_event_sanjeobyeong.php [--out-dir=logic/src/test/resources/golden/p2]
 */

namespace sammo;

require __DIR__ . '/_boot.php';
require __DIR__ . '/RandUtilDrawRecorder.php';

use sammo\Enums\InheritanceKey;
use sammo\Enums\NationAuxKey;

$opts   = getopt('', ['out-dir:']);
$outDir = $opts['out-dir'] ?? (__DIR__ . '/../../logic/src/test/resources/golden/p2');
if (!is_dir($outDir)) { mkdir($outDir, 0775, true); }

const RAW = 'event_산저병연구';
const ACTOR_OWNER = 777;   // synthetic player-owner so the inheritance increment path is reachable.

$db = DB::db();
$hiddenSeed = UniqueConst::$hiddenSeed;
$gameStor = KVStorage::getStorage($db, 'game_env');
[$year, $startYear, $month, $develCost] = $gameStor->getValuesAsArray(['year','startyear','month','develcost']);

// Prefer the shared manifest_c3 entry for this command's meta (ctor/logLines); fall back to the
// PHP-source-verified defaults if it isn't registered yet (the manifest_c3 event[] array is being
// co-edited for the OTHER event-research commands — this single-command driver stays decoupled).
$manifest = Json::decode(file_get_contents(__DIR__ . '/manifest_c3.json'));
$cmdMeta = [];
foreach ($manifest['commands'] as $group => $list) {
    foreach ($list as $entry) { $cmdMeta[$entry['rawClassName']] = $entry; }
}
// event_산저병연구.php: NationCommand (ctor 'nation'), pushGeneralActionLog writes exactly ONE
// acting action line (logLines=1) — verified from the source (run() = 3 logs total, but only the
// pushGeneralActionLog row lands in general_record log_type='action'; the other two are general/
// national history). Used only when the shared manifest entry is absent.
$meta = $cmdMeta[RAW] ?? ['rawClassName'=>RAW, 'ctor'=>'nation', 'logLines'=>1];

function hardAssert(bool $cond, string $msg): void {
    if (!$cond) { fwrite(STDERR, "EVENT HARD-ASSERT FAILED: {$msg}\n"); exit(2); }
}
function planMiss(string $raw, string $why): void { fwrite(STDERR, "PLAN-MISS {$raw}: {$why}\n"); }
function isNoneField(?string $v): bool { return $v === null || $v === '' || $v === 'None'; }

function assertModuleFree(General $g): void {
    hardAssert(isNoneField($g->getVar('special')),  'general has special module');
    hardAssert(isNoneField($g->getVar('special2')), 'general has special2 module');
    hardAssert(isNoneField($g->getVar('personal')), 'general has personal module');
    foreach (($g->getItems() ?? []) as $slot => $item) {
        hardAssert($item instanceof \sammo\ActionItem\None, "equipment slot {$slot} not a None item");
    }
}

function expBandLo(int $L): int { return $L < 10 ? $L * 100 : 10 * $L * $L; }
function expBandHi(int $L): int { return $L < 10 ? ($L + 1) * 100 : 10 * ($L + 1) * ($L + 1); }
function dedBandLo(int $L): int { return $L <= 0 ? 0 : (10 * ($L - 1)) * (10 * ($L - 1)); }
function dedBandHi(int $L): int { return (10 * $L) * (10 * $L); }

/** Module-free + no-level-cross precondition (mid-band exp/ded; computed golden stays real PHP). */
function applyPrecondition(object $db, int $gid): void {
    $row = $db->queryFirstRow('SELECT experience, dedication FROM general WHERE no=%i', $gid);
    $el = getExpLevel((int)$row['experience']); $dl = getDedLevel((int)$row['dedication']);
    $emid = (int)(expBandLo($el) + (expBandHi($el)-expBandLo($el))*0.4);
    $dmid = (int)(dedBandLo($dl) + (dedBandHi($dl)-dedBandLo($dl))*0.4);
    $db->update('general', ['personal'=>'None','experience'=>$emid,'dedication'=>$dmid,
        'explevel'=>getExpLevel($emid),'dedlevel'=>getDedLevel($dmid)], 'no=%i', $gid);
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

/** Snapshot a nation's treasury + aux (decode the JSON column → live key→value map). */
function snapshotNationAux(object $db, int $nationID): array {
    $row = $db->queryFirstRow('SELECT gold, rice, aux FROM nation WHERE nation=%i', $nationID);
    $aux = $row['aux'];
    if (is_string($aux)) { $aux = $aux === '' ? [] : Json::decode($aux); }
    if (!is_array($aux)) { $aux = []; }
    return ['nation' => $nationID, 'gold' => (int)$row['gold'], 'rice' => (int)$row['rice'], 'aux' => $aux];
}

/** active_action inheritance point for a general's owner (the value the +1 bump writes). */
function snapshotInheritanceActiveAction(object $db, General $g): array {
    $owner = (int)$g->getVar('owner');
    if (!$owner) return ['owner' => 0, 'active_action' => null];
    $stor = KVStorage::getStorage($db, "inheritance_{$owner}");
    $entry = $stor->getValue(InheritanceKey::active_action->value);
    $val = is_array($entry) ? $entry[0] : null;   // [value, aux]
    return ['owner' => $owner, 'active_action' => $val];
}

function logActionRows(object $db, int $gid): array {
    $rows = $db->query('SELECT text FROM general_record WHERE general_id=%i AND log_type=%s ORDER BY id', $gid, 'action');
    $out = []; foreach ($rows as $r) $out[] = $r['text']; return $out;
}
function generalHistoryRowsSince(object $db, int $gid, int $afterId): array {
    $rows = $db->query('SELECT text FROM general_record WHERE general_id=%i AND log_type=%s AND id>%i ORDER BY id', $gid, 'history', $afterId);
    $out = []; foreach ($rows as $r) $out[] = $r['text']; return $out;
}
function maxGeneralHistoryId(object $db, int $gid): int {
    return (int)($db->queryFirstField('SELECT COALESCE(MAX(id),0) FROM general_record WHERE general_id=%i AND log_type=%s', $gid, 'history'));
}
function nationalHistoryRowsSince(object $db, int $nationID, int $afterId): array {
    $rows = $db->query('SELECT text FROM world_history WHERE nation_id=%i AND id>%i ORDER BY id', $nationID, $afterId);
    $out = []; foreach ($rows as $r) $out[] = $r['text']; return $out;
}
function maxWorldHistoryId(object $db): int {
    return (int)($db->queryFirstField('SELECT COALESCE(MAX(id),0) FROM world_history'));
}
function globalActionRowsSince(object $db, int $afterId): array {
    $rows = $db->query('SELECT text FROM general_record WHERE general_id=0 AND log_type=%s AND id>%i ORDER BY id', 'history', $afterId);
    $out = []; foreach ($rows as $r) $out[] = $r['text']; return $out;
}
function maxGlobalActionId(object $db): int {
    return (int)($db->queryFirstField('SELECT COALESCE(MAX(id),0) FROM general_record WHERE general_id=0 AND log_type=%s', 'history'));
}

/** Full-world snapshot (rows the capture touches) so it restores byte-for-byte + idempotent. */
function snapshotWorld(object $db): array {
    return [
        'generals'   => $db->query('SELECT * FROM general'),
        'nations'    => $db->query('SELECT * FROM nation'),
        'maxGlobalId'=> maxGlobalActionId($db),
        'maxWorldHistoryId' => maxWorldHistoryId($db),
        // inheritance_777 ledger (the synthetic player-owner the precondition writes).
        'inheritance777' => $db->query('SELECT namespace, `key`, value FROM storage WHERE namespace=%s', 'inheritance_' . ACTOR_OWNER),
    ];
}
function restoreWorld(object $db, array $w): void {
    foreach ($w['generals'] as $r) $db->update('general', $r, 'no=%i', (int)$r['no']);
    foreach ($w['nations'] as $r)  $db->update('nation', $r, 'nation=%i', (int)$r['nation']);
    // wipe THIS-capture action rows + new global-action history + national-history rows.
    $db->delete('general_record', 'log_type=%s AND id>%i', 'history', $w['maxGlobalId']);
    $db->query('DELETE FROM general_record WHERE log_type=%s', 'action');
    $db->delete('world_history', 'id>%i', $w['maxWorldHistoryId']);
    // inheritance_777: drop the synthetic ledger, restore the (empty) base snapshot.
    $db->query('DELETE FROM storage WHERE namespace=%s', 'inheritance_' . ACTOR_OWNER);
    foreach ($w['inheritance777'] as $r) {
        $db->insert('storage', ['namespace'=>$r['namespace'],'key'=>$r['key'],'value'=>$r['value']]);
    }
}

// ── locate the nation-1 module-free chief (the C3 actor of record, gid152 하진) ────────────────
$chief = (int)$db->queryFirstField(
    "SELECT g.no FROM general g JOIN city c ON g.city=c.city
       WHERE c.nation<>0 AND c.supply=1 AND g.nation=c.nation AND g.officer_level>=5
         AND (g.special='None' OR g.special='' OR g.special IS NULL)
         AND (g.special2='None' OR g.special2='' OR g.special2 IS NULL)
       ORDER BY g.no LIMIT 1");
if (!$chief) { planMiss(RAW, "no nation-1 module-free chief on this install"); exit(1); }
$nid = (int)$db->queryFirstField('SELECT nation FROM general WHERE no=%i', $chief);

$world = snapshotWorld($db);

// ── precondition (static input only) ──────────────────────────────────────────────────────────
applyPrecondition($db, $chief);
// nation treasury bump + clear the can_산저병사용 flag.
$auxRow = $db->queryFirstField('SELECT aux FROM nation WHERE nation=%i', $nid);
$aux = is_string($auxRow) ? ($auxRow === '' ? [] : Json::decode($auxRow)) : [];
if (!is_array($aux)) { $aux = []; }
unset($aux[NationAuxKey::can_산저병사용->value]);
$db->update('nation', [
    'gold' => $db->sqleval('GREATEST(gold, %i)', 1000000),   // >= basegold(0)+50000
    'rice' => $db->sqleval('GREATEST(rice, %i)', 1000000),   // >= baserice(2000)+50000
    'aux'  => Json::encode($aux),
], 'nation=%i', $nid);
// position the actor as a player-owned chief so the inheritance increment fires (static input).
$db->update('general', ['owner' => ACTOR_OWNER, 'npc' => 0], 'no=%i', $chief);
$db->query('DELETE FROM storage WHERE namespace=%s', 'inheritance_' . ACTOR_OWNER);
\sammo\refreshNationStaticInfo();

// ── build the command via the SAME factory a real turn uses ─────────────────────────────────────
$general = General::createObjFromDB($chief);
assertModuleFree($general);
$cityId = (int)$general->getVar('city');
$city = $cityId ? $db->queryFirstRow('SELECT * FROM city WHERE city=%i', $cityId) : null;
if ($city !== null) hardAssert($city['trust'] == floor($city['trust']), "city {$cityId} trust not integer");

$explevelBefore = $general->getVar('explevel');
$dedlevelBefore = $general->getVar('dedlevel');
hardAssert((int)$general->getVar('nation') !== 0, RAW . ": neutral actor");
hardAssert((int)$general->getVar('officer_level') >= 5, RAW . ": officer<5");

$env = KVStorage::getStorage($db, 'game_env')->getAll();
$cmd = buildNationCommandClass(RAW, $general, $env, LastTurn::fromRaw(null), null);
hardAssert($cmd->getRawClassName() === RAW, "factory returned {$cmd->getRawClassName()} for " . RAW);
if (!$cmd->hasFullConditionMet()) {
    $fail = method_exists($cmd, 'getFailString') ? (string)@$cmd->getFailString() : '';
    planMiss(RAW, "full condition not met (" . str_replace("\n", " ", $fail) . ")");
    restoreWorld($db, $world); \sammo\refreshNationStaticInfo();
    exit(1);
}

$reqGold = $cmd->getCost()[0] ?? null;
$before = snapshotGeneralCity($general, $city);
$itemCountBefore = count($general->getItems() ?? []);
$globalIdBefore = maxGlobalActionId($db);
$nationBefore = snapshotNationAux($db, $nid);
$inheritBefore = snapshotInheritanceActiveAction($db, $general);
$genHistIdBefore = maxGeneralHistoryId($db, $chief);
$natHistIdBefore = maxWorldHistoryId($db);

$seedString = Util::simpleSerialize($hiddenSeed, 'nationCommand', $year, $month, $chief, $cmd->getRawClassName());
$rng = new RandUtilDrawRecorder(new LiteHashDRBG($seedString));

$ok = $cmd->run($rng);
if ($ok !== true) {
    planMiss(RAW, "run() returned false (no reachable outcome on this install)");
    restoreWorld($db, $world); \sammo\refreshNationStaticInfo();
    exit(1);
}

$actingLines = logActionRows($db, $chief);
$expectLines = (int)$meta['logLines'];
hardAssert(count($actingLines) === $expectLines,
    RAW . ": expected {$expectLines} acting line(s), got " . count($actingLines) . " :: " . implode(' | ', $actingLines));
hardAssert(count($general->getItems() ?? []) === $itemCountBefore, RAW . ": equipment slot count changed");
hardAssert($rng->getDrawCount() === 0, RAW . ": expected draw_count==0 (deterministic), got " . $rng->getDrawCount());

$broadcastLines = globalActionRowsSince($db, $globalIdBefore);

$generalAfter = General::createObjFromDB($chief);
$cityAfter = $cityId ? $db->queryFirstRow('SELECT * FROM city WHERE city=%i', $cityId) : null;
$after = snapshotGeneralCity($generalAfter, $cityAfter);
hardAssert($generalAfter->getVar('explevel') === $explevelBefore, RAW . ": exp level crossed ({$explevelBefore}->" . $generalAfter->getVar('explevel') . ")");
hardAssert($generalAfter->getVar('dedlevel') === $dedlevelBefore, RAW . ": ded level crossed");

// nation + inheritance deltas (the parity-load-bearing surface).
$nationAfter = snapshotNationAux($db, $nid);
$inheritAfter = snapshotInheritanceActiveAction($db, $generalAfter);
[$reqGoldC, $reqRiceC] = $cmd->getCost();
hardAssert($nationAfter['gold'] === $nationBefore['gold'] - (int)$reqGoldC,
    RAW . ": nation gold delta != -{$reqGoldC} ({$nationBefore['gold']}->{$nationAfter['gold']})");
hardAssert($nationAfter['rice'] === $nationBefore['rice'] - (int)$reqRiceC,
    RAW . ": nation rice delta != -{$reqRiceC} ({$nationBefore['rice']}->{$nationAfter['rice']})");
$rpAux = new \ReflectionProperty($cmd, 'auxType');
if (PHP_VERSION_ID < 80100) { $rpAux->setAccessible(true); }
$auxKey = $rpAux->getValue($cmd)->value;
hardAssert($auxKey === NationAuxKey::can_산저병사용->value, RAW . ": auxType != can_산저병사용 ({$auxKey})");
hardAssert((int)($nationAfter['aux'][$auxKey] ?? 0) === 1, RAW . ": aux[{$auxKey}] != 1 after run");
hardAssert(!isset($nationBefore['aux'][$auxKey]) || (int)$nationBefore['aux'][$auxKey] < 1,
    RAW . ": aux[{$auxKey}] was already set before run (gate not reachable)");

$expGain = 5 * ($cmd->getPreReqTurn() + 1);
hardAssert((int)$after['general']['experience'] === (int)$before['general']['experience'] + $expGain,
    RAW . ": exp delta != +{$expGain}");
hardAssert((int)$after['general']['dedication'] === (int)$before['general']['dedication'] + $expGain,
    RAW . ": ded delta != +{$expGain}");

$generalHistoryLines  = generalHistoryRowsSince($db, $chief, $genHistIdBefore);
$nationalHistoryLines = nationalHistoryRowsSince($db, $nid, $natHistIdBefore);
hardAssert(count($generalHistoryLines) === 1,
    RAW . ": expected 1 general-history line, got " . count($generalHistoryLines) . " :: " . implode(' | ', $generalHistoryLines));
hardAssert(count($nationalHistoryLines) === 1,
    RAW . ": expected 1 national-history line, got " . count($nationalHistoryLines) . " :: " . implode(' | ', $nationalHistoryLines));

// inheritance active_action += 1*pointCoeff (read the live coeff; player-owned actor → write fires).
$coeff = InheritancePointManager::getInstance()
    ->getInheritancePointType(InheritanceKey::active_action)->pointCoeff;
hardAssert($inheritBefore['owner'] === ACTOR_OWNER && (int)$generalAfter->getVar('npc') < 2,
    RAW . ": actor not positioned player-owned (owner={$inheritBefore['owner']} npc=" . $generalAfter->getVar('npc') . ")");
$before0 = $inheritBefore['active_action'] ?? 0;
hardAssert(($inheritAfter['active_action'] ?? 0) == $before0 + $coeff,
    RAW . ": inheritance active_action delta != +{$coeff} ({$before0}->" . ($inheritAfter['active_action'] ?? 'null') . ")");

$case = [
    'case'=>RAW, 'command'=>RAW, 'ctor'=>$meta['ctor'], 'scope'=>'nationCommand',
    'generalId'=>$chief, 'cityId'=>$cityId,
    'env'=>['year'=>$year,'startYear'=>(int)$env['startyear'],'month'=>$month,'develCost'=>(int)$env['develcost']],
    'arg'=>null, 'seedString'=>$seedString, 'reqGold'=>$reqGold,
    'before'=>$before, 'after'=>$after,
    'logLines'=>$actingLines, 'broadcastLines'=>$broadcastLines,
    'generalHistoryLines'=>$generalHistoryLines, 'nationalHistoryLines'=>$nationalHistoryLines,
    'nationBefore'=>$nationBefore, 'nationAfter'=>$nationAfter,
    'inheritanceBefore'=>$inheritBefore, 'inheritanceAfter'=>$inheritAfter,
    'inheritancePointCoeff'=>$coeff,
    'draws'=>['draw_count'=>$rng->getDrawCount(), 'draw_stream'=>$rng->getDrawStream()],
];

// restore the world so the capture is idempotent (next run starts pristine).
restoreWorld($db, $world);
\sammo\refreshNationStaticInfo();

$out = ['hiddenSeed'=>$hiddenSeed, 'command'=>RAW,
    'env'=>['year'=>$year,'startYear'=>$startYear,'develCost'=>$develCost], 'cases'=>[$case]];
$outPath = $outDir . '/' . RAW . '-fixtures.json';
file_put_contents($outPath, Json::encode($out, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES | JSON_PRETTY_PRINT));
fwrite(STDERR, "wrote " . basename($outPath) . " (1 case, " . $case['draws']['draw_count'] . " draws, "
    . count($actingLines) . " acting line(s))\n");
