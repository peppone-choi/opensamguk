package opensamguk.logic.actions.intake

import opensamguk.logic.domain.General
import kotlin.test.Test
import kotlin.test.assertIs

class NationFinanceSettersTest {

    private val permittedGeneral = General(
        id = 10,
        nationId = 1,
        cityId = 5,
        leadership = 80,
        strength = 70,
        intel = 60,
        injury = 0,
        experience = 0.0,
        dedication = 0.0,
        officerLevel = 12,
        gold = 0,
        rice = 0,
    )

    @Test
    fun `accepts astral code point boundaries for nation rich text`() {
        assertIs<FinanceSetterOutcome.Notice>(
            NationFinanceSetters.setNotice(permittedGeneral, "😀".repeat(NationFinanceSetters.NOTICE_MSG_MAX)),
        )
        assertIs<FinanceSetterOutcome.ScoutMsg>(
            NationFinanceSetters.setScoutMsg(permittedGeneral, "😀".repeat(NationFinanceSetters.SCOUT_MSG_MAX)),
        )
    }

    @Test
    fun `rejects the first astral code point past nation rich text boundaries`() {
        assertIs<FinanceSetterOutcome.Denied>(
            NationFinanceSetters.setNotice(permittedGeneral, "😀".repeat(NationFinanceSetters.NOTICE_MSG_MAX + 1)),
        )
        assertIs<FinanceSetterOutcome.Denied>(
            NationFinanceSetters.setScoutMsg(permittedGeneral, "😀".repeat(NationFinanceSetters.SCOUT_MSG_MAX + 1)),
        )
    }
}
