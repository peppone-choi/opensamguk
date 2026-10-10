package opensamguk.gameapi.council

import opensamguk.gameapi.read.WorldStateReadEntity
import opensamguk.gameapi.read.WorldStateReadRepository
import opensamguk.logic.input.RuleProfile
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

/** Explicit archive profile for unchanged office-based compatibility assertions. */
object FrozenBoardAccessFixture {
    fun query(): BoardSecretAccessQuery {
        val worlds = mock(WorldStateReadRepository::class.java)
        `when`(worlds.findProcessWorld()).thenReturn(
            WorldStateReadEntity(id = 1, config = mapOf("ruleProfile" to RuleProfile.fromWorldConfig(null).name)))
        return BoardSecretAccessQuery(mock(CouncilReader::class.java), worlds)
    }
}
