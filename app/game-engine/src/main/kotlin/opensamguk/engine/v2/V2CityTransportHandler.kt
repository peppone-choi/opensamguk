package opensamguk.engine.v2

import opensamguk.common.wire.CityTransport
import opensamguk.common.wire.CommandLifecycleResult
import opensamguk.common.wire.TurnDaemonCommandResult
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.infra.persistence.CommandInboxRepository
import opensamguk.logic.v2.command.V2CityTransportArgs
import opensamguk.logic.v2.command.V2CityTransportContext
import opensamguk.logic.v2.command.V2CityTransportDecision
import opensamguk.logic.world.CalcCityDistance
import opensamguk.logic.world.ActiveWorldMap
import opensamguk.logic.world.CityConstRegistry
import opensamguk.logic.v2.command.V2CommandRegistry
import opensamguk.logic.v2.command.decideCityTransport

/**
 * OPENSAM-154 (v2 R5) — 도시 자원 수송(`v2CityTransport`) 핸들러.
 *
 * 도메인 규칙은 [transportDecision](순수 함수, draw 0)이 갖고 여기서는 월드 조회·소속 검사·인접 판정·
 * 원장 델타 적용만 한다. **로그를 남기지 않는다** — 이 v2 명령의 승인된 result-poll 계약이 별도
 * 사용자 로그를 정의하지 않았기 때문이다. v1 동결 회귀는 이 v2 설계를 제약하지 않는다(ADR-LITE-042).
 *
 * 인접 판정은 `logic`의 [CalcCityDistance]를 **호출만** 한다. `logic/`은 T1 동결이라 한 줄도 고치지
 * 않았고, `CityConst.path` 인접은 기존 동결 회귀로 보호된 현재 구현 값이다. 변경 시 현재 spec과
 * 회귀 테스트로 검토하며 PHP/golden 선행 조건을 두지 않는다.
 *
 * 결과 타입으로 `CommandLifecycleResult`(`executionApplied`/`executionRejected`)를 재사용하는 이유는
 * [V2GarrisonRecruitHandler]에 적은 것과 같다 — `TurnDaemonCommandResultSerializer`가 닫힌
 * 화이트리스트라 신규 결과 타입은 T1 편집 없이는 불가능하다.
 *
 * **출발·도착 원장 델타는 같은 [ChangeRecorder]에 실린다** = `JdbcFlushExecutor`의 같은 트랜잭션에서
 * 커밋된다(티켓 DoD "같은 트랜잭션"). 한쪽만 반영되는 상태는 만들어지지 않는다.
 */
class V2CityTransportHandler(
    private val world: InMemoryTurnWorld,
    private val recorder: ChangeRecorder,
    private val ledger: V2CityLedgerStore,
) {
    fun handle(command: CityTransport): TurnDaemonCommandResult {
        val general = world.getGeneralById(command.generalId)
        val from = world.getCityById(command.fromCityId)
        val to = world.getCityById(command.toCityId)
        val resources = from?.let { ledger.entry(world.worldId, it.id) } ?: V2CityLedgerEntry.EMPTY
        val decision = decideCityTransport(
            V2CityTransportArgs(
                command.fromCityId,
                command.toCityId,
                command.gold,
                command.rice,
                command.garrison,
                command.routeRevision,
            ),
            V2CityTransportContext(
                generalCityId = general?.cityId,
                generalNationId = general?.nationId,
                escortCrew = general?.crew,
                fromNationId = from?.nationId,
                toNationId = to?.nationId,
                hopDistance = if (from == null || to == null) {
                    null
                } else {
                    val state = world.getState()
                    runCatching {
                        CityConstRegistry.of(ActiveWorldMap.requireName(state.config, state.meta))
                    }.getOrNull()?.let { map ->
                        CalcCityDistance.calcCityDistance(from.id, to.id, cityConst = map)
                    }
                },
                fromGold = resources.gold,
                fromRice = resources.rice,
                fromGarrison = resources.garrison,
            ),
        )

        return when (decision) {
            is V2CityTransportDecision.Denied -> rejected(command, decision.reason, decision.code)
            is V2CityTransportDecision.Applied -> {
                val resolvedFrom = checkNotNull(from)
                val resolvedTo = checkNotNull(to)
                ledger.adjust(
                    world.worldId, recorder, resolvedFrom.id,
                    goldDelta = -decision.gold, riceDelta = -decision.rice, garrisonDelta = -decision.garrison,
                )
                ledger.adjust(
                    world.worldId, recorder, resolvedTo.id,
                    goldDelta = decision.gold, riceDelta = decision.rice, garrisonDelta = decision.garrison,
                )
                // 묘섭 `:366` "수송하는 장수는 해당 도시로 이동하지 않습니다." — 장수 상태는 건드리지 않는다.
                applied(command)
            }
        }
    }

    companion object {
        const val ACTION_CODE = "v2CityTransport"

        internal fun applied(command: CityTransport): TurnDaemonCommandResult =
            CommandLifecycleResult(
                type = "executionApplied",
                ok = true,
                commandKind = CommandInboxRepository.CommandKind.IMMEDIATE.name,
                actionCode = ACTION_CODE,
                generalId = command.generalId,
                turnIdx = 0,
                canonicalCommandId = V2CommandRegistry.cityTransportSchema.canonicalId,
                replayEvent = V2CommandRegistry.cityTransportSchema.replayEvent,
                routeRevision = command.routeRevision,
            )

        internal fun rejected(command: CityTransport, reason: String, code: String? = null): TurnDaemonCommandResult =
            CommandLifecycleResult(
                type = "executionRejected",
                ok = false,
                commandKind = CommandInboxRepository.CommandKind.IMMEDIATE.name,
                actionCode = ACTION_CODE,
                generalId = command.generalId,
                turnIdx = 0,
                reason = reason,
                code = code,
                canonicalCommandId = V2CommandRegistry.cityTransportSchema.canonicalId,
                replayEvent = V2CommandRegistry.cityTransportSchema.replayEvent,
                routeRevision = command.routeRevision,
            )

        /** 원장이 없는 월드의 fail-closed deny — `null`을 돌려주면 FE 폴링이 PENDING에 갇힌다(R4와 동일). */
        fun unavailable(command: CityTransport): TurnDaemonCommandResult =
            rejected(command, "v2 도시 원장이 없는 월드입니다.")
    }
}
