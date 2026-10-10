# PEP 대상·계정 저장소 경계 읽기 전용 진단

이 도구는 `pep`/Docker 내부 key `spep`의 현재 대상 식별과 공유 계정 저장소 분리를 관측한다.
업데이트·초기화·삭제·maintenance 변경을 실행하거나 승인하지 않는다. 백업 존재 여부는 이 진단의
입력이나 성공 조건이 아니다. `CONSISTENT`는 아래 제한된 관측의 일치이며 배포 준비, 복구 성공,
계정 보존 완료, 로그인·명령 검증의 증거가 아니다.

## 실행 경계

`.github/workflows/pep-target-diagnostics.yml`은 입력 없는 `workflow_dispatch` 전용이다.
정확한 `peppone-choi/opensamguk`, `refs/heads/main`, 해당 실행의 `github.sha`만 허용한다.
`[self-hosted, Linux, X64, gcp-prod-opensamguk]`을 사용하며 `gcp-prod` fallback은 없다.
runner 이름과 label은 서로 다른 개념이다. 이 label의 실제 등록·기존 sudo 허용 여부는 아직
실행으로 확인하지 않았다. 다른 runner나 새 권한으로 대체하지 않는다.

별도 checkout은 기존 runner source/WIP를 청소하지 않는다. `contents: read`,
`persist-credentials: false`, 전용 concurrency와 `cancel-in-progress: false`를 사용한다.
기존 `pep-private-loop` 대기 작업을 이 진단의 concurrency로 합치지 않는다.
기존 noninteractive sudo로 `python3 -I -B ... observe`만 호출하고 secrets를 넘기지 않는다.
sudo 거절은 job 실패이며 토큰·계정·권한을 새로 만들지 않는다.

도구는 stdlib만 사용하며 `observe` 외의 CLI는 exit 64로 종료한다.
고정 `/usr/bin/docker --host=unix:///var/run/docker.sock`, `shell=False`, 최소 host 환경을 사용한다.
허용 명령은 다음뿐이다.

- `container ls --all` 및 고정 네 volume의 consumer filter
- 최대 512개 container를 64개씩 제한된 format으로 `container inspect`
- 관측한 image config ID의 제한된 `image inspect`
- 고정 네 volume의 제한된 `volume inspect`
- 구조·연결·world 설정이 모두 일치한 정확한 game PG container ID의 고정 읽기 전용 psql exec

pull/run/start/stop/rm/prune/logs/cp/redis-cli, host lock 생성·변경, control 저장소 sync,
HTTP·maintenance 호출, Redis key 조회, 파일·백업 생성 경로는 없다. 전후 snapshot 사이의
coherence만 검사하며 운영 lock을 소유하거나 writer를 동결하지 않는다. 따라서 관측 직후의
변화나 snapshot 사이에 바뀌었다가 되돌아온 상태를 막거나 증명할 수 없다.

job 제한은 10분, 도구 전체 제한은 240초다. child 명령은 20초, psql은 30초 이내이며
각 child의 stdout+stderr 합계는 스트리밍으로 4MiB 이하로 제한한다. stderr는 보관·반사하지 않는다.
raw inspect/env/연결 값/예외는 파일·artifact·보고서에 저장하지 않는다.

## 정본 이름과 분리 판정

앱 main 기준은 `4a9c746cea87c1ac5e37709a8f00f71c3b6e2f45`다.
game storage ownership은 [기존 preflight](../../tools/ops/pep_cold_capture_operator.py)의
`opensamguk-spep` project와 `game-pgdata`/`game-redisdata` volume key 계약을 그대로 따른다.

| 역할 | 고정 이름 | 저장소 |
| --- | --- | --- |
| 게임 PG | `spep-game-postgres` | `spep-game-pgdata` → `/var/lib/postgresql/data` |
| 게임 Redis | `spep-game-redis` | `spep-game-redisdata` → `/data` |
| 엔진 | `spep-game-engine` | 위 게임 저장소로의 연결을 내부 비교 |
| PUBLIC consumer | `spep-game-api`, `spep-web-game` | PRIVATE와 동시에 존재하면 MIXED |
| PRIVATE consumer | `spep-game-api-validation`, `spep-web-game-validation` | 정지된 반대 consumer도 혼재로 처리 |
| 공유 계정 API/PG | `opensamguk-gateway-api`, `opensamguk-gateway-postgres` | `opensamguk-shared_gateway-pgdata` |
| 공유 Redis | `opensamguk-gateway-redis` | `opensamguk-shared_gateway-redisdata` |

공유 이름과 volume key는 control repo
[`6f6d2a6`의 docker-compose.shared.yml](https://github.com/peppone-choi/opensamguk-docker/blob/6f6d2a6cf7a67c003528989f9420fb16d109b548/docker-compose.shared.yml)에 근거한다.
실제 이름·project·volume key가 다르거나 공유 Redis를 찾지 못하면 추정하지 않고 UNKNOWN/UNSAFE다.
공유 PG·Redis의 실제 mount를 관측하고 게임 volume과 분리된 local/default layout인지 검사한다.
다른 driver/options, bind 저장소, 추가 저장소 mount, mountpoint 겹침은 일치로 취급하지 않는다.
전체 container inventory의 mount와 volume filter를 대조하며 stopped consumer도 포함한다.
알 수 없는 consumer 이름·label은 출력하지 않고 고정 사유와 존재 여부만 기록한다.

`GAME_DATABASE_URL`, `GAME_DB_USER`, `GAME_DB_PASSWORD`, `REDIS_HOST`, `REDIS_PORT`는 내부 검증·drift
비교에만 사용한다. 값과 해시는 출력하지 않는다. 같은 NetworkID의 container 이름/alias를 실제
PG·Redis container ID에 매핑한다. 서로 다른 URL/alias가 같은 endpoint로 연결되면 분리 증거가 아니다.
alias 충돌, host override, 외부/알 수 없는 endpoint는 거절한다. DB명·user는 source-backed PG 환경과
내부 비교하며 임의 역할/다중 host/URL credentials/query 옵션은 지원하지 않는다.
Spring/JVM override key는 값 대신 boolean으로만 관측하고 존재하면 UNKNOWN이다.
세 앱 Dockerfile이 확장하는 JAVA_OPTS는 내부에서만 검사하며 source-backed GC·RAM percentage·Xms/Xmx·
urandom entropy flag 외의 옵션은 UNKNOWN이다. image와 다른 command/entrypoint도 UNKNOWN이다.
container env와 image metadata의 일치 관측이며 실행 중 JVM의 effective configuration을 직접 증명하지 않는다.

image inspect ID와 container image ID, 40자리 소문자 OCI revision, API/engine/web 역할간 revision 일치를
확인한다. OCI label과 RepoDigest는 metadata이며 서명·source provenance 검증을 대신하지 않는다.

## 실제 world와 SQL 범위

API와 engine의 `OPENSAMGUK_WORLD_ID`가 같은 양의 PostgreSQL integer일 때 그 실제 W만 사용한다.
`1`, 최신 world, 기본 scenario를 추정하지 않는다. 읽는 항목은 다음뿐이다.

- `world_state WHERE id=W`: id, scenario_code, config의 `maxgeneral`·`block_general_create` key 존재/값
- `game_kv WHERE world_id=W AND "table"='game_env' AND namespace='game_env'`:
  `maxgeneral`, `block_general_create` 두 key의 값

스키마 근거는 V1의 `world_state`, V7의 `game_kv`, V32의 `game_kv.world_id` 확장과
[기존 WORLD_SQL](../../tools/ops/pep_loop.py)이다. 전체 config/meta, 계정 행·계정 수, general 수,
Flyway, seed controls, generation은 읽거나 출력하지 않는다.

`BEGIN READ ONLY`, statement timeout 5초, lock timeout 1초, idle transaction timeout 10초,
`ROLLBACK`을 고정한다. psql은 `-X -A -t -q -w -v ON_ERROR_STOP=1`, local socket/port만 사용하며
container 내 최소 환경으로 PGHOSTADDR/service/password-file 같은 ambient 연결 변경을 배제한다.
비밀번호는 argv/파일/출력으로 전달하지 않는다. 인증·schema 불명은 UNKNOWN이다.
SQL 출력은 최대 8줄, 줄마다 4KiB다.

JSON number의 양의 정수 maxgeneral만 정상으로 판정한다. 숫자 문자열·boolean·소수·범위 밖 값은
정수로 강제 변환하지 않는다. `50.0`처럼 정수인 JSON number는 50으로 정규화한다.
block은 비음수 정수이며 bit 1이 설정됐는지 보고한다. 두 store의 검증된 정수 값 불일치는
UNSAFE, 누락/잘못된 타입/생성 차단 bit 미설정은 UNKNOWN으로 기록하며 값을 고치지 않는다.
scenario는 canonical `scenario_<number>` 문법으로 검증한다.

## 출력과 한계

stdout은 `schema: pep-target-diagnostics/v1`의 정제 JSON 한 줄이다.
고정 result/reasons/mode와 containers/volumes/bindings/world/drift만 포함한다.
source-backed 이름·검증된 hex identity·숫자·enum만 출력하며 raw stderr/환경/URL/host path/사용자 데이터는 없다.

| result | exit | 의미 |
| --- | --- | --- |
| CONSISTENT | 0 | 지원 범위의 전후 metadata와 제한된 world 관측이 일치 |
| UNKNOWN | 2 | 관측/권한/schema/지원 형태가 불명 |
| UNSAFE | 3 | 혼재·ownership·consumer·저장소/역할 binding 등의 불일치 |
| IDENTITY_DRIFT | 4 | 전후 container/image/revision/state/mount/volume/consumer 또는 원래 연결 값 변경 |
| UNKNOWN + INTERNAL | 1 | 내부 오류, 정제 사유만 기록 |
| UNKNOWN + USAGE | 64 | 허용되지 않은 CLI |

drift 비교에는 원래 연결 값이 메모리에서 그대로 포함되지만 값이나 해시는 결과에 없다.
진단이 일부 진행된 뒤 관측 자체가 실패하면 UNKNOWN으로 종료하며 부분 결과를 성공 증거로 남기지 않는다.
SQL은 world 구조/설정의 제한된 조회다. world 데이터 삭제, 계정 보존, 로그인, 실제 명령/턴/SSE의
완료 판정은 별도 실행 후 증거가 필요하다. 이번 변경으로 배포/초기화 workflow나 보호 gate를 연결·수정하지 않는다.

## 합성 검증

```bash
python3 -I -B tools/ops/test_pep_target_diagnostics.py -v
python3 -m py_compile tools/ops/pep_target_diagnostics.py tools/ops/test_pep_target_diagnostics.py
git diff --check
```

FakeTransport로 PUBLIC/PRIVATE, actual W, consumer·alias·volume/mountpoint collision, stopped consumer,
bind/driver/options, env 중복/NUL, revision, 연결 값 drift와 SQL 타입·범위를 검증한다.
stream cap/timeout은 Docker 대신 짧은 로컬 Python child로 검증한다. 비밀 canary·URL·PEM·GitHub command
문자열·예외가 보고서로 반사되지 않는지, subprocess 명령 allowlist와 workflow 계약도 검사한다.
실제 Docker/DB·runner sudo·workflow dispatch·업데이트·초기화는 이 합성 검증에서 실행하지 않는다.
