<?php
/**
 * capture_vote.php — voteUnique 로또 골든 캡처 (devsam-core PHP, grand truth).
 *
 * ONE-SHOT, MANUAL HOST STEP — NEVER CI. capture_command_args.php / capture_che.php의
 * 형제. F4 vote mutation 슬라이스의 RNG-critical 선결조건인 "설문조사 유니크 아이템
 * 로또"의 draw-for-draw 오라클을 생성한다.
 *
 * 무엇을 캡처하나 (legacy Vote.php:111-117):
 *   $uniqueRng = new RandUtil(new LiteHashDRBG(Util::simpleSerialize(
 *       UniqueConst::$hiddenSeed, 'voteUnique', $voteID, $generalID)));
 *   $wonLottery = tryUniqueItemLottery($uniqueRng, $general, '설문조사');
 * 이 EXACT seed로 RandUtilDrawRecorder를 감싸 tryUniqueItemLottery + (당첨 시)
 * giveRandomUniqueItem를 REAL 경로로 돌려, 전체 draw stream(seq/method/args/result/
 * consumed/stateIdx)과 결과(won + itemType/itemCode)를 박제한다.
 *
 * 로또 함수(func.php:1611-1703)는 vote_poll 상태를 전혀 읽지 않는다 — general(npc/items/
 * name) + game_env(year/month/scenario/startyear/init) + general 카운트만 입력. 따라서
 * vote poll(NewVote) 셋업 없이 로또를 정확히 구동할 수 있다(voteID는 seed 문자열의
 * 입력으로만 들어간다 — pair마다 다른 seed → 다른 draw).
 *
 * 정적-입력 선결조건 (P1의 mid-band exp/ded, P2의 crew>0과 동일 정신 — 계산 골든은
 * 100% real PHP, 입력만 도달가능 상태로 고정):
 *   1) npc=0  — scenario_1010는 174장수 전원 npc=2(순수 NPC). Vote API는 REQ_GAME_LOGIN
 *      (플레이어 장수)만 도달하고 tryUniqueItemLottery는 npc>=2를 첫 줄에서 false 반환한다.
 *      설치 시 플레이어 장수가 0명이므로, 액터를 npc=0(플레이어)으로 고정한다. npc는 로또
 *      "수학"의 입력이 아니라 도달가능성 게이트일 뿐(genCount=count(npc<2)에만 영향).
 *   2) special/special2/personal='None' — 형제 캡처의 assertModuleFree 미러(장비는 이미 None).
 *
 * 모든 fixture 장수를 캡처 전에 일괄 npc=0/module-free로 고정 → genCount(=count(npc<2))가
 * 전 케이스에서 상수가 되어 prob이 재현 가능하게 고정된다. 이 set은 캡처 후 원복한다.
 *
 * HARD assertion(형제 캡처 정신, 부분/불충실 골든 절대 미작성):
 *   - 각 액터 module-free + npc<2 (로또 진입 가능).
 *   - draw stream의 nextBool prob escalation이 func.php 공식과 일치(prob0/moreProb 재계산 일치).
 *   - 당첨 시 마지막 draw == choiceUsingWeightPair, 그 result == 부여된 itemCode.
 *   - 최소 1 win + 최소 2 no-win (가변 루프 길이 1..maxCnt 행사). 자연 set에서 win이 안 나오면
 *     강제하지 않고 SEARCH 로그를 남기고 종료(faithful-never-fabricate).
 *   - in-process 재현성: 동일 seed로 로또를 2회 빌드 → 동일 draw stream.
 *
 * 호출 (php 캡처 컨테이너 내부, repo가 /work에 마운트):
 *   php tools/php-golden/capture_vote.php
 *       [--out=logic/src/test/resources/golden/vote/vote-unique-fixtures.json]
 */

namespace sammo;

require __DIR__ . '/_boot.php';
require __DIR__ . '/RandUtilDrawRecorder.php';

use sammo\Enums\GeneralQueryMode;
use sammo\Enums\AuctionType;
use function sammo\tryUniqueItemLottery;

$opts   = getopt('', ['out:']);
$outPath = $opts['out'] ?? (__DIR__ . '/../../logic/src/test/resources/golden/vote/vote-unique-fixtures.json');
$outDir  = dirname($outPath);
if (!is_dir($outDir)) { mkdir($outDir, 0775, true); }

$db = DB::db();
$hiddenSeed = UniqueConst::$hiddenSeed;
$gameStor = KVStorage::getStorage($db, 'game_env');
[$year, $month, $startYear, $initYear, $initMonth, $scenario, $develCost] =
    $gameStor->getValuesAsArray(['year','month','startyear','init_year','init_month','scenario','develcost']);
$year = (int)$year; $month = (int)$month; $startYear = (int)$startYear;
$initYear = (int)$initYear; $initMonth = (int)$initMonth; $scenario = (int)$scenario; $develCost = (int)$develCost;

function hardAssert(bool $cond, string $msg): void {
    if (!$cond) { fwrite(STDERR, "VOTE HARD-ASSERT FAILED: {$msg}\n"); exit(2); }
}
function isNoneField(?string $v): bool { return $v === null || $v === '' || $v === 'None'; }

function assertModuleFree(General $g): void {
    hardAssert(isNoneField($g->getVar('special')),  'general has special module');
    hardAssert(isNoneField($g->getVar('special2')), 'general has special2 module');
    hardAssert(isNoneField($g->getVar('personal')), 'general has personal module');
    foreach (($g->getItems() ?? []) as $slot => $item) {
        hardAssert($item instanceof \sammo\ActionItem\None, "equipment slot {$slot} not a None item");
    }
}

/**
 * 로또가 choiceUsingWeightPair로 소비하는 가용 유니크 풀을 func.php giveRandomUniqueItem
 * (1487-1588)과 동일하게 재구성한다. 박제용 — Kotlin replay가 동일 풀+동일 float로
 * 동일 [itemType,itemCode]를 뽑는지 검증하는 오라클(insertion order 보존).
 * @return array<int,array{0:array{0:string,1:string},1:int}>
 */
function buildAvailableUniquePool(object $db, General $general): array {
    $availableUnique = [];
    $occupiedUnique = [];
    $invalidItemType = [];

    // 장수가 든 non-buyable 아이템 슬롯은 그 타입 전체 제외(module-free 액터는 전부 None=buyable → 빈 셋).
    foreach (array_keys(GameConst::$allItems) as $itemType) {
        $ownItem = $general->getItems()[$itemType] ?? null;
        if ($ownItem !== null && !$ownItem->isBuyable()) {
            $invalidItemType[$itemType] = true;
        }
    }

    // 모든 장수가 장착한 non-buyable(유니크) 점유 카운트.
    foreach (array_keys(GameConst::$allItems) as $itemType) {
        if (key_exists($itemType, $invalidItemType)) { continue; }
        foreach ($db->queryAllLists('SELECT %b, count(*) as cnt FROM general GROUP BY %b', $itemType, $itemType) as [$itemCode, $cnt]) {
            $itemClass = buildItemClass($itemCode);
            if (!$itemClass) { continue; }
            if ($itemClass->isBuyable()) { continue; }
            $occupiedUnique[$itemCode] = $cnt;
        }
    }

    // 진행 중 경매 유니크.
    $auctionItems = $db->queryFirstColumn(
        'SELECT `target` FROM `ng_auction` WHERE `type` = %s AND `finished` = 0',
        AuctionType::UniqueItem->value
    );
    foreach ($auctionItems as $itemCode) {
        $occupiedUnique[$itemCode] = ($occupiedUnique[$itemCode] ?? 0) + 1;
    }

    // storage ut_* 보관 유니크.
    foreach ($db->queryAllLists('SELECT namespace, count(*) as cnt FROM `storage` WHERE namespace LIKE "ut_%" GROUP BY namespace') as [$uniqueNS, $cnt]) {
        $itemCode = substr($uniqueNS, 3);
        $itemClass = buildItemClass($itemCode);
        if (!$itemClass) { continue; }
        if ($itemClass->isBuyable()) { continue; }
        $occupiedUnique[$itemCode] = ($occupiedUnique[$itemCode] ?? 0) + $cnt;
    }

    // 잔여 가용 = 총량 - 점유 (insertion order = GameConst::$allItems 순회 순서).
    foreach (GameConst::$allItems as $itemType => $itemCategories) {
        if (key_exists($itemType, $invalidItemType)) { continue; }
        foreach ($itemCategories as $itemCode => $cnt) {
            if ($cnt == 0) { continue; }
            if (!key_exists($itemCode, $occupiedUnique)) {
                $availableUnique[] = [[$itemType, $itemCode], $cnt];
                continue;
            }
            $remain = $cnt - $occupiedUnique[$itemCode];
            if ($remain > 0) {
                $availableUnique[] = [[$itemType, $itemCode], $remain];
            }
        }
    }
    return $availableUnique;
}

/** func.php tryUniqueItemLottery의 prob 유도를 독립 재계산(검증용). returns [genCount,itemTypeCnt,maxCnt,prob0,moreProb]. */
function deriveLotteryInputs(object $db, General $general, int $year, int $startYear): array {
    $itemTypeCnt = count(GameConst::$allItems);
    $relYear = $year - $startYear;

    $maxTrialCountByYear = 1;
    foreach (GameConst::$maxUniqueItemLimit as [$targetYear, $targetTrialCnt]) {
        if ($relYear < $targetYear) { break; }
        $maxTrialCountByYear = $targetTrialCnt;
    }
    $trialCnt = Util::valueFit($itemTypeCnt, null, $maxTrialCountByYear);
    $maxCnt = $itemTypeCnt;
    // module-free 액터: 모든 아이템 buyable → 감산 없음.
    foreach ($general->getItems() as $item) {
        if (!$item->isBuyable()) { $trialCnt -= 1; $maxCnt -= 1; }
    }
    hardAssert($trialCnt > 0, 'trialCnt<=0 (no item space) — actor not lottery-eligible');

    $gameStor = KVStorage::getStorage($db, 'game_env');
    $scenario = (int)$gameStor->scenario;
    $genCount = (int)$db->queryFirstField('SELECT count(*) FROM general WHERE npc<2');

    // '설문조사' 분기 (func.php:1668-1669).
    $prob = 1 / ($genCount * $itemTypeCnt * 0.7 / 3);
    $prob *= GameConst::$uniqueTrialCoef;
    $prob = Util::valueFit($prob, null, GameConst::$maxUniqueTrialProb);
    $prob /= sqrt(7);
    $moreProb = pow(10, 1 / 4);

    return [$genCount, $itemTypeCnt, $maxCnt, $prob, $moreProb];
}

// ── fixture pair set ─────────────────────────────────────────────────────────
// 정적-입력 선결조건으로 npc=0+module-free 고정할 액터 후보(설치 시 no 오름차순 상위군 —
// 결정적). 각 액터를 2개 voteID와 교차 → 가변 루프 길이 행사 + win/no-win 분포 확보.
$fixtureGids = [];
$candRows = $db->query("SELECT no FROM general ORDER BY no LIMIT 8");
foreach ($candRows as $r) { $fixtureGids[] = (int)$r['no']; }
hardAssert(count($fixtureGids) >= 4, 'not enough generals to build a fixture set');

$voteIDs = [1, 2];

// ── 정적-입력 선결조건 일괄 적용 (캡처 후 원복용 baseline 스냅샷) ─────────────
$baseline = [];
foreach ($fixtureGids as $gid) {
    $baseline[$gid] = $db->queryFirstRow('SELECT npc, special, special2, personal FROM general WHERE no=%i', $gid);
    $db->update('general', ['npc'=>0, 'special'=>'None', 'special2'=>'None', 'personal'=>'None'], 'no=%i', $gid);
}
$genCountFixed = (int)$db->queryFirstField('SELECT count(*) FROM general WHERE npc<2');
hardAssert($genCountFixed === count($fixtureGids),
    "genCount({$genCountFixed}) != fixture set size(" . count($fixtureGids) . ") — leaked player generals");

/**
 * 한 (voteID, generalID) pair의 로또를 REAL 경로로 구동 + draw stream 박제.
 * vote 로또는 부작용으로 general에 itemCode를 setVar하고 로그를 쌓으므로, 캡처 후
 * 액터 행을 원복하고 로그를 청소해 pair간 독립성을 보장한다.
 */
function captureVotePair(object $db, int $voteID, int $gid, string $hiddenSeed,
                         int $year, int $month, int $startYear): array {
    // 액터 baseline(아이템/로그 영향 행) 전체 스냅샷.
    $bg = $db->queryFirstRow('SELECT * FROM general WHERE no=%i', $gid);

    $general = General::createObjFromDB($gid, null, GeneralQueryMode::Full);
    assertModuleFree($general);
    hardAssert((int)$general->getNPCType() < 2, "actor {$gid} npc>=2 — lottery guard would reject");

    // func.php와 독립 재계산한 로또 입력(검증 + 박제).
    [$genCount, $itemTypeCnt, $maxCnt, $prob0, $moreProb] = deriveLotteryInputs($db, $general, $year, $startYear);
    // choiceUsingWeightPair가 소비할 가용 유니크 풀(당첨 시).
    $pool = buildAvailableUniquePool($db, $general);

    // Vote.php:111-116과 byte-identical한 seed 문자열.
    $seedString = Util::simpleSerialize($hiddenSeed, 'voteUnique', $voteID, $gid);

    // 재현성 검증: 동일 seed로 로또를 2회 빌드 → 동일 draw stream이어야 한다.
    $runOnce = function() use ($db, $gid, $seedString): array {
        $g = General::createObjFromDB($gid, null, GeneralQueryMode::Full);
        $rec = new RandUtilDrawRecorder(new LiteHashDRBG($seedString));
        $won = tryUniqueItemLottery($rec, $g, '설문조사');
        return [$won, $rec->getDrawStream(), $g];
    };

    [$wonA, $streamA, ] = $runOnce();
    [$wonB, $streamB, $generalFinal] = $runOnce();
    hardAssert($wonA === $wonB, "{$gid}/{$voteID}: won not reproducible ({$wonA} vs {$wonB})");
    hardAssert(Json::encode($streamA) === Json::encode($streamB),
        "{$gid}/{$voteID}: draw stream not reproducible across two in-process builds");

    $won = $wonA;
    $stream = $streamA;

    // draw stream 구조 검증: 마지막 nextBool 전까지 prob escalation이 공식과 일치.
    $nextBoolDraws = array_values(array_filter($stream, fn($d) => $d['method'] === 'nextBool'));
    hardAssert(count($nextBoolDraws) >= 1, "{$gid}/{$voteID}: no nextBool draw recorded");
    hardAssert(count($nextBoolDraws) <= $maxCnt, "{$gid}/{$voteID}: more nextBool draws than maxCnt");
    $p = $prob0;
    foreach ($nextBoolDraws as $i => $d) {
        hardAssert(abs((float)$d['args']['prob'] - $p) < 1e-9,
            "{$gid}/{$voteID}: nextBool[{$i}] prob mismatch (got {$d['args']['prob']}, expect {$p})");
        $p *= $moreProb;
    }

    $itemType = null; $itemCode = null;
    if ($won) {
        // 당첨 → 마지막 draw는 choiceUsingWeightPair, 그 result == 부여된 itemCode.
        $last = $stream[count($stream) - 1];
        hardAssert($last['method'] === 'choiceUsingWeightPair',
            "{$gid}/{$voteID}: win but last draw is {$last['method']}");
        [$itemType, $itemCode] = $last['result'];
        // 마지막 nextBool은 true(루프 break), 그 앞은 모두 false.
        $lastBool = $nextBoolDraws[count($nextBoolDraws) - 1];
        hardAssert($lastBool['result'] === true, "{$gid}/{$voteID}: win but final nextBool false");
        foreach (array_slice($nextBoolDraws, 0, -1) as $d) {
            hardAssert($d['result'] === false, "{$gid}/{$voteID}: pre-win nextBool not false");
        }
        // 부여된 itemCode가 general에 실제로 setVar 되었는지(부작용 충실성) 검증.
        $assigned = $generalFinal->getVar($itemType);
        hardAssert($assigned === $itemCode,
            "{$gid}/{$voteID}: assigned {$itemType}={$assigned} != lottery {$itemCode}");
    } else {
        // 무당첨 → choiceUsingWeightPair 없음, 모든 nextBool false, 길이==maxCnt.
        foreach ($stream as $d) {
            hardAssert($d['method'] !== 'choiceUsingWeightPair', "{$gid}/{$voteID}: no-win but pool drawn");
        }
        hardAssert(count($nextBoolDraws) === $maxCnt,
            "{$gid}/{$voteID}: no-win but nextBool count(" . count($nextBoolDraws) . ") != maxCnt({$maxCnt})");
        foreach ($nextBoolDraws as $d) {
            hardAssert($d['result'] === false, "{$gid}/{$voteID}: no-win but a nextBool true");
        }
    }

    // 액터 원복 + 로그 청소 (pair간 독립).
    $db->update('general', $bg, 'no=%i', $gid);
    $db->delete('general_record', 'general_id=%i', $gid);

    // 풀 박제 형태: [{itemType,itemCode,weight}, …]
    $poolOut = [];
    foreach ($pool as [$pair, $weight]) {
        $poolOut[] = ['itemType' => $pair[0], 'itemCode' => $pair[1], 'weight' => $weight];
    }

    return [
        'voteId'     => $voteID,
        'generalId'  => $gid,
        'hiddenSeed' => $hiddenSeed,
        'seedString' => $seedString,
        'inputs'     => [
            'genCount'    => $genCount,
            'itemTypeCnt' => $itemTypeCnt,
            'maxCnt'      => $maxCnt,
            'prob0'       => $prob0,
            'moreProb'    => $moreProb,
            'itemPool'    => $poolOut,
        ],
        'draws'   => $stream,
        'outcome' => array_filter([
            'won'      => $won,
            'itemType' => $itemType,
            'itemCode' => $itemCode,
        ], fn($v) => $v !== null),
    ];
}

$fixtures = [];
$winCnt = 0; $noWinCnt = 0;
$searchLog = [];
foreach ($fixtureGids as $gid) {
    foreach ($voteIDs as $voteID) {
        $case = captureVotePair($db, $voteID, $gid, $hiddenSeed, $year, $month, $startYear);
        $fixtures[] = $case;
        $w = $case['outcome']['won'];
        if ($w) { $winCnt++; } else { $noWinCnt++; }
        $searchLog[] = sprintf("gid=%d vote=%d won=%s nextBool=%d%s",
            $gid, $voteID, $w ? 'YES' : 'no',
            count(array_filter($case['draws'], fn($d) => $d['method'] === 'nextBool')),
            $w ? (" item=" . $case['outcome']['itemCode']) : "");
    }
}

fwrite(STDERR, "SEARCH:\n  " . implode("\n  ", $searchLog) . "\n");

// faithful-never-fabricate: 자연 set에서 분포가 부족하면 강제하지 않고 종료.
hardAssert($winCnt >= 1, "no win across natural fixture set ({$winCnt} wins) — DO NOT force; widen the set or document a quarantine");
hardAssert($noWinCnt >= 2, "fewer than 2 no-win cases ({$noWinCnt}) — variable-loop coverage insufficient");

// fixture set 원복(정적-입력 선결조건 되돌리기).
foreach ($fixtureGids as $gid) {
    $db->update('general', $baseline[$gid], 'no=%i', $gid);
}

$out = [
    'oracle'     => 'devsam-core PHP voteUnique lottery (Vote.php:111-117 + func.php tryUniqueItemLottery/giveRandomUniqueItem)',
    'hiddenSeed' => $hiddenSeed,
    'env'        => [
        'year'      => $year, 'month' => $month, 'startYear' => $startYear,
        'initYear'  => $initYear, 'initMonth' => $initMonth,
        'scenario'  => $scenario, 'develCost' => $develCost,
    ],
    'precondition' => [
        'npc'          => 0,
        'moduleFree'   => true,
        'note'         => 'static-input: actor set to npc=0 + special/special2/personal=None (Vote API is REQ_GAME_LOGIN; scenario_1010 ships 0 player generals). Lottery math is 100% real PHP for these inputs; npc only flips the npc>=2 reachability guard.',
        'genCountFixed'=> $genCountFixed,
    ],
    'summary'  => ['cases' => count($fixtures), 'wins' => $winCnt, 'noWins' => $noWinCnt],
    'fixtures' => $fixtures,
];

file_put_contents($outPath, Json::encode($out, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES | JSON_PRETTY_PRINT));
fwrite(STDERR, sprintf("wrote %s (%d cases: %d win / %d no-win, hiddenSeed=%s)\n",
    $outPath, count($fixtures), $winCnt, $noWinCnt, $hiddenSeed));
