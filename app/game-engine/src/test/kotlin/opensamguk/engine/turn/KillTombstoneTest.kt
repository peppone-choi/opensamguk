package opensamguk.engine.turn

import opensamguk.common.constants.ScenarioLifecycleMeta
import opensamguk.logic.actions.CommandRegistry
import opensamguk.logic.stats.GeneralActionPipeline
import opensamguk.logic.tick.ServerClock
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * B4 Task LC2 — kill() tombstone (4 game-table delete + durable owner-link cleanup) + possession-release
 * + storeOldGeneral.
 *
 * Port target: `TurnExecutionHelper.php:185-206` (the killturn<=0 gate: possession-release vs kill())
 * + `General.php:515-600` (kill()). The killturn<=0 branch of [ReservedTurnHandler.updateTurnTime]:
 *  - NPCType==1 & deadyear>year → 유체이탈 possession release (a NON-delete branch): push the global
 *    log FIRST, then legacy `killturn=(deadyear-year)*12` converted to three phase turns,
 *    `npc=npc_org, owner=0, defence_train=80,
 *    owner_name=null`, then record DELETE intents for general_access_log and the durable general_owner link.
 *  - else → kill(): nextRuler/demote → troop cleanup → dying message → storeOldGeneral → the F3
 *    4 game-table delete plus durable owner-link cleanup (the row leaves the update-set, no double-apply)
 *    → nation gennum-1.
 */
class KillTombstoneTest {

    private val t0 = Instant.parse("0200-06-15T14:00:00Z")
    private val turnTerm = 120

    private fun env(year: Int = 200, isunited: Int = 0) = LifecycleEnv(
        baselineKillturn = 12, year = year, month = 6, turnTerm = turnTerm, isunited = isunited,
    )

    private fun gen(
        id: Int = 1,
        npc: Int = 0,
        userId: String? = null,
        nationId: Int = 1,
        officerLevel: Int = 1,
        troopId: Int = 0,
        killturn: Int = 0,
        deadyear: Int = 999,
        extraMeta: Map<String, Any?> = emptyMap(),
    ) = TurnGeneral(
        id = id,
        name = "g$id",
        nationId = nationId,
        cityId = 5,
        troopId = troopId,
        stats = GeneralStats(80, 70, 60),
        experience = 0,
        dedication = 0,
        officerLevel = officerLevel,
        age = 40,
        npcState = npc,
        userId = userId,
        turnTime = t0,
        gold = 4242,
        meta = linkedMapOf<String, Any?>(
            "killturn" to killturn,
            "deadyear" to deadyear,
            "lived_month" to 100,
            "npc_org" to 3,
            "owner" to 77,
            "owner_name" to "유저A",
        ) + extraMeta,
    )

    private fun nation(id: Int = 1, gennum: Int = 5) =
        Nation(id, "n$id", "#000", meta = mapOf("gennum" to gennum))

    private fun world(
        generals: List<TurnGeneral>,
        nations: List<Nation> = listOf(nation()),
        troops: List<Troop> = emptyList(),
        accessLogs: List<GeneralAccessLog> = emptyList(),
    ) = InMemoryTurnWorld(
        WorldSnapshot(
            state = TurnWorldState(1, 200, 6, 3600, t0),
            generals = generals,
            nations = nations,
            troops = troops,
            accessLogs = accessLogs,
            worldId = opensamguk.common.world.WorldId((TurnWorldState(1, 200, 6, 3600, t0)).id),
        ),
    )

    private fun handler(world: InMemoryTurnWorld) =
        ReservedTurnHandler(
            world,
            registry = CommandRegistry(GeneralActionPipeline()),
            hiddenSeed = "0".repeat(32),
            startYear = 184,
        )

    // ── possession release (유체이탈, TurnExecutionHelper.php:187-200) ────────────────────────────

    @Test
    fun `NPC type 1 with deadyear ahead releases possession - field set, log, no delete`() {
        val g = gen(npc = 1, userId = "77", killturn = 0, deadyear = 205)
        val w = world(listOf(g), accessLogs = listOf(GeneralAccessLog(1, 77, t0, refreshScore = 9)))
        val h = handler(w)

        val outcome = h.updateTurnTime(1, env(year = 200))
        assertEquals(LifecycleOutcome.POSSESSION_RELEASED, outcome)

        val after = w.getGeneralById(1)!!
        assertEquals(180, (after.meta["killturn"] as Number).toInt())
        assertEquals(ScenarioLifecycleMeta.KILLTURN_UNIT_PHASE, after.meta["killturn_unit"])
        val reloadedMeta = ScenarioLifecycleMeta.ensureGeneralMeta(
            after.meta,
            deadYear = 205,
            startYear = 184,
            startMonth = 1,
            convertLegacyNpcKillturn = true,
        )
        assertEquals(180, (reloadedMeta["killturn"] as Number).toInt())
        // npc = npc_org (3)
        assertEquals(3, after.npcState)
        assertNull(after.userId, "possession release clears the typed user owner")
        assertEquals(0, (after.meta["owner"] as Number).toInt())
        assertEquals(80, (after.meta["defence_train"] as Number).toInt())
        assertNull(after.meta["owner_name"])
        assertNull(w.getAccessLog(1))
        assertEquals(setOf(1), h.recorder.accessLogDeletes())
        assertEquals(setOf(1), h.recorder.generalOwnerDeletes())

        val drained = w.consumeDirtyState()
        assertFalse(drained.deletedGenerals.contains(1), "possession release does NOT delete the general")
        // the 유체이탈 global log is pushed FIRST (before the field mutations).
        assertTrue(
            drained.logs.any {
                it.scope == "global" &&
                    it.text == "유저A</>가 <Y>g1</>의 육체에서 <S>유체이탈</>합니다!"
            },
            "유체이탈 global log byte-exact",
        )
        // turntime still advances (possession release falls through to addTurn).
        assertEquals(ServerClock.addTurn(t0, turnTerm, 1), after.turnTime)
    }

    @Test
    fun `possession release josa - owner_name ending in vowel uses 가`() {
        // JosaUtil.pick("철수", "이") -> 가 (철수 ends in vowel ㅜ? no; 수 has no jongsung) → 가
        val g = gen(npc = 1, killturn = 0, deadyear = 202, extraMeta = mapOf("owner_name" to "철수"))
        val w = world(listOf(g))
        handler(w).updateTurnTime(1, env(year = 200))
        assertTrue(
            w.consumeDirtyState().logs.any { it.text.startsWith("철수</>가 <Y>g1</>의 육체에서") },
        )
    }

    // ── kill() (General.php:515-600) ─────────────────────────────────────────────────────────────

    @Test
    fun `killturn zero non-possession kills - F3 tombstone, 4-table delete, excluded from update-set`() {
        val g = gen(npc = 0, killturn = 0)
        val w = world(listOf(g), accessLogs = listOf(GeneralAccessLog(1, 77, t0, refreshScore = 9)))
        val h = handler(w)

        val outcome = h.updateTurnTime(1, env(year = 200))
        assertEquals(LifecycleOutcome.KILLED, outcome)

        // tombstoned: not in the update-set, lands in deletedGenerals exactly once.
        assertFalse(h.recorder.dirtyGeneralIds().contains(1), "killed general excluded from update-set")
        val drained = w.consumeDirtyState()
        assertEquals(listOf(1), drained.deletedGenerals)
        assertTrue(drained.generals.none { it.id == 1 }, "killed general not in dirty rows")
        assertNull(w.getAccessLog(1))
        assertEquals(setOf(1), h.recorder.accessLogDeletes())
        assertEquals(setOf(1), h.recorder.generalOwnerDeletes())
    }

    @Test
    fun `kill captures the storeOldGeneral snapshot BEFORE the delete`() {
        val g = gen(npc = 0, killturn = 0)
        val w = world(listOf(g))
        val h = handler(w)
        h.updateTurnTime(1, env())
        val snap = h.recorder.oldGeneralSnapshots().single()
        assertEquals(1, snap.id)
        assertEquals(4242, snap.gold, "pre-delete row captured")
    }

    @Test
    fun `kill decrements the nation gennum by 1`() {
        val g = gen(npc = 0, killturn = 0, nationId = 1)
        val w = world(listOf(g), nations = listOf(nation(1, gennum = 5)))
        handler(w).updateTurnTime(1, env())
        assertEquals(4, (w.getNationById(1)!!.meta["gennum"] as Number).toInt())
    }

    @Test
    fun `kill of a troop leader disbands the troop and frees its members`() {
        // the leader's troop column == its own id (troopId=1); a member m=2 also in troop 1.
        val leader = gen(id = 1, npc = 0, killturn = 0, troopId = 1)
        val member = gen(id = 2, npc = 0, killturn = 12, troopId = 1, deadyear = 999)
        val w = world(listOf(leader, member), troops = listOf(Troop(1, 1, "t1")))
        val h = handler(w)
        h.updateTurnTime(1, env())
        // member freed (troop=0); troop row deleted.
        assertEquals(0, w.getGeneralById(2)!!.troopId)
        assertNull(w.getTroopById(1))
        assertTrue(w.consumeDirtyState().deletedTroops.contains(1))
    }

    @Test
    fun `kill of a ruler (officer_level 12) demotes via nextRuler hook`() {
        val ruler = gen(id = 1, npc = 0, killturn = 0, officerLevel = 12)
        val heir = gen(id = 2, npc = 0, killturn = 12, officerLevel = 9)
        val w = world(listOf(ruler, heir))
        val demoted = mutableListOf<Int>()
        val h = ReservedTurnHandler(
            w,
            registry = CommandRegistry(GeneralActionPipeline()),
            hiddenSeed = "0".repeat(32),
            startYear = 184,
            nextRuler = { gid, _ -> demoted.add(gid) },
        )
        h.updateTurnTime(1, env())
        assertEquals(listOf(1), demoted, "nextRuler hook invoked for the dying ruler")
    }

    @Test
    fun `kill pushes the dying message global log`() {
        val g = gen(id = 1, npc = 0, killturn = 0)
        val w = world(listOf(g))
        val h = handler(w)
        h.updateTurnTime(1, env())
        // the default DyingMessage: "<Y>{name}</>{josaYi} <R>사망</>했습니다." (josa pick(g1,'이') → 이)
        assertTrue(
            w.consumeDirtyState().logs.any {
                it.scope == "global" && it.text == "<Y>g1</>이 <R>사망</>했습니다."
            },
            "default dying-message global log byte-exact",
        )
    }

    // ── 군주 사망 후계/멸망 (RulerSuccessionHandler 실배선, func.php:1807/1713) ────────────────────────

    /** DaemonLoopConfig와 동일하게 후계 핸들러를 nextRuler 훅에 실배선(공유 recorder). */
    private fun successionHandler(w: InMemoryTurnWorld): ReservedTurnHandler {
        val recorder = ChangeRecorder()
        val succession = RulerSuccessionHandler(w, recorder, "0".repeat(32))
        return ReservedTurnHandler(
            w,
            registry = CommandRegistry(GeneralActionPipeline()),
            hiddenSeed = "0".repeat(32),
            startYear = 184,
            nextRuler = { gid, e -> succession.succeed(gid, e) },
            recorder = recorder,
        )
    }

    @Test
    fun `ruler death promotes the heir (fallback) and pushes the 유지 history log`() {
        // ruler npc=0 → NPC 경로 미발동 → 폴백1(officer_level>=9, npc!=5) → heir id=2 승계.
        val ruler = gen(id = 1, npc = 0, killturn = 0, officerLevel = 12)
        val heir = gen(id = 2, npc = 0, killturn = 12, officerLevel = 9)
        val w = world(listOf(ruler, heir))
        val h = successionHandler(w)

        h.updateTurnTime(1, env())

        // 후계 officer_level=12, officer_city=0.
        assertEquals(12, w.getGeneralById(2)!!.officerLevel)
        assertEquals(0, (w.getGeneralById(2)!!.meta["officer_city"] as? Number)?.toInt())
        val dirty = w.consumeDirtyState()
        // 죽은 군주는 tombstone.
        assertEquals(listOf(1), dirty.deletedGenerals)
        // 【유지】 global-history byte-exact (josa pick(g2,'이') → '가': '2'=이, 받침 없음).
        assertTrue(
            dirty.logs.any {
                it.scope == "global" && it.category == "history" &&
                    it.text == "<C><b>【유지】</b></><Y>g2</>가 <D><b>n1</b></>의 유지를 이어 받았습니다"
            },
            "【유지】 global-history log byte-exact",
        )
    }

    @Test
    fun `lone ruler death deletes the nation with 멸망 logs`() {
        // 후계 후보 0 (군주 혼자) → deleteNation 멸망 cascade.
        val ruler = gen(id = 1, npc = 0, killturn = 0, officerLevel = 12)
        val w = world(listOf(ruler))
        val h = successionHandler(w)

        h.updateTurnTime(1, env())

        val dirty = w.consumeDirtyState()
        // 국가 삭제.
        assertTrue(dirty.deletedNations.contains(1), "nation tombstoned")
        // 군주도 삭제(kill tombstone).
        assertEquals(listOf(1), dirty.deletedGenerals)
        // 【멸망】 global-history (josa pick(n1,'은') → '은': '1'=일, ㄹ받침).
        assertTrue(
            dirty.logs.any {
                it.scope == "global" && it.category == "history" &&
                    it.text == "<R><b>【멸망】</b></><D><b>n1</b></>은 <R>멸망</>했습니다."
            },
            "【멸망】 global-history log byte-exact",
        )
        // 장수별 멸망 action 로그(군주, josa pick(n1,'이') → '이').
        assertTrue(
            dirty.logs.any {
                it.scope == "general" && it.category == "action" &&
                    it.text == "<D><b>n1</b></>이 <R>멸망</>했습니다."
            },
            "per-general 멸망 action log byte-exact",
        )
    }

    @Test
    fun `NPC ruler death draws an heir from the npc candidate pool deterministically`() {
        // ruler npc=2(NPC) → NPC 경로 발동: npc 1..3, officer_level!=12 후보에서 시드 결정 추첨.
        val ruler = gen(id = 1, npc = 2, killturn = 0, officerLevel = 12, extraMeta = mapOf("affinity" to 50))
        val c2 = gen(id = 2, npc = 2, killturn = 12, officerLevel = 5, extraMeta = mapOf("affinity" to 50))
        val c3 = gen(id = 3, npc = 2, killturn = 12, officerLevel = 5, extraMeta = mapOf("affinity" to 50))
        val w = world(listOf(ruler, c2, c3))
        val h = successionHandler(w)

        h.updateTurnTime(1, env())

        // 후보 둘 중 하나가 군주(12)로 승계 — 결정적(시드 고정).
        val promoted = listOf(2, 3).filter { w.getGeneralById(it)?.officerLevel == 12 }
        assertEquals(1, promoted.size, "exactly one npc heir promoted")
        // 죽은 군주 tombstone.
        assertEquals(listOf(1), w.consumeDirtyState().deletedGenerals)
    }
}
