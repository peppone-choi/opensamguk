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
