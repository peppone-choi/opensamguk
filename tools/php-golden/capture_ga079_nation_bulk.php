<?php
declare(strict_types=1);

namespace sammo;

// The legacy PHP 8.3 bootstrap routes deprecation/warning noise through an
// error handler that asks Session for request metadata while Session itself is
// still being autoloaded. Match the other headless golden captures and keep
// those non-semantic levels out of the oracle process.
error_reporting(E_ALL & ~E_DEPRECATED & ~E_NOTICE & ~E_WARNING & ~E_USER_DEPRECATED & ~E_STRICT);
$_SERVER['REMOTE_ADDR'] ??= '127.0.0.1';

const GA079_PRECRASH_SCHEMA = 'ga079-nation-bulk-php-precrash-v1';
const GA079_CRASH_BEFORE_SCHEMA = 'ga079-nation-bulk-php-crash-before-v1';
const GA079_FINAL_SCHEMA = 'ga079-nation-bulk-php-v1';
const GA079_LEGACY_SHA = '4de7ebec17a722d516608dbb987467f1a451dada';
const GA079_FLOOR = 100;
const GA079_ACTION = '휴식';
const GA079_DB_FAULT_MARKER = 'GA079_DB_FAILURE_BEFORE_GENERAL';
const GA079_DB_FAULT_TRIGGER = 'ga079_fail_general_before_update';

const GA079_SOURCES = [
    [
        'path' => 'hwe/sammo/API/NationCommand/ReserveBulkCommand.php',
        'lines' => '16-75',
        'sha256' => '0a1b1a6324a64b7609d9f01d51f777aa1da0f7cdfda60813c6bf799d0b90c214',
    ],
    [
        'path' => 'hwe/func_command.php',
        'lines' => '222-240, 402-496',
        'sha256' => 'b5c43f408db95dcf65189524637341737451b5beae11b994458523b725d61a4d',
    ],
    [
        'path' => 'hwe/sammo/LazyVarUpdater.php',
        'lines' => '55-65',
        'sha256' => '302bf3c64fa4c559297a27ea4307b66e6365ab8885d12521a75bfab69c96b5a5',
    ],
    [
        'path' => 'hwe/sammo/General.php',
        'lines' => '704-725',
        'sha256' => '0ffd8d73bfe208b943051a5a1f99b550fd3ae90f024f645d455c22ca13ec72a0',
    ],
    [
        'path' => 'vendor/sergeytsalkov/meekrodb/db.class.php',
        'lines' => '919-961',
        'sha256' => '0a393a368d5b0d49bd6cc68474582b029675ff0265878420567e241999f31685',
    ],
    [
        'path' => 'hwe/sammo/Command/Nation/휴식.php',
        'lines' => '10-39',
        'sha256' => '9509838a778f1ab7bd01771efe0452ac131b99f9d101a8047dafc3cdee6cd9df',
    ],
    [
        'path' => 'hwe/sammo/GameConstBase.php',
        'lines' => '154-156, 378-381',
        'sha256' => '72a82cb70279f8f4ec08c900513f069ff93e54665e6388a3f70ef28a25e4f502',
    ],
    [
        'path' => 'hwe/sql/schema.sql',
        'lines' => '4-85, 142-153',
        'sha256' => 'a2957d84ce869c51acf8b8c2090591624496b1425edd5085f4f0aae199bc39ac',
    ],
];

function ga079Usage(): void
{
    echo <<<'USAGE'
Usage: php tools/php-golden/capture_ga079_nation_bulk.php --mode=<mode> [options]

Internal modes (normally invoked by run_ga079_nation_bulk.sh):
  --mode=precrash --out=/out/precrash.json
  --mode=crash-child --handshake=/out/handshake.txt --crash-before=/out/crash-before.json
  --mode=finalize --precrash=/out/precrash.json --crash-before=/out/crash-before.json \
      --parent-signal=SIGKILL --out=/out/final.json

All output paths must be under /out. The script fails closed on a legacy source
revision/hash mismatch, non-Aria target tables, non-autocommit DB state, a missing
scenario_1010 officer/ring row, or any matrix assertion failure.

Focused filesystem check (no DB bootstrap):
  --self-test-out-paths
USAGE;
}

function ga079Assert(bool $condition, string $message): void
{
    if (!$condition) {
        throw new \RuntimeException($message);
    }
}

function ga079RepoRoot(): string
{
    $root = realpath(__DIR__ . '/../..');
    ga079Assert($root !== false, 'cannot resolve repository root');
    return $root;
}

function ga079Options(): array
{
    foreach (array_slice($_SERVER['argv'], 1) as $arg) {
        ga079Assert(
            preg_match('/^--(?:mode|out|handshake|crash-before|precrash|parent-signal)=/', $arg) === 1,
            "unsupported argument: {$arg}"
        );
    }

    $opts = getopt('', [
        'mode:',
        'out:',
        'handshake:',
        'crash-before:',
        'precrash:',
        'parent-signal:',
    ]);

    return [
        'mode' => $opts['mode'] ?? null,
        'out' => $opts['out'] ?? null,
        'handshake' => $opts['handshake'] ?? null,
        'crash-before' => $opts['crash-before'] ?? null,
        'precrash' => $opts['precrash'] ?? null,
        'parent-signal' => $opts['parent-signal'] ?? null,
    ];
}

function ga079RequiredOption(array $options, string $key): string
{
    $value = $options[$key] ?? null;
    ga079Assert(is_string($value) && $value !== '', "missing --{$key}=...");
    return $value;
}

function ga079OutPath(string $path): string
{
    $outRoot = realpath('/out');
    ga079Assert($outRoot === '/out' && is_dir($outRoot), 'output root must resolve exactly to /out');
    ga079Assert(dirname($path) === '/out', 'output path must be a direct /out basename');
    $basename = basename($path);
    ga079Assert(
        $path === '/out/' . $basename && preg_match('/\\A[A-Za-z0-9][A-Za-z0-9._-]*\\z/D', $basename) === 1,
        'output path must be a safe /out basename'
    );
    ga079Assert(!is_link($path), 'output path must not be a symlink');
    if (file_exists($path)) {
        $stat = lstat($path);
        ga079Assert(
            is_array($stat) && (($stat['mode'] & 0170000) === 0100000) && (int)$stat['nlink'] === 1,
            'existing output path must be an unlinked regular file'
        );
        $resolved = realpath($path);
        ga079Assert($resolved !== false && dirname($resolved) === '/out', 'existing output path escapes /out');
    }
    return $path;
}

function ga079AtomicWrite(string $path, string $contents, string $label): void
{
    $path = ga079OutPath($path);
    $tmpPath = $path . '.tmp.' . getmypid();
    ga079Assert(!file_exists($tmpPath) && !is_link($tmpPath), "temporary {$label} path already exists");
    $handle = fopen($tmpPath, 'x');
    ga079Assert($handle !== false, "cannot create temporary {$label}");
    try {
        $written = fwrite($handle, $contents);
        ga079Assert($written === strlen($contents), "cannot write {$label}");
        ga079Assert(fflush($handle), "cannot flush {$label}");
    } finally {
        fclose($handle);
    }
    ga079Assert(rename($tmpPath, $path), "cannot publish {$label}");
}

function ga079WriteCanonical(string $path, array $value): void
{
    $encoded = json_encode(
        $value,
        JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES | JSON_THROW_ON_ERROR
    ) . "\n";
    ga079AtomicWrite($path, $encoded, 'capture output');
}

function ga079ReadCanonical(string $path): array
{
    $path = ga079OutPath($path);
    ga079Assert(is_file($path), 'required capture intermediate is missing');
    $decoded = json_decode((string)file_get_contents($path), true, 512, JSON_THROW_ON_ERROR);
    ga079Assert(is_array($decoded), 'capture intermediate must be a JSON object');
    return $decoded;
}

function ga079ExpectOutPathRejection(string $path): void
{
    try {
        ga079OutPath($path);
    } catch (\RuntimeException) {
        return;
    }
    throw new \RuntimeException("out-path self-test accepted {$path}");
}

function ga079OutPathSelfTest(): void
{
    $validPath = '/out/ga079-out-path-self-test.json';
    ga079Assert(ga079OutPath($validPath) === $validPath, 'out-path self-test rejected a valid basename');
    ga079ExpectOutPathRejection('/out/../work/ga079-escape.json');
    ga079ExpectOutPathRejection('/out/nested/ga079-escape.json');

    $symlinkPath = '/out/ga079-out-path-self-test-link';
    ga079Assert(!file_exists($symlinkPath) && !is_link($symlinkPath), 'stale out-path self-test symlink exists');
    ga079Assert(symlink('/work', $symlinkPath), 'cannot create out-path self-test symlink');
    try {
        ga079ExpectOutPathRejection($symlinkPath);
    } finally {
        ga079Assert(unlink($symlinkPath), 'cannot remove out-path self-test symlink');
    }
    fwrite(STDOUT, "ga079 out-path self-test passed\n");
}

function ga079SourceEvidence(): array
{
    $legacyRoot = ga079RepoRoot() . '/legacy/devsam-core';
    $output = [];
    $status = 1;
    exec('git -C ' . escapeshellarg($legacyRoot) . ' rev-parse HEAD 2>/dev/null', $output, $status);
    ga079Assert($status === 0 && count($output) === 1, 'cannot read legacy revision');
    ga079Assert(trim($output[0]) === GA079_LEGACY_SHA, 'legacy revision differs from GA-079 oracle');

    $evidence = [];
    foreach (GA079_SOURCES as $source) {
        $sourcePath = $legacyRoot . '/' . $source['path'];
        ga079Assert(is_file($sourcePath), "missing legacy source {$source['path']}");
        $actualHash = hash_file('sha256', $sourcePath);
        ga079Assert($actualHash === $source['sha256'], "legacy source hash drift: {$source['path']}");
        $evidence[] = [
            'path' => 'legacy/devsam-core/' . $source['path'],
            'lines' => $source['lines'],
            'sha256' => $actualHash,
        ];
    }
    return $evidence;
}

function ga079TableEngines($db): array
{
    $rows = $db->query(
        "SELECT table_name, engine\n" .
        "FROM information_schema.tables\n" .
        "WHERE table_schema = DATABASE()\n" .
        "  AND table_name IN ('general', 'nation_turn')\n" .
        'ORDER BY table_name ASC'
    );
    $engines = [];
    foreach ($rows as $row) {
        $engines[(string)$row['table_name']] = (string)$row['engine'];
    }
    ga079Assert($engines === ['general' => 'Aria', 'nation_turn' => 'Aria'], 'GA-079 requires Aria general and nation_turn tables');
    return $engines;
}

function ga079Metadata($db): array
{
    $autocommit = (int)$db->queryFirstField('SELECT @@autocommit');
    ga079Assert($autocommit === 1, 'GA-079 capture requires autocommit=1');

    return [
        'oracle' => [
            'legacySha' => GA079_LEGACY_SHA,
            'sourceEvidence' => ga079SourceEvidence(),
        ],
        'runtime' => [
            'phpVersion' => PHP_VERSION,
            'mariadbVersion' => (string)$db->queryFirstField('SELECT VERSION()'),
            'autocommit' => $autocommit,
            'tableEngines' => ga079TableEngines($db),
        ],
        'capture' => [
            'scenario' => 1010,
            'action' => GA079_ACTION,
            'killturnFloor' => GA079_FLOOR,
            'disposableReachabilityAdjustments' => [
                'selectExistingOfficerWithNationTurnRow',
                'setOnlyActorNpcKillturnPenalty',
                'setOnlyGameEnvKillturnFloor',
                'resetOnlyObservedNationTurnSlots',
            ],
            'excludedFromArtifact' => [
                'timestamps',
                'pids',
                'credentials',
                'durations',
                'hiddenSeeds',
            ],
        ],
    ];
}

function ga079FindActor($db): array
{
    $row = $db->queryFirstRow(
        'SELECT g.no, g.nation, g.officer_level ' .
        'FROM general AS g ' .
        'INNER JOIN nation_turn AS nt ' .
        '  ON nt.nation_id = g.nation AND nt.officer_level = g.officer_level ' .
        'WHERE g.nation <> 0 AND g.officer_level >= 5 ' .
        'ORDER BY g.no ASC ' .
        'LIMIT 1'
    );
    ga079Assert(is_array($row), 'scenario_1010 has no reachable nation officer/ring row');
    return [
        'id' => (int)$row['no'],
        'nationId' => (int)$row['nation'],
        'officerLevel' => (int)$row['officer_level'],
    ];
}

function ga079ResetRing($db, array $actor, array $slots): void
{
    sort($slots, SORT_NUMERIC);
    foreach ($slots as $slot) {
        ga079Assert($slot >= 0 && $slot < 3, 'GA-079 capture slot outside narrow baseline');
        $rowCount = (int)$db->queryFirstField(
            'SELECT COUNT(*) FROM nation_turn WHERE nation_id=%i AND officer_level=%i AND turn_idx=%i',
            $actor['nationId'],
            $actor['officerLevel'],
            $slot
        );
        ga079Assert($rowCount === 1, "expected exactly one reachable nation_turn row for slot {$slot}");
        $db->update('nation_turn', [
            'action' => "ga079_before_{$slot}",
            'arg' => '{"slot":' . $slot . '}',
            'brief' => "ga079-before-{$slot}",
        ], 'nation_id=%i AND officer_level=%i AND turn_idx=%i', $actor['nationId'], $actor['officerLevel'], $slot);
    }
}

function ga079ReadState($db, array $actor, array $slots): array
{
    sort($slots, SORT_NUMERIC);
    $ring = [];
    foreach ($slots as $slot) {
        $row = $db->queryFirstRow(
            'SELECT action, arg, brief FROM nation_turn WHERE nation_id=%i AND officer_level=%i AND turn_idx=%i',
            $actor['nationId'],
            $actor['officerLevel'],
            $slot
        );
        ga079Assert(is_array($row), "nation_turn row disappeared for slot {$slot}");
        $ring[] = [
            'turnIndex' => $slot,
            'action' => (string)$row['action'],
            'arg' => (string)$row['arg'],
            'brief' => (string)$row['brief'],
        ];
    }

    $general = $db->queryFirstRow('SELECT npc, killturn FROM general WHERE no=%i', $actor['id']);
    ga079Assert(is_array($general), 'capture actor disappeared');
    return [
        'ring' => $ring,
        'general' => [
            'npc' => (int)$general['npc'],
            'killturn' => (int)$general['killturn'],
        ],
    ];
}

function ga079ExpectedBaseline(array $slots, int $npc, int $killturn): array
{
    sort($slots, SORT_NUMERIC);
    $ring = [];
    foreach ($slots as $slot) {
        $ring[] = [
            'turnIndex' => $slot,
            'action' => "ga079_before_{$slot}",
            'arg' => '{"slot":' . $slot . '}',
            'brief' => "ga079-before-{$slot}",
        ];
    }
    return [
        'ring' => $ring,
        'general' => [
            'npc' => $npc,
            'killturn' => $killturn,
        ],
    ];
}

function ga079AssertState(array $actual, array $expected, string $message): void
{
    ga079Assert($actual === $expected, $message);
}

function ga079AssertReservedSlot(array $state, int $slot): void
{
    foreach ($state['ring'] as $row) {
        if ($row['turnIndex'] !== $slot) {
            continue;
        }
        ga079Assert(
            $row === [
                'turnIndex' => $slot,
                'action' => GA079_ACTION,
                'arg' => '{}',
                'brief' => GA079_ACTION,
            ],
            "slot {$slot} did not contain the real 휴식 reservation"
        );
        return;
    }
    ga079Assert(false, "slot {$slot} missing from observed state");
}

function ga079PrepareCase($db, int $npc, int $killturn, array $slots): array
{
    $actor = ga079FindActor($db);
    $gameStorage = KVStorage::getStorage($db, 'game_env');
    $gameStorage->setValue('killturn', GA079_FLOOR);
    $db->update('general', [
        'npc' => $npc,
        'killturn' => $killturn,
        'penalty' => '{}',
    ], 'no=%i', $actor['id']);
    ga079ResetRing($db, $actor, $slots);

    $observed = ga079ReadState($db, $actor, $slots);
    ga079AssertState(
        $observed,
        ga079ExpectedBaseline($slots, $npc, $killturn),
        'disposable reachability adjustment did not land exactly'
    );
    return $actor;
}

function ga079ValidChild(int $turnIndex): array
{
    return [
        'action' => GA079_ACTION,
        'turnList' => [$turnIndex],
        'arg' => [],
    ];
}

function ga079Api(array $payload): \sammo\API\NationCommand\ReserveBulkCommand
{
    return new \sammo\API\NationCommand\ReserveBulkCommand(ga079RepoRoot(), $payload);
}

function ga079SessionForActor(int $actorGeneralId): Session
{
    return new class($actorGeneralId) extends DummySession {
        private int $actorGeneralId;

        public function __construct(int $actorGeneralId)
        {
            parent::__construct();
            $this->actorGeneralId = $actorGeneralId;
        }

        public function __get(string $name)
        {
            if ($name === 'generalID') {
                return $this->actorGeneralId;
            }
            if ($name === 'generalName') {
                return 'GA079CaptureActor';
            }
            return parent::__get($name);
        }
    };
}

function ga079NormalizeMutation(string $query): ?string
{
    if (preg_match('/^\s*UPDATE\s+`?nation_turn`?(?:\s|$)/i', $query) === 1) {
        return 'UPDATE nation_turn';
    }
    if (preg_match('/^\s*UPDATE\s+`?general`?(?:\s|$)/i', $query) === 1) {
        return 'UPDATE general';
    }
    return null;
}

function ga079AddStatementRecorder($db, array &$statementOrder): int
{
    return $db->addHook('run_success', static function (array $hook) use (&$statementOrder): void {
        $normalized = ga079NormalizeMutation((string)$hook['query']);
        if ($normalized !== null) {
            $statementOrder[] = $normalized;
        }
    });
}

function ga079AddFailedStatementRecorder($db, array &$statementOrder): int
{
    return $db->addHook('run_failed', static function (array $hook) use (&$statementOrder) {
        $normalized = ga079NormalizeMutation((string)$hook['query']);
        if ($normalized !== null) {
            $statementOrder[] = "{$normalized} (failed)";
        }
        return null;
    });
}

function ga079TriggerCount($db, string $triggerName): int
{
    return (int)$db->queryFirstField(
        'SELECT COUNT(*) FROM information_schema.triggers WHERE trigger_schema=DATABASE() AND trigger_name=%s',
        $triggerName
    );
}

function ga079CreateBeforeGeneralFaultTrigger($db, int $actorId): void
{
    ga079Assert(ga079TriggerCount($db, GA079_DB_FAULT_TRIGGER) === 0, 'GA-079 fault trigger name is already in use');
    $db->query(
        'CREATE TRIGGER `' . GA079_DB_FAULT_TRIGGER . '` ' .
        'BEFORE UPDATE ON `general` FOR EACH ROW ' .
        'BEGIN ' .
        'IF NEW.`no` = ' . $actorId . ' THEN ' .
        "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '" . GA079_DB_FAULT_MARKER . "'; " .
        'END IF; ' .
        'END'
    );
    ga079Assert(ga079TriggerCount($db, GA079_DB_FAULT_TRIGGER) === 1, 'GA-079 fault trigger was not created');
}

function ga079DropBeforeGeneralFaultTrigger($db): void
{
    $db->query('DROP TRIGGER `' . GA079_DB_FAULT_TRIGGER . '`');
    ga079Assert(ga079TriggerCount($db, GA079_DB_FAULT_TRIGGER) === 0, 'GA-079 fault trigger cleanup failed');
}

function ga079LaunchWithOrder($db, int $actorId, array $payload): array
{
    $api = ga079Api($payload);
    ga079Assert($api->validateArgs() === null, 'valid matrix payload unexpectedly failed whole validation');

    $statementOrder = [];
    $hookIndex = ga079AddStatementRecorder($db, $statementOrder);
    try {
        $response = $api->launch(ga079SessionForActor($actorId), null, null);
    } finally {
        $db->removeHook('run_success', $hookIndex);
    }

    return [
        'response' => $response,
        'statementOrder' => $statementOrder,
    ];
}

function ga079AssertSuccessResponse($response): void
{
    ga079Assert(is_array($response), 'nation bulk success did not return an array');
    ga079Assert(($response['result'] ?? null) === true, 'nation bulk success result flag drifted');
    ga079Assert(($response['briefList'] ?? null) === [0 => GA079_ACTION], 'nation bulk success briefList drifted');
    ga079Assert(($response['reason'] ?? null) === 'success', 'nation bulk success reason drifted');
}

function ga079CaseWholePayloadValidation($db): array
{
    $slots = [0, 1];
    $actor = ga079PrepareCase($db, 0, GA079_FLOOR - 7, $slots);
    $before = ga079ReadState($db, $actor, $slots);
    $api = ga079Api([
        ga079ValidChild(0),
        ['turnList' => [1]],
    ]);
    $validation = $api->validateArgs();
    ga079Assert(is_string($validation) && str_starts_with($validation, '1:'), 'child 1 whole-payload validation did not fail');
    $after = ga079ReadState($db, $actor, $slots);
    ga079AssertState($after, $before, 'whole-payload validation allowed child 0 mutation');

    return [
        'id' => 'whole_payload_validation_failure_child_1',
        'response' => ['validationFailure' => $validation],
        'before' => $before,
        'after' => $after,
        'assertions' => [
            'launchSkippedAfterValidationFailure' => true,
            'child0Unchanged' => true,
        ],
    ];
}

function ga079CaseUserBelowFloor($db): array
{
    $slots = [0];
    $actor = ga079PrepareCase($db, 0, GA079_FLOOR - 7, $slots);
    $before = ga079ReadState($db, $actor, $slots);
    $run = ga079LaunchWithOrder($db, $actor['id'], [ga079ValidChild(0)]);
    ga079AssertSuccessResponse($run['response']);
    $after = ga079ReadState($db, $actor, $slots);
    ga079AssertReservedSlot($after, 0);
    ga079Assert($after['general'] === ['npc' => 0, 'killturn' => GA079_FLOOR], 'user below floor did not refill killturn');
    ga079Assert($run['statementOrder'] === ['UPDATE nation_turn', 'UPDATE general'], 'required nation_turn then general statement order drifted');

    return [
        'id' => 'user_below_floor_refills_killturn',
        'response' => $run['response'],
        'statementOrder' => $run['statementOrder'],
        'before' => $before,
        'after' => $after,
        'assertions' => [
            'killturnTransitionsFromFloorMinus7ToFloor' => true,
            'nationTurnPrecedesGeneral' => true,
        ],
    ];
}

function ga079CaseUserAboveFloor($db): array
{
    $slots = [0];
    $actor = ga079PrepareCase($db, 0, GA079_FLOOR + 7, $slots);
    $before = ga079ReadState($db, $actor, $slots);
    $run = ga079LaunchWithOrder($db, $actor['id'], [ga079ValidChild(0)]);
    ga079AssertSuccessResponse($run['response']);
    $after = ga079ReadState($db, $actor, $slots);
    ga079AssertReservedSlot($after, 0);
    ga079Assert($after['general'] === ['npc' => 0, 'killturn' => GA079_FLOOR + 7], 'user above floor was changed');
    ga079Assert($run['statementOrder'] === ['UPDATE nation_turn'], 'user above floor emitted a general UPDATE');

    return [
        'id' => 'user_above_floor_does_not_update_general',
        'response' => $run['response'],
        'statementOrder' => $run['statementOrder'],
        'before' => $before,
        'after' => $after,
        'assertions' => [
            'killturnRemainsFloorPlus7' => true,
            'noGeneralUpdate' => true,
        ],
    ];
}

function ga079CaseNpc($db): array
{
    $slots = [0];
    $actor = ga079PrepareCase($db, 2, GA079_FLOOR - 7, $slots);
    $before = ga079ReadState($db, $actor, $slots);
    $run = ga079LaunchWithOrder($db, $actor['id'], [ga079ValidChild(0)]);
    ga079AssertSuccessResponse($run['response']);
    $after = ga079ReadState($db, $actor, $slots);
    ga079AssertReservedSlot($after, 0);
    ga079Assert($after['general'] === ['npc' => 2, 'killturn' => GA079_FLOOR - 7], 'npc reservation changed killturn');
    ga079Assert($run['statementOrder'] === ['UPDATE nation_turn'], 'npc reservation emitted a general UPDATE');

    return [
        'id' => 'npc_skips_killturn_refresh',
        'response' => $run['response'],
        'statementOrder' => $run['statementOrder'],
        'before' => $before,
        'after' => $after,
        'assertions' => [
            'ringChanged' => true,
            'killturnUnchanged' => true,
            'noGeneralUpdate' => true,
        ],
    ];
}

function ga079CaseScalarPrefixFailure($db, string $kind, array $failedChild, string $expectedReason): array
{
    $slots = [0, 1, 2];
    $actor = ga079PrepareCase($db, 0, GA079_FLOOR + 7, $slots);
    $before = ga079ReadState($db, $actor, $slots);
    $run = ga079LaunchWithOrder($db, $actor['id'], [
        ga079ValidChild(0),
        $failedChild,
        ga079ValidChild(2),
    ]);
    ga079Assert($run['response'] === $expectedReason, "{$kind} scalar reason drifted");
    $after = ga079ReadState($db, $actor, $slots);
    ga079AssertReservedSlot($after, 0);
    ga079Assert($after['ring'][1] === $before['ring'][1], "{$kind} mutated failed child ring slot");
    ga079Assert($after['ring'][2] === $before['ring'][2], "{$kind} evaluated child 2");
    ga079Assert($after['general'] === $before['general'], "{$kind} changed an above-floor user general");
    ga079Assert($run['statementOrder'] === ['UPDATE nation_turn'], "{$kind} emitted an unexpected mutation order");

    return [
        'id' => "scalar_prefix_failure_{$kind}",
        'response' => $run['response'],
        'statementOrder' => $run['statementOrder'],
        'before' => $before,
        'after' => $after,
        'assertions' => [
            'child0PrefixPersists' => true,
            'child2Untouched' => true,
            'exactScalarReason' => true,
        ],
    ];
}

function ga079CaseStructuredPrefixFailure($db): array
{
    $slots = [0, 1, 2];
    $actor = ga079PrepareCase($db, 0, GA079_FLOOR + 7, $slots);
    $before = ga079ReadState($db, $actor, $slots);
    $run = ga079LaunchWithOrder($db, $actor['id'], [
        ga079ValidChild(0),
        ga079ValidChild(12),
        ga079ValidChild(2),
    ]);
    $expectedReason = '올바른 턴이 아닙니다. : 12';
    $response = $run['response'];
    ga079Assert(is_array($response), 'structured failure did not return an array');
    ga079Assert(($response['result'] ?? null) === false, 'structured failure result flag drifted');
    ga079Assert(($response['briefList'] ?? null) === [0 => GA079_ACTION], 'structured failure briefList drifted');
    ga079Assert(($response['errorIdx'] ?? null) === 1, 'structured failure errorIdx drifted');
    ga079Assert(($response['reason'] ?? null) === $expectedReason, 'structured failure reason drifted');
    $after = ga079ReadState($db, $actor, $slots);
    ga079AssertReservedSlot($after, 0);
    ga079Assert($after['ring'][1] === $before['ring'][1], 'structured failure mutated rejected child slot');
    ga079Assert($after['ring'][2] === $before['ring'][2], 'structured failure evaluated child 2');
    ga079Assert($after['general'] === $before['general'], 'structured failure changed above-floor actor');
    ga079Assert($run['statementOrder'] === ['UPDATE nation_turn'], 'structured failure emitted unexpected mutation order');

    return [
        'id' => 'structured_prefix_failure_turn_12',
        'response' => $response,
        'statementOrder' => $run['statementOrder'],
        'before' => $before,
        'after' => $after,
        'assertions' => [
            'resultFalse' => true,
            'briefListPreservesPrefix' => true,
            'errorIdxIs1' => true,
            'exactReason' => true,
            'child2Untouched' => true,
        ],
    ];
}

function ga079CaseCatchableFault($db): array
{
    $slots = [0];
    $actor = ga079PrepareCase($db, 0, GA079_FLOOR - 7, $slots);
    $before = ga079ReadState($db, $actor, $slots);
    $api = ga079Api([ga079ValidChild(0)]);
    ga079Assert($api->validateArgs() === null, 'fault payload unexpectedly failed validation');

    $statementOrder = [];
    $recorderHook = ga079AddStatementRecorder($db, $statementOrder);
    $failedRecorderHook = ga079AddFailedStatementRecorder($db, $statementOrder);

    $exception = null;
    $triggerCreated = false;
    $triggerRemoved = false;
    try {
        ga079CreateBeforeGeneralFaultTrigger($db, $actor['id']);
        $triggerCreated = true;
        $api->launch(ga079SessionForActor($actor['id']), null, null);
        ga079Assert(false, 'catchable DB fault did not interrupt the general update');
    } catch (\Throwable $error) {
        ga079Assert(str_contains($error->getMessage(), GA079_DB_FAULT_MARKER), 'unexpected catchable DB failure');
        $exception = [
            'class' => get_class($error),
            'identity' => GA079_DB_FAULT_MARKER,
            'code' => $error->getCode(),
        ];
    } finally {
        if ($triggerCreated) {
            ga079DropBeforeGeneralFaultTrigger($db);
            $triggerRemoved = true;
        }
        $db->removeHook('run_failed', $failedRecorderHook);
        $db->removeHook('run_success', $recorderHook);
    }

    ga079Assert($triggerCreated && $triggerRemoved, 'catchable DB trigger lifecycle was incomplete');
    $after = ga079ReadState($db, $actor, $slots);
    ga079AssertReservedSlot($after, 0);
    ga079Assert($after['general'] === $before['general'], 'catchable fault advanced killturn after ring');
    ga079Assert($statementOrder === ['UPDATE nation_turn', 'UPDATE general (failed)'], 'catchable fault trace drifted');

    return [
        'id' => 'catchable_db_failure_after_ring_before_general',
        'exception' => $exception,
        'statementOrder' => $statementOrder,
        'before' => $before,
        'after' => $after,
        'assertions' => [
            'nationTurnPersists' => true,
            'killturnRemainsOld' => true,
            'failedGeneralStageObserved' => true,
            'triggerRemovedInFinally' => true,
        ],
    ];
}

function ga079WriteHandshake(string $path): void
{
    $path = ga079OutPath($path);
    ga079Assert(!file_exists($path), 'stale crash handshake exists');
    $contents = 'pid=' . getmypid() . "\n" . 'stage=after_nation_turn_success' . "\n";
    ga079AtomicWrite($path, $contents, 'crash handshake');
}

function ga079RunCrashChild($db, string $handshakePath, string $crashBeforePath): void
{
    $slots = [0];
    $actor = ga079PrepareCase($db, 0, GA079_FLOOR - 7, $slots);
    $before = ga079ReadState($db, $actor, $slots);
    ga079AssertState($before, ga079ExpectedBaseline($slots, 0, GA079_FLOOR - 7), 'crash child pre-state drifted');
    ga079WriteCanonical($crashBeforePath, [
        'schema' => GA079_CRASH_BEFORE_SCHEMA,
        'before' => $before,
    ]);

    $api = ga079Api([ga079ValidChild(0)]);
    ga079Assert($api->validateArgs() === null, 'crash child payload unexpectedly failed validation');
    $fired = 0;
    $hookIndex = $db->addHook('run_success', static function (array $hook) use (&$fired, $handshakePath): void {
        if (ga079NormalizeMutation((string)$hook['query']) !== 'UPDATE nation_turn') {
            return;
        }
        $fired++;
        ga079Assert($fired === 1, 'crash hook fired more than once');
        ga079WriteHandshake($handshakePath);
        while (true) {
            usleep(100000);
        }
    });

    try {
        $api->launch(ga079SessionForActor($actor['id']), null, null);
        ga079Assert(false, 'crash child returned without parent SIGKILL');
    } finally {
        $db->removeHook('run_success', $hookIndex);
    }
}

function ga079CrashCaseAfterReconnect($db, string $crashBeforePath): array
{
    $crashBefore = ga079ReadCanonical($crashBeforePath);
    ga079Assert(($crashBefore['schema'] ?? null) === GA079_CRASH_BEFORE_SCHEMA, 'crash-before schema mismatch');
    ga079Assert(is_array($crashBefore['before'] ?? null), 'crash-before state missing');

    $slots = [0];
    $actor = ga079FindActor($db);
    $before = $crashBefore['before'];
    $expectedBefore = ga079ExpectedBaseline($slots, 0, GA079_FLOOR - 7);
    ga079AssertState($before, $expectedBefore, 'crash child did not observe the expected pre-state');
    $after = ga079ReadState($db, $actor, $slots);
    ga079AssertReservedSlot($after, 0);
    ga079Assert($after['general'] === $expectedBefore['general'], 'SIGKILL path advanced killturn after ring');

    return [
        'id' => 'true_process_crash_after_ring',
        'termination' => [
            'parentSignal' => 'SIGKILL',
            'hook' => 'run_success',
            'signalAfterSuccessfulStatement' => 'UPDATE nation_turn',
        ],
        'before' => $before,
        'after' => $after,
        'assertions' => [
            'handshakeOccurredAfterSuccessfulNationTurnUpdate' => true,
            'nationTurnPersistsAfterReconnect' => true,
            'killturnRemainsOldAfterReconnect' => true,
        ],
    ];
}

function ga079AssertArtifactExclusions(array $value): void
{
    $forbiddenKeys = [
        'timestamp' => true,
        'timestamps' => true,
        'pid' => true,
        'pids' => true,
        'password' => true,
        'credentials' => true,
        'duration' => true,
        'durations' => true,
        'hiddenseed' => true,
        'hiddenseeds' => true,
    ];
    foreach ($value as $key => $child) {
        if (is_string($key)) {
            ga079Assert(!isset($forbiddenKeys[strtolower($key)]), "forbidden artifact key {$key}");
        }
        if (is_array($child)) {
            ga079AssertArtifactExclusions($child);
        }
    }
}

function ga079RunPrecrash($db, string $outPath): void
{
    $cases = [
        ga079CaseWholePayloadValidation($db),
        ga079CaseUserBelowFloor($db),
        ga079CaseUserAboveFloor($db),
        ga079CaseNpc($db),
        ga079CaseScalarPrefixFailure(
            $db,
            'empty_turn_list',
            ['action' => GA079_ACTION, 'turnList' => [], 'arg' => []],
            '1: 턴이 입력되지 않았습니다'
        ),
        ga079CaseScalarPrefixFailure(
            $db,
            'unavailable_action',
            ['action' => 'ga079_unavailable', 'turnList' => [1], 'arg' => []],
            '1: 사용할 수 없는 커맨드입니다.'
        ),
        ga079CaseScalarPrefixFailure(
            $db,
            'non_array_arg',
            ['action' => GA079_ACTION, 'turnList' => [1], 'arg' => 'invalid'],
            '1: 올바른 arg 형태가 아닙니다.'
        ),
        ga079CaseStructuredPrefixFailure($db),
        ga079CaseCatchableFault($db),
    ];
    $payload = [
        'schema' => GA079_PRECRASH_SCHEMA,
        'metadata' => ga079Metadata($db),
        'cases' => $cases,
    ];
    ga079AssertArtifactExclusions($payload);
    ga079WriteCanonical($outPath, $payload);
}

function ga079Finalize($db, string $precrashPath, string $crashBeforePath, string $outPath, string $parentSignal): void
{
    ga079Assert($parentSignal === 'SIGKILL', 'finalize requires the bounded parent SIGKILL path');
    $precrash = ga079ReadCanonical($precrashPath);
    ga079Assert(($precrash['schema'] ?? null) === GA079_PRECRASH_SCHEMA, 'precrash schema mismatch');
    ga079Assert(is_array($precrash['metadata'] ?? null), 'precrash metadata missing');
    ga079Assert(is_array($precrash['cases'] ?? null) && count($precrash['cases']) === 9, 'precrash matrix count drifted');

    $cases = $precrash['cases'];
    $cases[] = ga079CrashCaseAfterReconnect($db, $crashBeforePath);
    $payload = [
        'schema' => GA079_FINAL_SCHEMA,
        'metadata' => $precrash['metadata'],
        'cases' => $cases,
    ];
    ga079AssertArtifactExclusions($payload);
    ga079WriteCanonical($outPath, $payload);
}

if (in_array('--help', $_SERVER['argv'], true)) {
    ga079Usage();
    exit(0);
}

if (in_array('--self-test-out-paths', $_SERVER['argv'], true)) {
    try {
        ga079Assert(count($_SERVER['argv']) === 2, '--self-test-out-paths accepts no other arguments');
        ga079OutPathSelfTest();
        exit(0);
    } catch (\Throwable $error) {
        fwrite(STDERR, 'ga079 out-path self-test failed: ' . $error->getMessage() . "\n");
        exit(1);
    }
}

try {
    $options = ga079Options();
    $mode = ga079RequiredOption($options, 'mode');
    require __DIR__ . '/_boot.php';
    $db = DB::db();

    if ($mode === 'precrash') {
        ga079RunPrecrash($db, ga079RequiredOption($options, 'out'));
        exit(0);
    }
    if ($mode === 'crash-child') {
        ga079RunCrashChild(
            $db,
            ga079RequiredOption($options, 'handshake'),
            ga079RequiredOption($options, 'crash-before')
        );
        exit(1);
    }
    if ($mode === 'finalize') {
        ga079Finalize(
            $db,
            ga079RequiredOption($options, 'precrash'),
            ga079RequiredOption($options, 'crash-before'),
            ga079RequiredOption($options, 'out'),
            ga079RequiredOption($options, 'parent-signal')
        );
        exit(0);
    }

    throw new \RuntimeException("unsupported mode: {$mode}");
} catch (\Throwable $error) {
    fwrite(STDERR, 'ga079 capture failed: ' . $error->getMessage() . "\n");
    exit(1);
}
