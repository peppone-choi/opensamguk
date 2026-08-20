# 서버 생명주기

> 상태: 현재 Gateway 관리자 표면과 opensamguk-docker 운영 경계 기준
> 마지막 검토: 2026-08-20

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

## 시나리오 시드와 reset

로컬 fresh DB에서는 `ScenarioSeedRunner`가 외부 `SCENARIO_DIR`을 먼저 보고 없으면 classpath 시나리오를
사용할 수 있습니다. 프로덕션은 기본 `SCENARIO_SEED_ENABLED=false`이며 운영자가 서버를 만들거나 reset하는
경로가 정본입니다.

> **현재 production reset은 blocked입니다.** reset 전 백업을 만드는 workflow는 있으나 검증된 restore
> runbook, 보존 정책과 RPO/RTO가 이 저장소에 없습니다. [운영·복구 매뉴얼](./operations-and-recovery.md)의
> `UNKNOWN / blocked` 조건이 해소되고 별도 명시 승인을 받기 전에는 실행하지 않습니다.

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

검증된 restore runbook이 없는 현재 production delete도 `blocked`입니다. 아래는 차단 해제 뒤에도 필요한
최소 조건이며, 그 자체로 실행을 허가하지 않습니다.

실행 조건:

- 사용자가 정확한 서버 ID와 영향 범위를 다시 확인함
- 보존해야 할 DB/기록을 백업하고 restore를 시험함
- 관련 도메인·로비·프록시에서 트래픽을 차단함
- 데이터 삭제에 대한 별도의 명시적 승인이 있음

문서나 자동화의 편의를 이유로 운영 데이터 삭제 권한을 추론하지 않습니다.
