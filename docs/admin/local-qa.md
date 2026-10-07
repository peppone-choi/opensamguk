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
