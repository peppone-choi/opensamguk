package com.opensam.engine

import com.opensam.entity.City
import com.opensam.entity.Nation
import com.opensam.entity.WorldState
import com.opensam.repository.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*

class UnificationServiceTest {

    private lateinit var service: UnificationService
    private lateinit var nationRepository: NationRepository
    private lateinit var cityRepository: CityRepository
    private lateinit var generalRepository: GeneralRepository
    private lateinit var appUserRepository: AppUserRepository
    private lateinit var hallOfFameRepository: HallOfFameRepository
    private lateinit var emperorRepository: EmperorRepository
    private lateinit var oldNationRepository: OldNationRepository
    private lateinit var oldGeneralRepository: OldGeneralRepository
    private lateinit var gameHistoryRepository: GameHistoryRepository
    private lateinit var messageRepository: MessageRepository

    @Suppress("UNCHECKED_CAST")
    private fun <T> anyNonNull(): T = any<T>() as T

    @BeforeEach
    fun setUp() {
        nationRepository = mock(NationRepository::class.java)
        cityRepository = mock(CityRepository::class.java)
        generalRepository = mock(GeneralRepository::class.java)
        appUserRepository = mock(AppUserRepository::class.java)
        hallOfFameRepository = mock(HallOfFameRepository::class.java)
        emperorRepository = mock(EmperorRepository::class.java)
        oldNationRepository = mock(OldNationRepository::class.java)
        oldGeneralRepository = mock(OldGeneralRepository::class.java)
        gameHistoryRepository = mock(GameHistoryRepository::class.java)
        messageRepository = mock(MessageRepository::class.java)

        service = UnificationService(
            nationRepository,
            cityRepository,
            generalRepository,
            appUserRepository,
            hallOfFameRepository,
            emperorRepository,
            oldNationRepository,
            oldGeneralRepository,
            gameHistoryRepository,
            messageRepository,
        )
    }

    private fun createWorld(isUnited: Int = 0): WorldState {
        return WorldState(
            id = 1,
            name = "테스트서버",
            scenarioCode = "test",
            currentYear = 200,
            currentMonth = 6,
            tickSeconds = 300,
            config = mutableMapOf("isUnited" to isUnited),
        )
    }

    @Test
    fun `checkAndSettleUnification skips if already united`() {
        val world = createWorld(isUnited = 1)

        service.checkAndSettleUnification(world)

        verify(nationRepository, never()).findByWorldId(anyLong())
    }

    @Test
    fun `checkAndSettleUnification skips if multiple active nations`() {
        val world = createWorld()
        val nations = listOf(
            Nation(id = 1, worldId = 1, name = "위", color = "#FF0000", level = 7),
            Nation(id = 2, worldId = 1, name = "촉", color = "#00FF00", level = 5),
        )
        `when`(nationRepository.findByWorldId(1L)).thenReturn(nations)

        service.checkAndSettleUnification(world)

        assertEquals(0, world.config["isUnited"], "Should not mark as united")
    }

    @Test
    fun `checkAndSettleUnification skips if single nation but not all cities owned`() {
        val world = createWorld()
        val nations = listOf(
            Nation(id = 1, worldId = 1, name = "위", color = "#FF0000", level = 7),
            Nation(id = 2, worldId = 1, name = "촉", color = "#00FF00", level = 0),
        )
        val cities = listOf(
            City(id = 1, worldId = 1, name = "낙양", nationId = 1),
            City(id = 2, worldId = 1, name = "성도", nationId = 0),
        )
        `when`(nationRepository.findByWorldId(1L)).thenReturn(nations)
        `when`(cityRepository.findByWorldId(1L)).thenReturn(cities)

        service.checkAndSettleUnification(world)

        assertEquals(0, world.config["isUnited"], "Should not mark as united when cities unowned")
    }

    @Test
    fun `checkAndSettleUnification marks united when single active nation owns all cities`() {
        val world = createWorld()
        val nations = listOf(
            Nation(id = 1, worldId = 1, name = "위", color = "#FF0000", level = 7),
            Nation(id = 2, worldId = 1, name = "촉", color = "#00FF00", level = 0),
        )
        val cities = listOf(
            City(id = 1, worldId = 1, name = "낙양", nationId = 1),
            City(id = 2, worldId = 1, name = "허창", nationId = 1),
        )
        `when`(nationRepository.findByWorldId(1L)).thenReturn(nations)
        `when`(cityRepository.findByWorldId(1L)).thenReturn(cities)
        `when`(generalRepository.findByWorldId(1L)).thenReturn(emptyList())
        `when`(messageRepository.findByWorldIdAndMailboxCodeAndDestIdOrderBySentAtDesc(anyLong(), anyString(), anyLong()))
            .thenReturn(emptyList())
        `when`(messageRepository.save(anyNonNull())).thenAnswer { it.arguments[0] }
        `when`(gameHistoryRepository.findByServerId(anyString())).thenReturn(null)
        `when`(gameHistoryRepository.save(anyNonNull())).thenAnswer { it.arguments[0] }
        `when`(gameHistoryRepository.count()).thenReturn(0)
        `when`(oldNationRepository.findByServerIdAndNation(anyString(), anyLong())).thenReturn(null)
        `when`(oldNationRepository.save(anyNonNull())).thenAnswer { it.arguments[0] }
        `when`(oldNationRepository.findByServerId(anyString())).thenReturn(emptyList())
        `when`(emperorRepository.save(anyNonNull())).thenAnswer { it.arguments[0] }

        service.checkAndSettleUnification(world)

        assertEquals(2, world.config["isUnited"], "Should mark as united with value 2")
        verify(messageRepository).save(anyNonNull())
    }

    @Test
    fun `checkAndSettleUnification skips when no cities exist`() {
        val world = createWorld()
        val nations = listOf(
            Nation(id = 1, worldId = 1, name = "위", color = "#FF0000", level = 7),
        )
        `when`(nationRepository.findByWorldId(1L)).thenReturn(nations)
        `when`(cityRepository.findByWorldId(1L)).thenReturn(emptyList())

        service.checkAndSettleUnification(world)

        assertEquals(0, world.config["isUnited"], "Should not unite when no cities")
    }
}
