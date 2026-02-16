package com.opensam.dto

import com.opensam.entity.Troop

data class CreateTroopRequest(val worldId: Long, val leaderGeneralId: Long, val nationId: Long, val name: String)

data class TroopActionRequest(val generalId: Long)

data class RenameTroopRequest(val name: String)

data class TroopMemberInfo(val id: Long, val name: String, val picture: String)

data class TroopWithMembers(val troop: Troop, val members: List<TroopMemberInfo>)
