<?php
/**
 * capture_general_builder.php — Scenario GeneralBuilder mint-path draw golden capture
 * (devsam-core PHP, grand truth). ONE-SHOT, MANUAL HOST STEP — NEVER CI.
 *
 * Sibling of capture_join.php (the closest analog — both record the create-general inline draws
 * via RandUtilDrawRecorder). DISTINCT path: this captures the SCENARIO-time mint path
 * hwe/sammo/Scenario/GeneralBuilder.php (build() + the fillRemainSpec* / fillRandomStat / setSpecialOption
 * helpers it calls), NOT the entrance Join.php / MakeGeneral path captured by capture_join.php.
 * The two have DIFFERENT draw sequences — do NOT conflate them.
 *
 * WHAT IS CAPTURED (GeneralBuilder.php — REAL code path, not re-implemented):
 *   Each case wires a GeneralBuilder EXACTLY like a real caller wires it (RegNPC / che_인재탐색 /
 *   CreateManyNPC / setSpecialOption), wraps the SAME RandUtil(LiteHashDRBG(seed)) in
 *   RandUtilDrawRecorder, and runs the REAL fill*()/build() methods. build() genuinely INSERTs
 *   general/general_turn/rank_data; we snapshot MAX(general.no) before, read the inserted row after,
 *   then DELETE the created rows so the install stays clean + the capture is idempotent (scenario
 *   tables are ENGINE=Aria → non-transactional, so rollback can't undo the INSERT; an explicit
 *   delete-by-insertId is the faithful cleanup). The draws + row are 100% REAL PHP — the only thing
 *   not persisted is the (intentionally rolled-back) general row itself.
 *
 * DRAW SEQUENCES (verified against GeneralBuilder.php / SpecialityHelper.php / func.php:getRandTurn —
 * the recorder records the ACTUAL order; the per-case HARD assertions verify it matches the source):
 *
 *   setSpecialOption('랜덤') (:122-129):
 *     nextBool(2/3) → if true pickSpecialWar substream, else pickSpecialDomestic substream.
 *       pickSpecialWar (SpecialityHelper:259) draws: calcCondDexterity = nextBool(0.8)
 *         [→ if false: nextRangeInt(0,99); → if dexSum==0: choice(dex); else choice(maxdex)],
 *         then ONE choiceUsingWeight (reqDex / pAbs / pRel select).
 *       pickSpecialDomestic (SpecialityHelper:198) draws: ONE-OR-TWO choiceUsingWeight (pAbs then pRel).
 *
 *   fillRandomStat(pickTypeList) (:313-345):
 *     choiceUsingWeight(pickTypeList) → nextRangeInt(0, defaultStatNPCMin=10) [mainStat]
 *       → nextRangeInt(0, toInt(defaultStatNPCMin/2)=5) [otherStat].
 *
 *   fillRemainSpecAsZero(env) (:347-398):
 *     affinity null/0 → nextRangeInt(1,150); ego null → choice(availablePersonality).
 *     (birth/specAge/specAge2/officerLevel/special-fields/killturn are deterministic — no draws.)
 *
 *   fillRemainSpecAsRandom(pickTypeList, avgGen, env) (:400-500):
 *     affinity null/0 OR fiction → nextRangeInt(1,150);
 *     birth null → nextRange(-5,5) [birth] + nextRangeInt(60,80) [death];
 *     stat null → fillRandomStat (3 draws above), else deterministic/choiceUsingWeight pickType;
 *     dex unset & avgGen has dex_t → choice(dexArrays) WHEN pickType=='무' (지/무지 deterministic);
 *     ego null OR fiction → choice(availablePersonality).
 *
 *   build(env) (:542-737):
 *     cityID null → choice(getAllNationCities(n) | getAllCities());
 *     getRandTurn(rng,turnterm,base) = nextRangeInt(0, 60*turnterm-1) + nextRangeInt(0, 999999);
 *     killturn null & owner==0 & birth!=null → nextRangeInt(0,11).
 *
 * STATIC-INPUT PRECONDITIONS (sibling-capture spirit — the COMPUTED golden is 100% real PHP; only the
 * inputs are positioned to a reachable, reproducible mid-game state):
 *   - env = $gameStor->getAll() VERBATIM (the EXACT env a real turn passes to build(), incl. fiction/
 *     turnterm/turntime/year/month/startyear/scenario/show_img_level/icon_path). Never fabricated.
 *   - per-case seedString = Util::simpleSerialize(hiddenSeed, '<scope>', …) mirroring the real caller's
 *     seed (RegNPC / CreateManyNPC / RaiseNPCNation / a 인재탐색-shaped per-NPC scope). Pinned + recorded.
 *   - generalName fixed per case (chosen to NOT collide with existing scenario names; build() prepends
 *     the npc-prefix). avgGen for the random case = the REAL `SELECT avg(...) FROM general WHERE npc<4`
 *     che_인재탐색 runs (not invented).
 *
 * HARD assertions (faithful-never-fabricate — abort, emit nothing, on any failure):
 *   - the created general/general_turn/rank_data rows are fully removed afterward (idempotent capture).
 *   - per-case draw stream order matches the GeneralBuilder source order (method/args).
 *   - general_turn row count == GameConst::$maxTurn; rank_data row count == count(RankColumn::cases()).
 *   - two in-process builds on the same seed → byte-identical draw stream + outcome.
 *   - age >= adultAge (build() returns the inserted general; <adultAge would return false = reserved).
 *
 * Invocation (inside the php capture container, repo mounted at /work):
 *   php tools/php-golden/capture_general_builder.php
 *       [--out=logic/src/test/resources/golden/scenario/장수빌더-fixtures.json]
 */

namespace sammo;

require __DIR__ . '/_boot.php';
require __DIR__ . '/RandUtilDrawRecorder.php';

use sammo\Scenario\GeneralBuilder;
use sammo\Enums\RankColumn;

$opts    = getopt('', ['out:']);
$outPath = $opts['out'] ?? (__DIR__ . '/../../logic/src/test/resources/golden/scenario/장수빌더-fixtures.json');
$outDir  = dirname($outPath);
if (!is_dir($outDir)) { mkdir($outDir, 0775, true); }

function hardAssert(bool $cond, string $msg): void {
    if (!$cond) { fwrite(STDERR, "GENERALBUILDER HARD-ASSERT FAILED: {$msg}\n"); exit(2); }
}

$db = DB::db();
$hiddenSeed = UniqueConst::$hiddenSeed;
$gameStor = KVStorage::getStorage($db, 'game_env');

// env = the EXACT KV env a real turn passes to GeneralBuilder::build() (TurnExecutionHelper.php:256
// `$env = $gameStor->getAll()`). Never trimmed/fabricated.
$env = $gameStor->getAll();
$year      = (int)$env['year'];
$month     = (int)$env['month'];
$startYear = (int)$env['startyear'];
$scenario  = (int)$env['scenario'];
$turnterm  = (int)$env['turnterm'];
$turntime  = (string)$env['turntime'];

hardAssert($scenario >= 1000, "scenario({$scenario}) < 1000 — specage branch differs; capture targets scenario_1010");

// avgGen — the REAL aggregate che_인재탐색.php:171-175 computes (random-fill dex distribution input).
$avgGen = $db->queryFirstRow(
    'SELECT avg(dedication) as ded,avg(experience) as exp,
     avg(dex1+dex2+dex3+dex4) as dex_t, avg(age) as age, avg(dex5) as dex5
     from general where npc < 4'
);
hardAssert(is_array($avgGen) && $avgGen['dex_t'] !== null, 'avgGen aggregate empty — random-fill dex draw input missing');

$availablePersonality = GameConst::$availablePersonality;
$maxTurn   = GameConst::$maxTurn;
$rankCount = count(RankColumn::cases());

/**
 * Run ONE builder case through the recorder + REAL build(), capture the inserted row, then delete it.
 * $setup($builder) wires the builder exactly like the modeled real caller; $fill($builder) runs the
 * REAL fill*() helper(s). build() then INSERTs — we read the row and roll it back via delete-by-id.
 *
 * @return array{0:array,1:array,2:bool}  [drawStream, outcomeRow, buildResult]
 */
function runBuilderCase(
    string $seedString, string $generalName, int $nationID, int $npcType,
    callable $setup, callable $fill, array $env, int $maxTurn, int $rankCount
): array {
    global $db;
    $rng = new RandUtilDrawRecorder(new LiteHashDRBG($seedString));
    $builder = new GeneralBuilder($rng, $generalName, false, null, $nationID);
    $builder->setNPCType($npcType);
    $setup($builder);
    $fill($builder);

    $maxNoBefore = (int)$db->queryFirstField('SELECT COALESCE(MAX(`no`),0) FROM general');
    $buildResult = $builder->build($env);

    $row = null;
    if ($buildResult === true) {
        $newNo = (int)$db->queryFirstField('SELECT COALESCE(MAX(`no`),0) FROM general');
        if ($newNo > $maxNoBefore) {
            $row = $db->queryFirstRow('SELECT * FROM general WHERE `no`=%i', $newNo);
            $turnRows = (int)$db->queryFirstField('SELECT count(*) FROM general_turn WHERE general_id=%i', $newNo);
            $rankRows = (int)$db->queryFirstField('SELECT count(*) FROM rank_data WHERE general_id=%i', $newNo);
            hardAssert($turnRows === $maxTurn, "general_turn rows {$turnRows} != maxTurn {$maxTurn} for {$generalName}");
            hardAssert($rankRows === $rankCount, "rank_data rows {$rankRows} != rankCount {$rankCount} for {$generalName}");
            // roll back (Aria = non-transactional → explicit delete by insertId).
            $db->query('DELETE FROM general WHERE `no`=%i', $newNo);
            $db->query('DELETE FROM general_turn WHERE general_id=%i', $newNo);
            $db->query('DELETE FROM rank_data WHERE general_id=%i', $newNo);
            $stillThere = (int)$db->queryFirstField('SELECT count(*) FROM general WHERE `no`=%i', $newNo);
            hardAssert($stillThere === 0, "failed to roll back created general {$newNo} ({$generalName})");
        }
    }

    $outcome = null;
    if ($row !== null) {
        // The outcome row fields the Kotlin port must reproduce.
        $outcome = [
            'name'            => $row['name'],
            'npc'             => (int)$row['npc'],
            'nation'          => (int)$row['nation'],
            'city'            => (int)$row['city'],
            'leadership'      => (int)$row['leadership'],
            'strength'        => (int)$row['strength'],
            'intel'           => (int)$row['intel'],
            'affinity'        => (int)$row['affinity'],
            'personal'        => $row['personal'],
            'special'         => $row['special'],     // domestic
            'special2'        => $row['special2'],    // war
            'specage'         => (int)$row['specage'],
            'specage2'        => (int)$row['specage2'],
            'bornyear'        => (int)$row['bornyear'],
            'deadyear'        => (int)$row['deadyear'],
            'age'             => (int)$row['age'],
            'officer_level'   => (int)$row['officer_level'],
            'killturn'        => (int)$row['killturn'],
            'turntime'        => $row['turntime'],
            'experience'      => (int)$row['experience'],
            'dedication'      => (int)$row['dedication'],
            'dex1'            => (int)$row['dex1'],
            'dex2'            => (int)$row['dex2'],
            'dex3'            => (int)$row['dex3'],
            'dex4'            => (int)$row['dex4'],
            'dex5'            => (int)$row['dex5'],
            'gold'            => (int)$row['gold'],
            'rice'            => (int)$row['rice'],
        ];
    }

    return [$rng->getDrawStream(), $outcome, $buildResult];
}

/**
 * Capture a case + assert two-build reproducibility, then return the fixture record.
 */
function captureCase(
    string $caseId, string $desc, string $fillPath,
    string $seedString, string $generalName, int $nationID, int $npcType,
    callable $setup, callable $fill, array $env, int $maxTurn, int $rankCount
): array {
    [$streamA, $outcomeA, $resultA] = runBuilderCase($seedString, $generalName, $nationID, $npcType, $setup, $fill, $env, $maxTurn, $rankCount);
    [$streamB, $outcomeB, $resultB] = runBuilderCase($seedString, $generalName, $nationID, $npcType, $setup, $fill, $env, $maxTurn, $rankCount);

    hardAssert(Json::encode($streamA) === Json::encode($streamB),
        "case {$caseId}: draw stream not reproducible across two in-process builds");
    hardAssert(Json::encode($outcomeA) === Json::encode($outcomeB),
        "case {$caseId}: outcome row not reproducible across two in-process builds");
    hardAssert($resultA === true, "case {$caseId}: build() returned non-true ({$resultA}) — general was reserved/dead, no row to capture (PLAN-MISS — do not fabricate)");
    hardAssert($outcomeA !== null, "case {$caseId}: build() returned true but no row captured");
    hardAssert($outcomeA['age'] >= GameConst::$adultAge, "case {$caseId}: age {$outcomeA['age']} < adultAge — would be reserved");

    return [
        'caseId'      => $caseId,
        'desc'        => $desc,
        'fillPath'    => $fillPath,
        'seedString'  => $seedString,
        'generalName' => $generalName,
        'nationID'    => $nationID,
        'npcType'     => $npcType,
        'draw_count'  => count($streamA),
        'draws'       => $streamA,
        'outcome'     => $outcomeA,
    ];
}

// ── case set ──────────────────────────────────────────────────────────────────
// Each case is wired EXACTLY like a real GeneralBuilder caller. Seed scope mirrors that caller.
$cases = [];

// CASE A — che_인재탐색 full-random path (fillRemainSpecAsRandom), city UNSPECIFIED, nation 0 (neutral).
//   setSpecial('None','None') + setNPCType(3) + setMoney + setLifeSpan + setSpecYear, then random fill.
//   birth IS set (setLifeSpan) → no birth/death draws; stat null → fillRandomStat; ego null → choice;
//   dex via choice WHEN pickType=='무'. build(): city choice + getRandTurn(2) + killturn(owner=0).
{
    $age = 22; // 인재탐색 picks nextRangeInt(20,25); we pin a representative adult age via setLifeSpan.
    $birthYear = $year - $age;
    $deathYear = $year + 30;
    $seedString = Util::simpleSerialize($hiddenSeed, 'GeneralBuilderScout', $year, $month, 'A_랜덤장수');
    $cases[] = captureCase(
        'A', '인재탐색-shaped full-random (fillRemainSpecAsRandom), city unspecified, neutral nation',
        'fillRemainSpecAsRandom',
        $seedString, 'A랜덤장수', 0, 3,
        function (GeneralBuilder $b) use ($birthYear, $deathYear, $age) {
            $b->setSpecial('None', 'None');
            $b->setMoney(1000, 1000);
            $b->setLifeSpan($birthYear, $deathYear);
            $b->setSpecYear(
                Util::round((GameConst::$retirementYear - $age) / 12) + $age,
                Util::round((GameConst::$retirementYear - $age) / 6) + $age
            );
        },
        function (GeneralBuilder $b) use ($avgGen, $env) {
            $b->fillRemainSpecAsRandom(['무' => 6, '지' => 6, '무지' => 3], $avgGen, $env);
        },
        $env, $maxTurn, $rankCount
    );
}

// CASE B — RegNPC-shaped fillRemainSpecAsZero, city SPECIFIED (setCity), explicit stat, affinity>0.
//   stat set + birth set + affinity>0 (no affinity draw) + ego null (choice). build(): cityID SET →
//   NO city choice; getRandTurn(2); killturn owner=0 → nextRangeInt(0,11). Isolates city-specified vs
//   city-unspecified (vs case A) AND the no-affinity-draw branch.
{
    $birthYear = $year - 40;
    $deathYear = $year + 40;
    $someCity  = (int)$db->queryFirstField('SELECT city FROM city ORDER BY city ASC LIMIT 1');
    hardAssert($someCity > 0, 'no city available for setCity (case B)');
    $seedString = Util::simpleSerialize($hiddenSeed, 'RegNPC', 'B고정장수', 0, 70, 65, 60);
    $cases[] = captureCase(
        'B', 'RegNPC-shaped fillRemainSpecAsZero, city SPECIFIED, explicit stat, affinity preset (no affinity/stat draw)',
        'fillRemainSpecAsZero',
        $seedString, 'B고정장수', 0, 2,
        function (GeneralBuilder $b) use ($someCity, $birthYear, $deathYear) {
            $b->setCity($someCity);
            $b->setStat(70, 65, 60);
            $b->setOfficerLevel(0);
            $b->setEgo(null);
            $b->setSpecialSingle('');     // → default domestic/war (deterministic)
            $b->setNPCText('');
            $b->setAffinity(77);          // 1<=affinity<=150 → kept verbatim, NO nextRangeInt draw
            $b->setLifeSpan($birthYear, $deathYear);
        },
        function (GeneralBuilder $b) use ($env) {
            $b->fillRemainSpecAsZero($env);
        },
        $env, $maxTurn, $rankCount
    );
}

// CASE C — CreateManyNPC-shaped fillRandomStat + fillRemainSpecAsZero, city UNSPECIFIED, neutral.
//   Exercises fillRandomStat (choiceUsingWeight + 2 nextRangeInt) THEN fillRemainSpecAsZero
//   (affinity nextRangeInt(1,150) + ego choice — affinity NOT preset here) THEN build city choice +
//   getRandTurn(2) + killturn(0,11). Distinct from A: Zero (not Random) remainder + explicit stat fill.
{
    $birthYear = $year - 30;
    $deathYear = $year + 50;
    $seedString = Util::simpleSerialize($hiddenSeed, 'CreateManyNPC', $year, $month);
    $cases[] = captureCase(
        'C', 'CreateManyNPC-shaped fillRandomStat + fillRemainSpecAsZero, city unspecified, affinity drawn',
        'fillRandomStat+fillRemainSpecAsZero',
        $seedString, 'C무명장수', 0, 2,
        function (GeneralBuilder $b) use ($birthYear, $deathYear) {
            $b->setLifeSpan($birthYear, $deathYear);
        },
        function (GeneralBuilder $b) use ($env) {
            $b->fillRandomStat(['무' => 0.333, '지' => 0.333, '무지' => 0.334]);
            $b->fillRemainSpecAsZero($env);
        },
        $env, $maxTurn, $rankCount
    );
}

// CASE D — setSpecialOption('랜덤') branch (nextBool(2/3) → pickSpecialWar | pickSpecialDomestic),
//   THEN fillRemainSpecAsRandom. Captures the random-spec branch draw (the nextBool(2/3) + the
//   speciality substream) that NO other case exercises. Stat preset so the spec-pick myCond is stable.
{
    $birthYear = $year - 28;
    $deathYear = $year + 45;
    $seedString = Util::simpleSerialize($hiddenSeed, 'GeneralBuilderRandSpec', $year, $month, 'D특기장수');
    $cases[] = captureCase(
        'D', "setSpecialOption('랜덤') (nextBool(2/3) → pickSpecial* substream) + fillRemainSpecAsRandom, stat preset",
        "setSpecialOption('랜덤')+fillRemainSpecAsRandom",
        $seedString, 'D특기장수', 0, 2,
        function (GeneralBuilder $b) use ($birthYear, $deathYear) {
            $b->setStat(72, 70, 55);
            $b->setLifeSpan($birthYear, $deathYear);
            $b->setSpecialOption('랜덤');   // nextBool(2/3) then a pickSpecial* substream
        },
        function (GeneralBuilder $b) use ($avgGen, $env) {
            // stat preset → fillRandomStat NOT called; pickType derived deterministically/choiceUsingWeight.
            $b->fillRemainSpecAsRandom(['무' => 6, '지' => 6, '무지' => 3], $avgGen, $env);
        },
        $env, $maxTurn, $rankCount
    );
}

// CASE E — fiction-mode probe. fillRemainSpecAsRandom re-rolls affinity + ego under fiction and
//   resets specials. Captured ONLY if the install's env['fiction'] is truthy (scenario_1010 install
//   default is 가상=1). If non-fiction, recorded as a documented note (NOT fabricated).
$isFiction = (Util::array_get($env['fiction'], 0) != 0);

// ── search/report ──────────────────────────────────────────────────────────────
$searchLog = [];
foreach ($cases as $c) {
    $o = $c['outcome'];
    $searchLog[] = sprintf(
        "case=%s fill=%s draws=%d L/S/I=%d/%d/%d aff=%d ego=%s war=%s dom=%s birth=%d death=%d age=%d kill=%d city=%d dex=%d/%d/%d/%d/%d",
        $c['caseId'], $c['fillPath'], $c['draw_count'],
        $o['leadership'], $o['strength'], $o['intel'], $o['affinity'], $o['personal'],
        $o['special2'], $o['special'], $o['bornyear'], $o['deadyear'], $o['age'], $o['killturn'], $o['city'],
        $o['dex1'], $o['dex2'], $o['dex3'], $o['dex4'], $o['dex5']
    );
}
fwrite(STDERR, "SEARCH:\n  " . implode("\n  ", $searchLog) . "\n");

$out = [
    'oracle'      => 'devsam-core PHP Scenario\\GeneralBuilder::build() + fill* helpers (GeneralBuilder.php:117-737). DISTINCT from Join.php/MakeGeneral (capture_join.php).',
    'hiddenSeed'  => $hiddenSeed,
    'env'         => [
        'year'           => $year,
        'month'          => $month,
        'startyear'      => $startYear,
        'scenario'       => $scenario,
        'turnterm'       => $turnterm,
        'turntime'       => $turntime,
        'fiction'        => $env['fiction'] ?? null,
        'show_img_level' => $env['show_img_level'] ?? null,
        'icon_path'      => $env['icon_path'] ?? null,
    ],
    'gameConst'   => [
        'defaultStatNPCTotal' => GameConst::$defaultStatNPCTotal,
        'defaultStatNPCMax'   => GameConst::$defaultStatNPCMax,
        'defaultStatNPCMin'   => GameConst::$defaultStatNPCMin,
        'adultAge'            => GameConst::$adultAge,
        'retirementYear'      => GameConst::$retirementYear,
        'maxTurn'             => $maxTurn,
        'rankColumnCount'     => $rankCount,
        'availablePersonality'=> $availablePersonality,
        'defaultSpecialWar'   => GameConst::$defaultSpecialWar,
        'defaultSpecialDomestic' => GameConst::$defaultSpecialDomestic,
    ],
    'precondition' => [
        'note' => 'env = $gameStor->getAll() VERBATIM (the exact env a real turn passes to build()). '
                . 'Each case is wired exactly like a real caller (RegNPC / che_인재탐색 / CreateManyNPC / setSpecialOption); '
                . 'seedString mirrors that caller\'s Util::simpleSerialize scope. build() genuinely INSERTs '
                . 'general/general_turn/rank_data; the inserted row is read then rolled back via delete-by-insertId '
                . '(Aria = non-transactional). Draws + row fields are 100% real PHP; only the (rolled-back) persistence is suppressed. '
                . 'avgGen = the real SELECT avg(...) FROM general WHERE npc<4 (che_인재탐색.php:171).',
        'fictionMode' => $isFiction,
    ],
    'fictionNote' => $isFiction
        ? 'env[fiction] is truthy on this install: case A/D fillRemainSpecAsRandom re-rolls affinity+ego and resets specials (the fiction branch draws are captured in their streams).'
        : 'env[fiction] is falsy on this install: the fiction-specific re-roll branch (GeneralBuilder.php:404-412,488) is NOT exercised by this fixture set. NOT fabricated — widen with a fiction install to capture it.',
    'summary'     => [
        'cases' => count($cases),
    ],
    'fixtures'    => $cases,
];

file_put_contents($outPath, Json::encode($out, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES | JSON_PRETTY_PRINT));
fwrite(STDERR, sprintf("wrote %s (%d cases, hiddenSeed=%s, fiction=%s)\n",
    $outPath, count($cases), $hiddenSeed, $isFiction ? '1' : '0'));
