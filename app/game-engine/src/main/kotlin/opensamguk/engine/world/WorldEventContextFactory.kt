package opensamguk.engine.world

import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.logic.event.DeleteEventContext
import opensamguk.logic.event.EventActionContext
import opensamguk.logic.event.EventStore
import opensamguk.logic.event.LightActionWorld
import opensamguk.logic.stats.GeneralActionPipeline
import opensamguk.logic.world.AssignGeneralSpecialityAction
import opensamguk.logic.world.CityConstRegistry
import opensamguk.logic.world.MergeInheritWorld
import opensamguk.logic.world.RaiseDisasterAction

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

    /** che/miniche만 등록돼 있으므로 미등록 맵은 che로 폴백(표시 외 정산은 prod=che). */
    fun create(
        world: InMemoryTurnWorld,
        recorder: ChangeRecorder,
        pipeline: GeneralActionPipeline,
        hiddenSeed: String,
        startYear: Int,
        mapName: String,
        eventStore: EventStore,
    ): (MutableMap<String, Any?>) -> EventActionContext {
        val cityConst = CityConstRegistry.find(mapName) ?: CityConstRegistry.of("che")
        return { env ->
            // 스칼라/스토어 env 키 (env-read leaf가 직접 읽음).
            env["startYear"] = startYear
            env[AssignGeneralSpecialityAction.ENV_HIDDEN_SEED] = hiddenSeed // "hiddenSeed"
            env["cityConst"] = cityConst                              // WorldActionContext.cityConst() 가 읽음
            env[DeleteEventContext.ENV_KEY] = eventStore              // "eventStore"

            // cast-ctx leaf가 그대로 받는 단일 컨텍스트. env를 참조로 감싸므로 currentEventID 갱신이 보인다.
            val wctx = WorldActionContext(env, world, recorder, pipeline)

            // env-read leaf의 world-view 키 (모두 같은 wctx — WorldActionContext가 전부 구현).
            env[AssignGeneralSpecialityAction.ENV_WORLD] = wctx // "specialityWorld"
            env[RaiseDisasterAction.ENV_WORLD] = wctx           // "disasterWorld"
            env[MergeInheritWorld.ENV_KEY] = wctx         // "mergeInheritWorld"
            env[LightActionWorld.ENV_KEY] = wctx          // "lightActionWorld"
            wctx
        }
    }
}
