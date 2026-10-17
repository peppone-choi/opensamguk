# Localhost 재야 인수 실행

이 실행기는 CI 이미지 작업이 은퇴한 뒤 새 격리 localhost stack에서 기존
`docs/qa/user-stories/재야.md`의 US-WAND-01/02를 관측한다. 기존 QA160 fixture,
운영 DB, 키, 사용자 계정에 연결하지 않는다. 장수 준비는 정상 가입·로그인·생성 API로 하며
이 준비만으로 US-WAND-10의 화면 인수를 통과했다고 표시하지 않는다.

## 준비

Docker 저장소의 nonce localhost 하네스로 정확한 통합 app SHA를 빌드·기동한다.
정본 nginx, `https://localhost`의 loopback 443, 신규 별도 auth/game DB 및
`scenario_3190`, world160, generation0, 실제 120초 턴이 필요하다.
실행기는 custody의 `contract.json`과 신규 `fixture.json`만 소비한다.
기존 `.env`·운영키를 읽지 않는다. custody는 현재 사용자의 0700 디렉터리,
JSON 파일은 심볼릭 링크가 아닌 0600 파일이어야 한다.

`contract.json`은 `nonce`(16자리 hex), `project=osglha<nonce>`,
`serverId=lha<nonce>`, `worldId=160`, `generation=0`, `origin=https://localhost`,
정확한 40자리 `appSha`·`dockerSha`, 소유 채팅이 smoke를 마친 `status=SMOKE_PASSED`를
제공한다. 실행기는 Docker 작성 채팅을 가장하거나 소유권을 바꾸지 않는다. `fixture.json`은 별도 ADMIN과
USER의 `username`·`password`를 `admin`·`user` 객체로 제공한다. ADMIN은 신규
DB의 AdminSeeder만 만들고 가입·로그인 설정을 열 때만 쓴다. 일반 계정을 SQL로 승격하지 않는다.

## 실행

앱 채팅 간 GUI 인수 실행창을 먼저 조율한다. 실제 Chromium과 Playwright 1.55.1이
설치된 의존성 경로를 사용하고 전역 TLS 신뢰 저장소는 변경하지 않는다.

```sh
node --test tools/qa/localhost-user-journey.test.mjs
node tools/qa/localhost-user-journey.mjs \
  --custody /absolute/new-private-custody \
  --harness /absolute/docker-worktree/tools/localhost-acceptance/harness.py \
  --playwright-root /absolute/app-worktree/web/game \
  --output /absolute/new-journey-results
```

출력 디렉터리는 새로 만들며 custody와 분리한다. 비밀번호·토큰·쿠키·인증응답 원문·trace를
출력하지 않는다. screenshot은 인증 완료 후의 로비·작전실·턴 반영 화면만 저장한다.
로컬 자체 TLS 인증서 예외는 해당 브라우저 context에만 적용한다.

데스크톱과 모바일은 다른 신규 재야를 쓴다. 각 재야는 반대 프로필의 새 context에서도
정상 재로그인하여 같은 장수와 소속 없음 상태를 확인한다. 견문은 01순(`slot=1`,
서버 `turnIdx=0`)에 한 번 입력한다. requestId·예약 revision·실제 자기 턴의
RESOLVED/ok·경험/헌신 증가·슬롯 소비·기록·새로고침 일치를 모두 요구한다.
DB 관측은 하네스의 소유권 검증을 거친 읽기 전용 snapshot 명령으로만 한다.

`result.json`에는 정확한 app/docker/tester SHA, 실행기·하네스 SHA256, 시각,
프로필별 PASS/FAIL/NOT_RUN, 정제된 단계 증거가 들어간다. 실패한 실행을 재시도할 때는
같은 장수를 덮어쓰거나 원본을 지우지 않고 새 nonce stack과 출력 경로를 사용한다.
정리도 하네스가 자기 자원 소유권을 검증한 뒤에만 수행한다.

이 두 스토리의 성공은 두 모드 완주, 실기기 최종 아트, 성능 soak, 재시작·epoch 복구,
전체 JVM 회귀를 대신하지 않는다. CI 비활성·skip은 PASS가 아니다.
