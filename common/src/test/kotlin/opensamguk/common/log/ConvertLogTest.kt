package opensamguk.common.log

import kotlin.test.Test; import kotlin.test.assertEquals

class ConvertLogTest {
    @Test fun empty() = assertEquals("", convertLog("", 1))
    @Test fun cyan() = assertEquals("<font color=cyan>x</font>", convertLog("<C>x</>", 1))
    @Test fun size1() = assertEquals("<font size=1>x</font>", convertLog("<1>x</>", 1))
    @Test fun y1BeforeY() = assertEquals("<font size=1 color=yellow>x</font>", convertLog("<Y1>x</>", 1))
    @Test fun yellow() = assertEquals("<font color=yellow>x</font>", convertLog("<Y>x</>", 1))
    @Test fun dEqualsOrangered() = assertEquals("<font color=orangered>x</font>", convertLog("<D>x</>", 1))
    @Test fun oEqualsOrangered() = assertEquals("<font color=orangered>x</font>", convertLog("<O>x</>", 1))
    @Test fun nestedDBold() = assertEquals("<font color=orangered><b>x</b></font>", convertLog("<D><b>x</b></>", 1))
    @Test fun bAndBrPassthrough() = assertEquals("a<b>b</b><br>c", convertLog("a<b>b</b><br>c", 1))
    @Test fun stripType0() = assertEquals("x", convertLog("<C>x</>", 0))
    @Test fun stripTypeNegative() = assertEquals("190년 3월:x", convertLog("<C>190년 3월:</><C>x</>", -1))
    @Test fun allTagsRoundtrip() {
        val raw = "<1><Y1><R><B><G><M><C><L><S><O><D><Y><W>z</>"
        val expected = "<font size=1><font size=1 color=yellow><font color=red><font color=blue>" +
            "<font color=green><font color=magenta><font color=cyan><font color=limegreen>" +
            "<font color=skyblue><font color=orangered><font color=orangered><font color=yellow>" +
            "<font color=white>z</font>"
        assertEquals(expected, convertLog(raw, 1))
    }
}
