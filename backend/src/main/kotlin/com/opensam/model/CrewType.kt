package com.opensam.model

enum class ArmType(val code: Int, val displayName: String) {
    CASTLE(0, "성벽"),
    FOOTMAN(1, "보병"),
    ARCHER(2, "궁병"),
    CAVALRY(3, "기병"),
    WIZARD(4, "귀병"),
    SIEGE(5, "차병"),
}

enum class CrewType(
    val code: Int,
    val displayName: String,
    val armType: ArmType,
    val attack: Int,
    val defence: Int,
    val speed: Int,
    val avoid: Int,
    val magicCoef: Double,
    val cost: Int,
    val riceCost: Int
) {
    // 성벽
    CASTLE(1000, "성벽", ArmType.CASTLE, 100, 100, 7, 0, 0.0, 99, 9),

    // 보병
    FOOTMAN(1100, "보병", ArmType.FOOTMAN, 100, 150, 7, 10, 0.0, 9, 9),
    CHEONGJU(1101, "청주병", ArmType.FOOTMAN, 100, 200, 7, 10, 0.0, 10, 11),
    MARINE(1102, "수병", ArmType.FOOTMAN, 150, 150, 7, 10, 0.0, 11, 10),
    ASSASSIN(1103, "자객병", ArmType.FOOTMAN, 100, 150, 8, 20, 0.0, 10, 10),
    GUARD(1104, "근위병", ArmType.FOOTMAN, 150, 200, 7, 10, 0.0, 12, 12),
    RATTAN(1105, "등갑병", ArmType.FOOTMAN, 100, 225, 7, 5, 0.0, 13, 10),
    BAEKYI(1106, "백이병", ArmType.FOOTMAN, 175, 175, 7, 5, 0.0, 13, 11),

    // 궁병
    ARCHER(1200, "궁병", ArmType.ARCHER, 100, 100, 7, 10, 0.0, 10, 10),
    MOUNTED_ARCHER(1201, "궁기병", ArmType.ARCHER, 100, 100, 8, 20, 0.0, 11, 12),
    CROSSBOW(1202, "연노병", ArmType.ARCHER, 150, 100, 8, 10, 0.0, 12, 11),
    LONGBOW(1203, "강궁병", ArmType.ARCHER, 150, 150, 7, 10, 0.0, 13, 13),
    STONE_CROSSBOW(1204, "석궁병", ArmType.ARCHER, 200, 100, 7, 10, 0.0, 13, 13),

    // 기병
    CAVALRY(1300, "기병", ArmType.CAVALRY, 150, 100, 7, 5, 0.0, 11, 11),
    WHITE_HORSE(1301, "백마병", ArmType.CAVALRY, 200, 100, 7, 5, 0.0, 12, 13),
    HEAVY_CAVALRY(1302, "중장기병", ArmType.CAVALRY, 150, 150, 7, 5, 0.0, 13, 12),
    CHARGE_CAVALRY(1303, "돌격기병", ArmType.CAVALRY, 200, 100, 8, 5, 0.0, 13, 11),
    IRON_CAVALRY(1304, "철기병", ArmType.CAVALRY, 100, 250, 7, 5, 0.0, 11, 13),
    HUNTER_CAVALRY(1305, "수렵기병", ArmType.CAVALRY, 150, 100, 8, 15, 0.0, 12, 12),
    BEAST(1306, "맹수병", ArmType.CAVALRY, 250, 175, 6, 0, 0.0, 16, 16),
    TIGER_CAVALRY(1307, "호표기병", ArmType.CAVALRY, 200, 150, 7, 5, 0.0, 14, 14),

    // 귀병
    WIZARD(1400, "귀병", ArmType.WIZARD, 80, 80, 7, 5, 0.5, 9, 9),
    DIVINE_WIZARD(1401, "신귀병", ArmType.WIZARD, 80, 80, 7, 20, 0.6, 10, 10),
    WHITE_WIZARD(1402, "백귀병", ArmType.WIZARD, 80, 130, 7, 5, 0.6, 9, 11),
    BLACK_WIZARD(1403, "흑귀병", ArmType.WIZARD, 130, 80, 7, 5, 0.6, 11, 9),
    EVIL_WIZARD(1404, "악귀병", ArmType.WIZARD, 130, 130, 7, 0, 0.6, 12, 12),
    SOUTH_WIZARD(1405, "남귀병", ArmType.WIZARD, 60, 60, 7, 10, 0.8, 8, 8),
    YELLOW_WIZARD(1406, "황귀병", ArmType.WIZARD, 110, 110, 7, 0, 0.8, 13, 10),
    HEAVEN_WIZARD(1407, "천귀병", ArmType.WIZARD, 80, 130, 7, 15, 0.6, 11, 12),
    DEMON_WIZARD(1408, "마귀병", ArmType.WIZARD, 130, 80, 7, 15, 0.6, 12, 11),

    // 차병(공성)
    JEONGRAN(1500, "정란", ArmType.SIEGE, 100, 100, 6, 0, 0.0, 14, 5),
    RAM(1501, "충차", ArmType.SIEGE, 150, 100, 6, 0, 0.0, 18, 5),
    CATAPULT(1502, "벽력거", ArmType.SIEGE, 150, 100, 6, 5, 0.0, 20, 5),
    WOODEN_OX(1503, "목우", ArmType.SIEGE, 50, 200, 5, 0, 0.0, 15, 5);

    companion object {
        private val byCode = entries.associateBy { it.code }

        fun fromCode(code: Int): CrewType? = byCode[code]
    }
}
