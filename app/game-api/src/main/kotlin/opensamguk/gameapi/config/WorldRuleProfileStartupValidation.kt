package opensamguk.gameapi.config

import opensamguk.logic.input.WorldRuleProfile
import org.springframework.stereotype.Component

@Component
class WorldRuleProfileStartupValidation {
    init {
        WorldRuleProfile.validateRuntimeConfiguration()
    }
}
