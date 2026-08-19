package opensamguk.gateway.security

import opensamguk.infra.entity.UserEntity
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.userdetails.UserDetails

/**
 * Spring Security [UserDetails] 구현체.
 */
class CustomUserDetails(
    private val user: UserEntity,
) : UserDetails {

    val id: Long = user.id

    /** 사람에게 보이는 표시 이름. V42 이전에 발급된 세션 대비로 아이디 폴백을 남긴다. */
    val nickname: String = user.nickname?.takeIf { it.isNotBlank() } ?: user.username

    override fun getAuthorities(): Collection<GrantedAuthority> =
        listOf(SimpleGrantedAuthority("ROLE_${user.role}"))

    override fun getPassword(): String = user.password

    override fun getUsername(): String = user.username

    override fun isAccountNonExpired(): Boolean = true

    override fun isAccountNonLocked(): Boolean = true

    override fun isCredentialsNonExpired(): Boolean = true

    override fun isEnabled(): Boolean = true
}
