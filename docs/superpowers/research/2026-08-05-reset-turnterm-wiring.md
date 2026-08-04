# 리셋 옵션 배선 — turnTerm 절반 폐쇄와 그 과정에서 나온 사실들

브랜치: `fix/reset-options-wiring` (origin/main 기준)
작성일: 2026-08-05 · 상태: **turnTerm 배선 완료(미푸시), 나머지 12개 옵션 미착수**

선행 조사: `docs/superpowers/research/2026-08-04-server-reset-investigation.md`
(브랜치 `fix/select-pool-pick-live-gap`에 있음)

---

## 0. 선행 조사의 서술을 두 군데 정정한다

코드를 끝까지 추적하는 과정에서 앞선 조사가 사실과 다른 부분이 나왔다. 먼저 정정한다.

### 정정 1 — `RESET_*` 키는 "Kotlin 소비자 0"이 아니라 "게임 엔진 소비자 0"이다

앞선 조사는 `DeployService.kt:62-74`의 13개 `RESET_*` 키에 소비자가 없다고 적었다.
실제로는 **`.github/workflows/reset-game-server.yml`이 전부 소비한다**:

- `:154` `TURN_TERM="$(env_or_default RESET_TURNTERM 60)"`
- `:237-248` 나머지 12개를 `env_or_default`로 읽음
- `:264-283` 16개 필드 JSON 본문을 만들어 deployer `POST /servers/reset`에 보냄

어드민 UI 경로도 같은 16필드 JSON을 보낸다(`web/gateway/app/admin/page.tsx:88-105`).

즉 **두 리셋 경로 모두 옵션을 deployer까지는 정상 전달한다.** 끊기는 지점은 그 뒤,
게임 엔진이 그 값을 읽을 방법이 없다는 데 있다. "옵션이 전달되지 않는다"가 아니라
"전달된 옵션을 엔진이 안 읽는다"가 정확한 서술이다.

### 정정 2 — 결함의 위치는 `ScenarioSeedRunner`가 맞다

`SeedBootstrap`(`ScenarioSeedRunner.kt`)의 `turnTerm`은 `SCENARIO_QA_TURNTERM`
하나만 보고, 그것도 `"1"`만 받았다. 그 외에는 무조건 `DEFAULT_TURN_TERM = 60`.
`BootstrapConfig.kt:32-45`의 `@Bean seedBootstrap`도 `SCENARIO_*` 넷만 주입했다.
이 부분은 선행 조사가 맞다.

---

## 1. 무엇을 고쳤나

`SeedBootstrap.resolveTurnTerm(qaTurnTerm, resetTurnTerm)` 도입.

우선순위: **`SCENARIO_QA_TURNTERM` > `RESET_TURNTERM` > 60.**
QA fence를 위에 둔 이유는 그것이 더 좁고 명시적인 opt-in이며 기존 테스트가 의존하기 때문이다.

허용 집합 `ALLOWED_TURN_TERMS = [120, 60, 30, 20, 10, 5, 2, 1]`은
`.github/workflows/reset-game-server.yml:157`의 `case` 문과 **같은 값**이다.
백엔드가 워크플로보다 관대하면 워크플로 리셋과 어드민 UI 리셋이 서로 다른 월드를 만든다.

이 일치를 주석이 아니라 **실행되는 검사**로 뒀다 —
`SeedBootstrapTurnTermTest.허용 집합이 reset-game-server 워크플로와 일치한다`가
워크플로 파일을 직접 읽어 대조한다. 한쪽만 고치면 red가 된다.

**잘못된 값은 조용히 60으로 떨어지지 않고 부팅을 실패시킨다.** 지금 닫는 결함이
"운영자가 고른 옵션이 말없이 버려짐"인데, 같은 실패 양상을 새로 만들 수는 없다.

## 2. 테스트가 잡은 실제 결함 — 비-ASCII 자릿수

`kotlin.String.toIntOrNull()`은 `Character.digit` 기반이라 아라비아-인도 숫자를 파싱한다:
`"١٢٠".toIntOrNull() == 120`. 이 값은 `ALLOWED_TURN_TERMS`에 들어 있으므로 첫 구현은
**통과시켰다**.

워크플로의 `case "$TURN_TERM" in 120|60|...)`는 리터럴 ASCII만 매치하므로, 백엔드가
이걸 받으면 워크플로가 거부하는 값으로 월드가 시드된다. 파싱 앞에 `^[0-9]+$` 가드를 뒀다.

발견 경위를 남겨둔다: 테스트를 먼저 쓰고 구현을 좁혔다. 고친 것은 테스트가 아니라 구현이다.

---

## 3. 아직 안 닫힌 것 (중요)

### 3-A. 이 저장소 변경만으로는 동작하지 않는다 — 크로스 저장소

`RESET_TURNTERM`이 게임 엔진 프로세스까지 닿으려면 **`opensamguk-docker`의
`docker-compose.server.yml`이 game-engine 서비스 `environment`에 전달해야 한다.**
현재 그 서비스가 받는 것은 `GAME_DATABASE_URL`·`GAME_DB_*`·`REDIS_*`·
`TURN_PROFILE_NAME`·`OPENSAMGUK_WORLD_ID`·`SCENARIO_SEED_ENABLED`·`SCENARIO_CODE`·
`SCENARIO_DIR`·`JAVA_OPTS`뿐이다.

⇒ **이 저장소만 머지하면 코드는 맞지만 값이 오지 않는다.** 두 저장소를 함께 내보내야 한다.
docker 쪽은 이미 PR #29(시나리오 바인드)·#30(deployer preflight)이 대기 중이므로 묶는 것이 맞다.

### 3-B. 워크플로가 죽은 `SCENARIO_HOST_DIR`를 계속 다시 쓴다

`.github/workflows/reset-game-server.yml:178-187`(및 `:190-199`)이 리셋마다
서버 env 파일에 `SCENARIO_HOST_DIR=./data/scenarios`를 기록한다.

이 상대경로가 바로 프로덕션에서 외부 시나리오 오버라이드를 무력화한 값이다
(deployer가 컨테이너 `/workspace` 안에서 compose를 실행하므로 호스트 데몬이
`/workspace/data/...`로 해석해 빈 디렉터리를 만든다).

docker PR #29가 머지되면 compose가 `SCENARIO_HOST_DIR`를 더 이상 읽지 않으므로
**기능적으로는 무해해진다.** 그러나 워크플로에는 죽은 설정이 남아 다음 사람에게
"마운트가 설정돼 있다"고 오독시킨다. 후속 정리 대상.

이 항목은 어느 티켓·조사 문서에도 없었다. 이번 추적에서 처음 나왔다.

### 3-C. 나머지 12개 옵션은 여전히 월드에 닿지 않는다

`sync` `fiction` `extend` `blockGeneralCreate` `npcMode` `showImgLevel`
`autorunUserOptions` `autorunUserMinutes` `joinMode` `tournamentTrig`
`reserveOpen` `preReserveOpen`.

이들은 `turnTerm`처럼 단순 배선이 아니다. `world_state.config`에 들어가야 하는 값이라
`ScenarioImporter`의 config 생성 경로(`:188` `"turnterm" to turnTerm`, `:250`, `:849`)를
건드려야 하고, 각 키의 이름·타입·기본값이 PHP 원본과 맞는지 대조가 필요하다.
**별도 작업으로 뗀다.** 여기서 같이 하면 turnTerm 배선의 검증 범위가 흐려진다.

### 3-D. 형제 파싱 지점은 같은 결함이 아니다 (확인함, 고치지 않음)

비-ASCII 자릿수 가드를 한 곳에만 넣는 것이 증상 수리인지 확인하려고 게임 엔진·
게이트웨이·게임 API의 `toIntOrNull` 호출부를 전수 확인했다. 대부분은 DB에서 온
`userId`/`namespace`라 운영자 입력이 아니다. **env에서 오는 것은 셋뿐이다:**

- `ScenarioSeedRunner.kt:117-122` — `SCENARIO_CODE`. 이미
  `SCENARIO_CODE_PATTERN = Regex("scenario_(0|[1-9]\\d*)")`로 걸러진다.
  Java 정규식 `\d`는 `UNICODE_CHARACTER_CLASS` 없이는 `[0-9]`이므로 **이미 ASCII 전용**이다.
- `config/WorldIdConfig.kt:11` — `OPENSAMGUK_WORLD_ID`. 가드 없음.
- `config/DaemonLoopConfig.kt:241` — `SCENARIO_CODE`의 `removePrefix` 폴백. 가드 없음.

뒤 둘은 가드가 없지만 **같은 결함 부류가 아니다.** `RESET_TURNTERM`에 ASCII 가드가
필요한 이유는 "입력 위생" 일반론이 아니라, 이 값이 **셸 `case` 문
(`reset-game-server.yml:157`)과 같은 판정을 내려야 한다**는 구체적 일치 요구 때문이다.
셸 `case`는 리터럴 ASCII만 매치하므로 Kotlin이 더 관대하면 두 리셋 경로가 갈라진다.

`OPENSAMGUK_WORLD_ID`에는 대응하는 셸 판정이 없다. 관대해도 갈라질 상대가 없다.
⇒ **일부러 넓히지 않는다.** 기록만 남긴다.

---

## 4. 증거

- `:app:game-engine:test --tests '*SeedBootstrapTurnTermTest*'` → 6 tests / 0 failures / 0 errors
- 첫 실행은 1 failed(비-ASCII 자릿수)였고, 구현을 좁힌 뒤 그린. 두 결과 모두 위에 기록했다.
- 전체 모듈 회귀는 별도로 기록한다(생성자 파라미터 추가가 기존 호출부에 미치는 영향 확인용).
- 프로덕션 변경·배포·커밋 푸시는 이 작업에서 **수행하지 않았다.**
- `.env` 파일·토큰·키를 읽거나 출력하지 않았다.
