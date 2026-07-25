# 개정 7차 — 6차 채점 `cleared` 10/10 후 비차단 MINOR 4건 반영

> 대상: `docs/loops/v2-planning-2026-07-12/round3-proposal-city-guanxi.md` (제자리 수정)
> 입력: `REVIEW-round3-r6.md` §6 (MINOR 4건) + §3 말미 부수 정정 2건
> 성격: **정정 반영이지 재설계가 아니다.** 설계 결정·수량·게이트는 하나도 건드리지 않았다.

---

## 1. 항목별 처리

| 항목 | 처리 | 제안서 반영 위치 | 근거 `path:line` (직접 열어 확인) |
|---|---|---|---|
| **m-new-1** `SCENARIO_CODE`·`SCENARIO_DIR` 누락 | 0A DoD (i)에 두 변수 + "양 스택 `SCENARIO_SEED_ENABLED=true`" 추가, 조용한 실패 양식을 인용 블록으로 설명, **R2 DoD에 시드 후 `event` 행 검증 3항목** 신설 | §7.1 "남는 DoD 강제 사항" 단락 + 그 아래 신설 인용 블록 | `docker-compose.yml:171-173` · `docker-compose.production.yml:66-68` · `infra/src/main/kotlin/opensamguk/infra/seed/ScenarioJson.kt:69,299` · `ScenarioImporter.kt:806` |
| **m-new-2** `event` 행 저작 서술 | "행 집합 **전체**" → "**시나리오 유래 행 전체**"로 3곳 정정 + 판정 아래 인용 블록 2개 신설(무해 논거 포함). **설계 무변경** | §7.1-2 분기 표 `true` 행 · 그 아래 판정 · `ignoreDefaultEvents` 런타임 판정 · §9.2 공유 파일 1번 | `ScenarioImporter.kt:806`(insertEvents) · `:827`(deferredRows) · `:828`(`defaults + scenarioRows + deferredRows`) · `:840`(생성기) · `:878-879`(`RegNPC`/`RegNeutralNPC`) · `WorldActions.kt:51,52,54`(v1 체인이 세 이름을 등록) |
| **m-new-3** 토폴로지 근거 (γ) 범위 | "이미 강제되는 코드 불변식" → "**시드 활성 부팅에서** 강제되는 불변식". 세 진입점 명시 + 시드 비활성 부팅의 담보(0A DoD env 분리) 한 줄. **갈래 A 확정 유지** 명기 | §7.1 증거 3 · §7.1-2 "부수" 인용 블록 · 자기채점 문항 7 (γ) | `ScenarioSeedRunner.kt:70-73`(`if (!seedEnabled) … return false`) · 진입점 3개 `ScenarioSeedRunner.kt:47` · `WorldSnapshotLoader.kt:53` · `EngineEventConfig.kt:40`(grep 전수) · `ScenarioSeedCoordinator.kt:37-49` |
| **m-new-4** 신규 파일 열거에 v2 시나리오 JSON 부재 | 의도된 공백 + 사유 3개(계층 정의상 밖 / v1 데이터 접근 불가 / gitignore 규약 무충돌) + **위치를 R2 DoD로 못 박음** | §7.1-2 "신규 파일(편집 아님)" 단락 직후 신설 | `.gitignore:93-95` · `ScenarioSeedRunner.kt:121-127`(`readScenarioJson`), `:122-125`(`SCENARIO_DIR` 우선) · `infra/src/main/resources/scenario/scenario_910.json`(추적, `ignoreDefaultEvents: true` + `events` 19행) |
| **부수** `EventAction.kt:61-64` | `:60-64`로 범위 확대 (2곳: §7.1-2 v1 inert 근거 · T2 표 8행) | 〃 | `EventAction.kt:60`(KDoc) · `:61`(시그니처) · `:62-64` |

---

## 2. 실측값 (m-new-1 핵심)

**compose 기본값 — 두 변수 모두 기본값이 있다.**

| 파일 | `SCENARIO_SEED_ENABLED` | `SCENARIO_CODE` | `SCENARIO_DIR` |
|---|---|---|---|
| `docker-compose.yml` | `:171` **true** | `:172` `scenario_1010` | `:173` `/data/scenarios` |
| `docker-compose.production.yml` | `:66` **false** | `:67` `scenario_1010` | `:68` `/data/scenarios` |

**`scenario_1010`의 `ignoreDefaultEvents` = 거짓 (두 사본 모두).**

| 사본 | 값 | 경로 |
|---|---|---|
| 클래스패스 정본(추적) | **키 자체가 없음** ⇒ `ScenarioJson.kt:69` `boolOf(root["ignoreDefaultEvents"], false)` · `:299` 필드 기본값 `false` | `infra/src/main/resources/scenario/scenario_1010.json` |
| 마운트 사본 | **명시적 `false`** | `data/extracted/scenario/scenario_1010.json` |

⇒ v2 스택이 기본값을 물려받으면 `insertEvents`의 `defaults` 분기가 살아나 `EventStore.DEFAULT_EVENTS` **12행 적재 · v2 leaf 0행**. 부팅·시드·헬스체크는 전부 성공한다. **채점자의 진단이 정확하다.**

**채점 보고서의 행 오차 1건(반영하며 확인).** `REVIEW-round3-r6.md:129`가 `SCENARIO_CODE`를 `docker-compose.yml:171`로 적었으나 실제는 `:172`(`:171`은 `SCENARIO_SEED_ENABLED`). 제안서에는 실측값 `:172-173`으로 적었다.

---

## 3. 구조 불변 확인

착수 전 정정이므로 구조는 손대지 않았다. 반영 후 재계수:

| 항목 | 6차 | 7차 | 확인 방법 |
|---|---|---|---|
| 오픈 경로 티켓 수량 | **20 단일값** | **20 단일값** | §9.2·§9.4·§9.5·자기채점 9행 무편집. "20 단일값" 문자열 3건 그대로 |
| T2 표 | **11행**(편집 10 + 마이그레이션 1) | **11행** | 표 본문 무편집(8행의 `EventAction.kt` 인용 범위만 `:61-64`→`:60-64`) |
| 게이트 | **①~⑤** | **①~⑤** | §7.2 코드블록 무편집. `--diff-filter=MD` 유지 |
| 방어선 | 8개 | 8개 | §7.1 무편집 |
| 코드·`docs/wiki/raw/**` | — | **무수정** | 제안서 1파일 + 이 기록 1파일만 생성/수정 |

**추가된 것은 DoD 문장 3종뿐이다** — 0A DoD (i)의 환경변수 2개, R2 DoD의 `event` 행 검증 3항목, R2 DoD의 v2 시나리오 JSON 위치. 셋 다 *착수 시 확인할 것*이지 설계 결정이 아니다.

---

## 4. 부수 — 6차 개정 기록의 오식 1건 (제안서는 정확)

`REVISION-round3-r6.md`의 요약표가 game-engine의 world-id를 `application.yml:30`으로 적었다. 실측:

- `app/game-api/src/main/resources/application.yml:30` — `world-id: ${OPENSAMGUK_WORLD_ID}`
- `app/game-engine/src/main/resources/application.yml:36` — `world-id: ${OPENSAMGUK_WORLD_ID}`

⇒ game-engine은 **`:36`**, `:30`은 game-api다. **제안서 본문에는 이 오류가 없다**(본문은 game-api `:30`을 맞게 인용한다)는 채점자 확인대로이므로 제안서는 손대지 않았고, 오식은 6차 기록에 남은 채 이 기록으로 정정한다.

---

## 5. 반영 중 새로 발견한 것

1. **채점자의 "함수 시그니처가 `:60`"은 한 행 어긋난다.** 실측 `EventAction.kt:60`은 KDoc(`/** Register a leaf builder … */`), `:61`이 `fun register(...)` 시그니처다. 즉 기존 인용 `:61-64`가 함수 본체와 정확히 일치했고 `:60-64`는 KDoc을 포함해 넓힌 범위다. 지시대로 넓혔으나 **사유는 "시그니처가 :60이라서"가 아니다**.
2. **프로덕션 compose는 이미 시드 비활성이 기본이다**(`docker-compose.production.yml:66` = `false`). m-new-3의 단서가 가정이 아니라 **현재 프로덕션 기본 구성 그 자체**라는 뜻이고, 그래서 0A DoD에 "양 스택 모두 시드 활성" 조건을 붙였다.
3. **`SCENARIO_DIR` 우선순위가 m-new-4를 리뷰 문제로 만든다.** `readScenarioJson()`(`ScenarioSeedRunner.kt:122-125`)이 `SCENARIO_DIR`의 동명 파일을 클래스패스보다 **먼저** 읽으므로, v2 시나리오를 마운트로만 배달하면 그 파일이 `data/scenarios/scenario_*.json`(gitignored, `.gitignore:95`)에 남아 전사한 `DEFAULT_EVENTS` 12행이 코드리뷰·이력에서 사라진다. R2 DoD에 "추적되는 클래스패스 경로로 커밋"을 명시한 이유다.

---

## 6. 남은 UNKNOWN (변동 없음 — 추측하지 않는다)

- **U9** — `@Serializable` sealed 서브클래스를 원 파일 밖 신규 파일에 두었을 때의 컴파일·직렬화 결과. 컴파일 미실행. 대안 (a) 준비됨. **어느 쪽이든 오픈 경로 수량 20 불변.**
- **U10** — v2 마이그레이션·시드가 `SeedBootstrap`/`ScenarioSeedRunner` 부팅 순서와 맞물리는 방식. 6차에서 "v2 store가 처음 읽는 시점에 0A-c location의 Flyway가 이미 돌았는가" 한 가지로 좁혀졌고, 완화안(lazy 초기화)은 있으나 **미실측**. 7차의 m-new-1·m-new-3 반영은 같은 시드 경로를 건드리지만 이 질문의 답을 주지 않는다 — 여전히 R1 착수 시 실측 몫이다.
- **U12** — `SPRING_FLYWAY_LOCATIONS` 환경변수 오버라이드가 이 리포에서 실제로 먹는지. Spring Boot 표준 동작이나 **미실측**. 0A-c 착수 첫 작업.

이번 개정으로 **새 UNKNOWN은 생기지 않았다.** m-new-1의 두 실측(compose 기본값·`scenario_1010` 플래그)은 파일을 열어 확인했고 추정이 아니다.
