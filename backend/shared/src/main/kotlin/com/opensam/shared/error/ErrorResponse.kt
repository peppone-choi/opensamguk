package com.opensam.shared.error

data class ErrorResponse(
    val code: String,
    val message: String,
    val details: Map<String, Any?> = emptyMap(),
)
