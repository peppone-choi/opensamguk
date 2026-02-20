package com.opensam.dto

data class RealtimeExecuteRequest(
    val generalId: Long,
    val actionCode: String,
    val arg: Map<String, Any>? = null,
)
