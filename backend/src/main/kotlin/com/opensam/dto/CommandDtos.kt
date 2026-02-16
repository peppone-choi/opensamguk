package com.opensam.dto

data class TurnEntry(
    val turnIdx: Short,
    val actionCode: String,
    val arg: Map<String, Any>? = null,
)

data class ReserveTurnsRequest(
    val turns: List<TurnEntry>,
)

data class ExecuteRequest(
    val actionCode: String,
    val arg: Map<String, Any>? = null,
)

data class CommandTableEntry(
    val actionCode: String,
    val name: String,
    val category: String,
    val enabled: Boolean,
    val reason: String? = null,
    val durationSeconds: Int = 300,
    val commandPointCost: Int = 1,
)

data class RepeatRequest(val count: Int)
data class PushRequest(val amount: Int)
