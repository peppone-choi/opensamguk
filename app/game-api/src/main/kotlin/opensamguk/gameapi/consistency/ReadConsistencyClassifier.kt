package opensamguk.gameapi.consistency

import org.springframework.stereotype.Component

@Component
class ReadConsistencyClassifier {
    fun classify(requestPath: String): ReadConsistencyClass {
        val path = requestPath.substringBefore('?').trimEnd('/')
        return when {
            path == "/api/command/result" || path.startsWith("/api/command/result/") ->
                ReadConsistencyClass.READ_YOUR_WRITES

            path == "/api/rankings" || path.startsWith("/api/rankings/") ||
                path == "/api/history" || path.startsWith("/api/history/") ||
                path == "/api/world-log" || path.startsWith("/api/world-log/") ||
                path == "/api/admin" || path.startsWith("/api/admin/") ->
                ReadConsistencyClass.EVENTUAL

            else -> ReadConsistencyClass.AUTHORITATIVE
        }
    }
}
