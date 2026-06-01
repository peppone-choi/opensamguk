package opensamguk.engine.auction

import opensamguk.common.wire.TurnDaemonCommand
import opensamguk.common.wire.TurnDaemonCommandResult

/**
 * TurnDaemon 명령 핸들러 인터페이스 — game-engine 레이어의 명령 처리 계약.
 *
 * 각 구현체는 특정 [TurnDaemonCommand] 하위 타입을 처리하며, [handle] 메서드는
 * 명령 실행 후 [TurnDaemonCommandResult]를 반환한다.
 *
 * @param T 처리할 명령 타입
 */
interface TurnDaemonCommandHandler<T : TurnDaemonCommand> {

    /**
     * 명령을 처리하고 결과를 반환한다.
     *
     * @param command 처리할 명령
     * @return 명령 처리 결과 (Ok 또는 Fail)
     */
    suspend fun handle(command: T): TurnDaemonCommandResult
}
