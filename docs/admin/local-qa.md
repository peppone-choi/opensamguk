# 격리된 로컬 QA 서버

이 도구는 `opensamguk-qa160` Docker 프로젝트만 만든다. UI는
`http://127.0.0.1:18300`, 서버 ID는 `qa160`, world ID는 `160`이다.
인증 DB `qa160_gateway`와 게임 DB `qa160_game`, Redis, bridge network,
모든 볼륨과 새 인증키를 별도로 만든다. 기존 서버 설정·키·볼륨을 사용하지 않는다.

등록 metadata의 `deployProject`는 gateway 정본 좌표인 `opensamguk-sqa160`을
사용한다. 실제 Docker 프로젝트는 `opensamguk-qa160`이다. QA deployer는 비활성화되어
있으며 이 metadata를 이용한 배포·외부 공개는 수행하지 않는다. 신규 gateway DB의
정상 registry bootstrap이 등록과 admission 원천을 준비한다.

## 이미지와 기동

먼저 main에 병합된 `.github/workflows/build-local-qa-images.yml`을 실행한다.
입력 `source_sha`는 성공한 main CI의 전체 커밋이다. 여섯 이미지 모두 같은
소스를 기존 Dockerfile로 빌드한다. UI 공개 주소는 위 loopback 주소로 고정한다.
태그는 `<service>-qa160-<source_sha>-<run_id>`이며 production/latest 태그를 쓰지 않는다.
게임 이미지는 기존 `build_game_jvm_images.sh enrich-internal` 경로와 시나리오
계약 시험을 거친다. 예전 후보 발급기의 폐기된 deploy 단계 이름 검사에는 의존하지 않는다.

```sh
python3 tools/qa/local-stack.py prepare --source-sha <main-full-sha> --run-id <successful-image-run>
python3 tools/qa/local-stack.py validate --custody <printed-private-directory>
python3 tools/qa/local-stack.py up --custody <printed-private-directory>
```

`prepare`는 새 임시 디렉터리를 0700, env·credential 파일을 0600으로 만든다.
출력은 디렉터리 경로뿐이다. `validate`는 비밀값을 출력하지 않고 compose의
격리와 자원 한도를 검사한다. Docker compose를 직접 호출해 resolved config를
출력하지 않는다. `up`은 이미지의 source label을 대조하며 로컬 빌드를 하지 않는다.

총 메모리 한도는 6 GiB 이하, 이미지 확보 중 디스크 증가 한도는 6 GiB다.
매 단계에서 여유 3 GiB 이상과 load5 400 이하를 확인한다. 이미지 pull·기동·fixture
변경은 메타 `.locks/heavy-run`을 원자적으로 취득한 실행만 수행한다. 다른 레인이
사용 중이면 기다렸다가 다시 호출하며 남의 잠금을 지우지 않는다.

정상 `scenario_3190` seed에 `RESET_TURNTERM=2`, `RESET_MAXGENERAL=50`,
`RESET_BLOCK_GENERAL_CREATE=0`을 전달한다. 기존 엔진 허용 목록에는 3분이 없어
허용 범위 2–5분 안의 120초를 사용한다. 실제 `world_state.tick_seconds`와 턴
진행으로 확인하기 전에는 기동·턴 검증을 완료로 기록하지 않는다.

## 이미지 교체와 rollback 뒤 라우팅

nginx의 정적 `proxy_pass`는 기동·reload 때의 upstream IP를 사용한다. gateway나
web 컨테이너를 재생성하면 health가 정상이어도 기존 nginx는 옛 IP를 사용할 수
있다. `local-stack.py up`은 compose의 health wait 성공 뒤 `refresh_routes`를 호출해
현재 설정의 `nginx -t`가 성공한 경우에만 reload한다. 설정 파일은 덮어쓰지 않는다.

별도 승격 실행기도 **설치와 rollback 양쪽**의 read surface `compose up --wait`
성공 직후, 첫 로그인·후속 검증 전에 로드한 STACK의 `refresh_routes(custody)`를
호출해야 한다. QA 운영자가 보유한 기존 heavy 잠금 안에서 호출하며, 함수는
잠금을 재취득하거나 DB·fixture·이미지·설정을 변경하지 않는다. 검사나 reload의
실패는 그대로 전파한다. 설치 실패는 기존 rollback으로 처리하고, rollback의
라우팅 갱신 실패도 복구 성공으로 숨기지 않는다. 이후 정상 계정 login/me를 실제
확인한다. 이미 V77인 DB에서는 기존 reservation revision까지 보존 비교한다.

관련 로컬 단위시험은 `python3 tools/qa/test_local_stack.py`다. 실제 nginx의
IP 교체·설치 실패 후 rollback 재연결 시험 `test_routing_docker.py`는 CI에서만
독립 Docker project/network와 HTTP fixture로 실행한다. 이 결과는 실제 인증·
V77 DB 보존·QA 서버 재시험의 성공을 대신하지 않는다.

### front-info 실패의 최소 진단

`tools/qa/front-diagnostics.py`의 `read_front(api, token, actor_id, record)`는
기존 `role-fixtures.py`의 API 함수를 고정 경로
`/api/game/api/front-info?server=qa160`에 한 번 호출한다. `actor_id`는 정상
로그인을 마친 행의 `row["generalId"]`이며 계정 ID가 아니다. 성공 시 기존 반환
객체를 그대로 돌려주고, HTTPError만 진단 후 같은 예외 객체를 다시 던진다.
기존 인증·30초 timeout·판정·retry·once guard·DB 검증은 호출자가 유지한다.

`record`는 진단 dict 하나를 받는 콜백이며 성공 시 호출되지 않는다. 필드는
`actorId`, 고정 `path`, `status`, 허용 MIME의 `contentType`, `errorCode`,
`errorMessage`, `elapsedMs`의 일곱 개다. JSON은 최대 4097바이트만 읽고
4096바이트 초과 시 code/문구를 버린다. code는 정본 admission의
`AUTH_REQUIRED`·`SERVER_NOT_PUBLIC`·`SERVER_ADMISSION_UNAVAILABLE`만 허용한다.
문구는 정본 프록시의 「게임 서버를 찾을 수 없습니다.」와 admission의
「서버 공개 상태를 확인할 수 없습니다.」에 정확히 일치할 때만 남긴다.
응답 원문·임의 message·토큰·헤더·계정명은 기록하거나 출력하지 않는다.
파싱·콜백 실패도 원 요청 예외를 가리지 않는다.

QA 운영자가 검산된 정본 helper를 별도 private 파일로 전달받은 뒤
`runpy.run_path(helper_path)["read_front"]`로 로드할 수 있다. 로딩은 조회나
운영을 수행하지 않는다. 설치·rollback의 두 front 호출에만 연결하며, 예를 들어
`record` 콜백은 기존 stage의 `frontDiagnostics` 배열에 진단 dict만 추가한다.
원 V3와 실패 CP는 보존한다. 실제 연결본·정본 전달과 이후 실행은 각각 C0의
정확한 인수·승인 뒤 진행하며, helper 병합으로 재승격이나 새 card 발급이
승인되는 것은 아니다. 원 body가 없는 과거 503의 원인은 이 helper로 소급 확정하지 않는다.

`python3 tools/qa/test_local_stack.py -v`는 기존 QA 단위시험과 진단 TestCase를
함께 실행한다. CI도 같은 진입점을 사용한다. 단위시험은 실제 QA의 16계정
재연결·DB 보존·503 원인 확인을 대신하지 않는다.

## 계정·인물과 신분 준비

신규 QA 인증 DB는 기본적으로 가입·로그인을 허용하지 않는다. `prepare`가 생성한
QA 신규 관리자(`qa160admin`)로 정상 로그인한 뒤 이 전용 서버의 관리 API
`/api/gateway/admin/system/allow_join`과 `allow_login`에 각각 `{"value":true}`를
POST하고 응답을 확인한다. 이 정책 준비는 QA 계정 생성에만 적용하며 기존 서버를
대상으로 수행하지 않는다. 비밀번호·토큰은 private custody 안에서만 사용한다.
장수 생성은 게임 UI와 같은 `/api/game/api/join?server=qa160` 경로를 사용한다.


```sh
python3 tools/qa/role-fixtures.py accounts --custody <private-directory>
python3 tools/qa/role-fixtures.py roles --custody <private-directory> --fixture-plan <public-plan.json>
python3 tools/qa/role-fixtures.py verify --custody <private-directory>
```

`accounts`는 실제 회원가입·로그인·`/auth/me`와 장수 생성 접수를 사용한다.
군주3·중간관리3·일반6·재야4의 16개 계정은 모두 정상 USER 계정이다. 엔진이
생성한 `general.user_id`·인물 이름·NPC 상태까지 확인한다. 비밀번호·발급 토큰은
출력하지 않는다. 이미 생성한 계정은 private custody로 로그인해 이어간다.

신분 fixture는 계정 생성 검증과 별개다. 실제 seed에서 선택한 세력·현·위치 증인의
정수 ID 세 개씩을 아래 형식으로 기록한다. 적대 양측의 현은 실제 연결된 경로에
있어야 하며 지형·소유·보급 원천을 실데이터에서 확인한다.

```json
{"nationIds":[1,2,3],"cityIds":[4,5,6],"positionGeneralIds":[7,8,9]}
```

`roles`는 QA engine/API를 멈추고 world160만 한 트랜잭션으로 설정한다. 실제
계정 소유 인물을 군주·소속 인물로 연결하고, 중간관리에는 실제 NPC 부하2명과
부곡을 배정한다. 비군주가 사람 부하를 가지지 않는 게임 규칙을 지킨다. 각 세력은
군주와 중간관리의 두 지휘관·부곡을 갖는다. 기존 NPC를 삭제하거나 사용자
계정의 인증 role을 변경하지 않는다. fixture 표지를 남겨 재실행으로 QA 진행을
덮지 않는다. 오류 때 engine/API를 다시 기동한다.

A–B는 실제 교전 코드0, A–C는 실제 불가침 코드7이다. 현재 구현에는 별도 동맹
코드가 없어 불가침을 동맹 검증 성공으로 기록하지 않는다. 동맹 기능의 화면·설계
차이는 QA 재현과 제품 수리 대상으로 따로 추적한다.

## 실제 기능 판정

API setup 성공과 브라우저 로그인 성공은 별도로 기록한다. 실제 UI로 각 신분의
입력·권한 거절·예약 인자 readback·engine 실행·상태 변화·새로고침을 확인한다.
전쟁은 양측 동시 출병, 행군/강행, 조우·전투, 아군 병력 공개·적군 정보 제한,
공성/항복/퇴각, 보급·포로·사상과 종료 상태를 포함한다. 버튼·HTTP200·예약
접수만으로 실행 완료를 판단하지 않는다. 모바일과 데스크톱은 같은 게임 상태를 쓴다.

실제 재현 결함은 기존 담당/티켓과 대조한 뒤 QA-FIX에 이관하고, 수정 PR이
CI·독립 리뷰·병합을 마치면 해당 main 이미지에서 실제 UI로 재시험한다.
기동·계정·신분·전기능 검증이 남아 있으면 상위 QA 티켓을 완료 처리하지 않는다.
