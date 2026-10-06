package opensamguk.engine.boot

import opensamguk.infra.seed.SelectedSourceUnavailable
import java.util.ServiceConfigurationError
import java.util.ServiceLoader

/** Root's reviewed installation must supply independently pinned native inputs and a byte transport.
 * This interface does not make its publisher an issuer or grant database authority. */
interface D101PreIntentCaptureInstallation : AutoCloseable {
    val fixedSource: D101NativePreIntentInputsSource
    fun deliverUnsigned(snapshot: D101PreIntentSelectedSnapshot)
}

fun interface D101PreIntentCaptureProvider {
    fun install(): D101PreIntentCaptureInstallation
}

/** One read-only capture before any final intent or selected capture index exists. */
internal object D101PreIntentCaptureProduction {
    fun run(): Int = try {
        runWithProviders(ServiceLoader.load(D101PreIntentCaptureProvider::class.java).toList())
    } catch (_: ServiceConfigurationError) {
        78
    } catch (_: Exception) {
        78
    }

    internal fun runWithProviders(providers: List<D101PreIntentCaptureProvider>): Int = try {
        if (providers.size != 1) throw SelectedSourceUnavailable()
        providers.single().install().use { installation ->
            // The provider may transport these unsigned facts to Root, but cannot turn
            // them into a signed receipt or a complete index from this JVM alone.
            installation.deliverUnsigned(D101PreIntentSelectedCapture(installation.fixedSource).capture())
        }
        0
    } catch (_: Exception) {
        78
    }
}
