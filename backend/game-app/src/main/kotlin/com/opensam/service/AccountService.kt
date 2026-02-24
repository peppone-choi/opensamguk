package com.opensam.service

import com.opensam.repository.AppUserRepository
import com.opensam.repository.GeneralRepository
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service

@Service
class AccountService(
    private val appUserRepository: AppUserRepository,
    private val generalRepository: GeneralRepository,
    private val passwordEncoder: PasswordEncoder,
) {
    fun changePassword(loginId: String, currentPassword: String, newPassword: String): Boolean {
        val user = appUserRepository.findByLoginId(loginId) ?: return false
        if (!passwordEncoder.matches(currentPassword, user.passwordHash)) return false
        user.passwordHash = passwordEncoder.encode(newPassword)
        appUserRepository.save(user)
        return true
    }

    fun deleteAccount(loginId: String): Boolean {
        val user = appUserRepository.findByLoginId(loginId) ?: return false
        val generals = generalRepository.findByUserId(user.id)
        generals.forEach { generalRepository.delete(it) }
        appUserRepository.delete(user)
        return true
    }

    fun updateSettings(
        loginId: String,
        defenceTrain: Int?,
        tournamentState: Int?,
        potionThreshold: Int?,
        autoNationTurn: Boolean?,
        preRiseDelete: Boolean?,
        preOpenDelete: Boolean?,
        borderReturn: Boolean?,
        customCss: String?,
    ): Boolean {
        val user = appUserRepository.findByLoginId(loginId) ?: return false
        val generals = generalRepository.findByUserId(user.id)
        generals.forEach { gen ->
            defenceTrain?.let { gen.defenceTrain = it.toShort() }
            tournamentState?.let { gen.tournamentState = it.toShort() }

            val meta = gen.meta.toMutableMap()
            potionThreshold?.let { meta["potionThreshold"] = it }
            autoNationTurn?.let { meta["autoNationTurn"] = it }
            preRiseDelete?.let { meta["preRiseDelete"] = it }
            preOpenDelete?.let { meta["preOpenDelete"] = it }
            borderReturn?.let { meta["borderReturn"] = it }
            customCss?.let { meta["customCss"] = it }
            gen.meta = meta

            generalRepository.save(gen)
        }
        return true
    }

    fun toggleVacation(loginId: String): Boolean {
        val user = appUserRepository.findByLoginId(loginId) ?: return false
        val generals = generalRepository.findByUserId(user.id)
        generals.forEach { gen ->
            val current = gen.meta["vacationMode"] as? Boolean ?: false
            gen.meta["vacationMode"] = !current
            generalRepository.save(gen)
        }
        return true
    }
}
