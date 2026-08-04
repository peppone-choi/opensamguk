<?php

namespace sammo;

require __DIR__ . '/_boot.php';
require __DIR__ . '/RandUtilDrawRecorder.php';

error_reporting(E_ALL & ~E_DEPRECATED & ~E_NOTICE & ~E_WARNING & ~E_USER_DEPRECATED & ~E_STRICT);

$opts = getopt('', ['out:']);
$out = $opts['out'] ?? (__DIR__ . '/../../build/v1-evidence/sortie-outer.json');
$db = DB::db();

function hardAssert(bool $condition, string $message): void
{
    if (!$condition) {
        fwrite(STDERR, "SORTIE-OUTER HARD-ASSERT FAILED: {$message}\n");
        exit(2);
    }
}

function keyedRows(object $db, string $sql, callable $key): array
{
    $result = [];
    foreach (($db->query($sql) ?: []) as $row) {
        $result[$key($row)] = $row;
    }
    return $result;
}

function outerSnapshot(object $db): array
{
    return [
        'general' => keyedRows($db, 'SELECT * FROM general ORDER BY no ASC', fn ($r) => (string)$r['no']),
        'rank_data' => keyedRows(
            $db,
            'SELECT * FROM rank_data ORDER BY general_id ASC, type ASC',
            fn ($r) => "{$r['general_id']}:{$r['type']}"
        ),
        'nation' => keyedRows($db, 'SELECT * FROM nation ORDER BY nation ASC', fn ($r) => (string)$r['nation']),
        'city' => keyedRows($db, 'SELECT * FROM city ORDER BY city ASC', fn ($r) => (string)$r['city']),
        'diplomacy' => keyedRows(
            $db,
            'SELECT * FROM diplomacy ORDER BY me ASC, you ASC',
            fn ($r) => "{$r['me']}:{$r['you']}"
        ),
        'general_turn' => keyedRows(
            $db,
            'SELECT * FROM general_turn ORDER BY general_id ASC, turn_idx ASC',
            fn ($r) => "{$r['general_id']}:{$r['turn_idx']}"
        ),
        'event' => keyedRows($db, 'SELECT * FROM event ORDER BY id ASC', fn ($r) => (string)$r['id']),
    ];
}

function snapshotDelta(array $before, array $after): array
{
    $delta = [];
    foreach ($before as $table => $beforeRows) {
        $created = [];
        $updated = [];
        $deleted = [];
        foreach ($after[$table] as $key => $row) {
            if (!array_key_exists($key, $beforeRows)) {
                $created[$key] = $row;
            } elseif ($beforeRows[$key] != $row) {
                $changed = [];
                foreach ($row as $column => $value) {
                    $old = $beforeRows[$key][$column] ?? null;
                    if ((string)$old !== (string)$value) {
                        $changed[$column] = ['from' => $old, 'to' => $value];
                    }
                }
                $updated[$key] = $changed;
            }
        }
        foreach ($beforeRows as $key => $row) {
            if (!array_key_exists($key, $after[$table])) {
                $deleted[$key] = $row;
            }
        }
        $delta[$table] = compact('created', 'updated', 'deleted');
    }
    return $delta;
}

$seedInput = getenv('SAMMO_EVIDENCE_HIDDEN_SEED');
if (is_string($seedInput) && preg_match('/^[0-9a-f]{32}$/', $seedInput)) {
    UniqueConst::$hiddenSeed = $seedInput;
}

$gameStor = KVStorage::getStorage($db, 'game_env');
$startYear = (int)$gameStor->getValue('startyear');
$year = $startYear + 3;
$month = 4;
$gameStor->setValue('year', $year);
$gameStor->setValue('month', $month);
$gameStor->resetCache();

$attackerID = 132;
$targetCityID = 80;
$attackerCityID = (int)$db->queryFirstField(
    'SELECT city FROM city WHERE nation = 1 AND city IN %li ORDER BY city ASC LIMIT 1',
    array_keys(CityConst::byID($targetCityID)->path)
);
hardAssert($attackerCityID > 0, 'no nation-1 city is adjacent to target city 80');
$fixedTurnTime = '0184-04-01 00:00:00.000000';
$db->update('general', [
    'city' => $attackerCityID,
    'crew' => 80000,
    'crewtype' => 1100,
    'train' => 100,
    'atmos' => 100,
    'rice' => 99999,
    'gold' => 99999,
    'injury' => 0,
    'turntime' => $fixedTurnTime,
], 'no = %i', $attackerID);
$db->update('general', ['city' => 1, 'crew' => 0], 'nation = 2');
$db->update('city', ['def' => 30, 'wall' => 30], 'city = %i', $targetCityID);
$db->update(
    'diplomacy',
    ['state' => 0],
    '(me = 1 AND you = 2) OR (me = 2 AND you = 1)'
);
refreshNationStaticInfo();

$general = General::createObjFromDB($attackerID);
$env = [
    'startyear' => $startYear,
    'year' => $year,
    'month' => $month,
    'join_mode' => $gameStor->getValue('join_mode'),
    'develcost' => (int)$gameStor->getValue('develcost'),
];
$command = buildGeneralCommandClass(
    'che_출병',
    $general,
    $env,
    ['destCityID' => $targetCityID]
);
hardAssert($command->hasFullConditionMet(), 'che_출병 gate: ' . ($command->testFullConditionMet() ?? 'UNKNOWN'));

$seedString = Util::simpleSerialize(
    UniqueConst::$hiddenSeed,
    'generalCommand',
    $year,
    $month,
    $attackerID,
    'che_출병'
);
$rng = new RandUtilDrawRecorder(new LiteHashDRBG($seedString));
$before = outerSnapshot($db);
$recordHighWater = (int)$db->queryFirstField('SELECT COALESCE(MAX(id), 0) FROM general_record');
$worldHighWater = (int)$db->queryFirstField('SELECT COALESCE(MAX(id), 0) FROM world_history');
$messageHighWater = (int)$db->queryFirstField('SELECT COALESCE(MAX(id), 0) FROM message');

$runResult = $command->run($rng);
hardAssert($runResult === true, 'che_출병 returned false/alternative');
$after = outerSnapshot($db);

$fixture = [
    'oracle' => 'legacy/devsam-core',
    'source' => [
        'command' => 'hwe/sammo/Command/General/che_출병.php:134-261',
        'outerWar' => 'hwe/process_war.php:8-190',
        'rank' => 'hwe/sammo/WarUnitGeneral.php:53-375',
    ],
    'input' => [
        'hiddenSeed' => UniqueConst::$hiddenSeed,
        'seedString' => $seedString,
        'year' => $year,
        'month' => $month,
        'attackerID' => $attackerID,
        'attackerCityID' => $attackerCityID,
        'targetCityID' => $targetCityID,
    ],
    'commandDraws' => [
        'draw_count' => $rng->getDrawCount(),
        'draw_stream' => $rng->getDrawStream(),
    ],
    'db_delta' => snapshotDelta($before, $after),
    'general_record' => $db->query(
        'SELECT id, general_id, log_type, year, month, text
           FROM general_record WHERE id > %i ORDER BY id ASC',
        $recordHighWater
    ) ?: [],
    'world_history' => $db->query(
        'SELECT id, nation_id, year, month, text
           FROM world_history WHERE id > %i ORDER BY id ASC',
        $worldHighWater
    ) ?: [],
    'messages' => $db->query(
        'SELECT id, mailbox, type, src, dest, valid_until, message
           FROM message WHERE id > %i ORDER BY id ASC',
        $messageHighWater
    ) ?: [],
];

hardAssert(
    count($fixture['db_delta']['rank_data']['updated']) > 0,
    'outer sortie produced no rank_data update'
);

$attackerNationID = (int)$before['general'][(string)$attackerID]['nation'];
$defenderNationID = (int)$before['city'][(string)$targetCityID]['nation'];
$attackerCityUpdate = $fixture['db_delta']['city']['updated'][(string)$attackerCityID] ?? [];
$defenderCityUpdate = $fixture['db_delta']['city']['updated'][(string)$targetCityID] ?? [];
$attackerDiplomacyKey = "{$attackerNationID}:{$defenderNationID}";
$defenderDiplomacyKey = "{$defenderNationID}:{$attackerNationID}";

// PHP hwe/process_war.php:116-124 persists the 0.4/0.6 city-dead split, and
// :172-178 persists one casualty total in each diplomacy direction. The two
// nation UPDATE calls at :160-170 can be SQL no-ops after integer formatting,
// so their invocation is not a persisted-row-delta observable.
hardAssert(
    array_key_exists('dead', $attackerCityUpdate),
    'outer sortie must persist the attacker-city casualty split'
);
hardAssert(
    array_key_exists('dead', $defenderCityUpdate),
    'outer sortie must persist the defender-city casualty split'
);
hardAssert(
    array_key_exists('dead', $fixture['db_delta']['diplomacy']['updated'][$attackerDiplomacyKey] ?? []),
    'outer sortie must persist attacker-to-defender diplomacy casualties'
);
hardAssert(
    array_key_exists('dead', $fixture['db_delta']['diplomacy']['updated'][$defenderDiplomacyKey] ?? []),
    'outer sortie must persist defender-to-attacker diplomacy casualties'
);

if (!is_dir(dirname($out))) {
    mkdir(dirname($out), 0775, true);
}
file_put_contents(
    $out,
    Json::encode($fixture, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES | JSON_PRETTY_PRINT) . "\n"
);
fwrite(STDERR, 'sortie-outer capture wrote ' . basename($out) . "\n");
