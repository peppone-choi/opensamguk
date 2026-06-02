package opensamguk.gateway.config

import opensamguk.infra.entity.UserEntity
import opensamguk.infra.read.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Component

/**
 * 부팅 시 관리자 계정을 1개 보장한다(없으면 생성, 있으면 그대로 둔다).
 *
 * 자격증명은 환경변수에서만 읽는다: `ADMIN_USERNAME` / `ADMIN_PASSWORD`.
 * 평문 비밀번호를 코드/리포에 하드코딩하지 않는다 — 둘 중 하나라도 비어 있으면 시드를 건너뛴다.
 * 이미 같은 username이 있으면 아무 것도 하지 않는다(멱등 · 관리자 1개 유지).
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
        if (userRepository.existsByUsername(adminUsername)) {
            log.info("Admin '{}' already exists — skip", adminUsername)
            return
        }
        userRepository.save(
            UserEntity(
                username = adminUsername,
                password = passwordEncoder.encode(adminPassword),
                nickname = adminUsername,
                role = "ADMIN",
            ),
        )
        log.info("Admin '{}' created (role=ADMIN)", adminUsername)
    }
}
