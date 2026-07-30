package opensamguk.logic.event

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class EventStoreDefaultRowsTest {
    @Test
    fun `PHP default events are the exact twelve rows in insertion order`() {
        val actual = EventStore.defaultWireRows()
            .map { listOf(it.target, it.priority.toString(), it.condition, it.action) }

        assertEquals(
            listOf(
                listOf("pre_month", "9000", "true", """[["UpdateCitySupply"],["ProcessWarIncome"]]"""),
                listOf("month", "9000", """["Date","==",null,1]""", """[["MergeInheritPointRank"],["ProcessSemiAnnual","gold"],["ProcessIncome","gold"],["ResetOfficerLock"],["RaiseDisaster"],["RandomizeCityTradeRate"],["NewYear"],["AssignGeneralSpeciality"]]"""),
                listOf("month", "9000", """["Date","==",null,4]""", """[["ResetOfficerLock"],["RaiseDisaster"]]"""),
                listOf("month", "9000", """["Date","==",null,7]""", """[["MergeInheritPointRank"],["ProcessSemiAnnual","rice"],["ProcessIncome","rice"],["ResetOfficerLock"],["RaiseDisaster"],["RandomizeCityTradeRate"]]"""),
                listOf("month", "9000", """["Date","==",null,10]""", """[["ResetOfficerLock"],["RaiseDisaster"]]"""),
                listOf("month", "2000", """["DateRelative","==",1,1]""", """[["NoticeToHistoryLog","<S>2년 뒤 출병 제한이 풀립니다.</>","EVENT_YEAR_MONTH"],["DeleteEvent"]]"""),
                listOf("month", "2000", """["DateRelative","==",2,1]""", """[["NoticeToHistoryLog","<S>1년 뒤 출병 제한이 풀립니다.</>","EVENT_YEAR_MONTH"],["DeleteEvent"]]"""),
                listOf("month", "2000", """["DateRelative","==",2,7]""", """[["NoticeToHistoryLog","<S>6개월 뒤 출병 제한이 풀립니다. 병력을 준비해주세요.</>","EVENT_YEAR_MONTH"],["DeleteEvent"]]"""),
                listOf("month", "2000", """["DateRelative","==",3,1]""", """[["NoticeToHistoryLog","<S>출병 제한이 풀렸습니다.</>","EVENT_YEAR_MONTH"],["DeleteEvent"]]"""),
                listOf("month", "2000", """["DateRelative","==",4,1]""", """[["NoticeToHistoryLog","<S>이제부터 하야, 망명시 패널티가 적용됩니다.</>","EVENT_YEAR_MONTH"],["AddGlobalBetray",1,0],["AddGlobalBetray",1,1],["DeleteEvent"]]"""),
                listOf("month", "1000", "true", """[["UpdateNationLevel"],["ProvideNPCTroopLeader"]]"""),
                listOf("united", "5000", "true", """[["MergeInheritPointRank"]]"""),
            ),
            actual,
        )
        assertFalse(actual.any { "OpenNationBetting" in it[3] })
    }
}
