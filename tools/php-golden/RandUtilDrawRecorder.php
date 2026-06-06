<?php
/**
 * RandUtilDrawRecorder.php — the P4 G1 RNG draw-recording seam (devsam-core PHP, grand truth).
 *
 * ONE-SHOT, MANUAL HOST STEP — NEVER CI.
 *
 * A faithful, DRAW-NEUTRAL decorator over the game's `RandUtil`. It records the
 * ORDERED draw stream produced by a battle's single shared `RandUtil(LiteHashDRBG(warSeed))`
 * — the load-bearing P4 parity surface (every nextBool/choice/nextRange/nextRangeInt/
 * nextInt/nextBit/nextFloat1 in the exact ORDER it is pulled off the ONE stream).
 *
 * WHY A SUBCLASS, NOT A WRAPPER: `WarUnit::__construct(public readonly RandUtil $rng, …)`
 * and `TriggerCaller::fire(RandUtil $rng, …)` are typed `RandUtil`. To thread the
 * recorder by reference into EVERY WarUnit + every trigger WITHOUT changing any call
 * site, the recorder MUST be an `instanceof RandUtil`. We extend `RandUtil` and override
 * each draw method to (1) snapshot the underlying LiteHashDRBG cursor BEFORE the draw,
 * (2) delegate to `parent::…` (the EXACT same RNG code path — byte-identical output),
 * (3) log `{seq, method, args, result, stateIdxBefore, bufferIdxBefore}`. The parent
 * holds `public readonly RNG $rng` (the LiteHashDRBG) so `parent::nextBool($p)` runs the
 * unmodified algorithm. The decorator consumes ZERO extra draws — it only OBSERVES.
 *
 * CURSOR FINGERPRINT: LiteHashDRBG exposes `protected int $stateIdx` (the SHA-512 block
 * counter, advanced by genNextBlock) and `protected int $bufferIdx` (the byte offset
 * within the current 64-byte block). Together they are the byte-exact position of the
 * stream. We read them via reflection (read-only; no behavior change) — the SAME
 * `stateIdx`/`bufferIdx` the Kotlin `LiteHashDrbg` exposes, so the Kotlin replay harness
 * asserts value-for-value AND cursor-for-cursor at the first divergent draw.
 *
 * Args are transcribed VERBATIM (the prob for nextBool, the [min,max] for nextRange/
 * nextRangeInt, the keys for choice). Never normalized — a short-circuited nextBool
 * ($prob>=1 / $prob<=0 → no draw) is recorded with consumed=false so the Kotlin side can
 * verify the short-circuit reproduces (NO cursor advance).
 */

namespace sammo;

class RandUtilDrawRecorder extends RandUtil
{
    /** @var array<int,array<string,mixed>> the ordered draw stream */
    private array $drawStream = [];
    private int $seq = 0;

    private ?\ReflectionProperty $rpState = null;
    private ?\ReflectionProperty $rpBuffer = null;

    public function __construct(RNG $rng)
    {
        parent::__construct($rng);
        // Reflect the LiteHashDRBG cursor properties once (read-only).
        try {
            $this->rpState = new \ReflectionProperty($rng, 'stateIdx');
            $this->rpBuffer = new \ReflectionProperty($rng, 'bufferIdx');
            if (PHP_VERSION_ID < 80100) {
                $this->rpState->setAccessible(true);
                $this->rpBuffer->setAccessible(true);
            }
        } catch (\ReflectionException $e) {
            // Cursor fields unknown — record null cursors rather than fake them.
            $this->rpState = null;
            $this->rpBuffer = null;
        }
    }

    private function cursor(): array
    {
        $state = null;
        $buffer = null;
        if ($this->rpState !== null && $this->rpState->isInitialized($this->rng)) {
            $state = (int)$this->rpState->getValue($this->rng);
        }
        if ($this->rpBuffer !== null && $this->rpBuffer->isInitialized($this->rng)) {
            $buffer = (int)$this->rpBuffer->getValue($this->rng);
        }
        return [$state, $buffer];
    }

    /**
     * Record one draw. $consumed=false means the method short-circuited (no stream
     * advance) — the cursor before == cursor after, recorded for the Kotlin assertion.
     */
    private function record(string $method, array $args, $result, array $cursorBefore, bool $consumed): void
    {
        $this->drawStream[] = [
            'seq'             => $this->seq++,
            'method'          => $method,
            'args'            => $args,
            'result'          => $result,
            'consumed'        => $consumed,
            'stateIdxBefore'  => $cursorBefore[0],
            'bufferIdxBefore' => $cursorBefore[1],
        ];
    }

    public function getDrawStream(): array
    {
        return $this->drawStream;
    }

    public function getDrawCount(): int
    {
        return count($this->drawStream);
    }

    // ── overridden draw methods (snapshot cursor BEFORE → delegate → log) ─────────

    public function nextFloat1(): float
    {
        $c = $this->cursor();
        $r = parent::nextFloat1();
        $this->record('nextFloat1', [], $r, $c, true);
        return $r;
    }

    public function nextRange(int|float $min, int|float $max): float
    {
        $c = $this->cursor();
        // nextRange = nextFloat1()*range+min — ONE float draw. We must NOT call the
        // overridden nextFloat1 (would double-log); replicate via the parent path on
        // the bare RNG so exactly one stream entry is recorded for the nextRange.
        $range = $max - $min;
        $r = $this->rng->nextFloat1() * $range + $min;
        $this->record('nextRange', ['min' => $min, 'max' => $max], $r, $c, true);
        return $r;
    }

    public function nextRangeInt(int $min, int $max): int
    {
        $c = $this->cursor();
        $r = parent::nextRangeInt($min, $max);
        $this->record('nextRangeInt', ['min' => $min, 'max' => $max], $r, $c, true);
        return $r;
    }

    public function nextInt(?int $max = null): int
    {
        $c = $this->cursor();
        $r = parent::nextInt($max);
        $this->record('nextInt', ['max' => $max], $r, $c, true);
        return $r;
    }

    public function nextBit(): bool
    {
        $c = $this->cursor();
        $r = parent::nextBit();
        $this->record('nextBit', [], $r, $c, true);
        return $r;
    }

    public function nextBool(int|float $prob = 0.5): bool
    {
        $c = $this->cursor();
        // Reproduce RandUtil::nextBool's branch structure so the recorded `consumed`
        // flag matches the real stream advance (the short-circuits are load-bearing).
        if ($prob >= 1) {
            $this->record('nextBool', ['prob' => $prob], true, $c, false);
            return true;
        }
        if ($prob === 0.5) {
            // delegates to nextBit (1 bit draw); record as nextBool (the trigger-level call)
            $r = $this->rng->nextBits(1) !== "\0";
            $this->record('nextBool', ['prob' => $prob], $r, $c, true);
            return $r;
        }
        if ($prob <= 0) {
            $this->record('nextBool', ['prob' => $prob], false, $c, false);
            return false;
        }
        $r = $this->rng->nextFloat1() < $prob;
        $this->record('nextBool', ['prob' => $prob], $r, $c, true);
        return $r;
    }

    public function choice(array $items)
    {
        $c = $this->cursor();
        // choice = rng->nextInt(count(keys)-1) then return items[keys[idx]].
        // The RNG-consuming value is the nextInt INDEX; the returned value is items[idx].
        // For the magic-table choice (che_계략시도.php:66/70 passes array_keys(table), a
        // LIST whose own keys are 0..n-1), the index IS the parity target and the value
        // is the magic name. Record both: `result` = chosen value, `choiceIndex` = the
        // nextInt draw (the cursor-load-bearing integer the Kotlin replay must match).
        $keys = array_keys($items);
        if (!$keys) {
            throw new \InvalidArgumentException();
        }
        $keyIdx = $this->rng->nextInt(count($keys) - 1);
        $chosenKey = $keys[$keyIdx];
        $chosenValue = $items[$chosenKey];
        $entry = [
            'seq'             => $this->seq++,
            'method'          => 'choice',
            'args'            => ['items' => $items],
            'choiceIndex'     => $keyIdx,
            'result'          => $chosenValue,
            'consumed'        => true,
            'stateIdxBefore'  => $c[0],
            'bufferIdxBefore' => $c[1],
        ];
        $this->drawStream[] = $entry;
        return $chosenValue;
    }

    public function choiceUsingWeight(array $items)
    {
        $c = $this->cursor();
        // RandUtil::choiceUsingWeight는 정확히 ONE nextFloat1() draw를 소비한다
        // (`$rd = nextFloat1()*$sum` 한 번; 이후 누적-감산 비교는 RNG 비소비).
        // parent::choiceUsingWeight를 호출하면 그 내부 `$this->nextFloat1()`이 이 클래스의
        // 오버라이드를 타서 nextFloat1이 별도 stream 엔트리로 이중 기록된다(choiceUsingWeight
        // 직전에 spurious nextFloat1). choiceUsingWeightPair/nextRange와 동일하게 bare RNG에서
        // nextFloat1을 직접 뽑아 정확히 1개 choiceUsingWeight 엔트리만 남긴다 — RNG 출력은
        // byte-identical(동일한 단일 float draw), 로그만 정확해진다.
        if (!$items) {
            throw new \InvalidArgumentException();
        }
        $sum = 0;
        foreach ($items as $value) {
            if ($value <= 0) { continue; }
            $sum += $value;
        }
        $rd = $this->rng->nextFloat1() * $sum;
        $chosen = null;
        foreach ($items as $item => $value) {
            if ($value <= 0) { $value = 0; }
            if ($rd <= $value) { $chosen = $item; break; }
            $rd -= $value;
        }
        if ($chosen === null) {
            // fallback. RandUtil 원본과 동일 — 정상 경로에서는 도달하지 않음.
            end($items);
            $chosen = $items[key($items)][0];
        }
        $this->record('choiceUsingWeight', ['items' => array_keys($items)], $chosen, $c, true);
        return $chosen;
    }

    /**
     * choiceUsingWeightPair — 가중치-쌍 배열에서 항목 1개 선택.
     * RandUtil::choiceUsingWeightPair는 정확히 ONE nextFloat1() draw를 소비한다
     * (`$rd = nextFloat1()*$sum` 한 번; 이후 누적 비교는 RNG 비소비).
     * giveRandomUniqueItem의 유니크 아이템 선택이 이 메서드를 통과하므로,
     * vote 로또 골든의 두 번째(아이템 선택) draw를 정확히 기록하기 위해 오버라이드한다.
     * args로 가중치 쌍 풀([itemType,itemCode]=>weight 순서)을 그대로 박제 —
     * Kotlin replay가 동일 풀+동일 float로 동일 항목을 골라내는지 검증하는 오라클.
     */
    public function choiceUsingWeightPair(array $items)
    {
        $c = $this->cursor();
        // RandUtil 본체와 byte-identical하게: bare RNG에서 nextFloat1을 직접 뽑아
        // (오버라이드 nextFloat1을 호출하면 이중 기록되므로) 정확히 1개 stream 엔트리만 남긴다.
        $sum = 0;
        foreach ($items as [$item, $value]) {
            if ($value <= 0) { continue; }
            $sum += $value;
        }
        $rd = $this->rng->nextFloat1() * $sum;
        $chosen = null;
        foreach ($items as [$item, $value]) {
            if ($value <= 0) { $value = 0; }
            if ($rd <= $value) { $chosen = $item; break; }
            $rd -= $value;
        }
        if ($chosen === null) {
            // fallback. RandUtil 원본과 동일 — 정상 경로에서는 도달하지 않음.
            end($items);
            $chosen = $items[key($items)][0];
        }
        // args.pool = [[itemType,itemCode], weight] 순서대로 (insertion order 보존).
        $pool = [];
        foreach ($items as [$pair, $weight]) { $pool[] = ['pair' => $pair, 'weight' => $weight]; }
        $this->record('choiceUsingWeightPair', ['pool' => $pool], $chosen, $c, true);
        return $chosen;
    }

    public function shuffle(array $srcArray): array
    {
        // Not used by the war path; delegate without per-swap logging (records 0 entries
        // — documented in the backlog if a future fixture needs it).
        return parent::shuffle($srcArray);
    }
}
