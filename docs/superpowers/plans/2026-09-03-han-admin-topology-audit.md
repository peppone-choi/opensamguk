# Han 행정 topology 감사 계획

## 목표

커밋된 `han-tiles.json` 정본을 재생성하거나 원자 프로빈스를 합치지 않고, 현·군국의 단절·위요·완전 포함과 단일 현 군국을 결정적으로 추출한다. 사료 정본과 좌표 결합 상태를 함께 보여 주어 실제 오류와 정당한 예외를 작은 후속 수정 PR로 분리한다.

## 범위와 판정

1. RLE `owner` 격자를 복원하고 4방향 셀 인접으로 공간 프로빈스 → 현 → 군국 component를 계산한다.
2. 같은 행정 ID의 셀이 여러 component이면 `DISCONNECTED` 후보로 기록한다.
3. 바다·`OUT_OF_SCOPE`·지도 외곽에 닿지 않고 외부 4방향 이웃이 정확히 하나의 다른 행정 ID인 component를 `FULLY_ENCLOSED` 후보로 기록한다. 이는 곧 오류 판정이 아니라 검토 후보이다.
4. 군국의 `jurisdictionIds`가 하나면 `SINGLE_JURISDICTION`으로 기록한다. `後漢書 郡國志` 행정 단위 catalog와 exact group name이 결합되는 경우 원전 열거 수, 좌표 결합 상태, 현재 누락 후보를 함께 낸다.
5. `哀牢` 이민족 권역과 `哀牢县` 행정현은 별도 ID·별도 군국·별도 kind로 유지되는지 계약 검사한다. 이름 유사성만으로 대체하지 않는다.
6. 생성된 감사 snapshot은 입력 SHA-256, exact 후보 ID·component 크기·둘러싼 행정 ID를 고정한다. 입력이나 알고리즘이 바뀌면 `--check`가 실패한다.

## 구현 순서

1. 실패 테스트: synthetic grid에서 단절/위요/단현/애뢰 분리와 canonical snapshot 부재를 RED로 만든다.
2. `tools/map/audit_han_admin_topology.py`에 순수 분석 함수와 CLI를 구현한다.
3. `data/curated/han/administrative-topology-audit-v1.json`을 materialize하고 canonical exact inventory 테스트를 GREEN으로 만든다.
4. 후보를 다음 세 후속 묶음으로 분류한다.
   - geometry 오류: 원자 프로빈스 소유권만 최소 재배치
   - 역사적/지리적 정당 예외: 근거 quote·기간을 가진 adjudication allowlist
   - 좌표 근거 부족: 누락 현으로 확정하되 geometry 추가는 차단
5. 지도 전체 Python suite, 시나리오 suite, 정본 materializer `--check`, agent strict check를 실행한다.
6. 독립 adversarial review 뒤 PR을 병합·배포한다. 이 감사 PR은 런타임 geometry를 바꾸지 않으므로 PEP 재시드는 하지 않는다.

## 후속 수정 기준

- 단일 현 군국에 원전상 여러 현이 있어도 위치 근거가 없으면 임의 8셀 현을 만들지 않는다.
- 좌표와 parent 기간이 확인된 누락 현만 해당 군국 내부 원자 프로빈스를 분할해 추가한다.
- 이민족 권역은 행정현의 상위 군국으로 자동 승격하거나 행정현을 대체하지 않는다. 같은 공간의 공존이 필요하면 `ethnicRegionId` 같은 별도 축으로 모델링한다.
- 모든 geometry 수정은 최소 8셀, 연결성, 현치 containment, 15개 시나리오 소유권 투영을 다시 검증한다.
