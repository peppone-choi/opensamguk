# 후한 외부 세계 데이터 감사

- 날짜: 2026-08-22
- 상태: **MEASURED — redesign required**
- 범위: 중국 제국 밖 장소·세력권·육로·해로·원격 교역 관문

## 1. 현재 실측

- `data/map/external-places.json`: 65행, 정식 군 28 + 외부 장소 37, `IDENTIFIED` 55 +
  `DISPUTED` 10. 전 행의 유효기간이 `-9999..9999`라 시나리오 시간축을 표현하지 못한다.
- 현재 780성 중 동이 region 14는 22성이다. 외부 장소 중 `挹婁·對馬國·一大國·末盧國·伊都國·奴國`은
  플레이 node로 승격되지 않았다.
- 현재 연결은 1,783개 무방향 후보 edge이며 동이 내부 35개, 동이와 타 권역 사이 10개다.
- 현재 `狗邪國 ↔ 邪馬壹國` 직결은 《삼국지》의 `對馬 → 一大 → 末盧 → 伊都 → 奴 →
  不彌 → 投馬 → 邪馬壹` 여정을 내부 topology에서 지운다.

## 2. 수정 판정

1. `帶方郡`은 건안 연간 설치 전 시나리오에서 활성화하지 않는다. 근거는
   `data/corpus/sgz-30.txt`의 “建安中，公孫康…爲帶方郡”이다.
2. 수대 명칭인 `流求`는 181~225 세계의 활성 node에서 제거하고 후대 alias claim으로만 둔다.
   `夷洲`도 Tainan 확정점이 아니라 후보 해역 또는 원격 항로로 낮춘다.
3. `古寧伽耶·大伽耶·星山伽耶` 같은 후대 명칭은 3세기 canonical name이 아니라 별칭·후보
   claim으로 저장한다.
4. 현대 Wikidata 좌표 조회 성공은 고대 위치의 `IDENTIFIED` 판정이 아니다. 위치 미결은
   `candidateRegions`와 `uncertaintyRadius`로 보존한다.
5. 외부 세계를 고정 도시만으로 채우지 않는다. 선비·오환·강·애뢰처럼 이동·분산된 대상은
   영역과 계절 camp, corridor를 가진 `PolityPresence`로 표현한다.

## 3. 데이터 모델

```text
AdministrativePlace  한 제국의 군·현·치소, 시나리오 날짜별 활성
AnchoredPlace        유적·항구·관문처럼 점 비정 가능한 장소
PolityPresence       영역·계절 camp·이동 corridor를 가진 외부 세력
RemoteGate           화면 밖 교역·조공·외교 대상

SourceClaim
  sourceBook, volume, verbatim, sourceClass
  attestationDate, subjectPeriod, effectiveFrom, effectiveTo
  locationResolution, candidateRegions[], uncertaintyRadius
```

`IDENTIFIED | DISPUTED` 한 필드와 무기한 활성 기간은 새 정본에 허용하지 않는다.

## 4. 권역별 우선순위

1. 동해: 정사 여정의 중간 섬·항구를 보존한다. 확정 좌표가 없는 `不彌·投馬`는
   `RELATIVE_ITINERARY`로 두고 좌표를 만들지 않는다.
2. 동북: `挹婁`를 부여·고구려·옥저·예와 사료상 방위 관계로 연결한다.
3. 서역: `玉門·陽關`에서 鄯善·伊吾·車師로 이어지는 남북도와, 지도 밖 대상의 remote gate를 만든다.
4. 북방: 선비 3부·오환·남흉노를 시기별 영역과 초원 corridor로 표현한다.
5. 남방: 애뢰·야랑·교주 남단과 192년 이후 임읍을 시간축에 놓고, 부남 등은 해상 remote gate로 둔다.

## 5. 완료 조건

- 활성 외부 행 100%에 원문·권차·대상 시기·위치 판정이 있다.
- 시나리오 날짜 밖 행은 활성 0건이며 `-9999..9999` 기본값은 없다.
- 후대 명칭은 alias/후대 claim으로만 남는다.
- 정사 여정의 중간 node는 LOD 표시에서 생략할 수 있어도 내부 경로에서는 생략하지 않는다.
- 모든 활성 대상은 육로·초원로·수로·해로·remote gate 중 하나에 연결되고 연결에도 provenance가 있다.
- 완전성은 임의 개수 목표가 아니라 선택한 사료의 열거·거리·방위 문장 coverage ledger 100%로 판정한다.
- 중국 밖은 동일한 성 아이콘으로 채우지 않고 세력권·교역로·해로·후보 영역을 semantic zoom으로 구분한다.
