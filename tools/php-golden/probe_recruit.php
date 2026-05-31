<?php
// ONE-SHOT host probe (NEVER CI): print 하진(152) fullLeadership + do징병 amount at clock 184/1.
// Restores the clock after. Does NOT touch the goldens.
namespace sammo;

require __DIR__ . '/_boot.php';

$db = DB::db();
$gameStor = KVStorage::getStorage($db, 'game_env');
$savedY = $gameStor->getValue('year');
$savedM = $gameStor->getValue('month');
$gameStor->setValue('year', 184);
$gameStor->setValue('month', 1);

$general = General::createObjFromDB(152);
fwrite(STDOUT, "leadership(false)=" . $general->getLeadership(false) . "\n");
fwrite(STDOUT, "leadership(true)=" . $general->getLeadership(true) . "\n");
fwrite(STDOUT, "rawLeadership=" . $general->getVar('leadership') . "\n");
fwrite(STDOUT, "strength(false)=" . $general->getStrength(false) . " intel(false)=" . $general->getIntel(false) . "\n");
fwrite(STDOUT, "gold=" . $general->getVar('gold') . " rice=" . $general->getVar('rice') . " crewtype=" . $general->getVar('crewtype') . "\n");

// restore clock
$gameStor->setValue('year', $savedY);
$gameStor->setValue('month', $savedM);
