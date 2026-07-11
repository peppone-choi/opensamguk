<?php
/**
 * capture_tournament.php — 대회(토너먼트) fight()/qualify() 골든 캡처 (devsam-core PHP, grand truth).
 *
 * ONE-SHOT, MANUAL HOST STEP — NEVER CI. capture_vote.php의 형제(비-Command 메커닉).
 * hwe/func_tournament.php의 대회 전투 경로를 draw-neutral하게 박제한다:
 *   - fight()  (func_tournament.php:1004) — tnmt_type {0,1,2,3} × type {0,1}의 실제 전투 시뮬레이션,
 *     아이템 플레이버 로그(비-구매 아이템), 페이즈별 damage/energy 로그, 승부 판정(sel),
 *     tournament 행 델타(win/draw/lose/gl), rank_data gl/w/d/l 업데이트.
 *   - qualify() (func_tournament.php:583) 의 phase>=55 승격 분기 — win*3+draw desc, gl desc, seq
 *     정렬로 조별 상위 4명 prmt 배정(승격 순서 의미론).
 *
 * ★ RNG 주의: 대회는 sammo RandUtil/LiteHashDRBG가 아니라 PHP 네이티브 rand()/mt_rand()/
 *   array_rand()를 쓴다(Util::randRangeInt=mt_rand, Util::choiceRandom=array_rand, fight()의
 *   rand()는 PHP 7.1+에서 mt_rand 별칭). 따라서 RandUtilDrawRecorder는 적용되지 않는다.
 *   결정성은 fight()/qualify() 직전 mt_srand($seed, MT_RAND_MT19937)로 MT19937 전역 상태를
 *   핀하여 확보한다(별칭 srand도 동일 상태). $seed는 fixture에 캡처 INPUT으로 박제한다.
 *   per-draw 스트림은 오라클(rand/mt_rand/array_rand)을 수정하지 않고는 기록 불가하므로,
 *   대신 (a) 동일 seed의 순수 MT19937 적합성 벡터(rngConformance)와 (b) 실제 fight() 출력
 *   전체(로그+행 델타)를 박제한다 — Kotlin 포트는 PHP-MT19937 + array_rand + mt_rand-range를
 *   충실 구현하고 이 골든으로 검증한다.
 *
 * 정적-입력 선결조건 (che 캡처의 mid-band exp/ded, vote의 npc=0과 동일 정신 — 계산/로그/델타는
 * 100% real PHP, 입력만 도달가능 mid-game 상태로 고정):
 *   - tournament 테이블에 통제된 소수 엔트리를 시드(실 general_id 사용 → rank_data 실 행에 적중).
 *     rank_data 행은 ResetHelper가 전 장수에 대해 RankColumn 전 타입 value=0으로 이미 생성하므로
 *     (ResetHelper.php:329-338), gl 델타는 real PHP.
 *   - 비-구매 아이템(che_명마_15_적토마/che_무기_15_청홍검/che_서적_07_논어)을 장착시켜
 *     fight()의 아이템 플레이버 로그(isBuyable()==false 분기)를 행사.
 *   - seed는 원하는 승부(sel) 결과를 얻기 위해 결정적 순서로 스캔해 선택할 수 있다(월 선택으로
 *     성공/실패를 고르는 che 캡처와 동일 — 어떤 seed든 그 출력은 100% real PHP). 선택된 seed를 박제.
 *
 * HARD assertion(형제 캡처 정신, 부분/불충실 골든 절대 미작성):
 *   - 각 fight 케이스: 동일 seed + 동일 시드 입력으로 2회 실행 → 로그/행 델타 byte-identical(in-process).
 *   - 승부 sel ∈ {0,1,2}, 마지막 로그 라인이 승리/무승부 문자열과 일치.
 *   - 비-구매 아이템 케이스: 최소 1개 아이템 플레이버 로그 라인 존재.
 *   - qualify 승격: 조별 정확히 4명 prmt=1..4, gd desc→gl desc→seq 순서와 일치.
 *   - 스크립트 전체 2회 실행 → fixture JSON sha256 동일(외부에서 확인).
 *
 * 호출 (php 캡처 컨테이너 내부, repo가 /work에 마운트):
 *   php tools/php-golden/capture_tournament.php
 *       [--out=logic/src/test/resources/golden/tournament/fight-fixtures.json]
 */

namespace sammo;

require __DIR__ . '/_boot.php';

$opts    = getopt('', ['out:']);
$outPath = $opts['out'] ?? (__DIR__ . '/../../logic/src/test/resources/golden/tournament/fight-fixtures.json');
$outDir  = dirname($outPath);
if (!is_dir($outDir)) { mkdir($outDir, 0775, true); }

$db = DB::db();

function hardAssert(bool $cond, string $msg): void {
    if (!$cond) { fwrite(STDERR, "TNMT HARD-ASSERT FAILED: {$msg}\n"); exit(2); }
}

// fight()가 쓰는 raw 로그 파일 경로(func_history.php pushTnmtFightLog).
function tnmtLogPath(int $group): string {
    return realpath(__DIR__ . '/../../legacy/devsam-core/hwe') . '/logs/' . UniqueConst::$serverID . "/fight{$group}.txt";
}
function ensureLogDir(): void {
    $dir = realpath(__DIR__ . '/../../legacy/devsam-core/hwe') . '/logs/' . UniqueConst::$serverID;
    if (!is_dir($dir)) { @mkdir($dir, 0775, true); }
}
// fight()가 push한 raw 로그 라인(byte-exact, ConvertLog 이전) 회수. 파일 끝 개행으로 생기는 공백 원소 제거.
function readTnmtLog(int $group): array {
    $path = tnmtLogPath($group);
    if (!file_exists($path)) { return []; }
    $lines = explode("\n", file_get_contents($path));
    while (count($lines) > 0 && end($lines) === '') { array_pop($lines); }
    return array_values($lines);
}
function clearTnmtLog(int $group): void {
    $path = tnmtLogPath($group);
    if (file_exists($path)) { @unlink($path); }
}

// tp2(tt/tl/ts/ti)별 rank_data 타입 목록.
function rankTypesFor(string $tp2): array {
    return ["{$tp2}w", "{$tp2}d", "{$tp2}l", "{$tp2}g"];
}
function readRankData(object $db, int $genId, string $tp2): array {
    $out = [];
    foreach (rankTypesFor($tp2) as $t) {
        $v = $db->queryFirstField('SELECT value FROM rank_data WHERE general_id=%i AND type=%s', $genId, $t);
        $out[$t] = $v === null ? null : (int)$v;
    }
    return $out;
}
function resetRankData(object $db, int $genId, string $tp2, array $preset = []): void {
    foreach (rankTypesFor($tp2) as $t) {
        $val = $preset[$t] ?? 0;
        $db->update('rank_data', ['value' => $val], 'general_id=%i AND type=%s', $genId, $t);
    }
}

// tournament 행 전체 + fight()가 읽는 계산 컬럼(total) 그대로 회수.
function readTnmtRow(object $db, int $group, int $grpNo): ?array {
    $r = $db->queryFirstRow(
        'SELECT *,(leadership+strength+intel)*7/15 as total from tournament where grp=%i AND grp_no=%i',
        $group, $grpNo
    );
    if ($r === null) { return null; }
    // total은 MariaDB DECIMAL → 문자열로 보존(byte-exact).
    $r['total'] = (string)$r['total'];
    return $r;
}

$TP2 = [0 => 'tt', 1 => 'tl', 2 => 'ts', 3 => 'ti'];

// ── tournament 시드 헬퍼 ─────────────────────────────────────────────────────
// TRUNCATE로 AUTO_INCREMENT(seq)를 1로 리셋 → 재시드 seq가 삽입 순서대로 결정적(1,2,…).
// (DELETE는 auto_increment 카운터를 유지해 seq가 계속 증가 → run/scan 간 비결정적.)
function clearTournament(object $db): void { $db->query('TRUNCATE TABLE tournament'); }
function seedEntry(object $db, array $e): void {
    $db->insert('tournament', array_merge([
        'no' => 0, 'npc' => 0, 'name' => '', 'w' => 'None', 'b' => 'None', 'h' => 'None',
        'leadership' => 0, 'strength' => 0, 'intel' => 0, 'lvl' => 0,
        'grp' => 0, 'grp_no' => 0, 'win' => 0, 'draw' => 0, 'lose' => 0, 'gl' => 0, 'prmt' => 0,
    ], $e));
}

/**
 * 단일 fight() 케이스를 실제 경로로 구동 + 박제.
 * @param array $g1spec/$g2spec: ['no','name','l','s','i','lvl','h','w','b']
 * @param int|null $wantSel: 지정 시 seed를 결정적 순서(1..maxSeed)로 스캔해 그 sel을 내는 첫 seed 사용.
 * @param int $fixedSeed: $wantSel=null일 때 사용할 고정 seed.
 * @param array $pre1/$pre2: gen1/gen2의 rank_data {tp2}g 등 사전값(gl 분기 행사용).
 */
function captureFight(
    object $db, array $TP2, string $caseId, string $desc,
    int $tnmtType, int $tnmt, int $phs, int $group, int $type,
    array $g1spec, array $g2spec,
    ?int $wantSel, int $fixedSeed,
    array $pre1 = [], array $pre2 = []
): array {
    $tp2 = $TP2[$tnmtType];
    $g1 = 0; $g2 = 1; // grp_no

    // 시드 입력을 한 번 구성하는 클로저(스캔/재현 반복용 — 매번 동일 초기 상태로 리셋).
    $setup = function () use ($db, $group, $g1, $g2, $g1spec, $g2spec, $tp2, $pre1, $pre2) {
        clearTournament($db);
        seedEntry($db, ['no' => $g1spec['no'], 'name' => $g1spec['name'], 'leadership' => $g1spec['l'],
            'strength' => $g1spec['s'], 'intel' => $g1spec['i'], 'lvl' => $g1spec['lvl'],
            'h' => $g1spec['h'], 'w' => $g1spec['w'], 'b' => $g1spec['b'], 'grp' => $group, 'grp_no' => $g1]);
        seedEntry($db, ['no' => $g2spec['no'], 'name' => $g2spec['name'], 'leadership' => $g2spec['l'],
            'strength' => $g2spec['s'], 'intel' => $g2spec['i'], 'lvl' => $g2spec['lvl'],
            'h' => $g2spec['h'], 'w' => $g2spec['w'], 'b' => $g2spec['b'], 'grp' => $group, 'grp_no' => $g2]);
        resetRankData($db, $g1spec['no'], $tp2, $pre1);
        resetRankData($db, $g2spec['no'], $tp2, $pre2);
    };

    // 한 seed로 fight를 돌리고 로그/델타/ sel을 회수(부작용 포함).
    $runOnce = function (int $seed) use ($db, $setup, $tnmtType, $tnmt, $phs, $group, $g1, $g2, $type, $g1spec, $g2spec, $tp2) {
        $setup();
        $before = [
            'g1' => readTnmtRow($db, $group, $g1),
            'g2' => readTnmtRow($db, $group, $g2),
            'rank1' => readRankData($db, $g1spec['no'], $tp2),
            'rank2' => readRankData($db, $g2spec['no'], $tp2),
        ];
        ensureLogDir();
        clearTnmtLog($group);
        mt_srand($seed, MT_RAND_MT19937);
        fight($tnmtType, $tnmt, $phs, $group, $g1, $g2, $type);
        $log = readTnmtLog($group);
        $after = [
            'g1' => readTnmtRow($db, $group, $g1),
            'g2' => readTnmtRow($db, $group, $g2),
            'rank1' => readRankData($db, $g1spec['no'], $tp2),
            'rank2' => readRankData($db, $g2spec['no'], $tp2),
        ];
        // sel 판정: 마지막 승부 로그 라인으로 역산.
        $sel = null; $winLine = null;
        foreach ($log as $ln) {
            if (str_contains($ln, '<S>승리</>') && str_contains($ln, $g1spec['name'])) { $sel = 0; $winLine = $ln; }
            elseif (str_contains($ln, '<S>승리</>') && str_contains($ln, $g2spec['name'])) { $sel = 1; $winLine = $ln; }
            elseif (str_contains($ln, '무승부!')) { $sel = 2; $winLine = $ln; }
        }
        return [$log, $before, $after, $sel, $winLine];
    };

    // seed 선택.
    $chosenSeed = $fixedSeed;
    if ($wantSel !== null) {
        $found = null;
        for ($s = 1; $s <= 5000; $s++) {
            [, , , $sel] = $runOnce($s);
            if ($sel === $wantSel) { $found = $s; break; }
        }
        hardAssert($found !== null, "{$caseId}: no seed in 1..5000 yields sel={$wantSel}");
        $chosenSeed = $found;
    }

    // 최종 캡처.
    [$log, $before, $after, $sel, $winLine] = $runOnce($chosenSeed);
    hardAssert($sel !== null, "{$caseId}: could not determine sel from log");
    if ($wantSel !== null) { hardAssert($sel === $wantSel, "{$caseId}: chosen seed sel mismatch"); }

    // 재현성: 동일 seed로 2회 → 로그/델타 byte-identical.
    [$log2, $before2, $after2] = $runOnce($chosenSeed);
    hardAssert(Json::encode($log) === Json::encode($log2), "{$caseId}: log not reproducible across two in-process runs");
    hardAssert(Json::encode($after) === Json::encode($after2), "{$caseId}: row deltas not reproducible");

    hardAssert(count($log) >= 2, "{$caseId}: implausibly short log");

    return [
        'caseId'   => $caseId,
        'desc'     => $desc,
        'fightArgs' => [
            'tnmtType' => $tnmtType, 'tnmt' => $tnmt, 'phs' => $phs,
            'group' => $group, 'g1' => $g1, 'g2' => $g2, 'type' => $type,
        ],
        'tp' => (['total','leadership','strength','intel'][$tnmtType]),
        'tp2' => $tp2,
        'seed' => $chosenSeed,
        'gen1' => $before['g1'],
        'gen2' => $before['g2'],
        'logLines' => $log,
        'sel' => $sel,
        'winLine' => $winLine,
        'tournamentBefore' => [
            'g1' => ['win'=>(int)$before['g1']['win'],'draw'=>(int)$before['g1']['draw'],'lose'=>(int)$before['g1']['lose'],'gl'=>(int)$before['g1']['gl']],
            'g2' => ['win'=>(int)$before['g2']['win'],'draw'=>(int)$before['g2']['draw'],'lose'=>(int)$before['g2']['lose'],'gl'=>(int)$before['g2']['gl']],
        ],
        'tournamentAfter' => [
            'g1' => ['win'=>(int)$after['g1']['win'],'draw'=>(int)$after['g1']['draw'],'lose'=>(int)$after['g1']['lose'],'gl'=>(int)$after['g1']['gl']],
            'g2' => ['win'=>(int)$after['g2']['win'],'draw'=>(int)$after['g2']['draw'],'lose'=>(int)$after['g2']['lose'],'gl'=>(int)$after['g2']['gl']],
        ],
        'rankDataBefore' => ['g1' => $before['rank1'], 'g2' => $before['rank2']],
        'rankDataAfter'  => ['g1' => $after['rank1'],  'g2' => $after['rank2']],
    ];
}

// ── RNG 적합성 벡터 (순수 PHP MT19937 출력, 결정적) ───────────────────────────
// Kotlin 포트의 PRNG/range/array_rand를 단위-검증하기 위한 real PHP 오라클(박제, 미조작).
function rngConformance(int $seed): array {
    $mtRand = [];
    mt_srand($seed, MT_RAND_MT19937);
    for ($i = 0; $i < 32; $i++) { $mtRand[] = mt_rand(); }

    $mtRandMod = [];
    mt_srand($seed, MT_RAND_MT19937);
    for ($i = 0; $i < 32; $i++) { $mtRandMod[] = mt_rand() % 100; }

    $mtRange1_100 = [];
    mt_srand($seed, MT_RAND_MT19937);
    for ($i = 0; $i < 32; $i++) { $mtRange1_100[] = mt_rand(1, 100); }

    $mtRange150_300 = [];
    mt_srand($seed, MT_RAND_MT19937);
    for ($i = 0; $i < 32; $i++) { $mtRange150_300[] = mt_rand(150, 300); }

    // array_rand(size=6, num=1) — Util::choiceRandom 경로.
    $arrRand6 = [];
    $arr6 = [0,1,2,3,4,5];
    mt_srand($seed, MT_RAND_MT19937);
    for ($i = 0; $i < 32; $i++) { $arrRand6[] = array_rand($arr6); }

    return [
        'seed' => $seed,
        'note' => 'pure PHP MT19937 (MT_RAND_MT19937) conformance vectors; mt_rand()==rand() alias (PHP7.1+); Util::randRangeInt=mt_rand(min,max); Util::choiceRandom=array_rand.',
        'mt_rand' => $mtRand,
        'mt_rand_mod100' => $mtRandMod,
        'mt_rand_1_100' => $mtRange1_100,
        'mt_rand_150_300' => $mtRange150_300,
        'array_rand_size6' => $arrRand6,
    ];
}

// ── qualify() 승격(phase>=55) 캡처 ───────────────────────────────────────────
// 8조 × 8엔트리를 크래프트: 각 조 grp_no 0-4,6에 gd/gl/seq 타이를 심어 정렬 3키
// (win*3+draw desc, gl desc, seq)를 행사하고, grp_no 5/7(phase55 대전자)은 하위에 둬
// 대전 결과가 상위 4명 순위를 흔들지 않게 한다.
function captureQualifyPromote(object $db, array $TP2, int $tnmtType, int $seed): array {
    $tp2 = $TP2[$tnmtType];
    clearTournament($db);

    // grp_no별 (win,draw,lose,gl) 패턴. gd=win*3+draw.
    //  0: gd15,gl10          → rank1 (최고 gd)
    //  2: gd13,gl12 (seq 이른) → rank2 (gd13 중 gl 높음, seq 이른)
    //  3: gd13,gl12 (seq 늦음) → rank3 (gd13,gl12 동률 → seq)
    //  1: gd13,gl9           → rank4 (gd13 중 gl 낮음)
    //  4: gd6                → 미승격
    //  6: gd3                → 미승격
    //  5: gd0 (대전자)        → 미승격
    //  7: gd0 (대전자)        → 미승격
    // 삽입 순서 = grp_no 0..7 (seq 오름차순으로 grp_no 순). 2가 3보다 먼저 삽입되어 seq가 작다.
    $pattern = [
        0 => ['win'=>5,'draw'=>0,'gl'=>10],
        1 => ['win'=>4,'draw'=>1,'gl'=>9],
        2 => ['win'=>4,'draw'=>1,'gl'=>12],
        3 => ['win'=>3,'draw'=>4,'gl'=>12],
        4 => ['win'=>2,'draw'=>0,'gl'=>5],
        5 => ['win'=>0,'draw'=>0,'gl'=>0],
        6 => ['win'=>1,'draw'=>0,'gl'=>3],
        7 => ['win'=>0,'draw'=>0,'gl'=>0],
    ];
    $genId = 1;
    for ($grp = 0; $grp < 8; $grp++) {
        for ($no = 0; $no < 8; $no++) {
            $p = $pattern[$no];
            seedEntry($db, [
                'no' => $genId, 'name' => "G{$grp}_{$no}",
                'leadership' => 50 + $no, 'strength' => 50, 'intel' => 50, 'lvl' => 10,
                'grp' => $grp, 'grp_no' => $no,
                'win' => $p['win'], 'draw' => $p['draw'], 'lose' => 0, 'gl' => $p['gl'],
            ]);
            resetRankData($db, $genId, $tp2);
            $genId++;
        }
    }

    // 승격 직전 전체 조 상태(정렬 키 값 포함) 박제.
    $before = [];
    for ($grp = 0; $grp < 8; $grp++) {
        $rows = $db->query(
            'SELECT grp,grp_no,seq,win,draw,lose,gl,win*3+draw as gd from tournament where grp=%i order by grp_no',
            $grp
        );
        $before[$grp] = array_map(fn($r) => [
            'grpNo'=>(int)$r['grp_no'],'seq'=>(int)$r['seq'],'win'=>(int)$r['win'],'draw'=>(int)$r['draw'],
            'lose'=>(int)$r['lose'],'gl'=>(int)$r['gl'],'gd'=>(int)$r['gd'],
        ], $rows);
    }

    // gameStor를 승격 분기(phase>=55)에 맞춰 세팅하고 실제 qualify() 호출.
    $gameStor = KVStorage::getStorage($db, 'game_env');
    $gameStor->phase = 55;
    $gameStor->tournament = 2;

    ensureLogDir();
    for ($grp = 0; $grp < 8; $grp++) { clearTnmtLog($grp); }
    mt_srand($seed, MT_RAND_MT19937);
    qualify($tnmtType, 2, 55);

    // 승격 결과: 조별 prmt 배정 + KV 전이.
    $after = [];
    $promote = [];
    for ($grp = 0; $grp < 8; $grp++) {
        $rows = $db->query(
            'SELECT grp,grp_no,seq,win,draw,lose,gl,prmt,win*3+draw as gd from tournament where grp=%i order by grp_no',
            $grp
        );
        $after[$grp] = array_map(fn($r) => [
            'grpNo'=>(int)$r['grp_no'],'seq'=>(int)$r['seq'],'win'=>(int)$r['win'],'draw'=>(int)$r['draw'],
            'lose'=>(int)$r['lose'],'gl'=>(int)$r['gl'],'gd'=>(int)$r['gd'],'prmt'=>(int)$r['prmt'],
        ], $rows);
        // prmt 배정 순서(prmt 1..4 → grp_no) 요약.
        $prm = $db->query('SELECT grp_no,prmt from tournament where grp=%i AND prmt>0 order by prmt', $grp);
        $promote[$grp] = array_map(fn($r) => ['prmt'=>(int)$r['prmt'],'grpNo'=>(int)$r['grp_no']], $prm);
    }

    // HARD: 조별 정확히 4명 prmt, 그룹0 순서가 크래프트된 기대(rank1=no0, 2=no2, 3=no3, 4=no1)와 일치.
    foreach ($promote as $grp => $prm) {
        hardAssert(count($prm) === 4, "qualify: group {$grp} promoted " . count($prm) . " != 4");
    }
    $expectG0 = [['prmt'=>1,'grpNo'=>0],['prmt'=>2,'grpNo'=>2],['prmt'=>3,'grpNo'=>3],['prmt'=>4,'grpNo'=>1]];
    hardAssert(Json::encode($promote[0]) === Json::encode($expectG0),
        "qualify: group0 promote order " . Json::encode($promote[0]) . " != expected " . Json::encode($expectG0));

    $kvAfter = [
        'phase' => (int)$gameStor->phase,
        'tournament' => (int)$gameStor->tournament,
    ];
    hardAssert($kvAfter['tournament'] === 3 && $kvAfter['phase'] === 0,
        "qualify: KV transition wrong " . Json::encode($kvAfter));

    return [
        'desc' => 'qualify() phase>=55 promote branch: 8 groups x 8 entries, top-4 per group by (win*3+draw) desc, gl desc, seq. Fighters grp_no 5 vs 7 (getTwo(2,55)=[7,5]) kept below top-4.',
        'tnmtType' => $tnmtType,
        'tp2' => $tp2,
        'seed' => $seed,
        'promoteQuery' => 'SELECT ... win*3+draw as gd FROM tournament WHERE grp=%i ORDER BY gd DESC, gl DESC, seq LIMIT 0,4',
        'pattern' => $pattern,
        'before' => $before,
        'after' => $after,
        'promote' => $promote,
        'kvAfter' => $kvAfter,
    ];
}

// ═══════════════════════════════════════════════════════════════════════════
// 실행: 상태 스냅샷 → 캡처 → 원복.
// ═══════════════════════════════════════════════════════════════════════════
$tnmtBaseline = $db->query('SELECT * FROM tournament');
$rankBaseline = $db->query('SELECT id,value FROM rank_data'); // 값만 원복(타입/장수 불변).
$gameStor = KVStorage::getStorage($db, 'game_env');
$kvBaseline = [
    'phase' => $gameStor->phase,
    'tournament' => $gameStor->tournament,
];

// 아이템 코드(비-구매) — fight()의 아이템 플레이버 로그 행사용.
$NB_HORSE = 'che_명마_15_적토마';
$NB_WEAPON = 'che_무기_15_청홍검';
$NB_BOOK  = 'che_서적_07_논어';

// 실 general_id 2개(scenario_1010: no 오름차순 상위 → 결정적). rank_data 실 행 보유.
$gids = array_map(fn($r) => (int)$r['no'], $db->query('SELECT no FROM general ORDER BY no LIMIT 4'));
hardAssert(count($gids) >= 2, 'need >=2 real generals for rank_data');
[$GA, $GB] = [$gids[0], $gids[1]];

// 스탯: 서로 다른 전력으로 승부가 갈리도록. tnmt_type별 tp가 달라짐에 유의.
$specStrong = fn(int $no, string $name, string $h, string $w, string $b) =>
    ['no'=>$no,'name'=>$name,'l'=>90,'s'=>88,'i'=>85,'lvl'=>50,'h'=>$h,'w'=>$w,'b'=>$b];
$specWeak = fn(int $no, string $name, string $h, string $w, string $b) =>
    ['no'=>$no,'name'=>$name,'l'=>60,'s'=>58,'i'=>55,'lvl'=>40,'h'=>$h,'w'=>$w,'b'=>$b];

$fixtures = [];

// C1 — tnmt_type 0 (전력전, total), type 0 (승무패). 비-구매 마/무/서 3종 → 아이템 로그 3분기.
//      draw(sel=2)를 노려 무승부 분기 행사(스캔).
$fixtures[] = captureFight($db, $TP2, 'C1_type0_total_draw',
    'tnmt_type=0 total, type=0 승무패, 무승부 분기 + 비-구매 마/무/서 아이템 로그',
    0, 2, 55, 0, 0,
    $specStrong($GA, '관우', $NB_HORSE, $NB_WEAPON, $NB_BOOK),
    $specStrong($GB, '장비', $NB_HORSE, $NB_WEAPON, $NB_BOOK),
    2, 0);

// C2 — tnmt_type 0, type 1 (승패, 100턴, 재대결 가능). g1 승(sel=0) 스캔.
$fixtures[] = captureFight($db, $TP2, 'C2_type0_total_g1win_type1',
    'tnmt_type=0 total, type=1 승패(100턴), g1 승리',
    0, 4, 5, 0, 1,
    $specStrong($GA, '관우', $NB_HORSE, $NB_WEAPON, $NB_BOOK),
    $specWeak($GB, '하후돈', 'None', 'None', 'None'),
    0, 0);

// C3 — tnmt_type 1 (통솔전, leadership), type 0. g1 승. 비-구매 마 → 마 아이템 로그(tnmt_type 0/1).
$fixtures[] = captureFight($db, $TP2, 'C3_type1_leadership_g1win',
    'tnmt_type=1 leadership, type=0, g1 승리 + 비-구매 마 아이템 로그',
    1, 2, 55, 0, 0,
    $specStrong($GA, '조조', $NB_HORSE, 'None', 'None'),
    $specWeak($GB, '원소', 'None', 'None', 'None'),
    0, 0);

// C4 — tnmt_type 2 (일기토, strength), type 0. g2 승(sel=1) 스캔. 비-구매 무 → 무 아이템 로그(0/2).
$fixtures[] = captureFight($db, $TP2, 'C4_type2_strength_g2win',
    'tnmt_type=2 strength, type=0, g2 승리 + 비-구매 무 아이템 로그',
    2, 2, 55, 0, 0,
    $specWeak($GA, '유비', 'None', 'None', 'None'),
    $specStrong($GB, '여포', 'None', $NB_WEAPON, 'None'),
    1, 0);

// C5 — tnmt_type 3 (설전, intel), type 0. g1 승. 비-구매 서 → 서 아이템 로그(0/3).
$fixtures[] = captureFight($db, $TP2, 'C5_type3_intel_g1win',
    'tnmt_type=3 intel, type=0, g1 승리 + 비-구매 서 아이템 로그',
    3, 2, 55, 0, 0,
    $specStrong($GA, '제갈량', 'None', 'None', $NB_BOOK),
    $specWeak($GB, '왕랑', 'None', 'None', 'None'),
    0, 0);

// C6 — gl 분기: gen1 ttg > gen2 ttg 사전값에서 g1 승 → gl1=1, gl2=0 (동률 아닌 > 분기).
$fixtures[] = captureFight($db, $TP2, 'C6_type0_total_g1win_gl_gt',
    'tnmt_type=0, g1 승, gen1 ttg(base 5) > gen2 ttg(base 0) → gl1=1/gl2=0 분기',
    0, 2, 55, 0, 0,
    $specStrong($GA, '관우', 'None', 'None', 'None'),
    $specWeak($GB, '하후돈', 'None', 'None', 'None'),
    0, 0,
    ['ttg' => 5], ['ttg' => 0]);

// qualify 승격.
$qualify = captureQualifyPromote($db, $TP2, 0, 777);

// RNG 적합성 벡터(대표 seed 몇 개).
$rngVectors = [];
foreach ([1, 777, 12345] as $s) { $rngVectors[] = rngConformance($s); }

// ── 원복 ─────────────────────────────────────────────────────────────────────
clearTournament($db);
foreach ($tnmtBaseline as $row) { $db->insert('tournament', $row); }
foreach ($rankBaseline as $row) { $db->update('rank_data', ['value' => $row['value']], 'id=%i', $row['id']); }
$gameStor->phase = $kvBaseline['phase'];
$gameStor->tournament = $kvBaseline['tournament'];
for ($grp = 0; $grp < 50; $grp++) { clearTnmtLog($grp); }

$out = [
    'oracle' => 'devsam-core PHP 대회 fight()/qualify() (func_tournament.php:1004 fight, :583 qualify). PHP native rand()/mt_rand()/array_rand — pinned via mt_srand(seed, MT_RAND_MT19937). NOT sammo RandUtil/LiteHashDRBG.',
    'rng' => 'php_native_mt19937',
    'installIndependent' => 'tournament path does NOT consume UniqueConst::hiddenSeed (native mt_rand pinned by mt_srand); general_ids are scenario_1010-fixed. Fixture reproduces byte-identically on any fresh scenario_1010 install.',
    'note' => 'per-draw stream not recordable without editing the native rand oracle; faithfulness = exact seed + real fight/qualify output (logLines/row deltas) + pure MT19937 conformance vectors. Kotlin must port PHP-MT19937 + array_rand + mt_rand-range and verify against this golden.',
    'itemsUsed' => ['horse' => $NB_HORSE, 'weapon' => $NB_WEAPON, 'book' => $NB_BOOK],
    'precondition' => 'static-input: tournament table seeded with controlled entries on real general_ids (rank_data rows pre-exist via ResetHelper for all generals). Non-buyable items seeded to exercise item-flavor logs. Seeds chosen deterministically (scan) to reach target sel — every output is 100% real PHP for its seed.',
    'fightCases' => $fixtures,
    'qualifyPromote' => $qualify,
    'rngConformance' => $rngVectors,
];

file_put_contents($outPath, Json::encode($out, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES | JSON_PRETTY_PRINT));
fwrite(STDERR, sprintf("wrote %s (%d fight cases + qualify promote + %d rng vectors)\n",
    $outPath, count($fixtures), count($rngVectors)));
