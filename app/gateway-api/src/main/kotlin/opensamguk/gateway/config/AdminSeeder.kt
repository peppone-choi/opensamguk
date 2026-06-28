package opensamguk.gateway.config

import opensamguk.infra.entity.UserEntity
import opensamguk.infra.read.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Component
import java.time.LocalDateTime

/**
 * 부팅 시 관리자 계정을 1개 보장한다(없으면 생성, 있으면 관리자 상태로 보정한다).
 *
 * 자격증명은 환경변수에서만 읽는다: `ADMIN_USERNAME` / `ADMIN_PASSWORD`.
 * 평문 비밀번호를 코드/리포에 하드코딩하지 않는다 — 둘 중 하나라도 비어 있으면 시드를 건너뛴다.
 * 이미 같은 username이 있으면 role/password/grade를 env 기준으로 맞춘다(멱등 · 관리자 1개 유지).
 */
@Component
class AdminSeeder(
    private val userRepository: UserRepository,
    private val passwordEncoder: PasswordEncoder,
    @Value("\${ADMIN_USERNAME:}") private val adminUsername: String,
    @Value("\${ADMIN_PASSWORD:}") private val adminPassword: String,
) : ApplicationRunner {

    private val log = LoggerFactory.getLogger(AdminSeeder::class.java)

    override fun run(args: ApplicationArguments) {
        if (adminUsername.isBlank() || adminPassword.isBlank()) {
            log.info("Admin seed skipped — ADMIN_USERNAME/ADMIN_PASSWORD not set")
            return
        }
        val existing = userRepository.findByUsername(adminUsername).orElse(null)
        if (existing != null) {
            existing.password = passwordEncoder.encode(adminPassword)
            existing.role = "ADMIN"
            existing.grade = 6
            existing.updatedAt = LocalDateTime.now()
            userRepository.save(existing)
            log.info("Admin '{}' already exists — ensured role=ADMIN", adminUsername)
            return
        }
        userRepository.save(
            UserEntity(
                username = adminUsername,
                password = passwordEncoder.encode(adminPassword),
                nickname = adminUsername,
                role = "ADMIN",
                grade = 6,
            ),
        )
        log.info("Admin '{}' created (role=ADMIN)", adminUsername)
    }
}
