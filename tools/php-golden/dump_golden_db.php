<?php
/**
 * dump_golden_db.php — row→JSON golden DB fragment (called by dump_golden_db.sh).
 *
 * ONE-SHOT, MANUAL HOST STEP — NEVER CI.
 *
 * Emits the post-tick rows of general / city / log_entry through PHP
 * `Json::encode` so the general.meta jsonb is in key INSERTION order, compact,
 * UTF-8 literal (no \uXXXX), unescaped slashes, integers without `.0` — the
 * exact byte shape the D1 Kotlin row mappers must reproduce and that G4 step 5
 * byte-compares against.
 *
 * Driven by env (set by dump_golden_db.sh): SAMMO_SERVER_ID,
 * SAMMO_GOLDEN_GENERALS (csv ids), SAMMO_GOLDEN_CITIES (csv ids), SAMMO_GOLDEN_OUT.
 */

namespace sammo;

require_once __DIR__ . '/../../legacy/devsam-core/hwe/lib.php';
require_once __DIR__ . '/../../legacy/devsam-core/hwe/func.php';

$serverID  = getenv('SAMMO_SERVER_ID') ?: throw new \RuntimeException('SAMMO_SERVER_ID required');
$generalIds = array_filter(array_map('intval', explode(',', getenv('SAMMO_GOLDEN_GENERALS') ?: '')));
$cityIds    = array_filter(array_map('intval', explode(',', getenv('SAMMO_GOLDEN_CITIES') ?: '')));
$outPath    = getenv('SAMMO_GOLDEN_OUT') ?: (__DIR__ . '/../../logic/src/test/resources/golden/p1/che-golden-db.json');

DB::setSelfConnInfo(GameConst::getServerConnInfo($serverID));
$db = DB::db();

// general: explicit column list so the dump is deterministic + the meta jsonb
// is re-encoded through Json::encode (NOT the DB driver) for key-order fidelity.
$generals = [];
foreach ($generalIds as $gid) {
    $row = $db->queryFirstRow('SELECT * FROM general WHERE no=%i', $gid);
    if (!$row) { fwrite(STDERR, "general {$gid} not found\n"); exit(2); }
    // Re-encode the meta jsonb (aux) through Json::encode for byte fidelity.
    if (isset($row['aux'])) {
        $row['aux'] = Json::encode(Json::decode($row['aux']));
    }
    $generals[] = $row;
}

$cities = [];
foreach ($cityIds as $cid) {
    $row = $db->queryFirstRow('SELECT * FROM city WHERE city=%i', $cid);
    if (!$row) { fwrite(STDERR, "city {$cid} not found\n"); exit(2); }
    $cities[] = $row;
}

// log_entry: the action-log rows for the captured generals (char-for-char).
$logEntries = [];
foreach ($generalIds as $gid) {
    $rows = $db->query(
        'SELECT general_no, log_type, year, month, text FROM general_action_log WHERE general_no=%i ORDER BY id',
        $gid
    );
    foreach ($rows as $r) { $logEntries[] = $r; }
}

$fragment = [
    'general'   => $generals,
    'city'      => $cities,
    'log_entry' => $logEntries,
];

file_put_contents(
    $outPath,
    Json::encode($fragment, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES | JSON_PRETTY_PRINT)
);
fwrite(STDERR, "wrote " . basename($outPath) . " (" . count($generals) . " generals, "
    . count($cities) . " cities, " . count($logEntries) . " log rows)\n");
