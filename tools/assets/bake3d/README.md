# 3D 굽기 도구 (BAKE-0)

단순 도형으로 조립한 3D 모델을 고정 정사영 카메라·고정 광원으로 방향마다 렌더해 투명 PNG와 manifest를 만든다. 새 설치 없이 `web/game`의 기존 의존성 `three` 0.171.0과 `@playwright/test` Chromium(SwiftShader WebGL)만 쓴다.

**상태: PROVISIONAL.** 카메라 각도·광원 방향·타일 배율·칸 크기는 동작 확인용 임시값이다. 아트 일관성 가이드(`opensamguk-images` `originals/aoe2-20261016/docs/art-consistency-guide.md`)의 기준 화면 승인 전에는 정본이 아니다. 시험 모델은 `kind: "test-fixture"`이며 제품 그림이 아니다.

## 원본·도구·배포 경계

- 이 폴더는 굽기 도구와 시험용 원시 도형만 둔다. 실제 건물·유닛 3D 원본과 미리보기의 정본은 `opensamguk-images`에 둔다(`docs/development/agent-reference.md`). 도구는 모델 파일 경로를 인자로 받으므로 원본 저장소의 파일을 그대로 읽을 수 있다.
- 출력은 검수용이다. 앱으로 복사하는 배포 export는 승인 뒤 별도 작업에서 정한다. 이 도구는 아무것도 배포하지 않는다.

## 실행

저장소 루트에서, `web`에서 `corepack pnpm install --frozen-lockfile --filter "@opensamguk/web-game..."`과 `web/game`에서 `corepack pnpm exec playwright install chromium`을 마친 뒤:

```sh
node tools/assets/bake3d/bake.mjs \
  --config tools/assets/bake3d/config.provisional.json \
  --model tools/assets/bake3d/fixtures/primitive-building.json \
  --out ./bake-out
node --test tools/assets/bake3d/test/*.test.mjs
```

`--chromium <실행파일>` 또는 `BAKE3D_CHROMIUM_PATH`로 다른 Chromium을 지정할 수 있다. 렌더 시험은 three·Chromium을 찾지 못하면 이유를 적고 건너뛴다. `BAKE3D_REQUIRE_RENDER=1`이면 실패한다(CI `.github/workflows/bake3d.yml`). `BAKE3D_EVIDENCE_DIR`를 주면 출력·대조 시트·결정성 통계(`render-evidence.json`)를 남긴다.

## 입력

- 설정(`opensamguk-bake3d-config/0`): `status`(`PROVISIONAL` 또는 `approvalRef`가 있는 `APPROVED`), `cell`, `anchor`(세계 원점 = 발 기준점이 놓일 픽셀), `camera`(`orthographic`, `elevationDeg`, `yawDeg`, `tileDiamondWidthPx`), `light`(`directionCamera` = 화면 기준 광원 쪽 벡터 x 오른쪽·y 위·z 보는 쪽, 세기 2개), `directions`, `ramp`. 숨은 기본값은 없다. 고도 30°에서 바닥 타일은 2:1 마름모가 된다.
- 모델(`opensamguk-bake3d-model/0`): `materials`(`#rrggbb`, `team: true`는 진영색 영역)와 `parts`(`box`·`cylinder`·`cone`·`sphere`, 위치·회전·재질). 1 단위 = 타일 한 변, 원점 = 발, 앞쪽 = +Z.

## 처리

- 방향(`s`부터 화면 시계 방향 8개)마다 **모델만** Y축으로 돌린다. 카메라와 광원은 고정이라 좌우 반전 없이 방향마다 따로 렌더한다.
- 패스 2개: 조명 색(`MeshLambertMaterial`, flat), 재질 id(빨강 채널 = 재질 번호, 0 = 배경). 안티앨리어싱 없음.
- alpha bbox로 자르고, 발 기준점은 칸 기준점 − bbox 원점이다. 다음은 실패로 멈추고 출력하지 않는다: 빈 렌더 `EMPTY`, 칸 가장자리에 닿음 `CLIPPED`, 두 패스 범위 불일치 `PASS_MISMATCH`, 기준점이 프레임 밖 `ANCHOR_OUTSIDE_FRAME`, 기존 출력 폴더 `OUTPUT_EXISTS`. 임시 폴더에서 모두 통과한 뒤에만 게시한다.
- 진영색 마스크: team 재질 픽셀만 조명 색의 밝기를 같은 회색으로, alpha 255.
- 팔레트·재질 램프·색 줄이기·바닥 그림자는 하지 않는다(manifest `postprocess`에 `NOT_RUN`). `ramp`가 null이 아니면 `RAMP_NOT_APPROVED`로 거부한다.

## 출력

- `frames/<방향>.{color,mask,matid}.png`: 잘린 RGBA PNG. 메타데이터 없음, 고정 deflate.
- `manifest.json`(`opensamguk-bake3d-manifest/0`): 상태, 도구·three·브라우저·GL 렌더러, 모델·설정 파일 SHA256, 카메라·광원, 방향별 yaw·bbox·프레임 크기·발 기준점·파일 SHA256. 시각·절대 경로는 넣지 않는다.
- `artpack-units-fragment.json`: `tools/assets/artpack`(PR #1639) 입력 spec의 `units` 부분 형식. A2 계약 방향(sw·nw·ne·se)만 담는다. 지형·그림자 선언이 없어 완성 spec이 아니며 `artpack check`는 아직 돌리지 않았다.

## 결정성

같은 설정·모델·도구·Chromium/SwiftShader 빌드에서 같은 바이트를 낸다. 렌더 시험이 새 브라우저로 두 번 구워 모든 파일을 바이트 비교하고, 픽셀 차이 수·최대 채널 차이도 기록한다. 다른 Chromium·러너 이미지 사이의 바이트 일치는 보장하지 않는다.
