# 전략 지도 베이크 번들

전략 지도용 키트 번호와 표시 구역 번호를 서버가 불변 파일로 제공하는 선택 기능이다. 실시간 전투의 등각 지도·판 번호·전투 지형 입력과 별도다. 기본 설정에서는 preview에 `topdownBakeId`가 없으며, 이 문서는 운영 전환 완료를 의미하지 않는다.

## 생성과 검증

필요한 Python 패키지는 numpy와 Pillow다. 이미지 정본 저장소에서 승인된 키트 export를 `data/map/waryong/<kitId>`에 먼저 반입한다. 기존 export가 그림 파일을 `web/game/public/map/waryong/<kitId>`에 둔 경우 그 경로도 지원한다. catalog, kit-index, synth-stats의 실제 SHA를 확인하며 `export.json`의 원본 merge commit과 kitId를 보존한다.

```sh
python3 tools/map/build_map_design.py --export build/map-design-export
python3 tools/map/bake_topdown_map.py \
  --export-dir build/map-design-export --kit-dir data/map/waryong/273d596 \
  --out build/topdown-map --workers 1
python3 tools/map/bake_topdown_map.py \
  --export-dir build/map-design-export --kit-dir data/map/waryong/273d596 \
  --out build/topdown-map --check
```

기본 worker는 1이다. 자원 슬롯과 메모리를 확인한 뒤에만 늘린다. `--region R0 R1 C0 C1`은 부분 검증용이며 공개 번들로 패키징하거나 활성 세계에 연결할 수 없다. 전지도 생성은 지형 구조와 전체 격자를 메모리에 올린다.

생성 명령에 `--bundle-root data/map/topdown`을 추가하면 검사 후 `<bundle-root>/<bakeId>/`로 필요한 파일만 패키징한다. 같은 ID가 이미 있으면 바이트가 같은 경우만 수용하고 다른 파일을 덮어쓰지 않는다. 검사는 모든 전송/raw 해시·파일 크기·격자 길이·범위·입력 지문을 검사하고 정해진 표본 조각을 다시 굽는다. 전 조각의 의미를 재합성하는 전수 검사는 아니다.

결합 검사 `check_map_inputs.py --check`에도 등록되어 있다. 공개 번들이 없으면 `SKIPPED`이며 검증 통과로 세지 않는다. 임시 staging 디렉터리만 남은 경우도 같다. 결합 목록의 `data/map/topdown`은 현재 입력으로 검증하는 생성 루트이며, 운영에서 보존하는 과거 불변 번들은 각 버전의 export·키트·생성기 검증 근거와 함께 별도 보관한다. 번들이 있으면 입력 export/키트가 없거나 낡은 경우에도 적색이다. 입력을 바꾸면 export와 번들을 새 ID로 다시 생성한다.

## 파일 계약

설계 export는 schemaVersion 2다. 기존 `[rows,cols]` 입력 shape와 8개 PNG를 유지하고 원본 tiles/world/roads/DEM/경제/설계 JSON의 실제 바이트 지문을 추가한다. PNG의 `sha256`/`bytes`는 실제 PNG 전송 파일, `rawSha256`은 디코딩한 u8 또는 u16LE 격자다. 길은 edgeId·원래 방향의 fromTrail/toTrail과 연결된 `[col,row]` cells를 보존한다. 대각 모서리는 원래 두 구간 방향에서 선택하며 역순으로 연결한 toTrail 때문에 모서리를 뒤집지 않는다.

공개 manifest는 schemaVersion 1, `shape={cols,rows}`, `chunkSize=256`이다.

| 파일 | 내용 |
| --- | --- |
| `grid/L0/<cx>_<cy>.bin.gz` | 행 우선 256² u16LE 키트 평면 뒤에 같은 크기의 구역 평면 |
| `grid/L2.bin.gz` | 4×4 최빈값 개관의 두 평면; 동률은 작은 번호 |
| `places.json.gz` | 기존 이름·좌표·행정 연결·표시 발자국·관·이름표 |
| `defects.json` | 표시 발자국과 관의 접속/길 우회 후보 |

키트 0은 실제 타일, 65535는 미표시·패딩이다. 구역 0은 없음, n은 `provinceRecords[n-1]`이다. 한 값의 두 평면은 uniform metadata만 제공한다. L2는 미표시값을 제외하되 모두 미표시면 65535를 유지한다. gzip mtime은 0이며 실제 gzip의 SHA/bytes와 풀린 raw SHA를 별도로 저장한다.

장소 표의 `roadEdges`는 `{edgeId:{status,cells:[[col,row]…]}}`다. 검증한 설계 export의 edgeId·status·연결된 칸 순서를 그대로 전달하며 빈 원천은 `{}`다. 건설되지 않은 길도 원천 상태로 포함한다. `status`는 설계 메타데이터이며 실행 중 통과·보급 가능 여부는 `strategicTopology.roadOpenEdgeIds`와 해당 게임 규칙을 따로 대조한다. 공개 감사는 필드·`BUILT`/`UNBUILT` enum·비어 있지 않은 정수 좌표 쌍을 검사한다. 두 상태 모두 지도 범위와 연속 칸의 8방향 인접성을 검증하고, 굽기 검사는 도로 전체를 입력 export와 대조한다. 기존 필드가 없는 immutable 번들도 감사할 수 있다. 새 생성기 지문으로 새 bakeId를 만들며 기존 번들의 바이트를 바꾸지 않는다. 새 places의 압축·해제 크기는 기존 16MiB 상한을 유지한다. 이 필드를 싣는 것만으로 화면의 길·보급선 소비가 완료되지는 않는다.

bakeId는 `{inputFingerprint,mapRelease,kitVersion,formatVersion}`의 SHA256 전체 64자리다. ASCII 키를 재귀 정렬하고 UTF-8로 공백·개행 없이 직렬화한다. 입력 지문에는 원본·export·키트 파일·생성기 SHA, 범위, 압축 runtime 버전을 포함한다. manifest 자기 SHA는 ID 입력에서 제외한다.

행정 치소는 `seatJurisdictionId → seatPlaceId → physicalPlaceRef`의 명시적 연결로 찾는다. 게임 `meta.isSeat`는 별도다. 애매한 연결은 null과 후보 목록을 제공한다. 미상 戶數는 null, 縣 없는 郡의 앵커가 없으면 결손으로 기록한다. 기존 `meta.displayName`을 우선하며 sourceName을 보존한다. 관 끝은 산 또는 큰물 접속을 구분하되 길/좁은 강은 차단 근거로 인정하지 않는다. 이 후보는 게임 통과·보급 차단 검증이 아니다. 실제 통제 edge/게임 점유 계약은 별도 확인이 필요하다.

## 서버 연결

승인된 완전 번들을 game-api의 `/app/data/map/topdown`에 읽기 전용으로 마운트한다. Docker 이미지는 빈 디렉터리만 준비하며 빌드 중 전지도 베이크를 실행하지 않는다. `TOPDOWN_MAP_ROOT`로 다른 루트를 지정할 수 있다. `TOPDOWN_BAKE_ID`는 기본 빈 값이며 활성화 시 완전한 64자리 ID를 지정한다. 환경값 변경·운영 마운트·승격은 해당 대상 승인 후 수행한다.

`GET /api/map/topdown/<bakeId>/<path>`는 manifest에 등록된 경로만 제공한다. 번들 밖 symlink/상대 탈출, 잘못된 ID, 크기/실제 파일 SHA/raw SHA 불일치는 404다. ETag는 실제 응답 바이트의 `"sha256-<SHA>"`, 캐시는 `public, max-age=31536000, immutable`, 같은 ETag는 304다. `.gz`는 `application/octet-stream`이며 HTTP `Content-Encoding`은 붙이지 않는다.

preview는 mapRelease 및 활성 세계의 tiles/world/roads 지문이 모두 일치하고 부분 번들이 아닐 때만 `topdownBakeId`를 제공한다. reset 후 다른 세계 핀에는 연결하지 않는다. 프론트는 preview의 선택과 manifest 입력 핀을 확인한 뒤 격자를 사용한다. 생성/검사 성공만으로 프론트 첫 그림·운영 성능·실제 게임 점유가 검증됐다고 판단하지 않는다.
