package opensamguk.engine.golden

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import opensamguk.common.rng.LiteHashDrbg
import opensamguk.common.rng.RandUtil
import opensamguk.engine.turn.AiTurnAdapter
import opensamguk.engine.turn.City
import opensamguk.engine.turn.GeneralStats
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.Nation
import opensamguk.engine.turn.TurnDiplomacy
import opensamguk.engine.turn.TurnGeneral
import opensamguk.engine.turn.TurnWorldState
import opensamguk.engine.turn.WorldSnapshot
import opensamguk.infra.persistence.ReservedTurnRepository.ReservedTurn
import opensamguk.logic.actions.CommandRegistry
import opensamguk.logic.ai.ChosenCommand
import opensamguk.logic.domain.LastTurn
import opensamguk.logic.stats.GeneralActionPipeline
import java.time.Instant
import java.time.format.DateTimeFormatter
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * P5 GT3 — the ENGINE-SIDE **live-selection** AI gate (the REAL selection-parity gate, not the kernel replay).
 *
 * The prior `:logic` `AiReplayGateTest` only RE-ISSUES the golden's recorded draws on a fresh DRBG (proving the
 * RNG kernel reproduces the byte stream). THIS gate runs the LIVE Kotlin AI ([AiTurnAdapter.chooseNationTurn] /
 * [AiTurnAdapter.chooseGeneralTurn]) over the REAL scenario-1010 pre-turn world ([world-1010.json], GT1b) and
 * asserts it PULLS the same draws + PICKS the same command as the PHP golden — the actual AI selection logic.
 *
 * ## What it does, per golden due-general turn (in the EXACT executeGeneralCommandUntil order — the fixture
 *    file index 000..173 IS that order: `turntime ASC, no ASC`):
 *  1. Materialises [world-1010.json] into ONE [InMemoryTurnWorld] (174 generals PK-asc, 94 cities, 2 nations,
 *     diplomacy, nation_env) — the exact pre-turn state — built ONCE and shared across all 174 turns (the AI is
 *     READ-ONLY over game entities, so iterating every due general against ONE pristine snapshot is faithful,
 *     exactly as the PHP capture does — see capture_ai.php header).
 *  2. Builds the live [AiTurnAdapter] with an [AiDrawRecorder]-wrapping rng factory keyed to the golden's
 *     `seedString` (so the LIVE AI's SOLE `"GeneralAI"` rng is observed draw-for-draw). For a lord
 *     (`nation!=0 && officer_level>=5`) it runs `chooseNationTurn` FIRST (the stream PREFIX, count pinned by
 *     `nationTurn.drawCountAtNationEnd`), THEN `chooseGeneralTurn` on the SAME shared rng — exactly the PHP
 *     single-`GeneralAI`-per-general semantics.
 *  3. Asserts draw-for-draw vs the golden at the FIRST divergence: the chosen general `(actionCode RAW, args)` +
 *     `reason` (and the nation `(actionCode, args, reason)` for lords) AND the LIVE-pulled draw stream
 *     (method+args+result, value AND stateIdx/bufferIdx cursor + `consumed`) + the draw COUNT + the
 *     nation/general split.
 *
 * ## Quarantines honored (per manifest_ai.json + GATE-DIVERGENCES.md)
 *  - **Q1 / ORDER BY RAND** (do선양 npc==5 / 오랑캐임관 npc==9 lord): UNREACHABLE in 1010 (census 0/0) — never
 *    fires in this window, nothing to exclude.
 *  - **AI-QUAR-INSTANTNATIONTURN**: `chooseInstantNationTurn` has no live call-site → not exercised.
 *  - **Diplomacy downstream delta (m10)**: the month-1 window does not reach 불가침제의/선전포고/천도; only
 *    SELECTION + draw stream are asserted here regardless (the gate never inspects resolved downstream delta).
 *
 * On a mismatch the test fails with the FIRST divergent turn — the golden is grand truth, NEVER weakened. Every
 * divergence is tallied into a cluster summary (by `reason` + cause) printed on failure, and the precise
 * remaining-divergence catalog is maintained in `.context/p5-research/GATE-DIVERGENCES.md`.
 */
class AiSelectionGateIT {

    private companion object {
        const val FIXTURE_COUNT = 174
        const val WORLD = "golden/p5/world-1010.json"
        val ISO_MICROS: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss[.SSSSSS]")
        const val MAX_REPORTED = 25
    }

    private fun loadResource(name: String): String =
        AiSelectionGateIT::class.java.classLoader.getResourceAsStream(name)!!.readBytes().toString(Charsets.UTF_8)

    private fun loadFixture(idx: Int): JsonObject =
        Json.parseToJsonElement(loadResource("golden/p5/ai-turn-%03d.json".format(idx))).jsonObject

    private val worldJson: JsonObject by lazy { Json.parseToJsonElement(loadResource(WORLD)).jsonObject }

    private fun loadWorld(name: String): JsonObject = Json.parseToJsonElement(loadResource(name)).jsonObject

    private val pipeline = GeneralActionPipeline()
    private val registry = CommandRegistry(pipeline)

    // ── the gate ───────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `live AI selection draw-for-draw over the scenario-1010 world snapshot`() {
        val meta = worldJson["meta"]!!.jsonObject
        val hiddenSeed = meta["hiddenSeed"]!!.jsonPrimitive.content
        val startYear = meta["startYear"]!!.jsonPrimitive.int
        val year = meta["year"]!!.jsonPrimitive.int
        val month = meta["month"]!!.jsonPrimitive.int
        val turnterm = meta["turnterm"]!!.jsonPrimitive.int

        // (1) materialise the pre-turn world ONCE (read-only over game entities, shared across all turns).
        val world = materializeWorld(worldJson, year, month, turnterm)
        // S2 — the per-general reserved-command name (turn_idx 0) lookup the categorize buckets read.
        val reservedNameOf = buildReservedNameLookup(worldJson)

        // (2) the recorder factory — wraps the SAME-seeded DRBG so the LIVE AI's rng is observed. We capture
        // the recorder per (general, year, month) so the gate can read the pulled stream after the live run.
        val recorders = LinkedHashMap<Int, AiDrawRecorder>()
        val rngFactory: (String, Int, Int, Int) -> RandUtil = { hidden, y, m, gid ->
            val rec = AiDrawRecorder(LiteHashDrbg(seedStringFor(hidden, y, m, gid)))
            recorders[gid] = rec
            rec
        }

        val adapter = AiTurnAdapter(
            world = world,
            registry = registry,
            hiddenSeed = hiddenSeed,
            startYear = startYear,
            turnTerm = turnterm,
            pipeline = pipeline,
            reservedCommandNameOf = reservedNameOf,
            rngFactory = rngFactory,
        )

        var matched = 0
        var liveDrawsVerified = 0
        var goldenDrawsTotal = 0
        val clusters = LinkedHashMap<String, Int>()
        val firstFailures = ArrayList<String>()

        for (idx in 0 until FIXTURE_COUNT) {
            val f = loadFixture(idx)
            val gid = f["generalId"]!!.jsonPrimitive.int
            recorders.remove(gid)
            adapter.resetRngFor(gid) // bound this due-general's nation+general decision window (fresh stream).
            // COLLAPSED counts (weighted-wrappers dropped on both sides — see [collapseGolden]/[collapseLive]).
            goldenDrawsTotal += collapseGolden(f["drawStream"]!!.jsonArray.map { it.jsonObject }).size

            val div = runOneTurn(adapter, recorders, f, idx, gid, quarantine = null)
            if (div == null) {
                matched += 1
                liveDrawsVerified += collapseLive(recorders[gid]?.drawStream() ?: emptyList()).size
            } else {
                clusters[div.cluster] = (clusters[div.cluster] ?: 0) + 1
                if (firstFailures.size < MAX_REPORTED) firstFailures.add(div.message)
            }
        }

        // NON-VACUOUS proof: print the matched count + the live draws actually pulled+verified (the golden
        // window is 667 draws total). A green gate with 0 live draws would be vacuous — assert real draws ran.
        println(
            "[AiSelectionGateIT] matched=$matched/$FIXTURE_COUNT  liveDrawsVerified=$liveDrawsVerified  " +
                "goldenDrawsTotal=$goldenDrawsTotal",
        )

        if (matched == FIXTURE_COUNT) {
            assertTrue(
                liveDrawsVerified == goldenDrawsTotal,
                "all turns matched but live draws ($liveDrawsVerified) != golden draws ($goldenDrawsTotal) — vacuous match guard",
            )
        }

        if (matched != FIXTURE_COUNT) {
            val summary = buildString {
                append("Live AI selection gate: $matched / $FIXTURE_COUNT due-general turns matched ")
                append("(selection + draw stream value+cursor).\n")
                append("Divergence clusters (reason:cause = count): ")
                append(clusters.entries.sortedByDescending { it.value }.joinToString { "${it.key}=${it.value}" })
                append("\nFirst failures (≤$MAX_REPORTED):\n")
                append(firstFailures.joinToString("\n"))
            }
            throw AssertionError(summary)
        }
        assertTrue(matched == FIXTURE_COUNT, "all $FIXTURE_COUNT turns matched")
    }

    // ── the CRAFTED-fixture gate (GT2 under-covered families) ────────────────────────────────────────────

    /**
     * The crafted families (GT2) — each FAITHFULLY mutates the SAME GT1 install (snapshot/restore returns the
     * DB to the year-181-month-1 baseline; the hiddenSeed is preserved) to reach a `do<한글>` the pinned
     * month-1 1010 window cannot exercise. Each entry: family → its banked `world-crafted-<fam>.json` snapshot +
     * the `ai-crafted-<fam>-NN.json` per-due-general goldens. (genfound-방랑군 is BACKLOGGED — see
     * GATE-DIVERGENCES.md: it needs the che_거병→건국 lifecycle to SEED the wandering-army nation_env, which the
     * installer never presents; fabricating that KV would violate parity law. Not blocked on here.)
     */
    private val craftedFamilies: List<CraftedFamily> = listOf(
        CraftedFamily("diplo-불가침제의", listOf(0)),
        CraftedFamily("diplo-선전포고", listOf(0)),
        CraftedFamily("diplo-천도", listOf(0)),
        CraftedFamily("war-출병", listOf(0)),
        CraftedFamily("genfound-선양", listOf(0)),
        CraftedFamily("nation-pass-m3", listOf(0, 1)),
        CraftedFamily("nation-pass-m6", listOf(0, 1)),
        CraftedFamily("nation-pass-m12", listOf(0, 1)),
    )

    private data class CraftedFamily(val name: String, val indices: List<Int>)

    /**
     * P5 GT2-LIVE — replay every crafted golden over its OWN `world-crafted-<fam>.json` snapshot through the
     * LIVE [AiTurnAdapter], asserting the chosen `(actionCode, RAW args, reason)` + the live-pulled draw stream
     * value+cursor+count — the FIRST real golden verification of the under-covered families (diplo SELECTION,
     * the war-state do출병, the genfound 선양, the month-3/6/9/12 nation pass).
     *
     * Per family: materialise its crafted world (at the MUTATED clock, e.g. 184/1 for the diplo+war families,
     * 181/{3,6,12} for the nation-pass-month families) into ONE [InMemoryTurnWorld], then run each due general
     * exactly like the main gate (nation pass → general pass on the SAME shared rng).
     *
     * Quarantines honored (GATE-DIVERGENCES.md):
     *  - **Q1 / ORDER BY RAND (genfound-선양)**: the golden's `che_선양 destGeneralID` is the sentinel
     *    `__ORDER_BY_RAND_QUARANTINED__` (NOT a DRBG draw — MySQL `RAND()`); the gate asserts the LIVE
     *    `destGeneralID ∈ _q1ValidTargets` (the deterministic `min(no)` substitute MUST land in the valid set),
     *    never byte-equality with the sentinel.
     *  - **Diplomacy m10**: the diplo families assert SELECTION + the boolean/short-circuit draw stream ONLY;
     *    the downstream che_불가침제의/선전포고/천도 resolved delta is NOT inspected (no P2-P4 resolver in scope).
     */
    @Test
    fun `live AI selection draw-for-draw over the GT2 crafted world snapshots`() {
        val clusters = LinkedHashMap<String, Int>()
        val firstFailures = ArrayList<String>()
        var matched = 0
        var total = 0
        var liveDrawsVerified = 0
        var goldenDrawsTotal = 0
        val familiesMatched = LinkedHashMap<String, Boolean>()

        for (fam in craftedFamilies) {
            val wj = loadWorld("golden/p5/world-crafted-${fam.name}.json")
            val meta = wj["meta"]!!.jsonObject
            val hiddenSeed = meta["hiddenSeed"]!!.jsonPrimitive.content
            val startYear = meta["startYear"]!!.jsonPrimitive.int
            val year = meta["year"]!!.jsonPrimitive.int
            val month = meta["month"]!!.jsonPrimitive.int
            val turnterm = meta["turnterm"]!!.jsonPrimitive.int

            val world = materializeWorld(wj, year, month, turnterm)
            val reservedNameOf = buildReservedNameLookup(wj)

            val recorders = LinkedHashMap<Int, AiDrawRecorder>()
            val rngFactory: (String, Int, Int, Int) -> RandUtil = { hidden, y, m, gid ->
                val rec = AiDrawRecorder(LiteHashDrbg(seedStringFor(hidden, y, m, gid)))
                recorders[gid] = rec
                rec
            }
            val adapter = AiTurnAdapter(
                world = world,
                registry = registry,
                hiddenSeed = hiddenSeed,
                startYear = startYear,
                turnTerm = turnterm,
                pipeline = pipeline,
                reservedCommandNameOf = reservedNameOf,
                rngFactory = rngFactory,
            )

            var famOk = true
            for (i in fam.indices) {
                total += 1
                val f = loadCrafted(fam.name, i)
                val gid = f["generalId"]!!.jsonPrimitive.int
                recorders.remove(gid)
                adapter.resetRngFor(gid)
                goldenDrawsTotal += collapseGolden(f["drawStream"]!!.jsonArray.map { it.jsonObject }).size

                val div = runOneTurn(adapter, recorders, f, i, gid, quarantine = q1ValidTargets(f))
                if (div == null) {
                    matched += 1
                    liveDrawsVerified += collapseLive(recorders[gid]?.drawStream() ?: emptyList()).size
                } else {
                    famOk = false
                    clusters[div.cluster] = (clusters[div.cluster] ?: 0) + 1
                    if (firstFailures.size < MAX_REPORTED) firstFailures.add("[${fam.name}] ${div.message}")
                }
            }
            familiesMatched[fam.name] = famOk
        }

        println(
            "[AiSelectionGateIT/crafted] families matched=${familiesMatched.count { it.value }}/${familiesMatched.size} " +
                "turns matched=$matched/$total  liveDrawsVerified=$liveDrawsVerified  goldenDrawsTotal=$goldenDrawsTotal\n" +
                "  per-family: " + familiesMatched.entries.joinToString { "${it.key}=${if (it.value) "OK" else "FAIL"}" },
        )

        if (matched == total) {
            assertTrue(
                liveDrawsVerified == goldenDrawsTotal,
                "all crafted turns matched but live draws ($liveDrawsVerified) != golden draws ($goldenDrawsTotal) — vacuous guard",
            )
        }
        if (matched != total) {
            val summary = buildString {
                append("Crafted live-selection gate: $matched / $total crafted due-general turns matched.\n")
                append("Per-family: ${familiesMatched.entries.joinToString { "${it.key}=${if (it.value) "OK" else "FAIL"}" }}\n")
                append("Divergence clusters: ${clusters.entries.sortedByDescending { it.value }.joinToString { "${it.key}=${it.value}" }}\n")
                append("First failures (≤$MAX_REPORTED):\n${firstFailures.joinToString("\n")}")
            }
            throw AssertionError(summary)
        }
        assertTrue(matched == total, "all $total crafted turns matched")
    }

    private fun loadCrafted(family: String, idx: Int): JsonObject =
        Json.parseToJsonElement(loadResource("golden/p5/ai-crafted-$family-%02d.json".format(idx))).jsonObject

    /**
     * The Q1 quarantine for the do선양 family: the golden's `che_선양 destGeneralID` is the
     * `__ORDER_BY_RAND_QUARANTINED__` sentinel (a MySQL `RAND()` pick, NOT a DRBG draw). When present, return
     * the banked valid-candidate set so the gate asserts the LIVE deterministic `min(no)` id ∈ that set
     * (membership, NOT byte-equality). Returns null for every non-quarantined family (plain byte-diff).
     */
    private fun q1ValidTargets(f: JsonObject): Set<Int>? {
        val raw = f["chosenRawArgs"]?.takeIf { it !is JsonNull }?.jsonObject ?: return null
        val dest = raw["destGeneralID"]?.jsonPrimitive?.contentOrNull
        if (dest != "__ORDER_BY_RAND_QUARANTINED__") return null
        return f["_q1ValidTargets"]!!.jsonArray.map { it.jsonPrimitive.int }.toSet()
    }

    private data class Divergence(val cluster: String, val message: String)

    /**
     * Run ONE due-general turn live and diff against the golden. Returns null on full match, else the FIRST
     * divergence. Runs the nation pass (lords) then the general pass on the SAME shared rng (PHP semantics).
     */
    private fun runOneTurn(
        adapter: AiTurnAdapter,
        recorders: Map<Int, AiDrawRecorder>,
        f: JsonObject,
        idx: Int,
        gid: Int,
        quarantine: Set<Int>?,
    ): Divergence? {
        val reason = f["reason"]?.jsonPrimitive?.contentOrNull ?: "?"
        val nationTurn = f["nationTurn"]?.takeIf { it !is JsonNull }?.jsonObject
        val tag = "ai-turn-%03d(gid=$gid reason=$reason)".format(idx)

        // ── nation pass FIRST for lords (officer_level>=5) — runs ONCE on the shared rng (the stream PREFIX) ──
        if (nationTurn != null) {
            val reservedAction = nationTurn["reservedAction"]?.jsonPrimitive?.contentOrNull ?: "휴식"
            val nc: ChosenCommand = try {
                adapter.chooseNationTurn(gid, ReservedTurn(reservedAction, ""), LastTurn())
            } catch (e: Throwable) {
                return Divergence("$reason:nation-throw", "$tag chooseNationTurn THREW: ${e.message} ${e.stackTrace.firstOrNull()}")
            }
            val expCode = nationTurn["chosenActionCode"]!!.jsonPrimitive.content
            val expReason = nationTurn["reason"]?.jsonPrimitive?.contentOrNull ?: ""
            val drawAtNationEnd = nationTurn["drawCountAtNationEnd"]?.jsonPrimitive?.intOrNull
            if (nc.actionCode != expCode) {
                return Divergence(
                    "$reason:nation-code",
                    "$tag NATION action mismatch — golden=$expCode live=${nc.actionCode} (reason golden=$expReason live=${nc.reason})",
                )
            }
            if (nc.reason != expReason) {
                return Divergence(
                    "$reason:nation-reason",
                    "$tag NATION reason mismatch — golden=$expReason live=${nc.reason} (code $expCode)",
                )
            }
            // Canonicalize the LIVE nation args through the command def's argTest/parseArgs — the PHP capture
            // records `$chosenNation->getArg()` (the POST-argTest arg), so e.g. che_포상 rounds `amount` to the
            // nearest 100 (Util::round(-2)) then clamps to [100, maxResourceActionAmount]. Comparing the raw AI
            // emit (the un-rounded geomean 1235.6) against the canonical golden (1200) would false-fail; the
            // canonical arg IS the parity target (SAME single canonicalization the general pass applies, :409).
            val canonicalNation = canonicalizeArgs(nc.actionCode, nc.args)
            val nationArgDiv = diffArgs("$tag NATION", nationTurn["chosenRawArgs"], canonicalNation, "$reason:nation-args")
            if (nationArgDiv != null) return nationArgDiv
            if (drawAtNationEnd != null) {
                // Collapse the weighted-wrappers on BOTH sides (see [collapseGolden]/[collapseLive]): the PHP
                // recorder logs a `choiceUsingWeight` wrapper (do선전포고 target pick) that the live
                // `choiceUsingWeightPair` recorder mirrors; both ride on an inner nextFloat1 (zero extra bytes).
                val goldenPrefix = f["drawStream"]!!.jsonArray.take(drawAtNationEnd).map { it.jsonObject }
                val goldenCount = collapseGolden(goldenPrefix).size
                val liveCount = collapseLive(recorders[gid]?.drawStream() ?: emptyList()).size
                if (liveCount != goldenCount) {
                    return Divergence(
                        "$reason:nation-drawcount",
                        "$tag NATION drawCountAtNationEnd mismatch — golden=$goldenCount (raw $drawAtNationEnd) live=$liveCount",
                    )
                }
            }
        }

        // ── general pass (always; continues the SAME shared rng stream) ──
        val reservedAction = f["reservedAction"]?.jsonPrimitive?.contentOrNull ?: "휴식"
        val chosen: ChosenCommand = try {
            adapter.chooseGeneralTurn(gid, ReservedTurn(reservedAction, ""))
        } catch (e: Throwable) {
            return Divergence("$reason:general-throw", "$tag chooseGeneralTurn THREW: ${e.message} ${e.stackTrace.firstOrNull()}")
        }

        val expCode = f["chosenActionCode"]!!.jsonPrimitive.content
        if (chosen.actionCode != expCode) {
            return Divergence(
                "$reason:code",
                "$tag GENERAL action mismatch — golden=$expCode live=${chosen.actionCode} (reason golden=$reason live=${chosen.reason})",
            )
        }
        if (chosen.reason != reason) {
            return Divergence(
                "$reason:reason",
                "$tag GENERAL reason mismatch — golden=$reason live=${chosen.reason} (code $expCode)",
            )
        }
        // Canonicalize the LIVE args through the command def's argTest/parseArgs — the PHP capture records
        // `$chosenGeneral->getArg()` (the POST-argTest arg), so e.g. che_헌납 clamps `amount` to
        // GameConst::$maxResourceActionAmount. Comparing the raw AI emit (the un-clamped 996900) against the
        // canonical golden (10000) would false-fail; the canonical arg IS the parity target.
        val canonicalGeneral = canonicalizeArgs(chosen.actionCode, chosen.args)
        if (quarantine != null) {
            // Q1 / ORDER BY RAND (do선양): the golden destGeneralID is the un-replayable MySQL RAND() sentinel.
            // Assert the LIVE deterministic min(no) substitute lands in the banked valid-candidate set
            // (membership, NOT byte-equality) — every OTHER emitted arg still byte-diffs against the golden.
            val liveDest = (canonicalGeneral["destGeneralID"] as? Number)?.toInt()
            if (liveDest == null || liveDest !in quarantine) {
                return Divergence(
                    "$reason:q1-destGeneral-out-of-set",
                    "$tag GENERAL Q1 destGeneralID live=$liveDest NOT in valid-candidate set $quarantine",
                )
            }
            // diff the remaining args (drop the quarantined dest key on both sides).
            val goldenOther = stripKey(f["chosenRawArgs"], "destGeneralID")
            val liveOther = canonicalGeneral.filterKeys { it != "destGeneralID" }
            val argDiv = diffArgs("$tag GENERAL", goldenOther, liveOther, "$reason:args")
            if (argDiv != null) return argDiv
        } else {
            val argDiv = diffArgs("$tag GENERAL", f["chosenRawArgs"], canonicalGeneral, "$reason:args")
            if (argDiv != null) return argDiv
        }

        // ── draw stream value + cursor + count (the WHOLE shared stream: nation prefix + general) ──
        val rec = recorders[gid]
            ?: return Divergence("$reason:no-recorder", "$tag no recorder captured (the live AI built no rng?)")
        val golden = f["drawStream"]!!.jsonArray.map { it.jsonObject }
        val live = rec.drawStream()
        val streamDiv = diffStream(tag, reason, golden, live)
        if (streamDiv != null) return streamDiv

        return null
    }

    // ── world materialisation ─────────────────────────────────────────────────────────────────────────

    private fun materializeWorld(wj: JsonObject, year: Int, month: Int, turnterm: Int): InMemoryTurnWorld {
        val gameEnv = wj["gameEnv"]!!.jsonObject
        val initYear = gameEnv["init_year"]?.jsonPrimitive?.intOrNull ?: year
        val initMonth = gameEnv["init_month"]?.jsonPrimitive?.intOrNull ?: 1

        val nationEnvByNation = LinkedHashMap<Int, Map<String, Any?>>()
        for (ne in wj["nationEnv"]!!.jsonArray.map { it.jsonObject }) {
            val nid = ne["nation"]!!.jsonPrimitive.int
            val kv = ne["kv"]?.takeIf { it !is JsonNull }?.jsonObject
            nationEnvByNation[nid] = kv?.let { decodeObject(it) } ?: emptyMap()
        }

        val generals = wj["generals"]!!.jsonArray.map { toGeneral(it.jsonObject) }.sortedBy { it.id }
        val cities = wj["cities"]!!.jsonArray.map { toCity(it.jsonObject) }.sortedBy { it.id }
        val nations = wj["nations"]!!.jsonArray.map { toNation(it.jsonObject, nationEnvByNation, initYear, initMonth) }
        val diplomacy = wj["diplomacy"]!!.jsonArray.map { toDiplomacy(it.jsonObject) }

        val state = TurnWorldState(
            id = 1,
            currentYear = year,
            currentMonth = month,
            tickSeconds = turnterm * 60,
            lastTurnTime = parseTurnTime(gameEnv["turntime"]?.jsonPrimitive?.contentOrNull),
            meta = linkedMapOf(
                "killturn" to (gameEnv["killturn"]?.jsonPrimitive?.intOrNull ?: 0),
                "isunited" to (gameEnv["isunited"]?.jsonPrimitive?.intOrNull ?: 0),
            ),
        )
        return InMemoryTurnWorld(WorldSnapshot(state, generals, cities, nations, emptyList(), diplomacy))
    }

    private fun buildReservedNameLookup(wj: JsonObject): (Int) -> String? {
        val byGeneral = LinkedHashMap<Int, String?>()
        for (gt in wj["generalTurns"]!!.jsonArray.map { it.jsonObject }) {
            if (gt["turn_idx"]?.jsonPrimitive?.intOrNull != 0) continue
            val gidv = gt["general_id"]!!.jsonPrimitive.int
            byGeneral[gidv] = gt["action"]?.jsonPrimitive?.contentOrNull
        }
        return { gid -> byGeneral[gid]?.let { if (it == "휴식") "che_휴식" else if (it.startsWith("che_")) it else "che_$it" } }
    }

    private fun toGeneral(o: JsonObject): TurnGeneral {
        val meta = decodeObject(o).toMutableMap()
        // remove the typed columns from meta to keep the bag = the non-typed remainder + verbatim extras
        // (we still keep dex/aux/rank-ish keys the adapter reads).
        return TurnGeneral(
            id = o["no"]!!.jsonPrimitive.int,
            name = o["name"]?.jsonPrimitive?.contentOrNull ?: "",
            nationId = o["nation"]?.jsonPrimitive?.intOrNull ?: 0,
            cityId = o["city"]?.jsonPrimitive?.intOrNull ?: 0,
            troopId = o["troop"]?.jsonPrimitive?.intOrNull ?: 0,
            stats = GeneralStats(
                leadership = o["leadership"]?.jsonPrimitive?.intOrNull ?: 0,
                strength = o["strength"]?.jsonPrimitive?.intOrNull ?: 0,
                intelligence = o["intel"]?.jsonPrimitive?.intOrNull ?: 0,
            ),
            experience = o["experience"]?.jsonPrimitive?.intOrNull ?: 0,
            dedication = o["dedication"]?.jsonPrimitive?.intOrNull ?: 0,
            officerLevel = o["officer_level"]?.jsonPrimitive?.intOrNull ?: 0,
            injury = o["injury"]?.jsonPrimitive?.intOrNull ?: 0,
            gold = o["gold"]?.jsonPrimitive?.intOrNull ?: 0,
            rice = o["rice"]?.jsonPrimitive?.intOrNull ?: 0,
            crew = o["crew"]?.jsonPrimitive?.intOrNull ?: 0,
            crewTypeId = o["crewtype"]?.jsonPrimitive?.intOrNull ?: 0,
            train = o["train"]?.jsonPrimitive?.intOrNull ?: 0,
            atmos = o["atmos"]?.jsonPrimitive?.intOrNull ?: 0,
            age = o["age"]?.jsonPrimitive?.intOrNull ?: 0,
            npcState = o["npc"]?.jsonPrimitive?.intOrNull ?: 0,
            turnTime = parseTurnTime(o["turntime"]?.jsonPrimitive?.contentOrNull),
            recentWarTime = o["recent_war"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.contentOrNull
                ?.let { runCatching { parseTurnTime(it) }.getOrNull() },
            meta = meta,
        )
    }

    private fun toCity(o: JsonObject): City {
        val meta = decodeObject(o).toMutableMap()
        // trust rides meta (logic City reads meta["trust"]); ensure present.
        return City(
            id = o["city"]!!.jsonPrimitive.int,
            name = o["name"]?.jsonPrimitive?.contentOrNull ?: "",
            nationId = o["nation"]?.jsonPrimitive?.intOrNull ?: 0,
            level = o["level"]?.jsonPrimitive?.intOrNull ?: 0,
            state = o["state"]?.jsonPrimitive?.intOrNull ?: 0,
            population = o["pop"]?.jsonPrimitive?.intOrNull ?: 0,
            populationMax = o["pop_max"]?.jsonPrimitive?.intOrNull ?: 0,
            agriculture = o["agri"]?.jsonPrimitive?.intOrNull ?: 0,
            agricultureMax = o["agri_max"]?.jsonPrimitive?.intOrNull ?: 0,
            commerce = o["comm"]?.jsonPrimitive?.intOrNull ?: 0,
            commerceMax = o["comm_max"]?.jsonPrimitive?.intOrNull ?: 0,
            security = o["secu"]?.jsonPrimitive?.intOrNull ?: 0,
            securityMax = o["secu_max"]?.jsonPrimitive?.intOrNull ?: 0,
            supplyState = o["supply"]?.jsonPrimitive?.intOrNull ?: 0,
            frontState = o["front"]?.jsonPrimitive?.intOrNull ?: 0,
            defence = o["def"]?.jsonPrimitive?.intOrNull ?: 0,
            defenceMax = o["def_max"]?.jsonPrimitive?.intOrNull ?: 0,
            wall = o["wall"]?.jsonPrimitive?.intOrNull ?: 0,
            wallMax = o["wall_max"]?.jsonPrimitive?.intOrNull ?: 0,
            trade = o["trade"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.intOrNull,
            region = o["region"]?.jsonPrimitive?.intOrNull ?: 0,
            term = o["term"]?.jsonPrimitive?.intOrNull ?: 0,
            officerSet = o["officer_set"]?.jsonPrimitive?.intOrNull ?: 0,
            conflict = jsonStorageString(o["conflict"]),
            meta = meta,
        )
    }

    private fun jsonStorageString(value: JsonElement?): String =
        when (value) {
            null, JsonNull -> "{}"
            is JsonPrimitive -> value.contentOrNull ?: value.toString()
            else -> value.toString()
        }

    private fun toNation(
        o: JsonObject,
        nationEnvByNation: Map<Int, Map<String, Any?>>,
        initYear: Int,
        initMonth: Int,
    ): Nation {
        val nid = o["nation"]!!.jsonPrimitive.int
        val meta = decodeObject(o).toMutableMap()
        // nation_env KV (the AI reads getNationById(nation).meta["nation_env"]).
        meta["nation_env"] = nationEnvByNation[nid] ?: emptyMap<String, Any?>()
        // init_year/init_month ride the nation meta (relYearMonth + do거병 foundDeadlineMore).
        meta.putIfAbsent("init_year", initYear)
        meta.putIfAbsent("init_month", initMonth)
        return Nation(
            id = nid,
            name = o["name"]?.jsonPrimitive?.contentOrNull ?: "",
            color = o["color"]?.jsonPrimitive?.contentOrNull ?: "#000000",
            capitalCityId = o["capital"]?.jsonPrimitive?.intOrNull,
            gold = o["gold"]?.jsonPrimitive?.intOrNull ?: 0,
            rice = o["rice"]?.jsonPrimitive?.intOrNull ?: 0,
            power = o["power"]?.jsonPrimitive?.intOrNull ?: 0,
            tech = o["tech"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
            level = o["level"]?.jsonPrimitive?.intOrNull ?: 0,
            typeCode = o["type"]?.jsonPrimitive?.contentOrNull ?: "None",
            meta = meta,
        )
    }

    private fun toDiplomacy(o: JsonObject): TurnDiplomacy = TurnDiplomacy(
        fromNationId = o["me"]!!.jsonPrimitive.int,
        toNationId = o["you"]!!.jsonPrimitive.int,
        state = o["state"]?.jsonPrimitive?.intOrNull ?: 0,
        term = o["term"]?.jsonPrimitive?.intOrNull ?: 0,
        dead = o["dead"]?.jsonPrimitive?.intOrNull ?: 0,
    )

    // ── arg + stream diffing ─────────────────────────────────────────────────────────────────────────

    /** Return a copy of the golden chosenRawArgs object with [key] removed (for the Q1 quarantine arg diff). */
    private fun stripKey(goldenArgs: kotlinx.serialization.json.JsonElement?, key: String): JsonObject? {
        val o = goldenArgs?.takeIf { it !is JsonNull }?.jsonObject ?: return null
        return JsonObject(o.filterKeys { it != key })
    }

    /** Compare golden chosenRawArgs (JSON) vs live args map by CONTENT (key→scalar), order-insensitive. */
    private fun diffArgs(tag: String, goldenArgs: kotlinx.serialization.json.JsonElement?, liveArgs: Map<String, Any?>, cluster: String): Divergence? {
        val golden: Map<String, Any?> = when {
            goldenArgs == null || goldenArgs is JsonNull -> emptyMap()
            goldenArgs is JsonArray -> if (goldenArgs.isEmpty()) emptyMap() else {
                // a non-empty array arg is unexpected for the asserted families; compare as positional.
                return if (liveArgs.isEmpty()) Divergence(cluster, "$tag args: golden array $goldenArgs vs live empty") else null
            }
            goldenArgs is JsonObject -> decodeObject(goldenArgs)
            else -> emptyMap()
        }
        val liveNorm = liveArgs.filterValues { it != null }
        val goldenNorm = golden.filterValues { it != null }
        if (goldenNorm.keys != liveNorm.keys) {
            return Divergence(cluster, "$tag args KEY mismatch — golden=${goldenNorm.keys} live=${liveNorm.keys} (golden=$goldenNorm live=$liveNorm)")
        }
        for (k in goldenNorm.keys) {
            if (!scalarEquals(goldenNorm[k], liveNorm[k])) {
                return Divergence(cluster, "$tag args[$k] mismatch — golden=${goldenNorm[k]} live=${liveNorm[k]}")
            }
        }
        return null
    }

    private fun scalarEquals(a: Any?, b: Any?): Boolean {
        if (a == null && b == null) return true
        if (a is Boolean || b is Boolean) return a == b
        if (a is Number && b is Number) return a.toDouble() == b.toDouble()
        return a?.toString() == b?.toString()
    }

    /**
     * The PHP `RandUtilDrawRecorder` overrides `choiceUsingWeight` (records a WRAPPER entry riding on its inner
     * `nextFloat1`, same before-cursor) but NOT `choiceUsingWeightPair`. The Kotlin AI families deliberately use
     * `choiceUsingWeightPair` (Int-key insertion order, NOT JS-key-reordered) for BOTH the do일반내정 develop pick
     * (PHP `choiceUsingWeightPair` → golden has NO wrapper) AND the do징병/do선전포고 armType/crewType/war-target
     * pick (PHP `choiceUsingWeight` → golden HAS a wrapper). So the SAME live method maps to two PHP methods; the
     * recorder cannot distinguish them. The wrapper consumes ZERO bytes beyond its inner `nextFloat1` (its cursor
     * == the inner draw's), so it is a pure recorder ARTIFACT, not an RNG draw. We collapse it on BOTH sides: drop
     * any `choiceUsingWeight`/`choiceUsingWeightPair` entry whose before-cursor equals the immediately-preceding
     * KEPT entry's cursor. The actual RNG parity target (the inner `nextFloat1` value + cursor + count) is
     * asserted unchanged; the wrapper-vs-pair recorder shape is reconciled.
     */
    private data class StreamEntry(val method: String, val state: Long, val buffer: Int)

    private fun collapseGolden(golden: List<JsonObject>): List<JsonObject> {
        val out = ArrayList<JsonObject>(golden.size)
        for (g in golden) {
            val m = g["method"]!!.jsonPrimitive.content
            if (m == "choiceUsingWeight" || m == "choiceUsingWeightPair") {
                val prev = out.lastOrNull()
                if (prev != null &&
                    prev["stateIdxBefore"]!!.jsonPrimitive.content.toLong() == g["stateIdxBefore"]!!.jsonPrimitive.content.toLong() &&
                    prev["bufferIdxBefore"]!!.jsonPrimitive.int == g["bufferIdxBefore"]!!.jsonPrimitive.int
                ) {
                    continue // wrapper over the preceding inner draw — recorder artifact, not an RNG draw.
                }
            }
            out.add(g)
        }
        return out
    }

    private fun collapseLive(live: List<AiDrawRecorder.Draw>): List<AiDrawRecorder.Draw> {
        val out = ArrayList<AiDrawRecorder.Draw>(live.size)
        for (l in live) {
            if (l.method == "choiceUsingWeight" || l.method == "choiceUsingWeightPair") {
                val prev = out.lastOrNull()
                if (prev != null && prev.stateIdxBefore == l.stateIdxBefore && prev.bufferIdxBefore == l.bufferIdxBefore) {
                    continue
                }
            }
            out.add(l)
        }
        return out
    }

    private fun diffStream(tag: String, reason: String, goldenRaw: List<JsonObject>, liveRaw: List<AiDrawRecorder.Draw>): Divergence? {
        val golden = collapseGolden(goldenRaw)
        val live = collapseLive(liveRaw)
        val n = maxOf(golden.size, live.size)
        for (seq in 0 until n) {
            val g = golden.getOrNull(seq)
            val l = live.getOrNull(seq)
            if (g == null) {
                return Divergence("$reason:extra-draw", "$tag seq=$seq EXTRA live draw ${describeLive(l!!)} (golden has ${golden.size}, live ${live.size})")
            }
            if (l == null) {
                return Divergence("$reason:missing-draw", "$tag seq=$seq MISSING live draw — golden ${describeGolden(g)} (golden ${golden.size}, live ${live.size})")
            }
            val gMethod = g["method"]!!.jsonPrimitive.content
            val gState = g["stateIdxBefore"]!!.jsonPrimitive.content.toLong()
            val gBuffer = g["bufferIdxBefore"]!!.jsonPrimitive.int
            val gConsumed = g["consumed"]!!.jsonPrimitive.booleanOrNull ?: true

            val methodBad = gMethod != l.method
            val cursorBad = gState != l.stateIdxBefore || gBuffer != l.bufferIdxBefore
            val consumedBad = gConsumed != l.consumed
            val valueBad = !resultMatches(g, l)
            val argsBad = !drawArgsMatch(g, l)

            if (methodBad || cursorBad || consumedBad || valueBad || argsBad) {
                val cause = when {
                    methodBad -> "$gMethod-vs-${l.method}:method"
                    argsBad -> "$gMethod:args"
                    cursorBad -> "$gMethod:cursor"
                    consumedBad -> "$gMethod:consumed"
                    else -> "$gMethod:value"
                }
                return Divergence(
                    "$reason:$cause",
                    "$tag FIRST-DIVERGENT-DRAW seq=$seq:\n  golden: ${describeGolden(g)}\n  live:   ${describeLive(l)}",
                )
            }
        }
        return null
    }

    private fun drawArgsMatch(g: JsonObject, l: AiDrawRecorder.Draw): Boolean {
        val ga = g["args"]
        return when (g["method"]!!.jsonPrimitive.content) {
            "nextBool" -> {
                val gp = (ga as? JsonObject)?.get("prob")?.jsonPrimitive?.doubleOrNull
                val lp = (l.args["prob"] as? Number)?.toDouble()
                gp == null || gp == lp
            }
            "nextRange", "nextRangeInt", "nextInt" -> {
                val gmin = (ga as? JsonObject)?.get("min")?.jsonPrimitive?.doubleOrNull
                val gmax = (ga as? JsonObject)?.get("max")?.jsonPrimitive?.doubleOrNull
                val lmin = (l.args["min"] as? Number)?.toDouble()
                val lmax = (l.args["max"] as? Number)?.toDouble()
                (gmin == null || gmin == lmin) && (gmax == null || gmax == lmax)
            }
            else -> true // nextFloat1/nextBit no args; choice compared by result/index in resultMatches.
        }
    }

    private fun resultMatches(g: JsonObject, l: AiDrawRecorder.Draw): Boolean {
        if (g["method"]!!.jsonPrimitive.content == "choice") {
            val gVal = g["result"]?.jsonPrimitive?.contentOrNull
            return gVal == (l.result as? String) || gVal == l.result?.toString()
        }
        val gp = g["result"]!!.jsonPrimitive
        gp.booleanOrNull?.let { return it == (l.result as? Boolean) }
        return when (val av = l.result) {
            is Double -> gp.doubleOrNull == av
            is Int -> gp.intOrNull == av
            is Boolean -> gp.booleanOrNull == av
            else -> gp.content == av.toString()
        }
    }

    private fun describeGolden(g: JsonObject): String =
        "method=${g["method"]!!.jsonPrimitive.content} args=${g["args"]} result=${g["result"]} " +
            "consumed=${g["consumed"]} cursor=(${g["stateIdxBefore"]},${g["bufferIdxBefore"]})"

    private fun describeLive(l: AiDrawRecorder.Draw): String =
        "method=${l.method} args=${l.args} result=${l.result} consumed=${l.consumed} cursor=(${l.stateIdxBefore},${l.bufferIdxBefore})"

    // ── helpers ────────────────────────────────────────────────────────────────────────────────────────

    private fun seedStringFor(hidden: String, year: Int, month: Int, generalId: Int): String =
        opensamguk.logic.ai.AiSeed.seed(hidden, year, month, generalId)

    /**
     * Canonicalize the LIVE AI-emitted args through the command def's [GeneralActionDefinition.parseArgs] — the
     * SAME single canonicalization the F-BRIDGE gate applies (CandidateBridge M2: the AI emits RAW, the bridge
     * canonicalizes EXACTLY ONCE via `def.parseArgs`). The PHP capture records the POST-argTest
     * `$chosenGeneral->getArg()` (e.g. che_헌납 clamps `amount` to `GameConst::$maxResourceActionAmount`), so the
     * canonical map IS the parity target; comparing the raw un-clamped AI emit would false-fail. Routes through
     * the SAME [registry] the adapter's gate uses (`registry.resolve(code)`); never double-canonicalizes.
     */
    private fun canonicalizeArgs(actionCode: String, rawArgs: Map<String, Any?>): Map<String, Any?> =
        registry.resolve(actionCode).parseArgs(rawArgs)

    private fun parseTurnTime(raw: String?): Instant {
        if (raw.isNullOrBlank()) return Instant.parse("0181-01-01T00:00:00Z")
        // the dump emits "YYYY-MM-DD HH:MM:SS[.ffffff]" (MariaDB datetime); treat as UTC.
        return runCatching {
            val ldt = java.time.LocalDateTime.parse(raw.trim(), ISO_MICROS)
            ldt.toInstant(java.time.ZoneOffset.UTC)
        }.getOrElse { Instant.parse("0181-01-01T00:00:00Z") }
    }

    /** Decode a JSON object into a Kotlin map (insertion order preserved; nested objects/arrays recursed). */
    private fun decodeObject(o: JsonObject): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>(o.size)
        for ((k, v) in o) out[k] = decodeElement(v)
        return out
    }

    private fun decodeElement(e: kotlinx.serialization.json.JsonElement): Any? = when (e) {
        is JsonNull -> null
        is JsonPrimitive -> when {
            e.isString -> e.content
            e.booleanOrNull != null -> e.booleanOrNull
            e.longOrNull != null -> {
                val l = e.longOrNull!!; if (l in Int.MIN_VALUE..Int.MAX_VALUE) l.toInt() else l
            }
            e.doubleOrNull != null -> e.doubleOrNull
            else -> e.content
        }
        is JsonArray -> e.map { decodeElement(it) }
        is JsonObject -> decodeObject(e)
    }
}
