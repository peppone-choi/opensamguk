# 와룡전 전투 동결 입력 v1

- Date: 2026-09-27
- Status: draft actor input; campaign producer와 통합 전
- Parent: [실시간 전술 전투 계약](2026-09-27-waryong-realtime-tactical-battle.md), [세션 저장 경계](2026-09-27-waryong-battle-session-store.md)

## 기존 티켓과 버전

최상위 `schemaVersion=1`, `worldId`, `battleId`, `kind`, `ruleSha256`, `catalogSha256`, `terrainSha256`, `seed`, `lockGeneration`, `lockSetRevision`, `joinDeadlineAt`, `deadlineAt`, `participants`, `entityRevisions`, 야전/성새의 `battlefieldId`는 #1001의 `BattleSessionCoordinator` 계약을 그대로 쓴다. 이 필드의 원문 UTF-8 SHA-256은 `battle_ticket.payload_sha256`에 저장한다. 기존 #1001 집중 테스트의 간략 v1 티켓은 저장소 계약만 검사하는 fixture이며, 실행 입력이 아니다. 세션 actor는 아래 `tacticalInput`이 없는 ENCOUNTER/SIEGE 티켓을 시작하지 않는다. 캠페인 producer가 해당 필드를 확정해 넣고 #1001 검증 경계를 강화한 뒤 운영 경로를 연다.

## `tacticalInput` 객체

ENCOUNTER와 SIEGE에는 다음 필드를 요구한다. 정수·문자열은 JSON number/string으로 기록하고 부동소수점은 사용하지 않는다.

| 필드 | 계약 |
|---|---|
| `schemaVersion` | `1` |
| `board.id` | 최상위 `battlefieldId`와 같음, 0–213 |
| `board.tileset` | owner-accepted catalog의 해당 판 tileset과 같음 |
| `board.terrainRowsSha256` | 64개 `terrainRows`를 개행 없이 이어 붙인 정확히 4096개 ASCII `P/F/M/R/W` 바이트의 SHA-256 |
| `attacker`, `defender` | 각각 `commanderGeneralId`와 `retinues` 배열. 배열은 부곡 ID 오름차순, 0–6개 |
| `retinues[]` | `id`, `generalId`, `leadership`, `strength`, `intelligence`, `politics`, `charisma`, `troops`, `kind` (`INFANTRY/ARCHER/CAVALRY`), `training`, `morale`, `fatigue`, `supply`, `accompaniesCorps` |
| `gate` | ENCOUNTER에서는 JSON `null`. SIEGE에서는 `row`, `col`, `hp` 정수. `row/col`은 해당 판의 `W` 칸, `hp>0` |

양측 부곡·장수 ID는 서로도 중복할 수 없다. 부곡은 실제 `troops>0`이고 `accompaniesCorps=true`여야 하며 능력·숙련·사기·피로·군량은 0–100이다. `commanderGeneralId`는 0보다 커야 한다. 자기 측 부곡이 없으면 중앙은 빈다. 주장의 부곡이 있으면 `BattleDeployment.default`가 중앙에 두고 나머지를 원장 순서로 배치한다. 입장 중 변경은 별도 `DEPLOYMENT_SET` 이벤트로만 확정한다. `entityRevisions`는 잠긴 원본 장수·부곡·군단·도시별 revision을 담고, 캠페인 결과 적용 시 다시 검사한다.

전장 catalog 원문 SHA가 티켓 `catalogSha256`과 같아야 한다. 선택된 board ID·tileset·지형 행 SHA도 각각 일치해야 한다. ENCOUNTER는 `FIELD`·`landEligible=true`, SIEGE는 `FORTRESS`·`landEligible=true`다. `ruleSha256`은 버전 원장 리소스 원문과 비교한다. `terrainSha256`은 캠페인 producer가 사용한 han-tiles 정본의 핀이며 actor가 새 지형판을 다시 선택하지 않는다. 수전 판은 이 육상 입력에서 거절한다.

## 개인 조우

`PERSONAL_DUEL`에는 `tacticalInput` 대신 별도 `duelInput.schemaVersion=1`과 양측 `generalId/strength/leadership/morale`를 요구한다. 부곡·전장 ID·전장 지형은 없다. #1000 `TacticalDuel`을 실행하는 별도 actor 절편에서 검증한다. 야전 일기토는 ENCOUNTER의 동결 장수 스탯에서 제안하고, 승인/거절을 이벤트로 기록한다.

## 재생과 미완성 경계

초기 상태는 티켓·핀된 catalog·규칙 원장으로 다시 만들며 사람 조작 측은 처음에 비어 있다. JOINING의 `HUMAN_JOIN`·`DEPLOYMENT_SET`, RUNNING의 승인 명령·AI 인계는 event seq 순서로 checkpoint 이후 재생한다. 체크포인트의 압축 본문은 #1002 코덱으로 복원하고 같은 `TacticalBattle.stateHash`를 강제한다. 손상·누락·다른 pin은 결과를 추측해 만들지 않고 격리 대상으로 둔다. 현재 입력 코덱은 야전/성새의 초기 상태만 구성하며 실제 캠페인 producer, 10Hz 스케줄러, 성벽/사다리/계책/일기토 이벤트, 결과 flush는 후속 절편이다.
