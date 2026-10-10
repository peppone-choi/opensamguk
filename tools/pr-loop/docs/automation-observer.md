# 관측 전용 개발 자동화

`work-observe`는 명시적으로 받은 snapshot을 준비 목록과 배정 **제안**으로 바꾼다.
프로세스 실행, 실제 claim/registry/tracker 쓰기, PR 생성/병합, 삭제 기능은 없다.
기존 work-unit AC parser와 issue/input/scope 충돌 계약을 재사용하며 별도 package와
입력 파일을 사용한다. 기존 `work-queue`, watcher, bundle hash와 그 기본값을 바꾸지 않는다.
검증된 미병합 work-unit 변경 위의 작은 확장이므로 정상 통합 때 해당 lib가 있어야 한다.
제품 구현이나 외부 핫픽스 레인의 착수는 이 도구 설치를 선행 조건으로 삼지 않는다.

## 재현

저장소 루트에서 Python 표준 라이브러리만 사용한다.

```sh
python3 tools/pr-loop/bin/work-observe \
  --input tools/pr-loop/fixtures/automation-observer.json \
  --now 2026-10-10T03:00:00+00:00
python3 -m unittest discover -s tools/pr-loop/tests -p 'automation_lane_test.py'
bash tools/pr-loop/bootstrap/setup-observer.sh --workspace .. --checkout opensamguk="$PWD"
```

fixture의 issue·HEAD·자원·배정은 합성이며 실제 저장소 상태나 사용 가능한 실행 권한이 아니다.
출력은 항상 `OBSERVE`, `executionAllowed:false`다. activation은 별도 검토와 승인,
실제 호스트/도구 lease 및 원래의 안전·승인·독립 리뷰·CI 계약을 요구한다.
실제 registry 경로는 자동으로 읽지 않는다. snapshot 파일은 최대 2 MiB이며
신뢰할 수 있는 호출자가 명시적으로 준비한다. 이 JSON은 인증이나 서명된 receipt가 아니다.

## 준비와 독립 레인

`automation-snapshot/1`은 `units`, `dependencies`, `lanes`, `activeLeases`,
`occupiedLanes`, `resources`를 포함한다. unit의 project/task/repo/HEAD, issue refs,
선택 AC, 그 HEAD의 계획, 필요한 결정, 의존성, runtime profile, 쓰기 경로와 자원 추정이 필요하다.
이슈 의존성은 `closed`, 명시적인 `unit:PROJECT/TASK` 의존성은 `UNIT_DONE`으로 구분한다.
내부 단위의 완료로 이슈 전체가 닫혔다고 해석하지 않는다.
오픈삼국 AC block은 기존 parser를 사용한다. 다른 프로젝트는 `normalized/1` adapter의
HEAD·validator version·지문·criteria·depends를 공급하며 프로젝트의 런타임을 유지한다.

- 불완전 항목은 `PREPARATION`에 이유와 다음 조치를 남기며 다른 항목을 버리지 않는다.
- 승인·안전 차단은 `BLOCKED`로 남는다. 합격 증거가 있어도 배정 제안을 만들지 않는다.
- product/planning/review/docs 큐와 레인 capability는 분리한다. 문서 레인이 없어도 리뷰 레인은 배정된다.
- 계획·리뷰는 해당 HEAD의 독립 읽기 전용 snapshot을 요구한다. 구현 lease가 남아 있어도
  읽기 전용 검토는 가능하며 provider lease는 별도로 필요하다.
- writer의 issue ref 충돌은 전역, command와 파일 경로 충돌은 같은 repo에서 검사한다.
  active lease의 repo·목록 타입·경로를 검사하며 파일 범위가 없거나 잘못되면 해당 repo의 writer를
  보류한다. repo 또는 전역 issue 범위가 미확인이면 모든 writer를 보류한다.
  malformed lease도 보고에서 버리지 않으며 `activeLeaseReports`에 원인을 남긴다.
  기존 claim의 의미는 바꾸지 않는다.
- 새로운 제품 작업은 오픈삼국을 우선한다. 대기 7일 이상 항목은 oldest-first로 앞선다.
  호환 레인·용량이 지속 제공되고 scope가 풀리는 조건에서 낮은 우선순위의 기아를 막는다.
- 선택 AC가 전체 기능의 일부여도 독립 단위를 제안한다. FOUNDATION은 `INTERNAL_FOUNDATION`,
  사용자 기능은 `FEATURE_SLICE`이며 어느 쪽도 issue 전체 완료를 선언하지 않는다.

계산 안에서는 앞선 제안의 파일/command scope와 자원을 예약하지만 실제 lock을 얻지 않는다.
동시 관측자 사이의 원자성은 없다. 기존 `work_units.claim.acquire`는 issue/input/command scope를
검사하지만 이 관측자의 `writePaths` 파일 충돌 검사를 수행하지 않는다. 따라서 기존 claim 호출만으로
파일 배정이 안전해지지 않는다. 향후 실행자는 파일 범위를 별도 lock 아래 다시 검증·예약하는 계약이
필요하며, 그 계약이 없는 상태에서는 관측 결과를 제품 실행에 연결하지 않는다.

## 검증 증거

receipt는 exact HEAD, 검사 paths/criteria/checks, 도구 이름/버전, AC/contract 지문,
기록 시각, PASS, failures/skip 0에 묶인다. 요청 범위를 덮고 24시간 이내이며 같은 HEAD이면
`REUSE_EXACT`를 제안한다. 지문·버전·범위·시각·실패·skip이 달라지면 재검증한다.
다른 HEAD는 완전한 diff와 프로젝트 위험 분류가 있을 때 변경되지 않은 범위의
`DELTA_CONTEXT_ONLY`만 제공한다. 현재 HEAD의 검토를 대체하거나 전체 PASS로 승격하지 않는다.
contract 또는 security 경로 변경은 재검증한다. 공통 도구/워크플로 경로의 기본 보호와
각 프로젝트가 공급한 추가 경로를 함께 사용한다. 위험 분류가 없으면 cross-HEAD 재사용하지 않는다.
모든 증거 판정은 `gateSatisfied:false`이며 원래의 승인·보호 차단을 해제하지 않는다.

## 자원과 산출물

현재 호스트 snapshot의 CPU 사용률, memory/disk used/total과 각 unit의 추정을 더해
어느 하나라도 90% 이상이면 보류한다. 관측은 300초 이내여야 하며 미확인/NaN/미래 시각은
용량으로 해석하지 않는다. heavy unit은 별도 정수 토큰 예산을 요구한다. 제안끼리도 예산을 공유한다.
`--observe-resources --disk-root PATH`는 procfs/cgroup/statvfs를 읽는다. 토큰은 기존 관리자 snapshot에서
공급하며 이 도구는 토큰을 획득하거나 프로세스를 종료하지 않는다. host별 snapshot을 별도로 만든다.
resource profile은 프로젝트별 runtime capability와 별개다.

heavy token에는 물리 자원과 별도의 `observedAt`과
`source:{"kind":"external-token-manager","id":"manager-id"}`가 필요하다. token 자체의 시각이
300초 이내이고 미래가 아니며 출처 형식이 확인되어야 제안 예산으로 사용한다. 누락·만료·미확인 출처는
heavy 제안을 보류한다. `--observe-resources`는 물리 자원만 갱신하며 token의 시각·출처를 그대로
보존한다. 출처는 snapshot 호출자의 선언이며 실제 관리자 인증·token 획득을 증명하지 않는다.

`--artifact-root PATH`를 명시한 경우에만 declared generated prefixes 아래의 개별 파일을 검사한다.
`generated-artifact/1` sidecar는 path/project-task owner/HEAD/content SHA-256,
generator 이름·버전, 생성·만료 시각, 재생성 argv를 포함한다. argv는 실행하지 않는다.
50 MiB 이내 regular file을 bounded read하며 symlink·경로 탈출·민감 파일은 보류한다.
일치한 provenance의 등급은 `LOCAL_RECEIPT_MATCHED`로 한정하고 외부 신뢰/서명을 주장하지 않는다.
만료되고 active owner가 없는 파일만 `CLEANUP_PROPOSAL`, `requiresApproval:true`로 출력한다.
active owner가 미확인인 경우에는 후보를 만들지 않는다. 파일/sidecar 변경·삭제는 하지 않는다.
snapshot에는 모든 실행자 owner 정보가 포함됐다는 `activeOwnersComplete:true`도 필요하다.

## 외부 핫픽스 반복 레인

`batches`는 repository/batchId/stage/findingsCount/HEAD/PR 목록과 차단을 관측한다.
SCANNING→PLAN_PENDING→IMPLEMENTING→REVIEW_PENDING→FIXING→READY_FOR_MERGE→DONE를
표시하며 저장소별 활성 batch 하나와 회차당 PR 하나를 검사한다. 계획 이후는 확인된 발견 5건을 요구한다.
READY_FOR_MERGE에는 exact HEAD의 독립 SOURCE_MERGEABLE 및 CI PASS가 필요하며 승인 차단은 유지한다.
repo·stage enum·양의 정수 PR 목록 등 batch 계약이 잘못되면 해당 repo의 writer를 보류한다.
repo도 미확인이면 모든 writer를 보류한다. 알려진 종료 상태라도 다른 필드가 잘못되면 비활성으로
단정하지 않는다. malformed batch를 활성 repo 검사에서 제거하지 않는다.
입력은 외부 runner의 선언을 표시할 뿐 실제 Codex/Claude 호출, 발견 확인 또는 병합 성공 증거가 아니다.
`observerRequiredToStart:false`이므로 runner는 하네스 없이 기존 절차로 착수할 수 있다.
관측자가 준비되면 동일 snapshot 계약으로 scope 충돌과 상태를 함께 보고한다.

malformed evidence 요청은 개별 `REVALIDATE`, malformed artifact·receipt·owner·generator는 개별
`OWNERSHIP_HELD`로 격리한다. 다른 유효한 큐·증거·산출물 보고는 유지한다. malformed lease가 있으면
active owner inventory도 미확인으로 처리해 정리 후보를 만들지 않는다.

## 오픈삼국+BP 환경 부트스트랩

`bootstrap/development-environment.json`은 project별 public configuration, skill 경로,
runtime 요구, 명령과 setup recipe의 합집합이다. BP는 실제 원본을 받기 전 `UNCONFIRMED`다.
현재 manifest의 오픈삼국 JDK 21·Node 22·pnpm 10.33.0은 기존 CI/설정의 요구이며
이 환경에 설치되었다는 뜻이 아니다. preflight가 관측 버전과 요구의 일치 여부를 따로 표시한다.
`--checkout PROJECT=PATH`로 기존 작업을 덮어쓰지 않는 checkout을 지정한다.

preflight는 allowed repository와 실제 checkout HEAD, tracked public 설정 hash와 SKILL.md 목록,
설치/버전 및 command availability를 읽는다. 선언된 작업 명령은 실행하지 않는다.
버전 probe는 고정 whitelist argv와 5초 제한을 쓰고 Corepack 네트워크를 끈다.
auth는 기존 표준 파일의 **존재 여부만** stat하며 값/세션을 읽거나 복사하지 않는다.
설정의 auth/env/local settings는 export 대상에서 제외한다. 공식 CLI/인증이 있는 별도 리뷰 환경의
파일이 여기에 자동 공유된다고 가정하지 않는다. 공식 Claude CLI가 없으면 계획은 NOT_RUN으로 인계한다.

`setup-observer.sh`는 재현 가능한 확인 진입점이며 도구 설치·login·clone·cloud 설정 변경을 하지 않는다.
setup recipe는 미실행 상태로 출력한다. 저장된 cloud environment 설정 변경 권한/도구가 없어도
이 manifest 준비를 실제 cloud 설정 변경으로 표현하지 않는다. BP의 허용 repo/기준 revision,
AGENTS/setup/runtime/skill 원본이 필요하며 확인 전에는 전체 bootstrap 완료를 주장하지 않는다.
