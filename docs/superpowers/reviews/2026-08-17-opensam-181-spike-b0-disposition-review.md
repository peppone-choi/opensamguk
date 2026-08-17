# OPENSAM-181 [H5] Spike B0 처분 확정 — 자체 리뷰

Scope: docs .ai/decisions.md ADR-LITE-034 신규 항목과 Jira OPENSAM-21(Spike B0+C0) 본문 편집. 코드 변경 없음.
Verdict: cleared

## 1. 무엇을 결정했나

H5 세 선택지 중 **(1) 잔여 범위 축소**. OPENSAM-21의 B0 절에서

- 동명 생존 4종(`BattleState`·`BattleClock`·`BattleEvent`·`BattleReplay`) → BATTLE-F2/F3/F5 위임 명시,
- `BattleTopology` → ADR-LITE-033대로 OPENSAM-158 단독 소유이므로 B0 범위에서 제외,
- 남는 것 = **개명 2종 이름 대응 기록**(대응표는 ADR-LITE-034 본문에 실었다),
- C0(콘텐츠 lifecycle)는 손대지 않음.

## 2. 티켓 수용 기준 대조

- AC1 "4종이 BATTLE-F2/F3/F5에 위임됐음이 본문에 명시되어 이중 착수가 방지된다" — **충족**. 위임 표 +
  잔여 수용 기준 2번("이 티켓이 동명 4종·`BattleTopology`에 어떤 계약도 새로 정의하지 않는다") +
  B0 비범위 문단, 세 곳에서 막았다.
- AC2 "`BattleTopology` 소유가 H7 티켓과 중복되지 않게 한쪽으로 귀속" — **충족**. OPENSAM-183(H7)이
  OPENSAM-158로 귀속시켰고(ADR-LITE-033), OPENSAM-21 본문은 "이 티켓 범위 아님"으로 명시적으로 비운다.
  `BattleTopology`를 범위로 갖는 티켓은 여전히 158 하나다.

## 3. 왜 (2) 종료·(3) 현행 유지가 아닌가

- **(2) 종료** — OPENSAM-21은 B0+C0 합본 에픽이다. C0(FormationTemplate/Facility/InfrastructureNetwork/
  ResourceSite/HistoricalContentPack + CatalogBudget lifecycle + dangling fixture 4종)는 ADR-LITE-032/033
  어느 쪽도 건드리지 않았다. B0 사유로 에픽을 닫으면 C0가 소유자를 잃는다.
- **(3) 현행 유지** — 편집 전 본문이 "grid/연속좌표 두 topology 공통 BattleState·OrderIntent·
  BattleEvent/Replay 직렬화 계약"을 자기 범위로 선언하고 있었다. 그대로 두면 BATTLE-F2/F3/F5와
  같은 계약을 이중 착수한다. 마감선(F2 착수 전)이 임박했다.

## 4. 스스로 공격해 본 것

- **개명 대응 기록을 "나중에"로 미루지 않았나?** 미루지 않았다 — 대응표를 ADR-LITE-034 본문에 실어
  잔여 산출물 자체를 이 커밋에서 만들었다. 구 이름(`OrderIntent`, `BattleServerAuthority`)으로
  검색하면 이 표에 도달한다.
- **C0를 훼손했나?** C0 문단은 문구 그대로 보존하고 "변경 없음"을 붙였다.
- **상태 전이를 몰래 했나?** 하지 않았다. OPENSAM-181 비범위가 "상태 전이·라벨·담당자 변경은 사람
  승인 사항"이라고 못박아 OPENSAM-21은 `할 일` 그대로다.
- **남는 불일치는?** 마이크로 티켓 B0-a~g와 백로그 문서(`01-backbone-micro.md:61`)는 아직 7종 전부를
  B0 범위로 적고 있다. ADR Consequences에 미반영으로 명시해 남겼다 — 조용히 넘기지 않았다.

## 5. 증거

- 편집 전 OPENSAM-21 본문(7종 전부 B0 범위 선언)과 편집 후 본문을 Jira API 응답으로 확인.
- 근거 문서: 연구문서 §2.4·§4·R5·H5, `.ai/decisions.md` ADR-LITE-032·033,
  `docs/superpowers/plans/2026-07-30-v2-realtime-battle-foundation-implementation-plan.md:80,82,92,143`.
- 코드·테스트 무변경이므로 Gradle/vitest 실행 없음. v1 패러티(logic/war·PHP golden·RNG·로그) 무관.

## 6. 남긴 것

- B0-a~g 마이크로 티켓과 백로그 문서의 축소 반영.
- ADR-LITE-034의 사람 비준. 비준 전에는 OPENSAM-21 종료·착수 승인으로 쓰지 않는다.
