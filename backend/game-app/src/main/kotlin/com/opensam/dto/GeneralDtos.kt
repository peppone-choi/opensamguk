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

data class BuildPoolGeneralRequest(
    val name: String,
    val leadership: Short = 70,
    val strength: Short = 70,
    val intel: Short = 70,
    val politics: Short = 70,
    val charm: Short = 70,
)

data class UpdatePoolGeneralRequest(
    val leadership: Short,
    val strength: Short,
    val intel: Short,
    val politics: Short,
    val charm: Short,
)

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
    val special2: String? = null,
    val dex: List<Int>? = null,
    val experience: Int? = null,
    val dedication: Int? = null,
    val expLevel: Short? = null,
    val personalityInfo: String? = null,
    val specialInfo: String? = null,
    val special2Info: String? = null,
    val keepCount: Int? = null,
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
