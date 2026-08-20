package opensamguk.infra.read

import opensamguk.infra.entity.UserEntity
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.util.Optional

@Repository
interface UserRepository : JpaRepository<UserEntity, Long> {
    fun findByUsername(username: String): Optional<UserEntity>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select user from UserEntity user where user.username = :username")
    fun findByUsernameForUpdate(@Param("username") username: String): Optional<UserEntity>

    fun existsByUsername(username: String): Boolean

    fun existsByNicknameIgnoreCase(nickname: String): Boolean
    fun existsByEmail(email: String): Boolean
    fun existsByPictureAndProfileIconManagedTrue(picture: String): Boolean
}
