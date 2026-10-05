package opensamguk.engine.boot

import opensamguk.common.world.WorldId
import opensamguk.infra.seed.SelectedSourceUnavailable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Actual SeedBootstrap parser decisions, without a source receipt or database. */
class D101SelectedParsedImporterOptionsTest {
    private fun bootstrap(fiction: String? = "1", qaTurnTerm: String? = null) = SeedBootstrap(
        scenarioCode = "scenario_3190",
        scenarioDir = "",
        qaTurnTerm = qaTurnTerm,
        resetTurnTerm = "60",
        resetMaxGeneral = "50",
        resetFirstTurn = "immediate",
        resetFiction = fiction,
        resetExtend = "1",
        resetBlockGeneralCreate = "1",
        resetNpcMode = "0",
        resetShowImgLevel = "3",
        worldId = WorldId(1),
    )

    @Test
    fun `explicit 3190 inputs expose only importer parsed values`() {
        val observed = bootstrap().selectedParsedImporterOptions()
        assertEquals("50", observed["RESET_MAXGENERAL"])
        assertEquals("immediate", observed["RESET_FIRST_TURN"])
        assertEquals("1", observed["RESET_EXTEND"])
        assertEquals("1", observed["RESET_FICTION"])
        assertEquals("60", observed["RESET_TURNTERM"])
        assertEquals("", observed["SCENARIO_LOOKUP_DIR"])
        assertEquals(11, observed.size)
    }

    @Test
    fun `missing fiction and QA override cannot become selected source facts`() {
        assertFailsWith<SelectedSourceUnavailable> { bootstrap(fiction = null).selectedParsedImporterOptions() }
        assertFailsWith<SelectedSourceUnavailable> { bootstrap(qaTurnTerm = "1").selectedParsedImporterOptions() }
    }
}
