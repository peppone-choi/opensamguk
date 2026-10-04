package opensamguk.engine

import opensamguk.common.architecture.ArchitectureRuleSupport
import kotlin.test.Test

class ArchitectureRulesTest {
    @Test fun reportCurrentViolations() = ArchitectureRuleSupport.reportOnly("game-engine", "opensamguk.engine")
}
