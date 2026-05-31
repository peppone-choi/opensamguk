<?php
/**
 * capture_ai.php — P5 GT1 GeneralAI selection draw-for-draw golden capture
 * (devsam-core PHP, grand truth).
 *
 * ONE-SHOT, MANUAL HOST STEP — NEVER CI (devsam capture quirks; see README).
 *
 * Drives the REAL autorun-selection path of TurnExecutionHelper::executeGeneralCommandUntil
 * (lines 233-348) for EVERY due general over the live scenario_1010 install, recording the
 * ordered AI draw STREAM pulled off each general's ONE shared
 *   RandUtil(LiteHashDRBG(simpleSerialize(hiddenSeed,'GeneralAI',year,month,generalId)))
 * (GeneralAI ctor, GeneralAI.php:153-159) — the #1 P5 parity surface.
 *
 * THE ONLY DEVIATION from the live engine: after constructing each `GeneralAI`, we REPLACE
 * its `protected RandUtil $rng` (via reflection, write-once) with a DRAW-NEUTRAL
 * `RandUtilDrawRecorder` wrapping a FRESH `LiteHashDRBG` seeded with the EXACT same
 * 'GeneralAI' seed string. The recorder `extends RandUtil` and delegates every draw to the
 * unmodified parent algorithm on the bare DRBG, so the AI plays out value-for-value as the
 * live engine while the recorder logs {seq, method, args, result, consumed, stateIdxBefore,
 * bufferIdxBefore}. ZERO extra draws — it only OBSERVES.
 *
 * WE DRIVE ONLY THE AI SELECTION (chooseNationTurn for officer_level>=5 BEFORE
 * chooseGeneralTurn), NOT processCommand — the GeneralAI is READ-ONLY over game entities
 * (it only reads general/nation/city/diplomacy rows + a nation_env KV cache), so iterating
 * every due general against ONE pristine install snapshot is faithful: no general's
 * selection mutates a game entity another general's selection reads. (processCommand IS the
 * mutating step + runs a SEPARATE 'generalCommand'/'nationCommand'-seeded rng — that is the
 * long-sim GT3 territory, not the AI-selection surface this gate pins.)
 *
 * DUE-GENERAL ORDER is the EXACT executeGeneralCommandUntil query:
 *   SELECT no FROM general WHERE turntime < $date ORDER BY turntime ASC, `no` ASC
 * with $date = the current game turn boundary (opentime + turnterm) so the whole first
 * turn window of due generals is captured.
 *
 * THE hiddenSeed IS LIVE-PINNED (R-GATE §2): read from UniqueConst::$hiddenSeed AFTER
 * install and emitted into manifest_ai.json as the fixture INPUT. It is fresh-random per
 * install (ResetHelper buildScenario), so a byte-0 desync desyncs EVERY per-general DRBG.
 * The capture writes the live hex into every fixture + the census + the manifest; the
 * Kotlin replay seeds world_state.hidden_seed with that EXACT hex.
 *
 * THE npc CENSUS is the QUARANTINE PROOF artifact (M7, R-GATE §1): emitted FIRST against
 * the INSTALLED fixture; ABORTS the capture if any npc==5 or (npc==9 && officer_level==12)
 * exists — the G4 'ORDER BY RAND' (do선양 / 오랑캐임관) quarantine validity DEPENDS on the
 * census staying 0/0.
 *
 * Output (logic/src/test/resources/golden/p5/):
 *   ai-turn-NNN.json   — per due general: {generalId, name, npc, nation, officerLevel,
 *                        year, month, seed (hex), hiddenSeed, drawStream[], nationTurn{...}?,
 *                        chosenActionCode, chosenRawArgs, reason}
 *   npc-census-1010.json — {hiddenSeed, scenario, generalCount, census[], npc5, npc9off12,
 *                        lords[], invariantHeld}
 *
 * Invocation (inside the php capture container, repo mounted at /work; scenario_1010
 * already installed by install_scenario.php so UniqueConst::$hiddenSeed + the clock exist):
 *   php tools/php-golden/capture_ai.php [--out-dir=logic/src/test/resources/golden/p5] [--limit=N]
 *
 * BYTE-STABILITY: run TWICE and diff — the goldens MUST be byte-identical (capture-twice).
 * The recorder keys on seq; the per-general DRBG is rebuilt from the pinned seed string, so
 * the streams are reproducible across runs of the same install.
 */

namespace sammo;

require __DIR__ . '/_boot.php';
require __DIR__ . '/RandUtilDrawRecorder.php';

// Drop PHP-version noise levels so the headless error handler does not chase a web Session
// (mirrors capture_battle.php). Zero deterministic state change.
error_reporting(E_ALL & ~E_DEPRECATED & ~E_NOTICE & ~E_WARNING & ~E_USER_DEPRECATED & ~E_STRICT);

$opts   = getopt('', ['out-dir:', 'limit:']);
$outDir = $opts['out-dir'] ?? (__DIR__ . '/../../logic/src/test/resources/golden/p5');
$limit  = isset($opts['limit']) ? (int)$opts['limit'] : PHP_INT_MAX;
if (!is_dir($outDir)) { mkdir($outDir, 0775, true); }

$db = DB::db();

function hardAssert(bool $cond, string $msg): void {
    if (!$cond) { fwrite(STDERR, "GT1 HARD-ASSERT FAILED: {$msg}\n"); exit(2); }
}

// ── 1) the LIVE-pinned hiddenSeed (R-GATE §2) ─────────────────────────────────────────
$hiddenSeed = UniqueConst::$hiddenSeed;
hardAssert(preg_match('/^[0-9a-f]{32}$/', $hiddenSeed) === 1,
    "hiddenSeed is not a 32-char lowercase hex (install scenario_1010 first): {$hiddenSeed}");

$gameStor = KVStorage::getStorage($db, 'game_env');
[$startYear, $year, $month, $turnterm, $initYear, $initMonth] = $gameStor->getValuesAsArray(
    ['startyear', 'year', 'month', 'turnterm', 'init_year', 'init_month']
);
hardAssert((int)$startYear > 0, "startYear not set — install scenario_1010 first");

// ── 2) the npc census PROOF artifact (M7, R-GATE §1) ──────────────────────────────────
// Emitted FIRST against the INSTALLED fixture. ABORT if any npc==5 or (npc==9 && lord)
// exists — the G4 ORDER BY RAND (do선양 / 오랑캐임관) quarantine validity depends on 0/0.
$censusRows = $db->query('SELECT npc, COUNT(*) AS cnt FROM general GROUP BY npc ORDER BY npc');
$census = [];
foreach ($censusRows as $r) {
    $census[] = ['npc' => (int)$r['npc'], 'count' => (int)$r['cnt']];
}
$npc5     = (int)$db->queryFirstField('SELECT COUNT(*) FROM general WHERE npc=5');
$npc9off12 = (int)$db->queryFirstField('SELECT COUNT(*) FROM general WHERE officer_level=12 AND npc=9');
$lordRows = $db->query('SELECT no, name, nation, npc, officer_level FROM general WHERE officer_level=12 ORDER BY `no` ASC');
$lords = [];
foreach ($lordRows as $r) {
    $lords[] = [
        'no' => (int)$r['no'], 'name' => $r['name'], 'nation' => (int)$r['nation'],
        'npc' => (int)$r['npc'], 'officer_level' => (int)$r['officer_level'],
    ];
}
$generalCount = (int)$db->queryFirstField('SELECT COUNT(*) FROM general');

$invariantHeld = ($npc5 === 0) && ($npc9off12 === 0);
$census_out = [
    'scenario'     => 1010,
    'hiddenSeed'   => $hiddenSeed,
    'startYear'    => (int)$startYear,
    'year'         => (int)$year,
    'month'        => (int)$month,
    'generalCount' => $generalCount,
    'census'       => $census,
    'npc5'         => $npc5,
    'npc9off12'    => $npc9off12,
    'lords'        => $lords,
    'invariantHeld' => $invariantHeld,
    '_doc'         => 'R-GATE §1 quarantine PROOF: do선양 (npc==5) + 오랑캐임관 (npc==9 lord) are UNREACHABLE in 1010. The G4 ORDER BY RAND quarantine holds as long as npc5==0 && npc9off12==0.',
];
file_put_contents(
    $outDir . '/npc-census-1010.json',
    Json::encode($census_out, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES | JSON_PRETTY_PRINT)
);
fwrite(STDERR, sprintf(
    "npc-census-1010.json: generals=%d npc5=%d npc9off12=%d lords=%d invariantHeld=%s\n",
    $generalCount, $npc5, $npc9off12, count($lords), $invariantHeld ? 'true' : 'false'
));
hardAssert($invariantHeld,
    "npc census INVARIANT VIOLATED (npc5={$npc5}, npc9off12={$npc9off12}) — the G4 ORDER BY RAND quarantine is INVALID; ABORT (M7, R-GATE §1)");

// ── 3) inject the recorder into a GeneralAI (write-once reflection on protected $rng) ──
// We construct the GeneralAI normally (its ctor builds the 'GeneralAI'-seeded RandUtil),
// then REPLACE that rng with a DRAW-NEUTRAL recorder over a FRESH LiteHashDRBG seeded with
// the EXACT same 'GeneralAI' seed string. The recorder observes value-for-value.
$rpAiRng = new \ReflectionProperty(GeneralAI::class, 'rng');
if (PHP_VERSION_ID < 80100) { $rpAiRng->setAccessible(true); }

function makeGeneralAiSeed(string $hiddenSeed, int $year, int $month, int $generalId): string {
    // EXACT GeneralAI.php:153-159 — Util::simpleSerialize(hiddenSeed,'GeneralAI',year,month,generalId).
    return Util::simpleSerialize($hiddenSeed, 'GeneralAI', $year, $month, $generalId);
}

/** Build a GeneralAI for $general with its rng swapped to a recorder; return [ai, recorder, seedString]. */
function buildRecordingAi(General $general, string $hiddenSeed, int $year, int $month, \ReflectionProperty $rpAiRng): array {
    $ai = new GeneralAI($general);
    $seedString = makeGeneralAiSeed($hiddenSeed, $year, $month, $general->getID());
    $recorder = new RandUtilDrawRecorder(new LiteHashDRBG($seedString));
    $rpAiRng->setValue($ai, $recorder);
    return [$ai, $recorder, $seedString];
}

/** The 'GeneralAI' DRBG seed bytes as a debug hex (the first 16 raw bytes are NOT the seed;
 *  the seed is the simpleSerialize STRING — we emit the string + its md5-free identity). */
function seedHexOf(string $seedString): string {
    // The DRBG is keyed by the full seed STRING (SHA-512 DRBG). For the fixture we emit the
    // raw seed string AND a stable bin2hex of its bytes so the Kotlin replay can assert the
    // serialize string byte-for-byte (the load-bearing input). We do NOT hash it.
    return bin2hex($seedString);
}

// ── 4) the due-general autorun-selection capture ──────────────────────────────────────
// EXACT executeGeneralCommandUntil due query. $date = current turn boundary so the whole
// first-turn window is due. We DROP killturn/block guards the live loop applies BEFORE the
// AI (executeGeneralCommandUntil pre-steps) only where they would skip a general entirely:
// we mirror the loop's "create general → build turnObj → maybe nation turn → general turn".
$turntimeRaw = trim($gameStor->getValue('turntime'), '"');
// the cutoff: any general whose turntime is in the open turn window is due. The live loop
// uses the turn $date passed by the daemon; for a pristine install every general's turntime
// is within (opentime, opentime+turnterm]. We use the MAX general turntime + 1s as the
// inclusive cutoff so the entire first-turn cohort is selected (ORDER preserved).
$maxTt = $db->queryFirstField('SELECT MAX(turntime) FROM general');
$dateCutoff = (new \DateTimeImmutable($maxTt))->modify('+1 second')->format('Y-m-d H:i:s.u');

$generalsTodo = $db->query(
    'SELECT no FROM general WHERE turntime < %s ORDER BY turntime ASC, `no` ASC',
    $dateCutoff
);

$nationTurnCache = []; // nation_env KV is read-only here; no cross-general mutation

$captured = 0;
$reasonHist = [];
$totalDraws = 0;
$idx = 0;

foreach ($generalsTodo as $rawGeneral) {
    if ($captured >= $limit) { break; }
    $generalId = (int)$rawGeneral['no'];

    $general = General::createObjFromDB($generalId);

    // env is re-read per general exactly like the live loop (NOTE: 매번 재 갱신).
    $env = $gameStor->getAll();
    [$startYear, $year, $month, $turnterm] = $gameStor->getValuesAsArray(['startyear', 'year', 'month', 'turnterm']);

    // ── ONE recording GeneralAI per general (the live single-ai-per-general semantics) ──
    // The live executeGeneralCommandUntil builds ONE GeneralAI per general and runs
    // chooseNationTurn (if officer_level>=5) on the SAME ai/rng BEFORE chooseGeneralTurn, so
    // both choose* consume the ONE shared 'GeneralAI' draw stream in order. Replicate exactly.
    [$ai, $recorder, $seedString] = buildRecordingAi($general, $hiddenSeed, (int)$year, (int)$month, $rpAiRng);

    // ── nation turn (officer_level>=5, BEFORE the general turn) ──────────────────────
    $nationTurnOut = null;
    if ($general->getVar('nation') != 0 && $general->getVar('officer_level') >= 5) {
        $nationId = $general->getVar('nation');
        $officerLevel = $general->getVar('officer_level');
        $lastNationTurnKey = "turn_last_{$officerLevel}";
        $nationStor = KVStorage::getStorage($db, $nationId, 'nation_env');

        $rawNationTurn = $db->queryFirstRow(
            'SELECT action, arg FROM nation_turn WHERE nation_id = %i AND officer_level = %i AND turn_idx = 0',
            $nationId, $officerLevel
        ) ?? [];
        $nationCommand = $rawNationTurn['action'] ?? null;
        $nationArg = Json::decode($rawNationTurn['arg'] ?? null);
        $lastNationTurn = LastTurn::fromRaw($nationStor->getValue($lastNationTurnKey));
        $nationCommandObj = buildNationCommandClass($nationCommand, $general, $env, $lastNationTurn, $nationArg);

        $chosenNation = $ai->chooseNationTurn($nationCommandObj);
        $nationDrawCount = $recorder->getDrawCount(); // draws consumed by chooseNationTurn (stream prefix)
        $nationTurnOut = [
            'reservedAction'       => $nationCommand,
            'chosenActionCode'     => $chosenNation->getRawClassName(true),
            'chosenRawArgs'        => $chosenNation->getArg(),
            'reason'               => $chosenNation->reason,
            'drawCountAtNationEnd' => $nationDrawCount,
        ];
        $reasonHist['NATION:' . ($chosenNation->reason ?? 'null')] = ($reasonHist['NATION:' . ($chosenNation->reason ?? 'null')] ?? 0) + 1;
    }

    // ── general turn (always; continues the SAME shared rng stream) ──────────────────
    $reservedTurn = $general->getReservedTurn(0, $env);
    $chosenGeneral = $ai->chooseGeneralTurn($reservedTurn);

    $reason = $chosenGeneral->reason;
    $reasonHist[$reason ?? 'null'] = ($reasonHist[$reason ?? 'null'] ?? 0) + 1;
    $drawCount = $recorder->getDrawCount();
    $totalDraws += $drawCount;

    $fixture = [
        'generalId'        => $generalId,
        'name'             => $general->getName(),
        'npc'              => $general->getNPCType(),
        'nation'           => (int)$general->getVar('nation'),
        'officerLevel'     => (int)$general->getVar('officer_level'),
        'year'             => (int)$year,
        'month'            => (int)$month,
        'hiddenSeed'       => $hiddenSeed,
        'seedString'       => $seedString,
        'seedHex'          => seedHexOf($seedString),
        'reservedAction'   => $reservedTurn->getRawClassName(true),
        'chosenActionCode' => $chosenGeneral->getRawClassName(true),
        'chosenRawArgs'    => $chosenGeneral->getArg(),
        'reason'           => $reason,
        'drawStream'       => $recorder->getDrawStream(),
        'drawCount'        => $drawCount,
        'nationTurn'       => $nationTurnOut,
    ];

    $outFile = sprintf('%s/ai-turn-%03d.json', $outDir, $idx);
    file_put_contents(
        $outFile,
        Json::encode($fixture, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES | JSON_PRETTY_PRINT)
    );

    $captured++;
    $idx++;
}

// ── 5) the reason distribution + coverage summary ─────────────────────────────────────
ksort($reasonHist);
fwrite(STDERR, "=== capture_ai DONE ===\n");
fwrite(STDERR, sprintf("captured %d due-general turns (year=%d month=%d), totalDraws=%d\n", $captured, (int)$year, (int)$month, $totalDraws));
fwrite(STDERR, "reason distribution:\n");
foreach ($reasonHist as $reason => $cnt) {
    fwrite(STDERR, sprintf("  %-24s %d\n", $reason, $cnt));
}
fwrite(STDERR, sprintf("goldens written to %s (ai-turn-000.json .. ai-turn-%03d.json + npc-census-1010.json)\n", $outDir, $idx - 1));
