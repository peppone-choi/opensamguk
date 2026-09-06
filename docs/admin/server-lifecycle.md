# 서버 생명주기

> 상태: 현재 Gateway 관리자 표면과 opensamguk-docker 운영 경계 기준
> 마지막 검토: 2026-09-05

## 작업별 데이터 영향

| 작업 | 목적 | 데이터 영향 | 위험 |
|---|---|---|---|
| 생성 | 새 게임 서버와 world 준비 | 새 env, DB/Redis volume, registry 항목 | 중간 |
| 버전 배포 | 서버별 stateless 서비스 태그 변경 | migration이 있으면 DB schema 영향 가능 | 높음 |
| daemon pause/resume | 턴 진행을 일시 정지·재개 | world 데이터는 유지 | 중간 |
| reset | 새 시나리오/기수로 초기화 | 해당 서버 DB·Redis volume 초기화 | 매우 높음 |
| delete | 서버 제거 | 컨테이너, DB/Redis volume, env, registry 항목 제거 | 파괴적 |

`reset`과 `delete`는 이름이 비슷한 정리 기능이 아닙니다. 둘 다 복구 가능한 앱 배포와 다르며, 실행 전에
백업과 복구 가능성을 확인해야 합니다.

## 서버 생성

Gateway `/admin`의 `서버 제어`에서 서버 ID, 이름, 시나리오와 화면이 요구하는 설정을 입력합니다.

사전 확인:

- 서버 ID가 영문·숫자의 공개 ID 규칙과 예약어를 위반하지 않는지
- 같은 ID의 registry/env/container/volume이 없는지
- 선택한 scenario와 map resource가 현재 이미지에 있는지
- `OPENSAMGUK_WORLD_ID`, DB와 Redis 식별자가 다른 서버와 겹치지 않는지
- 공유 JWT·Gateway 계정 정책과 서버별 game world 경계가 의도한 값인지

생성 뒤에는 Gateway 로비 노출, game-api와 game-engine health, 시드 행 존재, turn clock 전진을 확인합니다.

생성 성공 후 Gateway에만 오래된 CREATE transition이 남았다면 일반 생성 요청을 재전송하지
않습니다. [냉간 복구 문서의 관리 메타데이터 조정 절](./game-server-recovery.md#이미-충족된-create-관리-메타데이터-조정)에
따라 runtime/env/control registry를 먼저 대조하고, 24시간 이상·만료 lease·Gateway DB 전체 정의
일치·deployer의 정확한 operation 404가 모두 증명된 경우에만 ADMIN 조정 endpoint를 사용합니다.
이 조정은 transition 메타데이터 한 행만 삭제하며 생성, reset, 계정/게임 데이터 수정을 실행하지
않습니다.

## 시나리오 시드와 reset

로컬 fresh DB에서는 `ScenarioSeedRunner`가 외부 `SCENARIO_DIR`을 먼저 보고 없으면 classpath 시나리오를
사용할 수 있습니다. 프로덕션은 기본 `SCENARIO_SEED_ENABLED=false`이며 운영자가 서버를 만들거나 reset하는
경로가 정본입니다.

서버별 시나리오 조회 소스 전환에는 Gateway env PATCH의 `SCENARIO_LOOKUP_DIR`을 사용합니다. 현재 운영
사용 범위는 승인된 PEP 서버 전환뿐이며 다른 서버에는 이 키를 설정하지 않습니다. 값은 정규화하지 않고 아래
세 모드로 구분합니다.

- 키 미설정: 기존 동작을 유지하며 control Compose가 기존 `SCENARIO_DIR` 값 또는 기본
  `/data/scenarios`를 사용합니다.
- `SCENARIO_LOOKUP_DIR=`: engine/API에 빈 `SCENARIO_DIR`을 그대로 전달해 이미지에 포함된 classpath
  시나리오를 조회합니다.
- `SCENARIO_LOOKUP_DIR=/data/scenarios`: 읽기 전용으로 마운트된 외부 시나리오를 조회합니다.

빈 문자열과 정확한 `/data/scenarios` 외의 값은 Gateway와 deployer가 모두 거부합니다. 공백, 다른 경로,
후행 슬래시와 보간 표현은 classpath 또는 외부 모드로 해석하지 않습니다. 이 선택은 기존 scenario bind
mount나 reset body를 변경하지 않습니다.

> **현재 production reset은 blocked입니다.** [냉간 백업·복원 절차](./game-server-recovery.md)에 실행 도구와
> 보존 정책·RPO/RTO를 정의했지만 로컬 테스트는 실제 운영 복구를 증명하지 않습니다. 각 작업의 실제 bundle
> 저장소 복원·원본 비교·이전 앱 재적재·인증 smoke report, control-plane 정합성과 명시적 승인이 필요합니다.
> [운영·복구 매뉴얼](./operations-and-recovery.md)의 `UNKNOWN / blocked` 관문을 먼저 충족합니다.

reset 전 체크:

1. 대상 서버 ID, 현재 scenario, world ID와 현재 기수를 기록합니다.
2. 유지해야 할 기록과 초기화되는 DB·Redis 범위를 확인합니다.
3. 백업 위치와 실제 restore 절차를 확인합니다.
4. 실행 중인 게임이면 이용자 공지와 점검 창을 확보합니다.
5. 별도의 명시적 승인을 받습니다.

reset 후 체크:

- health가 모두 정상인지
- 설정한 scenario code와 world ID가 맞는지
- 장수·도시·국가가 0이 아닌지 또는 의도한 빈 월드인지
- turn clock이 한 번 이상 전진하는지
- 이전 기수 데이터가 의도치 않게 현재 read에 섞이지 않는지

## 턴 데몬 주기

턴 데몬은 외부 cron이 아니라 game-engine 프로세스 안에서 계속 실행됩니다. 서로 다른 세 주기를 구분해야
합니다.

- `opensamguk.daemon.idle-poll-ms` 기본값은 250ms입니다. 즉시 명령과 새 마감 시각을 관측하는 최대 대기
  간격이며, 게임 시간의 진행 속도가 아닙니다.
- 장수 예약 명령은 각 장수의 DB `turn_time` 직후 개인 마감 드레인에서 실행됩니다. 이 드레인은 장수 상태와
  명령 결과만 flush하며 세계 연·월·phase와 `world_state.last_turn_time`을 전진시키지 않습니다.
- `tickSeconds`는 세계 턴과 월경계 주기입니다. 현재 운영 월드는 300초이며, 개인 예턴 실행 주기와 독립적입니다.
- `.github/workflows/daemon-health-alert.yml`의 5분 schedule은 데몬 상태를 감시할 뿐 턴을 실행하지 않습니다.

장애나 재기동으로 세계 턴이 밀렸으면 월경계 순서를 보존하기 위해 세계 턴을 먼저 따라잡은 뒤 개인 예턴을
처리합니다. pause 중에는 개인 예턴과 세계 턴이 모두 멈추며, resume 뒤 밀린 경계부터 다시 처리합니다.

## 버전 승격·다운그레이드

Gateway 관리자 화면은 gateway, game-api, game-engine의 실행 버전과 서버별 skew를 보여 줍니다. 배포는 대상
서버와 immutable image tag를 명시합니다. 현재 deployer는 진행 중 턴의 desync를 피하기 위해 game-engine과
stateless 서비스의 승격 경계를 구분하므로 화면 설명과 운영 저장소의 계약을 함께 확인합니다.

배포 전 최소 조건:

- 요청 tag가 immutable인지
- checkout의 최고 Flyway migration과 DB 적용 상태가 맞는지
- self-hosted `gcp-prod` runner가 online인지
- disk 여유가 승인된 기준 이상인지
- 현재 seed/scenario code가 기대값과 같은지
- rollback 대상 앱이 새 schema와 호환되는지

근거 체크리스트는 [OPENSAM-31/#173](https://github.com/peppone-choi/opensamguk/issues/173)과
[OPENSAM-34/#176](https://github.com/peppone-choi/opensamguk/issues/176)에 있습니다. 과거 이슈의 EC2 문구는
현재 GCP 운영에 그대로 적용하지 않습니다.

## 서버 삭제

삭제는 최후 수단입니다. 현재 관리자 UI 설명 기준으로 해당 서버의 컨테이너, DB/Redis volume, env와 Gateway
registry 항목을 제거합니다.

각 작업의 검증된 복구 report가 없는 현재 production delete도 `blocked`입니다. 아래는 차단 해제 뒤에도 필요한
최소 조건이며, 그 자체로 실행을 허가하지 않습니다.

실행 조건:

- 사용자가 정확한 서버 ID와 영향 범위를 다시 확인함
- 보존해야 할 DB/기록을 백업하고 restore를 시험함
- 관련 도메인·로비·프록시에서 트래픽을 차단함
- 데이터 삭제에 대한 별도의 명시적 승인이 있음

문서나 자동화의 편의를 이유로 운영 데이터 삭제 권한을 추론하지 않습니다.
