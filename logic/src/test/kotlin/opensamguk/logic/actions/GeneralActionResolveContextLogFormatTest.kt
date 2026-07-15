package opensamguk.logic.actions

import opensamguk.common.rng.NoRng
import opensamguk.common.rng.RandUtil
import opensamguk.logic.domain.City
import opensamguk.logic.domain.General
import opensamguk.logic.domain.Nation
import opensamguk.logic.domain.WorldEnv
import kotlin.test.Test
import kotlin.test.assertEquals

class GeneralActionResolveContextLogFormatTest {

    @Test
    fun `PHP ActionLogger default formats are applied before logs are drained`() {
        val context = context()

        context.addLogTo(99, "대상 행동")
        context.addGeneralHistoryLog("장수 기록")
        context.addNationalHistoryLog("국가 기록")
        context.addGlobalHistoryLog("전역 기록")
        context.addHistoryLogTo(99, "대상 기록")

        assertEquals(listOf("<C>●</>5월:대상 행동"), context.logsTo(99))
        assertEquals(listOf("<C>●</>200년 5월:장수 기록"), context.generalHistoryLogs())
        assertEquals(listOf("<C>●</>200년 5월:국가 기록"), context.nationalHistoryLogs())
        assertEquals(listOf("<C>●</>200년 5월:전역 기록"), context.globalHistoryLogs())
        assertEquals(listOf("<C>●</>200년 5월:대상 기록"), context.historyLogsTo(99))
        assertEquals(
            listOf(
                "<C>●</>5월:대상 행동",
                "<C>●</>200년 5월:장수 기록",
                "<C>●</>200년 5월:국가 기록",
                "<C>●</>200년 5월:전역 기록",
                "<C>●</>200년 5월:대상 기록",
            ),
            context.orderedLogEvents().map { it.text },
        )
    }

    private fun context(): GeneralActionResolveContext =
        GeneralActionResolveContext(
            draft = GeneralActionDraft(general(), city(), nation()),
            rng = RandUtil(NoRng()),
            env = WorldEnv(year = 200, startYear = 184, develCost = 120),
            month = 5,
            date = "09:00",
        )

    private fun general(): General =
        General(
            id = 1,
            nationId = 1,
            cityId = 1,
            leadership = 50,
            strength = 50,
            intel = 50,
            injury = 0,
            experience = 0.0,
            dedication = 0.0,
            officerLevel = 1,
            gold = 0,
            rice = 0,
        )

    private fun city(): City =
        City(1, 1, 5, 0, 0, 0, 0, 1, 0, 50.0)

    private fun nation(): Nation =
        Nation(id = 1, level = 1, capitalCityId = 1, name = "촉")
}
