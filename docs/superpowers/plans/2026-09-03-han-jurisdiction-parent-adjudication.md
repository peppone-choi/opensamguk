# Han Jurisdiction Parent Adjudication (청주 4縣) Implementation Record

**Goal:** 220년 기준 청주(靑州) 4개 縣의 郡國 소속을 사료 근거로 바로잡되, 원자 프로빈스 기하·owner grid·면적·縣 인접성은 바꾸지 않는다. 재판정은 정본 재생성(protected build)에서도 살아남아야 한다.

**Architecture:** 검토 완료 ledger `data/curated/han/jurisdiction-commandery-adjudications-v1.json` 을 단일 진실로 두고, 공유 함수 `apply_jurisdiction_parent_adjudications`(`tools/map/world_province_geometry.py`)가 보호 생성기(`build_tile_grid.build`)와 materializer(`materialize_province_jurisdictions`) 양쪽에서 같은 순서로 적용한다. 적용 뒤 `parentOwner` 와 `adjacency.commandery` 를 owner grid 로부터 다시 파생한다. ledger 는 protected build 의 추적 입력 역할 `JURISDICTION_PARENT_ADJUDICATIONS` 로 등록된다.

**Tech Stack:** Python 3.11+, `unittest`, NumPy, `tools.map.build_terrain_grid` 인접성 파생.

## 재판정 4건 (referenceYear 220)

| jurisdictionId | 縣 | from | to | 핵심 근거 (shiliao 원문 인용) |
| --- | --- | --- | --- | --- |
| 45107 | 西平昌 | PARENT-0038 北海國 | PARENT-0036 平原郡 | 後漢書 卷042 光武十王列傳「但削祝阿、隰陰、東朝陽、安德、西平昌五縣」; 晉書 卷014 平原國「平原　高唐　茌平　博平　聊城　安德　西平昌　般　鬲」; 讀史方輿紀要 卷031「漢置平昌縣，屬平原郡。後漢曰西平昌縣，晉、宋因之」; TGAZ hvd_45107 part-of 206–221 |
| 85385 | 挺 | PARENT-0039 東萊郡 | PARENT-0038 北海國 | 後漢書 卷112 郡國志 北海國 縣 목록「挺，」; 讀史方輿紀要 卷036「漢置挺縣，屬膠東國。後漢屬北海國。晉屬長廣郡」 |
| 85505 | 不其 | PARENT-0038 北海國 | PARENT-0039 東萊郡 | 後漢書 卷112 郡國志 東萊郡「不其，侯國，故屬琅邪」; 讀史方輿紀要 卷002「長廣郡，建安五年魏武分東萊郡置，治不其縣」 |
| 85706 | 安德 | PARENT-0038 北海國 | PARENT-0036 平原郡 | 後漢書 卷112 郡國志 平原郡「安德，侯國」; 後漢書 卷042 (위와 같음); 晉書 卷014 平原國 목록 |

주의: 後漢書 郡國志에는 西平昌 항목이 없다(宋書 卷036 校勘記「續漢志無西平昌」). 郡國志 검색의「樂安國，高帝西平昌置」는 글자 일치 오탐이다.

## Global Constraints

- 1,524 공간 프로빈스 · 1,020 관할 · 172 군국 수는 그대로다.
- `owner`, `seatOwner`, `terrain`, `cities`, `juns`, `regions`, `adjacency.county`, `legacyGameplay` 는 바이트 단위로 동일해야 한다.
- 군국치(seat) 縣은 ledger 로 옮길 수 없다(적용 함수가 거부한다).
- ledger 는 idempotent 하다: 현재 부모가 `from` 이면 적용, 이미 `to` 면 통과, 둘 다 아니면 실패.

## Tasks

- [x] Task 1: 실패 테스트 — 생성기가 ledger 를 적용하지 않으면 parentOwner 172셀이 stale 로 남고 commandery 인접 그래프가 어긋난다 (`tools/map/tests/test_build_tile_grid_parent_adjudications.py`, `tools/map/tests/test_materialize_province_jurisdictions.py`).
- [x] Task 2: 공유 적용 함수 + parentOwner/commandery 인접 재파생 (`world_province_geometry.py`), 생성기·materializer 배선, protected build 입력 역할 등록.
- [x] Task 3: 정본 재물질화 — `data/map/han-tiles.json` sha256 `6e7507bc…`; 구조 diff 는 4縣 부모·군국 back-reference·parentOwner(4 프로빈스 내부 172셀)·commandery 인접(415→413, 전부 LAND) 만 바뀐다.
- [x] Task 4: 해시 고정 종속 산출물 재생성/재고정 — administrative-topology-audit-v1, han-scenario-province-ownership-v1(22 assignment, 4 프로빈스, 시나리오 1020/1021/1030/1031/1040/1041/1120), administrative-parent-reconciliation-v1(진단 전용 5행 185셀 multi→single), strategic-site-anchor-review-v1.
- [x] Task 5: 런타임 채색 부채 pin 갱신 — `tools/scenario/tests/test_runtime_province_fill_audit.py` 의 델타는 4 프로빈스로만 설명된다(HEAD 대비 전수 diff).

## Follow-ups (이 PR 범위 밖)

- 平原郡 jun 은 여전히 multi-group(승인 그룹 勃海郡+平原郡)이다.
- 1030/1031 소유권 투영에서 平原郡이 袁紹「領冀州牧」claim 으로 잡힌다(郡國志 기준 平原郡은 靑州). claim ledger 검토 필요.
- 런타임 도시 357 서평창 등은 infra han.json meta.jun 이 아직 北海國 이다. 정본 소유권 투영 PR 이 런타임 경로를 정본 소비로 바꿀 때 함께 해소한다.
- 삐죽 튀어나온·길쭉한 縣(예: 張掖屬國 侯官, 南郡 沙羨) 형태 감사·수정은 별도 PR.
