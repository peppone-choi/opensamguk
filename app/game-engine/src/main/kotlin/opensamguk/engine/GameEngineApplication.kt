package opensamguk.engine

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.boot.runApplication
import org.springframework.data.jpa.repository.config.EnableJpaRepositories

@SpringBootApplication
@EntityScan(basePackages = ["opensamguk.infra"])
@EnableJpaRepositories(basePackages = ["opensamguk.infra"])
class GameEngineApplication

fun main(args: Array<String>) {
    runApplication<GameEngineApplication>(*args)
}
