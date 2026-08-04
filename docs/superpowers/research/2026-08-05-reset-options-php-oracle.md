# 리셋 옵션 12종 — PHP 오라클 대조표와 배선 계획

브랜치: `fix/reset-options-remaining` (origin/main 기준)
작성일: 2026-08-05
선행: `docs/superpowers/research/2026-08-05-reset-turnterm-wiring.md` (turnTerm 배선, #362로 머지됨)

PHP 근거는 전부 `legacy/devsam-core`에서 직접 읽었다. 추정한 값은 하나도 없다.

---

## 1. PHP 오라클 대조표

세 파일이 각각 다른 역할을 한다.

- `hwe/install.php` — 폼. **기본값**(`checked`/`selected`)과 **선택지 집합**의 출처.
- `hwe/j_install.php` — POST 처리. **타입 캐스팅**과 구조의 출처.
- `hwe/sammo/ResetHelper.php` — `buildScenario()`. **서버 검증**과 최종 시그니처.

| 옵션 | PHP 이름 | 기본값 | 근거 | 타입 | 근거 |
| --- | --- | --- | --- | --- | --- |
| turnTerm | `turnterm` | 60 | `install.php:58` | int | `j_install.php:105` |
| sync | `sync` | 1 | `install.php:74` | int | `j_install.php:106` |
| fiction | `fiction` | **1** | `install.php:98` | int | `j_install.php:108` |
| extend | `extend` | 1 | `install.php:107` | int | `j_install.php:109` |
| blockGeneralCreate | `block_general_create` | 0 | `install.php:117` | int | `j_install.php:111` |
| npcMode | `npcmode` | 0 | `install.php:129` | int | `j_install.php:110` |
| showImgLevel | `show_img_level` | 3 | `install.php:194` | int | `j_install.php:112` |
| tournamentTrig | `tournament_trig` | 1 | `install.php:204` | **bool** (`!!(int)`) | `j_install.php:113` |
| joinMode | `join_mode` | `full` | `install.php:181` | string | `j_install.php:114` |
| autorunUserMinutes | `autorun_user_minutes` | **1440** | `install.php:154-168` (아래 주의) | int | `j_install.php:115` |
| autorunUserOptions | `autorun_user[]` | 7개 전부 | `install.php:140-146` | `{key: 1}` 맵 | `j_install.php:117-120` |
| reserveOpen / preReserveOpen | `reserve_open` / `pre_reserve_open` | 없음(빈 값) | — | DateTime 문자열 | `j_install.php:141,184` |

**워크플로 기본값 12개가 이 표와 전부 일치한다**(`.github/workflows/reset-game-server.yml:236-247`).
어긋나는 곳은 백엔드 한 군데뿐이다(§3).

### 주의 — `autorun_user_minutes`의 `selected`가 둘이다

`install.php`에 `value="0" selected`(꺼짐, `:155`)와 `value="1440" selected`(24시간, `:168`)가
**둘 다** 있다. `multiple`이 아닌 `<select>`에서 HTML 파서는 마지막 `selected`를 택하므로
실효 기본값은 **1440**이고, 워크플로 기본값과 일치한다. PHP 원본의 흠이지 우리 쪽 오류가 아니다.
`0`으로 오독하면 `autorun_user`가 통째로 `null`이 되므로(아래) 기록해 둔다.

---

## 2. #362가 머지한 turnTerm 허용 집합을 사후 검증했다 — 통과

PHP에는 층이 둘이다.

- **UI 층** `install.php`의 turnterm 라디오 값: `120 60 30 20 10 5 2 1`
- **서버 가드** `ResetHelper.php:176`: `if(120 % $turnterm != 0)` → 120의 약수
  (`1 2 3 4 5 6 8 10 12 15 20 24 30 40 60 120`)

`SeedBootstrap.ALLOWED_TURN_TERMS = [120, 60, 30, 20, 10, 5, 2, 1]`은

- UI 층과 **정확히 같고**,
- 서버 가드의 **진부분집합**이다(8개 모두 120을 나눈다).

⇒ 우리가 받는 값은 PHP도 반드시 받는다. 역은 성립하지 않지만(예: 3, 4, 24), 그건
PHP UI도 제공하지 않는 값이다. **머지된 구현은 그랜드 트루스와 모순되지 않는다.**

기록해 둘 것: 만약 나중에 UI에 없는 약수(24 등)를 허용하고 싶어지면 `ResetHelper:176`의
약수 규칙이 정본이고, 그때는 고정 목록이 아니라 `120 % n == 0`으로 바꿔야 한다.

---

## 3. 발견한 실제 불일치 — `ScenarioImporter.fiction` 기본값

`infra/.../ScenarioImporter.kt:66`

```kotlin
private val fiction: Int = 0,
```

PHP 기본값은 **1**이다(`install.php:98`, `fiction_1`이 `checked`). 같은 생성자의 이웃
파라미터들은 근거 주석을 달고 PHP 기본값을 그대로 따른다 —
`npcMode = 0`("Legacy install.php 기본값 0 (`npcmode_0` checked)"),
`blockGeneralCreate = 0`, `showImageLevel = 3`, `extendedGeneral = true`. 전부 표와 일치한다.
`fiction`만 주석 없이 0이다. **의도된 divergence가 아니라 누락으로 판단한다.**

영향: `fiction`은 게임 동작에 쓰인다 — `hwe/func.php:1820`
`if (!$fiction && $general->getNPCType() > 0)`. 시드된 월드가 PHP와 다른 모드로 뜬다.

### 정정 — "이 값을 고정하는 테스트가 없다"는 내 서술은 틀렸다

이 문서 초안은 "`fiction`을 고정하는 테스트는 없다(전수 grep 0건)"라고 적었다. **사실이 아니다.**
`ScenarioImporterIT`가 두 군데에서 `"0"`을 단언하고 있었고, 기본값을 1로 바꾸자 실제로 red가 났다:

- `ScenarioImporterIT.kt:162` — `game_kv` (`game_env` 네임스페이스) `fiction`
- `ScenarioImporterIT.kt:403` — `ng_games.env ->> 'fiction'` (같은 값의 미러)

초안의 grep은 zsh 글로빙 실패로 범위가 비어 있었는데 그걸 "0건"으로 읽었다.
**검색이 0건인 것과 대상이 없는 것은 다르다** — 앞으로 이 구분을 지킨다.

### 그래서 어느 쪽을 고쳤나 — 구현이 아니라 기대값

PHP 근거를 다시 끝까지 따라갔다.

- `ResetHelper.php:297` — `'fiction'=>$fiction`. `game_env`에 들어가는 값은 **설치 폼 값**이다.
- `install.php:98` — 폼 기본값은 **1**(`fiction_1` checked).
- 시나리오 JSON 최상위 `"fiction":0`은 **별개 필드**다. PHP `sammo/Scenario.php`도
  우리 `ScenarioJson.Scenario`도 이 키를 파싱하지 않는다 — `game_env`로 가는 값이 아니다.
  (`Scenario.php:540-541` 주석이 fiction을 "install 변수"로 분류한다.)

⇒ IT의 `"0"`은 PHP에서 캡처한 골든이 아니라 **예전 Kotlin 기본값을 굳힌 값**이다.
그래서 구현을 되돌리지 않고 기대값을 1로 정정하고, 두 단언 위에 근거 path:line을 주석으로 남겼다.
단언을 지우거나 완화하지 않았다 — 값만 PHP가 말하는 값으로 바꿨다.

**이것은 골든 수정이 아니다.** 골든은 `logic/src/test/resources/golden/**`의 PHP 캡처 픽스처이고,
`ScenarioImporterIT`는 A-minimal 시드 계약을 검증하는 IT다. 그럼에도 기대값을 바꾼 이상
근거를 코드 주석과 이 문서 양쪽에 남긴다.

---

## 4. `reserveOpen` / `preReserveOpen`은 config가 아니다

`j_install.php:140-199`를 읽고 알았다. 이 둘이 설정되면 PHP는 **설치를 하지 않는다.**
대신:

1. 옵션 전체를 `$reserveInfo` JSON으로 묶어 `reserved_open` 테이블에 넣고(`:190-193`),
2. `closeServer()`로 서버를 닫는다(`:194`).

즉 **예약 설치(deferred install) 메커니즘**이고, 나머지 10개처럼 `world_state.config`에
들어가는 값이 아니다. opensamguk에는 `reserved_open`에 해당하는 테이블도, 예약 설치 경로도
없다.

⇒ **이 둘은 "배선" 대상이 아니라 미구현 기능이다.** 여기서 값만 흘려보내면 아무 데도
도달하지 않는다. 별도 기능 티켓으로 뗀다. 지금 억지로 넣지 않는다.

⇒ 남은 실제 배선 대상은 **12개가 아니라 10개**다. 이 정정을 선행 문서에도 반영해야 한다.

---

## 5. `autorun_user`의 구조 — 스칼라가 아니다

`j_install.php:117-139`:

```php
foreach(Util::getPost('autorun_user', 'array_string', []) as $autorun_option){
    $autorun_user_options[$autorun_option] = 1;      // 값이 아니라 "존재"가 의미
}
...
$autorun_user = $autorun_user_minutes ? [
    'limit_minutes' => $autorun_user_minutes,
    'options'       => $autorun_user_options
] : null;                                             // 0분이면 통째로 null
```

세 가지가 중요하다.

1. `options`는 리스트가 아니라 **`{키: 1}` 맵**이다. 삽입 순서가 보존돼야 한다
   (CLAUDE.md 규칙 6) → Kotlin에서 `LinkedHashMap`.
2. `autorun_user_minutes == 0`이면 `autorun_user`가 **`null`**이다. 빈 맵이 아니다.
3. 검증 두 개가 `j_install.php:123-133`에 있다 — 분 > 0인데 옵션이 비면 거부,
   분 < 0이면 거부. 배선할 때 같이 옮긴다.

기본 옵션 7개(`install.php:140-146`, 전부 `checked`):
`develop warp recruit recruit_high train battle chief`
— 워크플로 기본값 문자열과 순서까지 일치한다(`reset-game-server.yml:242`).

---

## 6. 배선 계획 (3단계로 뗀다)

`ResetHelper::buildScenario`의 시그니처(`ResetHelper.php:161-173`)가 정본 순서다:
`turnterm, sync, scenario, fiction, extend, block_general_create, npcmode,
show_img_level, tournament_trig, join_mode, turntime, autorun_user`.

### 단계 A — `ScenarioImporter`가 이미 받는 5개 ✅ 완료

`fiction` `extend` `blockGeneralCreate` `npcMode` `showImgLevel`.

생성자 파라미터가 이미 있고 `world_state.config` + `game_env`에 이미 쓰인다
(`ScenarioImporter.kt:185-199`, `:233-256`). **env → `BootstrapConfig` → `SeedBootstrap` →
`ScenarioImporter`** 배선만 하면 된다. turnTerm과 완전히 같은 패턴이다.
`fiction` 기본값 정정(§3)을 함께 넣는다.

### 단계 B — 새 파라미터가 필요한 3개

`sync` `joinMode` `tournamentTrig`. `ScenarioImporter`에 파라미터가 없고
`world_state.config`/`game_env` 키도 없다. PHP가 이 값들을 정확히 어느 저장소의 어느 키로
쓰는지(`gameStor` vs `world_state`) `ResetHelper::buildScenario` 본문을 더 읽어야 한다.
`j_server_basic_info.php:73`이 `join_mode`를 `gameStor`에서 읽는 것은 확인했다.

### 단계 C — 중첩 구조 1개

`autorunUserOptions` + `autorunUserMinutes` → `autorun_user`. §5의 세 가지를 그대로 옮긴다.
`j_server_basic_info.php:73`이 `autorun_user`를 `gameStor`에서 읽는다.

### 범위 밖

`reserveOpen` / `preReserveOpen` — §4. 미구현 기능이므로 별도 티켓.

---

## 7. 단계 A 구현 결과

### 바꾼 것

| 저장소 | 파일 | 내용 |
| --- | --- | --- |
| opensamguk | `app/game-engine/.../boot/ScenarioSeedRunner.kt` | `SeedBootstrap`에 5개 생성자 파라미터 + `resolveOption()` + PHP 근거 상수 10개 |
| opensamguk | `app/game-engine/.../config/BootstrapConfig.kt` | `@Value("\${RESET_*:}")` 5개 주입 |
| opensamguk | `infra/.../seed/ScenarioImporter.kt` | `fiction` 기본값 `0 → 1` (§3) |
| opensamguk | `infra/src/test/.../ScenarioImporterIT.kt` | `fiction` 기대값 2곳 `"0" → "1"` + 근거 주석 (§3 정정) |
| opensamguk | `app/game-engine/src/test/.../SeedBootstrapResetOptionsTest.kt` | 신규 5 테스트 |
| opensamguk-docker | `docker-compose.server.yml` | game-engine `environment`에 5키 전달 |
| opensamguk-docker | `deployer/main_test.go` | 기존 turnTerm 테스트를 6키 전체로 확장 |

### 설계 결정 — 조용한 폴백을 금지한다

`resolveOption`은 허용 집합 밖 값에서 기본값으로 떨어지지 않고 **부팅을 실패시킨다**.
이 작업이 닫는 결함이 "운영자가 고른 옵션이 말없이 버려짐"이므로 같은 실패 양상을
새로 만들 수 없다. `RESET_TURNTERM`과 동일한 규약이다.

ASCII 자릿수 가드(`^[0-9]+$`)도 그대로 재사용한다. `"١"`(아라비아-인도 숫자 1)은
`toIntOrNull`이 1로 파싱하지만 워크플로의 셸 `case`는 리터럴 ASCII만 매치하므로,
백엔드가 더 관대하면 두 리셋 경로가 갈라진다. 테스트가 이 값을 명시적으로 넣어 고정한다.

**기본값의 정본은 게임 엔진 한 곳이다.** compose와 `BootstrapConfig` 어디에도 숫자를
박지 않고 `${KEY:-}` / `\${KEY:}`로 비워 둔다. compose가 기본값을 들고 있으면 정본이
둘이 되고 한쪽만 바뀌었을 때 조용히 갈라진다.

### 실행되는 대조 검사 2개

주석이 아니라 테스트로 둔 것:

1. `SeedBootstrapResetOptionsTest.기본값이 reset-game-server 워크플로와 일치한다` —
   `.github/workflows/reset-game-server.yml`을 실제로 읽어 `env_or_default` 5줄의
   기본값을 파싱해 상수와 대조한다. 5키를 못 찾으면 `checkNotNull`로 실패시킨다(0건 조용 통과 금지).
2. `deployer/main_test.go` — compose가 6키를 전달하는지 + compose 쪽 기본값이 없는지 +
   `resetEnvUpdates`가 같은 키 이름으로 쓰는지.

### 범위를 일부러 넓히지 않은 곳

`docker-compose.server.yml`의 `wired` 목록에 `sync` `joinMode` `tournamentTrig`
`autorun*` `reserve*`를 넣지 않았다. 엔진에 소비자가 없으므로 전달해 봐야 닿지 않는다.
**닿지 않는 값을 전달하는 척하면 다음 사람이 "배선됐다"고 오독한다.** 단계 B/C에서 함께 넣는다.

### 반증 시도 — 골든 캡처는 PHP 폼 기본값으로 설치하지 않는다

`logic/src/test/resources/golden/p5/world-1010.json`에 `"fiction":0`이 있다. 이게 실제 PHP 캡처이므로
"PHP 기본값은 1"이라는 주장의 반증일 수 있어 끝까지 확인했다. **반증이 아니었다.**

`tools/php-golden/install_scenario.php:85-97`이 `buildScenario`에 넘기는 값은 폼 기본값이 아니라
하네스가 **명시적으로 고른 값**이다:

| 인자 | 하네스 | PHP 폼 기본값 |
| --- | --- | --- |
| `fiction` | 0 | **1** |
| `extend` | 0 | **1** |
| `show_img_level` | 1 | **3** |
| `tournament_trig` | false | **true(1)** |
| `block_general_create` | 0 | 0 |
| `npcmode` | 0 | 0 |

⇒ `world-1010.json`의 `fiction:0`은 하네스가 0을 넘겼기 때문이지 폼 기본값이 0이어서가 아니다.
골든은 불변으로 두었고 건드리지 않았다.

부수 발견 두 가지를 기록해 둔다.

1. `capture_general_builder.php:341-342` 주석은 "scenario_1010 install default는 가상=1"이라고
   적어 폼 기본값 1을 인정하면서도, 같은 하네스가 0으로 설치한다. 그래서 `:396`이 스스로
   "fiction 분기는 이 픽스처 세트로 **실행되지 않는다** — 날조하지 말고 fiction install로 넓혀라"라고
   남겨 두었다. **기존부터 문서화된 갭이며 이번 변경이 만든 것이 아니다.**
2. 따라서 "골든 월드 = 기본 설치 결과"라고 읽으면 안 된다. 폼 기본값을 확인하려면
   `install.php`의 `checked`를 봐야 한다.

---

## 8. 증거

- PHP 인용은 전부 실제 파일에서 읽은 것이다. `legacy/`는 읽기 전용 오라클이며 수정하지
  않았고 커밋하지 않는다.
- `fiction`을 고정하는 테스트가 없다는 초안의 서술은 **틀렸다**. §3의 정정 항목을 볼 것.
  실제로는 `ScenarioImporterIT`가 두 곳에서 고정하고 있었고, 전체 스위트를 돌려서(문서 주장이 아니라
  실행으로) 발견했다. 이 발견 자체가 "테스트를 돌리지 않고 안전하다고 말하지 않는다"의 사례다.
- turnTerm 집합 대조(§2)는 `install.php`의 라디오 `value` 전수 추출 + `120 % n` 계산으로
  확인했다.
### 최종 게이트 결과

```
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew \
  :infra:test :app:game-engine:test :app:game-engine:baselineClasses --rerun-tasks --no-daemon
BUILD SUCCESSFUL in 9m 11s
```

test-results XML 집계 (exit code가 아니라 XML로 확인):

| | tests | skipped | failures | errors |
| --- | --- | --- | --- | --- |
| infra + game-engine 합계 | **976** | 1 | **0** | **0** |
| `ScenarioImporterIT` | 21 | **0** | 0 | 0 |

- `ScenarioImporterIT`는 Docker가 있어 **실제로 실행됐다**(skip 아님). skip을 통과로 읽지 않았다.
- skipped 1건은 `LongSimReplayGateTest` — P5 백로그(long-sim multi-turn)로 기존부터 스킵이며 이번 변경과 무관.
- 중간 상태 기록: `fiction` 기본값을 바꾼 직후 첫 실행은 **1 failed**(`ScenarioImporterIT`)였다.
  두 번째 실행도 **1 failed**(같은 함수 안의 미러 단언 `:403`). 두 실패 모두 위에 그대로 남긴다.
- docker 저장소: `TestServerComposePassesResetOptionsToGameEngine` PASS.
  compose에서 `RESET_NPCMODE` 한 줄을 지우고 `-count=1`로 재실행해 **실제로 FAIL하는지 증명**했다
  (`game-engine must receive the reset option "RESET_NPCMODE: ${RESET_NPCMODE:-}"`) — 실패할 수 없는
  테스트는 가치가 없다. 그 뒤 그 한 줄만 정확히 복원했다(`git checkout` 금지 — 같은 파일에 다른 변경이 있었다).
  주의: `go test`는 패키지 외부 파일 변경을 캐시 키로 보지 않아 compose만 바꾸면 stale PASS가 난다. `-count=1` 필수.
- docker 저장소의 기존 실패 3건(`TestRecreateWorkflow*`)은 이 작업 이전부터 있던 것이다.
  앞선 세션에서 순수 `origin/main` worktree로 동일 실패를 재현해 회귀가 아님을 확인했다(이슈 #31).
- 프로덕션 배포·커밋 푸시는 이 작업에서 수행하지 않았다. `.env` 파일·토큰·키를 읽거나 출력하지 않았다.
- `legacy/`는 읽기 전용 오라클로만 사용했고 수정하지 않았다.
