<?php
/**
 * dump_golden_db.php — row→JSON golden DB fragment (called by dump_golden_db.sh).
 *
 * ONE-SHOT, MANUAL HOST STEP — NEVER CI.
 *
 * Run AFTER capture_che.php has left the DB in the canonical commerce_success
 * post-turn state. Emits the post-turn rows of general / city / log_entry through
 * PHP `Json::encode` so the general.aux (meta) jsonb is in key INSERTION order,
 * compact, UTF-8 literal (no \uXXXX), unescaped slashes, integers without `.0` —
 * the exact byte shape the D1 Kotlin row mappers must reproduce and that G4 step 5
 * byte-compares against.
 *
 * Boot/DB binding is via _boot.php (the install-time d_setting/DB.php substitution
 * the live game uses — there is NO DB::setSelfConnInfo / GameConst::getServerConn-
 * Info in this revision). Driven by env (set by dump_golden_db.sh):
 * SAMMO_GOLDEN_GENERALS (csv ids), SAMMO_GOLDEN_CITIES (csv ids), SAMMO_GOLDEN_OUT,
 * plus SAMMO_DB_* for the connection.
 */

namespace sammo;

require __DIR__ . '/_boot.php';

$generalIds = array_values(array_filter(array_map('intval', explode(',', getenv('SAMMO_GOLDEN_GENERALS') ?: ''))));
$cityIds    = array_values(array_filter(array_map('intval', explode(',', getenv('SAMMO_GOLDEN_CITIES') ?: ''))));
$outPath    = getenv('SAMMO_GOLDEN_OUT') ?: (__DIR__ . '/../../logic/src/test/resources/golden/p1/che-golden-db.json');

$db = DB::db();

// general: SELECT * so the dump is the full ~70-column row; the aux (meta) jsonb
// is re-encoded through Json::encode (NOT the DB driver) for key-order fidelity.
$generals = [];
foreach ($generalIds as $gid) {
    $row = $db->queryFirstRow('SELECT * FROM general WHERE no=%i', $gid);
    if (!$row) { fwrite(STDERR, "general {$gid} not found\n"); exit(2); }
    // default the city set to each captured general's current city (the chosen
    // general's city varies per install, so deriving it is more robust than a
    // hardcoded --cities).
    if (!$cityIds) { $cityIds[] = (int)$row['city']; }
    // Re-encode the aux (meta) jsonb through Json::encode for byte fidelity.
    if (array_key_exists('aux', $row) && $row['aux'] !== null) {
        $row['aux'] = Json::encode(Json::decode($row['aux']));
    }
    $generals[] = $row;
}
$cityIds = array_values(array_unique($cityIds));

$cities = [];
foreach ($cityIds as $cid) {
    $row = $db->queryFirstRow('SELECT * FROM city WHERE city=%i', $cid);
    if (!$row) { fwrite(STDERR, "city {$cid} not found\n"); exit(2); }
    if (array_key_exists('conflict', $row) && $row['conflict'] !== null) {
        $row['conflict'] = Json::encode(Json::decode($row['conflict']));
    }
    $cities[] = $row;
}

// log_entry: the action-log rows for the captured generals (char-for-char). The
// command flushes its ActionLogger buffer into general_record on applyDB
// (General.php:749), log_type='action'.
$logEntries = [];
foreach ($generalIds as $gid) {
    $rows = $db->query(
        'SELECT general_id, log_type, year, month, text FROM general_record
            WHERE general_id=%i AND log_type=%s ORDER BY id',
        $gid, 'action'
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
