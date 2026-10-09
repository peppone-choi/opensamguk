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
   원본 세계의 plock, 입력, 시나리오, 설정을 수정하지 않습니다.
4. migration 전 모든 기존 public table의 모든 열·행과 sequence를 정렬 JSON으로 fingerprint합니다.
   새 generated 열은 기존 데이터 projection에서 제외하지만 원래 열·행·sequence 삭제/변경은 실패합니다.
   따라서 V78의 `nation_ref` 추가는 기존 `nation=0` 저장값을 보존할 수 있고, 데이터 재시드는 통과하지 못합니다.
   Redis는 16 DB 각각의 모든 key, `DUMP` 값과 절대 expiry를 비교합니다. key 수가 같은 값 교체도 실패합니다.
   TTL 만료 등 변화도 보수적으로 실패하며 자동으로 허용하거나 데이터를 고치지 않습니다.
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
새 코드가 게시·병합되지 않은 상태에서는 원격 main의 기존 자동배포 위험도 그대로입니다.

## 로컬 검증 범위

`test_pep_migration.py`는 admission/형태 불일치/소스 drift/inbox/백업/후보/rollback/원본 재개 실패를 모의 검사합니다.
이 테스트의 injected adapter 성공은 실제 운영 CI·backup·앱 boot를 대신하지 않습니다.
`test_pep_migration_docker.py`는 명시적 opt-in에서 실제 PG16/Redis7 임시 데이터를 생성하고 full cold capture,
storage restore, 승인된 V78 SQL, 기존 행·자산·sequence 보존, 자유 부대 nation=0, 음수·다른 world·leader FK 거절,
동일 key 수의 Redis 값 변경 감지, 원백업 rollback 및 원 저장소 재개를 확인합니다.
이 native 검사도 Spring/Flyway runner·구/후보 앱 image boot·운영 원본 재개·UI 검증이 아닙니다.

```bash
python3 -m unittest discover -s tools/ops -p 'test_pep_migration.py' -v
RUN_PEP_MIGRATION_DOCKER_TESTS=1 \
  python3 -m unittest discover -s tools/ops -p 'test_pep_migration_docker.py' -v
```

V78이 아직 이 checkout에 없으면 `PEP_MIGRATION_V78_SQL`로 실제 승인 소스 파일을 지정합니다.
검사는 승인된 SQL SHA256을 대조하며 fixture DDL로 대체하지 않습니다. 기본 opt-in 없는 skip은 실행 성공으로 세지 않습니다.
백업 접근·보관·RPO/RTO 한계는 [냉간 복구 문서](game-server-recovery.md)를 따릅니다.
