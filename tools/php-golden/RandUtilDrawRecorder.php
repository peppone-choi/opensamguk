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
        $r = parent::choiceUsingWeight($items);
        $this->record('choiceUsingWeight', ['items' => array_keys($items)], $r, $c, true);
        return $r;
    }

    public function shuffle(array $srcArray): array
    {
        // Not used by the war path; delegate without per-swap logging (records 0 entries
        // — documented in the backlog if a future fixture needs it).
        return parent::shuffle($srcArray);
    }
}
