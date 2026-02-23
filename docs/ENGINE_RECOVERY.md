# Engine Recovery & Control (Lane F)

## Crash Recovery 절차

본 엔진은 **메모리 우선(In-Memory First)** 방식으로 동작하며, 주기적으로 DB에 상태를 플러시(Flush)합니다.

### 1. 장애 유형 및 복구 전략

| 장애 유형               | 영향                        | 복구 방법                                                                                                     |
| :---------------------- | :-------------------------- | :------------------------------------------------------------------------------------------------------------ |
| **Turn Cycle 중 Crash** | 메모리 상의 변경사항 손실   | 엔진 재시작 시 DB에서 마지막 상태 로드. `turnTime`이 업데이트되지 않았으므로 동일한 턴이 재실행됨.            |
| **Flush 중 Crash**      | DB 상태가 일부만 업데이트됨 | 각 엔티티 타입별로 `upsert`를 사용하므로 데이터 무결성은 유지됨. 재시작 시 로드하여 이어서 진행.              |
| **DB 연결 장애**        | Flush 실패                  | `EngineService`가 오류를 감지하고 스냅샷을 폐기(`null` 설정). 재시작 또는 다음 루프에서 DB로부터 재로드 시도. |

### 2. 복구 프로세스 (Runbook)

1. **로그 확인**: `Error in turn cycle` 또는 `Failed to flush world` 메시지 확인.
2. **엔진 재시작**: `npm run start:engine` (또는 docker restart).
3. **상태 검증**: 엔진 시작 시 `Loading current world state into memory...` 로그와 로드된 엔티티 개수 확인.
4. **모니터링**: 다음 턴 사이클이 정상적으로 수행되는지 확인.

---

## Safe Stop (안전 종료)

엔진은 `SIGINT`, `SIGTERM` 신호를 수신하면 안전 종료 절차를 수행합니다.

### 종료 절차

1. `stopping` 플래그를 `true`로 설정하여 새로운 턴 사이클 시작을 방지.
2. 현재 진행 중인 턴 사이클이 완료될 때까지 대기 (`mainLoopPromise`).
3. 종료 전 마지막으로 메모리 상태를 DB에 플러시 (`repo.save`).
4. 프로세스 종료.

---

## Restart/Resume 전략

### Checkpoint (체크포인트)

현재 `GameEngine`은 처리량 제한(`TurnRunBudget`)이 있을 경우 `partial` 결과와 `checkpoint`를 반환합니다.

- **Restart/Resume Flow**: `runTurnCycle` 결과가 `partial`인 경우, `EngineService`는 `setImmediate`를 통해 대기 시간 없이 즉시 다음 루프를 실행합니다. 이를 통해 대량의 턴을 처리해야 할 때도 지연 없이 연속적인 처리가 가능합니다.
- **상태 보존**: `partial` 결과가 반환될 때마다 중간 결과가 DB에 플러시되며, `WorldState.meta.checkpoint`에 마지막 처리 지점이 기록됩니다.

### Checkpoint Resume

만약 대규모 업데이트 중 종료되었다면, 재시작 시 `WorldState.meta.checkpoint`에 저장된 정보를 바탕으로 특정 시점부터 재개할 수 있도록 설계되어 있습니다. (현재는 `turnTime` 기반 필터링만으로도 충분히 Resume 효과가 있음)
