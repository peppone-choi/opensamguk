# 후한 현급 행정단위 검출 감사

- 날짜: 2026-08-22
- 상태: **MEASURED — source catalog complete, coordinate join incomplete**
- 범위: 사료 총계, 현재 군국지 parser 산출물, CHGIS 220년 레이어, 780성 제품 맵의 개수 분리

## 1. 사료 기준선

로컬 정사 corpus의 《후한서》 권113 「군국지」에서 다음 문장을 확인했다.

> 至于孝順，凡郡、國百五，縣、邑、道、侯國千一百八十。

따라서 순제기 기준 총계는 군·국 105, 현·읍·도·후국 1,180이다. 여기서 1,180을 “현만
1,180개”라고 쓰지 않는다. 서로 다른 현급 행정 유형을 합친 수다.

검출 명령:

```bash
python3 tools/corpus/index_sources.py '縣邑道侯國千一百八十' --book 後漢書 --limit 5
```

## 2. 원문 구조 검출 결과

`tools/map/audit_junguozhi_source.py`가 로컬 공개 corpus 권109~113의 군국 header와 열거 구조를
따라 검출한 결과는 다음과 같다. CHGIS 이름 사전은 항목 경계 판정에 사용하지 않는다.

```text
PASS groups=105/105 units=1180/1180
types={'COUNTY': 1043, 'DAO': 19, 'MARQUISATE': 108, 'TOWN': 10}
SOURCE_MISMATCH volume=110 group=安平國 declared=13 enumerated=12
```

원문 `城數` 선언 합은 1,181이지만 안평국은 13성 선언 뒤 12개만 열거한다. 전역 요약과 실제
열거는 1,180으로 일치하므로 항목을 하나 만들어 선언 수에 강제로 맞추지 않는다.

## 3. 현재 저장 데이터 실측

| 계층 | 파일·기준 시점 | 현재 수 | 판정 |
| --- | --- | ---: | --- |
| 원문 구조 extractor | `tools/map/audit_junguozhi_source.py` | 1,180 | 사료 항목 전수 검출 |
| 기존 군국지+CHGIS 결합 | `data/map/junguozhi.json`, 순제기 baseline | 1,076 | 104개가 결합 산출물에서 누락 |
| 원문 `城數`가 있는 96군국의 기대 합 | 같은 파일 | 1,128 | parser가 1,048 검출, 80 미달 |
| `城數` 없는 10군국 | 같은 파일 | 28 검출 | 사료 총계와 대조하면 52가 기대되어 24 미달 |
| CHGIS 행정 치소 | `data/map/han-places.json`, 220년 | 현급 958 | `县` 941 + `侯国` 16 + `道` 1; 시점·커버리지가 달라 1,180 대체 불가 |
| CHGIS 전체 place | 같은 파일 | 1,144 | 현급 외 郡·國·尹·州·외부 거점을 포함 |
| 제품 플레이 성 | `infra/src/main/resources/map/han.json` | 780 | stable id 780, 군 치소 175 + 비치소 605 |

`junguozhi.json`의 세부 검출은 `RESOLVED_POINT` 788, `CANDIDATE_REGION` 288이다. 원문
`城數` 체크섬은 PASS 68, FAIL 28, NO_COUNT 10이며 FAIL 28건은 모두 미달이다.

현재 제품 맵은 780개 id가 모두 유일하고 degree 0 node는 없다. 다만 현재 1,783개 무방향
연결은 생성 topology이며 승인된 역사 도로를 뜻하지 않는다.

현재 `junguozhi.json`의 106번째 군국 `龜茲屬國`은 독립 군국이 아니라 上郡의 열거 항목이다.
또 현재 780은 결손 parser의 `zhi` 선택 산술에 의존하므로, 총 플레이 노드 780을 유지하더라도
개별 identity는 reviewed selection manifest를 통과해야 한다.

## 4. 데이터 판정

1. 제품 문서에는 `1,180 source-detected`, `1,076 coordinate-joined`, `104 join gaps`,
   `780 reviewed playable manifest target` 네 수를 구분해 기록한다.
2. CHGIS 220년 958개를 순제기 1,180의 완성본으로 간주하지 않는다. 시점과 공간 커버리지가 다르다.
3. 780성을 1,180개 행정행 중 임의 앞 780개로 선택하지 않는다. 시나리오 연도, 치소, 위치 확실성,
   전략적 포함 근거를 가진 mapping artifact가 필요하다.
4. 104개는 이름·유형·상위 군국이 미검출된 것이 아니다. 원문 identity를 좌표·후보 영역에 결합하지
   못한 항목이며, CHGIS 최근접 점으로 조용히 채우거나 가명·좌표를 만들지 않는다.
5. 도로망 완료와 행정 카탈로그 완료는 별도 gate다. 780성 route graph를 만들 수 있어도 1,180행
   역사 카탈로그의 104개 누락을 완료로 숨기지 않는다.

## 5. 다음 데이터 작업

- 원문 구조 extractor의 `(sourceVolume, canonicalGroup, ordinal)`을 identity oracle로 삼고 ctext
  번체 본문과 ordinal 정렬한다. `城數` 강제 분할은 진단 전용으로만 둔다.
- generic header 대신 정규화한 105군국 allowlist를 사용하고 `龜茲屬國`을 上郡 항목으로 교정한다.
- `unitType = COUNTY | TOWN | DAO | MARQUISATE`를 원문 표기에서 보존한다. 현으로 일괄 정규화하지 않는다.
- `AdministrativeUnitId ↔ PhysicalPlaceId ↔ RouteNodeId?` mapping을 별도 artifact로 만들고,
  1,180행 전수·reviewed 780 node manifest·dangling 양방향 참조를 validator로 검사한다.
