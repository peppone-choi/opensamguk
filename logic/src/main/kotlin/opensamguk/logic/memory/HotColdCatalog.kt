package opensamguk.logic.memory

object HotColdCatalog {
    const val version: String = "ARCH-S5-T2-2026-07-23"
    const val designSource: String =
        "docs/superpowers/plans/2026-07-23-opensam-138-bounded-boot-design.md"

    val snapshotAccesses: List<SnapshotAccess> = listOf(
        SnapshotAccess(
            methodName = "loadHistoricalMapPins",
            relation = "water_zone_control/province_control/general_spatial_position",
            temperature = DataTemperature.ALWAYS_HOT,
            boundary = AccessBoundary.BOOT_SNAPSHOT,
            ordering = "configured world_id exact in every channel; order independent identity checks",
            bound = AccessBound.HOT_ENTITY_SET,
        ),
        SnapshotAccess(
            methodName = "loadProvinceControlSnapshot",
            relation = "province_control",
            temperature = DataTemperature.ALWAYS_HOT,
            boundary = AccessBoundary.BOOT_SNAPSHOT,
            ordering = "configured world_id exact, province_id ASC",
            bound = AccessBound.HOT_ENTITY_SET,
        ),
        SnapshotAccess(
            methodName = "loadGeneralPositionSnapshot",
            relation = "general_spatial_position",
            temperature = DataTemperature.ALWAYS_HOT,
            boundary = AccessBoundary.BOOT_SNAPSHOT,
            ordering = "configured world_id exact, general_id ASC",
            bound = AccessBound.HOT_ENTITY_SET,
        ),
        SnapshotAccess(
            methodName = "loadWaterControlSnapshot",
            relation = "water_zone_control",
            temperature = DataTemperature.ALWAYS_HOT,
            boundary = AccessBoundary.BOOT_SNAPSHOT,
            ordering = "configured world_id exact, water_zone_id ASC",
            bound = AccessBound.HOT_ENTITY_SET,
        ),
        SnapshotAccess(
            methodName = "loadWorldState",
            relation = "world_state",
            temperature = DataTemperature.ALWAYS_HOT,
            boundary = AccessBoundary.BOOT_SNAPSHOT,
            ordering = "configured world_state.id",
            bound = AccessBound.SINGLE_ROW,
        ),
        SnapshotAccess(
            methodName = "loadGameEnv",
            relation = "game_kv:game_env",
            temperature = DataTemperature.ALWAYS_HOT,
            boundary = AccessBoundary.BOOT_SNAPSHOT,
            ordering = "game_kv.id ASC",
            bound = AccessBound.HOT_KEYSET,
            followUp = "S5-T2 should split this into a world-scoped bounded projection.",
        ),
        SnapshotAccess(
            methodName = "resolveActiveGame",
            relation = "ng_games",
            temperature = DataTemperature.QUERY_ONLY_COLD,
            boundary = AccessBoundary.BOOT_SNAPSHOT,
            ordering = "configured server_id or id ASC singleton fallback",
            bound = AccessBound.SINGLE_ROW,
        ),
        SnapshotAccess(
            methodName = "loadServerCount",
            relation = "ng_games",
            temperature = DataTemperature.QUERY_ONLY_COLD,
            boundary = AccessBoundary.BOOT_SNAPSHOT,
            ordering = "count aggregate",
            bound = AccessBound.AGGREGATE,
        ),
        SnapshotAccess(
            methodName = "loadStoredUniqueItemCounts",
            relation = "game_kv:unique item counts",
            temperature = DataTemperature.QUERY_ONLY_COLD,
            boundary = AccessBoundary.BOOT_SNAPSHOT,
            ordering = "namespace ASC",
            bound = AccessBound.AGGREGATE,
        ),
        SnapshotAccess(
            methodName = "loadInheritancePoints",
            relation = "game_kv:inheritance",
            temperature = DataTemperature.QUERY_ONLY_COLD,
            boundary = AccessBoundary.BOOT_SNAPSHOT,
            ordering = "active owner id ASC, game_kv.id ASC",
            bound = AccessBound.ACTIVE_OWNER_SET,
            followUp = "S5-T2 keeps only active general owners as the boot fallback; command paths use exact readers.",
        ),
        SnapshotAccess(
            methodName = "loadNationEnv",
            relation = "nation_env",
            temperature = DataTemperature.ALWAYS_HOT,
            boundary = AccessBoundary.BOOT_SNAPSHOT,
            ordering = "nation_env.id ASC",
            bound = AccessBound.HOT_KEYSET,
            followUp = "S5-T2 should make the world scope explicit when this cohort is bounded.",
        ),
        SnapshotAccess(
            methodName = "loadNations",
            relation = "nation",
            temperature = DataTemperature.ALWAYS_HOT,
            boundary = AccessBoundary.BOOT_SNAPSHOT,
            ordering = "nation.id ASC",
            bound = AccessBound.HOT_ENTITY_SET,
        ),
        SnapshotAccess(
            methodName = "loadTroops",
            relation = "troop",
            temperature = DataTemperature.ALWAYS_HOT,
            boundary = AccessBoundary.BOOT_SNAPSHOT,
            ordering = "troop_leader ASC",
            bound = AccessBound.HOT_ENTITY_SET,
        ),
        SnapshotAccess(
            methodName = "loadRetainers",
            relation = "general_retainers",
            temperature = DataTemperature.ALWAYS_HOT,
            boundary = AccessBoundary.BOOT_SNAPSHOT,
            ordering = "id ASC",
            bound = AccessBound.HOT_ENTITY_SET,
        ),
        SnapshotAccess(
            methodName = "loadBugoks",
            relation = "general_bugok",
            temperature = DataTemperature.ALWAYS_HOT,
            boundary = AccessBoundary.BOOT_SNAPSHOT,
            ordering = "id ASC",
            bound = AccessBound.HOT_ENTITY_SET,
        ),
        SnapshotAccess(
            methodName = "loadSieges",
            relation = "siege",
            temperature = DataTemperature.ALWAYS_HOT,
            boundary = AccessBoundary.BOOT_SNAPSHOT,
            ordering = "county_id ASC",
            bound = AccessBound.HOT_ENTITY_SET,
        ),
        SnapshotAccess(
            methodName = "loadOperations",
            relation = "operation",
            temperature = DataTemperature.ALWAYS_HOT,
            boundary = AccessBoundary.BOOT_SNAPSHOT,
            ordering = "id ASC",
            bound = AccessBound.HOT_ENTITY_SET,
        ),
        SnapshotAccess(
            methodName = "loadOperationUnits",
            relation = "operation_unit",
            temperature = DataTemperature.ALWAYS_HOT,
            boundary = AccessBoundary.BOOT_SNAPSHOT,
            ordering = "id ASC",
            bound = AccessBound.HOT_ENTITY_SET,
        ),
        SnapshotAccess(
            methodName = "loadBattlePlans",
            relation = "battle_plan",
            temperature = DataTemperature.ALWAYS_HOT,
            boundary = AccessBoundary.BOOT_SNAPSHOT,
            ordering = "id ASC",
            bound = AccessBound.HOT_ENTITY_SET,
        ),
        SnapshotAccess(
            methodName = "loadCities",
            relation = "city",
            temperature = DataTemperature.ALWAYS_HOT,
            boundary = AccessBoundary.BOOT_SNAPSHOT,
            ordering = "city.id ASC",
            bound = AccessBound.HOT_ENTITY_SET,
        ),
        SnapshotAccess(
            methodName = "loadGenerals",
            relation = "general",
            temperature = DataTemperature.ALWAYS_HOT,
            boundary = AccessBoundary.BOOT_SNAPSHOT,
            ordering = "general.id ASC",
            bound = AccessBound.HOT_ENTITY_SET,
        ),
        SnapshotAccess(
            methodName = "loadRankValues",
            relation = "rank_data",
            temperature = DataTemperature.ALWAYS_HOT,
            boundary = AccessBoundary.BOOT_SNAPSHOT,
            ordering = "general_id ASC, rank_data.id ASC",
            bound = AccessBound.HOT_KEYSET,
            followUp = "S5-T2 scopes this hot cohort to the configured world.",
        ),
        SnapshotAccess(
            methodName = "loadGeneralTurns",
            relation = "general_turn",
            temperature = DataTemperature.ALWAYS_HOT,
            boundary = AccessBoundary.BOOT_SNAPSHOT,
            ordering = "general_id ASC, turn_idx ASC",
            bound = AccessBound.HOT_KEYSET,
        ),
        SnapshotAccess(
            methodName = "loadDiplomacy",
            relation = "diplomacy",
            temperature = DataTemperature.ALWAYS_HOT,
            boundary = AccessBoundary.BOOT_SNAPSHOT,
            ordering = "diplomacy.id ASC",
            bound = AccessBound.HOT_KEYSET,
        ),
        SnapshotAccess(
            methodName = "loadAccessLogs",
            relation = "general_access_log",
            temperature = DataTemperature.ALWAYS_HOT,
            boundary = AccessBoundary.BOOT_SNAPSHOT,
            ordering = "general_id ASC",
            bound = AccessBound.HOT_KEYSET,
        ),
        SnapshotAccess(
            methodName = "loadArchivedNationIds",
            relation = "ng_old_nations",
            temperature = DataTemperature.QUERY_ONLY_COLD,
            boundary = AccessBoundary.BOOT_SNAPSHOT,
            ordering = "nation ASC",
            bound = AccessBound.ACTIVE_SERVER_SET,
        ),
    )

    val runtimeSourceDirectories: List<String> = listOf(
        "app/game-engine/src/main/kotlin/opensamguk/engine/intake",
        "app/game-engine/src/main/kotlin/opensamguk/engine/redis",
        "app/game-engine/src/main/kotlin/opensamguk/engine/run",
        "app/game-engine/src/main/kotlin/opensamguk/engine/turn",
        "app/game-engine/src/main/kotlin/opensamguk/engine/v2",
        "app/game-engine/src/main/kotlin/opensamguk/engine/war",
        "app/game-engine/src/main/kotlin/opensamguk/engine/world",
    )

    val runtimeDirectSqlBoundaries: List<DirectSqlBoundary> = listOf(
        DirectSqlBoundary(
            sourceFile = "infra/src/main/kotlin/opensamguk/infra/persistence/JdbcFlushExecutor.kt",
            relation = "log_entry:NATION/HISTORY,GENERAL/HISTORY",
            temperature = DataTemperature.QUERY_ONLY_COLD,
            boundary = AccessBoundary.COMMAND_OR_MONTH_BOUNDARY,
            bound = AccessBound.EXACT_KEY,
            ordering = "world/scope/entity exact, log_entry.id DESC",
            followUp = "S5-T2 moves archive history reads from boot meta to flush-time exact-key JDBC reads.",
        ),
        DirectSqlBoundary(
            sourceFile = "app/game-engine/src/main/kotlin/opensamguk/engine/v2/V2CityLedgerStore.kt",
            relation = "v2_city_ledger",
            temperature = DataTemperature.QUERY_ONLY_COLD,
            boundary = AccessBoundary.BOOT_SNAPSHOT,
            bound = AccessBound.HOT_KEYSET,
            ordering = "world_id = :world_id exact, city_id ASC",
            followUp = "S5-T2 folds the v2 ledger into the boot snapshot loader once R2 wires it into the loop.",
        ),
    )

    val runtimeReadSeams: List<RuntimeReadSeam> = listOf(
        RuntimeReadSeam(
            sourceFile = "app/game-engine/src/main/kotlin/opensamguk/engine/config/DaemonLoopConfig.kt",
            accessType = "ReservedTurnRepository",
            relation = "general_turn,nation_turn",
            temperature = DataTemperature.QUERY_ONLY_COLD,
            boundary = AccessBoundary.RESERVED_TURN_PHASE,
            bound = AccessBound.EXACT_KEY,
            ordering = "exact reserved slot",
            calls = listOf(
                RuntimeCall("reservedTurnRepository.readReserved", 2),
                RuntimeCall("reservedTurnRepository.readReservedNationTurn"),
            ),
        ),
        RuntimeReadSeam(
            sourceFile = "app/game-engine/src/main/kotlin/opensamguk/engine/config/DaemonLoopConfig.kt",
            accessType = "boot allocators",
            relation = "message,battle_replay",
            temperature = DataTemperature.QUERY_ONLY_COLD,
            boundary = AccessBoundary.BOOT_ALLOCATOR,
            bound = AccessBound.AGGREGATE,
            ordering = "world-scoped max id aggregates",
            calls = listOf(RuntimeCall("messageRepository.findMaxId"), RuntimeCall("battleReplayRepository.findMaxId")),
        ),
        RuntimeReadSeam(
            sourceFile = "app/game-engine/src/main/kotlin/opensamguk/engine/run/TurnRunService.kt",
            accessType = "command lifecycle control plane",
            relation = "command_inbox,command_outbox",
            temperature = DataTemperature.QUERY_ONLY_COLD,
            boundary = AccessBoundary.COMMAND_INTAKE,
            bound = AccessBound.BOUNDED_BATCH,
            ordering = "wake order then inbox lease order",
            calls = listOf(
                RuntimeCall("inbox.claimForExecution"),
                RuntimeCall("inbox.terminalRequestIds"),
                RuntimeCall("inbox.claimPendingForExecution"),
            ),
        ),
        RuntimeReadSeam(
            sourceFile = "app/game-engine/src/main/kotlin/opensamguk/engine/redis/CommandOutboxRelay.kt",
            accessType = "command outbox relay repository boundary",
            relation = "command_outbox",
            temperature = DataTemperature.QUERY_ONLY_COLD,
            boundary = AccessBoundary.COMMAND_OUTBOX_RELAY,
            bound = AccessBound.BOUNDED_BATCH,
            ordering = "published_at NULL, id ASC through repository contract",
            calls = listOf(
                RuntimeCall("repository.findPendingCommandResultOutbox"),
                RuntimeCall("repository.markCommandOutboxPublished"),
            ),
        ),
        RuntimeReadSeam(
            sourceFile = "app/game-engine/src/main/kotlin/opensamguk/engine/run/TurnDaemonCommandDispatcher.kt",
            accessType = "command exact readers",
            relation = "inheritance,game_env,user,vote_poll,message",
            temperature = DataTemperature.QUERY_ONLY_COLD,
            boundary = AccessBoundary.COMMAND_BOUNDARY,
            bound = AccessBound.EXACT_KEY,
            ordering = "exact key or first matching deterministic row",
            calls = listOf(
                RuntimeCall("repo.findByTableAndNamespaceAndKey"),
                RuntimeCall("repo.findByTable", 2),
                RuntimeCall("repo.findPollState"),
                RuntimeCall("reader.findMessage"),
            ),
            followUp = "S5-T2 should replace table-wide KV scans with named bounded projections.",
        ),
        RuntimeReadSeam(
            sourceFile = "app/game-engine/src/main/kotlin/opensamguk/engine/intake/BoardHandler.kt",
            accessType = "board comment exact reader",
            relation = "board_post",
            temperature = DataTemperature.QUERY_ONLY_COLD,
            boundary = AccessBoundary.COMMAND_BOUNDARY,
            bound = AccessBound.EXACT_KEY,
            ordering = "post id and nation id exact match",
            // handleComment + handleRead(ADR-LITE-049 14 기밀실 열람 기록) — 같은 exact reader 2회.
            calls = listOf(RuntimeCall("boardPostRepository.findByIdAndNationId", expectedCount = 2)),
        ),
        RuntimeReadSeam(
            sourceFile = "app/game-engine/src/main/kotlin/opensamguk/engine/intake/DiplomacyLetterHandler.kt",
            accessType = "diplomacy letter exact reader",
            relation = "diplomacy_letter",
            temperature = DataTemperature.QUERY_ONLY_COLD,
            boundary = AccessBoundary.COMMAND_BOUNDARY,
            bound = AccessBound.EXACT_KEY,
            ordering = "letter id exact match",
            calls = listOf(RuntimeCall("repo().findLetter", 5), RuntimeCall("repo().countNewerLetters")),
        ),
        RuntimeReadSeam(
            sourceFile = "app/game-engine/src/main/kotlin/opensamguk/engine/intake/SelectPoolHandler.kt",
            accessType = "select pool command readers",
            relation = "select_pool",
            temperature = DataTemperature.QUERY_ONLY_COLD,
            boundary = AccessBoundary.COMMAND_BOUNDARY,
            bound = AccessBound.ACTIVE_SET,
            ordering = "owner/name exact match plus current pool list order",
            calls = listOf(
                RuntimeCall("repo.listForUser"),
                RuntimeCall("repo.targetGeneralPool"),
                RuntimeCall("repo.listUniqueNames"),
                RuntimeCall("repo.findPoolEntry"),
            ),
        ),
        RuntimeReadSeam(
            sourceFile = "app/game-engine/src/main/kotlin/opensamguk/engine/intake/SelectPoolHandler.kt",
            accessType = "select pool owner profile reader",
            relation = "users",
            temperature = DataTemperature.QUERY_ONLY_COLD,
            boundary = AccessBoundary.COMMAND_BOUNDARY,
            bound = AccessBound.EXACT_KEY,
            ordering = "owner user id exact match",
            calls = listOf(RuntimeCall("repo.findOwnerProfile")),
        ),
        RuntimeReadSeam(
            sourceFile = "app/game-engine/src/main/kotlin/opensamguk/engine/world/WorldActionContext.kt",
            accessType = "world event KV and betting readers",
            relation = "game_kv:game_env,betting;ng_betting",
            temperature = DataTemperature.QUERY_ONLY_COLD,
            boundary = AccessBoundary.COMMAND_OR_MONTH_BOUNDARY,
            bound = AccessBound.ACTIVE_SET,
            ordering = "world-scoped table rows in repository order; betting id exact match",
            calls = listOf(
                RuntimeCall("gameKvRepository.findByTable", 3),
                RuntimeCall("bettingRepository.findByBettingId"),
            ),
            followUp = "S5-T2 should replace table-wide KV scans with named bounded projections.",
        ),
        RuntimeReadSeam(
            sourceFile = "app/game-engine/src/main/kotlin/opensamguk/engine/world/WorldActionContext.kt",
            accessType = "world action inheritance point reader",
            relation = "game_kv:inheritance",
            temperature = DataTemperature.QUERY_ONLY_COLD,
            boundary = AccessBoundary.COMMAND_OR_MONTH_BOUNDARY,
            bound = AccessBound.EXACT_KEY,
            ordering = "inheritance table, owner namespace, and point key exact match",
            calls = listOf(RuntimeCall("inheritanceRepository.findByTableAndNamespaceAndKey")),
        ),
        RuntimeReadSeam(
            sourceFile = "app/game-engine/src/main/kotlin/opensamguk/engine/run/MonthlyPreUpdateHook.kt",
            accessType = "yearbook global log reader",
            relation = "log_entry:SYSTEM/HISTORY,ACTION",
            temperature = DataTemperature.QUERY_ONLY_COLD,
            boundary = AccessBoundary.COMMAND_OR_MONTH_BOUNDARY,
            bound = AccessBound.CURRENT_MONTH_WINDOW,
            ordering = "world/year/month/category exact, log_entry.id DESC",
            calls = listOf(RuntimeCall("archiveHistoryReader.globalLogs")),
            followUp = "S5-T2 removes global logs from boot meta; this is the exact current-month reader.",
        ),
    )

    val snapshotMethodNames: Set<String> = snapshotAccesses.mapTo(linkedSetOf()) { it.methodName }
    val runtimeCallKeys: Set<String> = runtimeReadSeams
        .flatMap { seam -> seam.calls.map { call -> "${seam.sourceFile}:${call.expression}" } }
        .toSet()
    val runtimeCallCounts: Map<String, Int> = runtimeReadSeams
        .flatMap { seam -> seam.calls.map { call -> "${seam.sourceFile}:${call.expression}" to call.expectedCount } }
        .toMap()
    val runtimeDirectSqlBoundarySources: Set<String> =
        runtimeDirectSqlBoundaries.mapTo(linkedSetOf()) { it.sourceFile }
}

data class SnapshotAccess(
    val methodName: String,
    val relation: String,
    val temperature: DataTemperature,
    val boundary: AccessBoundary,
    val ordering: String,
    val bound: AccessBound,
    val followUp: String? = null,
)

data class RuntimeReadSeam(
    val sourceFile: String,
    val accessType: String,
    val relation: String,
    val temperature: DataTemperature,
    val boundary: AccessBoundary,
    val bound: AccessBound,
    val ordering: String,
    val calls: List<RuntimeCall>,
    val activationReady: Boolean = true,
    val followUp: String? = null,
)

data class RuntimeCall(
    val expression: String,
    val expectedCount: Int = 1,
)

data class DirectSqlBoundary(
    val sourceFile: String,
    val relation: String,
    val temperature: DataTemperature,
    val boundary: AccessBoundary,
    val bound: AccessBound,
    val ordering: String,
    val followUp: String,
)

enum class DataTemperature {
    ALWAYS_HOT,
    PHASE_HOT,
    QUERY_ONLY_COLD,
}

enum class AccessBoundary {
    BOOT_ALLOCATOR,
    BOOT_SNAPSHOT,
    COMMAND_BOUNDARY,
    COMMAND_INTAKE,
    COMMAND_OR_MONTH_BOUNDARY,
    COMMAND_OR_PHASE_BOUNDARY,
    COMMAND_OUTBOX_RELAY,
    RESERVED_TURN_PHASE,
}

enum class AccessBound {
    ACTIVE_ENTITY_HISTORY,
    ACTIVE_OWNER_SET,
    ACTIVE_SERVER_SET,
    ACTIVE_SET,
    AGGREGATE,
    BOUNDED_BOOT_PROJECTION,
    BOUNDED_BATCH,
    BOUNDED_ENTITY_HISTORY_WINDOW,
    CURRENT_MONTH_WINDOW,
    EXACT_KEY,
    HOT_ENTITY_SET,
    HOT_KEYSET,
    LEGACY_FULL_SCAN_PENDING_S5_T2,
    SINGLE_ROW,
}
