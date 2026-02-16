package com.opensam.entity

import jakarta.persistence.*
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.OffsetDateTime

@Entity
@Table(
    name = "general_turn",
    uniqueConstraints = [UniqueConstraint(columnNames = ["general_id", "turn_idx"])]
)
class GeneralTurn(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0,

    @Column(name = "general_id", nullable = false)
    var generalId: Long = 0,

    @Column(name = "turn_idx", nullable = false)
    var turnIdx: Short = 0,

    @Column(name = "action_code", nullable = false)
    var actionCode: String = "",

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    var arg: MutableMap<String, Any> = mutableMapOf(),

    var brief: String? = null,

    @Column(name = "created_at", nullable = false)
    var createdAt: OffsetDateTime = OffsetDateTime.now(),
)
