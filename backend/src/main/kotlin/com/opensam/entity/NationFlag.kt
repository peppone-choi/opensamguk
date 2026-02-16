package com.opensam.entity

import jakarta.persistence.*
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.io.Serializable
import java.time.OffsetDateTime

enum class NationAuxKey {
    can_국기변경, can_국호변경, did_특성초토화, can_무작위수도이전,
    can_대검병사용, can_극병사용, can_화시병사용, can_원융노병사용,
    can_산저병사용, can_상병사용, can_음귀병사용, can_무희사용, can_화륜차사용
}

data class NationFlagId(
    var nationId: Long = 0,
    var key: NationAuxKey = NationAuxKey.can_국기변경,
) : Serializable

@Entity
@Table(name = "nation_flag")
@IdClass(NationFlagId::class)
class NationFlag(
    @Id
    @Column(name = "nation_id", nullable = false)
    var nationId: Long = 0,

    @Id
    @Enumerated(EnumType.STRING)
    @Column(name = "key", nullable = false, columnDefinition = "nation_aux_key")
    var key: NationAuxKey = NationAuxKey.can_국기변경,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    var value: Any = true,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: OffsetDateTime = OffsetDateTime.now(),
)
