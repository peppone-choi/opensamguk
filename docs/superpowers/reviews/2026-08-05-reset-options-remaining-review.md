# Review: 리셋이 고른 나머지 시나리오 옵션 5개 배선 (PR #363)

Scope: `app/game-engine/` (`boot/ScenarioSeedRunner.kt`, `config/BootstrapConfig.kt`, `boot/SeedBootstrapResetOptionsTest.kt`), `infra/` (`seed/ScenarioImporter.kt`, `seed/ScenarioImporterIT.kt`), `docker-compose.yml`, `docker-compose.production.yml`, `.env.example` — 리셋 옵션 `fiction`·`extend`·`block_general_create`·`npcmode`·`show_img_level`의 env → BootstrapConfig → SeedBootstrap → ScenarioImporter 배선
Verdict: cleared

비평자는 이 변경의 작성에 관여하지 않은 별도 에이전트다. 작성자가 자기 작업을 승인하지 않았다. 첫 검토 판정은 **fix-required**였고, blocker 2건과 확인된 minor 1건을 닫은 뒤 cleared로 전환했다. 아래는 제기 순서대로 기록한다.

## 첫 검토가 fix-required였던 이유

**[blocker] 이 저장소의 어떤 compose도 `RESET_*` 키를 game-engine에 전달하지 않았다.**

```
grep -c "RESET_" docker-compose.yml docker-compose.production.yml
→ 0, 0
```

`game-engine` environment는 `SCENARIO_*`만 넘겼다(`docker-compose.yml:176-179`, `docker-compose.production.yml:66-68`). PR 본문은 실동작이 별도 저장소 `opensamguk-docker#34`에 달려 있다고 스스로 밝혔지만, 그 범위 설정은 **CLAUDE.md가 F5 정본 로컬 턴키 스택이라고 선언한 `docker-compose.yml`을 빠뜨린다**. 그 스택에서는 배선을 머지해도 옵션이 여전히 조용히 버려진다. #362(`RESET_TURNTERM`)가 같은 상태로 머지돼 갭이 6키로 누적돼 있었다.

해결: 두 compose 파일의 game-engine environment에 6키(#362의 `RESET_TURNTERM` 포함)를 추가하고 `.env.example`에 문서화했다. **compose에는 기본 숫자를 넣지 않았다** — 전부 `${VAR:-}` 빈 기본이다. 이 PR의 설계 결정 2("기본값의 정본은 게임 엔진 한 곳")를 compose가 숫자를 들어 깨뜨리면 두 곳이 조용히 갈라진다. 미설정의 의미는 `SeedBootstrap`의 `PHP_DEFAULT_*`가 단독으로 소유한다.

**[minor, 확인됨] `ScenarioImporter.kt`의 `fiction` KDoc이 0/1 의미를 뒤집어 적었다.** `0=사실 / 1=연의`로 돼 있었으나 실물 PHP는 반대다:

```
legacy/devsam-core/hwe/install.php:97  value="0" → 라벨 "연의"
legacy/devsam-core/hwe/install.php:98  value="1" checked → 라벨 "가상"
```

이 저장소도 같다 — `app/game-api/.../ServerBasicInfoController.kt:89` = `if (isFiction) "가상" else "사실"`. path:line 증거를 전제로 하는 PR에서 의미 라벨이 반대인 것은 다음 독자를 오도한다. `0=연의 / 1=가상 (install.php:97-98 라디오 라벨)`로 고쳤다. **기본값 1은 그대로 뒀다** — `fiction_1 ... checked`가 실물로 확인된다.

**[minor] `EXTEND_VALUES` 인용 좌표가 한 줄 어긋났다.** `install.php:106-107`로 적혀 있었으나 실제 라디오는 `:107-108`이다(`:106`은 `div`). `:107-108`로 정정했다.

## 인용 좌표 전수 대조

리뷰 중 5개 옵션의 PHP 인용을 실물 `install.php`와 하나씩 맞춰봤다. 허용집합 순서·기본값 모두 일치한다.

| 상수 | 인용 | 실물 | 기본값 근거 |
| --- | --- | --- | --- |
| `FICTION_VALUES` | :97-98 | ✅ | `:98` `fiction_1 checked` → 1 |
| `EXTEND_VALUES` | ~~:106-107~~ → :107-108 | 정정함 | `:107` `extend_1 checked` → 1 |
| `BLOCK_GENERAL_CREATE_VALUES` | :117-119 | ✅ | `:117` `block_general_create_0 checked` → 0 |
| `NPC_MODE_VALUES` | :128-130 | ✅ | `:129` `npcmode_0 checked` → 0 |
| `SHOW_IMG_LEVEL_VALUES` | :191-194 | ✅ | `:194` `show_img_level_3 checked` → 3 |

## 기각한 주장

- **"골든/패러티 경로에 영향"** — 기각. `logic/src/test/resources/golden/p5/world-1010.json`의 `"fiction":0`은 폼 기본값이 아니라 `tools/php-golden/install_scenario.php:84`가 명시 전달한 값이고, 골든 픽스처는 fiction을 자기 월드 JSON에 박고 있어 importer 기본값과 무관하다. 이 브랜치는 골든 파일을 한 줄도 바꾸지 않았다.
- **"`ScenarioImporterIT` 기대값 변경 = 단언 완화"** — 기각. `ScenarioImporterIT.kt:167,404`는 여전히 정확한 문자열 동등 단언이고 삭제·완화·skip이 없다. PHP 캡처 골든이 아니라 시드 계약 IT이므로 기대값 정정이 맞다(`ResetHelper.php:297` `'fiction'=>$fiction`).
- **"미설정 env로 재시작하면 기존 배포가 부팅 실패(회귀)"** — 기각. `BootstrapConfig.kt:46-50`이 `@Value("${RESET_*:}")`로 빈 문자열 기본을 주고 `resolveOption`이 null/빈/공백을 전부 미설정으로 처리한다(`ScenarioSeedRunner.kt:214`).
- **"워크플로 파싱이 조용히 0건 통과"** — 기각. `SeedBootstrapResetOptionsTest.kt:110`의 `checkNotNull(raw)`가 5키 각각에 걸리고, 파일 자체도 `:99` `checkNotNull(workflow)`로 막혀 있다.
- **"one-daemon-write-rule 위반"** — 기각. `ScenarioSeedRunner`는 `JdbcTemplate`만 쓰고(`:50`) `ScenarioImporter`는 `EntityManager`도 `ChangeRecorder`도 잡지 않는다(`:27-33`). 이번 변경은 값 전달만 추가했고 쓰기 경로를 건드리지 않았다.
- **"호출부 인자가 뒤바뀌었을 수 있다"** — 기각. `ScenarioSeedRunner.kt:115-129`를 직접 읽어 `fiction→fiction`, `extend!=0→extendedGeneral`, `blockGeneralCreate→blockGeneralCreate`, `npcMode→npcMode`, `showImgLevel→showImageLevel` 매핑이 전부 정확함을 확인했다. 다만 **이를 고정하는 테스트는 없다** — 아래 known limit 참조.

## 이 변경이 닫지 않는 것 (known limits)

- **`fiction` 기본값 0→1은 골든이 덮지 않는 RNG 분기를 켠다.** `GeneralBuilderGoldenTest.kt:80,107`은 `isFiction = false`만 돌리고, `tools/php-golden/capture_general_builder.php:396`이 "이 픽스처 세트로 fiction 분기는 실행되지 않는다"고 스스로 기록해 뒀다. 값 자체는 PHP 기본값과 일치하는 정당한 정정이지만(`install.php:98`), **미캡처 분기로 라이브를 이동시키는 것은 사실이다.** `GeneralBuilder`의 `isFiction` affinity/ego draw, `RulerSuccessionHandler.kt:48`, `ReservedTurnHandler.kt:1135`가 이 값에 갈린다. fiction=1 골든 캡처는 별도 백로그다 — 이 PR에서 지어내지 않았다.
- **호출부 매핑을 고정하는 테스트가 없다.** `SeedBootstrapResetOptionsTest`는 순수 함수 `resolveOption`과 상수만 검증한다. `npcMode = blockGeneralCreate` 같은 인자 뒤바뀜은 green으로 통과한다. 매핑은 리뷰에서 눈으로 확인했을 뿐이다. DB까지 읽는 IT(`ScenarioMapSeedIT.kt:335` `assertSeedCadence` 선례)는 별도 티켓.
- **fail-loud 폭발 반경이 1개에서 6개로 커졌다.** `resolveOption` 5개가 `SeedBootstrap` 프로퍼티 초기화 시점에 돌아(`ScenarioSeedRunner.kt:83-92`), `SCENARIO_SEED_ENABLED=false`라 옵션이 아무 영향도 못 주는 상황에서도 `RESET_SHOW_IMG_LEVEL` 오타 하나가 데몬 부팅을 죽인다. 조용한 폴백을 만들지 않겠다는 설계 결정 1의 의도된 대가로 남긴다.
- **어드민 폼이 기본값을 또 들고 있다.** `web/gateway/app/admin/page.tsx:531-535`가 `fiction '1'`/`extend '1'`/`blockGeneralCreate '0'`/`npcMode '0'`/`showImgLevel '3'`을 하드코딩한다. 새 대조 테스트는 엔진 상수와 워크플로 yml만 묶으므로 이 세 번째 출처는 드리프트 방지 밖이다. 리서치 문서 §7의 "기본값의 정본은 게임 엔진 한 곳"은 **compose까지 포함해도 여전히 정확하지 않다** — 어드민 폼이 남아 있다. 별도 티켓.
- **`RESET_TURNTERM`은 새 대조 테스트가 덮지 않는다.** `SeedBootstrapResetOptionsTest.kt:97`의 정규식은 `RESET_X="$(env_or_default RESET_X d)"` 꼴만 매치하는데, 워크플로는 `TURN_TERM="$(env_or_default RESET_TURNTERM 60)"`(`reset-game-server.yml:154`)로 다르게 쓴다. 6키 중 5키만 드리프트 방지 대상이다.
- **`resolveOption`이 API 검증기보다 관대하다.** `"01"`은 ASCII 자릿수 가드를 통과해 1로 받아들여지지만 `DeployService.kt:520`의 `textIn("fiction", setOf("0","1"))`은 거부한다. 실무상 도달 경로는 없으나 "좁은 쪽에 맞춘다"는 주석의 원칙과는 어긋난다.

## 증거

- `docker-compose.yml`·`docker-compose.production.yml` 모두 `yaml.safe_load` 통과.
- `install.php`의 5개 옵션 라디오 좌표·`checked` 위치를 실물 파일에서 전수 확인(위 표). `legacy/`는 gitignore이나 리뷰 시점 로컬에 존재했다.
- 배선 코드 자체의 테스트 근거는 PR 본문의 게이트 결과를 따른다: `:infra:test :app:game-engine:test --rerun-tasks` → `BUILD SUCCESSFUL`, XML 합계 tests=976 failures=0 errors=0 skipped=1(`LongSimReplayGateTest`, P5 백로그, 무관). `ScenarioImporterIT`는 Docker가 있어 실제 실행(tests=21 skipped=0)됐고 skip을 통과로 읽지 않았다.
- 이번 리뷰가 추가한 변경은 compose/`.env.example`의 env 전달과 주석 좌표뿐이다 — Kotlin 실행 경로를 바꾸지 않았고 골든을 건드리지 않았다.
- **이 리뷰로부터 배포가 일어나지 않았다.** `.env` 파일·토큰·시크릿을 읽거나 출력하지 않았다.
- 이 PR만으로는 프로덕션 동작이 바뀌지 않는다. 짝 PR `peppone-choi/opensamguk-docker#34`가 함께 나가야 한다.
