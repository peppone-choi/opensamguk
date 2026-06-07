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
        penalty: Map<String, Any?> = linkedMapOf(),
    ) = GeneralReadEntity(
        id = id, name = name, nationId = nationId, officerLevel = officerLevel, npcState = npcState,
        meta = meta, penalty = penalty,
    )

    private fun nation(id: Int, name: String, color: String = "#fff") =
        NationReadEntity(id = id, name = name, color = color)

    @Test
    fun `contacts groups playable generals by nation with neutral first and mailbox offset`() {
        `when`(nations.findAll()).thenReturn(listOf(nation(2, "위", "#0000ff"), nation(1, "촉", "#00ff00")))
        `when`(generals.findAll()).thenReturn(
            listOf(
                gen(id = 3, name = "조조", nationId = 2, officerLevel = 12), // 위 군주 → lord(1)|diplomat(4)=5
                gen(id = 1, name = "유비", nationId = 1, officerLevel = 12), // 촉 군주 → lord(1)|diplomat(4)=5
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
            // 촉: mailbox 9001, 유비.
            // PHP grand truth(func_message.php:19-29 + func.php checkSecretPermission): officer_level==12 군주는
            // lord(1)뿐 아니라 checkSecretPermission()==4(secretMin=4,secretMax=4)로 diplomat(4)도 set → flags=5.
            .andExpect(jsonPath("$.nation[1].mailbox").value(9001))
            .andExpect(jsonPath("$.nation[1].name").value("촉"))
            .andExpect(jsonPath("$.nation[1].general[0][0]").value(1))
            .andExpect(jsonPath("$.nation[1].general[0][2]").value(5))
            // 위: mailbox 9002, 조조(군주 → lord|diplomat=5).
            .andExpect(jsonPath("$.nation[2].mailbox").value(9002))
            .andExpect(jsonPath("$.nation[2].general[0][0]").value(3))
            .andExpect(jsonPath("$.nation[2].general[0][2]").value(5))
    }

    @Test
    fun `contacts sets npc bit for npc_state 1 and diplomat bit for ambassador permission`() {
        // PHP grand truth(func.php checkSecretPermission): diplomat(4) 비트는 checkSecretPermission()==4 일 때만 set.
        //  - officer_level==0  → 즉시 -1(국가소속이라도) → diplomat 없음.
        //  - permission=='ambassador' → secretMin=4 → (penalty 없으면) 4 → diplomat.
        //  - permission=='auditor'    → secretMin=3 → 3 → diplomat 아님.
        // permission은 문자열 컬럼('ambassador'/'auditor'/null)이며 정수 4는 ambassador로 매핑되지 않는다.
        `when`(nations.findAll()).thenReturn(listOf(nation(1, "촉")))
        `when`(generals.findAll()).thenReturn(
            listOf(
                gen(id = 1, name = "NPC장수", nationId = 1, npcState = 1), // npc 비트(2)
                // officer_level!=0 + ambassador → checkSecretPermission==4 → diplomat(4).
                gen(id = 2, name = "외교관", nationId = 1, officerLevel = 5, meta = mapOf("permission" to "ambassador")),
                // auditor → secretMin=3(≠4) → diplomat 비트 없음(flags=0).
                gen(id = 3, name = "감찰관", nationId = 1, officerLevel = 5, meta = mapOf("permission" to "auditor")),
            ),
        )

        mvc().perform(get("/api/contacts"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.nation[1].name").value("촉"))
            .andExpect(jsonPath("$.nation[1].general[0][2]").value(2)) // npc
            .andExpect(jsonPath("$.nation[1].general[1][2]").value(4)) // diplomat(ambassador)
            .andExpect(jsonPath("$.nation[1].general[2][2]").value(0)) // auditor(secretMin=3) → diplomat 아님
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

    // ------------------------------------------------------------------
    // D6 — checkSecretMaxPermission CLAMP 분기 (func.php:377-388) + penalty 소스 검증.
    // penalty는 general 전용 jsonb 컬럼(GeneralReadEntity.penalty)이지 meta 키가 아니다 —
    // 컨트롤러가 g.penalty에서 읽어야 클램프 분기가 실제로 동작한다(meta["penalty"]면 항상 무음).
    // ------------------------------------------------------------------

    @Test
    fun `NoChief penalty short-circuits secret permission to zero so diplomat bit is off`() {
        `when`(nations.findAll()).thenReturn(listOf(nation(1, "촉")))
        `when`(generals.findAll()).thenReturn(
            listOf(
                // 군주(officer_level==12)라도 NoChief penalty → checkSecretPermission==0 → diplomat 없음.
                // lord 비트(1)만 남는다.
                gen(id = 1, name = "징계군주", nationId = 1, officerLevel = 12, penalty = mapOf("noChief" to true)),
            ),
        )

        mvc().perform(get("/api/contacts"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.nation[1].general[0][2]").value(1)) // lord(1)만, diplomat(4) off
    }

    @Test
    fun `NoTopSecret penalty clamps secretMax to 1 so ambassador no longer gets diplomat bit`() {
        `when`(nations.findAll()).thenReturn(listOf(nation(1, "촉")))
        `when`(generals.findAll()).thenReturn(
            listOf(
                // ambassador(secretMin=4)지만 NoTopSecret → secretMax=1 → min(4,1)=1 → diplomat 아님.
                gen(
                    id = 1, name = "제한외교관", nationId = 1, officerLevel = 5,
                    meta = mapOf("permission" to "ambassador"), penalty = mapOf("noTopSecret" to true),
                ),
            ),
        )

        mvc().perform(get("/api/contacts"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.nation[1].general[0][2]").value(0)) // diplomat off (clamp to 1)
    }

    @Test
    fun `NoAmbassador penalty clamps secretMax to 2 so ambassador no longer gets diplomat bit`() {
        `when`(nations.findAll()).thenReturn(listOf(nation(1, "촉")))
        `when`(generals.findAll()).thenReturn(
            listOf(
                // ambassador(secretMin=4)지만 NoAmbassador → secretMax=2 → min(4,2)=2 → diplomat 아님.
                gen(
                    id = 1, name = "외교권박탈", nationId = 1, officerLevel = 5,
                    meta = mapOf("permission" to "ambassador"), penalty = mapOf("noAmbassador" to true),
                ),
            ),
        )

        mvc().perform(get("/api/contacts"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.nation[1].general[0][2]").value(0)) // diplomat off (clamp to 2)
    }

    @Test
    fun `lord with NoAmbassador still gets diplomat bit because officer_level 12 secretMax floor is not reached`() {
        // 군주 secretMin=4, NoAmbassador secretMax=2 → min(4,2)=2 → diplomat 아님. lord(1)만.
        `when`(nations.findAll()).thenReturn(listOf(nation(1, "촉")))
        `when`(generals.findAll()).thenReturn(
            listOf(
                gen(id = 1, name = "군주외교박탈", nationId = 1, officerLevel = 12, penalty = mapOf("noAmbassador" to true)),
            ),
        )

        mvc().perform(get("/api/contacts"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.nation[1].general[0][2]").value(1)) // lord only — diplomat clamped off
    }

    @Test
    fun `penalty flag stored as numeric 1 is honored (PHP truthy parity)`() {
        // PHP `($penalty[key] ?? false)`는 truthy 검사 — 1/문자열도 true. boolean true 외에도 작동해야 한다.
        `when`(nations.findAll()).thenReturn(listOf(nation(1, "촉")))
        `when`(generals.findAll()).thenReturn(
            listOf(
                gen(id = 1, name = "숫자플래그군주", nationId = 1, officerLevel = 12, penalty = mapOf("noChief" to 1)),
            ),
        )

        mvc().perform(get("/api/contacts"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.nation[1].general[0][2]").value(1)) // NoChief(=1 truthy) → diplomat off
    }

    @Test
    fun `officer_level 0 in a nation yields -1 permission so no diplomat bit`() {
        `when`(nations.findAll()).thenReturn(listOf(nation(1, "촉")))
        `when`(generals.findAll()).thenReturn(
            listOf(
                // 국가 소속이지만 officer_level==0 → checkSecretPermission==-1 → diplomat 없음(flags=0).
                gen(id = 1, name = "무관직", nationId = 1, officerLevel = 0),
            ),
        )

        mvc().perform(get("/api/contacts"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.nation[1].general[0][2]").value(0))
    }
}
