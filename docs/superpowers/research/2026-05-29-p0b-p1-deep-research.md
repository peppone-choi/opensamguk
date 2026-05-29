# Deep Research: P0-B + P1 Readiness — devsam → opensamguk Kotlin Migration

> Synthesis of an adversarial design review + 4 source-grounding probes (RNG kernel, JosaUtil/log-tokens, constants/wire/world skeleton, P1 vertical slice) against the approved design doc `2026-05-29-devsam-opensamguk-kotlin-migration-design.md`.

- **Date**: 2026-05-29
- **Confidence**: High. The probes are grounded in named TS/PHP source paths, with cross-language oracles already existing for the hardest premise (RNG).
- **Bottom line**: The architecture survives contact with reality. Three premises are weaker than the doc presents; none are fatal; all are fixable by tightening the spec before P0 locks. P0-B is **ready to plan** after 3 spec edits. P1 is **ready to plan** after pinning 1 porting decision and 5 PHP-confirmation questions.

---

## 1. Design Validation — challenged premises

Verdict legend: **HOLDS** (premise stands, no change) · **HOLDS-WITH-CAVEAT** (true but the doc under-specifies it) · **CONTRADICTORY** (internally inconsistent as written).

### (a) `precheck(api,DB) 판정 == full(daemon,memory) 판정` via one shared constraint library — **HOLDS-WITH-CAVEAT** (severity: med)

**Code-sharing half is genuinely demonstrated** in the TS reference: a single `evaluateConstraints` loop (`packages/logic/src/constraints/evaluate.ts`), one `Constraint{requires,test}` type (`types.ts`), and a `StateView` abstraction (`helpers.ts`) that lets `DbStateView` materialize whole entities so `requires()` stays coarse-grained. So code-duplication drift (Risk #2) is well-mitigated.

**The gap the doc misses**: the invariant conflates *code identity* with *judgment identity*. `precheck` runs at command-**submission** time against the last-flushed DB snapshot; `full` runs at turn-**execution** time against an in-memory world that may have absorbed many other players'/AI mutations since the flush. Identical logic on different data-freshness can legitimately return allow-then-deny. The deny→휴식 fallback (§6 Dual-StateView, §12 step 4) is therefore an **inherent, designed-for CQRS outcome**, not an error condition.

**Adjustment**: Reframe the §4 핵심 불변식 from "`precheck 판정 == full 판정`" to: *"precheck and full share one constraint library AND evaluate against semantically-equivalent snapshots; divergence due to intervening mutations is expected and surfaced to the user as a designed deny-at-execution path."* Add a precheck-vs-exec fixture (Risk #2 already calls for one) that **deliberately mutates the world between submission and execution** to assert the fallback fires cleanly — i.e. a *passing* test, not a parity failure. UI/UX must communicate "available at submission" ≠ "guaranteed at execution."

### (b) Byte-exact RNG / log parity (float, sort stability, JSON key order) — **SPLIT: RNG core HOLDS; conquest tie-break is CONTRADICTORY** (severity: high)

**RNG core HOLDS**: `LiteHashDRBG` is SHA-512 + integer/BigInt arithmetic; `nextFloat1` divides an integer `<2^53` by exactly `2^53` — exactly representable identically in PHP/JS/JVM IEEE-754. A cross-language test vector already exists (`legacy/devsam-core/tests/RNGTest.php` + `hwe/test-ts/rng.test.ts`, and `legacy/devsam-core2026/packages/common/test/rng.test.ts`). P0's RNG golden is low-risk. (Full spec in §2.)

**Conquest tie-break is internally contradictory.** `WarUnitCity.php addConflict()` does `$conflict[$nationID] += ($dead * 1.05)`, then `arsort($conflict)`, then `Json::encode`, and the **first key wins the city**. PHP `arsort` is **UNSTABLE** (`zend_sort`), and the JSON key-insertion order after `arsort` is the grand-truth artifact. The TS reference (`packages/logic/src/war/aftermath.ts` lines 115–118) re-derives this with `Object.entries().sort((a,b)=>rhs-lhs)`, which is **STABLE since ES2019** — so TS and PHP **already disagree on equal-contribution ties**.

The doc's stated porting policy "TS 구조 직역 + PHP 수치·로그 tiebreaker" (§2 Locked) is therefore **self-contradictory exactly here**: you cannot copy TS's stable sort AND match PHP's unstable `arsort`. Risk #8 names "arsort tie-break + JSON 키 삽입순서" but frames it as a solved "결정적 ordered-map" rather than as a TS-vs-PHP contradiction in the porting policy itself.

**Adjustment**: In §10.5 / Risk #8 add an explicit override: *"conflict ordering is governed by PHP `arsort` (unstable) semantics, NOT the TS stable sort; implement a Kotlin comparator + ordered-map that reproduces PHP `arsort` tie-break and JSON key-insertion order, validated by a dedicated golden with equal-contribution ties. Build that golden from PHP, not TS."* This is the one place the global "TS structure + PHP numbers" rule must be inverted.

### (c) P0→P8 ordering is dependency-correct, no hidden cycle — **HOLDS for implementation; verification-ordering cycle at the P5 gate** (severity: med)

**Implementation order is correct**: war (P4) before AI (P5); `packages/logic/src/war` imports no `ai`. No runtime import cycle.

**But the P5 gate is not closable at P5 as written.** The GeneralAI nation dispatcher (P5) emits diplomacy commands — `generalAi/nation/diplomacy.ts` returns `ai.buildNationCandidate('che_불가침제의' | 'che_선전포고', ...)` — whose **resolvers live in P6** (외교 directional-pair state machine + DiplomaticMessage accept → `che_*` nation 명령). P5's stated gate ("고정시드 풀게임 replay NPC 선택 + 다운스트림 로그 turn-for-turn 일치" and "AI 선택 명령이 실행과 동일 predicate 통과") cannot reach turn-for-turn parity if NPCs propose pacts / declare-with-diplomacy and those commands have no executable resolver until P6. The true edge is **P5_gate ← P6**, while the doc declares **P6 ← P5**.

**Adjustment**: Re-scope the P5 gate — either (a) limit P5 full-game replay parity to AI selections of P0–P5 commands and explicitly defer diplomacy-command *outcome* parity to P6, or (b) pull the minimal diplomacy resolvers (`che_불가침제의` / `che_선전포고` accept path) forward into P5. Document which. Update the §11 dependency graph note to reflect the verification edge.

### (d) Spring Data JPA + JDBC-batch flush, no entity/flush duality drift — **HOLDS as a decision; missing one load-bearing constraint** (severity: med)

The decision is defensible. The doc correctly diagnoses "JPA dirty-checking이 bulk flush에 부적합" and routes the hot path to JDBC batch (§2 Locked, §7). The TS reference uses **no ORM persistence context** — it flushes via explicit dirty-id Sets (`inMemoryWorld.ts consumeDirtyState`, ~line 697: `dirtyGeneralIds`/`createdGeneralIds`/`deletedGeneralIds`) + bulk `createMany` (`infra/turnEngineDb.ts`). **Single source of dirty truth.**

The target reintroduces **three representations**: JPA entity (read/precheck), logic domain object, and the JDBC row-builder + explicit change-recorder (the Immer replacement). The spec never states whether the **daemon's** in-memory world holds JPA-managed entities or plain logic objects. If the daemon ever attaches an entity to an `EntityManager`, JPA auto-dirty-checking and the explicit change-recorder become **two disagreeing dirty sources**, and JPA flush-on-commit could emit writes that bypass the byte-controlled `databaseHooks` ordering.

**Adjustment** (add as a Locked decision): *"The game-engine daemon never uses a JPA `EntityManager` for writes. JPA is confined to game-api read/precheck. Daemon writes flow only through the explicit change-recorder → `databaseHooks` JDBC batch."* Add a P0 architectural test that **fails if the daemon module depends on `EntityManager`/repository write APIs**.

### (e) Single-daemon single-thread loop with catch-up/checkpoint scales and is sound — **HOLDS as a model; 3 soundness gaps** (severity: med)

The loop and checkpoint model match the doc (`turnDaemonLifecycle.ts runLoop`; `inMemoryTurnProcessor.ts run`; resume by `generalId > checkpoint.generalId` via `shouldProcessByCheckpoint`).

1. **Mid-turn flush can create DB states PHP never had.** The budget deadline uses wall-clock `Date.now()`; a partial run can flush **mid-turn** (after N generals, before the rest), producing an intermediate DB state that won't byte-match a golden dump from an uninterrupted PHP run. The doc asserts flush must be "byte-comparable to golden DB dump" but the checkpoint mechanism can create flush boundaries PHP never had.
2. **No catch-up bound.** `catchUpCap` defaults to 1 (`cli.ts` line 25) → recovering from long downtime processes 1 in-game month per run iteration. Combined with single-thread + per-general loop, a daemon down for many in-game months catches up slowly, and §4/§6 give no bound on catch-up time vs profile count on one t3.large (one daemon per profile, multiple profiles per box).
3. **Resume contract is more nuanced than "cursor = 마지막 general id."** When generals complete but the month-loop is partial, `nextCheckpoint = undefined` (`inMemoryTurnProcessor.ts` lines 105–107).

**Adjustments**:
- §10/§12: parity harness dumps DB state **only at turn/month boundaries**, never observing a mid-checkpoint partial flush. For parity-replay runs, gate partials on **processed-count, not wall clock** (make `budgetMs` effectively non-binding so checkpoint partials cannot occur during golden comparison).
- §6: add a catch-up budget analysis — worst-case in-game months to recover from a daemon down for D hours, and max concurrent profiles per t3.large given single-thread + `catchUpCap`. State the headroom assumption explicitly if multiple busy profiles share one box.
- §6: correct the resume description to cover the `nextCheckpoint = undefined` (generals-done, month-partial) case.

### Cross-cutting backlog hazard (from the probe gaps)

The **23 missing TS commands** (§3, §11 P8) — `che_선양/모반시도/무작위건국`, `cr_건국`, `event_*연구` — are **concentrated in the unstable-sort hazard area**. Grep confirms `che_건국.php`, `che_방랑.php`, `che_무작위건국.php`, `che_무작위수도이전.php` all use `arsort` (conflict/capital tie-breaks). "Defer to backlog (P8)" therefore **defers the riskiest parity work to last**.

**Adjustment**: Re-prioritize — flag that 건국/방랑/수도이전/무작위건국 touch the `arsort`/capital tie-break hazard and should be ported **alongside P4 conflict work**, not deferred to the P8 backlog, so the riskiest parity surface is validated early.

---

## 2. P0-B Readiness — consolidated exact specs

P0-B = the three parity-gate kernels (RNG, JosaUtil/log, constants/wire/world skeleton) that must be locked before any rule code. All three probes returned implementation-ready specs grounded in named source. **Port target = TS (`devsam-core2026`); PHP is the second oracle.** Below are the load-bearing facts and the golden-fixture strategy. Full method-by-method specs live in the probe outputs; this is the consolidated, plan-ready summary.

### 2.1 Deterministic RNG kernel (`LiteHashDRBG` + `RandUtil` + seed serialization)

**Source of truth**: `legacy/devsam-core2026/packages/common/src/util/{LiteHashDRBG,RandUtil,sha512,convertBytesLikeToUint8Array}.ts`. PHP twin (`legacy/devsam-core/src/sammo/{LiteHashDRBG,RandUtil}.php`) is identical and used as second oracle.

**Constants**: `MAX_INT = 2^53-1 = 9007199254740991`; `TWO_POW_53 = 9007199254740992`; divisor literal `9007199254740992.0`; `BUFFER_BYTE_SIZE = 64`.

**DRBG core**:
- State = `(seedBytes, stateIdx:Long, buffer[64], bufferIdx:Int)`.
- `genNextBlock()`: `buffer = SHA-512(seedBytes ++ littleEndianUint32(stateIdx))`; `bufferIdx=0`; `stateIdx += 1`. **stateIdx is appended LITTLE-ENDIAN uint32** (`DataView.setUint32(pos,val,true)` / PHP `pack('V',...)`).
- Constructor order is load-bearing: `genNextBlock()` runs **FIRST** (consumes stateIdx 0, advances to 1, fills block), THEN assign the ctor `bufferIdx`.
- Seed is UTF-8 bytes when a string (production seeds are always strings).

**Draw methods** (all spec'd in the probe; the highest-blast-radius facts):
- `nextBytes(bytes, baseBytes?)`: real bytes consumed = `bytes` **regardless of baseBytes** — `baseBytes` only pads the OUTPUT array with trailing zeros. The eager refill `if (bufferIdx==64) genNextBlock()` (after computing the return slice) is mandatory for stream alignment.
- `_nextInt(bits)`: `nextBits(bits, baseBytes=8)` → LE u64 read. All call sites use `bits ≤ 54`, so the value fits a signed JVM `Long` (high bytes zero).
- `nextInt(max?)`: rejection loop `while (n > max)` → **INCLUSIVE upper bound** (`n==max` accepted). `max>MAX_INT` throws.
- `nextFloat1()`: TS two-branch form — `nInt=_nextInt(54)`; `if nInt<2^53 return nInt/2^53`; `if nInt==2^53 return 1.0`; else redraw. PHP single-branch form is numerically identical. Compare **bit-exact** (`Double.toRawLongBits`), never epsilon.
- `RandUtil`: `nextRangeInt` is **inclusive both ends**; `nextInt(minIncl,maxExcl)` passes `span-1` (off-by-one trap); `nextBool(0.5)` special-cases to `nextBit()` BEFORE the `<0` check; `shuffle` is forward-sweep Fisher-Yates with `destIdx = nextInt(cnt-srcIdx-1) + srcIdx`.

**Three porting hazards that must be matched to TS, not normalized**:
1. **Endianness** (stateIdx LE uint32 append; u64 LE read) — highest-likelihood silent break. Use `ByteBuffer.order(LITTLE_ENDIAN)`; do NOT use `DataOutputStream` (big-endian).
2. **JS object key ordering** for `choice`/`choiceUsingWeight` over a Record: integer-like keys ascending-numeric FIRST, then string keys in insertion order. Golden: `{c,a,b,4,2,'3'}` iterates `[2,3,4,c,a,b]`. Kotlin `LinkedHashMap` (pure insertion order) **diverges** — implement a `jsKeyOrder(keys)` helper. PHP `choiceUsingWeight` uses `array_keys` insertion order and **diverges from JS for integer keys** → **follow TS**.
3. **TournamentRNG double-pipe quirk**: `buildTournamentSeedKey` emits `gameIndex`/`extraSeed` tokens with a leading `|` then joins with `|`, producing `||game:` / `||extra:` in the seed string. **Reproduce the quirk exactly** (do not "clean it up"); lock with a seed-string golden.

**Three seed-string serializers** (each produces a String, then `LiteHashDRBG.build(string)`): `serializeSeed` (`str(len,val)`|`int(floor(n))`), `simpleSerialize` (adds `float(...)` en-US 6-frac formatting), `buildTournamentSeedKey` (labeled pipe-joined tokens). `str(len,...)` uses JS `.length` = UTF-16 code units = Kotlin `String.length` (match).

### 2.2 JosaUtil + action-log color-token model

**CRITICAL: PORT TS, NOT PHP.** The TS `JosaUtil.ts` is a *deliberately simplified* reimplementation of the richer PHP `JosaUtil.php` (PHP has ~3700-codepoint hanja jongsung tables + Latin/number regex tables that TS **dropped**). The P0-B gate grades against TS golden output. Porting PHP would be "more correct" and **FAIL the gate**. This is the single biggest mis-source risk in P0-B.

**JosaUtil public API** = only `pick(text, wJongsung, woJongsung="")` and `put(...)`. No `fix()`/`batch()`/`check()` (PHP has them; TS doesn't; logic doesn't use them).

- 8 josa pairs (`은→는`, `이→가`, `과→와`, `이나→나`, `을→를`, `으로→로`, `이라→라`, `이랑→랑`); normalization map registers `key`, `value`, `(key)value` → canonical key.
- `pick` throws `Error('올바르지 않은 조사 지정')` on an unknown josa with empty `woJongsung`. **Match the message byte-for-byte.**
- `hasJongsung` branches: Hangul syllable `(code-0xAC00)%28` (jongsung 0 = no, jongsung 8 = ㄹ → false if `isRo`); compat jamo `ㄱ..ㅎ`; ASCII digits via `getDigitJongsung` ({0,3,6}=has, {1,7,8}=has+rieul, else=no); fallback = "not a vowel `{a,e,i,o,u,y}`" — **all CJK/hanja fall here → true**.
- `getLastChar` strips everything except ASCII alnum, compat jamo `ㄱ-ㅎ`, Hangul `가-힣`, and the **narrow** hanja range `一-廓` = **U+4E00..U+5ED3** (hanja above U+5ED3 are stripped, so the *preceding* char decides josa). Replicate the narrow range exactly.

**Color-tag renderer** (`app/game-api/src/battleSim/logFormatter.ts convertLog`): 14 sequential `replaceAll` in a fixed order (`<1>,<Y1>,<R>,<B>,<G>,<M>,<C>,<L>,<S>,<O>,<D>,<Y>,<W>,</>`). `<b>`/`<br>` pass through untouched. `type<=0` strips the same tags. Kotlin `String.replace(String,String)` = JS `replaceAll`; keep the order.

**`formatLogText`** prepends a status-icon prefix per `LogFormat` enum (ints 0–8 must match for serialization): `●`(U+25CF) for PLAIN/YEAR_MONTH/YEAR/MONTH, `◆`(U+25C6) for EVENT_*, `★`(U+2605) for NOTICE_*. Use explicit `\uXXXX` escapes + a codepoint assertion (editors silently substitute look-alikes).

**ActionLogger/UserLogger**: `flush()` and `rollback()` are **identical drains** (`splice(0,len)`). Truthiness divergences must be ported **per-branch, not normalized**: `pushNationHistoryLog` uses `!nationId` (drops 0); general-id attach uses `!== undefined` (keeps 0); `shouldDropEntry` uses `!entry.xId` (drops 0).

**Render pipeline** (for parity tests): logic builds raw text with inline `<C>...` markup + josa → `ActionLogger` buffers `LogEntryDraft` → `finalizeLogEntry` bakes the `formatLogText` icon prefix (markup stays raw) → `convertLog` converts tags to `<font ...>` at API/display time. Final golden = `convertLog(formatLogText(rawTextWithJosaAndTags, format, year, month))`.

**Confirmed parity hazard**: `che_물자원조.ts:160` calls `JosaUtil.pick(nationName, '부터')` — `'부터'` is NOT a map key and `woJongsung` is omitted → **`pick()` THROWS at runtime**. Kotlin must mirror whatever TS golden does (currently: throws). Resolve with the parity owner before golden freeze (see §4).

### 2.3 Constants + wire contract + InMemoryTurnWorld skeleton

**Constants** — three PHP static-const classes port as a **layered immutable config**:
- `GameConstBase.php` (~120 scalar/array balance fields + enumerated string sets + nested item catalog + command menus + name pools + **declarative event tables** `defaultInitialEvents`/`defaultEvents` — these are tuple-arrays-of-strings = **DATA, not code**).
- `GameUnitConstBase.php` — unit catalog; `getBuildData()` ~40 rows, each an **18-element positional tuple**; `reqConstraints` port as a sealed `UnitConstraint`; `attackCoef`/`defenceCoef` maps **mix armType ints (0–6) AND specific unit-ids** (e.g. 1106) — keep raw `Map<Int,Double>`, resolve specific-id before armType.
- `CityConstBase.php` — 94 cities; each a **13-element positional tuple**; stat fields stored `/100` and `×100` at build; region/level are labels → ints at build; bidirectional connectivity validated. (Per project memory: lv4 "이" = 이민족-only; han county-seats use lv5 "소".)

**World-load override**: `effectiveConst = baseGameConst.mergeWith(scenario.config.const)` — scenario `const` map overrides base scalars. `killturn`/`develcost` are **runtime-derived at scenario build** (`killturn = 4800/turnterm`, `npcmode==1 → /3`; `develcost = (year-startYear+10)*2`), NOT static constants.

**Wire contract** (`turnDaemon/types.ts`): `TurnDaemonState` (5 lowercase values), `TurnDaemonCommand` (~30 variants, discriminated on `type`), `TurnDaemonCommandResult` (discriminated on **`type` AND `ok`** — a naive `@JsonTypeInfo(property="type")` cannot split `...Ok` from `...Fail`; use kotlinx.serialization sealed + `@SerialName`, or a custom resolver), `TurnDaemonEvent`, and the **separate** `RealtimeEvent` channel (player-facing push vs daemon control plane). Enum strings serialize **camelCase/lowercase** (`"troopJoin"`, `"auctionFinalize"`, `"running"`).

**Redis stream keys**: `sammo:{profile}:turn-daemon:commands` / `:events`. Envelope JSON `{requestId, sentAt(ISO), command}` in a single field `payload`; `XADD '*'`; consumer `lastId` starts at `'$'` (only-new); `XREAD {BLOCK, COUNT:100}`.

**InMemoryTurnWorld**: 5 entity maps + dirty/created/deleted sets + `deletedNationSnapshots` + `logs`. `consumeDirtyState()` is **single-shot** (drains + clears). **Create-then-delete in same tick cancels** (`removeX` prunes `createdXIds`) — load-bearing or you INSERT-then-DELETE phantom rows. Getters return **defensive copies**; deep-merge nested `meta/stats/role/items/triggerState`.

**Flush order** (`databaseHooks.ts`, exact, in **1 transaction**): `worldState update → OldNation archive → createMany[gen,nation,troop,diplomacy] → deleteMany[troop, gen+rank, nation-cascade(diplomacy,nationTurn,nation)] → batch updates[gen,city,nation-upsert,troop,diplomacy] → rankData upsert(~40 rows/general) → logEntry createMany → reservedTurns.flush`. Mis-ordering nation deletion vs member-general nulling will FK-violate.

**Flush-exclusion / survive-truncate contract** (PHP `ResetHelper.php` is grand-truth): per-season reset DROPs game tables but **NEVER** touches `inheritance_{userId}` storage namespaces, hall-of-fame (`emperior`), or dynasty (`ng_games`). Only `storage namespace LIKE 'general_%'` is wiped; `game_env/betting/vote/next_execute` KV are `resetValues()`'d. **Model inheritance/storage + hall-of-fame + dynasty as a separate repository OUTSIDE the world+flush boundary from day one** (this is Risk #6, and accidental truncation is a silent data-loss bug invisible until a season reset).

### 2.4 Golden-fixture strategy (consolidated)

| Kernel | Oracle | Fixtures (frozen JSON, committed as test resources — generate once, never per-run) |
|---|---|---|
| **RNG** | TS `rng.test.ts` (already has SHA-512 stream vector for `'HelloWorld'` = 5×64-byte blocks, byte/bit/float + DummyBlockRNG cases) + PHP second oracle | Mirror TS test cases as-is first. Then a Node dump script over fixed seeds: stateIdx-0..4 raw blocks (hex); scripted mixed `nextBytes`/`nextBits`/`nextInt`/`nextFloat1` sequence; `shuffle(range(n))` for n∈{0,1,2,8,10,17}; `choice`/`choiceUsingWeight` over array/Set/Record. **Floats as raw 64-bit hex** (not printf). Add an alignment-stress fixture (>5 block refills, alternating `nextBits(7)`/`nextBytes(1)`/`nextInt(99)`). Add seed-string fixtures (incl. the `||game:`/`||extra:` tournament quirk). Cross-dump from PHP to confirm TS==PHP and document the `choiceUsingWeight` integer-key divergence. |
| **JosaUtil/log** | TS (`JosaUtil.ts`, `formatter.ts`, `logFormatter.ts`) | Node dump of `{input,args,expected}` triples covering every `hasJongsung` branch (Hangul±jongsung, ㄹ+isRo, compat jamo, digits, latin vowel/consonant, in-range hanja, trailing punctuation/space, empty/null, explicit-woJongsung bypass, all 8 josa families, normalization equivalence `pick(x,'로')==pick(x,'으로')`, and the `'부터'` **throw** case as `{throws,message}`). `convertLog` golden (every tag incl nested `<D><b>..</b></>`, type 1 & 0, `<b>`/`<br>` passthrough). `formatLogText` one case per `LogFormat` 0–8 with year=190/month=3. **End-to-end render golden** (real `logic/src` templates → formatLogText → convertLog). ActionLogger/UserLogger scripted push sequences (scalar+array, empty, id=0/null) → flush → `finalizeLogEntry` (drop-rule + baked icon checks). Kotlin tests diff against committed JSON. |
| **Constants** | PHP built catalogs (use `GetConst.php` API output as canonical) | Dump `GameUnitConst::all()` (all 18 fields incl resolved `info[]`), `CityConst::all()` after `_generate()` (×100 stats, int region/level, resolved `path{id:name}`), and reflected `GameConstBase` statics → JSON; diff field-by-field. (Highest port-hazard = the 18-/13-field positional tuples; round-trip `id→all-fields`.) |
| **Wire** | TS Zod schemas (`commandRegistry.ts`) | No runtime to diff shapes — hand-author one valid envelope per command/result type (~30), run through TS `normalizeTurnDaemonCommand` to confirm acceptance, then assert the Kotlin deserializer accepts identical JSON and re-serializes equivalently. Port the Zod schemas as Kotlin validators; diff accept/reject on a valid+malformed corpus. |
| **Stream/consumer** | string assertion + real-Redis integration | Assert `turnDaemonStreamKeys('default')` == the two exact keys. Integration: `XADD` a known envelope, run Kotlin consumer with `lastId='$'`, assert it reads exactly new messages and advances `lastId`. |
| **Flush** (highest value) | PHP devsam before/after DB dump (byte-identical per project memory; 4 tables change/tick) | Run one tick on PHP, dump general/city/nation/troop/diplomacy/rank_data/log + storage; run Kotlin flush on the same mutation set; diff row-by-row. **CRITICAL assertions**: storage rows `namespace NOT LIKE 'general_%'` (inheritance_*) IDENTICAL before/after; `emperior`/`ng_games` untouched. Instrument the Kotlin flush to log SQL op sequence and assert it matches the documented order; cover create+delete-in-same-tick. |

---

## 3. P1 Readiness — vertical slice

**Slice** = `che_상업투자` (commerce investment) **+** `che_농지개간` (land reclamation). The doc names only `che_농지개간` (§12); the probe shows in PHP **농지개간 is a 9-line subclass of 상업투자** (overrides only `cityKey=agri`, `statKey=intel`, `actionKey=농업`, `actionName=농지 개간`). Port **ONE shared algorithm** and instantiate both. (Doc adjustment: §12 should name the shared `상업투자` base, not just 농지개간.)

### 3.1 The TS-reference trap (HIGH risk — do NOT mirror TS here)

- `che_상업투자.ts` is a *faithful-ish* standalone port but **drops** PHP's `getIntel(injury, cross-stat +str/4, clamp)` (uses raw `stats.intelligence`), and its env hooks (`getDomesticExpLevelBonus`, `getCriticalRatio`, `getCriticalScoreMultiplier`, `adjustFrontDebuff`) are **optional and NOT wired** in the daemon path → as-shipped TS always picks 'normal' with `expBonus=1`.
- `che_농지개간.ts` does **NOT** extend `che_상업투자.ts` — it extends a **simplified `cityDevelopment.ts`** (fixed `+100`, no trust/crit/front-debuff, different log) — **unfaithful to PHP**.

**P1 must port the full PHP `상업투자` algorithm for BOTH commands** (Risk: HIGH; reaffirmed). This is the clearest case where the global "TS structure" rule must yield to PHP.

### 3.2 Minimal entity / constraint / action set

**Entities & fields read/written**:
- **General**: `nationId, cityId, stats.{intelligence,strength,leadership}, injury, gold, rice, experience, dedication, officerLevel, meta.{killturn, explevel, intel_exp, max_domestic_critical}, turnTime`.
- **City**: `id, nationId, level, commerce/commerceMax, agriculture/agricultureMax, supplyState, frontState, meta.{trust, region}`.
- **Nation**: `id, level, capitalCityId`.
- **Env**: `develCost, currentYear, startYear, relYear = year-startYear`.

**Constraints (exact order; short-circuit on first deny)** with PHP reason strings:
1. `notBeNeutral` — `nationId != 0` else `재야입니다.`
2. `notWanderingNation` — `nation.level != 0` else `방랑군은 불가능합니다.`
3. `occupiedCity` — `city.nationId == general.nationId` else `아국이 아닙니다.`
4. `suppliedCity` — `city.supplyState` truthy else `고립된 도시입니다.`
5. `reqGeneralGold(reqGold)` — `gold >= reqGold` else `자금이 모자랍니다.`
6. `reqGeneralRice(0)` — always pass else `군량이 모자랍니다.`
7. `remainCityCapacity(cityKey)` — `city[cityKey] < city[maxKey]` else `{actionName}은(는) 충분합니다.` — **use JosaUtil 은/는 to match PHP; TS hardcodes '이' — follow PHP.** FailString = `{reason} {commandName} 실패.`

(TS `cityDevelopment.ts` reorders `remainCityCapacity` BEFORE `reqGeneralGold` — another TS divergence to fix; follow PHP order.)

**Cost**: `reqGold = round(develCost)` (no triggers = identity `onCalcDomestic`); `reqRice=0`.

**Score → critical → front-debuff → mutation** (order matters):
- `trust = max(city.meta.trust, 50)` (single-arg `valueFit` = lower bound only).
- `baseStat = getIntel(withInjury, crossStat +round(str·injuryFactor/4), clamp 0..maxLevel)` then `onCalcStat`. **Port the PHP `getStatValue` logic** (MED risk — TS drops it).
- `score = baseStat · (trust/100) · (1+explevel/500) · nextRange(0.8,1.2)`; `score = max(score, 1)`.
- `CriticalRatioDomestic`: `avg=(L+S+I)/3`; `r=min(avg/intel,1.2)`; `fail=clamp(pow(r/1.2,1.4)-0.3,0,0.5)`; `success=clamp(pow(r/1.2,1.5)-0.25,0,0.5)`; if `trust<80: success *= trust/80`; `normal=1-fail-success`; `choiceUsingWeight({fail,success,normal})`; `mult = success?nextRange(2.2,3.0) : fail?nextRange(0.2,0.4) : 1`; `score = round(score·mult)`.
- `exp = score·0.7`, `ded = score·1.0` — computed from **POST-critical, PRE-front-debuff** score.
- Front debuff (applied to score AFTER exp/ded): if `frontState∈{1,3}`: `df=0.5`; if capital AND `relYear<25`: `scale=clamp(relYear-5,0,20)·0.05`; `df=scale·0.5+(1-scale)`; `score *= df`.
- Mutations: `city[cityKey] = clamp(city[cityKey]+score, 0, city[maxKey])`; `gold = max(0, gold-reqGold)`; `experience += exp`; `dedication += ded`; `meta.intel_exp += 1`; on success `max_domestic_critical += score/2` (meta + inheritance), else `=0`.

**Log strings** (`pushGeneralActionLog`, `scoreText = number_format(score,0)` **with comma grouping**, `josaUl` via JosaUtil, `<1>date</>`):
- fail: `{actionName}{을} <span class='ev_failed'>실패</span>하여 <C>{scoreText}</> 상승했습니다. <1>{date}</>`
- success: `{actionName}{을} <S>성공</>하여 <C>{scoreText}</> 상승했습니다. <1>{date}</>`
- normal: `{actionName}{을} 하여 <C>{scoreText}</> 상승했습니다. <1>{date}</>`

### 3.3 Exact call graph

```
[Next.js] → game-api precheck
  buildTurnCommandTable (game-api/src/turns/commandTable.ts):
    DB rows → entities → MemoryStateView + ConstraintContext{mode:'precheck'}
    → definition.buildConstraints + evaluateConstraints → status available/blocked/needsInput/unknown
ENQUEUE:
  setGeneralTurn (game-api/src/turns/reservedTurns.ts):
    write general_turn row (generalId, turnIdx, actionCode, arg JSON); ring buffer MAX=30, default '휴식'
DAEMON tick:
  turnDaemon → InMemoryTurnProcessor.run → world.executeGeneralTurn → reservedTurnHandler:
    resolveDefinition(action) → parseArgs → buildConstraintContext + WorldStateView
    → buildConstraints + evaluateConstraints  (deny/unknown → '휴식' fallbackDefinition + log deny reason)
    → buildRng(LiteHashDRBG, serializeSeed(seedBase, actionKey, year, month, generalId))
    → resolveGeneralAction(definition, ctx, schedule, args)
RESOLVE (packages/logic/src/actions/engine.ts):
  resolver inside immer produceWithPatches → mutates draft.general/city/nation + context.addLog(...)
  → collects effects, computes nextTurnAt, analyzes worldPatches → sets dirty{general,city,nation}
DIRTY/FLUSH:
  reservedTurnHandler → worldOverlay.syncGeneral/syncCity/syncNation
  → inMemoryWorld.consumeDirtyState() → databaseHooks.flushChanges:
       general.update(buildGeneralUpdate) + city.update(buildCityUpdate) + logEntry.createMany
SSE:
  turnDaemon publishEvents → redis.publish(realtimeChannel, {type:'turnCompleted', at, lastTurnTime})
  → game-api SSE fan-out → frontend refetch (coarse signal only)
```

**RNG draw order in PHP `run()`** (must match for byte-identical golden): `calcBaseScore` draws `nextRange(0.8,1.2)` FIRST → `choiceUsingWeight` draws once → `CriticalScoreEx` draws `nextRange` for mult. `tryUniqueItemLottery` uses a **separate** RNG (`genGenericUniqueRNGFromGeneral`). Seed = `serializeSeed(seedBase, actionKey, year, month, generalId)`.

### 3.4 Golden source

Use the existing devsam-core PHP capture harness (project memory: `j_install.php` twice, `scenario_0.json` seed, byte-identical dumps). Pick ONE general in an owned, supplied, **non-front** city with `commerce < commerceMax` and `gold >= round(develcost)`. Capture BOTH `상업투자` and `농지개간` turns, ideally one success + one normal + one fail. Golden artifacts per execution: general row before/after (gold, exp, ded, intel_exp, explevel, dedlevel, aux `max_domestic_critical`); city row before/after (commerce/agriculture; `*_max` unchanged); action log row(s) **char-for-char** (incl. `<C>/<S>/ev_failed/<1>date</>` tags + comma grouping); computed `reqGold`. Add a BLOCKED case (non-owned/unsupplied/insufficient-gold) to lock `getFailString()`. Assert only general+city+logEntry tables change (no spurious writes). Name fixtures `commerce_success/_normal/_fail`, `agri_normal`, `blocked_notSupplied`.

---

## 4. Consolidated open questions — resolve against PHP before locking P0-B / P1

**Must resolve before P0-B lock:**
1. **`che_물자원조` `'부터'` throw** — Is `JosaUtil.pick(nationName,'부터')` an accepted latent TS bug (golden encodes a throw) or should `che_물자원조` pass `woJongsung`? Kotlin must mirror whatever TS golden freezes. If TS is "fixed" before freeze, the Kotlin spec changes. (Also a live functional risk: a real turn triggering it would crash.)
2. **Parity grading stage** — Does the gate compare RAW stored log text (with `<C>` markup, pre-`convertLog`) or FINAL rendered HTML (post-`convertLog`)? `finalizeLogEntry` stores raw; `convertLog` runs at API layer. Recommend grading BOTH as separate fixtures.
3. **Wire-contract scope** — TS `TurnDaemonCommand` has ~30 variants (auctionBid/voteReward/tournament*) the PHP doesn't expose as daemon commands. Is the P0-B parity target the full TS union or only the slice's subset? Recommend mirroring the full TS union for forward-compat but only WIRING the handlers the slice uses.
4. **`choiceUsingWeight` integer-key order** — confirm P0-B weighted-choice fixtures are generated from **TS** (JS numeric-key-first), not PHP (insertion). Any existing PHP weighted-choice goldens over integer-keyed maps would disagree.
5. **`ScenarioConfig.const` overridable key set** — `const: Record<string,unknown>` is untyped. Inspect `parseScenario.ts` + scenario JSON to enumerate which `GameConst` fields are scenario-overridable vs fixed (decides flat-map vs typed-class+override-layer).
6. **`inheritance/storage` + hall-of-fame + dynasty repository boundary** — confirm the Kotlin model places these OUTSIDE the world+flush boundary (Risk #6). Architectural, must be decided at P0.
7. **Redis client** (Lettuce vs Jedis vs Spring Data Redis) — determines the `XREAD` return-type adapter for the consumer skeleton.
8. **EventRow handler dispatch & reserved-turn store scope** — events model as inert data in P0-B; confirm the handler registry and `InMemoryReservedTurnStore.flushChanges` are P1 (referenced by `databaseHooks` options).

**Must resolve before P1 lock:**
9. **`GameConst.maxLevel`** for the che scenario (stat-clamp upper bound; likely 150 — confirm).
10. **`Util::round` semantics** — plain PHP `round()` (half-away-from-zero) or custom? Affects `reqGold` + `score`. (For positive values, half-up == half-away-from-zero, but confirm.) `number_format` comma grouping must appear in log strings.
11. **`RandUtil.choiceUsingWeight` exact draw consumption + iteration order** of `{fail,success,normal}` — must byte-match the pick (one `nextFloat1`, normalization, key order).
12. **Reserved command payload transport** — Does P1 route the command through Redis (task statement says "api precheck → redis → daemon") or through the DB `general_turn` ring buffer (what TS does; Redis only for control + realtime)? Confirm P1 design intent.
13. **`intel_exp` persistence** — is it a persisted `general` column in the P0 Flyway baseline (PHP: column; TS: meta JSON)? Decides the Kotlin General entity shape.
14. **P5 gate vs P6 diplomacy resolvers** — decide scoped-down P5 gate vs pulling `che_불가침제의`/`che_선전포고` accept-path resolvers into P5 (§1c).
15. **`buildDiplomacyKey` format** — directed `from:to` vs ordered `min:max`, and default diplomacy state — before porting diplomacy maps (P6, but the key format surfaces in `consumeDirtyState`).
16. **`tryUniqueItemLottery` + `StaticEventHandler` no-op confirmation** for the scenario_0 default general (so they can be stubbed in P1 without perturbing RNG state for later turns).

**Already in the doc's §14 (reaffirmed)**: preUpdate/postUpdate/turnDate side-effect order (P3), auction schedule (P6), per-map CityConst delta (P3/P0 seed), 23 missing-command PHP behavior (P2/P8 — but **re-prioritize the arsort-touching ones to P4**), 9-source action-stack merge/cache rules (P1/P2).

---

## 5. Top risks — reaffirmed + newly found

**Reaffirmed from the doc** (with sharper grounding):
- **R1 RNG byte-parity (single gating risk)** — confirmed. Endianness (stateIdx LE / u64 LE), `nextInt` inclusive-max off-by-one, `baseBytes` "pad-not-draw" semantics, float bit-exactness, and the JS integer-key order are the specific silent-break vectors. Mitigated by the SHA-512 stream vector + shuffle/choice goldens.
- **R2 dual-StateView CQRS drift** — confirmed, plus the **data-freshness skew** addition (§1a): deny-at-execution is by-design, not a bug.
- **R3 Korean log byte-parity** — confirmed, with the **mis-source trap** (port TS not PHP) and the per-branch truthiness divergences as concrete sub-risks.
- **R4 Immer absence** — confirmed; the explicit change-recorder must be the *only* dirty source (ties to the new R-d below).
- **R6 season-reset state loss** — confirmed; the storage/inheritance/hall-of-fame/dynasty repository must sit outside the flush boundary from day one (a silent data-loss bug).
- **R8 PHP float/rounding/order** — confirmed, **sharpened**: the `arsort` tie-break is not a solved "ordered-map" but a TS-vs-PHP **porting-policy contradiction** that must be resolved PHP-ward (§1b).

**Newly found:**
- **N1 (high) — `arsort`/stable-sort porting contradiction** (§1b). The global "TS structure + PHP tiebreaker" rule is internally inconsistent for the conquest conflict map; must invert to PHP `arsort` semantics for that path, with a dedicated equal-contribution-ties golden built from PHP.
- **N2 (high) — P1 농지개간 TS-fidelity trap** (§3.1). Mirroring TS would port the simplified `+100` path and break PHP parity; both slice commands must use the full PHP `상업투자` algorithm (incl. dropped `getIntel`).
- **N3 (med) — P5 verification-ordering cycle** (§1c). P5's full-game-replay gate implicitly requires P6 diplomacy resolvers; the gate is not closable at P5 as written.
- **N4 (med) — daemon JPA write-path** (§1d). Without an explicit "daemon never uses EntityManager for writes" rule + a P0 architectural test, JPA dirty-checking and the change-recorder become two competing dirty sources.
- **N5 (med) — mid-turn checkpoint flush has no PHP golden equivalent** (§1e). The parity harness must dump only at turn/month boundaries; replay runs must gate partials on processed-count, not wall clock.
- **N6 (med) — no catch-up / daemon-density bound** (§1e). `catchUpCap=1` + single-thread gives no recovery-time bound for extended downtime, and multiple profiles per t3.large is unaddressed.
- **N7 (med) — risky 23 missing commands deferred to last** (§1 cross-cutting). 건국/방랑/수도이전/무작위건국 all use `arsort` — the riskiest parity surface is currently scheduled for the P8 backlog; pull alongside P4.
- **N8 (low–med) — `Util::round` / `number_format` rounding + comma grouping** (§3, §4 Q10). Off-by-one and missing thousands-commas will fail char-for-char log diffs.
- **N9 (low) — `che_물자원조` `'부터'` runtime throw** (§2.2, §4 Q1) — a live functional crash, not just a test concern; needs a decision before P1 wiring.

---

## 6. Verdicts

- **Architecture**: sound and unusually well-grounded for a parity-driven rewrite (reverse-engineered from a working TS reference that already proves the hardest premises). No structural rework needed.
- **P0-B**: **READY to turn into a plan** after applying the §1b (arsort), §1d (daemon JPA write-path), and §1e (mid-turn flush / catch-up) spec edits, and resolving open questions 1–8. The three kernel specs (RNG, JosaUtil/log, constants/wire/world) are implementation-ready with named golden strategies.
- **P1**: **READY to turn into a plan** after pinning the §3.1 porting decision (full PHP `상업투자` for both commands) and resolving open questions 9–13, 16. The call graph, entity/constraint/action set, and golden source are fully specified.
