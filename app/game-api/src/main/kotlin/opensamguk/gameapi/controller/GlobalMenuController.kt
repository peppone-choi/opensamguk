package opensamguk.gameapi.controller

import opensamguk.gameapi.dto.GlobalMenuResponse
import opensamguk.gameapi.dto.MenuNode
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * F2 Wave 1 — server-driven menu + const reads (spec §4 + §1.5).
 *
 *  - `GET /api/global-menu` — the typed-union menu (item|split|multi|line) the client filters client-side
 *    against globalInfo. This is a FAITHFUL DEFAULT set ported byte-for-byte from legacy `GlobalMenu.php`
 *    v2 (the production truth). A dynamic per-server menu is deferred; the DTO shape ([MenuNode]) is the
 *    STABLE contract so swapping to a dynamic source is a data change only.
 *  - `GET /api/const` — server constants the client caches as a module singleton (map dims, maxTurn,
 *    officer-level label map).
 *
 * Both are PUBLIC reads (no identity needed — the client filters menu visibility itself).
 */
@RestController
@RequestMapping("/api")
class GlobalMenuController {

    @GetMapping("/global-menu")
    fun globalMenu(): ResponseEntity<GlobalMenuResponse> =
        ResponseEntity.ok(GlobalMenuResponse(result = true, version = MENU_VERSION, menu = DEFAULT_MENU))

    // GET /api/const는 W3 GetConstController로 이관(superset). 중복 매핑 제거.

    companion object {
        private const val MENU_VERSION = 2

        private fun item(
            name: String,
            url: String,
            newTab: Boolean? = null,
            condHighlightVar: String? = null,
            condShowVar: String? = null,
        ) = MenuNode(type = "item", name = name, url = url, newTab = newTab, condHighlightVar = condHighlightVar, condShowVar = condShowVar)

        private val line = MenuNode(type = "line")

        /**
         * Verbatim port of `GlobalMenu::getMenu()` v2 (devsam-core, the production truth — see spec §4
         * default-entries table). External `.php`/`http` targets keep `newTab` semantics; internal
         * targets are remapped to App-Router routes by the client later.
         */
        private val DEFAULT_MENU: List<MenuNode> = listOf(
            item("천통국 베팅", "v_nationBetting.php", condHighlightVar = "nationBetting"),
            MenuNode(
                type = "multi", name = "게임정보",
                subMenu = listOf(
                    item("세력일람", "a_kingdomList.php", newTab = true),
                    item("장수일람", "a_genList.php", newTab = true),
                    item("명장일람", "a_bestGeneral.php", newTab = true),
                    line,
                    item("명예의전당", "a_hallOfFame.php", newTab = true),
                    item("왕조일람", "a_emperior.php", newTab = true),
                ),
            ),
            item("연감", "v_history.php", newTab = true),
            MenuNode(
                type = "split",
                main = item("게시판", "/board/community", newTab = true),
                subMenu = listOf(
                    item("건의/제안", "/board/request", newTab = true),
                    item("팁/강좌", "/board/tip", newTab = true),
                    line,
                    item("패치 내역", "/board/patch", newTab = true),
                ),
            ),
            MenuNode(
                type = "split",
                main = item("공식 오픈 톡", "https://open.kakao.com/o/", newTab = true),
                subMenu = listOf(
                    item("잡담 오픈 톡", "https://open.kakao.com/o/", newTab = true),
                ),
            ),
            item("전투 시뮬레이터", "battle_simulator.php", newTab = true),
            MenuNode(
                type = "multi", name = "기타 정보",
                subMenu = listOf(
                    item("접속량정보", "a_traffic.php", newTab = true),
                    item("빙의일람", "a_npcList", newTab = true, condShowVar = "npcMode"),
                ),
            ),
            item("설문조사", "v_vote.php", newTab = true, condHighlightVar = "vote"),
        )
    }
}
