package opensamguk.engine.boot

import opensamguk.common.world.WorldId
import opensamguk.infra.persistence.ReservedTurnRepository
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate

internal class FullRehydrateWorldSeeder(
    private val jdbc: JdbcTemplate,
    private val namedJdbc: NamedParameterJdbcTemplate,
    private val config: FullRehydrateFixtureConfig,
) {
    fun seedWorld(worldId: WorldId, worldName: String, generalName: String) {
        val worldMeta =
            """{"startYear":${config.startYear},"startTime":"${config.start}","hiddenSeed":"${config.hiddenSeed}","mapName":"che"}"""
        jdbc.update(
            """
            INSERT INTO world_state
                (id, scenario_code, current_year, current_month, current_phase, tick_seconds, start_time, meta)
            VALUES (?, 'scenario_0', ?, 1, 1, 3600, CAST(? AS timestamptz), CAST(? AS jsonb))
            """.trimIndent(),
            worldId.value,
            config.startYear,
            config.start.toString(),
            worldMeta,
        )
        jdbc.update(
            """
            INSERT INTO game_kv (world_id, "table", namespace, key, value)
            VALUES (?, 'game_env', '', 'currentYear', '999'::jsonb),
                   (?, 'game_env', '', 'currentMonth', '99'::jsonb),
                   (?, 'game_env', '', 'currentPhase', '3'::jsonb)
            """.trimIndent(),
            worldId.value,
            worldId.value,
            worldId.value,
        )
        jdbc.update(
            """
            INSERT INTO nation (world_id, id, name, color, capital_city_id, gold, rice, tech, power, level, type_code)
            VALUES (?, ?, ?, '#123456', ?, 1000, 1000, 0, 0, 2, 'normal'),
                   (?, ?, ?, '#654321', 0, 0, 0, 0, 0, 1, 'normal')
            """.trimIndent(),
            worldId.value,
            config.nationId,
            "$worldName 제일국",
            config.cityId,
            worldId.value,
            config.secondNationId,
            "$worldName 제이국",
        )
        jdbc.update(
            """
            INSERT INTO city
                (world_id, id, name, level, nation_id, supply_state, front_state, pop, pop_max,
                 agri, agri_max, comm, comm_max, secu, secu_max, trust, trade, def, def_max,
                 wall, wall_max, region, meta)
            VALUES
                (?, ?, ?, 5, ?, 1, 0, 50000, 100000,
                 1000, 20000, 1000, 20000, 500, 1000, 50, 100, 1000, 2000,
                 1000, 2000, 1, CAST('{"trust":50}' AS jsonb))
            """.trimIndent(),
            worldId.value,
            config.cityId,
            "$worldName 도시",
            config.nationId,
        )
        jdbc.update(
            """
            INSERT INTO general
                (world_id, id, name, nation_id, city_id, leadership, strength, intel, injury,
                 experience, dedication, officer_level, gold, rice, turn_time, meta)
            VALUES
                (?, ?, ?, ?, ?, 70, 70, 80, 0, 1000, 1000, 0, 100000, 1000, CAST(? AS timestamptz),
                 CAST('{"explevel":10,"dedlevel":4,"intel_exp":3,"max_domestic_critical":0,"killturn":80}' AS jsonb))
            """.trimIndent(),
            worldId.value,
            config.generalId,
            generalName,
            config.nationId,
            config.cityId,
            config.start.toString(),
        )
        jdbc.update(
            """
            INSERT INTO rank_data (world_id, nation_id, general_id, type, value)
            VALUES (?, ?, ?, 'kill', 7)
            """.trimIndent(),
            worldId.value,
            config.nationId,
            config.generalId,
        )
        jdbc.update(
            """
            INSERT INTO troop (world_id, troop_leader, nation, name)
            VALUES (?, ?, ?, ?)
            """.trimIndent(),
            worldId.value,
            config.generalId,
            config.nationId,
            "$worldName 부대",
        )
        jdbc.update(
            """
            INSERT INTO diplomacy (world_id, src_nation_id, dest_nation_id, state_code, term, meta)
            VALUES (?, ?, ?, 1, 3, '{}'::jsonb)
            """.trimIndent(),
            worldId.value,
            config.nationId,
            config.secondNationId,
        )
        jdbc.update(
            """
            INSERT INTO general_access_log
                (world_id, general_id, user_id, last_refresh, refresh, refresh_total, refresh_score, refresh_score_total)
            VALUES (?, ?, 77, CAST(? AS timestamptz), 1, 2, 3, 4)
            """.trimIndent(),
            worldId.value,
            config.generalId,
            config.start.toString(),
        )
    }

    fun reserveTwoTurns(worldId: WorldId, requestPrefix: String) {
        val turns = ReservedTurnRepository(namedJdbc)
        turns.reserve(worldId, config.generalId, 0, "che_기술연구", requestId = "$requestPrefix-1")
        turns.reserve(worldId, config.generalId, 1, "che_농지개간", requestId = "$requestPrefix-2")
    }
}
