package opensamguk.gateway.service

import opensamguk.gateway.dto.AdminUserDto
import opensamguk.gateway.dto.AdminUserListResponse
import opensamguk.gateway.dto.BanEmailResult
import opensamguk.gateway.dto.ScrubResult
import opensamguk.gateway.dto.SystemFlagResponse
import opensamguk.gateway.dto.UserCommandResult
import opensamguk.gateway.security.AdminMemberGuard
import opensamguk.infra.entity.BannedMemberEntity
import opensamguk.infra.entity.SystemFlagEntity
import opensamguk.infra.entity.UserEntity
import opensamguk.infra.read.BannedMemberRepository
import opensamguk.infra.read.EmailHasher
import opensamguk.infra.read.SystemFlagRepository
import opensamguk.infra.read.UserRepository
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.SecureRandom
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 어드민 회원관리 서비스 — 루트DB(gateway 공유) `users`/`system_flag`/`banned_member` 직접 관리.
 *
 * grand truth:
 *   - 목록    : legacy `i_entrance/j_get_userlist.php`
 *   - 명령    : legacy `i_entrance/j_set_userlist.php`
 *   - 영구차단: legacy `src/sammo/API/Admin/BanEmailAddress.php`
 *
 * **strict 위반 아님**: 회원관리는 루트DB(gateway 유저) 대상이며 게임 내 general이 아니므로
 * one-daemon-write-rule(게임 엔진 단일 쓰기)이 적용되지 않는다 — JPA 직접 쓰기가 정상이다.
 *
 * 권한 게이트: `/admin/` 이하 전체가 [opensamguk.gateway.security.SecurityConfig]에서 role=ADMIN으로 막힌다.
 * 0.9.0 divergence: legacy의 GRADE 5/6 다단계는 ADMIN 단일 role로 단순화한다(인증 divergence, 패러티 위반 아님).
 * 회원 단일 명령(B2d)은 [AdminMemberGuard]로 self/peer 보호(자기 자신·다른 ADMIN 변경 거부)를 적용한다.
 */
@Service
class AdminMemberService(
    private val userRepository: UserRepository,
    private val systemFlagRepository: SystemFlagRepository,
    private val bannedMemberRepository: BannedMemberRepository,
    private val emailHasher: EmailHasher,
    private val guard: AdminMemberGuard,
    private val passwordEncoder: PasswordEncoder,
    private val serverRegistry: ServerRegistry,
) {
    // legacy TimeUtil 표시값 패러티 — 'YYYY-MM-DD HH:mm:ss'.
    private val dateTimeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    // legacy Util::randomStr 키스페이스(Util.php:264) — 0-9a-zA-Z.
    private val randomStrKeyspace = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
    private val secureRandom = SecureRandom()

    // ── B2a: 회원 목록 (legacy j_get_userlist.php) ──

    /**
     * 전체 회원 목록 + 실행 서버 + 전역 가입/로그인 허용 플래그.
     * legacy는 member_log(action_type=login) 최신으로 loginDate를 산출 — opensamguk은 users.last_login_at로 대체.
     */
    @Transactional(readOnly = true)
    fun listUsers(): AdminUserListResponse {
        // legacy `order by member.no asc` 패러티 — id 오름차순.
        val users = userRepository.findAll().sortedBy { it.id }.map { it.toAdminDto() }
        val flag = systemFlagRepository.findSingleton()
        return AdminUserListResponse(
            users = users,
            // legacy ServConfig::getServerList() 중 isRunning() — 멀티서버 레지스트리의 서버 id 목록으로 대체.
            servers = serverRegistry.all().map { it.id },
            allowJoin = flag?.allowJoin ?: false,
            allowLogin = flag?.allowLogin ?: false,
        )
    }

    private fun UserEntity.toAdminDto(): AdminUserDto = AdminUserDto(
        id = id,
        username = username,
        email = email,
        authType = oauthType,
        grade = grade,
        gradeLabel = gradeLabel(grade, role),
        blockUntil = blockUntil?.format(dateTimeFormat),
        nickname = nickname,
        // legacy IMGSVR 분기(j_get_userlist.php:21-26). picture 미설정이면 아이콘 없음(null).
        icon = picture,
        joinDate = createdAt.format(dateTimeFormat),
        lastLoginAt = lastLoginAt?.format(dateTimeFormat),
        deleteAfter = deleteAfter?.format(dateTimeFormat),
    )

    /**
     * 등급 라벨 — legacy admin_member.ts convUserGrade(0차단/1일반/4특별/5부운영자/6운영자, 그 외 숫자).
     * 0.9.0 divergence: grade 미설정(null)이면 role로 합성(ADMIN→운영자, USER→일반).
     */
    private fun gradeLabel(grade: Int?, role: String): String {
        if (grade == null) {
            return if (role == "ADMIN") "운영자" else "일반"
        }
        return when (grade) {
            0 -> "차단"
            1 -> "일반"
            4 -> "특별"
            5 -> "부운영자"
            6 -> "운영자"
            else -> grade.toString()
        }
    }

    // ── B2b: 시스템 플래그 토글 (legacy j_set_userlist.php:36-70) ──

    /** 전역 로그인 허용 토글 — legacy system.LOGIN. */
    @Transactional
    fun setAllowLogin(value: Boolean): SystemFlagResponse {
        val flag = loadOrCreateFlag()
        flag.allowLogin = value
        flag.updatedAt = LocalDateTime.now()
        val saved = systemFlagRepository.save(flag)
        return SystemFlagResponse(allowJoin = saved.allowJoin, allowLogin = saved.allowLogin)
    }

    /** 전역 가입 허용 토글 — legacy system.REG. */
    @Transactional
    fun setAllowJoin(value: Boolean): SystemFlagResponse {
        val flag = loadOrCreateFlag()
        flag.allowJoin = value
        flag.updatedAt = LocalDateTime.now()
        val saved = systemFlagRepository.save(flag)
        return SystemFlagResponse(allowJoin = saved.allowJoin, allowLogin = saved.allowLogin)
    }

    private fun loadOrCreateFlag(): SystemFlagEntity =
        systemFlagRepository.findSingleton()
            ?: SystemFlagEntity(id = SystemFlagRepository.SINGLETON_ID)

    // ── B2c: 계정 정리 (legacy j_set_userlist.php:72-144) ──

    /**
     * 탈퇴 신청 회원 정리 — legacy scrub_deleted(`delete_after < today`).
     * legacy는 TimeUtil::today()(날짜 경계) 기준. opensamguk은 오늘 00:00 미만(과거)을 정리한다.
     */
    @Transactional
    fun scrubDeleted(): ScrubResult {
        val todayStart = LocalDateTime.now().toLocalDate().atStartOfDay()
        val targets = userRepository.findAll().filter { u ->
            val da = u.deleteAfter
            da != null && da.isBefore(todayStart)
        }
        userRepository.deleteAll(targets)
        return ScrubResult(affected = targets.size)
    }

    /**
     * 6개월+ 미접속 회원 정리 — legacy scrub_old_user(`loginDate <= now-6개월` 또는 loginDate 없음).
     * legacy는 member_log(login/reg) 최신을 loginDate로 본다 — opensamguk은 users.last_login_at로 대체하고,
     * last_login_at이 null(접속/가입 기록 없음)이면 정리 대상으로 본다(legacy `loginDate === null` 분기 패러티).
     */
    @Transactional
    fun scrubOldUsers(): ScrubResult {
        // legacy TimeUtil::nowAddMinutes(-60*24*30*6) = 6개월 전(=30일*6 기준).
        val cutoff = LocalDateTime.now().minusMinutes(60L * 24 * 30 * 6)
        val targets = userRepository.findAll().filter { u ->
            val last = u.lastLoginAt
            last == null || !last.isAfter(cutoff) // null이거나 cutoff 이하(<=).
        }
        userRepository.deleteAll(targets)
        return ScrubResult(affected = targets.size)
    }

    // ── B2d: 회원 단일 명령 (legacy j_set_userlist.php:146-296) ──

    /**
     * 회원 강제 명령 dispatch — legacy delete/reset_pw/block/unblock/set_userlevel.
     *
     * self/peer 보호([AdminMemberGuard.assertMutable])를 모든 mutating 액션 직전에 적용한다
     * (legacy `:162` 동급/상위 거부 + `:274` 권한설정 상한의 0.9.0 단순화).
     *
     * @param actorId 행위 어드민 user id, @param actorRole 행위자 role(항상 ADMIN, 방어적 재검사),
     *        @param targetId 대상 user id, @param action 명령, @param param block/set_userlevel용 정수.
     */
    @Transactional
    fun runUserCommand(
        actorId: Long,
        actorRole: String,
        targetId: Long,
        action: String,
        param: Int?,
    ): UserCommandResult {
        val target = userRepository.findById(targetId).orElse(null)
            ?: return UserCommandResult(result = false, reason = "해당하는 유저가 없습니다.")

        // legacy `:162,274` self/peer 보호 — 자기 자신/다른 ADMIN 거부.
        guard.assertMutable(actorId = actorId, actorRole = actorRole, target = target)

        return when (action) {
            "delete" -> {
                userRepository.delete(target)
                UserCommandResult(result = true)
            }

            "reset_pw" -> {
                // legacy `:190` Util::randomStr(6) — 임시 평문 6자 후 인코딩 저장, 평문은 detail로 안내.
                val newPassword = randomStr(6)
                target.password = passwordEncoder.encode(newPassword)
                target.updatedAt = LocalDateTime.now()
                userRepository.save(target)
                // legacy `:210` 문구 byte-parity.
                UserCommandResult(result = true, detail = "비밀번호가 ${newPassword}로 초기화되었습니다.")
            }

            "block" -> {
                // legacy `:216-230` param 일수, ≤0이면 50*365일. grade=0 + block_date = now + param일.
                if (param == null) {
                    return UserCommandResult(result = false, reason = "올바르지 않은 param")
                }
                val days = if (param <= 0) 50L * 365 else param.toLong()
                target.grade = 0
                target.blockUntil = LocalDateTime.now().plusDays(days)
                target.updatedAt = LocalDateTime.now()
                userRepository.save(target)
                UserCommandResult(result = true)
            }

            "unblock" -> {
                // legacy `:246-250` grade=1 + block_date=null.
                target.grade = 1
                target.blockUntil = null
                target.updatedAt = LocalDateTime.now()
                userRepository.save(target)
                UserCommandResult(result = true)
            }

            "set_userlevel" -> {
                // legacy `:265-283` param>=1, param>=본인 grade 거부. 0.9.0: 본인=ADMIN이므로
                // 6(운영자) 이상은 거부(다른 ADMIN 승격 금지 = peer 보호의 등급판), 그 외 grade 설정.
                if (param == null || param < 1) {
                    return UserCommandResult(result = false, reason = "올바르지 않은 param")
                }
                if (param >= 6) {
                    return UserCommandResult(result = false, reason = "관리자보다 같거나 높은 등급을 설정할 수 없습니다.")
                }
                target.grade = param
                target.updatedAt = LocalDateTime.now()
                userRepository.save(target)
                UserCommandResult(result = true)
            }

            else -> UserCommandResult(result = false, reason = "알 수 없는 명령입니다. action:$action")
        }
    }

    // ── B2e: 이메일 영구차단 (legacy BanEmailAddress.php) ──

    /**
     * 이메일 영구차단 — legacy `sha512(salt|email|salt)` 해시를 banned_member에 삽입.
     * 중복(이미 등록)은 unique 제약 위반으로 거부하고 legacy `:51` 문구를 돌려준다.
     */
    @Transactional
    fun banEmail(email: String): BanEmailResult {
        val hashed = emailHasher.hash(email)
        if (bannedMemberRepository.existsByHashedEmail(hashed)) {
            return BanEmailResult(result = false, reason = "이미 등록된 이메일입니다.")
        }
        return try {
            bannedMemberRepository.save(
                BannedMemberEntity(
                    hashedEmail = hashed,
                    // legacy `:47` info => TimeUtil::now() — 등록 시각 문자열.
                    info = LocalDateTime.now().format(dateTimeFormat),
                ),
            )
            // legacy `:54-57` 패러티.
            BanEmailResult(result = true, reason = "등록되었습니다.")
        } catch (e: DataIntegrityViolationException) {
            // 동시 삽입 경합 등 unique 위반 — legacy catch 분기.
            BanEmailResult(result = false, reason = "이미 등록된 이메일입니다.")
        }
    }

    /** legacy Util::randomStr — 키스페이스에서 length개 무작위 추출(SecureRandom = PHP random_int 대응). */
    private fun randomStr(length: Int): String {
        val max = randomStrKeyspace.length
        val sb = StringBuilder(length)
        repeat(length) { sb.append(randomStrKeyspace[secureRandom.nextInt(max)]) }
        return sb.toString()
    }
}
