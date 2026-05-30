<?php
/**
 * capture_battle.php — P4 G1a battle draw-for-draw golden capture (devsam-core PHP, grand truth).
 *
 * ONE-SHOT, MANUAL HOST STEP — NEVER CI (devsam capture quirks; see README).
 *
 * Inlines the EXACT `simulateBattle()` body of hwe/j_simulate_battle.php (lines 366-476)
 * — repeatCnt=1, NO live DB write (the j_simulate getNextDefender does logger->rollback,
 * never applyDB; the city's applyDB is never called because we never run the
 * processWar() OUTER wrapper, only the inner processWar_NG via simulateBattle). The ONLY
 * deviation from j_simulate is the single line that builds the RNG:
 *
 *     $warRng = new RandUtilDrawRecorder(new LiteHashDRBG($warSeed));   // was: new RandUtil(...)
 *
 * RandUtilDrawRecorder `extends RandUtil` and is DRAW-NEUTRAL (verified byte-identical to
 * a bare RandUtil), so the battle plays out value-for-value as the live engine while the
 * recorder logs the ordered draw STREAM threaded into every WarUnit + every trigger fire.
 *
 * THE ARMIES ARE FAITHFUL: this is the SIMULATOR path (j_simulate_battle), which by
 * design takes hand-authored armies (the in-game battle simulator form). We author each
 * army from a REAL installed scenario_1010 general's stats (leadership/strength/intel/
 * specialty/crewtype pulled live from the DB) + the war parameters the simulator form
 * requires (crew/train/atmos/rice) — exactly what a player enters in the simulator UI.
 * personal='None' isolates the draw stream to the crewType + war-specialty (special2)
 * triggers (the P4 surface); no personality domestic hook is in the war path anyway.
 *
 * THE SEED IS FAITHFUL: warSeed is derived EXACTLY as che_출병.php:245-253 —
 *     pre = LiteHashDRBG(simpleSerialize(hiddenSeed,'war',year,month,attackerId,destCityId))
 *     warSeed = bin2hex(pre->nextBytes(16))
 * with the LIVE per-install hiddenSeed (committed as the golden fixture INPUT, exactly as
 * manifest_monthtick records the live monthly hiddenSeed) + a FIXED year/month/ids.
 *
 * Output (logic/src/test/resources/golden/p4/):
 *   battle-01.json — a general-vs-general exchange (조조 che_반계 vs 관우 che_위압)
 *   battle-02.json — a magic-heavy fixture (장각 che_환술, INT-93 → 계략 success folds up)
 *   Each: { seed, hiddenSeed, warSeedDerivation, attacker_input, defender_inputs[],
 *           defender_city_input, nations, draw_stream[] (the FULL ordered stream),
 *           phase_log[] (byte-exact 진격/퇴각/패퇴/전멸 + per-phase HP markup),
 *           post_state{attacker, city, defenders}, finishBattle_counters }
 *
 * Invocation (inside the php capture container, repo mounted at /work; scenario_1010
 * already installed by install_scenario.php so UniqueConst::$hiddenSeed + startYear exist):
 *   php tools/php-golden/capture_battle.php [--out-dir=logic/src/test/resources/golden/p4]
 *
 * BYTE-STABILITY: run TWICE and diff — the golden MUST be byte-identical (OQ #12). The
 * harness uses NO spl_object_id in the emitted golden (the recorder keys on seq, the
 * WarUnit identity is the general id), so the stream is reproducible across runs.
 */

namespace sammo;

require __DIR__ . '/_boot.php';
require __DIR__ . '/RandUtilDrawRecorder.php';

use sammo\Enums\RankColumn;

// See capture_monthtick.php — drop PHP-version noise levels so the headless error
// handler does not chase a web Session. Zero deterministic state change.
error_reporting(E_ALL & ~E_DEPRECATED & ~E_NOTICE & ~E_WARNING & ~E_USER_DEPRECATED & ~E_STRICT);

$opts   = getopt('', ['out-dir:']);
$outDir = $opts['out-dir'] ?? (__DIR__ . '/../../logic/src/test/resources/golden/p4');
if (!is_dir($outDir)) { mkdir($outDir, 0775, true); }

$db = DB::db();

function hardAssert(bool $cond, string $msg): void {
    if (!$cond) { fwrite(STDERR, "G1a HARD-ASSERT FAILED: {$msg}\n"); exit(2); }
}

// PIN the hiddenSeed to the plan's fixed live config value so the warSeed is REPRODUCIBLE
// against ANY scenario_1010 install (buildScenario draws a fresh random hiddenSeed each
// install). The hiddenSeed is a fixed per-game config value
// (d_setting/UniqueConst.php::$hiddenSeed); pinning it is faithful, and the plan specifies
// this exact hex as the golden fixture INPUT. The warSeed derivation (che_출병.php:245-253)
// then yields a stable per-fixture seed regardless of install.
UniqueConst::$hiddenSeed = '8ebfeb6fa932a181ec9ef43b7473f4c9';
$hiddenSeed = UniqueConst::$hiddenSeed;
hardAssert(preg_match('/^[0-9a-f]{32}$/', $hiddenSeed) === 1,
    "hiddenSeed is not a 32-char lowercase hex: {$hiddenSeed}");

$gameStor = KVStorage::getStorage($db, 'game_env');
$startYear = (int)$gameStor->startyear;
hardAssert($startYear > 0, "startYear not set — install scenario_1010 first");

/** extractRankVar — verbatim from j_simulate_battle.php:353-364. */
function extractRankVar(array $rawVal): \Ds\Map
{
    $rankVars = new \Ds\Map;
    foreach ($rawVal as $rawKey => $rawV) {
        $key = RankColumn::tryFrom($rawKey);
        if ($key === null) {
            continue;
        }
        $rankVars[$key] = $rawV;
    }
    return $rankVars;
}

/**
 * simulateBattle — VERBATIM copy of j_simulate_battle.php:366-476, with the ONE RNG line
 * swapped to RandUtilDrawRecorder. Returns the recorder too so the caller can dump the stream.
 */
function simulateBattleRecorded(
    $rawAttacker,
    $rawAttackerCity,
    $rawAttackerNation,
    $rawDefenderList,
    $rawDefenderCity,
    $rawDefenderNation,
    $startYear,
    $year,
    $month,
    $warSeed
): array {
    $warRng = new RandUtilDrawRecorder(new LiteHashDRBG($warSeed));   // ← the ONLY change vs j_simulate

    $attackerGeneral = new General($rawAttacker, extractRankVar($rawAttacker), null, $rawAttackerCity, $rawAttackerNation, $year, $month);
    $attacker = new WarUnitGeneral(
        $warRng,
        $attackerGeneral,
        $rawAttackerNation,
        true
    );
    $city = new WarUnitCity($warRng, $rawDefenderCity, $rawDefenderNation, $year, $month, $startYear);

    /** @var WarUnit[] */
    $defenderList = [];

    foreach ($rawDefenderList as $rawDefenderGeneral) {
        $defenderGeneral = new General($rawDefenderGeneral, extractRankVar($rawDefenderGeneral), null, $rawDefenderCity, $rawAttackerNation, $year, $month, true);

        $defenderList[] = new WarUnitGeneral(
            $warRng,
            $defenderGeneral,
            $rawDefenderNation,
            false
        );
    }

    if (count($defenderList) && extractBattleOrder($city, $attacker) > 0) {
        $defenderList[] = $city;
    }

    usort($defenderList, function (WarUnit $lhs, WarUnit $rhs) use ($attacker) {
        return - (extractBattleOrder($lhs, $attacker) <=> extractBattleOrder($rhs, $attacker));
    });

    $iterDefender = new \ArrayIterator($defenderList);
    $iterDefender->rewind();

    $battleResult = [];

    $attackerRice = $attacker->getGeneral()->getVar('rice');
    $defenderRice = 0;

    $getNextDefender = function (?WarUnit $prevDefender, bool $reqNext)
    use ($iterDefender, &$battleResult, &$defenderRice, $attacker): ?WarUnit {
        if ($prevDefender !== null) {
            $prevDefender->getLogger()->rollback();
            $battleResult[] = $prevDefender;
            if ($prevDefender instanceof WarUnitGeneral) {
                $defenderRice -= $prevDefender->getVar('rice');
            }
        }

        if (!$reqNext) {
            return null;
        }

        if (!$iterDefender->valid()) {
            return null;
        }

        /** @var WarUnit */
        $defender = $iterDefender->current();

        if (extractBattleOrder($defender, $attacker) <= 0) {
            return null;
        }

        if ($defender instanceof WarUnitGeneral) {
            $defenderRice += $defender->getVar('rice');
        }

        $iterDefender->next();
        return $defender;
    };

    $conquerCity = processWar_NG($warSeed, $attacker, $getNextDefender, $city);

    $attackerRice -= $attacker->getVar('rice');

    if ($city->getPhase() > 0) {
        $rice = $city->getKilled() / 100 * 0.8;
        $rice *= $city->getCrewType()->rice;
        $rice *= getTechCost($city->getRawNation()['tech']);
        $rice *= $city->getCityTrainAtmos() / 100 - 0.2;
        Util::setRound($rice);

        $defenderRice += $rice;
    }

    return [$attacker, $city, $battleResult, $conquerCity, $attackerRice, $defenderRice, $warRng];
}

/**
 * Build a faithful army raw-row from a REAL installed general + the simulator war params.
 * Pulls the general's identity/stats live from the DB; overlays the crew/train/atmos/rice
 * a player would enter in the simulator. personal='None' isolates the war-specialty draws.
 */
function buildArmy(object $db, int $generalId, int $crew, int $crewtype, int $train, int $atmos, int $rice, string $turntime, int $homeCityId): array
{
    $g = $db->queryFirstRow('SELECT * FROM general WHERE no=%i', $generalId);
    hardAssert($g !== null, "general {$generalId} not found — install scenario_1010 first");

    // PIN the install-random, battle-IRRELEVANT fields so the golden is reproducible across
    // installs (buildScenario randomizes general placement + the killturn auto-kick countdown).
    // The battle reads the city context from the explicit attackerCity/defenderCity raw rows
    // (passed separately to simulateBattle), NOT the general's own `city` field; and killturn
    // is never read by processWar_NG. Pinning these changes ZERO computed battle value.
    $g['city']     = $homeCityId;
    $g['killturn'] = 0;

    // Overlay simulator war parameters (the form inputs). Everything else is the real
    // installed general (name/leadership/strength/intel/special2/dex/etc).
    $g['crew']     = $crew;
    $g['crewtype'] = $crewtype;
    $g['train']    = $train;
    $g['atmos']    = $atmos;
    $g['rice']     = $rice;
    $g['gold']     = max((int)$g['gold'], 1000);
    $g['injury']   = 0;
    $g['personal'] = 'None';            // module-free personality (valid optionalPersonality)
    $g['defence_train'] = (int)($g['defence_train'] ?? 80);
    // j_simulate sets turntime = TimeUtil::now() (wall-clock) — the ONE non-deterministic
    // input. We PIN it to a fixed timestamp so the golden is byte-stable across runs
    // (OQ #12). turntime is only echoed into the 진격 log's <1>$date</> via getTurnTime
    // and stored to recent_war; pinning it keeps both deterministic.
    $g['turntime'] = $turntime;
    $g['recent_war'] = $g['recent_war'] ?? '';
    $g['owner']    = 0;
    $g['aux']      = Json::encode([]);  // no inheritBuff (j_simulate:259,271)

    return $g;
}

/** Pull a city raw-row live from the DB (the defender city). */
function pullCity(object $db, int $cityId): array
{
    $c = $db->queryFirstRow('SELECT * FROM city WHERE city=%i', $cityId);
    hardAssert($c !== null, "city {$cityId} not found");
    return $c;
}

/** Pull a nation raw-row live (the 9 fields simulateBattle/WarUnit read). */
function pullNation(object $db, int $nationId): array
{
    $n = $db->queryFirstRow(
        'SELECT nation,`level`,`name`,capital,gennum,tech,`type`,gold,rice FROM nation WHERE nation=%i',
        $nationId
    );
    hardAssert($n !== null, "nation {$nationId} not found");
    return $n;
}

/** Per-unit post-state + finishBattle rank counters (the gate-b oracle). */
function unitPostState(WarUnit $u): array
{
    $g = $u->getGeneral();
    $out = [
        'name'     => $u->getName(),
        'isAttacker' => $u->isAttacker(),
        'phase'    => $u->getPhase(),
        'killed'   => $u->getKilled(),
        'dead'     => $u->getDead(),
        'hp'       => $u->getHP(),
        'crewTypeName' => $u->getCrewTypeName(),
        'activatedSkillLog' => $u->getActivatedSkillLog(),
    ];
    if ($u instanceof WarUnitGeneral) {
        // the updated general vars (crew/rice/injury/atmos/train/exp/ded) + rank counters
        $out['vars'] = [
            'crew'       => $g->getVar('crew'),
            'rice'       => $g->getVar('rice'),
            'injury'     => $g->getVar('injury'),
            'atmos'      => $g->getVar('atmos'),
            'train'      => $g->getVar('train'),
            'experience' => $g->getVar('experience'),
            'dedication' => $g->getVar('dedication'),
            'strength_exp' => $g->getVar('strength_exp'),
            'intel_exp'    => $g->getVar('intel_exp'),
            'leadership_exp' => $g->getVar('leadership_exp'),
            'dex1' => $g->getVar('dex1'), 'dex2' => $g->getVar('dex2'),
            'dex3' => $g->getVar('dex3'), 'dex4' => $g->getVar('dex4'),
            'dex5' => $g->getVar('dex5'),
        ];
        // Rank counters live in a separate rank store; an untouched counter reads via the
        // default (0). The battle's increaseRankVar writes land in rankVarSet, surfaced here.
        $out['rankCounters'] = [
            'warnum'           => (int)$g->getRankVar(RankColumn::warnum, 0),
            'killnum'          => (int)$g->getRankVar(RankColumn::killnum, 0),
            'deathnum'         => (int)$g->getRankVar(RankColumn::deathnum, 0),
            'killcrew'         => (int)$g->getRankVar(RankColumn::killcrew, 0),
            'deathcrew'        => (int)$g->getRankVar(RankColumn::deathcrew, 0),
            'killcrew_person'  => (int)$g->getRankVar(RankColumn::killcrew_person, 0),
            'deathcrew_person' => (int)$g->getRankVar(RankColumn::deathcrew_person, 0),
            'occupied'         => (int)$g->getRankVar(RankColumn::occupied, 0),
        ];
    } else {
        // WarUnitCity post-state
        $out['cityVars'] = [
            'def'  => $u->getVar('def'),
            'wall' => $u->getVar('wall'),
            'agri' => $u->getVar('agri'),
            'comm' => $u->getVar('comm'),
            'secu' => $u->getVar('secu'),
            'conflict' => $u->getVar('conflict'),
        ];
    }
    return $out;
}

/** Run one fixture, derive the seed faithfully, emit the golden. */
function captureFixture(
    object $db, string $hiddenSeed, int $startYear, string $outFile, string $label,
    int $attackerId, int $attackerCityId, int $attackerNationId,
    array $defenders,   // [ [generalId, crew, crewtype, train, atmos, rice], ... ]
    int $defenderCityId, int $defenderNationId,
    int $atkCrew, int $atkCrewtype, int $atkTrain, int $atkAtmos, int $atkRice,
    int $year, int $month
): void {
    // PINNED turntime — fixed timestamp derived from the fixed year/month bucket so the
    // golden is byte-stable (the only wall-clock input j_simulate would inject).
    $turntime = sprintf('%04d-%02d-01 00:00:00', $year, $month);

    $rawAttacker     = buildArmy($db, $attackerId, $atkCrew, $atkCrewtype, $atkTrain, $atkAtmos, $atkRice, $turntime, $attackerCityId);
    $rawAttackerCity = pullCity($db, $attackerCityId);
    $rawAttackerNation = pullNation($db, $attackerNationId);

    $rawDefenderList = [];
    foreach ($defenders as $d) {
        // defender's home city = the defender city context (where they're besieged).
        $rawDefenderList[] = buildArmy($db, $d[0], $d[1], $d[2], $d[3], $d[4], $d[5], $turntime, $defenderCityId);
    }
    $rawDefenderCity   = pullCity($db, $defenderCityId);
    $rawDefenderNation = pullNation($db, $defenderNationId);

    // warSeed — EXACT che_출병.php:245-253 derivation.
    $pre = new LiteHashDRBG(Util::simpleSerialize($hiddenSeed, 'war', $year, $month, $attackerId, $defenderCityId));
    $warSeed = bin2hex($pre->nextBytes(16));

    [$attacker, $city, $battleResult, $conquerCity, $attackerRice, $defenderRice, $warRng] = simulateBattleRecorded(
        $rawAttacker,
        $rawAttackerCity,
        $rawAttackerNation,
        $rawDefenderList,
        $rawDefenderCity,
        $rawDefenderNation,
        $startYear,
        $year,
        $month,
        $warSeed
    );

    // phase log — the attacker logger's rolled-back buffer (byte-exact lines, in order).
    // getLogger()->rollback() returns the accumulated log keyed by type; we flatten the
    // battle-detail + action lines in order, NOT ConvertLog'd (raw color/tag markup so the
    // Kotlin BattleLogTokens byte-match is against the RAW template output).
    $phaseLog = collectPhaseLog($attacker, $battleResult);

    $defenderStates = [];
    foreach ($battleResult as $d) {
        $defenderStates[] = unitPostState($d);
    }

    $golden = [
        'label'      => $label,
        'hiddenSeed' => $hiddenSeed,             // fixture INPUT (per-install live value)
        'startYear'  => $startYear,
        'year'       => $year,
        'month'      => $month,
        'warSeed'    => $warSeed,
        'warSeedDerivation' => "bin2hex(LiteHashDRBG(simpleSerialize(hiddenSeed,'war',{$year},{$month},{$attackerId},{$defenderCityId})).nextBytes(16))",
        'attacker_input'      => $rawAttacker,
        'defender_inputs'     => $rawDefenderList,
        'attacker_city_input' => $rawAttackerCity,
        'defender_city_input' => $rawDefenderCity,
        'attacker_nation'     => $rawAttackerNation,
        'defender_nation'     => $rawDefenderNation,
        'conquerCity'         => $conquerCity,
        'draw_stream'         => $warRng->getDrawStream(),
        'draw_count'          => $warRng->getDrawCount(),
        'phase_log'           => $phaseLog,
        'post_state'          => [
            'attacker'  => unitPostState($attacker),
            'city'      => unitPostState($city),
            'defenders' => $defenderStates,
        ],
        'attackerRiceConsumed' => $attackerRice,
        'defenderRiceConsumed' => $defenderRice,
    ];

    file_put_contents(
        $outFile,
        Json::encode($golden, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES | JSON_PRETTY_PRINT)
    );
    fwrite(STDERR, sprintf(
        "wrote %s (label=%s warSeed=%s draws=%d phases=%d conquer=%s)\n",
        basename($outFile), $label, $warSeed, $warRng->getDrawCount(),
        $attacker->getPhase(), $conquerCity ? 'true' : 'false'
    ));
}

/**
 * Collect the byte-exact phase log lines. The attacker logger accumulates battle-detail
 * (per-phase HP markup) + action + global lines; we read its rolled-back buffer in order.
 * We emit the RAW lines (no ConvertLog) so the Kotlin port matches the template output.
 */
function collectPhaseLog(WarUnitGeneral $attacker, array $battleResult): array
{
    // The attacker's logger already had rollback() consumed by simulateBattle's lastWarLog
    // step in j_simulate; here we rollback ONCE from the attacker logger to grab the buffer.
    // (Defender loggers were rolled back inside getNextDefender.)
    $log = $attacker->getLogger()->rollback();
    // $log is keyed by log-type bucket (action/battleDetail/...). Flatten preserving the
    // per-bucket order; tag each line with its bucket so the Kotlin assertion can match
    // the battleDetail sequence (the per-phase HP lines) precisely.
    $out = [];
    foreach ($log as $bucket => $lines) {
        if (!is_array($lines)) { $lines = [$lines]; }
        foreach ($lines as $line) {
            $out[] = ['bucket' => (string)$bucket, 'text' => $line];
        }
    }
    return $out;
}

// ── FIXTURES ─────────────────────────────────────────────────────────────────────────
// crewtype 1100 = the standard footman crew the scenario generals carry. Train/atmos at
// the war cap (the simulator's typical max) so extractBattleOrder>0 and the fight is
// non-trivial. Year 200 / month 1 → a fixed mid-game warSeed bucket.

$FOOTMAN = 1100;

// battle-01: 조조(no=132, che_반계, L100/S80/I95) attacks 관우(no=18, che_위압, L96/S98/I80)
//            sitting in 홍농(city=42). General-vs-general exchange exercising 반계/위압
//            + the always-present base 필살시도/회피시도/계략시도 draws.
captureFixture(
    $db, $hiddenSeed, $startYear,
    $outDir . '/battle-01.json', 'jojo_vs_gwanu_normal_exchange',
    132, 17, 1,                                   // attacker 조조, his city 계(17), nation 후한(1)
    [[18, 5000, $FOOTMAN, 100, 100, 3000]],       // defender 관우 5000 crew
    42, 1,                                         // defender city 홍농(42), nation 후한(1)
    5000, $FOOTMAN, 100, 100, 3000,               // attacker army 5000 crew
    200, 1
);

// battle-02: 장각(no=105, che_환술, L93/S25/I93) on 귀병(1400, armType=4 wizard, magicCoef
//            0.5) — the canonical 환술 army. INT-93 × magicCoef 0.5 = trialProb 0.465; the
//            phase-0 rawIntel*3(279) >= allStat(211) ×3 boost + 환술's warMagicTrialProb
//            fold push it past 1.0 → 계략시도 trial fires → the choice(magicTable) draw +
//            the nextBool(successProb) success/fail draw are EXERCISED (the conditional
//            magic sub-stream the F3/A3 port must reproduce; pins success-folds-:74-75 vs
//            fail-stores-RAW-:85 asymmetry). Attacks 장량(no=110, che_환술) likewise on 귀병.
$WIZARD = 1400;
captureFixture(
    $db, $hiddenSeed, $startYear,
    $outDir . '/battle-02.json', 'janggak_magic_heavy',
    105, 80, 2,                                   // attacker 장각, city 관도(80), nation 황건적(2)
    [[110, 4000, $WIZARD, 100, 100, 3000]],       // defender 장량(110) 4000 crew, 귀병
    80, 2,                                         // defender city 관도(80) itself (siege)
    6000, $WIZARD, 100, 100, 3000,                // attacker army 6000 crew, 귀병
    200, 1
);

fwrite(STDERR, "DONE: battle fixtures written to {$outDir}\n");
