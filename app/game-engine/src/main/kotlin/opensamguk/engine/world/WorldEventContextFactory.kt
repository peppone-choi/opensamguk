package opensamguk.engine.world

import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.v2.V2CityLedgerStore
import opensamguk.infra.read.ArchiveHistoryReader
import opensamguk.infra.read.BettingRepository
import opensamguk.infra.read.GameKvRepository
import opensamguk.infra.read.InheritanceRepository
import opensamguk.infra.read.StatisticSnapshotReader
import opensamguk.logic.event.DeleteEventContext
import opensamguk.logic.event.EventActionContext
import opensamguk.logic.event.EventStore
import opensamguk.logic.event.LightActionWorld
import opensamguk.logic.stats.GeneralActionPipeline
import opensamguk.logic.world.ActiveWorldMap
import opensamguk.logic.world.AssignGeneralSpecialityAction
import opensamguk.logic.world.BlockScoutAction
import opensamguk.logic.world.InvaderEndingContext
import opensamguk.logic.world.MergeInheritWorld
import opensamguk.logic.world.SpatialSupplyNetwork
import opensamguk.logic.world.RaiseDisasterAction
import opensamguk.logic.world.UnblockScoutAction

/**
 * 월간 world-event 디스패치용 [EventActionContext] 팩토리.
 *
 * 엔진 월간 정산(MonthlyPipeline → EventDispatcher)에서 도는 world-event leaf는 **두 패턴**으로
 * 컨텍스트를 받는다:
 *  1. **cast-ctx** (UpdateCitySupply / UpdateNationLevel / ProcessIncome / ProcessWarIncome /
 *     ProcessSemiAnnual / RandomizeCityTradeRate / ProvideNPCTroopLeader): `ctx as? XContext ?: throw`
 *     → ctx가 [WorldActionContext]여야 한다. 아니면 **크래시**(prod 턴 동결의 직접 원인).
 *  2. **env-read** (RaiseDisasterAction / AssignGeneralSpecialityAction / MergeInheritPointRank / light actions /
 *     DeleteEvent): `env[specialityWorld]` / `env[disasterWorld]` / `env[mergeInheritWorld]` /
 *     `env[lightActionWorld]` / `env[hiddenSeed]` / `env[startYear]` / `env[eventStore]`를 읽는다.
 *     없으면 **무음 no-op**(재해·특기·유산병합·이벤트삭제가 조용히 미실행).
 *
 * 이 팩토리는 ① [WorldActionContext] 하나를 만들어 cast-ctx leaf에 그대로 주고, ② 같은 인스턴스를
 * env-read leaf가 읽는 world-view 키(+ hiddenSeed/startYear/cityConst/eventStore)로 env에 심는다.
 * WorldActionContext는 cast-ctx leaf의 인터페이스(SpecialityWorldView/DisasterWorldView/
 * MergeInheritWorld/LightActionWorld …)를 모두 구현하므로 단일 인스턴스가 두 패턴을 동시에 만족한다.
 *
 * env는 [opensamguk.logic.event.EventDispatcher]가 디스패치당 1회 만들고 `currentEventID`를 in-place로
 * 갱신하므로, 팩토리가 받은 가변 env에 그대로 심으면 row별 갱신이 ctx에 반영된다.
 */
object WorldEventContextFactory {

    fun create(
        world: InMemoryTurnWorld,
        recorder: ChangeRecorder,
        pipeline: GeneralActionPipeline,
        hiddenSeed: String,
        startYear: Int,
        eventStore: EventStore,
        archiveHistoryReader: ArchiveHistoryReader? = null,
        statisticSnapshotReader: StatisticSnapshotReader? = null,
        gameKvRepository: GameKvRepository? = null,
        bettingRepository: BettingRepository? = null,
        inheritanceRepository: InheritanceRepository? = null,
        lockGame: () -> Boolean = { false },
        unlockGame: () -> Unit = {},
        spatialSupplyNetworkProvider: () -> SpatialSupplyNetwork? = { null },
        // OPENSAM-151 — v2 도시 원장. v2 샌드박스 게이트가 꺼져 있으면 null(= v1 프로덕션 기본값).
        v2CityLedger: V2CityLedgerStore? = null,
    ): (MutableMap<String, Any?>) -> EventActionContext {
        val state = world.getState()
        val cityConst = ActiveWorldMap.requireVariant(state.config, state.meta)
        return { env ->
            // 스칼라/스토어 env 키 (env-read leaf가 직접 읽음).
            // 케이싱 분열 주의: 역사 PHP 기준 (ADR-LITE-042; 현재 제품 정본 아님) 키는 소문자 `startyear`(GameConstBase getValues + DateRelative.php +
            // AssignGeneralSpeciality/RaiseDisaster/UpdateNationLevel .php 전부). 그런데 Kotlin migration이
            // 두 갈래로 갈렸다 — EventCondition(DateRelative)·CheSeonjeonpogo는 소문자 `startyear`를 읽고,
            // event-action leaf(AssignGeneralSpeciality.kt:172 / RaiseDisaster.kt:222)와 command 경로는
            // camelCase `startYear`를 읽는다(camelCase 미존재 시 `?: return` → 무음 no-op). MONTH 이벤트의
            // DateRelative 조건이 소문자 키를 요구하므로 camelCase만 심으면 EventCondition이 throw해 매 월경계
            // 크래시-루프(= prod 턴 동결)였다. seam이 두 패턴을 모두 먹이도록 **두 키 다** 심는다.
            env["startyear"] = startYear // 역사 PHP 기준 (ADR-LITE-042; 현재 제품 정본 아님) 키 (EventCondition DateRelative가 읽음)
            env["startYear"] = startYear // camelCase 관행 (event-action leaf + command 경로 호환)
            env[AssignGeneralSpecialityAction.ENV_HIDDEN_SEED] = hiddenSeed // "hiddenSeed"
            env["cityConst"] = cityConst                              // WorldActionContext.cityConst() 가 읽음
            env[DeleteEventContext.ENV_KEY] = eventStore              // "eventStore"

            // cast-ctx leaf가 그대로 받는 단일 컨텍스트. env를 참조로 감싸므로 currentEventID 갱신이 보인다.
            val wctx = WorldActionContext(
                env,
                world,
                recorder,
                pipeline,
                archiveHistoryReader = archiveHistoryReader,
                statisticSnapshotReader = statisticSnapshotReader,
                gameKvRepository = gameKvRepository,
                bettingRepository = bettingRepository,
                inheritanceRepository = inheritanceRepository,
                lockGame = lockGame,
                unlockGame = unlockGame,
                spatialSupplyNetworkProvider = spatialSupplyNetworkProvider,
                v2CityLedger = v2CityLedger,
            )

            // env-read leaf의 world-view 키 (모두 같은 wctx — WorldActionContext가 전부 구현).
            env[AssignGeneralSpecialityAction.ENV_WORLD] = wctx // "specialityWorld"
            env[RaiseDisasterAction.ENV_WORLD] = wctx           // "disasterWorld"
            env[BlockScoutAction.ENV_WORLD] = wctx
            env[UnblockScoutAction.ENV_WORLD] = wctx
            env[MergeInheritWorld.ENV_KEY] = wctx         // "mergeInheritWorld"
            env[LightActionWorld.ENV_KEY] = wctx          // "lightActionWorld"
            env[InvaderEndingContext.ENV_KEY] = wctx      // "invaderEndingWorld" — InvaderEnding leaf(침략자 종료) 시임
            wctx
        }
    }
}
