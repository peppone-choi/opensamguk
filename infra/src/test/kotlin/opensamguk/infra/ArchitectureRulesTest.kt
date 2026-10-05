package opensamguk.infra

import opensamguk.common.architecture.ArchitectureRuleSupport
import kotlin.test.Test

class ArchitectureRulesTest {
    @Test fun reportCurrentViolations() = ArchitectureRuleSupport.reportOnly("infra", "opensamguk.infra")
}
