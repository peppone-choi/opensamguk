package opensamguk.gameapi.controller

import opensamguk.gameapi.read.GeneralReadEntity
import opensamguk.gameapi.read.GeneralReadRepository
import opensamguk.gameapi.read.NationReadEntity
import opensamguk.gameapi.read.NationReadRepository
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders

/**
 * W6a — `GET /api/contacts` standalone slice test (PHP `GetContactList` / `getMailboxList`).
 *
 * 검증: (a) 재야(0) 국가가 항상 먼저 + mailbox=9000, (b) 국가별 mailbox=nationId+9000, (c) 장수
 * `[id, name, flags]` 트리플, (d) flags 비트필드(1=lord/2=npc/4=diplomat), (e) `npc_state >= 2` 제외,
 * (f) id 오름차순 결정성. 모든 repo는 mock — Spring 컨텍스트/Testcontainers 불요.
 */
class ContactControllerIT {

    private val generals = mock(GeneralReadRepository::class.java)
    private val nations = mock(NationReadRepository::class.java)

    private fun mvc(): MockMvc = MockMvcBuilders.standaloneSetup(ContactController(generals, nations)).build()

    private fun gen(
        id: Int, name: String, nationId: Int, officerLevel: Int = 0, npcState: Int = 0,
        meta: Map<String, Any?> = linkedMapOf(),
    ) = GeneralReadEntity(
        id = id, name = name, nationId = nationId, officerLevel = officerLevel, npcState = npcState, meta = meta,
    )

    private fun nation(id: Int, name: String, color: String = "#fff") =
        NationReadEntity(id = id, name = name, color = color)

    @Test
    fun `contacts groups playable generals by nation with neutral first and mailbox offset`() {
        `when`(nations.findAll()).thenReturn(listOf(nation(2, "위", "#0000ff"), nation(1, "촉", "#00ff00")))
        `when`(generals.findAll()).thenReturn(
            listOf(
                gen(id = 3, name = "조조", nationId = 2, officerLevel = 12), // 위 군주 → lord(1)
                gen(id = 1, name = "유비", nationId = 1, officerLevel = 12), // 촉 군주 → lord(1)
                gen(id = 5, name = "방랑객", nationId = 0), // 재야
            ),
        )

        mvc().perform(get("/api/contacts"))
            .andExpect(status().isOk)
            // 국가 3개: 재야(0) + 촉(1) + 위(2) — 재야 먼저, 그다음 id 오름차순.
            .andExpect(jsonPath("$.nation.length()").value(3))
            // 재야: mailbox 9000, 방랑객 1명.
            .andExpect(jsonPath("$.nation[0].mailbox").value(9000))
            .andExpect(jsonPath("$.nation[0].name").value("재야"))
            .andExpect(jsonPath("$.nation[0].color").value("#000000"))
            .andExpect(jsonPath("$.nation[0].general[0][0]").value(5))
            .andExpect(jsonPath("$.nation[0].general[0][1]").value("방랑객"))
            .andExpect(jsonPath("$.nation[0].general[0][2]").value(0))
            // 촉: mailbox 9001, 유비(lord 비트 1).
            .andExpect(jsonPath("$.nation[1].mailbox").value(9001))
            .andExpect(jsonPath("$.nation[1].name").value("촉"))
            .andExpect(jsonPath("$.nation[1].general[0][0]").value(1))
            .andExpect(jsonPath("$.nation[1].general[0][2]").value(1))
            // 위: mailbox 9002, 조조(lord 비트 1).
            .andExpect(jsonPath("$.nation[2].mailbox").value(9002))
            .andExpect(jsonPath("$.nation[2].general[0][0]").value(3))
            .andExpect(jsonPath("$.nation[2].general[0][2]").value(1))
    }

    @Test
    fun `contacts sets npc bit for npc_state 1 and diplomat bit for meta permission 4`() {
        `when`(nations.findAll()).thenReturn(listOf(nation(1, "촉")))
        `when`(generals.findAll()).thenReturn(
            listOf(
                gen(id = 1, name = "NPC장수", nationId = 1, npcState = 1), // npc 비트(2)
                gen(id = 2, name = "외교관", nationId = 1, meta = mapOf("permission" to 4)), // diplomat 비트(4)
                gen(id = 3, name = "외교관문자열", nationId = 1, meta = mapOf("permission" to "ambassador")), // 4
            ),
        )

        mvc().perform(get("/api/contacts"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.nation[1].name").value("촉"))
            .andExpect(jsonPath("$.nation[1].general[0][2]").value(2)) // npc
            .andExpect(jsonPath("$.nation[1].general[1][2]").value(4)) // diplomat(int 4)
            .andExpect(jsonPath("$.nation[1].general[2][2]").value(4)) // diplomat(ambassador)
    }

    @Test
    fun `contacts excludes generals with npc_state 2 or higher`() {
        `when`(nations.findAll()).thenReturn(listOf(nation(1, "촉")))
        `when`(generals.findAll()).thenReturn(
            listOf(
                gen(id = 1, name = "재야NPC풀", nationId = 1, npcState = 2), // npc_state>=2 → 제외
                gen(id = 2, name = "유비", nationId = 1, officerLevel = 12),
            ),
        )

        mvc().perform(get("/api/contacts"))
            .andExpect(status().isOk)
            // 촉 국가에 유비 1명만(npc_state=2 제외).
            .andExpect(jsonPath("$.nation[1].general.length()").value(1))
            .andExpect(jsonPath("$.nation[1].general[0][0]").value(2))
            .andExpect(jsonPath("$.nation[1].general[0][1]").value("유비"))
    }

    @Test
    fun `contacts returns only the neutral nation when no nations or generals exist`() {
        `when`(nations.findAll()).thenReturn(emptyList())
        `when`(generals.findAll()).thenReturn(emptyList())

        mvc().perform(get("/api/contacts"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.nation.length()").value(1))
            .andExpect(jsonPath("$.nation[0].mailbox").value(9000))
            .andExpect(jsonPath("$.nation[0].general.length()").value(0))
    }
}
