package com.opensam.engine.modifier

import kotlin.math.sqrt
import kotlin.random.Random

/**
 * NPC 특기 선택 알고리즘.
 * core2026 triggers/special/selector.ts 에서 포팅.
 *
 * 스탯 기반 조건 + 숙련도 기반 조건을 계산한 뒤,
 * 적격 특기 풀에서 가중치 기반 무작위 선택.
 */
object TraitSelector {

    /**
     * 기초 스탯 기반 선택 조건 계산.
     *
     * chiefMin 미만 스탯이 있으면 NOT 플래그 설정,
     * 없으면 가장 높은 스탯의 플래그 설정.
     */
    fun calcCondGeneric(leadership: Int, strength: Int, intel: Int, chiefMin: Int): Int {
        var myCond = 0

        if (leadership < chiefMin || strength < chiefMin || intel < chiefMin) {
            if (leadership < chiefMin) myCond = myCond or TraitRequirement.STAT_NOT_LEADERSHIP
            if (strength < chiefMin) myCond = myCond or TraitRequirement.STAT_NOT_STRENGTH
            if (intel < chiefMin) myCond = myCond or TraitRequirement.STAT_NOT_INTEL
        }

        if (myCond == 0) {
            // 모든 스탯이 chiefMin 이상인 경우 — 가장 높은 스탯 결정
            if (leadership * 0.9 > strength && leadership * 0.9 > intel) {
                myCond = myCond or TraitRequirement.STAT_LEADERSHIP
            } else if (strength >= intel) {
                myCond = myCond or TraitRequirement.STAT_STRENGTH
            } else {
                myCond = myCond or TraitRequirement.STAT_INTEL
            }
        }

        return myCond
    }

    /**
     * 숙련도 기반 선택 조건 계산.
     *
     * dex: [보병, 궁병, 기병, 귀병, 차병] 숙련도 배열 (인덱스 0~4).
     * 80% 확률로 0 반환 (숙련 특기 미부여).
     * 나머지 경우에도 sqrt(합)/4 확률로 0 반환.
     * 통과하면 가장 높은 숙련도의 병종 플래그 반환.
     */
    fun calcCondDexterity(rng: Random, dex: IntArray): Int {
        val dexMap = mapOf(
            TraitRequirement.ARMY_FOOTMAN to (dex.getOrElse(0) { 0 }),
            TraitRequirement.ARMY_ARCHER to (dex.getOrElse(1) { 0 }),
            TraitRequirement.ARMY_CAVALRY to (dex.getOrElse(2) { 0 }),
            TraitRequirement.ARMY_WIZARD to (dex.getOrElse(3) { 0 }),
            TraitRequirement.ARMY_SIEGE to (dex.getOrElse(4) { 0 }),
        )

        val dexSum = dexMap.values.sum()
        val dexProb = sqrt(dexSum.toDouble()) / 4.0

        // 80% 확률로 숙련 특기 미부여
        if (rng.nextDouble() < 0.8) {
            return 0
        }

        // sqrt(합)/4 확률로 미부여
        if (rng.nextInt(100) < dexProb.toInt()) {
            return 0
        }

        // 숙련 합이 0이면 무작위 병종
        if (dexSum == 0) {
            val keys = dexMap.keys.toList()
            return keys[rng.nextInt(keys.size)]
        }

        // 가장 높은 숙련도 병종 (동률이면 무작위)
        val maxDex = dexMap.values.max()
        val candidates = dexMap.entries.filter { it.value == maxDex }.map { it.key }
        return candidates[rng.nextInt(candidates.size)]
    }

    /**
     * 적격 특기 풀에서 하나를 무작위 선택.
     *
     * myCond: calcCondGeneric + calcCondDexterity 결합 결과.
     * PERCENT 타입 특기를 먼저 확인 (weight/100 확률).
     * 이후 NORM 풀에서 가중치 기반 선택.
     */
    fun pickTrait(
        rng: Random,
        myCond: Int,
        traits: List<TraitSpec>,
        prevTraitKeys: List<String>,
    ): String? {
        val normPool = mutableMapOf<String, Double>()
        val percentPool = mutableListOf<Pair<String, Double>>()

        for (trait in traits) {
            val selection = trait.selection ?: continue
            if (trait.key in prevTraitKeys) continue

            // 요구조건 검사: requirements 중 하나라도 만족하면 적격
            var valid = false
            for (req in selection.requirements) {
                if (req == (req and myCond)) {
                    valid = true
                    break
                }
            }
            if (!valid) continue

            when (selection.weightType) {
                TraitWeightType.PERCENT -> percentPool.add(trait.key to selection.weight)
                TraitWeightType.NORM -> normPool[trait.key] = selection.weight
            }
        }

        // PERCENT 타입 우선 확인
        for ((key, weight) in percentPool) {
            if (rng.nextDouble() < weight / 100.0) {
                return key
            }
        }

        // NORM 풀 가중치 기반 선택
        if (normPool.isEmpty()) return null

        val totalWeight = normPool.values.sum()
        var roll = rng.nextDouble() * totalWeight
        for ((key, weight) in normPool) {
            roll -= weight
            if (roll <= 0) return key
        }

        // 부동소수점 오차 안전장치
        return normPool.keys.last()
    }

    /**
     * 전투 특기 선택 통합 로직.
     * 스탯 조건 + 숙련도 조건을 합산하여 pickTrait 호출.
     */
    fun pickWarTrait(
        rng: Random,
        leadership: Int,
        strength: Int,
        intel: Int,
        dex: IntArray,
        prevTraitKeys: List<String>,
        chiefMin: Int,
        traits: List<TraitSpec> = TraitSpecRegistry.war,
    ): String? {
        var myCond = calcCondGeneric(leadership, strength, intel, chiefMin)
        val dexCond = calcCondDexterity(rng, dex)
        if (dexCond != 0) {
            myCond = myCond or dexCond or TraitRequirement.REQ_DEXTERITY
        }
        return pickTrait(rng, myCond, traits, prevTraitKeys)
    }

    /**
     * 내정 특기 선택 통합 로직.
     * 스탯 조건만으로 pickTrait 호출 (숙련도 무관).
     */
    fun pickDomesticTrait(
        rng: Random,
        leadership: Int,
        strength: Int,
        intel: Int,
        prevTraitKeys: List<String>,
        chiefMin: Int,
        traits: List<TraitSpec> = TraitSpecRegistry.domestic,
    ): String? {
        val myCond = calcCondGeneric(leadership, strength, intel, chiefMin)
        return pickTrait(rng, myCond, traits, prevTraitKeys)
    }
}
