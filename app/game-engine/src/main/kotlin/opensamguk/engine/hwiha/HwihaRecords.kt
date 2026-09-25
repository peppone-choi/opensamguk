package opensamguk.engine.hwiha

import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.LogEntryDraft
import opensamguk.logic.input.RecordKind

/**
 * 휘하 기록을 쓰는 한 곳 — 종류([RecordKind])와 식별자(`meta.refs`)를 같은 모양으로 싣는다.
 *
 * 연·월·순은 [InMemoryTurnWorld.pushLog] 가 현재 세계 시각으로 찍는다(V22 `phase`). 쓰기는 기존 로그와 같은
 * flush 에 실린다(`DatabaseHooks.toLogRow` → `JdbcFlushExecutor`) — 따로 쓰는 길이 없다.
 *
 * 누가 무엇을 받는가는 부르는 쪽이 정한다. [general] 은 그 장수가 알아도 되는 것만 싣는다(#343).
 */
internal object HwihaRecords {
    /** 장수 한 명의 개인 기록(`scope=GENERAL`, `category=ACTION`). */
    fun general(
        world: InMemoryTurnWorld,
        generalId: Int,
        kind: String,
        text: String,
        refs: Map<String, Any?> = emptyMap(),
        nationId: Int? = world.getGeneralById(generalId)?.nationId,
    ) = world.pushLog(
        LogEntryDraft(
            scope = "general", category = "action", text = text, generalId = generalId, nationId = nationId,
            meta = meta(refs), eventKind = kind,
        ),
    )

    /**
     * 세력 앞 기록(`scope=NATION`). 공개 사건([RecordKind.NATION_SUMMARY_KINDS])은 `HISTORY`,
     * 세력 내부 정보는 `SUMMARY` 에 둔다 — 기존 국가 연혁 조회(`NATION`·`HISTORY`)에 내부 정보가 섞이지 않게.
     */
    fun nation(world: InMemoryTurnWorld, nationId: Int, kind: String, text: String, refs: Map<String, Any?> = emptyMap()) =
        world.pushLog(
            LogEntryDraft(
                scope = "nation",
                category = if (kind in RecordKind.NATION_SUMMARY_KINDS) "history" else "summary",
                text = text, nationId = nationId, meta = meta(refs), eventKind = kind,
            ),
        )

    /** 세계 공개 기록(`scope=SYSTEM`, `category=HISTORY` — 중원 정세). 모두가 봐도 되는 것만. */
    fun world(world: InMemoryTurnWorld, kind: String, text: String, refs: Map<String, Any?> = emptyMap()) =
        world.pushLog(
            LogEntryDraft(scope = "global", category = "history", text = text, meta = meta(refs), eventKind = kind),
        )

    private fun meta(refs: Map<String, Any?>): Map<String, Any?> =
        linkedMapOf(RecordKind.REFS_META_KEY to LinkedHashMap(refs))
}
