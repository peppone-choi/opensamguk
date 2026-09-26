package opensamguk.engine.campaign

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import opensamguk.common.world.WorldId
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.TurnWorldState
import opensamguk.engine.turn.WorldSnapshot
import opensamguk.infra.persistence.MetaJson
import opensamguk.logic.office.OfficeClaimOrigin
import opensamguk.logic.office.OfficeCredentialCodec
import opensamguk.logic.office.OfficeTenure
import opensamguk.logic.office.OfficeTenureCodec
import opensamguk.logic.vassal.VassalBreachKind
import opensamguk.logic.vassal.VassalContract
import opensamguk.logic.vassal.VassalDiplomacyRight
import opensamguk.logic.vassal.VassalState
import opensamguk.logic.vassal.VassalStateCodec

class CourtStateStoreTest {
    private fun newWorld(meta: Map<String, Any?>) = InMemoryTurnWorld(WorldSnapshot(
        state = TurnWorldState(1, 196, 1, 3600, Instant.EPOCH, meta = meta), worldId = WorldId(1)))

    @Test
    fun `separate court keys and unrelated game env survive KV round trip`() {
        val world = newWorld(mapOf("otherKey" to "preserved"))
        val recorder = ChangeRecorder()
        val store = CourtStateStore(world, recorder)
        val tenure = OfficeTenure("tenure-1", "office.commandery-prefect", "hhs-group:109:京兆尹", 2, 1, 7,
            OfficeClaimOrigin.POLITY_APPOINTMENT, 10, acceptedTurn = 11)
        val contract = VassalContract("contract-1", 1, 2, 7, setOf(100), 20, 100,
            emptySet(), VassalDiplomacyRight.WITH_APPROVAL, VassalBreachKind.entries.toSet(), 60, 12)
        val vassals = VassalState(listOf(contract), emptyList())

        store.saveTenures(listOf(tenure))
        store.saveCredentials(emptyList())
        store.saveVassals(vassals)
        assertEquals(listOf(tenure), store.tenures())
        assertEquals(vassals, store.vassals())
        assertEquals("preserved", world.getState().meta["otherKey"])

        val writes = recorder.kvDirty()
        assertEquals(setOf(OfficeTenureCodec.META_KEY, OfficeCredentialCodec.META_KEY, VassalStateCodec.META_KEY),
            writes.keys.map { it.key }.toSet())
        assertEquals(setOf("game_env"), writes.keys.map { it.table }.toSet())
        assertEquals(setOf("game_env"), writes.keys.map { it.namespace }.toSet())
        val coldMeta = linkedMapOf<String, Any?>("otherKey" to "preserved")
        writes.forEach { (key, value) ->
            coldMeta[key.key] = MetaJson.decode("{\"value\":${MetaJson.encode(value)}}")["value"]
        }
        val coldStore = CourtStateStore(newWorld(coldMeta), ChangeRecorder())
        assertEquals(listOf(tenure), coldStore.tenures())
        assertEquals(emptyList(), coldStore.credentials())
        assertEquals(vassals, coldStore.vassals())
    }
}
