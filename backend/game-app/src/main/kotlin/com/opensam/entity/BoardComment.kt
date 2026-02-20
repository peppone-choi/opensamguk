package com.opensam.entity

import jakarta.persistence.*
import java.time.OffsetDateTime

@Entity
@Table(name = "board_comment")
class BoardComment(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0,

    @Column(name = "board_id", nullable = false)
    var boardId: Long = 0,

    @Column(name = "author_general_id", nullable = false)
    var authorGeneralId: Long = 0,

    @Column(nullable = false)
    var content: String = "",

    @Column(name = "created_at", nullable = false)
    var createdAt: OffsetDateTime = OffsetDateTime.now(),
)
