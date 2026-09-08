package opensamguk.gateway.controller

import jakarta.validation.Valid
import opensamguk.gateway.dto.ProfileIconRequest
import opensamguk.gateway.dto.UserResponse
import opensamguk.gateway.profile.InvalidProfileIconException
import opensamguk.gateway.profile.ProfileIconPayloadTooLargeException
import opensamguk.gateway.profile.ProfileIconService
import opensamguk.gateway.profile.ProfileIconSettings
import opensamguk.gateway.security.CustomUserDetails
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import opensamguk.gateway.profile.PortraitBundle
import opensamguk.gateway.profile.PortraitCrops
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile

@RestController
@RequestMapping("/auth/account/profile-icon")
class ProfileIconController(
    private val profileIconService: ProfileIconService,
    private val settings: ProfileIconSettings,
) {
    @PostMapping(consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun upload(
        @AuthenticationPrincipal userDetails: CustomUserDetails,
        @RequestPart("file") file: MultipartFile,
        @RequestPart("crops", required = false) crops: String? = null,
    ): ResponseEntity<UserResponse> {
        val cropRequest = crops?.let(PortraitBundle::parseCrops)
        val maxBytes = if (cropRequest == null) settings.maxBytes else PortraitBundle.MAX_SOURCE_BYTES
        if (file.isEmpty) {
            throw InvalidProfileIconException()
        }
        if (file.size > maxBytes) {
            throw ProfileIconPayloadTooLargeException(if (cropRequest == null) "프로필 아이콘은 50KB 이하여야 합니다." else "초상 원본은 8MiB 이하여야 합니다.")
        }
        val bytes = file.inputStream.use { it.readNBytes(maxBytes + 1) }
        if (bytes.size > maxBytes) {
            throw ProfileIconPayloadTooLargeException(if (cropRequest == null) "프로필 아이콘은 50KB 이하여야 합니다." else "초상 원본은 8MiB 이하여야 합니다.")
        }
        return ResponseEntity.ok(profileIconService.upload(userDetails, bytes, cropRequest))
    }

    @PostMapping(consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun selectShared(
        @AuthenticationPrincipal userDetails: CustomUserDetails,
        @Valid @RequestBody request: ProfileIconRequest,
    ): ResponseEntity<UserResponse> = ResponseEntity.ok(profileIconService.selectShared(userDetails, request))

    @GetMapping("/source")
    fun source(@AuthenticationPrincipal userDetails: CustomUserDetails): ResponseEntity<ByteArray> {
        val (name, type, bytes) = profileIconService.source(userDetails)
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(type))
            .header("Cache-Control", "private, no-store")
            .header("X-Portrait-Id", name)
            .header("X-Content-Type-Options", "nosniff")
            .body(bytes)
    }

    @GetMapping("/crops")
    fun crops(@AuthenticationPrincipal userDetails: CustomUserDetails): ResponseEntity<PortraitCrops> {
        val (name, crops) = profileIconService.crops(userDetails)
        return ResponseEntity.ok().header("Cache-Control", "private, no-store")
            .header("X-Portrait-Id", name).body(crops)
    }

    @DeleteMapping
    fun delete(@AuthenticationPrincipal userDetails: CustomUserDetails): ResponseEntity<Void> {
        profileIconService.delete(userDetails)
        return ResponseEntity.noContent().build()
    }
}
