# OPENSAM-183 [H7] `BattleTopology` 소유자 공백 — 결정 자체 리뷰

Scope: docs .ai/decisions.md ADR-LITE-033 신규 항목과 Jira OPENSAM-158(BATTLE-F2) 범위·수용 기준 편집. 코드 변경 없음.
Verdict: cleared

## 1. 무엇을 결정했나

H7의 세 선택지 중 **(1) BATTLE-F2(OPENSAM-158)의 `BattleRulesAdapter` SPI에 선치**를 골랐다.
`BattleTopology`(위치·이동·충돌)와 SPI가 다루는 부대 핸들 인터페이스를 F2 범위·수용 기준에 이름으로
넣었고, 어댑터 3종(OPENSAM-170/171/172)과 OPENSAM-21은 소비만 한다.

산출물 2개:
- `.ai/decisions.md` — ADR-LITE-033 (Status: proposed, Approved by: NONE).
- Jira OPENSAM-158 — 범위 1항목 + 수용 기준 3번 + 비범위 1문장 + "소유 근거" 절.

## 2. 티켓 수용 기준 대조

- AC1 "`BattleTopology`를 이름으로 명시한 범위를 가진 티켓이 정확히 1개" — 편집 후 OPENSAM-158 하나뿐이다.
  다른 후보였던 OPENSAM-21(Spike B0)·170·171·172의 범위에는 이 이름이 없고 이번에 넣지도 않았다.
  ADR과 158 본문 모두 "유일한 티켓"을 명문화해 두 번째 티켓이 조용히 생기는 것을 막는다.
- AC2 "(1)안 채택 시 grid 지형과 연속 좌표 지형이 동일 인터페이스를 통과하고 부곡 부대가 동일 부대
  인터페이스를 쓴다는 것이 테스트로 증명" — 158 수용 기준 3번으로 옮겨 적었다. **이 리뷰가 그 테스트를
  통과시켰다고 주장하지 않는다.** 증명은 F2 구현 시점의 산출물이며 지금은 요구사항 등록까지다.

## 3. 왜 (2)·(3)이 아닌가 (요약)

- **(2) 어댑터 자율 + 사후 게이트** — 세 어댑터가 각자 좌표·부대 모델을 굳힌 뒤 맞추는 순서다.
  P-13b/P-13e 불변식이 "깨진 다음 발견"되고 되돌리는 비용이 SPI 선치보다 크다.
- **(3) OPENSAM-21 흡수** — ADR-LITE-032가 7종 중 4종을 F2/F3 구현으로 판정해 B0 계약 범위는 이미
  축소됐다(연구문서 R5). 소비처(F2 SPI)와 다른 티켓에 계약을 두면 F2가 먼저 동결해 B0가 사후 추인이 된다.

## 4. 스스로 공격해 본 것

- **F2 범위를 부풀린 것 아닌가?** 늘어난다. 다만 추가되는 테스트는 전투 규칙이 아니라 최소 지형 구현
  2종(grid 1 + 연속 1)이 같은 SPI를 통과함만 고정한다 — 158 비범위(어댑터 실제 규칙)는 그대로 유지했다.
- **`FormationModel` 전체를 F2로 끌어온 것 아닌가?** 아니다. 끌어온 것은 부대 핸들 인터페이스뿐이고,
  stack·선형·종대·방진·포병 배치는 158 비범위에 명시적으로 남겼다.
- **선치 창이 아직 열려 있나?** OPENSAM-158은 여전히 `할 일`이고 편집 전 범위에 `BattleTopology`가
  없었다(편집 전 본문 확인). F2가 착수되면 SPI 시그니처가 굳어 창이 닫힌다 — 지금이 마감선 안이다.
- **ADR을 승인으로 오용할 수 있나?** Status `proposed` + `Approved by: NONE`이며 ADR 본문이
  product-spec 개정·구현 착수·merge·배포를 승인하지 않음을 명시한다. Jira 범위 편집만 선반영했고
  이는 되돌릴 수 있는 기록 행위다.

## 5. 증거

- 이름 전수 확인: `grep -rn "BattleTopology" docs` → 정본 4곳(product-spec:423, 계약동결:341/488,
  백로그 2곳)과 연구문서(:105/:211/:238/:240/:280). 편집 전 Jira 158 본문에는 없었다.
- 근거 문서: `docs/superpowers/research/2026-08-16-v2-battle-canon-reconcile-p4-p13.md` R7(:240)·H7(:280),
  `.ai/decisions.md` ADR-LITE-032 Consequences 잔여 리스크 문단.
- 코드·테스트 무변경이므로 Gradle/vitest 실행 없음. v1 패러티(logic/war·PHP golden·RNG·로그) 무관.

## 6. 남긴 것

- H2(`phases[]` 축 대 어댑터 축, 수전 자리 없음)와 P-15d 미측정은 이 결정이 닫지 않는다 — 별도 티켓.
- ADR-LITE-033의 사람 비준. 비준 전에는 F2 착수 승인으로 쓰지 않는다.
