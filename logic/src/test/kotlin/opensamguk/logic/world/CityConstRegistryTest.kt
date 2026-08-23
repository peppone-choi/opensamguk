package opensamguk.logic.world

import opensamguk.common.constants.CityConst
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * F6 Task FM1 — CityConstRegistry: named immutable CityConst variants keyed by mapName.
 *
 * Resolved decision (plan §"Per-map CityConst override"): per-map override = whole-FILE class
 * substitution by mapName, NOT a per-city field patch. 'che' = the canonical base 94-city seed
 * (the GREEN `CityConst` object — canonical che data stays in the base, NOT an empty variant file);
 * 'miniche' = the 78-city `$initCity`-ONLY override that INHERITS buildInit/levelMap/regionMap and
 * runs the SAME `_generate()` algorithm (`scenario/map/miniche.php`).
 *
 * The gate-oracle invariant: scenario_1010 (no map block) resolves 'che' and the active CityConst
 * MUST byte-match the base 94-city seed (ZERO delta).
 */
class CityConstRegistryTest {

    @Test
    fun `che variant byte-matches the canonical base 94-city seed (zero delta)`() {
        val che = CityConstRegistry.of("che")
        val base = CityConst.all()
        assertEquals(94, che.all().size, "che has 94 cities")
        assertEquals(base.size, che.all().size, "che row count == base")
        // Every city — id/name/level/stats/region/pos/path — is byte-identical to the base.
        assertEquals(base, che.all(), "che.all() == CityConst.all() (zero delta)")
        for ((id, c) in base) {
            assertEquals(c, che.byId(id), "byId id=$id")
            assertEquals(c, che.byName(c.name), "byName ${c.name}")
        }
    }

    @Test
    fun `che variant inherits buildInit and buildInitCommon from the base`() {
        val che = CityConstRegistry.of("che")
        assertEquals(CityConst.buildInit, che.buildInit, "buildInit inherited")
        assertEquals(CityConst.buildInitCommon, che.buildInitCommon, "buildInitCommon inherited")
    }

    @Test
    fun `che byRegion preserves the last-wins quirk`() {
        val che = CityConstRegistry.of("che")
        for ((region, _) in CityConst.regionMap.entries.filter { it.key is Int }
            .associate { it.key as Int to it.value }) {
            assertEquals(CityConst.byRegion(region), che.byRegion(region),
                "byRegion last-wins region=$region")
        }
    }

    @Test
    fun `miniche variant is a 78-city initCity-only override (inherits buildInit and levelMap)`() {
        val miniche = CityConstRegistry.of("miniche")
        assertEquals(78, miniche.all().size, "miniche has 78 cities (\$initCity override)")
        // buildInit/buildInitCommon are INHERITED (miniche.php overrides ONLY \$initCity).
        assertEquals(CityConst.buildInit, miniche.buildInit, "miniche inherits buildInit")
        assertEquals(CityConst.buildInitCommon, miniche.buildInitCommon, "miniche inherits buildInitCommon")
    }

    @Test
    fun `miniche display variants use the miniche city constant variant`() {
        val miniche = CityConstRegistry.of("miniche")
        assertEquals(miniche.all(), CityConstRegistry.of("miniche_b").all())
        assertEquals(miniche.all(), CityConstRegistry.of("miniche_clean").all())
    }

    @Test
    fun `miniche resolves levels and regions through the inherited shared maps`() {
        val miniche = CityConstRegistry.of("miniche")
        // 낙양(id 1) is 특(level 8), region 중원(2); pos NOT scaled; stats ×100.
        val nakyang = assertNotNull(miniche.byName("낙양"), "낙양 present")
        assertEquals(1, nakyang.id)
        assertEquals(8, nakyang.level, "특 -> 8 via inherited levelMap")
        assertEquals(2, nakyang.region, "중원 -> 2 via inherited regionMap")
        assertEquals(835700, nakyang.population, "pop 8357 ×100")
        assertEquals(285, nakyang.posX, "posX NOT scaled")
        assertEquals(176, nakyang.posY, "posY NOT scaled")
        // 강(id 72) is 이(level 4 — 이민족 전용), distinct from 소(level 5).
        assertEquals(4, miniche.byName("강")!!.level, "이 -> 4")
    }

    @Test
    fun `miniche path adjacency is bidirectional (every connection lists this city back)`() {
        val miniche = CityConstRegistry.of("miniche")
        for ((id, city) in miniche.all()) {
            for ((connId, _) in city.path) {
                val conn = assertNotNull(miniche.byId(connId), "missing conn id=$connId")
                assertTrue(conn.path.containsKey(id),
                    "도시 연결이 올바르지 않음! ${city.name} <=> ${conn.name}")
            }
        }
    }

    @Test
    fun `path adjacency preserves insertion order (the BFS input)`() {
        // 낙양's path order in miniche.php is exactly [하내, 홍농, 호로] -> their ids in that order.
        val miniche = CityConstRegistry.of("miniche")
        val pathNames = miniche.byName("낙양")!!.path.values.toList()
        assertEquals(listOf("하내", "홍농", "호로"), pathNames, "LinkedHashMap insertion order preserved")
    }

    @Test
    fun `han has exactly 780 cities (independent pin, not size-vs-itself)`() {
        // 아래 인접 해시/BFS 연결성 테스트는 모두 han.all() 을 자기 자신과만 비교한다 — 임포터가
        // 도시를 누락·중복해도 두 테스트 다 통과할 수 있다. 여기서는 DB IT(ScenarioImporterIT 등)
        // 가 쓰는 것과 같은 780 이라는 고정 값을 Docker 없이 바로 대조한다.
        assertEquals(780, CityConstRegistry.of("han").all().size, "han 780성 고정 핀")
    }

    @Test
    fun `Han numeric adjacency preserves the generated 780-city graph`() {
        val graph = buildString {
            for ((id, city) in CityConstRegistry.of("han").all()) {
                append(id).append(':').append(city.path.keys.joinToString(",")).append('\n')
            }
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(graph.toByteArray())
            .joinToString("") { "%02x".format(it) }
        // 2026-08-22 의도적 갱신: 섬 郡治 5개(이주·유구·우산국·주호·야마일국)가 육지 인접 0개라
        // 도달 불가였고 checkEmperior 의 「전 城 소유」가 영구 불가능했다. build_han_world.py 의
        // SEA_LINKS(사료 근거 4 + UNKNOWN 1)로 해상 간선 5개를 붙이면서 해시가 바뀐다.
        assertEquals("88d14c49b03d96214b9e3e44abd91e7496269731070bd9e2fd704e18109cbd7b", digest)
    }

    @Test
    fun `Han graph is fully connected — every city reachable from id 1`() {
        val han = CityConstRegistry.of("han")
        val seen = mutableSetOf(1)
        val queue = ArrayDeque(listOf(1))
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            for (next in han.byId(cur)!!.path.keys) if (seen.add(next)) queue.addLast(next)
        }
        assertEquals(han.all().size, seen.size,
            "고립 城: " + han.all().keys.filter { it !in seen })
    }

    @Test
    fun `unknown mapName has no variant`() {
        assertNull(CityConstRegistry.find("__nope__"), "unknown map -> null")
    }
}
