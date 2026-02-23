package com.opensam.gateway.bootstrap

import com.opensam.gateway.entity.AppUser
import com.opensam.gateway.repository.AppUserRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
class AdminUserBootstrap(
    private val appUserRepository: AppUserRepository,
    private val passwordEncoder: PasswordEncoder,
    @Value("\${app.bootstrap.admin.enabled:false}")
    private val enabled: Boolean,
    @Value("\${app.bootstrap.admin.login-id:}")
    private val loginId: String,
    @Value("\${app.bootstrap.admin.password:}")
    private val password: String,
    @Value("\${app.bootstrap.admin.display-name:관리자}")
    private val displayName: String,
    @Value("\${app.bootstrap.admin.grade:5}")
    private val grade: Int,
) : ApplicationRunner {
    private val log = LoggerFactory.getLogger(AdminUserBootstrap::class.java)

    @Transactional
    override fun run(args: ApplicationArguments) {
        if (!enabled) {
            return
        }

        val targetLoginId = loginId.trim()
        if (targetLoginId.isEmpty() || password.isBlank()) {
            log.warn("Admin bootstrap skipped: login-id or password is empty")
            return
        }

        val targetGrade = if (grade < 5) 5 else grade
        val targetRole = "ADMIN"
        val existing = appUserRepository.findByLoginId(targetLoginId)

        if (existing == null) {
            appUserRepository.save(
                AppUser(
                    loginId = targetLoginId,
                    displayName = displayName,
                    passwordHash = passwordEncoder.encode(password),
                    role = targetRole,
                    grade = targetGrade.toShort(),
                ),
            )
            log.info("Admin bootstrap created user loginId={} grade={}", targetLoginId, targetGrade)
            return
        }

        var changed = false

        if (existing.displayName != displayName) {
            existing.displayName = displayName
            changed = true
        }

        if (existing.grade.toInt() != targetGrade) {
            existing.grade = targetGrade.toShort()
            changed = true
        }

        if (existing.role != targetRole) {
            existing.role = targetRole
            changed = true
        }

        if (!passwordEncoder.matches(password, existing.passwordHash)) {
            existing.passwordHash = passwordEncoder.encode(password)
            changed = true
        }

        if (changed) {
            appUserRepository.save(existing)
            log.info("Admin bootstrap updated user loginId={} grade={}", targetLoginId, targetGrade)
        } else {
            log.info("Admin bootstrap user already up to date loginId={}", targetLoginId)
        }
    }
}
