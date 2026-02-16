package com.opensam.engine.modifier

import com.opensam.entity.General
import com.opensam.entity.Nation
import org.springframework.stereotype.Service

@Service
class ModifierService {

    fun getModifiers(general: General, nation: Nation? = null): List<ActionModifier> {
        val modifiers = mutableListOf<ActionModifier>()

        // 1. Nation type
        nation?.typeCode?.let { NationTypeModifiers.get(it)?.let { m -> modifiers.add(m) } }

        // 2. Personality
        if (general.personalCode != "None") {
            PersonalityModifiers.get(general.personalCode)?.let { modifiers.add(it) }
        }

        // 3. War special (specialCode)
        if (general.specialCode != "None") {
            SpecialModifiers.get(general.specialCode)?.let { modifiers.add(it) }
        }

        // 4. Domestic special (special2Code)
        if (general.special2Code != "None") {
            SpecialModifiers.get(general.special2Code)?.let { modifiers.add(it) }
        }

        // 5. Items
        if (general.weaponCode != "None") {
            ItemModifiers.get(general.weaponCode)?.let { modifiers.add(it) }
        }
        if (general.bookCode != "None") {
            ItemModifiers.get(general.bookCode)?.let { modifiers.add(it) }
        }
        if (general.horseCode != "None") {
            ItemModifiers.get(general.horseCode)?.let { modifiers.add(it) }
        }
        if (general.itemCode != "None") {
            ItemModifiers.get(general.itemCode)?.let { modifiers.add(it) }
        }

        return modifiers
    }

    fun applyStatModifiers(modifiers: List<ActionModifier>, baseStat: StatContext): StatContext {
        var stat = baseStat
        for (mod in modifiers) {
            stat = mod.onCalcStat(stat)
        }
        return stat
    }

    fun applyDomesticModifiers(modifiers: List<ActionModifier>, baseCtx: DomesticContext): DomesticContext {
        var ctx = baseCtx
        for (mod in modifiers) {
            ctx = mod.onCalcDomestic(ctx)
        }
        return ctx
    }

    fun applyStrategicModifiers(modifiers: List<ActionModifier>, baseCtx: StrategicContext): StrategicContext {
        var ctx = baseCtx
        for (mod in modifiers) {
            ctx = mod.onCalcStrategic(ctx)
        }
        return ctx
    }

    fun applyIncomeModifiers(modifiers: List<ActionModifier>, baseCtx: IncomeContext): IncomeContext {
        var ctx = baseCtx
        for (mod in modifiers) {
            ctx = mod.onCalcIncome(ctx)
        }
        return ctx
    }

    fun getTotalWarPowerMultiplier(modifiers: List<ActionModifier>): Double {
        return modifiers.fold(1.0) { acc, mod -> acc * mod.getWarPowerMultiplier() }
    }
}
