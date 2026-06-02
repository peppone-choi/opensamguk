package opensamguk.infra.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table

/**
 * JPA entity for `game_kv` table (V7 string-namespace KV store).
 *
 * Backs `game_env`, `betting`, `inheritance_{id}` string namespaces.
 * `value` is jsonb; delete-on-null is enforced by the flush executor.
 */
@Entity
@Table(name = "game_kv")
class GameKvEntity(
    @Column(name = "\"table\"", nullable = false)
    var table: String,

    @Column(name = "namespace", nullable = false)
    var namespace: String,

    @Column(name = "key", nullable = false)
    var key: String,

    @Column(name = "value", nullable = false, columnDefinition = "jsonb")
    var value: String,

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Int? = null,
)
