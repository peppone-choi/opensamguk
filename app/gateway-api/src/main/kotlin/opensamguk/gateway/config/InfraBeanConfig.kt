package opensamguk.gateway.config

import opensamguk.infra.read.EmailHasher
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * gateway-api가 소비하는 :infra 평범한(component-scan 밖) 협력자 빈을 명시 배선한다.
 *
 * :infra는 gateway-api 컴포넌트 스캔(`opensamguk.gateway`) 범위 밖이라 `@Component`가 자동 등록되지 않는다.
 * 리포지토리는 `@EnableJpaRepositories(basePackages=["opensamguk.infra"])`로 잡히지만,
 * 일반 협력자([EmailHasher])는 여기서 `@Bean`으로 제공한다.
 */
@Configuration
class InfraBeanConfig {

    /**
     * 영구차단용 이메일 해시기 — legacy 전역 salt(`RootDB::$globalSalt`) 대응.
     * `GLOBAL_SALT` 미설정 시 legacy 기본값 `'goldensalt'`로 폴백(운영에서는 반드시 설정).
     */
    @Bean
    fun emailHasher(@Value("\${GLOBAL_SALT:goldensalt}") globalSalt: String): EmailHasher =
        EmailHasher(globalSalt)
}
