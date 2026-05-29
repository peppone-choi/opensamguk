package opensamguk.infra.worldstate

import org.springframework.data.jpa.repository.JpaRepository

interface WorldStateRepository : JpaRepository<WorldStateEntity, Int>
