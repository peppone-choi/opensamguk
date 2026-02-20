package com.opensam.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.OffsetDateTime

@Entity
@Table(name = "game_history")
class GameHistory(
    @Id
    @Column(name = "server_id")
    var serverId: String = "",

    @Column(name = "winner_nation")
    var winnerNation: Long? = null,

    var date: OffsetDateTime? = null,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    var meta: MutableMap<String, Any> = mutableMapOf(),
)
