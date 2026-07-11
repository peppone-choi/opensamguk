package opensamguk.engine.tournament

import com.fasterxml.jackson.databind.ObjectMapper
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.LogEntryDraft
import opensamguk.engine.turn.PerTurnOverlay
import opensamguk.engine.turn.TurnGeneral
import opensamguk.infra.entity.GameKvEntity
import opensamguk.infra.read.GameKvRepository
import opensamguk.logic.tournament.InMemoryTournamentStore
import opensamguk.logic.tournament.TournamentBettingPort
import opensamguk.logic.tournament.TournamentEntry
import opensamguk.logic.tournament.TournamentProcessResult
import opensamguk.logic.tournament.TournamentProcessor
import opensamguk.logic.tournament.TournamentState
import opensamguk.logic.tournament.calcTournamentTermSeconds
import opensamguk.logic.tournament.tournamentTypeText
import java.time.Instant

class TournamentDaemon(
    private val gameKvRepository: GameKvRepository,
    private val objectMapper: ObjectMapper = ObjectMapper(),
    private val bettingFactory: (InMemoryTurnWorld, ChangeRecorder) -> TournamentBettingPort,
) {
    fun processTournament(world: InMemoryTurnWorld, recorder: ChangeRecorder, now: Instant): TournamentProcessResult {
        val kv = TournamentKv(gameKvRepository.findByTable("game_env"), objectMapper)
        val state = kv.state() ?: return TournamentProcessResult(
            TournamentState(0, 0, 0, auto = false, time = null, turnTermMinutes = world.getState().tickSeconds / 60),
            changed = false,
        )
        if (state.tournament == 1 && kv.entries().isEmpty()) {
            recorder.recordTournamentEntries(seedEntries(world))
        }

        val latestKv = if (state.tournament == 1 && kv.entries().isEmpty()) {
            kv.withEntries(seedEntries(world))
        } else {
            kv
        }
        val store = InMemoryTournamentStore(latestKv.entries().associateBy { it.id })
        val result = TournamentProcessor(store, bettingFactory(world, recorder)).process(state, now)
        if (!result.changed) return result

        recorder.recordTournamentState(result.state)
        recorder.recordTournamentEntries(store.entries())
        return result
    }

    private fun seedEntries(world: InMemoryTurnWorld): List<TournamentEntry> {
        val entrants = world.listGenerals()
            .filter { (it.meta["tnmt"] as? Number)?.toInt() == 1 }
            .sortedWith(compareByDescending<TurnGeneral> { it.stats.leadership + it.stats.strength + it.stats.intelligence }.thenBy { it.id })
            .take(64)
            .mapIndexed { index, general -> general.toTournamentEntry(seq = index + 1) }
            .toMutableList()

        var nextId = -1
        while (entrants.size < 64) {
            val idx = entrants.size
            entrants += TournamentEntry(
                id = nextId--,
                npc = 2,
                name = "무명장수",
                leadership = 10,
                strength = 10,
                intel = 10,
                level = 10,
                group = idx % 8,
                groupNo = idx / 8,
                seq = idx + 1,
            )
        }
        return entrants
    }
}

class TournamentAdminService(
) {
    fun startTournament(world: InMemoryTurnWorld, recorder: ChangeRecorder, type: Int, now: Instant): TournamentState {
        val state = world.getState()
        val turnTerm = state.tickSeconds / 60
        val unit = calcTournamentTermSeconds(turnTerm)
        val next = TournamentState(
            tournament = 1,
            phase = 0,
            type = type.coerceIn(0, 3),
            auto = true,
            time = now.plusSeconds(unit.toLong() * 60L),
            turnTermMinutes = turnTerm,
            lastBettingId = 0,
        )

        for (general in world.listGenerals()) {
            val pre = PerTurnOverlay.toLogicGeneral(general)
            val meta = LinkedHashMap(general.meta)
            meta["tournament"] = 0
            val post = general.copy(meta = meta)
            world.applyGeneralDirtyFree(post)
            recorder.diffGeneral(pre, PerTurnOverlay.toLogicGeneral(post))
        }

        recorder.recordTournamentState(next)
        recorder.recordTournamentEntries(emptyList())
        recorder.recordTournamentLogs(emptyMap())
        val opener = world.listGenerals()
            .asSequence()
            .filter { it.officerLevel == 12 }
            .mapNotNull { general ->
                world.getNationById(general.nationId)
                    ?.takeIf { it.level == 7 }
                    ?.let { general.name }
            }
            .firstOrNull()
            ?: state.meta["prev_winner"]?.toString()?.takeIf { it.isNotBlank() }
        val openerText = opener?.let { "황제 <Y>$it</>의 명으로 " } ?: ""
        val genTypeText = listOf("영웅", "명사", "용사", "책사").getOrElse(type) { "영웅" }
        world.pushLog(
            LogEntryDraft(
                scope = "global",
                category = "history",
                text = "<S>◆</>${state.currentYear}년 ${state.currentMonth}월:<B><b>【대회】</b></>$openerText<C>${tournamentTypeText(type)}</> 대회가 개최됩니다! 천하의 <span class='ev_highlight'>$genTypeText</span>들을 모집하고 있습니다!",
            ),
        )
        return next
    }

    fun resetTournament(recorder: ChangeRecorder, current: TournamentState? = null): TournamentState {
        val next = (current ?: TournamentState(0, 0, 0, false, null, 0)).copy(
            tournament = 0,
            phase = 0,
            auto = false,
            lastBettingId = 0,
        )
        recorder.recordTournamentState(next)
        recorder.recordTournamentEntries(emptyList())
        recorder.recordTournamentLogs(emptyMap())
        return next
    }
}

private class TournamentKv(
    private val rows: List<GameKvEntity>,
    private val objectMapper: ObjectMapper,
) {
    fun state(): TournamentState? {
        val tournament = int("tournament", 0)
        val phase = int("phase", 0)
        val type = int("tnmt_type", 0)
        val auto = bool("tnmt_auto", false)
        val time = text("tnmt_time", "").takeIf { it.isNotBlank() }?.let { runCatching { Instant.parse(it) }.getOrNull() }
        val turnTerm = int("turnterm", 0)
        val lastBettingId = int("last_tournament_betting_id", 0)
        if (tournament == 0 && !auto && time == null) return null
        return TournamentState(tournament, phase, type, auto, time, turnTerm, lastBettingId)
    }

    fun entries(): List<TournamentEntry> =
        row("tournament_entries")?.value?.let {
            runCatching {
                objectMapper.readTree(it).map { node ->
                    TournamentEntry(
                        id = node.path("id").asInt(),
                        npc = node.path("npc").asInt(),
                        name = node.path("name").asText(""),
                        leadership = node.path("leadership").asInt(),
                        strength = node.path("strength").asInt(),
                        intel = node.path("intel").asInt(),
                        level = node.path("level").asInt(),
                        group = node.path("group").asInt(),
                        groupNo = node.path("groupNo").asInt(),
                        win = node.path("win").asInt(),
                        draw = node.path("draw").asInt(),
                        lose = node.path("lose").asInt(),
                        goal = node.path("goal").asInt(),
                        promote = node.path("promote").asInt(),
                        seq = node.path("seq").asInt(),
                        horse = node.path("horse").asText("None"),
                        weapon = node.path("weapon").asText("None"),
                        book = node.path("book").asText("None"),
                    )
                }
            }.getOrDefault(emptyList())
        } ?: emptyList()

    fun withEntries(entries: List<TournamentEntry>): TournamentKv {
        val merged = rows.filterNot { it.namespace == "game_env" && it.key == "tournament_entries" }.toMutableList()
        merged += GameKvEntity(table = "game_env", namespace = "game_env", key = "tournament_entries", value = objectMapper.writeValueAsString(entries))
        return TournamentKv(merged, objectMapper)
    }

    private fun int(key: String, default: Int): Int =
        row(key)?.value?.let { runCatching { objectMapper.readTree(it).asInt(default) }.getOrDefault(default) } ?: default

    private fun bool(key: String, default: Boolean): Boolean =
        row(key)?.value?.let { runCatching { objectMapper.readTree(it).asBoolean(default) }.getOrDefault(default) } ?: default

    private fun text(key: String, default: String): String =
        row(key)?.value?.let {
            runCatching {
                val node = objectMapper.readTree(it)
                if (node.isTextual) node.asText() else node.toString()
            }.getOrDefault(default)
        } ?: default

    private fun row(key: String): GameKvEntity? =
        rows.firstOrNull { it.namespace == "game_env" && it.key == key }
            ?: rows.firstOrNull { it.namespace == "global" && it.key == key }
}

private fun TurnGeneral.toTournamentEntry(seq: Int): TournamentEntry =
    TournamentEntry(
        id = id,
        npc = npcState,
        name = name,
        leadership = stats.leadership,
        strength = stats.strength,
        intel = stats.intelligence,
        level = (meta["explevel"] as? Number)?.toInt() ?: 0,
        group = (seq - 1) % 8,
        groupNo = (seq - 1) / 8,
        seq = seq,
        horse = role.items.horse ?: "None",
        weapon = role.items.weapon ?: "None",
        book = role.items.book ?: "None",
    )

private fun ChangeRecorder.recordTournamentState(state: TournamentState) {
    recordKv("game_env", "game_env", "tournament", state.tournament)
    recordKv("game_env", "game_env", "phase", state.phase)
    recordKv("game_env", "game_env", "tnmt_type", state.type)
    recordKv("game_env", "game_env", "tnmt_auto", state.auto)
    recordKv("game_env", "game_env", "tnmt_time", state.time?.toString())
    recordKv("game_env", "game_env", "turnterm", state.turnTermMinutes)
    recordKv("game_env", "game_env", "last_tournament_betting_id", state.lastBettingId)
}

private fun ChangeRecorder.recordTournamentEntries(entries: List<TournamentEntry>) {
    recordKv("game_env", "game_env", "tournament_entries", entries)
}

private fun ChangeRecorder.recordTournamentLogs(logs: Map<Int, List<String>>) {
    recordKv("game_env", "game_env", "tournament_logs", logs)
}
