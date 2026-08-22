package opensamguk.logic.world

import opensamguk.common.constants.GameConst
import opensamguk.common.constants.GameUnitConst
import opensamguk.common.rng.RandUtil
import opensamguk.logic.util.phpRound
import opensamguk.logic.util.valueFit
import kotlin.math.floor

/**
 * GeneralBuilder — faithful port of `legacy/devsam-core/hwe/sammo/Scenario/GeneralBuilder.php` (737L).
 *
 * 시나리오/NPC mint 경로의 장수 생성 빌더. **순수 로직(no DB)** — draw-for-draw 패러티 타겟.
 * 진입(Join.php → [MakeGeneral]) 경로와는 **별개 draw 시퀀스**다(섞지 말 것).
 *
 * 단일 [RandUtil] 을 ctor 로 받아 fill 및 build 가 by-reference 로 공유한다(절대 재시드 금지).
 * DB INSERT(general/general_turn/rank_data)는 별도 flush 단(infra/seed)에서 [BuiltGeneral] 을 소비한다.
 *
 * 호출 형상(캡처 frozen historical baseline (ADR-LITE-042; not current product authority) = tools/php-golden/capture_general_builder.php):
 *   - 인재탐색형: setSpecial, setMoney, setLifeSpan, setSpecYear, fillRemainSpecAsRandom, build
 *   - RegNPC형: setCity, setStat, setEgo null, setAffinity, setLifeSpan, fillRemainSpecAsZero, build
 *   - CreateManyNPC형: setLifeSpan, fillRandomStat, fillRemainSpecAsZero, build
 *   - 특기형: setStat, setLifeSpan, setSpecialOption 랜덤, fillRemainSpecAsRandom, build
 */
class GeneralBuilder(
    val rng: RandUtil,
    private val name: String,
    private var nationID: Int,
) {
    // --- PHP 필드 기본값(GeneralBuilder.php:19-71) ---
    private var owner: Int = 0
    private var ownerName: String? = null
    private var affinity: Int? = null
    private var nameCustomPrefix: String? = null
    private var cityID: Int? = null
    private var leadership: Int? = null
    private var strength: Int? = null
    private var intel: Int? = null
    private var officerLevel: Int? = null
    private var birth: Int? = null
    private var death: Int? = null
    private var ego: String? = null
    private var specialDomestic: String? = null
    private var specialWar: String? = null
    private var npc: Int = 2
    private var text: String? = null
    private var gold: Int = 1000
    private var rice: Int = 1000
    private var specAge: Int? = null
    private var specAge2: Int? = null
    private var experience: Int? = null
    private var dedication: Int? = null
    private var killturn: Int? = null
    private var dex1: Int = 0
    private var dex2: Int = 0
    private var dex3: Int = 0
    private var dex4: Int = 0
    private var dex5: Int = 0
    private var politics: Int = 50
    private var charm: Int = 50
    private var appearanceYear: Int? = null
    private var rtkMetadata: Map<String, Any?> = emptyMap()

    // npc 타입 → 이름 접두(GeneralBuilder.php:42-52).
    private val prefixList: Map<Int, String> = mapOf(
        0 to "", 1 to "ⓝ", 2 to "ⓝ", 3 to "ⓜ", 4 to "ⓖ", 5 to "㉥", 6 to "ⓤ", 9 to "ⓞ",
    )

    fun setOwner(owner: Int): GeneralBuilder { require(owner > 0); this.owner = owner; return this }
    fun setOwnerName(n: String): GeneralBuilder { this.ownerName = n; return this }
    fun setNationID(n: Int): GeneralBuilder { this.nationID = n; return this }
    fun setNPCType(npcType: Int): GeneralBuilder {
        require(prefixList.containsKey(npcType)); this.npc = npcType; return this
    }
    fun setSpecial(specialDomestic: String, specialWar: String): GeneralBuilder {
        this.specialDomestic = specialDomestic; this.specialWar = specialWar; return this
    }
    fun setOfficerLevel(level: Int): GeneralBuilder { this.officerLevel = level; return this }
    fun setNPCText(text: String?): GeneralBuilder { this.text = text; return this }
    fun setEgo(ego: String?): GeneralBuilder { this.ego = ego; return this }
    fun setAffinity(affinity: Int): GeneralBuilder {
        this.affinity = when {
            affinity < 1 -> rng.nextRangeInt(1, 150)
            affinity >= 900 -> 999
            affinity in 1..150 -> affinity
            else -> throw IllegalArgumentException("invalid affinity: $affinity")
        }
        return this
    }
    fun setStat(leadership: Int, strength: Int, intel: Int): GeneralBuilder {
        this.leadership = leadership; this.strength = strength; this.intel = intel; return this
    }
    fun setPoliticsCharm(politics: Int, charm: Int): GeneralBuilder {
        this.politics = politics; this.charm = charm; return this
    }
    fun setAppearanceYear(appearanceYear: Int?): GeneralBuilder {
        this.appearanceYear = appearanceYear; return this
    }
    fun setRtkMetadata(rtkMetadata: Map<String, Any?>): GeneralBuilder {
        this.rtkMetadata = LinkedHashMap(rtkMetadata); return this
    }
    fun setSpecYear(specAge: Int?, specAge2: Int?): GeneralBuilder {
        this.specAge = specAge; this.specAge2 = specAge2; return this
    }
    fun setExpDed(experience: Int?, dedication: Int?): GeneralBuilder {
        this.experience = experience; this.dedication = dedication; return this
    }
    fun setMoney(gold: Int, rice: Int): GeneralBuilder { this.gold = gold; this.rice = rice; return this }
    fun setCityID(cityID: Int): GeneralBuilder { this.cityID = cityID; return this }
    fun setKillturn(killturn: Int?): GeneralBuilder { this.killturn = killturn; return this }
    fun setDex(footman: Int, archer: Int, cavalry: Int, wizard: Int, siege: Int): GeneralBuilder {
        dex1 = footman; dex2 = archer; dex3 = cavalry; dex4 = wizard; dex5 = siege; return this
    }
    fun setLifeSpan(birth: Int, death: Int): GeneralBuilder { this.birth = birth; this.death = death; return this }

    fun setSpecialSingle(special: String?): GeneralBuilder {
        this.specialDomestic = GameConst.defaultSpecialDomestic
        this.specialWar = GameConst.defaultSpecialWar
        val code = special?.takeIf { it.isNotBlank() }?.let { if ('_' in it) it else "che_$it" }
            ?: return this
        when (code) {
            in GameConst.availableSpecialDomestic -> this.specialDomestic = code
            in GameConst.availableSpecialWar -> this.specialWar = code
        }
        return this
    }

    /** setSpecialOption('랜덤'/'랜덤전특'/'랜덤내특') — RNG 특기 선발(GeneralBuilder.php:110-131). */
    fun setSpecialOption(option: String?): GeneralBuilder {
        val l = leadership ?: 0; val s = strength ?: 0; val i = intel ?: 0
        when (option) {
            "랜덤전특" -> specialWar = SpecialityHelper.pickSpecialWar(rng, l, s, i, dex1, dex2, dex3, dex4, dex5)
            "랜덤내특" -> specialDomestic = SpecialityHelper.pickSpecialDomestic(rng, l, s, i)
            "랜덤" -> {
                if (rng.nextBool(2.0 / 3.0)) {
                    specialWar = SpecialityHelper.pickSpecialWar(rng, l, s, i, dex1, dex2, dex3, dex4, dex5)
                } else {
                    specialDomestic = SpecialityHelper.pickSpecialDomestic(rng, l, s, i)
                }
            }
        }
        return this
    }

    /** fillRandomStat(GeneralBuilder.php:313-345) — choiceUsingWeight + nextRangeInt×2. */
    fun fillRandomStat(pickTypeList: Map<String, Double>): String {
        val pickType = rng.choiceUsingWeight(pickTypeList)
        val totalStat = GameConst.defaultStatNPCTotal
        val minStat = GameConst.defaultStatNPCMin
        var mainStat = GameConst.defaultStatNPCMax - rng.nextRangeInt(0, GameConst.defaultStatNPCMin)
        var otherStat = minStat + rng.nextRangeInt(0, GameConst.defaultStatNPCMin / 2)
        var subStat = totalStat - mainStat - otherStat
        if (subStat < minStat) {
            subStat = otherStat
            otherStat = minStat
            mainStat = totalStat - subStat - otherStat
            require(mainStat == 0) { "기본 스탯 설정값이 잘못되어 있음" }
        }
        when (pickType) {
            "무" -> setStat(subStat, mainStat, otherStat)
            "지" -> setStat(subStat, otherStat, mainStat)
            else -> setStat(otherStat, subStat, mainStat)
        }
        return pickType
    }

    /** fillRemainSpecAsZero(GeneralBuilder.php:347-398) — affinity/ego draw(설정 안된 경우만). */
    fun fillRemainSpecAsZero(year: Int, startYear: Int): GeneralBuilder {
        checkNotNull(leadership) { "stat이 설정되어 있지 않음" }
        if (affinity == null || affinity == 0) affinity = rng.nextRangeInt(1, 150)
        if (birth == null) { birth = year - 20; death = year + 60 }
        if (specAge == null) specAge = computeSpecAge(year, startYear, 12)
        if (specAge2 == null) specAge2 = computeSpecAge(year, startYear, 6)
        if (officerLevel == null) officerLevel = if (nationID != 0) 1 else 0
        if (ego == null) ego = rng.choice(GameConst.availablePersonality)
        if (specialDomestic == null) specialDomestic = GameConst.defaultSpecialDomestic
        if (specialWar == null) specialWar = GameConst.defaultSpecialWar
        if (killturn == null && owner != 0) killturn = 5
        return this
    }

    /**
     * fillRemainSpecAsRandom(GeneralBuilder.php:400-500).
     * @param avgGenDexTotal avgGen['dex_t'] (npc<4 dex 합 평균 — dex 분포 입력)
     * @param avgGenDex5     avgGen['dex5']
     * @param hasDexAvg      avgGen 에 dex_t 키 존재 여부(key_exists)
     */
    fun fillRemainSpecAsRandom(
        pickTypeList: Map<String, Double>,
        avgGenDexTotal: Double,
        avgGenDex5: Int,
        hasDexAvg: Boolean,
        year: Int,
        startYear: Int,
        isFiction: Boolean,
    ): GeneralBuilder {
        if (isFiction || specialWar == null) specialWar = GameConst.defaultSpecialWar
        if (isFiction || specialDomestic == null) specialDomestic = GameConst.defaultSpecialDomestic
        if (affinity == null || affinity == 0 || isFiction) affinity = rng.nextRangeInt(1, 150)
        if (birth == null) {
            birth = year + rng.nextRange(-5.0, 5.0).toInt()
            death = birth!! + rng.nextRangeInt(60, 80)
        }
        if (specAge == null) specAge = computeSpecAge(year, startYear, 12)
        if (specAge2 == null) specAge2 = computeSpecAge(year, startYear, 6)

        val pickType: String
        if (leadership == null || strength == null || intel == null) {
            pickType = fillRandomStat(pickTypeList)
        } else {
            val l = leadership!!; val s = strength!!; val i = intel!!
            pickType = when {
                l < 40 -> "무지"
                i * 0.8 > s -> "지"
                s * 0.8 > i -> "무"
                else -> rng.choiceUsingWeight(linkedMapOf("무" to s.toDouble(), "지" to i.toDouble()))
            }
        }

        if (officerLevel == null) officerLevel = if (nationID != 0) 1 else 0

        if (dex1 == 0 && hasDexAvg) {
            val t = avgGenDexTotal
            val dexVal: List<Double> = when (pickType) {
                "무" -> rng.choice(
                    listOf(
                        listOf(t * 5 / 8, t / 8, t / 8, t / 8),
                        listOf(t / 8, t * 5 / 8, t / 8, t / 8),
                        listOf(t / 8, t / 8, t * 5 / 8, t / 8),
                    ),
                )
                "지" -> listOf(t / 8, t / 8, t / 8, t * 5 / 8)
                else -> listOf(t / 4, t / 4, t / 4, t / 4)
            }
            setDex(dexVal[0].toInt(), dexVal[1].toInt(), dexVal[2].toInt(), dexVal[3].toInt(), avgGenDex5)
        }

        if (ego == null || isFiction) ego = rng.choice(GameConst.availablePersonality)
        if (experience == null) experience = 0
        if (dedication == null) dedication = 0
        return this
    }

    /** specAge/specAge2 결정 계산(GeneralBuilder.php:374-385,422-432). div=12(specAge)/6(specAge2). */
    private fun computeSpecAge(year: Int, startYear: Int, div: Int): Int {
        val age = year - birth!!
        val relYear = valueFit((year - startYear).toDouble(), 0.0).toInt()
        return valueFit(phpRound((GameConst.retirementYear - age).toDouble() / div - relYear / 2.0).toDouble(), 3.0).toInt() + age
    }

    /**
     * build(GeneralBuilder.php:542-737) — 도시배치 choice + getRandTurn + killturn draw 후 [BuiltGeneral].
     * @param cityPool      cityID 미지정 시 choice 대상(getAllCities | getAllNationCities). id+nation 보유.
     * @return null = legacy 사망(death<=year)/예약(age<adultAge), or explicit RTK 사망(year>death)/예약(year<appearance).
     */
    fun build(
        year: Int,
        month: Int,
        turnterm: Int,
        cityPool: List<CityChoice>,
        isFictionMode: Boolean = false,
        onAdultGeneral: ((String) -> Unit)? = null,
    ): BuiltGeneral? {
        val age = year - birth!!

        val finalName = (nameCustomPrefix ?: (prefixList[npc] ?: "ⓧ")) + name

        val explicitAppearanceYear = appearanceYear
        val isNewGeneral = if (explicitAppearanceYear == null) {
            if (death!! <= year) return null         // legacy: 사망 → 스킵
            if (age < GameConst.adultAge) return null // legacy: 미성년 → 예약
            age == GameConst.adultAge.toInt()
        } else {
            // RTK14 source rows are a sanctioned lifecycle divergence. Their appearance is explicit,
            // including under-age rows, and the source death year remains inclusive.
            if (year < explicitAppearanceYear) return null
            if (year > death!!) return null
            year == explicitAppearanceYear
        }

        var nation = nationID
        if (isFictionMode && isNewGeneral) nation = 0

        if (isNewGeneral) onAdultGeneral?.invoke(finalName)

        if (cityID == null) {
            val pool = if (nation == 0) cityPool else cityPool.filter { it.nationId == nation }.ifEmpty { cityPool }
            cityID = rng.choice(pool).id
        }

        val exp = if ((experience ?: 0) != 0) experience!! else age * 100
        val ded = if ((dedication ?: 0) != 0) dedication!! else age * 100
        var ol = officerLevel ?: 0
        if (ol == 0 || isNewGeneral) ol = if (nation != 0) 1 else 0

        // getRandTurn(GeneralBuilder.php:656) = nextRangeInt(0,60*turnterm-1) + nextRangeInt(0,999999).
        val turntimeSecond = rng.nextRangeInt(0, 60 * turnterm - 1)
        val turntimeFraction = rng.nextRangeInt(0, 999999)

        val kt = when {
            (killturn ?: 0) != 0 -> killturn!!
            else -> {
                val derivedKillturn = (death!! - year) * 12 + rng.nextRangeInt(0, 11) + month - 1
                if (explicitAppearanceYear == null) derivedKillturn else derivedKillturn.coerceAtLeast(1)
            }
        }

        return BuiltGeneral(
            name = finalName,
            npc = npc,
            nation = nation,
            cityId = cityID!!,
            leadership = leadership!!,
            strength = strength!!,
            intel = intel!!,
            affinity = affinity!!,
            ego = ego!!,
            specialDomestic = specialDomestic!!,
            specialWar = specialWar!!,
            specAge = specAge!!,
            specAge2 = specAge2!!,
            birth = birth!!,
            death = death!!,
            age = age,
            officerLevel = ol,
            killturn = kt,
            experience = exp,
            dedication = ded,
            gold = gold,
            rice = rice,
            crewType = GameUnitConst.DEFAULT_CREWTYPE,
            dex1 = dex1, dex2 = dex2, dex3 = dex3, dex4 = dex4, dex5 = dex5,
            turntimeSecond = turntimeSecond,
            turntimeFraction = turntimeFraction,
            npcText = text,
            politics = politics,
            charm = charm,
            appearanceYear = appearanceYear,
            rtkMetadata = LinkedHashMap(rtkMetadata),
        )
    }

    /** 도시배치 choice 입력(런타임 city 행 — id+현재 nation). */
    data class CityChoice(val id: Int, val nationId: Int)
}

/** GeneralBuilder.build 결과 — flush 단(general/general_turn/rank_data INSERT)이 소비할 outcome. */
data class BuiltGeneral(
    val name: String,
    val npc: Int,
    val nation: Int,
    val cityId: Int,
    val leadership: Int,
    val strength: Int,
    val intel: Int,
    val affinity: Int,
    val ego: String,
    val specialDomestic: String,
    val specialWar: String,
    val specAge: Int,
    val specAge2: Int,
    val birth: Int,
    val death: Int,
    val age: Int,
    val officerLevel: Int,
    val killturn: Int,
    val experience: Int,
    val dedication: Int,
    val gold: Int,
    val rice: Int,
    val crewType: Int,
    val dex1: Int, val dex2: Int, val dex3: Int, val dex4: Int, val dex5: Int,
    val turntimeSecond: Int,
    val turntimeFraction: Int,
    val picture: String = "default.jpg",
    val npcText: String? = null,
    val politics: Int = 50,
    val charm: Int = 50,
    val appearanceYear: Int? = null,
    val rtkMetadata: Map<String, Any?> = emptyMap(),
)
