package com.opensam.repository

import com.opensam.entity.AppUser
import org.springframework.data.jpa.repository.JpaRepository

interface AppUserRepository : JpaRepository<AppUser, Long> {
    fun findByLoginId(loginId: String): AppUser?
    fun existsByLoginId(loginId: String): Boolean
}
