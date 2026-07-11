package opensamguk.logic.golden

import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import opensamguk.logic.tournament.InMemoryTournamentStore
import opensamguk.logic.tournament.TournamentBettingPort
import opensamguk.logic.tournament.TournamentEntry
import opensamguk.logic.tournament.TournamentFightEngine
import opensamguk.logic.tournament.TournamentFightLogPort
import opensamguk.logic.tournament.TournamentProcessor
import opensamguk.logic.tournament.TournamentRankPort
import opensamguk.logic.tournament.TournamentState
import opensamguk.logic.tournament.tournamentTotalStat
import opensamguk.logic.util.PhpMt19937
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * AREA 대회(토너먼트) — fight()/qualify() PHP 골든 byte 게이트.
 *
 * 오라클: golden/tournament/fight-fixtures.json (tools/php-golden/capture_tournament.php로
 * real devsam-core PHP에서 캡처 — func_tournament.php:1004 fight, :583 qualify).
 *
 * ★ RNG divergence: 대회 경로는 sammo RandUtil/LiteHashDRBG가 아니라 PHP 네이티브
 *   rand()/mt_rand()/array_rand()를 쓴다. 결정성은 mt_srand(seed, MT_RAND_MT19937) 핀으로
 *   확보되며, per-draw 스트림 캡처는 불가(오라클 미수정 원칙)이므로 이 게이트는
 *   (a) 순수 MT19937 적합성 벡터 byte-match + (b) 실제 fight/qualify 출력 전체
 *   (logLines/행 델타/rank 델타/KV 전이) byte-match의 2단으로 파리티를 증명한다.
 *   골든 수치·로그는 절대 수정 금지 — mismatch면 Kotlin 구현이 틀린 것.
 */
class TournamentFightGoldenTest {
    private val root: JsonObject = Json.parseToJsonElement(
        checkNotNull(javaClass.getResourceAsStream("/golden/tournament/fight-fixtures.json")) {
            "fixture missing: golden/tournament/fight-fixtures.json"
        }.bufferedReader().readText(),
    ).jsonObject

    // ── JSON 헬퍼 ───────────────────────────────────────────────────────────
    private fun JsonObject.int(key: String): Int = getValue(key).jsonPrimitive.int
    private fun JsonObject.str(key: String): String = getValue(key).jsonPrimitive.content
    private fun JsonElement.ints(): List<Int> = jsonArray.map { it.jsonPrimitive.int }
    private fun JsonElement.strs(): List<String> = jsonArray.map { it.jsonPrimitive.content }

    /** 캡처 gen 행(SELECT * from tournament) → TournamentEntry. */
    private fun entryOf(o: JsonObject) = TournamentEntry(
        id = o.int("no"),
        npc = o.int("npc"),
        name = o.str("name"),
        leadership = o.int("leadership"),
        strength = o.int("strength"),
        intel = o.int("intel"),
        level = o.int("lvl"),
        group = o.int("grp"),
        groupNo = o.int("grp_no"),
        win = o.int("win"),
        draw = o.int("draw"),
        lose = o.int("lose"),
        goal = o.int("gl"),
        promote = o.int("prmt"),
        seq = o.int("seq"),
        horse = o.str("h"),
        weapon = o.str("w"),
        book = o.str("b"),
    )

    /** rank_data in-memory — PHP UPDATE 의미론(행 없으면 no-op). */
    private class MemRank : TournamentRankPort {
        val values = HashMap<Pair<Int, String>, Int>()
        override fun value(generalId: Int, type: String): Int? = values[generalId to type]
        override fun increase(generalId: Int, type: String, amount: Int) {
            val key = generalId to type
            val cur = values[key] ?: return // 행 없으면 UPDATE 미적중
            values[key] = cur + amount
        }
    }

    /** pushTnmtFightLog 파일 append 의미론 캡처. */
    private class MemFightLog : TournamentFightLogPort {
        val byGroup = HashMap<Int, MutableList<String>>()
        override fun erase(group: Int) {
            byGroup.remove(group)
        }
        override fun push(group: Int, lines: List<String>) {
            byGroup.getOrPut(group) { mutableListOf() }.addAll(lines)
        }
    }

    private val noopBetting = object : TournamentBettingPort {
        override fun open(type: Int, unitSeconds: Int, finalists: List<TournamentEntry>): Int = 0
        override fun close(bettingId: Int) = Unit
        override fun refund(bettingId: Int) = Unit
        override fun payout(bettingId: Int, winnerId: Int) = Unit
    }

    @Test
    fun `PHP MT19937 적합성 벡터 - mt_rand·mod·range·array_rand byte-match`() {
        val vectors = root.getValue("rngConformance").jsonArray
        assertEquals(3, vectors.size, "rngConformance vector count")
        for (vecEl in vectors) {
            val vec = vecEl.jsonObject
            val seed = vec.int("seed")
            assertEquals(
                vec.getValue("mt_rand").ints(),
                PhpMt19937(seed).let { r -> List(32) { r.mtRand() } },
                "mt_rand() seed=$seed",
            )
            assertEquals(
                vec.getValue("mt_rand_mod100").ints(),
                PhpMt19937(seed).let { r -> List(32) { r.mtRand() % 100 } },
                "mt_rand()%100 seed=$seed",
            )
            assertEquals(
                vec.getValue("mt_rand_1_100").ints(),
                PhpMt19937(seed).let { r -> List(32) { r.mtRand(1, 100) } },
                "mt_rand(1,100) seed=$seed",
            )
            assertEquals(
                vec.getValue("mt_rand_150_300").ints(),
                PhpMt19937(seed).let { r -> List(32) { r.mtRand(150, 300) } },
                "mt_rand(150,300) seed=$seed",
            )
            assertEquals(
                vec.getValue("array_rand_size6").ints(),
                PhpMt19937(seed).let { r -> List(32) { r.arrayRand(6) } },
                "array_rand(size6) seed=$seed",
            )
        }
    }

    @Test
    fun `fight 케이스 - 로그·tournament 행 델타·rank_data 델타 byte-match`() {
        val cases = root.getValue("fightCases").jsonArray
        assertEquals(6, cases.size, "fight case count")
        for (caseEl in cases) {
            val c = caseEl.jsonObject
            val caseId = c.str("caseId")
            val args = c.getValue("fightArgs").jsonObject
            val gen1Json = c.getValue("gen1").jsonObject
            val gen2Json = c.getValue("gen2").jsonObject
            val gen1 = entryOf(gen1Json)
            val gen2 = entryOf(gen2Json)

            // tnmt_type=0: SQL DECIMAL total ((l+s+i)*7/15, 4자리 half-away) 재현 검증
            if (args.int("tnmtType") == 0) {
                assertEquals(
                    gen1Json.str("total").toDouble(),
                    tournamentTotalStat(gen1.leadership, gen1.strength, gen1.intel),
                    0.0,
                    "$caseId gen1 total",
                )
                assertEquals(
                    gen2Json.str("total").toDouble(),
                    tournamentTotalStat(gen2.leadership, gen2.strength, gen2.intel),
                    0.0,
                    "$caseId gen2 total",
                )
            }

            val store = InMemoryTournamentStore(linkedMapOf(gen1.id to gen1, gen2.id to gen2))
            val rank = MemRank()
            for ((who, gid) in listOf("g1" to gen1.id, "g2" to gen2.id)) {
                val before = c.getValue("rankDataBefore").jsonObject.getValue(who).jsonObject
                for ((type, v) in before) rank.values[gid to type] = v.jsonPrimitive.int
            }
            val logPort = MemFightLog()
            val engine = TournamentFightEngine(store, rank, logPort, PhpMt19937(c.int("seed")))

            val sel = engine.fight(
                args.int("tnmtType"), args.int("tnmt"), args.int("phs"),
                args.int("group"), args.int("g1"), args.int("g2"), args.int("type"),
            )

            assertEquals(c.int("sel"), sel, "$caseId sel")
            assertEquals<List<String>?>(c.getValue("logLines").strs(), logPort.byGroup[args.int("group")]?.toList(), "$caseId logLines")

            val after = c.getValue("tournamentAfter").jsonObject
            for ((who, grpNo) in listOf("g1" to args.int("g1"), "g2" to args.int("g2"))) {
                val exp = after.getValue(who).jsonObject
                val row = store.entries().first { it.group == args.int("group") && it.groupNo == grpNo }
                assertEquals(exp.int("win"), row.win, "$caseId $who win")
                assertEquals(exp.int("draw"), row.draw, "$caseId $who draw")
                assertEquals(exp.int("lose"), row.lose, "$caseId $who lose")
                assertEquals(exp.int("gl"), row.goal, "$caseId $who gl")
            }

            for ((who, gid) in listOf("g1" to gen1.id, "g2" to gen2.id)) {
                val expRank = c.getValue("rankDataAfter").jsonObject.getValue(who).jsonObject
                for ((type, v) in expRank) {
                    assertEquals(v.jsonPrimitive.int, rank.values[gid to type], "$caseId $who rank $type")
                }
            }
        }
    }

    @Test
    fun `qualify 승격 분기 - 8조 fight 스트림 공유 + top4 prmt + KV 전이`() {
        val q = root.getValue("qualifyPromote").jsonObject
        val before = q.getValue("before").jsonArray

        // 캡처 크래프트 입력(capture_tournament.php:302-315): no=seq(삽입순), name=G{grp}_{no},
        // leadership=50+no, strength=50, intel=50, lvl=10. win/draw/lose/gl/seq는 before가 정본.
        val entries = linkedMapOf<Int, TournamentEntry>()
        for (grp in 0 until 8) {
            for (rowEl in before[grp].jsonArray) {
                val row = rowEl.jsonObject
                val grpNo = row.int("grpNo")
                val id = row.int("seq")
                entries[id] = TournamentEntry(
                    id = id,
                    npc = 0,
                    name = "G${grp}_${grpNo}",
                    leadership = 50 + grpNo,
                    strength = 50,
                    intel = 50,
                    level = 10,
                    group = grp,
                    groupNo = grpNo,
                    win = row.int("win"),
                    draw = row.int("draw"),
                    lose = row.int("lose"),
                    goal = row.int("gl"),
                    promote = 0,
                    seq = row.int("seq"),
                )
            }
        }
        val store = InMemoryTournamentStore(entries)
        val rank = MemRank()
        for (id in 1..64) for (t in listOf("ttw", "ttd", "ttl", "ttg")) rank.values[id to t] = 0
        val logPort = MemFightLog()

        val processor = TournamentProcessor(store, noopBetting, rank, logPort, PhpMt19937(q.int("seed")))
        val t0 = Instant.parse("2026-07-10T00:00:00Z")
        val result = processor.process(
            TournamentState(
                tournament = 2, phase = 55, type = q.int("tnmtType"),
                auto = true, time = t0, turnTermMinutes = 30,
            ),
            now = t0,
        )

        // KV 전이: tournament 2→3, phase 0 (qualify phase>=55 분기)
        val kv = q.getValue("kvAfter").jsonObject
        assertEquals(kv.int("tournament"), result.state.tournament, "kvAfter tournament")
        assertEquals(kv.int("phase"), result.state.phase, "kvAfter phase")

        val after = q.getValue("after").jsonArray
        val promote = q.getValue("promote").jsonArray
        for (grp in 0 until 8) {
            for (rowEl in after[grp].jsonArray) {
                val row = rowEl.jsonObject
                val e = store.entries().first { it.group == grp && it.groupNo == row.int("grpNo") }
                val tag = "grp$grp no${row.int("grpNo")}"
                assertEquals(row.int("win"), e.win, "$tag win")
                assertEquals(row.int("draw"), e.draw, "$tag draw")
                assertEquals(row.int("lose"), e.lose, "$tag lose")
                assertEquals(row.int("gl"), e.goal, "$tag gl")
                assertEquals(row.int("gd"), e.point, "$tag gd(win*3+draw)")
                assertEquals(row.int("prmt"), e.promote, "$tag prmt")
            }
            // prmt 1..4 → grp_no 순서 (gd desc, gl desc, seq)
            val expPromote = promote[grp].jsonArray.map { it.jsonObject.let { o -> o.int("prmt") to o.int("grpNo") } }
            val actPromote = store.entries()
                .filter { it.group == grp && it.promote > 0 }
                .sortedBy { it.promote }
                .map { it.promote to it.groupNo }
            assertEquals(expPromote, actPromote, "grp$grp promote order")
        }
    }
}
