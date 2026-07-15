<?php
/**
 * Controlled native-shuffle evidence only, never a runtime parity golden.
 * mt_srand() is an experimental input absent from both production target paths.
 */

namespace sammo;

$devsamRoot = realpath(__DIR__ . '/../../legacy/devsam-core');
if ($devsamRoot === false) {
    fwrite(STDERR, "AMBIENT HARD-ASSERT FAILED: cannot locate legacy/devsam-core\n");
    exit(2);
}

require_once $devsamRoot . '/src/sammo/Util.php';

const RAISE_NPC_PLAN_MISS = 'RaiseNPCNation::Util::shuffle_assoc — native shuffle() uses an automatically seeded process-global RNG; devsam-core never calls mt_srand/srand, and neither the seed nor pre-call cursor is present in game state or RandUtilDrawRecorder.';
const TOURNAMENT_PLAN_MISS = 'triggerTournament::shuffle — native shuffle() is outside the monthly RandUtil; its automatically seeded process-global seed and pre-call cursor are not persisted or captured, so the selected type cannot be replayed byte-identically from the monthly seed.';

$opts = getopt('', ['out:', 'runtime-parity']);

function hardAssert(bool $condition, string $message): void
{
    if (!$condition) {
        fwrite(STDERR, "AMBIENT HARD-ASSERT FAILED: {$message}\n");
        exit(2);
    }
}

function captureRaiseNpcShuffle(int $seed, int $prefixMtRandDraws): array
{
    mt_srand($seed, MT_RAND_MT19937);
    for ($idx = 0; $idx < $prefixMtRandDraws; $idx++) {
        mt_rand();
    }

    $cities = [11 => 'a', 22 => 'b', 33 => 'c', 44 => 'd', 55 => 'e', 66 => 'f'];
    $inputKeys = array_keys($cities);
    Util::shuffle_assoc($cities);

    return [
        'inputKeys' => $inputKeys,
        'outputKeys' => array_keys($cities),
    ];
}

function captureTournamentShuffle(int $seed, int $prefixMtRandDraws): array
{
    mt_srand($seed, MT_RAND_MT19937);
    for ($idx = 0; $idx < $prefixMtRandDraws; $idx++) {
        mt_rand();
    }

    $pattern = [0, 0, 1, 2, 3];
    $inputPattern = $pattern;
    shuffle($pattern);
    $shuffledPattern = $pattern;
    $selectedType = array_pop($pattern);

    return [
        'inputPattern' => $inputPattern,
        'shuffledPattern' => $shuffledPattern,
        'remainingPattern' => $pattern,
        'selectedType' => $selectedType,
    ];
}

function hasExplicitNativeSeed(string $source): bool
{
    return preg_match('/(?<![A-Za-z0-9_])(?:mt_srand|srand)\s*\(/', $source) === 1;
}

function validateProductionSources(string $devsamRoot): void
{
    $raiseSource = file_get_contents($devsamRoot . '/hwe/sammo/Event/Action/RaiseNPCNation.php');
    $funcSource = file_get_contents($devsamRoot . '/hwe/func.php');
    hardAssert($raiseSource !== false, 'cannot read RaiseNPCNation.php');
    hardAssert($funcSource !== false, 'cannot read func.php');
    hardAssert(str_contains($raiseSource, 'Util::shuffle_assoc($emptyCities);'), 'RaiseNPCNation shuffle anchor missing');
    hardAssert(str_contains($funcSource, 'shuffle($tnmt_pattern);'), 'triggerTournament shuffle anchor missing');
    hardAssert(!hasExplicitNativeSeed($raiseSource), 'RaiseNPCNation.php now explicitly seeds native RNG; quarantine must be reviewed');
    hardAssert(!hasExplicitNativeSeed($funcSource), 'func.php now explicitly seeds native RNG; quarantine must be reviewed');

    $seeders = [];
    $iterator = new \RecursiveIteratorIterator(new \RecursiveDirectoryIterator($devsamRoot));
    foreach ($iterator as $file) {
        $path = $file->getPathname();
        if (!$file->isFile() || $file->getExtension() !== 'php' || str_contains($path, DIRECTORY_SEPARATOR . 'vendor' . DIRECTORY_SEPARATOR)) {
            continue;
        }
        $source = file_get_contents($path);
        if ($source !== false && hasExplicitNativeSeed($source)) {
            $seeders[] = substr($path, strlen($devsamRoot) + 1);
        }
    }
    hardAssert($seeders === [], 'production native RNG seeder found: ' . implode(', ', $seeders));
}

function buildProof(string $devsamRoot): array
{
    validateProductionSources($devsamRoot);

    $cases = [];
    foreach ([1, 777, 12345] as $seed) {
        foreach ([0, 1, 7] as $prefixMtRandDraws) {
            $cases[] = [
                'seed' => $seed,
                'prefixMtRandDraws' => $prefixMtRandDraws,
                'raiseNpcNation' => captureRaiseNpcShuffle($seed, $prefixMtRandDraws),
                'triggerTournament' => captureTournamentShuffle($seed, $prefixMtRandDraws),
            ];
        }
    }

    return [
        'oracle' => 'devsam-core PHP native shuffle controlled-state quarantine proof; NOT a runtime parity golden',
        'phpVersion' => PHP_VERSION,
        'nativeRng' => 'process-global MT19937 state pinned only for this experiment with mt_srand(seed, MT_RAND_MT19937)',
        'runtimeParity' => false,
        'disposition' => 'quarantined-with-proof',
        'sourceAnchors' => [
            'raiseNpcNation' => 'legacy/devsam-core/hwe/sammo/Event/Action/RaiseNPCNation.php:232 -> Util::shuffle_assoc',
            'shuffleAssoc' => 'legacy/devsam-core/src/sammo/Util.php:414-425 -> shuffle($keys)',
            'triggerTournament' => 'legacy/devsam-core/hwe/func.php:1296-1304 -> shuffle + array_pop',
        ],
        'explicitNativeSeederInTargetFiles' => false,
        'cases' => $cases,
        'planMiss' => [RAISE_NPC_PLAN_MISS, TOURNAMENT_PLAN_MISS],
    ];
}

if (array_key_exists('runtime-parity', $opts)) {
    validateProductionSources($devsamRoot);
    fwrite(STDERR, 'PLAN-MISS ' . RAISE_NPC_PLAN_MISS . "\n");
    fwrite(STDERR, 'PLAN-MISS ' . TOURNAMENT_PLAN_MISS . "\n");
    exit(2);
}

$first = buildProof($devsamRoot);
$second = buildProof($devsamRoot);
hardAssert($first === $second, 'same controlled seeds did not reproduce byte-identically in-process');

$seed12345 = array_values(array_filter($first['cases'], fn(array $case): bool => $case['seed'] === 12345));
$raiseOrders = array_map(fn(array $case): string => json_encode($case['raiseNpcNation']['outputKeys']), $seed12345);
$tournamentOrders = array_map(fn(array $case): string => json_encode($case['triggerTournament']['shuffledPattern']), $seed12345);
hardAssert(count(array_unique($raiseOrders)) > 1, 'prefix native draws did not perturb RaiseNPCNation order');
hardAssert(count(array_unique($tournamentOrders)) > 1, 'prefix native draws did not perturb tournament order');

$encoded = json_encode($first, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES | JSON_PRETTY_PRINT);
hardAssert($encoded !== false, 'JSON encoding failed');
$json = $encoded . "\n";

if (isset($opts['out'])) {
    $outPath = (string)$opts['out'];
    $outDir = dirname($outPath);
    if (!is_dir($outDir)) {
        mkdir($outDir, 0775, true);
    }
    file_put_contents($outPath, $json);
    fwrite(STDERR, "wrote {$outPath}\n");
} else {
    echo $json;
}
