package com.opensam.gateway.orchestrator

import com.opensam.gateway.dto.AttachWorldProcessRequest
import com.opensam.gateway.dto.GameInstanceStatus
import com.opensam.gateway.service.WorldRouteRegistry
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

@Service
class GameProcessOrchestrator(
    private val worldRouteRegistry: WorldRouteRegistry,
    @Value("\${gateway.orchestrator.health-timeout-ms:30000}")
    private val healthTimeoutMs: Long,
) {
    private val log = LoggerFactory.getLogger(GameProcessOrchestrator::class.java)
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(2))
        .build()

    private data class ManagedGameInstance(
        val commitSha: String,
        val gameVersion: String,
        val jarPath: String,
        val port: Int,
        val process: Process,
        val worldIds: MutableSet<Long>,
    )

    private val lock = ReentrantLock()
    private val instances = ConcurrentHashMap<String, ManagedGameInstance>()

    fun attachWorld(worldId: Long, request: AttachWorldProcessRequest): GameInstanceStatus {
        val commitSha = request.commitSha.trim()
        require(commitSha.isNotEmpty()) { "commitSha is required" }

        return lock.withLock {
            cleanupDeadInstances()

            val currentlyAssigned = instances.values.firstOrNull { worldId in it.worldIds }
            if (currentlyAssigned != null && currentlyAssigned.commitSha != commitSha) {
                currentlyAssigned.worldIds.remove(worldId)
                if (currentlyAssigned.worldIds.isEmpty()) {
                    stopInstance(currentlyAssigned)
                    instances.remove(currentlyAssigned.commitSha)
                }
            }

            val managed = getOrStartInstance(commitSha, request)
            managed.worldIds.add(worldId)
            worldRouteRegistry.attach(worldId, baseUrl(managed.port))
            toStatus(managed)
        }
    }

    fun ensureVersion(request: AttachWorldProcessRequest): GameInstanceStatus {
        val commitSha = request.commitSha.trim()
        require(commitSha.isNotEmpty()) { "commitSha is required" }

        return lock.withLock {
            cleanupDeadInstances()
            val managed = getOrStartInstance(commitSha, request)
            toStatus(managed)
        }
    }

    fun detachWorld(worldId: Long): Boolean {
        return lock.withLock {
            cleanupDeadInstances()

            val entry = instances.entries.firstOrNull { (_, instance) -> worldId in instance.worldIds }
                ?: return false

            val managed = entry.value
            managed.worldIds.remove(worldId)
            worldRouteRegistry.detach(worldId)

            if (managed.worldIds.isEmpty()) {
                stopInstance(managed)
                instances.remove(managed.commitSha)
            }

            true
        }
    }

    fun statuses(): List<GameInstanceStatus> {
        return lock.withLock {
            cleanupDeadInstances()

            instances.values
                .map { toStatus(it) }
                .sortedBy { it.commitSha }
        }
    }

    @PreDestroy
    fun shutdownAll() {
        lock.withLock {
            instances.values.forEach { stopInstance(it) }
            instances.clear()
        }
    }

    private fun getOrStartInstance(commitSha: String, request: AttachWorldProcessRequest): ManagedGameInstance {
        val existing = instances[commitSha]
        if (existing == null) {
            return startNewInstance(request)
        }

        if (existing.process.isAlive) {
            return existing
        }

        val retainedWorldIds = existing.worldIds.toSet()
        instances.remove(commitSha)

        log.warn("Restarting dead game instance for commitSha={}", commitSha)
        val restarted = startNewInstance(request)
        restarted.worldIds.addAll(retainedWorldIds)
        retainedWorldIds.forEach { worldRouteRegistry.attach(it, baseUrl(restarted.port)) }
        return restarted
    }

    private fun startNewInstance(request: AttachWorldProcessRequest): ManagedGameInstance {
        val commitSha = request.commitSha.trim()
        val gameVersion = request.gameVersion.trim().ifEmpty { "dev" }
        val jarPath = resolveJarPath(commitSha, request.jarPath)
        require(Files.exists(jarPath)) { "JAR not found: $jarPath" }

        val port = request.port ?: allocatePort()
        val logsDir = Paths.get("logs")
        Files.createDirectories(logsDir)
        val logFile = logsDir.resolve("game-$commitSha.log").toFile()

        val command = listOf(
            request.javaCommand,
            "-jar",
            jarPath.toString(),
            "--server.port=$port",
            "--game.commit-sha=$commitSha",
            "--game.version=$gameVersion",
        )

        val process = ProcessBuilder(command)
            .directory(File(System.getProperty("user.dir")))
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
            .start()

        waitForHealth(port, process)

        val managed = ManagedGameInstance(
            commitSha = commitSha,
            gameVersion = gameVersion,
            jarPath = jarPath.toString(),
            port = port,
            process = process,
            worldIds = mutableSetOf(),
        )
        instances[commitSha] = managed
        return managed
    }

    private fun resolveJarPath(commitSha: String, explicitJarPath: String?): Path {
        if (!explicitJarPath.isNullOrBlank()) {
            return Paths.get(explicitJarPath).toAbsolutePath().normalize()
        }
        return Paths.get("artifacts", "game-app-$commitSha.jar").toAbsolutePath().normalize()
    }

    private fun allocatePort(): Int {
        val usedPorts = instances.values.map { it.port }.toSet()
        for (candidate in 9001..9999) {
            if (candidate !in usedPorts) return candidate
        }
        throw IllegalStateException("No available port in range 9001..9999")
    }

    private fun cleanupDeadInstances() {
        val deadInstances = instances.values.filter { !it.process.isAlive }
        deadInstances.forEach { instance ->
            instance.worldIds.forEach { worldRouteRegistry.detach(it) }
            instances.remove(instance.commitSha)
            log.warn("Removed dead game instance commitSha={}", instance.commitSha)
        }
    }

    private fun stopInstance(instance: ManagedGameInstance) {
        if (!instance.process.isAlive) return
        instance.process.destroy()
        val exited = instance.process.waitFor(5, TimeUnit.SECONDS)
        if (!exited && instance.process.isAlive) {
            instance.process.destroyForcibly()
            instance.process.waitFor(5, TimeUnit.SECONDS)
        }
    }

    private fun toStatus(instance: ManagedGameInstance): GameInstanceStatus {
        return GameInstanceStatus(
            commitSha = instance.commitSha,
            gameVersion = instance.gameVersion,
            jarPath = instance.jarPath,
            port = instance.port,
            worldIds = instance.worldIds.toList().sorted(),
            alive = instance.process.isAlive,
            pid = instance.process.pid(),
        )
    }

    private fun baseUrl(port: Int): String {
        return "http://127.0.0.1:$port"
    }

    private fun waitForHealth(port: Int, process: Process) {
        val deadline = System.currentTimeMillis() + healthTimeoutMs
        val uri = URI.create("${baseUrl(port)}/internal/health")
        var lastErrorMessage: String? = null

        while (System.currentTimeMillis() < deadline) {
            if (!process.isAlive) {
                throw IllegalStateException("Game process exited before health check on port=$port")
            }

            try {
                val request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(2))
                    .GET()
                    .build()
                val response = httpClient.send(request, HttpResponse.BodyHandlers.discarding())
                if (response.statusCode() in 200..299) {
                    return
                }
                lastErrorMessage = "health status=${response.statusCode()}"
            } catch (_: Exception) {
                lastErrorMessage = "health endpoint not ready"
            }

            Thread.sleep(500)
        }

        process.destroyForcibly()
        throw IllegalStateException(
            "Game process health check timed out on port=$port (${lastErrorMessage ?: "unknown"})",
        )
    }
}
