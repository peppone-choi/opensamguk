package opensamguk.engine.turn

import opensamguk.logic.event.EventStore

/** Runs one independently recoverable unit against a paired world and recorder savepoint. */
class TurnUnitExecutor(
    private val world: InMemoryTurnWorld,
    private val recorder: ChangeRecorder,
    private val eventStore: EventStore? = null,
) {
    sealed interface Outcome<out T> {
        data class Succeeded<T>(val value: T) : Outcome<T>
        data class Failed(val cause: Exception) : Outcome<Nothing>
    }

    fun <T> run(block: () -> T): Outcome<T> {
        val worldCheckpoint = world.checkpoint()
        val recorderCheckpoint = try {
            recorder.checkpoint()
        } catch (error: Throwable) {
            world.restore(worldCheckpoint)
            throw error
        }
        val eventCheckpoint = try {
            eventStore?.checkpoint()
        } catch (error: Throwable) {
            world.restore(worldCheckpoint)
            recorder.restore(recorderCheckpoint)
            throw error
        }
        return try {
            val value = block()
            world.commit(worldCheckpoint)
            Outcome.Succeeded(value)
        } catch (error: Exception) {
            // A failed restore is a world-level invariant failure; let it reach the recovery gate.
            world.restore(worldCheckpoint)
            if (eventCheckpoint != null) checkNotNull(eventStore).restore(eventCheckpoint)
            recorder.restore(recorderCheckpoint)
            if (error is InterruptedException || Thread.currentThread().isInterrupted) {
                Thread.currentThread().interrupt()
                throw error
            }
            if (error is java.util.concurrent.CancellationException) throw error
            Outcome.Failed(error)
        }
    }
}
