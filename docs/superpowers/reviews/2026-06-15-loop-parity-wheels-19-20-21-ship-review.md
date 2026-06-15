# Cross-agent critique — loop-parity 바퀴 19·20·21 (배포 묶음)

- **날짜**: 2026-06-15
- **브랜치**: `loop-parity-2026-06-14-c` → `main` (PR #87)
- **범위**: 3 바퀴 — `logic` 제약 1(che_감축) + `app/game-api` read 1(시나리오 이름) + `web/gateway` 프론트 1(로비 진입 3-버튼). + LEDGER docs.
- **DB 마이그레이션 / enum / flush 컬럼 변경 없음.** che_감축=precheck 제약 전용, ScenarioTitleResolver=classpath 리소스 read-only, lobby=프론트.

이 문서는 본 PR diff(`origin/main...HEAD`)에 포함되는 PR-가시 cross-agent critique 아티팩트로, 각 바퀴를 닫을 때 수행된 fresh 적대 채점(LEDGER `docs/loops/bug-parity-2026-06-15/LEDGER.md` 19·20·21행)을 인용·요약한다. 새 게이트 완화·골든 재생성·테스트 약화는 0. 모든 legacy 오라클은 diff 에서 직접 재확인했다.

## 대상 변경 (LEDGER 19·20·21)

| 바퀴 | 영역 | 파일 | legacy 오라클 | 채점자(LEDGER) | 판정 |
|---|---|---|---|---|---|
| 19 | logic | `CheGamchuk.kt`, `Presets.kt` (+ `CheGamchukConstraintTest.kt`) | `che_감축.php:63-71` | fresh **parity-reviewer**(적대, che_감축.php byte + XML) | 채택 |
| 21 | app/game-api | `ScenarioTitleResolver.kt`(신규), `ServerBasicInfoController.kt` (+ `ScenarioTitleResolverTest.kt`, `ServerBasicInfoControllerTest.kt`) | legacy `ResetHelper`→`getTitle()` 동일 출처(커밋된 scenario 리소스) | fresh: game-api XML(결정적) + 배치 적대리뷰(ship 전) | 채택 |
| 20 | web/gateway | `web/gateway/app/lobby/page.tsx` | devsam `entrance.ts:51-58,274-276` | fresh: tsc(결정적) + 배치 적대리뷰(ship 전) | 채택(조건부) |

## 바퀴별 채점 요약 (diff 직접 재확인)

### 바퀴 19 — che_감축 level 제약 + cityIntField 커널갭 (logic)

- legacy byte-parity: `che_감축.php:63` `origCityLevel = CityConst::byID(capital)->level`(정적 시나리오 레벨), `:69-70` 두 `ReqDestCityValue`(둘 다 errMsg `더이상 감축할 수 없습니다.`): `level>4` + `level>origCityLevel`. diff 의 `reqDestCityValue("level","규모",">",4,…)` + `(">",origCityLevel,…)` 가 op `>`·errMsg byte·정적레벨 출처(`CityConst.byId(it)?.level`, dynamic 아님) 모두 일치.
- 커널갭: `Presets.cityIntField` 에 `"level" -> c.level` 단일 case 추가 — 종전 미지원이라 감축(`>4`,`>orig`)·증축(`>3`,`<8`) 4 제약 전부 런타임 throw(latent-broken)였음. 타 key(`pop`/`agri`…) 불변 → 증축도 co-fix.
- 행동 테스트: `CheGamchukConstraintTest` deny@level==orig / allow@level>orig. AI/selection 골든 불변(no-rng, draw 스트림 무영향).
- 게이트: logic 2154→2157 green. **VERDICT: PASS** (LEDGER 19행 parity-reviewer 채택).

### 바퀴 21 — 시나리오 이름 표시 (app/game-api)

- 출처 정합: 표시 제목은 코드(`scenario_1010`)가 아니라 시나리오 JSON `title`(`【역사모드1】 황건적의 난`) — legacy `ResetHelper`→`getTitle()` 와 동일 커밋 리소스. `ScenarioTitleResolver` 가 `config["title"]`/`meta["title"]` 미시드 시 `scenario/<code>.json` 에서 read-time 해석(코드별 1회 캐시, 실패시 null→코드 폴백). 라이브 s1 config title 미시드 → **재시드 불요**(read-time 상위 폴백).
- 우선순위 정합: `ServerBasicInfoController.buildGame` = config/meta title → `scenarioTitle.titleOf(scenarioCode)` → `scenarioCode`. 기존 시드 경로 동작 불변.
- read-only: classpath 리소스 read + ConcurrentHashMap 캐시. write/flush/DB 경로 무관 → turn-freeze 위험 0.
- 게이트: game-api 307→311 green(ScenarioTitleResolverTest byte-exact, ServerBasicInfoControllerTest 불변). **VERDICT: PASS** (LEDGER 21행).

### 바퀴 20 — 로비 진입 3-버튼 패러티 (web/gateway)

- legacy byte-parity: devsam `entrance.ts:51-58,274-276` 3-버튼 게이팅을 복원. `canCreate=!(blockGeneralCreate&1)`→장수생성`/game/join` · `canSelectNpc=npcMode==1`→장수빙의`/game`(CharacterClaim) · `canSelectPool=npcMode==2`→장수선택`/game/select-pool`. 셋 다 비활성이면 빈 셀(legacy 동일).
- gameUrl `/game` 베이스 정규화: `rawUrl.endsWith('/game') ? rawUrl : rawUrl+'/game'` (prod=.../game, local=:3001+/game).
- crash-safety: 라벨은 `LOBBY_LABELS` 상수, 비트연산·동등비교만 — undefined deref 없음.
- 게이트: web/gateway `tsc --noEmit` EXIT=0/0err.
- **조건부 채택 — 알려진 라이브 캐비엇(LEDGER 20행 + 백로그):** 라이브 s1 config 는 `npcmode`/`block_general_create` 가 importer 미기록이라 `ServerBasicInfoController` 기본 0 → npcMode=0 이면 **장수빙의/장수선택 버튼이 표시되지 않음(=정상 패러티)**. 즉 라이브에서 미등록 행은 장수생성 버튼만 보이는 게 현재 config 기준 올바른 동작. 빙의/선택 버튼 실제 표시는 npcmode 시드/어드민 편집(버그4, 별도 plan)이 선행돼야 함 — 이 PR 범위 밖. UI 게이팅 로직 자체는 byte-parity. **VERDICT: PASS(조건부, 캐비엇 정직 명시).**

## 게이트 증거 (LOCAL green, 적대-리뷰 PASS)

- logic 2157/0/0 · game-api 311/0/0 · web/gateway `tsc --noEmit` 0 errors.
- 백엔드 골든/replay 게이트 무영향(che_감축=no-rng 제약, ScenarioTitleResolver=read-only). DB 마이그/enum/flush 컬럼 변경 0 → flush-IT(turn-freeze) 위험 0.
- 골든/테스트 완화 0. 날조 0. 모든 오라클 legacy verbatim.

## Verdict: cleared

블로커/HIGH 0. 3 바퀴 모두 LEDGER 기록 fresh 적대 채점 PASS(19=parity-reviewer, 20·21=배치 적대리뷰 + 결정적 XML/tsc). 엔진 미변경 → 배포 시 s1-game-engine 미바운스(턴 상태 보존). 배포 후 라이브 검증(`/health` 200·502 무·s1 game-api UP·시나리오 이름 한글·로비 진입 버튼 + 위 npcmode 캐비엇 확인·엔진 시계 무되감김)은 deployer 가 수행한다.
