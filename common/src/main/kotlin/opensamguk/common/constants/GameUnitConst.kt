package opensamguk.common.constants

/**
 * Faithful port of `legacy/devsam-core/hwe/sammo/GameUnitConstBase.php`.
 *
 * getBuildData() rows 41-355 (positional 18-element tuples) + _generate() (lines 412-484) which
 * appends each constraint's getInfo() onto info[] and indexes by id/name/armType. The 18-field
 * tuple is the highest constants port hazard; the golden test round-trips id->all-fields for all rows.
 */
object GameUnitConst {
    const val T_CASTLE = 0
    const val T_FOOTMAN = 1
    const val T_ARCHER = 2
    const val T_CAVALRY = 3
    const val T_WIZARD = 4
    const val T_SIEGE = 5
    const val T_MISC = 6
    const val CREWTYPE_CASTLE = 1000

    const val DEFAULT_CREWTYPE = 1100

    /** PHP $typeData (lines 33-39): arm-type label map (footman..siege only). */
    val typeData: Map<Int, String> = linkedMapOf(
        T_FOOTMAN to "보병",
        T_ARCHER to "궁병",
        T_CAVALRY to "기병",
        T_WIZARD to "귀병",
        T_SIEGE to "차병",
    )

    /**
     * Raw build rows BEFORE _generate(). Faithful transcription of getBuildData() lines 41-355.
     * Each row is constructed with base info[] (constraint getInfo() NOT yet appended — that happens
     * in _generate()).
     */
    private fun buildData(): List<GameUnitDetail> = listOf(
        GameUnitDetail(
            1000, T_CASTLE, "성벽",
            100, 100, 7, 0, 0.0, 99, 9,
            listOf(UnitConstraint.Impossible),
            linkedMapOf(), // 성벽은 공격할 수 없다.
            linkedMapOf(T_FOOTMAN to 1.2),
            listOf("성벽입니다.", "생성할 수 없습니다."),
            null, listOf("che_선제사격시도", "che_선제사격발동"), null,
        ),
        GameUnitDetail(
            1100, T_FOOTMAN, "보병",
            100, 150, 7, 10, 0.0, 9, 9,
            listOf(),
            linkedMapOf(T_ARCHER to 1.2, T_CAVALRY to 0.8, T_SIEGE to 1.2),
            linkedMapOf(T_ARCHER to 0.8, T_CAVALRY to 1.2, T_SIEGE to 0.8),
            listOf("표준적인 보병입니다.", "보병은 방어특화이며,", "상대가 회피하기 어렵습니다."),
            null, listOf("che_방어력증가5p"), null,
        ),
        GameUnitDetail(
            1101, T_FOOTMAN, "청주병",
            100, 200, 7, 10, 0.0, 10, 11,
            listOf(UnitConstraint.ReqTech(1000), UnitConstraint.ReqRegions(listOf("중원"))),
            linkedMapOf(T_ARCHER to 1.2, T_CAVALRY to 0.8, T_SIEGE to 1.2),
            linkedMapOf(T_ARCHER to 0.8, T_CAVALRY to 1.2, T_SIEGE to 0.8),
            listOf("저렴하고 튼튼합니다."),
            null, listOf("che_방어력증가5p"), null,
        ),
        GameUnitDetail(
            1102, T_FOOTMAN, "수병",
            150, 150, 7, 10, 0.0, 11, 10,
            listOf(UnitConstraint.ReqTech(1000), UnitConstraint.ReqRegions(listOf("오월"))),
            linkedMapOf(T_ARCHER to 1.2, T_CAVALRY to 0.8, T_SIEGE to 1.2),
            linkedMapOf(T_ARCHER to 0.8, T_CAVALRY to 1.2, T_SIEGE to 0.8),
            listOf("저렴하고 강력합니다."),
            null, listOf("che_방어력증가5p"), null,
        ),
        GameUnitDetail(
            1103, T_FOOTMAN, "자객병",
            100, 150, 8, 20, 0.0, 10, 10,
            listOf(UnitConstraint.ReqTech(2000), UnitConstraint.ReqCities(listOf("저"))),
            linkedMapOf(T_ARCHER to 1.2, T_CAVALRY to 0.8, T_SIEGE to 1.2),
            linkedMapOf(T_ARCHER to 0.8, T_CAVALRY to 1.2, T_SIEGE to 0.8),
            listOf("은밀하고 날쌥니다."),
            null, listOf("che_방어력증가5p"), null,
        ),
        GameUnitDetail(
            1104, T_FOOTMAN, "근위병",
            150, 200, 7, 10, 0.0, 12, 12,
            listOf(UnitConstraint.ReqTech(3000), UnitConstraint.ReqCities(listOf("낙양"))),
            linkedMapOf(T_ARCHER to 1.2, T_CAVALRY to 0.8, T_SIEGE to 1.2),
            linkedMapOf(T_ARCHER to 0.8, T_CAVALRY to 1.2, T_SIEGE to 0.8),
            listOf("최강의 보병입니다."),
            null, listOf("che_방어력증가5p"), null,
        ),
        GameUnitDetail(
            1105, T_FOOTMAN, "등갑병",
            100, 225, 7, 5, 0.0, 13, 10,
            listOf(UnitConstraint.ReqTech(1000), UnitConstraint.ReqRegions(listOf("남중"))),
            linkedMapOf(T_ARCHER to 1.2, T_CAVALRY to 0.8, T_SIEGE to 1.2),
            linkedMapOf(T_ARCHER to 0.8, T_CAVALRY to 1.2, T_SIEGE to 0.8),
            listOf("등갑을 두른 보병입니다."),
            null, listOf("che_방어력증가5p"), null,
        ),
        GameUnitDetail(
            1106, T_FOOTMAN, "백이병",
            175, 175, 7, 5, 0.0, 13, 11,
            listOf(UnitConstraint.ReqTech(3000), UnitConstraint.ReqCities(listOf("성도"))),
            linkedMapOf(T_ARCHER to 1.1, T_CAVALRY to 0.9, T_SIEGE to 1.1),
            linkedMapOf(T_ARCHER to 0.9, T_CAVALRY to 1.1, T_SIEGE to 0.9),
            listOf("정예 보병입니다. 불리한 싸움도 버텨냅니다."),
            null, listOf("che_방어력증가5p"), null,
        ),
        GameUnitDetail(
            1200, T_ARCHER, "궁병",
            100, 100, 7, 10, 0.0, 10, 10,
            listOf(),
            linkedMapOf(T_CAVALRY to 1.2, T_FOOTMAN to 0.8, T_SIEGE to 1.2),
            linkedMapOf(T_CAVALRY to 0.8, T_FOOTMAN to 1.2, T_SIEGE to 0.8),
            listOf("표준적인 궁병입니다.", "궁병은 선제사격을 하는 병종입니다."),
            null, listOf("che_선제사격시도", "che_선제사격발동"), null,
        ),
        GameUnitDetail(
            1201, T_ARCHER, "궁기병",
            100, 100, 8, 20, 0.0, 11, 12,
            listOf(UnitConstraint.ReqTech(1000), UnitConstraint.ReqRegions(listOf("동이"))),
            linkedMapOf(T_CAVALRY to 1.2, T_FOOTMAN to 0.9, T_SIEGE to 1.2),
            linkedMapOf(T_CAVALRY to 0.8, T_FOOTMAN to 1.1, T_SIEGE to 0.8),
            listOf("말을 타고 잘 피합니다. 특히 다른 궁병보다 보병에게 조금 더 강합니다."),
            null, listOf("che_선제사격시도", "che_선제사격발동"), null,
        ),
        GameUnitDetail(
            1202, T_ARCHER, "연노병",
            150, 100, 8, 10, 0.0, 12, 11,
            listOf(UnitConstraint.ReqTech(1000), UnitConstraint.ReqRegions(listOf("서촉"))),
            linkedMapOf(T_CAVALRY to 1.2, T_FOOTMAN to 0.8, T_SIEGE to 1.2),
            linkedMapOf(T_CAVALRY to 0.8, T_FOOTMAN to 1.2, T_SIEGE to 0.8),
            listOf("화살을 연사합니다."),
            null, listOf("che_선제사격시도", "che_선제사격발동"), null,
        ),
        GameUnitDetail(
            1203, T_ARCHER, "강궁병",
            150, 150, 7, 10, 0.0, 13, 13,
            listOf(UnitConstraint.ReqTech(3000), UnitConstraint.ReqCities(listOf("양양"))),
            linkedMapOf(T_CAVALRY to 1.2, T_FOOTMAN to 0.8, T_SIEGE to 1.2),
            linkedMapOf(T_CAVALRY to 0.8, T_FOOTMAN to 1.2, T_SIEGE to 0.8),
            listOf("강건한 궁병입니다."),
            null, listOf("che_선제사격시도", "che_선제사격발동"), null,
        ),
        GameUnitDetail(
            1204, T_ARCHER, "석궁병",
            200, 100, 7, 10, 0.0, 13, 13,
            listOf(UnitConstraint.ReqTech(3000), UnitConstraint.ReqCities(listOf("건업"))),
            linkedMapOf(T_CAVALRY to 1.2, T_FOOTMAN to 0.8, T_SIEGE to 1.2),
            linkedMapOf(T_CAVALRY to 0.8, T_FOOTMAN to 1.2, T_SIEGE to 0.8),
            listOf("강력한 화살을 쏩니다."),
            null, listOf("che_선제사격시도", "che_선제사격발동"), null,
        ),
        GameUnitDetail(
            1300, T_CAVALRY, "기병",
            150, 100, 7, 5, 0.0, 11, 11,
            listOf(),
            linkedMapOf(T_FOOTMAN to 1.2, T_ARCHER to 0.8, T_SIEGE to 1.2),
            linkedMapOf(T_FOOTMAN to 0.8, T_ARCHER to 1.2, T_SIEGE to 0.8),
            listOf("표준적인 기병입니다.", "기병은 공격특화입니다."),
            null, listOf("che_기병병종전투"), null,
        ),
        GameUnitDetail(
            1301, T_CAVALRY, "백마병",
            200, 100, 7, 5, 0.0, 12, 13,
            listOf(UnitConstraint.ReqTech(1000), UnitConstraint.ReqRegions(listOf("하북"))),
            linkedMapOf(T_FOOTMAN to 1.2, T_ARCHER to 0.8, T_SIEGE to 1.2),
            linkedMapOf(T_FOOTMAN to 0.8, T_ARCHER to 1.2, T_SIEGE to 0.8),
            listOf("백마의 위용을 보여줍니다."),
            null, listOf("che_기병병종전투"), null,
        ),
        GameUnitDetail(
            1302, T_CAVALRY, "중장기병",
            150, 150, 7, 5, 0.0, 13, 12,
            listOf(UnitConstraint.ReqTech(1000), UnitConstraint.ReqRegions(listOf("서북"))),
            linkedMapOf(T_FOOTMAN to 1.2, T_ARCHER to 0.8, T_SIEGE to 1.2),
            linkedMapOf(T_FOOTMAN to 0.8, T_ARCHER to 1.2, T_SIEGE to 0.8),
            listOf("갑주를 두른 기병입니다."),
            null, listOf("che_기병병종전투"), null,
        ),
        GameUnitDetail(
            1303, T_CAVALRY, "돌격기병",
            200, 100, 8, 5, 0.0, 13, 11,
            listOf(UnitConstraint.ReqTech(2000), UnitConstraint.ReqCities(listOf("흉노"))),
            linkedMapOf(T_FOOTMAN to 1.2, T_ARCHER to 0.8, T_SIEGE to 1.2),
            linkedMapOf(T_FOOTMAN to 0.8, T_ARCHER to 1.2, T_SIEGE to 0.8),
            listOf("저돌적으로 공격합니다."),
            null, listOf("che_기병병종전투"), null,
        ),
        GameUnitDetail(
            1304, T_CAVALRY, "철기병",
            100, 250, 7, 5, 0.0, 11, 13,
            listOf(UnitConstraint.ReqTech(2000), UnitConstraint.ReqCities(listOf("강"))),
            linkedMapOf(T_FOOTMAN to 1.2, T_ARCHER to 0.8, T_SIEGE to 1.2),
            linkedMapOf(T_FOOTMAN to 0.8, T_ARCHER to 1.2, T_SIEGE to 0.8),
            listOf("철갑을 두른 기병입니다."),
            null, listOf("che_기병병종전투"), null,
        ),
        GameUnitDetail(
            1305, T_CAVALRY, "수렵기병",
            150, 100, 8, 15, 0.0, 12, 12,
            listOf(UnitConstraint.ReqTech(2000), UnitConstraint.ReqCities(listOf("산월"))),
            linkedMapOf(T_FOOTMAN to 1.2, T_ARCHER to 0.8, T_SIEGE to 1.2),
            linkedMapOf(T_FOOTMAN to 0.8, T_ARCHER to 1.2, T_SIEGE to 0.8),
            listOf("날쎄고 빠른 기병입니다."),
            null, listOf("che_기병병종전투"), null,
        ),
        GameUnitDetail(
            1306, T_CAVALRY, "맹수병",
            250, 175, 6, 0, 0.0, 16, 16,
            listOf(UnitConstraint.ReqTech(2000), UnitConstraint.ReqCities(listOf("남만"))),
            linkedMapOf(T_FOOTMAN to 1.2, T_ARCHER to 0.8, T_SIEGE to 1.2),
            linkedMapOf(T_FOOTMAN to 0.8, T_ARCHER to 1.2, T_SIEGE to 0.8),
            listOf("어느 누구보다 강력합니다."),
            null, listOf("che_기병병종전투"), null,
        ),
        GameUnitDetail(
            1307, T_CAVALRY, "호표기병",
            200, 150, 7, 5, 0.0, 14, 14,
            listOf(UnitConstraint.ReqTech(3000), UnitConstraint.ReqCities(listOf("허창"))),
            linkedMapOf(T_FOOTMAN to 1.2, T_ARCHER to 0.8, T_SIEGE to 1.2),
            linkedMapOf(T_FOOTMAN to 0.8, T_ARCHER to 1.2, T_SIEGE to 0.8),
            listOf("정예 기병입니다."),
            null, listOf("che_기병병종전투"), null,
        ),
        GameUnitDetail(
            1400, T_WIZARD, "귀병",
            80, 80, 7, 5, 0.5, 9, 9,
            listOf(),
            linkedMapOf(T_SIEGE to 1.2),
            linkedMapOf(T_SIEGE to 0.8),
            listOf("계략을 사용하는 병종입니다."),
            null, null, null,
        ),
        GameUnitDetail(
            1401, T_WIZARD, "신귀병",
            80, 80, 7, 20, 0.6, 10, 10,
            listOf(UnitConstraint.ReqTech(1000), UnitConstraint.ReqRegions(listOf("초"))),
            linkedMapOf(T_SIEGE to 1.2),
            linkedMapOf(T_SIEGE to 0.8),
            listOf("신출귀몰한 귀병입니다."),
            null, null, null,
        ),
        GameUnitDetail(
            1402, T_WIZARD, "백귀병",
            80, 130, 7, 5, 0.6, 9, 11,
            listOf(UnitConstraint.ReqTech(2000), UnitConstraint.ReqCities(listOf("오환"))),
            linkedMapOf(T_SIEGE to 1.2),
            linkedMapOf(T_SIEGE to 0.8),
            listOf("저렴하고 튼튼합니다."),
            null, null, null,
        ),
        GameUnitDetail(
            1403, T_WIZARD, "흑귀병",
            130, 80, 7, 5, 0.6, 11, 9,
            listOf(UnitConstraint.ReqTech(2000), UnitConstraint.ReqCities(listOf("왜"))),
            linkedMapOf(T_SIEGE to 1.2),
            linkedMapOf(T_SIEGE to 0.8),
            listOf("저렴하고 강력합니다."),
            null, null, null,
        ),
        GameUnitDetail(
            1404, T_WIZARD, "악귀병",
            130, 130, 7, 0, 0.6, 12, 12,
            listOf(UnitConstraint.ReqTech(3000), UnitConstraint.ReqCities(listOf("장안"))),
            linkedMapOf(T_SIEGE to 1.2),
            linkedMapOf(T_SIEGE to 0.8),
            listOf("백병전에도 능숙합니다."),
            null, null, null,
        ),
        GameUnitDetail(
            1405, T_WIZARD, "남귀병",
            60, 60, 7, 10, 0.8, 8, 8,
            listOf(UnitConstraint.ReqTech(1000)),
            linkedMapOf(T_SIEGE to 1.2),
            linkedMapOf(T_SIEGE to 0.8),
            listOf("전투를 포기하고 계략에 몰두합니다."),
            null, null, null,
        ),
        GameUnitDetail(
            1406, T_WIZARD, "황귀병",
            110, 110, 7, 0, 0.8, 13, 10,
            listOf(UnitConstraint.ReqTech(3000), UnitConstraint.ReqCities(listOf("낙양"))),
            linkedMapOf(T_SIEGE to 1.2),
            linkedMapOf(T_SIEGE to 0.8),
            listOf("고도로 훈련된 귀병입니다."),
            null, null, null,
        ),
        GameUnitDetail(
            1407, T_WIZARD, "천귀병",
            80, 130, 7, 15, 0.6, 11, 12,
            listOf(UnitConstraint.ReqTech(3000), UnitConstraint.ReqCities(listOf("성도"))),
            linkedMapOf(T_SIEGE to 1.2),
            linkedMapOf(T_SIEGE to 0.8),
            listOf("갑주를 두른 귀병입니다."),
            null, null, null,
        ),
        GameUnitDetail(
            1408, T_WIZARD, "마귀병",
            130, 80, 7, 15, 0.6, 12, 11,
            listOf(UnitConstraint.ReqTech(3000), UnitConstraint.ReqCities(listOf("업"))),
            linkedMapOf(T_SIEGE to 1.2),
            linkedMapOf(T_SIEGE to 0.8),
            listOf("날카로운 무기를 가진 귀병입니다."),
            null, null, null,
        ),
        GameUnitDetail(
            1500, T_SIEGE, "정란",
            100, 100, 6, 0, 0.0, 14, 5,
            listOf(UnitConstraint.ReqMinRelYear(3)),
            linkedMapOf(T_FOOTMAN to 1.25, T_ARCHER to 1.25, T_CAVALRY to 1.25, T_WIZARD to 1.25, T_CASTLE to 1.8, 1106 to 1.112),
            linkedMapOf(T_FOOTMAN to 1.2, T_ARCHER to 1.2, T_CAVALRY to 1.2, T_WIZARD to 1.2, 1106 to 1.067),
            listOf("높은 구조물 위에서 공격합니다. 첫 공격은 성벽을 향합니다."),
            listOf("che_성벽부상무효"), listOf("che_선제사격시도", "che_선제사격발동"), listOf("che_성벽선제"),
        ),
        GameUnitDetail(
            1501, T_SIEGE, "충차",
            150, 100, 6, 0, 0.0, 18, 5,
            listOf(UnitConstraint.ReqTech(1000), UnitConstraint.ReqMinRelYear(3)),
            linkedMapOf(T_FOOTMAN to 0.8, T_ARCHER to 0.8, T_CAVALRY to 0.8, T_WIZARD to 0.8, T_CASTLE to 2.4),
            linkedMapOf(T_FOOTMAN to 1.2, T_ARCHER to 1.2, T_CAVALRY to 1.2, T_WIZARD to 1.2),
            listOf("엄청난 위력으로 성벽을 부수어버립니다."),
            listOf("che_성벽부상무효"), null, null,
        ),
        GameUnitDetail(
            1502, T_SIEGE, "벽력거",
            150, 100, 6, 5, 0.0, 20, 5,
            listOf(UnitConstraint.ReqTech(3000), UnitConstraint.ReqCities(listOf("업"))),
            linkedMapOf(T_FOOTMAN to 1.25, T_ARCHER to 1.25, T_CAVALRY to 1.25, T_WIZARD to 1.25, T_CASTLE to 1.8, 1106 to 1.112),
            linkedMapOf(T_FOOTMAN to 1.2, T_ARCHER to 1.2, T_CAVALRY to 1.2, T_WIZARD to 1.2, 1106 to 1.067),
            listOf("상대에게 돌덩이를 날립니다. 첫 공격은 성벽을 향합니다."),
            listOf("che_성벽부상무효"), listOf("che_선제사격시도", "che_선제사격발동"), listOf("che_성벽선제"),
        ),
        GameUnitDetail(
            1503, T_SIEGE, "목우",
            50, 200, 5, 0, 0.0, 15, 5,
            listOf(UnitConstraint.ReqTech(3000), UnitConstraint.ReqCities(listOf("성도"))),
            linkedMapOf(T_FOOTMAN to 1.0, T_ARCHER to 1.0, T_CAVALRY to 1.0, T_WIZARD to 1.0, T_CASTLE to 1.8),
            linkedMapOf(T_FOOTMAN to 1.0, T_ARCHER to 1.0, T_CAVALRY to 1.0, T_WIZARD to 1.0, 1106 to 1.0),
            listOf("상대를 저지하는 특수병기입니다."),
            listOf("che_성벽부상무효"), listOf("che_저지시도", "che_저지발동"), null,
        ),
    )

    /**
     * Port of _generate() (lines 412-484): for each row, append each constraint's getInfo() onto
     * info[], then index by id / name / armType (insertion-ordered).
     */
    private val generated: Triple<Map<Int, GameUnitDetail>, Map<String, GameUnitDetail>, Map<Int, List<GameUnitDetail>>> by lazy {
        val constID = LinkedHashMap<Int, GameUnitDetail>()
        val constName = LinkedHashMap<String, GameUnitDetail>()
        val constType = LinkedHashMap<Int, MutableList<GameUnitDetail>>()

        for (raw in buildData()) {
            val resolvedInfo = raw.info + raw.reqConstraints.map { it.getInfo() }
            val unit = raw.copy(info = resolvedInfo)

            constID[unit.id] = unit
            constName[unit.name] = unit
            constType.getOrPut(unit.armType) { mutableListOf() }.add(unit)
        }

        Triple(constID, constName, constType.mapValues { it.value.toList() })
    }

    /** @return all units indexed by id (insertion-ordered). Mirrors PHP all(). */
    fun all(): Map<Int, GameUnitDetail> = generated.first

    fun allType(): Map<Int, String> = typeData

    /** Mirrors PHP byID(): throws on id < 1000, returns null if not found. */
    fun byId(id: Int): GameUnitDetail? {
        require(id >= 1000) { "적절한 id는 1000이상이어야합니다:$id" }
        return generated.first[id]
    }

    fun byName(name: String): GameUnitDetail? = generated.second[name]

    fun byType(type: Int): List<GameUnitDetail> = generated.third[type] ?: emptyList()
}
