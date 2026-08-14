# R1~R6 티켓 발행 결과 (2026-07-25)

`round3-proposal-city-guanxi.md` 채택(ADR-LITE-021)으로 오픈 경로가 **14 → 20 티켓**이 됐다. 새로 생긴 6티켓을 Jira(OPENSAM)와 GitHub에 발행한 결과다.

Jira↔GitHub 자동 동기화는 없다. 진실 순서는 **코드 > PR > GitHub > Jira**이고, 커밋 메시지에 `OPENSAM-###`이 필수다.

## 발행 결과표

| 코드 | Jira 키 | GitHub | 요약 | 우선순위 | 라벨 |
|---|---|---|---|---|---|
| R1 | `OPENSAM-150` | [#325](https://github.com/peppone-choi/opensamguk/issues/325) | v2 도시 원장 기반 — `v2_city_ledger` 스키마 + `ChangeRecorder`→`JdbcFlushExecutor` 쓰기 경로 + migration-before-seed/source→DB 적재 seam | High / `priority-next` | `v2-open-path`, `v2-open-step-3b` |
| R2 | `OPENSAM-151` | [#326](https://github.com/peppone-choi/opensamguk/issues/326) | 수입·봉록 도시 귀속 (**생산자**) — `V2ProcessCityIncome` leaf + v2 시나리오 `event` 12행 저작·재시드 | High / `priority-next` | `v2-open-path`, `v2-open-step-3b` |
| R3 | `OPENSAM-152` | [#327](https://github.com/peppone-choi/opensamguk/issues/327) | 도시병사 감소·공백지화 (**소비자, R2 뒤**) — `V2CityGarrisonAttrition` 월간 leaf | High / `priority-next` | `v2-open-path`, `v2-open-step-3b` |
| R4 | `OPENSAM-153` | [#328](https://github.com/peppone-choi/opensamguk/issues/328) | 도시병사 보충 커맨드 — v2 개인턴 resolver + intake 배선 + `pollCommandResult` 규약 | High / `priority-next` | `v2-open-path`, `v2-open-step-4b` |
| R5 | `OPENSAM-154` | [#329](https://github.com/peppone-choi/opensamguk/issues/329) | 수송 커맨드 — 금·병량·도시병사, 인접 1홉, 각 5만·최소 병사 2000 | High / `priority-next` | `v2-open-path`, `v2-open-step-4b` |
| R6 | `OPENSAM-155` | [#330](https://github.com/peppone-choi/opensamguk/issues/330) | 도시 원장 열람 — game-api read 엔드포인트(`JdbcTemplate`) + `web/game` 패널 | High / `priority-next` | `v2-open-path`, `v2-open-step-4` |

## 20티켓 최종 순서

| # | 단계 라벨 | 티켓 | 내용 |
|---|---|---|---|
| 0 | `v2-open-step-0` | `OPENSAM-31`·`32`·`33`·`34` | v1 선행 4종 |
| 1 | `v2-open-step-1` | `OPENSAM-149` | restart-rehydrate lossless gate |
| 2 | `v2-open-step-2` | `OPENSAM-35` | V2-0A production 격리 (+DoD 3항목 추가) |
| 3 | `v2-open-step-3` | `OPENSAM-43`·`44` | V2-0B runtime/isolation 계약 + broad T1 영속화의 just-in-time 소유권 분해(제품 SQL 0) |
| 3b | `v2-open-step-3b` | `OPENSAM-150` → `151` → `152` | **R1 → R2 → R3, 순차** |
| 4 | `v2-open-step-4` | `OPENSAM-45`·`46`·`47` · `155` | V2-1 lifecycle + 조작 대상 패널 · **R6 동시** |
| 4b | `v2-open-step-4b` | `OPENSAM-153` → `154` | **R4 → R5, 순차** |
| 5 | `v2-open-step-5` | `OPENSAM-48` | V2-2 부곡 |
| 6 | `v2-open-step-6` | `OPENSAM-56` | V2-3 작전 |
| 7 | `v2-open-step-7` | `OPENSAM-61` | V2-5 가신 |
| | | **합계 20** | |

기존 14티켓의 단계 라벨(`v2-open-step-0`~`7`)·우선순위 필드는 **변경하지 않았다.** 신규 6티켓이 `3b`/`4`/`4b`로 들어가면서 사전식 정렬이 그대로 실행 순서가 되기 때문이다(`3` < `3b` < `4` < `4b` < `5`). GitHub 우선순위는 기존 관례대로 step 0~2 = `priority-now`, step 3 이후 = `priority-next`이며 신규 6티켓은 전부 후자다.

## 순차 제약 (병렬 아님)

- **R2 → R3.** 공유 파일 2건(v2 시나리오 JSON의 같은 행 3개 · `WorldActionContext.kt`) + 등록 순서 의존(R3 leaf가 먼저 들어가면 `EventAction.kt:70-74`가 미등록 이름에 `IllegalArgumentException`을 던져 v2 월드가 첫 1월에 죽는다). CLAUDE.md "cross-area shared artifacts build sequentially, creator-then-consumer".
- **R4 → R5.** `CommandWireMapper.kt`·`TurnDaemonCommandDispatcher.kt` 두 파일 공유.
- **R1 → R2.** R2·R3·R6이 읽고 쓸 원장 표와 flush 경로를 R1이 만든다.

## 같이 수정한 기존 티켓

- **`OPENSAM-35`** — 기존 0A-a~g 7항목은 그대로 두고 **DoD 3항목 추가**: ① v2 스택을 별도 compose 서비스로 띄우고 `GAME_DATABASE_URL`·`OPENSAMGUK_WORLD_ID`·`SCENARIO_CODE`/`SCENARIO_DIR`·`V2_ENABLED`·`SPRING_PROFILES_ACTIVE`를 v1과 다른 값으로 주입(오늘 compose에 v2 스택은 없다) ② v2 Flyway location은 `SPRING_FLYWAY_LOCATIONS` env 오버라이드로만 추가(`application.yml` 무수정 = 게이트 ⑤ 유지, U12) ③ 0A-f "프로덕션 컨텍스트 v2 빈 0" 아키텍처 테스트를 v1 프로세스에서 실측.
- **`OPENSAM-113`** — **"유저 맞춤" 표시 원칙**을 요구사항·AC에 추가. "20버튼 전부 노출"이 아니라 "이 주체가 지금 할 수 있는 것만"이며, 불가능한 것은 숨기거나 사유와 함께 비활성한다. **신설이 아니라 기존 게이팅(F2 `MainControlBar` 20버튼+게이팅) 위의 표시 규칙**임을 명시했다.

## 잔여 UNKNOWN 처리

| # | UNKNOWN | 닫는 티켓 |
|---|---|---|
| U9 | `@Serializable` sealed 서브클래스 파일 분리 | `OPENSAM-150`(R1) 착수 첫 작업 — 소비자는 R4·R5. §11은 원래 R4 착수 첫 작업으로 적었으나 앞당겼다 |
| U10 | v2 시드·마이그레이션 부팅 순서 + configured scenario source→v2 DB event 적재 seam | `OPENSAM-150`(R1) 착수 첫 작업; event payload·`ignoreDefaultEvents`·재시드 판정은 `OPENSAM-151`(R2) |
| U12 | `SPRING_FLYWAY_LOCATIONS` env 오버라이드 | `OPENSAM-150`(R1) 착수 첫 작업 + `OPENSAM-35` DoD ② |
| U6 | 도시병사 수송 상한 | `OPENSAM-154`(R5) 본문에서 5만 임시 적용 + **"묘섭 미명시"** 표기 |
| U7 | 국고 4대 지출 균형 | 오픈 전 관측 3종(국고 월간 추이 / 병종연구 최초 완료 시점 / `maxResourceActionAmount` 분포) |

어느 것도 오픈 경로 수량 20의 전제가 아니다(ADR-LITE-021).

## 정본

- 설계: `docs/loops/v2-planning-2026-07-12/round3-proposal-city-guanxi.md` §9.2(산출물·삽입 위치) · §7.1-2(T2 11행 표 + 가드 영향) · §7.2(게이트 ①~⑤) · §11(UNKNOWN)
- 근거: `.ai/decisions.md` ADR-LITE-021 (ADR-LITE-018·019 위)
- 오픈 경로 표: `docs/superpowers/plans/2026-07-17-v2-ticket-backlog/README.md` §착수 순서
