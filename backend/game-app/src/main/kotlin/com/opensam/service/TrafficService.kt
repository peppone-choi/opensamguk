package com.opensam.service

import com.opensam.repository.GeneralAccessLogRepository
import com.opensam.repository.GeneralRepository
import com.opensam.repository.TrafficSnapshotRepository
import org.springframework.stereotype.Service

@Service
class TrafficService(
    private val trafficSnapshotRepo: TrafficSnapshotRepository,
    private val accessLogRepo: GeneralAccessLogRepository,
    private val generalRepo: GeneralRepository,
) {
    data class TrafficResponse(
        val recentTraffic: List<TrafficEntry>,
        val maxRefresh: Int,
        val maxOnline: Int,
        val topRefreshers: List<TopRefresher>,
        val totalRefresh: Long,
        val totalRefreshScoreTotal: Long,
    )

    data class TrafficEntry(
        val year: Int,
        val month: Int,
        val refresh: Int,
        val online: Int,
        val date: String,
    )

    data class TopRefresher(
        val name: String,
        val refresh: Int,
        val refreshScoreTotal: Int,
    )

    fun getTraffic(worldId: Long): TrafficResponse {
        val snapshots = trafficSnapshotRepo.findTop30ByWorldIdOrderByRecordedAtDesc(worldId).reversed()
        val maxRefresh = trafficSnapshotRepo.findMaxRefresh(worldId).coerceAtLeast(1)
        val maxOnline = trafficSnapshotRepo.findMaxOnline(worldId).coerceAtLeast(1)

        val recentTraffic = snapshots.map { s ->
            TrafficEntry(
                year = s.year.toInt(),
                month = s.month.toInt(),
                refresh = s.refresh,
                online = s.online,
                date = s.recordedAt.toString(),
            )
        }

        // Top refreshers from general_access_log
        val topLogs = accessLogRepo.findTopRefreshersByWorldId(worldId).take(5)
        val generalIds = topLogs.map { it.generalId }.toSet()
        val generalNames = generalRepo.findAllById(generalIds).associate { it.id to it.name }

        val topRefreshers = topLogs.map { log ->
            TopRefresher(
                name = generalNames[log.generalId] ?: "#${log.generalId}",
                refresh = log.refresh,
                refreshScoreTotal = log.refreshScoreTotal,
            )
        }

        val totalRefresh = accessLogRepo.sumRefreshByWorldId(worldId)
        val totalRefreshScoreTotal = accessLogRepo.sumRefreshScoreTotalByWorldId(worldId)

        return TrafficResponse(
            recentTraffic = recentTraffic,
            maxRefresh = maxRefresh,
            maxOnline = maxOnline,
            topRefreshers = topRefreshers,
            totalRefresh = totalRefresh,
            totalRefreshScoreTotal = totalRefreshScoreTotal,
        )
    }
}
