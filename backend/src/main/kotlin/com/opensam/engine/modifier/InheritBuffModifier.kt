package com.opensam.engine.modifier

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.opensam.entity.General
import kotlin.math.floor

class InheritBuffModifier private constructor(
    private val buff: Map<String, Any?>,
) : ActionModifier {
    override val code: String = "inherit_buff"
    override val name: String = "계승버프"

    private val domesticTargets = setOf("상업", "농업", "치안", "성벽", "수비", "민심", "인구", "기술")

    override fun onCalcDomestic(ctx: DomesticContext): DomesticContext {
        if (ctx.actionCode !in domesticTargets) {
            return ctx
        }

        val successLevel = readBuffLevel("success")
        val failLevel = readBuffLevel("fail")

        return ctx.copy(
            successMultiplier = ctx.successMultiplier + successLevel * 0.01,
            failMultiplier = ctx.failMultiplier - failLevel * 0.01,
        )
    }

    override fun onCalcStat(stat: StatContext): StatContext {
        return stat.copy(
            dodgeChance = stat.dodgeChance + readBuffLevel("warAvoidRatio") * 0.01,
            criticalChance = stat.criticalChance + readBuffLevel("warCriticalRatio") * 0.01,
            magicTrialProb = stat.magicTrialProb + readBuffLevel("warMagicTrialProb") * 0.01,
        )
    }

    override fun onCalcOpposeStat(stat: StatContext): StatContext {
        return stat.copy(
            dodgeChance = stat.dodgeChance - readBuffLevel("warAvoidRatioOppose") * 0.01,
            criticalChance = stat.criticalChance - readBuffLevel("warCriticalRatioOppose") * 0.01,
            magicTrialProb = stat.magicTrialProb - readBuffLevel("warMagicTrialProbOppose") * 0.01,
        )
    }

    private fun readBuffLevel(key: String): Int {
        val raw = buff[key]
        val value = when (raw) {
            is Number -> raw.toDouble()
            is String -> raw.toDoubleOrNull() ?: 0.0
            else -> 0.0
        }
        if (!value.isFinite()) {
            return 0
        }
        return floor(value).toInt().coerceIn(0, 5)
    }

    companion object {
        private val mapper = ObjectMapper().registerModule(KotlinModule.Builder().build())

        fun fromGeneral(general: General): InheritBuffModifier {
            val fromMeta = parseInheritBuff(general.meta["inheritBuff"])
            if (fromMeta.isNotEmpty()) {
                return InheritBuffModifier(fromMeta)
            }

            val fromLegacyMeta = parseInheritBuff(general.meta["inheritBuffs"])
            if (fromLegacyMeta.isNotEmpty()) {
                return InheritBuffModifier(fromLegacyMeta)
            }

            return InheritBuffModifier(emptyMap())
        }

        private fun parseInheritBuff(value: Any?): Map<String, Any?> {
            return when (value) {
                is String -> {
                    try {
                        mapper.readValue(value, Map::class.java) as? Map<String, Any?> ?: emptyMap()
                    } catch (_: Exception) {
                        emptyMap()
                    }
                }
                is Map<*, *> -> {
                    value.entries
                        .filter { it.key is String }
                        .associate { it.key as String to it.value }
                }
                else -> emptyMap()
            }
        }
    }
}
