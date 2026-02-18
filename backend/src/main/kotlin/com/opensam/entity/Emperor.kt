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
@Table(name = "emperor")
class Emperor(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0,

    @Column(name = "server_id", nullable = false)
    var serverId: String = "",

    @Column(nullable = false)
    var phase: String = "",

    @Column(name = "nation_count", nullable = false)
    var nationCount: String = "",

    @Column(name = "nation_name", nullable = false)
    var nationName: String = "",

    @Column(name = "nation_hist", nullable = false)
    var nationHist: String = "",

    @Column(name = "gen_count", nullable = false)
    var genCount: String = "",

    @Column(name = "personal_hist", nullable = false)
    var personalHist: String = "",

    @Column(name = "special_hist", nullable = false)
    var specialHist: String = "",

    @Column(nullable = false)
    var name: String = "",

    @Column(nullable = false)
    var type: String = "",

    @Column(nullable = false)
    var color: String = "",

    @Column(nullable = false)
    var year: Short = 0,

    @Column(nullable = false)
    var month: Short = 0,

    @Column(nullable = false)
    var power: Int = 0,

    @Column(nullable = false)
    var gennum: Int = 0,

    @Column(nullable = false)
    var citynum: Int = 0,

    @Column(nullable = false)
    var pop: String = "",

    @Column(nullable = false)
    var poprate: String = "",

    @Column(nullable = false)
    var gold: Int = 0,

    @Column(nullable = false)
    var rice: Int = 0,

    @Column(nullable = false)
    var l12name: String = "",

    @Column(nullable = false)
    var l12pic: String = "",

    @Column(nullable = false)
    var l11name: String = "",

    @Column(nullable = false)
    var l11pic: String = "",

    @Column(nullable = false)
    var l10name: String = "",

    @Column(nullable = false)
    var l10pic: String = "",

    @Column(nullable = false)
    var l9name: String = "",

    @Column(nullable = false)
    var l9pic: String = "",

    @Column(nullable = false)
    var l8name: String = "",

    @Column(nullable = false)
    var l8pic: String = "",

    @Column(nullable = false)
    var l7name: String = "",

    @Column(nullable = false)
    var l7pic: String = "",

    @Column(nullable = false)
    var l6name: String = "",

    @Column(nullable = false)
    var l6pic: String = "",

    @Column(nullable = false)
    var l5name: String = "",

    @Column(nullable = false)
    var l5pic: String = "",

    @Column(nullable = false)
    var tiger: String = "",

    @Column(nullable = false)
    var eagle: String = "",

    @Column(nullable = false)
    var gen: String = "",

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    var history: List<String> = emptyList(),

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    var aux: MutableMap<String, Any> = mutableMapOf(),
)
