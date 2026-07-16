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

`[아키]` 태그(04-systems): 스펙이 아니라 CQRS 아키텍처에서 추론한 산출물(마이그레이션/mapper/flush/read API). **영속화 시점은 V2-0B 이후**(문서1), 문서2는 미명시 → 착수 전 확정 필요.

## 계층 구조 (Jira 매핑)

- **Epic = phase/트랙** (V2-0A, V2-G0, V2-0B, V2-1, V2-2, B0, C0, C-track, V2-3, V2-4A, V2-4B, V2-5, I0, V2-6, O0, V2-7, V2-8, 콘텐츠 카탈로그, 커맨드 카탈로그, 정체성, 황실·관직).
- **Story/작업 = wave·그룹** (G0-A/B/C, 카탈로그 그룹 A~N, systems 그룹 A~K …) — 그룹당 Jira 이슈 1개, 설명에 소속 마이크로 티켓 ID 체크리스트.
- **마이크로 티켓** = 소스 4파일의 ID(P-*, 0A-*, G0A-*, D1~D4-*, T1/T2-*) — Jira 이슈 본문 체크리스트 항목으로 유지하고, 착수 시점에 개별 이슈로 승격(just-in-time 승격).

이렇게 하면 "굉장히 작은 단위" 분해는 소스 파일+체크리스트로 보존되고, Jira는 실행 가능한 수준(수십 개)으로 유지된다.

## 착수 순서 (정본 게이팅 반영)

1. **선행(비-v2)**: v1 안정화 잔여 D4-01~17 + 배포 체크 D4-31~35. B1은 오픈 전 hardening gate 승격.
2. **V2-0A** production 격리(0A-a~g = D4-18~22) — 모든 v2 코드의 관문.
3. **V2-G0** 3웨이브 병렬 가능(G0-A 행정 / G0-B 주변세계 / G0-C 3D) — in-memory, DB write 없음. T1 그룹 A/B/E/F/G의 `[문서]` 모델·validator 티켓이 여기 속한다.
4. **V2-0B** sandbox 적재 — `[아키]` 마이그레이션/mapper/flush 티켓 일괄 여기서.
5. **V2-1** 명령 lifecycle → 이후 execution-plan 순서대로. C-track은 exact-count 게이트(120/72/18/24/24/32) 준수, ContentEntry는 ACTIVE만 완료 계수.
6. **매 phase 공통 게이트** GATE-a~f (PHP oracle 증거·webapp-testing·loop 증거·check.py strict·v1 gate·외부 리뷰어).

## 비범위 불변 (전 티켓 공통)

v1 PHP 패러티(골든·로그·RNG·officer_level·병종 수치) 변경 금지 · s1 production에 v2 주입 금지 · 런타임 LLM 금지 · one-daemon-write rule(ChangeRecorder→JdbcFlushExecutor) 유지 · CHRONICLE에서 연의·게임 콘텐츠 역사화 금지.
