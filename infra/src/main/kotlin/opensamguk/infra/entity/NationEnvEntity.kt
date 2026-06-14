package opensamguk.infra.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table

/**
 * JPA READ 엔티티 — V3 `nation_env` 테이블(int-namespace KV 스토어, namespace = nation id).
 *
 * 데몬이 nationNotice/scout_msg/available_war_setting_cnt 등을 `ChangeRecorder.recordNationEnvKv` →
 * `JdbcFlushExecutor.nationEnvKvWrite`로 쓰는 그 테이블(V3__p2_nation_env.sql). game-api는 READ 전용 —
 * KV write는 데몬 flush가 유일 주체(one-daemon-write-rule). `value`는 jsonb이며 raw JSON String으로
 * 매핑해 호출부가 ObjectMapper로 디코드한다(int / "string" / {obj} 패밀리 혼재).
 *
 * 형제 엔티티 [GameKvEntity]는 string-namespace V7 `game_kv` 테이블을 매핑한다(다른 테이블 — 혼동 주의:
 * nationNotice류는 game_kv가 아니라 nation_env에 있다).
 */
@Entity
@Table(name = "nation_env")
class NationEnvEntity(
    @Column(name = "namespace", nullable = false)
    var namespace: Int,

    @Column(name = "key", nullable = false)
    var key: String,

    @Column(name = "value", nullable = false, columnDefinition = "jsonb")
    var value: String,

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Int? = null,
)
