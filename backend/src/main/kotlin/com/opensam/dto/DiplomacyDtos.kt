package com.opensam.dto

import com.opensam.entity.Diplomacy

data class DiplomacyDto(
    val id: Long,
    val worldId: Long,
    val srcNationId: Long,
    val destNationId: Long,
    val stateCode: String,
    val term: Int,
    val isDead: Boolean,
    val isShowing: Boolean,
) {
    companion object {
        fun from(d: Diplomacy) = DiplomacyDto(
            id = d.id,
            worldId = d.worldId,
            srcNationId = d.srcNationId,
            destNationId = d.destNationId,
            stateCode = d.stateCode,
            term = d.term.toInt(),
            isDead = d.isDead,
            isShowing = d.isShowing,
        )
    }
}
