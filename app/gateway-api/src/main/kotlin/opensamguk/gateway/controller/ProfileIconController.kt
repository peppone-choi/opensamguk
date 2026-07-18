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
    ): ResponseEntity<UserResponse> {
        if (file.isEmpty) {
            throw InvalidProfileIconException()
        }
        if (file.size > settings.maxBytes) {
            throw ProfileIconPayloadTooLargeException()
        }
        val bytes = file.inputStream.use { it.readNBytes(settings.maxBytes + 1) }
        if (bytes.size > settings.maxBytes) {
            throw ProfileIconPayloadTooLargeException()
        }
        return ResponseEntity.ok(profileIconService.upload(userDetails, bytes))
    }

    @PostMapping(consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun selectShared(
        @AuthenticationPrincipal userDetails: CustomUserDetails,
        @Valid @RequestBody request: ProfileIconRequest,
    ): ResponseEntity<UserResponse> = ResponseEntity.ok(profileIconService.selectShared(userDetails, request))

    @DeleteMapping
    fun delete(@AuthenticationPrincipal userDetails: CustomUserDetails): ResponseEntity<Void> {
        profileIconService.delete(userDetails)
        return ResponseEntity.noContent().build()
    }
}
