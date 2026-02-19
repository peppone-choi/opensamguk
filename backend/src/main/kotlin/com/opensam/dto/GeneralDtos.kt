package com.opensam.dto

import java.time.Instant

data class CreateGeneralRequest(
    val name: String,
    val cityId: Long = 0,
    val nationId: Long = 0,
    val leadership: Short = 50,
    val strength: Short = 50,
    val intel: Short = 50,
    val politics: Short = 50,
    val charm: Short = 50,
    val crewType: Short = 0,
)

data class SelectNpcRequest(val generalId: Long)

data class RefreshNpcTokenRequest(
    val nonce: String,
    val keepIds: List<Long> = emptyList(),
)

data class SelectNpcWithTokenRequest(
    val nonce: String,
    val generalId: Long,
)

data class NpcCard(
    val id: Long,
    val name: String,
    val picture: String,
    val imageServer: Short,
    val leadership: Short,
    val strength: Short,
    val intel: Short,
    val politics: Short,
    val charm: Short,
    val nationId: Long,
    val nationName: String,
    val nationColor: String,
    val personality: String,
    val special: String,
)

data class NpcTokenResponse(
    val nonce: String,
    val npcs: List<NpcCard>,
    val validUntil: Instant,
    val pickMoreAfter: Instant,
    val keepCount: Int,
)

data class SelectNpcResult(
    val success: Boolean,
    val general: GeneralResponse,
)
