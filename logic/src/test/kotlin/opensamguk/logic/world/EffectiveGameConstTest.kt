package opensamguk.logic.world

import opensamguk.common.constants.CityConst
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * F6 Task FM1 — EffectiveGameConst.resolve(scenario): the getGameConf merge pipeline.
 *
 * PHP `Scenario.php:254-282` getGameConf:
 *   $this->gameConf = array_merge($stat, $this->data['map']??[], $this->data['const']??[]);
 *   $this->gameConf['mapName'] = $this->gameConf['mapName'] ?? 'che';
 *   $this->gameConf['unitSet'] = $this->gameConf['unitSet'] ?? 'che';
 *
 * array_merge precedence: LATER overwrites EARLIER, so const wins over map for a shared key, and
 * map wins over stat. mapName/unitSet are forced to 'che' ONLY when absent AFTER the merge.
 * The active CityConst variant is selected by the resolved mapName (a RUNTIME config, NOT a
 * compile-time singleton).
 */
class EffectiveGameConstTest {

    @Test
    fun `scenario_1010 (no map block) resolves to che and the active CityConst is the base 94-city seed`() {
        // scenario_1010: no `map`, const = {defaultMaxGeneral: 600}.
        val eff = EffectiveGameConst.resolve(
            ScenarioConf(map = null, const = mapOf("defaultMaxGeneral" to 600)),
        )
        assertEquals("che", eff.mapName, "no map block -> mapName null-coalesces to 'che'")
        assertEquals("che", eff.unitSet, "no unitSet -> 'che'")
        assertEquals(600, eff.gameConf["defaultMaxGeneral"], "const merged through")
        // gate-oracle invariant: active CityConst byte-matches the base (zero delta).
        assertEquals(94, eff.cityConst.all().size)
        assertEquals(CityConst.all(), eff.cityConst.all(), "active CityConst == base 94-city seed")
    }

    @Test
    fun `scenario_1 (map miniche) resolves the 78-city override with inherited buildInit`() {
        // scenario_1: map = {mapName: miniche}, const = {joinRuinedNPCProp:0, npcBanMessageProb:1}.
        val eff = EffectiveGameConst.resolve(
            ScenarioConf(
                map = mapOf("mapName" to "miniche"),
                const = mapOf("joinRuinedNPCProp" to 0, "npcBanMessageProb" to 1),
            ),
        )
        assertEquals("miniche", eff.mapName, "map.mapName threaded through")
        assertEquals("che", eff.unitSet, "unitSet absent -> 'che'")
        assertEquals(0, eff.gameConf["joinRuinedNPCProp"])
        assertEquals(1, eff.gameConf["npcBanMessageProb"])
        assertEquals(78, eff.cityConst.all().size, "miniche 78-city override")
        assertEquals(CityConst.buildInit, eff.cityConst.buildInit, "inherited buildInit")
    }

    @Test
    fun `const wins over map for a shared key (array_merge later-wins precedence)`() {
        val eff = EffectiveGameConst.resolve(
            ScenarioConf(
                map = mapOf("mapName" to "miniche", "sharedKey" to "fromMap"),
                const = mapOf("sharedKey" to "fromConst"),
            ),
        )
        assertEquals("fromConst", eff.gameConf["sharedKey"], "const (later) wins over map")
        assertEquals("miniche", eff.mapName, "mapName from map kept (not overridden)")
    }

    @Test
    fun `mapName and unitSet null-coalesce to che AFTER the merge`() {
        // const can SET mapName/unitSet — that survives; absence -> 'che'.
        val withConstMap = EffectiveGameConst.resolve(
            ScenarioConf(map = null, const = mapOf("mapName" to "miniche")),
        )
        assertEquals("miniche", withConstMap.mapName, "const-set mapName survives the ?? 'che'")

        val bare = EffectiveGameConst.resolve(ScenarioConf(map = null, const = null))
        assertEquals("che", bare.mapName)
        assertEquals("che", bare.unitSet)
    }

    @Test
    fun `resolve is frozen at load — the active CityConst is the registry variant instance`() {
        val eff = EffectiveGameConst.resolve(ScenarioConf(map = null, const = null))
        assertSame(CityConstRegistry.of("che"), eff.cityConst,
            "active CityConst is the shared immutable registry 'che' variant")
        assertTrue(eff.gameConf.containsKey("mapName"), "resolved gameConf carries mapName")
        assertTrue(eff.gameConf.containsKey("unitSet"), "resolved gameConf carries unitSet")
    }
}
