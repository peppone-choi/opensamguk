package opensamguk.gateway.profile

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.ZoneId

data class ProfileIconSettings(
    /** 저장 상한. 변환 뒤 바이트가 이 안에 들어와야 한다. */
    val maxBytes: Int,
    /** 업로드 입력 상한. 서버가 카드 규격으로 다시 인코딩하므로 저장 상한보다 커도 된다. */
    val uploadMaxBytes: Int,
    /** 디코더가 감당하는 한 변 최대 픽셀. */
    val maxDimension: Int,
)

@Configuration
class ProfileIconConfiguration {
    @Bean
    fun profileIconSettings(
        @Value("\${profile-icon.max-bytes:51200}") maxBytes: Int,
        @Value("\${profile-icon.upload-max-bytes:4194304}") uploadMaxBytes: Int,
        @Value("\${profile-icon.max-dimension:4096}") maxDimension: Int,
    ) = ProfileIconSettings(maxBytes, uploadMaxBytes, maxDimension)

    @Bean
    fun profileIconDecoder(settings: ProfileIconSettings) = ProfileIconDecoder(
        maxBytes = settings.uploadMaxBytes,
        minDimension = 32,
        maxDimension = settings.maxDimension,
    )

    @Bean
    fun profileIconTransformer(settings: ProfileIconSettings) =
        ProfileIconTransformer(maxStoredBytes = settings.maxBytes)

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
