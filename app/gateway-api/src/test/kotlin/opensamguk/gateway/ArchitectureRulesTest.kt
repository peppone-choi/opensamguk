package opensamguk.gateway

import opensamguk.common.architecture.ArchitectureRuleSupport
import kotlin.test.Test

class ArchitectureRulesTest {
    @Test fun reportCurrentViolations() = ArchitectureRuleSupport.reportOnly("gateway-api", "opensamguk.gateway")
}
