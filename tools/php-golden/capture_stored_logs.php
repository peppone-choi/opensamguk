<?php

namespace sammo;

require __DIR__ . '/_boot.php';

error_reporting(E_ALL & ~E_DEPRECATED & ~E_NOTICE & ~E_WARNING & ~E_USER_DEPRECATED & ~E_STRICT);

$opts = getopt('', ['out:']);
$out = $opts['out'] ?? (__DIR__ . '/../../build/v1-evidence/stored-logs.json');
$db = DB::db();

function hardAssert(bool $condition, string $message): void
{
    if (!$condition) {
        fwrite(STDERR, "STORED-LOG HARD-ASSERT FAILED: {$message}\n");
        exit(2);
    }
}

$actor = $db->queryFirstRow(
    'SELECT no, nation FROM general WHERE nation > 0 ORDER BY no ASC LIMIT 1'
);
hardAssert((bool)$actor, 'scenario_1010 has no nation general');

$year = 187;
$month = 3;
$generalID = (int)$actor['no'];
$nationID = (int)$actor['nation'];
$generalHighWater = (int)$db->queryFirstField('SELECT COALESCE(MAX(id), 0) FROM general_record');
$worldHighWater = (int)$db->queryFirstField('SELECT COALESCE(MAX(id), 0) FROM world_history');

$logger = new ActionLogger($generalID, $nationID, $year, $month, false);
$calls = [
    ['seq' => 0, 'scope' => 'global', 'category' => 'history', 'format' => 'YEAR_MONTH', 'payload' => '저장로그-세계연감'],
    ['seq' => 1, 'scope' => 'general', 'category' => 'action', 'format' => 'MONTH', 'payload' => '저장로그-장수행동'],
    ['seq' => 2, 'scope' => 'nation', 'category' => 'history', 'format' => 'YEAR_MONTH', 'payload' => '저장로그-국가연감'],
    ['seq' => 3, 'scope' => 'general', 'category' => 'battle', 'format' => 'PLAIN', 'payload' => '저장로그-전투상세'],
    ['seq' => 4, 'scope' => 'general', 'category' => 'history', 'format' => 'NOTICE_YEAR_MONTH', 'payload' => '저장로그-장수연감'],
    ['seq' => 5, 'scope' => 'global', 'category' => 'action', 'format' => 'EVENT_YEAR_MONTH', 'payload' => '저장로그-세계동향'],
    ['seq' => 6, 'scope' => 'general', 'category' => 'battle_brief', 'format' => 'RAWTEXT', 'payload' => '저장로그-전투요약'],
];

$logger->pushGlobalHistoryLog($calls[0]['payload'], ActionLogger::YEAR_MONTH);
$logger->pushGeneralActionLog($calls[1]['payload'], ActionLogger::MONTH);
$logger->pushNationalHistoryLog($calls[2]['payload'], ActionLogger::YEAR_MONTH);
$logger->pushGeneralBattleDetailLog($calls[3]['payload'], ActionLogger::PLAIN);
$logger->pushGeneralHistoryLog($calls[4]['payload'], ActionLogger::NOTICE_YEAR_MONTH);
$logger->pushGlobalActionLog($calls[5]['payload'], ActionLogger::EVENT_YEAR_MONTH);
$logger->pushGeneralBattleResultLog($calls[6]['payload'], ActionLogger::RAWTEXT);
$logger->flush();

$generalRows = $db->query(
    'SELECT id, general_id, log_type, year, month, text
       FROM general_record WHERE id > %i ORDER BY id ASC',
    $generalHighWater
);
$worldRows = $db->query(
    'SELECT id, nation_id, year, month, text
       FROM world_history WHERE id > %i ORDER BY id ASC',
    $worldHighWater
);

hardAssert(count($generalRows) === 5, 'expected five persisted general/global-action rows');
hardAssert(count($worldRows) === 2, 'expected two persisted nation/global-history rows');

$fixture = [
    'oracle' => 'legacy/devsam-core',
    'source' => [
        'logger' => 'hwe/sammo/ActionLogger.php:80-215',
        'storage' => 'hwe/func_history.php:114-357',
    ],
    'input' => [
        'generalID' => $generalID,
        'nationID' => $nationID,
        'year' => $year,
        'month' => $month,
        'calls' => $calls,
    ],
    'persisted' => [
        'general_record' => $generalRows,
        'world_history' => $worldRows,
    ],
    'observedFlushGrouping' => [
        'general_history',
        'general_action',
        'general_battle_brief',
        'general_battle',
        'nation_history',
        'global_history',
        'global_action',
    ],
];

if (!is_dir(dirname($out))) {
    mkdir(dirname($out), 0775, true);
}
file_put_contents(
    $out,
    Json::encode($fixture, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES | JSON_PRETTY_PRINT) . "\n"
);
fwrite(STDERR, 'stored-log capture wrote ' . basename($out) . "\n");
