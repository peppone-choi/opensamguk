package opensamguk.logic.input

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 휘하 인물 카드의 적성 네 축(장·리·사·사자) — 다섯 능력치의 가중 평균.
 *
 * **게임 설계 수치다.** 가중값은 `data/curated/han/hwiha-aptitude-weights-v1.json` 한 곳이 정본이고
 * (2026-09-23 사용자 승인), 이 객체는 그 파일을 classpath(`hwiha/`)로 받아 쓴다 — 코드에 박지 않는다.
 *
 * 가중은 정수이고 축마다 합이 `denominator` 와 같아야 한다. 계산은 정수로 하고 0.5 는 올린다
 * (실수 0.6·0.4 로 곱하면 72.5 가 72.4999… 로 떨어져 반올림이 갈린다).
 */
object HwihaAptitude {
    enum class Stat(val key: String) {
        LEADERSHIP("leadership"), STRENGTH("strength"), INTELLIGENCE("intelligence"),
        POLITICS("politics"), CHARM("charm"),
    }

    enum class Axis(val key: String) {
        COMMAND("command"), ADMINISTRATION("administration"), STRATEGY("strategy"), ENVOY("envoy"),
    }

    data class Stats(val leadership: Int, val strength: Int, val intelligence: Int, val politics: Int, val charm: Int) {
        init { require(listOf(leadership, strength, intelligence, politics, charm).all { it >= 0 }) { "stats must be nonnegative" } }

        fun of(stat: Stat): Int = when (stat) {
            Stat.LEADERSHIP -> leadership
            Stat.STRENGTH -> strength
            Stat.INTELLIGENCE -> intelligence
            Stat.POLITICS -> politics
            Stat.CHARM -> charm
        }
    }

    data class Aptitudes(val command: Int, val administration: Int, val strategy: Int, val envoy: Int)

    class Weights internal constructor(val denominator: Int, val axes: Map<Axis, Map<Stat, Int>>) {
        init {
            require(denominator > 0) { "denominator must be positive" }
            require(axes.keys == Axis.entries.toSet()) { "every aptitude axis must be weighted" }
            axes.forEach { (axis, weights) ->
                require(weights.isNotEmpty() && weights.values.all { it > 0 }) { "weights of $axis must be positive" }
                require(weights.values.sum() == denominator) { "weights of $axis must sum to $denominator" }
            }
        }
    }

    private const val RESOURCE = "hwiha/hwiha-aptitude-weights-v1.json"

    /** 정본 가중값. classpath 에 없으면 빌드가 잘못된 것이다 — 조용히 기본값으로 가지 않는다. */
    val CANON: Weights by lazy {
        parse(checkNotNull(HwihaAptitude::class.java.classLoader.getResource(RESOURCE)) {
            "hwiha aptitude weights resource is missing: $RESOURCE"
        }.readText())
    }

    fun parse(payload: String): Weights {
        val root = Json.parseToJsonElement(payload).jsonObject
        require(root.getValue("schemaVersion").jsonPrimitive.int == 1) { "unsupported aptitude weights schemaVersion" }
        require(root.getValue("ledgerId").jsonPrimitive.content == "hwiha-aptitude-weights-v1") { "unexpected ledgerId" }
        val axesNode = root.getValue("axes").jsonObject
        require(axesNode.keys == Axis.entries.map { it.key }.toSet()) { "unexpected aptitude axes: ${axesNode.keys}" }
        val axes = Axis.entries.associateWith { axis ->
            val row: JsonObject = axesNode.getValue(axis.key).jsonObject
            row.entries.associate { (key, value) ->
                val stat = requireNotNull(Stat.entries.firstOrNull { it.key == key }) { "unknown stat '$key' in ${axis.key}" }
                stat to value.jsonPrimitive.int
            }
        }
        return Weights(root.getValue("denominator").jsonPrimitive.int, axes)
    }

    fun compute(stats: Stats, weights: Weights = CANON): Aptitudes {
        fun axis(axis: Axis): Int {
            val sum = weights.axes.getValue(axis).entries.sumOf { (stat, weight) -> stats.of(stat).toLong() * weight }
            // floor(sum / d + 1/2) — 음수가 없으므로 정수 나눗셈이 곧 내림이다.
            return ((2 * sum + weights.denominator) / (2L * weights.denominator)).toInt()
        }
        return Aptitudes(axis(Axis.COMMAND), axis(Axis.ADMINISTRATION), axis(Axis.STRATEGY), axis(Axis.ENVOY))
    }
}
