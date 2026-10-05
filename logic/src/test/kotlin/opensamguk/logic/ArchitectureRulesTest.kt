package opensamguk.logic

import opensamguk.common.architecture.ArchitectureRuleSupport
import kotlin.test.Test

class ArchitectureRulesTest {
    @Test fun reportCurrentViolations() = ArchitectureRuleSupport.reportOnly("logic", "opensamguk.logic")
}
