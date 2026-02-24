package com.opensam.gateway.service

import com.opensam.gateway.entity.SystemSetting
import com.opensam.gateway.repository.SystemSettingRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime

@Service
class SystemSettingsService(
    private val systemSettingRepository: SystemSettingRepository,
) {
    private companion object {
        const val KEY_AUTH_FLAGS = "authFlags"
    }

    data class AuthFlags(
        val allowLogin: Boolean,
        val allowJoin: Boolean,
    )

    @Transactional(readOnly = true)
    fun getAuthFlags(): AuthFlags {
        val setting = systemSettingRepository.findById(KEY_AUTH_FLAGS).orElse(null)
        val value = setting?.value ?: mutableMapOf()
        val allowLogin = (value["allowLogin"] as? Boolean) ?: true
        val allowJoin = (value["allowJoin"] as? Boolean) ?: true
        return AuthFlags(allowLogin = allowLogin, allowJoin = allowJoin)
    }

    @Transactional
    fun updateAuthFlags(allowLogin: Boolean?, allowJoin: Boolean?): AuthFlags {
        val current = systemSettingRepository.findById(KEY_AUTH_FLAGS).orElse(SystemSetting(key = KEY_AUTH_FLAGS))
        allowLogin?.let { current.value["allowLogin"] = it }
        allowJoin?.let { current.value["allowJoin"] = it }
        current.updatedAt = OffsetDateTime.now()
        systemSettingRepository.save(current)
        return getAuthFlags()
    }
}
