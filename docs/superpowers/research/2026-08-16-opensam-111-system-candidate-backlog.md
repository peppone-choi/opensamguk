# OPENSAM-111 시스템 후보 통합 백로그 — 벤치마킹 ①②③ 종합

- 작성일: 2026-08-16 (Asia/Seoul)
- 상태: `PROPOSAL_AWAITING_APPROVAL` — 우선순위는 **제안**이며 결정이 아니다. 사용자 승인 전 후보별 티켓 분해·구현을 시작하지 않는다.
- 에픽: OPENSAM-107 · 티켓: OPENSAM-111
- base: `origin/main` `d63f6fec`

## 0. 입력과 계약

| 소스 | 티켓 | 리포트 | 상태 |
|---|---|---|---|
| ① 국내 삼모 계열 (묘삼·samnet) | OPENSAM-108 | `docs/superpowers/research/2026-08-13-opensam-108-domestic-sammo-differential.md` | `INCOMPLETE_BLOCKED` (리포트 자체 판정) |
| ② 코에이 RTK 시리즈 | OPENSAM-109 | `docs/superpowers/research/2026-07-17-rtk-system-candidate-catalog.md` | `DONE_WITH_CONCERNS` |
| ③ 기타 삼국지 게임 | OPENSAM-110 | `docs/superpowers/research/2026-08-13-opensam-110-other-three-kingdoms-games.md` | `DONE_WITH_CONCERNS` |

이 문서는 위 세 리포트에 **이미 기록된 후보만** 통합·중복제거한다. 세 리포트에 없는 시스템을 새로 발명하지 않았고, 리포트가 `UNKNOWN`으로 남긴 항목은 `UNKNOWN`으로 이어받았다. 외부 URL은 재검증하지 않았으며 근거는 원 리포트의 해당 섹션을 가리킨다(§9 참조).

### 0.1 판정 언어 (원 리포트 계승)

- devsam 차분 분류: `신규` / `변경` / `제거` / `DIFFERENTIAL-UNKNOWN`.
  - ①은 PHP `legacy/devsam-core`·`hwe/ts` 오라클 부재로 **모든 항목이 `DIFFERENTIAL-UNKNOWN`**이다(①§4.1·§5.1).
  - ②③은 devsam이 아닌 외부 제품 관찰이므로 devsam 대비 분류가 원리적으로 성립하지 않는다. 여기서는 "**우리 v1 표면 대비**" 신규/변경으로 표기하고, devsam 차분은 `N/A(외부제품)`로 둔다.
- 도입 판정: `ADAPT`(핵심 아이디어만) / `HOLD`(근거·선행계약 부족) / `REJECT`(v2 정본과 충돌) / `ALREADY-TICKETED` / `DISCOVERY-ONLY`.
- 구현 난이도(우리 아키텍처 기준): **낮음** = 기존 read model/projection에 필드·필터 추가 · **중간** = 새 versioned state/delta + flush·rehydrate·read projection 필요 · **높음** = 새 runtime cadence/동기화 모델 또는 daemon 계약 변경(③§2.3 기준 계승).

### 0.2 격리 원칙 — M-config 선행 여부

OPENSAM-111 AC는 "후보별 M-config 선행 필요 여부 명시"를 요구한다. 판정은 다음과 같다.

> **본 백로그의 전 후보에 대해 M-config는 선행 요건이 아니다.**

근거: ①§9.1·§9.2가 기록한 대로 ADR-LITE-018은 v1 `GameConst`/PHP 골든을 **동결**하고 v2를 **별도 DB/profile/route/Flyway 제품**으로 분리한다. 따라서 후보는 "frozen-baseline 갱신 후 v1 시스템 변경"이 아니라 **v2 격리 게이트 위의 sanctioned divergence**로 구현된다. 상위 에픽 OPENSAM-107 본문의 M-config 문구를 후속 티켓의 전제로 복사하면 안 된다(①§9.2가 명시한 conflict).

부수 조건: 어떤 후보도 v1의 RNG draw·반올림·한국어 로그·`officer_level`·예약 결과를 바꾸지 않는다(②조사 경계, ①§9.1). v1 표면 변경을 요구하는 후보가 나타나면 그 시점에 M-config 판정을 다시 연다. 현재 그런 후보는 **없다**.

## 1. 중복 제거 결과

원 리포트의 raw 후보는 ① 9건 · ② 16건 · ③ 13건(후속 4항목 + 카탈로그 9게임)이다. 동일 시스템을 다른 소스가 각각 제시한 경우를 병합해 **통합 후보 22건**으로 정리했다. 병합 8건은 다중 출처이므로 **검증 신호**로 취급한다(OPENSAM-111 범위 1의 지시).

| 통합 ID | 병합된 raw 후보 | 출처 수 |
|---|---|---|
| `SYS-03` | ① `DOM-02` 임원진 담당도시 효과 + ② `DOM-14-REGIONAL-OFFICER` | 2 |
| `SYS-04` | ② `BATTLE-14-FORMATION-SUPPLY`의 병참단절 + ③ 삼국지 클래식 보급 5단계 + ① 묘삼 수송 | 3 |
| `SYS-02` | ① `DOM-04` 공개 feed/전투 payload + ③ Observer/Presentation/Replay·History Book·보존정책 | 2 |
| `SYS-05` | ② `DIP-14-ALLIANCE` + ② `DIP-8R-COALITION` | 2 |
| `SYS-08` | ② `HR-8R-RECRUIT-RELATION` + ② `HR-13-BOND-NETWORK` + ③ Total War 관계(Guanxi) 참고 | 3 |
| `SYS-09` | ② `STR-14-STATE-SABOTAGE` + ② `STR-8R-INTEL-BRIDGE` | 2 |
| `SYS-12` | ② `ITEM-14-SEARCH-GRANT` + ② `ITEM-8R13-SOCIAL-OBJECT` | 2 |
| `SYS-07` | ② `OFFICE-8R-SCOPE-RANK`(ADAPT) + ② `OFFICE-14-SCALE-UNLOCK`(REJECT, 반례로 보존) | 2 |
| `SYS-19` | ③ Full Delegation 패널 + ③ Last Warlord 정책 기반 위임 taxonomy | 2 |

주의(원 리포트가 명시한 오독 방지): ①§4는 **묘삼에 확인된 장수↔장수 관계망이 없다**고 기록한다. `SYS-08` 관계 그래프를 묘삼 근거로 정당화하면 안 된다. 출처는 ②③뿐이다.

## 2. 통합 후보 표

`devsam 차분` 열은 §0.1 규칙을 따른다. `난이도` = 우리 memory-centric CQRS + one-daemon-write rule + PHP 패러티 동결 기준.

| ID | 후보 | 출처 | devsam 차분 | 멀티 적합성 | 난이도 | 판정 | V2 로드맵 접점 |
|---|---|---|---|---|---|---|---|
| `SYS-01` | 개인턴/사령턴/월·시즌 cadence 분리 | ② `DOM-8R-MONTH-SEASON` | N/A(외부제품). v1 대비 **변경**(상중하순 UX 유지, 정책 cadence 추가) | 높음 — 짧은 접속·비동기 제출과 정합 | 중간 | `ADAPT` | **기존** — OPENSAM-19(명령 lifecycle), OPENSAM-30(P-* 계약) |
| `SYS-02` | 관전·기록 read model (공개 요약 / 인가 상세 / 아카이브 replay 3분리) | ① `DOM-04` + ③ 후속1 | ①은 `DIFFERENTIAL-UNKNOWN`, ③은 N/A. v1 대비 **신규** | 높음 — fog·기밀 경계가 핵심 | 중간 | `ADAPT` | **기존(부분)** — OPENSAM-24 replay spine 위 소비. 미인증 공개 projection은 **신규** |
| `SYS-03` | 담당관·부서 배치 효과 (도시/지역 한정 판정) | ① `DOM-02` + ② `DOM-14` | ①`DIFFERENTIAL-UNKNOWN`. v1 `ProcessIncome.officerCntByCity` 대비 **변경/확장** | 중간 — 자리 독점·무응답 위임 위험 | 중간 | `ADAPT` | **기존(선행)** — OPENSAM-150/151/155 도시 원장 뒤. 효과 calculator 자체는 **신규** |
| `SYS-04` | 보급 단계 상태 + 수송 ETA + 병참단절/포위·기근 | ② `BATTLE-14` + ③ 후속2 + ① 묘삼 수송 | N/A + ①`DIFFERENTIAL-UNKNOWN`. v1 대비 **신규** | 높음 — 전선 지연이 비동기와 정합 | 중간~높음 | `ADAPT` | **기존(부분)** — OPENSAM-23 Operation 경로/원군 지연. shipment aggregate는 **신규** |
| `SYS-05` | 외교 계약 (기간제 동맹·표적 연합·원군 의무·파기 notice) | ② `DIP-14` + `DIP-8R` | N/A. v1 외교 제안/수락 대비 **변경/확장** | 높음 — 만료·비공격 상태가 비동기에 적합 | 중간 | `ADAPT` | **기존(부분)** — OPENSAM-28 봉신·조공. 일반 `DiplomaticContract`는 **신규** |
| `SYS-06` | 전투 사전 회의 + 지연 원군 + 전의·기상 fixed tick | ② `BATTLE-8R-COUNCIL-MORALE` | N/A. v1 전투 대비 **신규**(v1 전투는 불변) | 중간 — 총대장 독점·전역 pause 금지 필요 | 높음 | `ADAPT` | **기존** — OPENSAM-21(B0), OPENSAM-25(4B runtime) |
| `SYS-07` | 관직 권한 범위·품계·공적 (claim/tenure/assignment 분리) | ② `OFFICE-8R`(ADAPT) + `OFFICE-14`(REJECT) | N/A. v1 `officer_level` 대비 **신규**(v1은 adapter로 보존) | 높음 — 분업 구조에 적합 | 중간 | `ADAPT` (규모→작위 직접 unlock은 `REJECT`) | **기존** — OPENSAM-28 (O0 8계약 + resolver) |
| `SYS-08` | 관계 그래프 (방향성 신뢰·유대·소개 edge, consent 기반) | ② `HR-8R` + `HR-13` + ③ TW3K | N/A. v1 대비 **신규** | 중간 — alt farming·harassment 위험 큼 | 중간 | `ADAPT` | **신규** — 명시 에픽 없음. OPENSAM-26 가신과 인접하나 별개 |
| `SYS-09` | 계략 상태기계 (prepare→detect/counter→resolve) + 만료형 첩보 | ② `STR-14` + `STR-8R` | N/A. v1 `che_` 계략 대비 **신규**(v1 의미 불변) | 중간 — offline 타격·정보 oracle 위험 | 중간~높음 | `ADAPT` | **신규** — v2 espionage namespace 미착수 |
| `SYS-10` | opt-in 이벤트 분기 계약 (audience·expiry·quorum·branch lock) | ② `EVENT-8R` | N/A. v1 월간 이벤트 대비 **신규** | 중간 — actor hostage·deadline grief | 중간 | `ADAPT` | **기존(부분)** — v2 event DSL/chronicle. 공유월드 quorum은 **신규** |
| `SYS-11` | 도시 소유 금·병량 + 도시병사 원장 | ① `DOM-01` | `DIFFERENTIAL-UNKNOWN` | 높음 | 중간 | `ALREADY-TICKETED` | **기존** — OPENSAM-150~155. **새 티켓 금지**, provenance link만 |
| `SYS-12` | 명품·보물 ledger (stable ID·유일 소유·이전 이력) | ② `ITEM-14` + `ITEM-8R13` | N/A. v1 아이템/경매 대비 **신규**(v1 수치 불변) | 중간 — dupe·독점·alt 보관 위험 | 중간 | `HOLD` (rights + 제품 정본 긴장) | **기존(부분)** — OPENSAM-22 콘텐츠 승격. asset rights 미완 |
| `SYS-13` | 도시 시설·장애물 ↔ 전장 phase 연계 | ① `DOM-03` | `DIFFERENTIAL-UNKNOWN` | 중간 | 중간 | `CANDIDATE-DUPLICATE-CHECK` | **기존** — OPENSAM-53, OPENSAM-171, OPENSAM-22(시설 72) |
| `SYS-14` | 2D/3D 지도 전환·공간 선택 | ① `DOM-07` | `DIFFERENTIAL-UNKNOWN` | 중간 | 높음 | `ALREADY-TICKETED` | **기존** — OPENSAM-17(G0-C), OPENSAM-41, OPENSAM-173, OPENSAM-29 |
| `SYS-15` | 지도 기반 시작 도시 선택 + 세력 혼잡도 | ① `DOM-05` | `DIFFERENTIAL-UNKNOWN` | 중간 — 쏠림·다계정·스파이 | 중간 | `CANDIDATE-DEFER` | **신규** — v2 account/possession foundation 티켓 ID가 `UNKNOWN`(①Draft C) |
| `SYS-16` | 황건적 토벌 공개 이벤트 | ① `DOM-06` | `DIFFERENTIAL-UNKNOWN` | `UNKNOWN` | `UNKNOWN` | `DISCOVERY-ONLY` | **신규** — 규칙 5항목 중 하나라도 UNKNOWN이면 승격 금지(①Draft D) |
| `SYS-17` | 조건형 이벤트 카탈로그(역사/범용 prerequisites) | ② `EVENT-14-CONDITION-CATALOG` | N/A. v1 대비 **신규** | 낮음 | 중간 | `HOLD` — 팬위키 `E-C/R4`, 재배포 권리 미관찰 | **기존(부분)** — CLASSIC overlay. ingest 금지, 질문 목록으로만 |
| `SYS-18` | 결정적 authoritative engine 외부 benchmark spike | ③ Late Eastern Han Dynasty (MIT) | N/A | 구조 참고 높음 / 실제 multi 낮음 | 중간 | `FOLLOW-UP` (spike) | **신규** — acceptance vocabulary 정리용, 코드 복제 금지 |
| `SYS-19` | AI 위임 진행 표시 + 정책 기반 원격 관리 taxonomy | ③ Full Delegation + Last Warlord | N/A. v1 autorun 정책 대비 **변경** | 중간 — 권한 위임 계약 필요 | 낮음~중간 | `FOLLOW-UP` | **기존(부분)** — OPENSAM-25(AI delegation), OPENSAM-29(위임 UI) |
| `SYS-20` | bounded room/world lifecycle (짧은 매치형 월드) | ③ Sanguo's Ambition 4 | N/A | 상품 참고 높음 / 우리 cadence와 충돌 | 높음 | `FOLLOW-UP` (world lifecycle만) | **신규** — engine 채택은 `DEFER` |
| `SYS-21` | 비동기 상대 스냅샷 대전 (immutable opponent snapshot) | ③ Three Kingdoms:BOND | N/A | async encounter 높음 / grand strategy 낮음 | 중간 | `FOLLOW-UP` | **신규**. 경제·수집 구조는 `REJECT` |
| `SYS-22` | 실시간 대형 sandbox·과금 surface·FMV 내러티브 | ③ Ancient Battlefield / Real-Time War / Rebirth / Oriental Empires | N/A | 우리 예약턴 cadence와 충돌 | 높음 | `REJECT` / `DEFER`(Oriental: 고유 근거 부족) | 접점 없음 — **반례로만 보존** |

부가 관측(후보 아님): ① `DOM-08` 도시 시세·금광·풍작·재난 공개는 v1 월간 재해/호황/`RandomizeCityTradeRate`와 기계적 차분이 확인되지 않아 `NO-DIFFERENTIAL-YET`이며 신규 티켓을 만들지 않는다. ① `DOM-09` 명령 큐·일괄등록·프리셋·가챠는 공개-only 조사에서 `UNKNOWN`이라 `HOLD`다.

## 3. 우선순위 제안 (결정 아님)

### 3.1 순위 기준

원 리포트의 판정을 다섯 축으로 접었다. 위에서 아래로 갈수록 약한 신호다.

1. **선행성** — 다른 후보가 이 계약 없이는 결정적으로 정의되지 않는가 (②§우선순위 F0~F6의 foundation order).
2. **다중 출처** — 두 개 이상 독립 소스가 같은 시스템을 제시했는가 (OPENSAM-111 범위 1의 검증 신호).
3. **기존 로드맵 접점** — 이미 열린 v2 에픽 위에 얹히는가 (새 계약 비용이 낮고 중복 위험이 낮다).
4. **UNKNOWN 밀도** — 원 리포트가 남긴 미확인이 구현을 막는가.
5. **패러티 위험** — v1 골든/RNG/로그에 닿을 여지가 있는가 (현재 전 후보 v2 격리이므로 변별력은 약하다).

### 3.2 P1 — 먼저 계약을 고정할 것 (4건)

| 순위 | 후보 | 왜 이 순서인가 |
|---:|---|---|
| 1 | `SYS-01` cadence 분리 | ②가 vertical slice의 **첫 항목**으로 지목했고(②§우선순위 F3·수직슬라이스), 나머지 거의 모든 후보(외교 settlement·계략 resolve·회의 deadline·이벤트 expiry)가 "언제 판정되는가"를 이 계약에서 빌려 쓴다. 기준 1(선행성)이 압도적이며, 우리 예약턴/월간 파이프라인이 이미 존재해 기준 3도 충족한다. 세계 시간 전진은 서버가 소유하고 유저는 deadline 전 intent만 제출한다는 형태가 우리 intake 구조와 동형이다. |
| 2 | `SYS-02` 관전·기록 read model | 기준 2(2 출처) + 기준 3(OPENSAM-24 replay spine). ③§3.3이 지적한 대로 **simulation semantics를 바꾸지 않고** 검증 가능한 유일한 상위 후보다 — read/projection만 건드리므로 실패해도 월드가 다치지 않는다. 현재 SSE는 coarse `turnCompleted` relay이므로 3-projection 분리는 UI 복제가 아니라 실제 계약 작업이다. |
| 3 | `SYS-03` 담당관·부서 배치 효과 | 기준 2(①②) + 기준 3(도시 원장 150~155가 이미 진행). ①§4.1이 v1 `officerCntByCity`라는 **실재 확장점**을 지목했다는 점이 다른 후보와 다르다 — 새 축을 만들지 않고 기존 축을 넓힌다. 다만 선행 티켓 merge 전 착수 금지(①Draft A 시작 조건). |
| 4 | `SYS-04` 보급 단계 상태 | **유일한 3-출처 후보**(기준 2 최고점). ②의 병참단절·③의 5단계 보급·①의 수송이 서로 독립적으로 같은 결론에 도달했다. 순위가 3위가 아닌 4위인 이유는 기준 4 — 새 persistent aggregate + ordered delta가 필요해 CQRS 비용이 `중간~높음`이고(③§3.2), OPENSAM-23 Operation 계약이 먼저 고정돼야 shipment의 소유자가 정해진다. |

### 3.3 P2 — P1 계약 위에서 (6건)

| 순위 | 후보 | 왜 이 순서인가 |
|---:|---|---|
| 5 | `SYS-05` 외교 계약 | 2 출처 + `SYS-01`의 시즌 settlement를 즉시 소비한다. ②§F4가 domestic/contract 다음 단계로 지목. 원군 의무가 `SYS-04`·`SYS-06`의 입력이 되므로 전투 계열보다 앞선다. |
| 6 | `SYS-06` 전투 회의·지연 원군 | ②의 수직 슬라이스 3번째 항목. 난이도 `높음`이고 OPENSAM-21/25가 선행이라 P1에 둘 수 없지만, `SYS-05`의 원군 계약이 도착하는 지점이라 5번 직후가 자연스럽다. |
| 7 | `SYS-07` 관직 권한 범위 | OPENSAM-28에 이미 8계약 + resolver가 잡혀 있어 기준 3이 강하다. P1이 아닌 이유는 이 후보가 다른 후보를 막지 않기 때문이다(권한 없이도 위 계약들은 정의된다). ②가 `OFFICE-14`를 `REJECT`한 반례를 함께 들고 있어 설계 방향이 이미 좁혀져 있다. |
| 8 | `SYS-09` 계략 상태기계 | 2 출처. `SYS-01`(resolve 시점)과 `SYS-05`(관계 악화 결과)에 모두 의존하고, offline 플레이어 타격이라는 멀티 위험이 가장 큰 축이라 계약이 성숙한 뒤가 안전하다. |
| 9 | `SYS-10` opt-in 이벤트 분기 | ②§F6이 명시적으로 **마지막**에 열라고 한 축이다(앞선 권한·관계·자원·전투 상태를 소비). P2 하단에 두되 P3로 내리지 않는 이유는 계약 자체(offer/expiry/quorum)가 다른 후보와 독립적으로 설계 가능하기 때문이다. |
| 10 | `SYS-19` AI 위임 진행 표시 | 난이도 `낮음~중간`이고 OPENSAM-25/29에 접점이 있다. 순위가 낮은 이유는 기준 1이 0이기 때문 — 아무것도 막지 않는다. 반대로 저비용이므로 상위 작업의 여유 슬롯에 끼워 넣기 좋다. |

### 3.4 P3 — 보류·조사·반례 (12건)

| 후보 | 처분 사유 |
|---|---|
| `SYS-08` 관계 그래프 | 2~3 출처로 신호는 강하지만 v2 로드맵에 대응 에픽이 없고(순수 신규 축), ②가 남긴 UNKNOWN(친밀 증감·수락·해소)이 크며, 플레이어 간 관계를 일방 변경하면 안 된다는 consent 설계가 통째로 미결이다. 기준 4로 P3. **다만 P3 중 가장 먼저 재검토할 후보다.** |
| `SYS-11` 도시 원장 | `ALREADY-TICKETED`(150~155). 새 티켓 금지, provenance link만. |
| `SYS-13` 시설·장애물 | 중복 확인 먼저(53/171/22). gap이 확인되기 전에는 티켓 없음. |
| `SYS-14` 2D/3D 지도 | `ALREADY-TICKETED`(17/41/173/29). |
| `SYS-12` 명품·보물 ledger | `HOLD` — asset/IP rights 미cleared, 무작위 전리품이 v2 제품 정본과 긴장(②`ITEM-14`). |
| `SYS-15` 시작 도시 선택 | `DEFER` — 선행 v2 account/possession foundation 티켓 ID가 `UNKNOWN`이라 startable하지 않다(①Draft C). |
| `SYS-16` 황건적 이벤트 | `DISCOVERY-ONLY` — 규칙 전부 `UNKNOWN`. spike 산출물 없이는 구현 티켓 승격 금지. |
| `SYS-17` 조건형 이벤트 카탈로그 | `HOLD` — 재배포 권리 미관찰(`R4`). bulk ingest·표/이미지 번들 금지. |
| `SYS-18` 결정론 benchmark spike | 저비용 spike. 우선순위는 낮지만 `SYS-02`/`SYS-04`의 acceptance 어휘를 정리해 주므로 P1 착수 전 병렬 실행이 가능하다. |
| `SYS-20` bounded room world | `FOLLOW-UP` — world lifecycle 개념만. engine 채택 `DEFER`. |
| `SYS-21` 비동기 스냅샷 대전 | `FOLLOW-UP` — grand strategy 적합성 낮음. |
| `SYS-22` 실시간/과금/FMV 계열 | `REJECT`. 우리 cadence의 counterexample로만 보존. |

### 3.5 제안 수직 슬라이스

②§우선순위가 제시한 슬라이스를 통합 ID로 옮기면 다음과 같다. 이것도 **제안**이다.

`SYS-01` cadence → `SYS-05`의 원군 의무 → `SYS-06`의 회의/지연 원군 → `SYS-02`의 아카이브 replay → `SYS-10`의 전후 선택

`SYS-18` spike는 이 슬라이스 착수 전 병렬로 돌려 replay/determinism 수용 어휘를 고정하는 데 쓸 수 있다.

## 4. V2 로드맵(OPENSAM-17~30) 대비 접점

### 4.1 이미 계획에 있는 것

| V2 에픽 | 대응 후보 |
|---|---|
| OPENSAM-17 `[V2-G0]` 지리·3D 공간 계약 | `SYS-14` |
| OPENSAM-19 `[V2-1]` 명령 result lifecycle + 카탈로그 | `SYS-01`(부분) |
| OPENSAM-21 `[Spike B0+C0]` 전술·콘텐츠 lifecycle | `SYS-06`(부분), `SYS-13`(부분) |
| OPENSAM-22 `[C-track]` 콘텐츠 승격 C1~C5 | `SYS-12`(부분), `SYS-13`(시설 72) |
| OPENSAM-23 `[V2-3]` 작전(Operation) | `SYS-04`(원군 지연·경로), `SYS-06`(부분) |
| OPENSAM-24 `[V2-4A]` replay spine | `SYS-02`(아카이브 replay) |
| OPENSAM-25 `[V2-4B]` 실시간 formation 전투 | `SYS-06`, `SYS-19` |
| OPENSAM-26 `[V2-5]` 가신 | `SYS-08` 인접(별개), `SYS-19` 인접 |
| OPENSAM-27 `[V2-6+I0]` 어전회의·정체성 | `SYS-06` 회의 개념과 구분 필요(어전회의 ≠ 전투 군의) |
| OPENSAM-28 `[V2-7+O0]` 황실·관직·개혁·봉신 | `SYS-07`, `SYS-05`(봉신·조공 부분) |
| OPENSAM-29 `[V2-8]` hardening | `SYS-14`, `SYS-19`(위임 UI) |
| OPENSAM-30 `[계약동결]` product-spec 계약 레인 | `SYS-01` cadence 계약의 귀속 후보처 |
| OPENSAM-150~155 (에픽 외 도시 원장) | `SYS-11`, `SYS-03`의 선행 |

### 4.2 신규 (대응 에픽 없음)

- `SYS-08` 관계 그래프 — 가장 큰 신규 축. OPENSAM-26 가신과 인접하나 별개 모델이다.
- `SYS-09` 계략 상태기계 — v2 espionage namespace 자체가 미착수.
- `SYS-02`의 **미인증 공개 projection** — replay spine(24)은 있으나 로그인 전 공개 표면 계약은 없다.
- `SYS-04`의 **shipment aggregate** — Operation(23)에 원군 지연은 있으나 보급 물류 상태기계는 없다.
- `SYS-05`의 **일반 `DiplomaticContract`** — 28은 봉신·조공 계약이며 일반 동맹/연합은 별도 타입이어야 한다(②`DIP-14` conflict 항).
- `SYS-10`의 **공유월드 quorum/offline fallback** — 제품 결정 미완.
- `SYS-15` 온보딩 시작 위치 — 선행 foundation 티켓 자체가 `UNKNOWN`.
- `SYS-16` 황건적, `SYS-18` benchmark spike, `SYS-20` room lifecycle, `SYS-21` 비동기 대전.

## 5. 이어받은 UNKNOWN

원 리포트가 남긴 것을 그대로 승계한다. 아래를 추론으로 메우지 않는다.

**① 국내 삼모**
- 묘삼·samnet 전 항목의 **devsam 차분** — PHP `legacy/devsam-core`·`hwe/ts` 오라클 부재로 `DIFFERENTIAL-UNKNOWN`.
- 묘삼: 현행 명령 전체 목록, 제거된 devsam 명령, 외교 상태기계·조약 수치, NPC/AI 의사결정식·RNG, 계승·유산 포인트, 현재 운영 여부/버전.
- samnet: 명령 셋·예약 슬롯 수, 내정 명령·국가 정책·관직 권한, 외교·서신·조약, NPC/황건적 AI, 계승·유산·성장, 전투 replay의 피해 공식·RNG·서버 판정, 인증 후 UI(일괄등록·프리셋·가챠).
- `sam.peppone.dev` 공개 관찰 실패.

**② RTK**
- 모든 공식 페이지의 build/patch fingerprint, 숨은 산식, RNG draw, AI 보정.
- RTK14 공식 페이지의 base/PK 혼재 범위.
- 후보별: 동맹 성공 산식·파기 페널티 / 연합 최대 회원·수명·AI 응답 / 계략 성공률·비용·저항·중첩 / 첩보 counterplay·중복 내통·플레이어 장수 동의 모델 / 아이템 uniqueness·발견 풀·확률·압수·상속 / RTK8R 가격·재고 회전, RTK13 취향 보정치 / 작위 조건·관직 수치 / 품계 threshold·특권·경쟁 claim / 진형 계수·전법 연계 산식 / 날짜-실시간 변환·원군 철회·동시 전투 cadence / 개발 산식·담당관 교체 비용·자동 점령 conflict 순서 / 친밀 증감·AI 수락·관계 해소 / 가치관 수치·관계 상한·전향 저항 / 이벤트 quorum·offline fallback / 이벤트 전체 조건·효과·공식 정확성.
- WIKIWIKI 재배포 권리 미관찰.

**③ 기타 게임**
- 삼국지 클래식: offline 실행 보장, determinism(seed·draw order·command-log replay), authority 모델 — 전부 `UNKNOWN`.
- 카탈로그 전 게임: offline·seed·replay·서버 권위 대부분 `UNKNOWN`. closed-source 후보 중 memory-centric CQRS / event sourcing / durable idempotent intake / seeded authoritative multiplayer replay를 명시한 제품은 **없었다**.
- 공식 사이트와 Steam의 출시일 1일 차이 원인 `UNKNOWN`.

## 6. 위험·제약 (원 리포트 계승)

1. **v1에 이식하지 않는다.** 전 후보는 v2 profile/DB/route/Flyway 아래 sanctioned divergence다.
2. **M-config를 후속 티켓 전제로 복사하지 않는다.** §0.2 참조 — 상위 에픽 문구와 ADR-LITE-018이 충돌하며 후자가 이긴다.
3. **보이는 UI는 mechanics 증거가 아니다.** samnet의 버튼·피드·전쟁 라벨에서 수치·RNG·AI를 추론하지 않는다.
4. **중복 생성 금지.** `SYS-11`·`SYS-13`·`SYS-14`는 열린 티켓의 provenance/AC gap으로 먼저 소비한다.
5. **명칭·문구·자산 복제 금지.** RTK/WIKIWIKI/타사 게임의 이름·표·이미지·데이터를 번들하지 않는다. 시스템 패턴만 요약해 우리 도메인 언어로 재설계한다.
6. **증거 확신도를 섞지 않는다.** 묘삼 = historical, samnet = unauthenticated shell, RTK = 공식 매뉴얼/홍보, 기타 게임 = store/공식 공지, 우리 = repository. 다섯을 같은 확신도로 합치지 않는다.
7. **①은 자체 판정이 `INCOMPLETE_BLOCKED`이다.** ① 유래 후보(`SYS-11`·`SYS-13`~`SYS-16`, `SYS-03`의 묘삼 절반)를 근거로 release 결정을 내리지 않는다.

## 7. 다음 행동

AC에 따라 이 문서는 **사용자 승인 대기** 상태로 마감한다. 승인 후에야 후보별 스펙/구현 티켓 분해를 시작한다. 승인 전 수행 가능한 무비용 작업은 다음 두 가지뿐이다.

- `SYS-11`·`SYS-14`의 provenance link를 기존 티켓(150~155, 17/41/173)에 추가.
- `SYS-13`의 중복 확인(53/171/22 대비 gap 유무).

## 8. 검증 명령

```bash
test -s docs/superpowers/research/2026-08-16-opensam-111-system-candidate-backlog.md
test "$(rg -o '`SYS-[0-9]{2}`' docs/superpowers/research/2026-08-16-opensam-111-system-candidate-backlog.md | sort -u | wc -l)" -eq 22
rg -n 'UNKNOWN|M-config|ALREADY-TICKETED|REJECT|HOLD' docs/superpowers/research/2026-08-16-opensam-111-system-candidate-backlog.md
git diff --check -- docs/superpowers/research/2026-08-16-opensam-111-system-candidate-backlog.md
tools/agent-system/check.py
```

## 9. 근거 원장

이 문서는 1차 소스를 재방문하지 않았다. 모든 규칙 요약과 URL은 아래 리포트가 기록한 것이다.

| 후보 범위 | 근거 위치 |
|---|---|
| `SYS-11`·`SYS-03`(묘삼측)·`SYS-13`·`SYS-15`·`SYS-16`·`SYS-14`·`SYS-02`(samnet측) | ① `2026-08-13-opensam-108-domestic-sammo-differential.md` §2 소스 원장, §4, §5, §7 후보 목록, §8 Draft A~D |
| `SYS-01`·`SYS-03`(RTK측)·`SYS-04`(병참)·`SYS-05`·`SYS-06`·`SYS-07`·`SYS-08`·`SYS-09`·`SYS-10`·`SYS-12`·`SYS-17` | ② `2026-07-17-rtk-system-candidate-catalog.md` — 축별 `candidate_id` 블록의 `exact evidence` 행 (`E-A`/`E-B`/`E-C`, `R3`/`R4`) |
| `SYS-02`(관전·기록)·`SYS-04`(보급 5단계)·`SYS-18`·`SYS-19`·`SYS-20`·`SYS-21`·`SYS-22` | ③ `2026-08-13-opensam-110-other-three-kingdoms-games.md` §3.2, §3.3, §4 카탈로그 표, §5 우선순위 |
| ③ 후속 draft 3종 | `docs/superpowers/plans/2026-08-13-opensam-110-{spectator-record,supply,deterministic-benchmark}-follow-up-draft.md` (모두 `DRAFT / NOT APPROVED`) |
