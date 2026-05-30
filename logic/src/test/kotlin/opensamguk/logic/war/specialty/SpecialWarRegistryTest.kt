package opensamguk.logic.war.specialty

import opensamguk.logic.stats.GeneralActionModule
import opensamguk.logic.world.SpecialityHelper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Port-faithful tests for [SpecialWarRegistry] (AW1) — the id→[GeneralActionModule] factory for source #4
 * of the 9-source action stack (`specialWar`), mirroring the GREEN P2 [opensamguk.logic.traits.SpecialDomesticRegistry].
 *
 * Grand truth:
 *   - PHP `ActionSpecialWar/` = 21 files (20 selectable war specials + `None.php`). The 20 selectable ids
 *     are exactly [SpecialityHelper.WAR] (the GREEN selection table).
 *   - `None`(id 0) extends BaseSpecial with no battle-hook override → folds as identity → the registry
 *     resolves it to `null` (a skipped slot), exactly as SpecialDomesticRegistry resolves None → null.
 *   - An unknown / blank / null code → null.
 *   - ZERO RNG: resolution is a pure map lookup.
 *
 * The concrete modules land across AW2 (stat-folds) + AW3 (multipliers); each registers BY id. This test
 * pins the registry contract (None/unknown inert, the 20 WAR ids as the registrable key set, stable
 * singletons); the per-specialty behavior is pinned by [WarSpecialtyStatTest] / [WarSpecialtyMultiplierTest].
 */
class SpecialWarRegistryTest {

    @Test
    fun `None blank null and unknown resolve to null`() {
        assertNull(SpecialWarRegistry.resolve(null))
        assertNull(SpecialWarRegistry.resolve(""))
        assertNull(SpecialWarRegistry.resolve("None"))
        assertNull(SpecialWarRegistry.resolve("che_없는특기"))
    }

    @Test
    fun `the registrable key set is exactly the 20 WAR selection ids`() {
        val warIds = SpecialityHelper.WAR.map { it.id }.toSet()
        assertEquals(20, warIds.size, "SpecialityHelper.WAR is the 20-id selection table")
        // The declared registrable key set IS the 20 WAR ids (the modules behind them land across AW2/AW3).
        assertEquals(warIds, SpecialWarRegistry.registrableIds())
    }

    @Test
    fun `a known id never resolves outside the declared registrable key set`() {
        // Any id resolve() returns a module for MUST be a declared registrable WAR id.
        for (meta in SpecialityHelper.WAR) {
            val m: GeneralActionModule? = SpecialWarRegistry.resolve(meta.id)
            if (m != null) {
                assertTrue(
                    meta.id in SpecialWarRegistry.registrableIds(),
                    "resolved id ${meta.id} must be a declared registrable id",
                )
                assertSame(m, SpecialWarRegistry.resolve(meta.id), "modules are stable singletons")
            }
        }
    }
}
