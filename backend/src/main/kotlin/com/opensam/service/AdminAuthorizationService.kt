package com.opensam.service

import com.opensam.entity.AppUser
import com.opensam.entity.WorldState
import com.opensam.repository.AppUserRepository
import com.opensam.repository.WorldStateRepository
import org.springframework.security.access.AccessDeniedException
import org.springframework.stereotype.Service

@Service
class AdminAuthorizationService(
    private val appUserRepository: AppUserRepository,
    private val worldStateRepository: WorldStateRepository,
) {
    private companion object {
        const val PERMISSION_ADMIN_PROFILES = "admin.profiles.manage"
        const val GRADE_SERVER_ADMIN = 5
        const val GRADE_SYSTEM_ADMIN = 6

        val GRADE_BYPASS_PERMISSIONS = setOf(
            "openClose",
            "blockGeneral",
            "notice",
            "admin.profiles.manage",
            "admin.resume.when-stopped",
            "admin.reset.schedule",
            "admin.survey.open",
        )
    }

    fun resolveWorldIdOrThrow(loginId: String, requestedWorldId: Long?, permission: String): Long {
        val user = requireUser(loginId)
        val worlds = worldStateRepository.findAll().sortedBy { it.id }
        if (worlds.isEmpty()) {
            throw IllegalArgumentException("월드가 없습니다.")
        }

        val targetWorld = if (requestedWorldId != null) {
            worlds.firstOrNull { it.id.toLong() == requestedWorldId }
                ?: throw IllegalArgumentException("월드를 찾을 수 없습니다.")
        } else {
            worlds.firstOrNull { hasWorldPermission(user, it, permission) }
                ?: throw AccessDeniedException("접근 가능한 월드가 없습니다.")
        }

        if (!hasWorldPermission(user, targetWorld, permission)) {
            throw AccessDeniedException("해당 월드 관리자 권한이 없습니다.")
        }
        return targetWorld.id.toLong()
    }

    fun requireGlobalAdmin(loginId: String) {
        val user = requireUser(loginId)
        if (!isGlobalAdmin(user)) {
            throw AccessDeniedException("전역 관리자 권한이 없습니다.")
        }
    }

    fun requirePermission(loginId: String, permission: String, scope: String? = null) {
        val user = requireUser(loginId)
        if (hasScopedPermission(user, permission, scope)) {
            return
        }
        throw AccessDeniedException("요청한 관리자 권한이 없습니다.")
    }

    fun requireAnyPermission(loginId: String, permissions: Collection<String>, scope: String? = null) {
        val user = requireUser(loginId)
        if (permissions.any { hasScopedPermission(user, it, scope) }) {
            return
        }
        throw AccessDeniedException("요청한 관리자 권한이 없습니다.")
    }

    private fun requireUser(loginId: String): AppUser {
        return appUserRepository.findByLoginId(loginId)
            ?: throw AccessDeniedException("유효하지 않은 사용자입니다.")
    }

    private fun isGlobalAdmin(user: AppUser): Boolean {
        if (userGrade(user) >= GRADE_SYSTEM_ADMIN) {
            return true
        }
        val roles = parseRoles(user)
        return roles.contains("superuser") || roles.contains("admin.superuser")
    }

    private fun isServerAdmin(user: AppUser): Boolean {
        return userGrade(user) >= GRADE_SERVER_ADMIN || isGlobalAdmin(user)
    }

    private fun userGrade(user: AppUser): Int {
        val grade = user.grade.toInt().coerceIn(0, 7)
        if (user.role.uppercase() == "ADMIN" && grade < GRADE_SERVER_ADMIN) {
            return GRADE_SYSTEM_ADMIN
        }
        return grade
    }

    private fun hasScopedPermission(user: AppUser, permission: String, scope: String?): Boolean {
        if (isGlobalAdmin(user)) {
            return true
        }

        if (isServerAdmin(user) && permission in GRADE_BYPASS_PERMISSIONS) {
            return true
        }

        val roles = parseRoles(user)
        if (roles.any { roleMatchesScope(it, permission, scope) }) {
            return true
        }

        if (permission == PERMISSION_ADMIN_PROFILES && hasLegacyProfilesPermission(user)) {
            return true
        }

        return false
    }

    private fun parseRoles(user: AppUser): Set<String> {
        val raw = user.meta["roles"]
        if (raw !is Collection<*>) {
            return emptySet()
        }
        return raw
            .mapNotNull { it?.toString()?.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
    }

    private fun roleMatchesScope(role: String, permission: String, scope: String?): Boolean {
        if (role == permission || role == "$permission:*") {
            return true
        }
        if (!scope.isNullOrBlank() && role == "$permission:$scope") {
            return true
        }
        return false
    }

    private fun hasLegacyProfilesPermission(user: AppUser): Boolean {
        val acl = parseAcl(user)
        val globalPermissions = acl["global"].orEmpty()
        return containsPermission(globalPermissions, "openClose") || containsPermission(globalPermissions, "*")
    }

    private fun hasWorldPermission(user: AppUser, world: WorldState, permission: String): Boolean {
        if (isGlobalAdmin(user)) {
            return true
        }

        if (isServerAdmin(user) && permission in GRADE_BYPASS_PERMISSIONS) {
            return true
        }

        val acl = parseAcl(user)
        val globalPermissions = acl["global"].orEmpty()
        if (containsPermission(globalPermissions, permission)) {
            return true
        }

        val worldIdPermissions = acl[world.id.toString()].orEmpty()
        if (containsPermission(worldIdPermissions, permission)) {
            return true
        }

        val scenarioPermissions = acl[world.scenarioCode].orEmpty()
        if (containsPermission(scenarioPermissions, permission)) {
            return true
        }

        val worldName = world.name.trim()
        if (worldName.isNotEmpty()) {
            val worldNamePermissions = acl[worldName].orEmpty()
            if (containsPermission(worldNamePermissions, permission)) {
                return true
            }
        }

        return false
    }

    private fun parseAcl(user: AppUser): Map<String, Set<String>> {
        val raw = user.meta["acl"] as? Map<*, *> ?: return emptyMap()
        val parsed = linkedMapOf<String, Set<String>>()
        raw.entries.forEach { (key, value) ->
            val aclKey = key?.toString()?.trim().orEmpty()
            if (aclKey.isEmpty()) {
                return@forEach
            }
            val permissions = when (value) {
                is Collection<*> -> value.mapNotNull { it?.toString()?.trim() }.filter { it.isNotEmpty() }.toSet()
                else -> emptySet()
            }
            parsed[aclKey] = permissions
        }
        return parsed
    }

    private fun containsPermission(permissions: Set<String>, permission: String): Boolean {
        return permissions.contains(permission) || permissions.contains("*")
    }
}
