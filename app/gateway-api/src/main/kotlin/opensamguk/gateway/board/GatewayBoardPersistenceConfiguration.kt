package opensamguk.gateway.board

import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.context.annotation.Configuration
import org.springframework.data.jpa.repository.config.EnableJpaRepositories

@Configuration
@EntityScan(basePackageClasses = [GatewayBoardPostEntity::class, GatewayBoardCommentEntity::class])
@EnableJpaRepositories(basePackageClasses = [GatewayBoardPostRepository::class, GatewayBoardCommentRepository::class])
class GatewayBoardPersistenceConfiguration
