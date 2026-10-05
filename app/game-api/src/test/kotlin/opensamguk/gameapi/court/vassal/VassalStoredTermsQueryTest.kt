package opensamguk.gameapi.court.vassal

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import opensamguk.gameapi.owner.GeneralResolver
import opensamguk.gameapi.read.GameKvReadRepository
import opensamguk.gameapi.read.GeneralReadEntity
import opensamguk.gameapi.read.GeneralReadRepository
import opensamguk.gameapi.read.WorldStateReadEntity
import opensamguk.gameapi.read.WorldStateReadRepository
import opensamguk.infra.entity.GameKvEntity
import opensamguk.logic.economy.Resources
import opensamguk.logic.vassal.VassalAutonomy
import opensamguk.logic.vassal.VassalContract
import opensamguk.logic.vassal.VassalDiplomacyRight
import opensamguk.logic.vassal.VassalState
import opensamguk.logic.vassal.VassalStateCodec
import opensamguk.logic.vassal.VassalTributeReceipt
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import java.util.Optional
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VassalStoredTermsQueryTest {
    private val resolver = mock(GeneralResolver::class.java)
    private val worlds = mock(WorldStateReadRepository::class.java)
    private val gameKv = mock(GameKvReadRepository::class.java)
    private val generals = mock(GeneralReadRepository::class.java)
    private val reader = VassalStoredTermsQuery(resolver, VassalStoredTermsReader(worlds, gameKv, generals))

    private fun owned(nationId: Int = 7, worldId: Int = 1) {
        val actor = GeneralReadEntity(id = 1, worldId = worldId, userId = "10", nationId = nationId)
        `when`(resolver.resolve(10)).thenReturn(GeneralResolver.ResolvedGeneral(actor, 0, 0, nationId, 1))
        `when`(worlds.findProcessWorld()).thenReturn(WorldStateReadEntity(
            id = 1, currentYear = 196, currentMonth = 1, currentPhase = 1,
        ))
    }

    private fun contract(id: String = "contract-1", vassalId: Int = 2, nationId: Int = 7) = VassalContract(
        id, 1, vassalId, nationId, linkedSetOf(101, 100), 20, 80,
        linkedSetOf(VassalAutonomy.TAX_ALLOCATION, VassalAutonomy.COUNTY_POLICY),
        VassalDiplomacyRight.WITH_APPROVAL, emptySet(), 60, 12,
    )

    private fun receipt(id: String, month: Int) = VassalTributeReceipt(
        id, 196, month, Resources(money = 20), Resources(money = 15), Resources(money = 5),
    )

    private fun persisted(raw: String, worldId: Int? = 1) {
        `when`(gameKv.findByTableAndNamespaceAndKey("game_env", "game_env", VassalStateCodec.META_KEY))
            .thenReturn(GameKvEntity("game_env", "game_env", VassalStateCodec.META_KEY, raw, worldId))
    }

    @Test
    fun `persisted same nation terms and tribute amounts survive the read projection`() {
        owned()
        val state = VassalState(listOf(contract()), listOf(receipt("contract-1", 2), receipt("contract-1", 1)))
        persisted(VassalStateCodec.encode(state))
        `when`(generals.findById(2)).thenReturn(Optional.of(
            GeneralReadEntity(id = 2, worldId = 1, nationId = 7, name = "봉신"),
        ))
        val snapshot = reader.read(1, 10)
        assertEquals(StoredVassalTermsStatus.READY, snapshot.status)
        assertEquals(196, snapshot.now!!.year)
        val row = snapshot.contracts.single()
        assertEquals("contract-1", row.contractId)
        assertEquals("봉신", row.vassalName)
        assertEquals(listOf(100, 101), row.fiefCountyIds)
        assertEquals(20, row.tributePercent)
        assertEquals(80, row.reinforcementTroops)
        assertEquals(VassalDiplomacyRight.WITH_APPROVAL, row.diplomacyRight)
        assertEquals(60, row.loyalty)
        assertEquals(listOf(1, 2), row.tributeHistory.map { it.month })
        assertEquals(Resources(money = 15), row.tributeHistory.first().paid)
        assertEquals(Resources(money = 5), row.tributeHistory.first().unpaid)
    }

    @Test
    fun `terms projection preserves lifecycle facts without interpreting the Long time basis`() {
        val ended = contract().copy(expiresTurn = 13, endedTurn = 14)
        val future = contract("contract-2", 3).copy(signedTurn = Long.MAX_VALUE)
        val rows = VassalStoredTermsView.project(VassalState(listOf(future, ended), emptyList()), 7, emptyMap())
        assertEquals(listOf("contract-1", "contract-2"), rows.map { it.contractId })
        assertEquals(13L, rows[0].expiresTurn)
        assertEquals(14L, rows[0].endedTurn)
        assertEquals(Long.MAX_VALUE, rows[1].signedTurn)
    }

    @Test
    fun `another nation does not contribute terms receipts or a name lookup`() {
        owned()
        val other = contract("other", 3, 8)
        persisted(VassalStateCodec.encode(VassalState(listOf(other), listOf(receipt("other", 1)))))
        assertTrue(reader.read(1, 10).contracts.isEmpty())
        verifyNoInteractions(generals)
    }

    @Test
    fun `missing persisted row differs from a valid persisted empty state`() {
        owned()
        assertEquals(StoredVassalTermsStatus.NOT_SEEDED, reader.read(1, 10).status)
        persisted(VassalStateCodec.encode(VassalState(emptyList(), emptyList())))
        val empty = reader.read(1, 10)
        assertEquals(StoredVassalTermsStatus.READY, empty.status)
        assertTrue(empty.contracts.isEmpty())
        verifyNoInteractions(generals)
    }

    @Test
    fun `unsupported or malformed persisted state is unavailable`() {
        owned()
        for (raw in listOf("{}", "null", "{", "{\"version\":9,\"contracts\":[],\"receipts\":[]}")) {
            persisted(raw)
            val snapshot = reader.read(1, 10)
            assertEquals(StoredVassalTermsStatus.UNAVAILABLE, snapshot.status)
            assertTrue(snapshot.contracts.isEmpty())
        }
        verifyNoInteractions(generals)
    }

    @Test
    fun `requested general must be the resolved live owned general`() {
        owned()
        assertFailsWith<VassalTermsForbidden> { reader.read(2, 10) }
        assertFailsWith<VassalTermsForbidden> { reader.read(1, 11) }
        verifyNoInteractions(worlds, gameKv, generals)
    }

    @Test
    fun `invalid account identifiers do not reach the ownership resolver`() {
        for (userId in listOf(0L, -1L, Int.MAX_VALUE.toLong() + 1)) {
            assertFailsWith<VassalTermsForbidden> { reader.read(1, userId) }
        }
        verifyNoInteractions(resolver, worlds, gameKv, generals)
    }

    @Test
    fun `world and nation readiness precede the stored terms query`() {
        owned(worldId = 2)
        assertEquals(StoredVassalTermsStatus.UNAVAILABLE, reader.read(1, 10).status)
        owned(nationId = 0)
        assertEquals(StoredVassalTermsStatus.UNAVAILABLE, reader.read(1, 10).status)
        verifyNoInteractions(gameKv, generals)
    }

    @Test
    fun `a row outside the process world is unavailable`() {
        owned()
        persisted(VassalStateCodec.encode(VassalState(listOf(contract()), emptyList())), worldId = 2)
        assertEquals(StoredVassalTermsStatus.UNAVAILABLE, reader.read(1, 10).status)
        verifyNoInteractions(generals)
    }

    @Test
    fun `display name is nullable when its current world or nation differs`() {
        owned()
        persisted(VassalStateCodec.encode(VassalState(listOf(contract()), emptyList())))
        for (person in listOf(
            GeneralReadEntity(id = 2, worldId = 2, nationId = 7, name = "다른 월드"),
            GeneralReadEntity(id = 2, worldId = 1, nationId = 8, name = "다른 세력"),
            GeneralReadEntity(id = 2, worldId = 1, nationId = 7, name = " "),
        )) {
            `when`(generals.findById(2)).thenReturn(Optional.of(person))
            assertNull(reader.read(1, 10).contracts.single().vassalName)
        }
        verify(gameKv, org.mockito.Mockito.times(3))
            .findByTableAndNamespaceAndKey("game_env", "game_env", VassalStateCodec.META_KEY)
    }

    @Test
    fun `unknown display name and absent lifecycle markers remain explicit nulls`() {
        val row = VassalStoredTermsView.project(VassalState(listOf(contract()), emptyList()), 7, emptyMap()).single()
        val mapper = ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL)
        val json = mapper.valueToTree<JsonNode>(row)
        for (key in listOf("vassalName", "expiresTurn", "endedTurn")) {
            assertTrue(json.has(key))
            assertTrue(json.get(key).isNull)
        }
    }
}
