package opensamguk.logic.inheritance

object InheritanceKeyRegistry {
    val entries: LinkedHashMap<InheritanceKey, InheritancePointType> = linkedMapOf<InheritanceKey, InheritancePointType>(
        InheritanceKey.PREVIOUS to InheritancePointType(true, 1.0, "기존 보유", 1.0),
        InheritanceKey.LIVED_MONTH to InheritancePointType(true, 1.0, "생존", 1.0),
        InheritanceKey.MAX_BELONG to InheritancePointType(false, 10.0, "최대 임관년 수", null),
        InheritanceKey.MAX_DOMESTIC_CRITICAL to InheritancePointType(true, 1.0, "최대 연속 내정 성공", null),
        InheritanceKey.ACTIVE_ACTION to InheritancePointType(true, 3.0, "능동 행동 수", 1.0),
        InheritanceKey.COMBAT to InheritancePointType(listOf("rank", "warnum"), 5.0, "전투 횟수", 1.0),
        InheritanceKey.SABOTAGE to InheritancePointType(listOf("rank", "firenum"), 20.0, "계략 성공 횟수", 1.0),
        InheritanceKey.UNIFIER to InheritancePointType(true, 1.0, "천통 기여", null),
        InheritanceKey.DEX to InheritancePointType(false, 0.001, "숙련도", 0.5),
        InheritanceKey.TOURNAMENT to InheritancePointType(true, 1.0, "토너먼트", 1.0),
        InheritanceKey.BETTING to InheritancePointType(false, 10.0, "베팅 당첨", 1.0),
    )

    operator fun get(key: InheritanceKey): InheritancePointType =
        entries[key] ?: throw IllegalArgumentException("$key 는 유산 타입이 아님")
}
