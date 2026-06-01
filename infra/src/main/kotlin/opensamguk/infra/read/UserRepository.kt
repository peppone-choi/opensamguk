package opensamguk.infra.read

import opensamguk.infra.entity.UserEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.Optional

@Repository
interface UserRepository : JpaRepository<UserEntity, Long> {
    fun findByUsername(username: String): Optional<UserEntity>
    fun existsByUsername(username: String): Boolean
    fun existsByEmail(email: String): Boolean
}
