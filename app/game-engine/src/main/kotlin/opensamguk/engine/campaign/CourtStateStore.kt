package opensamguk.engine.campaign

import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.infra.persistence.MetaJson
import opensamguk.logic.content.PersistedMetaJson
import opensamguk.logic.office.OfficeCredential
import opensamguk.logic.office.OfficeCredentialCodec
import opensamguk.logic.office.OfficeTenure
import opensamguk.logic.office.OfficeTenureCodec
import opensamguk.logic.vassal.VassalState
import opensamguk.logic.vassal.VassalStateCodec

/** Keeps each court state key independent in game_env, including after cold reload. */
class CourtStateStore(private val world: InMemoryTurnWorld, private val recorder: ChangeRecorder) {
    fun tenures(): List<OfficeTenure> = OfficeTenureCodec.decode(
        PersistedMetaJson.raw(world.getState().meta[OfficeTenureCodec.META_KEY]))

    fun credentials(): List<OfficeCredential> = OfficeCredentialCodec.decode(
        PersistedMetaJson.raw(world.getState().meta[OfficeCredentialCodec.META_KEY]))

    fun vassals(): VassalState = VassalStateCodec.decode(
        PersistedMetaJson.raw(world.getState().meta[VassalStateCodec.META_KEY]))

    fun saveTenures(tenures: Collection<OfficeTenure>) = write(OfficeTenureCodec.META_KEY, OfficeTenureCodec.encode(tenures))

    fun saveCredentials(credentials: Collection<OfficeCredential>) =
        write(OfficeCredentialCodec.META_KEY, OfficeCredentialCodec.encode(credentials))

    fun saveVassals(state: VassalState) = write(VassalStateCodec.META_KEY, VassalStateCodec.encode(state))

    private fun write(key: String, raw: String) {
        val value = MetaJson.decode(raw)
        world.setGameEnvValue(key, value)
        recorder.recordKv("game_env", "game_env", key, value)
    }
}
