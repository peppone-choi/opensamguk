package opensamguk.engine.v2

import opensamguk.common.wire.CityGarrisonRecruit
import opensamguk.common.wire.CommandLifecycleResult
import opensamguk.common.wire.TurnDaemonCommandResult
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.PerTurnOverlay
import opensamguk.infra.persistence.CommandInboxRepository

/**
 * OPENSAM-153 (v2 R4) — 도시병사 보충(`v2GarrisonRecruit`) 핸들러. 도메인 규칙은 [recruitDecision]
 * (순수 함수, draw 0)이 갖고 여기서는 월드 조회·소속 검사·적용만 한다.
 *
 * **로그를 남기지 않는다.** v2 인테이크 결과는 승인된 result-poll(OPENSAM-13/135) 계약으로
 * 회신되며 별도 사용자 로그를 정의하지 않았다. v1 동결 회귀는 v2 로그 설계를 제약하지 않는다
 * (ADR-LITE-042).
 *
 * ## Why [CommandLifecycleResult] instead of a new result type
 *
 * `TurnDaemonCommandResult.kt` is a retained frozen regression surface, and its `TurnDaemonCommandResultSerializer.
 * selectSerializer` dispatches on a **closed whitelist** of `type` strings — an unregistered type
 * string throws `IllegalArgumentException` at flush/serialize time, not at compile time. Reusing
 * `CommandLifecycleResult` with `type = "executionApplied"/"executionRejected"` and
 * `commandKind = IMMEDIATE` needs no whitelist edit because both strings are already registered
 * (`COMMAND_LIFECYCLE_TYPES` in `TurnDaemonCommandResult.kt`) — the exact shape
 * `opensamguk.engine.intake.ProfileIconSyncHandler` already uses for its own typed-intake result.
 */
class V2GarrisonRecruitHandler(
    private val world: InMemoryTurnWorld,
    private val recorder: ChangeRecorder,
    private val ledger: V2CityLedgerStore,
) {
    fun handle(command: CityGarrisonRecruit): TurnDaemonCommandResult {
        val general = world.getGeneralById(command.generalId)
            ?: return rejected(command, "장수를 찾을 수 없습니다.")
        val city = world.getCityById(command.cityId)
            ?: return rejected(command, "도시를 찾을 수 없습니다.")
        if (general.cityId != command.cityId) {
            return rejected(command, "다른 도시의 병사를 보충할 수 없습니다.")
        }
        if (city.nationId == 0 || city.nationId != general.nationId) {
            return rejected(command, "자국 도시가 아닙니다.")
        }

        val preLogic = PerTurnOverlay.toLogicCity(city)
        val ledgerGold = ledger.entry(world.worldId, city.id).gold

        return when (
            val decision = recruitDecision(
                amount = command.amount,
                leadership = general.stats.leadership,
                cityPopulation = preLogic.population,
                cityTrust = preLogic.trust,
                ledgerGold = ledgerGold,
            )
        ) {
            is V2RecruitDecision.Denied -> rejected(command, decision.reason)
            is V2RecruitDecision.Applied -> {
                ledger.adjust(world.worldId, recorder, city.id, goldDelta = -decision.goldCost, garrisonDelta = decision.amount)

                // 적용 경로는 `PersonnelHandler.applyCity`와 동형이다: 엔진 City를 먼저 만들고
                // 양쪽을 `toLogicCity`로 변환해 diff 한다. 논리 City를 직접 copy 하면 `trust`는
                // 컬럼 diff 에만 잡히고 meta jsonb 의 trust 는 옛 값 그대로 남아 두 값이 갈린다.
                //
                // `applyCityDirtyFree` — `updateCity`가 아니다. `updateCity`는 월드의
                // `dirtyCityIds`를 함께 세워 ChangeRecorder 말고 **두 번째 dirty 원천**을 만든다
                // (설계 Risk #4: 조용한 flush 분기). v2도 예외가 아니다.
                val next = city.copy(
                    population = decision.popAfter,
                    meta = LinkedHashMap(city.meta).apply { put("trust", decision.trustAfter) },
                )
                recorder.diffCity(preLogic, PerTurnOverlay.toLogicCity(next))
                world.applyCityDirtyFree(next)

                applied(command)
            }
        }
    }

    companion object {
        const val ACTION_CODE = "v2GarrisonRecruit"

        internal fun applied(command: CityGarrisonRecruit): TurnDaemonCommandResult =
            CommandLifecycleResult(
                type = "executionApplied",
                ok = true,
                commandKind = CommandInboxRepository.CommandKind.IMMEDIATE.name,
                actionCode = ACTION_CODE,
                generalId = command.generalId,
                turnIdx = 0,
            )

        internal fun rejected(command: CityGarrisonRecruit, reason: String): TurnDaemonCommandResult =
            CommandLifecycleResult(
                type = "executionRejected",
                ok = false,
                commandKind = CommandInboxRepository.CommandKind.IMMEDIATE.name,
                actionCode = ACTION_CODE,
                generalId = command.generalId,
                turnIdx = 0,
                reason = reason,
            )

        /**
         * Fail-closed deny for a world with no v2 city ledger bean (v2 sandbox gate off). Returning
         * `null` here would leave the request PENDING forever — the FE result-poll (OPENSAM-13/135)
         * never sees RESOLVED for a null dispatch, so this must be an explicit deny, not a silent drop.
         */
        fun unavailable(command: CityGarrisonRecruit): TurnDaemonCommandResult =
            rejected(command, "v2 도시 원장이 없는 월드입니다.")
    }
}
