package com.opensam.entity

import jakarta.persistence.*
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes

@Entity
@Table(name = "city")
class City(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0,

    @Column(name = "world_id", nullable = false)
    var worldId: Long = 0,

    @Column(nullable = false)
    var name: String = "",

    @Column(nullable = false)
    var level: Short = 0,

    @Column(name = "nation_id", nullable = false)
    var nationId: Long = 0,

    @Column(name = "supply_state", nullable = false)
    var supplyState: Short = 1,

    @Column(name = "front_state", nullable = false)
    var frontState: Short = 0,

    @Column(nullable = false)
    var pop: Int = 0,

    @Column(name = "pop_max", nullable = false)
    var popMax: Int = 0,

    @Column(nullable = false)
    var agri: Int = 0,

    @Column(name = "agri_max", nullable = false)
    var agriMax: Int = 0,

    @Column(nullable = false)
    var comm: Int = 0,

    @Column(name = "comm_max", nullable = false)
    var commMax: Int = 0,

    @Column(nullable = false)
    var secu: Int = 0,

    @Column(name = "secu_max", nullable = false)
    var secuMax: Int = 0,

    @Column(nullable = false)
    var trust: Int = 0,

    @Column(nullable = false)
    var trade: Int = 100,

    @Column(nullable = false)
    var dead: Short = 0,

    @Column(nullable = false)
    var def: Int = 0,

    @Column(name = "def_max", nullable = false)
    var defMax: Int = 0,

    @Column(nullable = false)
    var wall: Int = 0,

    @Column(name = "wall_max", nullable = false)
    var wallMax: Int = 0,

    @Column(name = "officer_set", nullable = false)
    var officerSet: Int = 0,

    @Column(nullable = false)
    var state: Short = 0,

    @Column(nullable = false)
    var region: Short = 0,

    @Column(nullable = false)
    var term: Short = 0,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    var conflict: MutableMap<String, Any> = mutableMapOf(),

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    var meta: MutableMap<String, Any> = mutableMapOf(),
)
