package opensamguk.engine.turn

import opensamguk.common.josa.JosaUtil
import opensamguk.common.rng.LiteHashDrbg
import opensamguk.common.rng.RandUtil
import opensamguk.common.rng.serializeSeed
import opensamguk.logic.actions.intake.RulerSuccession

/**
 * 군주 사망 후계 — PHP `func.php:1807 nextRuler` + `func.php:1713 deleteNation`의 엔진 포트.
 *
 * [ReservedTurnHandler.kill]이 `officer_level==12`(군주) 사망 시 `nextRuler` 훅으로 위임한다
 * (`General.php:554-558`). 여기서:
 *  1. **NPC 후계 경로**(`!fiction && npc>0`): `npcmatch2` 친화도 정렬 + **버그 재현 동률수집**
 *     ([RulerSuccession]) → `RandUtil(LiteHashDRBG(simpleSerialize(hiddenSeed,'NextNPCRuler',year,month,
 *     rulerID))).choice(...)` **단 1뽑**. 이 뽑기 + 버그가 draw-for-draw 패리티 타깃.
 *  2. **폴백 1**: `officer_level>=9 && npc!=5` ORDER BY officer_level DESC LIMIT 1 (뽑기 없음).
 *  3. **폴백 2**: `npc!=5` ORDER BY dedication DESC LIMIT 1 (뽑기 없음).
 *  4. 후계 없으면 [deleteNation] 멸망 cascade.
 *
 * 후계 승계: 후계 `officer_level=12, officer_city=0` 승격 + 【유지】 global/general 히스토리 로그.
 * 죽는 군주의 `officer_level=1`은 [ReservedTurnHandler.kill]이 훅 반환 후 적용하고(행은 곧 tombstone),
 * `officer_city`는 삭제될 행이라 관측 불가 → 여기서 군주는 변이하지 않는다(폴백 SQL의 동률 2차정렬은
 * `id ASC` 결정적 대체 — 실제 MySQL 미지정 순서; nextRuler 골든 캡처 전까지 문서화된 가정).
 *
 * 멸망 cascade는 [ChangeRecorder.markNationDeleted](도시 공백화 + ng_old_nations 스냅샷 + 국가/외교 삭제)을
 * 재사용하고, 전 장수를 재야로 리셋한다(markNationDeleted는 장수를 건드리지 않음).
 */
class RulerSuccessionHandler(
    private val world: InMemoryTurnWorld,
    private val recorder: ChangeRecorder,
    /** 게임당 `UniqueConst::$hiddenSeed` — NextNPCRuler 시드 구성요소 1(ReservedTurnHandler와 동일 입력). */
    private val hiddenSeed: String,
) {

    /** [ReservedTurnHandler.nextRuler] 훅 본체. dyingRulerId = 사망 군주(officer_level==12). */
    fun succeed(dyingRulerId: Int, env: LifecycleEnv) {
        val ruler = world.getGeneralById(dyingRulerId) ?: return
        val nation = world.getNationById(ruler.nationId) ?: return
        val nationId = nation.id
        val year = env.year
        val month = env.month
        val fiction = (world.getState().meta["fiction"] as? Number)?.toInt() ?: 0

        var heir: TurnGeneral? = null

        // ── 1. NPC 후계 경로 (!fiction && getNPCType()>0) ──
        if (fiction == 0 && ruler.npcState > 0) {
            val rulerAffinity = metaInt(ruler, "affinity")
            // SELECT … WHERE nation=X AND officer_level!=12 AND 1<=npc<=3 ORDER BY npcmatch2 ASC.
            // 2차정렬 id ASC = 결정적 대체(실제 SQL 미지정).
            val raw = world.listGenerals()
                .filter { it.nationId == nationId && it.officerLevel != 12 && it.npcState in 1..3 }
                .map { it to RulerSuccession.npcMatch2(metaInt(it, "affinity"), rulerAffinity) }
                .sortedWith(compareBy({ it.second }, { it.first.id }))
            if (raw.isNotEmpty()) {
                val tied = RulerSuccession.collectTiedCandidates(raw) { it.second }
                val rng = RandUtil(
                    LiteHashDrbg(serializeSeed(hiddenSeed, "NextNPCRuler", year, month, dyingRulerId)),
                )
                heir = rng.choice(tied).first
            }
        }

        // ── 2. 폴백 1: officer_level>=9 && npc!=5, ORDER BY officer_level DESC LIMIT 1 ──
        if (heir == null) {
            heir = world.listGenerals()
                .filter { it.nationId == nationId && it.officerLevel != 12 && it.officerLevel >= 9 && it.npcState != 5 }
                .sortedWith(compareByDescending<TurnGeneral> { it.officerLevel }.thenBy { it.id })
                .firstOrNull()
        }

        // ── 3. 폴백 2: npc!=5, ORDER BY dedication DESC LIMIT 1 ──
        if (heir == null) {
            heir = world.listGenerals()
                .filter { it.nationId == nationId && it.officerLevel != 12 && it.npcState != 5 }
                .sortedWith(compareByDescending<TurnGeneral> { it.dedication }.thenBy { it.id })
                .firstOrNull()
        }

        // ── 4. 후계 없음 → 멸망 ──
        if (heir == null) {
            deleteNation(ruler, nation, env)
            return
        }

        // ── 후계 승격: officer_level=12, officer_city=0 + 【유지】 로그 ──
        val heirPre = PerTurnOverlay.toLogicGeneral(heir)
        val heirNext = heir.copy(officerLevel = 12, meta = withMeta(heir.meta, "officer_city" to 0))
        world.applyGeneralDirtyFree(heirNext)
        recorder.diffGeneral(heirPre, PerTurnOverlay.toLogicGeneral(heirNext))

        val heirName = heir.name
        val nationName = nation.name
        val josaYi = JosaUtil.pick(heirName, "이")
        world.pushLog(
            LogEntryDraft(
                "global", "history",
                "<C><b>【유지】</b></><Y>$heirName</>$josaYi <D><b>$nationName</b></>의 유지를 이어 받았습니다",
                generalId = heir.id, nationId = nationId,
            ),
        )
        world.pushLog(
            LogEntryDraft(
                "general", "history",
                "<C><b>【유지】</b></><Y>$heirName</>$josaYi <D><b>$nationName</b></>의 유지를 이어 받음.",
                generalId = heir.id, nationId = nationId,
            ),
        )
    }

    /**
     * deleteNation (func.php:1713) — 후계 없는 국가 멸망. 로그 순서(패리티): global 【멸망】 →
     * (markNationDeleted: 도시 공백화 + ng_old_nations 스냅샷 + 국가/외교 삭제) → 전 장수 재야 리셋 +
     * 장수별 멸망 action/history 로그(타 장수 id ASC, 군주 마지막). 죽는 군주도 재야 리셋되나 직후
     * [ReservedTurnHandler.kill]이 dying message + tombstone 한다.
     */
    private fun deleteNation(lord: TurnGeneral, nation: Nation, env: LifecycleEnv) {
        val nationId = nation.id
        val nationName = nation.name

        // 1. global-history 【멸망】 (FIRST).
        val josaUn = JosaUtil.pick(nationName, "은")
        world.pushLog(
            LogEntryDraft(
                "global", "history",
                "<R><b>【멸망】</b></><D><b>$nationName</b></>$josaUn <R>멸망</>했습니다.",
                nationId = nationId,
            ),
        )

        // 멸망 대상 장수 순서: 타 장수 id ASC + 군주 마지막 (func.php:1735).
        val orderedIds = world.listGenerals()
            .filter { it.nationId == nationId && it.id != lord.id }
            .map { it.id }.sorted() + lord.id

        // 2. 국가 tombstone — 장수가 아직 nation=X일 때 스냅샷(ng_old_nations) + 도시 공백화 + 국가/외교 삭제.
        recorder.markNationDeleted(world, nationId)

        // 3. 전 장수 재야 리셋 + 장수별 멸망 로그.
        val josaYi = JosaUtil.pick(nationName, "이")
        val destroyLog = "<D><b>$nationName</b></>$josaYi <R>멸망</>했습니다."
        val destroyHistory = "<D><b>$nationName</b></>$josaYi <R>멸망</>"
        for (gid in orderedIds) {
            val g = world.getGeneralById(gid) ?: continue
            val pre = PerTurnOverlay.toLogicGeneral(g)
            // max_belong aux 갱신 (NPCType<2).
            var nextMeta = g.meta
            if (g.npcState < 2) {
                val belong = metaInt(g, "belong")
                val curAux = (g.meta["aux"] as? Map<*, *>)?.entries
                    ?.associate { it.key.toString() to it.value } ?: emptyMap()
                val prevMax = (curAux["max_belong"] as? Number)?.toInt() ?: 0
                val nextAux = LinkedHashMap(curAux)
                nextAux["max_belong"] = maxOf(belong, prevMax)
                nextMeta = withMeta(g.meta, "aux" to nextAux)
            }
            // belong=0, troop=0, officer_level=0, officer_city=0, nation=0, permission='normal'.
            val next = g.copy(
                officerLevel = 0,
                nationId = 0,
                troopId = 0,
                meta = withMeta(nextMeta, "belong" to 0, "officer_city" to 0, "permission" to "normal"),
            )
            world.applyGeneralDirtyFree(next)
            recorder.diffGeneral(pre, PerTurnOverlay.toLogicGeneral(next))
            world.pushLog(LogEntryDraft("general", "action", destroyLog, generalId = gid, nationId = 0))
            world.pushLog(LogEntryDraft("general", "history", destroyHistory, generalId = gid, nationId = 0))
        }
    }

    // ── meta 헬퍼 (ReservedTurnHandler companion과 동일 규약, private라 로컬 복제) ──
    private fun metaInt(g: TurnGeneral, key: String): Int = (g.meta[key] as? Number)?.toInt() ?: 0

    private fun withMeta(meta: Map<String, Any?>, vararg pairs: Pair<String, Any?>): Map<String, Any?> {
        val next = LinkedHashMap(meta)
        for ((k, v) in pairs) next[k] = v
        return next
    }
}
