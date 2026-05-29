<?php
/**
 * install_scenario.php — headless devsam-core scenario install (manual host only).
 *
 * Drives the REAL game install path: ResetHelper::buildScenario(...), the exact
 * same call hwe/j_install.php makes. This generates d_setting/UniqueConst.php
 * (per-game hiddenSeed), copies GameConst.php, imports reset.sql + schema.sql,
 * and populates the scenario into the DB.
 *
 * Usage (inside the php container, repo mounted at /work):
 *   php tools/php-golden/install_scenario.php --scenario=0 --turnterm=120 [--sync=0]
 */

namespace sammo;

// _boot.php binds d_setting/DB.php + ServConfig.php, then requires lib.php+func.php.
require __DIR__ . '/_boot.php';

$opts = getopt('', ['scenario:', 'turnterm:', 'sync:']);
$scenario = (int)($opts['scenario'] ?? 0);
$turnterm = (int)($opts['turnterm'] ?? 120);
$sync     = (int)($opts['sync'] ?? 0);

// Generate d_setting/RootDB.php (account DB) — point at the same MySQL so the
// admin-founder loop (SELECT FROM member WHERE grade>=6) can run. We create an
// empty `member` table so it inserts zero founder generals.
$DEVSAM_HWE = realpath(__DIR__ . '/../../legacy/devsam-core/hwe');
$rootOrig = file_get_contents(realpath($DEVSAM_HWE . '/..') . '/f_install/templates/RootDB.orig.php');
$rootBound = strtr($rootOrig, [
    '_tK_host_'       => getenv('SAMMO_DB_HOST') ?: 'devsam-golden-db',
    '_tK_user_'       => getenv('SAMMO_DB_USER') ?: 'root',
    '_tK_password_'   => getenv('SAMMO_DB_PASS') ?: 'rootpw',
    '_tK_dbName_'     => getenv('SAMMO_DB_NAME') ?: 'samdb',
    '_tK_port_'       => (string)((int)(getenv('SAMMO_DB_PORT') ?: 3306)),
    '_tK_globalSalt_' => 'goldensalt',
]);
file_put_contents($DEVSAM_HWE . '/d_setting/RootDB.php', $rootBound);

$db = DB::db();
// Ensure an (empty) member table exists for RootDB founder query.
$db->query('CREATE TABLE IF NOT EXISTS member (
    `no` INT PRIMARY KEY AUTO_INCREMENT,
    `name` VARCHAR(64) DEFAULT NULL,
    `grade` INT DEFAULT 0,
    `picture` VARCHAR(255) DEFAULT NULL,
    `imgsvr` INT DEFAULT 0
) DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin');

// ── the real game install call (hwe/j_install.php line ~tail) ────────────────
$result = ResetHelper::buildScenario(
    $turnterm,                 // turnterm
    $sync,                     // sync
    $scenario,                 // scenario
    0,                         // fiction
    0,                         // extend
    0,                         // block_general_create
    0,                         // npcmode
    1,                         // show_img_level
    false,                     // tournament_trig
    'full',                    // join_mode
    TimeUtil::now(),           // turntime
    null                       // autorun_user
);

fwrite(STDERR, "buildScenario => " . Json::encode($result) . "\n");
if (!($result['result'] ?? false)) {
    fwrite(STDERR, "INSTALL FAILED\n");
    exit(2);
}

// Re-read game_env + report what the install actually produced.
$gameStor = KVStorage::getStorage($db, 'game_env');
[$year, $month, $startYear, $develCost, $turntermOut] = $gameStor->getValuesAsArray(
    ['year', 'month', 'startyear', 'develcost', 'turnterm']
);

$genCount  = $db->queryFirstField('SELECT COUNT(*) FROM general');
$cityCount = $db->queryFirstField('SELECT COUNT(*) FROM city');
$natCount  = $db->queryFirstField('SELECT COUNT(*) FROM nation');
$ownedCities = $db->queryFirstField('SELECT COUNT(*) FROM city WHERE nation<>0');

fwrite(STDERR, sprintf(
    "INSTALL OK: hiddenSeed=%s year=%d month=%d startYear=%d develCost=%d turnterm=%d | generals=%d cities=%d nations=%d ownedCities=%d\n",
    UniqueConst::$hiddenSeed, $year, $month, $startYear, $develCost, $turntermOut,
    $genCount, $cityCount, $natCount, $ownedCities
));
