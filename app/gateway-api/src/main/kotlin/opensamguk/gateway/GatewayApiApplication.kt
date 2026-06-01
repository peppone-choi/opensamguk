package opensamguk.gateway

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.boot.runApplication
import org.springframework.data.jpa.repository.config.EnableJpaRepositories

@SpringBootApplication
@EnableJpaRepositories(basePackages = ["opensamguk.infra"])
@EntityScan(basePackages = ["opensamguk.infra"])
class GatewayApiApplication

fun main(args: Array<String>) {
    runApplication<GatewayApiApplication>(*args)
}
