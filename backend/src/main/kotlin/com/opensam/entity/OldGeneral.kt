package com.opensam.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.OffsetDateTime

@Entity
@Table(name = "old_general")
class OldGeneral(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0,

    @Column(name = "server_id", nullable = false)
    var serverId: String = "",

    @Column(name = "general_no", nullable = false)
    var generalNo: Long = 0,

    var owner: String? = null,

    @Column(nullable = false)
    var name: String = "",

    @Column(name = "last_yearmonth", nullable = false)
    var lastYearMonth: Int = 0,

    var turnTime: OffsetDateTime? = null,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    var data: MutableMap<String, Any> = mutableMapOf(),
)
