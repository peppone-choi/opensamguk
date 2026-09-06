# 게임 서버 냉간 백업과 복구 검증

상태: 도구의 로컬 검증과 운영 복구 증거는 별개입니다. **각 운영 작업의 저장소 복원, 이전 이미지의 실제
월드 재적재, 사용자 경로 확인이 기록되기 전까지 production reset/delete는 `UNKNOWN / blocked`입니다.**
이 문서는 실행 권한을 새로 부여하지 않습니다. 운영자가 승인된 점검 창과 정확한 대상 `pep`를 확인합니다.

## 범위와 성공 판정

[`game_server_recovery.py`](../../tools/ops/game_server_recovery.py)는 다음 두 작업만 수행합니다.

- `capture`: 이미 종료된 `spep-web-game`, `spep-game-api`, `spep-game-engine`, `spep-game-postgres`,
  `spep-game-redis`의 identity를 검사하고 서버 env, Compose, 다섯 서비스의 실제 이미지 ID를 저장합니다.
  `spep-game-pgdata`, `spep-game-redisdata`의 전체 내용을 읽기 전용으로 tar에 담습니다. 서비스를 중지하지 않습니다.
- `verify`: 전체 파일 목록·크기·SHA256·서버 identity·tar 경로와 링크를 검증한 후 새 scratch volume에
  숫자 uid/gid를 보존해 복원합니다. 저장한 정확한 PostgreSQL/Redis 이미지로 `--network none`, 공개 포트 없이
  실행하고 로컬 소켓으로 확인합니다. 라이브 Compose나 운영 env로 앱을 실행하지 않습니다.

PostgreSQL의 연결, Flyway history 전체 성공, world/city 존재, env가 선택한 world 존재, world/city/nation/general
행 수, city ID 범위, migration version과 전체 논리 dump SHA256을 기록합니다. `pg_dump --no-owner --no-privileges`의
출력에서 도구가 만든 `\restrict`/`\unrestrict` 키 행만 제외해 해시를 계산합니다. SQL·플레이어 정보·world ID·토큰은
보고서에 쓰지 않습니다. Redis는 원래 `appendonly yes` 계약과 전체 AOF 디렉터리를 사용하며 `PONG`, 로딩 종료,
지속성 상태, key 수를 확인합니다. AOF 꼬리 자동 절단을 허용하지 않습니다.

`verification.json`의 `success=true`는 모든 저장소 검사와 **이번 실행이 만든 scratch 자원 정리**의 성공입니다.
`application_boot_verified=false`, `authenticated_smoke_verified=false`는 별도 운영 관문입니다.
실제 manifest SHA256에 묶인 보고서 없이 과거의 성공 결과를 재사용하지 않습니다. 재검증은 기존 보고서를 먼저
미완료 상태로 바꾸고 최신 결과를 기록하므로, 앞선 보고서는 별도 비공개 작업 증거 디렉터리에 보존합니다.

파일 백업은 [PostgreSQL 전체 클러스터 종료 조건](https://www.postgresql.org/docs/16/backup-file.html)을 따릅니다.
[Redis 7 지속성](https://redis.io/docs/latest/operate/oss_and_stack/management/persistence/)은 단일 AOF 파일만 복사하지
않고 전체 볼륨을 보존합니다. tablespace/WAL symlink, 외부 추가 mount, 다른 PGDATA, 다른 Redis command는 지원하지
않으며 발견하면 중단합니다. 운영 형태를 도구에 맞추려고 고치지 않습니다.

## 보존과 권한

PEP 작업의 보관 범위는 기존 GCP VM의 암호화된 영구 디스크이며 VM root/승인 운영자만 접근합니다.
[GCP 기본 저장 암호화](https://docs.cloud.google.com/docs/security/encryption/default-encryption)는 백업 디렉터리의
파일 접근 권한을 대신하지 않습니다. 새 디렉터리는 `0700`, 파일은 `0600`입니다. 원본 env와 image archive도 비공개
데이터입니다. Git, 이슈, 공개 로그, 외부 저장소에 올리지 않습니다.

최소 **30일 AND rollout/rollback 인수 확인까지** 보존하며 자동 삭제하지 않습니다. 이 백업은 같은 VM/디스크에
있으므로 VM 전체 또는 디스크 유실에 대한 재해 복구를 보장하지 않습니다. RPO는 앱 writer 종료 후 마지막 DB commit이며
flush되지 않은 메모리 작업은 포함하지 않습니다. RTO 목표는 30분이나 실제 운영 복구의 측정 전에는 보장하지 않습니다.
manifest의 capture 시각, verification의 restore 시각과 전체 점검 시작/복구 완료 시각을 각각 기록합니다.

도구는 Linux의 `/tmp/opensamguk-production.lock`을 최대 10초 동안 기다려 기존 운영 작업과 직렬화합니다.
Docker 기본 로컬 소켓만 사용하며 원격 Docker 환경 변수는 받지 않습니다. lock은 capture/verify 각각의 실행을
보호합니다. 같은 `Recovery` instance의 같은 PID/thread에서 `locked()`를 중첩하면 가장 바깥 descriptor를
유지합니다. 다른 instance/process/thread는 그 잠금을 재진입할 수 없습니다. CLI lock 우회 옵션은 없습니다.
**전체 stop → source fingerprint → capture → verify → 동일 container 재개를 같은 프로세스의 검토된 operator
harness로 잠글 때까지 실제 운영 실행은 BLOCKED입니다.** 그 harness는 별도 작업이며 아직 검증되지 않았습니다.
외부 shell에서 lock을 잡고 별도 프로세스의 CLI를 호출하는 것은 재진입이 아니므로 시간 초과합니다.

현재 control-plane의 maintenance GET `open`은 다른 작업이 없다는 증거가 아닙니다. maintenance POST enter는
비대상 서버를 포함한 active job을 취소하고 무기한 기다릴 수 있으므로 PEP-only 권한으로 호출하지 않습니다.
원자적으로 새 작업을 막되 기존 비대상 작업을 취소하지 않는 admission 경로 또는 별도의 명시적 권한 조정이
필요합니다. source promotion workflow가 maintenance를 우회할 수 있으므로 전체 host lock 소비자도 확인해야 합니다.

## 이미 충족된 CREATE 관리 메타데이터 조정

원격 생성이 이미 완료되어 현재 Gateway `game_server`의 전체 정의가 생성 transition과 같지만,
deployer가 해당 정확한 operation ID를 엄격한 `not_found` 404로 응답하는 오래된 잔존
CREATE transition이 있을 수 있습니다. 이 경우에만 ADMIN이 다음 Gateway 경로로 조정을 요청할 수
있습니다.

```http
POST /admin/servers/{canonicalServerId}/operations/{operationId}/reconcile-satisfied-create
Content-Type: application/json

{"confirm":"RECONCILE CREATE {canonicalServerId}"}
```

이 endpoint는 모두 일치할 때만 transition 한 행을 삭제합니다.

- operation ID와 canonical server ID가 정확하고 확인 문구 외 다른 JSON 필드가 없음
- CREATE가 24시간 이상 되었고 `dispatched=true`, `remote_applied=false`, 이전 lease가 만료됨
- transition과 현재 `game_server`의 ID, 이름, game-api/game-engine URL, deploy project, generation,
  scenario code가 transaction lock 안에서 null까지 포함해 전부 같음
- deployer의 정확한 operation 조회가 다른 operation이나 일반 404가 아닌 엄격한 `not_found`임
- 완료 직전에도 owner, lease, age, flags, fingerprint와 모든 서버 정의가 그대로임

성공 응답은 `ok=true`, `reconciled=true`, `completed=true`이지만, 없는 원격 작업을
`succeeded`로 만들어 내지 않습니다. 성공 후 반복 요청은 404입니다. 조건 불일치·활성 lease·원격
pending/succeeded/failed와 같은 상태는 409, deployer/DB 조회 불가·5xx·timeout·잘못된 404는
503으로 종료되며 transition을 삭제하지 않습니다. 이 경로는 remote POST, CREATE 재전송,
`game_server` 등록/수정, 계정 수정, reset을 하지 않습니다.

endpoint 호출 **전**에 운영자가 실제 runtime identity, 서버 env, control repository registry가
Gateway의 현재 정의와 같은지 별도로 확인해야 합니다. management/lifecycle admission fence는
이 비교를 시작하기 전부터 remote operation 조회와 reconciliation이 끝날 때까지 계속 유지해야
합니다. 이 endpoint는 그 fence를 설정하지도, 유지 여부를 검증하지도 않습니다. 이 API가 증명하는 것은
잠긴 Gateway DB 내의 정의 일치와 정확한 remote 404뿐입니다. 조정 성공은 냉간 복원,
application drill, promotion, reset의 완료나 실행 승인이 아닙니다.

외부 `data/scenarios` bind의 실제 파일은 이 bundle에 포함되지 않습니다. 앱 drill과 live 복구 전에는 당시의
effective scenario 입력과 현재 입력이 동일함을 별도로 검증해야 합니다. 이미지와 DB 복원만으로 외부 scenario
입력의 보존을 증명하지 않습니다. 다른 서버와 공유하는 scenario 디렉터리를 이 절차에서 바꾸지 않습니다.

## 운영 캡처 순서

아래 경로는 운영자가 확인한 절대 경로로 지정합니다. shell tracing(`set -x`)을 끄고 로그 접근을 제한합니다.
기존 비공개 env를 화면에 출력하거나 shell로 `source`하지 않습니다.

```bash
umask 077
STACK=/absolute/control/repo
RECOVERY_ROOT=/absolute/recovery/root
TOOL=/absolute/reviewed/checkout/tools/ops/game_server_recovery.py
install -d -m 700 "$RECOVERY_ROOT"
cd "$STACK"
```

1. 현재 operation/journal/registry 상태와 사용자의 작업 승인을 확인합니다. pending/repair-required가 있으면
   promotion/reset/delete를 중단하고 지원되는 control-plane 조회·수리 절차로 해결합니다. 실제 실행 중인 작업이
   없음을 확인하고 별도 승인한 점검·냉간 capture/verify·동일 container 재개는 이 차단과 구분합니다. 다른 서버와 공유 계정 서비스는
   조작하지 않습니다. 유지보수 장벽과 진행 중 배포·reset 금지를 작업 시작부터 재개까지 유지합니다.
2. 서버별 현재 이미지 ID, world/scenario/map/기수, Flyway version, 도시 수와 핵심 read를 비공개로 기록합니다.
3. PEP web/API intake를 먼저 차단·종료하고 engine을 종료합니다. **daemon pause만으로 API intake는 차단되지 않습니다.**

   ```bash
   docker compose -p opensamguk-spep -f docker-compose.server.yml --env-file servers/spep.env stop -t 120 web-game game-api
   docker compose -p opensamguk-spep -f docker-compose.server.yml --env-file servers/spep.env stop -t 120 game-engine
   ```

4. 비공개 container inspect/engine 로그로 정상 shutdown과 마지막 flush를 확인합니다. OOM, SIGKILL, 미해결 recovery
   상태가 있으면 중단합니다. PostgreSQL이 아직 실행 중인 이 시점에 committed row counts와 world identity를 기록합니다.
   동일 PostgreSQL 이미지의 `pg_dump --no-owner --no-privileges`를 비공개 파일로 받아 helper의
   `logical_dump_hash`로 정규화한 SHA256을 기록합니다. dump는 비공개 작업 디렉터리에만 둡니다.
5. Redis/PostgreSQL을 정상 종료하고 종료 상태를 확인합니다.

   ```bash
   docker compose -p opensamguk-spep -f docker-compose.server.yml --env-file servers/spep.env stop -t 120 game-redis game-postgres
   python3 "$TOOL" capture --server pep --confirm 'BACKUP pep' --stack-dir "$STACK" --backup-root "$RECOVERY_ROOT"
   ```

6. 출력된 **정확한** bundle 경로를 `BUNDLE`로 지정해 검증합니다. `INCOMPLETE` bundle은 사용하지 않습니다.

   ```bash
   BUNDLE=/absolute/recovery/root/pep-exact-captured-token
   python3 "$TOOL" verify --server pep --confirm 'VERIFY pep' --bundle "$BUNDLE"
   ```

7. `verification.json`의 source server, manifest SHA256, counts, versions, 전체 DB 논리 해시를 4번 원본 관측과
   대조합니다. Redis key 수와 지속성 결과를 대조합니다. 불일치를 repair·재시드·blind retry로 없애지 않습니다.
8. 별도 disposable clone에서 이전 engine 이미지의 실제 재적재를 검증합니다. seed는 반드시 비활성화하고 공유
   gateway/account/registry로 나가는 경로가 없는 격리 네트워크만 사용합니다. **Compose의 project 이름만 바꾸면
   격리되지 않습니다**: container/volume 이름이 `spep-*`로 고정되기 때문입니다. 별도 이름·volume·network를 명시한
   검토된 앱 drill을 사용합니다. daemon 비활성화 상태의 health `UP`는 lazy world rehydrate 증거가 아닙니다.
   clone 전용 durable plock 또는 폐기 가능한 clone tick을 사용한 활성 daemon 관측으로 `serviceMaterialized=true`,
   recovery READY, 핵심 read, migration/world identity 불변을 확인합니다. 이 helper는 앱 drill을 수행하지 않습니다.
9. 저장소+앱 관문을 기록한 뒤 승인 범위에 따라 기존 production 컨테이너를 재개하거나 작업을 진행합니다. 기존 것을
   재개할 때는 이미지 pull/recreate 대신 `docker start spep-game-postgres spep-game-redis`, DB readiness 확인,
   `docker start spep-game-engine`, engine recovery 확인, `docker start spep-game-api spep-web-game` 순서입니다.
   인증된 입장/read/명령 terminal/SSE/턴 전진은 실제 사용자 경로에서 별도로 확인합니다.

## 승인된 rollback의 정확한 교체 순서

아래는 저장소 원본 교체 권한이 포함된 점검 창에서만 사용하는 절차입니다. image-only rollback이나 Flyway 역실행이
아닙니다. `BUNDLE`은 **해당 작업에서 검증한 이전 이미지+이전 DB/Redis+PEP env의 같은 세트**여야 합니다.
helper에는 live restore 명령이 없습니다. 운영자가 교체할 대상과 image override를 검토한 다음 진행합니다.

1. 유지보수 장벽과 control-plane operation 상태를 확인하고 위 순서대로 web/API → engine을 정지합니다.
   PostgreSQL이 실행 중일 때 현재 실패 월드의 committed 증거를 남긴 뒤 Redis/PostgreSQL을 정지합니다.
   동일 `capture` 명령으로 현재 실패 월드도 새 bundle에 보존합니다. 이전 bundle을 덮어쓰지 않습니다.
2. 이전 bundle의 기존 verification 보고서를 별도 비공개 작업 디렉터리에 보존한 뒤 `verify`를 다시 실행합니다.
   이전 앱 image의 clone 재적재 증거와 schema 호환성을 다시 확인합니다. 실패하면 production volume을 교체하지 않습니다.
3. 아래는 **이미 quiesce·현재 실패 월드 capture·이전 bundle 재검증을 끝낸 뒤의 storage 교체 부분만** 담은
   단일 실행 블록입니다. 전체 운영 harness와 외부 scenario 입력의 보존 증거가 검토되기 전에는 실행하지 않습니다.
   `TOOL`, `BUNDLE`, `FAILED_BUNDLE`, `STACK`, `OPERATION_DIR`를 확인한 절대 경로로 환경에 지정합니다.
   `FAILED_BUNDLE`은 1번의 현재 실패 월드 bundle이며 이전 `BUNDLE`과 다릅니다. 모든 명령의 실패는 함수에서
   즉시 반환하여 후속 삭제·추출·env 복원을 막습니다. 중간 줄을 따로 복사해 실행하지 않습니다.

   ```bash
   (
   set -eu
   umask 077
   rollback_storage() {
     : "${TOOL:?}" "${BUNDLE:?}" "${FAILED_BUNDLE:?}" "${STACK:?}" "${OPERATION_DIR:?}" || return 1
     exec 9>/tmp/opensamguk-production.lock || return 1
     flock -w 10 9 || return 1
     install -d -m 700 "$OPERATION_DIR" || return 1
     python3 - "$TOOL" "$BUNDLE" "$FAILED_BUNDLE" "$STACK" "$OPERATION_DIR" <<'PY' || return 1
   import sys
   from pathlib import Path
   sys.path.insert(0, str(Path(sys.argv[1]).parent))
   from game_server_recovery import Recovery, checked_path, selected_env, write_private, json_bytes, read_json, digest, require
   bundle, failed_bundle, stack, operation = map(Path, sys.argv[2:])
   checked_path(operation, directory=True, private=True)
   checked_path(stack, directory=True)
   checked_path(stack / 'servers/spep.env')
   helper = Recovery()
   manifest, _ = helper.validate_bundle('pep', bundle)
   failed_manifest, _ = helper.validate_bundle('pep', failed_bundle)
   require(bundle != failed_bundle, 'separate current failure capture required')
   verification = read_json(bundle / 'verification.json')
   require(verification.get('success') is True and verification.get('manifest_sha256') == digest(bundle / 'manifest.json')['sha256'], 'current verified bundle required')
   current_env = selected_env(stack / 'servers/spep.env', 'pep')
   containers, volumes = helper.source('pep', stack, current_env)
   require(containers == failed_manifest['containers'] and volumes == failed_manifest['volumes'], 'current failure capture identity drift')
   require((stack / 'servers/spep.env').read_bytes() == (failed_bundle / 'server.env').read_bytes(), 'current env drift')
   services = {service: {'image': record['image_id']} for service, record in manifest['containers'].items()}
   services['game-engine']['environment'] = {'SCENARIO_SEED_ENABLED': 'false'}
   write_private(operation / 'exact-images.yml', json_bytes({'services': services}))
   PY
     PG_IMAGE=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["containers"]["game-postgres"]["image_id"])' "$BUNDLE/manifest.json") || return 1
     REDIS_IMAGE=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["containers"]["game-redis"]["image_id"])' "$BUNDLE/manifest.json") || return 1
     docker --host unix:///var/run/docker.sock container rm spep-web-game spep-game-api spep-game-engine spep-game-postgres spep-game-redis || return 1
     docker --host unix:///var/run/docker.sock volume rm spep-game-pgdata spep-game-redisdata || return 1
     docker --host unix:///var/run/docker.sock volume create --label com.docker.compose.project=opensamguk-spep --label com.docker.compose.volume=game-pgdata spep-game-pgdata || return 1
     docker --host unix:///var/run/docker.sock volume create --label com.docker.compose.project=opensamguk-spep --label com.docker.compose.volume=game-redisdata spep-game-redisdata || return 1
     python3 - "$TOOL" <<'PY' || return 1
   import sys
   from pathlib import Path
   sys.path.insert(0, str(Path(sys.argv[1]).parent))
   from game_server_recovery import Recovery, require
   helper = Recovery()
   for suffix in ('game-pgdata', 'game-redisdata'):
       name = 'spep-' + suffix
       volume = helper.inspect('volume', name)
       require(volume['Name'] == name and volume['Driver'] == 'local' and not volume.get('Options'), 'replacement volume mismatch')
       require(volume['Labels'].get('com.docker.compose.project') == 'opensamguk-spep' and volume['Labels'].get('com.docker.compose.volume') == suffix, 'replacement volume ownership mismatch')
   PY
     docker --host unix:///var/run/docker.sock run --rm --network none --user 0 --read-only --entrypoint sh --mount type=volume,source=spep-game-pgdata,target=/var/lib/postgresql/data,readonly "$PG_IMAGE" -ec 'test -z "$(find /var/lib/postgresql/data -mindepth 1 -print -quit)"' || return 1
     docker --host unix:///var/run/docker.sock run --rm --network none --user 0 --read-only --entrypoint sh --mount type=volume,source=spep-game-redisdata,target=/data,readonly "$REDIS_IMAGE" -ec 'test -z "$(find /data -mindepth 1 -print -quit)"' || return 1
     docker --host unix:///var/run/docker.sock run --rm -i --network none --user 0 --entrypoint tar --mount type=volume,source=spep-game-pgdata,target=/var/lib/postgresql/data "$PG_IMAGE" -xpf - --numeric-owner -C /var/lib/postgresql/data < "$BUNDLE/postgres.tar" || return 1
     docker --host unix:///var/run/docker.sock run --rm -i --network none --user 0 --entrypoint tar --mount type=volume,source=spep-game-redisdata,target=/data "$REDIS_IMAGE" -xpf - --numeric-owner -C /data < "$BUNDLE/redis.tar" || return 1
     install -m 600 "$BUNDLE/server.env" "$STACK/servers/spep.env" || return 1
   }
   rollback_storage || exit 1
   )
   ```

   이 블록은 새 live restore CLI가 아닙니다. 현재 앱 재개까지의 전체 실행 잠금은 아직 검증되지 않았으므로 운영
   승인 관문은 계속 BLOCKED입니다. 내부 Python의 직접 검사 함수는 shell의 lock을 새로 잡지 않습니다. 생성한
   override는 JSON 형식의 유효한 YAML이고, 기존 동일 이름 파일은 덮어쓰지 않습니다. Docker 대상은 preflight와
   동일한 기본 로컬 소켓으로 고정하며 사용자 context로 바꾸지 않습니다. 실패 시 부분 진행 상태와
   보존 bundle을 조사하며 재실행하지 않습니다.
4. 앱 재개는 별도 검토할 전체 operator harness에 포함해야 합니다. 기존 control directory에서 `COMPOSE_HOST_DIR`를
   고정하고 이전 `compose.yml`과 다섯 image ID override를 직접 지정하여 `--pull never`로 DB/Redis → engine →
   API/web 순서로 진행합니다. DB readiness·원본 counts/fingerprint, engine materialized/recovery READY 검사는 각각
   다음 단계의 실제 중단 조건이어야 합니다. 댓글이나 수동 확인 지시만으로 뒤 명령이 자동 진행되게 하지 않습니다.
   원래 image tag가 archive에 보존된다고 가정하지 않으며 공유 Compose 파일을 덮어쓰지 않습니다.
5. registry/journal과 Gateway DB의 lifecycle 상태는 지원되는 control-plane 작업으로만 정합성을 맞춥니다.
   per-game DB 복원으로 공유 상태가 자동 rollback되지 않습니다. shared registry/account DB/Redis를 통째로 복원하지
   않습니다. 직접 deployer 호출로 Gateway 상태 전이를 우회하거나 journal SQL을 고치는 명령을 사용하지 않습니다.
   `repair-required`가 해결되지 않으면 재개를 중단합니다.
6. 실제 이미지 ID, counts/fingerprint, world/scenario/map/기수, health/materialized/recovery, 인증 read/권한/명령/SSE와
   턴 전진을 확인합니다. 인수 완료 시각·실제 RTO·남은 제한을 비밀 없는 운영 report에 기록합니다.

## 로컬 검증과 실패 처리

```bash
python3 tools/ops/test_game_server_recovery.py
RUN_RECOVERY_DOCKER_TESTS=1 python3 tools/ops/test_game_server_recovery_docker.py
```

빠른 행동 테스트는 CI에서 실행합니다. 실제 Docker 테스트는 명시적 opt-in이며 직접 실행할 때 미설정이면
`NOT RUN`과 실패 exit를 냅니다. unittest 로더/discovery로 가져올 때도 `RUN_RECOVERY_DOCKER_TESTS=1`이
아니면 사유가 표시된 skip으로 처리하여 Docker에 접근하지 않습니다. 로컬 fixture 이미지만 사용하며 서비스 앱 대신 `/bin/true`를 실행합니다.
따라서 로컬 Docker 통과도 실제 PEP 데이터나 application boot를 검증했다는 뜻이 아닙니다.

capture 실패는 `INCOMPLETE` bundle을 남깁니다. verify 실패는 성공 표시를 남기지 않으며 `cleanup.remaining_resources`의
자원은 정확한 이름·label·identity를 확인해 수동 처리합니다. 다른 작업의 자원을 지우거나 자동 retry하지 않습니다.
운영 보고서에는 결과, 실행 코드 커밋, 검증 종류, 데이터 비교 결과, 남은 위험을 기록하고 원본 비공개 데이터는 첨부하지 않습니다.
