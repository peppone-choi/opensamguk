package opensamguk.gateway.profile

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.ZoneId

data class ProfileIconSettings(
    val maxBytes: Int,
)

@Configuration
class ProfileIconConfiguration {
    @Bean
    fun profileIconSettings(
        @Value("\${profile-icon.max-bytes:51200}") maxBytes: Int,
    ) = ProfileIconSettings(maxBytes)

    @Bean
    fun profileIconDecoder(settings: ProfileIconSettings) = ProfileIconDecoder(settings.maxBytes)

    @Bean
    fun profileIconRootStreamFactory() = ProfileIconRootStreamFactory { root ->
        Files.newDirectoryStream(root)
    }

    @Bean
    fun localProfileIconStorage(
        @Value("\${profile-icon.storage-root:/var/lib/opensamguk/profile-icons}") storageRoot: String,
        settings: ProfileIconSettings,
        rootStreamFactory: ProfileIconRootStreamFactory,
    ) = LocalProfileIconStorage(
        Path.of(storageRoot),
        maxStoredBytes = settings.maxBytes,
        rootStreamFactory = rootStreamFactory,
    )

    @Bean
    fun sharedProfileIconCatalog(
        @Value("\${profile-icon.shared-manifest:profile-icons/shared-manifest.json}") manifest: String,
    ) = SharedProfileIconCatalog.fromClasspath(manifest)

    @Bean("profileIconClock")
    fun profileIconClock(): Clock = Clock.systemUTC()

    @Bean("profileIconZoneId")
    fun profileIconZoneId(
        @Value("\${profile-icon.zone-id:Asia/Seoul}") zoneId: String,
    ): ZoneId = ZoneId.of(zoneId)
}
