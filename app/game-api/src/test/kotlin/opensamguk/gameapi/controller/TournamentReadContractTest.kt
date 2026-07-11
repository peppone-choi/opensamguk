package opensamguk.gameapi.controller

import com.fasterxml.jackson.databind.ObjectMapper
import opensamguk.gameapi.read.GameKvReadRepository
import opensamguk.infra.entity.GameKvEntity
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders

class TournamentReadContractTest {
    private val objectMapper = ObjectMapper()
    private val gameKv = mock(GameKvReadRepository::class.java)

    @Test
    fun `tournament exposes production entries as PHP standing rows and semantic bracket matches`() {
        val entries = listOf(
            entry(1, "관우", leadership = 95, strength = 97, intel = 75, group = 0, groupNo = 0, win = 2, draw = 1, goal = 12, promote = 1, seq = 1),
            entry(2, "장비", leadership = 88, strength = 99, intel = 52, group = 0, groupNo = 1, win = 1, lose = 2, goal = -3, seq = 2),
            entry(3, "조운", leadership = 96, strength = 96, intel = 77, group = 10, groupNo = 0, win = 3, goal = 18, promote = 1, seq = 3),
            entry(4, "여포", leadership = 92, strength = 100, intel = 26, group = 20, groupNo = 1, lose = 1, goal = -1, seq = 4),
            entry(6, "마초", leadership = 91, strength = 97, intel = 48, group = 30, groupNo = 1, win = 2, goal = 9, seq = 6),
            entry(7, "주유", leadership = 97, strength = 71, intel = 98, group = 40, groupNo = 1, win = 3, goal = 11, seq = 7),
            entry(8, "조조", leadership = 99, strength = 72, intel = 99, group = 50, groupNo = 1, win = 4, goal = 12, seq = 8),
            entry(5, "손책", leadership = 94, strength = 95, intel = 72, group = 60, groupNo = 0, win = 7, draw = 1, goal = 16, seq = 5),
        )
        val values = mapOf(
            "tournament" to "8",
            "tnmt_type" to "2",
            "turnterm" to "60",
            "tnmt_msg" to objectMapper.writeValueAsString("8강 진행 중"),
            "tournament_entries" to objectMapper.writeValueAsString(entries),
        )
        `when`(gameKv.findByTableAndNamespaceAndKey(anyString(), anyString(), anyString())).thenAnswer { call ->
            val table = call.getArgument<String>(0)
            val namespace = call.getArgument<String>(1)
            val key = call.getArgument<String>(2)
            values[key]
                ?.takeIf { table == "game_env" && namespace == "game_env" }
                ?.let { GameKvEntity(table, namespace, key, it) }
        }

        MockMvcBuilders.standaloneSetup(TournamentController(gameKv, objectMapper)).build()
            .perform(get("/api/tournament"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.state").value(8))
            .andExpect(jsonPath("$.tnmtType").value(2))
            .andExpect(jsonPath("$.tnmtTypeText").value("일기토"))
            .andExpect(jsonPath("$.turnTerm").value(60))
            .andExpect(jsonPath("$.groups").doesNotExist())
            .andExpect(jsonPath("$.entrants.length()").value(3))
            .andExpect(jsonPath("$.entrants[0].stage").value("MAIN"))
            .andExpect(jsonPath("$.entrants[0].groupNo").value(0))
            .andExpect(jsonPath("$.entrants[0].generalName").value("조운"))
            .andExpect(jsonPath("$.entrants[0].ability").value(96))
            .andExpect(jsonPath("$.entrants[0].games").value(3))
            .andExpect(jsonPath("$.entrants[0].points").value(9))
            .andExpect(jsonPath("$.entrants[0].goalDifference").value(18))
            .andExpect(jsonPath("$.entrants[0].promoted").value(true))
            .andExpect(jsonPath("$.entrants[1].stage").value("PRELIMINARY"))
            .andExpect(jsonPath("$.entrants[1].generalName").value("관우"))
            .andExpect(jsonPath("$.entrants[1].groupRank").value(1))
            .andExpect(jsonPath("$.entrants[1].ability").value(97))
            .andExpect(jsonPath("$.entrants[1].games").value(3))
            .andExpect(jsonPath("$.entrants[1].points").value(7))
            .andExpect(jsonPath("$.entrants[1].goalDifference").value(12))
            .andExpect(jsonPath("$.entrants[2].generalName").value("장비"))
            .andExpect(jsonPath("$.bracket[?(@.round == 16 && @.matchIdx == 0)].leftGeneralId").value(5))
            .andExpect(jsonPath("$.bracket[?(@.round == 16 && @.matchIdx == 0)].leftName").value("손책"))
            .andExpect(jsonPath("$.bracket[?(@.round == 16 && @.matchIdx == 0)].rightGeneralId").value(4))
            .andExpect(jsonPath("$.bracket[?(@.round == 16 && @.matchIdx == 0)].rightName").value("여포"))
            .andExpect(jsonPath("$.bracket[?(@.round == 16 && @.matchIdx == 0)].winnerGeneralId").value(5))
            .andExpect(jsonPath("$.bracket[?(@.round == 8 && @.matchIdx == 0)].leftName").value("손책"))
            .andExpect(jsonPath("$.bracket[?(@.round == 8 && @.matchIdx == 0)].rightName").value("마초"))
            .andExpect(jsonPath("$.bracket[?(@.round == 4 && @.matchIdx == 0)].winnerName").value("손책"))
            .andExpect(jsonPath("$.bracket[?(@.round == 2 && @.matchIdx == 0)].winnerName").value("손책"))
            .andExpect(jsonPath("$.rankings.length()").value(4))
    }

    private fun entry(
        id: Int,
        name: String,
        leadership: Int,
        strength: Int,
        intel: Int,
        group: Int,
        groupNo: Int,
        win: Int = 0,
        draw: Int = 0,
        lose: Int = 0,
        goal: Int = 0,
        promote: Int = 0,
        seq: Int,
    ): Map<String, Any> = linkedMapOf(
        "id" to id,
        "npc" to 0,
        "name" to name,
        "leadership" to leadership,
        "strength" to strength,
        "intel" to intel,
        "level" to 10,
        "group" to group,
        "groupNo" to groupNo,
        "win" to win,
        "draw" to draw,
        "lose" to lose,
        "goal" to goal,
        "promote" to promote,
        "seq" to seq,
    )
}
