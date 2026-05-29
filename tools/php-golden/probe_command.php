<?php
/**
 * probe_command.php — choose the capture picks for the CURRENT install/seed by
 * REALLY executing each P2 command's candidate turn (module-free + mid-band
 * precondition applied, then baseline-restored) and recording the pick, rejecting
 * any that level-cross or emit the wrong acting-general action-line count.
 *
 * Generalization of P1's probe_picks.php (which probed only che_상업투자/농지개간).
 * Per command in tools/php-golden/manifest.json this:
 *   - selects module-free candidate generals matching the command's ctor gate
 *     (general: owned/supplied/non-front officer; nation: officer_level>=5),
 *   - builds the command through the correct factory (3-arg general /
 *     4-arg nation with a fresh LastTurn), seeds it under the matching scope
 *     ('generalCommand' | 'nationCommand'), runs MONTH 1..12, and records which
 *     months yield DISTINCT clean reachable outcomes (success/normal/fail, or for a
 *     deterministic command its single outcome) with no level cross and the manifest
 *     logLines count,
 *   - prints a PICKS line per command for the operator to paste into
 *     capture_command.php's $picks (+ SAMMO_BLOCKED_GID for the blocked case).
 *
 * SYNTHETIC-SEED FALLBACK: scenario_0 is empty land (no module-free owned general).
 * When the running install has NO general satisfying a command's gate, the probe
 * SYNTHESIZES one — it picks the lowest-gid general, applies the precondition, and
 * (for nation commands) promotes it (officer_level=5, a non-zero nation) — and prints
 * the synthetic pick with a `synthetic=1` flag so the operator captures from a
 * known-positioned general. The action's RNG/score/log/deltas stay 100% real PHP.
 *
 * Run AFTER install_scenario.php, BEFORE capture_command.php (see README).
 */

namespace sammo;
require __DIR__ . '/_boot.php';

$db = DB::db();
$gameStor = KVStorage::getStorage($db, 'game_env');
[$year, $month0] = $gameStor->getValuesAsArray(['year', 'month']);
$hs = UniqueConst::$hiddenSeed;

$opts    = getopt('', ['command:']);
$onlyCmd = $opts['command'] ?? null;

$manifest = Json::decode(file_get_contents(__DIR__ . '/manifest.json'));
$cmdMeta = [];
foreach ($manifest['commands'] as $group => $list) {
    foreach ($list as $entry) { $cmdMeta[$entry['rawClassName']] = $entry; }
}

function setGameMonth(object $db, int $month): array {
    $gs = KVStorage::getStorage($db, 'game_env');
    $gs->month = $month;
    $gs->resetCache();
    return KVStorage::getStorage($db, 'game_env')->getAll();
}

function expBandLo(int $L): int { return $L < 10 ? $L * 100 : 10 * $L * $L; }
function expBandHi(int $L): int { return $L < 10 ? ($L + 1) * 100 : 10 * ($L + 1) * ($L + 1); }
function dedBandLo(int $L): int { return $L <= 0 ? 0 : (10 * ($L - 1)) * (10 * ($L - 1)); }
function dedBandHi(int $L): int { return (10 * $L) * (10 * $L); }

/** Module-free + mid-band precondition; returns the new baseline row. */
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

/** True iff the general's domestic pipeline is identity + every item is None. */
function isModuleFree(General $g): bool {
    foreach (['상업', '농업'] as $k) foreach ([['score',123.0],['cost',100.0],['success',0.5],['fail',0.25]] as [$vt,$v])
        if (abs($g->onCalcDomestic($k,$vt,$v) - $v) > 1e-9) return false;
    foreach (($g->getItems() ?? []) as $it) if (!($it instanceof \sammo\ActionItem\None)) return false;
    return true;
}

/**
 * Run ONE candidate turn at game month $m from baseline rows; restore baseline +
 * clock. Forks the ctor + seed scope on the manifest entry. Returns
 * [pick, cross, lines].
 */
function runOnce(object $db, array $meta, int $gid, int $year, int $m, string $hs, array $bg, array $bc, int $month0): array {
    $cid = (int)$bg['city'];
    $env = setGameMonth($db, $m);
    $db->update('general', $bg, 'no=%i', $gid);
    if ($cid && $bc) $db->update('city', $bc, 'city=%i', $cid);
    $db->delete('general_record', 'general_id=%i AND log_type=%s', $gid, 'action');

    $g  = General::createObjFromDB($gid);
    $eb = $g->getVar('explevel'); $dbf = $g->getVar('dedlevel');
    $raw = $meta['rawClassName'];

    if ($meta['ctor'] === 'nation') {
        if ((int)$g->getVar('nation') === 0 || (int)$g->getVar('officer_level') < 5) {
            $res = ['pick' => 'GATE', 'cross' => true, 'lines' => 0];
        } else {
            $cmd = buildNationCommandClass($raw, $g, $env, LastTurn::fromRaw(null), null);
            $scope = 'nationCommand';
            $res = runCmd($db, $cmd, $g, $gid, $year, $m, $hs, $scope, $eb, $dbf);
        }
    } else {
        $cmd = buildGeneralCommandClass($raw, $g, $env, null);
        $scope = 'generalCommand';
        $res = runCmd($db, $cmd, $g, $gid, $year, $m, $hs, $scope, $eb, $dbf);
    }

    $db->update('general', $bg, 'no=%i', $gid);
    if ($cid && $bc) $db->update('city', $bc, 'city=%i', $cid);
    $db->delete('general_record', 'general_id=%i AND log_type=%s', $gid, 'action');
    setGameMonth($db, $month0);
    return $res;
}

function runCmd(object $db, \sammo\Command\BaseCommand $cmd, General $g, int $gid, int $year, int $m, string $hs, string $scope, $eb, $dbf): array {
    if (!$cmd->hasFullConditionMet()) return ['pick' => 'BLOCKED', 'cross' => true, 'lines' => 0];
    $seed = Util::simpleSerialize($hs, $scope, $year, $m, $gid, $cmd->getRawClassName());
    $cmd->run(new RandUtil(new LiteHashDRBG($seed)));
    $rows = $db->query('SELECT text FROM general_record WHERE general_id=%i AND log_type=%s ORDER BY id', $gid, 'action');
    $l0 = $rows[0]['text'] ?? '';
    $pick = str_contains($l0, 'ev_failed') ? 'fail' : (str_contains($l0, '<S>성공</>') ? 'success' : 'normal');
    $ga = General::createObjFromDB($gid);
    return ['pick' => $pick, 'cross' => ($ga->getVar('explevel') !== $eb) || ($ga->getVar('dedlevel') !== $dbf), 'lines' => count($rows)];
}

/** Candidate generals for a command's gate (general vs nation). Empty → caller
 *  triggers the synthetic-seed fallback. */
function candidateGenerals(object $db, array $meta): array {
    if ($meta['ctor'] === 'nation') {
        return $db->queryFirstColumn(
            "SELECT no FROM general WHERE nation<>0 AND officer_level>=5
               AND (special='None' OR special='' OR special IS NULL)
               AND (special2='None' OR special2='' OR special2 IS NULL)
             ORDER BY no"
        );
    }
    return $db->queryFirstColumn(
        "SELECT g.no FROM general g JOIN city c ON g.city=c.city
           WHERE c.nation<>0 AND c.supply=1 AND c.front NOT IN (1,3)
             AND c.trust=floor(c.trust)
             AND g.nation=c.nation AND g.officer_level>=1
             AND (g.special='None' OR g.special='' OR g.special IS NULL)
             AND (g.special2='None' OR g.special2='' OR g.special2 IS NULL)
         ORDER BY g.no"
    );
}

/** Synthesize a positioned module-free general for a command whose gate no real
 *  general meets (empty scenario_0). Lowest-gid general, precondition applied, and
 *  (nation cmd) promoted to officer_level=5 + a non-zero nation. */
function synthesizeGeneral(object $db, array $meta): ?int {
    $gid = (int)$db->queryFirstField('SELECT no FROM general ORDER BY no LIMIT 1');
    if ($gid <= 0) return null;
    applyPrecondition($db, $gid);
    if ($meta['ctor'] === 'nation') {
        $nid = (int)$db->queryFirstField('SELECT nation FROM nation ORDER BY nation LIMIT 1');
        if ($nid <= 0) return null;
        $cid = (int)$db->queryFirstField('SELECT city FROM city WHERE nation=%i ORDER BY city LIMIT 1', $nid);
        $db->update('general', ['nation' => $nid, 'officer_level' => 5] + ($cid ? ['city' => $cid] : []), 'no=%i', $gid);
    }
    return $gid;
}

/** Probe one command: returns the PICKS payload or null if it cannot be cleanly
 *  positioned. */
function probeCommand(object $db, array $meta, int $year, string $hs, int $month0): ?array {
    $cands = candidateGenerals($db, $meta);
    $synthetic = 0;
    if (!$cands) {
        $gid = synthesizeGeneral($db, $meta);
        if ($gid === null) return null;
        $cands = [$gid];
        $synthetic = 1;
    }

    foreach ($cands as $gidRaw) {
        $gid = (int)$gidRaw;
        $bg = applyPrecondition($db, $gid);
        $cid = (int)$bg['city'];
        $bc = $cid ? $db->queryFirstRow('SELECT * FROM city WHERE city=%i', $cid) : [];

        $g0 = General::createObjFromDB($gid);
        if (!isModuleFree($g0)) continue;

        // distinct reachable outcomes across months 1..12.
        $months = ['success' => null, 'normal' => null, 'fail' => null];
        for ($m = 1; $m <= 12; $m++) {
            $cc = runOnce($db, $meta, $gid, $year, $m, $hs, $bg, $bc, $month0);
            if (in_array($cc['pick'], ['BLOCKED', 'GATE'], true)) continue;
            if ($cc['cross'] || $cc['lines'] !== (int)$meta['logLines']) continue;
            if ($months[$cc['pick']] === null) $months[$cc['pick']] = $m;
        }
        // a deterministic command (most non-develop) only ever yields one pick kind;
        // accept it if ANY clean month exists. A stochastic develop command needs the
        // full success/normal/fail trio.
        $hasAny = $months['success'] ?? $months['normal'] ?? $months['fail'] ?? null;
        if ($hasAny === null) continue;

        return [
            'command'   => $meta['rawClassName'],
            'gid'       => $gid,
            'city'      => $cid,
            'm_success' => $months['success'],
            'm_normal'  => $months['normal'],
            'm_fail'    => $months['fail'],
            'synthetic' => $synthetic,
        ];
    }
    return null;
}

// ── probe the requested command(s) ───────────────────────────────────────────
$targets = $onlyCmd !== null ? [$onlyCmd] : array_keys($cmdMeta);
$ok = 0;
foreach ($targets as $raw) {
    if (!isset($cmdMeta[$raw])) { fwrite(STDERR, "skip {$raw}: not in manifest\n"); continue; }
    $p = probeCommand($db, $cmdMeta[$raw], $year, $hs, $month0);
    if ($p === null) { fwrite(STDERR, "PROBE MISS {$raw}: no clean general yields a pick\n"); continue; }
    printf("PICKS command=%s gid=%d city=%d m_success=%s m_normal=%s m_fail=%s synthetic=%d hiddenSeed=%s year=%d\n",
        $p['command'], $p['gid'], $p['city'],
        $p['m_success'] ?? '-', $p['m_normal'] ?? '-', $p['m_fail'] ?? '-',
        $p['synthetic'], $hs, $year);
    $ok++;
}

// blocked-case general (neutral) — verbatim from P1.
$blockedGid = (int)$db->queryFirstField("SELECT no FROM general WHERE nation=0 ORDER BY no LIMIT 1");
if ($blockedGid > 0) {
    printf("PICKS blocked_gid=%d hiddenSeed=%s year=%d\n", $blockedGid, $hs, $year);
}

if ($ok === 0) { fwrite(STDERR, "PROBE FAIL: no command could be positioned on this install\n"); exit(3); }
