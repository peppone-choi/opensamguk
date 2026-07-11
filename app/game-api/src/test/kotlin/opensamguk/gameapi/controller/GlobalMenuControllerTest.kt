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
            .andExpect(jsonPath("$.menu.length()").value(8))
            // #1 천통국 베팅 — item with highlight var
            .andExpect(jsonPath("$.menu[0].type").value("item"))
            .andExpect(jsonPath("$.menu[0].name").value("천통국 베팅"))
            .andExpect(jsonPath("$.menu[0].condHighlightVar").value("nationBetting"))
            // #2 게임정보 — multi with a MenuLine inside
            .andExpect(jsonPath("$.menu[1].type").value("multi"))
            .andExpect(jsonPath("$.menu[1].subMenu.length()").value(6))
            .andExpect(jsonPath("$.menu[1].subMenu[3].type").value("line"))
            .andExpect(jsonPath("$.menu[3].type").value("item"))
            .andExpect(jsonPath("$.menu[3].name").value("게시판"))
            .andExpect(jsonPath("$.menu[3].url").value("/game/board"))
            // #7 기타 정보 — 빙의일람 gated by condShowVar npcMode
            .andExpect(jsonPath("$.menu[6].subMenu[1].condShowVar").value("npcMode"))
    }

    // 구 `/api/const` 테스트는 삭제: 엔드포인트가 W3 GetConstController(superset)로 이관됨
    // (GetConstControllerTest가 정본 검증). GlobalMenuController는 더는 /api/const를 매핑하지 않는다.
}
