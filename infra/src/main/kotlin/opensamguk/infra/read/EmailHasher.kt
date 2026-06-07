package opensamguk.infra.read

import java.security.MessageDigest

/**
 * 영구차단용 이메일 해시 산출기 — legacy의 전역 salt + SHA512 패러티.
 *
 * grand truth: legacy BanEmailAddress.php:46 + RootDB.php:74(getGlobalSalt)
 *   `hash('sha512', globalSalt . email . globalSalt)` → 소문자 hex 128자.
 *
 * 전역 salt는 생성자로 주입한다(legacy의 `RootDB::$globalSalt` 대응 — 운영 시 비밀값).
 * gateway-api가 환경변수 `GLOBAL_SALT`(미설정 시 legacy 기본값 `'goldensalt'` 플레이스백)로 빈을 제공한다
 * ([opensamguk] 인프라는 component-scan 밖이므로 `@Component` 대신 명시 `@Bean`으로 배선).
 * **운영에서는 반드시 GLOBAL_SALT를 설정해야** 해시가 캡처 가능/일관적이다.
 *
 * 어드민 영구차단(B2e) 등록부와 회원가입 차단 검사부가 동일한 [hash]를 호출해야 한다.
 */
class EmailHasher(
    private val globalSalt: String,
) {
    /**
     * legacy `hash('sha512', salt . email . salt)` — 소문자 hex 128자.
     * 입력 이메일은 PHP와 동일하게 정규화 없이 그대로 연결한다(byte-parity).
     */
    fun hash(email: String): String {
        val digest = MessageDigest.getInstance("SHA-512")
        val bytes = digest.digest("$globalSalt$email$globalSalt".toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
