package opensamguk.logic.world

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * han 지도 전체 연결성 — 섬 郡治(夷洲·流求·州胡·邪馬壹國·于山國)는 육지 보로노이 인접이
 * 없어 [SEA_LINKS]([tools/scenario/build_han_world.py]) 없이는 780성 중 5성이 고립된다.
 * `checkEmperior` 가 「전 城 소유」를 요구하므로, 고립 城이 하나라도 있으면 그 城을 최종
 * 보루로 남긴 세력을 이동/출병 인접만으로는 영원히 함락할 수 없어 천하통일이 막힌다.
 *
 * 이 테스트는 `SEA_LINKS` 별칭이 실제로 물려서 도달 불가 城이 0임을 고정한다.
 */
class HanMapConnectivityTest {

    @Test
    fun `han 城 전체가 하나의 연결 성분이다(고립 城 0)`() {
        val han = CityConstRegistry.of("han")
        val all = han.all()
        val start = all.keys.first()

        val seen = mutableSetOf(start)
        val frontier = ArrayDeque(listOf(start))
        while (frontier.isNotEmpty()) {
            val cur = frontier.removeFirst()
            for (next in all.getValue(cur).path.keys) {
                if (seen.add(next)) frontier.addLast(next)
            }
        }

        val unreachable = all.keys - seen
        assertTrue(
            unreachable.isEmpty(),
            "id=$start 에서 도달 불가 城 ${unreachable.size}개: " +
                unreachable.map { "${all.getValue(it).name}($it)" },
        )
        assertEquals(all.size, seen.size, "780성 전부 하나의 연결 성분이어야 한다")
    }
}
