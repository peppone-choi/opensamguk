package opensamguk.boardapi

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration
import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration
import org.springframework.boot.runApplication
import org.springframework.data.jpa.repository.config.EnableJpaRepositories

@SpringBootApplication(
    exclude = [
        RedisAutoConfiguration::class,
        RedisRepositoriesAutoConfiguration::class,
        UserDetailsServiceAutoConfiguration::class,
    ],
)
@EnableJpaRepositories(basePackages = ["opensamguk.infra", "opensamguk.boardapi.board"])
@EntityScan(basePackages = ["opensamguk.infra", "opensamguk.boardapi.board"])
class BoardApiApplication

fun main(args: Array<String>) {
    runApplication<BoardApiApplication>(*args)
}
