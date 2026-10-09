# PEP 데이터 보존 migration rehearsal

상태: **보존형 배포 준비 도구이며 운영 배포 완료가 아닙니다.**
`tools/ops/pep_migration.py`는 `prepare`와 명시적 `rehearse`만 제공합니다.
후보 이미지의 live 적용, reset, 재시드, 원 PG/Redis 볼륨 삭제는 구현하지 않습니다.
항상 `ready_for_deployment=false`, `candidate_applied_live=false`를 반환합니다.
이 결과로 기존 `pep-loop`의 자동 초기화 관문을 해제하지 않습니다.

## 지원 대상과 실행 전 차단

지원 대상은 정확한 `pep`, `/home/peppone_choi/opensamguk-docker`와 다음 두 consumer 배치입니다.
PUBLIC는 실행 중인 `spep-game-api`/`spep-web-game`, PRIVATE는 실행 중인
`spep-game-api-validation`/`spep-web-game-validation`입니다. PG/Redis/engine은 공유 canonical 이름입니다.
혼재, 한쪽만 존재, 반대 배치의 정지된 잔여 컨테이너는 첫 정지 전에 거절합니다.
PRIVATE는 published PortBindings와 network aliases가 없어야 하고 web upstream은
`http://spep-game-api-validation:8081`이어야 합니다. 원래 PortBindings·ExposedPorts·aliases·network ID와
모든 원본 container ID/설정은 보존하며 재개할 때 새 consumer를 만들지 않습니다.

engine은 `data/scenarios`→`/data/scenarios` 읽기 전용 bind 하나, API는 같은 scenario bind와
fullbundle일 때 `data/topdown/pep`→`/app/data/map/topdown` 읽기 전용 bind 하나를 허용합니다.
web mount는 없습니다. extra/duplicate/volume mount, symlink 부모와 다른 filesystem은 거절합니다.
실효 `SCENARIO_DIR=/data/scenarios`가 필요하며 bundled-source·빈 값은 지원 밖입니다.
fullbundle API는 `SERVER_ID=pep`, 정확한 `TOPDOWN_MAP_ROOT`와 catalog 안의 `TOPDOWN_BAKE_ID`가 필요합니다.
빈 세계·다른 포트·무제한 앱 메모리·tablespace/WAL 링크·다른 Redis 명령의 기존 거절은 유지합니다.

PUBLIC/PRIVATE는 게임 규칙의 공개 여부가 아닌 consumer 배치입니다. fullbundle은 API에 연결한
지도 산출물 계약이며 PG/Redis/image의 냉간 recovery bundle과 다릅니다. `pep_topdown_catalog.py`는
선택하지 않은 bake까지 전체 root를 대조합니다. 앱 reader의 schema/format 1, 네 identity key의
재귀 정렬 compact JSON SHA256, full/region=null, L0/L2 크기와 모든 manifest/asset transport/raw hash,
파일·디렉터리 inventory를 검증합니다. manifest는 2MiB, raw asset은 16MiB를 넘을 수 없습니다.
새 builder pin을 강요하거나 bake를 재생성하지 않습니다. 실제 API preview의 selected bake와 모든
bake manifest/asset bytes도 읽기 전용 HTTP로 대조하며 503/권한 거절을 성공으로 취급하지 않습니다.

기존 capture의 v1은 유지합니다. preserving 호출만 topology/topdown metadata를 추가한 v2를 만듭니다.
fullbundle의 `<bundle>.topdown/{manifest.json,tree/}`는 bundle manifest SHA256에 묶인 전체 사본이며
0700 directory/0600 file과 INCOMPLETE marker를 사용합니다. source/copy가 같아야 완료됩니다.
기존 비공개 recovery payload인 `server.env`/Compose는 v1 계약을 계승합니다. 신규 topology/catalog
metadata나 결과·로그에 full effective env, 인증정보 또는 전체 inspect를 덤프하지 않습니다.
이 payload까지 env 기록 금지에 포함하는지는 독립 인수 때 별도 확인해야 합니다.

**실제 fullbundle 앱 검증은 아직 미통과입니다.** 2026-10-09 합성 실제 이미지 fixture의 첫 API
preview가 `SERVER_ADMISSION_UNAVAILABLE`(503)로 거절됐습니다. clone은 외부 서비스에 연결하지 않는
격리 network를 쓰므로 Gateway publication 원천을 안전하게 제공할 계약이 먼저 필요합니다.
공개 상태 mock을 운영 admission으로 취급하거나 filter/network 정책을 바꾸지 않습니다.
`prepare`는 readonly 계약을 검사하지만 fullbundle `rehearse`는 이 원천 계약이 구현될 때까지
첫 정지·journal·candidate admission 전에 명시적으로 거절합니다. capture/companion 단위 검증 경로만 열어 둡니다.
따라서 구현된 mount/catalog 계약의 단위 PASS를 fullbundle clone/rollback 완료로 보고하지 않습니다.

## 하나의 lock 안에서 실행하는 작업

1. web/API intake, engine 순으로 정확한 원본 ID만 정상 종료합니다. 원본 설정과 ID가 달라졌거나
   OOM/SIGKILL이면 중단합니다. committed `ACCEPTED`/`CLAIMED` inbox가 0이어야 합니다.
2. 아직 실행 중인 PG/Redis의 전체 논리 dump hash·Flyway·world/city 존재와 행 수·AOF 상태를 읽고,
   두 저장소를 정상 종료합니다. `Recovery.capture`와 `verify`로 전체 냉간 백업을 만들고 격리 복원합니다.
   원본 committed 상태와 복원 상태를 비교하고 시나리오 동반 사본을 보존합니다.
3. 각각 **새 PG와 전체 Redis AOF 사본**을 복원한 내부 network에서 구 API/engine,
   후보 API/Flyway와 후보 engine, 원백업의 구 API/engine rollback을 순서대로 실행합니다.
   모든 단계는 seed disabled이며 engine은 복제 DB의 `plock=1`로 정지된 동일 세계를 재적재해야 합니다.
   현행 clock의 `worldId`와 `serverId`도 복원 DB의 world/선택된 `ng_games` 정본과 대조합니다.
   여러 서버 중 선택이 없거나 지정된 서버가 없으면 최신 행을 추정하지 않고 거절합니다.
   원본 세계의 plock, 입력, 시나리오, 설정을 수정하지 않습니다.
   매 clone 자원 생성 전에 recovery bundle의 payload hash와 manifest/env를 다시 검증합니다.
   각 앱 단계 전후와 원본 재개 직전에는 scenario/topdown 동반 사본·원본 tree의 byte/hash와
   원본 ID/설정/노출도 대조합니다. API clone과 원본 재개에서 preview·모든 map asset을 읽어 검증합니다.
4. migration 전 모든 기존 public table의 모든 열·행과 sequence를 정렬 JSON으로 fingerprint합니다.
   새 generated 열은 기존 데이터 projection에서 제외하지만 원래 열·행·sequence 삭제/변경은 실패합니다.
   따라서 V78의 `nation_ref` 추가는 기존 `nation=0` 저장값을 보존할 수 있고, 데이터 재시드는 통과하지 못합니다.
   Redis는 16 DB 각각의 모든 key, 비-stream의 `DUMP` 값과 절대 expiry를 비교합니다.
   stream은 모든 메시지 ID·field/value bytes, 마지막 생성/삭제 ID, 누적 추가 수, first-entry ID,
   group 이름·delivery cursor·entries-read·lag를 대조합니다. 모든 group/consumer의 pending은 0이어야 합니다.
   key 수가 같은 값 교체, expiry, group/cursor 변경도 실패합니다.
   pending=0인 빈 consumer의 이름·수·idle/inactive 시계만 제외합니다. 합성 실제 앱에서 빈 stream을 읽어
   만든 consumer는 AOF 복원 후 사라지고 앱 시작 시 다시 생기는 것을 재현했습니다. 이것을 메시지나
   delivery state 소실로 간주하지 않지만, pending이 하나라도 있으면 지원 밖으로 거절합니다.
   consumer 관측 항목은 [Redis 공식 XINFO CONSUMERS 문서](https://redis.io/docs/latest/commands/xinfo-consumers/)를 따릅니다.
   TTL 만료 등 변화도 보수적으로 실패하며 자동으로 허용하거나 데이터를 고치지 않습니다.
   앱 정지 후 저장소 정지 직전에 측정한 원본 Redis fingerprint와 모든 cold clone,
   재개한 원본 Redis fingerprint도 대조합니다. 첫 정지 전 관측값은 복원 비교 기준으로 사용하지 않습니다.
   원본 Redis의 key 수가 같아도 값·expiry가 다르면 API/engine 재개를 거절합니다.
5. 원백업을 새로 복원한 rollback 증거가 최초 구 이미지 증거와 같아야 합니다.
   후보 schema를 구 이미지로 되돌리는 down migration이나 이미지 태그만의 rollback을 주장하지 않습니다.
6. 같은 원본 다섯 컨테이너의 설정·ID가 보존됐는지 다시 확인합니다. 원본 PG/Redis를 재개해 저장소 fingerprint를
   확인한 다음 구 engine/API와 web을 재개합니다. API health, engine materialized READY, web HTTP와 기존 노출을 확인합니다.
   source cursor나 live Compose를 후보로 바꾸지 않습니다. 정상 source daemon은 재개 후 기존 정책에 따라 턴을 진행할 수 있습니다.

어느 단계든 실패하면 `.pep-migration-incomplete`와 비공개 `status.json`을 남기고 intake/engine을 닫습니다.
원본을 무조건 재시작하거나 backup을 덮어 쓰거나 reset하지 않습니다. 운영자가 정확한 실패 단계와
남은 서비스를 확인해야 합니다. storage scratch 정리는 이름·ID·소유권을 재검사한 이번 실행 자원에 한합니다.
원본 `spep-game-pgdata`, `spep-game-redisdata`는 삭제하지 않습니다. 상태 journal이 없어졌다는 이유만으로
다른 lifecycle 작업을 허용하지 않으며 maintenance fence는 별도 인수까지 유지합니다.

## 남은 운영 승격 관문

이 도구의 앱 boot/paused world는 인증 읽기, 브라우저, 실제 입력→턴→SSE 완주를 증명하지 않습니다.
`authenticated_reads_verified=false`로 기록합니다. 실제 Gateway 정의와 management transition이 idle인지의
원자적 admission, PRIVATE/fullbundle 실제 이미지의 Gateway publication 원천·map HTTP bytes·exposure 보존,
운영 backup 복원과 구 이미지 인증 읽기,
후보의 해당 source/world migration rehearsal 및 rollback, 운영 DDL lock·소요 시간·여유 공간·인수 확인이 남아 있습니다.
현재 운영 접근·현재 shape·실제 backup 상태를 확인하기 전에는 **라이브 정지나 후보 배포 준비 완료로 판단하지 않습니다.**

최종 live apply는 위 증거가 동일 source/bundle/world/images에 묶인 후 별도 구현·검증해야 합니다.
writer를 유지보수 아래 동결하고 shared lock을 계속 보유한 상태에서 live PG를 보존하는 동일 검증 migration,
seed disabled 후보 서비스 교체, 같은 map/scenario/world/노출·인증 QA를 수행해야 합니다.
실패 시에는 intake를 닫고 원본 냉간 백업을 **별도 새 recovery volume**에 복원해 검증된 구 이미지로 복귀하는
경로가 필요합니다. 원본 PG/Redis를 지우거나 candidate-schema에 구 이미지를 붙이는 자동 fallback은 없습니다.
이 CLI를 게시·병합해도 기존 자동배포 경로를 대체하거나 보존형 운영 적용을 연결하지 않습니다.

## 로컬 검증 범위

`test_pep_migration.py`는 admission/형태 불일치/소스 drift/inbox/백업/후보/rollback/원본 재개 실패를 모의 검사합니다.
이 테스트의 injected adapter 성공은 실제 운영 CI·backup·앱 boot를 대신하지 않습니다.
`test_pep_topdown_catalog.py`는 두 합성 bake의 전체 catalog/companion, corruption/link/size/inventory와
v1/v2 capture 계약을 검사합니다. 선택되지 않은 두 번째 bake의 kitVersion도 다릅니다.
`test_pep_migration_docker.py`는 명시적 opt-in에서 실제 PG16/Redis7 임시 데이터를 생성하고 full cold capture,
storage restore, 승인된 V78 SQL, 기존 행·자산·sequence 보존, 자유 부대 nation=0, 음수·다른 world·leader FK 거절,
동일 key 수의 Redis 값/expiry·DB15 binary 값·group/cursor 변경 감지, pending 거절,
빈 consumer의 AOF 복원, 원백업 rollback 및 원 저장소 재개를 확인합니다.
`NativeMigrationTests`는 Spring/Flyway runner·앱 image boot·운영 원본 재개·UI 검증이 아닙니다.

```bash
python3 -m unittest discover -s tools/ops -p 'test_pep_migration.py' -v
RUN_PEP_MIGRATION_DOCKER_TESTS=1 \
  python3 tools/ops/test_pep_migration_docker.py NativeMigrationTests -v
```

V78이 아직 이 checkout에 없으면 `PEP_MIGRATION_V78_SQL`로 실제 승인 소스 파일을 지정합니다.
검사는 승인된 SQL SHA256을 대조하며 fixture DDL로 대체하지 않습니다. 기본 opt-in 없는 skip은 실행 성공으로 세지 않습니다.
백업 접근·보관·RPO/RTO 한계는 [냉간 복구 문서](game-server-recovery.md)를 따릅니다.

`ApplicationMigrationTests`는 실제 구/후보 API와 engine image를 별도로 받아 실행합니다.
구 engine으로 **새 합성 fixture만** 초기 생성한 뒤, seed를 끈 구 앱의 paused READY를 확인합니다.
전체 cold capture/verify 후 같은 백업에서 새 PG/Redis를 세 번 복원해 구 앱, 후보 Spring/Flyway,
구 앱 rollback을 검증하고, 동일 source storage/API/engine 객체의 재개와 저장 데이터 불변을 확인합니다.
전체 기존 public row/column/sequence, Redis fingerprint, scenario/topdown 사본을 실제 `clone_stage`로 대조하도록
구성합니다. 현재 PRIVATE/fullbundle 실행은 첫 preview 503에서 중단되므로 세 clone 단계의 새 PASS 증거는 없습니다.
운영 maintenance/CI admission과 중앙 `rehearse`/`resume`는 호출하지 않습니다. web은 capture 계약용
정지 placeholder이며 web/Gateway/인증 API/UI QA는 수행하지 않습니다.

다음 여섯 값이 필수입니다. `OLD_SOURCE`/`CANDIDATE_SOURCE`는 정확한 40자리 revision,
네 `*_IMAGE`는 로컬에 존재하는 불변 `sha256:<64자리>` image config ID입니다.
각 image의 실제 linux/amd64와 OCI revision label이 해당 source인지 검사합니다. 부동 tag는 거절합니다.
이는 로컬 합성 시험 admission이며 production의 GHCR manifest/CI admission을 대체하지 않습니다.

```bash
export PEP_MIGRATION_OLD_SOURCE=<40자리-구-source>
export PEP_MIGRATION_CANDIDATE_SOURCE=<40자리-후보-source>
export PEP_MIGRATION_OLD_API_IMAGE=sha256:<64자리-config>
export PEP_MIGRATION_OLD_ENGINE_IMAGE=sha256:<64자리-config>
export PEP_MIGRATION_CANDIDATE_API_IMAGE=sha256:<64자리-config>
export PEP_MIGRATION_CANDIDATE_ENGINE_IMAGE=sha256:<64자리-config>
RUN_PEP_MIGRATION_APP_TESTS=1 \
  python3 tools/ops/test_pep_migration_docker.py ApplicationMigrationTests -v
```

읽기 전용 사용자 홈을 사용하는 개발 환경에서는 [Docker 공식 설정 경로 옵션](https://docs.docker.com/reference/cli/docker/#change-the-docker-directory)의
`DOCKER_CONFIG`를 승인된 scratch 경로로 지정할 수 있습니다. 이번 환경에서는
`/workspace/scratch/pep-rehearsal-docker-config`를 mode 0700으로 생성했습니다.
보호 경로·권한·daemon 보안 설정을 바꾸거나 기존 인증정보를 복사하지 않았습니다.
캐시된 JDK21 base와 실제 Boot JAR, 저장소 map 파일만 사용한 로컬 runtime image를
`docker build --network=none --pull=false`로 만들었습니다. 운영 Dockerfile의 전체 빌드나 게시된
GHCR image와 동일하다는 주장은 하지 않습니다. 이미지·설정·실행 로그·백업은 커밋하지 않습니다.

합성 앱 검증 결과와 정확한 source/image ID는 [개발 인계 문서](../development/pep-preserving-rehearsal-handoff.md)를 따릅니다.
이 CLI의 제한된 구현 검증과 전체 운영 배포 리허설 완료는 별개이며 `ready_for_deployment=false`를 유지합니다.
