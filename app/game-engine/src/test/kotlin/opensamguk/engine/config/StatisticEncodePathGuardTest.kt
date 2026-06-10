package opensamguk.engine.config

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertTrue

/**
 * 리버트 가드 — DaemonLoopConfig가 kotlinx `Json.encodeToString`으로 되돌아가는 것을 막는다.
 *
 * checkStatistic aux는 `Map<String, Any?>`라 kotlinx 경로는 런타임에
 * "Serializer for class 'Any' is not found"를 던지고, 연경계(새 달 == 1월) tick을 영구
 * 동결시킨다(2026-06-09 prod s1/spep 회귀). 컴파일은 통과하므로 소스 스캔으로만 잡을 수 있다.
 * statistic 채널 인코딩은 [StatisticInsertColumns] → MetaJson 경로만 허용.
 */
class StatisticEncodePathGuardTest {

    private fun daemonLoopConfigSource(): File = listOf(
        File("src/main/kotlin/opensamguk/engine/config/DaemonLoopConfig.kt"),
        File("app/game-engine/src/main/kotlin/opensamguk/engine/config/DaemonLoopConfig.kt"),
    ).firstOrNull { it.isFile } ?: error("DaemonLoopConfig.kt source not found from ${File(".").absolutePath}")

    @Test
    fun `DaemonLoopConfig는 kotlinx Json encodeToString을 쓰지 않는다`() {
        val source = daemonLoopConfigSource().readText()
        // 주석 줄은 제외하고 코드 줄에서만 검사한다 (회귀 설명 주석에 토큰이 등장).
        val codeLines = source.lineSequence().filterNot { it.trimStart().startsWith("//") }
        assertTrue(
            codeLines.none { it.contains("encodeToString") || it.contains("kotlinx.serialization.json.Json") },
            "DaemonLoopConfig에 kotlinx Json 인코딩 경로가 다시 들어왔다 — statistic aux는 " +
                "Map<String, Any?>여서 런타임 SerializationException으로 연경계 tick이 영구 동결된다. " +
                "StatisticInsertColumns(MetaJson) 경로를 사용할 것.",
        )
    }
}
