<?php

namespace sammo;

use sammo\Enums\EventTarget;
use sammo\Event\Action\OpenNationBetting;

require __DIR__ . '/_boot.php';

error_reporting(E_ALL & ~E_DEPRECATED & ~E_NOTICE & ~E_WARNING & ~E_USER_DEPRECATED & ~E_STRICT);

$opts = getopt('', ['out:']);
$out = $opts['out'] ?? (__DIR__ . '/../../build/v1-evidence/nation-betting.json');
$db = DB::db();
$fixtureTurntime = '2000-01-01 00:00:00';
$fixtureServerId = 'hwe_php_golden';

function hardAssert(bool $condition, string $message): void
{
    if (!$condition) {
        fwrite(STDERR, "NATION-BETTING HARD-ASSERT FAILED: {$message}\n");
        exit(2);
    }
}

function rows(object $db, string $sql): array
{
    return $db->query($sql) ?: [];
}

function snapshotBetting(object $db): array
{
    $messages = [];
    foreach (rows(
        $db,
        'SELECT id, mailbox, type, src, dest, valid_until, message FROM message ORDER BY id ASC'
    ) as $row) {
        $messages[] = $row;
    }
    return [
        'storage' => rows(
            $db,
            "SELECT namespace, `key`, value FROM storage
              WHERE namespace IN ('game_env', 'betting', 'inheritance_1')
              ORDER BY namespace ASC, `key` ASC"
        ),
        'event' => rows(
            $db,
            "SELECT id, target, priority, `condition`, action FROM event
              WHERE target = 'DESTROY_NATION' ORDER BY priority ASC, id ASC"
        ),
        'ng_betting' => rows(
            $db,
            'SELECT id, betting_id, general_id, user_id, betting_type, amount
               FROM ng_betting ORDER BY id ASC'
        ),
        'rank_data' => rows(
            $db,
            "SELECT general_id, nation_id, type, value FROM rank_data
              WHERE general_id = 132 AND type IN
                ('inherit_point_spent_dynamic', 'inherit_point_earned_by_action')
              ORDER BY type ASC"
        ),
        'messages' => $messages,
        'world_history' => rows(
            $db,
            'SELECT id, nation_id, year, month, text FROM world_history ORDER BY id ASC'
        ),
        'user_record' => rows(
            $db,
            'SELECT id, user_id, server_id, log_type, year, month, text
               FROM user_record ORDER BY id ASC'
        ),
    ];
}

$gameStor = KVStorage::getStorage($db, 'game_env');
$gameStor->setValue('year', 184);
$gameStor->setValue('month', 5);
$gameStor->setValue('opentime', $fixtureTurntime);
$gameStor->setValue('turntime', $fixtureTurntime);
UniqueConst::$serverID = $fixtureServerId;
$db->update('user_record', ['server_id' => $fixtureServerId], 'id > 0');
$gameStor->resetCache();
refreshNationStaticInfo();

$generalID = 132;
$ownerID = 1;
$db->update('general', ['owner' => $ownerID, 'npc' => 0], 'no = %i', $generalID);
$inheritance = KVStorage::getStorage($db, "inheritance_{$ownerID}");
$inheritance->setValue('previous', [1000, null]);

$before = snapshotBetting($db);
$open = new OpenNationBetting(2, 100);
$openResult = $open->run(['year' => 184, 'month' => 5]);
hardAssert(($openResult[1] ?? false) === true, 'OpenNationBetting returned false');

$bettingID = (int)$gameStor->getValue(Betting::LAST_BETTING_ID_KEY);
$betting = new Betting($bettingID);
$info = $betting->getInfo();
$nationToCandidate = [];
foreach ($info->candidates as $idx => $candidate) {
    $nationToCandidate[(int)$candidate->aux['nation']] = $idx;
}
$winnerNations = array_map(
    'intval',
    $db->queryFirstColumn('SELECT nation FROM nation WHERE level > 0 ORDER BY nation ASC')
);
hardAssert(count($winnerNations) === 2, 'scenario must expose exactly two active nations');
$winnerTypes = array_map(
    static fn (int $nationID): int => (int)$nationToCandidate[$nationID],
    $winnerNations
);

$afterOpen = snapshotBetting($db);
$betting->bet($generalID, $ownerID, $winnerTypes, 200);
$afterBet = snapshotBetting($db);

$handled = TurnExecutionHelper::runEventHandler($db, $gameStor, EventTarget::DestroyNation);
hardAssert($handled === true, 'DESTROY_NATION handler did not run');
$afterFinish = snapshotBetting($db);

$finishedInfo = (new Betting($bettingID))->getInfo();
hardAssert($finishedInfo->finished === true, 'betting master was not marked finished');
hardAssert($finishedInfo->winner === $winnerTypes, 'winner types differ from active nations');
$matchesOpenedFinishEvent = static function (array $event) use ($bettingID): bool {
    return $event['target'] === 'DESTROY_NATION'
        && $event['condition'] === Json::encode(["RemainNation", "<=", 2])
        && $event['action'] === Json::encode([
            ["FinishNationBetting", $bettingID],
            ["DeleteEvent"],
        ]);
};
$beforeEventIds = array_flip(array_map(
    static fn (array $event): string => (string)$event['id'],
    $before['event'],
));
$afterOpenExistingEvents = array_values(array_filter(
    $afterOpen['event'],
    static function (array $event) use ($beforeEventIds): bool {
        return isset($beforeEventIds[(string)$event['id']]);
    },
));
$afterOpenEventDelta = array_values(array_filter(
    $afterOpen['event'],
    static function (array $event) use ($beforeEventIds): bool {
        return !isset($beforeEventIds[(string)$event['id']]);
    },
));
hardAssert(
    count($afterOpen['event']) === count($before['event']) + 1 &&
        $afterOpenExistingEvents === $before['event'] &&
        count($afterOpenEventDelta) === 1 &&
        $matchesOpenedFinishEvent($afterOpenEventDelta[0]),
    'open did not add exactly one matching finish event',
);
hardAssert($afterFinish['event'] === $before['event'], 'DeleteEvent did not restore prior finish events');

$fixture = [
    'oracle' => 'legacy/devsam-core',
    'source' => [
        'open' => 'hwe/sammo/Event/Action/OpenNationBetting.php:22-153',
        'finish' => 'hwe/sammo/Event/Action/FinishNationBetting.php:14-73',
        'reward' => 'hwe/sammo/Betting.php:348-434',
    ],
    'input' => [
        'year' => 184,
        'month' => 5,
        'nationCnt' => 2,
        'bonusPoint' => 100,
        'generalID' => $generalID,
        'ownerID' => $ownerID,
        'betAmount' => 200,
        'winnerNations' => $winnerNations,
        'winnerTypes' => $winnerTypes,
        'fixtureTurntime' => $fixtureTurntime,
        'fixtureServerId' => $fixtureServerId,
    ],
    'openResult' => $openResult,
    'bettingID' => $bettingID,
    'before' => $before,
    'afterOpen' => $afterOpen,
    'afterBet' => $afterBet,
    'afterFinish' => $afterFinish,
    'finishedMaster' => $finishedInfo->toArray(),
    'nondeterministicColumnsExcluded' => [
        'message.time' => 'OpenNationBetting uses new DateTime(); mailbox/body/order are retained',
        'user_record.date' => 'UserLogger wall clock; text/category/order are retained',
    ],
];

if (!is_dir(dirname($out))) {
    mkdir(dirname($out), 0775, true);
}
file_put_contents(
    $out,
    Json::encode($fixture, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES | JSON_PRETTY_PRINT) . "\n"
);
fwrite(STDERR, 'nation-betting capture wrote ' . basename($out) . "\n");
