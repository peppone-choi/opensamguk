<?php
namespace sammo;
require __DIR__ . '/_boot.php';
$db = DB::db();
$gameStor = KVStorage::getStorage($db, 'game_env');
$sy=$gameStor->getValue('year'); $sm=$gameStor->getValue('month');
$gameStor->setValue('year',184); $gameStor->setValue('month',1);
$g = General::createObjFromDB(152);
$rl = new \ReflectionMethod($g, 'getActionList');
$rl->setAccessible(true);
$list = $rl->invoke($g);
foreach($list as $i=>$a){
    if($a===null){ continue; }
    $cls = get_class($a);
    $v = $a->onCalcStat($g, 'leadership', 49.0);
    fwrite(STDOUT, "action[$i]=$cls leadership 49 -> $v\n");
}
$gameStor->setValue('year',$sy); $gameStor->setValue('month',$sm);
