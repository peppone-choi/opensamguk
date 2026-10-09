# PEP 데이터 보존 migration rehearsal

상태: **보존형 배포 준비 도구이며 운영 배포 완료가 아닙니다.**
`tools/ops/pep_migration.py`는 `prepare`와 명시적 `rehearse`만 제공합니다.
후보 이미지의 live 적용, reset, 재시드, 원 PG/Redis 볼륨 삭제는 구현하지 않습니다.
항상 `ready_for_deployment=false`, `candidate_applied_live=false`를 반환합니다.
이 결과로 기존 `pep-loop`의 자동 초기화 관문을 해제하지 않습니다.

## 지원 대상과 실행 전 차단

지원 대상은 정확한 `pep`, `/home/peppone_choi/opensamguk-docker`, canonical PUBLIC 다섯 서비스입니다.
API/engine은 단일 읽기 전용 `/data/scenarios` bind와 **실제로 그 디렉터리를 사용하는**
`SCENARIO_DIR=/data/scenarios`가 있어야 합니다. 외부 시나리오 전체는 같은 냉간 bundle의
동반 사본으로 보존하며 파일별 크기·SHA256과 bundle manifest를 묶습니다.
PRIVATE `*-validation`, fullbundle 추가 bind, bundled scenario, 빈 세계, 다른 포트,
무제한 앱 메모리, tablespace/WAL 링크, 다른 Redis 명령은 지원하지 않습니다.
운영 구성을 도구에 맞추려고 변경하거나 기존 검사를 우회하지 않습니다.

여기서 PUBLIC/PRIVATE는 게임 규칙이나 데이터의 공개 여부가 아니라 `pep_loop`의 consumer 배치를 뜻합니다.
PUBLIC는 `spep-game-api`/`spep-web-game`, PRIVATE는 `spep-game-api-validation`/
`spep-web-game-validation`이며 PRIVATE는 service ports/aliases를 제거한 격리 배치입니다.
PRIVATE 이름의 컨테이너가 정지된 상태로 남거나 PUBLIC와 함께 있어도 첫 정지 전에 거절합니다.
`fullbundle`은 API의 `/app/data/map/topdown`에 읽기 전용으로 연결한 지도 산출물과
`TOPDOWN_BAKE_ID`, manifest·각 asset의 byte/hash 결합을 뜻합니다. PG/Redis/image 전체를 담는
**냉간 recovery bundle**과는 다른 대상입니다. scenario 동반 사본을 보존해도 fullbundle을 보존한 것은 아닙니다.
현재 control Compose의 API는 이 topdown 추가 bind를 선언하므로 단일 scenario bind만 받는
이 도구로 실행할 수 없습니다. PRIVATE/fullbundle 지원은 capture·restore의 mount 계약과
노출·bake·map 검증까지 함께 구현한 뒤 검증해야 합니다.

`prepare`는 같은 production lock 안에서 maintenance `drained`, marker, lifecycle journal 부재,
컨테이너·이미지·PG/Redis 볼륨 소유권, 추가 volume consumer 부재, source cursor와 세 런타임 이미지
revision 일치, engine READY를 확인합니다. 컨테이너를 바꾸지 않지만 lock 파일은 생성될 수 있습니다.
성공도 백업·복구·migration·운영 승격 승인이 아닙니다.

```bash
python3 tools/ops/pep_migration.py prepare --server pep --confirm 'PREPARE pep' \
  --stack-dir /home/peppone_choi/opensamguk-docker
```

`rehearse`는 점검 창의 **정지와 원본 재개**가 승인된 경우에만 실행합니다.
main SHA, 그 SHA의 전체 CI 및 필수 여섯 job, main 계보를 `pep_loop.admit_snapshot`으로 직접 조회합니다.
후보 세 이미지 계약은 `ref`/`manifest`/`config`를 포함하며 로컬에 이미 존재하는 불변 GHCR digest,
linux/amd64, image config ID와 실제 OCI revision이 동일 SHA인지 대조합니다. pull/build/login을 실행하지 않습니다.
입력의 `backup=true`, `rollback=true`, `PASS` 같은 boolean은 실행 증거로 받지 않습니다.
느린 CI 조회 후 컨테이너 설정·이미지·노출과 전체 지원 형태를 다시 검사한 뒤 첫 정지를 시작합니다.

```bash
umask 077
python3 tools/ops/pep_migration.py rehearse --server pep \
  --confirm 'REHEARSE AND RESUME pep' \
  --stack-dir /home/peppone_choi/opensamguk-docker \
  --backup-root /absolute/private/recovery-root \
  --checkout /absolute/exact-main-checkout --source <40자리-main-SHA> \
  --images /absolute/immutable-three-role-image-contract.json
```

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
   각 앱 단계 전후와 원본 재개 직전에는 scenario 동반 사본과 원본 tree의 byte/hash도 대조합니다.
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
   정지 전 원본 Redis fingerprint와 모든 cold clone, 재개한 원본 Redis fingerprint도 대조합니다.
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
원자적 admission, PRIVATE/fullbundle의 map bytes·exposure 보존, 운영 backup 복원과 구 이미지 인증 읽기,
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
전체 기존 public row/column/sequence, Redis fingerprint, scenario 사본을 실제 `clone_stage`로 대조합니다.
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
