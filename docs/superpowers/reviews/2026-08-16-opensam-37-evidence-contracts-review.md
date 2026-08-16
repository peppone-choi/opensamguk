# OPENSAM-37 [G0-A②] 출처·확실성 계약 적대적 비평

Date: 2026-08-16

Scope: logic/src/main/kotlin/opensamguk/logic/v2/evidence + logic/src/test/kotlin/opensamguk/logic/v2/evidence 신규 v2 출처·확실성 계약(T1-A01/A04/A05/A06/A15/A16/K01)과 CHGIS 라이선스 검토 문서 (PR #408).

Reviewer: 코드 작성자가 아닌 독립 비평 에이전트. 브랜치 `op-37-g0a2-evidence-contracts`, base `origin/main`.

## 무엇을 어떻게 공격했나

### A05 역투영 — 뚫으려 한 3가지 경로

1. **활성화 판정이 attestationDate에 걸려 있는가.** `isActivatableAt`(EvidenceContractValidator.kt:84)은
   `worldYear`를 `subjectPeriodFrom..subjectPeriodTo`로 먼저 자른다. `attestationDate`는 활성화 판정에
   전혀 등장하지 않는다. 429년 배송지주가 250-280년을 서술하는 claim은 189년 월드에서 활성화되지 않는다
   (테스트 `후대 사료는 189년 월드에 역투영되지 않는다`). **버텼다.**
2. **validFrom/To를 조작해 우회.** `validFrom = 189`로 넓히면 V-A05-3, `validTo`를 늘려도 V-A05-3.
   설령 검증을 건너뛰고 `validFrom=189`인 claim을 만들어도 `isActivatableAt`은 subjectPeriod로 먼저
   자르므로 활성화되지 않는다 — **유효 구간은 좁힐 수만 있고 넓힐 수 없다.** 2중 방어 확인. **버텼다.**
3. **subjectPeriodFrom 자체를 넓히기.** 이건 뚫린다: 429년 기록에 `subjectPeriodFrom=180`이라고 적으면
   V-A05-2(attestation ≥ from)도 통과하고 189년 활성화된다. 다만 이는 계약이 막을 수 있는 종류가 아니라
   **데이터 작성자의 신뢰 경계**다(아래 L1로 문서화). 기계적 우회 경로는 전부 닫혀 있다.

### A06 등급 혼합 — 표의 구멍 탐색

- `allowedProximities` 5개 등급 전수 확인: `PRIMARY_ATTESTED`에 FICTION·GAME·LATER_TRADITION·
  MODERN_STUDY·EARLY_ANNOTATION을 붙이면 전부 V-A06-1. `SCHOLARLY_RECONSTRUCTION`에 FICTION/GAME도 거부.
  `ROMANCE_ATTESTED`는 FICTION만, `GAME_REFERENCE`는 GAME만. 표 밖 조합은 존재하지 않는다.
- **`BALANCE_ONLY`가 근거 0개를 정말 강제하는가**: `allowedProximities = emptySet()`이므로 *어떤*
  proximity의 근거를 붙여도 V-A06-1이 나온다. `SourceProximity`에 값이 없는 `EvidenceRef`는 만들 수
  없으므로(비-널 필드) 근거를 붙일 방법 자체가 없다. 동시에 V-A06-2의 "근거 0개 금지"에서 BALANCE_ONLY만
  면제된다. **정확히 0개로 고정된다. 버텼다.**
- **정사 claim에 FICTION 근거를 붙이는 우회 경로**: `validateClaimEvidence`는 `validateClaims` →
  `validateWorld`에서 항상 호출되며, 모듈 안에 근거 검증을 건너뛰는 공개 경로가 없다. 없음.

### A15 / A16 — overlay 격리

- CHRONICLE은 `resolveSnapshot`에서 무조건 `emptyList()` → overlay 0개. **버텼다.**
- 손으로 만든 snapshot으로 CHRONICLE에 CLASSIC/LEGACY overlay를 강제 주입 → V-A16-2 + (연의·게임 claim이
  활성이면) V-A16-1. **버텼다.**
- CHRONICLE 프로필 overlay 선언 자체를 V-A15-4가 거부하고, overlay가 base claim id를 재선언하면 V-A15-1로
  덮어쓰기를 막는다. base 계층의 연의·게임 claim은 V-A15-3. **버텼다.**
- **여기서 진짜 구멍이 나왔다 → 아래 F1.**

### K01 라이선스

- `validateBundling`(EvidenceContractValidator.kt:260 부근)은 `bundling != BUNDLING_ALLOWED`인 근거를
  **전부** 위반으로 만든다. 즉 `UNKNOWN`은 통과가 아니라 **차단**이다. 보고 내용과 코드가 일치한다.
  화이트리스트 방식이므로 `LicenseBundling`에 값이 추가돼도 기본이 차단이다. **버텼다.**

## 발견한 결함과 처리

- **F1 (MAJOR, 수정함) — LEGACY overlay가 CLASSIC 월드로 새는 경로.** `validateWorld`는 snapshot의
  `activeOverlayIds`에 대해 (a) 존재 여부와 (b) `profile == CHRONICLE`일 때의 A16만 검사했다. 따라서
  `validateWorld(profile = CLASSIC, snapshot = WorldContentSnapshot(CLASSIC, ["ov-legacy"]))`는
  **위반 0건**을 반환했다 — v1 패러티 콘텐츠(LEGACY, `GAME_REFERENCE`)가 CLASSIC 월드에서 활성화되는데
  아무도 막지 않는다. `resolveSnapshot`은 이런 snapshot을 만들지 않지만, snapshot은 검증 대상 **외부
  입력**이며(A16 테스트가 정확히 손으로 만든 snapshot을 검증한다) 그것이 이 함수의 존재 이유다.
  → overlay 프로필과 월드 프로필 불일치를 V-A15-6으로 거부하도록 수정하고 회귀 테스트
  `CLASSIC 월드가 LEGACY overlay를 활성화하면 거부된다`를 추가했다.
- **F2 (MINOR, 수정함) — 라이선스 문서의 라벨이 자기 판정보다 관대했다.** `chgis-license-review.md` §2는
  상업 프로젝트의 **내부 위치 검증조차 RESTRICTED**로 결론냈는데, §3의 코드 매핑은 CHGIS를
  `RESEARCH_ONLY`로 적었다. 그 enum의 KDoc은 "위치 검증 등 내부 연구에는 쓸 수 있다"이므로 라벨이 실제
  판정보다 넓은 허가를 암시한다(차단 여부는 동일하지만 나중에 읽는 사람이 내부 사용을 승인된 것으로
  오독할 수 있다). → 매핑을 `LicenseBundling.UNKNOWN`으로 바꾸고 이유를 문서에 남겼다.

## CHGIS 라이선스 결론 재검증 (독립 확인)

인용이 실재하는지 브라우저 경유가 아닌 독립 경로로 다시 확인했다:

- `chgis.fas.harvard.edu/data/chgis/v6/` — **"free for academic research, no commercial use, resale, or
  redistribution permitted."** 원문 확인. 인용 일치.
- `chgis.fas.harvard.edu/pages/intro/` — "free distribution to scholars without restriction",
  "a no-cost GIS platform for use in teaching, research, and publications", "© CHGIS 2001 -" 원문 확인.
  라이선스 절 없음. 문서가 이를 "홍보 문구, 근거로 쓸 수 없음"으로 처리한 것은 타당하다.
- DataCite `10.7910/DVN/FDLFJ3` — "CHGIS V6 EULA", Lex Berman, 2016, PDF, rights = **Custom terms**. 존재 확인.
- DataCite `10.7910/DVN/M7WEFY` — "CHGIS V5 Shapefiles (2012)", rights = **"Creative Commons Zero v1.0
  Universal" / cc0-1.0 (SPDX)**. CC0 표기가 실재한다는 문서의 진술도 사실이다.
- V6 EULA **본문 인용은 독립 재확인 불가**(Dataverse가 AWS WAF로 비브라우저 접근을 차단, 문서 §4가 같은
  제약을 이미 기록). 다만 결론이 EULA 본문에만 걸려 있지 않다는 점이 중요하다 — 독립 확인된 버전 페이지
  한 줄("no commercial use, resale, or redistribution permitted")만으로도 **번들 금지** 결론은 유지된다.

**판정: 번들 금지 결론에 동의한다.** CC0 표기와 EULA 금지 조항의 충돌을 UNKNOWN으로 남긴 처리도 타당하다.
같은 저장소가 같은 데이터에 대해 상충하는 두 문서를 내놓았고 CC0 표기가 의도적 권리 부여인지 Dataverse
기본값인지 검증되지 않은 이상, 더 관대한 쪽을 근거로 채택하는 것은 낙관적 해석이다. 여기서는 **금지 조항이
이긴다.** 재검증하려면 CHGIS Management Committee의 서면 확인이 필요하며 웹 표기만으로는 뒤집을 수 없다.

## 남은 한계 (수정하지 않음 — 알고 남긴다)

- **L1.** `subjectPeriodFrom/To`는 작성자가 선언하는 신뢰 경계다. 계약은 그 값을 **기준으로 한** 역투영을
  전부 막지만 값 자체의 정직성은 검증할 수 없다. 데이터 리뷰가 담당한다.
- **L2.** `V-A05-2`(attestation ≥ subjectPeriodFrom)는 대상 시기보다 먼저 발행된 기록(예: 188년 조서가
  189년부터 유효한 군을 설치)에 대해 오탐이다. 작성자가 이를 `subjectPeriodFrom`을 넓혀 회피하면 L1과
  같은 위험이 된다. 향후 그런 데이터가 실제로 나오면 별도 필드로 다뤄야지 기간을 넓혀 회피해선 안 된다.
- **L3.** `validateBundling`은 `validateWorld`에서 호출되지 않는 **export 시점 게이트**다. 현재 번들·export
  경로가 존재하지 않으므로 의도적이지만, 자산 export 경로가 생기면 반드시 이 함수를 통과시켜야 한다.
- **L4.** `isActivatableAt`은 아직 호출자가 없다(계약 전용 티켓). 활성화 경로가 붙을 때 이 함수를 지나야 한다.

## 위생 점검

- **v1 패러티 침범 0.** `git diff --name-only --diff-filter=MD origin/main...` = 빈 출력. 수정·삭제된
  기존 파일 0건, 전부 신규 추가. RNG·로그·골든·`GameConst`·`PhpRound` 미참조(신규 코드에 `common` import 0건).
- **스텁·TODO·skip 0건.** `TODO`/`FIXME`/`@Ignore`/`@Disabled`/`Ignore(` 검색 0건. 미구현 분기 없음.
- **테스트 양방향.** 각 계약마다 거부 케이스와 통과 케이스가 함께 있다(예: 역투영 거부 + 260년 활성화 허용,
  BALANCE_ONLY+근거 거부 + BALANCE_ONLY 무근거 통과, 등급 다른 정사·연의 claim 공존 통과).
  값 집합 고정 테스트(7/5/3)가 등급 신설을 막는다.
- **다른 레인 영역 미침범.** `app/**`, `infra/**`, `web/**`, `logic/**/v2/geo/**` 변경 0건.

Verdict: cleared
