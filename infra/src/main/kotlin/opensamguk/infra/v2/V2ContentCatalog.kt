package opensamguk.infra.v2

import org.springframework.core.io.support.PathMatchingResourcePatternResolver

/**
 * OPENSAM-35 0A-d — `content/v2/` **읽기 전용** 카탈로그 로더.
 *
 * `ScenarioCatalogService`(`app/gateway-api/.../service/ScenarioCatalogService.kt:13-16`)의
 * [PathMatchingResourcePatternResolver] 패턴을 그대로 재사용한다. 다른 점은 스코프뿐이다 —
 * 이 로더는 [DEFAULT_LOCATION] **한 디렉터리의 직속 `*.json`만** 본다.
 *
 * ## 이 클래스가 하지 **않는** 것 (0A-d의 본체)
 *
 * - **startup seed 없음.** `ApplicationRunner`/`CommandLineRunner`를 구현하지 않는다. 부팅 시
 *   아무 메서드도 자동 호출되지 않으며, 호출자가 [names]/[read]를 부를 때만 클래스패스를 읽는다.
 *   v1의 `ScenarioSeedRunner` 경로와는 어떤 접점도 없다.
 * - **DB 쓰기 없음.** `DataSource`·`JdbcTemplate`·`EntityManager`·`ChangeRecorder`를 의존하지 않는다.
 *   이 두 가지는 주석이 아니라 `V2ContentCatalogTest`의 클래스파일 상수풀 스캔이 강제한다.
 * - **전역 classpath scan 없음.** 패턴이 location 직속 `.json`으로 한정되고 `**`를 쓰지 않아 하위
 *   디렉터리로 재귀하지 않는다. (S1이 Flyway에서 겪은 재귀 스캔 사고의 반대 방향 실수를 막는다.)
 * - **쓰기 메서드 없음.** 조회 2개가 API 전부다.
 *
 * 콘텐츠 파일이 0개면 [names]는 빈 목록을 돌려준다 — 예외를 던지지 않는다. `classpath*:` 접두사는
 * 루트가 없어도 빈 배열을 반환한다.
 *
 * @param location 클래스패스 루트 기준 디렉터리. 기본값 [DEFAULT_LOCATION]. 파라미터는 테스트가
 *   스코프 격리를 실측하기 위해 존재한다(운영 코드는 기본값을 쓴다).
 */
class V2ContentCatalog(private val location: String = DEFAULT_LOCATION) {

    private val resolver = PathMatchingResourcePatternResolver()

    /** [location] 직속 `*.json` 파일명 목록(사전순). 없으면 빈 목록. */
    fun names(): List<String> = entries().mapNotNull { it.filename }.sorted()

    /** [names]에 들어 있는 파일 하나의 원문. 없으면 `null`. 이름 대조라 경로 탈출이 불가능하다. */
    fun read(name: String): String? = entries()
        .firstOrNull { it.filename == name }
        ?.inputStream
        ?.use { it.readBytes().toString(Charsets.UTF_8) }

    private fun entries() = resolver.getResources("classpath*:$location/*.json")

    companion object {
        /** 규약 고정: v1 리소스(`scenario/`, `db/migration/`)와 겹치지 않는 형제 경로. */
        const val DEFAULT_LOCATION: String = "content/v2"
    }
}
