package com.opensam.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes

@Entity
@Table(name = "hall_of_fame")
class HallOfFame(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0,

    @Column(name = "server_id", nullable = false)
    var serverId: String = "",

    @Column(nullable = false)
    var season: Int = 1,

    @Column(nullable = false)
    var scenario: Int = 0,

    @Column(name = "general_no", nullable = false)
    var generalNo: Long = 0,

    @Column(nullable = false)
    var type: String = "",

    @Column(nullable = false)
    var value: Double = 0.0,

    var owner: String? = null,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    var aux: MutableMap<String, Any> = mutableMapOf(),
)
