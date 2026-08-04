<?php

$opts = getopt('', ['legacy-root:']);
$legacyRoot = $opts['legacy-root'] ?? null;
if ($legacyRoot === null || !is_dir($legacyRoot)) {
    fwrite(STDERR, "usage: php instrument_longsim_sql_rand.php --legacy-root=<disposable devsam-core copy>\n");
    exit(64);
}

$target = rtrim($legacyRoot, '/') . '/hwe/sammo/GeneralAI.php';
$source = file_get_contents($target);
if ($source === false) {
    fwrite(STDERR, "cannot read disposable GeneralAI.php\n");
    exit(1);
}

$replacements = [
    <<<'PHP'
        $cmd = buildGeneralCommandClass('che_선양', $this->general, $this->env, [
            'destGeneralID' => $db->queryFirstField('SELECT `no` FROM general WHERE nation = %i AND npc != 5 ORDER BY RAND() LIMIT 1', $this->general->getNationID())
        ]);
PHP
    =>
    <<<'PHP'
        $destGeneralID = $db->queryFirstField('SELECT `no` FROM general WHERE nation = %i AND npc != 5 ORDER BY RAND() LIMIT 1', $this->general->getNationID());
        recordLongsimSqlRandSelection(
            'seonyang',
            $this->general->getID(),
            (int)$this->env['year'],
            (int)$this->env['month'],
            $this->general->getNationID(),
            $destGeneralID === null ? null : (int)$destGeneralID
        );
        $cmd = buildGeneralCommandClass('che_선양', $this->general, $this->env, [
            'destGeneralID' => $destGeneralID
        ]);
PHP,
    <<<'PHP'
            $rulerNation = $db->queryFirstField(
                'SELECT nation FROM general WHERE `officer_level`=12 AND npc=9 and nation ORDER BY RAND() limit 1'
            );
PHP
    =>
    <<<'PHP'
            $rulerNation = $db->queryFirstField(
                'SELECT nation FROM general WHERE `officer_level`=12 AND npc=9 and nation ORDER BY RAND() limit 1'
            );
            recordLongsimSqlRandSelection(
                'orankae-ruler',
                $this->general->getID(),
                (int)$this->env['year'],
                (int)$this->env['month'],
                $this->general->getNationID(),
                $rulerNation === null ? null : (int)$rulerNation
            );
PHP,
    <<<'PHP'
        if (!$cmdList) {
            return null;
        }

        return $this->rng->choiceUsingWeightPair($cmdList);
PHP
    =>
    <<<'PHP'
        if (!$cmdList) {
            return null;
        }

        \sammo\beginLongsimDomesticDecision(
            $general->getID(),
            (int)$env['year'],
            (int)$env['month'],
            Util::simpleSerialize(
                UniqueConst::$hiddenSeed,
                'GeneralAI',
                $env['year'],
                $env['month'],
                $general->getID(),
            ),
            [
                'leadership' => (float)$leadership,
                'strength' => (float)$strength,
                'intel' => (float)$intel,
                'genType' => (int)$genType,
            ],
            [
                'cityId' => (int)$city['city'],
                'trust' => (float)$city['trust'],
                'pop' => (int)$city['pop'],
                'popMax' => (int)$city['pop_max'],
                'agri' => (int)$city['agri'],
                'agriMax' => (int)$city['agri_max'],
                'comm' => (int)$city['comm'],
                'commMax' => (int)$city['comm_max'],
                'secu' => (int)$city['secu'],
                'secuMax' => (int)$city['secu_max'],
                'def' => (int)$city['def'],
                'defMax' => (int)$city['def_max'],
                'wall' => (int)$city['wall'],
                'wallMax' => (int)$city['wall_max'],
            ],
            [
                'trust' => (float)$develRate['trust'],
                'pop' => (float)$develRate['pop'],
                'agri' => (float)$develRate['agri'],
                'comm' => (float)$develRate['comm'],
                'def' => (float)$develRate['def'],
                'wall' => (float)$develRate['wall'],
                'secu' => (float)$develRate['secu'],
            ],
            array_map(
                static fn(array $pair): array => [$pair[0]->getRawClassName(), (float)$pair[1]],
                $cmdList
            )
        );
        return $this->rng->choiceUsingWeightPair($cmdList);
PHP,
];

$newline = str_contains($source, "\r\n") ? "\r\n" : "\n";
foreach ($replacements as $before => $after) {
    if ($newline === "\r\n") {
        $before = str_replace("\n", "\r\n", $before);
        $after = str_replace("\n", "\r\n", $after);
    }
    $count = 0;
    $source = str_replace($before, $after, $source, $count);
    if ($count !== 1) {
        fwrite(STDERR, "instrumentation source match count was {$count}, expected 1\n");
        exit(2);
    }
}

if (file_put_contents($target, $source) === false) {
    fwrite(STDERR, "cannot write disposable GeneralAI.php\n");
    exit(1);
}

$generalBuilderTarget = rtrim($legacyRoot, '/') . '/hwe/sammo/Scenario/GeneralBuilder.php';
$generalBuilderSource = file_get_contents($generalBuilderTarget);
if ($generalBuilderSource === false) {
    fwrite(STDERR, "cannot read disposable GeneralBuilder.php\n");
    exit(1);
}
$generalBuilderNewline = str_contains($generalBuilderSource, "\r\n") ? "\r\n" : "\n";
$generalBuilderBefore = <<<'PHP'
        $this->generalID = $db->insertId();
PHP;
$generalBuilderAfter = <<<'PHP'
        $this->generalID = $db->insertId();
        if (function_exists('\sammo\recordLongsimKillturnTransition')) {
            \sammo\recordLongsimKillturnTransition(
                $this->generalID,
                null,
                (int)$killturn,
                'GeneralBuilder',
                'month-derived'
            );
        }
PHP;
if ($generalBuilderNewline === "\r\n") {
    $generalBuilderBefore = str_replace("\n", "\r\n", $generalBuilderBefore);
    $generalBuilderAfter = str_replace("\n", "\r\n", $generalBuilderAfter);
}
$generalBuilderCount = 0;
$generalBuilderSource = str_replace(
    $generalBuilderBefore,
    $generalBuilderAfter,
    $generalBuilderSource,
    $generalBuilderCount
);
if ($generalBuilderCount !== 1) {
    fwrite(STDERR, "GeneralBuilder killturn instrumentation source match count was {$generalBuilderCount}, expected 1\n");
    exit(2);
}
if (file_put_contents($generalBuilderTarget, $generalBuilderSource) === false) {
    fwrite(STDERR, "cannot write disposable GeneralBuilder.php\n");
    exit(1);
}

$foundNationTarget = rtrim($legacyRoot, '/') . '/hwe/sammo/Command/General/che_거병.php';
$foundNationSource = file_get_contents($foundNationTarget);
if ($foundNationSource === false) {
    fwrite(STDERR, "cannot read disposable che_거병.php\n");
    exit(1);
}
$foundNationNewline = str_contains($foundNationSource, "\r\n") ? "\r\n" : "\n";
$foundNationReplacements = [
    <<<'PHP'
        $diplomacyInit = [];
        foreach(getAllNationStaticInfo() as $destNation){
PHP
    =>
    <<<'PHP'
        $diplomacyInit = [];
        $longsimExistingNations = getAllNationStaticInfo();
        foreach($longsimExistingNations as $destNation){
PHP,
    <<<'PHP'
        if($diplomacyInit){
            $db->insert('diplomacy', $diplomacyInit);
        }
PHP
    =>
    <<<'PHP'
        if($diplomacyInit){
            $db->insert('diplomacy', $diplomacyInit);
            $longsimCreatedDiplomacyRows = $db->query(
                'SELECT `no`, `me`, `you` FROM diplomacy WHERE me = %i OR you = %i ORDER BY `no` ASC',
                $nationID,
                $nationID
            ) ?: [];
            \sammo\recordLongsimFoundNationDiplomacyIdentity(
                $general->getID(),
                $nationID,
                $nationName,
                array_values(array_map(
                    static fn(array $destNation): int => (int)$destNation['nation'],
                    $longsimExistingNations
                )),
                $longsimCreatedDiplomacyRows
            );
        }
PHP,
];
foreach ($foundNationReplacements as $before => $after) {
    if ($foundNationNewline === "\r\n") {
        $before = str_replace("\n", "\r\n", $before);
        $after = str_replace("\n", "\r\n", $after);
    }
    $count = 0;
    $foundNationSource = str_replace($before, $after, $foundNationSource, $count);
    if ($count !== 1) {
        fwrite(STDERR, "che_거병 diplomacy instrumentation source match count was {$count}, expected 1\n");
        exit(2);
    }
}
if (file_put_contents($foundNationTarget, $foundNationSource) === false) {
    fwrite(STDERR, "cannot write disposable che_거병.php\n");
    exit(1);
}

$deleteNationTarget = rtrim($legacyRoot, '/') . '/hwe/func.php';
$deleteNationSource = file_get_contents($deleteNationTarget);
if ($deleteNationSource === false) {
    fwrite(STDERR, "cannot read disposable func.php\n");
    exit(1);
}
$deleteNationNewline = str_contains($deleteNationSource, "\r\n") ? "\r\n" : "\n";
$deleteNationBefore = <<<'PHP'
    // 외교 삭제
    $db->delete('diplomacy', 'me = %i OR you = %i', $nationID, $nationID);
PHP;
$deleteNationAfter = <<<'PHP'
    // 외교 삭제
    $longsimDeletedDiplomacyRows = $db->query(
        'SELECT `no`, `me`, `you` FROM diplomacy WHERE me = %i OR you = %i ORDER BY `no` ASC',
        $nationID,
        $nationID
    ) ?: [];
    $longsimDeletionCaller = debug_backtrace(\DEBUG_BACKTRACE_IGNORE_ARGS, 2)[1] ?? [];
    $longsimDeletionProvenance = [
        'derivation' => 'deleteNation debug_backtrace frame 1',
        'class' => $longsimDeletionCaller['class'] ?? null,
        'type' => $longsimDeletionCaller['type'] ?? null,
        'function' => $longsimDeletionCaller['function'] ?? null,
    ];
    \sammo\recordLongsimNationDeletionDiplomacyIdentity(
        $lordID,
        $nationID,
        $nationName,
        $applyDB,
        $longsimDeletionProvenance,
        $longsimDeletedDiplomacyRows
    );
    $db->delete('diplomacy', 'me = %i OR you = %i', $nationID, $nationID);
PHP;
if ($deleteNationNewline === "\r\n") {
    $deleteNationBefore = str_replace("\n", "\r\n", $deleteNationBefore);
    $deleteNationAfter = str_replace("\n", "\r\n", $deleteNationAfter);
}
$deleteNationCount = 0;
$deleteNationSource = str_replace(
    $deleteNationBefore,
    $deleteNationAfter,
    $deleteNationSource,
    $deleteNationCount
);
if ($deleteNationCount !== 1) {
    fwrite(STDERR, "deleteNation diplomacy instrumentation source match count was {$deleteNationCount}, expected 1\n");
    exit(2);
}
if (file_put_contents($deleteNationTarget, $deleteNationSource) === false) {
    fwrite(STDERR, "cannot write disposable func.php\n");
    exit(1);
}

$randUtilTarget = rtrim($legacyRoot, '/') . '/src/sammo/RandUtil.php';
$randUtilSource = file_get_contents($randUtilTarget);
if ($randUtilSource === false) {
    fwrite(STDERR, "cannot read disposable RandUtil.php\n");
    exit(1);
}
$randUtilNewline = str_contains($randUtilSource, "\r\n") ? "\r\n" : "\n";
$weightedDrawBefore = <<<'PHP'
        $rd = $this->nextFloat1() * $sum;
        foreach ($items as [$item, $value]) {
PHP;
$weightedDrawAfter = <<<'PHP'
        $longsimCursorBefore = \sammo\readLongsimDrbgCursor($this->rng);
        $longsimDrawValue = $this->nextFloat1();
        $rd = $longsimDrawValue * $sum;
        \sammo\recordLongsimDomesticWeightedDraw(
            $longsimCursorBefore,
            \sammo\readLongsimDrbgCursor($this->rng),
            $longsimDrawValue,
            $sum,
            $items
        );
        foreach ($items as [$item, $value]) {
PHP;
if ($randUtilNewline === "\r\n") {
    $weightedDrawBefore = str_replace("\n", "\r\n", $weightedDrawBefore);
    $weightedDrawAfter = str_replace("\n", "\r\n", $weightedDrawAfter);
}
$weightedDrawCount = 0;
$randUtilSource = str_replace($weightedDrawBefore, $weightedDrawAfter, $randUtilSource, $weightedDrawCount);
if ($weightedDrawCount !== 1) {
    fwrite(STDERR, "RandUtil weighted draw instrumentation source match count was {$weightedDrawCount}, expected 1\n");
    exit(2);
}
if (file_put_contents($randUtilTarget, $randUtilSource) === false) {
    fwrite(STDERR, "cannot write disposable RandUtil.php\n");
    exit(1);
}

$randomImgwanTarget = rtrim($legacyRoot, '/') . '/hwe/sammo/Command/General/che_랜덤임관.php';
$randomImgwanSource = file_get_contents($randomImgwanTarget);
if ($randomImgwanSource === false) {
    fwrite(STDERR, "cannot read disposable che_랜덤임관.php\n");
    exit(1);
}
$randomImgwanNewline = str_contains($randomImgwanSource, "\r\n") ? "\r\n" : "\n";
$shuffleBefore = '            shuffle($nations);';
$shuffleAfter = <<<'PHP'
            shuffle($nations);
            \sammo\recordLongsimRandomImgwanPermutation(
                $general->getID(),
                (int)$env['year'],
                (int)$env['month'],
                array_column($nations, 'nation')
            );
PHP;
if ($randomImgwanNewline === "\r\n") {
    $shuffleAfter = str_replace("\n", "\r\n", $shuffleAfter);
}
$shuffleCount = 0;
$randomImgwanSource = str_replace($shuffleBefore, $shuffleAfter, $randomImgwanSource, $shuffleCount);
if ($shuffleCount !== 1) {
    fwrite(STDERR, "random imgwan shuffle source match count was {$shuffleCount}, expected 1\n");
    exit(2);
}
if (file_put_contents($randomImgwanTarget, $randomImgwanSource) === false) {
    fwrite(STDERR, "cannot write disposable che_랜덤임관.php\n");
    exit(1);
}

$turnHelperTarget = rtrim($legacyRoot, '/') . '/hwe/sammo/TurnExecutionHelper.php';
$turnHelperSource = file_get_contents($turnHelperTarget);
if ($turnHelperSource === false) {
    fwrite(STDERR, "cannot read disposable TurnExecutionHelper.php\n");
    exit(1);
}
$turnHelperNewline = str_contains($turnHelperSource, "\r\n") ? "\r\n" : "\n";
$turnHelperReplacements = [
    <<<'PHP'
            $general = General::createObjFromDB($rawGeneral['no']);
PHP
    =>
    <<<'PHP'
            $general = General::createObjFromDB($rawGeneral['no']);
            $longsimCommandCode = 'BLOCKED';
            $longsimNationCommandCode = null;
            \sammo\beginLongsimHandledCommand($general->getID());
PHP,
    <<<'PHP'
                    $rng = new RandUtil(new LiteHashDRBG(Util::simpleSerialize(
                        UniqueConst::$hiddenSeed,
                        'nationCommand',
PHP
    =>
    <<<'PHP'
                    $longsimNationCommandCode = $nationCommandObj->getRawClassName();
                    $rng = new RandUtil(new LiteHashDRBG(Util::simpleSerialize(
                        UniqueConst::$hiddenSeed,
                        'nationCommand',
PHP,
    <<<'PHP'
                $rng = new RandUtil(new LiteHashDRBG(Util::simpleSerialize(
                    UniqueConst::$hiddenSeed,
                    'generalCommand',
PHP
    =>
    <<<'PHP'
                $longsimCommandCode = $generalCommandObj->getRawClassName();
                $rng = new RandUtil(new LiteHashDRBG(Util::simpleSerialize(
                    UniqueConst::$hiddenSeed,
                    'generalCommand',
PHP,
    <<<'PHP'
            $turnObj->updateTurnTime();
            $turnObj->applyDB();
PHP
    =>
    <<<'PHP'
            $turnObj->updateTurnTime();
            $turnObj->applyDB();
            \sammo\recordLongsimHandledCommand(
                $general->getID(),
                $rawGeneral['turntime'],
                $longsimCommandCode,
                $longsimNationCommandCode,
                $general->getTurnTime()
            );
PHP,
    <<<'PHP'
        } else {
            $general->setVar('killturn', $killTurn);
        }
PHP
    =>
    <<<'PHP'
        } else {
            $longsimKillturnBefore = (int)$general->getVar('killturn');
            $general->setVar('killturn', $killTurn);
            \sammo\recordLongsimKillturnTransition(
                $general->getID(),
                $longsimKillturnBefore,
                (int)$killTurn,
                'human-reset',
                'execution-constant'
            );
        }
PHP,
    <<<'PHP'
                $general->setVar('killturn', ($general->getVar('deadyear') - $gameStor->year) * 12);
PHP
    =>
    <<<'PHP'
                $longsimKillturnBefore = (int)$general->getVar('killturn');
                $longsimKillturnAfter = ($general->getVar('deadyear') - $gameStor->year) * 12;
                $general->setVar('killturn', $longsimKillturnAfter);
                \sammo\recordLongsimKillturnTransition(
                    $general->getID(),
                    $longsimKillturnBefore,
                    (int)$longsimKillturnAfter,
                    'possession-release',
                    'month-derived'
                );
PHP,
];
foreach ($turnHelperReplacements as $before => $after) {
    if ($turnHelperNewline === "\r\n") {
        $before = str_replace("\n", "\r\n", $before);
        $after = str_replace("\n", "\r\n", $after);
    }
    $count = 0;
    $turnHelperSource = str_replace($before, $after, $turnHelperSource, $count);
    if ($count !== 1) {
        fwrite(STDERR, "turn helper instrumentation source match count was {$count}, expected 1\n");
        exit(2);
    }
}

$processStart = strpos($turnHelperSource, '    public function processCommand(');
$processEnd = strpos($turnHelperSource, '    function updateTurnTime()', $processStart);
if ($processStart === false || $processEnd === false) {
    fwrite(STDERR, "cannot isolate disposable processCommand body\n");
    exit(2);
}
$processBody = substr($turnHelperSource, $processStart, $processEnd - $processStart);
$processReplacements = [
    <<<'PHP'
        $commandClassName = $commandObj->getName();

        while (true) {
PHP
    =>
    <<<'PHP'
        $commandClassName = $commandObj->getName();
        \sammo\recordLongsimActionOutcome(false, 'started');

        while (true) {
PHP,
    <<<'PHP'
                $failString = $commandObj->getFailString();
                $text = "{$failString} <1>{$date}</>";
                $general->getLogger()->pushGeneralActionLog($text);
                break;
PHP
    =>
    <<<'PHP'
                $failString = $commandObj->getFailString();
                $text = "{$failString} <1>{$date}</>";
                $general->getLogger()->pushGeneralActionLog($text);
                \sammo\recordLongsimActionOutcome(false, 'condition-failed');
                break;
PHP,
    <<<'PHP'
                $termString = $commandObj->getTermString();
                $text = "{$termString} <1>{$date}</>";
                $general->getLogger()->pushGeneralActionLog($text);
                break;
PHP
    =>
    <<<'PHP'
                $termString = $commandObj->getTermString();
                $text = "{$termString} <1>{$date}</>";
                $general->getLogger()->pushGeneralActionLog($text);
                \sammo\recordLongsimActionOutcome(false, 'term-failed');
                break;
PHP,
    <<<'PHP'
            if ($result) {
                $commandObj->setNextAvailable();
                break;
            }
PHP
    =>
    <<<'PHP'
            if ($result) {
                $commandObj->setNextAvailable();
                \sammo\recordLongsimActionOutcome(true, 'success');
                break;
            }
PHP,
    <<<'PHP'
            if ($alt === null) {
                break;
            }
PHP
    =>
    <<<'PHP'
            if ($alt === null) {
                \sammo\recordLongsimActionOutcome(false, 'run-failed');
                break;
            }
PHP,
];
foreach ($processReplacements as $before => $after) {
    if ($turnHelperNewline === "\r\n") {
        $before = str_replace("\n", "\r\n", $before);
        $after = str_replace("\n", "\r\n", $after);
    }
    $count = 0;
    $processBody = str_replace($before, $after, $processBody, $count);
    if ($count !== 1) {
        fwrite(STDERR, "processCommand outcome instrumentation source match count was {$count}, expected 1\n");
        exit(2);
    }
}
$turnHelperSource =
    substr($turnHelperSource, 0, $processStart)
    . $processBody
    . substr($turnHelperSource, $processEnd);

if (file_put_contents($turnHelperTarget, $turnHelperSource) === false) {
    fwrite(STDERR, "cannot write disposable TurnExecutionHelper.php\n");
    exit(1);
}

$aiKillturnReplacements = [
    <<<'PHP'
            $general->setVar('killturn', $newKillTurn);
PHP
    =>
    <<<'PHP'
            $longsimKillturnBefore = (int)$general->getVar('killturn');
            $general->setVar('killturn', $newKillTurn);
            \sammo\recordLongsimKillturnTransition(
                $general->getID(),
                $longsimKillturnBefore,
                (int)$newKillTurn,
                'ai-gather-reroll',
                'execution-constant'
            );
PHP,
    <<<'PHP'
                $general->setVar('killturn', 1);
PHP
    =>
    <<<'PHP'
                $longsimKillturnBefore = (int)$general->getVar('killturn');
                $general->setVar('killturn', 1);
                \sammo\recordLongsimKillturnTransition(
                    $general->getID(),
                    $longsimKillturnBefore,
                    1,
                    'ai-npc-death',
                    'execution-constant'
                );
PHP,
];
$instrumentedAiSource = file_get_contents($target);
if ($instrumentedAiSource === false) {
    fwrite(STDERR, "cannot re-read disposable GeneralAI.php\n");
    exit(1);
}
foreach ($aiKillturnReplacements as $before => $after) {
    if ($newline === "\r\n") {
        $before = str_replace("\n", "\r\n", $before);
        $after = str_replace("\n", "\r\n", $after);
    }
    $count = 0;
    $instrumentedAiSource = str_replace($before, $after, $instrumentedAiSource, $count);
    if ($count !== 1) {
        fwrite(STDERR, "GeneralAI killturn instrumentation source match count was {$count}, expected 1\n");
        exit(2);
    }
}
if (file_put_contents($target, $instrumentedAiSource) === false) {
    fwrite(STDERR, "cannot write disposable GeneralAI.php killturn instrumentation\n");
    exit(1);
}

fwrite(STDERR, "instrumented disposable long-sim oracle inputs and killturn provenance\n");
