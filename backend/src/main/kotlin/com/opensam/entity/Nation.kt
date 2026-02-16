package com.opensam.entity

import jakarta.persistence.*
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.OffsetDateTime

@Entity
@Table(name = "nation")
class Nation(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0,

    @Column(name = "world_id", nullable = false)
    var worldId: Long = 0,

    @Column(nullable = false)
    var name: String = "",

    @Column(nullable = false)
    var color: String = "",

    @Column(name = "capital_city_id")
    var capitalCityId: Long? = null,

    @Column(nullable = false)
    var gold: Int = 0,

    @Column(nullable = false)
    var rice: Int = 0,

    @Column(nullable = false)
    var bill: Short = 0,

    @Column(nullable = false)
    var rate: Short = 0,

    @Column(name = "rate_tmp", nullable = false)
    var rateTmp: Short = 0,

    @Column(name = "secret_limit", nullable = false)
    var secretLimit: Short = 3,

    @Column(name = "chief_general_id", nullable = false)
    var chiefGeneralId: Long = 0,

    @Column(name = "scout_level", nullable = false)
    var scoutLevel: Short = 0,

    @Column(name = "war_state", nullable = false)
    var warState: Short = 0,

    @Column(name = "strategic_cmd_limit", nullable = false)
    var strategicCmdLimit: Short = 36,

    @Column(name = "surrender_limit", nullable = false)
    var surrenderLimit: Short = 72,

    @Column(nullable = false)
    var tech: Float = 0f,

    @Column(nullable = false)
    var power: Int = 0,

    @Column(nullable = false)
    var level: Short = 0,

    @Column(name = "type_code", nullable = false)
    var typeCode: String = "che_중립",

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    var spy: MutableMap<String, Any> = mutableMapOf(),

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    var meta: MutableMap<String, Any> = mutableMapOf(),

    @Column(name = "created_at", nullable = false)
    var createdAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: OffsetDateTime = OffsetDateTime.now(),
)
