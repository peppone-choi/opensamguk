# OPENSAM-235 / GitHub #536 — Han 타일 재생성 정본 복구 계획

## 목표

ADR-LITE-039/040의 CHGIS 격리를 유지하면서 `data/map/han-tiles.json`을 768×669 정본으로 결정적으로 재생성하고, 잘못된 256 기본 실행·누락 입력·수동 후처리 드리프트가 다시 커밋되지 않게 한다. OPENSAM-234C1에서 검토된 물리 ID `85168`, `42901`을 최종 타일과 모든 하류 provenance에 안전하게 물질화한다.

## 확정 원인

- 역사 커밋 `b87e663a`의 `build_han_places.py` 문서와 기본값은 grid 256이지만, 정상 커밋 타일은 숨은 `--grid 768` 실행 결과다.
- 같은 역사 코드와 같은 보존 원본에서 `--grid 768`은 1,145 cities, 768×669, county adjacency 2,662, commandery adjacency 425를 만들며 역사 타일 blob을 바이트 단위로 재현한다.
- 인자를 생략한 grid 256은 256×223, collision nudge 165, county adjacency 1,230, commandery adjacency 366을 만들어 #536의 실패 수치를 정확히 재현한다.
- 따라서 파편화 원인은 비결정성이나 `kind` 분리가 아니라 CLI 기본값/문서와 실제 정본 빌드 인자의 불일치다.

## 제약

- `han-tiles.json`만 커밋 가능한 CHGIS 파생 지리 정본이다. CHGIS 원본, `han-places.json`, `terrain-grid.json`은 계속 gitignored·미커밋이다.
- 공용 CI에는 CHGIS 원본·중간 산출물·좌표 로그를 업로드하지 않는다.
- 원본 기반 전체 재생성은 승인된 로컬 입력에서 수행하고, 공용 CI는 추적된 계약·최종 산출물·synthetic fixture를 검증한다.
- 수동 JSON 치환이나 배열 인덱스 기반 후처리를 금지한다. 변환은 stable physical/admin ID와 검토 원장을 사용한다.

## 실행 순서

### Task 1 — canonical grid 계약

1. `build_han_places.py`의 canonical 기본 grid를 768로 고정하고 help/docstring을 정본 명령과 일치시킨다.
2. `build_terrain_grid.py`의 무효한 `--grid`를 실제 projection 검증으로 바꿔, 요청 grid와 `han-places` projection이 다르면 실패시킨다.
3. `build_tile_grid.py`가 768×669/year 220 이외 canonical 입력과 `readings.json` 부재를 실패시키게 한다. 비정본 synthetic 실행은 명시적 입력/모드로만 허용한다.
4. 256 기본 실행이 RED, 768 명시/기본 실행이 동일 GREEN인 단위·CLI 테스트를 추가한다.

### Task 2 — 닫힌 build contract와 protected orchestrator

1. 좌표를 포함하지 않는 closed build-contract에 year/grid/bbox, 단계 순서, 필수 입력·도구·dependency SHA, 중간 hash/bytes/count, 최종 hash/count를 고정한다.
2. source root와 임시 output root를 받는 orchestrator를 추가해 입력 해시 선검증 → 두 번의 clean build → byte identity → semantic gate 순으로 실행한다.
3. 누락 입력, unknown role/key, hash drift, optional fallback, 서로 다른 두 실행 결과를 모두 실패시킨다.

### Task 3 — 과거 수동 병합의 stable-ID upstream화

1. `f070cf36`, `cfcf94a0`, `80896505`, `f109f860`의 phantom/duplicate 郡 병합·owner 보정을 검토된 stable-ID transform으로 전환한다.
2. 현재 타일의 stale `_meta.counts`, juns seat 좌표, seatOwner-at-seat, zero-area jun을 재생성 단계에서 해소한다.
3. county/commandery adjacency를 각각 owner/seatOwner에서 재유도하고 exact equality를 검증한다.

### Task 4 — C1 물질화와 전 산출물 동기화

1. duplicate adjudication ledger를 사용해 selected `85168`, `42901`을 정확히 1회 포함하고 rejected `87267`, `85581`을 0회로 만든다.
2. 768 기준 projection, collision, owner, seatOwner, county/commandery adjacency를 전체 재계산한다.
3. route-node candidates/policy/selection/migration, parent reconciliation, Han world, Kotlin constants와 gate index를 동일 provenance에서 재생성한다.

### Task 5 — CI·문서·종료

1. 추적 final artifact의 schema/meta/RLE/ID/seat/owner/adjacency/connectivity/C1 semantic gate와 mutation tests를 CI에 연결한다.
2. 공개 synthetic fixture로 pipeline과 `--check`를 검증한다.
3. ADR-LITE-039/040의 격리를 유지하며 protected local attestation 절차와 재생성 명령을 문서화한다.
4. 전체 map/scenario/backend 검증, byte-identical 이중 재생성, 독립 리뷰 후 GitHub #536과 OPENSAM-235를 완료한다.

## 완료 기준

- 기본 canonical 명령은 768×669만 생성하며 256 accidental build는 커밋 경로에서 실패한다.
- 승인된 동일 입력으로 두 번 실행한 모든 중간 hash와 최종 `han-tiles.json`이 byte-identical이다.
- `_meta.counts`가 실제 데이터와 일치하고 owner/seatOwner/adjacency/seat invariants가 모두 통과한다.
- county 연결성은 main ≥98%, isolated <15, components <20을 유지한다.
- C1 selected/rejected ID 집합과 모든 downstream provenance가 일치한다.
- CHGIS 원본과 금지된 중간 산출물은 git에 추가되지 않는다.
