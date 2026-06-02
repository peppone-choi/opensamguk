package opensamguk.gameapi

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.boot.runApplication
import org.springframework.data.jpa.repository.config.EnableJpaRepositories

@SpringBootApplication
@EntityScan(basePackages = ["opensamguk.infra", "opensamguk.gameapi.read", "opensamguk.gameapi.owner"])
@EnableJpaRepositories(basePackages = ["opensamguk.infra", "opensamguk.gameapi.read", "opensamguk.gameapi.owner"])
class GameApiApplication

fun main(args: Array<String>) {
    runApplication<GameApiApplication>(*args)
}
