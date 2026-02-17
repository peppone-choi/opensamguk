package com.opensam.entity

import jakarta.persistence.*
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.OffsetDateTime

@Entity
@Table(name = "world_state")
class WorldState(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Short = 0,

    @Column(nullable = false)
    var name: String = "",

    @Column(name = "scenario_code", nullable = false)
    var scenarioCode: String = "",

    @Column(name = "current_year", nullable = false)
    var currentYear: Short = 0,

    @Column(name = "current_month", nullable = false)
    var currentMonth: Short = 0,

    @Column(name = "tick_seconds", nullable = false)
    var tickSeconds: Int = 0,

    @Column(name = "realtime_mode", nullable = false)
    var realtimeMode: Boolean = false,

    @Column(name = "command_point_regen_rate", nullable = false)
    var commandPointRegenRate: Int = 1,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    var config: MutableMap<String, Any> = mutableMapOf(),

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    var meta: MutableMap<String, Any> = mutableMapOf(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: OffsetDateTime = OffsetDateTime.now(),
)
