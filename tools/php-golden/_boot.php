<?php
/**
 * _boot.php — devsam-core PHP boot for the P1 golden capture (manual host only).
 *
 * Replicates the game's INSTALL-TIME DB binding faithfully: the live game binds
 * the DB connection by substituting the container host/creds into
 * d_setting/DB.php via Util::generateFileUsingSimpleTemplate (exactly what
 * hwe/j_install_db.php does), then accesses the DB through DB::db(). There is NO
 * DB::setSelfConnInfo / GameConst::getServerConnInfo in this revision.
 *
 * This file ONLY binds the connection + autoloader. The scenario install
 * (ResetHelper::buildScenario) is driven by install_scenario.php so DB.php /
 * ServConfig.php exist before lib.php's d_setting classmap is evaluated.
 *
 * Env (set by the caller / docker exec):
 *   SAMMO_DB_HOST  SAMMO_DB_PORT  SAMMO_DB_USER  SAMMO_DB_PASS  SAMMO_DB_NAME
 */

namespace sammo;

$DEVSAM_HWE = realpath(__DIR__ . '/../../legacy/devsam-core/hwe');
if ($DEVSAM_HWE === false) {
    fwrite(STDERR, "boot: cannot locate legacy/devsam-core/hwe\n");
    exit(70);
}

$dbHost = getenv('SAMMO_DB_HOST') ?: 'devsam-golden-db';
$dbPort = (int)(getenv('SAMMO_DB_PORT') ?: 3306);
$dbUser = getenv('SAMMO_DB_USER') ?: 'root';
$dbPass = getenv('SAMMO_DB_PASS') ?: 'rootpw';
$dbName = getenv('SAMMO_DB_NAME') ?: 'samdb';

// 1) d_setting/DB.php — the install-time _tK_*_ substitution (j_install_db.php).
//    We cannot call Util::* before the autoloader exists, so do the same
//    placeholder substitution that generateFileUsingSimpleTemplate performs for
//    scalar params (str_replace of "_tK_{key}_").
$dbOrig = file_get_contents($DEVSAM_HWE . '/d_setting/DB.orig.php');
$dbBound = strtr($dbOrig, [
    '_tK_host_'     => $dbHost,
    '_tK_user_'     => $dbUser,
    '_tK_password_' => $dbPass,
    '_tK_dbName_'   => $dbName,
    '_tK_port_'     => (string)$dbPort,   // bare int placeholder in the template
    '_tK_prefix_'   => 'hwe',             // basename of the install dir
]);
file_put_contents($DEVSAM_HWE . '/d_setting/DB.php', $dbBound);

// 2) d_setting/ServConfig.php — ResetHelper::clearDB() calls
//    ServConfig::getServerList()['hwe']->getKorName(). The template hardcodes
//    the ['hwe','훼','red'] entry; the _tK_* path placeholders are cosmetic for
//    our headless capture, so substitute empty strings.
$servOrig = file_get_contents(realpath($DEVSAM_HWE . '/..') . '/f_install/templates/ServConfig.orig.php');
$servBound = strtr($servOrig, [
    '_tK_serverBasePath_'  => '',
    '_tK_sharedIconPath_'  => '',
    '_tK_gameImagePath_'   => '',
    '_tK_imageRequestPath_' => '',
    '_tK_imageRequestKey_' => '',
    '[/*_tK_serverList_*/]' => '[]',
]);
file_put_contents($DEVSAM_HWE . '/d_setting/ServConfig.php', $servBound);

// 3) Boot the game runtime exactly as every hwe/*.php entrypoint does.
//    lib.php pulls vendor/autoload + the hwe/d_setting classmap (which only
//    covers files that have a matching *.orig.php IN hwe/d_setting). DB.php is
//    covered (DB.orig.php ships in hwe/d_setting); ServConfig/RootDB are NOT
//    (their templates live in f_install/templates), so lib.php would miss the
//    copies we generated above. We grab the same Composer ClassLoader lib.php
//    uses and register those two explicitly — the faithful equivalent of the
//    d_setting classmap the installer relies on. f_config/config.php (composer
//    "files" autoload) defines ROOT; func.php pulls the rule engine.
$loader = require $DEVSAM_HWE . '/../vendor/autoload.php';
$loader->addClassMap([
    'sammo\\ServConfig' => $DEVSAM_HWE . '/d_setting/ServConfig.php',
    'sammo\\RootDB'     => $DEVSAM_HWE . '/d_setting/RootDB.php',
]);

require_once $DEVSAM_HWE . '/lib.php';
require_once $DEVSAM_HWE . '/func.php';

return DB::db();
