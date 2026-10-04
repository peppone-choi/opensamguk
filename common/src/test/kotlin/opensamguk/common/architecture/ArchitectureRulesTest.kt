package opensamguk.common.architecture

import com.tngtech.archunit.core.importer.ClassFileImporter
import opensamguk.archprobe.first.ProbeFirst
import opensamguk.archprobe.second.ProbeSecond
import opensamguk.engine.campaign.archprobe.ProbeHandler
import opensamguk.gameapi.archprobe.ProbeController
import opensamguk.infra.archprobe.ProbeRepository
import opensamguk.logic.archprobe.ProbeClock
import kotlin.test.Test
import kotlin.test.assertTrue

class ArchitectureRulesTest {
    @Test
    fun reportCurrentViolations() = ArchitectureRuleSupport.reportOnly("common", "opensamguk.common")

    @Test
    fun fixedProbeMustTripEveryDetector() {
        val classes = ClassFileImporter().importClasses(
            ProbeController::class.java, ProbeHandler::class.java, ProbeClock::class.java,
            ProbeRepository::class.java, ProbeFirst::class.java, ProbeSecond::class.java,
        )
        val measured = ArchitectureRuleSupport.detect(classes, "opensamguk.archprobe")
        (1..6).forEach { index ->
            val rule = "A$index"
            assertTrue(measured.getValue(rule).count > 0, "$rule detector missed its fixed violation")
        }
    }
}
