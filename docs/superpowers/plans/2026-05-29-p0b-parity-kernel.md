# P0-B: Parity Kernel + CQRS Skeleton Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development. Execute each task as a checkbox step (`- [ ]`), TDD red→green, one logical commit per task, ending every commit message with the `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>` trailer. The orchestrator (not you) creates branches and commits after review; treat the inline `git` lines as the intended commit boundaries, not as instructions to run git yourself. Verify build success by tail/grep of the gradle output, never by exit code (project memory: task-notification exit 0 is unreliable). Every gradle invocation MUST be prefixed `JAVA_HOME=$(/usr/libexec/java_home -v 21)` — Gradle 8.x fails to parse Java 25. Run from repo root `/Users/apple/Desktop/개인프로젝트/opensamguk` via `./gradlew`.

**Goal:** Build the deterministic parity kernel and the CQRS write-loop skeleton that every later phase depends on: a byte-exact RNG (`LiteHashDRBG`/`RandUtil`/seed serializers), the Korean-josa + color-tag log-token model, the immutable game constants + scenario-override layer, the sealed Redis wire contract (commands/results/events/realtime), and the in-memory turn world with a flush-order stub, flush-exclusion contract, Redis Streams consumer, and SSE relay. Each unit is a faithful port verified against committed golden fixtures generated once from the reference source (TS `devsam-core2026` first, PHP `devsam-core` as second oracle / grand truth for constants).

**Architecture:** memory-CQRS skeleton. `InMemoryTurnWorld` is the single source of truth during a tick; mutations record into dirty/created/deleted sets; `consumeDirtyState()` is a single-shot drain; the flush path replays the exact `databaseHooks.ts` write ORDER through a `FlushOpRecorder` seam. The daemon write path **NEVER uses a JPA EntityManager for writes** (design §0.1 #3) — enforced structurally (recorder-only sink) and by a class-file constant-pool guard test. The per-season flush-exclusion contract (`inheritance_*`/`storage` non-`general_%`/`hall`/`ng_games`) lives OUTSIDE the world+flush boundary (§7). Redis Streams (`XREAD BLOCK COUNT 100` from cursor `$`) carry the api→daemon control plane; a separate pub/sub channel carries the coarse player-facing `turnCompleted` signal that game-api fans out via `SseEmitter`.

**Tech Stack:** Kotlin 2.1 / Spring Boot 3.4 / Lettuce Redis (Spring Data Redis, Lettuce default — no Jedis) / JDBC batch (flush execution deferred to P1; P0-B records op intent only) / SHA-512 DRBG (JDK `MessageDigest`) / Testcontainers (`redis:7-alpine`, macOS Docker Desktop quirk replicated). Module layout: `:common` (pure kotlin-jvm, `jvmToolchain(21)`, JUnit5, kotlinx-serialization-json added test+impl scope), `:app:game-engine`, `:app:game-api`, `:infra` (P0-A baseline). Test command base: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :<module>:test`.

---

## File Structure

All paths relative to repo root `/Users/apple/Desktop/개인프로젝트/opensamguk`.

```
gradle/
  libs.versions.toml                         # +kotlinxSerialization version, kotlinx-serialization-json lib, kotlin-serialization plugin
common/
  build.gradle.kts                           # +kotlin-serialization plugin, +kotlinx-serialization-json (impl for wire, test for goldens)
  src/main/kotlin/opensamguk/common/
    rng/
      Sha512.kt                              # SHA-512 byte source (JDK MessageDigest)
      BytesLike.kt                           # UTF-8 seed → ByteArray conversion
      LiteHashDrbg.kt                        # DRBG byte engine + nextInt/nextFloat1 (LE block gen)
      RandUtil.kt                            # shuffle/choice/weighted/nextBool draw utilities
      JsKeyOrder.kt                          # JS object key-order helper (numeric-first)
      SeedSerializer.kt                      # serializeSeed + buildTournamentSeedKey (double-pipe quirk)
    josa/
      Josa.kt                                # JosaTables + JosaDetect + JosaUtil.pick/put
    log/
      LogFormat.kt                           # LogFormat enum (codes 0–8 = serialization contract)
      LogFormatter.kt                        # formatLogText icon-prefix
      ConvertLog.kt                          # color-tag → HTML renderer (14 ordered replacements)
      LogEntry.kt                            # LogScope/LogCategory/LogEntryDraft/Record/Context
      ActionLogger.kt                        # buffer/flush, per-branch truthiness
      UserLogger.kt                          # user-scope logger
      FinalizeLogEntry.kt                    # shouldDropEntry + finalizeLogEntry
    constants/
      GameConst.kt                           # immutable boot config (faithful PHP port)
      GameUnitConst.kt                       # 40-row 18-tuple unit catalog + _generate()
      GameUnitDetail.kt                      # unit detail data class
      UnitConstraint.kt                      # sealed constraint + info()
      CityConst.kt                           # 94-row 13-tuple city catalog (×100, bidirectional path)
      CityInitialDetail.kt                   # city detail data class
      EffectiveGameConst.kt                  # base + scenario-override merge + derived killturn/develcost
      ScenarioConstOverride.kt               # untyped override map wrapper
    wire/
      TurnDaemonWire.kt                       # state/reason enums + status/budget/checkpoint/result value types
      TurnDaemonCommand.kt                    # sealed command union (~28 variants, type discriminator)
      TurnDaemonCommandResult.kt              # dual-discriminator (type + ok) result union
      TurnDaemonEvent.kt                      # daemon event union (nests CommandResult)
      RealtimeEvent.kt                        # player-facing pub/sub channel type
      WireEnvelope.kt                         # command/event envelope + WIRE_PAYLOAD_FIELD
      StreamKeys.kt                           # turn-daemon stream keys + realtime channel
      WireJson.kt                             # shared Json codec (classDiscriminator=type)
  src/test/kotlin/opensamguk/common/
    rng/ Sha512Test, LiteHashDrbgBytesTest, LiteHashDrbgIntFloatTest, RandUtilTest, JsKeyOrderTest, SeedSerializerTest, RngKernelParityGateTest
    josa/ JosaMapTest, HasJongsungTest, JosaUtilTest
    log/ LogFormatterTest, ConvertLogTest, ActionLoggerTest, FinalizeLogEntryTest
    golden/ JosaLogGoldenTest
    constants/ GameConstTest, GameUnitConstTest, CityConstTest, EffectiveGameConstTest
    wire/ TurnDaemonWireTypesTest, TurnDaemonCommandWireTest, TurnDaemonCommandResultWireTest, RealtimeEventWireTest, StreamKeysTest
  src/test/resources/
    rng/rng-fixtures.json                    # committed RNG golden (one-shot TS dump)
    golden/josa/{pick,hasJongsung}.json
    golden/log/{convertLog,formatLogText,render-e2e,actionLogger}.json
    golden/{game_unit_const,city_const}.golden.json
    golden/wire/{wire_commands_valid,wire_commands_malformed,wire_results,wire_realtime}.json
app/game-engine/
  build.gradle.kts                           # +:common, kotlinx-serialization-json, data-redis (Lettuce), testcontainers
  src/main/kotlin/opensamguk/engine/
    turn/ TurnWorldModel.kt, DirtyState.kt, InMemoryTurnWorld.kt
    flush/ FlushOp.kt, DatabaseHooks.kt, TruncateContract.kt
    redis/ RedisCommandStream.kt, RealtimePublisher.kt   # consume :common TurnDaemonStreamKeys.of / gameEventChannel (no engine-local StreamKeys.kt)
  src/test/kotlin/opensamguk/engine/
    turn/InMemoryTurnWorldTest.kt
    flush/{DatabaseHooksOrderTest,DaemonNoEntityManagerTest,TruncateContractTest}.kt
    redis/RedisCommandStreamIT.kt
app/game-api/
  build.gradle.kts                           # +:common, data-redis, kotlinx-serialization-json, testcontainers
  src/main/kotlin/opensamguk/gameapi/sse/
    RealtimeSubscriber.kt, RealtimeRelayController.kt
  src/test/kotlin/opensamguk/gameapi/sse/RealtimeRelayIT.kt
tools/
  rng-dump/{package.json, dumpRngFixtures.mjs, README.md}    # one-shot RNG golden generator
  golden-dump/josa-log-golden.mjs                            # one-shot josa/log golden generator
```

> **Build prerequisite shared by every area:** `kotlinx.serialization` must be added once to `gradle/libs.versions.toml` (`[versions] kotlinxSerialization = "1.7.3"`; `[libraries] kotlinx-serialization-json = { module = "org.jetbrains.kotlinx:kotlinx-serialization-json", version.ref = "kotlinxSerialization" }`; `[plugins] kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }`) and applied in `common/build.gradle.kts`. The RNG and josa-log areas use it **test-scope only** (golden JSON parsing); the wire area uses it **implementation-scope** (it IS the wire codec). Whichever area lands first performs this build edit; later areas verify it is present rather than re-adding it — EXCEPT the wire area (Task 19), which must PROMOTE the existing `testImplementation` kotlinx-serialization-json declaration to `implementation` scope (impl subsumes test, so RNG/josa-log test imports still resolve — never downgrade). If the wire area lands first it declares `implementation` directly. This is the single shared build mutation across areas — see Self-Review for the consistency check.

---

# AREA 1 — RNG Parity Kernel (gates everything)

> **Port target = TS `devsam-core2026/packages/common/src`. PHP is the second oracle.** This kernel gates every rule line of every later phase (§10.1 RNG 골든). Byte/draw-for-draw parity is non-negotiable. **This area MUST land before any other** — josa-log, constants-wire, and world-flush-redis all assume a green RNG kernel exists (RandUtil/serializeSeed are consumed by rules in P1+).
>
> **Module facts (verified):** opensamguk is a Gradle Kotlin-JVM multi-module project. `common/` is `kotlin.jvmToolchain(21)`, `useJUnitPlatform()`, `testImplementation(kotlin("test"))`. Kotlin package root for common = `opensamguk.common`. Test command always runs from repo root: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :common:test`.
>
> **Source-of-truth facts pinned during research (do NOT re-derive — port verbatim):**
> - `bufferByteSize = 64` (SHA-512). `maxInt = 0x1f_ffff_ffff_ffff = 2^53-1 = 9007199254740991`. `maxIntMore1 = 0x20_0000_0000_0000 = 2^53 = 9007199254740992`. `maxRngSupportBit = 53`.
> - stateIdx is appended as a **LITTLE-ENDIAN uint32** (`DataView.setUint32(pos,val,true)` / PHP `pack('V',...)`).
> - `genNextBlock()` runs **FIRST** in the constructor (consumes stateIdx 0 → fills block → stateIdx=1), THEN ctor `bufferIdx` is assigned.
> - `_nextInt(bits) = nextBits(bits, baseBytes=8)` then read **little-endian uint64** (`getBigUint64(0,true)`).
> - `nextInt(max)` rejection loop is `while (n > max)` → **INCLUSIVE upper bound** (n==max accepted). `max>maxInt` throws "Over max int".
> - `nextFloat1()`: draw `_nextInt(54)`; if `<2^53` return `n/2^53`; if `==2^53` return `1.0`; else redraw.
> - `nextBytes(bytes, baseBytes?)`: **real bytes consumed == `bytes` regardless of `baseBytes`**; `baseBytes` only widens the output array (trailing zeros). Eager refill `if (bufferIdx==64) genNextBlock()` AFTER slicing is mandatory.
> - `choice`/`choiceUsingWeight` over a Record iterate keys in **JS object order**: integer-like keys ascending-numeric FIRST, then string keys in insertion order (`{c,a,b,4,2,'3'}` → `[2,3,4,c,a,b]`). PHP uses insertion order and **diverges** → follow TS.
> - `serializeSeed(...vals)`: per value `typeof string → str(len,val)` (len = UTF-16 code-unit count = Kotlin `String.length`), else `int(floor(n))`; join with `|`.
> - `buildTournamentSeedKey` emits `|game:X`/`|extra:Y` tokens each WITH a leading `|`, filters empty strings, then joins with `|` → produces the **double-pipe** `...participant:N||game:X` quirk. Reproduce exactly.
>
> **Big-endian trap (highest-likelihood silent break):** NEVER use `DataOutputStream`/`ByteBuffer` default order. Use `ByteBuffer.order(ByteOrder.LITTLE_ENDIAN)` for both the stateIdx uint32 write and the u64 read.
>
> **Unsigned-64 decision:** all call sites use `bits ≤ 54`, so the LE u64 read fits a signed JVM `Long` (top bytes always zero, never negative). Represent as **`Long`** (not `BigInteger`). Goldens fail loudly if a future >54-bit caller appears.

### Task 1 — SHA-512 byte source + `BytesLike` UTF-8 conversion (the DRBG substrate)

**Files:**
- create `common/src/main/kotlin/opensamguk/common/rng/Sha512.kt`
- create `common/src/main/kotlin/opensamguk/common/rng/BytesLike.kt`
- create `common/src/test/kotlin/opensamguk/common/rng/Sha512Test.kt`

Steps:
- [ ] Create `BytesLike.kt`. TS `convertBytesLikeToUint8Array` only ever receives a `String` (production seeds) or raw bytes. String path = `TextEncoder().encode` = **UTF-8**:
  ```kotlin
  package opensamguk.common.rng

  /** Mirrors TS convertBytesLikeToUint8Array(data, encodeUTF8=true). Production seeds are always Strings → UTF-8. */
  fun bytesLikeToByteArray(seed: String): ByteArray = seed.toByteArray(Charsets.UTF_8)

  fun bytesLikeToByteArray(seed: ByteArray): ByteArray = seed.copyOf()
  ```
- [ ] Create `Sha512.kt` using JDK `MessageDigest` (no external dep; matches `node:crypto` + `@noble/hashes`):
  ```kotlin
  package opensamguk.common.rng

  import java.security.MessageDigest

  /** SHA-512 digest of [input]; 64-byte output. Matches TS sha512Bytes (node:crypto / @noble/hashes). */
  fun sha512(input: ByteArray): ByteArray =
      MessageDigest.getInstance("SHA-512").digest(input)
  ```
- [ ] Create `Sha512Test.kt`. Keep only the empty-string FIPS vector and a UTF-8 length test (the load-bearing SHA-512 verification is the stateIdx-appended block vector in Task 3):
  ```kotlin
  package opensamguk.common.rng

  import kotlin.test.Test
  import kotlin.test.assertEquals

  class Sha512Test {
      private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

      @Test
      fun `sha512 of empty string matches FIPS vector`() {
          assertEquals(
              "cf83e1357eefb8bdf1542850d66d8007d620e4050b5715dc83f4a921d36ce9ce" +
              "47d0d13c5d85f2b0ff8318d2877eec2f63b931bd47417a81a538327af927da3e",
              hex(sha512("".toByteArray(Charsets.UTF_8))),
          )
      }

      @Test
      fun `utf8 seed conversion byte length`() {
          // '한' is 3 UTF-8 bytes; ensures we are NOT using UTF-16 or codepoint-as-byte.
          assertEquals(3, bytesLikeToByteArray("한").size)
      }
  }
  ```
- [ ] Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :common:test --tests 'opensamguk.common.rng.Sha512Test'`. Expected: `BUILD SUCCESSFUL`, 2 tests passing.
- [ ] Commit: `feat(common/rng): SHA-512 byte source + UTF-8 seed conversion` + Co-Authored-By trailer.

### Task 2 — TS fixture dump script under `tools/` (the golden generator)

**Files:**
- create `tools/rng-dump/package.json`, `tools/rng-dump/dumpRngFixtures.mjs`, `tools/rng-dump/README.md`
- output (committed test resource) `common/src/test/resources/rng/rng-fixtures.json`

> Imports the TS kernel from `legacy/devsam-core2026/packages/common`, emits a single JSON fixture consumed by every Kotlin RNG golden test. Generate ONCE, commit the JSON, never run per-CI. **Prerequisite (one-shot, manual host step — never CI):** from `legacy/devsam-core2026` root run `pnpm install` (pnpm 10) so `@noble/hashes` resolves — verified: the package has no node_modules/dist today. Run via `npx tsx` to import `.ts` directly; the DOCUMENTED FALLBACK only (if tsx fails) is `pnpm --filter @sammo-ts/common build` + import `dist/index.js`.

Steps:
- [ ] Create `tools/rng-dump/package.json`:
  ```json
  {
    "name": "opensamguk-rng-dump",
    "private": true,
    "type": "module",
    "scripts": { "dump": "npx tsx dumpRngFixtures.mjs" }
  }
  ```
- [ ] Create `tools/rng-dump/dumpRngFixtures.mjs`. Imports the kernel + the three seed serializers from the legacy TS source and emits every fixture; floats emitted as **raw 64-bit hex** (`writeDoubleBE`) to remove printf ambiguity:
  ```js
  import { fileURLToPath } from 'node:url';
  import { dirname, resolve } from 'node:path';
  import { writeFileSync } from 'node:fs';

  const here = dirname(fileURLToPath(import.meta.url));
  const SRC = resolve(here, '../../legacy/devsam-core2026/packages/common/src');

  const { LiteHashDRBG } = await import(`${SRC}/util/LiteHashDRBG.ts`);
  const { RandUtil } = await import(`${SRC}/util/RandUtil.ts`);
  const { createTournamentSeedKey } = await import(`${SRC}/util/TournamentRNG.ts`);

  const hex = (u8) => Buffer.from(u8).toString('hex');
  const floatBits = (d) => { const b = Buffer.alloc(8); b.writeDoubleBE(d, 0); return b.toString('hex'); };

  const out = {};

  // (1) raw SHA-512 blocks for stateIdx 0..4 on seed 'HelloWorld' (= rng.test.ts testVector)
  { const rng = new LiteHashDRBG('HelloWorld'); out.helloWorldBlocks = hex(rng.nextBytes(64 * 5)); }

  // (2) byte-stream draws incl baseBytes pad
  { const rng = new LiteHashDRBG('HelloWorld');
    out.bytesSeq = { b10: hex(rng.nextBytes(10)), b32: hex(rng.nextBytes(32)), b1: hex(rng.nextBytes(1)),
      b64: hex(rng.nextBytes(64)), b5: hex(rng.nextBytes(5)), b16pad18: hex(rng.nextBytes(16, 18)) }; }

  // (3) bit draws
  { const rng = new LiteHashDRBG('HelloWorld'); out.bitsSeq = {};
    for (const bits of [10, 4, 15, 32, 7, 99, 512, 1, 2, 3]) out.bitsSeq[String(bits)] = hex(rng.nextBits(bits)); }

  // (4) nextFloat1 — 18 draws as raw 64-bit hex
  { const rng = new LiteHashDRBG('HelloWorld'); out.floatSeq = Array.from({ length: 18 }, () => floatBits(rng.nextFloat1())); }

  // (5) nextInt draws (incl rejection-sampling i99 and default path)
  { const rng = new LiteHashDRBG('HelloWorld');
    out.intSeq = { i255: rng.nextInt(0xff), i65535: rng.nextInt((1 << 16) - 1), i4G: rng.nextInt(0xffffffff),
      iDefault: rng.nextInt(), i15: rng.nextInt(0x0f), i18: rng.nextInt(0x12), i99: rng.nextInt(99) }; }

  // (5b) inclusive-max golden: a deterministic block where nextInt(max) draws EXACTLY max (proves n==max ACCEPTED, not just in-range).
  //      Find a seed/max pair from a known block whose first nextInt(max) == max; e.g. the TS oracle pins nextInt(0x99)→0x99.
  { const rng = new LiteHashDRBG('inclusiveMax');
    out.intInclusiveMax = { max: 0x99, draw: rng.nextInt(0x99) }; }   // assert draw === max in the Kotlin test

  // (6) alignment-stress: >5 block refills, alternating widths
  { const rng = new LiteHashDRBG('alignStress'); const seq = [];
    for (let i = 0; i < 40; i++) { seq.push(['bits7', hex(rng.nextBits(7))]); seq.push(['bytes1', hex(rng.nextBytes(1))]); seq.push(['int99', rng.nextInt(99)]); }
    out.alignStress = seq; }

  // (7) RandUtil draws on a fixed seed
  { const mk = () => new RandUtil(new LiteHashDRBG('randUtilSeed'));
    const range = (n) => Array.from({ length: n }, (_, i) => i);
    let ru = mk(); out.randUtil = { nextInt_5_10: Array.from({ length: 8 }, () => ru.nextInt(5, 10)) };
    ru = mk(); out.randUtil.nextRangeInt_0_9 = Array.from({ length: 8 }, () => ru.nextRangeInt(0, 9));
    ru = mk(); out.randUtil.nextBool_half = Array.from({ length: 16 }, () => ru.nextBool(0.5));
    ru = mk(); out.randUtil.nextBool_0_3 = Array.from({ length: 16 }, () => ru.nextBool(0.3));
    ru = mk(); out.randUtil.nextBit = Array.from({ length: 16 }, () => ru.nextBit());
    ru = mk(); out.randUtil.nextFloat1 = Array.from({ length: 6 }, () => floatBits(ru.nextFloat1()));
    out.randUtil.shuffle = {};
    for (const n of [0, 1, 2, 8, 10, 17]) { const r = mk(); out.randUtil.shuffle[String(n)] = r.shuffle(range(n)); }
    ru = mk(); out.randUtil.choiceArray = Array.from({ length: 6 }, () => ru.choice([0, 1, 2, 3, 4, 5]));
    ru = mk(); out.randUtil.choiceSet = ru.choice(new Set([5, 3, 1, 2, 8, 0]));
    ru = mk(); out.randUtil.choiceRecord = ru.choice({ c: 'c', a: 'a', b: 'b', 4: 'x', 2: 't', '3': 'q' });
    ru = mk(); out.randUtil.choiceWeight = ru.choiceUsingWeight({ a: 0.1, b: 10, tt: 2, x: -1, c: 20, d: 0, e: 6 });
    ru = mk(); out.randUtil.choiceWeightNumeric = ru.choiceUsingWeight({ 10: 5, 2: 5, 1: 5, 3: 5 }); }

  // (8) seed-string serializers (str/int + tournament double-pipe quirk)
  { const serializeSeed = (...values) =>
      values.map((v) => (typeof v === 'string' ? `str(${v.length},${v})` : `int(${Math.floor(v)})`)).join('|');
    out.seeds = {
      mixed: serializeSeed('ConquerCity', 190, 3, 'attacker가나', 42),
      floored: serializeSeed(3.9, -2.1),
      tournamentNoGame: createTournamentSeedKey('base', { openYear: 200, openMonth: 1, stage: 0, phase: 0, matchIndex: 0, participantIndex: 0 }),
      tournamentWithGame: createTournamentSeedKey('base', { openYear: 200, openMonth: 1, stage: 0, phase: 0, matchIndex: 0, participantIndex: 0, gameIndex: 7, extraSeed: 'xs' }),
    };
    out.seedDrawMixed = hex(new LiteHashDRBG(out.seeds.mixed).nextBytes(16)); }

  writeFileSync(resolve(here, '../../common/src/test/resources/rng/rng-fixtures.json'), JSON.stringify(out, null, 2) + '\n');
  console.log('wrote rng-fixtures.json');
  ```
- [ ] Create `tools/rng-dump/README.md`: purpose (one-shot golden generator), prerequisite (`legacy/devsam-core2026` present **and** a one-shot `pnpm install` (pnpm 10) run from the `legacy/devsam-core2026` root so `@noble/hashes` resolves — the package has no node_modules/dist today), run command (`npx tsx /Users/apple/Desktop/개인프로젝트/opensamguk/tools/rng-dump/dumpRngFixtures.mjs`), and that the output JSON is committed and regenerated only when the TS kernel changes.
- [ ] Prerequisite (one-shot, manual host step — never CI): from `legacy/devsam-core2026` root run `pnpm install` (pnpm 10) so `@noble/hashes` resolves. Verified: the package has no node_modules/dist today.
- [ ] Run the dump: `npx tsx /Users/apple/Desktop/개인프로젝트/opensamguk/tools/rng-dump/dumpRngFixtures.mjs`. Expected: `wrote rng-fixtures.json`.
- [ ] Fallback if tsx fails: `pnpm --filter @sammo-ts/common build` then re-point imports at `dist/index.js`. (DOCUMENTED FALLBACK ONLY — prefer `npx tsx` above.)
- [ ] Verify: `grep -c '||game:7||extra:xs' common/src/test/resources/rng/rng-fixtures.json` → `1`. Confirm `helloWorldBlocks` first 8 hex chars == `24d9ccd6` (rng.test.ts testVector head — proves SHA + LE-append).
- [ ] Commit: `chore(tools): rng fixture dump script + committed TS golden` + Co-Authored-By trailer.

### Task 3 — Kotlin `LiteHashDRBG` core: block gen + `nextBytes`/`nextBits` (the byte engine)

**Files:**
- create `common/src/main/kotlin/opensamguk/common/rng/LiteHashDrbg.kt`
- create `common/src/test/kotlin/opensamguk/common/rng/LiteHashDrbgBytesTest.kt`
- edit `gradle/libs.versions.toml`, `common/build.gradle.kts` (add kotlinx-serialization-json + plugin — the shared build prerequisite; if a later-renumbered area already added it, verify only)

Steps:
- [ ] Create `LiteHashDrbg.kt` — faithful port of `genNextBlock`/`nextBytes`/`nextBits`. Constructor runs `genNextBlock()` FIRST then assigns `bufferIdx`. stateIdx appended LE-uint32 via `ByteBuffer.order(LITTLE_ENDIAN)`:
  ```kotlin
  package opensamguk.common.rng

  import java.nio.ByteBuffer
  import java.nio.ByteOrder

  const val BUFFER_BYTE_SIZE = 64
  const val MAX_RNG_SUPPORT_BIT = 53
  const val MAX_INT_L = 0x1f_ffff_ffff_ffffL        // 2^53-1
  const val MAX_INT_MORE1_L = 0x20_0000_0000_0000L  // 2^53
  const val TWO_POW_53_D = 9007199254740992.0       // exact 2^53 divisor

  open class LiteHashDrbg(seed: ByteArray, stateIdx: Long = 0, bufferIdx: Int = 0) {
      constructor(seed: String, stateIdx: Long = 0, bufferIdx: Int = 0) : this(bytesLikeToByteArray(seed), stateIdx, bufferIdx)

      private val hq: ByteArray
      private val hqIdxPos: Int
      protected var stateIdx: Long = stateIdx
      protected var buffer: ByteArray = ByteArray(0)
      protected var bufferIdx: Int = 0

      init {
          require(bufferIdx in 0 until BUFFER_BYTE_SIZE) { "bufferIdx $bufferIdx out of range" }
          require(stateIdx >= 0) { "stateIdx $stateIdx < 0" }
          val seedU8 = seed.copyOf()
          hqIdxPos = seedU8.size
          hq = ByteArray(seedU8.size + 4)
          seedU8.copyInto(hq, 0)
          genNextBlock()                 // FIRST: consumes stateIdx 0, advances to 1
          this.bufferIdx = bufferIdx
      }

      protected open fun genNextBlock() {
          ByteBuffer.wrap(hq, hqIdxPos, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(stateIdx.toInt())
          buffer = sha512(hq)
          bufferIdx = 0
          stateIdx += 1
      }

      fun getMaxInt(): Long = MAX_INT_L

      fun nextBytes(bytes: Int, baseBytes: Int? = null): ByteArray {
          val n = bytes
          if (n <= 0) throw IllegalArgumentException("$n <= 0")
          if (bufferIdx + n <= BUFFER_BYTE_SIZE) {
              if (baseBytes == null || n >= baseBytes) {
                  val result = buffer.copyOfRange(bufferIdx, bufferIdx + n)
                  bufferIdx += n
                  if (bufferIdx == BUFFER_BYTE_SIZE) genNextBlock()
                  return result
              }
              val result = ByteArray(maxOf(n, baseBytes))
              buffer.copyInto(result, 0, bufferIdx, bufferIdx + n)
              bufferIdx += n
              if (bufferIdx == BUFFER_BYTE_SIZE) genNextBlock()
              return result
          }
          val result = ByteArray(if (baseBytes != null) maxOf(n, baseBytes) else n)
          buffer.copyInto(result, 0, bufferIdx, BUFFER_BYTE_SIZE)
          var offset = BUFFER_BYTE_SIZE - bufferIdx
          var remain = n - offset
          while (remain > BUFFER_BYTE_SIZE) {
              genNextBlock(); buffer.copyInto(result, offset, 0, BUFFER_BYTE_SIZE); offset += BUFFER_BYTE_SIZE; remain -= BUFFER_BYTE_SIZE
          }
          genNextBlock()
          if (remain == 0) return result
          buffer.copyInto(result, offset, 0, remain)
          bufferIdx = remain
          return result
      }

      fun nextBits(bits: Int, baseBytes: Int? = null): ByteArray {
          if (bits <= 0) throw IllegalArgumentException("$bits <= 0")
          val bytes = (bits + 7) shr 3
          val headBits = bits and 0x7
          val result = nextBytes(bytes, baseBytes)
          if (headBits == 0) return result
          result[bytes - 1] = (result[bytes - 1].toInt() and (0xff ushr (8 - headBits))).toByte()
          return result
      }
  }
  ```
  NOTE: Kotlin reproduces the observable throw via its own `bits<=0` guard (TS reaches it through `bytes==0` in nextBytes); both throw IllegalArgumentException for all bits<=0, so parity holds. KEEP the Kotlin guard.
- [ ] Add the shared build prerequisite (kotlinx-serialization-json + kotlin-serialization plugin) to `gradle/libs.versions.toml` and `common/build.gradle.kts` per the File Structure note. **test-scope** dependency for this area's golden parsing. If already present (another area landed first), verify and skip.
- [ ] One-time resolve check (this is the first lander): confirm `kotlinxSerialization = 1.7.3` resolves from mavenCentral (brand-new dep, never resolved by any P0-A build).
- [ ] Create `LiteHashDrbgBytesTest.kt`. Load the committed fixture; assert the 5-block stream + byte/bit sequences + the throw cases:
  ```kotlin
  package opensamguk.common.rng

  import kotlinx.serialization.json.*
  import kotlin.test.Test
  import kotlin.test.assertEquals

  class LiteHashDrbgBytesTest {
      private val fx: JsonObject = Json.parseToJsonElement(
          this::class.java.getResource("/rng/rng-fixtures.json")!!.readText()).jsonObject
      private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

      @Test fun `helloWorld 5 blocks match SHA + LE-append`() {
          assertEquals(fx["helloWorldBlocks"]!!.jsonPrimitive.content, hex(LiteHashDrbg("HelloWorld").nextBytes(64 * 5)))
      }
      @Test fun `byte sequence with baseBytes pad`() {
          val rng = LiteHashDrbg("HelloWorld"); val s = fx["bytesSeq"]!!.jsonObject
          assertEquals(s["b10"]!!.jsonPrimitive.content, hex(rng.nextBytes(10)))
          assertEquals(s["b32"]!!.jsonPrimitive.content, hex(rng.nextBytes(32)))
          assertEquals(s["b1"]!!.jsonPrimitive.content, hex(rng.nextBytes(1)))
          assertEquals(s["b64"]!!.jsonPrimitive.content, hex(rng.nextBytes(64)))
          assertEquals(s["b5"]!!.jsonPrimitive.content, hex(rng.nextBytes(5)))
          assertEquals(s["b16pad18"]!!.jsonPrimitive.content, hex(rng.nextBytes(16, 18)))
      }
      @Test fun `bit sequence`() {
          val rng = LiteHashDrbg("HelloWorld"); val s = fx["bitsSeq"]!!.jsonObject
          for (bits in listOf(10, 4, 15, 32, 7, 99, 512, 1, 2, 3))
              assertEquals(s[bits.toString()]!!.jsonPrimitive.content, hex(rng.nextBits(bits)), "bits=$bits")
      }
      @Test fun `nextBytes(0) throws`() {
          try { LiteHashDrbg("HelloWorld").nextBytes(0); throw AssertionError("expected throw") } catch (e: IllegalArgumentException) {}
      }
      @Test fun `nextBits(0) throws`() {
          try { LiteHashDrbg("HelloWorld").nextBits(0); throw AssertionError("expected throw") } catch (e: IllegalArgumentException) {}
      }
  }
  ```
- [ ] Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :common:test --tests 'opensamguk.common.rng.LiteHashDrbgBytesTest'`. Expected: `BUILD SUCCESSFUL`, 5 tests. (Block test fails → stateIdx append endianness wrong; verify `LITTLE_ENDIAN`.)
- [ ] Commit: `feat(common/rng): LiteHashDRBG byte engine (LE block gen, nextBytes/nextBits)` + Co-Authored-By trailer.

### Task 4 — `_nextInt` / `nextInt` (rejection sampling) / `nextFloat1`

**Files:**
- edit `common/src/main/kotlin/opensamguk/common/rng/LiteHashDrbg.kt`
- create `common/src/test/kotlin/opensamguk/common/rng/LiteHashDrbgIntFloatTest.kt`

Steps:
- [ ] Add to `LiteHashDrbg`. `bitMaskLen` saturates `max` to all-ones then takes bit length (== TS `intBitMapMask` lookup for `max ≤ 2^53-1`). `_nextInt` reads LE uint64 (`baseBytes=8`):
  ```kotlin
  // inside LiteHashDrbg, alongside the byte engine:
  private fun _nextInt(bits: Int): Long {
      require(bits <= MAX_RNG_SUPPORT_BIT + 1) { "bits $bits > 54: LE u64 read would overflow signed Long" }  // OQ2 guard: any future >54-bit caller fails loudly
      return ByteBuffer.wrap(nextBits(bits, 8)).order(ByteOrder.LITTLE_ENDIAN).long
  }

  private fun bitMaskLen(max: Long): Int {
      var n = max
      n = n or (n ushr 1); n = n or (n ushr 2); n = n or (n ushr 4)
      n = n or (n ushr 8); n = n or (n ushr 16); n = n or (n ushr 32)
      return 64 - java.lang.Long.numberOfLeadingZeros(n)
  }

  fun nextInt(max: Long? = null): Long {
      if (max == null || max == MAX_INT_L) return _nextInt(MAX_RNG_SUPPORT_BIT)
      if (max > MAX_INT_L) throw IllegalArgumentException("Over max int")
      if (max == 0L) return 0
      if (max < 0) return -nextInt(-max)
      val bits = bitMaskLen(max)
      var n = _nextInt(bits)
      while (n > max) n = _nextInt(bits)   // INCLUSIVE: n==max accepted
      return n
  }

  fun nextFloat1(): Double {
      while (true) {
          val nInt = _nextInt(MAX_RNG_SUPPORT_BIT + 1)   // 54 bits
          if (nInt < MAX_INT_MORE1_L) return nInt.toDouble() / TWO_POW_53_D
          if (nInt == MAX_INT_MORE1_L) return 1.0
      }
  }
  ```
- [ ] Create `LiteHashDrbgIntFloatTest.kt`. Assert `intSeq`/`floatSeq`/`alignStress`; compare floats via raw bits; assert `Over max int` / inclusive-max / zero behaviors:
  ```kotlin
  package opensamguk.common.rng

  import kotlinx.serialization.json.*
  import kotlin.test.Test
  import kotlin.test.assertEquals
  import kotlin.test.assertTrue

  class LiteHashDrbgIntFloatTest {
      private val fx = Json.parseToJsonElement(this::class.java.getResource("/rng/rng-fixtures.json")!!.readText()).jsonObject
      private fun bitsHex(d: Double) = "%016x".format(java.lang.Double.doubleToRawLongBits(d))

      @Test fun `nextInt sequence on HelloWorld`() {
          val rng = LiteHashDrbg("HelloWorld"); val s = fx["intSeq"]!!.jsonObject
          assertEquals(s["i255"]!!.jsonPrimitive.long, rng.nextInt(0xffL))
          assertEquals(s["i65535"]!!.jsonPrimitive.long, rng.nextInt(((1 shl 16) - 1).toLong()))
          assertEquals(s["i4G"]!!.jsonPrimitive.long, rng.nextInt(0xffffffffL))
          assertEquals(s["iDefault"]!!.jsonPrimitive.long, rng.nextInt())
          assertEquals(s["i15"]!!.jsonPrimitive.long, rng.nextInt(0x0fL))
          assertEquals(s["i18"]!!.jsonPrimitive.long, rng.nextInt(0x12L))
          assertEquals(s["i99"]!!.jsonPrimitive.long, rng.nextInt(99L))
      }
      @Test fun `nextFloat1 18 draws bit-exact`() {
          val rng = LiteHashDrbg("HelloWorld"); val arr = fx["floatSeq"]!!.jsonArray
          for (i in 0 until 18) assertEquals(arr[i].jsonPrimitive.content, bitsHex(rng.nextFloat1()), "float[$i]")
      }
      @Test fun `nextInt over maxInt throws`() {
          try { LiteHashDrbg("x").nextInt(MAX_INT_L + 1); throw AssertionError("expected throw") }
          catch (e: IllegalArgumentException) { assertEquals("Over max int", e.message) }
      }
      @Test fun `nextInt zero is zero and inclusive max accepted`() {
          val rng = LiteHashDrbg("x")
          assertEquals(0L, rng.nextInt(0L))
          repeat(50) { assertTrue(rng.nextInt(1L) in 0L..1L) }
      }
      @Test fun `nextInt accepts a draw of exactly max (inclusive upper bound)`() {
          // deterministic golden row from a known block: TS oracle pins nextInt(0x99) -> 0x99
          val o = fx["intInclusiveMax"]!!.jsonObject
          val max = o["max"]!!.jsonPrimitive.long
          val draw = LiteHashDrbg("inclusiveMax").nextInt(max)
          assertEquals(o["draw"]!!.jsonPrimitive.long, draw)
          assertEquals(max, draw, "draw of EXACTLY max must be accepted (n==max), not rejected")
      }
      @Test fun `alignment stress sequence`() {
          val rng = LiteHashDrbg("alignStress")
          fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
          for (entry in fx["alignStress"]!!.jsonArray) {
              val p = entry.jsonArray
              when (p[0].jsonPrimitive.content) {
                  "bits7"  -> assertEquals(p[1].jsonPrimitive.content, hex(rng.nextBits(7)))
                  "bytes1" -> assertEquals(p[1].jsonPrimitive.content, hex(rng.nextBytes(1)))
                  "int99"  -> assertEquals(p[1].jsonPrimitive.long, rng.nextInt(99L))
              }
          }
      }
  }
  ```
- [ ] Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :common:test --tests 'opensamguk.common.rng.LiteHashDrbgIntFloatTest'`. Expected: 5 tests. (Float mismatch → check exact `9007199254740992.0` divisor + LE read.)
- [ ] Commit: `feat(common/rng): nextInt rejection sampling + nextFloat1 54-bit bit-exact` + Co-Authored-By trailer.

### Task 5 — `RandUtil` + JS object-key ordering helper

**Files:**
- create `common/src/main/kotlin/opensamguk/common/rng/RandUtil.kt`, `JsKeyOrder.kt`
- create `common/src/test/kotlin/opensamguk/common/rng/RandUtilTest.kt`, `JsKeyOrderTest.kt`

Steps:
- [ ] Create `JsKeyOrder.kt` — integer-like keys (canonical array indices `< 2^32-1`, no leading zeros) ascending-numeric FIRST, then string keys in insertion order:
  ```kotlin
  package opensamguk.common.rng

  fun jsKeyOrder(keys: Collection<String>): List<String> {
      val intKeys = ArrayList<String>(); val strKeys = ArrayList<String>()
      for (k in keys) if (isArrayIndex(k)) intKeys.add(k) else strKeys.add(k)
      intKeys.sortBy { it.toLong() }
      return intKeys + strKeys
  }
  private fun isArrayIndex(k: String): Boolean {
      if (k.isEmpty()) return false
      if (k == "0") return true
      if (k[0] == '0') return false
      if (!k.all { it in '0'..'9' }) return false
      val v = k.toLongOrNull() ?: return false
      return v in 0 until 4294967295L
  }
  ```
- [ ] Create `JsKeyOrderTest.kt` locking the rng.test.ts ordering example:
  ```kotlin
  package opensamguk.common.rng
  import kotlin.test.Test; import kotlin.test.assertEquals
  class JsKeyOrderTest {
      @Test fun `numeric keys ascending then string keys insertion order`() =
          assertEquals(listOf("2","3","4","c","a","b"), jsKeyOrder(listOf("c","a","b","4","2","3")))
      @Test fun `leading zero is a string key`() = assertEquals(listOf("1","01"), jsKeyOrder(listOf("01","1")))
  }
  ```
- [ ] Create `RandUtil.kt`. `nextInt(min,maxExcl)` passes `span-1` (off-by-one); `nextRangeInt` inclusive both ends; `nextBool(0.5)`→`nextBit()` BEFORE the `<=0` check; `shuffle` forward-sweep Fisher-Yates `destIdx = nextInt(cnt-srcIdx-1) + srcIdx`; `choice`/`choiceUsingWeight` over a map route through `jsKeyOrder`:
  ```kotlin
  package opensamguk.common.rng

  class RandUtil(private val rng: LiteHashDrbg) {
      fun nextFloat1(): Double = rng.nextFloat1()
      fun nextRange(min: Double, max: Double): Double = nextFloat1() * (max - min) + min
      fun nextRangeInt(minInclusive: Int, maxInclusive: Int): Int =
          rng.nextInt((maxInclusive - minInclusive).toLong()).toInt() + minInclusive
      fun nextInt(minInclusive: Int, maxExclusive: Int): Int {
          val span = maxExclusive - minInclusive
          if (span <= 1) return minInclusive
          return minInclusive + rng.nextInt((span - 1).toLong()).toInt()
      }
      fun nextBit(): Boolean = rng.nextBits(1)[0].toInt() != 0
      fun nextBool(prob: Double = 0.5): Boolean {
          if (prob >= 1) return true
          if (prob == 0.5) return nextBit()
          if (prob <= 0) return false
          return nextFloat1() < prob
      }
      fun <T> shuffle(srcArray: List<T>): List<T> {
          val cnt = srcArray.size
          if (cnt == 0) return emptyList()
          if (cnt.toLong() > rng.getMaxInt()) throw IllegalStateException("Invalid random int range")
          val result = ArrayList(srcArray)
          for (srcIdx in 0 until cnt) {
              val destIdx = rng.nextInt((cnt - srcIdx - 1).toLong()).toInt() + srcIdx
              if (srcIdx == destIdx) continue
              val tmp = result[srcIdx]; result[srcIdx] = result[destIdx]; result[destIdx] = tmp
          }
          return result
      }
      fun <T> choice(items: List<T>): T {
          if (items.isEmpty()) throw IllegalArgumentException("Empty items")
          return items[rng.nextInt((items.size - 1).toLong()).toInt()]
      }
      fun <T> choiceSet(items: Set<T>): T = choice(items.toList())
      fun <T> choiceMap(items: Map<String, T>): T = items.getValue(choice(jsKeyOrder(items.keys)))
      fun choiceUsingWeight(items: Map<String, Double>): String {
          if (items.isEmpty()) throw IllegalArgumentException("Empty items")
          val keys = jsKeyOrder(items.keys)
          var sum = 0.0; for (k in keys) { val v = items.getValue(k); if (v > 0) sum += v }
          var rd = nextFloat1() * sum
          for (k in keys) { val v = items.getValue(k); if (v <= 0) { if (rd <= 0) return k; continue }; if (rd <= v) return k; rd -= v }
          throw IllegalStateException("Unreacheable")
      }
      fun <T> choiceUsingWeightPair(items: List<Pair<T, Double>>): T {
          if (items.isEmpty()) throw IllegalArgumentException("Empty items")
          var sum = 0.0; for ((_, v) in items) if (v > 0) sum += v
          var rd = nextFloat1() * sum
          for ((item, v) in items) { if (v <= 0) { if (rd <= 0) return item; continue }; if (rd <= v) return item; rd -= v }
          throw IllegalStateException("Unreacheable")
      }
  }
  ```
- [ ] Create `RandUtilTest.kt` asserting every `randUtil.*` fixture branch (shuffle n∈{0,1,2,8,10,17}; choiceArray/Set/Map; choiceUsingWeight string-keyed AND numeric-keyed — the JS divergence guard):
  ```kotlin
  package opensamguk.common.rng

  import kotlinx.serialization.json.*
  import kotlin.test.Test
  import kotlin.test.assertEquals

  class RandUtilTest {
      private val ru = Json.parseToJsonElement(this::class.java.getResource("/rng/rng-fixtures.json")!!.readText())
          .jsonObject["randUtil"]!!.jsonObject
      private fun seed() = RandUtil(LiteHashDrbg("randUtilSeed"))

      @Test fun `nextInt exclusive span-1`() = assertEquals(
          ru["nextInt_5_10"]!!.jsonArray.map { it.jsonPrimitive.int }, seed().let { r -> List(8) { r.nextInt(5, 10) } })
      @Test fun `nextRangeInt inclusive`() = assertEquals(
          ru["nextRangeInt_0_9"]!!.jsonArray.map { it.jsonPrimitive.int }, seed().let { r -> List(8) { r.nextRangeInt(0, 9) } })
      @Test fun `nextBool half == nextBit`() = assertEquals(
          ru["nextBool_half"]!!.jsonArray.map { it.jsonPrimitive.boolean }, seed().let { r -> List(16) { r.nextBool(0.5) } })
      @Test fun `nextBool 0_3`() = assertEquals(
          ru["nextBool_0_3"]!!.jsonArray.map { it.jsonPrimitive.boolean }, seed().let { r -> List(16) { r.nextBool(0.3) } })
      @Test fun `nextBit`() = assertEquals(
          ru["nextBit"]!!.jsonArray.map { it.jsonPrimitive.boolean }, seed().let { r -> List(16) { r.nextBit() } })
      @Test fun `shuffle n in 0,1,2,8,10,17`() {
          val sh = ru["shuffle"]!!.jsonObject
          for (n in listOf(0, 1, 2, 8, 10, 17))
              assertEquals(sh[n.toString()]!!.jsonArray.map { it.jsonPrimitive.int }, seed().shuffle((0 until n).toList()), "n=$n")
      }
      @Test fun `choice array`() = assertEquals(
          ru["choiceArray"]!!.jsonArray.map { it.jsonPrimitive.int }, seed().let { r -> List(6) { r.choice(listOf(0,1,2,3,4,5)) } })
      @Test fun `choice set preserves order`() = assertEquals(ru["choiceSet"]!!.jsonPrimitive.int, seed().choiceSet(linkedSetOf(5,3,1,2,8,0)))
      @Test fun `choice record uses JS key order`() =
          assertEquals(ru["choiceRecord"]!!.jsonPrimitive.content,
              seed().choiceMap(linkedMapOf("c" to "c","a" to "a","b" to "b","4" to "x","2" to "t","3" to "q")))
      @Test fun `choiceUsingWeight string keys`() =
          assertEquals(ru["choiceWeight"]!!.jsonPrimitive.content,
              seed().choiceUsingWeight(linkedMapOf("a" to 0.1,"b" to 10.0,"tt" to 2.0,"x" to -1.0,"c" to 20.0,"d" to 0.0,"e" to 6.0)))
      @Test fun `choiceUsingWeight numeric keys iterate ascending (JS divergence from PHP)`() =
          assertEquals(ru["choiceWeightNumeric"]!!.jsonPrimitive.content,
              seed().choiceUsingWeight(linkedMapOf("10" to 5.0,"2" to 5.0,"1" to 5.0,"3" to 5.0)))
  }
  ```
- [ ] Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :common:test --tests 'opensamguk.common.rng.RandUtilTest' --tests 'opensamguk.common.rng.JsKeyOrderTest'`. Expected: 13 tests (11 + 2). Failure in `choiceUsingWeight numeric keys` = `jsKeyOrder` not applied (the key divergence guard).
- [ ] Commit: `feat(common/rng): RandUtil + JS-key-order helper (shuffle/choice/weighted)` + Co-Authored-By trailer.

### Task 6 — Seed serializers: `serializeSeed` / `buildTournamentSeedKey`

**Files:**
- create `common/src/main/kotlin/opensamguk/common/rng/SeedSerializer.kt`
- create `common/src/test/kotlin/opensamguk/common/rng/SeedSerializerTest.kt`

Steps:
- [ ] Create `SeedSerializer.kt`. `serializeSeed`: String → `str(len,val)` (`String.length` = UTF-16 code units), Number → `int(floor(n))`; join `|`. Tournament key reproduces the **double-pipe quirk** (each game/extra token carries its own leading `|`, empty filtered, joined `|`):
  ```kotlin
  package opensamguk.common.rng

  import kotlin.math.floor

  fun serializeSeed(vararg values: Any): String =
      values.joinToString("|") { v ->
          when (v) {
              is String -> "str(${v.length},$v)"
              is Int    -> "int($v)"
              is Long   -> "int($v)"
              is Double -> "int(${floor(v).toLong()})"
              is Float  -> "int(${floor(v.toDouble()).toLong()})"
              else      -> throw IllegalArgumentException("Unsupported seed value: $v")
          }
      }

  data class TournamentRngContext(
      val openYear: Int, val openMonth: Int, val stage: Int, val phase: Int,
      val matchIndex: Int, val participantIndex: Int,
      val gameIndex: Int? = null, val extraSeed: Any? = null,
  )

  fun buildTournamentSeedKey(baseSeed: String, ctx: TournamentRngContext): String {
      val gameIndex = if (ctx.gameIndex != null) "|game:${ctx.gameIndex}" else ""
      val extraSeed = if (ctx.extraSeed != null) "|extra:${ctx.extraSeed}" else ""
      return listOf(
          "Tournament", "open:${ctx.openYear}-${ctx.openMonth}", "stage:${ctx.stage}", "phase:${ctx.phase}",
          "match:${ctx.matchIndex}", "participant:${ctx.participantIndex}", gameIndex, extraSeed, "seed:$baseSeed",
      ).filter { it.isNotEmpty() }.joinToString("|")
  }
  ```
  > **Name relationship note:** Kotlin's public `buildTournamentSeedKey` corresponds to the TS EXPORTED wrapper `createTournamentSeedKey` (TournamentRNG.ts:~36), which delegates to a TS-private `buildTournamentSeedKey` (~:15); output is byte-identical. The dump script imports the exported name (`createTournamentSeedKey`); the Kotlin port keeps the single name `buildTournamentSeedKey`.
  > **`serializeSeed` provenance note:** `serializeSeed` is a reconstructed helper — there is no single exported TS source; it is modeled on two byte-identical inline copies. `str(len,...)` uses `String.length` = UTF-16 code units (correct). Seed inputs are constrained to small integers + strings.
- [ ] Create `SeedSerializerTest.kt` asserting `seeds` fixture + end-to-end `serializeSeed→build→draw` (`seedDrawMixed`) + explicit floor + double-pipe:
  ```kotlin
  package opensamguk.common.rng

  import kotlinx.serialization.json.*
  import kotlin.test.Test
  import kotlin.test.assertEquals

  class SeedSerializerTest {
      private val fx = Json.parseToJsonElement(this::class.java.getResource("/rng/rng-fixtures.json")!!.readText()).jsonObject
      private val seeds = fx["seeds"]!!.jsonObject
      private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

      @Test fun `mixed seed str+int with hangul length`() =
          assertEquals(seeds["mixed"]!!.jsonPrimitive.content, serializeSeed("ConquerCity", 190, 3, "attacker가나", 42))
      @Test fun `floor of doubles`() = assertEquals(seeds["floored"]!!.jsonPrimitive.content, serializeSeed(3.9, -2.1))
      @Test fun `tournament key no game`() =
          assertEquals(seeds["tournamentNoGame"]!!.jsonPrimitive.content, buildTournamentSeedKey("base", TournamentRngContext(200,1,0,0,0,0)))
      @Test fun `tournament key double-pipe quirk`() {
          val actual = buildTournamentSeedKey("base", TournamentRngContext(200,1,0,0,0,0, gameIndex = 7, extraSeed = "xs"))
          assertEquals(seeds["tournamentWithGame"]!!.jsonPrimitive.content, actual)
          assert(actual.contains("participant:0||game:7||extra:xs")) { "double-pipe quirk lost: $actual" }
      }
      @Test fun `serializeSeed feeds DRBG end-to-end`() =
          assertEquals(fx["seedDrawMixed"]!!.jsonPrimitive.content,
              hex(LiteHashDrbg(serializeSeed("ConquerCity", 190, 3, "attacker가나", 42)).nextBytes(16)))
  }
  ```
- [ ] Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :common:test --tests 'opensamguk.common.rng.SeedSerializerTest'`. Expected: 5 tests.
- [ ] Commit: `feat(common/rng): serializeSeed + tournament seed-key (double-pipe quirk)` + Co-Authored-By trailer.

### Task 7 — Full-kernel regression gate

**Files:**
- create `common/src/test/kotlin/opensamguk/common/rng/RngKernelParityGateTest.kt`

Steps:
- [ ] Create `RngKernelParityGateTest.kt` — wires the whole kernel (`serializeSeed → LiteHashDrbg → RandUtil`), draws a mixed sequence, asserts deterministic repeatability across two identical instances:
  ```kotlin
  package opensamguk.common.rng
  import kotlin.test.Test; import kotlin.test.assertEquals
  class RngKernelParityGateTest {
      @Test fun `same seed yields identical draw streams`() {
          fun draw(): List<Any> {
              val ru = RandUtil(LiteHashDrbg(serializeSeed("ConquerCity", 190, 3, 42L)))
              return listOf(ru.nextInt(0, 100), ru.nextRangeInt(1, 6), ru.nextBool(0.5), ru.shuffle((0 until 12).toList()),
                  "%016x".format(java.lang.Double.doubleToRawLongBits(ru.nextFloat1())),
                  ru.choiceUsingWeight(linkedMapOf("a" to 1.0, "b" to 2.0, "c" to 3.0)))
          }
          assertEquals(draw(), draw())
      }
  }
  ```
- [ ] Run the full common suite: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :common:test`. Expected: `BUILD SUCCESSFUL`. RNG test count: Sha512(2) + Bytes(5) + IntFloat(5) + RandUtil(11) + JsKeyOrder(2) + Seed(5) + Gate(1) = 31, plus any pre-existing (BuildInfo).
- [ ] Commit: `test(common/rng): full kernel determinism gate + suite green` + Co-Authored-By trailer.

**Area 1 golden-fixture strategy:** Single committed JSON fixture `common/src/test/resources/rng/rng-fixtures.json`, generated ONCE by `tools/rng-dump/dumpRngFixtures.mjs` importing the real TS kernel (`LiteHashDRBG`/`RandUtil`/`TournamentRNG`). It emits: (1) raw SHA-512 blocks for `'HelloWorld'` stateIdx 0..4 (== rng.test.ts testVector — proves SHA digest + LE-uint32 stateIdx append + slicing together; head must be `24d9ccd6`); (2) nextBytes incl baseBytes pad-not-draw (16,18); (3) nextBits `[10,4,15,32,7,99,512,1,2,3]`; (4) 18 nextFloat1 as RAW 64-bit IEEE-754 hex compared bit-exact vs `Double.doubleToRawLongBits`, never epsilon; (5) nextInt incl rejection-sampling (max=99) + inclusive-max; (6) 40× alternating bits7/bytes1/int99 alignment stress (>5 refills); (7) RandUtil draws covering span-1, inclusive nextRangeInt, nextBool(0.5==nextBit & 0.3), nextBit, shuffle n∈{0,1,2,8,10,17}, choice over array/Set/Record, choiceUsingWeight over BOTH string-keyed and numeric-keyed maps (the JS divergence from PHP); (8) serializeSeed with a 9-UTF16-codeunit hangul token, `Math.floor` of negatives, the tournament `||game:||extra:` double-pipe quirk, plus end-to-end serializeSeed→build→draw. Kotlin tests load it via kotlinx-serialization-json and diff byte/bit/draw-for-draw. PHP (`legacy/devsam-core` RNGTest.php + LiteHashDRBG.php/RandUtil.php) is an available-but-deferred second oracle; not required to land because rng.test.ts already encodes the Python/PHP-cross-checked SHA-512 stream vector.

---

# AREA 2 — JosaUtil + log-token model + golden

> Parity-kernel sub-plan. **Port target = TS `devsam-core2026`**, PHP is second oracle ONLY. The TS `JosaUtil.ts` is a *deliberately simplified* reimplementation of PHP's richer `JosaUtil.php`. **The P0-B gate grades against TS golden output — porting PHP would be "more correct" and FAIL the gate.** This is the single biggest mis-source risk in this area. No RNG dependency, so this area can land in parallel with Area 1, but Area 1 (RNG) must remain the leading gate for the kernel as a whole. All Kotlin in module `common` (`opensamguk.common.josa`, `opensamguk.common.log`).
>
> **Source divergences (trust the source, not the stale research summary):**
> - `formatter.ts` wraps status icons in color tags: PLAIN/YEAR_MONTH/YEAR/MONTH → `<C>●</>`; EVENT_PLAIN/EVENT_YEAR_MONTH → `<S>◆</>`; NOTICE/NOTICE_YEAR_MONTH → `<R>★</>`. RAWTEXT (0)/unknown → text unchanged.
> - `LogFormat` 0–8 = {RAWTEXT, PLAIN, YEAR_MONTH, YEAR, MONTH, EVENT_PLAIN, EVENT_YEAR_MONTH, NOTICE, NOTICE_YEAR_MONTH} (9 values).
> - `convertLog` `<D>` maps to `<font color=orangered>` — the SAME value as `<O>`. Preserve the duplicate.
> - `pushNationHistoryLog` `!nationId` early-return DROPS nationId 0 (truthiness divergence).
> - `finalizeLogEntry` default format when `entry.format` undefined = RAWTEXT (no prefix).

### Task 8 — `Josa` enum + canonical maps

**Files:**
- create `common/src/main/kotlin/opensamguk/common/josa/Josa.kt`
- create `common/src/test/kotlin/opensamguk/common/josa/JosaMapTest.kt`

Steps:
- [ ] Write `JosaTables` in `Josa.kt` — 8 josa pairs + `buildMapPostPosition` (3 keys/pair → canonical key):
  ```kotlin
  package opensamguk.common.josa

  internal object JosaTables {
      val DEFAULT_POSTPOSITION: Map<String, String> = linkedMapOf(
          "은" to "는", "이" to "가", "과" to "와", "이나" to "나",
          "을" to "를", "으로" to "로", "이라" to "라", "이랑" to "랑",
      )
      val MAP_POSTPOSITION: Map<String, String> = buildMap {
          for ((key, value) in DEFAULT_POSTPOSITION) { put(key, key); put(value, key); put("($key)$value", key) }
      }
  }
  ```
- [ ] Write `JosaMapTest.kt`:
  ```kotlin
  package opensamguk.common.josa
  import kotlin.test.Test; import kotlin.test.assertEquals
  class JosaMapTest {
      @Test fun `default postposition has 8 pairs`() {
          assertEquals(8, JosaTables.DEFAULT_POSTPOSITION.size)
          assertEquals("는", JosaTables.DEFAULT_POSTPOSITION["은"]); assertEquals("로", JosaTables.DEFAULT_POSTPOSITION["으로"])
      }
      @Test fun `map registers key value and parenthesized forms`() {
          assertEquals(24, JosaTables.MAP_POSTPOSITION.size)
          assertEquals("은", JosaTables.MAP_POSTPOSITION["은"]); assertEquals("은", JosaTables.MAP_POSTPOSITION["는"])
          assertEquals("은", JosaTables.MAP_POSTPOSITION["(은)는"]); assertEquals("으로", JosaTables.MAP_POSTPOSITION["(으로)로"])
      }
  }
  ```
- [ ] Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :common:test --tests 'opensamguk.common.josa.JosaMapTest'`. Expected: 2 tests.
- [ ] Commit: `feat(common): port JosaUtil canonical postposition maps` + Co-Authored-By trailer.

### Task 9 — `getLastChar` + jongsung detection helpers

**Files:**
- modify `common/src/main/kotlin/opensamguk/common/josa/Josa.kt`
- create `common/src/test/kotlin/opensamguk/common/josa/HasJongsungTest.kt`

Steps:
- [ ] Add `JosaDetect` — narrow hanja `一-廓` (U+4E00..U+5ED3), digit `{0,3,6}=has`, `{1,7,8}=has+rieul`, latin fallback = "not a vowel `{a,e,i,o,u,y}`". Codepoint iteration must match JS `Array.from` (code points, not UTF-16 chars):
  ```kotlin
  internal object JosaDetect {
      private const val KO_START_CODE = 0xAC00
      private const val KO_FINISH_CODE = 0xD7A3
      private const val JONGSUNG_RIEUL = 8
      private val REG_INVALID_CHAR = Regex("[^a-zA-Z0-9\\u3131-\\u314E\\uAC00-\\uD7A3\\u4E00-\\u5ED3\\s]+")
      private val REG_TARGET_CHAR = Regex("^[\\s\\S]*?(\\S*)\\s*$")

      fun getLastChar(text: String): String {
          var cleaned = REG_INVALID_CHAR.replace(text, " ")
          cleaned = REG_TARGET_CHAR.replace(cleaned) { m -> m.groupValues[1] }
          cleaned = cleaned.trim()
          if (cleaned.isEmpty()) return ""
          val cps = cleaned.codePoints().toArray()
          if (cps.isEmpty()) return ""
          return String(Character.toChars(cps[cps.size - 1]))
      }
      private data class DigitJong(val has: Boolean, val rieul: Boolean)
      private fun getDigitJongsung(digit: Int): DigitJong = when (digit) {
          0, 3, 6 -> DigitJong(true, false); 1, 7, 8 -> DigitJong(true, true); else -> DigitJong(false, false)
      }
      private val VOWELS = setOf('a', 'e', 'i', 'o', 'u', 'y')

      fun hasJongsung(text: String, isRo: Boolean): Boolean {
          val lastChar = getLastChar(text)
          if (lastChar.isEmpty()) return false
          val code = lastChar.codePointAt(0)
          if (code in KO_START_CODE..KO_FINISH_CODE) {
              val jongsung = (code - KO_START_CODE) % 28
              if (jongsung == 0) return false
              if (isRo && jongsung == JONGSUNG_RIEUL) return false
              return true
          }
          if (lastChar.length == 1 && lastChar[0] in 'ㄱ'..'ㅎ') { if (isRo && lastChar[0] == 'ㄹ') return false; return true }
          if (lastChar.length == 1 && lastChar[0] in '0'..'9') {
              val (has, rieul) = getDigitJongsung(lastChar[0].digitToInt()); if (isRo && rieul) return false; return has
          }
          val lower = lastChar.lowercase()
          return !(lower.length == 1 && lower[0] in VOWELS)
      }
  }
  ```
  NOTE (record, do NOT change): PHP carries ~3700-codepoint hanja jongsung tables; TS collapses to "in-range hanja → has jongsung" and "latin → not-a-vowel". Hanja > U+5ED3 is stripped by `REG_INVALID_CHAR`, so the preceding char decides. Replicate TS exactly. Flag at golden-freeze: any string whose josa depends on a hanja > U+5ED3 differs from PHP — accepted TS divergence.
  NOTE (LATIN-path divergence — record, do NOT change): the hanja table is not the only divergence. PHP `JosaUtil::check` applies regex special-char tables (`PRE_REG_SPECIAL_CHAR` / `_RO` / `_NORMAL_FIXED`) to romanized text, while TS 2026 collapses to a last-char vowel check `{a,e,i,o,u,y}`. Romanized golden cases (Kim, Park, etc.) should be added to `josa/pick.json`. This latin divergence is a P1+ watch-point for PHP DB byte-parity.
- [ ] Write `HasJongsungTest.kt` — one assertion per branch (the human spec; JSON golden in Task 14 is the byte oracle):
  ```kotlin
  package opensamguk.common.josa
  import kotlin.test.Test; import kotlin.test.assertEquals; import kotlin.test.assertFalse; import kotlin.test.assertTrue
  class HasJongsungTest {
      @Test fun `hangul with jongsung`() = assertTrue(JosaDetect.hasJongsung("한국", false))
      @Test fun `hangul without jongsung`() = assertFalse(JosaDetect.hasJongsung("사과", false))
      @Test fun `rieul jongsung is true normally`() = assertTrue(JosaDetect.hasJongsung("서울", false))
      @Test fun `rieul jongsung is false for ro`() = assertFalse(JosaDetect.hasJongsung("서울", true))
      @Test fun `compat jamo consonant`() = assertTrue(JosaDetect.hasJongsung("ㄱ", false))
      @Test fun `compat jamo rieul for ro`() = assertFalse(JosaDetect.hasJongsung("ㄹ", true))
      @Test fun `digit 0 has jongsung`() = assertTrue(JosaDetect.hasJongsung("100", false))
      @Test fun `digit 2 no jongsung`() = assertFalse(JosaDetect.hasJongsung("12", false))
      @Test fun `digit 1 rieul for ro`() = assertFalse(JosaDetect.hasJongsung("1", true))
      @Test fun `latin consonant has jongsung`() = assertTrue(JosaDetect.hasJongsung("Kim", false))
      @Test fun `latin vowel no jongsung`() = assertFalse(JosaDetect.hasJongsung("Lee", false))
      @Test fun `in-range hanja true`() = assertTrue(JosaDetect.hasJongsung("一", false))
      @Test fun `boundary hanja U5ED3 true`() = assertTrue(JosaDetect.hasJongsung("廓", false))
      @Test fun `out-of-range hanja stripped uses preceding`() = assertTrue(JosaDetect.hasJongsung("국廔", false))
      @Test fun `trailing punctuation and space stripped`() = assertTrue(JosaDetect.hasJongsung("한국!! ", false))
      @Test fun `empty string false`() = assertFalse(JosaDetect.hasJongsung("", false))
      @Test fun `getLastChar picks last non-space token`() = assertEquals("국", JosaDetect.getLastChar("대한 민국 "))
  }
  ```
- [ ] Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :common:test --tests 'opensamguk.common.josa.HasJongsungTest'`. Expected: 17 tests. Failures in `digit_1_rieul_for_ro` / `out-of-range hanja` = regex char-class or codepoint iteration diverged (highest silent-break vector).
- [ ] Commit: `feat(common): port JosaUtil jongsung detection (narrow hanja + digit + latin)` + Co-Authored-By trailer.

### Task 10 — `JosaUtil.pick` / `put` public API (+ the `'부터'` throw)

**Files:**
- modify `common/src/main/kotlin/opensamguk/common/josa/Josa.kt`
- create `common/src/test/kotlin/opensamguk/common/josa/JosaUtilTest.kt`

Steps:
- [ ] Add `JosaUtil` to `Josa.kt`. Public API is ONLY `pick`/`put`. Unknown josa with empty `woJongsung` throws `IllegalArgumentException("올바르지 않은 조사 지정")` — match byte-for-byte. (The `che_물자원조` `'부터'` case throws; encoded as a golden `{throws:true, message:...}` — see open questions.)
  ```kotlin
  object JosaUtil {
      fun pick(text: String?, wJongsung: String, woJongsung: String = ""): String {
          val normalizedText = text ?: ""
          var withJongsung = wJongsung
          var withoutJongsung = woJongsung
          if (withoutJongsung.isEmpty()) {
              val mapped = JosaTables.MAP_POSTPOSITION[wJongsung] ?: throw IllegalArgumentException("올바르지 않은 조사 지정")
              withJongsung = mapped
              withoutJongsung = JosaTables.DEFAULT_POSTPOSITION.getValue(mapped)
          }
          val isRo = withJongsung == "으로"
          return if (JosaDetect.hasJongsung(normalizedText, isRo)) withJongsung else withoutJongsung
      }
      fun put(text: String, wJongsung: String, woJongsung: String = ""): String = text + pick(text, wJongsung, woJongsung)
  }
  ```
- [ ] Write `JosaUtilTest.kt` (8 families, normalization equivalence, explicit-woJongsung bypass, null text, the throw):
  ```kotlin
  package opensamguk.common.josa
  import kotlin.test.Test; import kotlin.test.assertEquals; import kotlin.test.assertFailsWith
  class JosaUtilTest {
      @Test fun `eun-neun with jongsung`() = assertEquals("은", JosaUtil.pick("한국", "은"))
      @Test fun `eun-neun without jongsung`() = assertEquals("는", JosaUtil.pick("사과", "은"))
      @Test fun `i-ga with jongsung`() = assertEquals("이", JosaUtil.pick("한국", "이"))
      @Test fun `eul-reul without jongsung`() = assertEquals("를", JosaUtil.pick("사과", "을"))
      @Test fun `gwa-wa`() = assertEquals("과", JosaUtil.pick("한국", "과"))
      @Test fun `ro after rieul drops to ro`() = assertEquals("로", JosaUtil.pick("서울", "으로"))
      @Test fun `ro after non-rieul jongsung`() = assertEquals("으로", JosaUtil.pick("한국", "으로"))
      @Test fun `normalization pick by value equals pick by key`() = assertEquals(JosaUtil.pick("한국", "은"), JosaUtil.pick("한국", "는"))
      @Test fun `normalization parenthesized form`() = assertEquals(JosaUtil.pick("한국", "은"), JosaUtil.pick("한국", "(은)는"))
      @Test fun `explicit woJongsung bypasses map`() = assertEquals("AA", JosaUtil.pick("사과", "BB", "AA"))
      @Test fun `put concatenates`() = assertEquals("한국은", JosaUtil.put("한국", "은"))
      @Test fun `null text treated as empty`() = assertEquals("는", JosaUtil.pick(null, "은"))
      @Test fun `unknown josa with empty woJongsung throws exact message`() {
          val ex = assertFailsWith<IllegalArgumentException> { JosaUtil.pick("진", "부터") }
          assertEquals("올바르지 않은 조사 지정", ex.message)
      }
  }
  ```
- [ ] Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :common:test --tests 'opensamguk.common.josa.JosaUtilTest'`. Expected: 13 tests.
- [ ] Commit: `feat(common): port JosaUtil.pick/put public API incl 부터 throw` + Co-Authored-By trailer.

### Task 11 — `LogFormat` enum + `formatLogText` icon-prefix

**Files:**
- create `common/src/main/kotlin/opensamguk/common/log/LogFormat.kt`, `LogFormatter.kt`
- create `common/src/test/kotlin/opensamguk/common/log/LogFormatterTest.kt`

Steps:
- [ ] Write `LogFormat.kt` with explicit codes 0–8 (serialization contract):
  ```kotlin
  package opensamguk.common.log
  enum class LogFormat(val code: Int) {
      RAWTEXT(0), PLAIN(1), YEAR_MONTH(2), YEAR(3), MONTH(4),
      EVENT_PLAIN(5), EVENT_YEAR_MONTH(6), NOTICE(7), NOTICE_YEAR_MONTH(8);
      companion object { fun fromCode(code: Int): LogFormat = entries.first { it.code == code } }
  }
  ```
- [ ] Write `LogFormatter.kt` (color-tag-wrapped icons; glyphs `\uXXXX`-pinned via the test):
  ```kotlin
  package opensamguk.common.log
  private const val ICON_CIRCLE = "●"   // U+25CF
  private const val ICON_DIAMOND = "◆"  // U+25C6
  private const val ICON_STAR = "★"     // U+2605
  fun formatLogText(text: String, format: LogFormat, year: Int, month: Int): String = when (format) {
      LogFormat.RAWTEXT -> text
      LogFormat.PLAIN -> "<C>$ICON_CIRCLE</>$text"
      LogFormat.YEAR_MONTH -> "<C>$ICON_CIRCLE</>${year}년 ${month}월:$text"
      LogFormat.YEAR -> "<C>$ICON_CIRCLE</>${year}년:$text"
      LogFormat.MONTH -> "<C>$ICON_CIRCLE</>${month}월:$text"
      LogFormat.EVENT_PLAIN -> "<S>$ICON_DIAMOND</>$text"
      LogFormat.EVENT_YEAR_MONTH -> "<S>$ICON_DIAMOND</>${year}년 ${month}월:$text"
      LogFormat.NOTICE -> "<R>$ICON_STAR</>$text"
      LogFormat.NOTICE_YEAR_MONTH -> "<R>$ICON_STAR</>${year}년 ${month}월:$text"
  }
  ```
- [ ] Write `LogFormatterTest.kt` — glyph codepoint asserts + one case per format at year=190 month=3:
  ```kotlin
  package opensamguk.common.log
  import kotlin.test.Test; import kotlin.test.assertEquals
  class LogFormatterTest {
      @Test fun `glyph codepoints are exact`() {
          assertEquals(0x25CF, "●".codePointAt(0)); assertEquals(0x25C6, "◆".codePointAt(0)); assertEquals(0x2605, "★".codePointAt(0))
      }
      @Test fun `enum codes are 0 to 8`() {
          assertEquals(0, LogFormat.RAWTEXT.code); assertEquals(8, LogFormat.NOTICE_YEAR_MONTH.code); assertEquals(LogFormat.YEAR_MONTH, LogFormat.fromCode(2))
      }
      @Test fun rawtext() = assertEquals("X", formatLogText("X", LogFormat.RAWTEXT, 190, 3))
      @Test fun plain() = assertEquals("<C>●</>X", formatLogText("X", LogFormat.PLAIN, 190, 3))
      @Test fun yearMonth() = assertEquals("<C>●</>190년 3월:X", formatLogText("X", LogFormat.YEAR_MONTH, 190, 3))
      @Test fun year() = assertEquals("<C>●</>190년:X", formatLogText("X", LogFormat.YEAR, 190, 3))
      @Test fun month() = assertEquals("<C>●</>3월:X", formatLogText("X", LogFormat.MONTH, 190, 3))
      @Test fun eventPlain() = assertEquals("<S>◆</>X", formatLogText("X", LogFormat.EVENT_PLAIN, 190, 3))
      @Test fun eventYearMonth() = assertEquals("<S>◆</>190년 3월:X", formatLogText("X", LogFormat.EVENT_YEAR_MONTH, 190, 3))
      @Test fun notice() = assertEquals("<R>★</>X", formatLogText("X", LogFormat.NOTICE, 190, 3))
      @Test fun noticeYearMonth() = assertEquals("<R>★</>190년 3월:X", formatLogText("X", LogFormat.NOTICE_YEAR_MONTH, 190, 3))
  }
  ```
- [ ] Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :common:test --tests 'opensamguk.common.log.LogFormatterTest'`. Expected: 12 tests.
- [ ] Commit: `feat(common): port LogFormat enum + formatLogText icon prefixes` + Co-Authored-By trailer.

### Task 12 — `convertLog` color-tag → HTML renderer (14 ordered replacements)

**Files:**
- create `common/src/main/kotlin/opensamguk/common/log/ConvertLog.kt`
- create `common/src/test/kotlin/opensamguk/common/log/ConvertLogTest.kt`

Steps:
- [ ] Write `ConvertLog.kt` — 14 sequential `replace` in FIXED order; `<D>`→orangered (== `<O>`); `type<=0` strips; empty→`""`. `<Y1>` MUST precede `<Y>`:
  ```kotlin
  package opensamguk.common.log
  fun convertLog(value: String, type: Int = 1): String {
      if (value.isEmpty()) return ""
      var r = value
      if (type > 0) {
          r = r.replace("<1>", "<font size=1>"); r = r.replace("<Y1>", "<font size=1 color=yellow>")
          r = r.replace("<R>", "<font color=red>"); r = r.replace("<B>", "<font color=blue>")
          r = r.replace("<G>", "<font color=green>"); r = r.replace("<M>", "<font color=magenta>")
          r = r.replace("<C>", "<font color=cyan>"); r = r.replace("<L>", "<font color=limegreen>")
          r = r.replace("<S>", "<font color=skyblue>"); r = r.replace("<O>", "<font color=orangered>")
          r = r.replace("<D>", "<font color=orangered>"); r = r.replace("<Y>", "<font color=yellow>")
          r = r.replace("<W>", "<font color=white>"); r = r.replace("</>", "</font>")
          return r
      }
      for (tag in listOf("<1>","<Y1>","<R>","<B>","<G>","<M>","<C>","<L>","<S>","<O>","<D>","<Y>","<W>","</>")) r = r.replace(tag, "")
      return r
  }
  ```
- [ ] Write `ConvertLogTest.kt` — every tag, nested, `<b>`/`<br>` passthrough, type 1/0/-1, `<Y1>` ordering trap, empty, all-tags roundtrip:
  ```kotlin
  package opensamguk.common.log
  import kotlin.test.Test; import kotlin.test.assertEquals
  class ConvertLogTest {
      @Test fun empty() = assertEquals("", convertLog("", 1))
      @Test fun cyan() = assertEquals("<font color=cyan>x</font>", convertLog("<C>x</>", 1))
      @Test fun size1() = assertEquals("<font size=1>x</font>", convertLog("<1>x</>", 1))
      @Test fun y1BeforeY() = assertEquals("<font size=1 color=yellow>x</font>", convertLog("<Y1>x</>", 1))
      @Test fun yellow() = assertEquals("<font color=yellow>x</font>", convertLog("<Y>x</>", 1))
      @Test fun dEqualsOrangered() = assertEquals("<font color=orangered>x</font>", convertLog("<D>x</>", 1))
      @Test fun oEqualsOrangered() = assertEquals("<font color=orangered>x</font>", convertLog("<O>x</>", 1))
      @Test fun nestedDBold() = assertEquals("<font color=orangered><b>x</b></font>", convertLog("<D><b>x</b></>", 1))
      @Test fun bAndBrPassthrough() = assertEquals("a<b>b</b><br>c", convertLog("a<b>b</b><br>c", 1))
      @Test fun stripType0() = assertEquals("x", convertLog("<C>x</>", 0))
      @Test fun stripTypeNegative() = assertEquals("190년 3월:x", convertLog("<C>190년 3월:</><C>x</>", -1))
      @Test fun allTagsRoundtrip() {
          val raw = "<1><Y1><R><B><G><M><C><L><S><O><D><Y><W>z</>"
          val expected = "<font size=1><font size=1 color=yellow><font color=red><font color=blue>" +
              "<font color=green><font color=magenta><font color=cyan><font color=limegreen>" +
              "<font color=skyblue><font color=orangered><font color=orangered><font color=yellow>" +
              "<font color=white>z</font>"
          assertEquals(expected, convertLog(raw, 1))
      }
  }
  ```
- [ ] Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :common:test --tests 'opensamguk.common.log.ConvertLogTest'`. Expected: all pass.
- [ ] Commit: `feat(common): port convertLog color-tag to HTML renderer` + Co-Authored-By trailer.

### Task 13 — Log-entry types + ActionLogger / UserLogger / finalizeLogEntry (per-branch truthiness)

**Files:**
- create `common/src/main/kotlin/opensamguk/common/log/LogEntry.kt`, `ActionLogger.kt`, `UserLogger.kt`, `FinalizeLogEntry.kt`
- create `common/src/test/kotlin/opensamguk/common/log/ActionLoggerTest.kt`, `FinalizeLogEntryTest.kt`

Steps:
- [ ] Write `LogEntry.kt` (optional fields nullable; `undefined ≡ null`):
  ```kotlin
  package opensamguk.common.log
  enum class LogScope { SYSTEM, NATION, GENERAL, USER }
  enum class LogCategory { HISTORY, SUMMARY, ACTION, BATTLE_BRIEF, BATTLE_DETAIL, USER }
  data class LogEntryDraft(
      val scope: LogScope, val category: LogCategory, val text: String,
      val generalId: Int? = null, val nationId: Int? = null, val userId: Int? = null,
      val subType: String? = null, val meta: Map<String, Any?>? = null, val format: LogFormat? = null,
  )
  data class LogEntryRecord(
      val scope: LogScope, val category: LogCategory, val text: String, val year: Int, val month: Int,
      val generalId: Int? = null, val nationId: Int? = null, val userId: Int? = null,
      val subType: String? = null, val meta: Map<String, Any?>? = null, val createdAt: java.time.Instant? = null,
  )
  data class LogContext(val year: Int, val month: Int, val at: java.time.Instant? = null)
  ```
- [ ] Write `ActionLogger.kt`. `flush()`/`rollback()` are IDENTICAL drains. Per-branch truthiness ported NOT normalized: `pushNationHistoryLog` `!nationId` drops 0; general-id attach `!== undefined` keeps 0; empty-string skip:
  ```kotlin
  package opensamguk.common.log
  class ActionLogger(private val generalId: Int? = null, private val nationId: Int? = null) {
      private val logs = mutableListOf<LogEntryDraft>()
      fun flush(): List<LogEntryDraft> { val i = logs.toList(); logs.clear(); return i }
      fun rollback(): List<LogEntryDraft> = flush()
      fun pushGeneralHistoryLog(text: String, format: LogFormat = LogFormat.YEAR_MONTH) = pushOne(text) { generalDraft(LogCategory.HISTORY, it, format) }
      fun pushGeneralHistoryLog(text: List<String>, format: LogFormat = LogFormat.YEAR_MONTH) = pushMany(text) { generalDraft(LogCategory.HISTORY, it, format) }
      fun pushGeneralActionLog(text: String, format: LogFormat = LogFormat.MONTH) = pushOne(text) { generalDraft(LogCategory.ACTION, it, format) }
      fun pushGeneralActionLog(text: List<String>, format: LogFormat = LogFormat.MONTH) = pushMany(text) { generalDraft(LogCategory.ACTION, it, format) }
      fun pushGeneralBattleResultLog(text: String, format: LogFormat = LogFormat.RAWTEXT) = pushOne(text) { generalDraft(LogCategory.BATTLE_BRIEF, it, format) }
      fun pushGeneralBattleDetailLog(text: String, format: LogFormat = LogFormat.PLAIN) = pushOne(text) { generalDraft(LogCategory.BATTLE_DETAIL, it, format) }
      fun pushNationHistoryLog(text: String, format: LogFormat = LogFormat.YEAR_MONTH, nationId: Int? = this.nationId) {
          if (nationId == null || nationId == 0) return
          pushOne(text) { LogEntryDraft(LogScope.NATION, LogCategory.HISTORY, it, nationId = nationId, format = format) }
      }
      fun pushGlobalHistoryLog(text: String, format: LogFormat = LogFormat.YEAR_MONTH) = pushOne(text) { LogEntryDraft(LogScope.SYSTEM, LogCategory.HISTORY, it, format = format) }
      fun pushGlobalActionLog(text: String, format: LogFormat = LogFormat.MONTH) = pushOne(text) { LogEntryDraft(LogScope.SYSTEM, LogCategory.SUMMARY, it, format = format) }
      private fun generalDraft(category: LogCategory, msg: String, format: LogFormat) =
          LogEntryDraft(LogScope.GENERAL, category, msg, generalId = generalId, format = format)
      private fun pushOne(text: String, builder: (String) -> LogEntryDraft) { if (text.isEmpty()) return; logs.add(builder(text)) }
      private fun pushMany(text: List<String>, builder: (String) -> LogEntryDraft) { for (item in text) if (item.isNotEmpty()) logs.add(builder(item)) }
  }
  ```
- [ ] Write `UserLogger.kt`:
  ```kotlin
  package opensamguk.common.log
  class UserLogger(private val userId: Int) {
      private val logs = mutableListOf<LogEntryDraft>()
      fun flush(): List<LogEntryDraft> { val i = logs.toList(); logs.clear(); return i }
      fun rollback(): List<LogEntryDraft> = flush()
      fun push(text: String, subType: String) { if (text.isEmpty()) return; logs.add(LogEntryDraft(LogScope.USER, LogCategory.USER, text, userId = userId, subType = subType)) }
      fun push(text: List<String>, subType: String) { for (item in text) if (item.isNotEmpty()) logs.add(LogEntryDraft(LogScope.USER, LogCategory.USER, item, userId = userId, subType = subType)) }
  }
  ```
- [ ] Write `FinalizeLogEntry.kt` — `shouldDropEntry` `!xId` drops 0; default format RAWTEXT; bakes `formatLogText` (tags stay raw, convertLog runs at display layer):
  ```kotlin
  package opensamguk.common.log
  internal fun shouldDropEntry(entry: LogEntryDraft): Boolean {
      if (entry.scope == LogScope.GENERAL && (entry.generalId == null || entry.generalId == 0)) return true
      if (entry.scope == LogScope.NATION && (entry.nationId == null || entry.nationId == 0)) return true
      if (entry.scope == LogScope.USER && (entry.userId == null || entry.userId == 0)) return true
      return false
  }
  fun finalizeLogEntry(entry: LogEntryDraft, context: LogContext): LogEntryRecord? {
      if (shouldDropEntry(entry)) return null
      val format = entry.format ?: LogFormat.RAWTEXT
      val text = formatLogText(entry.text, format, context.year, context.month)
      return LogEntryRecord(entry.scope, entry.category, text, context.year, context.month,
          entry.generalId, entry.nationId, entry.userId, entry.subType, entry.meta, context.at)
  }
  ```
  NOTE the preserved asymmetry: a GENERAL log with generalId=0 is ATTACHED into the draft (`!== undefined` keep) but DROPPED at finalize (`!xId`). Keep both.
- [ ] Write `ActionLoggerTest.kt` (flush==rollback drain, array/scalar/empty/id-0/null, nation drop):
  ```kotlin
  package opensamguk.common.log
  import kotlin.test.Test; import kotlin.test.assertEquals; import kotlin.test.assertTrue
  class ActionLoggerTest {
      @Test fun `flush drains and clears`() { val l = ActionLogger(5); l.pushGeneralActionLog("a"); l.pushGeneralActionLog("b"); assertEquals(2, l.flush().size); assertTrue(l.flush().isEmpty()) }
      @Test fun `rollback identical to flush`() { val l = ActionLogger(5); l.pushGeneralActionLog("a"); assertEquals(1, l.rollback().size); assertTrue(l.flush().isEmpty()) }
      @Test fun `array push skips empty strings`() { val l = ActionLogger(5); l.pushGeneralActionLog(listOf("a","","b")); assertEquals(2, l.flush().size) }
      @Test fun `scalar empty string skipped`() { val l = ActionLogger(5); l.pushGeneralActionLog(""); assertTrue(l.flush().isEmpty()) }
      @Test fun `general draft attaches id zero`() { val l = ActionLogger(0); l.pushGeneralActionLog("x"); assertEquals(0, l.flush()[0].generalId) }
      @Test fun `general draft null id when not provided`() { val l = ActionLogger(); l.pushGeneralActionLog("x"); assertEquals(null, l.flush()[0].generalId) }
      @Test fun `nation history drops nationId zero`() { val l = ActionLogger(nationId = 0); l.pushNationHistoryLog("x"); assertTrue(l.flush().isEmpty()) }
      @Test fun `nation history keeps nonzero nationId`() { val l = ActionLogger(nationId = 3); l.pushNationHistoryLog("x"); assertEquals(3, l.flush()[0].nationId) }
      @Test fun `global action log has system scope and summary category`() {
          val l = ActionLogger(); l.pushGlobalActionLog("x"); val d = l.flush()[0]
          assertEquals(LogScope.SYSTEM, d.scope); assertEquals(LogCategory.SUMMARY, d.category); assertEquals(LogFormat.MONTH, d.format)
      }
  }
  ```
- [ ] Write `FinalizeLogEntryTest.kt`:
  ```kotlin
  package opensamguk.common.log
  import kotlin.test.Test; import kotlin.test.assertEquals; import kotlin.test.assertNull
  class FinalizeLogEntryTest {
      private val ctx = LogContext(190, 3)
      @Test fun `general scope with id zero is dropped`() = assertNull(finalizeLogEntry(LogEntryDraft(LogScope.GENERAL, LogCategory.ACTION, "x", generalId = 0), ctx))
      @Test fun `general scope with null id is dropped`() = assertNull(finalizeLogEntry(LogEntryDraft(LogScope.GENERAL, LogCategory.ACTION, "x", generalId = null), ctx))
      @Test fun `system scope never dropped`() = assertEquals("x", finalizeLogEntry(LogEntryDraft(LogScope.SYSTEM, LogCategory.SUMMARY, "x"), ctx)!!.text)
      @Test fun `bakes year_month prefix`() {
          val r = finalizeLogEntry(LogEntryDraft(LogScope.GENERAL, LogCategory.ACTION, "투자", generalId = 7, format = LogFormat.YEAR_MONTH), ctx)!!
          assertEquals("<C>●</>190년 3월:투자", r.text); assertEquals(7, r.generalId); assertEquals(190, r.year)
      }
      @Test fun `default format is rawtext when format null`() = assertEquals("투자", finalizeLogEntry(LogEntryDraft(LogScope.GENERAL, LogCategory.ACTION, "투자", generalId = 7, format = null), ctx)!!.text)
  }
  ```
- [ ] Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :common:test --tests 'opensamguk.common.log.*'`. Expected: all pass.
- [ ] Commit: `feat(common): port log-entry types + ActionLogger/UserLogger/finalizeLogEntry` + Co-Authored-By trailer.

### Task 14 — TS-generated josa/log golden fixtures + golden-diff tests

**Files:**
- create `tools/golden-dump/josa-log-golden.mjs`
- create committed fixtures `common/src/test/resources/golden/josa/{pick,hasJongsung}.json`, `.../log/{convertLog,formatLogText,render-e2e,actionLogger}.json`
- create `common/src/test/kotlin/opensamguk/common/golden/JosaLogGoldenTest.kt`

Steps:
- [ ] Confirm the shared build prerequisite (kotlinx-serialization-json test-scope) is present (added in Task 3). If josa-log lands before RNG, add it here instead.
- [ ] Write `tools/golden-dump/josa-log-golden.mjs` importing the actual TS modules (`JosaUtil.ts`, `formatter.ts`, `logFormatter.ts`); emit `{input, args, expected}` triples. Cover every `hasJongsung` branch via `pick`; all 8 josa families; normalization equivalence; explicit-woJongsung bypass; the `'부터'` THROW as `{throws:true, message}`; every convertLog tag incl nested `<D><b>..</b></>`, `<Y1>` ordering, `<b>`/`<br>` passthrough, type 1/0/-1; one case per LogFormat 0–8; render-e2e `convertLog(formatLogText(raw,fmt,190,3))`. Skeleton:
  ```js
  import { JosaUtil } from '../../legacy/devsam-core2026/packages/common/src/util/JosaUtil.ts';
  import { formatLogText } from '../../legacy/devsam-core2026/packages/logic/src/logging/formatter.ts';
  import { convertLog } from '../../legacy/devsam-core2026/app/game-api/src/battleSim/logFormatter.ts';
  import { writeFileSync, mkdirSync } from 'node:fs';
  const base = 'common/src/test/resources/golden';
  mkdirSync(`${base}/josa`, { recursive: true }); mkdirSync(`${base}/log`, { recursive: true });
  const pickCases = [
    {text:'한국',josa:'은'},{text:'사과',josa:'은'},{text:'서울',josa:'으로'},{text:'한국',josa:'으로'},
    {text:'ㄱ',josa:'은'},{text:'ㄹ',josa:'으로'},{text:'100',josa:'은'},{text:'12',josa:'은'},{text:'1',josa:'으로'},
    {text:'Kim',josa:'은'},{text:'Lee',josa:'은'},{text:'一',josa:'은'},{text:'廓',josa:'은'},{text:'국廔',josa:'은'},
    {text:'한국!! ',josa:'은'},{text:'',josa:'은'},{text:null,josa:'은'},{text:'한국',josa:'는'},{text:'한국',josa:'(은)는'},
  ];
  const pick = pickCases.map(c => ({ ...c, expected: JosaUtil.pick(c.text, c.josa) }));
  let buteo; try { JosaUtil.pick('진','부터'); buteo = {throws:false}; } catch (e) { buteo = {throws:true, message:e.message}; }
  pick.push({ text:'진', josa:'부터', ...buteo });
  writeFileSync(`${base}/josa/pick.json`, JSON.stringify(pick, null, 2));
  const convCases = ['<C>x</>','<1>x</>','<Y1>x</>','<Y>x</>','<D>x</>','<O>x</>','<D><b>x</b></>','a<b>b</b><br>c','<C>x</>'];
  const conv = convCases.map(v => ({ value:v, type:1, expected:convertLog(v,1) }));
  conv.push({ value:'<C>x</>', type:0, expected:convertLog('<C>x</>',0) });
  conv.push({ value:'<C>x</>', type:-1, expected:convertLog('<C>x</>',-1) });
  writeFileSync(`${base}/log/convertLog.json`, JSON.stringify(conv, null, 2));
  const fmt = []; for (let f=0; f<=8; f++) fmt.push({ format:f, expected:formatLogText('본문',f,190,3) });
  writeFileSync(`${base}/log/formatLogText.json`, JSON.stringify(fmt, null, 2));
  const e2e = ['투자', `${JosaUtil.put('한국','은')} 성공`, '<C>점수</> 상승'].map(raw => ({ raw, format:2, expected:convertLog(formatLogText(raw,2,190,3)) }));
  writeFileSync(`${base}/log/render-e2e.json`, JSON.stringify(e2e, null, 2));
  console.log('golden written');
  ```
  (Also emit `hasJongsung.json` raw branch table and `actionLogger.json` scripted push→flush→finalize sequences if convenient; the pick/convertLog/formatLogText/render-e2e set is the minimum byte oracle. Per-branch truthiness divergences are asserted directly in ActionLoggerTest/FinalizeLogEntryTest, not via JSON.)
- [ ] Generate once (manual host step; not in CI loop): `cd /Users/apple/Desktop/개인프로젝트/opensamguk && npx tsx tools/golden-dump/josa-log-golden.mjs`. Expected: `golden written`; inspect `pick.json` — the `'부터'` entry MUST be `{"throws":true,"message":"올바르지 않은 조사 지정"}`.
- [ ] Write `JosaLogGoldenTest.kt` parsing each JSON and diffing against the port:
  ```kotlin
  package opensamguk.common.golden
  import kotlinx.serialization.json.*
  import opensamguk.common.josa.JosaUtil
  import opensamguk.common.log.LogFormat
  import opensamguk.common.log.convertLog
  import opensamguk.common.log.formatLogText
  import kotlin.test.Test; import kotlin.test.assertEquals; import kotlin.test.assertFailsWith
  class JosaLogGoldenTest {
      private fun load(p: String): JsonArray = Json.parseToJsonElement(this::class.java.getResource(p)!!.readText()).jsonArray
      @Test fun `josa pick golden`() {
          for (c in load("/golden/josa/pick.json")) {
              val o = c.jsonObject; val text = o["text"]?.jsonPrimitive?.contentOrNull; val josa = o["josa"]!!.jsonPrimitive.content
              if (o["throws"]?.jsonPrimitive?.booleanOrNull == true) {
                  val ex = assertFailsWith<IllegalArgumentException> { JosaUtil.pick(text, josa) }
                  assertEquals(o["message"]!!.jsonPrimitive.content, ex.message)
              } else assertEquals(o["expected"]!!.jsonPrimitive.content, JosaUtil.pick(text, josa), "pick($text,$josa)")
          }
      }
      @Test fun `convertLog golden`() {
          for (c in load("/golden/log/convertLog.json")) { val o = c.jsonObject
              assertEquals(o["expected"]!!.jsonPrimitive.content, convertLog(o["value"]!!.jsonPrimitive.content, o["type"]!!.jsonPrimitive.int)) }
      }
      @Test fun `formatLogText golden`() {
          for (c in load("/golden/log/formatLogText.json")) { val o = c.jsonObject
              assertEquals(o["expected"]!!.jsonPrimitive.content, formatLogText("본문", LogFormat.fromCode(o["format"]!!.jsonPrimitive.int), 190, 3)) }
      }
      @Test fun `render e2e golden`() {
          for (c in load("/golden/log/render-e2e.json")) { val o = c.jsonObject
              assertEquals(o["expected"]!!.jsonPrimitive.content, convertLog(formatLogText(o["raw"]!!.jsonPrimitive.content, LogFormat.fromCode(o["format"]!!.jsonPrimitive.int), 190, 3))) }
      }
  }
  ```
- [ ] Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :common:test --tests 'opensamguk.common.golden.JosaLogGoldenTest'`. Expected: 4 golden tests. Any mismatch = porting bug (regex char-class, codepoint iteration, or replacement order).
- [ ] Run whole module: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :common:test`. Expected: all josa + log + golden green.
- [ ] Commit: `test(common): TS-generated josa/log golden fixtures + byte-diff tests` + Co-Authored-By trailer.

**Area 2 golden-fixture strategy:** Oracle = TS `devsam-core2026` (the parity target), NOT PHP. `tools/golden-dump/josa-log-golden.mjs` imports the actual TS modules and emits `{input,args,expected}` JSON triples ONCE into `common/src/test/resources/golden/`; fixtures committed, script never run in CI. `josa/pick.json` covers every `hasJongsung` branch via `pick` (Hangul ±jongsung, ㄹ+isRo, compat jamo, digits 0-9, latin vowel/consonant, in-range hanja U+4E00/U+5ED3, out-of-range U+5ED4, trailing punct/space, empty/null), all 8 josa families, normalization equivalence `pick(x,'은')==pick(x,'는')==pick(x,'(은)는')`, explicit-woJongsung bypass, and the `'부터'` THROW as `{throws:true,message:"올바르지 않은 조사 지정"}`. `log/convertLog.json` covers every tag incl nested `<D><b>..</b></>`, `<Y1>`-before-`<Y>` ordering trap, `<b>`/`<br>` passthrough, type 1/0/-1, the `<D>==<O>==orangered` duplicate. `log/formatLogText.json` = one case per LogFormat 0-8 at year=190 month=3 with source-accurate `<C>●</>`/`<S>◆</>`/`<R>★</>` prefixes (= the RAW baked markup `finalizeLogEntry` stores, pre-convertLog). `log/render-e2e.json` = `convertLog(formatLogText(...))` (the FINAL display html, post-convertLog) — both stages graded per the RAW-vs-FINAL open question. Kotlin `JosaLogGoldenTest` parses via kotlinx-serialization-json and asserts byte-identical. Per-branch JS-truthiness divergences (`!nationId` drops 0, generalId `!== undefined` keeps 0, `shouldDropEntry` `!xId` drops 0) are structural and asserted directly in ActionLoggerTest/FinalizeLogEntryTest.

---

# AREA 3 — Constants config (GameConst / GameUnitConst / CityConst / EffectiveGameConst)

> Module `:common`, package `opensamguk.common`. **Constants values port PHP** (the three `legacy/devsam-core/hwe/sammo/*Base.php` static tables are grand truth); the scenario-override structure mirrors TS. Do not normalize either toward the other. The `:common` kotlinx.serialization plugin/dep (shared build prerequisite) is assumed present from Area 1; verify, do not re-add. The 0-9 Nation level extension (project memory) is NOT a constants concern — `GameConst.maxLevel` stays 255 (PHP grand truth); do not "fix" it.

### Task 15 — GameConst immutable boot config (structure + representative slice + event-table DATA)

**Files:**
- create `common/src/main/kotlin/opensamguk/common/constants/GameConst.kt`
- create `common/src/test/kotlin/opensamguk/common/constants/GameConstTest.kt`

> Source: `legacy/devsam-core/hwe/sammo/GameConstBase.php` (lines 5-536). Transcribe scalar/array balance fields, enumerated string sets, item catalog, command menus, name pools, and the declarative event tables (`defaultInitialEvents` 441-446, `defaultEvents` 447-531). Event tables are tuple-arrays-of-strings = DATA, not control flow.

Steps:
- [ ] Write `GameConstTest.kt` first (red) — representative slice + DATA nature + maxLevel==255 guard:
  ```kotlin
  package opensamguk.common.constants
  import kotlin.test.Test; import kotlin.test.assertEquals; import kotlin.test.assertTrue
  class GameConstTest {
      @Test fun scalarSlice() {
          assertEquals("che", GameConst.mapName); assertEquals("che", GameConst.unitSet)
          assertEquals(50, GameConst.develrate); assertEquals(30, GameConst.upgradeLimit)
          assertEquals(0.35, GameConst.sabotageDefaultProb, 0.0)
          assertEquals(255, GameConst.maxLevel)   // PHP grand truth; do NOT change to 7/9
          assertEquals(30, GameConst.maxTurn); assertEquals(12, GameConst.maxTechLevel); assertEquals(80, GameConst.retirementYear)
      }
      @Test fun enumeratedSets() {
          assertEquals(13, GameConst.availableNationType.size); assertEquals("che_도적", GameConst.availableNationType.first())
          assertEquals("che_중립", GameConst.neutralNationType)
          assertEquals(8, GameConst.availableSpecialDomestic.size); assertEquals(20, GameConst.availableSpecialWar.size)
          assertEquals(10, GameConst.availablePersonality.size)
      }
      @Test fun itemCatalogShape() {
          assertEquals(2, GameConst.allItems.getValue("horse").getValue("che_명마_15_적토마"))
          assertEquals(0, GameConst.allItems.getValue("weapon").getValue("che_무기_01_단도"))
      }
      @Test fun commandMenusAreData() {
          assertEquals(listOf("che_농지개간","che_상업투자","che_기술연구","che_수비강화","che_성벽보수","che_치안강화","che_정착장려","che_주민선정"),
              GameConst.availableGeneralCommand.getValue("내정"))
          assertTrue(GameConst.availableChiefCommand.getValue("외교").contains("che_물자원조"))
      }
      @Test fun eventTablesAreDeclarativeData() {
          val first = GameConst.defaultInitialEvents.first(); assertEquals(2, first.size)
          assertTrue(GameConst.defaultEvents.any { it.firstOrNull() == "pre_month" })
          assertTrue(GameConst.defaultEvents.any { it.firstOrNull() == "united" })
      }
  }
  ```
- [ ] Implement `GameConst.kt` as a Kotlin `object` of immutable `val`s, faithfully transcribed from the PHP file (scalars 7-256, enumerated sets, `allItems` 259-312, command menus 316-415, name pools 421-435, event tables 441-531). Event tables = `List<List<Any>>` carrying raw string tokens; replace `ActionLogger::EVENT_YEAR_MONTH` enum refs with the int literal the LogFormat enum serializes to (`// LogFormat.EVENT_YEAR_MONTH = 6`). Skeleton:
  ```kotlin
  package opensamguk.common.constants
  object GameConst {
      const val title = "삼국지 모의전투 PHP HiDCHe"
      const val mapName = "che"; const val unitSet = "che"
      const val develrate = 50; const val upgradeLimit = 30; const val dexLimit = 1_000_000
      const val sabotageDefaultProb = 0.35
      const val maxLevel = 255            // PHP grand truth (Nation 0-9 extension is separate)
      const val maxTurn = 30; const val maxTechLevel = 12; const val retirementYear = 80
      // ... transcribe all scalar fields ...
      val availableNationType = listOf("che_도적","che_명가","che_음양가","che_종횡가","che_불가","che_오두미도","che_태평도","che_도가","che_묵가","che_덕가","che_병가","che_유가","che_법가")
      const val neutralNationType = "che_중립"
      val availableSpecialDomestic = listOf("che_경작","che_상재","che_발명","che_축성","che_수비","che_통찰","che_인덕","che_귀모")
      val availableSpecialWar = listOf(/* 20 entries */)
      val availablePersonality = listOf("che_안전","che_유지","che_재간","che_출세","che_할거","che_정복","che_패권","che_의협","che_대의","che_왕좌")
      val allItems: Map<String, Map<String, Int>> = mapOf("horse" to linkedMapOf(/*...*/), "weapon" to linkedMapOf(/*...*/), "book" to linkedMapOf(/*...*/), "item" to linkedMapOf(/*...*/))
      val availableGeneralCommand: Map<String, List<String>> = linkedMapOf(/*...*/)
      val availableChiefCommand: Map<String, List<String>> = linkedMapOf(/*...*/)
      val randGenFirstName = listOf(/*...*/); val randGenLastName = listOf(/*...*/)
      private const val LOGFORMAT_EVENT_YEAR_MONTH = 6
      val defaultInitialEvents: List<List<Any>> = listOf(listOf(true, listOf("NoticeToHistoryLog", "<S>2년간 거병 및 건국이 가능합니다.</>", LOGFORMAT_EVENT_YEAR_MONTH)))
      val defaultEvents: List<List<Any>> = listOf(/* faithful transcription of 447-531 */)
  }
  ```
- [ ] Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :common:test --tests 'opensamguk.common.constants.GameConstTest'`. Expected: 5 tests.
- [ ] Commit: `feat(common): GameConst immutable boot config (faithful PHP port)` + Co-Authored-By trailer.

### Task 16 — GameUnitConst catalog (18-element tuple port + golden verification)

**Files:**
- create `common/src/main/kotlin/opensamguk/common/constants/UnitConstraint.kt`, `GameUnitDetail.kt`, `GameUnitConst.kt`
- create `common/src/test/kotlin/opensamguk/common/constants/GameUnitConstTest.kt`
- create `common/src/test/resources/golden/game_unit_const.golden.json`

> Source: `legacy/devsam-core/hwe/sammo/GameUnitConstBase.php` getBuildData() lines 41-355 (40 rows, 18-element positional tuples) + _generate() lines 412-484 (appends each constraint's getInfo() to info[]). The 18-field tuple is the highest constants port hazard; the test round-trips id→all-fields for all 40.

Steps:
- [ ] Implement `UnitConstraint.kt` (sealed; **PHP `hwe/sammo/GameUnitConstraint/*.php` is the SOLE oracle for these strings** — TS `devsam-core2026` emits boolean logic only, no human strings. All 10 PHP types are present below, transcribed verbatim from the PHP `getInfo()` bodies; the method is named `getInfo()` to match PHP. `cityNameText`/`regionNameText` = PHP `implode(', ', ...)` = comma-space join; `cityLevelText` = `getCityLevelList()[reqCityLevel]` label):
  ```kotlin
  package opensamguk.common.constants
  sealed class UnitConstraint { abstract fun getInfo(): String
      object Impossible : UnitConstraint() { override fun getInfo() = "불가능" }
      data class ReqTech(val reqTech: Int) : UnitConstraint() { override fun getInfo() = "기술력 ${reqTech} 이상 필요" }
      data class ReqCities(val reqCities: List<String>) : UnitConstraint() {
          private val cityNameText get() = reqCities.joinToString(", ")
          override fun getInfo() = "${cityNameText} 소유시 가능"
      }
      data class ReqRegions(val reqRegions: List<String>) : UnitConstraint() {
          private val regionNameText get() = reqRegions.joinToString(", ")
          override fun getInfo() = "${regionNameText} 지역 소유시 가능"
      }
      data class ReqMinRelYear(val reqMinRelYear: Int) : UnitConstraint() { override fun getInfo() = "${reqMinRelYear}년 경과 후 사용 가능" }
      object ReqChief : UnitConstraint() { override fun getInfo() = "군주 및 수뇌부만 가능" }
      object ReqNotChief : UnitConstraint() { override fun getInfo() = "군주 및 수뇌부는 불가" }
      data class ReqCitiesWithCityLevel(val reqCityLevel: Int, val reqCities: List<String>) : UnitConstraint() {
          private val cityNameText get() = reqCities.joinToString(", ")
          private val cityLevelText get() = getCityLevelList()[reqCityLevel]
          override fun getInfo() = "${cityNameText} ${cityLevelText}성 소유시 가능"
      }
      data class ReqHighLevelCities(val reqCityLevel: Int, val reqCityCount: Int) : UnitConstraint() {
          private val cityLevelText get() = getCityLevelList()[reqCityLevel]
          override fun getInfo() = "${cityLevelText}성 ${reqCityCount}개 이상 소유시 가능"
      }
      // ReqNationAux.getInfo() ports the per-key switch from ReqNationAux.php:62-119 verbatim,
      // then the generic ==/!=/default fallback. nationAuxKey carries the PHP NationAuxKey->value string.
      data class ReqNationAux(val reqNationAuxKey: String, val cmp: String, val value: Double) : UnitConstraint() {
          override fun getInfo(): String {
              // Enum별 특수한 경우 (NationAuxKey switch)
              when (reqNationAuxKey) {
                  "can_대검병사용"   -> if (cmp == "==" && value == 1.0) return "대검병 연구 시 가능"
                  "can_극병사용"     -> if (cmp == "==" && value == 1.0) return "극병 연구 시 가능"
                  "can_화시병사용"   -> if (cmp == "==" && value == 1.0) return "화시병 연구 시 가능"
                  "can_원융노병사용" -> if (cmp == "==" && value == 1.0) return "원융노병 연구 시 가능"
                  "can_산저병사용"   -> if (cmp == "==" && value == 1.0) return "산저병 연구 시 가능"
                  "can_상병사용"     -> if (cmp == "==" && value == 1.0) return "상병 연구 시 가능"
                  "can_음귀병사용"   -> if (cmp == "==" && value == 1.0) return "음귀병 연구 시 가능"
                  "can_무희사용"     -> if (cmp == "==" && value == 1.0) return "무희 연구 시 가능"
                  "can_화륜차사용"   -> if (cmp == "==" && value == 1.0) return "화륜차 연구 시 가능"
                  "did_특성초토화"   -> if (cmp == ">=" && value == 1.0) return "특성 초토화 시 가능"
              }
              // 범용 fallback
              return when (cmp) {
                  "==" -> when {
                      value == 0.0 -> "${reqNationAuxKey} 없을 때"
                      value == 1.0 -> "${reqNationAuxKey} 있을 때"
                      else         -> "${reqNationAuxKey} = ${value} 일 때"
                  }
                  "!=" -> when {
                      value == 0.0 -> "${reqNationAuxKey} 없을 때"
                      value == 1.0 -> "${reqNationAuxKey} 있을 때"
                      else         -> "${reqNationAuxKey} != ${value} 일 때"
                  }
                  else -> "${reqNationAuxKey} ${cmp} ${value} 일 때"
              }
          }
      }
  }
  ```
  > **Freeze note (PHP is the sole oracle):** the strings above are transcribed verbatim from `hwe/sammo/GameUnitConstraint/*.php` `getInfo()` bodies — PHP is the SOLE oracle here because TS `devsam-core2026` emits boolean constraint logic only (no human-facing strings). Capture all 10 in `game_unit_const.golden.json` and diff byte-for-byte. The PHP `getInfo()` method name is preserved (NOT `info()`).
- [ ] Implement `GameUnitDetail.kt` (18 fields; `attackCoef`/`defenceCoef` = `Map<Int, Double>` mixing armType ints 0-6 AND specific unit-ids like 1106/1100; lookup resolves specific-id before armType):
  ```kotlin
  package opensamguk.common.constants
  data class GameUnitDetail(
      val id: Int, val armType: Int, val name: String,
      val attack: Int, val defence: Int, val speed: Int, val avoid: Int, val magicCoef: Double,
      val cost: Int, val rice: Int, val reqConstraints: List<UnitConstraint>,
      val attackCoef: Map<Int, Double>, val defenceCoef: Map<Int, Double>, val info: List<String>,
      val initSkillTrigger: List<String>?, val phaseSkillTrigger: List<String>?, val iActionList: List<String>?,
  )
  ```
- [ ] Implement `GameUnitConst.kt` — `object` with the 40-row `buildData` (transcribe 41-355) + `_generate()` appending each `constraint.getInfo()` onto `info`; expose `all()`/`byId`/`byName`/`byType` + arm-type constants (`T_CASTLE=0..T_MISC=6`, `CREWTYPE_CASTLE=1000`, `DEFAULT_CREWTYPE=1100`).
- [ ] Author `game_unit_const.golden.json` — 40 fully-resolved entries transcribed independently from the PHP rows (a transcription typo in either fails the diff).
- [ ] Write `GameUnitConstTest.kt`:
  ```kotlin
  // load golden, assert GameUnitConst.all() matches field-by-field for all 40 ids;
  // spot-check specific-id coef (e.g. unit 1500 attackCoef[1106] vs attackCoef[T_CASTLE]); byId(999) throws; info[] for a constrained unit ends with appended constraint getInfo() strings.
  ```
- [ ] Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :common:test --tests 'opensamguk.common.constants.GameUnitConstTest'`. Expected: 40-row diff clean.
- [ ] Commit: `feat(common): GameUnitConst catalog + golden round-trip` + Co-Authored-By trailer.

### Task 17 — CityConst catalog (13-element tuple, ×100, bidirectional path + golden)

**Files:**
- create `common/src/main/kotlin/opensamguk/common/constants/CityInitialDetail.kt`, `CityConst.kt`
- create `common/src/test/kotlin/opensamguk/common/constants/CityConstTest.kt`
- create `common/src/test/resources/golden/city_const.golden.json`

> Source: `legacy/devsam-core/hwe/sammo/CityConstBase.php` — $regionMap (14-34), $levelMap (25-34), $initCity 94 rows (58-154), _generate() ×100 + label→int + bidirectional path (156-240), $buildInit per-level (247-312). Per project memory: lv=4 "이" = 이민족-only; han county-seats use lv=5 "소" — do not collapse them.

Steps:
- [ ] Implement `CityInitialDetail.kt` (level/region resolved to ints via maps; stats ×100; `path: Map<Int,String>` connectedId→name).
- [ ] Implement `CityConst.kt` — `object` with `regionMap`/`levelMap` (two maps each direction), 94-row raw `initCity` (transcribe 58-154), `_generate()` doing ×100 + label→int + name→id path resolution; expose `all()`/`byId`/`byName`; replicate PHP `byRegion` **last-wins quirk** with a code comment; port `$buildInit`/`$buildInitCommon` per-level (trust=50/trade=100) used at DB seed time (P0 infra) but defined here.
- [ ] Author `city_const.golden.json` — 94 resolved entries transcribed independently.
- [ ] Write `CityConstTest.kt`:
  ```kotlin
  // allCitiesMatchGolden (94, field-by-field); statsScaledByHundred (e.g. 업 agri 125→12500);
  // levelAndRegionResolvedToInts (업: level 8 특, region 1 하북; 강: level 4 이; 진양: level 5 소);
  // bidirectionalConnectivity (every connected city lists this city back — mirrors CityConstBase::test()).
  ```
- [ ] Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :common:test --tests 'opensamguk.common.constants.CityConstTest'`. Expected: 94-row diff clean; ×100 holds; lv4/lv5 distinct; connectivity bidirectional.
- [ ] Commit: `feat(common): CityConst catalog + ×100/bidirectional golden` + Co-Authored-By trailer.

### Task 18 — EffectiveGameConst: scenario override layer + runtime-derived values

**Files:**
- create `common/src/main/kotlin/opensamguk/common/constants/ScenarioConstOverride.kt`, `EffectiveGameConst.kt`
- create `common/src/test/kotlin/opensamguk/common/constants/EffectiveGameConstTest.kt`

> Spec: `effectiveConst = baseGameConst.mergeWith(scenario.config.const)`. `scenario.config.const` is `Record<string,unknown>` (parseScenario.ts:178, worldLoader zScenarioConfig.const = z.record). `killturn`/`develcost` are runtime-derived (CONFIRMED source `ResetHelper.php:264-267`: killturn = 4800/turnterm, npcmode==1 → intdiv(killturn,3); develcost = (year-startyear+10)*2), NOT static — model as derived functions. **develCost must be RECOMPUTED per-turn (not cached once) in P1** — PHP recomputes it each turn at `func_gamerule.php:219`. PHP-only port (no TS golden).

Steps:
- [ ] Write `EffectiveGameConstTest.kt` first (red): override replaces scalar; unspecified falls through to base; derived killturn (40 / 13-floored for npcmode 1); derived develcost ((year-startYear+10)*2).
- [ ] Implement `ScenarioConstOverride.kt` (typed wrapper over the untyped map):
  ```kotlin
  package opensamguk.common.constants
  @JvmInline value class ScenarioConstOverride(val values: Map<String, Any?>) {
      fun int(key: String): Int? = (values[key] as? Number)?.toInt()
      fun double(key: String): Double? = (values[key] as? Number)?.toDouble()
      fun str(key: String): String? = values[key] as? String
      operator fun contains(key: String) = key in values
  }
  ```
- [ ] Implement `EffectiveGameConst.kt` — base + override merge with a generic accessor (key set is untyped/open per the open question) + derived helpers:
  ```kotlin
  package opensamguk.common.constants
  class EffectiveGameConst private constructor(private val override: ScenarioConstOverride) {
      fun getInt(key: String): Int = override.int(key) ?: baseInt(key)
      fun getDouble(key: String): Double = override.double(key) ?: baseDouble(key)
      private fun baseInt(key: String): Int = when (key) {
          "maxTurn" -> GameConst.maxTurn; "develrate" -> GameConst.develrate; "upgradeLimit" -> GameConst.upgradeLimit
          "maxLevel" -> GameConst.maxLevel; else -> error("unknown GameConst int key: $key")
      }
      private fun baseDouble(key: String): Double = when (key) {
          "sabotageDefaultProb" -> GameConst.sabotageDefaultProb; else -> error("unknown GameConst double key: $key")
      }
      companion object {
          fun of(override: Map<String, Any?>): EffectiveGameConst = EffectiveGameConst(ScenarioConstOverride(override))
          fun killturn(turnterm: Int, npcmode: Int): Int { val base = 4800 / turnterm; return if (npcmode == 1) base / 3 else base }
          fun develcost(year: Int, startYear: Int): Int = (year - startYear + 10) * 2
      }
  }
  ```
- [ ] Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :common:test --tests 'opensamguk.common.constants.EffectiveGameConstTest'`. Expected: 4 tests.
- [ ] Commit: `feat(common): EffectiveGameConst scenario-override + derived killturn/develcost` + Co-Authored-By trailer.

**Area 3 golden-fixture strategy:** Oracle = PHP built catalogs (the design's §10 / research §2.4 grand truth). Transcribe the source tables faithfully from the three PHP `*Base.php` files, then verify with structural + spot-value tests + two committed JSON fixtures generated independently from the same PHP line ranges (a transcription typo in either source or golden fails the diff). `game_unit_const.golden.json`: 40 fully-resolved `GameUnitDetail` rows (id, armType, stats, costs, attackCoef/defenceCoef maps mixing armType ints + specific unit-ids, resolved info[] with constraint `.getInfo()` appended, skill triggers). The test iterates all 40 ids field-by-field — the 18-element positional tuple is the highest port hazard. `city_const.golden.json`: 94 resolved `CityInitialDetail` rows after `_generate()` (×100-scaled stats, int-resolved level/region via levelMap/regionMap, bidirectionally-resolved path). The test asserts ×100 scaling, int label resolution, lv4(이)/lv5(소) distinction (project memory), and bidirectional connectivity (mirrors `CityConstBase::test()`). The transcription tasks point AT the exact PHP source line ranges; a real PHP `GetConst` dump from the devsam capture env cross-validates at P0-B exit (open question). `GameConst` and `EffectiveGameConst` use spot-value/structural tests rather than full golden JSON (event tables are DATA, derived values are formula-checked).

---

# AREA 4 — InMemoryTurnWorld + flush stub + Redis Streams consumer + SSE relay + flush-exclusion

> CQRS-loop SKELETON. No game rules. Faithful structural transcription of `inMemoryWorld.ts` / `databaseHooks.ts` / `redisCommandStream.ts` / `turnDaemon/types.ts`. Proves dirty-set semantics, flush write-ORDER, flush-EXCLUSION contract, Redis Streams consumer, SSE relay. AUTHORITATIVE OVERRIDES (design §0.1): #3 daemon never uses JPA EntityManager for writes; §7 inheritance/storage/hall/dynasty live OUTSIDE the flush boundary. Depends on Area 1 (RNG green) for the kernel-as-a-whole gate, and on the wire sealed types below (Tasks 19-21) before the world/flush/redis tasks. The `:common` wire types use kotlinx.serialization **implementation-scope** (the shared build prerequisite, extended to impl scope here).

### Task 19 — Wire enums + status/checkpoint/budget/result value types in `:common`

**Files:**
- create `common/src/main/kotlin/opensamguk/common/wire/TurnDaemonWire.kt`
- create `common/src/main/kotlin/opensamguk/common/wire/WireJson.kt`
- create `common/src/main/kotlin/opensamguk/common/wire/StreamKeys.kt`
- create `common/src/test/kotlin/opensamguk/common/wire/TurnDaemonWireTypesTest.kt`, `StreamKeysTest.kt`
- edit `common/build.gradle.kts` (promote kotlinx-serialization-json to `implementation` scope; the shared prerequisite was added at Area 1 — extend, don't duplicate)

Steps:
- [ ] Ensure `common/build.gradle.kts` has `alias(libs.plugins.kotlin.serialization)` and `implementation(libs.kotlinx.serialization.json)` (Area 1 added it test-scope for golden parsing; wire needs it at runtime). Keep `testImplementation(kotlin("test"))`.
- [ ] Write `StreamKeys.kt` (mirrors `redisCommandStream.ts` + `realtime/keys.ts`; command/event builders do NOT trim, realtime trims + defaults `unknown`):
  ```kotlin
  package opensamguk.common.wire
  data class TurnDaemonStreamKeys(val commandStream: String, val eventStream: String) {
      companion object {
          fun of(profileName: String) = TurnDaemonStreamKeys("sammo:$profileName:turn-daemon:commands", "sammo:$profileName:turn-daemon:events")
      }
  }
  fun gameEventChannel(profileName: String): String {
      val trimmed = profileName.trim(); val normalized = if (trimmed.isNotEmpty()) trimmed else "unknown"
      return "sammo:$normalized:realtime:events"
  }
  ```
- [ ] Write `StreamKeysTest.kt` locking the exact strings (`sammo:default:turn-daemon:commands`, `che:scenario_2` interpolation, realtime trim + `unknown` default).
- [ ] Write `TurnDaemonWire.kt` — `TurnDaemonState`/`RunReason` enums (lowercase `@SerialName`), `TurnRunBudget`/`TurnCheckpoint`/`TurnRunResult`/`TurnDaemonStatus` serializable data classes (faithful to `turnDaemon/types.ts:1-41`).
- [ ] Write `WireJson.kt` — single shared codec: `Json { classDiscriminator = "type"; ignoreUnknownKeys = true; encodeDefaults = false; explicitNulls = false }`.
- [ ] Write `TurnDaemonWireTypesTest.kt`: `TurnDaemonState.RUNNING` serializes to `"running"`; `TurnRunBudget` round-trips.
- [ ] Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :common:test --tests 'opensamguk.common.wire.TurnDaemonWireTypesTest' --tests 'opensamguk.common.wire.StreamKeysTest'`. Expected: 5 tests (2 + 3).
- [ ] Commit: `feat(common): wire enums + status/budget/checkpoint value types + stream keys` + Co-Authored-By trailer.

### Task 20 — TurnDaemonCommand sealed union (~28 variants, type discriminator)

**Files:**
- create `common/src/main/kotlin/opensamguk/common/wire/TurnDaemonCommand.kt`
- create `common/src/main/kotlin/opensamguk/common/wire/WireEnvelope.kt`
- create `common/src/test/kotlin/opensamguk/common/wire/TurnDaemonCommandWireTest.kt`
- create `common/src/test/resources/golden/wire/wire_commands_valid.json`, `wire_commands_malformed.json`

Steps:
- [ ] Author `wire_commands_valid.json` — one envelope-`command` object per variant from `turnDaemon/types.ts:43-186` (~27-28 entries keyed by `type`): run, pause, resume, shutdown, getStatus, troopJoin, troopExit, dieOnPrestart, buildNationCandidate, instantRetreat, vacation, setMySetting, dropItem, auctionFinalize, changePermission, kick, appoint, tournamentRefund, tournamentBettingPayout, tournamentReward, voteReward, setNationMeta, adjustGeneralResources, adjustGeneralMeta, tournamentMatchResult, patchGeneral, auctionBid. Exact shapes (see field lists in source).
- [ ] Author `wire_commands_malformed.json` — entries that MUST reject: unknown type, troopJoin missing troopId, run bad reason, appoint with string generalId (mirrors Zod `safeParse` failures).
- [ ] Write `TurnDaemonCommand.kt` as a kotlinx `@Serializable sealed class` with `@SerialName` per variant, plus nested `@Serializable` helpers (`MySettings`, `AmountEntry`, `VoteUnique`, `ResourceAdj`, `MetaAdj`, `GeneralPatch`, `PatchStats`, `MatchResult` enum). `requestId` optional on most; full union transcribed for forward-compat (no handlers wired). Also add the command/event envelope helpers in `WireEnvelope.kt`:
  ```kotlin
  // WireEnvelope.kt
  package opensamguk.common.wire
  import kotlinx.serialization.Serializable
  @Serializable data class TurnDaemonCommandEnvelope(val requestId: String, val sentAt: String, val command: TurnDaemonCommand)
  @Serializable data class TurnDaemonEventEnvelope(val requestId: String? = null, val sentAt: String, val event: TurnDaemonEvent)
  const val WIRE_PAYLOAD_FIELD: String = "payload"
  fun encodeCommandPayload(env: TurnDaemonCommandEnvelope): String = WireJson.encodeToString(TurnDaemonCommandEnvelope.serializer(), env)
  fun decodeCommandEnvelope(payload: String): TurnDaemonCommandEnvelope = WireJson.decodeFromString(TurnDaemonCommandEnvelope.serializer(), payload)
  ```
- [ ] Write `TurnDaemonCommandWireTest.kt`: load valid corpus, deserialize each, re-serialize, assert round-trip JSON-tree equality; assert every malformed entry throws; assert all ~28 `type` keys covered. Lock the corpus-size assertion to the actual authored count.
- [ ] Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :common:test --tests 'opensamguk.common.wire.TurnDaemonCommandWireTest'`. Expected: 3 tests; all variants covered, malformed rejected.
- [ ] Commit: `feat(common): TurnDaemonCommand sealed union + wire corpus` + Co-Authored-By trailer.

### Task 21 — TurnDaemonCommandResult dual discriminator (type + ok) + Event + Realtime unions

**Files:**
- create `common/src/main/kotlin/opensamguk/common/wire/TurnDaemonCommandResult.kt`, `TurnDaemonEvent.kt`, `RealtimeEvent.kt`
- create `common/src/test/kotlin/opensamguk/common/wire/TurnDaemonCommandResultWireTest.kt`, `RealtimeEventWireTest.kt`
- create `common/src/test/resources/golden/wire/wire_results.json`, `wire_realtime.json`

Steps:
- [ ] **Why custom:** `turnDaemon/types.ts:188-371` results share `type` but split on `ok:true|false` (e.g. `auctionFinalize` Ok = `{type,ok,auctionId}`, Fail adds `reason`). A single `@JsonClassDiscriminator("type")` cannot split them → implement a `JsonContentPolymorphicSerializer` keyed on `(type, ok)`. The boolean-ok group (`dieOnPrestart/buildNationCandidate/instantRetreat/vacation/setMySetting/dropItem/changePermission/kick/appoint`) collapses to one `GeneralBool`/`GenericOk`/`GenericFail` class with nullable `reason`.
- [ ] Author `wire_results.json` — one Ok and one Fail per result `type` (lock the dual discriminator): auctionFinalize, troopJoin, troopExit, dieOnPrestart, tournamentRefund, tournamentBettingPayout, tournamentReward, setNationMeta, voteReward, auctionBid, adjustGeneralResources, adjustGeneralMeta, tournamentMatchResult, patchGeneral, + the boolean-ok group.
- [ ] Implement `TurnDaemonCommandResult.kt` with the custom `(type, ok)` selector. Concrete classes re-emit `type` and `ok` so encode round-trips (the round-trip test catches a missing discriminator).
- [ ] Implement `TurnDaemonEvent.kt` (`turnDaemon/types.ts:373-378`: status/runStarted/runCompleted/runFailed/commandResult; `commandResult` nests the dual-discriminator result).
- [ ] Implement `RealtimeEvent.kt` (`realtime/types.ts:1-20`: turnCompleted / messageCreated; `MessageTypeKey` enum public/private/national/diplomacy).
- [ ] Write `TurnDaemonCommandResultWireTest.kt`: deserialize each corpus entry, assert concrete subclass is correct (`auctionFinalize_ok` → AuctionFinalizeOk), assert Fail carries `reason`, assert round-trip tree equality, assert `troopJoin ok:true` does NOT produce the Fail class.
- [ ] Write `RealtimeEventWireTest.kt`: round-trip both realtime variants (`msgType` serializes `"national"`); round-trip a full command envelope and an event envelope nesting a `commandResult` Fail (exercises the nested dual discriminator end-to-end).
- [ ] Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :common:test --tests 'opensamguk.common.wire.*'`. Expected: all wire tests pass; Ok/Fail split correctly; nested `commandResult` envelope round-trips.
- [ ] Commit: `feat(common): result dual-discriminator + daemon event + realtime unions + envelope` + Co-Authored-By trailer.

### Task 22 — Turn world domain model (minimal skeleton entities) in game-engine

**Files:**
- create `app/game-engine/src/main/kotlin/opensamguk/engine/turn/TurnWorldModel.kt`, `DirtyState.kt`
- edit `app/game-engine/build.gradle.kts` (add `:common`, kotlinx-serialization-json, spring-boot-starter-data-redis)

Steps:
- [ ] Add deps to `app/game-engine/build.gradle.kts`: `implementation(project(":common"))`, `implementation(libs.kotlinx.serialization.json)`, `implementation("org.springframework.boot:spring-boot-starter-data-redis")` (Lettuce default), `kotlin("plugin.serialization") version "2.1.0"`, `testImplementation(libs.testcontainers.junit)` + `testImplementation("org.testcontainers:testcontainers:1.20.4")`. Keep the existing `tasks.test { ... }` Docker quirk block (api.version=1.44 + DOCKER_HOST). The edit is ADDITIVE — insert deps into the existing `dependencies { }` — NOT a file rewrite.
- [ ] Write `TurnWorldModel.kt` — minimal data classes: `GeneralStats`, `GeneralRole(Items)`, `TurnGeneral`, `City`, `Nation`, `Troop`, `TurnDiplomacy`, `LogEntryDraft` (engine-local, inert in P0-B), `TurnWorldState`, and `buildDiplomacyKey(src,dest) = "$src:$dest"`. Use `meta: Map<String, Any?>` bags (mirrors TS jsonb). `Instant` for turnTime. Full ~70-column General is P1.
- [ ] Write `DirtyState.kt` — the exact `consumeDirtyState()` return shape: `DeletedNationSnapshot` + `DirtyState(generals, cities, nations, troops, deletedTroops, deletedGenerals, deletedNations, deletedNationSnapshots, diplomacy, logs, createdGenerals, createdNations, createdTroops, createdDiplomacy)`.
- [ ] Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :app:game-engine:compileKotlin`. Expected: `BUILD SUCCESSFUL`.
- [ ] Commit: `feat(game-engine): turn-world domain skeleton + DirtyState shape` + Co-Authored-By trailer.

### Task 23 — InMemoryTurnWorld skeleton (dirty/created/deleted sets + consumeDirtyState)

**Files:**
- create `app/game-engine/src/main/kotlin/opensamguk/engine/turn/InMemoryTurnWorld.kt`
- create `app/game-engine/src/test/kotlin/opensamguk/engine/turn/InMemoryTurnWorldTest.kt`

> Faithful transcription of `inMemoryWorld.ts`: 5 entity maps, 5 dirty sets, 4 created sets, 3 deleted sets, `deletedNationSnapshots`, `logs`. Load-bearing invariants: `consumeDirtyState()` is **single-shot** (drains then clears), getters return **defensive copies**, **create-then-delete in the same tick cancels** (`removeX` prunes `createdXIds`), `removeNation` also prunes its diplomacy from all dirty/created sets.

Steps:
- [ ] Write `InMemoryTurnWorld.kt` (constructor seeds from a `WorldSnapshot`; `LinkedHashMap`/`LinkedHashSet` for deterministic order). Mutation API: `getState`/defensive-copy getters/`pushLog`/`updateGeneral`/`createGeneral`/`removeGeneral` (prunes createdGeneralIds)/`updateCity`/`updateNation`/`createTroop`/`removeTroop`/`removeNation` (cascades diplomacy)/`recordDeletedNationSnapshot`/`setLastTurnTime`/`consumeDirtyState()` (drain into DirtyState then clear ALL sets). Include `WorldSnapshot` data class.
- [ ] Write `InMemoryTurnWorldTest.kt`:
  ```kotlin
  // - update marks dirty; consume drains then second consume is empty (single-shot)
  // - create-then-delete in same tick: NO createdGenerals row AND NO deletedGenerals row
  // - delete existing general appears in deletedGenerals
  // - getter returns defensive copy (external copy with gold=9999 does not mutate internal map)
  // - removeNation prunes its diplomacy from dirty sets and records deletedNations
  ```
- [ ] Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :app:game-engine:test --tests 'opensamguk.engine.turn.InMemoryTurnWorldTest'`. Expected: 5 tests.
- [ ] Commit: `feat(game-engine): InMemoryTurnWorld + dirty/created/deleted sets + consumeDirtyState` + Co-Authored-By trailer.

### Task 24 — Flush STUB with exact write ORDER (FlushOpRecorder, no EntityManager)

**Files:**
- create `app/game-engine/src/main/kotlin/opensamguk/engine/flush/FlushOp.kt`, `DatabaseHooks.kt`
- create `app/game-engine/src/test/kotlin/opensamguk/engine/flush/DatabaseHooksOrderTest.kt`, `DaemonNoEntityManagerTest.kt`

> Transcribe the `databaseHooks.ts` flush ORDER as recorded op tags. NO real SQL. The recorder is the seam; design §0.1 #3 (no JPA EntityManager in the write path) is structurally trivial because the stub takes only a `FlushOpRecorder`. Exact order: `worldState.update` → `oldNation.upsert` (deletedNationSnapshots) → createMany `[general, nation, troop, diplomacy]` → deleteMany `troop`, then `general` + `rankData`, then nation-cascade `diplomacy` → `nationTurn` → `nation` → updates `[general, city, nation-upsert, troop, diplomacy]` → `rankData.upsert` (~40 rows/general) → `logEntry.createMany` → `reservedTurns.flush`.

Steps:
- [ ] Write `FlushOp.kt` — `FlushOp(table, verb, count)` with `Verb { UPDATE, UPSERT, CREATE_MANY, DELETE_MANY }` + `FlushOpRecorder` (`record` guards `count<=0` for non-UPDATE; `recordAlways`; `ops()`). The recorder is the ONLY sink the daemon flush path may write through.
- [ ] Write `DatabaseHooks.kt` — `flushChanges(world)` consumes dirty state and records ops in the exact order. `RANK_ROWS_PER_GENERAL = 40`. Update counts exclude created ids. Documents §0.1 #3 (recorder-only, P1 swaps for JDBC-batch, never JPA) and §7 (inheritance/storage/hall/dynasty NOT in op list; the single archive write is `ng_old_nations`, the per-season game table, not the dynasty table).
- [ ] Write `DatabaseHooksOrderTest.kt`: mixed dirty set (update general, update city, removeNation cascading diplomacy, push log) → assert the exact op-tag sequence; assert `rank_data` count = 40 per updated general.
- [ ] Write `DaemonNoEntityManagerTest.kt` — class-file constant-pool scan (no ArchUnit dep): the compiled `opensamguk/engine/flush` classes must contain neither `jakarta/persistence/EntityManager` nor `org/springframework/data/jpa/repository/JpaRepository`.
- [ ] Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :app:game-engine:test --tests 'opensamguk.engine.flush.DatabaseHooksOrderTest' --tests 'opensamguk.engine.flush.DaemonNoEntityManagerTest'`. Expected: 3 tests. Order test fails loudly on any reorder. (Note: `DaemonNoEntityManagerTest` depends on compiled main classes — run after `:app:game-engine:compileKotlin`.)
- [ ] Commit: `feat(game-engine): flush STUB with exact write ORDER + no-EntityManager guard` + Co-Authored-By trailer.

### Task 25 — Flush-EXCLUSION (survive-truncate) contract from ResetHelper.php

**Files:**
- create `app/game-engine/src/main/kotlin/opensamguk/engine/flush/TruncateContract.kt`
- create `app/game-engine/src/test/kotlin/opensamguk/engine/flush/TruncateContractTest.kt`

> Per-season reset must NEVER truncate `inheritance_*`, `storage`/KV namespaces other than `general_%`, `hall`, or `ng_games`. Transcribe the allow/deny table from PHP `ResetHelper.php` (grand truth) as a frozen contract; verify completeness against the V1 baseline table list. Reference-data transcription — do NOT invent the list; read `legacy/devsam-core/hwe/sammo/ResetHelper.php` reset()/resetValues()/dropped-table set + `infra/src/main/resources/db/migration/V1__baseline.sql`.

Steps:
- [ ] Read `ResetHelper.php`; identify (a) game tables DROP/TRUNCATEd on reset and (b) the survive-set (storage non-`general_%`, inheritance_*, hall, ng_games). Cross-check (a) against V1 baseline names.
- [ ] Write `TruncateContract.kt` — `SURVIVE` and `TRUNCATED` `Set<String>` + `isExcludedFromTruncate(table)`. Document that the contract sits OUTSIDE the world+flush boundary (§7).
- [ ] Write `TruncateContractTest.kt`: inheritance/hall/dynasty survive; per-season tables (general/city/nation/diplomacy/log_entry) are truncated not survived; SURVIVE ∩ TRUNCATED is empty; **every concrete baseline `CREATE TABLE` is classified** (parse `../../infra/src/main/resources/db/migration/V1__baseline.sql`; if CWD differs, switch to a classpath resource — verify the path resolves on first run).
- [ ] Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :app:game-engine:test --tests 'opensamguk.engine.flush.TruncateContractTest'`. Expected: 4 tests. The completeness test forces every future baseline table to be classified.
- [ ] Commit: `feat(game-engine): flush-exclusion (survive-truncate) contract from ResetHelper.php` + Co-Authored-By trailer.

### Task 26 — Redis stream keys + XREAD consumer skeleton (Lettuce via Spring Data Redis)

**Files:**
- create `app/game-engine/src/main/kotlin/opensamguk/engine/redis/RedisCommandStream.kt`

> Transcribe `redisCommandStream.ts`: stream key builder, `XREAD BLOCK COUNT 100` from cursor `lastId` (starts `'$'` = only-new), single `payload` JSON field per message, advance `lastId` per consumed message. Use Lettuce-backed `StringRedisTemplate.opsForStream()`. **No engine-local `StreamKeys.kt`** — `RedisCommandStream` consumes the `:common` `TurnDaemonStreamKeys.of` / `gameEventChannel` directly (game-engine already depends on `:common` per Task 22). The duplicate engine `StreamKeysTest` is dropped; the key strings are already locked by `:common` `StreamKeysTest` (Task 19).

Steps:
- [ ] Write `RedisCommandStream.kt` — `readCommands(blockMs)`: `StreamReadOptions.empty().count(100).block(...)`, read from `StreamOffset.create(commandStream, ReadOffset.from(lastId))` where `commandStream` comes from the `:common` `TurnDaemonStreamKeys.of(profile).commandStream`, advance `lastId = record.id.value` per record, decode the `payload` field via `WireJson` to `TurnDaemonCommandEnvelope.command`, skip unparseable payloads (lastId still advances). `startId` defaults to `"$"`. (If a thin engine wrapper is ever kept it MUST delegate to `:common` and use identical key strings — but P0-B keeps none.)
- [ ] Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :app:game-engine:compileKotlin`. Expected: consumer compiles against Spring Data Redis stream API + `:common` stream keys.
- [ ] Commit: `feat(game-engine): Redis stream keys + XREAD consumer skeleton (Lettuce)` + Co-Authored-By trailer.

### Task 27 — Redis consumer integration test (Testcontainers redis:7-alpine)

**Files:**
- create `app/game-engine/src/test/kotlin/opensamguk/engine/redis/RedisCommandStreamIT.kt`

Steps:
- [ ] Write `RedisCommandStreamIT.kt` (`@Testcontainers`, `GenericContainer("redis:7-alpine").withExposedPorts(6379)`, Lettuce `LettuceConnectionFactory` → `StringRedisTemplate`): add a message BEFORE consumer construction (lastId=`$` → ignored), construct consumer, add a NEW message, `readCommands(2000)` returns exactly 1 (the post-construction `resume`), `lastId()` advanced, second drain empty. If `$` timing proves flaky, switch the test to `startId="0"` and assert both-then-advance — production default stays `$`.
- [ ] Run (Docker required): `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :app:game-engine:test --tests 'opensamguk.engine.redis.RedisCommandStreamIT'`. Expected: 1 test; verify `redis:7-alpine` started in output.
- [ ] Commit: `test(game-engine): Redis Streams consumer integration test (Testcontainers)` + Co-Authored-By trailer.

### Task 28 — Realtime publisher (engine) + SSE relay (api) closing the CQRS loop edge

**Files:**
- create `app/game-engine/src/main/kotlin/opensamguk/engine/redis/RealtimePublisher.kt`
- edit `app/game-api/build.gradle.kts` (add `:common`, data-redis, kotlinx-serialization-json, testcontainers)
- create `app/game-api/src/main/kotlin/opensamguk/gameapi/sse/RealtimeSubscriber.kt`, `RealtimeRelayController.kt`
- create `app/game-api/src/test/kotlin/opensamguk/gameapi/sse/RealtimeRelayIT.kt`

> Per §4: game-engine publishes `turnCompleted` to the pub/sub channel; game-api subscribes (Redis `MessageListener`) and fans out via `SseEmitter`. Coarse signal only.

Steps:
- [ ] Write `RealtimePublisher.kt` — `publishTurnCompleted(atIso, lastTurnTimeIso)`: `template.convertAndSend(gameEventChannel(profile), WireJson.encodeToString(RealtimeEvent.serializer(), RealtimeEvent.TurnCompleted(...)))` consuming the `:common` `gameEventChannel` directly (no engine-local key helper).
- [ ] Edit `app/game-api/build.gradle.kts` — add `implementation(project(":common"))`, `implementation("org.springframework.boot:spring-boot-starter-data-redis")`, `implementation(libs.kotlinx.serialization.json)`, `kotlin("plugin.serialization") version "2.1.0"`, `testImplementation("org.testcontainers:testcontainers:1.20.4")`. Keep the existing `tasks.test { ... }` Docker quirk block (api.version=1.44 + DOCKER_HOST + DOCKER_CONTEXT=default + RYUK_DISABLED). The edit is ADDITIVE — insert deps into the existing `dependencies { }` — NOT a file rewrite. (app/game-api/build.gradle.kts already contains the quirk block; a rewrite to bare `tasks.test { useJUnitPlatform() }` would stop the redis:7-alpine Testcontainer.)
- [ ] Write `RealtimeRelayController.kt` — `@RestController @RequestMapping("/realtime")` with `CopyOnWriteArrayList<SseEmitter>`; `GET /events` registers an emitter (no timeout, removes on completion/timeout); `fanOut(json)` sends `event().name("realtime").data(json)` to all, pruning failures. The kotlin-spring plugin opens `@RestController` so the IT can subclass it.
- [ ] Write `RealtimeSubscriber.kt` — `@Configuration` providing a `RedisMessageListenerContainer` bound to `sammo:$profile:realtime:events` (profile from `@Value("\${opensamguk.profile:che:scenario_2}")`); the `MessageListener` calls `relay.fanOut(String(msg.body))`. Ensure `opensamguk.profile` exists in game-api `application.yml` (add if missing).
- [ ] Write `RealtimeRelayIT.kt` (`@Testcontainers` redis): wire a bare `RedisMessageListenerContainer` to `sammo:default:realtime:events`, `convertAndSend` a `turnCompleted` payload, poll until received (deadline), assert delivery. Exercises the pub/sub→listener path the production `@Bean` wires.
- [ ] Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :app:game-api:test --tests 'opensamguk.gameapi.sse.RealtimeRelayIT'` then `:app:game-api:compileKotlin`. Expected: IT passes (grep `turnCompleted` / `redis:7-alpine` started).
- [ ] Commit: `feat: realtime publisher (engine) + SSE relay (api) closing CQRS loop edge` + Co-Authored-By trailer.

### Task 29 — Full-area build + gate verification

**Files:** none (verification only).

Steps:
- [ ] Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :common:test :app:game-engine:test :app:game-api:test`. Expected: `BUILD SUCCESSFUL`; verify each module's test summary by output tail/grep (exit 0 unreliable). Approx counts: common (rng 31 + josa/log + wire; stream-key strings locked in :common StreamKeysTest) ; game-engine (world 5, flush-order 3, no-EM 1, truncate 4, redis IT 1; no engine-local StreamKeysTest — keys consumed from :common) ; game-api (relay IT 1).
- [ ] Grep for failures: `... | grep -Ei 'FAIL|exception|> Task .* FAILED'` returns nothing.
- [ ] Run the existing P0-A tests to confirm no regression: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :infra:test --tests 'opensamguk.infra.worldstate.WorldStateRepositoryIT'`. Expected: still `BUILD SUCCESSFUL`.
- [ ] Commit: `test: world-flush-redis area green (full build + tests)` + Co-Authored-By trailer.

**Area 4 golden-fixture strategy:** Oracle = TS Zod schemas in `commandRegistry.ts` + discriminated-union `types.ts` (the target's own contract; hand-author one valid envelope per command/result type and confirm accept/reject). Committed corpus under `golden/wire/`: `wire_commands_valid.json` (one envelope per ~28 command variants, exact shapes from types.ts), `wire_commands_malformed.json` (missing required field / wrong type / unknown type), `wire_results.json` (one Ok + one Fail per result type to lock the dual `type`+`ok` discriminator), `wire_realtime.json` (turnCompleted + messageCreated). The Kotlin tests deserialize each valid envelope, re-serialize, and assert round-trip JSON-tree equivalence; assert each malformed entry rejects; assert the Result deserializer splits `...Ok` vs `...Fail` on the same `type` by reading `ok`. Stream-key golden is a pure string assertion (`TurnDaemonStreamKeys.of('default')`/`'che:scenario_2'` and `gameEventChannel` trim+`unknown`). No live Redis needed in `:common`. The world/flush/exclusion contracts are verified by behavioral unit tests (single-shot drain, create-then-delete cancel, exact flush op-tag ORDER, 40-rows-per-general, no-EntityManager constant-pool scan, baseline-table-completeness) rather than JSON fixtures, because they are structural invariants, not text-byte outputs. The Redis consumer + SSE relay are proven by Testcontainers `redis:7-alpine` integration tests (only-new cursor `$` semantics, lastId advance, pub/sub→listener delivery). Real JDBC flush execution and byte-comparable PHP DB-dump diff are a P1 gate; P0-B records op-ORDER + exclusion intent only.

---

## Self-Review

**Spec coverage vs design §0.1 + research:**
- §0.1 #3 (daemon never uses JPA EntityManager for writes) — enforced two ways: structurally (the flush stub's only sink is `FlushOpRecorder`; P1 swaps for JDBC batch, never JPA) and by `DaemonNoEntityManagerTest` scanning compiled `engine.flush` class constant pools for `jakarta/persistence/EntityManager` and `JpaRepository`. Covered (Task 24).
- §7 (inheritance/storage/hall/dynasty outside the world+flush boundary) — `TruncateContract` SURVIVE set + the absence of those tables from the flush op list; baseline-completeness test forces classification of every table. Covered (Tasks 24-25).
- RNG byte parity (§10.1) — full LiteHashDRBG/RandUtil/seed-serializer port with a single committed TS golden proving SHA + LE-uint32 append + slicing + rejection sampling + bit-exact float + JS-key-order weighted choice + tournament double-pipe quirk. Covered (Tasks 1-7).
- Josa + log-token model — JosaUtil (TS-simplified, not PHP), LogFormat 0-8, formatLogText color-wrapped icons, convertLog 14-ordered replacements incl `<D>==<O>` duplicate and `<Y1>`-before-`<Y>`, ActionLogger/UserLogger buffer/flush with per-branch truthiness, finalizeLogEntry drop-rule + baked prefix. TS goldens (pick/convertLog/formatLogText/render-e2e). Covered (Tasks 8-14).
- Constants (PHP grand truth) — GameConst scalars/sets/items/menus/event-tables-as-DATA, GameUnitConst 40×18-tuple + constraint-info append + specific-id coef resolution, CityConst 94×13-tuple ×100 + label→int + bidirectional path + lv4/lv5 distinction, EffectiveGameConst override merge + derived killturn/develcost. Two independently-transcribed golden JSONs. Covered (Tasks 15-18).
- Wire contract (sealed, TS structure) — state/reason enums, full ~28-variant command union (type discriminator), result union (dual type+ok via JsonContentPolymorphicSerializer), event union (nests result), realtime union, command/event envelope, stream keys. Covered (Tasks 19-21).
- CQRS skeleton — InMemoryTurnWorld dirty/created/deleted sets + single-shot consumeDirtyState, flush-order stub, Redis Streams `XREAD BLOCK COUNT 100` from `$`, SSE relay over pub/sub. Covered (Tasks 22-29). Maps to design §11 P0 gate items: empty world + dirty sets (22-23), databaseHooks stub (24), flush-exclusion + rehydrate contract (25), Redis Streams consume (26-27), SSE relay + no-op command round-trip (19-21, 28).

**Placeholder scan:** No code placeholders remain in the load-bearing port logic — every Kotlin block above is a real faithful port. Intentional transcription stubs are explicitly marked `/* transcribe lines X-Y */` only in the large PHP constants tables (GameConst scalar block, GameUnitConst 40 rows, CityConst 94 rows) and the wire corpus author-all instructions, where reproducing the full table inline in the plan would be noise — the implementer fills these from the cited source line ranges, and the golden round-trip diff fails loudly on any transcription error. The early-draft RNG plan's `HelloWorld matches reference` test stub and the `bytes |= 0` note were resolved (deleted/clarified) before assembly.

**Type consistency:** Unsigned-64 represented as signed `Long` (safe because all call sites use bits ≤ 54; guarded by float/int goldens). `LogFormat` codes pinned 0-8 as the serialization contract. `meta`/`const`-override bags typed `Map<String, Any?>` (mirrors TS `Record<string,unknown>` / jsonb). `createdAt`/turnTime use `java.time.Instant` (carried, never byte-compared in P0-B). attackCoef/defenceCoef `Map<Int, Double>` keep raw mixed armType+unit-id keys. Wire dual-discriminator result classes re-emit both `type` and `ok` so encode round-trips. **One cross-area consistency hazard flagged:** all four areas depend on the single `gradle/libs.versions.toml` + `common/build.gradle.kts` kotlinx.serialization edit. The RNG and josa-log areas need it test-scope; the wire area needs it implementation-scope. The plan resolves this deterministically: whichever area lands first adds the lib + plugin (Task 3 default, test-scope), and the wire area (Task 19) promotes the dependency to `implementation` scope rather than re-declaring. Later areas verify presence instead of re-adding. The `kotlin("plugin.serialization") version "2.1.0"` form appears in the app-module edits (Tasks 22, 28) while `:common` uses the `libs.plugins.kotlin.serialization` alias — both resolve to the same Kotlin 2.1.0 plugin; the implementer should prefer the `libs` alias everywhere if `libs.versions.toml` already pins `kotlin = "2.1.0"`, falling back to the literal version only in app modules that don't yet alias it.

---

## Open questions to resolve against PHP before/during implementation

1. **RNG weighted-choice numeric-key order (research §4 Q4):** `choiceUsingWeight` numeric-key ordering is pinned to TS (numeric-first) via the `choiceWeightNumeric` fixture. If the parity owner rules a PHP integer-keyed weighted-choice golden authoritative, only that one fixture must be regenerated from PHP. Flag at golden-freeze.
2. **RNG >54-bit caller — RESOLVED (guard added):** Unsigned-64 chosen as signed `Long` (safe because every call site uses bits ≤ 54). The Kotlin `_nextInt` equivalent now carries a `require(bits <= MAX_RNG_SUPPORT_BIT + 1)` (i.e. `<= 54`) assertion at the top (Task 4), so any future >54-bit caller fails loudly instead of silently overflowing the sign bit. Also guarded by the float/int goldens.
3. **RNG PHP second-oracle dump:** `legacy/devsam-core` RNGTest.php cross-check is deferred (not built in P0-B). Confirm with the parity owner whether a PHP byte-stream cross-check must land in P0-B or can be a P8 parity-harness task.
4. **Node native `.ts` import:** `node --experimental-strip-types` / `npx tsx` may not resolve the legacy packages' `.js`-extension ESM imports. Documented fallback: build the TS package (`pnpm --filter @sammo-ts/common build`) and import `dist/index.js`. Needs a one-time confirmation when each dump script first runs.
5. **Josa `'부터'` throw + LATIN-path divergence (open Q1):** The golden freezes Kotlin to mirror TS = throw `IllegalArgumentException("올바르지 않은 조사 지정")`. The parity owner must confirm before freeze whether TS will be "fixed" to pass `woJongsung` — if so, regenerate `josa/pick.json` and adjust `JosaUtilTest`. Also a live functional crash risk in P1 wiring. **LATIN-path divergence (not only hanja):** PHP `JosaUtil::check` applies regex special-char tables (`PRE_REG_SPECIAL_CHAR` / `_RO` / `_NORMAL_FIXED`) to romanized text, while TS 2026 collapses to a last-char vowel check `{a,e,i,o,u,y}`. Romanized golden cases (Kim, Park, etc.) should be added to `josa/pick.json`; this latin divergence is a P1+ watch-point for PHP DB byte-parity.
6. **Log RAW-vs-FINAL grading (open Q2):** The plan grades BOTH — `formatLogText.json` = RAW baked markup (what `finalizeLogEntry` stores, pre-convertLog); `render-e2e.json` = post-convertLog html. Confirm the DB-stored byte-parity gate compares the RAW form (finalize stores raw; convertLog runs at API/display layer).
7. **Log `createdAt` / `meta` mapping:** `createdAt` (JS Date → `java.time.Instant`) and `meta` (`Record<string,unknown>` → `Map<String,Any?>`) are carried opaquely in P0-B, never byte-compared. Confirm a later phase (DB flush byte-parity) does not need a specific string format for `createdAt` or a serialized `meta`.
8. **ScenarioConfig.const overridable key set (research Q5):** `const` is untyped `Record<string,unknown>`. P0-B ships the generic-map `EffectiveGameConst` (matches TS). Before locking, enumerate which GameConst fields scenario JSON actually overrides (inspect `scenario_0.json` + parseScenario) to decide whether a typed override subset is worth it. Follow-up.
9. **killturn/develcost derivation (research §2.3) — CONFIRMED source:** `ResetHelper.php:264-267` — `killturn = 4800/turnterm; if(npcmode==1) killturn = intdiv(killturn,3); develcost = (year-startyear+10)*2;`. develcost is additionally recomputed per-turn at `func_gamerule.php:219`. These are computed at scenario build (and per-turn for develcost), not static consts — the source does NOT "fail to surface". This is a PHP-only port (no TS golden). develCost MUST be RECOMPUTED per-turn (not cached once) in P1.
10. **Constants live PHP dump:** The committed constants goldens are transcribed from the PHP static tables. A real `GetConst.php` dump from the devsam capture env should cross-validate once available; until then the structural/spot test guards transcription typos but not a PHP-side runtime transform we may have missed.
11. **UnitConstraint `info()` strings:** The exact `info()` strings must be locked from `hwe/sammo/GameUnitConstraint/*.php` (parity-graded). Capture in the unit golden if uncertain at implementation time.
12. **CityConst `byRegion` last-wins quirk:** PHP `_generate()` does `$constRegion[$region] = $city` (overwrites). The plan mirrors last-wins for parity (documented in a code comment); the parity-critical surface is byID/byName/all(). Confirm last-wins is acceptable vs an append-list if a later phase needs all cities per region.
13. **Wire-contract scope (research Q3):** The full ~28-variant union (incl auction/tournament/vote variants the PHP daemon never exposes) is transcribed for forward-compat; only the slice handlers get wired in P1. Confirm full-union is the intended P0-B parity target vs control-subset only.
14. **Serialization library + dual discriminator — RESOLVED:** kotlinx.serialization on `:common` is approved (no competing serializer on `:common`; Jackson is app-web-only). Keep the `JsonContentPolymorphicSerializer` for the type+ok result split (sealed + `@SerialName`). The dual-discriminator handling is mandatory.
15. **Redis client + SSE placement — RESOLVED:** Lettuce via Spring Data Redis confirmed (already on infra classpath, Lettuce default — no Jedis). engine-publishes / api-relays-SSE is confirmed: SSE fan-out on game-api with game-engine publishing to pub/sub per §4. Do NOT co-locate SSE on the engine.
16. **Domain shapes + flush stub fidelity:** P0-B models TurnGeneral/City/Nation/Troop/Diplomacy as minimal data classes + `meta` bag (full ~70-col General is P1). The flush stub records op intent only (no real JDBC). Confirm the minimal skeleton is sufficient and that the byte-comparable PHP DB-dump diff is a P1 gate (P0-B needs only op-ORDER + exclusion contract).
17. **Enum string casing:** TS serializes command/state enums camelCase/lowercase (troopJoin, auctionFinalize, running). `@SerialName` must pin each exactly; confirm no PHP-side daemon enum differs (PHP commands are a subset; casing follows TS).
