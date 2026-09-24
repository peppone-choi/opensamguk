# 운영·복구 매뉴얼

> 상태: 현재 GCP/shared-server 운영 경계 기준
> 마지막 검토: 2026-09-05

## 승인 경계

다음은 각각 별도 승인 대상입니다.

- commit, push, PR, merge
- production workflow dispatch와 deploy
- 서버 reset·delete와 운영 데이터 삭제
- secret 접근·변경
- DB migration 적용과 restore

한 작업의 승인이 다른 작업의 승인을 자동으로 포함하지 않습니다.

## 복구 준비 상태: `UNKNOWN / blocked`

[서버 냉간 백업·격리 복원 절차](./game-server-recovery.md)는 서버 env·정확한 이미지·PostgreSQL/Redis 전체
볼륨의 capture/verify 명령과 보존·교체 절차를 제공합니다. 도구의 로컬 테스트는 운영 복구 증거가 아닙니다.
각 작업의 실제 bundle에 대해 저장소 복원과 원본 fingerprint/count 대조, 이전 이미지의 실제 world rehydrate,
인증 사용자 경로를 확인한 report가 있어야 합니다. 현재 이 관문은 `UNKNOWN / blocked`입니다.

reset/delete는 해당 작업의 관문과 명시적 승인, control-plane lifecycle 정합성이 확보될 때까지 실행하지
않습니다. [reset workflow](../../.github/workflows/reset-game-server.yml)의 존재나 직접 deployer 호출을
Gateway 상태 전이·복구 관문을 우회할 권한으로 해석하지 않습니다.

## 배포 전 체크리스트

1. 대상 저장소, 서버 ID, branch와 immutable tag/SHA를 기록합니다.
2. GitHub CI와 필요한 로컬 검증이 현재 SHA에서 통과했는지 확인합니다.
3. `gcp-prod` runner와 대상 VM/container가 정상인지 확인합니다.
4. disk 용량과 Docker image/volume 여유를 확인합니다.
5. 승인된 DB backup/restore runbook, 보존 범위와 Flyway 현재/목표 버전을 확인합니다. 없으면 중단합니다.
6. server env의 scenario code와 world ID를 값 노출 없이 확인합니다.
7. rollback 가능한 앱 버전과 schema 호환성을 확인합니다.
8. 점검 공지와 관측 담당자를 정합니다.

## 배포 후 체크리스트

### shared source 배포의 idle maintenance admission

`main` source 배포 workflow는 먼저 `/tmp/opensamguk-production.lock`을 잡고, 이미 실행
중인 deployer의 loopback control plane을 조회합니다. 초기 상태가
`maintenance-v1/open`이고 `POST /maintenance/enter-if-idle`가 새 `drained` window를 원자적으로
내준 경우에만 Git 동기화, socket-proxy/deployer 교체, shared env/image/container 변경을
시작합니다. 활성 lifecycle 작업, 기존의 닫힌 maintenance, 구형 controller, 잘못된
응답, transport 장애는 모두 변경 전에 fail-closed로 중단합니다. 자동 workflow는
기존 작업을 취소하는 `/maintenance/enter`로 fallback하지 않습니다.

첫 업그레이드에서 controller가 `enter-if-idle`을 지원하지 않으면 source workflow가
자체로 controller를 교체하지 않습니다. 인증 HTTP를 위해 구형 deployer CLI를 실행하면
request dispatch 전에 durable recovery가 시작될 수 있으므로, 별도로 검토·승인된
control-plane 초기 업그레이드 절차를 사용합니다. 수동 marker 생성, 취소형 maintenance
entry, 자동 repair로 이 관문을 우회하지 않습니다.

admission 이후에 rollout이나 leave 이전 검증이 하나라도 실패하면 maintenance를 닫힌
채 남겨 운영자가 원인을 조사하게 합니다. 교체된 controller가 여전히 `drained`인지,
deployer→Docker 도달성과 registry, shared/public health, 모든 실행 중 registered game server의
API·engine·web route·daemon recovery/clock 검증이 모두 성공한 뒤에만 workflow
자신이 소유한 window를 leave합니다. leave request의 transport가 실패하거나 응답이
잘못되면 서버에서 leave가 commit되었는지 알 수 없으므로 상태는 `UNKNOWN`입니다. 자동
재시도·repair·open 단정 없이 운영자가 control plane을 조회합니다. 이 배포는 서버별
`IMAGE_TAG`/`WEB_GAME_TAG`를 변경하거나 game service를 시작·중지하거나 rollback image를
광범위하게 prune하지 않습니다.

- nginx `/health`
- gateway-api, game-api, game-engine health
- 로그인 → 로비 → 대상 서버 입장
- read API와 SSE 연결
- 명령 1건의 접수 → terminal 결과 → 권위 read 반영
- `world_state`의 현재 연·월 또는 턴 시각 전진
- 서버별 game-api/game-engine 버전 skew 없음
- 로그에 secret·개인정보 노출 없음

local unit, Testcontainers와 Docker smoke는 production 네트워크·runner·DNS·실데이터 전환을 증명하지 않습니다.

## 활성 월드 지도 정체성 복구

### 독립 전장 위치(V59)

V59는 `general_spatial_position`에 전장 ID, 전장 카탈로그 해시, 귀환 도시를 함께 저장합니다.
기존 위치는 세 값이 모두 NULL인 상태로 유지되며 월드 초기화나 도시 재생성이 필요하지 않습니다.
전장에 있는 장수도 기존 도시 ID를 보유하지만 도시 주민·수비병으로 집계하지 않습니다.
운영 확인에는 도시 ID만 보지 말고 전장 위치 행을 함께 조회해야 합니다.

API와 엔진은 같은 이미지의 `han-world-v3-battlefields.json`을 사용해야 합니다. 저장된 전장 위치의
해시·물리 노드·귀환 도시가 카탈로그와 다르면 복구를 중단합니다. 전장 주둔자가 있는 상태에서
카탈로그를 수정할 때에는 기존 위치를 새 카탈로그로 이행하는 별도 검증이 필요합니다.
해시만 강제로 바꾸거나 위치 행을 삭제해서 부팅 검증을 우회하지 않습니다.
전장 기능을 모르는 이전 엔진은 주둔 장수를 도시 주민으로 처리할 수 있으므로, 전장 위치가 생성된
뒤의 롤백은 스키마 호환성만으로 판단하지 않고 위치 데이터와 앱 버전을 함께 복구해야 합니다.

한 번 seed된 월드에서 `mapName`은 불변의 숫자 city-id 공간을 식별합니다. 생성된 도시의 수나 순서를
바꾸려면 새 버전 지도 키를 사용합니다. 기존 월드를 새 키로 조용히 옮기거나 ordinal id 추측으로 고치지
않습니다.

`han-780-v1`는 과거 780성 한나라 월드의 호환 지도 키입니다. V45는 활성 지도 키가 `han`인 월드만
검사하며, 정확히 `1..780`인 city-id 모양만 `han-780-v1`로 고정합니다. 정확히 `1..774`인 기존 한나라
월드는 변경하지 않습니다. 그 밖의 city 수·최솟값·최댓값 모양은 모호하므로 fail-closed로 중단하며,
ordinal id를 추측해 수리하지 않습니다.

V45 또는 그 앱 버전을 production에 적용하기 전에는 승인된 DB backup과 restore runbook 증거가 반드시
있어야 합니다. 적용 뒤에는 다음 네 신호를 두 번의 관측으로 확인합니다.

- engine health가 `UP`
- `successfulTicks`가 증가
- `consecutiveFailures`가 `0`
- public game time이 전진

V45 뒤 image-only rollback은 안전하지 않습니다. 이전 image와 V45 전 DB backup을 함께 복원하는 승인된
경로만 사용합니다. `ng_games.map`, city/gameplay id를 수동으로 바꾸거나 V45·validator 조건을 약화하지
않습니다.

### 1133 릴리스 제자리 재핀(ADR-LITE-058, 2026-09-17)

`han-world-v3-1133` 번들은 저지 지형 재분류로 제자리에서 재핀됐습니다. 도시·省·관할·인접은 그대로지만 blob 해시가
`StrategicTopology.contentHash` 입력이라 contentHash 가 바뀝니다. **재핀 전 1133 으로 pin 이 박힌 월드는
`province_control`·`general_spatial_position`·`water_zone_control` 의 topology_hash 가 어긋나 로드에 실패합니다.**
이 변경이 든 이미지를 승격하기 전에 해당 서버의 월드 초기화를 같은 작업으로 잡으십시오. 1098 이하 판으로 pin 된
월드는 영향이 없습니다.

## 대표 장애 대응

### Han V3 신규 세계와 V2 저장 세계 구분

`han`과 `han-world-v2`는 기존 774개 도시의 ID 공간입니다. V47의 V2 명칭을 새 도시 원장으로
재해석하지 않습니다. `han-world-v3`는 검토된 781개 route node를 사용하는 **신규 세계 전용** 지도입니다.
역사 시나리오 15개의 새 템플릿이 V3를 가리켜도, 이미 저장된 V2 세계는 그 템플릿으로 재시드하지 않습니다.

- 기존 `han-780-v1` 호환 자산과 V45/V47 migration을 수정하지 않습니다.
- 숫자 ID 수만 맞추거나 `mapName`만 바꾸는 수동 전환은 금지합니다. 지점의 physical ref와 stable
  route key까지 달라질 수 있습니다. 운영 세계의 V3 전환·reset은 별도 승인과 복구 계획이 필요합니다.
- V3 배포 후보는 `build_han_world.py --target han-world-v3 --check`,
  `apply_han_world.py --map han-world-v3 --check`,
  `audit_han_supply_disagreements.py --map han-world-v3 --check`를 모두 통과해야 합니다.
- 공급 보호 원장은 지도별로 구분합니다. V3 원장을 legacy 숫자 ID에 적용하거나 반대로 적용하지 않습니다.
- 수역 overlay는 정확한 land tile SHA와 manifest에 묶입니다. 해시 불일치를 건너뛰지 말고 동일한
  검토 산출물 세트로 되돌립니다. 현재 항구·강 통과점 근거가 없어 실행 가능한 수운 간선은 없으며,
  수역이 보인다는 이유만으로 항행·수전이 활성화된 것으로 해석하지 않습니다.

이 절은 배포/DB 전환 명령이나 승인을 대신하지 않습니다.

### HWIHA 행군 반응 상태

새 HWIHA 월드를 생성할 때 설치 계책·요격·회피의 초기 빈 목록을 `world_state.meta.hwihaMarchReactions`에 기록한다. 재기동은 이 값을 초기화하거나 누락된 키를 보충하지 않는다. SAMMO 월드는 이 초기값을 만들지 않는다.

키 부재·손상·미지원 버전이나 현재 판독기가 해결하지 못하는 반응 기록은 행군 진입 판정 불가로 처리한다. 이동을 재개하려고 목록을 임의로 지우거나 빈 값으로 덮어쓰지 않는다. 현재 버전은 초기 빈 상태와 군단 판정 연결만 제공하며, 설치·요격·회피 입력은 별도 구현이다. 개인 턴의 부임 행군은 이 저장 상태를 매번 읽는다.

### HWIHA 육상 통행 상태

새 HWIHA 월드는 위치를 시드한 토폴로지와 같은 핀으로 `world_state.meta.hwihaLandPassage`를 저장한다. 육로·여울·다리 전체를 명시하며 상시 통행 간선만 초기 활성화한다. 계절 개방 정보가 없는 길은 닫아 둔다. 가용량은 정적 기반 시설 용량 이하이고, 부임 행군은 양수 통행 가능 여부를 검사한다. 병력 수에 대한 수송 용량으로 해석하지 않는다.

키 누락·손상·토폴로지 불일치는 개인 기록에 정지 사유를 남기고 이동을 멈춘다. 기존 월드의 재기동 시 자동 보충하거나 핀만 교체하지 않는다. 별도 전환 검토 없이 운영 metadata를 덮어쓰지 않는다.

### HWIHA 개인 출병 예약과 재개

`action.deploy` 예약은 `bugokIds`와 `destinationProvinceId`만 받는다. 현재는 본인 지휘 부곡의 개인 행동이며 부장 배치는 별도 입력이다. 접수 뒤 실행 단계에서 원래 `command_inbox.owner_user_id`와 현재 장수 소유자를 다시 대조한다. 원장 결속이 없거나 소유자가 바뀌면 `FORBIDDEN`으로 거절한다. 슬롯 이동은 원래 제출자 결속을 유지한다.

실행된 목적지는 지휘 장수의 `hwihaCorpsOrder`, 군단 진행은 `hwihaCorpsMarch`에 저장한다. `hwihaDeployment`와 명령 ID·소유자·지휘자·토폴로지 핀 및 경로 목적지가 일치해야 재개한다. 누락·불일치 상태를 임의 초기화하지 않는다. 통행 중단 뒤에도 원래 목적지와 진행량을 보존하며 도착이 군단 해산을 뜻하지 않는다.

개인 행동 처리 뒤 단일 행군 단계가 같은 recorder와 flush 트랜잭션을 사용한다. 위치 revision 충돌은 명령 생성·행군·개인순·결과 기록을 함께 rollback한다. 재시작 진단은 저장된 원장·명령·진행·위치와 개인 결과를 대조하며, 재실행을 위해 순 stamp나 요청 ID를 지우지 않는다.

### HWIHA 군단 조우 사건

실제 출병 군단이 적 군단이 있는 지역에 진입하면 모든 참여 지휘 장수의 `hwihaCorpsEncounter`에 동일한 pending 사건을 저장한다. 공격 명령·방어 군단·부곡 ID·접근 방향·순·토폴로지 핀을 결속하고, 참가자 정렬 순서가 달라도 같은 사건 ID를 계산한다. 이 기록은 전투 결과나 방어 군단 사이의 동맹을 의미하지 않는다.

양측 기록·군단 소유/지휘·현재 위치·공격 측 행군 checkpoint가 일치해야 상태를 읽을 수 있다. 일부 장수의 기록만 지우거나 수정하면 상태 판정 불가로 멈춘다. 위치 저장 충돌 시 사건 전체도 같은 트랜잭션에서 되돌린다. 기존 조우가 진행 중인 군단에게 다른 군단이 진입하려 하면 현재는 진입 판정 불가로 멈추며 사건을 덮어쓰지 않는다. 격자 전투 해결과 지원군 합류는 아직 별도 구현이다.

새 조우는 같은 트랜잭션에서 모든 참가자의 `hwihaEncounterDeployment`도 봉인한다. 선택된 지도 묶음의 타일 해시·규칙 버전·사건 ID와 기본 패 배치/예비대를 결속하며, 지형 또는 방어 참가자 수용량 부족은 별도 상태로 보존한다. codec 재로드는 같은 핀의 원본으로 재계산한 값과 정확히 비교한다. 이전 사건에 없는 배치를 자동 소급 생성하지 않는다. 이 단계는 기본 배치 저장까지이며, 전체 계획·교전·정산은 아직 연결되지 않았다. 기존 이동 잠금 투영은 사건 기록을 사용하며, 봉인 배치 codec은 향후 전투 소비자가 재검사해야 한다.

같은 조우 트랜잭션에서 `hwihaEncounterForces`와 `hwihaEncounterRelations`도 모든 참가자에게 동일하게 저장한다. 전자는 부곡의 실제 자원·훈련·병종 식별자와 현재 지휘 능력, 후자는 모든 참가 군단 쌍의 교전 적대 여부를 보존한다. codec은 정확한 필드·정수 범위·참가자 결속·정규 순서·snapshotId를 검사한다. 이후 live 부곡/장수/외교 수치가 달라져도 봉인값은 바뀌지 않는다. 수치 봉인은 병종 catalog 검증이나 전투 공식 승인을 의미하지 않는다. 기존 사건에 빠진 자료는 자동 소급 생성하지 않으며, 향후 전투 소비자는 모든 참가자 봉인값의 일치와 원본 결속을 재검사해야 한다. 상대 봉인 자료를 일반 API에 그대로 반환하지 않는다.

### HWIHA 조우 정산

pending 조우는 공격 지휘관의 다음 개인 턴 시작(`HwihaAssignmentMarchTurn` → `HwihaEncounterResolver`)에 봉인 기록만으로 끝까지 해결한다. 참가자 전원의 봉인 기록이 바이트 단위로 같지 않거나 전투 기록(`hwihaBattleJournal`)이 없으면 결과를 만들지 않고 대기로 둔다. 해결되면 봉인 키들을 모든 참가자에서 함께 지우고 `hwihaLastBattle`(판정·회차·전체 입력 journal·재현 해시)을 남긴다. 병력 0 이 된 부곡은 `general_bugok` 제약(troops > 0) 때문에 행을 지우고 살아남은 군단의 부곡 목록에서 뺀다. 패한 공격 측은 접근 省으로 돌아가며 출전 기록·명령·행군을 지운다. 궤멸한 패자 지휘관에는 임시 포로 표식 `hwihaCaptive`만 남는다(효과 없음). 명망 사건은 `HwihaWarOutcomeListener`(기본 무동작)로 나가며 기록 스트림 병합 때 연결한다.

### HWIHA 포위 상태(V61 `hwiha_siege`)

縣治 城 하나에 행 하나(PK world_id·county_id)이고 `ACTIVE`·`LIFTED`·`FALLEN` 을 가진다. 끝난 포위도 조회·기록용으로 남고 같은 縣의 새 포위가 덮어쓴다. 쓰기는 엔진 world dirty 집합 → `JdbcFlushExecutor` 8j 채널(CREATE → UPDATE)뿐이며 부팅 스냅샷(`loadHwihaSieges`)이 싣는다. 포위 장수 행이 지워지면 FK CASCADE 로 함께 사라진다. 순 경계(`HwihaPhaseBoundary`)는 `settled_*` 도장으로 같은 순을 두 번 정산하지 않는다. 성 안 급식은 `HwihaWarehouseSettlement` 로 縣 창고 곡을 뺀다. 함락은 `city.nation_id`·`pop`·`def` 를 바꾸고 창고는 縣에 남긴다. 수도 함락 때 수도 이전·국고 이동은 아직 없다. 조회는 `GET /api/hwiha/sieges?generalId=`(관여한 포위만).

### HWIHA 순 경계 보급·녹봉·기존 유지비

HWIHA 순 경계마다 포위 정산 뒤 세력 수도에서 보급 BFS 를 다시 돌려 縣 `supply_state` 만 고친다(감쇠·중립화는 기존 월간 `UpdateCitySupply`). 포위 중인 縣은 외부 보급이 끊긴다. 보급망을 계산할 수 없으면 경고만 남기고 이전 값을 유지한다(턴 루프를 멈추지 않는다). 월 경계는 징세 → 녹봉(`HwihaMonthlySalary`, 도장 `hwihaSalaryMonth`) → 부곡 군량 보충(`HwihaUnitResupply`, 도장 `hwihaUnitResupplyMonth`, 자국 縣에 있는 부곡만 창고 곡으로 병력×2개월까지) → 월단평 순이다. 적지의 포위군은 보충받지 못해 군량이 떨어지면 포위를 풀고 원정을 멈춘다. `RetainerMonthlyService` 는 HWIHA 에서 부곡 급여·가신 유지비 30/30 의 재정 효과를 적용하지 않는다. 상사(`court.reward`)는 발령처럼 결정권자 meta `hwihaQueuedReward` 에 한 건 대기했다가 그 장수의 턴에 실행한다.

### HWIHA 반응 기록 확정 규칙

`hwihaMarchReactions` 기록(버전 1, 세 목록)은 `HwihaMarchReactionInterpreter`가 행군 진입에서 해석한다. 설치 계책·FULL 시야·1省 요격·회피 철수는 PR #874에서 연결됐고, 틀이 깨지면 판정 불가로 처리한다. 요격 범위 1省은 #872 확정값이며 `data/curated/han/hwiha-s3-provisional-v1.json`의 `reactions`가 정본이다.

### Han V3 수역 상태와 보급 복구

V49는 세계별 `water_zone_control` 빈 테이블을 추가합니다. V3 부팅은 실제 snapshot loader에서
해당 세계의 행만 읽고 지형 버전·해시·수역 ID를 검증합니다. 행 없음은 미확인이며 육지 소유권에서
통제를 만들지 않습니다. V2 저장 세계는 수역 테이블을 조회하지 않습니다.

- V901을 이미 적용한 실험 DB는 낮은 버전 V49의 별도 업그레이드 검증이 필요합니다.
  `outOfOrder` 전역 활성화, Flyway history repair, DB reset으로 이 단계를 건너뛰지 않습니다.
- 수역 변경은 daemon recorder와 기존 JDBC transaction으로만 저장합니다. 한 틱의 여러 변경은
  최초 기대 revision과 최종 상태로 합칩니다. 전송 오류 재시도는 보존한 동일 payload를 사용합니다.
- `StaleWaterControlException`은 재로드가 필요한 동시 수정 충돌입니다. 동일 명령을 계속 재시도하거나
  revision 값을 수동으로 올리지 말고 기존 격리·재로드 절차를 따릅니다.
- 수운 보급은 명시된 통과 허가·가용 용량과 자국 수역 통제, 계절 조건을 모두 요구합니다.
  현재 실제 함대 용량 공급자는 없으므로 정적 간선 capacity만으로 수운을 열지 않습니다.
- 기존 두 그래프의 보급 안전 판정을 유지합니다. 수역 통제 변경은 토지 소유권을 직접 바꾸지 않습니다.
- 기존 legacy full-rehydrate 서명만으로 수역의 DB roundtrip이나 replay를 검증했다고 간주하지 않습니다.
  수역 전용 PostgreSQL 복구·CAS rollback 검증을 배포 전에 별도로 실행해야 합니다.

### Han V3 수역 표시와 경로 미리보기

`GET /api/map/strategic-topology`는 process world에 묶인 읽기 전용 조회입니다. 일반 요청은 수역 통제
SQL을 조회하지 않고 `REDACTED`를 반환합니다. 현재 검증된 `ROLE_ADMIN`만 실시간 통제를 읽으며,
정찰/FOW 정책이 확정되기 전까지 국가 소속만으로 조회 권한을 넓히지 않습니다.

- 지도 binding은 world ID, topology revision/hash, 원본 tile SHA와 크기를 포함합니다.
  기존 `worldMap.version = 0`을 수역 revision으로 사용하지 않습니다.
- 같은 topology hash를 알고 있는 요청에는 정적 도형을 생략할 수 있지만, 통제 응답은 `no-store`입니다.
  클라이언트 캐시는 서버·세계·전체 binding으로 격리합니다.
- 지형 바이트 SHA가 다르면 수역과 경로를 표시하지 않습니다. 수역 산출물 장애는 별도 조회에서
  409로 차단하며 기존 육상 preview를 제거하지 않습니다.
- 수송 미리보기의 전체 경로는 요청 당시 서버와 응답 world ID를 보존합니다. 새 클라이언트는
  제출에 `expectedWorldId`를 보내고, 불일치는 예약 전에 422로 거절합니다. 기존 클라이언트의
  생략 호환성은 남아 있으므로 이를 모든 과거 요청의 world pin 보장으로 해석하지 않습니다.
- 이 UI는 항구·도하·함대를 생성하지 않습니다. 통제 공개 범위와 실제 물길 활성화는 별도 검토 대상입니다.

### 서비스는 online인데 화면이 502

nginx 정적 upstream의 stale DNS, 대상 container health와 포트를 확인합니다. shared 서비스 변경 뒤 nginx를
마지막에 재시작하는 운영 순서를 따릅니다.

### 명령은 접수됐는데 결과가 없음

durable inbox, Redis wake, engine claim, flush, result/outbox, XACK 순서에서 요청 ID를 추적합니다. Redis 알림은
DB commit의 대체 진실이 아닙니다.

### engine 재기동 뒤 상태가 과거로 돌아감

새로운 write를 멈추고 world ID, 마지막 version, snapshot loader와 flush/recovery 상태를 확인합니다. 현재
DB를 추측으로 고치지 말고 restart-rehydrate 증거와 quarantine 절차를 따릅니다.

### OOM 또는 반복 재시작

최근 [OPENSAM-217/#477](https://github.com/peppone-choi/opensamguk/issues/477)의 Compose restart·메모리
계약과 현재 JVM/container 한도를 대조합니다. 구형 로컬 `.env`에서 world ID가 빠졌는지도 확인하되 실제 값을
출력하지 않습니다.

### migration 실패

추가 migration을 재시도하기 전에 실패한 버전, transaction 여부, 적용된 schema history와 앱 호환성을
확인합니다. 이미 릴리스된 migration 파일을 수정하지 않고 새 전진 migration으로 수리합니다.

## 복구 원칙

- 앱 rollback과 DB rollback을 같은 것으로 취급하지 않습니다.
- Flyway migration은 자동 역실행하지 않습니다.
- 승인된 restore runbook이 생기면 새 격리 DB에서 먼저 연습하고 행 수·world ID·핵심 read를 대조합니다.
- reset/delete가 commit된 뒤에는 “재시도”가 중복 삭제·정산을 만들지 않는지 확인합니다.
- 복구 뒤 로그인, 권한, 명령, SSE와 턴 전진을 실제 사용자 경로로 다시 확인합니다.

## 에스컬레이션에 필요한 정보

- 발생 시각과 서버 ID
- 배포 SHA/tag와 서비스별 버전
- request ID 또는 operation ID
- health와 오류 메시지의 비밀 제거본
- 마지막 정상 관측과 최초 실패 관측
- 실행한 조치와 결과

토큰, 비밀번호, 실제 `.env`, 사용자 개인정보는 첨부하지 않습니다.


## 전콘 원본·세 구도 보관

새 수동 편집 업로드는 `8hex.portrait` 관리 파일 하나에 검증된 원본, 정규화 자르기 좌표, 히어로633×900·카드148×210·아이콘96×96 JPEG를 함께 보관합니다. 기존 단일 이미지 파일도 계속 지원합니다.
프로필 파일 볼륨의 백업·복원에 `.portrait` 및 기존 `.ops` 복구 기록을 포함해야 합니다. 파일을 변형별로 분해하거나 `.ops`를 별도 정리하지 않습니다. 기존 업로드/교체/삭제 트랜잭션 복구 경로를 그대로 사용합니다.

브라우저가 보내는 원본 상한은8MiB, archive 저장 상한은12MiB입니다. multipart 요청 상한은 원본과 좌표를 담을 수 있도록9MiB로 설정합니다. 외부 reverse proxy를 별도로 운영한다면 해당 업로드 요청의 body 상한도 확인해야 합니다.
공개 `/profile-icons/<관리이름>.portrait/{hero|card|icon}.jpg`는 렌더 결과만 제공합니다. 원본 및 자르기 정보는 인증된 본인 전용 `/auth/account/profile-icon/{source|crops}`이며 캐시하지 않습니다. 원본과 metadata의 `X-Portrait-Id`가 다르면 클라이언트는 동시 변경으로 판정해 다시 읽도록 안내합니다.

Gateway Next 서버가 공개 변형 요청을 gateway-api로 전달합니다. game 단독 개발 서버는 `GATEWAY_WEB_URL`(기본 http://localhost:3000)로 같은 경로를 전달합니다. archive 자체를 nginx 정적 파일 허용 목록에 추가해서는 안 됩니다.

조우 병종 규칙은 `data/battle/hwiha-unit-profiles-v1.json`의 버전과 원본 해시, 사용한 수치를 함께 저장합니다. 이전 조우를 읽을 때는 동일 해시의 원장이 필요하므로 규칙을 개정할 때 이전 버전 파일을 보존해야 합니다. 미등록 병종과 아직 지원하지 않는 병종은 준비 불가 사유를 남기며, 다른 병종으로 자동 치환하지 않습니다. 현재 피해·전투 정산은 개발 중입니다.

조우 기본 계획은 양측 참가자의 `hwihaBattlePlans`에 동일하게 봉인됩니다. 사건·참가자 집합과 계획 해시가 일치하지 않으면 읽기를 거절합니다. 계획 저장만으로 전투가 끝나지는 않으며, 조건 발동 계산과 실제 퇴각·정산 연결을 구분해야 합니다.

HWIHA 개인 턴은 행동 처리 전에 소유 장수의 `hwihaStratagemHand`를 공급·갱신합니다. 최초 손패2장, 이후 순당1장, 보유 상한3장이며 같은 순의 재처리는 공급하지 않습니다. 일반 장수 메타와 같은 트랜잭션으로 저장됩니다. 잘못된 버전·소유자·중복 카드 인스턴스는 새 손패로 덮어쓰지 않습니다. 손패가 없다는 이유만으로 전투 계책이 없다고 판정하지 마세요. 현재 카드 사용·공개·비용 정산은 아직 연결되지 않았으며, 이 내부 손패를 공개 SSE나 일반 장수 응답에 넣으면 안 됩니다.

`GET /api/commands/stratagem-hand?generalId=…`는 현재 소유자만 손패의 인스턴스·종류·이름을 조회합니다. 익명은401, 타인·미존재 장수는403이며 응답은 `Cache-Control: no-store`입니다. `NOT_READY`는 첫 공급 전, `UNAVAILABLE`은 저장 상태나 월드 확인 실패이며 빈 손패로 해석하지 않습니다. `READY`도 카드 사용 가능을 뜻하지 않으며 현재 `canUse=false`입니다. 조회는 공급·소모를 수행하지 않습니다.

### HWIHA 縣 창고 정산 저장 경계

縣治 `city.meta.hwihaCountyWarehouse`는 전·곡·철·목재·말의 비음수 정수 게임 단위와 revision을 저장한다. 기존 도시 스냅샷·HotCold 등록·ChangeRecorder·JDBC flush 경로를 공유한다. 미설정 창고는 `NOT_READY`이며 조회·재기동으로 비축을 만들지 않는다. 국고·개인 재화를 복사하지 않는다. 새 시나리오의 명시적 초기 재고 입력은 아래 계약으로 연결되며, 실제 생산·보급·계책 소비자 연결은 아직 남아 있다.

내부 정산은 현재 縣 통제자와 예상 revision을 재확인하고 모든 비용을 낼 수 있을 때만 반영한다. 부족·오염·범위 초과는 부분 차감 없이 거절한다. 동일 엔진 writer에서 원래 revision을 재사용한 재전달은 차단되며, 이 계약은 작업 ID 멱등성이나 복수 writer의 DB CAS를 대신하지 않는다. 실패한 DB 트랜잭션 이후에는 기존 엔진 복구 경로로 상태를 다시 로드해야 한다.

새 HWIHA 시나리오는 선택 항목 `hwihaWarehouses`로 최초 재고를 선언할 수 있다. 정확한 필드는 `version: 1`, `units: "game-resource-v1"`, `source: "GAME_DESIGN"`, 실제 지도의 `topologyRevision`·`topologyHash`, 그리고 `warehouses` 배열이다. 각 행은 `countyId`와 `stock`(money·grain·iron·timber·horses 전부 명시한 비음수 정수)만 담는다. 선택된 지도의 모든 행정 縣治를 정확히 한 번씩 선언해야 하며 다른 城·누락·중복·다른 지도 핀은 거절한다. 재고가 없는 縣도 다섯 항목을 명시적으로 0으로 적는다. 이 수량은 게임 기획 입력이며 역사 생산량으로 표시하지 않는다.

이 항목을 사용하면 모든 `nation`의 기존 gold/rice는 0이어야 한다. 수도를 포함해 모든 재화는 명시된 縣 창고에만 넣고, 기존 국가 잔고를 복사하지 않는다. 입력 부재는 기존 시나리오 동작을 유지하며 창고를 추정 생성하지 않는다. `ScenarioSeedCoordinator`의 신규 월드 트랜잭션 안에서만 초기화하며, 뒤 단계가 실패하면 창고도 롤백된다. 기존 월드 재기동은 소비한 재고를 보충하지 않는다. 초기 입력의 단위·출처·지도 핀·縣 수는 `world_state.meta.hwihaWarehouseSeed`에 남긴다.

HWIHA 에서는 기존 월간 국가 세입·전쟁 수입과 개인 재화 기반 가신 유지비·부곡 급여를 적용하지 않는다. 縣 창고 월세입(`HwihaMonthlyCountyIncome`)과 녹봉(`HwihaMonthlySalary`)이 창고 경제를 맡는다. 기존 세입과 창고 세입을 동시에 켜서는 안 된다. 연결 창고 간 자동 이동·군단 군량 소모·계책 비용은 아직 없다.
