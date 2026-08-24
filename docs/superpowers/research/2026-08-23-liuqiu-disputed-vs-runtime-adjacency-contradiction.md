# 流求 — DISPUTED 사료 판정과 런타임 인접 간선의 모순

**배경.** PR #508(work/opensamguk/han-map-wave) 독립 리뷰
(`docs/superpowers/reviews/2026-08-23-han-city-const-gate-index-independent-critique.md`)
가 non-blocking 으로 지적한 항목. B1(commandery+tribe 게이트 AND 복원) 커밋
메시지에도 한 줄 남겼지만, 커밋 메시지는 나중에 검색·참조하기 어려우니 별도
문서로 남긴다.

## 모순

- `data/curated/han/external-world-candidates-v1.json` (W1-B 사료 계약 산출물,
  이 PR 에서 유지됨) 은 流求 의 역사적 위치 확정 claim 을 **`DISPUTED`** 로
  판정한다 — 즉 "이 시대에 流求 가 지금의 어느 섬을 가리키는지 사료가 하나로
  결정하지 못한다"는 것이 이 계약의 공식 입장이다.
- 그런데 같은 PR 의 `tools/scenario/build_han_world.py` `SEA_LINKS` 테이블
  (line 163-178)은 流求↔會稽郡 해상 인접 간선을 `han.json` 런타임 그래프에
  **이미 확정된 사실처럼** 심는다. 게임 엔진 입장에서 이 간선은 "流求 와
  會稽郡 사이를 배로 이동할 수 있다"는, 좌표 확정을 전제로 한 주장이다.

**즉 같은 PR 안에서 "위치 미확정"(사료 계약)과 "위치가 확정된 것처럼 행동하는
런타임 그래프"(han.json)가 공존한다.**

## 참고 — 인접이 "런타임 활성"이라는 판단 자체는 리뷰에서 확인됨

독립 리뷰는 "인접 간선 = 이동 가능성이며 시나리오 상태와 무관하게 항상 활성"
이라는 이 PR 의 설계 판단 자체는 맞다고 확인했다. 문제는 그 판단이 틀렸다는
게 아니라, **위치가 DISPUTED 인 대상에 대해서도 똑같이 확정 간선을 얹었다**
는 것이다. `SEA_LINKS` 의 다른 4개 항목(夷洲↔會稽郡, 州胡↔辟卑離國,
邪馬壹國↔狗邪國) 은 스크립트 자체에 출처 근거가 달려 있고, 于山國↔悉直國
은 스크립트가 스스로 `UNKNOWN` 이라 표기한다. 流求 는 다섯 항목 중 유일하게
"이 좌표 자체가 사료상 DISPUTED"라는 상위 문서와 충돌하는 케이스다.

## 조치

- **이 PR 에서 코드를 고치지 않는다.** F3 소관 파일(`HanCityConst.kt` /
  `HanGateIndex.kt` / `infra/src/main/resources/map/han.json`)은 이 작업
  범위 밖이고, `tools/scenario/build_han_world.py` 의 `SEA_LINKS` 조정은
  별도 판단(예: DISPUTED 대상은 간선을 빼거나 별도 신뢰도 라벨을 단다)이
  필요해 이 문서에서 결정하지 않는다.
- 이 문서는 그 모순이 존재한다는 사실과 근거만 고정한다. 후속 결정은 F3
  소관 트랙에서 진행한다.
