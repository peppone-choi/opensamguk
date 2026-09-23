package opensamguk.gameapi.read

import com.fasterxml.jackson.databind.ObjectMapper
import opensamguk.gameapi.dto.HwihaRenownPendingEventDto
import opensamguk.gameapi.dto.HwihaRenownReasonDto
import opensamguk.gameapi.dto.HwihaRetinueResponse
import opensamguk.gameapi.dto.HwihaWarehousesResponse
import opensamguk.gameapi.dto.HwihaYuedanResponse
import opensamguk.gameapi.web.HwihaCampController
import opensamguk.infra.entity.GameKvEntity
import opensamguk.infra.seed.ResolvedHanWorldArtifacts
import opensamguk.logic.economy.HwihaCountyWarehouse
import opensamguk.logic.economy.HwihaResources
import opensamguk.logic.input.HwihaPersonPolicyState
import opensamguk.logic.input.HwihaRenownAssessment
import opensamguk.logic.input.HwihaRenownEvents
import opensamguk.logic.world.HanWorldVariant
import org.mockito.Mockito.*
import java.util.Optional
import kotlin.test.*

class HwihaCampReaderTest {
    private val generals = mock(GeneralReadRepository::class.java)
    private val worlds = mock(WorldStateReadRepository::class.java)
    private val nations = mock(NationReadRepository::class.java)
    private val cities = mock(CityReadRepository::class.java)
    private val retainers = mock(RetainerReadRepository::class.java)
    private val gameKv = mock(GameKvReadRepository::class.java)
    private val resolver = mock(ActiveWorldArtifactResolver::class.java)
    private val geography = mock(HwihaCityGeography::class.java)
    private val mapper = ObjectMapper()
    // 원장은 진짜 classpath 판(빌드가 data/curated 에서 실은 것)을 읽는다 — 패키징까지 같이 검증한다.
    private val ledgers = HwihaCampLedgers(mapper)
    private val reader = HwihaCampReader(generals, worlds, nations, cities, retainers, gameKv, resolver, ledgers, geography, mapper)
    private val controller = HwihaCampController(reader)

    private val world = WorldStateReadEntity(id = 1, config = mapOf("ruleProfile" to "HWIHA"))
    private val bundle = mock(ResolvedHanWorldArtifacts::class.java)

    private fun policy(renown: Int, source: String = "synthetic-qa:camp") =
        HwihaPersonPolicyState(renown, false, source, "v1", 1).toMetaValue()

    // 본인: 시나리오 장수 조조(沛國 譙). 휘하: 하후돈(沛國 譙) · 유비(涿郡 涿縣) · 정봉(동명이인) · 장수 없는 카드.
    private val lord = GeneralReadEntity(id = 1, worldId = 1, name = "조조", nationId = 1, cityId = 5, userId = "41",
        leadership = 100, strength = 80, intel = 95, politics = 90, charm = 95,
        meta = mapOf("npc_org" to 1, HwihaPersonPolicyState.META_KEY to policy(30)))
    private val xiahou = GeneralReadEntity(id = 2, worldId = 1, name = "하후돈", nationId = 1, cityId = 2, npcState = 2,
        leadership = 90, strength = 90, intel = 60, politics = 60, charm = 70,
        meta = mapOf("npc_org" to 2, HwihaPersonPolicyState.META_KEY to policy(30)))
    private val liubei = GeneralReadEntity(id = 3, worldId = 1, name = "유비", nationId = 0, npcState = 2,
        leadership = 80, strength = 70, intel = 70, politics = 70, charm = 99,
        meta = mapOf("npc_org" to 2, HwihaPersonPolicyState.META_KEY to policy(40)))
    private val dingfeng = GeneralReadEntity(id = 4, worldId = 1, name = "정봉", nationId = 1, npcState = 2,
        leadership = 70, strength = 77, intel = 64, politics = 50, charm = 50,
        meta = mapOf("npc_org" to 2, HwihaPersonPolicyState.META_KEY to policy(30)))
    private val other = GeneralReadEntity(id = 9, worldId = 1, name = "남", userId = "42")

    private fun setup(profile: String = "HWIHA") {
        world.config = mapOf("ruleProfile" to profile)
        `when`(worlds.findProcessWorld()).thenReturn(world)
        listOf(lord, xiahou, liubei, dingfeng, other).forEach { `when`(generals.findById(it.id)).thenReturn(Optional.of(it)) }
        `when`(generals.findById(99)).thenReturn(Optional.empty())
        `when`(generals.findAll()).thenReturn(listOf(lord, xiahou, liubei, dingfeng, other))
        `when`(retainers.retainersOf(1)).thenReturn(listOf(
            GeneralRetainerReadEntity(worldId = 1, id = 11, masterGeneralId = 1, generalId = 2, name = "하후돈", loyalty = 90, role = "GUARD", task = "train"),
            GeneralRetainerReadEntity(worldId = 1, id = 12, masterGeneralId = 1, generalId = 3, name = "유비", loyalty = 20),
            GeneralRetainerReadEntity(worldId = 1, id = 13, masterGeneralId = 1, generalId = 4, name = "정봉", loyalty = 20),
            GeneralRetainerReadEntity(worldId = 1, id = 14, masterGeneralId = 1, generalId = null, name = "무명 식객", loyalty = 50),
        ))
        `when`(retainers.bugoksOf(1)).thenReturn(listOf(
            GeneralBugokReadEntity(worldId = 1, id = 21, masterGeneralId = 1, name = "호표기", troops = 300, provisions = 900)))
        `when`(nations.findAll()).thenReturn(listOf(NationReadEntity(id = 1, worldId = 1, name = "위", color = "#1A4E8C", capitalCityId = 2)))
        `when`(nations.findById(1)).thenReturn(Optional.of(NationReadEntity(id = 1, worldId = 1, name = "위", color = "#1A4E8C", capitalCityId = 2)))
        `when`(resolver.resolve()).thenReturn(ActiveWorldArtifactSnapshot(world, emptyList(), bundle))
        `when`(geography.places(bundle)).thenReturn(mapOf(
            2 to HwihaCityGeography.Place("하내", "40556"),
            5 to HwihaCityGeography.Place("파군", "200197"),
            6 to HwihaCityGeography.Place("경조윤", "70623"),
        ))
        // 한글 지명 색인: 유비(涿縣, 관할 87307)는 id 로, 조조·하후돈(沛國 譙, id 없음)은 (郡, 縣) 쌍으로 풀린다.
        val fold = ledgers.fold
        `when`(geography.countyNames(bundle)).thenReturn(HwihaCityGeography.CountyNames(
            byJurisdiction = mapOf("87307" to listOf("탁군 탁현")),
            byPair = mapOf((fold.group("沛國") to fold.county("谯县")) to listOf("패국 초현")),
            fold = fold,
        ))
    }

    private fun kv(key: String, json: String) =
        `when`(gameKv.findByTableAndNamespaceAndKey("game_env", "game_env", key)).thenReturn(GameKvEntity("game_env", "game_env", key, json, 1))

    private fun warehouseMeta(cityId: Int, money: Long) =
        mapOf(HwihaCountyWarehouse.META_KEY to HwihaCountyWarehouse(cityId, 3, HwihaResources(money, 200, 3, 4, 5)).toMetaValue())

    // ── 인증 ─────────────────────────────────────────────────────────────
    @Test fun `principal 없음·범위 밖은 401, 남의 장수는 403, 본인은 200 no-store`() {
        setup()
        `when`(cities.findById(5)).thenReturn(Optional.of(CityReadEntity(id = 5, worldId = 1, name = "탕거")))
        val calls: List<(Long?) -> org.springframework.http.ResponseEntity<Any>> = listOf(
            { controller.yuedan(it, 1) }, { controller.warehouses(it, 1) },
            { controller.county(it, 5, 1) }, { controller.retinue(it, 1) })
        calls.forEach { call ->
            assertEquals(401, call(null).statusCode.value())
            assertEquals(401, call(0).statusCode.value())
            assertEquals(401, call(Int.MAX_VALUE.toLong() + 1).statusCode.value())
            assertEquals(403, call(42).statusCode.value())
            val ok = call(41)
            assertEquals(200, ok.statusCode.value())
            assertEquals("no-store", ok.headers.cacheControl)
        }
        assertEquals(403, controller.retinue(41, 99).statusCode.value(), "없는 장수도 403 이다(존재 여부를 흘리지 않는다)")
    }

    @Test fun `소유 확인이 먼저다 - 남의 장수로는 휘하·창고를 읽지 않는다`() {
        setup()
        assertFailsWith<HwihaCampForbidden> { reader.retinue(1, 42) }
        assertFailsWith<HwihaCampForbidden> { reader.warehouses(1, 42) }
        verifyNoInteractions(retainers, cities, gameKv, resolver)
    }

    @Test fun `휘하 규칙이 아닌 월드는 200 WRONG_RULE_PROFILE 빈 데이터`() {
        setup(profile = "SAMMO")
        `when`(cities.findById(5)).thenReturn(Optional.of(CityReadEntity(id = 5, worldId = 1, name = "탕거")))
        assertEquals(HwihaYuedanResponse("WRONG_RULE_PROFILE"), reader.yuedan(1, 41))
        assertEquals(HwihaWarehousesResponse("WRONG_RULE_PROFILE"), reader.warehouses(1, 41))
        assertEquals(HwihaRetinueResponse("WRONG_RULE_PROFILE"), reader.retinue(1, 41))
        val county = assertNotNull(reader.county(5, 1, 41))
        assertEquals("WRONG_RULE_PROFILE", county.status); assertTrue(county.specialties.isEmpty())
        verifyNoInteractions(retainers, gameKv, resolver)
    }

    // ── 월단평 ───────────────────────────────────────────────────────────
    @Test fun `월단평 전이면 NOT_ASSESSED 이고 본인 명망·코스트는 채운다`() {
        setup()
        val out = reader.yuedan(1, 41)
        assertEquals("NOT_ASSESSED", out.status); assertNull(out.stamp); assertTrue(out.ranking.isEmpty())
        // 무명 카드는 검증된 5스탯이 없으므로 총합도 미상이다.
        val self = assertNotNull(out.self)
        assertEquals(30, self.renown); assertNull(self.retinueCost); assertFalse(self.overCapacity)
    }

    @Test fun `월단평 순위는 발표된 자리대로 싣고 사라진 장수는 뺀다`() {
        setup()
        kv(HwihaRenownAssessment.STAMP_KEY, "\"0190-03\"")
        kv(HwihaRenownAssessment.RANKING_KEY, "[3, 99, 1, 2]")
        val out = reader.yuedan(1, 41)
        assertEquals("READY", out.status); assertEquals("0190-03", out.stamp)
        // 99 는 없는 장수; 발표된 자리 번호는 그대로다.
        assertEquals(listOf(1 to 3, 3 to 1, 4 to 2), out.ranking.map { it.rank to it.generalId })
        val liu = out.ranking.first()
        assertEquals("유비", liu.name); assertEquals(40, liu.renown); assertNull(liu.nationName); assertNull(liu.nationColor)
        val cao = out.ranking[1]
        assertEquals("위", cao.nationName); assertEquals("#1A4E8C", cao.nationColor); assertEquals(30, cao.renown)
    }

    @Test fun `순위 행에 지난 월단평 사유를 종류로만 싣고 본인 대기 사건은 본인에게만 준다`() {
        setup()
        // 본인(조조) 집계: 이번 달 발령 거절 1건 — 원인까지 본인은 본다.
        lord.meta = lord.meta + (HwihaRenownEvents.META_KEY to mapOf("entries" to listOf(
            mapOf("kind" to "dispatchRefusal", "stamp" to "0190-03", "source" to "DISPATCH_REFUSAL"))))
        // 남(유비)의 집계는 응답 어디에도 나오지 않는다.
        liubei.meta = liubei.meta + (HwihaRenownEvents.META_KEY to mapOf("entries" to listOf(
            mapOf("kind" to "warMerit", "stamp" to "0190-03", "source" to "ENCOUNTER_VICTORY"))))
        kv(HwihaRenownAssessment.STAMP_KEY, "\"0190-03\"")
        kv(HwihaRenownAssessment.RANKING_KEY, "[3, 1]")
        kv(HwihaRenownAssessment.REASONS_KEY, """{"stamp":"0190-03","byGeneral":{"3":[{"kind":"warMerit","count":1,"amount":3},
            {"kind":"bogus","count":1,"amount":9}],"1":[{"kind":"defeat","count":2,"amount":-6}]}}""")
        val out = reader.yuedan(1, 41)
        assertEquals(listOf(HwihaRenownReasonDto("warMerit", "전공", 1, 3)), out.ranking.first { it.generalId == 3 }.reasons)
        assertEquals(listOf(HwihaRenownReasonDto("defeat", "패전", 2, -6)), out.ranking.first { it.generalId == 1 }.reasons)
        assertEquals(listOf(HwihaRenownPendingEventDto("dispatchRefusal", "발령 거절", "0190-03", "DISPATCH_REFUSAL", "발령 거절", -4)),
            out.selfPendingEvents)
        assertFalse("ENCOUNTER_VICTORY" in mapper.writeValueAsString(out), "남의 사건 원인은 새지 않는다")

        // 사유의 도장이 발표 도장과 다르면(다른 달) 싣지 않는다.
        kv(HwihaRenownAssessment.REASONS_KEY, """{"stamp":"0190-02","byGeneral":{"3":[{"kind":"warMerit","count":1,"amount":3}]}}""")
        assertTrue(reader.yuedan(1, 41).ranking.all { it.reasons.isEmpty() })
    }

    @Test fun `월단평 전에도 본인 대기 사건은 보인다`() {
        setup()
        lord.meta = lord.meta + (HwihaRenownEvents.META_KEY to mapOf("entries" to listOf(
            mapOf("kind" to "betrayal", "stamp" to "0190-03", "source" to "DEFECTION"))))
        val out = reader.yuedan(1, 41)
        assertEquals("NOT_ASSESSED", out.status)
        assertEquals(listOf("betrayal"), out.selfPendingEvents.map { it.kind })
    }

    @Test fun `순위 값이 목록이 아니면 UNAVAILABLE`() {
        setup()
        kv(HwihaRenownAssessment.RANKING_KEY, "{\"x\":1}")
        assertEquals("UNAVAILABLE", reader.yuedan(1, 41).status)
    }

    // ── 창고 ─────────────────────────────────────────────────────────────
    @Test fun `창고는 우리 나라 縣만, 수도 먼저 그다음 id 순, 깨진 창고는 센다`() {
        setup()
        `when`(cities.findByNationIdOrderByIdAsc(1)).thenReturn(listOf(
            CityReadEntity(id = 2, worldId = 1, name = "회", nationId = 1, supplyState = 1, meta = warehouseMeta(2, 10)),
            CityReadEntity(id = 5, worldId = 1, name = "탕거", nationId = 1, supplyState = 0, meta = warehouseMeta(5, 70)),
            CityReadEntity(id = 3, worldId = 1, name = "깨짐", nationId = 1, meta = mapOf(HwihaCountyWarehouse.META_KEY to mapOf("version" to 2))),
            CityReadEntity(id = 4, worldId = 1, name = "창고없음", nationId = 1),
            CityReadEntity(id = 1, worldId = 1, name = "앞번호", nationId = 1, supplyState = 1, meta = warehouseMeta(1, 5)),
        ))
        val out = reader.warehouses(1, 41)
        assertEquals("READY", out.status); assertEquals(1, out.invalidCount)
        assertEquals(listOf(2, 1, 5), out.warehouses.map { it.cityId })
        val capital = out.warehouses.first()
        assertTrue(capital.isCapital); assertTrue(capital.supplied); assertEquals("하내", capital.commanderyName)
        assertEquals(10L, capital.stock.money); assertEquals(5L, capital.stock.horses)
        assertFalse(out.warehouses.last().supplied)
        assertNull(out.warehouses[1].commanderyName, "번들 지도에 없는 城은 郡 이름이 없다")
    }

    @Test fun `재야는 서 있는 城의 창고만 본다`() {
        setup()
        val ronin = GeneralReadEntity(id = 7, worldId = 1, name = "재야", nationId = 0, cityId = 5, userId = "43")
        `when`(generals.findById(7)).thenReturn(Optional.of(ronin))
        `when`(cities.findById(5)).thenReturn(Optional.of(CityReadEntity(id = 5, worldId = 1, name = "탕거", meta = warehouseMeta(5, 70))))
        val out = reader.warehouses(7, 43)
        assertEquals(listOf(5), out.warehouses.map { it.cityId }); assertFalse(out.warehouses.single().isCapital)
        verify(cities, never()).findByNationIdOrderByIdAsc(anyInt())
    }

    // ── 현 특산 ──────────────────────────────────────────────────────────
    @Test fun `현 특산은 산출 원장의 縣 행을 싣고 월 생산은 엔진과 같은 식으로 이번 달 실제 적립량이다`() {
        setup()
        reader.production = mapOf(5 to HwihaResources(iron = 1000, timber = 132))
        fun tangqu(supply: Int) = CityReadEntity(id = 5, worldId = 1, name = "탕거", nationId = 1, supplyState = supply,
            population = 5000, meta = warehouseMeta(5, 0))
        `when`(cities.findById(5)).thenReturn(Optional.of(tangqu(1)))
        `when`(cities.findById(6)).thenReturn(Optional.of(CityReadEntity(id = 6, worldId = 1, name = "장안", nationId = 1, supplyState = 1)))
        val iron = assertNotNull(reader.county(5, 1, 41))
        assertEquals("READY", iron.status); assertEquals("탕거", iron.name)
        // 원장 행 200197(宕渠) = 철 1000 · 목재 132(면적 축).
        assertEquals(listOf("IRON" to 1000L, "TIMBER" to 132L), iron.specialties.map { it.resource to it.ledgerMonthly })
        assertEquals("철", iron.specialties.first().label)
        assertEquals(listOf<Long?>(1000, 132), iron.specialties.map { it.monthly }, "보급된 우리 縣은 원장대로 들어온다")
        // 보급이 끊기면 엔진이 아무것도 넣지 않는다.
        `when`(cities.findById(5)).thenReturn(Optional.of(tangqu(0)))
        assertEquals(listOf<Long?>(0, 0), assertNotNull(reader.county(5, 1, 41)).specialties.map { it.monthly })
        // 창고가 없는 縣은 엔진이 건너뛴다 — 원장 행(목재 10)은 보이되 월 생산은 0.
        val noWarehouse = assertNotNull(reader.county(6, 1, 41))
        assertEquals(listOf("TIMBER" to 10L), noWarehouse.specialties.map { it.resource to it.ledgerMonthly })
        assertEquals(listOf<Long?>(0), noWarehouse.specialties.map { it.monthly })
    }

    @Test fun `창고 meta 가 깨진 縣은 월 생산을 모른다(null)`() {
        setup()
        `when`(cities.findById(5)).thenReturn(Optional.of(CityReadEntity(id = 5, worldId = 1, name = "탕거", nationId = 1,
            supplyState = 1, meta = mapOf(HwihaCountyWarehouse.META_KEY to "broken"))))
        assertTrue(assertNotNull(reader.county(5, 1, 41)).specialties.all { it.monthly == null })
    }

    @Test fun `없는 城은 404, 번들 없는 월드는 UNAVAILABLE`() {
        setup()
        `when`(cities.findById(77)).thenReturn(Optional.empty())
        assertNull(reader.county(77, 1, 41))
        assertEquals(404, controller.county(41, 77, 1).statusCode.value())
        `when`(cities.findById(5)).thenReturn(Optional.of(CityReadEntity(id = 5, worldId = 1, name = "탕거")))
        `when`(resolver.resolve()).thenReturn(ActiveWorldArtifactSnapshot(world, emptyList(), null))
        assertEquals("UNAVAILABLE", assertNotNull(reader.county(5, 1, 41)).status)
    }

    // ── 휘하 카드 ────────────────────────────────────────────────────────
    @Test fun `휘하 카드는 코스트·적성·향당을 싣고 상한 이하면 이탈 순번이 없다`() {
        setup()
        val out = reader.retinue(1, 41)
        assertEquals("READY", out.status); assertEquals(30, out.renown); assertNull(out.costSum); assertFalse(out.overCapacity)
        assertEquals(listOf(11, 12, 13, 14), out.people.map { it.retainerId })
        assertTrue(out.people.all { it.departureOrder == null })

        val xia = out.people[0]
        assertEquals(8, xia.cost); assertEquals("호위", xia.roleLabel); assertEquals("훈련", xia.taskLabel)
        assertEquals(2, xia.locationCityId, "상사 화면은 카드 인물의 현재 城으로 창고망을 계산한다")
        assertEquals(90, xia.stats?.leadership)
        // 장 90*.6+90*.4=90, 리 60*.7+60*.3=60, 사 60, 사자 70*.6+60*.4=66
        assertEquals(opensamguk.gameapi.dto.HwihaAptitudesDto(90, 60, 60, 66), xia.aptitudes)
        val bond = xia.bonds.single()
        assertEquals("HYANGDANG", bond.kind); assertEquals("향당", bond.label)
        assertEquals("패국 초현", bond.nativeCountyName, "한글 우선 — 簡體 城 표(谯县)와 繁體 원장(譙)을 같은 정규화로 맞춘다")
        assertEquals("沛國 譙", bond.nativeCountyHanja)
        assertTrue(bond.sameAsLord, "조조·하후돈은 둘 다 「沛國譙人」이다")

        val liu = out.people[1].bonds.single()
        assertEquals("탁군 탁현", liu.nativeCountyName); assertEquals("涿郡 涿縣", liu.nativeCountyHanja); assertFalse(liu.sameAsLord)
        assertTrue(out.people[2].bonds.isEmpty(), "정봉은 동명이인(丁奉·丁封) — 고르지 않는다")

        val nobody = out.people[3]
        assertNull(nobody.generalId); assertNull(nobody.cost); assertNull(nobody.stats); assertNull(nobody.aptitudes)
        assertNull(nobody.locationCityId)
        assertTrue(nobody.bonds.isEmpty()); assertEquals("무명 식객", nobody.name)

        val unit = out.units.single()
        assertEquals(21, unit.id); assertEquals("-", unit.crewTypeName); assertEquals(300, unit.troops)
    }

    @Test fun `상한을 넘으면 충성 낮은 쪽부터 동점은 id 큰 쪽부터 이탈 순번을 단다`() {
        setup()
        val namedCards = retainers.retainersOf(1).filter { it.generalId != null }
        `when`(retainers.retainersOf(1)).thenReturn(namedCards)
        lord.meta = lord.meta + (HwihaPersonPolicyState.META_KEY to policy(15))
        val out = reader.retinue(1, 41)
        assertTrue(out.overCapacity)
        // 23 > 15: 충성 20 동점(12 유비, 13 정봉) → 13 먼저(-7 → 16), 그다음 12(-8 → 8 ≤ 15) 에서 멈춘다.
        assertEquals(23, out.costSum)
        assertEquals(mapOf(11 to null, 12 to 2, 13 to 1), out.people.associate { it.retainerId to it.departureOrder })
    }

    @Test fun `능력치 출처가 없는 인물은 코스트를 계산하지 않는다`() {
        setup()
        xiahou.meta = mapOf("npc_org" to 2)
        val out = reader.retinue(1, 41)
        assertNull(out.people.first { it.generalId == 2 }.cost)
        assertNull(out.costSum)
        assertNull(reader.yuedan(1, 41).self?.retinueCost)
    }

    @Test fun `사람이 만든 장수는 이름이 같아도 본관을 받지 않는다`() {
        setup()
        // 이름만 「유비」인 신규 장수 — npc_org 가 없고 정책 출처가 created-general 이다.
        liubei.meta = mapOf(HwihaPersonPolicyState.META_KEY to policy(30, source = HwihaCampLedgers.CREATED_GENERAL_SOURCE))
        val out = reader.retinue(1, 41)
        assertTrue(out.people[1].bonds.isEmpty())
        liubei.meta = mapOf("npc_org" to 2, HwihaPersonPolicyState.META_KEY to policy(30, source = HwihaCampLedgers.CREATED_GENERAL_SOURCE))
        assertTrue(reader.retinue(1, 41).people[1].bonds.isEmpty())
    }

    @Test fun `명망 정책이 없으면 renown null 이고 초과가 아니다`() {
        setup()
        lord.meta = mapOf("npc_org" to 1)
        val out = reader.retinue(1, 41)
        assertNull(out.renown); assertFalse(out.overCapacity); assertTrue(out.people.all { it.departureOrder == null })
        assertEquals(null, reader.yuedan(1, 41).self?.renown)
    }

    // ── 원장·지리 ────────────────────────────────────────────────────────
    @Test fun `본관 원장은 EXACT DIRECT 縣 있는 행만, 동명 이름은 싣지 않는다`() {
        val table = ledgers.nativeCountyByScenarioName
        assertEquals("譙", table["조조"]?.county); assertEquals("沛國", table["조조"]?.commandery)
        assertEquals("87307", table["유비"]?.jurisdictionId)
        assertNull(table["장비"], "장비는 郡(涿郡)만 안다 — 縣 결속을 만들지 않는다")
        assertNull(table["허저"], "UNSPLIT 행은 縣이 없다")
        assertNull(table["정봉"]); assertNull(table["정봉1"])
        assertNull(table["하후연"], "MISSING 은 결속이 없다")
    }

    @Test fun `EXACT 행이라도 같은 이름을 다른 행이 실으면 고르지 않는다`() {
        fun row(id: String, link: String, names: String, county: String?) =
            """{"stableId":"$id","scenarioLink":"$link","scenarioNames":[$names],"method":"DIRECT",
               "nativeCommandery":"沛國","nativeCounty":${county?.let { "\"$it\"" } ?: "null"},"jurisdictionId":null}"""
        val json = """{"schemaVersion":1,"ledgerId":"officer-native-county-v1","officers":[
            ${row("1", "EXACT", "\"갑\"", "譙")}, ${row("2", "HOMONYM", "\"갑\",\"갑1\"", "譙")},
            ${row("3", "EXACT", "\"을\"", "蘄")}, ${row("4", "EXACT", "\"병\"", null)}]}"""
        val table = ledgers.parseNativeCounty(json.toByteArray())
        assertEquals(setOf("을"), table.keys, "갑은 두 행이 싣고, 병은 縣이 없다")
    }

    @Test fun `산출 원장은 縣마다 목재(면적 축)이고 철·말은 새 판의 40 縣이다`() {
        val table = ledgers.productionByJurisdiction
        // 縣 수는 지도 판마다 바뀐다 — 박지 않고 모든 행에 목재가 있는지만 본다.
        assertTrue(table.values.all { rows -> rows.any { it.resource == "TIMBER" } })
        assertEquals(listOf(HwihaCampLedgers.Specialty("IRON", 1000), HwihaCampLedgers.Specialty("TIMBER", 132)), table["200197"])
        assertTrue(table.values.flatten().all { it.resource in setOf("IRON", "HORSE", "TIMBER") })
        assertEquals(40, table.values.count { rows -> rows.any { it.resource != "TIMBER" } }, "1447 판: 결손 縣 배치 뒤 생산 원장 실측")
    }

    @Test fun `城 표에서 풀리지 않는 본관은 한글 이름이 null 이고 한자는 남는다`() {
        setup()
        // 번들 없는 월드 — 한글 색인을 만들 수 없다. 결속 자체는 그대로 낸다.
        `when`(resolver.resolve()).thenReturn(ActiveWorldArtifactSnapshot(world, emptyList(), null))
        val bond = reader.retinue(1, 41).people[0].bonds.single()
        assertNull(bond.nativeCountyName); assertEquals("沛國 譙", bond.nativeCountyHanja); assertTrue(bond.sameAsLord)
    }

    @Test fun `실제 城 표로 본관을 한글로 푼다 - 풀리지 않으면 null`() {
        val artifacts = mock(ResolvedHanWorldArtifacts::class.java)
        `when`(artifacts.variant).thenReturn(HanWorldVariant.entries.first())
        val runtimeMap = checkNotNull(javaClass.classLoader.getResourceAsStream("map/han-world-v3.json")).use { it.readBytes() }
        val root = generateSequence(java.nio.file.Path.of("").toAbsolutePath()) { it.parent }
            .first { java.nio.file.Files.isRegularFile(it.resolve("data/map/han-tiles.json")) }
        `when`(artifacts.artifactBytes(HwihaCityGeography.RUNTIME_MAP)).thenReturn(runtimeMap)
        `when`(artifacts.artifactBytes(HwihaCityGeography.TILES)).thenReturn(
            java.nio.file.Files.readAllBytes(root.resolve("data/map/han-tiles.json")))
        val names = HwihaCityGeography(mapper, ledgers).countyNames(artifacts)
        val table = ledgers.nativeCountyByScenarioName
        fun korean(name: String) = names.korean(assertNotNull(table[name], name))
        assertEquals("패국 초현", korean("조조"), "관할 id 없는 DIRECT 행 — (郡, 縣) 쌍")
        assertEquals("패국 초현", korean("하후돈"))
        assertEquals("탁군 탁현", korean("유비"), "관할 id 있는 행")
        assertEquals("낭사국 양도현", korean("제갈량"))
        assertNull(korean("전종"), "吳郡 錢唐 — 城 표는 會稽郡 錢唐이다. 郡이 다르면 고르지 않는다")
        assertNull(korean("사마랑"), "河內 溫 — 글자표에 溫→温 이 없어 풀리지 않는다(표 범위 밖, 지어내지 않는다)")
        // 기준선(2026-09-23 실측): 1168 판에서는 120명 중 93명(관할 id 91 · (郡, 縣) 쌍 2)이 풀렸다
        // (tools/map/audit_county_coverage.make_normalizer 로 같은 대조를 파이썬에서 돌린 값). 1224 판(#865, 결손 縣 56곳)에서
        // 새 縣(1342–1397)에 본관이 걸린 7명(여범 세양·주유 서·장료 마읍·서황 양·전예 옹노·진교 동양·가후 고장)이 더 풀려
        // 100명이다 — null 은 27 → 20 명. 1447 판에서 새 城 223곳을 더한 뒤에는 104명이 풀린다.
        assertEquals(120, table.size)
        assertEquals(104, table.values.count { names.korean(it) != null })
    }

    @Test fun `지명 정규화는 audit 도구 규칙이다 - 邑·道·國은 이름의 일부`() {
        val fold = ledgers.fold
        assertEquals("安邑", fold.county("安邑县")); assertEquals("狄道", fold.county("狄道縣")); assertEquals("安国", fold.county("安國"))
        assertEquals("张掖", fold.county("張掖屬國")); assertEquals(fold.county("谯县"), fold.county("譙"))
        assertEquals("沛国", fold.group("沛國")); assertEquals("县", fold.county("县"), "한 글자는 떼지 않는다")
        assertEquals("谯", fold.county("譙"), "繁→簡 은 표로 눕힌다")
    }

    @Test fun `창고 DTO 는 isCapital 이름으로 직렬화된다`() {
        // 운영과 같은 빌더(Spring Boot 가 쓰는 Jackson2ObjectMapperBuilder). Kotlin 모듈은 classpath 에 없다.
        val boot = org.springframework.http.converter.json.Jackson2ObjectMapperBuilder.json().build<ObjectMapper>()
        val json = boot.writeValueAsString(opensamguk.gameapi.dto.HwihaWarehouseDto(2, "회", null, true, false,
            opensamguk.gameapi.dto.HwihaStockDto(1, 2, 3, 4, 5)))
        val node = boot.readTree(json)
        assertTrue(node.get("isCapital").asBoolean(), json); assertFalse(node.has("capital"), json)
        assertEquals(listOf("cityId", "commanderyName", "isCapital", "name", "stock", "supplied"), node.fieldNames().asSequence().sorted().toList())
    }

    @Test fun `지리 색인은 런타임 省 index 를 han-tiles 관할 id 로 푼다`() {
        val artifacts = mock(ResolvedHanWorldArtifacts::class.java)
        `when`(artifacts.variant).thenReturn(HanWorldVariant.entries.first())
        `when`(artifacts.artifactBytes(HwihaCityGeography.RUNTIME_MAP)).thenReturn("""{"width":1,"height":1,"cities":[
            {"id":1,"name":"장안","x":1,"y":1,"provinceId":1,"meta":{"jun":"경조윤","junCh":"京兆尹","nameCh":"长安县","displayName":"경조윤 장안현(长安)"}},
            {"id":2,"name":"떠돌이","x":2,"y":2,"provinceId":9},
            {"id":3,"name":"없음","x":3,"y":3}]}""".toByteArray())
        `when`(artifacts.artifactBytes(HwihaCityGeography.TILES)).thenReturn(
            """{"provinceRecords":[{"jurisdictionId":"1"},{"jurisdictionId":"70623"}]}""".toByteArray())
        val places = HwihaCityGeography(mapper, ledgers).places(artifacts)
        assertEquals(HwihaCityGeography.Place("경조윤", "70623", "京兆尹", "长安县", "경조윤 장안현"), places[1])
        assertEquals(HwihaCityGeography.Place(null, null), places[2])
        assertEquals(HwihaCityGeography.Place(null, null), places[3])
    }
}
