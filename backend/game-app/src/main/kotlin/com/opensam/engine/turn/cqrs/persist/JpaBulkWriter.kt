package com.opensam.engine.turn.cqrs.persist

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Component

@Component
class JpaBulkWriter {
    fun <T, ID : Any> saveAll(repository: JpaRepository<T, ID>, entities: Collection<T>) {
        if (entities.isEmpty()) return
        repository.saveAll(entities)
    }

    fun <T, ID : Any> deleteAllById(repository: JpaRepository<T, ID>, ids: Collection<ID>) {
        if (ids.isEmpty()) return
        repository.deleteAllById(ids)
    }
}
