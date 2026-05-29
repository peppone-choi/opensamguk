<?php
/**
 * probe_picks.php — choose the capture picks for the CURRENT install/seed by
 * REALLY executing each candidate's MONTH-1 turn (with the module-free + mid-band
 * precondition applied, then baseline-restored) and recording its pick, rejecting
 * any that level-cross or emit a non-action log line.
 *
 * Each fixture case is a real turn for ONE module-free general at a chosen game
 * month: game_env.month is set to the case month BEFORE the turn, so the
 * ActionLogger "N월:" prefix is faithful (createObjFromDB builds the logger from
 * game_env year/month — General.php:1037) and the seed's month component matches.
 *
 * Prints a PICKS= line for the orchestrator: a single general + the months that
 * give distinct success/normal/fail commerce + a normal agri, plus the lowest-gid
 * neutral general (blocked).
 *
 * Precondition (also applied by capture_che.php): personal='None' (no-op
 * personality), explevel/dedlevel synced to experience/dedication, and exp/ded
 * nudged to ~40% into their current level band so the turn's gain does not cross a
 * level (the install leaves explevel=0 with experience>0, so an unsynced first turn
 * would log a spurious 레벨업 — assertion 3's deferred path). This positions the
 * general mid-band; the action's RNG/score/log/deltas are 100% real PHP.
 */

namespace sammo;
require __DIR__ . '/_boot.php';
use sammo\Command\General\che_상업투자;

$db = DB::db();
$gameStor = KVStorage::getStorage($db, 'game_env');
[$year, $month0] = $gameStor->getValuesAsArray(['year', 'month']);
$hs = UniqueConst::$hiddenSeed;

/** Set the game clock month (the ActionLogger reads it via createObjFromDB) and
 *  return the refreshed env. */
function setGameMonth(object $db, int $month): array {
    $gs = KVStorage::getStorage($db, 'game_env');
    $gs->month = $month;
    $gs->resetCache();
    return KVStorage::getStorage($db, 'game_env')->getAll();
}

/** exp band [lo,hi) for getExpLevel level L; ded band (lo,hi] for getDedLevel L. */
function expBandLo(int $L): int { return $L < 10 ? $L * 100 : 10 * $L * $L; }
function expBandHi(int $L): int { return $L < 10 ? ($L + 1) * 100 : 10 * ($L + 1) * ($L + 1); }
function dedBandLo(int $L): int { return $L <= 0 ? 0 : (10 * ($L - 1)) * (10 * ($L - 1)); }
function dedBandHi(int $L): int { return (10 * $L) * (10 * $L); }

/** Apply the module-free + mid-band precondition; return the new baseline row. */
function applyPrecondition(object $db, int $gid): array {
    $row = $db->queryFirstRow('SELECT experience, dedication FROM general WHERE no=%i', $gid);
    $exp = (int)$row['experience'];
    $ded = (int)$row['dedication'];
    $el = getExpLevel($exp);
    $dl = getDedLevel($ded);
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

/** Real turn at game month $m from baseline rows; restores baseline + clock. */
function runOnce(object $db, int $gid, string $raw, int $year, int $m, string $hs, array $bg, array $bc, int $month0): array {
    $cid = (int)$bg['city'];
    $env = setGameMonth($db, $m);
    $db->update('general', $bg, 'no=%i', $gid);
    $db->update('city', $bc, 'city=%i', $cid);
    $db->delete('general_record', 'general_id=%i AND log_type=%s', $gid, 'action');

    $g = General::createObjFromDB($gid);
    $eb = $g->getVar('explevel'); $dbf = $g->getVar('dedlevel');
    $cmd = buildGeneralCommandClass($raw, $g, $env, null);
    if (!$cmd->hasFullConditionMet()) $res = ['pick' => 'BLOCKED', 'cross' => true, 'lines' => 0];
    else {
        $seed = Util::simpleSerialize($hs, 'generalCommand', $year, $m, $gid, $cmd->getRawClassName());
        $cmd->run(new RandUtil(new LiteHashDRBG($seed)));
        $rows = $db->query('SELECT text FROM general_record WHERE general_id=%i AND log_type=%s ORDER BY id', $gid, 'action');
        $l0 = $rows[0]['text'] ?? '';
        $pick = str_contains($l0, 'ev_failed') ? 'fail' : (str_contains($l0, '<S>성공</>') ? 'success' : 'normal');
        $ga = General::createObjFromDB($gid);
        $res = ['pick' => $pick, 'cross' => ($ga->getVar('explevel') !== $eb) || ($ga->getVar('dedlevel') !== $dbf), 'lines' => count($rows)];
    }
    $db->update('general', $bg, 'no=%i', $gid);
    $db->update('city', $bc, 'city=%i', $cid);
    $db->delete('general_record', 'general_id=%i AND log_type=%s', $gid, 'action');
    setGameMonth($db, $month0);
    return $res;
}

$rows = $db->query("SELECT g.no FROM general g JOIN city c ON g.city=c.city
    WHERE c.nation<>0 AND c.supply=1 AND c.front NOT IN (1,3)
      AND c.comm<c.comm_max AND c.agri<c.agri_max AND c.trust=floor(c.trust)
      AND g.nation=c.nation AND g.officer_level>=1
      AND (g.special='None' OR g.special='' OR g.special IS NULL)
      AND (g.special2='None' OR g.special2='' OR g.special2 IS NULL)
    ORDER BY g.no");

$chosen = null;
foreach ($rows as $r) {
    $gid = (int)$r['no'];
    $bg = applyPrecondition($db, $gid);
    $cid = (int)$bg['city'];
    $bc = $db->queryFirstRow('SELECT * FROM city WHERE city=%i', $cid);

    $g0 = General::createObjFromDB($gid);
    $ok = true;
    foreach (['상업', '농업'] as $k) foreach ([['score',123.0],['cost',100.0],['success',0.5],['fail',0.25]] as [$vt,$v])
        if (abs($g0->onCalcDomestic($k,$vt,$v)-$v) > 1e-9) $ok = false;
    if (!$ok) continue;
    foreach (($g0->getItems() ?? []) as $it) if (!($it instanceof \sammo\ActionItem\None)) { $ok = false; break; }
    if (!$ok) continue;

    $cm = ['success' => null, 'normal' => null, 'fail' => null];
    for ($m = 1; $m <= 12; $m++) {
        $cc = runOnce($db, $gid, 'che_상업투자', $year, $m, $hs, $bg, $bc, $month0);
        if ($cc['pick'] !== 'BLOCKED' && !$cc['cross'] && $cc['lines'] === 1 && $cm[$cc['pick']] === null) $cm[$cc['pick']] = $m;
    }
    if ($cm['success'] === null || $cm['normal'] === null || $cm['fail'] === null) continue;

    $am = null;
    for ($m = 1; $m <= 12; $m++) {
        $ac = runOnce($db, $gid, 'che_농지개간', $year, $m, $hs, $bg, $bc, $month0);
        if ($ac['pick'] === 'normal' && !$ac['cross'] && $ac['lines'] === 1) { $am = $m; break; }
    }
    if ($am === null) continue;

    $chosen = ['gid' => $gid, 'cs' => $cm['success'], 'cn' => $cm['normal'], 'cf' => $cm['fail'], 'an' => $am];
    break;
}

if ($chosen === null) { fwrite(STDERR, "PROBE FAIL: no clean general yields all picks\n"); exit(3); }

$blockedGid = (int)$db->queryFirstField("SELECT no FROM general WHERE nation=0 ORDER BY no LIMIT 1");
if ($blockedGid <= 0) { fwrite(STDERR, "PROBE FAIL: no neutral general for blocked case\n"); exit(3); }

$cityId = (int)$db->queryFirstField('SELECT city FROM general WHERE no=%i', $chosen['gid']);

printf("PICKS gid=%d city=%d m_success=%d m_normal=%d m_fail=%d m_agri_normal=%d blocked_gid=%d hiddenSeed=%s year=%d\n",
    $chosen['gid'], $cityId, $chosen['cs'], $chosen['cn'], $chosen['cf'], $chosen['an'], $blockedGid, $hs, $year);
