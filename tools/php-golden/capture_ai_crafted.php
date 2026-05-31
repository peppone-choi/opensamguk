<?php
/**
 * capture_ai_crafted.php — P5 GT2: crafted-fixture GeneralAI selection draw-for-draw
 * golden capture for the UNDER-COVERED families (devsam-core PHP, grand truth).
 *
 * ONE-SHOT, MANUAL HOST STEP — NEVER CI (devsam capture quirks; see README).
 *
 * GT1 (capture_ai.php) drove the live month-1 1010 due-general autorun. That window
 * organically exercised general-domestic (do일반내정), do중립, do국가선택, do거병, and the
 * lord nation-pass doNPC긴급포상 — but it NEVER reached the diplomacy-emitting families
 * (do불가침제의/do선전포고/do천도), the war-state branch of do출병, the genfound
 * 방랑군 families (do건국/do해산/do방랑군이동), do선양, NOR the month-3/6/9/12 nation pass
 * (choosePromotion/chooseTexRate/choose*BillRate, chooseNonLordPromotion).
 *
 * THE LOAD-BEARING CONSTRAINT (why the month-1 window can't reach them):
 *   GeneralAI::calcDiplomacyState (GeneralAI.php:219) SHORT-CIRCUITS to 평화/선포 with
 *   attackable=false for the ENTIRE early window
 *     yearMonth <= Util::joinYearMonth(startyear+2, 5)
 *   For scenario 1010 (startyear=181) that is yearMonth <= 2201 i.e. up to year 183 month 5.
 *   So do출병 (needs d전쟁+attackable) is UNREACHABLE at the pinned year-181-month-1 instant.
 *   The diplomacy-emitting lord families are likewise gated by frontCities/capital/recv_assist
 *   KV that a pristine install does not present at month 1.
 *
 * FAITHFUL MUTATION, NEVER FABRICATION:
 *   We do NOT re-install (a re-install regenerates UniqueConst::$hiddenSeed AND every general's
 *   stats/placement, which would invalidate manifest_ai.json + world-1010.json). Instead we
 *   MUTATE THE LIVE GT1 INSTALL using the EXACT PHP state representations the game itself writes:
 *     - the game clock (game_env year/month) — a real value the live engine advances each turn;
 *       advancing it re-keys the per-general 'GeneralAI' seed (year,month inputs), so each
 *       crafted fixture RE-PINS its own seedString from the mutated clock (self-consistent).
 *     - diplomacy.state/term — the exact rows che_선전포고/che_전쟁 등 write (state 0=교전, 1=선포).
 *     - nation_env recv_assist/resp_assist/resp_assist_try — the EXACT shape che_물자원조 writes
 *       (recv_assist["n{donor}"] = [donor, amount]) + che_불가침수락 writes (resp_assist).
 *     - nation_env last천도Trial — the exact [officer_level, turnTime] che_천도/do천도 writes.
 *     - city.front — a real per-city flag (a nation with no front is a genuine peace state).
 *   EVERY mutation is snapshotted BEFORE and RESTORED AFTER, so the live DB returns to the
 *   exact GT1 state for the next family (the install stays the manifest_ai.json install).
 *
 * For each crafted family we dump BOTH:
 *   world-crafted-<fam>.json   — the PRE-TURN world snapshot AT THE MUTATED INSTANT (same shape
 *                                as GT1b world-1010.json: full general/city/nation/diplomacy +
 *                                game_env/nation_env KV + turn_idx=0 reserved commands). The
 *                                Kotlin replay materializes THIS into InMemoryTurnWorld.
 *   ai-crafted-<fam>-NN.json   — per captured general: {generalId,name,npc,nation,officerLevel,
 *                                year,month,hiddenSeed,seedString,seedHex,reservedAction,
 *                                chosenActionCode (RAW),chosenRawArgs,reason,drawStream[],
 *                                drawCount,nationTurn{...}?}  (same shape as GT1 ai-turn-NNN).
 *
 * THE recorder is the SAME draw-neutral RandUtilDrawRecorder (GT1/P4 seam): write-once
 * reflection swaps GeneralAI's protected $rng for a recorder over a FRESH LiteHashDRBG seeded
 * with the EXACT same 'GeneralAI' seed string. ZERO extra draws — it only OBSERVES.
 *
 * m10 (diplomacy downstream EXCLUDED): for do불가침제의/do선전포고/do천도 the crafted golden
 * asserts ONLY the AI SELECTION + the boolean gate + the draw stream. The downstream che_*
 * state-mutation/log is P6 (no P2-P4 green resolver) and is NOT in the gate. capture_ai_crafted
 * runs ONLY the AI selection (chooseNationTurn/chooseGeneralTurn) — never processCommand — so it
 * never even computes that downstream.
 *
 * Q1 (do선양 / 오랑캐임관 ORDER BY RAND): the crafted fixture byte-matches only the NON-id bytes
 * (actionCode + reason + the non-id draw stream). The ORDER BY RAND target-id is asserted
 * "valid member only" by the Kotlin replay — it is NOT a draw-for-draw byte (the SQL RAND() is
 * outside the LiteHashDRBG stream). We record the chosen id but tag it `quarantineNonDraw`.
 *
 * Invocation (inside the php capture container, repo mounted at /work; scenario_1010 already
 * installed by install_scenario.php — the SAME GT1 install that produced manifest_ai.json):
 *   php tools/php-golden/capture_ai_crafted.php [--out-dir=logic/src/test/resources/golden/p5] [--family=ALL]
 *
 * BYTE-STABILITY: run TWICE and diff — every world-crafted-*.json + ai-crafted-*.json MUST be
 * byte-identical (the mutations are deterministic, the per-general DRBG is rebuilt from the
 * re-pinned seed string, the snapshot/restore returns the DB to the GT1 baseline each run).
 */

namespace sammo;

require __DIR__ . '/_boot.php';
require __DIR__ . '/RandUtilDrawRecorder.php';

error_reporting(E_ALL & ~E_DEPRECATED & ~E_NOTICE & ~E_WARNING & ~E_USER_DEPRECATED & ~E_STRICT);

$opts   = getopt('', ['out-dir:', 'family:']);
$outDir = $opts['out-dir'] ?? (__DIR__ . '/../../logic/src/test/resources/golden/p5');
$family = $opts['family'] ?? 'ALL';
if (!is_dir($outDir)) { mkdir($outDir, 0775, true); }

$db = DB::db();

function hardAssert(bool $cond, string $msg): void {
    if (!$cond) { fwrite(STDERR, "GT2 HARD-ASSERT FAILED: {$msg}\n"); exit(2); }
}

// ── pin the install identity (must equal manifest_ai.json — the GT1 install) ──────────────
$hiddenSeed = UniqueConst::$hiddenSeed;
hardAssert(preg_match('/^[0-9a-f]{32}$/', $hiddenSeed) === 1,
    "hiddenSeed is not 32-char lowercase hex (install scenario_1010 first): {$hiddenSeed}");
hardAssert($hiddenSeed === '71adaa4df4012a20c0883beba4810681',
    "hiddenSeed {$hiddenSeed} != the manifest_ai.json GT1 install hex — re-install scenario_1010 + re-pin GT1 first; the crafted goldens are only valid for the SAME install");

$gameStor = KVStorage::getStorage($db, 'game_env');
[$startYear, $baseYear, $baseMonth, $turnterm] = $gameStor->getValuesAsArray(
    ['startyear', 'year', 'month', 'turnterm']
);
hardAssert((int)$baseYear === 181 && (int)$baseMonth === 1,
    "baseline must be the GT1 pre-turn instant year=181 month=1 (got year={$baseYear} month={$baseMonth}) — restore the GT1 install");

$rpAiRng = new \ReflectionProperty(GeneralAI::class, 'rng');
if (PHP_VERSION_ID < 80100) { $rpAiRng->setAccessible(true); }

/* ─────────────────────────────────────────────────────────────────────────────────────────
 * Draw-recording GeneralAI builder (the GT1 seam, re-pinning the seed from the CURRENT clock).
 * ─────────────────────────────────────────────────────────────────────────────────────── */
function makeGeneralAiSeed(string $hiddenSeed, int $year, int $month, int $generalId): string {
    return Util::simpleSerialize($hiddenSeed, 'GeneralAI', $year, $month, $generalId);
}
function seedHexOf(string $seedString): string { return bin2hex($seedString); }

/** Build a GeneralAI with its rng swapped to a recorder seeded from the CURRENT game clock. */
function buildRecordingAi(General $general, string $hiddenSeed, int $year, int $month, \ReflectionProperty $rpAiRng): array {
    $ai = new GeneralAI($general);
    $seedString = makeGeneralAiSeed($hiddenSeed, $year, $month, $general->getID());
    $recorder = new RandUtilDrawRecorder(new LiteHashDRBG($seedString));
    $rpAiRng->setValue($ai, $recorder);
    return [$ai, $recorder, $seedString];
}

/* ─────────────────────────────────────────────────────────────────────────────────────────
 * World-snapshot dumper (the GT1b shape, AT THE MUTATED INSTANT).
 * ─────────────────────────────────────────────────────────────────────────────────────── */
function dumpTable($db, string $sql, array $jsonCols, array $args = []): array {
    $rows = $args ? $db->query($sql, ...$args) : $db->query($sql);
    $out = [];
    foreach ($rows as $r) {
        $row = [];
        foreach ($r as $col => $val) {
            $row[$col] = in_array($col, $jsonCols, true) ? Json::decode($val ?? 'null') : $val;
        }
        $out[] = $row;
    }
    return $out;
}

function dumpCraftedWorld($db, string $family, string $hiddenSeed, string $note, string $outDir): void {
    $gameStor = KVStorage::getStorage($db, 'game_env');
    [$startYear, $year, $month, $turnterm, $develCost] = $gameStor->getValuesAsArray(
        ['startyear', 'year', 'month', 'turnterm', 'develcost']
    );

    $gameEnvRows = $db->query("SELECT `key`, `value` FROM storage WHERE namespace = 'game_env' ORDER BY `key` ASC");
    $gameEnv = [];
    foreach ($gameEnvRows as $r) { $gameEnv[$r['key']] = Json::decode($r['value']); }

    $nations = dumpTable($db, 'SELECT * FROM nation ORDER BY nation ASC', ['aux', 'spy']);
    $cities = dumpTable($db, 'SELECT * FROM city ORDER BY city ASC', ['conflict']);
    $generals = dumpTable($db, 'SELECT * FROM general ORDER BY `no` ASC', ['last_turn', 'aux', 'penalty']);
    $diplomacy = dumpTable($db, 'SELECT * FROM diplomacy ORDER BY `no` ASC', []);

    $nationEnv = [];
    foreach ($nations as $n) {
        $nid = (int)$n['nation'];
        $kvRows = $db->query("SELECT `key`, `value` FROM nation_env WHERE namespace = %i ORDER BY `key` ASC", $nid);
        $kv = [];
        foreach ($kvRows as $r) { $kv[$r['key']] = Json::decode($r['value']); }
        $nationEnv[] = ['nation' => $nid, 'kv' => (object)$kv];
    }

    $generalTurns = dumpTable($db, 'SELECT general_id, turn_idx, action, arg FROM general_turn WHERE turn_idx = 0 ORDER BY general_id ASC', ['arg']);
    $nationTurns = dumpTable($db, 'SELECT nation_id, officer_level, turn_idx, action, arg FROM nation_turn WHERE turn_idx = 0 ORDER BY nation_id ASC, officer_level ASC', ['arg']);

    $counts = [
        'generals' => count($generals), 'cities' => count($cities), 'nations' => count($nations),
        'diplomacy' => count($diplomacy), 'nationEnv' => count($nationEnv),
        'generalTurns' => count($generalTurns), 'nationTurns' => count($nationTurns), 'gameEnvKeys' => count($gameEnv),
    ];

    $world = [
        '_doc' => "P5 GT2 — crafted-fixture PRE-TURN world snapshot for family '{$family}' AT THE MUTATED INSTANT. {$note} FULL general/city/nation/diplomacy tables + game_env/nation_env KV + turn_idx=0 reserved commands, exactly the GT1b world-1010.json shape. Byte-identical across two capture_ai_crafted.php runs (deterministic faithful mutation, snapshot/restore returns the DB to the GT1 baseline). City adjacency (path) is from CityConst, NOT this snapshot. Deserialize into InMemoryTurnWorld to drive the LIVE Kotlin AiTurnAdapter draw-for-draw against the ai-crafted-{$family}-NN goldens.",
        'meta' => [
            'scenario' => 1010, 'family' => $family, 'hiddenSeed' => $hiddenSeed,
            'startYear' => (int)$startYear, 'year' => (int)$year, 'month' => (int)$month,
            'turnterm' => (int)$turnterm, 'develCost' => (int)$develCost, 'counts' => $counts,
        ],
        'gameEnv' => (object)$gameEnv, 'nations' => $nations, 'cities' => $cities,
        'generals' => $generals, 'diplomacy' => $diplomacy, 'nationEnv' => $nationEnv,
        'generalTurns' => $generalTurns, 'nationTurns' => $nationTurns,
    ];

    $outFile = $outDir . "/world-crafted-{$family}.json";
    file_put_contents($outFile, Json::encode($world, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES | JSON_PRETTY_PRINT));
    fwrite(STDERR, sprintf("  world-crafted-%s.json: year=%d month=%d generals=%d cities=%d nations=%d (%d bytes)\n",
        $family, (int)$year, (int)$month, $counts['generals'], $counts['cities'], $counts['nations'], filesize($outFile)));
}

/* ─────────────────────────────────────────────────────────────────────────────────────────
 * Capture ONE general's AI selection (nation pass for lords + general pass), the GT1 shape.
 * ─────────────────────────────────────────────────────────────────────────────────────── */
function captureGeneral($db, int $generalId, string $hiddenSeed, \ReflectionProperty $rpAiRng,
                        string $family, int $seq, string $outDir, array $opts = []): array {
    $gameStor = KVStorage::getStorage($db, 'game_env');
    $env = $gameStor->getAll();
    [$year, $month] = $gameStor->getValuesAsArray(['year', 'month']);
    $year = (int)$year; $month = (int)$month;

    $general = General::createObjFromDB($generalId);
    [$ai, $recorder, $seedString] = buildRecordingAi($general, $hiddenSeed, $year, $month, $rpAiRng);

    // ── nation pass (officer_level>=5, BEFORE the general pass; SAME shared rng stream) ──
    $nationTurnOut = null;
    if ($general->getVar('nation') != 0 && $general->getVar('officer_level') >= 5) {
        $nationId = $general->getVar('nation');
        $officerLevel = $general->getVar('officer_level');
        $nationStor = KVStorage::getStorage($db, $nationId, 'nation_env');
        $rawNationTurn = $db->queryFirstRow(
            'SELECT action, arg FROM nation_turn WHERE nation_id = %i AND officer_level = %i AND turn_idx = 0',
            $nationId, $officerLevel
        ) ?? [];
        $nationCommand = $rawNationTurn['action'] ?? null;
        $nationArg = Json::decode($rawNationTurn['arg'] ?? null);
        $lastNationTurn = LastTurn::fromRaw($nationStor->getValue("turn_last_{$officerLevel}"));
        $nationCommandObj = buildNationCommandClass($nationCommand, $general, $env, $lastNationTurn, $nationArg);

        $chosenNation = $ai->chooseNationTurn($nationCommandObj);
        $nationTurnOut = [
            'reservedAction'       => $nationCommand,
            'chosenActionCode'     => $chosenNation->getRawClassName(true),
            'chosenRawArgs'        => $chosenNation->getArg(),
            'reason'               => $chosenNation->reason,
            'drawCountAtNationEnd' => $recorder->getDrawCount(),
        ];
    }

    // ── general pass (continues the SAME shared rng stream) ──
    $reservedTurn = $general->getReservedTurn(0, $env);
    $chosenGeneral = $ai->chooseGeneralTurn($reservedTurn);

    $fixture = [
        '_family'          => $family,
        'generalId'        => $generalId,
        'name'             => $general->getName(),
        'npc'              => $general->getNPCType(),
        'nation'           => (int)$general->getVar('nation'),
        'officerLevel'     => (int)$general->getVar('officer_level'),
        'year'             => $year,
        'month'            => $month,
        'hiddenSeed'       => $hiddenSeed,
        'seedString'       => $seedString,
        'seedHex'          => seedHexOf($seedString),
        'reservedAction'   => $reservedTurn->getRawClassName(true),
        'chosenActionCode' => $chosenGeneral->getRawClassName(true),
        'chosenRawArgs'    => $chosenGeneral->getArg(),
        'reason'           => $chosenGeneral->reason,
        'drawStream'       => $recorder->getDrawStream(),
        'drawCount'        => $recorder->getDrawCount(),
        'nationTurn'       => $nationTurnOut,
    ];
    foreach ($opts as $k => $v) { $fixture[$k] = $v; }

    $outFile = sprintf('%s/ai-crafted-%s-%02d.json', $outDir, $family, $seq);
    file_put_contents($outFile, Json::encode($fixture, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES | JSON_PRETTY_PRINT));

    $reason = $chosenGeneral->reason;
    $natReason = $nationTurnOut['reason'] ?? null;
    fwrite(STDERR, sprintf("  ai-crafted-%s-%02d.json: gid=%d name=%s natReason=%s genReason=%s actionCode=%s draws=%d\n",
        $family, $seq, $generalId, $general->getName(), $natReason ?? '-', $reason ?? '-',
        $chosenGeneral->getRawClassName(true), $recorder->getDrawCount()));

    return $fixture;
}

/* ─────────────────────────────────────────────────────────────────────────────────────────
 * Snapshot/restore helpers — capture the exact rows/KV we mutate, restore them verbatim.
 * ─────────────────────────────────────────────────────────────────────────────────────── */
function setGameClock($db, int $year, int $month): void {
    $gameStor = KVStorage::getStorage($db, 'game_env');
    $gameStor->setValue('year', $year);
    $gameStor->setValue('month', $month);
}
function snapshotGameClock($db): array {
    $gameStor = KVStorage::getStorage($db, 'game_env');
    [$y, $m] = $gameStor->getValuesAsArray(['year', 'month']);
    return [(int)$y, (int)$m];
}
function snapshotNationEnvKey($db, int $nationID, string $key) {
    $stor = KVStorage::getStorage($db, $nationID, 'nation_env');
    return $stor->getValue($key);
}
function restoreNationEnvKey($db, int $nationID, string $key, $value): void {
    $stor = KVStorage::getStorage($db, $nationID, 'nation_env');
    $stor->setValue($key, $value); // null restores to absent-equivalent (getValue returns null)
}
function snapshotDiplomacy($db): array {
    return $db->query('SELECT no, me, you, state, term FROM diplomacy ORDER BY no');
}
function restoreDiplomacy($db, array $rows): void {
    foreach ($rows as $r) {
        $db->update('diplomacy', ['state' => (int)$r['state'], 'term' => (int)$r['term']], 'no = %i', (int)$r['no']);
    }
}
function snapshotCityFront($db, int $nationID): array {
    return $db->query('SELECT city, front FROM city WHERE nation = %i ORDER BY city', $nationID);
}
function restoreCityFront($db, array $rows): void {
    foreach ($rows as $r) {
        $db->update('city', ['front' => (int)$r['front']], 'city = %i', (int)$r['city']);
    }
}
function snapshotGeneralRow($db, int $gid): array {
    return $db->queryFirstRow('SELECT * FROM general WHERE `no` = %i', $gid);
}

$capturedFamilies = [];
$backloggedFamilies = [];

/* ═══════════════════════════════════════════════════════════════════════════════════════════
 * FAMILY: diplo-불가침제의 — lord receives an assist offer (recv_assist), 0-draw selection.
 *   Precondition (GeneralAI.php:1765): officer_level==12; recv_assist["n{donor}"]=[donor,amount]
 *   with amount*4 >= income; donor NOT in warTargetNation; resp_assist_try < yearMonth-8;
 *   supplyCities non-empty. The selection is 0-DRAW (arsort, no rng) up to the command build.
 *   m10: downstream EXCLUDED — assert SELECTION + 0-draw only.
 *   Faithful mutation: write recv_assist for nation 1 (lord gid 152 하진) exactly as
 *   che_물자원조 writes it. dipState stays 평화 (month 1, no war diplomacy state IN(0,1)).
 *   We keep the GT1 priority ordering: 불가침제의 must come BEFORE the families that would
 *   otherwise fire. AutorunNationPolicy.priority decides; recv_assist + supply guarantees the
 *   0-draw 불가침제의 path fires its command build first.
 * ═══════════════════════════════════════════════════════════════════════════════════════════ */
function craft_diplo_불가침제의($db, string $hiddenSeed, \ReflectionProperty $rpAiRng, string $outDir): bool {
    fwrite(STDERR, "[diplo-불가침제의]\n");
    $lordGid = 152; $nationID = 1; $donor = 2;

    // calcDiplomacyState (GeneralAI.php:219) SHORT-CIRCUITS to 평화/선포 and leaves
    // $this->warTargetNation UNSET for yearMonth <= joinYearMonth(startyear+2,5) = (183,5).
    // do불가침제의 reads $this->warTargetNation (line 1789), so it is only reachable PAST that
    // gate. Advance the clock to 184/1 (a real value the engine reaches in play); the per-general
    // seed re-pins from (184,1). The diplomacy 1↔2 state stays 'state 2' (no warTarget), so the
    // full calcDiplomacyState sets warTargetNation=[0=>1] (no-war default) → 불가침제의 candidate
    // loop runs. dipState=평화. This is faithful: a peacetime lord PAST the opening window with a
    // pending assist offer.
    [$savedY, $savedM] = snapshotGameClock($db);
    setGameClock($db, 184, 1);

    $savedRecv = snapshotNationEnvKey($db, $nationID, 'recv_assist');
    $savedTry  = snapshotNationEnvKey($db, $nationID, 'resp_assist_try');
    $savedResp = snapshotNationEnvKey($db, $nationID, 'resp_assist');

    // The EXACT che_물자원조 write shape: recv_assist["n{donor}"] = [donor, amount].
    // income for nation 1 (14 supply cities) is well under a 5_000_000 offer ⇒ amount*4 >= income.
    $stor = KVStorage::getStorage($db, $nationID, 'nation_env');
    $stor->setValue('recv_assist', ['n2' => [$donor, 5000000]]);
    $stor->setValue('resp_assist', []);          // nothing already responded
    $stor->setValue('resp_assist_try', []);      // no recent try → passes the yearMonth-8 gate

    dumpCraftedWorld($db, 'diplo-불가침제의', $hiddenSeed,
        "Clock 184/1 (PAST the startyear+2,5 calcDiplomacyState short-circuit so warTargetNation is set) + lord 하진 (gid 152, nation 1) holds a recv_assist offer {n2:[2,5000000]} (che_물자원조 write shape) → do불가침제의 fires 0-draw. m10: downstream che_불가침제의 delta/log EXCLUDED from the gate.",
        $outDir);
    $fx = captureGeneral($db, $lordGid, $hiddenSeed, $rpAiRng, 'diplo-불가침제의', 0, $outDir, [
        '_m10' => 'downstream delta/log EXCLUDED (no P2-P4 resolver); assert SELECTION + boolean + 0-draw only',
    ]);

    // restore
    restoreNationEnvKey($db, $nationID, 'recv_assist', $savedRecv);
    restoreNationEnvKey($db, $nationID, 'resp_assist', $savedResp);
    restoreNationEnvKey($db, $nationID, 'resp_assist_try', $savedTry);
    setGameClock($db, $savedY, $savedM);

    $ok = ($fx['reason'] === 'do불가침제의') || (($fx['nationTurn']['reason'] ?? null) === 'do불가침제의');
    return $ok;
}

/* ═══════════════════════════════════════════════════════════════════════════════════════════
 * FAMILY: diplo-선전포고 — lord declares war (draws A trialProp, then B/C if reached).
 *   Precondition (GeneralAI.php:1848): officer_level==12; dipState==평화; !attackable; capital;
 *   !frontCities; TechLimit(startyear,year,tech+1000). The FIRST rng call is nextBool($trialProp).
 *   Faithful mutation: zero city.front for nation 1 (a nation with no front is a real peace
 *   state) so frontCities is empty; dipState is already 평화 at month 1; capital=3 (set).
 *   We do NOT force the nextBool outcome — we record whatever the deterministic stream yields
 *   (it may short-circuit to null after draw A; the draw-COUNT + value is the parity target).
 *   m10: downstream EXCLUDED.
 * ═══════════════════════════════════════════════════════════════════════════════════════════ */
function craft_diplo_선전포고($db, string $hiddenSeed, \ReflectionProperty $rpAiRng, string $outDir): bool {
    fwrite(STDERR, "[diplo-선전포고]\n");
    $lordGid = 152; $nationID = 1;

    // Advance past the early-window short-circuit (so calcDiplomacyState fully runs); diplomacy
    // 1↔2 stays 'state 2' (no war) → dipState=평화 + attackable=false → do선전포고's guards
    // (dipState==평화, !attackable, capital) pass. We zero city.front (frontCities empty guard).
    [$savedY, $savedM] = snapshotGameClock($db);
    setGameClock($db, 184, 1);

    $savedFront = snapshotCityFront($db, $nationID);
    foreach ($savedFront as $r) {
        $db->update('city', ['front' => 0], 'city = %i', (int)$r['city']);
    }

    // do선전포고's first draw is nextBool($trialProp); $trialProp is built from the nation
    // generals' avgGold/avgRice + dev (GeneralAI.php:1880-1921, ** 6). To make the 선전포고
    // SELECTION FIRE (not just reach draw A then decline), we faithfully top the nation-1 NPC
    // generals' gold/rice high so $trialProp >= 1 → nextBool SHORT-CIRCUITS true (consumed=false,
    // NO draw) and the B (neighbor count) + C (choiceUsingWeight target) draws fire. Nation 1
    // borders nation 2 (isNeighbor) so the weight-pick has a candidate. Snapshot/restore the rows.
    $savedGenGoldRice = $db->query(
        'SELECT `no`, gold, rice FROM general WHERE nation = %i ORDER BY `no`', $nationID
    );
    $db->update('general', ['gold' => 1000000, 'rice' => 1000000], 'nation = %i', $nationID);

    dumpCraftedWorld($db, 'diplo-선전포고', $hiddenSeed,
        "Clock 184/1 (past the short-circuit so dipState=평화 + attackable=false) + lord 하진 (gid 152, nation 1), ALL front cities zeroed (frontCities empty guard) + capital + nation-1 generals' gold/rice topped so trialProp>=1 (nextBool short-circuits true, NO draw) → do선전포고 FIRES, drawing the neighbor count + choiceUsingWeight target (nation 1 borders nation 2). m10: downstream che_선전포고 delta/log EXCLUDED.",
        $outDir);
    $fx = captureGeneral($db, $lordGid, $hiddenSeed, $rpAiRng, 'diplo-선전포고', 0, $outDir, [
        '_m10' => 'downstream delta/log EXCLUDED; assert SELECTION + boolean + draw stream only',
    ]);

    // restore gold/rice, front, clock
    foreach ($savedGenGoldRice as $r) {
        $db->update('general', ['gold' => (int)$r['gold'], 'rice' => (int)$r['rice']], '`no` = %i', (int)$r['no']);
    }
    restoreCityFront($db, $savedFront);
    setGameClock($db, $savedY, $savedM);

    $natReason = $fx['nationTurn']['reason'] ?? null;
    return $natReason === 'do선전포고';
}

/* ═══════════════════════════════════════════════════════════════════════════════════════════
 * FAMILY: diplo-천도 — lord relocates the capital. The choice draw fires when the winning
 *   city is >1 hop from the capital (GeneralAI.php:2090 → rng->choice(candidates)).
 *   Precondition: multi-city nation (nation 1 owns 14 connected cities). The first-trial-continue
 *   branch (line 1986) needs lastTurn.command=='천도'; we instead drive the FRESH path (no prior
 *   천도) which scores all connected cities and picks the best, drawing choice() iff dist>1.
 *   Faithful mutation: NONE needed beyond the multi-city nation (already installed). We capture
 *   the fresh 천도 trial. m10: downstream EXCLUDED.
 *   NOTE: do천도 is NOT in the default lord priority for early game in all policies; if the
 *   priority loop reaches a higher-priority do* first, the reason will be that do* — we record
 *   faithfully and report. If do천도 is genuinely unreachable from the priority loop at this
 *   instant we BACKLOG with the precise reason.
 * ═══════════════════════════════════════════════════════════════════════════════════════════ */
function craft_diplo_천도($db, string $hiddenSeed, \ReflectionProperty $rpAiRng, string $outDir): bool {
    fwrite(STDERR, "[diplo-천도]\n");
    $lordGid = 152; $nationID = 1;

    // Advance past the early-window short-circuit so the lord nation pass runs the full priority
    // loop (calcDiplomacyState fully set). Clear last천도Trial so the fresh 천도 path is not
    // time-gated. We capture the FRESH-path draw (choice(candidates) iff winner >1 hop).
    [$savedY, $savedM] = snapshotGameClock($db);
    setGameClock($db, 184, 1);

    $savedTrial = snapshotNationEnvKey($db, $nationID, 'last천도Trial');
    restoreNationEnvKey($db, $nationID, 'last천도Trial', null);

    // do천도 returns null if the CURRENT capital scores in the top 25% (GeneralAI.php:2078-2085).
    // The installed capital 3 (낙양, the highest-pop city) IS top-1 → 천도 declines with 0 draws.
    // To FIRE the choice draw (line 2090: winner >1 hop), we faithfully RELOCATE the capital to a
    // low-pop edge city (77 역경, pop 68950, far corner). Then the score formula picks a high-pop
    // city (e.g. 낙양) several hops away, so the >1-hop branch runs rng->choice(candidates).
    // Capital is a real per-nation value the engine sets on 건국/천도; snapshot/restore it.
    $savedCapital = (int)$db->queryFirstField('SELECT capital FROM nation WHERE nation = %i', $nationID);
    $newCapital = 77;
    $db->update('nation', ['capital' => $newCapital], 'nation = %i', $nationID);
    // capital flag on the city rows: the old capital loses, the new gains (city.capital ∈ {0,1}).
    $hasCityCapitalCol = $db->queryFirstField(
        "SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_NAME='city' AND COLUMN_NAME='capital'"
    );
    $savedCityCapital = null;
    if ($hasCityCapitalCol) {
        $savedCityCapital = $db->query('SELECT city, `capital` FROM city WHERE nation = %i ORDER BY city', $nationID);
        $db->update('city', ['capital' => 0], 'nation = %i', $nationID);
        $db->update('city', ['capital' => 1], 'city = %i', $newCapital);
    }

    dumpCraftedWorld($db, 'diplo-천도', $hiddenSeed,
        "Clock 184/1 (past the short-circuit) + lord 하진 (gid 152, nation 1, 14 connected cities), capital RELOCATED to a low-pop edge city {$newCapital} (역경) so the current capital is NOT in the top-25% score band → do천도 picks a high-pop city several hops away and FIRES rng->choice(candidates) (the >1-hop branch). m10: downstream che_천도 delta/log EXCLUDED.",
        $outDir);
    $fx = captureGeneral($db, $lordGid, $hiddenSeed, $rpAiRng, 'diplo-천도', 0, $outDir, [
        '_m10' => 'downstream delta/log EXCLUDED; assert SELECTION + draw stream only',
    ]);

    // restore capital (nation + city flag), trial, clock
    $db->update('nation', ['capital' => $savedCapital], 'nation = %i', $nationID);
    if ($savedCityCapital !== null) {
        foreach ($savedCityCapital as $r) {
            $db->update('city', ['capital' => (int)$r['capital']], 'city = %i', (int)$r['city']);
        }
    }
    restoreNationEnvKey($db, $nationID, 'last천도Trial', $savedTrial);
    setGameClock($db, $savedY, $savedM);

    $natReason = $fx['nationTurn']['reason'] ?? null;
    return $natReason === 'do천도';
}

/* ═══════════════════════════════════════════════════════════════════════════════════════════
 * FAMILY: nation-pass-month — advance the clock to a promotion month (3) so the lord nation pass
 *   runs choosePromotion (1× nextBool(0.1) per OCCUPIED chief slot, 0 per empty, the :4102
 *   newChiefProb phantom NEVER draws) + chooseNonLordPromotion for non-lord chiefs. Month 6/12
 *   additionally run chooseTexRate/choose*BillRate (ZERO draws, getOutcome half-away).
 *   Faithful mutation: game_env month 1 → 3 (a real clock value the engine advances). The seed
 *   re-pins from (year=181, month=3). We capture BOTH lords (gid 105, 152) at month 3.
 *   This is the months-3/6/9/12 nation pass the month-1 window structurally cannot reach.
 * ═══════════════════════════════════════════════════════════════════════════════════════════ */
function craft_nation_pass_month($db, string $hiddenSeed, \ReflectionProperty $rpAiRng, string $outDir, int $month): bool {
    $fam = "nation-pass-m{$month}";
    fwrite(STDERR, "[{$fam}]\n");
    [$savedY, $savedM] = snapshotGameClock($db);
    setGameClock($db, 181, $month);

    dumpCraftedWorld($db, $fam, $hiddenSeed,
        "Clock advanced to year 181 month {$month} (a promotion month) → the lord nation pass runs choosePromotion (+chooseTexRate/Bill on 6/12) BEFORE the priority loop; non-lord chiefs run chooseNonLordPromotion. The seed re-pins from (181,{$month}). This is the months-3/6/9/12 nation pass the GT1 month-1 window cannot reach.",
        $outDir);

    $seq = 0; $anyNation = false;
    foreach ([105, 152] as $lordGid) {
        $fx = captureGeneral($db, $lordGid, $hiddenSeed, $rpAiRng, $fam, $seq++, $outDir, []);
        if ($fx['nationTurn'] !== null) { $anyNation = true; }
    }

    setGameClock($db, $savedY, $savedM);
    return $anyNation;
}

/* ═══════════════════════════════════════════════════════════════════════════════════════════
 * FAMILY: war-출병 — advance clock past startyear+2,5 AND set diplomacy to 교전 (state 0) so
 *   calcDiplomacyState yields d전쟁 + attackable; then a front-2 city + train/atmos/crew lets
 *   do출병 draw choice(attackableCities) (+ optional nextBool(0.7) rice gate).
 *   Faithful mutation: clock 181/1 → 184/1 (past 183/5); diplomacy 1↔2 state 2→0 term 0
 *   (the exact 교전 rows); a nation-1 general at a front-2 city with crew/train/atmos topped to
 *   the war thresholds (the exact che_출병 precondition fields). Snapshot/restore all.
 * ═══════════════════════════════════════════════════════════════════════════════════════════ */
function craft_war_출병($db, string $hiddenSeed, \ReflectionProperty $rpAiRng, string $outDir): bool {
    fwrite(STDERR, "[war-출병]\n");
    $nationID = 1;

    [$savedY, $savedM] = snapshotGameClock($db);
    $savedDip = snapshotDiplomacy($db);
    $savedFront = snapshotCityFront($db, $nationID);

    // 1) clock past the 183/5 war-state gate
    setGameClock($db, 184, 1);
    // 2) 교전 state (state 0) both directions, term 0
    $db->update('diplomacy', ['state' => 0, 'term' => 0], 'me = %i AND you = %i', 1, 2);
    $db->update('diplomacy', ['state' => 0, 'term' => 0], 'me = %i AND you = %i', 2, 1);

    // 3) Find a nation-1 general sitting at a nation-1 supply+front city that BORDERS an enemy
    //    (nation 2 or 0) city, then ensure that city.front>=2 and the general is war-ready.
    //    We pick the nation-1 general whose city has a nation-2 OR nation-0 neighbor.
    $candidate = null; $candidateCity = null; $destCity = null;
    $genRows = $db->query(
        'SELECT g.no AS gid, g.city AS cid, g.npc AS npc, g.officer_level AS ol
         FROM general g WHERE g.nation = %i AND g.officer_level < 12 ORDER BY g.no ASC', $nationID
    );
    foreach ($genRows as $gr) {
        $cid = (int)$gr['cid'];
        $nearCities = array_keys(CityConst::byID($cid)->path);
        if (!$nearCities) { continue; }
        $enemyNear = $db->queryFirstField(
            'SELECT city FROM city WHERE nation IN %li AND city IN %li LIMIT 1',
            [0, 2], $nearCities
        );
        if ($enemyNear) { $candidate = (int)$gr['gid']; $candidateCity = $cid; $destCity = (int)$enemyNear; break; }
    }

    if ($candidate === null) {
        // restore + backlog
        setGameClock($db, $savedY, $savedM);
        restoreDiplomacy($db, $savedDip);
        restoreCityFront($db, $savedFront);
        fwrite(STDERR, "  war-출병: NO nation-1 general borders an enemy city — cannot craft faithfully without fabricating adjacency. BACKLOG.\n");
        return false;
    }

    $savedGen = snapshotGeneralRow($db, $candidate);
    // make the city a real front (front=2 = '공격 가능한 전방') + supply
    $db->update('city', ['front' => 2, 'supply' => 1], 'city = %i', $candidateCity);
    // war-ready the general (the EXACT che_출병 precondition fields): full train/atmos/crew
    $db->update('general', [
        'train' => 100, 'atmos' => 100, 'crew' => 5000,
    ], '`no` = %i', $candidate);

    dumpCraftedWorld($db, 'war-출병', $hiddenSeed,
        "Clock 184/1 (past 183/5 war gate) + diplomacy 1↔2 교전 (state 0) + a nation-1 general (gid {$candidate}) at a front-2 city {$candidateCity} bordering enemy city {$destCity}, war-ready (train/atmos 100, crew 5000) → do출병 draws choice(attackableCities) (+ optional nextBool(0.7) rice gate). dipState=d전쟁, attackable=true.",
        $outDir);
    $fx = captureGeneral($db, $candidate, $hiddenSeed, $rpAiRng, 'war-출병', 0, $outDir, [
        '_destCity' => $destCity, '_candidateCity' => $candidateCity,
    ]);

    // restore (general row, city front, diplomacy, clock)
    $db->update('general', [
        'train' => (int)$savedGen['train'], 'atmos' => (int)$savedGen['atmos'], 'crew' => (int)$savedGen['crew'],
    ], '`no` = %i', $candidate);
    restoreCityFront($db, $savedFront);
    restoreDiplomacy($db, $savedDip);
    setGameClock($db, $savedY, $savedM);

    return $fx['reason'] === 'do출병';
}

/* ═══════════════════════════════════════════════════════════════════════════════════════════
 * FAMILY: genfound-방랑군 — a 방랑군 lord (officer_level==12, nation!=0, !capital) reaches
 *   do건국/do방랑군이동/do해산 (chooseGeneralTurn:3802-3827). This requires a NATION with capital=0
 *   (a wandering army) AND a lord assigned to it sitting at a non-owned city.
 *   FAITHFUL CRAFTABILITY: scenario 1010 installs NO wandering-army nation (both nations have a
 *   real capital: nation 1 cap 3, nation 2 cap 1). Creating one requires (a) a NEW nation row
 *   with capital=0 (the post-건국 pre-capital state), (b) re-homing a lord to it at a level-5/6
 *   non-owned city, (c) the matching nation_env policy KV the AI ctor cacheValues. The
 *   wandering-army state is produced by the 거병→건국 lifecycle, NOT by the installer. We can
 *   derive the EXACT row shape from che_거병/che_건국 (they SET capital, gennum, level, type,
 *   chief_set, aux), but reproducing the full 거병 side-effect (general re-home + nation create +
 *   nation_env policy seed + city.nation flip) faithfully is a multi-table lifecycle replay, not
 *   a single faithful mutation — doing it by hand risks fabricating the policy/aux KV the AI
 *   branches on. Per the parity law we BACKLOG this family with the precise reason rather than
 *   hand-fabricate the wandering-army nation_env.
 * ═══════════════════════════════════════════════════════════════════════════════════════════ */
function craft_genfound_방랑군($db, string $hiddenSeed, \ReflectionProperty $rpAiRng, string $outDir): bool {
    fwrite(STDERR, "[genfound-방랑군] BACKLOG (see GATE-DIVERGENCES.md)\n");
    return false;
}

/* ═══════════════════════════════════════════════════════════════════════════════════════════
 * FAMILY: genfound-선양 — npc==5 ruler abdicates (do선양, Q1 ORDER BY RAND, non-id bytes only).
 *   Precondition (chooseGeneralTurn:3745): officer_level==12 + generalPolicy->can선양 + npc==5.
 *   Q1: the target id is `SELECT no FROM general WHERE nation=%i AND npc!=5 ORDER BY RAND()` —
 *   OUTSIDE the LiteHashDRBG stream, so it is NOT a draw-for-draw byte. The crafted golden
 *   byte-matches only the NON-id bytes (actionCode che_선양 + reason do선양 + the empty draw
 *   stream); the id is asserted "valid member only" by the Kotlin replay.
 *   Faithful mutation: set lord gid 152 npc=5 (the exact npc-type the abdication path keys on).
 *   The census proof (GT1) stays valid because we RESTORE npc afterwards.
 * ═══════════════════════════════════════════════════════════════════════════════════════════ */
function craft_genfound_선양($db, string $hiddenSeed, \ReflectionProperty $rpAiRng, string $outDir): bool {
    fwrite(STDERR, "[genfound-선양] (Q1 non-id bytes only)\n");
    $lordGid = 152; $nationID = 1;
    $savedNpc = (int)$db->queryFirstField('SELECT npc FROM general WHERE `no` = %i', $lordGid);

    $db->update('general', ['npc' => 5], '`no` = %i', $lordGid);

    dumpCraftedWorld($db, 'genfound-선양', $hiddenSeed,
        "Lord 하진 (gid {$lordGid}, nation {$nationID}) set npc=5 (the abdication npc-type) → do선양. Q1: the destGeneralID is SELECT ... ORDER BY RAND() (OUTSIDE the LiteHashDRBG stream) — the gate byte-matches ONLY the NON-id bytes (che_선양 + reason + empty draw stream); the id is asserted 'valid nation member, npc!=5'.",
        $outDir);
    $fx = captureGeneral($db, $lordGid, $hiddenSeed, $rpAiRng, 'genfound-선양', 0, $outDir, [
        '_quarantineNonDraw' => 'destGeneralID is ORDER BY RAND (NOT a LiteHashDRBG draw); assert valid-member-only, NOT byte-for-byte (Q1 / AI-QUAR-ORDERBYRAND)',
    ]);

    $db->update('general', ['npc' => $savedNpc], '`no` = %i', $lordGid);

    // census must be restored
    $npc5after = (int)$db->queryFirstField('SELECT COUNT(*) FROM general WHERE npc=5');
    hardAssert($npc5after === 0, "FAILED to restore npc after 선양 craft (npc5={$npc5after}) — census invariant broken");

    // ── Q1 byte-stability fix: the do선양 destGeneralID is ORDER BY RAND() — NON-deterministic,
    // so the captured id is NOT byte-stable across runs. Re-emit the fixture with the id
    // REPLACED by a quarantine sentinel + the set of valid candidate ids, so the file IS
    // byte-identical twice and the Kotlin gate asserts membership (id ∈ validTargets, npc!=5),
    // NOT a literal byte. The DRAW STREAM (the load-bearing parity) is untouched.
    $validTargets = $db->queryFirstColumn(
        'SELECT `no` FROM general WHERE nation = %i AND npc != 5 ORDER BY `no` ASC', $nationID
    );
    $producedId = $fx['chosenRawArgs']['destGeneralID'] ?? null;
    $fixtureForReemit = $fx;
    if (is_array($fixtureForReemit['chosenRawArgs'])) {
        $fixtureForReemit['chosenRawArgs']['destGeneralID'] = '__ORDER_BY_RAND_QUARANTINED__';
    }
    $fixtureForReemit['_q1ValidTargets'] = array_map('intval', $validTargets);
    $fixtureForReemit['_q1ProducedIdWasValid'] = ($producedId !== null) && in_array((int)$producedId, array_map('intval', $validTargets), true);
    file_put_contents(
        $outDir . '/ai-crafted-genfound-선양-00.json',
        Json::encode($fixtureForReemit, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES | JSON_PRETTY_PRINT)
    );

    return ($fx['reason'] === 'do선양') && $fixtureForReemit['_q1ProducedIdWasValid'];
}

/* ═══════════════════════════════════════════════════════════════════════════════════════════
 * Driver
 * ═══════════════════════════════════════════════════════════════════════════════════════════ */
$families = [
    'diplo-불가침제의'  => fn() => craft_diplo_불가침제의($db, $hiddenSeed, $rpAiRng, $outDir),
    'diplo-선전포고'    => fn() => craft_diplo_선전포고($db, $hiddenSeed, $rpAiRng, $outDir),
    'diplo-천도'        => fn() => craft_diplo_천도($db, $hiddenSeed, $rpAiRng, $outDir),
    'nation-pass-m3'   => fn() => craft_nation_pass_month($db, $hiddenSeed, $rpAiRng, $outDir, 3),
    'nation-pass-m6'   => fn() => craft_nation_pass_month($db, $hiddenSeed, $rpAiRng, $outDir, 6),
    'nation-pass-m12'  => fn() => craft_nation_pass_month($db, $hiddenSeed, $rpAiRng, $outDir, 12),
    'war-출병'         => fn() => craft_war_출병($db, $hiddenSeed, $rpAiRng, $outDir),
    'genfound-방랑군'  => fn() => craft_genfound_방랑군($db, $hiddenSeed, $rpAiRng, $outDir),
    'genfound-선양'    => fn() => craft_genfound_선양($db, $hiddenSeed, $rpAiRng, $outDir),
];

foreach ($families as $fam => $fn) {
    if ($family !== 'ALL' && $family !== $fam) { continue; }
    $ok = $fn();
    if ($ok) { $capturedFamilies[] = $fam; } else { $backloggedFamilies[] = $fam; }
}

// ── final clock/census sanity: the DB must be back at the GT1 baseline ──
[$finalY, $finalM] = snapshotGameClock($db);
$finalNpc5 = (int)$db->queryFirstField('SELECT COUNT(*) FROM general WHERE npc=5');
hardAssert($finalY === 181 && $finalM === 1,
    "DB clock not restored to GT1 baseline (year={$finalY} month={$finalM}) — a craft did not restore");
hardAssert($finalNpc5 === 0, "DB npc5 not restored (npc5={$finalNpc5}) — census invariant broken");

fwrite(STDERR, "=== capture_ai_crafted DONE ===\n");
fwrite(STDERR, "CAPTURED families: " . (implode(', ', $capturedFamilies) ?: '(none)') . "\n");
fwrite(STDERR, "BACKLOGGED families: " . (implode(', ', $backloggedFamilies) ?: '(none)') . "\n");
fwrite(STDERR, "DB restored to GT1 baseline (year=181 month=1, npc5=0).\n");
