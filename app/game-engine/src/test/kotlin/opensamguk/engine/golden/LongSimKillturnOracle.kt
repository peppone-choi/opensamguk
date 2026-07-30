package opensamguk.engine.golden

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import opensamguk.engine.turn.InMemoryTurnWorld

internal class LongSimKillturnOracle private constructor(
    transitions: List<Transition>,
) {
    private val pendingByGeneral = transitions
        .groupByTo(LinkedHashMap()) { it.generalId }
        .mapValuesTo(LinkedHashMap()) { (_, values) -> ArrayDeque(values) }
    private val lastInternal = LinkedHashMap<Int, Int>()

    fun observeWorld(world: InMemoryTurnWorld) {
        for (general in world.listGenerals().sortedBy { it.id }) {
            val current = (general.meta["killturn"] as? Number)?.toInt() ?: continue
            val prior = lastInternal[general.id]
            if (prior == null) {
                val first = pendingByGeneral[general.id]?.firstOrNull()
                if (first != null && first.from == null) {
                    applyTransition(world, general.id, current, first)
                    pendingByGeneral.getValue(general.id).removeFirst()
                }
                lastInternal[general.id] = current
                continue
            }
            if (current == prior || current == prior - 1 || (prior == 0 && current == 0)) {
                lastInternal[general.id] = current
                continue
            }

            val transition = pendingByGeneral[general.id]?.removeFirstOrNull()
                ?: error(
                    "unknown absolute killturn transition for general ${general.id}: " +
                        "$prior -> $current",
                )
            val offset = offsetOf(world, general.id)
            val projectedPrior = prior - offset
            check(transition.from == projectedPrior) {
                "killturn transition source mismatch for general ${general.id}: " +
                    "manifest=${transition.from}, live=$projectedPrior"
            }
            applyTransition(world, general.id, current, transition)
            lastInternal[general.id] = current
        }
    }

    fun assertComplete() {
        val remaining = pendingByGeneral.values.sumOf { it.size }
        check(remaining == 0) { "$remaining killturn manifest transition(s) were not observed" }
    }

    private fun applyTransition(
        world: InMemoryTurnWorld,
        generalId: Int,
        current: Int,
        transition: Transition,
    ) {
        val expectedInternal = when (transition.family) {
            Family.MONTH_DERIVED -> transition.to * PHASES_PER_MONTH
            Family.EXECUTION_CONSTANT -> transition.to
        }
        check(current == expectedInternal || current == expectedInternal - 1) {
            "killturn transition target mismatch for general $generalId: " +
                "provenance=${transition.provenance}, expected=$expectedInternal, live=$current"
        }
        val offset = when (transition.family) {
            Family.MONTH_DERIVED -> transition.to * (PHASES_PER_MONTH - 1)
            Family.EXECUTION_CONSTANT -> 0
        }
        val general = checkNotNull(world.getGeneralById(generalId))
        world.applyGeneralDirtyFree(
            general.copy(meta = general.meta + (OFFSET_META_KEY to offset)),
        )
    }

    private fun offsetOf(world: InMemoryTurnWorld, generalId: Int): Int =
        (world.getGeneralById(generalId)?.meta?.get(OFFSET_META_KEY) as? Number)?.toInt() ?: 0

    private data class Transition(
        val generalId: Int,
        val from: Int?,
        val to: Int,
        val provenance: String,
        val family: Family,
    )

    private enum class Family {
        MONTH_DERIVED,
        EXECUTION_CONSTANT,
    }

    companion object {
        const val OFFSET_META_KEY: String = "__longsim_php_killturn_offset"
        private const val PHASES_PER_MONTH = 3

        fun fromManifest(manifest: JsonObject): LongSimKillturnOracle {
            require(manifest["schemaVersion"]?.jsonPrimitive?.intOrNull in 2..4) {
                "long-sim killturn oracle requires manifest schemaVersion=2, 3, or 4"
            }
            val transitions = manifest["killturnTransitions"]!!.jsonArray.map { raw ->
                val entry = raw.jsonObject
                val provenance = entry["provenance"]!!.jsonPrimitive.content
                val family = when (entry["family"]!!.jsonPrimitive.content) {
                    "month-derived" -> {
                        require(provenance in MONTH_DERIVED_PROVENANCE) {
                            "unknown month-derived killturn provenance: $provenance"
                        }
                        Family.MONTH_DERIVED
                    }
                    "execution-constant" -> {
                        require(provenance in EXECUTION_CONSTANT_PROVENANCE) {
                            "unknown execution-constant killturn provenance: $provenance"
                        }
                        Family.EXECUTION_CONSTANT
                    }
                    else -> throw IllegalArgumentException(
                        "unknown killturn transition family for $provenance: " +
                            entry["family"]!!.jsonPrimitive.content,
                    )
                }
                Transition(
                    generalId = entry["generalId"]!!.jsonPrimitive.int,
                    from = entry["from"]?.jsonPrimitive?.intOrNull,
                    to = entry["to"]!!.jsonPrimitive.int,
                    provenance = provenance,
                    family = family,
                )
            }
            return LongSimKillturnOracle(transitions)
        }

        private val MONTH_DERIVED_PROVENANCE = setOf(
            "GeneralBuilder",
            "possession-release",
        )
        private val EXECUTION_CONSTANT_PROVENANCE = setOf(
            "human-reset",
            "ClaimNpc",
            "ai-gather-reroll",
            "ai-npc-death",
        )
    }
}
