<?php
/**
 * capture_join.php — 장수생성(Join / MakeGeneral) draw 골든 캡처 (devsam-core PHP, grand truth).
 *
 * ONE-SHOT, MANUAL HOST STEP — NEVER CI. capture_vote.php / capture_che.php의 형제.
 * B1-B3 장수생성/빙의/선택 슬라이스의 RNG-critical 선결조건인 "create-general" MakeGeneral
 * 루틴의 draw-for-draw 오라클을 생성한다.
 *
 * 무엇을 캡처하나 (legacy Join.php:225-392 — MakeGeneral 클래스는 없고 Join::launch 인라인):
 *   $now = TimeUtil::now(false);          // STRING "Y-m-d H:i:s" (4번째 seed 토큰 = str(19,…))
 *   $rng = new RandUtil(new LiteHashDRBG(Util::simpleSerialize(
 *       UniqueConst::$hiddenSeed, 'MakeGeneral', $userID, $now)));
 * 이 EXACT seed로 RandUtilDrawRecorder를 감싸 Join.php의 정확한 draw 순서를 REAL 경로로
 * 재현한다 (full Join::launch는 general/general_turn/rank_data INSERT + member_log 부작용을
 * 일으키므로 돌리지 않는다 — spec §E.4: draw 서브시퀀스만 박제, INSERT/member_log 무):
 *
 *   1. nextBool(0.01)                          → genius?
 *   2. choice($cities)  ($cities = REAL SELECT city WHERE level∈[5,6] AND nation=0,
 *                        빈 경우 폴백 WHERE level∈[5,6])  → birth city(공백지)
 *   3. nextRangeInt(3,5)                        → bonus 루프 횟수 N
 *   4. choiceUsingWeight([lead,str,int]) × N    → 각 보너스 +1 대상(0/1/2)
 *   5. nextRangeInt(0,1)                        → age = 20 + bonusSum*2 - draw
 *   6. [genius일 때만] SpecialityHelper::pickSpecialWar($rng, stats(dex1..5=0)) → special2
 *      (가변 서브스트림 — spec §A.1; recorder로 박제, 재유도 금지)
 *   7. choice(GameConst::$availablePersonality) → personal
 *      (character='Random' = 폼 기본값 → in_array 실패 → 이 draw가 잡힌다)
 *   8. nextRangeInt(1,150)                      → affinity(상성)
 *   9. getRandTurn($rng, turnterm, base): nextRangeInt(0, 60*turnterm-1) then
 *      nextRangeInt(0, 999999)                  → turntime 컴포넌트(2 draws)
 *
 * 정적-입력 선결조건 (형제 캡처의 mid-band exp/ded·crew>0과 동일 정신 — 계산 골든은 100%
 * real PHP, 입력만 도달가능/재현가능하게 고정):
 *   1) $now를 fixed string으로 PIN (기본 '2026-06-06 00:00:00') — live 코드는 wall-clock now를
 *      쓰지만 골든은 재현을 위해 PIN하고 seedString + now를 fixture에 박제한다. seed의 4번째
 *      토큰은 str(19,…) (TimeUtil::now(false) 폭 19), int(now) 아님 — spec §A #1 (load-bearing).
 *   2) 폼 스탯을 고정 (leadership=60, strength=60, intel=45 → sum 165 ≤ defaultStatTotal,
 *      각 [15,80]). choiceUsingWeight의 가중치 입력 = 폼 스탯(pre-bonus)이므로 박제한다.
 *   3) character='Random' (폼 기본) → personality choice draw가 잡힌다 (spec §A #4).
 *
 * scenario_1010 확인: scenario>=1000 (specage=specage2=age+3, RNG 무관), relYear=0
 * (year==startyear → experience=0, 결정적, RNG 무관 — note만, draw 아님).
 *
 * 부작용 없음: 이 캡처는 DB에 쓰지 않는다 (general INSERT / member_log 무). member 행도
 * 필요 없다 — draw 서브시퀀스는 seedString + city 쿼리 + 폼 스탯 + pickSpecialWar만 입력으로
 * 받는다. 따라서 snapshot/restore 불필요(순수 read + RNG 재현).
 *
 * HARD assertion (형제 캡처 정신, 부분/불충실 골든 절대 미작성):
 *   - scenario>=1000 (genius branch·specage 분기 도달 가능, experience 무 RNG).
 *   - 공백지 city 풀 비어있지 않음 (choice 가능).
 *   - 폼 스탯 합 ≤ defaultStatTotal, 각 [defaultStatMin, defaultStatMax].
 *   - draw stream 순서가 Join.php 순서와 정확히 일치 (method/args 검증):
 *     nextBool(0.01) → choice(city) → nextRangeInt(3,5) → choiceUsingWeight×N
 *     → nextRangeInt(0,1) → [genius: pickSpecialWar 서브스트림] → choice(personality)
 *     → nextRangeInt(1,150) → nextRangeInt(0,7199) → nextRangeInt(0,999999).
 *   - N ∈ {3,4,5}, choiceUsingWeight draw 수 == N, bonus 델타 합 == N.
 *   - in-process 재현성: 동일 seed로 2회 빌드 → 동일 draw stream (byte-identical).
 *   - 자연 set에서 genius(nextBool 0.01)가 안 나오면 강제하지 않음 — fixture에 genius
 *     uncaptured quarantine note를 남기고 non-genius fixture로 진행 (faithful-never-fabricate).
 *
 * 호출 (php 캡처 컨테이너 내부, repo가 /work에 마운트):
 *   php tools/php-golden/capture_join.php
 *       [--out=logic/src/test/resources/golden/entrance/장수생성-fixtures.json]
 *       [--now='2026-06-06 00:00:00']
 */

namespace sammo;

require __DIR__ . '/_boot.php';
require __DIR__ . '/RandUtilDrawRecorder.php';

$opts    = getopt('', ['out:', 'now:']);
$outPath = $opts['out'] ?? (__DIR__ . '/../../logic/src/test/resources/golden/entrance/장수생성-fixtures.json');
$baseNow = $opts['now'] ?? '2026-06-06 00:00:00';
$outDir  = dirname($outPath);
if (!is_dir($outDir)) { mkdir($outDir, 0775, true); }

function hardAssert(bool $cond, string $msg): void {
    if (!$cond) { fwrite(STDERR, "JOIN HARD-ASSERT FAILED: {$msg}\n"); exit(2); }
}

$db = DB::db();
$hiddenSeed = UniqueConst::$hiddenSeed;
$gameStor = KVStorage::getStorage($db, 'game_env');
[$year, $month, $startYear, $scenario, $turnterm, $genius, $turntime] =
    $gameStor->getValuesAsArray(['year', 'month', 'startyear', 'scenario', 'turnterm', 'genius', 'turntime']);
$year = (int)$year; $month = (int)$month; $startYear = (int)$startYear;
$scenario = (int)$scenario; $turnterm = (int)$turnterm; $genius = (int)$genius;
$turntime = (string)$turntime;

// scenario_1010 선결조건 검증.
hardAssert($scenario >= 1000, "scenario({$scenario}) < 1000 — specage/experience branch differs from spec");
$relYear = Util::valueFit($year - $startYear, 0);
// relYear<3 → experience=0 (결정적, RNG 무관). 1010: year==startyear → relYear=0.
hardAssert($relYear < 3, "relYear({$relYear}) >= 3 — experience would hit the 20th-percentile DB query (not captured)");

// 공백지 lv5-6 city 풀 (Join.php:279-282 — 정확히 동일 쿼리, 폴백 포함).
$cities = $db->queryFirstColumn('SELECT city FROM city where `level`>=5 and `level`<=6 and nation=0');
if (!$cities) {
    $cities = $db->queryFirstColumn('SELECT city FROM city where `level`>=5 and `level`<=6');
}
$cities = array_map('intval', $cities);
hardAssert(count($cities) > 0, 'vacant lv5-6 city pool empty — choice($cities) cannot draw');

// 폼 스탯 (정적-입력, choiceUsingWeight 가중치 = pre-bonus 폼 스탯).
$formLeadership = 60;
$formStrength   = 60;
$formIntel      = 45;
hardAssert($formLeadership >= GameConst::$defaultStatMin && $formLeadership <= GameConst::$defaultStatMax, 'form leadership out of [min,max]');
hardAssert($formStrength   >= GameConst::$defaultStatMin && $formStrength   <= GameConst::$defaultStatMax, 'form strength out of [min,max]');
hardAssert($formIntel      >= GameConst::$defaultStatMin && $formIntel      <= GameConst::$defaultStatMax, 'form intel out of [min,max]');
hardAssert($formLeadership + $formStrength + $formIntel <= GameConst::$defaultStatTotal, 'form stat sum > defaultStatTotal');

$availablePersonality = GameConst::$availablePersonality;
$character = 'Random'; // 폼 기본값 → in_array 실패 → personality choice draw가 잡힌다.

/**
 * 한 (userID, now) fixture의 MakeGeneral draw 서브시퀀스를 REAL 경로로 1회 구동.
 * Join.php:225-392의 정확한 draw 순서를 recorder로 재현한다 (INSERT/member_log 무).
 * @return array{0:array,1:array}  [drawStream, outcome]
 */
function runJoinDraws(
    int $userID, string $now, string $hiddenSeed, array $cities,
    int $formLeadership, int $formStrength, int $formIntel,
    string $character, array $availablePersonality,
    int $turnterm, string $turntime
): array {
    // Join.php:226-231과 byte-identical한 seed 문자열. 4번째 토큰 = STRING now(false).
    $seedString = Util::simpleSerialize($hiddenSeed, 'MakeGeneral', $userID, $now);
    $rng = new RandUtilDrawRecorder(new LiteHashDRBG($seedString));

    // 1. genius? (Join.php:264) — nextBool(0.01) (inheritSpecial null 경로).
    $geniusRoll = $rng->nextBool(0.01);

    // 2. birth city (Join.php:283) — choice($cities), 공백지 lv5-6.
    $city = $rng->choice($cities);

    // 3-4. bonus 루프 (Join.php:293-305) — nextRangeInt(3,5) 후 choiceUsingWeight×N.
    $pleadership = 0; $pstrength = 0; $pintel = 0;
    $N = 0;
    foreach (Util::range($rng->nextRangeInt(3, 5)) as $statIdx) {
        $N++;
        switch ($rng->choiceUsingWeight([$formLeadership, $formStrength, $formIntel])) {
            case 0: $pleadership++; break;
            case 1: $pstrength++; break;
            case 2: $pintel++; break;
        }
    }
    $leadership = $formLeadership + $pleadership;
    $strength   = $formStrength + $pstrength;
    $intel      = $formIntel + $pintel;

    // 5. age (Join.php:314) — nextRangeInt(0,1).
    $age = 20 + ($pleadership + $pstrength + $pintel) * 2 - $rng->nextRangeInt(0, 1);

    // 6. genius 전투특기 (Join.php:316-331) — pickSpecialWar 가변 서브스트림 (genius일 때만).
    //    Join.php:308에서 $leadership = $leadership + $pleadership (POST-BONUS)이 이미
    //    반영된 뒤 321에서 호출되므로 POST-BONUS 스탯을 넘긴다(dex1..5=0).
    $special2 = null;
    if ($geniusRoll) {
        $special2 = SpecialityHelper::pickSpecialWar($rng, [
            'leadership' => $leadership,
            'strength'   => $strength,
            'intel'      => $intel,
            'dex1' => 0, 'dex2' => 0, 'dex3' => 0, 'dex4' => 0, 'dex5' => 0,
        ]);
    }

    // 7. turntime (Join.php:369) — getRandTurn = nextRangeInt(0,60*term-1) then nextRangeInt(0,999999).
    //    Join.php 라인 실행 순서상 369(getRandTurn)는 389(personality)·392(affinity)보다 FIRST다.
    $base = new \DateTimeImmutable($turntime);
    $randSecond   = $rng->nextRangeInt(0, 60 * $turnterm - 1);
    $randFraction = $rng->nextRangeInt(0, 999999);

    // 8. personality (Join.php:388-390) — character='Random' → choice(availablePersonality).
    $personal = $character;
    if (!in_array($character, $availablePersonality)) {
        $personal = $rng->choice($availablePersonality);
    }

    // 9. affinity (Join.php:392) — nextRangeInt(1,150).
    $affinity = $rng->nextRangeInt(1, 150);

    $outcome = [
        'genius'          => $geniusRoll,
        'pickedCity'      => $city,
        'N'               => $N,
        'bonus'           => [$pleadership, $pstrength, $pintel],
        'leadership'      => $leadership,
        'strength'        => $strength,
        'intel'           => $intel,
        'age'             => $age,
        'personal'        => $personal,
        'affinity'        => $affinity,
        'turntimeSecond'  => $randSecond,
        'turntimeFraction'=> $randFraction,
    ];
    if ($geniusRoll) {
        $outcome['special2'] = $special2;
    }

    return [$rng->getDrawStream(), $outcome, $seedString];
}

/**
 * 한 fixture를 캡처 + 재현성(2회 빌드 byte-identical) HARD-assert + 순서 검증.
 */
function captureFixture(
    int $userID, string $now, string $hiddenSeed, array $cities,
    int $formLeadership, int $formStrength, int $formIntel,
    string $character, array $availablePersonality,
    int $turnterm, string $turntime
): array {
    [$streamA, $outcomeA, $seedString] = runJoinDraws(
        $userID, $now, $hiddenSeed, $cities,
        $formLeadership, $formStrength, $formIntel,
        $character, $availablePersonality, $turnterm, $turntime
    );
    [$streamB, $outcomeB, ] = runJoinDraws(
        $userID, $now, $hiddenSeed, $cities,
        $formLeadership, $formStrength, $formIntel,
        $character, $availablePersonality, $turnterm, $turntime
    );
    hardAssert(Json::encode($streamA) === Json::encode($streamB),
        "userID={$userID} now={$now}: draw stream not reproducible across two in-process builds");
    hardAssert(Json::encode($outcomeA) === Json::encode($outcomeB),
        "userID={$userID} now={$now}: outcome not reproducible across two in-process builds");

    $stream  = $streamA;
    $outcome = $outcomeA;
    $genius  = $outcome['genius'];
    $N       = $outcome['N'];

    // ── draw 순서/구조 HARD 검증 (Join.php 순서) ──────────────────────────────
    $idx = 0;
    $expect = function (string $method, ?array $argCheck = null) use ($stream, &$idx, $userID, $now) {
        hardAssert($idx < count($stream), "userID={$userID} now={$now}: stream too short at idx {$idx} (expected {$method})");
        $d = $stream[$idx];
        hardAssert($d['method'] === $method,
            "userID={$userID} now={$now}: draw[{$idx}] method={$d['method']} != expected {$method}");
        if ($argCheck !== null) {
            foreach ($argCheck as $k => $v) {
                $got = $d['args'][$k] ?? null;
                hardAssert($got === $v || (is_float($v) && is_numeric($got) && abs((float)$got - $v) < 1e-12),
                    "userID={$userID} now={$now}: draw[{$idx}] arg {$k}={$got} != expected {$v}");
            }
        }
        $idx++;
        return $d;
    };

    // 1. nextBool(0.01)
    $expect('nextBool', ['prob' => 0.01]);
    // 2. choice(city)
    $cityDraw = $expect('choice');
    hardAssert(in_array($cityDraw['result'], $cities, true),
        "userID={$userID} now={$now}: picked city {$cityDraw['result']} not in vacant pool");
    // 3. nextRangeInt(3,5)
    $expect('nextRangeInt', ['min' => 3, 'max' => 5]);
    hardAssert($N >= 3 && $N <= 5, "userID={$userID} now={$now}: N={$N} not in {3,4,5}");
    // 4. choiceUsingWeight × N
    for ($i = 0; $i < $N; $i++) {
        $expect('choiceUsingWeight');
    }
    // 5. nextRangeInt(0,1)
    $expect('nextRangeInt', ['min' => 0, 'max' => 1]);

    // 6. genius pickSpecialWar 서브스트림 (가변) — genius일 때만, 다음 known draw(getRandTurn의
    //    nextRangeInt(0, 60*turnterm-1)) 전까지 모두 소비. (Join.php 라인 순서: 321 pickSpecialWar
    //    → 369 getRandTurn → 389 personality → 392 affinity.) 마지막은 special을 고르는 choiceUsingWeight.
    if ($genius) {
        hardAssert($idx < count($stream), "userID={$userID} now={$now}: genius but no pickSpecialWar draws");
        $specStart = $idx;
        // pickSpecialWar의 첫 draw는 nextBool(0.8) (calcCondDexterity).
        $first = $stream[$idx];
        hardAssert($first['method'] === 'nextBool' && abs((float)$first['args']['prob'] - 0.8) < 1e-12,
            "userID={$userID} now={$now}: genius first spec draw {$first['method']} != nextBool(0.8)");
        // getRandTurn의 첫 draw(nextRangeInt(0, 60*turnterm-1))가 나올 때까지 진행하며 special
        // choiceUsingWeight를 적어도 하나 본다. getRandTurn 마진 max=60*turnterm-1은 pickSpecialWar
        // 서브스트림의 어떤 nextRangeInt(0,99) max=99와도 겹치지 않으므로 안전한 종단 표지다.
        $turnSecondMax = 60 * $turnterm - 1;
        $sawSpecWeight = false;
        while ($idx < count($stream)) {
            $d = $stream[$idx];
            if ($d['method'] === 'nextRangeInt'
                && ($d['args']['min'] ?? null) === 0
                && ($d['args']['max'] ?? null) === $turnSecondMax) {
                break; // getRandTurn 첫 draw 도달 — pickSpecialWar 서브스트림 끝.
            }
            if ($d['method'] === 'choiceUsingWeight') { $sawSpecWeight = true; }
            $idx++;
        }
        hardAssert($sawSpecWeight, "userID={$userID} now={$now}: genius but no choiceUsingWeight (special pick) in pickSpecialWar substream");
        hardAssert(isset($outcome['special2']) && $outcome['special2'] !== '' && $outcome['special2'] !== 'None',
            "userID={$userID} now={$now}: genius but special2 not set");
    }

    // 7. getRandTurn — nextRangeInt(0, 60*turnterm-1) then nextRangeInt(0,999999) (Join.php:369).
    $expect('nextRangeInt', ['min' => 0, 'max' => 60 * $turnterm - 1]);
    $expect('nextRangeInt', ['min' => 0, 'max' => 999999]);
    // 8. choice(personality) (Join.php:389).
    $persDraw = $expect('choice');
    hardAssert(in_array($persDraw['result'], $availablePersonality, true),
        "userID={$userID} now={$now}: personal {$persDraw['result']} not in availablePersonality");
    // 9. nextRangeInt(1,150) affinity (Join.php:392).
    $expect('nextRangeInt', ['min' => 1, 'max' => 150]);

    hardAssert($idx === count($stream),
        "userID={$userID} now={$now}: {$idx} draws asserted but stream has " . count($stream) . " — trailing unexpected draws");

    // bonus 델타 합 == N (각 choiceUsingWeight가 정확히 +1).
    hardAssert(array_sum($outcome['bonus']) === $N,
        "userID={$userID} now={$now}: bonus sum " . array_sum($outcome['bonus']) . " != N {$N}");

    return [
        'userID'               => $userID,
        'now'                  => $now,
        'seedString'           => $seedString,
        'hiddenSeed'           => $hiddenSeed,
        'formStats'            => [
            'leadership' => $formLeadership,
            'strength'   => $formStrength,
            'intel'      => $formIntel,
        ],
        'character'            => $character,
        'cityPool'             => $cities,
        'availablePersonality' => $availablePersonality,
        'draws'                => $stream,
        'outcome'              => $outcome,
    ];
}

// ── fixture set ──────────────────────────────────────────────────────────────
// userID + pinned now를 교차해 서로 다른 seed → 서로 다른 draw stream. N∈{3,4,5} 분포 확보.
// (각 seed는 결정적이므로 fixed set으로 재현 가능; genius는 0.01 확률 — 자연히 나오면 박제.)
$nowVariants = [
    '2026-06-06 00:00:00',
    '2026-06-06 12:34:56',
];
// 1001-1005: 비-천재 다양성(N∈{3,4,5} 자연 분포). 1097/1330: nextBool(0.01)이 자연히
// fire하는 REAL 천재 seed(브로드 서치로 발견 — 강제·조작 아님). 천재 fixture는 pickSpecialWar
// 가변 서브스트림(spec §A.1)을 recorder로 박제한다.
$userIDs = [1001, 1002, 1003, 1004, 1005, 1097, 1330];

$fixtures = [];
$Ndist = [3 => 0, 4 => 0, 5 => 0];
$geniusCount = 0;
$searchLog = [];

foreach ($userIDs as $uid) {
    foreach ($nowVariants as $nowStr) {
        $fx = captureFixture(
            $uid, $nowStr, $hiddenSeed, $cities,
            $formLeadership, $formStrength, $formIntel,
            $character, $availablePersonality, $turnterm, $turntime
        );
        $fixtures[] = $fx;
        $N = $fx['outcome']['N'];
        $Ndist[$N]++;
        $g = $fx['outcome']['genius'];
        if ($g) { $geniusCount++; }
        $searchLog[] = sprintf(
            "userID=%d now=%s N=%d genius=%s city=%d age=%d personal=%s%s",
            $uid, $nowStr, $N, $g ? 'YES' : 'no',
            $fx['outcome']['pickedCity'], $fx['outcome']['age'], $fx['outcome']['personal'],
            $g ? (' special2=' . $fx['outcome']['special2']) : ''
        );
    }
}

fwrite(STDERR, "SEARCH:\n  " . implode("\n  ", $searchLog) . "\n");

// faithful-never-fabricate: N 분포가 {3,4,5} 전부 나오는지 확인 (강제 금지 — set이 부족하면 확장).
hardAssert($Ndist[3] >= 1 && $Ndist[4] >= 1 && $Ndist[5] >= 1,
    sprintf("N distribution incomplete (N3=%d N4=%d N5=%d) — widen the userID/now set; DO NOT fabricate",
        $Ndist[3], $Ndist[4], $Ndist[5]));

$geniusNote = $geniusCount > 0
    ? "{$geniusCount} genius fixture(s) captured naturally (nextBool(0.01) fired) — pickSpecialWar substream recorded via recorder."
    : "QUARANTINE: genius branch UNCAPTURED — nextBool(0.01) did not fire across this fixture set. NOT fabricated. "
    . "The genius pickSpecialWar substream (Join.php:321, spec §A.1) remains uncaptured; widen the seed set in a future run to hit it. "
    . "All fixtures here are non-genius (faithful real PHP draws).";

$out = [
    'oracle'      => 'devsam-core PHP MakeGeneral (Join.php:225-392, inline create-general — there is NO MakeGeneral class)',
    'hiddenSeed'  => $hiddenSeed,
    'env'         => [
        'turnterm'  => $turnterm,
        'year'      => $year,
        'startyear' => $startYear,
        'month'     => $month,
        'scenario'  => $scenario,
        'genius'    => $genius,
        'turntime'  => $turntime,
        'relYear'   => $relYear,
    ],
    'precondition' => [
        'note'      => 'static-input: now PINNED to a fixed string per fixture (live code uses wall-clock TimeUtil::now(false); golden pins it for replay). '
                     . 'The 4th seed token is str(19, Y-m-d H:i:s), NOT int(now) (spec §A #1). Form stats fixed (lead=60,str=60,int=45, sum 165 <= defaultStatTotal). '
                     . 'character=Random (form default) -> personality choice draw taken. scenario>=1000 (specage=age+3, RNG-irrelevant); relYear=0 -> experience=0 (deterministic, not an RNG draw). '
                     . 'No DB writes: pure draw-replay of the MakeGeneral sub-sequence (no general INSERT, no member_log) per spec §E.4.',
        'experience'=> 0,
        'specageRule'=> 'scenario>=1000: specage=specage2=age+3',
    ],
    'geniusNote'  => $geniusNote,
    'summary'     => [
        'cases'      => count($fixtures),
        'geniusCount'=> $geniusCount,
        'Ndist'      => $Ndist,
    ],
    'fixtures'    => $fixtures,
];

file_put_contents($outPath, Json::encode($out, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES | JSON_PRETTY_PRINT));
fwrite(STDERR, sprintf(
    "wrote %s (%d fixtures: genius=%d, N3=%d N4=%d N5=%d, hiddenSeed=%s)\n",
    $outPath, count($fixtures), $geniusCount, $Ndist[3], $Ndist[4], $Ndist[5], $hiddenSeed
));
