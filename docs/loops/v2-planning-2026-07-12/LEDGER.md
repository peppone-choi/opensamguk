# v2 기획 수렴 루프

> 범위: `docs/wiki`, 묘섭 도움말 1.0.6, 기존 v2 PRD/ROADMAP/계획, 현재 v1 운영 기준을 대조해 v2 제품 정본과 실행 순서를 정하는 루프.
> 상태: round-2 adopted

## 판정 규칙

- 같은 자료군에서 반복되는 사용자 문법은 채택 후보로 올린다.
- 서로 충돌하는 수치·cadence는 생산 운영값, QA profile, 내부 simulation 값으로 분리한다.
- v1 패러티와 운영 불변식을 깨는 제안은 콘텐츠가 매력적이어도 보류한다.
- 신규 콘텐츠는 `결정 → deterministic 판정 → 다음 상태 변화 → replay/log`를 만들지 못하면 첫 출시에서 제외한다.

## 루프 기록

| 라운드 | 기준선 | 가설 | 채점기 | 결과 | 결정 |
|---|---|---|---|---|---|
| 0 | 106개 wiki 문서 약 1,239개 섹션, 외부 도움말 1.0.6, 기존 v2 문서군 대조 | 새 v2는 커맨드 수보다 작전·회의·원군·replay 한 장면을 먼저 고정해야 한다 | 자료별 채택/보류 표, cadence 충돌 표, v1 경계 검토 | 기존 문서의 1분·21초·30초·1시간 값이 서로 다른 층에 섞여 있음 | 200ms 내부 목표, production 3600초, QA/s1 60초로 분리하고 제품 spec을 제안 기준으로 채택 |
| 1 | 장수 생성 화면이 `front-info`를 최대 20초 반복 조회하고, 지도 컴포넌트가 detail 규칙만 사용 | flush 후 command result를 기다리고 basic/detail marker 규칙을 분리하면 턴 완료와 무관하게 즉시 반영된다 | join/MapViewer 테스트, engine targeted test, PHP/legacy CSS 대조 | 검증 중 | 테스트·리뷰 완료 후 채택 또는 원복 |
| 3 | 묘섭 도움말 37페이지 전수 + opensamguk 도시·인맥 코드 기준선 (도시 특산·지형·시설 0, 장수↔장수 관계 구조 0 hits) | 묘섭의 "도시 중심"은 3D·거점 수가 아니라 자원 소유권의 위치(국가→도시)이며, 묘섭에는 관계 수치 자체가 없으므로 인맥은 인사권·배치효과·감시·자원분배 4축이다 — 즉 도시 중심과 인맥 중심은 하나의 시스템이고 G0 없이 오픈 경로에서 구현 가능하다 | `GOLDENSET-round3-city-guanxi.md` 10문항 + 독립 reviewer | 자기채점 10/10 → **독립 reviewer 5/10 `fix-required`**(`REVIEW-round3-r1.md`: CRITICAL 2 · MAJOR 9 · MINOR 7, N=문항 2·4·7·8·9) → 개정 2차 완료(`REVISION-round3-r2.md`: C1 미러 폐기·국고 병존, R2+R3 병합, 관계망 오픈 후, 오픈 경로 **20(조건부 21)**) → **재채점 6/10 `fix-required`**(`REVIEW-round3-r2.md`: 1차 18건 중 15 해소, 그러나 `BAD_STATE_CODES` 라벨 **날조** 신규 발견 + `prev_income` 누락 + 국고 무세원, N=문항 2·4·7·9) → 개정 3차 완료(`REVISION-round3-r3.md`: 날조 정정·`prev_income` (a) 채택·`EngineEventConfig` T2 편입·R0 오픈 후 이동, 오픈 경로 **20 단일값**) → **재채점 #3 = 9/10 `fix-required`**(`REVIEW-round3-r3.md`: 날조 재발 0, 2차 9건 중 8 해소, N=문항 7 하나 — T2 목록에 `WorldActionContext.kt` 누락) → 개정 4차 완료(`REVISION-round3-r4.md`: R1~R6 전 티켓 확장점→구현자 추적으로 §7.1-2 전면 재작성 11편집+마이그레이션 1, R2→R3 **순차** 전환, `ignoreDefaultEvents: true` 확정, `UpdateNationLevel` 지적 반박, U9 신설) → **재채점 #4 = 9/10 `fix-required`**(`REVIEW-round3-r4.md`: 날조 0·숫자 전건 재현 성공·MAJOR-2와 MINOR 3건 닫힘, 그러나 채점자가 다른 출발점에서 절차를 재실행해 T2 누락 2건 신규 발견 — `BootstrapConfig.kt`(R1)·`GameApiApplication.kt:9-10`(R6). 같은 실패형 4바퀴 연속) → 개정 5차 완료(`REVISION-round3-r5.md`: 메커니즘 역추적으로 **4차 설계가 물리적으로 불성립**임을 발견 — `HotColdWorldCatalogGuardTest`가 `WorldSnapshotLoader`를 봉인, `JdbcFlushExecutor`는 v1 DataSource 결박. R1 읽기·쓰기 경로를 `InMemoryTurnWorld`/`FlushPayload`에서 분리, `engine.v2` 패키지 격리, T2 12행 → **10행**(5삭제·3신설), 게이트 ⑤ 신설, U10·U11 추가) → **재채점 #5 = 9/10 `fix-required`**(`REVIEW-round3-r5.md`: 인용 50건 날조 0·읽기 경로 전환은 정당(가드 봉인 확인)·MAJOR-A/B·MINOR-C 닫힘, 그러나 **C1 = v2 월드의 프로세스·DataSource 토폴로지가 문서 내부 모순**(`:966` 별도 DB 전제 vs `:1031` v1 템플릿 전제, 한 프로세스에 DataSource 1개) — 어느 갈래든 T2 표 일부가 무너진다) → 개정 6차 완료(`REVISION-round3-r6.md`: 토폴로지를 **분기 A로 코드 확정** — `ScenarioSeedCoordinator.kt:37-49`가 한 DB 다중 월드를 `error()`로 이미 차단. 5차의 "v1 템플릿" 전제가 거짓으로 확정돼 `JdbcFlushExecutor`·`DatabaseHooks` 2행 T2 복귀·두 번째 Hikari 풀 폐기·U11 철회·v1v2 단일 트랜잭션. T2 **11행** + 가드영향 열 신설, UNK-C/D 종결) → **재채점 #6 = 10/10 `cleared`**(`REVIEW-round3-r6.md`: 인용 약 75건 날조 0, 문항 7 최초 Y, 토폴로지 확정 정당, T2 11행×6열 빈칸 0, 채점자 독립 재수색에서 패러티 누락 0건. 비차단 MINOR 4건) → 개정 7차(`REVISION-round3-r7.md`: MINOR 4건 실측 반영, **구조 무변경** — 20 단일값·T2 11행·게이트 ①~⑤ 그대로. 실측으로 밝혀진 것: `docker-compose.production.yml:66`이 `SCENARIO_SEED_ENABLED=false`라 "한 DB=한 월드" 코드 불변식이 **프로덕션 기본 구성에서는 안 돈다**) | **채택** — 도시 중심·인맥 = 하나의 시스템, 오픈 경로 **20 단일값**, 관계망 전체(P0~P6 7티켓)는 **오픈 후**. ADR-LITE-019 오픈 경로 표(14→20) 및 `product-spec.md:388` 개정 대상 |
| 2 | 역사 지리·3D 골든셋 2/10: 시계열·오차와 v1 격리만 충족 | 행정단위·물리 장소·치소·주변 네트워크를 분리하고 2,000개 거점을 3단계 LOD로 정합화하면 전수 등장과 3D 구현 가능성을 함께 만족한다 | `GOLDENSET.md` 10문항, fresh 문서 reviewer, `git diff --check`, agent-system strict check | 2/10 → 5/10 → 5/10 → 8/10 → 9/10 → fresh 전수 재검토 7/10 → 최종 10/10; 독립 reviewer `cleared` | 채택: V2-0A 격리 후 G0→0B, C0→C1..C5 순서를 구현 기준으로 고정 |

## 현재 채택안

- 첫 콘텐츠: 작전 목표, 전쟁 전야 회의, 원군·봉신 협상, 도시 사건, 전쟁 기록관.
- 전투 형식: 공통 전술 엔진 기반 위에 전략 지도와 분리된 연속 좌표·fixed-tick 실시간 대형 부대 전장을 먼저 노출한다. 테이블탑 미니어처처럼 footprint·전면·지휘거리·사기·보급을 읽게 하고, grid는 내부 자료구조로만 둔다.
- 확장 콘텐츠: 장수 관계망, 첩보·역정보, 인재 사건, 계절·정세 사건, 플레이어 연대기.
- 보류 콘텐츠: 반복 일일퀘스트, 무작위 전리품, 과금 능력치, 장식 목적 3D, runtime LLM 서술.

## 다음 채점 대기

- round-2 역사 지리·3D 범위와 데이터 계약은 GOLDENSET 승인을 완료했다. production cadence, s1 sandbox 승격, 개별 3D asset 구매·license는 각 구현 gate의 별도 승인 전까지 고정하지 않는다.
- V2-1 구현 때 `commandAccepted → commandResolved/Rejected → 영향 query 갱신`의 실제 p95를 측정한다.
- V2-3 이후 작전 목표 3종과 원군 지연 fixture를 같은 seed로 재실행해 replay diff를 측정한다.
- 2바퀴는 지리 범위와 데이터 계약만 채점한다. 개별 현 좌표, 주변 정체의 정확한 수, 3D asset 구매·라이선스는 이 바퀴에서 확정하지 않는다.
