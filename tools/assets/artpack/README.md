# 전술 아트 팩 처리 도구 (A5)

사전에 작성한 RGBA PNG를 **픽셀 변경 없이** atlas에 넣고, A2 manifest v1과 같은 좌표·발 기준점·진영색 마스크를 기록합니다. 이미지 생성·편집·구매·유료 API·게임 실행·렌더러 활성화는 하지 않습니다. `shadow: "baked"`는 제작자가 그림자를 이미지에 넣었다는 선언이며, 도구가 그림자를 만들거나 그림 속 그림자 존재를 판별하지는 않습니다.

## A2 정본 상태와 재대조

- 기준: PR [#1637](https://github.com/peppone-choi/opensamguk/pull/1637), commit `7852d6bec0c17b929eccbb139e89527deb24be6c`.
- 경로: `web/shared/src/battle/artpack/manifest.ts`.
- Git blob: `b84694776495f5e6516b4dc206c2a50c6f9b2a94`.
- 이 작업에서 읽은 기준은 **draft·독립 리뷰 전**입니다. 최종 승인된 계약으로 취급하지 않습니다. A2 파일은 수정하지 않았습니다.
- `tests/fixtures/a2-manifest.ts`는 위 blob의 변경 없는 시험용 사본입니다. Node 시험은 이 원문의 `validateArtpackManifest`, `parseArtpackManifest`, `resolveUnitFrame`을 실행합니다. 유일한 의존성 `battleUnits`의 방향 상수 `['sw','nw','ne','se']`는 해당 commit 132행과 대조한 격리 의존성입니다. Canvas/브라우저를 구동하는 시험이 아닙니다.
- 실제 작업트리에 A2 manifest가 있으면 시험이 사본과 바이트 대조합니다. A2 변경 시 CI가 실패합니다. 새 commit/blob 원문을 읽고 Python 규칙·fixture·resolver 시험·문서를 **명시적으로 재대조한 뒤** 갱신해야 합니다. 해시만 조용히 바꾸거나 게이트를 약화하지 않습니다. A2가 아직 main에 없으면 사본 기반 호환성만 입증되며 통합 완료는 아닙니다.

## 실행

Python 3.12+, Node 22.15+와 Pillow 12.2.0을 사용합니다. 저장소 루트에서 실행합니다.

```sh
python3 -m pip install -r tools/assets/artpack/requirements.txt
python3 -m tools.assets.artpack build ./source/spec.json ./new-pack
python3 -m tools.assets.artpack check ./new-pack/manifest.json
python3 -m tools.assets.artpack check-chroma ./source/raw.png
python3 -m unittest tools.ci.test_artpack_assets -v
```

기존 contracts CI의 `tools/ci/test_*.py` discovery가 새 시험 entry를 실행합니다. contracts-execution은 map 선택 여부와 무관하게 Pillow 12.2.0을 먼저 설치합니다. 지도 의존성의 기존 map 조건과 시험 선택·게이트는 유지합니다. 무거운 빌드/전체 회귀는 GitHub-hosted Actions에서만 실행합니다.

`check-chroma`는 기존 `tools/assets/check_sprite_chroma.py`의 `bad()`를 직접 호출합니다(그 안에서 같은 `is_magenta()` 사용). 5×5 미만은 명확히 거부하며 파일은 바꾸지 않습니다. 마젠타를 투명색으로 바꾸는 기능은 없습니다. 이 검사는 raw 검사이고 atlas/투명 RGBA 입력 검사와 별개입니다.

## 입력

입력 spec은 `atlases`가 없는 manifest 템플릿입니다. 모든 frame 사각형 대신 `{ "file": "relative.png", "ax": 16, "ay": 8 }`를 넣습니다. `file`은 spec 디렉터리 내부 PNG이며 절대 경로·`..`·밖을 가리키는 symlink를 거부합니다. 모든 소스는 단일 프레임 RGBA PNG, 한 변 1–2048px이어야 합니다. 이미 작성한 alpha·그림자·발 기준점은 그대로 보존합니다. 투명화·crop·resize·flip·mask 추출은 수행하지 않습니다.

최소 예시(사전에 `ground.png` 32×16 RGBA, `spear.png` 7×11 RGBA, `mask.png` 7×11 회색 RGBA를 준비):

```json
{
  "schemaVersion": 1,
  "artifactId": "opensamguk-battle-artpack",
  "packId": "example-v1",
  "pixelsPerBoardPx": 1,
  "shadow": "baked",
  "terrain": {
    "P": {"ground": [{"file": "ground.png", "ax": 16, "ay": 8}]},
    "F": {"ground": [{"file": "ground.png", "ax": 16, "ay": 8}]},
    "M": {"ground": [{"file": "ground.png", "ax": 16, "ay": 8}]},
    "R": {"ground": [{"file": "ground.png", "ax": 16, "ay": 8}]},
    "W": {"ground": [{"file": "ground.png", "ax": 16, "ay": 8}]}
  },
  "units": {
    "spear": {"actions": {"idle": {"frames": 1, "directions": {
      "sw": {"frames": [{"file": "spear.png", "ax": 2.5, "ay": 9}], "masks": [{"file": "mask.png", "ax": 2.5, "ay": 9}]},
      "nw": {"frames": [{"file": "spear.png", "ax": 2.5, "ay": 9}], "masks": [{"file": "mask.png", "ax": 2.5, "ay": 9}]}
    }}}}
  },
  "provenance": [{"source": "hand", "note": "실제 제작 경위를 작성"}]
}
```

예시 그림은 제품 자산이 아닙니다. 시험은 자체 합성 RGBA만 만듭니다. `tests.test_artpack.fixture()`가 PFMRW·모든 동작 최대 장 수·RFM 16 edge의 합성 spec을 만듭니다.

## 계약과 검사

- `schemaVersion=1`, `artifactId=opensamguk-battle-artpack`, `pixelsPerBoardPx` 정수 1–4, `shadow=baked`.
- PNG atlas 한 변 ≤2048. frame의 atlas index·정수 rect·경계·유한한 발 기준점(ax/ay, 경계 포함)을 검사합니다. PPB로 좌표를 나눠 판에 놓으며 발을 옮기지 않습니다.
- PFMRW ground 각각 ≥1. 폭 `32*ppb`, 높이 ≥`16*ppb`. 이 도구의 제작 관례는 아래 정렬된 마름모이며 ground/전이 anchor는 `(16*ppb, h-8*ppb)`입니다. 일반 unit/tall anchor는 이미지 안에서 임의 유한 좌표를 보존합니다.
- `tallByEdges`는 16항목. `transitions`는 R/F/M만 16항목이고 index 0은 null. bit는 NE=1, SE=2, SW=4, NW=8. null은 그대로 유지하며 순서를 바꾸지 않습니다.
- unit별 idle 필수, 동작별 1–최대 장 수: idle 2 / move 4 / attack 4 / hit 2 / fall 4. sw·nw는 실제 그린 방향이며, 생략한 ne는 nw, se는 sw를 렌더러가 좌우 반전합니다(`ax → w-ax`). 명시적 mirror는 실제 그린 방향만 가리킬 수 있습니다. 도구가 반전 이미지를 만들지 않습니다.
- masks는 frame과 동수·동크기. 보이는 RGB 채널은 같은 회색이어야 하며 alpha·밝기를 그대로 보존합니다. mask 색상·발 배치 등 제품 미술 품질 판정은 별도입니다.
- provenance ≥1, source는 procedural/generated/hand, note는 문자열. generated는 model/prompt/createdAt의 비어 있지 않은 문자열이 필수입니다. 실제 값만 입력하며 현재 시각을 자동 삽입하지 않습니다.
- A2 구조 검사보다 엄격하게 잘못된 컨테이너·NaN/Infinity·중복 JSON key·중복 atlas 이름·소스와 완성 rect 혼용도 거부합니다. 성공한 출력은 고정 A2 validator 시험을 통과해야 합니다.
- `check`는 구조뿐 아니라 실제 PNG RGBA·크기·선언된 SHA256·mask 픽셀도 검사합니다. sha256이 생략된 외부 manifest는 A2와 같이 허용하지만 생성기는 항상 해시를 넣습니다.

## 결정성과 출력 보존

소스 파일명을 사전순으로 정렬해 고정 shelf 순서로 패킹합니다. 최대 side는 기본 2048, `--atlas-size 1..2048`로 제한할 수 있습니다. 넘치는 frame은 거부하고, 꽉 차면 다음 atlas를 만듭니다. 같은 파일의 여러 anchor는 한 사각형을 공유하되 각 발 기준점은 보존합니다. 겹치거나 alpha 합성하지 않습니다.

사용 영역만 RGBA PNG로 저장하며 압축 옵션을 고정하고 메타데이터를 넣지 않습니다. manifest는 key 정렬·고정 들여쓰기·개행으로 직렬화합니다. 입력 경로/mtime/출력 위치/현재 시각을 기록하지 않으므로 **동일 spec·동일 소스 바이트·동일 도구/Pillow/zlib 환경 → 동일 출력 바이트/SHA256**입니다. 다른 압축 라이브러리 버전 사이의 바이트 일치까지 보장하지는 않습니다.

기존 출력 디렉터리는 거부하며 staging에서 전체 PNG·manifest 검사를 마친 후 게시합니다. 입력 파일과 기존 팩은 덮어쓰지 않습니다. 검증 실패 시 staging을 정리합니다. 도구는 팩을 배포하지 않습니다.

## 입증 범위

합성 fixture의 해시 결정성·원본 RGBA/anchor 보존·multi-atlas/2048 경계·잘못된 manifest/파일 거부·공유 chroma 재사용·A2 validator/resolver 호환성을 자동 검사합니다. 독립 리뷰·실게임·브라우저 Canvas 시각 QA·제품 이미지 품질·서버 반영은 이 시험의 PASS에 포함되지 않습니다. 독립 리뷰 전 병합하지 않습니다.
