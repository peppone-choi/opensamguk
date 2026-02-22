package com.opensam.gateway.dto

data class AttachWorldProcessRequest(
    val commitSha: String,
    val gameVersion: String = "dev",
    val jarPath: String? = null,
    val port: Int? = null,
    val javaCommand: String = "java",
    val imageTag: String? = null,
)

data class ActivateWorldRequest(
    val commitSha: String? = null,
    val gameVersion: String? = null,
    val jarPath: String? = null,
    val port: Int? = null,
    val javaCommand: String? = null,
    val imageTag: String? = null,
)

data class GameInstanceStatus(
    val commitSha: String,
    val gameVersion: String,
    val jarPath: String,
    val port: Int,
    val worldIds: List<Long>,
    val alive: Boolean,
    val pid: Long,
    val baseUrl: String = "",
    val containerId: String? = null,
    val imageTag: String? = null,
)

data class DeployGameVersionRequest(
    val gameVersion: String,
    val imageTag: String? = null,
    val commitSha: String? = null,
)
