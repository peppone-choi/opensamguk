# v2 로드맵 티켓 백로그 — 합성본 (2026-07-17)

v2 문서 10종 전체를 4개 독립 에이전트가 정독·분해한 결과의 합성. 원자 티켓(반나절 이하·PR 1개·파일 몇 개)의 정본 목록은 이 디렉터리의 소스 4파일이며, 이 README는 **정본 판정·중복 제거 규칙·계층 구조·착수 순서**만 고정한다.

## 소스 파일

| 파일 | 대상 문서 | 원자 티켓 규모 |
|---|---|---|
| `01-backbone-micro.md` | 07-12 product-spec + 07-12 execution-plan | 스펙 계약 P-* ~60 + 구현 0A/G0/0B/1..8/GATE ~190 |
| `02-plans-micro.md` | 07-13 identity-rework(D1) · 06-28 design(D2, superseded) · 06-29 release(D3) · 07-13 active-plan(D4) | D1 84 + D2 31 + D3 61 + D4 35 |
| `03-catalog-micro.md` | 07-12 command-catalog(D1-*) + 07-13 troop-building-content(D2-*) | ~207 + ~212 |
| `04-systems-micro.md` | 07-13 historical-city-army-terrain(T1-*) + 07-13 imperial-court-office(T2-*) | ~95 + ~90 |
| `appendix-*.md` | 초기(큰 단위) 분해 — 배경 설명·Exit 상술이 더 풍부, 참고용 | — |

합계 원자 티켓 후보 약 **800+**. 단 아래 중복 제거를 적용하면 실제 착수 단위는 줄어든다(같은 산출물을 스펙/계획/카탈로그가 3중으로 가리키는 경우 다수).

## 정본 판정 (충돌 시 이기는 문서)

1. **제품 사양 정본** = `2026-07-12-opensamguk-v2-product-spec.md`.
2. **phase·Exit 정본** = `2026-07-12-opensamguk-v2-execution-plan.md` (V2-0A→G0→0B→1→2→B0→C0→C1..C5→3→4A→4B→5→I0→6→O0→7→8).
3. **오픈 게이팅 순서 정본** = `2026-07-13-v1-stabilization-and-v2-open-plan.md` — **v2 코드는 V2-0A production 격리 게이트(D4-18~22) 통과가 선행**. B0·B0.5 깨지면 v2 중단.
4. **커맨드 canonical id·payload·authority·롤아웃 정본** = `2026-07-12-v2-command-catalog-and-rollout.md` (C0→C1→C2→C3→C4→C5).
5. `2026-06-28` design-plan은 헤더에 superseded 명시 → **폐기** (개념 출처로만, D2-* 티켓은 D3-*/07-12가 재상술). 3D=기본 surface 확정은 2026-07-14 정정이 정본.
6. `2026-06-29` release-plan(D3-*)은 supersession 표기가 없으나 06-28 기반 — **작업 상세(P0 안정화·조작대상 패널·KST 등)는 유효하되 phase 순서는 07-13이 이긴다**. replay phase는 D3 7종(approach/scout/intercept/field/siege/urban/aftermath)이 개정판.

## 중복 제거 규칙 (라벨 분리)

같은 산출물을 가리키는 티켓 쌍은 **스펙 티켓 = "계약 동결"(contract), 계획 티켓 = "구현"(impl)** 으로 라벨을 분리해 중복 착수를 막는다. 확인된 쌍:

- P-12(지리 수치) ↔ G0A~C/8-* — 구현 정본은 계획.
- P-13(전술 계약) ↔ B0-a~g — 구현 정본은 Spike V2-B0.
- P-4(Replay 계약) ↔ 4A-f/g.
- P-9(건물) ↔ 6-j·C-track.
- P-15(성공 기준) ↔ 각 phase Exit.
- D2-06~28(06-28) ↔ D3-32~54(06-29) ↔ 계획 4A/5/6/7 — 실행은 execution-plan 티켓만.
- D1(카탈로그) 명령 등록 ↔ D1-18~27(identity) ↔ T2-I(court) — **id·payload는 카탈로그가 정본**, identity/court 문서는 lifecycle·resolver 의미만.
- T1-*(systems) 모델 정의 ↔ G0A~C 계약 티켓 — 같은 계약, T1이 필드 상세판. G0 착수 시 T1 필드 정의를 그대로 사용.

`[아키]` 태그(04-systems): 스펙이 아니라 CQRS 아키텍처에서 추론한 산출물(마이그레이션/mapper/flush/read API). 과거의 V2-0B 일괄 귀속은 `2026-08-13-opensam-44-contract-crosswalk.md`가 supersede한다. OPENSAM-44는 계약·소유권 분해만 하며, 실제 영속화는 선행 모델과 소비 동작을 가진 개별 제품 티켓이 just-in-time으로 소유한다. 첫 제품 migration은 OPENSAM-150의 `V901`이다.

## 계층 구조 (Jira 매핑)

- **Epic = phase/트랙** (V2-0A, V2-G0, V2-0B, V2-1, V2-2, B0, C0, C-track, V2-3, V2-4A, V2-4B, V2-5, I0, V2-6, O0, V2-7, V2-8, 콘텐츠 카탈로그, 커맨드 카탈로그, 정체성, 황실·관직).
- **Story/작업 = wave·그룹** (G0-A/B/C, 카탈로그 그룹 A~N, systems 그룹 A~K …) — 그룹당 Jira 이슈 1개, 설명에 소속 마이크로 티켓 ID 체크리스트.
- **마이크로 티켓** = 소스 4파일의 ID(P-*, 0A-*, G0A-*, D1~D4-*, T1/T2-*) — Jira 이슈 본문 체크리스트 항목으로 유지하고, 착수 시점에 개별 이슈로 승격(just-in-time 승격).

이렇게 하면 "굉장히 작은 단위" 분해는 소스 파일+체크리스트로 보존되고, Jira는 실행 가능한 수준(수십 개)으로 유지된다.

## 착수 순서 (정본 게이팅 반영)

> **개정 (2026-07-30, 승인된 실시간 전투 스펙) — V2 전투는 오픈 후가 아니라 출시 필수다.**
> 기존 20티켓 경로 뒤에 공통 battle-engine 기반과 야전·공성·수전·2.5D 클라이언트·출시 게이트를 추가한다. 정확한 신규 티켓 수와 키는 `2026-07-30-v2-realtime-battle-session-command-replay-design.md`의 후속 implementation plan과 Jira/GitHub 발행 결과가 정본이 된다. 따라서 ADR-LITE-021의 “20 단일값”과 아래 기존 합계는 **전투 승인 이전의 부분합**으로 강등한다.
>
> **개정 (2026-07-25, ADR-LITE-021) — 오픈 경로 = 20 티켓. 아래 원본 순서보다 이 표가 우선한다.**
> ADR-LITE-019가 고정한 14 티켓에 round-3 설계안(`docs/loops/v2-planning-2026-07-12/round3-proposal-city-guanxi.md`, 독립 채점 6바퀴 끝 10/10 `cleared`)의 **R1~R6이 추가돼 20**이 됐다. ADR-019의 나머지(`V2-G0`·`C-track` 오픈 후 연기, `OPENSAM-149` 선행)는 그대로 유효하다.
> `V2-0B` 적재는 G0 카탈로그 대신 기존 도시 세트 또는 RTK 빌더(`OPENSAM-104`/`105`) 산출물을 쓴다.
>
> | # | 티켓 | 내용 |
> |---|---|---|
> | 0 | `OPENSAM-31`·`32`·`33`·`34` | v1 선행 — 안정화 체크리스트 · B1b 자동외교 6종 · B2 운영 스모크 · 배포 체크 Go 5종 |
> | 1 | `OPENSAM-149` | restart-rehydrate lossless gate (v1/v2 공용 데몬 — 포크 전에 한 번만 고친다) |
> | 2 | `OPENSAM-35` | V2-0A production 격리 게이트 (+DoD 3항목: v2 별도 compose 스택·env 분리 / `SPRING_FLYWAY_LOCATIONS` 오버라이드 / 0A-f 실측) |
> | 3 | `OPENSAM-43`·`44` | V2-0B runtime/isolation 계약 완료 + broad T1 영속화의 just-in-time 소유권 분해(OP44는 제품 SQL 0) |
> | 3b | **`OPENSAM-150`(R1) → `151`(R2) → `152`(R3)** | **도시 원장 3종 — 순차.** R1 `v2_city_ledger` 기반(스키마+flush 경로) / R2 수입·봉록 도시 귀속(`ProcessIncome` leaf 치환, **생산자**) / R3 병력 0 → 공백지화(**소비자**). R3‖R2 병렬은 철회됐다(공유 파일 2건 + 등록 순서 의존) |
> | 4 | `OPENSAM-45`·`46`·`47` · **`155`(R6, 동시)** | V2-1 command result lifecycle + 조작 대상 패널 · **R6 도시 원장 열람**(read API + 패널 필드)은 패널 위에 얹으므로 동시 |
> | 4b | **`OPENSAM-153`(R4) → `154`(R5)** | **v2 개인턴 커맨드 2종 — 순차.** R4 도시병사 보충 / R5 수송(금·병량·도시병사, 인접 1홉). `CommandWireMapper`·`TurnDaemonCommandDispatcher` 두 파일을 공유 |
> | 5 | `OPENSAM-48` | V2-2 부곡 foundation |
> | 6 | `OPENSAM-56` | V2-3 작전 (che_출병 wrapping) |
> | 7 | `OPENSAM-61` | V2-5 가신 (ADR-LITE-017로 1트랙 병합) |
> | 8 | **신규 전투 프로그램 — 키 발행 대기** | 공통 battle-engine 기반 → 야전 → 공성 → 수전 → 2.5D 클라이언트 → G0–G6 출시 판정 |
> | | **기존 부분합** | **20 + 신규 전투 티켓(후속 plan에서 확정)** |
> | — | **오픈** | 출시 보류 조건(`2026-06-29-v2-release-implementation-plan.md` §6) 통과 시 |
>
> R1~R6의 산출물·삽입 위치·T2 파일별 가드 영향·v1-inert 증명·DoD는 `round3-proposal-city-guanxi.md` §9.2·§7.1-2·§7.2에 있고, 발행 결과표(코드 ↔ Jira 키 ↔ GitHub 번호)는 `docs/loops/v2-planning-2026-07-12/TICKETS-issued.md`에 있다.
> **R2 분해 주의** — R2가 최대 티켓이라 반나절 규율로 분해하면 20 → 21이 될 수 있다. 동일 산출물의 분해이지 범위 추가가 아니다(ADR-LITE-021).
>
> 오픈 후: `V2-G0`(36~42) · `C-track`(51~55) · `I0`/`V2-6`(62~65) · `O0`/`V2-7`(66~69) · 전투 외 `V2-8` 잔여(70~72) · **장수↔장수 관계망 7티켓**(P0~P6, §9.4). 기존 `V2-4A`(57)·`V2-4B`(58~60)는 신규 전투 프로그램으로 대체·재분해한다.

1. **선행(비-v2)**: v1 안정화 잔여 D4-01~17 + 배포 체크 D4-31~35. B1은 오픈 전 hardening gate 승격.
2. **V2-0A** production 격리(0A-a~g = D4-18~22) — 모든 v2 코드의 관문.
3. **V2-G0** 3웨이브 병렬 가능(G0-A 행정 / G0-B 주변세계 / G0-C 3D) — in-memory, DB write 없음. T1 그룹 A/B/E/F/G의 `[문서]` 모델·validator 티켓이 여기 속한다.
4. **V2-0B** sandbox runtime 계약 — OP43이 test-only V900 probe와 typed adapter를 검증하고, OP44가 `[아키]` 항목을 실제 소비 제품 티켓으로 분해한다. 제품 SQL은 OP150 `V901`부터 시작한다.
5. **V2-1** 명령 lifecycle → 이후 execution-plan 순서대로. C-track은 exact-count 게이트(120/72/18/24/24/32) 준수, ContentEntry는 ACTIVE만 완료 계수.
6. **매 phase 공통 게이트** GATE-a~f (PHP oracle 증거·webapp-testing·loop 증거·check.py strict·v1 gate·외부 리뷰어).

## 비범위 불변 (전 티켓 공통)

v1 PHP 패러티(골든·로그·RNG·officer_level·병종 수치) 변경 금지 · s1 production에 v2 주입 금지 · 런타임 LLM 금지 · one-daemon-write rule(ChangeRecorder→JdbcFlushExecutor) 유지 · CHRONICLE에서 연의·게임 콘텐츠 역사화 금지.
