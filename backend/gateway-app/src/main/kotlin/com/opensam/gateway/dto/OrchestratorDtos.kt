package com.opensam.gateway.dto

data class AttachWorldProcessRequest(
    val commitSha: String,
    val gameVersion: String = "dev",
    val jarPath: String? = null,
    val port: Int? = null,
    val javaCommand: String = "java",
)

data class ActivateWorldRequest(
    val commitSha: String? = null,
    val gameVersion: String? = null,
    val jarPath: String? = null,
    val port: Int? = null,
    val javaCommand: String? = null,
)

data class GameInstanceStatus(
    val commitSha: String,
    val gameVersion: String,
    val jarPath: String,
    val port: Int,
    val worldIds: List<Long>,
    val alive: Boolean,
    val pid: Long,
)
