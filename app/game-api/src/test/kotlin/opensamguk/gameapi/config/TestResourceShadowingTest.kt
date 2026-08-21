package opensamguk.gameapi.config

import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * OPENSAM-221 (GH #487) 재발 방지.
 *
 * Spring 의 `application.yml` 은 키 단위가 아니라 **파일 단위로** 가려진다. 테스트 리소스에
 * `application.yml` 을 만들면 main 의 `application.yml` 이 통째로 사라지고, 거기 있던
 * `spring.flyway.postgresql.transactional-lock: false` 도 함께 증발한다. 그러면 V29 의
 * `CREATE INDEX CONCURRENTLY` 가 Flyway 트랜잭션형 advisory lock 과 상호 대기에 빠져
 * 테스트가 영원히 매달리고 CI 배포 파이프 전체가 멈춘다(실측 3546s, CPU 13s).
 *
 * 주석만으로는 강제되지 않으므로 파일 존재 자체를 실패로 만든다. 모듈 테스트 오버라이드는
 * `application-test.yml` 에 두고 `@ActiveProfiles("test")` 로 태워라.
 */
class TestResourceShadowingTest {
    private val testResources = Path.of("src", "test", "resources")

    @Test
    fun `test resources must not shadow the main application yml`() {
        for (name in listOf("application.yml", "application.yaml", "application.properties")) {
            assertFalse(
                testResources.resolve(name).exists(),
                "src/test/resources/$name 은 main 의 application.yml 을 파일 단위로 가린다. " +
                    "오버라이드는 application-test.yml 에 두고 @ActiveProfiles(\"test\") 를 붙여라 (OPENSAM-221).",
            )
        }
    }

    @Test
    fun `the test profile keeps the non-transactional flyway lock`() {
        val profile = testResources.resolve("application-test.yml")
        assertTrue(profile.exists(), "application-test.yml 이 있어야 한다")
        val text = profile.toFile().readText()
        assertTrue(
            Regex("""transactional-lock:\s*false""").containsMatchIn(text),
            "application-test.yml 은 flyway.postgresql.transactional-lock: false 를 유지해야 한다. " +
                "없으면 V29 CREATE INDEX CONCURRENTLY 가 데드락 난다 (OPENSAM-221).",
        )
    }
}
