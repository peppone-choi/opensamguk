package opensamguk.gameapi.controller

import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders

/**
 * F2 Wave 1 slice test for [GlobalMenuController] — the server-driven menu union + const. Asserts the
 * stable [opensamguk.gameapi.dto.MenuNode] union discriminator shape (item/multi/split/line) ported
 * from legacy GlobalMenu.php v2, and the const surface.
 */
class GlobalMenuControllerTest {

    private fun mockMvc(): MockMvc = MockMvcBuilders.standaloneSetup(GlobalMenuController()).build()

    @Test
    fun `global-menu returns the v2 typed-union default set`() {
        mockMvc().perform(get("/api/global-menu"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.result").value(true))
            .andExpect(jsonPath("$.version").value(2))
            // 삼모 「천통국 베팅」 항목은 베팅 은퇴로 뺐다(#917) — 나머지는 한 칸씩 앞으로.
            .andExpect(jsonPath("$.menu.length()").value(7))
            .andExpect(jsonPath("$.menu[*].name").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem("천통국 베팅"))))
            // #1 게임정보 — multi with a MenuLine inside
            .andExpect(jsonPath("$.menu[0].type").value("multi"))
            .andExpect(jsonPath("$.menu[0].subMenu.length()").value(6))
            .andExpect(jsonPath("$.menu[0].subMenu[3].type").value("line"))
            .andExpect(jsonPath("$.menu[2].type").value("item"))
            .andExpect(jsonPath("$.menu[2].name").value("게시판"))
            .andExpect(jsonPath("$.menu[2].url").value("/game/board"))
            // #6 기타 정보 — 빙의일람 gated by condShowVar npcMode
            .andExpect(jsonPath("$.menu[5].subMenu[1].condShowVar").value("npcMode"))
    }

    // 구 `/api/const` 테스트는 삭제: 엔드포인트가 W3 GetConstController(superset)로 이관됨
    // (GetConstControllerTest가 정본 검증). GlobalMenuController는 더는 /api/const를 매핑하지 않는다.
}
